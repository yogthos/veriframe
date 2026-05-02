/**
 * Tests for vetted SMT templates. Each template's primary AND
 * cross-check encodings should agree on inputs that satisfy the
 * property, and disagree (cross-check rejects) on inputs that
 * don't.
 */
import { describe, it, expect } from "vitest";
import { runSmt } from "../src/harness/smt.js";
import {
  TEMPLATES,
  sidonSetTemplate,
  noThreeApTemplate,
  capSetF3nTemplate,
} from "../src/harness/smt-templates.js";

describe("sidon_set template", () => {
  it("primary SAT + cross-check UNSAT for the Mian-Chowla 20 (genuine Sidon)", () => {
    const elements = [
      1, 2, 4, 8, 13, 21, 31, 45, 66, 81, 97, 123, 148, 182, 204, 252, 290,
      361, 401, 475,
    ];
    const primary = sidonSetTemplate.assemble({ elements });
    const cross = sidonSetTemplate.assembleCrossCheck({ elements });
    const pr = runSmt(primary);
    const cr = runSmt(cross);
    expect(pr.status).toBe("ok");
    expect(cr.status).toBe("ok");
    if (pr.status !== "ok" || cr.status !== "ok") return;
    expect(pr.verdict).toBe("sat"); // distinct sums satisfiable
    expect(cr.verdict).toBe("unsat"); // no collision exists
  });

  it("primary UNSAT + cross-check SAT for the size-26 Round-1 false positive (NOT Sidon)", () => {
    // Includes the elements that produce the 178 + 223 = 1 + 400 collision.
    const elements = [
      1, 8, 23, 47, 54, 68, 80, 92, 101, 124, 140, 156, 163, 178, 193, 198,
      209, 223, 241, 251, 269, 274, 297, 317, 322, 400,
    ];
    const primary = sidonSetTemplate.assemble({ elements });
    const cross = sidonSetTemplate.assembleCrossCheck({ elements });
    const pr = runSmt(primary);
    const cr = runSmt(cross);
    expect(pr.status).toBe("ok");
    expect(cr.status).toBe("ok");
    if (pr.status !== "ok" || cr.status !== "ok") return;
    expect(pr.verdict).toBe("unsat"); // distinct-sums not satisfiable
    expect(cr.verdict).toBe("sat"); // a collision exists
  });

  it("rejects malformed slots", () => {
    expect(() => sidonSetTemplate.assemble({ elements: [] })).toThrow();
    expect(() => sidonSetTemplate.assemble({ elements: [1, 1, 2] })).toThrow();
    expect(() => sidonSetTemplate.assemble({})).toThrow();
    // Non-integer
    expect(() => sidonSetTemplate.assemble({ elements: [1, 2.5] })).toThrow();
  });
});

describe("no_3ap_subset template", () => {
  it("primary UNSAT + cross-check SAT for a 3-AP-free set", () => {
    // {1, 2, 4, 5} — sums 3, 5, 6, 6, 7, 9 — wait 4-2 = 2, 5-4 = 1, no 3-AP.
    // Triples: (1,2,4): 4-2=2, 2-1=1 — different, no AP. (1,2,5): 5-2=3, 2-1=1.
    // (1,4,5): 5-4=1, 4-1=3. (2,4,5): 5-4=1, 4-2=2. No 3-APs.
    const elements = [1, 2, 4, 5];
    const primary = noThreeApTemplate.assemble({ elements });
    const cross = noThreeApTemplate.assembleCrossCheck({ elements });
    const pr = runSmt(primary);
    const cr = runSmt(cross);
    expect(pr.status).toBe("ok");
    expect(cr.status).toBe("ok");
    if (pr.status !== "ok" || cr.status !== "ok") return;
    expect(pr.verdict).toBe("unsat"); // no 3-AP exists
    expect(cr.verdict).toBe("sat"); // explicit enumeration: all triples avoid AP
  });

  it("primary SAT + cross-check UNSAT for a set containing a 3-AP", () => {
    // {1, 3, 5} — classic 3-AP.
    const elements = [1, 3, 5];
    const primary = noThreeApTemplate.assemble({ elements });
    const cross = noThreeApTemplate.assembleCrossCheck({ elements });
    const pr = runSmt(primary);
    const cr = runSmt(cross);
    expect(pr.status).toBe("ok");
    expect(cr.status).toBe("ok");
    if (pr.status !== "ok" || cr.status !== "ok") return;
    expect(pr.verdict).toBe("sat"); // 3-AP exists
    expect(cr.verdict).toBe("unsat"); // contradiction in the cross-check
  });
});

