/**
 * Compare direct vs harnessed reasoning on a chosen problem.
 *
 *   npx tsx scripts/compare.ts <problem-id>
 *
 * Writes the full transcript to /tmp/bench-<id>.log and a JSON summary
 * to /tmp/bench-<id>.json so the doc generator can pick it up.
 */

import fs from "node:fs";
import { Agent, fetch as undiciFetch } from "undici";
import { PROBLEMS, type Problem } from "./problems.js";

// The harness server only writes response headers after the full run
// completes (no streaming). Hard problems can take 5–15 min, so the
// default 300 s headersTimeout kills the connection. Bump it.
const longLivedAgent = new Agent({
  headersTimeout: 60 * 60 * 1000,
  bodyTimeout: 60 * 60 * 1000,
  connect: { timeout: 30 * 1000 },
});

const HOST = process.env.HARNESS_HOST ?? "127.0.0.1";
const PORT = process.env.HARNESS_PORT ?? "3001";
const ENDPOINT = `http://${HOST}:${PORT}/v1/chat/completions`;

interface ChatResponse {
  choices: Array<{ message: { content: string }; finish_reason: string }>;
  harness?: {
    status: string;
    steps: number;
    error?: string;
    trace: Array<{
      stepNumber: number;
      explanation: string;
      assertions: string[];
      status: string;
      unsatCore?: string[];
    }>;
    verification?: {
      model: Record<string, string>;
      unique: boolean;
      counterExample?: Record<string, string>;
    };
    unsatCore?: string[];
  };
}

async function ask(problem: Problem, raw: boolean): Promise<ChatResponse> {
  const res = await undiciFetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "local-model",
      messages: [{ role: "user", content: problem.prompt }],
      raw,
      max_steps: problem.maxSteps ?? 12,
    }),
    dispatcher: longLivedAgent,
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  }
  return (await res.json()) as ChatResponse;
}

function hr(label: string): string {
  return "\n" + "═".repeat(72) + "\n" + label + "\n" + "═".repeat(72);
}

async function main(): Promise<void> {
  const id = process.argv[2];
  if (!id || !PROBLEMS[id]) {
    console.error(`Usage: tsx scripts/compare.ts <problem-id>`);
    console.error(`Known problems: ${Object.keys(PROBLEMS).join(", ")}`);
    process.exit(1);
  }
  const problem = PROBLEMS[id];

  const out: string[] = [];
  const log = (s: string): void => {
    console.log(s);
    out.push(s);
  };

  log(`Problem id:    ${problem.id}`);
  log(`Type:          ${problem.type}`);
  log(`Difficulty:    ${problem.difficulty}`);
  log(`Expected:      ${problem.expectedAnswer}`);
  log(hr("PROMPT"));
  log(problem.prompt);

  log(hr("DIRECT (raw model)"));
  const t1 = Date.now();
  const direct = await ask(problem, true);
  const directMs = Date.now() - t1;
  log(`(${(directMs / 1000).toFixed(1)}s)`);
  log(direct.choices[0].message.content);

  log(hr("HARNESSED (Z3-verified loop)"));
  const t2 = Date.now();
  const harnessed = await ask(problem, false);
  const harnessMs = Date.now() - t2;
  log(`(${(harnessMs / 1000).toFixed(1)}s)`);
  log(harnessed.choices[0].message.content);

  if (harnessed.harness) {
    log(hr("HARNESS TRACE"));
    log(`Status: ${harnessed.harness.status}`);
    if (harnessed.harness.error) {
      log(`Error:  ${harnessed.harness.error}`);
    }
    log(`Steps:  ${harnessed.harness.steps}`);
    for (const step of harnessed.harness.trace) {
      log(
        `\n  [${step.status.toUpperCase()}] step ${step.stepNumber}: ${step.explanation.slice(0, 600)}`,
      );
      for (const a of step.assertions) log(`    ${a}`);
      if (step.unsatCore && step.unsatCore.length > 0) {
        log(`    unsat core: ${step.unsatCore.join(", ")}`);
      }
    }
    const v = harnessed.harness.verification;
    if (v) {
      log(hr("Z3 VERIFICATION"));
      log("Model:");
      for (const [k, val] of Object.entries(v.model)) log(`  ${k} = ${val}`);
      log(`Unique: ${v.unique}`);
      if (!v.unique && v.counterExample) {
        log("Counter-example:");
        for (const [k, val] of Object.entries(v.counterExample)) {
          log(`  ${k} = ${val}`);
        }
      }
    }
  }

  fs.writeFileSync(`/tmp/bench-${id}.log`, out.join("\n"));
  fs.writeFileSync(
    `/tmp/bench-${id}.json`,
    JSON.stringify(
      {
        id: problem.id,
        type: problem.type,
        difficulty: problem.difficulty,
        expectedAnswer: problem.expectedAnswer,
        directMs,
        harnessMs,
        directContent: direct.choices[0].message.content,
        harnessedContent: harnessed.choices[0].message.content,
        harness: harnessed.harness,
      },
      null,
      2,
    ),
  );
  console.log(`\n→ wrote /tmp/bench-${id}.log and /tmp/bench-${id}.json`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
