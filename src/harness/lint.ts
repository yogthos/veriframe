/**
 * Pre-execution linters for SMT-LIB, Lean, and Prolog snippets.
 *
 * Engines silently accept some inputs that are valid by spec but
 * functionally empty — the textbook example: an SMT-LIB body packed
 * onto one line with a mid-line `;` comment that swallows every
 * assertion. Z3 returns SAT against a constraint-free formula and
 * the harness happily reports it as "verified."
 *
 * The lints below run before each engine call. If they fire, we skip
 * the engine and return an error to the caller — better to fail
 * loudly than verify a hollow encoding.
 *
 * Common shape for each linter:
 *   { ok: boolean; warnings: string[] }
 *
 * `ok = false` means at least one warning was severe enough to block
 * execution. Callers should surface the warnings verbatim — they're
 * already phrased for an LLM-facing error.
 */

export interface LintResult {
  ok: boolean;
  warnings: string[];
}

// ---------------------------------------------------------------------
// SMT-LIB
// ---------------------------------------------------------------------

/**
 * Strip `;` line comments from SMT-LIB. SMT-LIB has only line
 * comments — everything from `;` to the next newline is ignored.
 * Block comments don't exist in standard SMT-LIB, so we don't
 * need to worry about `(*...*)` style.
 */
function stripSmtComments(s: string): string {
  return s.replace(/;[^\n]*/g, "");
}

/**
 * Count balanced-pair characters.
 */
function countParenDelta(s: string): number {
  let depth = 0;
  for (const c of s) {
    if (c === "(") depth++;
    else if (c === ")") depth--;
  }
  return depth;
}

