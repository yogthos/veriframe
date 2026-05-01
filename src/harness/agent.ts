/**
 * REPL-style agent loop with a persistent SWI-Prolog session.
 *
 * The agent solves problems intuition-first: it reasons step by step
 * in natural language, forms a hypothesis at each step, then verifies
 * the hypothesis against the puzzle rules using Prolog. Verified
 * facts get asserted into the session for later steps to build on.
 * This matches the way a human solves a logic puzzle (or writes a
 * proof) with the help of a checker.
 *
 * Tools the model sees:
 *   - prolog_assert(code): add facts/rules to the session
 *   - prolog_query(goal): run a verification query
 *   - done(answer): submit
 *   - give_up(reason): bail
 *
 * Design rationale: published research (ZebraLogic, the 2025
 * "Training LMs to Use Prolog" paper) shows base LLMs struggle to
 * write whole correct Prolog programs in one shot. Splitting the
 * generation into small, separately-verifiable assertions fits the
 * "generate-then-validate" pattern the literature endorses, and lets
 * the model use its strong natural-language reasoning to drive the
 * structure while Prolog acts as the proof oracle.
 */

import type { LLMClient, ChatMessage } from "../llm/types.js";
import { type ReasoningStep, type RunResult } from "../types.js";
import { createSession, type PrologSession } from "./prolog.js";
import { runSmt } from "./smt.js";

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

type VerifyOutcome = "verified" | "not_verified" | "error";

interface VerifyEvent {
  claim: string;
  outcome: VerifyOutcome;
}

interface AgentSession {
  problem: string;
  finalAnswer: string | null;
  turns: TurnEntry[];
  prolog: PrologSession;
  /** Number of bytes asserted so far — surfaced to the model so it
   *  can sense when the session has accumulated a lot of state. */
  assertedBytes: number;
  /**
   * Recent verify history. Used to detect "stuck on a step" patterns
   * — three failed/repeated verifications in a row triggers a hint
   *  appended to the next tool result. Capped at the last 8 entries.
   */
  verifyHistory: VerifyEvent[];
  /** Marks turns where we already injected the hint, so we don't
   *  spam it every turn while the model recovers. */
  hintCooldownTurns: number;
  messages: ChatMessage[];
}

