/**
 * Local LLM provider using node-llama-cpp.
 *
 * Runs a GGUF model in-process via llama.cpp — no HTTP overhead,
 * model stays loaded between requests.
 *
 * KV cache reuse: a persistent LlamaChatSession is kept. When incoming
 * messages are a prefix-extension of the session's current history
 * (common case: harness loop iterations appending to history), we just
 * call prompt() with the new user message — KV cache is preserved
 * across iterations. When messages don't match, we reset.
 *
 * All calls serialized through a queue since we have a single sequence.
 */

import {
  getLlama,
  LlamaChatSession,
  resolveChatWrapper,
  type Llama,
  type LlamaModel,
  type LlamaContext,
  type LlamaContextSequence,
  type ChatWrapper,
} from "node-llama-cpp";
import type {
  LLMConfig,
  LLMClient,
  ChatMessage,
  LLMResponse,
  ChatOptions,
} from "./types.js";
import {
  convertToolsToFunctions,
  convertToolCallToOpenAI,
  type CapturedCall,
} from "./tool-calls.js";
import { debug } from "./debug.js";

let llamaInstance: Llama | null = null;
let loadedModel: LlamaModel | null = null;
let modelContext: LlamaContext | null = null;
let modelSequence: LlamaContextSequence | null = null;
let loadedModelPath: string | null = null;

let activeSession: LlamaChatSession | null = null;
let activeSystemPrompt: string = "";
let activeHistory: ChatHistoryItem[] = [];
let activePreserveThinking: boolean | null = null;

function buildChatWrapper(
  model: LlamaModel,
  preserveThinking: boolean,
): ChatWrapper {
  return resolveChatWrapper(model, {
    customWrapperSettings: {
      qwen: { keepOnlyLastThought: !preserveThinking },
    },
  });
}

async function ensureModel(config: LLMConfig): Promise<{
  model: LlamaModel;
  context: LlamaContext;
  sequence: LlamaContextSequence;
}> {
  const modelPath = config.modelPath!;

  if (
    loadedModel &&
    modelContext &&
    modelSequence &&
    loadedModelPath === modelPath
  ) {
    return {
      model: loadedModel,
      context: modelContext,
      sequence: modelSequence,
    };
  }

  if (activeSession) {
    activeSession = null;
    activeSystemPrompt = "";
    activeHistory = [];
    activePreserveThinking = null;
  }
  if (modelSequence) {
    modelSequence.dispose();
    modelSequence = null;
  }
  if (modelContext) {
    await modelContext.dispose();
    modelContext = null;
  }
  if (loadedModel) {
    await loadedModel.dispose();
    loadedModel = null;
  }

  if (!llamaInstance) {
    llamaInstance = await getLlama("lastBuild");
  }

  console.log(`Loading model: ${modelPath}...`);
  loadedModel = await llamaInstance.loadModel({ modelPath });

  const contextSize = config.contextWindow
    ? { min: 8192, max: config.contextWindow }
    : { min: 8192 };

  modelContext = await loadedModel.createContext({
    contextSize,
    flashAttention: true,
  });
  modelSequence = modelContext.getSequence();
  loadedModelPath = modelPath;

  console.log(`Model loaded. Context size: ${modelContext.contextSize} tokens`);

  return { model: loadedModel, context: modelContext, sequence: modelSequence };
}

interface QueuedRequest {
  messages: ChatMessage[];
  config: LLMConfig;
  onChunk?: (token: string) => void;
  options?: ChatOptions;
  resolve: (response: LLMResponse) => void;
  reject: (error: Error) => void;
}

let queue: QueuedRequest[] = [];
let processing = false;

async function processQueue(): Promise<void> {
  if (processing) return;
  processing = true;

  while (queue.length > 0) {
    const req = queue.shift()!;
    debug("queue", `processing request, ${queue.length} more queued`);
    const start = Date.now();
    try {
      const response = await runInference(
        req.messages,
        req.config,
        req.onChunk,
        req.options,
      );
      const ms = Date.now() - start;
      debug(
        "queue",
        `completed in ${ms}ms, ${response.usage?.completionTokens ?? 0} tokens`,
      );
      req.resolve(response);
    } catch (err) {
      const ms = Date.now() - start;
      debug("queue", `failed after ${ms}ms:`, err);
      req.reject(err instanceof Error ? err : new Error(String(err)));
    }
  }

  processing = false;
}