describe("cap_set_f3n template", () => {
  // F_3^2 has 9 elements: 0..8.
  // The maximum cap set in F_3^2 has size 4. One example:
  //   the 4 "corners" {0, 2, 6, 8} (i.e., (0,0), (2,0), (0,2), (2,2)).
  // Sum check: 0+2+6 = 8 mod 9? In F_3 addition: (0,0)+(2,0)+(0,2) = (2,2)=8. So 8 must NOT be in S.
  // {0, 2, 6, 8} contains 8, so 0+2+6 = 8 means {0,2,6,8} has a 3-AP. Not a cap set.
  // Better: {0, 1, 2, 5} — let me verify by hand. (0)+(1)+(2)=3≡0 mod 3 first component. So {0,1,2} sum to 0 → 3-AP. Bad.
  // Use a known size-4 cap: {0, 1, 4, 8} = {(0,0), (1,0), (1,1), (2,2)}.
  // Sums: 0+1+4=5 (not 0 mod 9 in F_3 sense)... let me check componentwise.
  // (0,0)+(1,0)+(1,1) = (2,1)=5 ≠ 0. (0,0)+(1,0)+(2,2)=(0,2)=6 ≠ 0.
  // (0,0)+(1,1)+(2,2)=(0,0)=0. Oh! {0, 4, 8} sums to (0,0) component-wise. So {0,4,8} is a 3-AP.
  // So {0, 1, 4, 8} contains a 3-AP. Bad.
  // Let me use a verified cap set in F_3^2: {(0,0), (1,0), (0,1), (2,1)} = {0, 1, 3, 7}.
  // Sums:
  //   0+1+3 = (0+1+0, 0+0+1) = (1,1) ≠ 0 ✓
  //   0+1+7 = (0+1+1, 0+0+2) = (2, 2) ≠ 0 ✓
  //   0+3+7 = (0+0+1, 0+1+2) = (1, 0) ≠ 0 ✓
  //   1+3+7 = (1+0+1, 0+1+2) = (2, 0) ≠ 0 ✓
  // So {0, 1, 3, 7} is a cap set of size 4 in F_3^2. ✓

  it("primary UNSAT + cross-check SAT for a known cap set in F_3^2", () => {
    const elements = [0, 1, 3, 7];
    const primary = capSetF3nTemplate.assemble({ n: 2, elements });
    const cross = capSetF3nTemplate.assembleCrossCheck({ n: 2, elements });
    const pr = runSmt(primary);
    const cr = runSmt(cross);
    expect(pr.status).toBe("ok");
    expect(cr.status).toBe("ok");
    if (pr.status !== "ok" || cr.status !== "ok") return;
    expect(pr.verdict).toBe("unsat"); // no 3-AP
    expect(cr.verdict).toBe("sat"); // explicit enum: no collision
  });

  it("primary SAT + cross-check UNSAT for a non-cap set in F_3^2", () => {
    // {0, 4, 8} = {(0,0), (1,1), (2,2)} — a 3-AP (sums to (0,0)=0).
    const elements = [0, 4, 8];
    const primary = capSetF3nTemplate.assemble({ n: 2, elements });
    const cross = capSetF3nTemplate.assembleCrossCheck({ n: 2, elements });
    const pr = runSmt(primary);
    const cr = runSmt(cross);
    expect(pr.status).toBe("ok");
    expect(cr.status).toBe("ok");
    if (pr.status !== "ok" || cr.status !== "ok") return;
    expect(pr.verdict).toBe("sat"); // 3-AP exists
    expect(cr.verdict).toBe("unsat"); // contradiction asserted
  });

  it("rejects elements outside [0, 3^n - 1]", () => {
    expect(() =>
      capSetF3nTemplate.assemble({ n: 2, elements: [0, 1, 9] }),
    ).toThrow();
  });
});

describe("template registry", () => {
  it("registers all three templates by name", () => {
    expect(TEMPLATES.sidon_set).toBeDefined();
    expect(TEMPLATES.no_3ap_subset).toBeDefined();
    expect(TEMPLATES.cap_set_f3n).toBeDefined();
  });
});
