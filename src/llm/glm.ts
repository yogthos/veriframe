/**
 * GLM (Zhipu BigModel) provider — OpenAI-compatible HTTP backend.
 *
 * Defaults to the BigModel coding API
 * (`https://open.bigmodel.cn/api/coding/paas/v4/chat/completions`),
 * which serves the GLM-4.5 / 5.x family. Override the URL via
 * `HARNESS_BASE_URL` for non-coding endpoints. Auth: `ZHIPU_API_KEY`.
 *
 * GLM-5.1 is a thinking model — it returns `reasoning_content`
 * separately from `content`. The shared OpenAI-compat factory merges
 * them into `<think>...</think>` framing so the agent's tool-call
 * fence parser sees the fence wherever the model put it.
 */

import type { LLMClient, LLMConfig } from "./types.js";
import { createOpenAICompatProvider } from "./openai-compat.js";

const DEFAULT_URL =
  "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";

export function createGLMProvider(config: LLMConfig): LLMClient {
  return createOpenAICompatProvider(config, {
    name: "GLM",
    defaultUrl: DEFAULT_URL,
    apiKeyEnvVar: "ZHIPU_API_KEY",
  });
}
