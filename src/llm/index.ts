/**
 * LLM provider factory.
 *
 * Picks a provider based on config:
 *   - config.provider explicit, OR
 *   - config.modelPath set → "local"
 *
 * Currently only "local" is implemented; remote providers can be added
 * later (openai, ollama, deepseek) following the rlm-sandbox layout.
 */

import type { LLMConfig, LLMClient, ProviderType } from "./types.js";
import { createLocalProvider } from "./local.js";
import { createGLMProvider } from "./glm.js";

export {
  createLocalProvider,
  disposeLocalProvider,
  preloadLocalModel,
} from "./local.js";
export { createGLMProvider } from "./glm.js";

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
    case "openai":
    case "ollama":
    case "deepseek":
      throw new Error(
        `Provider "${provider}" not yet implemented in reasoning-harness`,
      );
    default: {
      const _exhaustive: never = provider;
      throw new Error(`Unknown provider: ${_exhaustive}`);
    }
  }
}
