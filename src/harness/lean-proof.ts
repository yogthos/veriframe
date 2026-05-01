/**
 * Stateful Lean proof sessions — Phase 3a (naive replay backend).
 *
 * Implements the stepwise proof flow: the model declares a theorem,
 * then applies tactics one at a time, seeing the goal state after
 * each. Mirrors how a human writes a proof in tactic mode (induction,
 * contradiction, case analysis, direct — the technique is up to the
 * model; this layer only manages the proof state).
 *
 * MVP backend: each `proof_step` re-runs the *whole* accumulated
 * tactic list via `lake env lean --json`. Per-step cost is ~5-10s
 * (mathlib startup) — Phase 3b will swap to a long-lived
 * `leanprover-community/repl` subprocess for sub-second steps.
 *
 * Design choices:
 *   - At most one active proof per agent run. Multi-session would
 *     complicate the tool surface for marginal benefit; if we need
 *     to interleave lemmas later, that's Phase 3c (theorem registry).
 *   - On a tactic error, the failed tactic is *automatically popped*
 *     so the session stays in a coherent state. The model sees the
 *     diagnostic and can try a different tactic.
 *   - The initial goal we surface to the model is the theorem
 *     statement itself (no Lean call needed); the first `proof_step`
 *     is what triggers actual checking.
 */

import { runLean, type LeanDiagnostic } from "./lean.js";

export interface ProofSession {
  /** Natural-language claim the proof is establishing. */
  claim: string;
  /** Lean type expression (the goal at session open). */
  theorem: string;
  /** Identifier used in the generated `theorem <name> : ...` snippet. */
  name: string;
  /** Tactics applied so far, in order. Replayed on each step. */
  tactics: string[];
  /** Most recent state from Lean. */
  status: "open" | "closed";
  /** Pretty-printed goals when `status === "open"`. */
  goals: string;
}

export interface ProofStartArgs {
  claim: string;
  theorem: string;
  name?: string;
}

export interface ProofStepResult {
  status: "open" | "closed" | "tactic_error";
  /** New goal state (when "open") or last-known goals (after error). */
  goals: string;
  /** Diagnostics surfaced by Lean for this attempt. */
  errors: LeanDiagnostic[];
  /** Tactic count after the call (failed tactics are popped). */
  tacticCount: number;
}

