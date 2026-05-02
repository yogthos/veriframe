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

describe("template registry", () => {
  it("registers both templates by name", () => {
    expect(TEMPLATES.sidon_set).toBeDefined();
    expect(TEMPLATES.no_3ap_subset).toBeDefined();
  });
});
