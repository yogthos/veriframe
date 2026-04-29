/**
 * Harness loop — drives the LLM through Z3-verified reasoning steps.
 *
 * Each iteration:
 *   1. Ask the LLM for the next step (JSON: explanation, assertions, complete).
 *   2. Parse, validate.
 *   3. solver.push(); assert each; solver.check().
 *      - SAT      → step accepted; loop with new accumulated facts.
 *      - UNSAT    → pop, ask the LLM to fix using the unsat core.
 *      - Unknown  → treat as accepted (Z3 couldn't decide; not a contradiction).
 *   4. If `complete: true` and assertions consistent → done.
 */

import type {
  LLMClient,
  ChatMessage,
} from "../llm/types.js";
import {
  buildSystemPrompt,
  buildStepPrompt,
  buildFixPrompt,
} from "./prompts.js";
import { parseLLMOutput } from "./parser.js";
import { createIncrementalSolver, classifySmt } from "./solver.js";
import {
  DEFAULT_HARNESS_CONFIG,
  type HarnessConfig,
  type ReasoningStep,
  type RunResult,
  type SolutionVerification,
} from "../types.js";

export interface RunOptions {
  config?: Partial<HarnessConfig>;
  /** Optional progress callback fired after each step decision. */
  onStep?: (step: ReasoningStep) => void;
  signal?: AbortSignal;
}

export async function runHarness(
  problem: string,
  llm: LLMClient,
  opts: RunOptions = {},
): Promise<RunResult> {
  const config: HarnessConfig = { ...DEFAULT_HARNESS_CONFIG, ...opts.config };
  const maxReadBack = config.maxReadBackRetries;
  let readBackHint = "";
  let lastResult: RunResult | null = null;
  const readBackHistory: Array<{ attempt: number; verdict: "OK" | "NEEDS_FIX"; explanation: string }> = [];

  for (let attemptIdx = 0; attemptIdx <= maxReadBack; attemptIdx++) {
    const result = await runHarnessOnce(problem, llm, opts, readBackHint);
    lastResult = result;

    // Read-back fires for both completed and unsat runs:
    //   - completed: catch encoding bugs that produce a verified-wrong answer
    //   - unsat:     catch encoding bugs that flip a SAT puzzle to UNSAT
    // Hard "failed" runs (parse errors, etc.) skip read-back — there's
    // no encoding to read back from.
    if (result.status === "failed") {
      return result;
    }

    // Skip read-back if disabled or if no real assertions were emitted
    // (e.g. degenerate single-integer-answer problems where the model
    // just asserted a value).
    const hasMaterialEncoding = result.steps.some(
      (s) => s.status === "accepted" && s.assertions.length >= 2,
    );
    if (maxReadBack === 0 || !hasMaterialEncoding) {
      return result;
    }

    const readBack = await runReadBackCheck(llm, problem, result.steps, opts.signal, result.status);
    readBackHistory.push({
      attempt: attemptIdx + 1,
      verdict: readBack.ok ? "OK" : "NEEDS_FIX",
      explanation: readBack.explanation,
    });

    if (readBack.ok) {
      return appendReadBackNote(result, readBackHistory);
    }
    if (attemptIdx === maxReadBack) {
      // Out of retries — surface the read-back warning loudly but
      // still return the result. The user can decide.
      return appendReadBackNote(result, readBackHistory);
    }

    // Retry with the read-back feedback as a hint for step 1
    readBackHint = readBack.explanation;
  }

  // Should not reach. Return last result as a safety net.
  return lastResult ?? {
    status: "failed",
    steps: [],
    error: "read-back loop did not produce a result",
  };
}

function appendReadBackNote(
  result: RunResult,
  history: Array<{ attempt: number; verdict: "OK" | "NEEDS_FIX"; explanation: string }>,
): RunResult {
  if (result.status !== "completed" || history.length === 0) return result;
  const last = history[history.length - 1];
  const lines: string[] = ["", "─── Read-back verification ───"];
  for (const h of history) {
    lines.push(`Attempt ${h.attempt}: ${h.verdict}`);
  }
  if (last.verdict === "OK") {
    lines.push(
      "Model confirmed the SMT-LIB encoding matches the natural-language prompt.",
    );
  } else {
    lines.push(
      "⚠ Model could not reconcile its encoding with the prompt within the retry budget.",
    );
    lines.push("Final issues reported:");
    lines.push(last.explanation);
    lines.push(
      "The verified Z3 model below may not correspond to the original problem.",
    );
  }
  return {
    ...result,
    finalAnswer: result.finalAnswer + "\n" + lines.join("\n"),
  };
}

