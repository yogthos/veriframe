/**
 * OpenAI-compatible HTTP server.
 *
 *   POST /v1/chat/completions  — runs the harness loop on the user's
 *                                last message; returns the final answer
 *                                in OpenAI ChatCompletion format.
 *   GET  /v1/models            — lists the configured model.
 *   GET  /health               — health check.
 *
 * Streaming is not yet implemented — non-streaming only.
 */

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import type { LLMClient } from "./llm/types.js";
import type { ServerConfig } from "./config.js";
import { runAgent } from "./harness/agent.js";

/**
 * Slugify a string into a filesystem-safe id (used for result-file paths
 * when the client passes a `run_id`). Strict allowlist + length cap
 * prevents an arbitrary-write vector if a malicious request sets
 * run_id="../../etc/passwd".
 */
function slugifyRunId(s: string): string | null {
  const cleaned = s.replace(/[^A-Za-z0-9_.-]/g, "").slice(0, 80);
  return cleaned.length > 0 ? cleaned : null;
}

const MAX_BODY_SIZE = 10 * 1024 * 1024;

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY_SIZE) {
        reject(new Error("Request body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf-8")));
    req.on("error", reject);
  });
}

function jsonResponse(
  res: http.ServerResponse,
  status: number,
  body: unknown,
): void {
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
  });
  res.end(JSON.stringify(body));
}

function errorResponse(
  res: http.ServerResponse,
  status: number,
  message: string,
): void {
  jsonResponse(res, status, {
    error: { message, type: "invalid_request_error", code: status.toString() },
  });
}

interface ChatRequest {
  model?: string;
  messages: Array<{ role: string; content: string }>;
  max_turns?: number;
  /** When true, bypass the harness and call the model directly. */
  raw?: boolean;
  /** Optional id used to write the run's full result to a file at
   *  `${run_results_dir}/agent-<run_id>.json` once the harness loop
   *  completes. Lets the client recover the result even if the HTTP
   *  connection drops mid-run (long beam searches frequently outlast
   *  TCP idle timeouts on flaky links). Slugified server-side. */
  run_id?: string;
}

export function createServer(
  config: ServerConfig,
  llmClient: LLMClient,
): http.Server {
  return http.createServer(async (req, res) => {
    if (req.method === "OPTIONS") {
      res.writeHead(204, { "Access-Control-Allow-Origin": "*" });
      res.end();
      return;
    }

    if (req.method === "GET" && req.url === "/health") {
      jsonResponse(res, 200, { status: "ok" });
      return;
    }

    if (req.method === "GET" && req.url === "/v1/models") {
      const models = await llmClient.listModels();
      jsonResponse(res, 200, {
        object: "list",
        data: models.map((id) => ({
          id,
          object: "model",
          created: Math.floor(Date.now() / 1000),
          owned_by: "local",
        })),
      });
      return;
    }

    if (req.method === "POST" && req.url === "/v1/chat/completions") {
      let body: ChatRequest;
      try {
        body = JSON.parse(await readBody(req)) as ChatRequest;
      } catch {
        return errorResponse(res, 400, "Invalid JSON body");
      }
      if (!body.messages || !Array.isArray(body.messages) || body.messages.length === 0) {
        return errorResponse(res, 400, "messages array required");
      }

      const userMsg = [...body.messages].reverse().find((m) => m.role === "user");
      if (!userMsg) {
        return errorResponse(res, 400, "no user message found");
      }

      const id = `chatcmpl-${crypto.randomUUID().replace(/-/g, "").slice(0, 24)}`;
      const created = Math.floor(Date.now() / 1000);
      const modelId = body.model ?? config.llm.model;

      try {
        let content: string;
        let finishReason: string;
        let extra: Record<string, unknown> = {};

        if (body.raw) {
          const llmRes = await llmClient.chat(
            body.messages.map((m) => ({
              role: m.role as "system" | "user" | "assistant" | "tool",
              content: m.content,
            })),
          );
          content = llmRes.content;
          finishReason = llmRes.finishReason;
        } else {
          const result = await runAgent(userMsg.content, llmClient, {
            config: body.max_turns ? { maxTurns: body.max_turns } : undefined,
          });
          if (result.status === "completed") {
            content = result.finalAnswer;
            finishReason = "stop";
            extra = {
              harness: {
                status: "completed",
                steps: result.steps.length,
                trace: result.steps,
                verifiedArtifacts: result.verifiedArtifacts,
              },
            };
          } else {
            content = `[harness failed: ${result.error}]`;
            finishReason = "length";
            extra = {
              harness: {
                status: "failed",
                error: result.error,
                steps: result.steps.length,
                trace: result.steps,
                verifiedArtifacts: result.verifiedArtifacts,
              },
            };
          }
        }

        const responseBody = {
          id,
          object: "chat.completion",
          created,
          model: modelId,
          choices: [
            {
              index: 0,
              message: { role: "assistant", content },
              finish_reason: finishReason,
            },
          ],
          usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
          ...extra,
        };

        // Persist the run's full result to disk if the client passed a
        // run_id. Lets the client recover after a TCP/idle-timeout
        // disconnect on long beam searches — the file is the source of
        // truth, the HTTP response is just the "done" signal. We write
        // BEFORE sending the response so a client racing to read after
        // a successful response always finds the file.
        if (!body.raw && typeof body.run_id === "string") {
          const slug = slugifyRunId(body.run_id);
          if (slug) {
            try {
              const dir = path.join(os.tmpdir(), "harness-runs");
              fs.mkdirSync(dir, { recursive: true });
              const file = path.join(dir, `agent-${slug}.json`);
              const tmp = `${file}.tmp`;
              fs.writeFileSync(tmp, JSON.stringify(responseBody, null, 2));
              fs.renameSync(tmp, file);
              (responseBody as Record<string, unknown>).result_file = file;
            } catch (e) {
              console.error(
                `[server] failed to write result file for run_id=${slug}: ${e instanceof Error ? e.message : String(e)}`,
              );
            }
          }
        }

        jsonResponse(res, 200, responseBody);
      } catch (err) {
        errorResponse(
          res,
          500,
          err instanceof Error ? err.message : String(err),
        );
      }
      return;
    }

    errorResponse(res, 404, `Not found: ${req.method} ${req.url}`);
  });
}
