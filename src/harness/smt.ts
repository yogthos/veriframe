/**
 * Z3 SMT verification — second backend for the agent's `verify` flow.
 *
 * The model writes SMT-LIB; the harness shells out to the system `z3`
 * binary (4.15+, installed via Homebrew) and parses sat/unsat/unknown.
 * On SAT we additionally request the witness model via `(get-model)`
 * and parse the variable assignments — when the model is asserting
 * existence ("there is a coloring such that…") the witness is the
 * constructive proof and we surface it alongside the SMT-LIB.
 *
 * We use `execSync` with stdin piping rather than the npm z3-solver
 * package, because:
 *   1. The npm package is async-only, but `prolog-wasm-full`'s foreign-
 *      predicate API is sync-only — there's no good way to bridge
 *      from inside a Prolog query. Shelling out is sync from JS's
 *      perspective and sidesteps the impedance mismatch entirely.
 *   2. Z3 4.15 has years of fixes the WASM port lags on.
 *   3. The shell-out is cheap (millisecond-range) for typical query
 *      sizes; we don't need an in-process solver for verification.
 *
 * Convention: the harness appends `(check-sat)` if the user's input
 * doesn't already contain it, and `(get-model)` after the check.
 * If the user already wrote `(get-model)` we don't duplicate.
 */

import { spawnSync } from "node:child_process";
import { lintSmt } from "./lint.js";

const Z3_BINARY = "z3";
const Z3_TIMEOUT_MS = 30_000;
const MAX_OUTPUT_BYTES = 64 * 1024;

export type SmtVerdict = "sat" | "unsat" | "unknown";

export type SmtResult =
  | {
      status: "ok";
      verdict: SmtVerdict;
      output: string;
      /** Parsed witness model (only present on SAT). Maps each
       *  declared constant to its assigned value as printed by Z3
       *  (e.g. `true`, `false`, `3`, `(- 5)`). */
      model?: Record<string, string>;
    }
  | { status: "error"; error: string };

