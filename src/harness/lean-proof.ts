/**
 * Stateful Lean proof sessions — Phase 3b backend.
 *
 * The model declares a theorem, then applies tactics one at a time.
 * Each tactic sees the goal state evolve, and the model picks the
 * next move based on what's left. Mirrors how a human writes a proof
 * in tactic mode (induction, contradiction, case analysis, direct,
 * chained rewrites — the technique is up to the model; this layer
 * only manages the proof state).
 *
 * Backend: long-lived `leanprover-community/repl` subprocess (see
 * lean-repl.ts). Per-step latency is sub-second after the one-time
 * Mathlib import warm-up. Replaces the Phase 3a naive replay backend
 * which cost 5-15s per step.
 *
 * Design choices:
 *   - At most one active proof per agent run (multi-session would
 *     complicate the tool surface for marginal benefit).
 *   - On a tactic error, the proofState pointer stays on the previous
 *     coherent state — the bad tactic is not retained. The model
 *     sees the diagnostic and can try a different tactic.
 *   - The initial goal we surface comes directly from the REPL (it
 *     includes hypotheses + ⊢ goal — the most informative format).
 */

import {
  openProof,
  applyTactic,
  type ReplMessage,
} from "./lean-repl.js";

/**
 * One checkpoint in the proof: the REPL-side proofState pointer and
 * the goal-state text *at that point*. history[0] is the initial
 * state (post-openProof, before any tactic). history[i+1] is the
 * state after applying tactics[i]. Length invariant: history.length
 * === tactics.length + 1.
 */
export interface ProofCheckpoint {
  proofState: number;
  goals: string;
}

export interface ProofSession {
  /** Natural-language claim the proof is establishing. */
  claim: string;
  /** Lean type expression (the goal at session open). */
  theorem: string;
  /** Identifier used in the generated `theorem <name> : ...` snippet. */
  name: string;
  /** Tactics applied so far, in order. */
  tactics: string[];
  /** Checkpoint after each tactic + the initial. Used for undo. */
  history: ProofCheckpoint[];
  /** Status of the session. */
  status: "open" | "closed";
  /** Convenience: REPL state pointer at the latest checkpoint. */
  proofState: number;
  /** Convenience: goal text at the latest checkpoint. */
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
  errors: ReplMessage[];
  /** Tactic count after the call (failed tactics aren't counted). */
  tacticCount: number;
}