function enqueue(
  messages: ChatMessage[],
  config: LLMConfig,
  onChunk?: (token: string) => void,
  options?: ChatOptions,
): Promise<LLMResponse> {
  return new Promise<LLMResponse>((resolve, reject) => {
    debug(
      "queue",
      `enqueue ${messages.length} messages, queue depth now ${queue.length + 1}`,
    );
    queue.push({ messages, config, onChunk, options, resolve, reject });
    processQueue();
  });
}

type ChatHistoryItem =
  | { type: "system"; text: string }
  | { type: "user"; text: string }
  | { type: "model"; response: string[] };

function convertHistory(messages: ChatMessage[]): {
  systemPrompt: string;
  priorHistory: ChatHistoryItem[];
  lastUserMessage: string;
} {
  let systemPrompt = "";
  const priorHistory: ChatHistoryItem[] = [];

  const firstSystem = messages.find((m) => m.role === "system");
  if (firstSystem) {
    systemPrompt = firstSystem.content;
  }

  const nonSystem = messages.filter((m) => m !== firstSystem);

  const formatTool = (m: ChatMessage): string => {
    const id = m.tool_call_id ? ` (id: ${m.tool_call_id})` : "";
    return `Tool result${id}:\n${m.content}`;
  };

  let lastPrompt = "";
  const historyMessages = [...nonSystem];

  for (let i = historyMessages.length - 1; i >= 0; i--) {
    const m = historyMessages[i];
    if (m.role === "user") {
      lastPrompt = m.content;
      historyMessages.splice(i, 1);
      break;
    }
    if (m.role === "tool") {
      lastPrompt = formatTool(m);
      historyMessages.splice(i, 1);
      break;
    }
  }

  for (const msg of historyMessages) {
    if (msg.role === "user") {
      priorHistory.push({ type: "user", text: msg.content });
    } else if (msg.role === "assistant") {
      let text = msg.content ?? "";
      if (msg.tool_calls && msg.tool_calls.length > 0) {
        const callsStr = msg.tool_calls
          .map(
            (tc) =>
              `[called ${tc.function.name} with ${tc.function.arguments}]`,
          )
          .join("\n");
        text = text ? `${text}\n${callsStr}` : callsStr;
      }
      priorHistory.push({ type: "model", response: [text] });
    } else if (msg.role === "tool") {
      priorHistory.push({ type: "user", text: formatTool(msg) });
    } else if (msg.role === "system") {
      priorHistory.push({ type: "user", text: msg.content });
    }
  }

  return { systemPrompt, priorHistory, lastUserMessage: lastPrompt };
}

function canReuseSession(
  systemPrompt: string,
  priorHistory: ChatHistoryItem[],
): boolean {
  if (!activeSession) return false;
  if (systemPrompt !== activeSystemPrompt) return false;
  if (priorHistory.length < activeHistory.length) return false;

  for (let i = 0; i < activeHistory.length; i++) {
    const a = activeHistory[i];
    const b = priorHistory[i];
    if (a.type !== b.type) return false;
    if (a.type === "user" && b.type === "user") {
      if (a.text !== b.text) return false;
    } else if (a.type === "model" && b.type === "model") {
      if (a.response.join("") !== b.response.join("")) return false;
    } else if (a.type === "system" && b.type === "system") {
      if (a.text !== b.text) return false;
    } else {
      return false;
    }
  }
  return true;
}

