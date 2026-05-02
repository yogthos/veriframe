/**
 * SMT-LIB templates for common verification problems. The model
 * supplies slot values (e.g., the candidate set elements); the
 * harness assembles the SMT-LIB from a vetted template and runs
 * BOTH the primary encoding AND an independent cross-check
 * encoding. Both must agree for the artifact to be recorded as
 * confirmed.
 *
 * Why templates: every false positive we've seen came from the
 * model writing its own SMT-LIB and getting it subtly wrong —
 * forall ordering chains that miss cases, ellipsis shorthand that
 * Z3 errors on, missing distinctness on the underlying constants.
 * Templates eliminate this entire class of bug for known problem
 * shapes by doing the assembly in vetted, tested code.
 *
 * Tradeoff: less flexibility. The model can't innovate the
 * encoding for templated problems. For genuinely novel problems
 * the model still writes its own SMT-LIB via verify_smt; templates
 * are an opt-in shortcut for known shapes.
 */

export interface SmtTemplate {
  name: string;
  description: string;
  /** Slot specs: name → human-readable purpose. The model passes
   *  values for each slot in the verify_template call. */
  slots: Record<string, string>;
  /** Assemble the primary verification SMT-LIB. */
  assemble(slots: Record<string, unknown>): string;
  /** Assemble the independent cross-check SMT-LIB. Different
   *  encoding shape (distinctness vs existence-of-collision, or
   *  similar). The two should agree on the same property. */
  assembleCrossCheck(slots: Record<string, unknown>): string;
  /** What verdict on the PRIMARY confirms the claim. */
  primaryExpectedVerdict: "sat" | "unsat";
  /** What verdict on the CROSS-CHECK confirms the claim. Usually
   *  the opposite polarity to primary — that's the point of
   *  independence. */
  crossCheckExpectedVerdict: "sat" | "unsat";
}

/**
 * Validate that the elements slot is a non-empty array of distinct
 * positive integers. Common pre-check for set-shaped templates.
 */
function validateIntegerSet(slots: Record<string, unknown>): {
  ok: boolean;
  values?: number[];
  error?: string;
} {
  const raw = slots.elements;
  if (!Array.isArray(raw) || raw.length === 0) {
    return {
      ok: false,
      error: "`elements` slot must be a non-empty array of integers",
    };
  }
  const values: number[] = [];
  for (const v of raw) {
    if (typeof v !== "number" || !Number.isInteger(v)) {
      return {
        ok: false,
        error: `every element must be an integer; got ${JSON.stringify(v)}`,
      };
    }
    values.push(v);
  }
  const unique = new Set(values);
  if (unique.size !== values.length) {
    return {
      ok: false,
      error: "`elements` contains duplicates; the set must consist of distinct integers",
    };
  }
  return { ok: true, values };
}

/**
 * Sidon-set template. Verifies that a given set S has all pairwise
 * sums (a+b for a ≤ b in S) distinct, with both encodings:
 *
 *   PRIMARY: explicit (distinct (+ a_i a_j) ...) over all pairs.
 *            SAT means all sums are distinct → S is Sidon.
 *
 *   CROSS-CHECK: existence-of-collision. Assert that there exist
 *                two distinct unordered pairs in S with equal sums.
 *                UNSAT means no such collision exists → S is Sidon.
 *
 * The two encodings have OPPOSITE polarity — that's the point. A
 * bug in one is unlikely to also appear in the other.
 *
 * Slots:
 *   - elements: number[] — the candidate set members (positive
 *     integers, distinct)
 *   - upper_bound?: number — optional max value (defaults to
 *     max(elements))
 */
export const sidonSetTemplate: SmtTemplate = {
  name: "sidon_set",
  description:
    "Verify that a candidate set S is a Sidon set (all pairwise sums distinct). Runs (distinct ...) primary AND existence-of-collision cross-check; both must agree.",
  slots: {
    elements: "Array of distinct positive integers — the candidate Sidon set.",
    upper_bound:
      "Optional integer — the universe upper bound (e.g., 500 for [1,500]). Defaults to max(elements).",
  },
  primaryExpectedVerdict: "sat",
  crossCheckExpectedVerdict: "unsat",

  assemble(slots): string {
    const v = validateIntegerSet(slots);
    if (!v.ok) throw new Error(v.error);
    const values = v.values!;
    const n = values.length;
    const lines: string[] = [];
    // Declare each element as a separate constant pinned to its
    // claimed value.
    for (let i = 0; i < n; i++) {
      lines.push(`(declare-const a${i} Int)`);
    }
    for (let i = 0; i < n; i++) {
      lines.push(`(assert (= a${i} ${values[i]}))`);
    }
    // Distinctness on the underlying constants — defends against
    // the n=500 size-23 degenerate-witness case where Z3 SAT'd
    // pair-sum distinctness with all-zero values.
    lines.push(`(assert (distinct ${values.map((_, i) => `a${i}`).join(" ")}))`);
    // Distinctness on every pair sum (i ≤ j).
    const pairs: string[] = [];
    for (let i = 0; i < n; i++) {
      for (let j = i; j < n; j++) {
        pairs.push(`(+ a${i} a${j})`);
      }
    }
    lines.push(`(assert (distinct ${pairs.join(" ")}))`);
    return lines.join("\n");
  },

  assembleCrossCheck(slots): string {
    const v = validateIntegerSet(slots);
    if (!v.ok) throw new Error(v.error);
    const values = v.values!;
    // Existence-of-collision encoding: are there two unordered
    // pairs with the same sum? UNSAT = no, so S is Sidon.
    const elemList = values.map((x) => `(= y ${x})`).join(" ");
    const inS = `(define-fun inS ((y Int)) Bool (or ${elemList}))`;
    return [
      inS,
      "(declare-const a Int) (declare-const b Int)",
      "(declare-const c Int) (declare-const d Int)",
      "(assert (inS a)) (assert (inS b)) (assert (inS c)) (assert (inS d))",
      "(assert (< a b))",
      "(assert (< c d))",
      "(assert (or (< a c) (and (= a c) (not (= b d)))))",
      "(assert (= (+ a b) (+ c d)))",
      "(check-sat)",
    ].join("\n");
  },
};

