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

export interface ProofSession {
  /** Natural-language claim the proof is establishing. */
  claim: string;
  /** Lean type expression (the goal at session open). */
  theorem: string;
  /** Identifier used in the generated `theorem <name> : ...` snippet. */
  name: string;
  /** Tactics applied so far, in order. */
  tactics: string[];
  /** REPL-side proof state pointer for the *current* coherent state. */
  proofState: number;
  /** Most recent goal-state text from the REPL. */
  goals: string;
  /** Status of the session. */
  status: "open" | "closed";
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

export async function startSession(args: ProofStartArgs): Promise<ProofSession> {
  const name = args.name?.trim() || "_active_proof";
  if (!PROOF_NAME_RE.test(name)) {
    throw new Error(
      `proof name "${name}" must match ${PROOF_NAME_RE} (Lean identifier rules)`,
    );
  }
  const claim = args.claim.trim();
  const theorem = args.theorem.trim();
  // Open the proof against the REPL — this types-checks the theorem
  // statement and returns the initial goal/state. Errors here mean
  // the type itself was malformed.
  const opened = await openProof(name, theorem);
  return {
    claim,
    theorem,
    name,
    tactics: [],
    proofState: opened.proofState,
    goals: opened.goal,
    status: "open",
  };
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
    session.status = "closed";
    session.goals = "(no goals — proof closed)";
    session.tactics.push(t);
    if (r.proofState !== undefined) session.proofState = r.proofState;
    return {
      status: "closed",
      goals: session.goals,
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
  session.tactics.push(t);
  session.goals = r.goals;
  if (r.proofState !== undefined) session.proofState = r.proofState;
  return {
    status: "open",
    goals: r.goals,
    errors: [],
    tacticCount: session.tactics.length,
  };
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