const SYSTEM_PROMPT = `You're solving a problem the way a mathematician proves a theorem: by reasoning out the next step in natural language, then verifying that one step with Prolog. **You are not writing a Prolog solver.** You are deriving the answer step by step, with Prolog as the proof checker for each derivation.

Each turn, emit ONE tool call inside a fenced block:

\`\`\`tool-call
{"name": "<tool>", "args": {...}}
\`\`\`

Free-form prose around the fence is allowed for your reasoning; only the first fence is parsed.

## The flow — every turn answers two questions

1. **What is the next step I need to prove?** State it in one sentence in your prose. Examples: "House 1 is yellow." "If A is a knight, then B is a knight." "Move D1 from peg A to peg B is legal in the initial state."

2. **What's the smallest Prolog check that confirms this step?** Write it as the \`check\` field of \`verify\`. The Prolog runs against rules you've added so far + library(lists) + library(clpfd).

If verification PASSES, the step is proved; the next turn picks up from there. If it FAILS, your claim was wrong or your encoding incomplete — rethink the *step* (not the encoding) before trying a different formulation.

## Tools

**add_rule** — \`{"name"?: string, "code": "..."}\`. Append Prolog rules/facts to the persistent session.
- **With \`name\`** (recommended for new rules): the rule is **tentative** — you can \`retract_rule({"name": ...})\` it if it turns out to be wrong, or \`commit({"name": ...})\` to lock it in once you're confident. This is your safety net against rule conflicts.
- **Without \`name\`**: anonymous and permanent. Use only for things you're certain about (e.g., the puzzle's literal clues).

**Keep each \`add_rule\` small** — one or a few related clauses. Don't write a full solver. Name your rules so you have an undo button.

**retract_rule** — \`{"name": "..."}\`. Undo a previously-named tentative rule. Errors if the name doesn't exist or has been committed.

**commit** — \`{"name": "..."}\`. Lock in a named tentative rule — it can no longer be retracted. Use after you've verified the rule is sound and want to build on it without risk of accidentally undoing it.

**verify** — \`{"claim": "...", "check": "..."}\`. Both fields required. \`claim\` is your one-sentence natural-language statement of what you're proving this turn. \`check\` is the Prolog goal that succeeds iff the claim holds. The harness reports VERIFIED (claim supported, with bindings) or NOT VERIFIED (no answers — rethink).

**verify_smt** — \`{"claim": "...", "smtlib": "..."}\`. Same shape as \`verify\` but the check is SMT-LIB code, executed by Z3 4.15. Use this when Prolog/CLP(FD) isn't enough: real arithmetic, nonlinear arithmetic, large bitvectors, theory combinations, optimisation. The harness reports SAT / UNSAT / UNKNOWN — read the description below for proof semantics. Don't include \`(check-sat)\`; the harness appends it.

**assume** — \`{"name": "...", "fact": "..."}\`. Open a *named hypothetical scope*. \`fact\` is Prolog code asserted while the scope is open. Anything you \`verify\` afterward is conditional on this assumption. Used for "assume A, derive B; therefore A → B" reasoning.

**discharge** — \`{"name": "..."}\`. Close a previously-opened scope, retracting its assumption. Whatever you proved while it was open now stands as a conditional ("under hypothesis X, conclusion Y holds").

**done** — \`{"answer": "..."}\`. Submit the human-readable final answer once you've derived it.

**give_up** — \`{"reason": "..."}\`. Stop with a stated reason.

## Anti-patterns — read carefully

- **Don't write the whole puzzle solver as one giant \`add_rule\`.** That's the failure mode this harness is designed against. If you find yourself writing 30+ lines in one assert, you're not doing TDD — you're back to one-shot solving.
- **Don't \`verify\` a goal you wrote without first writing a one-sentence claim.** The whole point is to commit to the *idea* before writing the *check*. Skipping the claim collapses back into "write Prolog and hope."
- **Don't use \`write\`, \`format\`, \`nl\`, or other side-effects in queries.** The harness reads variable bindings; stdout is invisible.
- **Don't run unbounded search.** Each query has a 50,000,000-inference budget (~5-15s). If you hit it, narrow the goal — don't widen it.

## Output format

\`verify\` returns one of:

\`\`\`
VERIFIED — 3 answer(s):
  [1] X = a
  [2] X = b
  [3] X = c
\`\`\`

or

\`\`\`
NOT VERIFIED — 0 answers (the check failed under your current rules).
\`\`\`

or

\`\`\`
[error] query exceeded inference limit ...
\`\`\`

NOT VERIFIED is a green light to **rethink the step**. Re-examine your reasoning, not your code. Maybe the claim was wrong, or you're missing a rule you haven't encoded yet.

\`verify_smt\` returns SAT, UNSAT, or UNKNOWN. **Pick your encoding to match the question:**

- To prove "P holds for all values," assert \`(not P)\` and look for **UNSAT** (no counter-example exists).
- To prove "there exists x such that P(x)," assert \`P(x)\` and look for **SAT** (a witness exists).
- UNKNOWN means Z3 couldn't decide — try simpler arithmetic or a different encoding.

## When to use \`assume\` / \`discharge\`

Use these when the claim is conditional ("if A, then B"). The pattern is:

1. \`assume\` the antecedent into a named scope.
2. \`verify\` the consequent (it can use the assumption as a premise).
3. \`discharge\` the scope. The proven step now reads "under assumption A, B holds."

Without \`assume\`/\`discharge\`, you can't cleanly prove implications — every \`verify\` is in the *current* (unconditional) context, so a hypothetical fact would either pollute later steps (if asserted via \`add_rule\`) or be unavailable (if not asserted at all).

## Preloaded libraries

- \`library(lists)\`: \`member/2\`, \`append/3\`, \`length/2\`, \`reverse/2\`, \`permutation/2\`, \`maplist/2..8\`, \`select/3\`, \`nth0/3\`, \`nth1/3\`, \`last/2\`, \`sum_list/2\`, \`max_list/2\`, \`min_list/2\`, \`include/3\`, \`exclude/3\`, \`foldl/4..7\`, \`msort/2\`.
- \`library(clpfd)\`: \`#=\`, \`#\\=\`, \`#<\`, \`#>\`, \`#=<\`, \`#>=\`, \`ins\`, \`in\`, \`all_distinct\`, \`all_different\`, \`label\`, \`labeling\`, \`#==>\`, \`#<==>\`.

\`format/2\`, \`format/3\` are SWI built-ins.

## Worked example — a small deduction

Problem: "Among A, B, C, exactly one is a liar. A says B is the liar. If A is the liar, what does that mean for B?"

**Turn 1** — set up the basic types:

prose: "I'll encode the type domain so I can reason about who's a liar."

\`\`\`tool-call
{"name": "add_rule", "args": {"code": "type(liar). type(truth_teller)."}}
\`\`\`

**Turn 2** — first deductive step. Claim: "A liar's statement is false."

prose: "By definition, a liar lies. A says 'B is the liar.' If A is the liar, A's statement is false, so B is *not* the liar. Let me check this with a small predicate."

\`\`\`tool-call
{"name": "verify", "args": {
  "claim": "If A is the liar, then B is not the liar.",
  "check": "(member(A, [liar, truth_teller]), member(B, [liar, truth_teller]), A = liar, (A = liar -> \\\\+ B = liar ; B = liar))"
}}
\`\`\`

The harness reports VERIFIED with A=liar, B=truth_teller (or similar). Now I have a building block.

**Turn 3** — combine with the "exactly one liar" constraint:

prose: "Since exactly one of {A, B, C} is a liar, and A=liar implies B is a truth-teller, then C must also be a truth-teller. Let me check."

\`\`\`tool-call
{"name": "verify", "args": {
  "claim": "If A is the liar, both B and C are truth-tellers.",
  "check": "..."
}}
\`\`\`

…and so on. Each step is one sentence + one small check. Build incrementally.

That's it — claim, check, accept, next. The flow is the proof.`;

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

