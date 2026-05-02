/**
 * SWI-Prolog wrapper — relational reasoning engine for the agent.
 *
 * Backed by `prolog-wasm-full` (SWI-Prolog 10.1.4 in WebAssembly with
 * `library(clpfd)` included). Two surface shapes:
 *
 *   - `runPrologSolver({program, query})` — one-shot: load a complete
 *     program, run a single query, return all answers.
 *   - `createSession()` — persistent REPL: incrementally `assert`
 *     facts/rules, `query` against the accumulated state. Used by the
 *     agent to do hypothesis-then-verify reasoning ("I claim X, let me
 *     check it") across many turns without re-encoding the puzzle.
 *
 * NOTE: prolog-wasm-full's foreign-predicate API is sync-only, but
 * Z3's check() is async, so we don't bridge Z3 here. CLP(FD) covers
 * the finite-domain cases that motivated z3_solve. If we later need
 * theories CLP(FD) can't handle, the options are Atomics.wait + a
 * worker thread, or shelling out to a `z3` binary via execSync.
 */

import { initProlog, type PrologFull } from "prolog-wasm-full";
import { lintPrologProgram, lintPrologQuery } from "./lint.js";

const MAX_ANSWERS = 1000;

// Cap on Prolog inferences per query. SWI-WASM has no `library(time)`
// (so `call_with_time_limit/2` is unavailable), but
// `call_with_inference_limit/3` is built-in. Inference count is a
// reasonable wall-clock proxy: ~5-15 seconds at 50M inferences on
// the M3 we test on. The harness wraps every query in this so a
// runaway labeling (e.g., a CLP(FD) program with weak constraints
// asking to enumerate billions of assignments) can't hang the server.
const DEFAULT_INFERENCE_LIMIT = 50_000_000;

// Internal variable name used to detect inference-limit overrun. Picked
// to be unlikely to collide with anything the model writes.
const LIMIT_MARKER_VAR = "HarnessLimitResult_3F2A1B";
const LIMIT_EXCEEDED_ATOM = "inference_limit_exceeded";

export interface PrologAnswer {
  /** Variable name → bound term (Prolog-syntax string). */
  bindings: Record<string, string>;
  /** Pretty-printed substitution: `X = knight, Y = knave`. */
  formatted: string;
}

export type PrologResult =
  | { status: "success"; answers: PrologAnswer[] }
  | { status: "error"; error: string };

export interface PrologInput {
  program: string;
  query: string;
  signal?: AbortSignal;
}

// ---------------------------------------------------------------------
// Term rendering — reverse of prolog-wasm-full's stock marshaller
// ---------------------------------------------------------------------
//
// The stock query API returns these JS shapes:
//
//   atom              → string  ("knight")
//   integer / float   → number
//   true / false      → boolean
//   list              → array
//   compound foo(...) → { $t: "t", functor: "foo", foo: [[arg1, ...]] }
//
// Args are double-wrapped (`foo: [[a, b, c]]`) — the outer array has
// one element, the args tuple. We unwrap and render to Prolog syntax.

function isAtomBare(s: string): boolean {
  return /^[a-z][a-zA-Z0-9_]*$/.test(s);
}