const PROOF_NAME_RE = /^[A-Za-z_][A-Za-z0-9_'.]*$/;

// Process-monotonic counter so every proof_start emits a unique
// `theorem <name> : ...` declaration. The REPL env carries declared
// names forward across calls; without uniquification, two sequential
// proof_start calls (or even two within different runs of the same
// long-lived agent process) would collide on "already declared".
// Phase 3c will introduce a proper theorem registry; for now the
// suffix is invisible to the model except as a debugging aid.
let proofCounter = 0;

export async function startSession(args: ProofStartArgs): Promise<ProofSession> {
  const baseName = args.name?.trim() || "_active_proof";
  if (!PROOF_NAME_RE.test(baseName)) {
    throw new Error(
      `proof name "${baseName}" must match ${PROOF_NAME_RE} (Lean identifier rules)`,
    );
  }
  const uniqueName = `${baseName}_${++proofCounter}`;
  const claim = args.claim.trim();
  const theorem = args.theorem.trim();
  // Open the proof against the REPL — this types-checks the theorem
  // statement and returns the initial goal/state. Errors here mean
  // the type itself was malformed.
  const opened = await openProof(uniqueName, theorem);
  const initial: ProofCheckpoint = {
    proofState: opened.proofState,
    goals: opened.goal,
  };
  return {
    claim,
    theorem,
    name: uniqueName,
    tactics: [],
    history: [initial],
    status: "open",
    proofState: initial.proofState,
    goals: initial.goals,
  };
}

/**
 * Test hook — reset the uniquification counter. Production code
 * should never need this; tests use it to keep names predictable
 * across vitest workers when asserting against `session.name`.
 */
export function _resetProofCounterForTests(): void {
  proofCounter = 0;
}

export interface StepOptions {
  /** Reserved for future use (per-call timeout etc.). */
  timeoutMs?: number;
}

export async function applyStep(
  session: ProofSession,
  tactic: string,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _opts: StepOptions = {},
): Promise<ProofStepResult> {
  const t = tactic.trim();
  if (!t) {
    return {
      status: "tactic_error",
      goals: session.goals,
      errors: [{ severity: "error", data: "tactic is empty" }],
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
          data: "proof already closed — open a new proof_start to begin another",
        },
      ],
      tacticCount: session.tactics.length,
    };
  }
  const r = await applyTactic(session.proofState, t);
  if (r.status === "closed") {
    const closedGoals = "(no goals — proof closed)";
    const newProofState = r.proofState ?? session.proofState;
    session.status = "closed";
    session.tactics.push(t);
    session.history.push({ proofState: newProofState, goals: closedGoals });
    session.proofState = newProofState;
    session.goals = closedGoals;
    return {
      status: "closed",
      goals: closedGoals,
      errors: [],
      tacticCount: session.tactics.length,
    };
  }
  if (r.status === "error") {
    return {
      status: "tactic_error",
      goals: session.goals, // unchanged — REPL state pointer not advanced
      errors: r.messages,
      tacticCount: session.tactics.length,
    };
  }
  // status === "open"
  const newProofState = r.proofState ?? session.proofState;
  session.tactics.push(t);
  session.history.push({ proofState: newProofState, goals: r.goals });
  session.proofState = newProofState;
  session.goals = r.goals;
  return {
    status: "open",
    goals: r.goals,
    errors: [],
    tacticCount: session.tactics.length,
  };
}

/**
 * Roll back the last `steps` tactics. The REPL retains earlier
 * proofStates by integer ID, so resuming from one is cheap — no
 * re-execution. Re-opens the session if it was closed by the rollback.
 */
export async function undoStep(
  session: ProofSession,
  steps: number = 1,
): Promise<{ status: "ok"; tacticCount: number; goals: string } | { status: "error"; error: string }> {
  if (!Number.isFinite(steps) || steps < 1) {
    return { status: "error", error: "steps must be a positive integer" };
  }
  if (steps > session.tactics.length) {
    return {
      status: "error",
      error: `cannot undo ${steps} step(s) — only ${session.tactics.length} tactic(s) applied`,
    };
  }
  // Trim parallel arrays.
  session.tactics.splice(session.tactics.length - steps, steps);
  session.history.splice(session.history.length - steps, steps);
  const last = session.history[session.history.length - 1];
  session.proofState = last.proofState;
  session.goals = last.goals;
  // We're back in an open state — even if the rollback un-closed a
  // proof, the rolled-back checkpoint was an open one.
  session.status = "open";
  return { status: "ok", tacticCount: session.tactics.length, goals: last.goals };
}

/**
 * Verify the session is closed. With the REPL backend this is
 * effectively a no-op since `applyStep` already updated `status`
 * the moment the proof closed; we keep this entrypoint as a clean
 * place for callers to ask "are we actually done?".
 */
export async function closeSession(
  session: ProofSession,
): Promise<
  | { status: "closed" }
  | { status: "open"; goals: string }
  | { status: "error"; error: string }
> {
  if (session.status === "closed") {
    return { status: "closed" };
  }
  if (session.tactics.length === 0) {
    return { status: "open", goals: session.goals };
  }
  return { status: "open", goals: session.goals };
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
    s.status === "closed"
      ? "STATUS: CLOSED ✓"
      : `current goals:\n  ${s.goals.split("\n").join("\n  ")}`,
  ].join("\n");
}
