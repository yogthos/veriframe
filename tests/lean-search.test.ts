/**
 * Tests for the Mathlib premise-retrieval index.
 *
 * Index build is the slow part (1-2s scanning + ~200ms cache write).
 * Subsequent searches are <100ms. We deliberately don't preload an
 * index here — vitest will trigger getIndex() lazily in the first
 * test, and subsequent tests reuse the in-memory index.
 */

import { describe, it, expect } from "vitest";
import {
  searchLemmas,
  getIndex,
  extractSearchHints,
} from "../src/harness/lean-search.js";

describe("lean-search index", () => {
  it("indexes a non-trivial number of declarations", async () => {
    const idx = await getIndex();
    // Mathlib v4.29 has well over 100k named decls; 50k is a
    // generous lower bound that catches "we missed huge swaths".
    expect(idx.length).toBeGreaterThan(50_000);
  }, 60_000);

  it("finds add_comm by exact name", async () => {
    const r = await searchLemmas("add_comm", 5);
    expect(r.length).toBeGreaterThan(0);
    expect(r.some((h) => h.lemma.name === "add_comm")).toBe(true);
  });

  it("finds Real.sqrt-related lemmas with phrase query", async () => {
    const r = await searchLemmas("Real sqrt non-negative", 10);
    expect(r.length).toBeGreaterThan(0);
    // We expect *something* sqrt-related in the top-10 — the index
    // contains multiple sqrt_nonneg theorems from different rings.
    const sqrtHits = r.filter((h) =>
      h.lemma.name.toLowerCase().includes("sqrt"),
    );
    expect(sqrtHits.length).toBeGreaterThan(0);
  });

  it("finds geom_mean_le_arith_mean (AM-GM family)", async () => {
    const r = await searchLemmas("geom_mean_le_arith_mean", 5);
    // After namespace tracking, names are qualified (e.g.
    // `Real.geom_mean_le_arith_mean`). Match the suffix.
    expect(r.some((h) =>
      /(?:^|\.)geom_mean_le_arith_mean/.test(h.lemma.name),
    )).toBe(true);
  });

  it("returns empty results for a query that matches nothing", async () => {
    // Use truly opaque tokens — short English words like "no" or
    // "the" can sneak into Mathlib names ("exists_accPt_of_noAtoms"
    // tokenises to include "no").
    const r = await searchLemmas("qqqzz xxxyyy aaabbb", 5);
    expect(r).toHaveLength(0);
  });

  it("caps results at top_k", async () => {
    const r = await searchLemmas("add", 3);
    expect(r.length).toBeLessThanOrEqual(3);
  });

  it("captures the source-file path on each hit", async () => {
    const r = await searchLemmas("add_comm", 3);
    expect(r[0].lemma.file).toMatch(/\.lean$/);
    expect(r[0].lemma.line).toBeGreaterThan(0);
  });

  it("tracks namespace prefixes — Real.sqrt_nonneg is qualified", async () => {
    // Round 1 of Phase 2 review: the indexer must emit
    // `Real.sqrt_nonneg` (not bare `sqrt_nonneg`) for the lemma in
    // Mathlib/Data/Real/Sqrt.lean's `namespace Real` block. This
    // test pops the moment we lose namespace tracking.
    const idx = await getIndex();
    const realSqrtNonneg = idx.find(
      (l) => l.name === "Real.sqrt_nonneg" && l.file.includes("Real/Sqrt"),
    );
    expect(realSqrtNonneg).toBeDefined();
  });

  it("searches by qualified name", async () => {
    const r = await searchLemmas("Real.sqrt_nonneg", 5);
    // Must return the qualified Real one near the top.
    const top = r.slice(0, 3).map((h) => h.lemma.name);
    expect(top).toContain("Real.sqrt_nonneg");
  });
});

describe("extractSearchHints", () => {
  it("extracts the goal expression from an unsolved-goals diagnostic", () => {
    const hints = extractSearchHints([
      {
        severity: "error",
        kind: "Tactic.unsolvedGoals",
        message: "unsolved goals\n⊢ 4 * x * y ≤ (x + y) ^ 2",
      },
    ]);
    expect(hints).toEqual([
      { source: "goal", query: "4 * x * y ≤ (x + y) ^ 2" },
    ]);
  });

  it("extracts an unknown identifier name", () => {
    const hints = extractSearchHints([
      {
        severity: "error",
        kind: "lean.unknownIdentifier._namedError",
        message: "Unknown identifier `Real.sqrt_does_not_exist`",
      },
    ]);
    expect(hints).toEqual([
      { source: "unknown_identifier", query: "Real.sqrt_does_not_exist" },
    ]);
  });

  it("extracts the expected type from a type-mismatch diagnostic", () => {
    const hints = extractSearchHints([
      {
        severity: "error",
        kind: "[anonymous]",
        message:
          "Type mismatch\n  h\nhas type\n  0 ≤ x\nbut is expected to have type\n  4 * x ≤ (x + x) ^ 2",
      },
    ]);
    expect(hints).toEqual([
      { source: "expected_type", query: "4 * x ≤ (x + x) ^ 2" },
    ]);
  });

  it("returns [] for unrelated diagnostics (e.g. pure syntax error)", () => {
    const hints = extractSearchHints([
      {
        severity: "error",
        kind: "Parser",
        message: "expected `:=`",
      },
    ]);
    expect(hints).toEqual([]);
  });

  it("dedupes identical hints across diagnostics", () => {
    const hints = extractSearchHints([
      { severity: "error", kind: "Tactic.unsolvedGoals", message: "unsolved goals\n⊢ a = b" },
      { severity: "error", kind: "Tactic.unsolvedGoals", message: "unsolved goals\n⊢ a = b" },
    ]);
    expect(hints).toHaveLength(1);
  });

  it("skips warnings and information diagnostics", () => {
    const hints = extractSearchHints([
      { severity: "warning", kind: "Tactic.unsolvedGoals", message: "unsolved goals\n⊢ x = y" },
    ]);
    expect(hints).toEqual([]);
  });
});
