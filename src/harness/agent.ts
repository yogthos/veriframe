/**
 * Minimal REPL-style agent loop — model issues one tool call per turn
 * against a persistent Z3 solver, the way a programmer iterates against
 * a REPL.
 *
 * Design follows Amp's "How to Build an Agent" recipe:
 *   - tiny tool surface (7 tools), each does one thing
 *   - no workflow imposed; the model decides what to call when
 *   - terse system prompt; tool descriptions carry the weight
 *   - tool-call format is markdown-fenced JSON (kept in lieu of native
 *     tool-use because Qwen's <think> block gets suppressed by the
 *     grammar that native tool-use installs)
 */

import type { LLMClient, ChatMessage } from "../llm/types.js";
import { createIncrementalSolver } from "./solver.js";
import {
  type ReasoningStep,
  type RunResult,
  type SolutionVerification,
} from "../types.js";
import {
  validatePlanningSpec,
  generatePlanningSmt,
  PlanningSpecError,
  type PlanningSpec,
} from "./agent-planning.js";

export interface AgentRunOptions {
  config?: { maxTurns?: number };
  signal?: AbortSignal;
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
  /**
   * Each entry is a raw chunk of SMT-LIB the model added via add_smt.
   * Tracking chunks (rather than individual declarations / assertions)
   * lets the model write multi-statement chunks the way it would at a
   * REPL, without imposing a declare-vs-assert distinction.
   */
  chunks: string[];
  /** Last solver.check() result, for eval guarding. */
  lastCheckResult: "sat" | "unsat" | "unknown" | null;
  cachedModel: Record<string, string> | null;
  finalAnswer: string | null;
  turns: TurnEntry[];
  /**
   * Multi-turn conversation history (excluding the system prompt). Each
   * turn appends one assistant message (the model's response, including
   * its tool-call fence) and one user message (the tool result). This
   * lets the local LLM provider reuse its KV cache across turns —
   * `canReuseSession` checks that incoming priorHistory is a strict
   * prefix-extension of the active session's history, which only holds
   * if we send the conversation as proper alternating messages instead
   * of cramming everything into one user message per turn.
   */
  messages: ChatMessage[];
}

const SYSTEM_PROMPT = `You have an interactive Z3 SMT solver. Solve the user's problem by translating it to SMT-LIB and using the solver to verify your answer. Call \`done\` when finished.

Each turn, emit ONE tool call inside a fenced block:

\`\`\`tool-call
{"name": "<tool>", "args": {...}}
\`\`\`

Free-form prose around the fence is allowed for your reasoning; only the first fence is parsed.

## Tools

**add_smt** — \`{"code": "..."}\`. Append SMT-LIB to the solver. The code can be one or many statements; declarations and assertions are both fine. Use \`(assert (! ... :named foo))\` if you want to be able to retract this assertion by name later.

**view_smt** — show every chunk you've added so far, in the order added.

**retract** — \`{"name": "..."}\`. Remove the chunk that contains the named assertion. The solver is rebuilt from the remaining chunks.

**check_sat** — run \`(check-sat)\`. Returns \`sat\` plus the model, \`unsat\` plus the unsat core (named assertions involved), or \`unknown\`.

**eval** — \`{"expr": "..."}\`. Evaluate an expression in the current model (after a sat check_sat). Useful for sanity-checking a derived value.

**done** — \`{"answer": "..."}\`. Submit the human-readable final answer. The harness re-runs check_sat and a uniqueness probe (asserting the negation of the model in a temporary frame); the verification verdict is appended to your answer.

**give_up** — \`{"reason": "..."}\`. Stop with a stated reason.

**setup_planning** — for state-transition planning problems (find a sequence of actions transforming initial state into goal state). Provide a structured spec; the harness generates the boilerplate SMT-LIB (per-timestep variables, transition disjunctions with frame axioms, initial/goal/invariants) and pushes it to the solver. Args:

- \`horizon\`: number of transitions (K). Variables \`name_0\` … \`name_K\` are declared.
- \`state_vars\`: \`[{name, sort, domain?}]\`. Sort is \`"Int"\` or \`"Bool"\`. \`domain\` is \`[min, max]\` for Int.
- \`initial\`, \`goal\`: maps \`{var_name: value}\`.
- \`invariants\` (optional): SMT-LIB strings asserted at every timestep. Reference state vars with the suffix \`_t\` (e.g. \`"(not (= flag_t 0))"\`); the harness substitutes the concrete timestep.
- \`actions\`: \`[{name, changes, predicate}]\`. \`changes\` lists the base names of state vars this action modifies. \`predicate\` is the action's SMT-LIB precondition + change, referencing state with suffixes \`_t\` (current) and \`_tp1\` (next). Frame axioms (\`(= var_t var_tp1)\` for vars not in \`changes\`) are emitted automatically.

After the tool succeeds, run \`check_sat\`. SAT → read off the action sequence by inspecting how state changed each timestep; UNSAT → just call \`setup_planning\` again with a larger \`horizon\` (the tool auto-retracts any prior planning chunk, so calling it repeatedly with increasing K is the standard iteration pattern). UNSAT at K does not mean impossible — only that K is too small.

That's it. There's no required workflow — use the tools the way you'd use a REPL.`;

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
      parseError: "tool-call body must be a JSON object with a non-empty `name` string",
    };
  }
  const obj = parsed as { name: string; args?: unknown };
  const args =
    obj.args && typeof obj.args === "object" && !Array.isArray(obj.args)
      ? (obj.args as Record<string, unknown>)
      : {};
  return { name: obj.name, args };
}