function termToProlog(value: unknown): string {
  if (value === null || value === undefined) return "_";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") return String(value);
  if (typeof value === "string") {
    if (isAtomBare(value)) return value;
    return `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
  }
  if (Array.isArray(value)) {
    return `[${value.map(termToProlog).join(", ")}]`;
  }
  if (typeof value === "object") {
    const v = value as Record<string, unknown>;
    if (v.$t === "t" && typeof v.functor === "string") {
      const fn = v.functor;
      const argsWrap = v[fn];
      const args: unknown[] =
        Array.isArray(argsWrap) &&
        argsWrap.length === 1 &&
        Array.isArray(argsWrap[0])
          ? (argsWrap[0] as unknown[])
          : Array.isArray(argsWrap)
            ? (argsWrap as unknown[])
            : [argsWrap];
      if (fn === "-" && args.length === 2) {
        return `${termToProlog(args[0])}-${termToProlog(args[1])}`;
      }
      return `${fn}(${args.map(termToProlog).join(", ")})`;
    }
    return JSON.stringify(v);
  }
  return JSON.stringify(value);
}

function bindingsToFormatted(bindings: Record<string, string>): string {
  const entries = Object.entries(bindings);
  if (entries.length === 0) return "true";
  return entries.map(([k, v]) => `${k} = ${v}`).join(", ");
}

// ---------------------------------------------------------------------
// Global SWI-Prolog instance
// ---------------------------------------------------------------------
//
// prolog-wasm-full has a single-init lifecycle (Emscripten factory
// invalidates after first instantiation), so we lazily init exactly
// one global instance. The cached promise stays even on rejection —
// retrying init in the same process would fail anyway, so subsequent
// calls surface the same error fast instead of doomed re-attempts.

let plPromise: Promise<PrologFull> | null = null;
let pathCounter = 0;

async function getPl(): Promise<PrologFull> {
  plPromise ??= (async () => {
    const pl = await initProlog();
    pl.consult(`
      :- use_module(library(lists)).
      :- use_module(library(clpfd)).
    `);
    return pl;
  })();
  return plPromise;
}

function uniqueTempPath(prefix: string): string {
  return `/tmp/_harness_${prefix}_${Date.now()}_${pathCounter++}.pl`;
}

const SAFE_PATH_RE = /^[/A-Za-z0-9_.-]+$/;

/**
 * Strip the trailing `.` (or `?-` prefix) the model sometimes includes
 * around a goal — SWI's stock query API expects a bare goal expression.
 */
function normalizeQuery(q: string): string {
  let s = q.trim();
  if (s.startsWith("?-")) s = s.slice(2).trim();
  if (s.endsWith(".")) s = s.slice(0, -1).trim();
  return s;
}

class AbortError extends Error {}
class CapReachedError extends Error {}
class LimitExceededError extends Error {}

/**
 * Run a query against the current pl state. Caller is responsible for
 * having loaded any necessary predicates first.
 *
 * The user's goal is wrapped in `call_with_inference_limit/3` so a
 * pathological query can't hang the server. If the limit is hit,
 * we return an error rather than partial results — partial results
 * from a timed-out enumeration tend to mislead the agent (it sees
 * "X = a" and thinks the goal succeeded, missing that enumeration
 * was cut short).
 */
async function executeQuery(
  pl: PrologFull,
  goal: string,
  signal?: AbortSignal,
): Promise<PrologResult> {
  if (signal?.aborted) {
    return { status: "error", error: "aborted" };
  }
  const normalized = normalizeQuery(goal);
  if (!normalized) {
    return { status: "error", error: "empty query" };
  }

  // Wrap in inference limit — see DEFAULT_INFERENCE_LIMIT comment.
  const wrapped = `call_with_inference_limit((${normalized}), ${DEFAULT_INFERENCE_LIMIT}, ${LIMIT_MARKER_VAR})`;

  let handle: ReturnType<PrologFull["query"]>;
  try {
    handle = pl.query(wrapped);
  } catch (e) {
    return {
      status: "error",
      error: `query error: ${e instanceof Error ? e.message : String(e)}`,
    };
  }

  const answers: PrologAnswer[] = [];
  let limitExceeded = false;
  try {
    try {
      handle.forEach((rawBindings) => {
        if (signal?.aborted) throw new AbortError();
        if (answers.length >= MAX_ANSWERS) throw new CapReachedError();

        // Inspect the limit-marker; strip from user-facing bindings.
        const marker = rawBindings[LIMIT_MARKER_VAR];
        if (marker === LIMIT_EXCEEDED_ATOM) {
          limitExceeded = true;
          throw new LimitExceededError();
        }

        const bindings: Record<string, string> = {};
        for (const [k, v] of Object.entries(rawBindings)) {
          if (k === LIMIT_MARKER_VAR) continue;
          bindings[k] = termToProlog(v);
        }
        answers.push({ bindings, formatted: bindingsToFormatted(bindings) });
      });
    } finally {
      try {
        handle.close();
      } catch {
        /* best-effort */
      }
    }
  } catch (e) {
    if (e instanceof AbortError) {
      return { status: "error", error: "aborted" };
    }
    if (e instanceof LimitExceededError || limitExceeded) {
      return {
        status: "error",
        error: `query exceeded inference limit (${DEFAULT_INFERENCE_LIMIT.toLocaleString()}) — likely a runaway labeling or unbounded search; tighten constraints or narrow the goal`,
      };
    }
    if (!(e instanceof CapReachedError)) {
      return {
        status: "error",
        error: `answer error: ${e instanceof Error ? e.message : String(e)}`,
      };
    }
    // Cap reached — return what we have.
  }
  return { status: "success", answers };
}

/**
 * Remove every predicate this file defined, unload the source-file
 * record, and unlink the file. Workaround for a bug in this SWI-WASM
 * build where `unload_file/1` alone leaves stale clauses queryable —
 * we explicitly enumerate via `source_file/2` and `abolish/1` each.
 */
function cleanupTempFile(pl: PrologFull, path: string): void {
  try {
    const preds = pl
      .query(`source_file(P, '${path}'), functor(P, F, A)`)
      .all();
    for (const row of preds) {
      const f = row.F;
      const a = row.A;
      if (typeof f !== "string" || typeof a !== "number") continue;
      if (!isAtomBare(f)) continue;
      try {
        pl.stock.call(`abolish(${f}/${a})`);
      } catch {
        /* best-effort */
      }
    }
    pl.stock.call(`unload_file('${path}')`);
  } catch {
    /* best-effort */
  }
  try {
    pl.em.FS.unlink(path);
  } catch {
    /* file may already be gone */
  }
}

// ---------------------------------------------------------------------
// One-shot solver
// ---------------------------------------------------------------------

export async function runPrologSolver(
  input: PrologInput,
): Promise<PrologResult> {
  let pl: PrologFull;
  try {
    pl = await getPl();
  } catch (e) {
    return {
      status: "error",
      error: `prolog init failed: ${e instanceof Error ? e.message : String(e)}`,
    };
  }

  const path = uniqueTempPath("call");
  if (!SAFE_PATH_RE.test(path)) {
    return {
      status: "error",
      error: `internal: tempfile path failed safety check: ${path}`,
    };
  }
  let consulted = false;

  try {
    if (input.signal?.aborted) {
      return { status: "error", error: "aborted" };
    }
    // Pre-execution lints — refuse empty / comment-only / dot-less
    // programs and empty queries before consulting / running.
    if (input.program.trim()) {
      const programLint = lintPrologProgram(input.program);
      if (!programLint.ok) {
        return {
          status: "error",
          error: `Prolog program lint rejected the input — execution skipped:\n  • ${programLint.warnings.join("\n  • ")}`,
        };
      }
    }
    const queryLint = lintPrologQuery(input.query);
    if (!queryLint.ok) {
      return {
        status: "error",
        error: `Prolog query lint rejected the input — execution skipped:\n  • ${queryLint.warnings.join("\n  • ")}`,
      };
    }
    if (input.program.trim()) {
      try {
        pl.em.FS.writeFile(path, input.program);
        pl.stock.call(`consult('${path}')`);
        consulted = true;
      } catch (e) {
        return {
          status: "error",
          error: `program error: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }
    return await executeQuery(pl, input.query, input.signal);
  } finally {
    if (consulted) cleanupTempFile(pl, path);
    else {
      try {
        pl.em.FS.unlink(path);
      } catch {
        /* never written */
      }
    }
  }
}