/**
 * Read-back verification: ask the model to translate each :named
 * assertion in its SMT encoding back into English, compare against the
 * original prompt's clues, and return a verdict.
 */
async function runReadBackCheck(
  llm: LLMClient,
  problem: string,
  steps: ReasoningStep[],
  signal?: AbortSignal,
  resultStatus?: "completed" | "unsat",
): Promise<{ ok: boolean; explanation: string }> {
  const allSmt: string[] = [];
  for (const s of steps) {
    if (s.status !== "accepted") continue;
    for (const a of s.assertions) {
      const trimmed = a.trim();
      if (trimmed.length > 0) allSmt.push(trimmed);
    }
  }
  if (allSmt.length === 0) return { ok: true, explanation: "" };

  const statusContext = resultStatus === "unsat"
    ? `\n\nIMPORTANT: Z3 reported these constraints UNSAT (no satisfying assignment exists). If the puzzle is supposed to have a solution, the UNSAT verdict means your encoding has a bug — most likely a wrong categorical-to-integer mapping or a flipped direction. If the puzzle truly has no solution (e.g. a pigeonhole problem), then UNSAT is correct and the encoding is fine. Decide which case applies here and report accordingly.`
    : "";

  const checkPrompt = `You previously produced the following SMT-LIB encoding for a problem. Your task now is to verify, BEFORE the result is finalized, that the encoding faithfully captures the natural-language prompt.${statusContext}

PROBLEM (original natural-language statement):
${problem}

YOUR ENCODING:
${allSmt.join("\n")}

VERIFICATION INSTRUCTIONS:
Step through every \`:named\` assertion in the encoding above. For each one:
1. State the SMT-LIB form.
2. Read it back into plain English.
3. Identify which sentence/clue in the original PROBLEM it is meant to encode.
4. Compare the back-translation against the original clue. Do they say the same thing?

Pay particular attention to:
- Categorical-to-integer mappings: if you declared a key like "0=Englishman, 1=Spaniard, …", verify each assertion uses the right index for the value name. Off-by-one mistakes are common here.
- Direction: "left of" vs "right of", "above" vs "below", "before" vs "after".
- Quantifier scope: "exactly N" vs "at least N" vs "at most N".
- Negation polarity: did you mean "X is Y" or "X is NOT Y"?
- Coverage: are there clues in the prompt that have NO corresponding assertion?

After going through every assertion, finish your response with EXACTLY one of these two lines on its own:

  VERDICT: OK

if every assertion correctly encodes its source clue and every clue is represented, OR

  VERDICT: NEEDS_FIX

if any assertion is mis-encoded or any clue is missing. After NEEDS_FIX, list the specific issues, one per line, prefixed with "- ", e.g.:
  - clue1 is encoded as "Spaniard in Red house" but the prompt says "Englishman in Red house" (off-by-one in the nationality key)
  - clue9 is missing from the encoding
  - clue11 used >= where the prompt says strictly greater-than

Be conservative: if you are uncertain whether an assertion matches the source, lean toward NEEDS_FIX so the encoding can be corrected before Z3 runs.`;

  const response = await llm.chat(
    [{ role: "user", content: checkPrompt }],
    { signal },
  );
  const text = response.content;

  const okMatch = /^\s*VERDICT:\s*OK\b/im.test(text);
  const needsFixMatch = /^\s*VERDICT:\s*NEEDS_FIX\b/im.test(text);

  if (needsFixMatch) {
    // Extract everything from VERDICT: NEEDS_FIX onward as the issue list.
    const idx = text.search(/^\s*VERDICT:\s*NEEDS_FIX\b/im);
    const issues = idx >= 0 ? text.slice(idx) : text;
    return { ok: false, explanation: issues.trim().slice(0, 4000) };
  }
  if (okMatch) {
    return { ok: true, explanation: "" };
  }
  // No clear verdict — be conservative and accept (don't loop forever
  // on noisy responses). The uniqueness check is still a backstop.
  return { ok: true, explanation: "" };
}

