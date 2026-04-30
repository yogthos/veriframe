/**
 * Agent-only driver — skips the direct run, only invokes the agent
 * mode. Used when direct is already known and we want to iterate on
 * the agent.
 *
 *   npx tsx scripts/agent-only.ts <problem-id>
 */

import fs from "node:fs";
import { Agent, fetch as undiciFetch } from "undici";
import { PROBLEMS } from "./problems.js";

const HOST = process.env.HARNESS_HOST ?? "127.0.0.1";
const PORT = process.env.HARNESS_PORT ?? "3001";
const ENDPOINT = `http://${HOST}:${PORT}/v1/chat/completions`;

const agentDispatcher = new Agent({
  headersTimeout: 3 * 60 * 60 * 1000,
  bodyTimeout: 3 * 60 * 60 * 1000,
  connect: { timeout: 30 * 1000 },
});

async function main(): Promise<void> {
  const id = process.argv[2];
  if (!id || !PROBLEMS[id]) {
    console.error("Usage: tsx scripts/agent-only.ts <problem-id>");
    process.exit(1);
  }
  const problem = PROBLEMS[id];

  console.log(`Problem: ${problem.id}`);
  console.log(`Type:    ${problem.type}`);

  const t0 = Date.now();
  const res = await undiciFetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "local-model",
      messages: [{ role: "user", content: problem.prompt }],
      mode: "agent",
      max_turns: 40,
    }),
    dispatcher: agentDispatcher,
  });
  const ms = Date.now() - t0;
  if (!res.ok) {
    console.error(`HTTP ${res.status}: ${await res.text()}`);
    process.exit(1);
  }
  const body = await res.json() as {
    choices: Array<{ message: { content: string } }>;
    harness?: unknown;
  };

  console.log(`(${(ms / 1000).toFixed(1)}s)`);
  console.log("\n=== ANSWER ===\n");
  console.log(body.choices[0].message.content);
  console.log("\n=== HARNESS ===\n");
  console.log(JSON.stringify(body.harness, null, 2).slice(0, 8000));

  fs.writeFileSync(`/tmp/agent-${id}.json`, JSON.stringify(body, null, 2));
  console.log(`\n→ wrote /tmp/agent-${id}.json`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