// ---------------------------------------------------------------------
// Persistent session — for the agent's incremental TDD-style flow
// ---------------------------------------------------------------------
//
// The agent maintains one session per run. Each `assert(code)` adds
// rules to a fresh tempfile and consults it; we track the file so
// `dispose()` can clean up *only* this session's predicates without
// touching the shared `library(lists)` / `library(clpfd)` state.
//
// Concurrency: sessions share the global `pl` instance, so two
// concurrent agent runs would see each other's predicates. Today the
// model server processes one agent at a time, so single-tenant
// suffices. If we later parallelise, switch to per-session SWI
// modules (`:- module(session_<id>, [])`).

type OkOrError = { status: "ok" } | { status: "error"; error: string };

export interface PrologSession {
  /**
   * Append anonymous, permanent rules/facts to the session. Cannot
   * be retracted by name (cleaned only at session dispose).
   */
  assert(code: string): Promise<OkOrError>;
  /**
   * Add a *named* rule/fact. Tentative by default — can be retracted
   * via `retract(name)`, or promoted to permanent via `commit(name)`.
   * Names must be unique within the session. Used by the agent for
   * both retractable rules (add_rule with a name) and hypothetical
   * frames (assume): same mechanism, different intent.
   */
  addNamed(name: string, code: string): Promise<OkOrError>;
  /**
   * Remove a named scope. Errors if the name doesn't exist or has
   * already been committed.
   */
  retract(name: string): Promise<OkOrError>;
  /**
   * Promote a named tentative rule to permanent — afterwards it can't
   * be retracted. The clauses themselves are unchanged; this just
   * locks the name from removal.
   */
  commit(name: string): Promise<OkOrError>;
  /** Run a query against the accumulated state. */
  query(goal: string, signal?: AbortSignal): Promise<PrologResult>;
  /** Tear down: abolish every predicate this session defined. */
  dispose(): Promise<void>;
}