async function runHarnessOnce(
  problem: string,
  llm: LLMClient,
  opts: RunOptions,
  readBackHint: string,
): Promise<RunResult> {
  const config: HarnessConfig = { ...DEFAULT_HARNESS_CONFIG, ...opts.config };
  const solver = await createIncrementalSolver();
  const accepted: ReasoningStep[] = [];

  const systemPrompt = buildSystemPrompt();

  try {
    let totalRetries = 0;
    let assertionsEverAccepted = 0;

    for (let stepNum = 1; stepNum <= config.maxSteps; stepNum++) {
      let prompt = buildStepPrompt(problem, accepted, stepNum);
      // On a read-back retry, prepend the previous attempt's issues as
      // a correction hint to the very first step. This way the model
      // re-encodes the problem with the discrepancies in mind rather
      // than rediscovering them.
      if (stepNum === 1 && readBackHint) {
        prompt = `## Read-back verification on your previous attempt found the following issues:\n\n${readBackHint}\n\nThese issues are blocking. Re-encode the problem from scratch, paying particular attention to the discrepancies above. Do NOT repeat the same mistakes — re-derive each :named assertion carefully and double-check categorical-to-integer mappings, direction, quantifier scope, and negation polarity before emitting.\n\n────────────────\n\n${prompt}`;
      }
      let attempt = 0;
      let stepAccepted = false;
      const failedAttempts: Array<{ reason: string; content: string }> = [];

      while (attempt < config.maxRetriesPerStep && !stepAccepted) {
        if (opts.signal?.aborted) {
          return {
            status: "failed",
            steps: accepted,
            error: "Aborted",
          };
        }

        const messages: ChatMessage[] = [
          { role: "system", content: systemPrompt },
          { role: "user", content: prompt },
        ];

        // NOTE: do NOT pass responseFormat: json_object here.
        // node-llama-cpp's JSON grammar forces the very first token to
        // be `{`, which suppresses Qwen-style <think>...</think> blocks
        // and starves the model of reasoning budget on hard problems.
        // The parser already handles JSON in markdown fences or
        // surrounded by prose, so we let the model reason freely.
        const response = await llm.chat(messages, {
          signal: opts.signal,
        });

        const parsed = parseLLMOutput(response.content);
        if (!parsed) {
          attempt++;
          totalRetries++;
          failedAttempts.push({
            reason: "unparseable",
            content: response.content.slice(0, 800),
          });
          if (totalRetries >= config.maxRetries) {
            return {
              status: "failed",
              steps: accepted,
              error: `Could not parse LLM output after ${totalRetries} retries. Last response: ${response.content.slice(0, 300)}`,
            };
          }
          prompt = `${prompt}\n\nYour previous response could not be parsed. Output ONLY the JSON object — no prose, no markdown.`;
          continue;
        }

        // Filter out solver-control commands the harness owns
        // (check-sat, get-model, push, pop, etc.). These are no-ops for
        // the harness — it manages frames and result extraction itself.
        const realAssertions: string[] = [];
        const droppedControls: string[] = [];
        for (const a of parsed.assertions) {
          if (classifySmt(a) === "control") droppedControls.push(a);
          else realAssertions.push(a);
        }

        // No-op step: the model produced only control commands (or nothing)
        // without claiming completion. Reject and ask for real progress.
        if (realAssertions.length === 0 && !parsed.complete) {
          attempt++;
          totalRetries++;
          const rejected: ReasoningStep = {
            stepNumber: stepNum,
            explanation: parsed.explanation,
            assertions: parsed.assertions,
            status: "rejected",
          };
          opts.onStep?.(rejected);
          if (totalRetries >= config.maxRetries) {
            return {
              status: "failed",
              steps: accepted,
              error: `Maximum retries (${config.maxRetries}) reached at step ${stepNum} (no-op steps)`,
            };
          }
          const droppedNote = droppedControls.length > 0
            ? ` (dropped: ${droppedControls.join(", ")})`
            : "";
          prompt = `${prompt}\n\nYour previous step contained no new declarations or assertions${droppedNote}. The harness manages (check-sat) / (get-model) / push / pop itself — do NOT include those. Each step must introduce at least one new declaration or assertion that advances the proof. If the problem is fully solved, set "complete": true and return an empty assertions array.`;
          continue;
        }

        // Empty assertions + complete=true => done. Verify before accepting.
        if (parsed.complete && realAssertions.length === 0) {
          // Premature completion: nothing has been declared/asserted yet.
          // The model is trying to skip the work — push back.
          if (assertionsEverAccepted === 0) {
            attempt++;
            totalRetries++;
            const rejected: ReasoningStep = {
              stepNumber: stepNum,
              explanation: parsed.explanation,
              assertions: parsed.assertions,
              status: "rejected",
            };
            opts.onStep?.(rejected);
            if (totalRetries >= config.maxRetries) {
              return {
                status: "failed",
                steps: accepted,
                error: `Maximum retries (${config.maxRetries}) reached at step ${stepNum} (premature completion with zero assertions)`,
              };
            }
            prompt = `${prompt}\n\nYou cannot mark the problem complete without first emitting any SMT-LIB. Start by declaring the variables and asserting the constraints from the problem statement, one step at a time. Each accepted step adds to the running constraint set.`;
            continue;
          }
          const verification = await verifySolution(solver);
          const finalStep: ReasoningStep = {
            stepNumber: stepNum,
            explanation: parsed.explanation || "Problem solved.",
            assertions: [],
            status: "accepted",
          };
          accepted.push(finalStep);
          opts.onStep?.(finalStep);
          return {
            status: "completed",
            steps: accepted,
            finalAnswer: renderFinalAnswer(accepted, verification),
            verification,
          };
        }

        // Try the assertions in a push frame.
        solver.push();
        try {
          for (const a of realAssertions) solver.assert(a);
          const result = await solver.check();

          if (result === "unsat") {
            const core = solver.unsatCore();
            solver.pop();
            attempt++;
            totalRetries++;
            failedAttempts.push({
              reason: "unsat",
              content: core.join("|"),
            });

            const rejected: ReasoningStep = {
              stepNumber: stepNum,
              explanation: parsed.explanation,
              assertions: parsed.assertions,
              status: "rejected",
              unsatCore: core,
            };
            opts.onStep?.(rejected);

            if (totalRetries >= config.maxRetries) {
              return {
                status: "failed",
                steps: accepted,
                error: `Maximum retries (${config.maxRetries}) reached at step ${stepNum}`,
              };
            }

            prompt = buildFixPrompt(
              problem,
              parsed.assertions,
              core,
              accepted,
              stepNum,
            );
            continue;
          }

          // SAT or unknown — keep frame, move on.
          const acceptedStep: ReasoningStep = {
            stepNumber: stepNum,
            explanation: parsed.explanation,
            assertions: realAssertions,
            status: "accepted",
          };
          accepted.push(acceptedStep);
          assertionsEverAccepted += realAssertions.length;
          opts.onStep?.(acceptedStep);
          stepAccepted = true;

          if (parsed.complete) {
            const verification = await verifySolution(solver);
            return {
              status: "completed",
              steps: accepted,
              finalAnswer: renderFinalAnswer(accepted, verification),
              verification,
            };
          }
        } catch (err) {
          // Solver threw on a malformed assertion — treat as a fix-required.
          solver.pop();
          attempt++;
          totalRetries++;
          if (totalRetries >= config.maxRetries) {
            return {
              status: "failed",
              steps: accepted,
              error: `Solver error after ${totalRetries} retries: ${err instanceof Error ? err.message : String(err)}`,
            };
          }
          prompt = `${prompt}\n\nThe Z3 solver rejected your last batch with: ${err instanceof Error ? err.message : String(err)}\nFix the SMT-LIB syntax and try again.`;
        }
      }

      if (!stepAccepted) {
        // If at least one attempt produced UNSAT *and* among the UNSAT
        // attempts the unsat cores are consistent, treat this as a
        // valid "no solution" terminus rather than a harness failure.
        // This handles the case where some attempts truncated/unparseable
        // but the model eventually produced a clean encoding that Z3
        // confidently rejected.
        const unsatAttempts = failedAttempts.filter((f) => f.reason === "unsat");
        const unsatCores = unsatAttempts.map((f) => f.content);
        const coresConsistent = unsatCores.length > 0
          && unsatCores.every((c) => c === unsatCores[0]);
        if (unsatAttempts.length >= 1 && coresConsistent) {
          const core = unsatCores[0].split("|").filter(Boolean);
          const coreStr = core.length > 0
            ? `\n\nMinimal conflicting set (unsat core):\n${core.map((c) => `  - ${c}`).join("\n")}`
            : "\n\n(The model's assertions did not use :named labels, so no minimal core could be reported. Consider adding :named annotations on each assertion to identify the conflict.)";
          return {
            status: "unsat",
            steps: accepted,
            finalAnswer: `The encoded constraints are mutually inconsistent — Z3 proved UNSAT.${coreStr}\n\nIf the SMT-LIB faithfully encodes the puzzle, this means the puzzle as stated has NO valid solution.`,
            unsatCore: core,
          };
        }
        const sample = failedAttempts.length > 0
          ? `\nLast failed attempt (${failedAttempts[failedAttempts.length - 1].reason}):\n${failedAttempts[failedAttempts.length - 1].content}`
          : "";
        return {
          status: "failed",
          steps: accepted,
          error: `Step ${stepNum} could not be accepted within ${config.maxRetriesPerStep} attempts.${sample}`,
        };
      }
    }

    return {
      status: "failed",
      steps: accepted,
      error: `Reached maxSteps=${config.maxSteps} without completion`,
    };
  } finally {
    solver.dispose();
  }
}

