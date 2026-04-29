#!/usr/bin/env node
/**
 * Reasoning-harness entry point.
 * Preloads the local model, then starts the OpenAI-compatible server.
 */

import { loadConfig } from "../config.js";
import {
  createLLMClient,
  preloadLocalModel,
  disposeLocalProvider,
} from "../llm/index.js";
import { createServer } from "../server.js";

async function main(): Promise<void> {
  const config = loadConfig();

  if (!config.llm.modelPath && config.llm.provider === "local") {
    console.error(
      "No model path configured. Set HARNESS_MODEL_PATH=models/Qwen3.6-35B-A3B-Q8_0.gguf",
    );
    process.exit(1);
  }

  console.log(`Provider: ${config.llm.provider}`);
  if (config.llm.modelPath) {
    console.log(`Model:    ${config.llm.modelPath}`);
  }
  console.log(`Context:  ${config.llm.contextWindow} tokens`);
  console.log(`Max out:  ${config.llm.maxTokens} tokens`);

  if (config.llm.provider === "local") {
    await preloadLocalModel(config.llm);
  }

  const client = createLLMClient(config.llm);
  const server = createServer(config, client);

  const shutdown = (sig: string) => () => {
    console.log(`\nReceived ${sig}, shutting down...`);
    server.close();
    disposeLocalProvider();
    process.exit(0);
  };
  process.on("SIGINT", shutdown("SIGINT"));
  process.on("SIGTERM", shutdown("SIGTERM"));

  server.listen(config.port, config.host, () => {
    console.log(`Listening on http://${config.host}:${config.port}`);
    console.log(`  POST /v1/chat/completions   (harness loop; { raw: true } to bypass)`);
    console.log(`  GET  /v1/models`);
    console.log(`  GET  /health`);
  });
}

main().catch((err) => {
  console.error("Fatal:", err);
  disposeLocalProvider();
  process.exit(1);
});
