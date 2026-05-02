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
