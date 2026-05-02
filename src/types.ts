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

export type RunResult =
  | {
      status: "completed";
      steps: ReasoningStep[];
      finalAnswer: string;
    }
  | { status: "failed"; steps: ReasoningStep[]; error: string };