export function runSmt(smtlib: string): SmtResult {
  // Pre-execution lint — refuse to run inputs that would silently
  // produce vacuous results (e.g., a `;` line comment swallowed all
  // the assertions). Catches the kind of encoding bugs Z3 itself
  // doesn't surface.
  const lint = lintSmt(smtlib);
  if (!lint.ok) {
    return {
      status: "error",
      error: `SMT lint rejected the input — execution skipped:\n  • ${lint.warnings.join("\n  • ")}`,
    };
  }

  const hasCheckSat = /\(\s*check-sat\s*\)/.test(smtlib);
  const hasGetModel = /\(\s*get-model\s*\)/.test(smtlib);
  let code = smtlib;
  if (!hasCheckSat) code += "\n(check-sat)";
  if (!hasGetModel) code += "\n(get-model)";
  code += "\n";

  // spawnSync (vs execSync) so a non-zero exit doesn't throw — Z3
  // returns non-zero when `(get-model)` is called after unsat, even
  // though the verdict itself was emitted cleanly. We read stdout
  // regardless and let the verdict-line scan decide.
  const proc = spawnSync(Z3_BINARY, ["-smt2", "-in"], {
    input: code,
    timeout: Z3_TIMEOUT_MS,
    maxBuffer: MAX_OUTPUT_BYTES,
    encoding: "utf8",
  });
  if (proc.error) {
    return {
      status: "error",
      error: `z3 invocation failed: ${proc.error.message}`,
    };
  }
  if (proc.signal) {
    return {
      status: "error",
      error: `z3 terminated by signal ${proc.signal} (timeout?)`,
    };
  }
  const raw = proc.stdout ?? "";
  const stderr = proc.stderr ?? "";

  const lines = raw.split("\n").map((l) => l.trim()).filter(Boolean);

  // Refuse to interpret a verdict if Z3 emitted any parse/type
  // errors. Z3 keeps running after individual `(error ...)` lines
  // and will gladly print `sat` for the empty constraint set that
  // remained — which is how the n=500 Sidon "size 23" false positive
  // happened: the model wrote `... (declare-const a23 Int)` with
  // literal ellipsis shorthand, Z3 emitted 4 parse errors, then
  // SAT'd a constraint set that was effectively empty. The harness
  // recorded the artifact as "confirmed".
  //
  // Detect Z3 errors in two places:
  //   - stdout: `(error "...")` S-expressions, or lines starting
  //     with `error:` / `unsupported:` (some Z3 builds use these).
  //   - stderr: any non-trivial content (Z3 normally writes nothing
  //     to stderr on success).
  // Whitelist benign Z3 errors that don't invalidate the verdict.
  // The most common: `(error "...model is not available...")` after
  // `(get-model)` follows an UNSAT verdict. The verdict itself was
  // emitted cleanly; only the model fetch failed.
  const isBenignError = (l: string): boolean =>
    /model is not available/i.test(l);
  const z3ErrorLines = lines.filter(
    (l) =>
      (l.startsWith("(error ") ||
        /^error\b/i.test(l) ||
        /^unsupported\b/i.test(l) ||
        l.startsWith('(error "')) &&
      !isBenignError(l),
  );
  const stderrTrim = stderr.trim();
  if (z3ErrorLines.length > 0 || stderrTrim.length > 0) {
    const summary: string[] = [];
    if (z3ErrorLines.length > 0) {
      summary.push(
        `Z3 emitted ${z3ErrorLines.length} parse/type error(s):`,
      );
      for (const e of z3ErrorLines.slice(0, 5)) summary.push(`  ${e}`);
      if (z3ErrorLines.length > 5) {
        summary.push(`  (+${z3ErrorLines.length - 5} more)`);
      }
    }
    if (stderrTrim.length > 0) {
      summary.push(`stderr: ${stderrTrim.slice(0, 500)}`);
    }
    summary.push(
      "Common cause: literal '...' ellipsis shorthand in SMT-LIB. Spell out every `(declare-const ...)` and every `(+ a_i a_j)` explicitly — Z3 has no abbreviation syntax. The harness refuses to report a verdict when Z3 emitted errors, because the verdict applies to the SUBSET of your formula that parsed, not the formula you wrote.",
    );
    return {
      status: "error",
      error: summary.join("\n"),
    };
  }

  // The verdict line is the last `sat | unsat | unknown` we emit.
  const verdictLine = [...lines]
    .reverse()
    .find((l) => l === "sat" || l === "unsat" || l === "unknown");
  if (!verdictLine) {
    return {
      status: "error",
      error: `z3 produced no verdict. Output was:\n${raw.trim()}${stderrTrim ? `\nstderr: ${stderrTrim}` : ""}`,
    };
  }
  const verdict = verdictLine as SmtVerdict;

  if (verdict !== "sat") {
    // No model on UNSAT/UNKNOWN; skip parsing.
    return { status: "ok", verdict, output: raw.trim() };
  }
  const model = parseModel(raw);

  // Layer 2 — witness sanity check. SAT with a degenerate witness
  // (e.g., all-zero values when the formula CLAIMED to assert
  // distinctness, or values outside an asserted range) is a sign
  // that the formula didn't actually constrain what the model
  // thought it did. Treat such a witness as a hard error so the
  // model gets a clear "your encoding has a bug" signal instead of
  // shipping a vacuous SAT.
  const witnessIssues = checkWitnessAgainstFormula(smtlib, model);
  if (witnessIssues.length > 0) {
    return {
      status: "error",
      error: [
        "Z3 returned SAT but the witness model is internally inconsistent with the formula's stated constraints. This means Z3 satisfied a SUBSET of what you asserted — usually because the model's values violate a (distinct ...) you wrote (the values aren't actually distinct) or fall outside a range you asserted (e.g., the witness has 0 but you required >= 1). Common cause: missing `(distinct a_1 a_2 ... a_n)` for the underlying constants when you only asserted distinctness of derived sums.",
        ...witnessIssues.map((w) => `  • ${w}`),
        `Witness: ${JSON.stringify(model)}`,
      ].join("\n"),
    };
  }
  return {
    status: "ok",
    verdict,
    output: raw.trim(),
    model,
  };
}

/**
 * Layer 2 — witness sanity check. After Z3 returns SAT with a model,
 * we re-read the (textual) SMT-LIB formula to extract the simple
 * constraints we know how to check, and validate them against the
 * concrete witness values. This catches the failure modes where Z3
 * SAT'd a formula that didn't actually capture the property the
 * model meant to assert.
 *
 * Checks:
 *   1. `(assert (distinct v1 v2 ... vn))` — verify the witness has
 *      pairwise-distinct values for v1..vn.
 *   2. `(assert (>= x N))` / `(assert (<= x N))` — verify the
 *      witness's x falls in range. Also handles the equivalent
 *      `(and (>= x 1) (<= x 500))` shape.
 *   3. `(assert (= var literal))` — verify the witness matches.
 *
 * NOT a full SMT-LIB parser. We use simple regexes over the comment-
 * stripped text. Misses anything wrapped in (let ...) / quantifiers
 * / function applications. Catches the common direct-assertion case,
 * which is what the false-positive bugs we've seen always use.
 */
