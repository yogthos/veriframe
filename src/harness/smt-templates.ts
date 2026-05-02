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

/**
 * Cap-set template for F_3^n. A cap set in F_3^n is a subset with
 * no three distinct elements x, y, z satisfying x + y + z = 0 in
 * F_3^n (component-wise modular sum). Equivalently: no three-term
 * arithmetic progression in the F_3 vector space.
 *
 * Element encoding: each F_3^n vector v = (v_0, v_1, …, v_{n-1})
 * is encoded as the integer V = sum_i v_i * 3^i, ranging 0 to
 * 3^n − 1. The model passes the subset as a list of these integer
 * encodings.
 *
 * Slots:
 *   - n: number — the dimension (e.g., 7 for F_3^7).
 *   - elements: number[] — the subset members as base-3 integers
 *     in [0, 3^n − 1]. Distinct.
 *
 * PRIMARY: existence-of-3AP via Z3. Declare X, Y, Z, assert each is
 *          in S, distinct, and the per-component F_3 sum is zero.
 *          UNSAT → no 3-AP exists → S is a cap set.
 *
 * CROSS-CHECK: explicit JS enumeration of pairs at assembly time.
 *              For each unordered pair (a, b) in S, compute the
 *              forced third element c = (−a − b) in F_3^n. If c is
 *              in S and c ≠ a, c ≠ b, emit `(assert false)` to make
 *              the SMT-LIB UNSAT. If no collision found, the SMT-LIB
 *              is trivially SAT.
 */
export const capSetF3nTemplate: SmtTemplate = {
  name: "cap_set_f3n",
  description:
    "Verify that a candidate subset S of F_3^n is a cap set (no 3-term AP in the F_3 vector space). Slots: {n, elements}; elements are base-3 integer encodings 0..3^n-1.",
  slots: {
    n: "Dimension of the F_3 vector space (e.g., 7 for F_3^7).",
    elements:
      "Array of distinct integers in [0, 3^n - 1], each encoding an F_3^n vector via v_0 + 3*v_1 + 9*v_2 + … + 3^{n-1}*v_{n-1}.",
  },
  primaryExpectedVerdict: "unsat",
  crossCheckExpectedVerdict: "sat",

  assemble(slots): string {
    const n = slots.n;
    if (typeof n !== "number" || !Number.isInteger(n) || n < 1) {
      throw new Error("`n` must be a positive integer (the dimension).");
    }
    const v = validateIntegerSet(slots);
    if (!v.ok) throw new Error(v.error);
    const max = 3 ** n - 1;
    for (const x of v.values!) {
      if (x < 0 || x > max) {
        throw new Error(
          `element ${x} is outside [0, ${max}] for F_3^${n}`,
        );
      }
    }
    const elements = v.values!;
    // Build the inS membership predicate.
    const memberships = elements.map((x) => `(= v ${x})`).join(" ");
    const lines: string[] = [
      `(define-fun inS ((v Int)) Bool (or ${memberships}))`,
      "(declare-const X Int)",
      "(declare-const Y Int)",
      "(declare-const Z Int)",
      "(assert (inS X)) (assert (inS Y)) (assert (inS Z))",
      "(assert (distinct X Y Z))",
    ];
    // Per-component F_3 sum constraints. For each component i in
    // [0, n), the sum of (v / 3^i) mod 3 across X, Y, Z is 0 mod 3.
    for (let i = 0; i < n; i++) {
      const pow = 3 ** i;
      lines.push(
        `(assert (= 0 (mod (+ (mod (div X ${pow}) 3) (mod (div Y ${pow}) 3) (mod (div Z ${pow}) 3)) 3)))`,
      );
    }
    lines.push("(check-sat)");
    return lines.join("\n");
  },

  assembleCrossCheck(slots): string {
    const n = slots.n;
    if (typeof n !== "number" || !Number.isInteger(n) || n < 1) {
      throw new Error("`n` must be a positive integer.");
    }
    const v = validateIntegerSet(slots);
    if (!v.ok) throw new Error(v.error);
    const elements = v.values!;
    const set = new Set(elements);
    // Helper: decompose an integer into n base-3 digits.
    const digits = (x: number): number[] => {
      const out: number[] = [];
      for (let i = 0; i < n; i++) {
        out.push(Math.floor(x / 3 ** i) % 3);
      }
      return out;
    };
    // Helper: assemble n base-3 digits into an integer.
    const fromDigits = (ds: number[]): number =>
      ds.reduce((acc, d, i) => acc + d * 3 ** i, 0);
    // For each unordered pair (a, b) in S with a < b, compute the
    // forced third c such that a + b + c = 0 in F_3^n.
    const collisions: string[] = [];
    for (let i = 0; i < elements.length; i++) {
      for (let j = i + 1; j < elements.length; j++) {
        const a = elements[i];
        const b = elements[j];
        const da = digits(a);
        const db = digits(b);
        const dc = da.map((_, k) => (3 - ((da[k] + db[k]) % 3)) % 3);
        const c = fromDigits(dc);
        if (set.has(c) && c !== a && c !== b) {
          collisions.push(
            `; collision: a=${a}=(${da.join(",")}), b=${b}=(${db.join(",")}), c=${c}=(${dc.join(",")})`,
          );
        }
      }
    }
    if (collisions.length > 0) {
      // S has a 3-AP — emit UNSAT to refute.
      return [...collisions, "(assert false)", "(check-sat)"].join("\n");
    }
    // No collisions found by enumeration — trivially SAT.
    return [
      "; cross-check enumerated all pairs; no 3-AP collision found",
      "(declare-const ok Bool)",
      "(assert ok)",
      "(check-sat)",
    ].join("\n");
  },
};

