export interface IncrementalSolver {
  push(): void;
  pop(): void;
  assert(expr: string): void;
  check(): Promise<"sat" | "unsat" | "unknown">;
  unsatCore(): string[];
  getModel(): Record<string, string>;
  dispose(): void;
}

export interface Fact {
  assertion: string;
  name?: string;
}

export interface LLMStepOutput {
  explanation: string;
  assertions: string[];
  complete: boolean;
}

export interface ReasoningStep {
  stepNumber: number;
  explanation: string;
  assertions: string[];
  status: "accepted" | "rejected";
  unsatCore?: string[];
}

export interface HarnessState {
  problem: string;
  steps: ReasoningStep[];
  currentStep: number;
  status: "in_progress" | "completed" | "failed";
  error?: string;
}

export interface HarnessConfig {
  maxSteps: number;
  maxRetries: number;
  maxRetriesPerStep: number;
  /**
   * After the model marks the run complete, ask it to read its SMT-LIB
   * encoding back into English and verify it matches the prompt. If it
   * spots a discrepancy, the run is reset and re-attempted with the
   * read-back feedback as a hint, up to this many extra attempts.
   * Set to 0 to disable read-back verification.
   */
  maxReadBackRetries: number;
}

export const DEFAULT_HARNESS_CONFIG: HarnessConfig = {
  maxSteps: 20,
  maxRetries: 10,
  maxRetriesPerStep: 3,
  maxReadBackRetries: 1,
};

export interface LLMClient {
  chat(
    messages: { role: string; content: string }[]
  ): Promise<{ content: string }>;
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
       * the puzzle UNSAT and the model couldn't fix it after retries.
       * This is a valid terminal answer ("the puzzle has no solution"),
       * not a harness failure, when the unsat core covers the puzzle's
       * own constraints rather than a slip in encoding.
       */
      status: "unsat";
      steps: ReasoningStep[];
      finalAnswer: string;
      unsatCore: string[];
    }
  | { status: "failed"; steps: ReasoningStep[]; error: string };
