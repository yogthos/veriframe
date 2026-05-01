/**
 * GLM (Zhipu BigModel) provider — OpenAI-compatible HTTP backend.
 *
 * Endpoint defaults to the BigModel coding API
 * (`https://open.bigmodel.cn/api/coding/paas/v4/chat/completions`),
 * which serves the GLM-4.5 family. The base URL is overridable via
 * `HARNESS_BASE_URL` for non-coding endpoints.
 *
 * Auth: `ZHIPU_API_KEY` env var (or `LLMConfig.apiKey`). Follows the
 * pattern used in matryoshka's openai-compat provider — same wire
 * format, no provider-specific quirks for our chat usage.
 *
 * Notes:
 *   - Supports the full `LLMClient.chat` surface but NOT streaming.
 *     Adding streaming is straightforward (SSE parser) when needed.
 *   - We don't pass `tools` to the API even when caller supplies them —
 *     our agent uses fenced markdown tool-calls in the chat content,
 *     not native OpenAI function calling. Keep tools out of the wire
 *     payload to avoid GLM trying to use its own format.
 */

import type {
  LLMClient,
  LLMConfig,
  ChatMessage,
  ChatOptions,
  LLMResponse,
} from "./types.js";

const DEFAULT_URL =
  "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";

interface GlmChoice {
  message: {
    content: string | null;
    /**
     * Thinking models (glm-5.1, glm-zero, etc.) put their chain of
     * thought here, separate from the final answer in `content`.
     * The agent parses tool-call fences out of the model output, so
     * we merge reasoning + content into one string with the chain
     * wrapped in `<think>...</think>` (matching the local Qwen
     * provider's convention) — the fence may live in either field.
     */
    reasoning_content?: string | null;
  };
  finish_reason?: string;
}

interface GlmResponse {
  choices?: GlmChoice[];
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
  error?: { message?: string; code?: string | number };
}

export function createGLMProvider(config: LLMConfig): LLMClient {
  const url = config.baseUrl ?? DEFAULT_URL;
  const apiKey = config.apiKey ?? process.env.ZHIPU_API_KEY;
  if (!apiKey) {
    throw new Error(
      "GLM provider requires an API key (set ZHIPU_API_KEY env var or LLMConfig.apiKey)",
    );
  }
  // Per-request wall-clock cap. GLM responses on the coding endpoint
  // can take 1-3 minutes for long completions; default to 10 min so
  // the agent's per-turn budget isn't artificially capped by Node's
  // default fetch timeout (which depends on undici version and can
  // bite us on slow turns).
  const timeoutMs = config.timeoutMs ?? 600_000;
  const maxRetries = 2;

  async function callOnce(
    body: Record<string, unknown>,
    callerSignal: AbortSignal | undefined,
  ): Promise<LLMResponse> {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(new Error("timeout")), timeoutMs);
    const onCallerAbort = () => ctrl.abort();
    if (callerSignal) {
      if (callerSignal.aborted) ctrl.abort();
      else callerSignal.addEventListener("abort", onCallerAbort, { once: true });
    }
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });
      if (!response.ok) {
        let errBody = "";
        try {
          errBody = await response.text();
        } catch {
          /* ignore */
        }
        throw new Error(
          `GLM error: ${response.status} ${response.statusText}${errBody ? ` — ${errBody.slice(0, 500)}` : ""}`,
        );
      }
      let data: GlmResponse;
      try {
        data = (await response.json()) as GlmResponse;
      } catch (e) {
        throw new Error(
          `GLM returned non-JSON response: ${e instanceof Error ? e.message : String(e)}`,
        );
      }
      if (data.error) {
        throw new Error(
          `GLM API error: ${data.error.message ?? "unknown"}${data.error.code !== undefined ? ` (code ${data.error.code})` : ""}`,
        );
      }
      const choice = data.choices?.[0];
      if (!choice) throw new Error("GLM response had no choices");
      const rawContent = choice.message.content ?? "";
      const reasoning = choice.message.reasoning_content ?? "";
      const merged =
        reasoning && rawContent
          ? `<think>${reasoning}</think>\n${rawContent}`
          : reasoning
            ? `<think>${reasoning}</think>`
            : rawContent;
      if (!merged) {
        throw new Error("GLM response missing both content and reasoning");
      }
      return {
        content: merged,
        finishReason: choice.finish_reason ?? "stop",
        usage: data.usage
          ? {
              promptTokens: data.usage.prompt_tokens ?? 0,
              completionTokens: data.usage.completion_tokens ?? 0,
              totalTokens: data.usage.total_tokens ?? 0,
            }
          : undefined,
      };
    } finally {
      clearTimeout(timer);
      if (callerSignal) callerSignal.removeEventListener("abort", onCallerAbort);
    }
  }

  function isRetryableError(e: unknown): boolean {
    // Network blips (`fetch failed`, ECONNRESET, etc.) and 5xx — the
    // model itself didn't disagree with us, just transit failed. Don't
    // retry on caller abort or 4xx (those are permanent for this call).
    if (e instanceof Error) {
      const msg = e.message;
      if (msg.includes("fetch failed")) return true;
      if (/GLM error: 5\d\d/.test(msg)) return true;
      if (msg.includes("ECONNRESET") || msg.includes("socket hang up"))
        return true;
    }
    return false;
  }

  return {
    async chat(
      messages: ChatMessage[],
      options?: ChatOptions,
    ): Promise<LLMResponse> {
      const body: Record<string, unknown> = {
        model: config.model,
        messages: messages.map((m) => ({
          role: m.role,
          content: m.content,
        })),
        max_tokens: config.maxTokens ?? 4096,
        temperature: config.temperature ?? 0.7,
      };
      if (config.topP !== undefined) body.top_p = config.topP;

      let lastErr: unknown;
      for (let attempt = 0; attempt <= maxRetries; attempt++) {
        if (options?.signal?.aborted) {
          throw new Error("aborted by caller");
        }
        try {
          return await callOnce(body, options?.signal);
        } catch (e) {
          lastErr = e;
          if (!isRetryableError(e) || attempt === maxRetries) throw e;
          // Backoff: 2s, 8s.
          const delay = 2000 * Math.pow(4, attempt);
          await new Promise((r) => setTimeout(r, delay));
        }
      }
      throw lastErr;
    },

    async listModels(): Promise<string[]> {
      return [config.model];
    },
  };
}