export function checkWitnessAgainstFormula(
  smtlib: string,
  model: Record<string, string>,
): string[] {
  const issues: string[] = [];

  // Strip line comments, which would otherwise fool the regex below.
  const stripped = smtlib.replace(/;[^\n]*/g, "");

  // Helper: read a witness value as a number, returning NaN on
  // anything we can't interpret as a plain integer or `(- N)`.
  const numOf = (raw: string | undefined): number => {
    if (raw === undefined) return NaN;
    const s = raw.trim();
    const n = Number(s);
    if (Number.isFinite(n)) return n;
    const neg = s.match(/^\(\s*-\s+(\d+)\s*\)$/);
    if (neg) return -Number(neg[1]);
    return NaN;
  };

  // 1. (distinct v1 v2 ... vn) — only when the args are bare
  //    identifiers (not nested expressions like (+ a b)). This is
  //    the case we're trying to catch: the model wrote a distinctness
  //    constraint over identifiers and Z3 returned a witness with
  //    duplicates, which means the constraint wasn't actually
  //    asserted (e.g., it was inside a quantifier that didn't
  //    apply, or shadowed by a different scope).
  const distinctCallRe = /\(\s*distinct\s+([^()]+?)\s*\)/g;
  let m: RegExpExecArray | null;
  while ((m = distinctCallRe.exec(stripped)) !== null) {
    const vars = m[1].trim().split(/\s+/).filter(Boolean);
    if (vars.length < 2) continue;
    // Only check if every entry is a bare identifier (no parens,
    // no operators).
    if (!vars.every((v) => /^[A-Za-z_][\w-]*$/.test(v))) continue;
    const seen = new Map<string, string>();
    for (const v of vars) {
      const raw = model[v];
      if (raw === undefined) continue; // not in witness; skip
      const key = raw.trim();
      const prev = seen.get(key);
      if (prev !== undefined && prev !== v) {
        issues.push(
          `(distinct ${vars.join(" ")}) was asserted, but the witness gives ${prev}=${key} AND ${v}=${key} — the constants are NOT distinct.`,
          // Only report the first duplicate per group, otherwise
          // noise.
        );
        break;
      }
      seen.set(key, v);
    }
  }

  // 2. Range constraints `(>= x N)` / `(<= x N)`.
  // Only checks bare-identifier-on-LHS forms.
  const geRe = /\(\s*>=\s+([A-Za-z_][\w-]*)\s+(-?\d+)\s*\)/g;
  while ((m = geRe.exec(stripped)) !== null) {
    const [, name, bound] = m;
    if (model[name] === undefined) continue;
    const v = numOf(model[name]);
    const b = Number(bound);
    if (Number.isFinite(v) && v < b) {
      issues.push(
        `(>= ${name} ${bound}) was asserted, but the witness gives ${name}=${v} which is below the bound.`,
      );
    }
  }
  const leRe = /\(\s*<=\s+([A-Za-z_][\w-]*)\s+(-?\d+)\s*\)/g;
  while ((m = leRe.exec(stripped)) !== null) {
    const [, name, bound] = m;
    if (model[name] === undefined) continue;
    const v = numOf(model[name]);
    const b = Number(bound);
    if (Number.isFinite(v) && v > b) {
      issues.push(
        `(<= ${name} ${bound}) was asserted, but the witness gives ${name}=${v} which is above the bound.`,
      );
    }
  }

  // 3. `(= var literal)` — direct equality assertions. If the
  //    model violates these, something is very wrong.
  const eqRe = /\(\s*=\s+([A-Za-z_][\w-]*)\s+(-?\d+)\s*\)/g;
  while ((m = eqRe.exec(stripped)) !== null) {
    const [, name, lit] = m;
    if (model[name] === undefined) continue;
    const v = numOf(model[name]);
    const l = Number(lit);
    if (Number.isFinite(v) && v !== l) {
      issues.push(
        `(= ${name} ${lit}) was asserted, but the witness gives ${name}=${v}.`,
      );
    }
  }

  return issues;
}

/**
 * Parse Z3's `(get-model)` output into a name → value map. The
 * output format for a SAT model:
 *
 *   (
 *     (define-fun e01 () Bool true)
 *     (define-fun x () Int (- 5))
 *     ...
 *   )
 *
 * Values can contain nested parens (e.g. `(- 5)`) so we paren-balance
 * each `(define-fun ...)` block rather than regex-greedy-matching.
 * Returns an empty object if no define-funs were found (common when
 * Z3 emitted an error in lieu of a model).
 */
export function parseModel(output: string): Record<string, string> {
  const out: Record<string, string> = {};
  let i = 0;
  while (true) {
    const start = output.indexOf("(define-fun", i);
    if (start < 0) break;
    // Walk forward, paren-balanced, to the matching close.
    let depth = 0;
    let j = start;
    let foundEnd = -1;
    while (j < output.length) {
      const c = output[j];
      if (c === "(") depth++;
      else if (c === ")") {
        depth--;
        if (depth === 0) {
          foundEnd = j + 1;
          break;
        }
      }
      j++;
    }
    if (foundEnd < 0) break; // unbalanced; bail
    const block = output.slice(start, foundEnd);
    const inner = block.slice("(define-fun".length, -1).trim();
    // Match: NAME () SORT, then VALUE is the rest.
    const m = inner.match(/^([^\s()]+)\s+\(\s*\)\s+\S+\s+/);
    if (m) {
      const name = m[1];
      const value = inner.slice(m[0].length).trim();
      out[name] = value;
    }
    i = foundEnd;
  }
  return out;
}