/**
 * Schur-coloring template. A k-coloring c : [1, n] → [1, k] is
 * "Schur-good" if there are no three numbers x, y, z in [1, n]
 * with x + y = z and c(x) = c(y) = c(z) (a monochromatic Schur
 * triple). The Schur number S(k) is the largest n admitting such
 * a coloring; classically S(2)=4, S(3)=13, S(4)=44, S(5)=160
 * (Heule 2017 via SAT), S(6) is OPEN.
 *
 * The model passes a candidate coloring as an array of length n,
 * with entries in [1, k]. The harness checks via two encodings:
 *
 *   PRIMARY (existence-of-bad-triple): assert ∃ x, y in [1, n]
 *     with x + y ≤ n and c(x) = c(y) = c(x + y). UNSAT means no
 *     bad triple exists → coloring is Schur-good.
 *
 *   CROSS-CHECK (explicit JS enumeration): for every (x, y) with
 *     x ≤ y and x + y ≤ n, check the triple isn't monochromatic.
 *     Any collision at assembly time → emit (assert false).
 *
 * Slots:
 *   - n: number — the upper bound of the coloring.
 *   - k: number — the number of colors.
 *   - coloring: number[] — length n, each entry in [1, k].
 *     coloring[i-1] is the color of integer i.
 */
export const schurColoringTemplate: SmtTemplate = {
  name: "schur_coloring",
  description:
    "Verify that a candidate k-coloring of [1, n] has no monochromatic Schur triple (x + y = z, all same color). Slots: {n, k, coloring}.",
  slots: {
    n: "Upper bound (the integers being coloured are 1..n).",
    k: "Number of colors (must be ≥ 1).",
    coloring:
      "Array of length n with entries in [1, k]; coloring[i-1] is the color of integer i.",
  },
  primaryExpectedVerdict: "unsat",
  crossCheckExpectedVerdict: "sat",

  assemble(slots): string {
    const n = slots.n;
    const k = slots.k;
    const coloring = slots.coloring;
    if (typeof n !== "number" || !Number.isInteger(n) || n < 1) {
      throw new Error("`n` must be a positive integer");
    }
    if (typeof k !== "number" || !Number.isInteger(k) || k < 1) {
      throw new Error("`k` must be a positive integer");
    }
    if (!Array.isArray(coloring) || coloring.length !== n) {
      throw new Error(
        `\`coloring\` must be an array of length ${n}; got ${Array.isArray(coloring) ? coloring.length : typeof coloring}`,
      );
    }
    for (let i = 0; i < n; i++) {
      const c = coloring[i];
      if (typeof c !== "number" || !Number.isInteger(c) || c < 1 || c > k) {
        throw new Error(
          `coloring[${i}] = ${JSON.stringify(c)} is not an integer in [1, ${k}]`,
        );
      }
    }
    const colors = coloring as number[];
    // Define c(i) for i in [1, n] via a chain of ites — compact for
    // moderate n. For n=160 this is fine for Z3.
    let cBody = `${colors[n - 1]}`;
    for (let i = n - 1; i >= 1; i--) {
      cBody = `(ite (= i ${i}) ${colors[i - 1]} ${cBody})`;
    }
    const lines: string[] = [
      `(define-fun c ((i Int)) Int ${cBody})`,
      "(declare-const x Int)",
      "(declare-const y Int)",
      `(assert (and (>= x 1) (<= x ${n})))`,
      `(assert (and (>= y 1) (<= y ${n})))`,
      `(assert (<= (+ x y) ${n}))`,
      "(assert (= (c x) (c y)))",
      "(assert (= (c x) (c (+ x y))))",
      "(check-sat)",
    ];
    return lines.join("\n");
  },

  assembleCrossCheck(slots): string {
    const n = slots.n;
    const coloring = slots.coloring;
    // Re-validate (called separately from assemble).
    if (typeof n !== "number" || !Number.isInteger(n) || n < 1) {
      throw new Error("`n` must be a positive integer");
    }
    if (!Array.isArray(coloring) || coloring.length !== n) {
      throw new Error("`coloring` length mismatch");
    }
    const colors = coloring as number[];
    const collisions: string[] = [];
    for (let x = 1; x <= n; x++) {
      for (let y = x; y <= n; y++) {
        const z = x + y;
        if (z > n) break;
        const cx = colors[x - 1];
        const cy = colors[y - 1];
        const cz = colors[z - 1];
        if (cx === cy && cy === cz) {
          collisions.push(
            `; collision: x=${x}, y=${y}, x+y=${z} all colored ${cx}`,
          );
        }
      }
    }
    if (collisions.length > 0) {
      return [...collisions.slice(0, 10), "(assert false)", "(check-sat)"].join(
        "\n",
      );
    }
    return [
      "; cross-check enumerated all triples; none monochromatic",
      "(declare-const ok Bool)",
      "(assert ok)",
      "(check-sat)",
    ].join("\n");
  },
};

export const TEMPLATES: Record<string, SmtTemplate> = {
  [sidonSetTemplate.name]: sidonSetTemplate,
  [noThreeApTemplate.name]: noThreeApTemplate,
  [capSetF3nTemplate.name]: capSetF3nTemplate,
  [schurColoringTemplate.name]: schurColoringTemplate,
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
