/**
 * Tau Prolog wrapper — relational/logical reasoning layer.
 *
 * Adapted from chiasmus's prolog-solver: same callback-to-promise
 * pattern, returning structured answers. The runtime is in-process via
 * tau-prolog (pure JS — no native deps), so it can sit alongside Z3 in
 * the harness without extra setup.
 *
 * Design intent: many of the puzzles in our benchmark (knights &
 * knaves, zebra-style logic puzzles, Tower-of-Hanoi-style planning)
 * are more naturally expressed as Prolog than as SMT-LIB. The agent
 * gets a `prolog_solve` tool alongside the SMT tools and can pick the
 * right one per problem.
 *
 * Each call is one-shot: pass a complete program + a single goal,
 * receive all answers. No persistent session — keeps the API simple
 * and matches how Prolog programs are typically structured.
 */

import pl from "tau-prolog";

const MAX_ANSWERS = 1000;
const DEFAULT_MAX_INFERENCES = 200_000;

export interface PrologAnswer {
  /** Variable name → bound term (as a string). */
  bindings: Record<string, string>;
  /** tau-prolog's pretty-printed form of the substitution. */
  formatted: string;
}

export type PrologResult =
  | { status: "success"; answers: PrologAnswer[] }
  | { status: "error"; error: string };

export interface PrologInput {
  /** Prolog source: facts and rules. */
  program: string;
  /** Goal to query, e.g. `solution(X, Y, Z).` */
  query: string;
  /**
   * Override the default 200k inference budget. Raise for analyses
   * that walk large search spaces; lower for adversarial input.
   */
  maxInferences?: number;
  /**
   * Cancellation signal — checked between answer iterations. Tau
   * Prolog is single-threaded JS and can't be interrupted mid-call,
   * but we can stop enumerating between answers to be cooperative.
   */
  signal?: AbortSignal;
}

function consult(
  session: ReturnType<typeof pl.create>,
  program: string,
): Promise<void> {
  return new Promise((resolve, reject) => {
    session.consult(program, {
      success: () => resolve(),
      error: (err) => reject(err),
    });
  });
}

function query(
  session: ReturnType<typeof pl.create>,
  goal: string,
): Promise<void> {
  return new Promise((resolve, reject) => {
    session.query(goal, {
      success: () => resolve(),
      error: (err) => reject(err),
    });
  });
}

function nextAnswer(
  session: ReturnType<typeof pl.create>,
): Promise<Record<string, unknown> | null> {
  return new Promise((resolve, reject) => {
    session.answer({
      success: (ans) => resolve(ans as unknown as Record<string, unknown>),
      fail: () => resolve(null),
      error: (err) => reject(err),
      limit: () => reject(new Error("inference limit exceeded")),
    });
  });
}

function formatError(
  session: ReturnType<typeof pl.create>,
  err: unknown,
): string {
  // Tau Prolog throws Prolog *terms* (e.g. error(syntax_error(...),...))
  // on parse / type failures; format_answer prints them readably.
  // Anything else falls back to the JS error message.
  try {
    if (err && typeof err === "object" && "toString" in err) {
      const formatted = session.format_answer(err as never);
      if (formatted) return formatted;
    }
  } catch {
    /* fall through to plain stringification */
  }
  return err instanceof Error ? err.message : String(err);
}

export async function runPrologSolver(
  input: PrologInput,
): Promise<PrologResult> {
  const inferenceBudget = input.maxInferences ?? DEFAULT_MAX_INFERENCES;
  const session = pl.create(inferenceBudget);

  try {
    await consult(session, input.program);
  } catch (e: unknown) {
    return { status: "error", error: `program error: ${formatError(session, e)}` };
  }

  try {
    await query(session, input.query);
  } catch (e: unknown) {
    return { status: "error", error: `query error: ${formatError(session, e)}` };
  }

  const answers: PrologAnswer[] = [];
  try {
    for (let i = 0; i < MAX_ANSWERS; i++) {
      if (input.signal?.aborted) {
        return { status: "error", error: "aborted" };
      }
      const ans = await nextAnswer(session);
      if (ans === null) break;

      const bindings: Record<string, string> = {};
      const links = (ans as { links?: Record<string, unknown> }).links;
      if (links) {
        for (const [name, term] of Object.entries(links)) {
          const t = term as { toString?: () => string; id?: string };
          bindings[name] = t.toString?.() ?? t.id ?? String(term);
        }
      }
      // format_answer can return null/undefined on edge inputs; fall
      // back to a JSON-rendering of the bindings so the answer is
      // never opaque to the caller.
      const formatted = pl.format_answer(ans as never)
        || formatBindingsAsFallback(bindings);
      answers.push({ bindings, formatted });
    }
  } catch (e: unknown) {
    return { status: "error", error: `answer error: ${formatError(session, e)}` };
  }

  return { status: "success", answers };
}

function formatBindingsAsFallback(bindings: Record<string, string>): string {
  const entries = Object.entries(bindings);
  if (entries.length === 0) return "true";
  return entries.map(([k, v]) => `${k} = ${v}`).join(", ");
}