const PROOF_NAME_RE = /^[A-Za-z_][A-Za-z0-9_'.]*$/;

export function startSession(args: ProofStartArgs): ProofSession {
  const name = args.name?.trim() || "_active_proof";
  if (!PROOF_NAME_RE.test(name)) {
    throw new Error(
      `proof name "${name}" must match ${PROOF_NAME_RE} (Lean identifier rules)`,
    );
  }
  return {
    claim: args.claim.trim(),
    theorem: args.theorem.trim(),
    name,
    tactics: [],
    status: "open",
    goals: `⊢ ${args.theorem.trim()}`,
  };
}

/**
 * Build the Lean snippet for the current session state. With zero
 * tactics we add `skip` so the snippet compiles to a state where Lean
 * reports "unsolved goals" (i.e., the initial goal).
 */
function buildSnippet(s: ProofSession): string {
  const body =
    s.tactics.length === 0
      ? "  skip"
      : s.tactics.map((t) => "  " + t).join("\n");
  return `import Mathlib\n\ntheorem ${s.name} : ${s.theorem} := by\n${body}\n`;
}

/**
 * Extract the readable goals payload from Lean diagnostics. Returns
 * the empty string if no unsolved-goals diagnostic was found (i.e.,
 * the proof closed cleanly).
 */
function readGoals(diagnostics: LeanDiagnostic[]): string {
  for (const d of diagnostics) {
    if (d.severity !== "error") continue;
    if (
      d.kind === "Tactic.unsolvedGoals" ||
      /^unsolved goals/i.test(d.message)
    ) {
      // Strip the leading "unsolved goals\n" if present.
      return d.message.replace(/^unsolved goals\s*\n?/i, "").trim();
    }
  }
  return "";
}

/**
 * Detect whether the diagnostics indicate a *tactic* error (vs a
 * mere unsolved-goals state). Tactic errors mean the latest tactic
 * was malformed or didn't apply — we pop it and report the error.
 */
function hasTacticError(diagnostics: LeanDiagnostic[]): boolean {
  return diagnostics.some(
    (d) =>
      d.severity === "error" &&
      d.kind !== "Tactic.unsolvedGoals" &&
      !/^unsolved goals/i.test(d.message),
  );
}

export interface StepOptions {
  /** Override the Lean call timeout. Defaults to runLean's default. */
  timeoutMs?: number;
}

export async function applyStep(
  session: ProofSession,
  tactic: string,
  opts: StepOptions = {},
): Promise<ProofStepResult> {
  const t = tactic.trim();
  if (!t) {
    return {
      status: "tactic_error",
      goals: session.goals,
      errors: [
        {
          severity: "error",
          message: "tactic is empty",
          line: 0,
          column: 0,
        },
      ],
      tacticCount: session.tactics.length,
    };
  }
  if (session.status === "closed") {
    return {
      status: "tactic_error",
      goals: session.goals,
      errors: [
        {
          severity: "error",
          message:
            "proof already closed — open a new proof_start to begin another",
          line: 0,
          column: 0,
        },
      ],
      tacticCount: session.tactics.length,
    };
  }
  // Tentatively append, run, decide what to keep.
  session.tactics.push(t);
  const snippet = buildSnippet(session);
  const r = await runLean(snippet, { timeoutMs: opts.timeoutMs });

  if (r.status === "ok") {
    // Lean accepted everything — proof is closed.
    session.status = "closed";
    session.goals = "(no goals — proof closed)";
    return {
      status: "closed",
      goals: session.goals,
      errors: [],
      tacticCount: session.tactics.length,
    };
  }
  // r.status === "error"
  const newGoals = readGoals(r.diagnostics);
  const tacticErr = hasTacticError(r.diagnostics);
  if (tacticErr) {
    // The latest tactic broke the proof — pop it and surface error.
    session.tactics.pop();
    const errs = r.diagnostics.filter((d) => d.severity === "error");
    return {
      status: "tactic_error",
      goals: session.goals, // unchanged (we rolled back)
      errors: errs,
      tacticCount: session.tactics.length,
    };
  }
  if (newGoals) {
    session.goals = newGoals;
    return {
      status: "open",
      goals: newGoals,
      errors: [],
      tacticCount: session.tactics.length,
    };
  }
  // No tactic error but no unsolved-goals either — unusual; treat as
  // ambiguous and surface the raw error.
  const errs = r.diagnostics.filter((d) => d.severity === "error");
  return {
    status: "tactic_error",
    goals: session.goals,
    errors: errs,
    tacticCount: session.tactics.length,
  };
}

/**
 * Verify the session is closed: re-run the accumulated tactics and
 * confirm Lean accepts. Returns OK or surfaces the remaining goals.
 */
export async function closeSession(
  session: ProofSession,
  opts: StepOptions = {},
): Promise<{ status: "closed"; snippet: string } | { status: "open"; goals: string } | { status: "error"; error: string }> {
  if (session.tactics.length === 0) {
    return { status: "open", goals: session.goals };
  }
  const snippet = buildSnippet(session);
  const r = await runLean(snippet, { timeoutMs: opts.timeoutMs });
  if (r.status === "ok") {
    session.status = "closed";
    session.goals = "(no goals — proof closed)";
    return { status: "closed", snippet };
  }
  const goals = readGoals(r.diagnostics);
  if (goals) {
    return { status: "open", goals };
  }
  return {
    status: "error",
    error: r.error || "Lean rejected the closing snippet",
  };
}

/** Render the session for the model. */
export function renderSession(s: ProofSession): string {
  const head = `proof: "${s.claim}"`;
  const stmt = `theorem ${s.name} : ${s.theorem}`;
  const tact =
    s.tactics.length === 0
      ? "  (no tactics yet)"
      : s.tactics.map((t, i) => `  [${i + 1}] ${t}`).join("\n");
  return [
    head,
    stmt,
    "tactics so far:",
    tact,
    s.status === "closed" ? "STATUS: CLOSED ✓" : `current goals:\n  ${s.goals.split("\n").join("\n  ")}`,
  ].join("\n");
}