function renderFinalAnswer(
  steps: ReasoningStep[],
  verification: SolutionVerification | undefined,
): string {
  const lines: string[] = [];
  for (const s of steps) {
    if (s.status !== "accepted") continue;
    if (s.explanation) lines.push(`Step ${s.stepNumber}: ${s.explanation}`);
  }

  if (!verification) {
    lines.push("");
    lines.push("⚠ NO Z3 VERIFICATION — solver returned 'unknown' or no model was extractable.");
    lines.push("This usually means the encoding used quantifiers (forall/exists) over an");
    lines.push("undecidable theory, or unsupported features. The reasoning above is the model's");
    lines.push("prose only — Z3 did NOT independently confirm a unique solution. Re-encode the");
    lines.push("problem in a quantifier-free, finite-domain form (enumerate cases explicitly,");
    lines.push("use bounded Int sorts) and re-run if you need a verified answer.");
  } else if (verification) {
    const modelEntries = Object.entries(verification.model);
    if (modelEntries.length > 0) {
      lines.push("");
      if (verification.unique) {
        lines.push("Z3-verified assignment (UNIQUE — proven by UNSAT on negation):");
        for (const [name, val] of modelEntries) {
          lines.push(`  ${name} = ${val}`);
        }
      } else {
        lines.push("⚠ ENCODING UNDER-CONSTRAINED — multiple assignments satisfy these constraints.");
        lines.push("This usually means the SMT-LIB does NOT actually capture the problem (e.g. wrong");
        lines.push("variable semantics, sort confusion, or a missing constraint). The reported answer");
        lines.push("is one Z3 model out of many and should NOT be trusted as the puzzle solution.");
        lines.push("");
        lines.push("Sample Z3 model:");
        for (const [name, val] of modelEntries) {
          lines.push(`  ${name} = ${val}`);
        }
        if (verification.counterExample) {
          lines.push("Another satisfying assignment Z3 found (proves non-uniqueness):");
          for (const [name, val] of Object.entries(verification.counterExample)) {
            lines.push(`  ${name} = ${val}`);
          }
        }
      }
    }
  }

  return lines.join("\n");
}

