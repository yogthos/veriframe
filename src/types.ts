export interface IncrementalSolver {
  push(): void;
  pop(): void;
  assert(expr: string): void;
  check(): Promise<"sat" | "unsat" | "unknown">;
  unsatCore(): string[];
  getModel(): Record<string, string>;
  dispose(): void;
}

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
}

export interface SolutionVerification {
  /** Z3's model assignments captured after the final SAT check. */
  model: Record<string, string>;
  /** True when no other model satisfies the constraints (uniqueness check). */
  unique: boolean;
  /** When non-unique: a counter-example assignment Z3 found. */
  counterExample?: Record<string, string>;
}

export type RunResult =
  | {
      status: "completed";
      steps: ReasoningStep[];
      finalAnswer: string;
      verification?: SolutionVerification;
    }
  | {
      /**
       * The encoded constraints are mutually inconsistent — Z3 proved
       * the puzzle UNSAT. This is a valid terminal answer ("the puzzle
       * has no solution"), not a harness failure.
       */
      status: "unsat";
      steps: ReasoningStep[];
      finalAnswer: string;
      unsatCore: string[];
    }
  | { status: "failed"; steps: ReasoningStep[]; error: string };
