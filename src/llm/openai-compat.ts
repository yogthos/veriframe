/**
 * Generic OpenAI-compatible HTTP provider factory.
 *
 * Used by GLM (Zhipu) and DeepSeek — both speak the OpenAI chat
 * completions wire format with minor variations (different default
 * URLs, API-key env vars, default models). Both also emit a
 * `reasoning_content` field on thinking-model responses (GLM-5.1,
 * deepseek-reasoner) which we merge into the visible content with
 * `<think>...</think>` framing so the agent's tool-call fence parser
 * can find a fence wherever the model put it.
 *
 * Quirks handled:
 *   - Configurable per-request wall-clock timeout via AbortController.
 *   - Two-attempt retry on transient network failures (`fetch failed`,
 *     ECONNRESET, 5xx). Caller-aborts and 4xx pass straight through.
 *   - Graceful fallback when content is empty but reasoning is
 *     present (rare; usually means the model hit max_tokens during
 *     thinking).
 */

import type {
  LLMClient,
  LLMConfig,
  ChatMessage,
  ChatOptions,
  LLMResponse,
} from "./types.js";

interface ChatChoice {
  message: {
    content: string | null;
    reasoning_content?: string | null;
  };
  finish_reason?: string;
}

/**
 * Strip `<think>…</think>` blocks from content. Applied to prior
 * assistant messages before sending them back to the API so we
 * don't accumulate reasoning across turns. Both DeepSeek and GLM's
 * docs are explicit that `reasoning_content` should NOT be sent
 * back on subsequent turns — the model regenerates fresh thinking
 * each turn. Local Qwen's chat template handles this internally;
 * the OpenAI-compat HTTP path has to do it ourselves.
 *
 * Tolerant to nesting and to unmatched tags (drops opens with no
 * close). Matches across line breaks.
 */
export function stripThinkBlocks(content: string): string {
  // Greedy-but-non-overlapping replace. `[\s\S]` matches across
  // newlines (vs `.` which doesn't by default).
  return content.replace(/<think>[\s\S]*?<\/think>/g, "").trim();
}

interface ChatResponse {
  choices?: ChatChoice[];
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
  error?: { message?: string; code?: string | number };
}

export interface OpenAICompatProviderOptions {
  /** Display name for error messages (e.g. "GLM", "DeepSeek"). */
  name: string;
  /** Default endpoint URL (overridable via `LLMConfig.baseUrl`). */
  defaultUrl: string;
  /** Env var holding the API key (e.g. "ZHIPU_API_KEY"). */
  apiKeyEnvVar: string;
  /** How long to wait per request before aborting. Default 600s. */
  defaultTimeoutMs?: number;
  /** Retries on transient errors. Default 2 (so up to 3 attempts). */
  maxRetries?: number;
}

export function createOpenAICompatProvider(
  config: LLMConfig,
  opts: OpenAICompatProviderOptions,
): LLMClient {
  const url = config.baseUrl ?? opts.defaultUrl;
  const apiKey = config.apiKey ?? process.env[opts.apiKeyEnvVar];
  if (!apiKey) {
    throw new Error(
      `${opts.name} provider requires an API key (set ${opts.apiKeyEnvVar} env var or LLMConfig.apiKey)`,
    );
  }
  const timeoutMs = config.timeoutMs ?? opts.defaultTimeoutMs ?? 600_000;
  const maxRetries = opts.maxRetries ?? 2;

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
          `${opts.name} error: ${response.status} ${response.statusText}${errBody ? ` — ${errBody.slice(0, 500)}` : ""}`,
        );
      }
      let data: ChatResponse;
      try {
        data = (await response.json()) as ChatResponse;
      } catch (e) {
        throw new Error(
          `${opts.name} returned non-JSON response: ${e instanceof Error ? e.message : String(e)}`,
        );
      }
      if (data.error) {
        throw new Error(
          `${opts.name} API error: ${data.error.message ?? "unknown"}${data.error.code !== undefined ? ` (code ${data.error.code})` : ""}`,
        );
      }
      const choice = data.choices?.[0];
      if (!choice) throw new Error(`${opts.name} response had no choices`);
      const rawContent = choice.message.content ?? "";
      const reasoning = choice.message.reasoning_content ?? "";
      const merged =
        reasoning && rawContent
          ? `<think>${reasoning}</think>\n${rawContent}`
          : reasoning
            ? `<think>${reasoning}</think>`
            : rawContent;
      if (!merged) {
        throw new Error(
          `${opts.name} response missing both content and reasoning`,
        );
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
    if (e instanceof Error) {
      const msg = e.message;
      if (msg.includes("fetch failed")) return true;
      if (new RegExp(`${opts.name} error: 5\\d\\d`).test(msg)) return true;
      if (msg.includes("ECONNRESET") || msg.includes("socket hang up")) {
        return true;
      }
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
        // Strip `<think>` blocks from PRIOR assistant turns — both
        // DeepSeek and GLM expect reasoning_content to NOT be re-fed
        // on subsequent turns, and accumulating it across turns
        // burns context for no benefit. We leave the latest user
        // message and any system prompts untouched.
        messages: messages.map((m) => ({
          role: m.role,
          content:
            m.role === "assistant" && m.content
              ? stripThinkBlocks(m.content)
              : m.content,
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
          // Backoff: 2s, 8s, ...
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