/**
 * After a successful run, capture Z3's model and verify uniqueness by
 * pushing the negation of the model and re-checking. UNSAT → unique.
 * SAT → another solution exists; record it as a counter-example.
 */
async function verifySolution(
  solver: Awaited<ReturnType<typeof createIncrementalSolver>>,
): Promise<SolutionVerification | undefined> {
  // Make sure we have a current SAT result so model() is valid.
  const status = await solver.check();
  if (status !== "sat") return undefined;

  let model: Record<string, string>;
  try {
    model = solver.getModel();
  } catch {
    return undefined;
  }
  // Empty model = no constants declared. Nothing to verify; surface this
  // as "no answer extracted" rather than vacuously claiming uniqueness.
  if (Object.keys(model).length === 0) {
    return undefined;
  }

  // Build (or (not (= v1 val1)) (not (= v2 val2)) ...) — any difference
  // would satisfy this, so UNSAT means no other assignment exists.
  const clauses: string[] = [];
  for (const [name, val] of Object.entries(model)) {
    clauses.push(`(not (= ${name} ${val}))`);
  }
  const negation = clauses.length === 1 ? clauses[0] : `(or ${clauses.join(" ")})`;

  solver.push();
  try {
    solver.assert(negation);
    const result = await solver.check();
    if (result === "unsat") {
      return { model, unique: true };
    }
    if (result === "sat") {
      let counterExample: Record<string, string> | undefined;
      try {
        counterExample = solver.getModel();
      } catch {
        counterExample = undefined;
      }
      return { model, unique: false, counterExample };
    }
    return { model, unique: false };
  } finally {
    solver.pop();
  }
}