function formatVerifyResult(
  claim: string,
  answers: { formatted: string }[],
): string {
  const header = `claim: "${claim}"`;
  if (answers.length === 0) {
    return `${header}\nNOT VERIFIED — 0 answers under the current rules. Rethink the *step*: was the claim wrong, or are you missing a rule?`;
  }
  const SOFT_BUDGET = 3500;
  const MIN_ANSWERS = 5;
  const lines: string[] = [header, `VERIFIED — ${answers.length} answer(s):`];
  let usedChars = lines[0].length + lines[1].length;
  let shown = 0;
  for (let i = 0; i < answers.length; i++) {
    const line = `  [${i + 1}] ${answers[i].formatted}`;
    if (shown >= MIN_ANSWERS && usedChars + line.length > SOFT_BUDGET) break;
    lines.push(line);
    usedChars += line.length + 1;
    shown++;
  }
  if (shown < answers.length) {
    lines.push(`  ... (${answers.length - shown} more not shown)`);
  }
  return lines.join("\n");
}

function recordVerify(
  session: AgentSession,
  claim: string,
  outcome: VerifyOutcome,
): void {
  session.verifyHistory.push({ claim, outcome });
  if (session.verifyHistory.length > 8) {
    session.verifyHistory.splice(0, session.verifyHistory.length - 8);
  }
}

function isSimilarClaim(a: string, b: string): boolean {
  const ax = a.toLowerCase().replace(/[^a-z0-9 ]/g, "").trim();
  const bx = b.toLowerCase().replace(/[^a-z0-9 ]/g, "").trim();
  if (!ax || !bx) return false;
  if (ax === bx) return true;
  // Prefix overlap of >= 30 chars indicates the same step framing.
  const minLen = Math.min(ax.length, bx.length);
  if (minLen < 30) return ax === bx;
  return ax.slice(0, 30) === bx.slice(0, 30);
}

const STUCK_HINT = `\n\n⚠ Heads up: the last 3 verifications haven't moved you forward — they failed, errored, or restated a similar claim. Step back before writing more Prolog:\n  • Is the *claim* too ambitious for one step? Decompose it (one-fact-per-claim).\n  • Did a recent rule break things? \`retract_rule({"name": "..."})\` to undo a tentative \`add_rule\` you named.\n  • Is the encoding shape wrong? Form a different hypothesis instead of patching this one.`;

function checkStuckHint(session: AgentSession): string {
  if (session.hintCooldownTurns > 0) return "";
  const recent = session.verifyHistory.slice(-3);
  if (recent.length < 3) return "";
  // Pattern 1: three non-passing in a row.
  const allFailed = recent.every(
    (e) => e.outcome === "not_verified" || e.outcome === "error",
  );
  // Pattern 2: three verifies on similar claims (regardless of outcome) —
  // model is grinding the same step.
  const allSimilar =
    isSimilarClaim(recent[0].claim, recent[1].claim) &&
    isSimilarClaim(recent[1].claim, recent[2].claim);
  if (allFailed || allSimilar) {
    session.hintCooldownTurns = 4; // skip next 4 verifies before re-hinting
    return STUCK_HINT;
  }
  return "";
}

