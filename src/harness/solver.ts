import { init } from "z3-solver";
import type { IncrementalSolver } from "../types.js";

let z3Promise: ReturnType<typeof init> | null = null;

function getZ3() {
  if (!z3Promise) {
    z3Promise = init();
  }
  return z3Promise;
}

const DECLARATION_PATTERNS = [
  /\(\s*declare-sort\s/,
  /\(\s*declare-datatypes?\s/,
  /\(\s*declare-const\s/,
  /\(\s*declare-fun\s/,
  /\(\s*define-sort\s/,
  /\(\s*define-fun\s/,
  /\(\s*set-option\s/,
  /\(\s*set-logic\s/,
];

const CONTROL_PATTERN =
  /^\(\s*(?:check-sat|get-model|get-unsat-core|get-value|exit|push|pop|reset)\b/;

export type SmtKind = "declaration" | "assertion" | "control" | "expression";

export function classifySmt(expr: string): SmtKind {
  const trimmed = expr.trim();
  if (CONTROL_PATTERN.test(trimmed)) return "control";
  if (DECLARATION_PATTERNS.some((p) => p.test(trimmed))) return "declaration";
  if (/\(\s*assert\s/.test(trimmed)) return "assertion";
  return "expression";
}

function isDeclaration(expr: string): boolean {
  return DECLARATION_PATTERNS.some((p) => p.test(expr));
}

function isAssertion(expr: string): boolean {
  return /\(\s*assert\s/.test(expr);
}

function sanitizeSmtlib(input: string): string {
  return input
    .replace(
      /\(\s*(?:check-sat|get-model|get-unsat-core|exit|set-option\s+:produce-unsat-cores\s+\w+)\s*\)/g,
      ""
    )
    .trim();
}

export async function createIncrementalSolver(): Promise<IncrementalSolver> {
  const z3 = await getZ3();
  const ctx = z3.Context("main");
  const solver = new ctx.Solver();

  solver.fromString("(set-option :produce-unsat-cores true)");

  let disposed = false;
  let lastCheckResult: "sat" | "unsat" | "unknown" | null = null;

  function guard() {
    if (disposed) {
      throw new Error("Solver has been disposed");
    }
  }

  return {
    push(): void {
      guard();
      solver.push();
    },

    pop(): void {
      guard();
      solver.pop(1);
    },

    assert(expr: string): void {
      guard();
      const sanitized = sanitizeSmtlib(expr);
      if (!sanitized) return;

      if (isDeclaration(sanitized)) {
        solver.fromString(sanitized);
      } else if (isAssertion(sanitized)) {
        solver.fromString(sanitized);
      } else {
        solver.fromString(`(assert ${sanitized})`);
      }
      lastCheckResult = null;
    },

    async check(): Promise<"sat" | "unsat" | "unknown"> {
      guard();
      lastCheckResult = await solver.check();
      return lastCheckResult;
    },

    unsatCore(): string[] {
      guard();
      if (lastCheckResult !== "unsat") {
        throw new Error(
          "unsatCore() can only be called after a check that returned 'unsat'"
        );
      }
      try {
        const coreVector = solver.unsatCore();
        const core: string[] = [];
        for (let i = 0; i < coreVector.length(); i++) {
          core.push(coreVector.get(i).sexpr());
        }
        return core;
      } catch {
        return [];
      }
    },

    getModel(): Record<string, string> {
      guard();
      if (lastCheckResult !== "sat") {
        throw new Error(
          "getModel() can only be called after a check that returned 'sat'"
        );
      }
      const model = solver.model();
      const assignments: Record<string, string> = {};
      for (const decl of model.decls()) {
        const name = decl.name() as string;
        const valueStr = model.eval(decl.call()).toString();
        // Z3 exposes `:named` tracked-assertion labels as Bool decls whose
        // model value renders as `(_ <name> 0)`. They aren't real
        // variables — skip them so the answer stays readable.
        if (valueStr === `(_ ${name} 0)` || valueStr === `(_ ${name} 1)`) {
          continue;
        }
        assignments[name] = valueStr;
      }
      return assignments;
    },

    dispose(): void {
      if (!disposed) {
        disposed = true;
        solver.release();
      }
    },
  };
}
