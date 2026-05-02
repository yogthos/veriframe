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
import crypto from "node:crypto";
import type { LLMClient } from "./llm/types.js";
import type { ServerConfig } from "./config.js";
import { runAgent } from "./harness/agent.js";

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
              },
            };
          }
        }

        jsonResponse(res, 200, {
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
        });
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
