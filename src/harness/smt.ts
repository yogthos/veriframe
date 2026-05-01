/**
 * Z3 SMT verification — second backend for the agent's `verify` flow.
 *
 * The model writes SMT-LIB; the harness shells out to the system `z3`
 * binary (4.15+, installed via Homebrew) and parses sat/unsat/unknown.
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
 * doesn't already contain it. Anything more elaborate (`(get-model)`,
 * `(get-unsat-core)`) the user can include themselves.
 */

import { execSync } from "node:child_process";

const Z3_BINARY = "z3";
const Z3_TIMEOUT_MS = 30_000;
const MAX_OUTPUT_BYTES = 64 * 1024;

export type SmtVerdict = "sat" | "unsat" | "unknown";

export type SmtResult =
  | { status: "ok"; verdict: SmtVerdict; output: string }
  | { status: "error"; error: string };

export function runSmt(smtlib: string): SmtResult {
  const code = /\(\s*check-sat\s*\)/.test(smtlib)
    ? smtlib
    : `${smtlib}\n(check-sat)\n`;

  let raw: string;
  try {
    raw = execSync(`${Z3_BINARY} -smt2 -in`, {
      input: code,
      timeout: Z3_TIMEOUT_MS,
      maxBuffer: MAX_OUTPUT_BYTES,
      stdio: ["pipe", "pipe", "pipe"],
    }).toString();
  } catch (e) {
    const err = e as { stderr?: Buffer | string; stdout?: Buffer | string; message?: string };
    const stderr = err.stderr?.toString() ?? "";
    const stdout = err.stdout?.toString() ?? "";
    return {
      status: "error",
      error: `z3 invocation failed: ${err.message ?? "unknown"}${
        stderr ? `\nstderr: ${stderr.trim()}` : ""
      }${stdout ? `\nstdout: ${stdout.trim()}` : ""}`,
    };
  }

  const lines = raw.split("\n").map((l) => l.trim()).filter(Boolean);
  // The verdict line is the last `sat | unsat | unknown` we emit. Z3
  // may also print errors / warnings interleaved — propagate those.
  const verdictLine = [...lines]
    .reverse()
    .find((l) => l === "sat" || l === "unsat" || l === "unknown");
  if (!verdictLine) {
    return {
      status: "error",
      error: `z3 produced no verdict. Output was:\n${raw.trim()}`,
    };
  }
  return {
    status: "ok",
    verdict: verdictLine as SmtVerdict,
    output: raw.trim(),
  };
}
