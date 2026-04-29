/**
 * REPL-style agent loop — drives the LLM through a tool-call interface
 * against a persistent Z3 solver, the way a programmer iterates against
 * a REPL.
 *
 * Contrast with `runHarness` (harness.ts):
 *   harness.ts → model emits ALL its SMT in a step, harness pushes a
 *                frame, checks, retries on UNSAT. Single big commit
 *                per turn.
 *   agent.ts   → model issues ONE tool call per turn (assert / check /
 *                eval / view / done), gets the result back as history,
 *                and iterates. Mirrors how a programmer uses a REPL or
 *                debugger to build up a solution incrementally.
 *
 * Tool-call protocol: the model emits ```tool-call fenced JSON
 * `{"name": "<tool>", "args"?: {...}}`. The harness parses the FIRST
 * fence (one tool per turn), runs it against the persistent solver,
 * and feeds the result back as the next turn's "tool result". A
 * response with no fence is a wasted turn that decrements the budget.
 *
 * Turn budget: default 25, configurable via opts.config.maxTurns.
 *
 * Tool surface:
 *   View        — view_smt, list_assertions, summary
 *   Edit        — declare, assert, retract, reset
 *   Solver      — check, get_model, get_unsat_core, eval
 *   Probe       — probe_sat (try an assertion in a temp frame),
 *                 check_uniqueness (current model is the only one?)
 *   Reasoning   — note (no-op, helps the model organize)
 *   Control     — done (with claimed answer; harness verifies),
 *                 give_up
 */

import type { LLMClient, ChatMessage } from "../llm/types.js";
import { createIncrementalSolver, classifySmt } from "./solver.js";
import {
  type HarnessConfig,
  type ReasoningStep,
  type RunResult,
  type SolutionVerification,
} from "../types.js";

export interface AgentRunOptions {
  config?: Partial<HarnessConfig> & { maxTurns?: number };
  signal?: AbortSignal;
  /** Optional progress callback fired after each tool call. */
  onTurn?: (entry: TurnEntry) => void;
}

export interface TurnEntry {
  turn: number;
  toolCall: ToolCall;
  result: string;
}

export interface ToolCall {
  name: string;
  args: Record<string, unknown>;
  parseError?: string;
}

interface AgentSession {
  problem: string;
  solver: Awaited<ReturnType<typeof createIncrementalSolver>>;
  /** Named assertions the model has added (for display + retract). */
  assertions: Map<string, string>;
  /** Order of declarations (declare-const / declare-fun / declare-sort etc.). */
  declarations: string[];
  /** Last solver.check() result so eval / get_model know the state. */
  lastCheckResult: "sat" | "unsat" | "unknown" | null;
  /** Cached Z3 model decls after a SAT check, for eval. */
  cachedModel: Record<string, string> | null;
  /** Notes the model writes for its own bookkeeping. */
  notes: string[];
  /** Final answer when done is called. */
  finalAnswer: string | null;
  /** Trace of all turns for the run result. */
  turns: TurnEntry[];
}

