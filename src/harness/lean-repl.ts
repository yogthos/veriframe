/**
 * Long-lived `leanprover-community/repl` subprocess — Phase 3b
 * backend for stateful Lean proof sessions.
 *
 * The previous backend (Phase 3a) replayed the full tactic list per
 * step via `lake env lean`, costing 5-15s per step. This wrapper
 * keeps a single REPL process alive across all proof steps in a
 * harness session: Mathlib loads once (~5s on cold cache, faster
 * warm), then each tactic costs sub-second.
 *
 * Protocol (per leanprover-community/repl README):
 *   - Communicate via JSON on stdin/stdout, blank-line-separated.
 *   - Command mode: `{"cmd": "...", "env"?: N}` returns `{env, sorries?, messages?}`.
 *   - Tactic mode: `{"tactic": "...", "proofState": M}` returns
 *     `{proofState, goals, proofStatus?, messages?}`.
 *
 * To open a proof:
 *   1. Send `{"cmd": "import Mathlib"}` once → record `env`.
 *   2. Send `{"cmd": "theorem T : <type> := by sorry", "env": <mathlibEnv>}`
 *      → record the returned `sorries[0].proofState` as the initial state.
 *   3. Apply tactics: `{"tactic": "...", "proofState": current}` →
 *      new proofState + goals.
 *   4. When `goals === []`, proof is closed.
 *
 * Singleton lifecycle: one REPL process per Node process. If it dies
 * we transparently respawn on the next call.
 */

import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, "..", "..");
const WORKSPACE_DIR =
  process.env.HARNESS_LEAN_WORKSPACE ??
  resolve(REPO_ROOT, "tools", "lean-workspace");
const REPL_BIN =
  process.env.HARNESS_LEAN_REPL_BIN ??
  resolve(REPO_ROOT, "tools", "lean-repl", ".lake", "build", "bin", "repl");
const LAKE_BIN = process.env.LAKE_BIN ?? "lake";

// Time we'll wait for any one REPL response (Mathlib import is the
// slow case — give it a generous ceiling). Each tactic step
// post-import is sub-second.
const REPL_TIMEOUT_MS = 5 * 60_000;

// ---------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------

export interface ReplMessage {
  severity?: "error" | "warning" | "info" | "information";
  data?: string;
  pos?: { line?: number; column?: number };
  endPos?: { line?: number; column?: number };
}

export interface ReplCommandResponse {
  env?: number;
  sorries?: Array<{
    proofState: number;
    goal: string;
    pos?: { line?: number; column?: number };
    endPos?: { line?: number; column?: number };
  }>;
  messages?: ReplMessage[];
}

export interface ReplTacticResponse {
  proofState?: number;
  goals?: string[];
  proofStatus?: string;
  messages?: ReplMessage[];
}

// ---------------------------------------------------------------------
// Singleton REPL
// ---------------------------------------------------------------------

let proc: ChildProcessWithoutNullStreams | null = null;
let mathlibEnv: number | null = null;
let stdoutBuffer = "";
const queue: Array<{
  resolve: (data: unknown) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout;
}> = [];

function findJsonEnd(s: string): number {
  // Locate the end of the first complete JSON object in `s`. Returns
  // -1 if more data is needed. Tolerates strings with escaped quotes.
  if (!s || s[0] !== "{") return -1;
  let depth = 0;
  let inStr = false;
  let escape = false;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (escape) {
      escape = false;
      continue;
    }
    if (inStr) {
      if (c === "\\") {
        escape = true;
        continue;
      }
      if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') {
      inStr = true;
      continue;
    }
    if (c === "{") depth++;
    if (c === "}") {
      depth--;
      if (depth === 0) return i + 1;
    }
  }
  return -1;
}

function onStdout(chunk: Buffer): void {
  stdoutBuffer += chunk.toString("utf8");
  while (true) {
    const trimmed = stdoutBuffer.replace(/^[\s]+/, "");
    if (!trimmed) {
      stdoutBuffer = "";
      break;
    }
    if (trimmed[0] !== "{") {
      // Drop any non-JSON noise (defensive — REPL is supposed to emit
      // only JSON objects).
      const next = trimmed.indexOf("{");
      if (next < 0) {
        stdoutBuffer = "";
        break;
      }
      stdoutBuffer = trimmed.slice(next);
      continue;
    }
    const end = findJsonEnd(trimmed);
    if (end < 0) {
      stdoutBuffer = trimmed; // wait for more
      return;
    }
    const blob = trimmed.slice(0, end);
    stdoutBuffer = trimmed.slice(end);
    let data: unknown;
    try {
      data = JSON.parse(blob);
    } catch (e) {
      const next = queue.shift();
      if (next) {
        clearTimeout(next.timer);
        next.reject(
          new Error(
            `failed to parse REPL response: ${e instanceof Error ? e.message : String(e)}`,
          ),
        );
      }
      continue;
    }
    const next = queue.shift();
    if (next) {
      clearTimeout(next.timer);
      next.resolve(data);
    }
  }
}

function failAllPending(err: Error): void {
  while (queue.length > 0) {
    const item = queue.shift()!;
    clearTimeout(item.timer);
    item.reject(err);
  }
}