function formatSmtResult(claim: string, verdict: "sat" | "unsat" | "unknown"): string {
  const header = `claim: "${claim}"`;
  // SMT semantics differ from Prolog. We report the raw verdict and
  // remind the model what each one means so it doesn't misread.
  switch (verdict) {
    case "unsat":
      return `${header}\nUNSAT — the formula is unsatisfiable. If you encoded \`(assert (not P))\`, this proves P holds for all values (claim VERIFIED). If you encoded \`(assert P)\` directly, UNSAT means P is impossible (claim DISPROVED).`;
    case "sat":
      return `${header}\nSAT — the formula is satisfiable. Means a model exists. If you encoded \`(assert (not P))\`, SAT shows P is NOT universally true (counter-example exists). If you encoded \`(assert P)\` directly, SAT confirms P is achievable.`;
    case "unknown":
      return `${header}\nUNKNOWN — Z3 couldn't decide. The formula may be undecidable in this fragment (e.g., nonlinear arithmetic over reals) or hit an internal limit. Try a stronger encoding or simpler claim.`;
  }
}

async function runTool(
  session: AgentSession,
  call: ToolCall,
  signal?: AbortSignal,
): Promise<{ result: string; done?: boolean; gaveUp?: boolean }> {
  const { name, args } = call;
  switch (name) {
    case "add_rule": {
      const code = typeof args.code === "string" ? args.code.trim() : "";
      const name =
        typeof args.name === "string" ? args.name.trim() : undefined;
      if (!code) {
        return { result: "[error] add_rule requires {code: string, name?: string}" };
      }
      const r = name
        ? await session.prolog.addNamed(name, code)
        : await session.prolog.assert(code);
      if (r.status === "error") {
        return { result: `[add_rule error] ${r.error}` };
      }
      session.assertedBytes += code.length;
      const named = name
        ? ` as "${name}" (tentative — retract or commit later)`
        : " (anonymous, permanent)";
      return {
        result: `OK — rule added${named} (${code.length} chars; ${session.assertedBytes} total in session).`,
      };
    }

    case "retract_rule": {
      const name = typeof args.name === "string" ? args.name.trim() : "";
      if (!name) {
        return {
          result: "[error] retract_rule requires {name: string}",
        };
      }
      const r = await session.prolog.retract(name);
      if (r.status === "error") {
        return { result: `[retract_rule error] ${r.error}` };
      }
      return { result: `OK — "${name}" retracted; its rules no longer apply.` };
    }

    case "commit": {
      const name = typeof args.name === "string" ? args.name.trim() : "";
      if (!name) {
        return { result: "[error] commit requires {name: string}" };
      }
      const r = await session.prolog.commit(name);
      if (r.status === "error") {
        return { result: `[commit error] ${r.error}` };
      }
      return {
        result: `OK — "${name}" committed; rules now permanent and cannot be retracted.`,
      };
    }

    case "verify": {
      const claim = typeof args.claim === "string" ? args.claim.trim() : "";
      const check = typeof args.check === "string" ? args.check.trim() : "";
      if (!claim) {
        return {
          result:
            '[error] verify requires {claim: string, check: string}. The `claim` is your one-sentence natural-language statement of what you are proving this turn — commit to it before writing the Prolog check.',
        };
      }
      if (!check) {
        return {
          result:
            "[error] verify requires {claim: string, check: string}. The `check` is the Prolog goal that succeeds iff the claim holds.",
        };
      }
      const r = await session.prolog.query(check, signal);
      if (r.status === "error") {
        recordVerify(session, claim, "error");
        const hint = checkStuckHint(session);
        return { result: `claim: "${claim}"\n[verify error] ${r.error}${hint}` };
      }
      const outcome: VerifyOutcome = r.answers.length > 0 ? "verified" : "not_verified";
      recordVerify(session, claim, outcome);
      const hint = checkStuckHint(session);
      return { result: formatVerifyResult(claim, r.answers) + hint };
    }

    case "verify_smt": {
      const claim = typeof args.claim === "string" ? args.claim.trim() : "";
      const smtlib =
        typeof args.smtlib === "string" ? args.smtlib.trim() : "";
      if (!claim) {
        return {
          result:
            "[error] verify_smt requires {claim: string, smtlib: string}. State the one-sentence claim before writing SMT.",
        };
      }
      if (!smtlib) {
        return {
          result:
            "[error] verify_smt requires {claim: string, smtlib: string}. The `smtlib` is the SMT-LIB code; the harness appends `(check-sat)` if you don't.",
        };
      }
      const r = runSmt(smtlib);
      if (r.status === "error") {
        recordVerify(session, claim, "error");
        const hint = checkStuckHint(session);
        return {
          result: `claim: "${claim}"\n[verify_smt error] ${r.error}${hint}`,
        };
      }
      // Treat sat/unsat as "the engine answered" — interpretation is the
      // model's job. Only `unknown` and errors count as not-progressing.
      const outcome: VerifyOutcome = r.verdict === "unknown" ? "not_verified" : "verified";
      recordVerify(session, claim, outcome);
      const hint = checkStuckHint(session);
      return { result: formatSmtResult(claim, r.verdict) + hint };
    }

    case "assume": {
      const name = typeof args.name === "string" ? args.name.trim() : "";
      const fact = typeof args.fact === "string" ? args.fact.trim() : "";
      if (!name) {
        return {
          result:
            "[error] assume requires {name: string, fact: string}. `name` identifies this hypothetical scope so you can `discharge` it later.",
        };
      }
      if (!fact) {
        return {
          result:
            "[error] assume requires {name: string, fact: string}. `fact` is Prolog code (asserted while this scope is open).",
        };
      }
      // Assume uses the same named-scope mechanism as a tentative add_rule.
      const r = await session.prolog.addNamed(name, fact);
      if (r.status === "error") {
        return { result: `[assume error] ${r.error}` };
      }
      return {
        result: `OK — assumption "${name}" introduced. Verify under this assumption, then call \`discharge\` with the same name to close the scope.`,
      };
    }

    case "discharge": {
      const name = typeof args.name === "string" ? args.name.trim() : "";
      if (!name) {
        return {
          result:
            "[error] discharge requires {name: string} naming the assumption frame to close.",
        };
      }
      const r = await session.prolog.retract(name);
      if (r.status === "error") {
        return { result: `[discharge error] ${r.error}` };
      }
      return {
        result: `OK — assumption "${name}" discharged. Anything you proved while it was active now stands as a conditional ("if ${name}-fact, then …").`,
      };
    }

    case "done": {
      if (typeof args.answer !== "string" || args.answer.trim().length === 0) {
        return {
          result:
            "[error] done requires {answer: string} — your final human-readable answer.",
        };
      }
      session.finalAnswer = args.answer;
      return { result: "OK — finalizing.", done: true };
    }

    case "give_up": {
      const reason =
        typeof args.reason === "string" ? args.reason : "(no reason given)";
      session.finalAnswer = `[gave up: ${reason}]`;
      return { result: "OK — abandoning.", gaveUp: true };
    }

    case "__parse_error__":
      return {
        result: `Your tool-call JSON was invalid: ${call.parseError ?? "unknown"}`,
      };

    default:
      return {
        result: `[error] unknown tool "${name}". Valid: add_rule, retract_rule, commit, verify, verify_smt, assume, discharge, done, give_up.`,
      };
  }
}