export async function createSession(): Promise<PrologSession> {
  let pl: PrologFull;
  try {
    pl = await getPl();
  } catch (e) {
    const err = e instanceof Error ? e.message : String(e);
    const initErr = `prolog init failed: ${err}`;
    return {
      async assert() {
        return { status: "error", error: initErr };
      },
      async addNamed() {
        return { status: "error", error: initErr };
      },
      async retract() {
        return { status: "error", error: initErr };
      },
      async commit() {
        return { status: "error", error: initErr };
      },
      async query() {
        return { status: "error", error: initErr };
      },
      async dispose() {
        /* nothing to clean */
      },
    };
  }

  // Anonymous (permanent) tempfiles — cleaned at dispose.
  const anonymous: string[] = [];
  // Named scopes — `path` for cleanup; `committed` blocks retraction.
  const named = new Map<string, { path: string; committed: boolean }>();

  function consultCode(code: string): { path: string } | { error: string } {
    const path = uniqueTempPath("session");
    if (!SAFE_PATH_RE.test(path)) {
      return { error: `internal: tempfile path failed safety check: ${path}` };
    }
    try {
      pl.em.FS.writeFile(path, code);
      pl.stock.call(`consult('${path}')`);
      return { path };
    } catch (e) {
      try {
        pl.em.FS.unlink(path);
      } catch {
        /* best-effort */
      }
      return { error: e instanceof Error ? e.message : String(e) };
    }
  }

  return {
    async assert(code: string) {
      const trimmed = code.trim();
      if (!trimmed) {
        return { status: "error", error: "assert requires non-empty code" };
      }
      const lint = lintPrologProgram(trimmed);
      if (!lint.ok) {
        return {
          status: "error",
          error: `Prolog lint rejected the rule:\n  • ${lint.warnings.join("\n  • ")}`,
        };
      }
      const r = consultCode(trimmed);
      if ("error" in r) return { status: "error", error: r.error };
      anonymous.push(r.path);
      return { status: "ok" };
    },

    async addNamed(name: string, code: string) {
      const n = name.trim();
      const c = code.trim();
      if (!n) {
        return { status: "error", error: "addNamed requires a non-empty name" };
      }
      if (!c) {
        return { status: "error", error: "addNamed requires non-empty code" };
      }
      const lint = lintPrologProgram(c);
      if (!lint.ok) {
        return {
          status: "error",
          error: `Prolog lint rejected the rule:\n  • ${lint.warnings.join("\n  • ")}`,
        };
      }
      if (named.has(n)) {
        return {
          status: "error",
          error: `addNamed: name "${n}" is already in use — retract it first or pick a different name`,
        };
      }
      const r = consultCode(c);
      if ("error" in r) return { status: "error", error: r.error };
      named.set(n, { path: r.path, committed: false });
      return { status: "ok" };
    },

    async retract(name: string) {
      const n = name.trim();
      if (!n) {
        return { status: "error", error: "retract requires a non-empty name" };
      }
      const entry = named.get(n);
      if (!entry) {
        return {
          status: "error",
          error: `retract: no named scope "${n}"`,
        };
      }
      if (entry.committed) {
        return {
          status: "error",
          error: `retract: scope "${n}" has been committed and can no longer be retracted`,
        };
      }
      cleanupTempFile(pl, entry.path);
      named.delete(n);
      return { status: "ok" };
    },

    async commit(name: string) {
      const n = name.trim();
      if (!n) {
        return { status: "error", error: "commit requires a non-empty name" };
      }
      const entry = named.get(n);
      if (!entry) {
        return {
          status: "error",
          error: `commit: no named scope "${n}"`,
        };
      }
      if (entry.committed) {
        return {
          status: "error",
          error: `commit: scope "${n}" is already committed`,
        };
      }
      entry.committed = true;
      return { status: "ok" };
    },

    async query(goal: string, signal?: AbortSignal) {
      const lint = lintPrologQuery(goal);
      if (!lint.ok) {
        return {
          status: "error",
          error: `Prolog query lint rejected the input:\n  • ${lint.warnings.join("\n  • ")}`,
        } as PrologResult;
      }
      return executeQuery(pl, goal, signal);
    },

    async dispose() {
      for (const path of anonymous) cleanupTempFile(pl, path);
      anonymous.length = 0;
      for (const { path } of named.values()) cleanupTempFile(pl, path);
      named.clear();
    },
  };
}