async function ensureStarted(): Promise<void> {
  if (proc && !proc.killed && mathlibEnv !== null) return;
  if (!existsSync(REPL_BIN)) {
    throw new Error(
      `Lean REPL binary not found at ${REPL_BIN}. Build it: cd tools/lean-repl && lake build`,
    );
  }
  if (!existsSync(WORKSPACE_DIR)) {
    throw new Error(
      `Lean workspace not found at ${WORKSPACE_DIR}. Run \`lake update\` in tools/lean-workspace.`,
    );
  }
  // Reset state if a previous process died.
  proc = null;
  mathlibEnv = null;
  stdoutBuffer = "";
  failAllPending(new Error("REPL process restarting"));

  const child = spawn(LAKE_BIN, ["env", REPL_BIN], {
    cwd: WORKSPACE_DIR,
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", onStdout);
  // Surface stderr lines but don't fail the call — they're often
  // build warnings, not errors.
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", () => {
    /* swallow; available for debug if needed */
  });
  child.on("error", (err) => {
    failAllPending(new Error(`REPL spawn error: ${err.message}`));
    proc = null;
    mathlibEnv = null;
  });
  child.on("exit", (code) => {
    failAllPending(new Error(`REPL exited unexpectedly (code ${code})`));
    proc = null;
    mathlibEnv = null;
  });
  proc = child;

  // Load Mathlib. First call is slow (~5-30s on cold cache); subsequent
  // commands using this env are fast.
  const r = (await sendRaw({ cmd: "import Mathlib" })) as ReplCommandResponse;
  if (r.messages?.some((m) => m.severity === "error")) {
    throw new Error(
      `REPL failed to import Mathlib: ${r.messages
        .filter((m) => m.severity === "error")
        .map((m) => m.data)
        .join(" / ")}`,
    );
  }
  mathlibEnv = r.env ?? 0;
}

function sendRaw(command: unknown): Promise<unknown> {
  return new Promise((resolveOuter, rejectOuter) => {
    if (!proc || proc.killed) {
      rejectOuter(new Error("REPL not running"));
      return;
    }
    const timer = setTimeout(() => {
      // Find this entry in queue and remove it (best effort: assume
      // FIFO and pop oldest).
      const idx = queue.findIndex((q) => q.resolve === resolveOuter);
      if (idx >= 0) queue.splice(idx, 1);
      rejectOuter(new Error(`REPL response timeout (${REPL_TIMEOUT_MS}ms)`));
    }, REPL_TIMEOUT_MS);
    queue.push({
      resolve: resolveOuter as (data: unknown) => void,
      reject: rejectOuter,
      timer,
    });
    const text = JSON.stringify(command) + "\n\n";
    proc.stdin.write(text);
  });
}

// ---------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------

export interface OpenedProof {
  /** The proofState to feed into the first tactic. */
  proofState: number;
  /** Initial goal (with hypothesis context if any). */
  goal: string;
}

/**
 * Declare a `theorem` with `sorry` to enter tactic mode and capture
 * the initial proof state.
 */
export async function openProof(
  name: string,
  theorem: string,
): Promise<OpenedProof> {
  await ensureStarted();
  const cmd = `theorem ${name} : ${theorem} := by sorry`;
  const r = (await sendRaw({
    cmd,
    env: mathlibEnv,
  })) as ReplCommandResponse;
  // Parse errors (other than the expected "uses sorry" warning) mean
  // the type didn't typecheck — surface them.
  const errs =
    r.messages?.filter((m) => m.severity === "error") ?? [];
  if (errs.length > 0) {
    throw new Error(
      `Lean rejected the theorem statement: ${errs.map((e) => e.data).join(" / ")}`,
    );
  }
  if (!r.sorries || r.sorries.length === 0) {
    throw new Error(
      "REPL returned no sorries — theorem may already be trivially proved or the response shape changed.",
    );
  }
  const s = r.sorries[0];
  return { proofState: s.proofState, goal: s.goal };
}

export interface TacticOutcome {
  /** "closed" means proof complete; "open" means goals remain;
   *  "error" means tactic was rejected (state unchanged in REPL). */
  status: "closed" | "open" | "error";
  /** New proof state to feed into the next tactic (when status="open"). */
  proofState?: number;
  /** Pretty-printed goals (multiple separated by blank lines). */
  goals: string;
  /** Lean error/warning messages if any. */
  messages: ReplMessage[];
}

/**
 * Apply a tactic to a proof state. Returns the new state's goals or
 * an error if Lean rejected the tactic.
 *
 * Note: when the tactic is rejected, the REPL still allocates a new
 * proofState (often equal to the old goals); we report status="error"
 * and don't advance the caller's tracked proofState. Callers should
 * stay on the previous proofState.
 */
export async function applyTactic(
  proofState: number,
  tactic: string,
): Promise<TacticOutcome> {
  await ensureStarted();
  const r = (await sendRaw({ tactic, proofState })) as ReplTacticResponse;
  const messages = r.messages ?? [];
  const errs = messages.filter((m) => m.severity === "error");
  // Errors win over closed/open — the tactic was rejected even if the
  // REPL's goal list happens to be empty. (Empty goals + error means
  // the tactic produced no new state, not that the proof closed.)
  if (errs.length > 0) {
    return {
      status: "error",
      goals: "", // caller keeps previous goal text
      messages,
    };
  }
  const closed =
    r.proofStatus === "Completed" ||
    (Array.isArray(r.goals) && r.goals.length === 0);
  if (closed) {
    return {
      status: "closed",
      proofState: r.proofState,
      goals: "(no goals — proof closed)",
      messages,
    };
  }
  const goalsStr = (r.goals ?? []).join("\n\n");
  return {
    status: "open",
    proofState: r.proofState,
    goals: goalsStr,
    messages,
  };
}

/**
 * Force-stop the REPL (mostly useful in tests). Next call will
 * respawn.
 */
export function stopRepl(): void {
  if (proc && !proc.killed) {
    proc.kill("SIGTERM");
  }
  proc = null;
  mathlibEnv = null;
  stdoutBuffer = "";
}

/** Test hook — true if the singleton process is alive. */
export function isReplRunning(): boolean {
  return proc !== null && !proc.killed;
}
