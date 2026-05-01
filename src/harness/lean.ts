/**
 * Lean 4 verification — third backend for the agent's `verify` flow.
 *
 * The model writes a Lean snippet (typically a `theorem` or `example`
 * with a tactic-block proof); the harness executes it inside a
 * pre-built workspace that imports `Mathlib`. We use
 * `lake env lean --json <file>` so Mathlib's precompiled .olean files
 * are visible to the process — first-call overhead is ~1-3s for
 * Lean process startup; subsequent calls are similar (the OS keeps
 * mathlib's olean files in the file cache).
 *
 * Why not `lean --server` (LSP)? It would shave the per-call startup
 * overhead, but the MVP-friendliness of execSync + JSON-line parsing
 * is hard to beat. We can swap to a long-lived server later.
 *
 * Workspace layout (built once, reused forever):
 *   tools/lean-workspace/
 *     lakefile.toml          — requires Mathlib v4.x
 *     lean-toolchain         — pins Lean version Mathlib expects
 *     LeanWorkspace/Basic.lean
 *     .lake/                 — fetched via `lake update` + cache get
 *
 * Setup is one-time:
 *   curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh
 *   cd tools/lean-workspace && lake update
 */

import { spawnSync } from "node:child_process";
import { writeFileSync, unlinkSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const WORKSPACE_DIR = join(
  process.cwd(),
  "tools",
  "lean-workspace",
);
const LAKE_BIN = process.env.LAKE_BIN ?? "lake";
const LEAN_TIMEOUT_MS = 60_000;

/**
 * One Lean diagnostic. Lean emits one JSON object per line on
 * stdout when given `--json`. We surface the fields agents/tests
 * care about.
 */
export interface LeanDiagnostic {
  severity: "error" | "warning" | "information";
  message: string;
  line: number;
  column: number;
  /** Lean's category (e.g. "Tactic.unsolvedGoals"); useful for
   *  programmatic handling. */
  kind?: string;
}

export type LeanResult =
  | { status: "ok"; diagnostics: LeanDiagnostic[] }
  | { status: "error"; error: string; diagnostics: LeanDiagnostic[] };

interface RawLeanDiagnostic {
  severity?: string;
  data?: unknown;
  caption?: string;
  pos?: { line?: number; column?: number };
  kind?: string;
}

function parseDiagnosticLine(line: string): LeanDiagnostic | null {
  const trimmed = line.trim();
  if (!trimmed) return null;
  let raw: RawLeanDiagnostic;
  try {
    raw = JSON.parse(trimmed) as RawLeanDiagnostic;
  } catch {
    return null;
  }
  const severity =
    raw.severity === "error"
      ? "error"
      : raw.severity === "warning"
        ? "warning"
        : raw.severity === "information"
          ? "information"
          : null;
  if (!severity) return null;
  const message =
    typeof raw.data === "string"
      ? raw.data
      : typeof raw.caption === "string" && raw.caption
        ? raw.caption
        : JSON.stringify(raw.data ?? "");
  return {
    severity,
    message,
    line: raw.pos?.line ?? 0,
    column: raw.pos?.column ?? 0,
    kind: raw.kind,
  };
}

export interface LeanOptions {
  /** Override the default 60s wall-clock timeout. */
  timeoutMs?: number;
  /** Override the workspace directory (mostly for tests). */
  workspaceDir?: string;
}

export function runLean(snippet: string, opts: LeanOptions = {}): LeanResult {
  const workspace = opts.workspaceDir ?? WORKSPACE_DIR;
  if (!existsSync(workspace)) {
    return {
      status: "error",
      error: `Lean workspace not found at ${workspace}. Run \`lake update\` in tools/lean-workspace first (see src/harness/lean.ts header).`,
      diagnostics: [],
    };
  }

  const tmp = join(tmpdir(), `_harness_lean_${Date.now()}_${Math.floor(Math.random() * 1e9)}.lean`);
  writeFileSync(tmp, snippet, "utf8");

  try {
    const result = spawnSync(LAKE_BIN, ["env", "lean", "--json", tmp], {
      cwd: workspace,
      timeout: opts.timeoutMs ?? LEAN_TIMEOUT_MS,
      maxBuffer: 4 * 1024 * 1024,
      encoding: "utf8",
    });
    if (result.error) {
      return {
        status: "error",
        error: `lake invocation failed: ${result.error.message}`,
        diagnostics: [],
      };
    }
    if (result.signal) {
      return {
        status: "error",
        error: `lake terminated by signal ${result.signal} (likely timeout — raise timeoutMs or simplify the proof)`,
        diagnostics: [],
      };
    }
    const stdoutLines = (result.stdout ?? "").split("\n");
    const stderrLines = (result.stderr ?? "").split("\n");
    const diagnostics: LeanDiagnostic[] = [];
    for (const line of [...stdoutLines, ...stderrLines]) {
      const d = parseDiagnosticLine(line);
      if (d) diagnostics.push(d);
    }
    const hasErrors = diagnostics.some((d) => d.severity === "error");
    if (result.status !== 0 || hasErrors) {
      // Snapshot stderr lines that didn't parse (e.g. pre-flight
      // fatal errors from lake itself) so the model sees them.
      const unparsedStderr = stderrLines
        .filter((l) => l.trim() && parseDiagnosticLine(l) === null)
        .join("\n")
        .trim();
      const errMsg = hasErrors
        ? "Lean rejected the proof — see diagnostics."
        : `lake exited with status ${result.status}${unparsedStderr ? `\n${unparsedStderr.slice(0, 1000)}` : ""}`;
      return { status: "error", error: errMsg, diagnostics };
    }
    return { status: "ok", diagnostics };
  } finally {
    try {
      unlinkSync(tmp);
    } catch {
      /* best-effort */
    }
  }
}
