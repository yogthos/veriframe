/**
 * Agent-only driver — skips the direct run, only invokes the agent
 * mode. Used when direct is already known and we want to iterate on
 * the agent.
 *
 *   npx tsx scripts/agent-only.ts <problem-id>
 *
 * Network-blip resilience: passes a `run_id` to the server which
 * persists the full run result to ${TMPDIR}/harness-runs/agent-<id>.json
 * so we can recover the result even if the HTTP connection drops
 * mid-run. If `fetch` fails with a transient error (timeout / reset /
 * server unreachable), the client polls for that file for a while
 * before giving up.
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { Agent, fetch as undiciFetch } from "undici";
import { PROBLEMS } from "./problems.js";

const HOST = process.env.HARNESS_HOST ?? "127.0.0.1";
const PORT = process.env.HARNESS_PORT ?? "3001";
const ENDPOINT = `http://${HOST}:${PORT}/v1/chat/completions`;
const HEALTH = `http://${HOST}:${PORT}/health`;

const agentDispatcher = new Agent({
  headersTimeout: 6 * 60 * 60 * 1000,
  bodyTimeout: 6 * 60 * 60 * 1000,
  connect: { timeout: 30 * 1000 },
});

const RESULT_DIR = path.join(os.tmpdir(), "harness-runs");

interface RunBody {
  choices: Array<{ message: { content: string } }>;
  harness?: unknown;
  result_file?: string;
}

/**
 * Wait up to `maxWaitMs` for a result file matching `runId` to appear.
 * Polls the filesystem at 5s intervals AND the server's /health endpoint
 * to make sure it's still alive (a dead server means no file is coming).
 */
async function waitForResultFile(
  runId: string,
  maxWaitMs: number,
): Promise<RunBody | null> {
  const file = path.join(RESULT_DIR, `agent-${runId}.json`);
  const t0 = Date.now();
  let nextHealthCheck = Date.now();
  while (Date.now() - t0 < maxWaitMs) {
    if (fs.existsSync(file)) {
      try {
        const content = fs.readFileSync(file, "utf-8");
        return JSON.parse(content) as RunBody;
      } catch {
        // File still being written; keep polling.
      }
    }
    if (Date.now() >= nextHealthCheck) {
      try {
        const h = await undiciFetch(HEALTH, { dispatcher: agentDispatcher });
        if (!h.ok) {
          console.error(`[recover] server health check returned ${h.status}; giving up.`);
          return null;
        }
      } catch {
        console.error("[recover] server health check failed; server appears down. Giving up.");
        return null;
      }
      nextHealthCheck = Date.now() + 30_000;
    }
    await new Promise((r) => setTimeout(r, 5_000));
  }
  return null;
}

function isTransientFetchError(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  const cause = (err as { cause?: { code?: string; name?: string } }).cause;
  const code = cause?.code ?? "";
  const name = cause?.name ?? err.name ?? "";
  // undici timeouts / TCP resets / DNS hiccups all look like these:
  return (
    code === "UND_ERR_HEADERS_TIMEOUT" ||
    code === "UND_ERR_BODY_TIMEOUT" ||
    code === "UND_ERR_SOCKET" ||
    code === "UND_ERR_CONNECT_TIMEOUT" ||
    code === "ECONNRESET" ||
    code === "ECONNREFUSED" ||
    code === "ETIMEDOUT" ||
    code === "EAI_AGAIN" ||
    name === "HeadersTimeoutError" ||
    name === "BodyTimeoutError" ||
    name === "SocketError"
  );
}

async function main(): Promise<void> {
  const id = process.argv[2];
  if (!id || !PROBLEMS[id]) {
    console.error("Usage: tsx scripts/agent-only.ts <problem-id>");
    process.exit(1);
  }
  const problem = PROBLEMS[id];

  // Honour the per-problem maxSteps when set; fall back to 80 only when
  // unspecified. The previous hardcode silently overrode problem-level
  // budgets, leading to runs that blew past their intended scope and
  // burned API tokens on wandering trajectories.
  const maxTurns = problem.maxSteps ?? 80;
  const runId = id;
  console.log(`Problem: ${problem.id}`);
  console.log(`Type:    ${problem.type}`);
  console.log(`max_turns: ${maxTurns}`);
  console.log(`run_id: ${runId} (server result file: ${RESULT_DIR}/agent-${runId}.json)`);

  const requestBody = JSON.stringify({
    model: "local-model",
    messages: [{ role: "user", content: problem.prompt }],
    mode: "agent",
    max_turns: maxTurns,
    run_id: runId,
  });

  const t0 = Date.now();
  let body: RunBody | null = null;
  try {
    const res = await undiciFetch(ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: requestBody,
      dispatcher: agentDispatcher,
    });
    if (!res.ok) {
      console.error(`HTTP ${res.status}: ${await res.text()}`);
      process.exit(1);
    }
    body = (await res.json()) as RunBody;
  } catch (err) {
    if (isTransientFetchError(err)) {
      const elapsedSec = ((Date.now() - t0) / 1000).toFixed(0);
      console.error(`[recover] fetch failed after ${elapsedSec}s with transient error: ${err instanceof Error ? err.message : String(err)}`);
      console.error(`[recover] polling for server-side result file (server keeps running independently)...`);
      // Poll for up to 6 hours — long beam searches can run that long.
      body = await waitForResultFile(runId, 6 * 60 * 60 * 1000);
      if (body === null) {
        console.error(`[recover] no result file appeared and server unreachable; giving up.`);
        process.exit(1);
      }
      console.error(`[recover] recovered result from server-side file.`);
    } else {
      console.error("Fatal:", err);
      process.exit(1);
    }
  }

  const ms = Date.now() - t0;
  console.log(`(${(ms / 1000).toFixed(1)}s)`);
  console.log("\n=== ANSWER ===\n");
  console.log(body.choices[0].message.content);
  console.log("\n=== HARNESS ===\n");
  console.log(JSON.stringify(body.harness, null, 2).slice(0, 8000));

  // Mirror the result to the legacy path so existing tooling keeps working.
  fs.writeFileSync(`/tmp/agent-${id}.json`, JSON.stringify(body, null, 2));
  console.log(`\n→ wrote /tmp/agent-${id}.json`);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