export async function runAgent(
  problem: string,
  llm: LLMClient,
  opts: AgentRunOptions = {},
): Promise<RunResult> {
  const maxTurns = opts.config?.maxTurns ?? 40;
  const prolog = await createSession();
  const session: AgentSession = {
    problem,
    finalAnswer: null,
    turns: [],
    prolog,
    assertedBytes: 0,
    verifyHistory: [],
    hintCooldownTurns: 0,
    messages: [{ role: "user", content: buildInitialUserMessage(problem) }],
  };

  let turn = 0;
  let outcome: "done" | "gave_up" | "exhausted" = "exhausted";

  try {
    while (turn < maxTurns) {
      turn++;
      if (session.hintCooldownTurns > 0) session.hintCooldownTurns--;
      if (opts.signal?.aborted) {
        outcome = "gave_up";
        session.finalAnswer = "[aborted]";
        break;
      }
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

      session.messages.push({ role: "assistant", content: response });

      const call = parseToolCall(response);
      if (!call) {
        const noCallMsg =
          "Your previous response had no ```tool-call fence. Emit one tool call per turn.";
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

      const { result, done, gaveUp } = await runTool(session, call, opts.signal);
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
  } finally {
    await session.prolog.dispose();
  }

  if (outcome === "done") {
    return {
      status: "completed",
      steps: turnsToSteps(session.turns),
      finalAnswer: renderFinalAnswer(session),
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

function renderFinalAnswer(session: AgentSession): string {
  const lines: string[] = [];
  lines.push("Final answer (model-claimed):");
  lines.push(session.finalAnswer ?? "(none)");
  lines.push("");
  lines.push(`(${session.turns.length} turns)`);
  return lines.join("\n");
}