function buildInitialUserMessage(problem: string): string {
  return `## Problem\n\n${problem}\n\nIssue your first tool call.`;
}

function truncateToolResult(result: string): string {
  return result.length > 4000
    ? result.slice(0, 4000) + `\n... [truncated]`
    : result;
}


/** Find the chunk containing a named assertion, return its index or -1. */
function findChunkByName(session: AgentSession, name: string): number {
  const re = new RegExp(`:named\\s+${escapeRegExp(name)}\\b`);
  for (let i = 0; i < session.chunks.length; i++) {
    if (re.test(session.chunks[i])) return i;
  }
  return -1;
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

async function rebuildSolver(session: AgentSession): Promise<void> {
  session.solver.dispose();
  session.solver = await createIncrementalSolver();
  for (const chunk of session.chunks) {
    session.solver.assert(chunk);
  }
  session.lastCheckResult = null;
  session.cachedModel = null;
}

async function runTool(
  session: AgentSession,
  call: ToolCall,
): Promise<{ result: string; done?: boolean; gaveUp?: boolean }> {
  const { name, args } = call;
  switch (name) {
    case "add_smt": {
      const code = typeof args.code === "string" ? args.code.trim() : "";
      if (!code) return { result: "[error] add_smt requires {code: string}" };
      try {
        session.solver.assert(code);
        session.chunks.push(code);
        session.lastCheckResult = null;
        session.cachedModel = null;
        return { result: `OK — added (${session.chunks.length} chunk(s) total).` };
      } catch (e) {
        // Solver may have applied this chunk partially before throwing.
        // Rebuild from the existing chunks so chunks ↔ solver state
        // stay in sync; the rejected code is NOT added.
        await rebuildSolver(session).catch(() => { /* best-effort */ });
        return {
          result: `[error] solver rejected the code: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "view_smt": {
      if (session.chunks.length === 0) return { result: "(empty)" };
      return { result: session.chunks.join("\n\n") };
    }

    case "retract": {
      const name = typeof args.name === "string" ? args.name.trim() : "";
      if (!name) return { result: "[error] retract requires {name: string}" };
      const idx = findChunkByName(session, name);
      if (idx < 0) {
        return { result: `[error] no chunk contains an assertion named "${name}".` };
      }
      session.chunks.splice(idx, 1);
      try {
        await rebuildSolver(session);
        return { result: `OK — retracted "${name}" and rebuilt solver (${session.chunks.length} chunk(s) remain).` };
      } catch (e) {
        return {
          result: `[error] solver rebuild failed: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "check_sat": {
      try {
        const result = await session.solver.check();
        session.lastCheckResult = result;
        session.cachedModel = null;
        if (result === "sat") {
          let model: Record<string, string>;
          try {
            model = session.solver.getModel();
          } catch {
            return { result: "sat (could not extract model)" };
          }
          session.cachedModel = model;
          const lines = Object.entries(model)
            .sort(([a], [b]) => a.localeCompare(b))
            .map(([k, v]) => `  ${k} = ${v}`);
          return {
            result: lines.length === 0
              ? "sat (model is empty — no constants declared)"
              : `sat\nmodel:\n${lines.join("\n")}`,
          };
        }
        if (result === "unsat") {
          let core: string[] = [];
          try {
            core = session.solver.unsatCore();
          } catch {
            // ignore
          }
          if (core.length === 0) {
            return { result: "unsat (no :named assertions in conflict — try adding :named labels)" };
          }
          return {
            result: `unsat\nconflicting set:\n${core.map((c) => `  - ${c}`).join("\n")}`,
          };
        }
        return { result: "unknown" };
      } catch (e) {
        // Don't let a failed check leave a stale "sat" cached result
        // that subsequent eval calls would mistakenly trust.
        session.lastCheckResult = null;
        session.cachedModel = null;
        return {
          result: `[error] check_sat failed: ${e instanceof Error ? e.message : String(e)}`,
        };
      }
    }

    case "eval": {
      const expr = typeof args.expr === "string" ? args.expr.trim() : "";
      if (!expr) return { result: "[error] eval requires {expr: string}" };
      if (session.lastCheckResult !== "sat") {
        return { result: `[error] eval needs sat (last check was ${session.lastCheckResult ?? "no check yet"}).` };
      }
      if (!session.cachedModel) {
        try {
          session.cachedModel = session.solver.getModel();
        } catch {
          return { result: "[error] could not extract a model" };
        }
      }
      const v = session.cachedModel[expr];
      if (v !== undefined) return { result: `${expr} = ${v}` };
      return {
        result: `[hint] eval looks up bare variable names in the model. Available: ${Object.keys(session.cachedModel).join(", ") || "(none)"}`,
      };
    }

    case "done": {
      // Strict: answer must be a non-empty string. Previously this
      // accepted `done({})` because JSON.stringify("") returns the
      // 2-char literal `""` which is truthy.
      if (typeof args.answer !== "string" || args.answer.trim().length === 0) {
        return { result: "[error] done requires {answer: string} — your final human-readable answer." };
      }
      session.finalAnswer = args.answer;
      return { result: "OK — finalizing.", done: true };
    }

    case "give_up": {
      const reason = typeof args.reason === "string" ? args.reason : "(no reason given)";
      session.finalAnswer = `[gave up: ${reason}]`;
      return { result: "OK — abandoning.", gaveUp: true };
    }

    case "setup_planning": {
      let spec: PlanningSpec;
      try {
        spec = validatePlanningSpec(args);
      } catch (e) {
        if (e instanceof PlanningSpecError) return { result: `[error] ${e.message}` };
        return { result: `[error] setup_planning: ${e instanceof Error ? e.message : String(e)}` };
      }
      // Auto-retract any previous planning chunk so the agent can
      // simply re-call setup_planning with a higher horizon when the
      // current K is UNSAT. Without this, repeated calls accumulate
      // contradictory horizon-K-and-horizon-K' constraints and the
      // agent has to do a manual retract dance.
      const priorPlanningIdx = session.chunks.findIndex((c) =>
        c.startsWith(";; --- PLANNING_SETUP "),
      );
      const replaced = priorPlanningIdx >= 0;
      const priorChunk = replaced ? session.chunks[priorPlanningIdx] : null;
      if (replaced) session.chunks.splice(priorPlanningIdx, 1);

      const generated = generatePlanningSmt(spec);
      try {
        if (replaced) {
          // Solver state needs to be rebuilt from remaining chunks
          // before we apply the new planning encoding.
          await rebuildSolver(session);
        }
        session.solver.assert(generated);
        session.chunks.push(generated);
        session.lastCheckResult = null;
        session.cachedModel = null;
        const lineCount = generated.split("\n").length;
        const numVars = spec.state_vars.length * (spec.horizon + 1);
        const replacedNote = replaced
          ? " (replaced prior planning chunk)"
          : "";
        return {
          result: `OK — planning skeleton generated and asserted${replacedNote} (${lineCount} lines, ${numVars} state-var instances across timesteps 0..${spec.horizon}, ${spec.actions.length} action(s) per transition). Run check_sat next.`,
        };
      } catch (e) {
        // If the new encoding was rejected, restore the prior planning
        // chunk so the agent doesn't lose work. Rebuild the solver
        // either way to ensure chunks ↔ solver state stay in sync.
        if (priorChunk !== null) {
          session.chunks.splice(priorPlanningIdx, 0, priorChunk);
        }
        await rebuildSolver(session).catch(() => { /* best-effort */ });
        const restoredNote = priorChunk !== null
          ? " (prior planning chunk restored)"
          : "";
        return {
          result: `[error] solver rejected the generated planning encoding${restoredNote}: ${e instanceof Error ? e.message : String(e)}\n\n--- generated SMT (first 1500 chars) ---\n${generated.slice(0, 1500)}`,
        };
      }
    }

    case "__parse_error__":
      return { result: `Your tool-call JSON was invalid: ${call.parseError ?? "unknown"}` };

    default:
      return {
        result: `[error] unknown tool "${name}". Valid: add_smt, view_smt, retract, check_sat, eval, setup_planning, done, give_up.`,
      };
  }
}

export async function runAgent(
  problem: string,
  llm: LLMClient,
  opts: AgentRunOptions = {},
): Promise<RunResult> {
  const maxTurns = opts.config?.maxTurns ?? 40;
  const solver = await createIncrementalSolver();
  const session: AgentSession = {
    problem,
    solver,
    chunks: [],
    lastCheckResult: null,
    cachedModel: null,
    finalAnswer: null,
    turns: [],
    messages: [
      { role: "user", content: buildInitialUserMessage(problem) },
    ],
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
      // Send system prompt + the running conversation. The local LLM
      // provider's canReuseSession check sees this as a prefix-
      // extension of the previous turn's conversation and only
      // processes the newly appended messages — KV cache stays warm
      // across the whole agent run.
      const messages: ChatMessage[] = [
        { role: "system", content: SYSTEM_PROMPT },
        ...session.messages,
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

      // Always record the assistant turn into the running conversation,
      // even if parsing the tool call fails — the model needs to see
      // its own prior output for any retry to make sense.
      session.messages.push({ role: "assistant", content: response });

      const call = parseToolCall(response);
      if (!call) {
        const noCallMsg = "Your previous response had no ```tool-call fence. Emit one tool call per turn.";
        session.messages.push({ role: "user", content: noCallMsg });
        const entry: TurnEntry = {
          turn,
          toolCall: { name: "__no_call__", args: {} },
          result: noCallMsg,
        };
        session.turns.push(entry);
        opts.onTurn?.(entry);
        continue;
      }

      const { result, done, gaveUp } = await runTool(session, call);
      session.messages.push({ role: "user", content: truncateToolResult(result) });
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
      const verification = await verifyOnDone(session);
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
    return {
      status: "failed",
      steps: turnsToSteps(session.turns),
      error: `Turn budget (${maxTurns}) exhausted without calling done() or give_up().`,
    };
  } finally {
    session.solver.dispose();
  }
}

async function verifyOnDone(
  session: AgentSession,
): Promise<SolutionVerification | undefined> {
  try {
    // Reuse the cached check_sat result if the model just ran check_sat
    // and the solver state hasn't been mutated since (any chunk change
    // resets lastCheckResult to null). Saves one Z3 round-trip per
    // typical agent run, which always ends with check_sat → done.
    let model: Record<string, string> | undefined;
    if (session.lastCheckResult === "sat" && session.cachedModel) {
      model = session.cachedModel;
    } else {
      const checkResult = await session.solver.check();
      if (checkResult !== "sat") return undefined;
      model = session.solver.getModel();
    }
    if (!model || Object.keys(model).length === 0) return undefined;

    const clauses = Object.entries(model).map(([n, v]) => `(not (= ${n} ${v}))`);
    const negation = clauses.length === 1 ? clauses[0] : `(or ${clauses.join(" ")})`;
    session.solver.push();
    try {
      session.solver.assert(negation);
      const r = await session.solver.check();
      if (r === "unsat") return { model, unique: true };
      if (r === "sat") {
        let counterExample: Record<string, string> | undefined;
        try {
          counterExample = session.solver.getModel();
        } catch {
          /* ignore */
        }
        return { model, unique: false, counterExample };
      }
      return { model, unique: false };
    } finally {
      session.solver.pop();
    }
  } catch {
    return undefined;
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
    lines.push("⚠ No Z3 verification was performed — done was called without leaving the solver in a SAT state with extractable variables.");
  }
  lines.push("");
  lines.push(`(${session.turns.length} turns)`);
  return lines.join("\n");
}