async function runInference(
  messages: ChatMessage[],
  config: LLMConfig,
  onChunk?: (token: string) => void,
  options?: ChatOptions,
): Promise<LLMResponse> {
  const { model, context, sequence } = await ensureModel(config);
  const { systemPrompt, priorHistory, lastUserMessage } =
    convertHistory(messages);

  const preserveThinking = config.preserveThinking ?? true;
  const thinkingChanged =
    activePreserveThinking !== null &&
    activePreserveThinking !== preserveThinking;

  const reuse = !thinkingChanged && canReuseSession(systemPrompt, priorHistory);

  if (!reuse) {
    sequence.eraseContextTokenRanges([
      {
        start: 0,
        end: context.contextSize,
      },
    ]);

    activeSession = new LlamaChatSession({
      contextSequence: sequence,
      chatWrapper: buildChatWrapper(model, preserveThinking),
      systemPrompt: systemPrompt || undefined,
    });
    activeSystemPrompt = systemPrompt;
    activePreserveThinking = preserveThinking;

    const fullHistory: ChatHistoryItem[] = [
      ...(systemPrompt
        ? [{ type: "system" as const, text: systemPrompt }]
        : []),
      ...priorHistory,
    ];
    if (fullHistory.length > 0) {
      activeSession.setChatHistory(fullHistory);
    }
    activeHistory = [...priorHistory];
  } else {
    if (priorHistory.length > activeHistory.length) {
      const fullHistory: ChatHistoryItem[] = [
        ...(systemPrompt
          ? [{ type: "system" as const, text: systemPrompt }]
          : []),
        ...priorHistory,
      ];
      activeSession!.setChatHistory(fullHistory);
      activeHistory = [...priorHistory];
    }
  }

  let tokenCount = 0;

  const captures: CapturedCall[] = [];
  const functions =
    options?.tools && options.tools.length > 0
      ? convertToolsToFunctions(options.tools, captures)
      : undefined;

  let grammar: import("node-llama-cpp").LlamaGrammar | undefined;
  if (options?.responseFormat && !functions) {
    const rf = options.responseFormat;
    const llama = llamaInstance!;
    if (rf.type === "json_schema") {
      grammar = await llama.createGrammarForJsonSchema(
        rf.json_schema.schema as any,
      );
    } else if (rf.type === "json_object") {
      grammar = await llama.getGrammarFor("json");
    }
  }

  const promptOptions: Record<string, unknown> = {
    temperature: config.temperature ?? 0.7,
    topP: config.topP ?? 0.9,
    maxTokens: config.maxTokens ?? 4096,
    onTextChunk: (chunk: string) => {
      tokenCount++;
      onChunk?.(chunk);
    },
  };
  if (options?.signal) {
    promptOptions.signal = options.signal;
    promptOptions.stopOnAbortSignal = true;
  }
  if (functions) {
    promptOptions.functions = functions;
  } else if (grammar) {
    promptOptions.grammar = grammar;
  }

  const session = activeSession!;
  const content = await session.prompt(
    lastUserMessage,
    promptOptions as Parameters<typeof session.prompt>[1],
  );

  activeHistory.push({ type: "user", text: lastUserMessage });
  activeHistory.push({ type: "model", response: [content] });

  const toolCalls =
    captures.length > 0 ? captures.map(convertToolCallToOpenAI) : undefined;

  return {
    content,
    finishReason: toolCalls ? "tool_calls" : "stop",
    toolCalls,
    usage: {
      promptTokens: 0,
      completionTokens: tokenCount,
      totalTokens: tokenCount,
    },
  };
}

export function createLocalProvider(config: LLMConfig): LLMClient {
  return {
    chat(
      messages: ChatMessage[],
      options?: ChatOptions,
    ): Promise<LLMResponse> {
      return enqueue(messages, config, undefined, options);
    },

    chatStream(
      messages: ChatMessage[],
      onChunk: (token: string) => void,
      options?: ChatOptions,
    ): Promise<LLMResponse> {
      return enqueue(messages, config, onChunk, options);
    },

    async listModels(): Promise<string[]> {
      return [config.model];
    },
  };
}

/** Pre-load the model so the first request doesn't pay the load cost. */
export async function preloadLocalModel(config: LLMConfig): Promise<void> {
  await ensureModel(config);
}

export function convertMessagesForTesting(messages: ChatMessage[]) {
  return convertHistory(messages);
}

export function disposeLocalProvider(): void {
  activeSession = null;
  activeSystemPrompt = "";
  activeHistory = [];
  if (modelSequence) {
    modelSequence.dispose();
    modelSequence = null;
  }
  if (modelContext) {
    modelContext.dispose();
    modelContext = null;
  }
  if (loadedModel) {
    loadedModel.dispose();
    loadedModel = null;
  }
  loadedModelPath = null;
  queue = [];
  processing = false;
}