const SYSTEM_PROMPT = `You are an SMT-LIB programmer working interactively with a Z3 solver. You will solve a given problem by issuing one tool call per turn, the same way a programmer iterates against a REPL.

You emit each tool call as a fenced JSON block:

\`\`\`tool-call
{"name": "<tool>", "args": {...}}
\`\`\`

The harness runs the tool and the result becomes the next turn's input. ONE tool call per response. Free-form prose around the fence is allowed for your own reasoning, but the harness only acts on the first fence.

## Tools

### View
- \`view_smt()\` — show the full SMT-LIB script you've built so far (declarations + named assertions).
- \`list_assertions()\` — list just the names of your asserted constraints.
- \`summary()\` — show a one-line status: number of declarations, assertions, last check result.

### Edit (changes solver state)
- \`declare({"smt": "(declare-const x Int)"})\` — add a declaration to the solver. Must be a single declaration (declare-const, declare-fun, declare-sort, define-fun, define-sort).
- \`assert({"name": "clue1", "smt": "(...)"})\` — assert a constraint with a name. The constraint goes through (assert (! ... :named clue1)). The expression should be the BODY of the assert, not wrapped in (assert ...).
- \`retract({"name": "clue1"})\` — remove a previously named assertion. The harness rebuilds the solver from the remaining declarations + assertions, so retracting is safe but resets any uncached state.

### Solver (queries — read-only)
- \`check()\` — run check-sat on the current state. Returns sat / unsat / unknown.
- \`get_model()\` — after a sat check, returns the current variable assignments. Errors if last check wasn't sat.
- \`get_unsat_core()\` — after an unsat check, returns the minimal conflicting :named labels. Errors if last check wasn't unsat.
- \`eval({"expr": "(+ a b)"})\` — evaluate an SMT expression in the current model (after sat). Useful to sanity-check a derived value.

### Probe
- \`probe_sat({"smt": "(...)"})\` — push a frame, assert this constraint, run check-sat, pop. Use to test "is X consistent with current state?" without permanent commitment.
- \`check_uniqueness()\` — after a sat check, asserts the negation of the current model in a fresh frame and re-runs check. Returns "unique" if UNSAT, "not unique" with a counter-example otherwise. Pops the frame on completion.

### Reasoning
- \`note({"text": "..."})\` — record a thinking note. No-op for the solver; helps you organize across turns.

### Control
- \`done({"answer": "..."})\` — declare the puzzle solved. Provide the human-readable final answer. The harness will verify by running check-sat + uniqueness on the current state and surface any discrepancy.
- \`give_up({"reason": "..."})\` — abandon the problem with a stated reason.

## Approach

1. Read the problem. Plan briefly in prose.
2. Declare your variables and add constraints incrementally. After each significant addition, run \`check()\` to confirm consistency. Use named assertions so you can retract or trace conflicts.
3. If \`check()\` returns unsat, run \`get_unsat_core()\` to see which constraints conflict, then retract or reformulate.
4. Once the constraints fully pin down the answer, run \`get_model()\` to extract Z3's solution. Verify uniqueness with \`check_uniqueness()\`.
5. Call \`done({"answer": "..."})\` with the human-readable answer.

Avoid dumping all your SMT in one turn — you lose the benefit of incremental feedback. Build up the encoding piece by piece and check often.

Avoid using quantifiers (forall / exists) on finite domains. Enumerate cases explicitly. For categorical mappings, declare a clear key in a \`note()\` and reference it consistently.

Never include solver-control commands inside an \`assert\` call — the harness manages (check-sat), (push), (pop), etc.`;

const TOOL_CALL_FENCE_RE = /```tool-call\s*\r?\n([\s\S]*?)```/;

function parseToolCall(response: string): ToolCall | null {
  const m = response.match(TOOL_CALL_FENCE_RE);
  if (!m) return null;
  const body = m[1].trim();
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    return {
      name: "__parse_error__",
      args: {},
      parseError: e instanceof Error ? e.message : String(e),
    };
  }
  if (
    !parsed ||
    typeof parsed !== "object" ||
    Array.isArray(parsed) ||
    typeof (parsed as { name?: unknown }).name !== "string" ||
    ((parsed as { name: string }).name as string).length === 0
  ) {
    return {
      name: "__parse_error__",
      args: {},
      parseError:
        "tool-call body must be a JSON object with a non-empty `name` string",
    };
  }
  const obj = parsed as { name: string; args?: unknown };
  const args =
    obj.args && typeof obj.args === "object" && !Array.isArray(obj.args)
      ? (obj.args as Record<string, unknown>)
      : {};
  return { name: obj.name, args };
}