export function lintSmt(smtlib: string): LintResult {
  const warnings: string[] = [];
  const trimmed = smtlib.trim();
  if (!trimmed) {
    warnings.push("SMT-LIB input is empty.");
    return { ok: false, warnings };
  }

  const stripped = stripSmtComments(smtlib);

  // The biggest real-world bug: a `;` mid-line ate one or more
  // assertions / declarations. Compare counts before and after
  // strip to detect it.
  const tokens = ["assert", "declare-fun", "declare-const", "declare-sort", "define-fun"];
  for (const tok of tokens) {
    const re = new RegExp(`\\(\\s*${tok}\\b`, "g");
    const before = (smtlib.match(re) ?? []).length;
    const after = (stripped.match(re) ?? []).length;
    if (before > after) {
      warnings.push(
        `${before - after} \`${tok}\` form(s) appear inside a \`;\` line comment and will be ignored by Z3. Put each statement on its own line, or end the comment with a newline before further code.`,
      );
    }
  }

  // Balanced parens after strip.
  const delta = countParenDelta(stripped);
  if (delta !== 0) {
    warnings.push(
      `Unbalanced parentheses after stripping comments (depth ${delta}). Open and close counts don't match.`,
    );
  }

  // After strip, is there anything substantive at all?
  const stripTrim = stripped.trim();
  if (!stripTrim) {
    warnings.push("All SMT-LIB content was inside comments; nothing to check.");
  } else {
    const hasAnyForm = /\(\s*(assert|declare-|define-|check-sat|set-logic|set-option)\b/.test(
      stripped,
    );
    if (!hasAnyForm) {
      warnings.push(
        "SMT-LIB body has no `(assert ...)`, `(declare-...)`, or `(check-sat)` after stripping comments. Z3 would have nothing to do.",
      );
    }
  }

  // Layer 1 — Round 2 false-positive coverage: ellipsis shorthand.
  // The model used `...` as informal shorthand for "and so on";
  // Z3 emits parse errors then SAT'd the empty surviving formula.
  // SMT-LIB has no abbreviation syntax — `...` is an error.
  if (/(?<!["])\.\.\.(?!["])/.test(stripped)) {
    warnings.push(
      "Literal `...` (ellipsis) detected outside string literals. SMT-LIB has no abbreviation syntax — every (declare-const ...), (assert ...), and (+ a_i a_j) must be spelled out explicitly. Z3 will emit parse errors and may silently SAT the empty constraint set that survives.",
    );
  }

  // Layer 1 — flag `(distinct sums)` over expressions whose summands
  // include constants that aren't themselves asserted distinct. The
  // n=500 false positive at "size 23 existential" had this shape:
  // `(distinct a1 ... a23)` was missing, so `(distinct (+ a_i a_j))`
  // was vacuously satisfiable with all-zero values. Heuristic check
  // for the smell, not a full parser.
  const distinctSumsRe = /\(\s*distinct\s+\(\s*\+\s/;
  if (distinctSumsRe.test(stripped)) {
    // We're asserting distinctness of pair-sums. Look for a sibling
    // `(distinct varname1 varname2 ...)` that constrains the
    // underlying constants — without it the witness can be all-equal
    // and the sum-distinctness becomes trivially "0 ≠ 0 ≠ 0 …" → SAT
    // with a degenerate model.
    const distinctVarsRe = /\(\s*distinct\s+(?!\(\s*\+)/;
    if (!distinctVarsRe.test(stripped)) {
      warnings.push(
        "`(distinct (+ a_i a_j) ...)` asserts pair-sum distinctness but there's no sibling `(distinct a_1 a_2 ... a_n)` constraining the underlying constants. Z3 can SAT this with a degenerate witness (e.g., all-zero values) where pair-sums collapse to a single element. Add `(assert (distinct a_1 ... a_n))` so the constants must take distinct values.",
      );
    }
  }

  // Layer 1 — suspicious forall over a small finite domain. The
  // Round-1 size-26 false positive used `(forall ((i Int) (j Int)
  // (k Int) (l Int)) (=> (and (<= 1 i) (<= i j) (<= j k) (<= k l)
  // (<= l N)) ...))` — an ordering chain that misses most pair-vs-
  // pair comparisons. forall over a small finite range is almost
  // always wrong: enumerate the pairs explicitly with `(distinct
  // ...)` instead. Heuristic: forall whose body restricts the bound
  // variables to a small range with `<=`.
  const forallSmallDomainRe = /\(\s*forall\s+\(\s*\([^)]+\s+Int\s*\)/;
  if (forallSmallDomainRe.test(stripped)) {
    // Estimate the upper bound declared inside the forall body
    // by looking for `<= var N)` patterns where N is a small
    // integer (under 100, say).
    const upperBoundMatches = stripped.match(/<=\s+\w+\s+(\d+)\s*\)/g) ?? [];
    const bounds = upperBoundMatches
      .map((m) => Number(m.match(/\d+/)?.[0] ?? "0"))
      .filter((n) => n > 0 && n < 100);
    if (bounds.length > 0) {
      warnings.push(
        `\`(forall ((i Int) ...) ...)\` with a small finite range (max bound observed: ${Math.max(...bounds)}). Universal quantification over Int is hard for Z3 to discharge correctly when the property is really "for all members of a finite set" — and ordering-chain encodings (e.g., \`i ≤ j ≤ k ≤ l\`) routinely miss most cases. Enumerate the pairs explicitly with (distinct ...) instead. The Round-1 size-26 false positive in this harness used exactly this pattern.`,
      );
    }
  }

  return { ok: warnings.length === 0, warnings };
}

// ---------------------------------------------------------------------
// Lean
// ---------------------------------------------------------------------

// Lean block comments: `/-` … `-/`. Same TypeScript-lexer concern
// as the Prolog block comments above; use RegExp constructor.
const LEAN_BLOCK_COMMENT_RE = new RegExp("/-[\\s\\S]*?-/", "g");
const LEAN_LINE_COMMENT_RE = /--[^\n]*/g;

function stripLeanComments(s: string): string {
  return s.replace(LEAN_BLOCK_COMMENT_RE, "").replace(LEAN_LINE_COMMENT_RE, "");
}

export function lintLean(snippet: string): LintResult {
  const warnings: string[] = [];
  const trimmed = snippet.trim();
  if (!trimmed) {
    warnings.push("Lean snippet is empty.");
    return { ok: false, warnings };
  }

  const stripped = stripLeanComments(snippet);

  // Same comment-eats-code check, on Lean's `theorem`/`example`/etc.
  const tokens = [
    "theorem",
    "example",
    "lemma",
    "def",
    "abbrev",
    "instance",
  ];
  for (const tok of tokens) {
    const re = new RegExp(`\\b${tok}\\b`, "g");
    const before = (snippet.match(re) ?? []).length;
    const after = (stripped.match(re) ?? []).length;
    if (before > after) {
      warnings.push(
        `${before - after} \`${tok}\` declaration(s) appear inside a \`--\` line comment or \`/-...-/\` block and will be ignored by Lean. Put each declaration on its own line.`,
      );
    }
  }

  // After strip, the snippet should contain a top-level form to
  // check (theorem, example, lemma, def, …). Tactics-only snippets
  // are not what verify_lean expects (proof_step is the right tool
  // for individual tactics).
  const stripTrim = stripped.trim();
  if (!stripTrim) {
    warnings.push("All Lean content was inside comments; nothing to check.");
  } else {
    const hasDecl =
      /\b(theorem|example|lemma|def|abbrev|instance|structure|class|inductive)\b/.test(
        stripped,
      );
    if (!hasDecl) {
      warnings.push(
        "Lean snippet has no `theorem` / `example` / `lemma` / `def` declaration after stripping comments. verify_lean expects a complete declaration; for individual tactics use `proof_step`.",
      );
    }
    // Reject `sorry` and `admit` placeholders. Lean accepts both
    // with only a warning (not an error), so without this check the
    // harness would record "claimStatus: confirmed" for snippets
    // whose proofs aren't actually closed. Observed in the Frankl
    // run: a `two_element_set_lemma` artifact with two `sorry`
    // statements got marked confirmed despite proving nothing.
    //
    // We scan word-boundary so identifiers like `mySorry` don't
    // false-trigger.
    const sorryRe = /\b(sorry|admit)\b/;
    if (sorryRe.test(stripped)) {
      warnings.push(
        "Snippet contains `sorry` or `admit` — these are placeholder tactics that compile but do NOT prove anything (Lean only emits a warning). Replace them with real tactics, or split the work: `lean_define` adds the goal as an axiom you can use elsewhere, or `proof_start` lets you develop the closed proof step by step.",
      );
    }
  }

  return { ok: warnings.length === 0, warnings };
}

// ---------------------------------------------------------------------
// Prolog
// ---------------------------------------------------------------------

// Prolog block comments: `/*` … `*/`. The literal regex form trips
// up the TypeScript lexer (it sees `/\*` and treats it as a comment
// open), so we build via RegExp constructor.
const PROLOG_BLOCK_COMMENT_RE = new RegExp("/\\*[\\s\\S]*?\\*/", "g");
const PROLOG_LINE_COMMENT_RE = /%[^\n]*/g;

function stripPrologComments(s: string): string {
  return s.replace(PROLOG_BLOCK_COMMENT_RE, "").replace(PROLOG_LINE_COMMENT_RE, "");
}

/**
 * Lint a Prolog program (set of facts/rules). Used by add_rule and
 * the implicit program of runPrologSolver.
 */
export function lintPrologProgram(code: string): LintResult {
  const warnings: string[] = [];
  const trimmed = code.trim();
  if (!trimmed) {
    warnings.push("Prolog program is empty.");
    return { ok: false, warnings };
  }

  const stripped = stripPrologComments(code);
  const stripTrim = stripped.trim();
  if (!stripTrim) {
    warnings.push("All Prolog content was inside comments; nothing to load.");
    return { ok: false, warnings };
  }

  // Heuristic: a Prolog program should end its statements with `.`.
  // Strings and numbers complicate it; keep the check simple — just
  // verify the stripped program contains at least one period.
  if (!/\./.test(stripTrim)) {
    warnings.push(
      "Prolog program contains no `.` clause terminators. Each fact / rule / directive must end with a period.",
    );
  }

  return { ok: warnings.length === 0, warnings };
}

/**
 * Lint a Prolog query. The query is a single goal (no leading `?-`
 * or trailing `.` required — the harness strips both before passing
 * to SWI's stock query API).
 */
export function lintPrologQuery(goal: string): LintResult {
  const warnings: string[] = [];
  const trimmed = goal.trim();
  if (!trimmed) {
    warnings.push("Prolog query is empty.");
    return { ok: false, warnings };
  }
  // Drop a `?-` prefix and trailing period if present (matches
  // normalizeQuery's behaviour).
  let cleaned = trimmed;
  if (cleaned.startsWith("?-")) cleaned = cleaned.slice(2).trim();
  if (cleaned.endsWith(".")) cleaned = cleaned.slice(0, -1).trim();
  if (!cleaned) {
    warnings.push("Prolog query is empty after stripping `?-` / trailing dot.");
    return { ok: false, warnings };
  }
  // Reject query bodies that are entirely a comment.
  const stripped = stripPrologComments(cleaned).trim();
  if (!stripped) {
    warnings.push("Prolog query body is entirely commented out.");
    return { ok: false, warnings };
  }
  return { ok: warnings.length === 0, warnings };
}
