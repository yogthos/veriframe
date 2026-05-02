/**
 * Tests for the pre-execution linters. Each engine runs its lint
 * before invoking Z3 / Lean / Prolog; lint failure short-circuits
 * with an error so the engine never sees broken input.
 *
 * The motivating bug: a model emitted SMT-LIB on one line with a
 * mid-line `;` comment that swallowed every assertion. Z3 returned
 * SAT against an empty constraint set, the harness called it
 * "verified," and the user got a vacuous result.
 */

import { describe, it, expect } from "vitest";
import {
  lintSmt,
  lintLean,
  lintPrologProgram,
  lintPrologQuery,
} from "../src/harness/lint.js";

describe("lintSmt", () => {
  it("accepts a valid multi-line program", () => {
    const r = lintSmt(`
      (declare-const x Int)
      (assert (= x 1))
    `);
    expect(r.ok).toBe(true);
    expect(r.warnings).toEqual([]);
  });

  it("rejects empty input", () => {
    expect(lintSmt("").ok).toBe(false);
    expect(lintSmt("   ").ok).toBe(false);
  });

  it("catches the comment-eats-assertion bug (the motivating case)", () => {
    // Single line, mid-line `;` swallows the (assert ...).
    const r = lintSmt(
      "(declare-const x Int); pentagon(assert (= x 1))",
    );
    expect(r.ok).toBe(false);
    expect(r.warnings.join(" ")).toMatch(/assert.*line comment/);
  });

  it("catches comments swallowing declarations", () => {
    const r = lintSmt(
      "(set-logic QF_LIA); foo(declare-const x Int)\n(assert (= x 1))",
    );
    expect(r.ok).toBe(false);
    expect(r.warnings.join(" ")).toMatch(/declare-const.*line comment/);
  });

  it("accepts comments at end-of-line followed by a newline", () => {
    const r = lintSmt(`
      (declare-const x Int) ; this is fine
      (assert (= x 1))
    `);
    expect(r.ok).toBe(true);
  });

  it("flags unbalanced parens", () => {
    const r = lintSmt("(declare-const x Int))");
    expect(r.ok).toBe(false);
    expect(r.warnings.join(" ")).toMatch(/Unbalanced/);
  });

  it("flags content that's all comment", () => {
    const r = lintSmt("; just a comment\n; another");
    expect(r.ok).toBe(false);
    expect(r.warnings.join(" ")).toMatch(/All SMT-LIB content was inside comments/);
  });
});

describe("lintLean", () => {
  it("accepts a valid example", () => {
    const r = lintLean("import Mathlib\nexample : 1 + 1 = 2 := by norm_num");
    expect(r.ok).toBe(true);
  });

  it("rejects empty input", () => {
    expect(lintLean("").ok).toBe(false);
  });

  it("catches `--` line comment swallowing a theorem", () => {
    const r = lintLean(
      "import Mathlib -- comment theorem foo : True := trivial",
    );
    expect(r.ok).toBe(false);
    expect(r.warnings.join(" ")).toMatch(/theorem.*line comment/);
  });

  it("catches /-...-/ block comment swallowing a declaration", () => {
    const r = lintLean(
      "/- example : True := trivial -/\nimport Mathlib",
    );
    expect(r.ok).toBe(false);
    // Either the example-eaten warning or the no-decl warning fires.
    expect(r.warnings.length).toBeGreaterThan(0);
  });

  it("flags decl-less snippets (e.g., raw tactics)", () => {
    const r = lintLean("intro x\nlinarith");
    expect(r.ok).toBe(false);
    expect(r.warnings.join(" ")).toMatch(/no `theorem` \/ `example` \/ `lemma` \/ `def`/);
  });
});

describe("lintPrologProgram", () => {
  it("accepts simple facts and rules", () => {
    const r = lintPrologProgram("color(red). color(green).\nlikes(X) :- color(X).");
    expect(r.ok).toBe(true);
  });

  it("rejects empty input", () => {
    expect(lintPrologProgram("").ok).toBe(false);
  });

  it("rejects all-comment input", () => {
    const r = lintPrologProgram("% just a comment\n% another");
    expect(r.ok).toBe(false);
    expect(r.warnings.join(" ")).toMatch(/All Prolog content was inside comments/);
  });

  it("flags absence of clause terminator `.`", () => {
    const r = lintPrologProgram("color(red)");
    expect(r.ok).toBe(false);
    expect(r.warnings.join(" ")).toMatch(/clause terminators/);
  });
});

describe("lintPrologQuery", () => {
  it("accepts a normal goal", () => {
    expect(lintPrologQuery("member(X, [a, b, c]).").ok).toBe(true);
  });

  it("strips a leading `?-` and trailing dot", () => {
    expect(lintPrologQuery("?- color(X).").ok).toBe(true);
  });

  it("rejects empty input", () => {
    expect(lintPrologQuery("").ok).toBe(false);
    expect(lintPrologQuery("?- .").ok).toBe(false);
  });

  it("rejects a fully-commented query body", () => {
    const r = lintPrologQuery("% this comments out the whole query");
    expect(r.ok).toBe(false);
  });
});