function buildPrompt(session: AgentSession): string {
  const lines: string[] = [];
  lines.push("## Problem");
  lines.push(session.problem);
  lines.push("");
  if (session.notes.length > 0) {
    lines.push("## Your notes so far");
    for (const n of session.notes) lines.push(`- ${n}`);
    lines.push("");
  }
  lines.push("## Conversation history (most recent last)");
  if (session.turns.length === 0) {
    lines.push("(no turns yet — issue your first tool call)");
  } else {
    for (const t of session.turns) {
      const argsStr = Object.keys(t.toolCall.args).length > 0
        ? ` ${JSON.stringify(t.toolCall.args)}`
        : "";
      lines.push(`Turn ${t.turn}: ${t.toolCall.name}${argsStr}`);
      const truncated = t.result.length > 4000
        ? t.result.slice(0, 4000) + `\n... [truncated, ${t.result.length - 4000} more chars]`
        : t.result;
      lines.push(`→ ${truncated}`);
      lines.push("");
    }
  }
  lines.push("## Your turn");
  lines.push(
    "Issue exactly one tool call inside a ```tool-call fence. Free-form prose around the fence is allowed for reasoning.",
  );
  return lines.join("\n");
}

async function runTool(
  session: AgentSession,
  call: ToolCall,
): Promise<{ result: string; done?: boolean; gaveUp?: boolean }> {
  const { name, args } = call;
  switch (name) {
    case "view_smt": {
      const lines: string[] = [];
      if (session.declarations.length > 0) {
        lines.push("; Declarations");
        for (const d of session.declarations) lines.push(d);
      }
      if (session.assertions.size > 0) {
        lines.push("; Named assertions");
        for (const [n, body] of session.assertions) {
          lines.push(`(assert (! ${body} :named ${n}))`);
        }
      }
      if (lines.length === 0) return { result: "(empty — no declarations or assertions yet)" };
      return { result: lines.join("\n") };
    }
    case "list_assertions": {
      if (session.assertions.size === 0) return { result: "(no assertions)" };
      return {
        result: Array.from(session.assertions.keys())
          .map((n) => `- ${n}`)
          .join("\n"),
      };
    }
    case "summary": {
      const lc = session.lastCheckResult ?? "(not checked yet)";
      return {
        result: `decls=${session.declarations.length} asserts=${session.assertions.size} last_check=${lc}`,
      };
    }

    case "declare": {
      const smt = typeof args.smt === "string" ? args.smt.trim() : "";
      if (!smt) return { result: "[error] declare requires {smt: string}" };
      const kind = classifySmt(smt);
      if (kind !== "declaration") {
        return {
          result: `[error] declare expects a declaration (declare-const, declare-fun, declare-sort, define-fun, define-sort) — got ${kind}`,
        };
      }
      try {
        session.solver.assert(smt);
        session.declarations.push(smt);
        session.lastCheckResult = null;
        session.cachedModel = null;
        return { result: `OK — declaration added (#${session.declarations.length}).` };
      } catch (e) {
        return {
          result: `[error] solver rejected declaration: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "assert": {
      const name = typeof args.name === "string" ? args.name.trim() : "";
      const smt = typeof args.smt === "string" ? args.smt.trim() : "";
      if (!name || !smt) {
        return { result: "[error] assert requires {name: string, smt: string}" };
      }
      if (session.assertions.has(name)) {
        return {
          result: `[error] an assertion named "${name}" already exists. Retract it first or use a different name.`,
        };
      }
      // Strip wrapper if model wrote (assert ...) or (assert (! ... :named ...))
      let body = smt;
      const wrapMatch = body.match(/^\(\s*assert\s+(.*)\s*\)\s*$/s);
      if (wrapMatch) body = wrapMatch[1].trim();
      const namedMatch = body.match(/^\(\s*!\s+(.+?)\s+:named\s+\w+\s*\)$/s);
      if (namedMatch) body = namedMatch[1].trim();
      try {
        session.solver.assert(`(assert (! ${body} :named ${name}))`);
        session.assertions.set(name, body);
        session.lastCheckResult = null;
        session.cachedModel = null;
        return { result: `OK — asserted ${name}. Run check() when ready to test consistency.` };
      } catch (e) {
        return {
          result: `[error] solver rejected assertion: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "retract": {
      const name = typeof args.name === "string" ? args.name.trim() : "";
      if (!name) return { result: "[error] retract requires {name: string}" };
      if (!session.assertions.has(name)) {
        return { result: `[error] no assertion named "${name}". Current assertions: ${Array.from(session.assertions.keys()).join(", ") || "(none)"}` };
      }
      session.assertions.delete(name);
      // Rebuild solver from remaining declarations + assertions.
      session.solver.dispose();
      session.solver = await createIncrementalSolver();
      for (const d of session.declarations) session.solver.assert(d);
      for (const [n, body] of session.assertions) {
        session.solver.assert(`(assert (! ${body} :named ${n}))`);
      }
      session.lastCheckResult = null;
      session.cachedModel = null;
      return { result: `OK — retracted ${name}. Solver rebuilt with ${session.assertions.size} assertions remaining.` };
    }

    case "check": {
      try {
        const result = await session.solver.check();
        session.lastCheckResult = result;
        session.cachedModel = null;
        return { result };
      } catch (e) {
        return {
          result: `[error] check failed: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "get_model": {
      if (session.lastCheckResult !== "sat") {
        return {
          result: `[error] get_model requires the last check to have returned sat (last was ${session.lastCheckResult ?? "no check yet"}). Run check() first.`,
        };
      }
      try {
        const model = session.solver.getModel();
        session.cachedModel = model;
        const lines = Object.entries(model)
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([k, v]) => `  ${k} = ${v}`);
        if (lines.length === 0) return { result: "(model is empty)" };
        return { result: `Model:\n${lines.join("\n")}` };
      } catch (e) {
        return {
          result: `[error] get_model failed: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "get_unsat_core": {
      if (session.lastCheckResult !== "unsat") {
        return {
          result: `[error] get_unsat_core requires the last check to have returned unsat (last was ${session.lastCheckResult ?? "no check yet"}). Run check() first.`,
        };
      }
      try {
        const core = session.solver.unsatCore();
        if (core.length === 0) return { result: "(empty core — no :named assertions are in conflict)" };
        return { result: `Conflicting set:\n${core.map((c) => `  - ${c}`).join("\n")}` };
      } catch (e) {
        return {
          result: `[error] get_unsat_core failed: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "eval": {
      if (session.lastCheckResult !== "sat") {
        return {
          result: `[error] eval requires a sat state (last check was ${session.lastCheckResult ?? "no check yet"}).`,
        };
      }
      // Z3 eval requires a model context. For simplicity, return the
      // value of any variable already in the cached model, or evaluate
      // by asserting equality in a temp frame.
      const expr = typeof args.expr === "string" ? args.expr.trim() : "";
      if (!expr) return { result: "[error] eval requires {expr: string}" };
      // Simple variable lookup
      if (session.cachedModel && session.cachedModel[expr] !== undefined) {
        return { result: `${expr} = ${session.cachedModel[expr]}` };
      }
      // Ensure cached model
      if (!session.cachedModel) {
        try {
          session.cachedModel = session.solver.getModel();
        } catch {
          return {
            result: "[error] could not extract a model to evaluate against",
          };
        }
      }
      if (session.cachedModel[expr] !== undefined) {
        return { result: `${expr} = ${session.cachedModel[expr]}` };
      }
      return {
        result: `[hint] eval currently only supports bare variable names from the model. The model has: ${Object.keys(session.cachedModel).join(", ") || "(empty)"}`,
      };
    }

    case "probe_sat": {
      const smt = typeof args.smt === "string" ? args.smt.trim() : "";
      if (!smt) return { result: "[error] probe_sat requires {smt: string}" };
      let body = smt;
      const wrapMatch = body.match(/^\(\s*assert\s+(.*)\s*\)\s*$/s);
      if (wrapMatch) body = wrapMatch[1].trim();
      try {
        session.solver.push();
        try {
          session.solver.assert(body);
          const result = await session.solver.check();
          return { result: `probe ${result} (frame popped, no permanent change)` };
        } finally {
          session.solver.pop();
          session.lastCheckResult = null;
          session.cachedModel = null;
        }
      } catch (e) {
        return {
          result: `[error] probe_sat failed: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "check_uniqueness": {
      if (session.lastCheckResult !== "sat") {
        return {
          result: `[error] check_uniqueness requires a sat state (last was ${session.lastCheckResult ?? "no check"}). Run check() and ensure it's sat first.`,
        };
      }
      let model: Record<string, string>;
      try {
        model = session.solver.getModel();
      } catch (e) {
        return { result: `[error] could not extract model: ${e instanceof Error ? e.message : String(e)}` };
      }
      const entries = Object.entries(model);
      if (entries.length === 0) return { result: "[hint] model is empty (no constants declared) — uniqueness is vacuous" };
      const clauses = entries.map(([n, v]) => `(not (= ${n} ${v}))`);
      const negation = clauses.length === 1 ? clauses[0] : `(or ${clauses.join(" ")})`;
      try {
        session.solver.push();
        try {
          session.solver.assert(negation);
          const result = await session.solver.check();
          if (result === "unsat") {
            return { result: "UNIQUE — Z3 confirms no other model satisfies the constraints." };
          }
          if (result === "sat") {
            const counter = session.solver.getModel();
            const counterStr = Object.entries(counter)
              .map(([n, v]) => `  ${n} = ${v}`)
              .join("\n");
            return { result: `NOT UNIQUE — another satisfying assignment exists:\n${counterStr}` };
          }
          return { result: `inconclusive — Z3 returned ${result} on the negation` };
        } finally {
          session.solver.pop();
          // The solver's internal lastCheckResult was clobbered by the
          // negation probe's check. Re-run to restore the SAT state so
          // get_model / eval still work in subsequent turns.
          await session.solver.check();
          session.lastCheckResult = "sat";
        }
      } catch (e) {
        return {
          result: `[error] check_uniqueness failed: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "note": {
      const text = typeof args.text === "string" ? args.text.trim() : "";
      if (!text) return { result: "[error] note requires {text: string}" };
      session.notes.push(text);
      return { result: `Noted (${session.notes.length} note(s) total).` };
    }

    case "done": {
      const answer = typeof args.answer === "string" ? args.answer : JSON.stringify(args.answer ?? "");
      if (!answer) return { result: "[error] done requires {answer: string} — your final human-readable answer." };
      session.finalAnswer = answer;
      return { result: "OK — finalizing.", done: true };
    }

    case "give_up": {
      const reason = typeof args.reason === "string" ? args.reason : "(no reason given)";
      session.finalAnswer = `[gave up: ${reason}]`;
      return { result: "OK — abandoning.", gaveUp: true };
    }

    case "__parse_error__": {
      return { result: `Your tool-call JSON was invalid: ${call.parseError ?? "unknown parse error"}` };
    }

    default:
      return {
        result: `[error] unknown tool "${name}". Valid: view_smt, list_assertions, summary, declare, assert, retract, check, get_model, get_unsat_core, eval, probe_sat, check_uniqueness, note, done, give_up.`,
      };
  }
}

export async function runAgent(
  problem: string,
  llm: LLMClient,
  opts: AgentRunOptions = {},
): Promise<RunResult> {
  const maxTurns = opts.config?.maxTurns ?? 25;
  const solver = await createIncrementalSolver();
  const session: AgentSession = {
    problem,
    solver,
    assertions: new Map(),
    declarations: [],
    lastCheckResult: null,
    cachedModel: null,
    notes: [],
    finalAnswer: null,
    turns: [],
  };

  let turn = 0;
  let outcome: "done" | "gave_up" | "exhausted" = "exhausted";

  try {
    while (turn < maxTurns) {
      turn++;
      if (opts.signal?.aborted) {
        outcome = "gave_up";
        session.finalAnswer = "[aborted]";
        break;
      }
      const messages: ChatMessage[] = [
        { role: "system", content: SYSTEM_PROMPT },
        { role: "user", content: buildPrompt(session) },
      ];
      let response: string;
      try {
        const resp = await llm.chat(messages, { signal: opts.signal });
        response = resp.content;
      } catch (e) {
        return {
          status: "failed",
          steps: turnsToSteps(session.turns),
          error: `chat failed at turn ${turn}: ${e instanceof Error ? e.message : String(e)}`,
        };
      }

      const call = parseToolCall(response);
      if (!call) {
        const entry: TurnEntry = {
          turn,
          toolCall: { name: "__no_call__", args: {} },
          result: "Your previous response contained no ```tool-call fence. Emit exactly one tool call per turn.",
        };
        session.turns.push(entry);
        opts.onTurn?.(entry);
        continue;
      }

      const { result, done, gaveUp } = await runTool(session, call);
      const entry: TurnEntry = { turn, toolCall: call, result };
      session.turns.push(entry);
      opts.onTurn?.(entry);

      if (done) {
        outcome = "done";
        break;
      }
      if (gaveUp) {
        outcome = "gave_up";
        break;
      }
    }

    if (outcome === "done") {
      // Verify the final state: confirm SAT and surface uniqueness.
      // Re-run check() unconditionally — the solver's internal state may
      // have been left at "unsat" by the model's earlier check_uniqueness
      // probe (which proves uniqueness via UNSAT-on-negation but leaves
      // the solver pointing at that negative result).
      let verification: SolutionVerification | undefined;
      try {
        const checkResult = await session.solver.check();
        if (checkResult === "sat") {
          const model = session.solver.getModel();
          if (Object.keys(model).length > 0) {
            // Uniqueness probe
            const clauses = Object.entries(model).map(
              ([n, v]) => `(not (= ${n} ${v}))`,
            );
            const negation = clauses.length === 1 ? clauses[0] : `(or ${clauses.join(" ")})`;
            session.solver.push();
            try {
              session.solver.assert(negation);
              const r = await session.solver.check();
              if (r === "unsat") {
                verification = { model, unique: true };
              } else if (r === "sat") {
                let counterExample: Record<string, string> | undefined;
                try {
                  counterExample = session.solver.getModel();
                } catch {
                  // ignore
                }
                verification = { model, unique: false, counterExample };
              } else {
                verification = { model, unique: false };
              }
            } finally {
              session.solver.pop();
            }
          }
        }
      } catch {
        // verification best-effort
      }
      return {
        status: "completed",
        steps: turnsToSteps(session.turns),
        finalAnswer: renderFinalAnswer(session, verification),
        verification,
      };
    }

    if (outcome === "gave_up") {
      return {
        status: "failed",
        steps: turnsToSteps(session.turns),
        error: session.finalAnswer ?? "[gave up]",
      };
    }

    // exhausted
    return {
      status: "failed",
      steps: turnsToSteps(session.turns),
      error: `Turn budget (${maxTurns}) exhausted without calling done() or give_up().`,
    };
  } finally {
    session.solver.dispose();
  }
}

function turnsToSteps(turns: TurnEntry[]): ReasoningStep[] {
  return turns.map((t) => ({
    stepNumber: t.turn,
    explanation: `${t.toolCall.name}${
      Object.keys(t.toolCall.args).length > 0
        ? ` ${JSON.stringify(t.toolCall.args)}`
        : ""
    }`,
    assertions: [],
    status: "accepted",
  }));
}

function renderFinalAnswer(
  session: AgentSession,
  verification: SolutionVerification | undefined,
): string {
  const lines: string[] = [];
  lines.push("Final answer (model-claimed):");
  lines.push(session.finalAnswer ?? "(none)");
  lines.push("");
  if (verification) {
    if (verification.unique) {
      lines.push("Z3-verified assignment (UNIQUE — proven by UNSAT on negation):");
    } else {
      lines.push("⚠ Z3 model is NOT UNIQUE — multiple assignments satisfy the constraints.");
    }
    for (const [k, v] of Object.entries(verification.model)) {
      lines.push(`  ${k} = ${v}`);
    }
    if (!verification.unique && verification.counterExample) {
      lines.push("Counter-example:");
      for (const [k, v] of Object.entries(verification.counterExample)) {
        lines.push(`  ${k} = ${v}`);
      }
    }
  } else {
    lines.push("⚠ No Z3 verification was performed — the model claimed done without leaving the solver in a SAT state with extractable variables.");
  }
  lines.push("");
  lines.push(`(${session.turns.length} turns)`);
  return lines.join("\n");
}
