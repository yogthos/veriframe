/**
 * One entry in the agent's per-turn trace. The agent emits a `toolCall`
 * each turn (parsed from the model's response), and the harness fills
 * in the `result`. We surface this as a flat ReasoningStep array on the
 * RunResult for backward-compatible API shape.
 */
export interface ReasoningStep {
  stepNumber: number;
  explanation: string;
  assertions: string[];
  status: "accepted" | "rejected";
  unsatCore?: string[];
  /**
   * Truncated tool result so a reader can see *what each step
   * actually returned*, not just what the model called. Without this,
   * a `verify_smt` that returned UNKNOWN looks identical in the trace
   * to one that returned UNSAT, and the user cannot diagnose why a
   * call produced no artifact.
   */
  result?: string;
}

/**
 * Machine-verified output produced during a run — Lean snippets that
 * compiled, SMT-LIB queries that Z3 answered SAT/UNSAT. We surface
 * these on every `RunResult` (success OR failure) so the caller can
 * inspect partial progress when the agent runs out of turns. Mirrors
 * `VerifiedArtifact` inside the agent module — kept structurally
 * compatible without forcing types.ts to depend on agent internals.
 */
export interface RunVerifiedArtifact {
  kind: "lean" | "smt";
  claim: string;
  code: string;
  verdict?: "sat" | "unsat" | "unknown";
  model?: Record<string, string>;
  claimStatus: "confirmed" | "refuted" | "ambiguous" | "existential";
}

export type RunResult =
  | {
      status: "completed";
      steps: ReasoningStep[];
      finalAnswer: string;
      verifiedArtifacts: RunVerifiedArtifact[];
    }
  | {
      status: "failed";
      steps: ReasoningStep[];
      error: string;
      verifiedArtifacts: RunVerifiedArtifact[];
    };
