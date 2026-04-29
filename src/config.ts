/**
 * Server / runtime configuration loaded from environment variables.
 *
 * Provider selection priority:
 *   1. HARNESS_PROVIDER explicitly set
 *   2. HARNESS_MODEL_PATH set → "local"
 *   3. Default: "local"
 */

import type { LLMConfig, ProviderType } from "./llm/types.js";

export interface ServerConfig {
  port: number;
  host: string;
  llm: LLMConfig;
}

function pickProvider(envProvider: string | undefined): ProviderType {
  if (envProvider && ["local", "openai", "deepseek", "ollama"].includes(envProvider)) {
    return envProvider as ProviderType;
  }
  if (process.env.HARNESS_MODEL_PATH) return "local";
  return "local";
}

function num(value: string | undefined): number | undefined {
  if (value === undefined) return undefined;
  const n = Number(value);
  return Number.isNaN(n) ? undefined : n;
}

function bool(value: string | undefined): boolean | undefined {
  if (value === undefined) return undefined;
  const v = value.toLowerCase().trim();
  if (v === "1" || v === "true" || v === "yes" || v === "on") return true;
  if (v === "0" || v === "false" || v === "no" || v === "off") return false;
  return undefined;
}

export function loadConfig(overrides?: Partial<ServerConfig>): ServerConfig {
  const provider =
    overrides?.llm?.provider ?? pickProvider(process.env.HARNESS_PROVIDER);

  const modelPath =
    process.env.HARNESS_MODEL_PATH ?? overrides?.llm?.modelPath;

  return {
    port: num(process.env.HARNESS_PORT) ?? overrides?.port ?? 3000,
    host: process.env.HARNESS_HOST ?? overrides?.host ?? "0.0.0.0",
    llm: {
      provider,
      modelPath,
      baseUrl: process.env.HARNESS_BASE_URL ?? overrides?.llm?.baseUrl,
      apiKey: overrides?.llm?.apiKey,
      model:
        process.env.HARNESS_MODEL ?? overrides?.llm?.model ?? "local-model",
      maxTokens:
        num(process.env.HARNESS_MAX_TOKENS) ??
        overrides?.llm?.maxTokens ??
        4096,
      temperature:
        num(process.env.HARNESS_TEMPERATURE) ??
        overrides?.llm?.temperature ??
        0.7,
      timeoutMs:
        num(process.env.HARNESS_TIMEOUT_MS) ??
        overrides?.llm?.timeoutMs ??
        300_000,
      contextWindow:
        num(process.env.HARNESS_CONTEXT_WINDOW) ??
        overrides?.llm?.contextWindow ??
        131_072,
      gpuLayers:
        num(process.env.HARNESS_GPU_LAYERS) ?? overrides?.llm?.gpuLayers,
      preserveThinking:
        bool(process.env.HARNESS_PRESERVE_THINKING) ??
        overrides?.llm?.preserveThinking ??
        true,
    },
  };
}
