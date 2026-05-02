/**
 * DeepSeek provider — OpenAI-compatible HTTP backend.
 *
 * Endpoint: `https://api.deepseek.com/v1/chat/completions`. Auth:
 * `DEEPSEEK_API_KEY`. Models: `deepseek-chat` (general) and
 * `deepseek-reasoner` (thinking model — emits `reasoning_content`
 * which the shared factory merges into `<think>...</think>` framing
 * so the agent's tool-call fence parser sees the fence wherever
 * the model put it).
 */

import type { LLMClient, LLMConfig } from "./types.js";
import { createOpenAICompatProvider } from "./openai-compat.js";

const DEFAULT_URL = "https://api.deepseek.com/v1/chat/completions";

export function createDeepSeekProvider(config: LLMConfig): LLMClient {
  return createOpenAICompatProvider(config, {
    name: "DeepSeek",
    defaultUrl: DEFAULT_URL,
    apiKeyEnvVar: "DEEPSEEK_API_KEY",
  });
}
