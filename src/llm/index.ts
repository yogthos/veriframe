/**
 * LLM provider factory. Picks per-config:
 *   - config.provider explicit, OR
 *   - ZHIPU_API_KEY → "glm"
 *   - DEEPSEEK_API_KEY → "deepseek"
 *   - config.modelPath → "local"
 *
 * Implemented: local (node-llama-cpp), glm (Zhipu), deepseek.
 * Stubbed: openai, ollama.
 */

import type { LLMConfig, LLMClient, ProviderType } from "./types.js";
import { createLocalProvider } from "./local.js";
import { createGLMProvider } from "./glm.js";
import { createDeepSeekProvider } from "./deepseek.js";

export {
  createLocalProvider,
  disposeLocalProvider,
  preloadLocalModel,
} from "./local.js";
export { createGLMProvider } from "./glm.js";
export { createDeepSeekProvider } from "./deepseek.js";

export type {
  LLMConfig,
  LLMClient,
  ChatMessage,
  ChatOptions,
  LLMResponse,
  ProviderType,
} from "./types.js";

export function pickProvider(config: LLMConfig): ProviderType {
  if (config.provider) return config.provider;
  if (config.modelPath) return "local";
  return "local";
}

export function createLLMClient(config: LLMConfig): LLMClient {
  const provider = pickProvider(config);
  switch (provider) {
    case "local":
      return createLocalProvider(config);
    case "glm":
      return createGLMProvider(config);
    case "deepseek":
      return createDeepSeekProvider(config);
    case "openai":
    case "ollama":
      throw new Error(
        `Provider "${provider}" not yet implemented in reasoning-harness`,
      );
    default: {
      const _exhaustive: never = provider;
      throw new Error(`Unknown provider: ${_exhaustive}`);
    }
  }
}