/**
 * 3-AP-free set template. Verifies a candidate subset S of [1, N]
 * contains no three-term arithmetic progression.
 *
 *   PRIMARY: existence-of-3AP via (exists a Int, d Int, ...).
 *            UNSAT means no 3-AP → property holds.
 *
 *   CROSS-CHECK: explicit enumeration. For each unordered triple
 *                (i, j, k) with i < j < k in S, assert that
 *                S[k] - S[j] != S[j] - S[i]. SAT means all triples
 *                avoid AP.
 *
 * Slots:
 *   - elements: number[] — the candidate set (positive integers)
 */
export const noThreeApTemplate: SmtTemplate = {
  name: "no_3ap_subset",
  description:
    "Verify that a candidate subset S contains no three-term arithmetic progression. Runs existence-of-3AP primary AND explicit-enumeration cross-check.",
  slots: {
    elements: "Array of distinct positive integers — the candidate set.",
  },
  primaryExpectedVerdict: "unsat",
  crossCheckExpectedVerdict: "sat",

  assemble(slots): string {
    const v = validateIntegerSet(slots);
    if (!v.ok) throw new Error(v.error);
    const values = v.values!;
    const elemList = values.map((x) => `(= y ${x})`).join(" ");
    return [
      `(define-fun inS ((y Int)) Bool (or ${elemList}))`,
      "(declare-const a Int) (declare-const d Int)",
      "(assert (> d 0))",
      "(assert (inS a))",
      "(assert (inS (+ a d)))",
      "(assert (inS (+ a (* 2 d))))",
      "(check-sat)",
    ].join("\n");
  },

  assembleCrossCheck(slots): string {
    const v = validateIntegerSet(slots);
    if (!v.ok) throw new Error(v.error);
    const values = v.values!;
    // Explicit enumeration: for every triple (i, j, k) with
    // i < j < k, S[k] - S[j] != S[j] - S[i].
    const constraints: string[] = [];
    for (let i = 0; i < values.length; i++) {
      for (let j = i + 1; j < values.length; j++) {
        for (let k = j + 1; k < values.length; k++) {
          const lhs = values[k] - values[j];
          const rhs = values[j] - values[i];
          if (lhs === rhs) {
            // The set has a 3-AP. Encode as an unsatisfiable
            // conjunction so SAT means "yes, 3-AP-free" and UNSAT
            // means "no, has a 3-AP".
            //
            // Actually for the cross-check pattern we want
            // crossCheckExpectedVerdict="sat" to mean "S is
            // 3-AP-free." If the set has a 3-AP we should emit a
            // formula that's UNSAT (showing the contradiction).
            constraints.push(
              `(assert false) ; collision found at (S[${i}]=${values[i]}, S[${j}]=${values[j]}, S[${k}]=${values[k]})`,
            );
          }
        }
      }
    }
    if (constraints.length === 0) {
      // No triples form an AP — formula is trivially SAT.
      constraints.push("(declare-const ok Bool)", "(assert ok)");
    }
    constraints.push("(check-sat)");
    return constraints.join("\n");
  },
};

export const TEMPLATES: Record<string, SmtTemplate> = {
  [sidonSetTemplate.name]: sidonSetTemplate,
  [noThreeApTemplate.name]: noThreeApTemplate,
};

export function listTemplates(): string {
  const lines: string[] = [];
  for (const t of Object.values(TEMPLATES)) {
    lines.push(`  • ${t.name} — ${t.description}`);
    for (const [slot, doc] of Object.entries(t.slots)) {
      lines.push(`      ${slot}: ${doc}`);
    }
  }
  return lines.join("\n");
}
