/**
 * Lean 4 verification — third backend for the agent's `verify` flow.
 *
 * The model writes a Lean snippet (typically a `theorem` or `example`
 * with a tactic-block proof); the harness compiles it inside a
 * pre-built workspace that imports `Mathlib` and reports VERIFIED /
 * NOT VERIFIED with structured diagnostics.
 *
 * Implementation notes:
 *   - Async by design (`spawn`, not `spawnSync`). Multiple Lean
 *     checks can overlap — the OS file cache keeps mathlib's olean
 *     files warm so each subprocess starts quickly.
 *   - Workspace path is resolved relative to *this source file*, not
 *     `process.cwd()`. The harness can be started from any cwd
 *     without breaking. Override via `HARNESS_LEAN_WORKSPACE` env.
 *   - Default timeout is 120s (configurable per call or via env).
 *
 * Setup is one-time per machine:
 *   curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh
 *   cd tools/lean-workspace && lake update
 */

import { spawn } from "node:child_process";
import { writeFile, unlink } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";
import { lintLean } from "./lint.js";

// Workspace lives at <repo-root>/tools/lean-workspace. This file is
// at <repo-root>/src/harness/lean.ts (or dist/harness/lean.js when
// compiled). Two levels up from `dirname(this file)` is the repo
// root; tools/lean-workspace is from there.
const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_WORKSPACE_DIR = resolve(HERE, "..", "..", "tools", "lean-workspace");
const WORKSPACE_DIR =
  process.env.HARNESS_LEAN_WORKSPACE ?? DEFAULT_WORKSPACE_DIR;
const LAKE_BIN = process.env.LAKE_BIN ?? "lake";
const DEFAULT_TIMEOUT_MS = (() => {
  const env = process.env.HARNESS_LEAN_TIMEOUT_MS;
  if (env) {
    const n = Number(env);
    if (Number.isFinite(n) && n > 0) return n;
  }
  return 120_000;
})();

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
  /** Override the default 120s wall-clock timeout. */
  timeoutMs?: number;
  /** Override the workspace directory (mostly for tests). */
  workspaceDir?: string;
}

interface SpawnOutcome {
  code: number | null;
  signal: NodeJS.Signals | null;
  stdout: string;
  stderr: string;
  spawnError?: Error;
  timedOut?: boolean;
}

function spawnAsync(
  cmd: string,
  args: string[],
  cwd: string,
  timeoutMs: number,
): Promise<SpawnOutcome> {
  return new Promise((resolveOutcome) => {
    const child = spawn(cmd, args, { cwd, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, timeoutMs);
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk: string) => {
      stderr += chunk;
    });
    child.on("error", (err) => {
      clearTimeout(timer);
      resolveOutcome({
        code: null,
        signal: null,
        stdout,
        stderr,
        spawnError: err,
      });
    });
    child.on("close", (code, signal) => {
      clearTimeout(timer);
      resolveOutcome({ code, signal, stdout, stderr, timedOut });
    });
  });
}

export async function runLean(
  snippet: string,
  opts: LeanOptions = {},
): Promise<LeanResult> {
  // Pre-execution lint. Catches "comment-eats-declaration" style
  // bugs and empty / decl-less inputs before we pay the lake startup.
  const lint = lintLean(snippet);
  if (!lint.ok) {
    return {
      status: "error",
      error: `Lean lint rejected the snippet — execution skipped:\n  • ${lint.warnings.join("\n  • ")}`,
      diagnostics: [],
    };
  }
  const workspace = opts.workspaceDir ?? WORKSPACE_DIR;
  if (!existsSync(workspace)) {
    return {
      status: "error",
      error: `Lean workspace not found at ${workspace}. Run \`lake update\` in tools/lean-workspace first (see src/harness/lean.ts header).`,
      diagnostics: [],
    };
  }

  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const tmp = join(tmpdir(), `_harness_lean_${randomUUID()}.lean`);
  try {
    await writeFile(tmp, snippet, "utf8");
  } catch (e) {
    return {
      status: "error",
      error: `failed to write Lean snippet: ${e instanceof Error ? e.message : String(e)}`,
      diagnostics: [],
    };
  }

  try {
    const out = await spawnAsync(
      LAKE_BIN,
      ["env", "lean", "--json", tmp],
      workspace,
      timeoutMs,
    );
    if (out.spawnError) {
      return {
        status: "error",
        error: `lake invocation failed: ${out.spawnError.message}`,
        diagnostics: [],
      };
    }
    if (out.timedOut) {
      return {
        status: "error",
        error: `Lean check timed out after ${timeoutMs}ms (raise timeoutMs / HARNESS_LEAN_TIMEOUT_MS or simplify the proof)`,
        diagnostics: [],
      };
    }
    if (out.signal) {
      return {
        status: "error",
        error: `lake terminated by signal ${out.signal}`,
        diagnostics: [],
      };
    }
    // Lean writes JSON diagnostics to stdout in --json mode. lake's
    // own messages and any non-JSON Lean output go to stderr — keep
    // that as a fallback for unparsed errors only.
    const diagnostics: LeanDiagnostic[] = [];
    for (const line of out.stdout.split("\n")) {
      const d = parseDiagnosticLine(line);
      if (d) diagnostics.push(d);
    }
    const hasErrors = diagnostics.some((d) => d.severity === "error");
    if (out.code !== 0 || hasErrors) {
      const errMsg = hasErrors
        ? "Lean rejected the proof — see diagnostics."
        : `lake exited with status ${out.code}${out.stderr.trim() ? `\n${out.stderr.trim().slice(0, 1500)}` : ""}`;
      return { status: "error", error: errMsg, diagnostics };
    }
    return { status: "ok", diagnostics };
  } finally {
    try {
      await unlink(tmp);
    } catch {
      /* best-effort */
    }
  }
}
