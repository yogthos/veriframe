/**
 * LLM provider types — compatible with the rlm-sandbox provider layer
 * so the same local/openai/ollama implementations can be reused.
 */

export type ProviderType = "local" | "openai" | "ollama" | "deepseek" | "glm";

export interface LLMConfig {
  provider?: ProviderType;
  /** For local inference: path to GGUF file. */
  modelPath?: string;
  /** For remote inference: base URL. */
  baseUrl?: string;
  apiKey?: string;
  /** Model name (used for remote backends and API responses). */
  model: string;
  maxTokens?: number;
  temperature?: number;
  topP?: number;
  timeoutMs?: number;
  /** Context window size in tokens (local: passed to createContext). */
  contextWindow?: number;
  /** GPU layers to offload (-1 = all, 0 = CPU only). */
  gpuLayers?: number;
  /**
   * Preserve the model's chain-of-thought across turns (Qwen 3.x).
   * Maps to `QwenChatWrapper.keepOnlyLastThought = !preserveThinking`.
   * Default: true.
   */
  preserveThinking?: boolean;
}

export interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  tool_calls?: Array<{
    id: string;
    type: "function";
    function: { name: string; arguments: string };
  }>;
  tool_call_id?: string;
}

export interface LLMResponse {
  content: string;
  finishReason: string;
  toolCalls?: Array<{
    id: string;
    type: "function";
    function: { name: string; arguments: string };
  }>;
  usage?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  };
}

export interface ChatOptions {
  tools?: Array<{
    type: "function";
    function: {
      name: string;
      description?: string;
      parameters?: Record<string, unknown>;
    };
  }>;
  toolChoice?:
    | "auto"
    | "none"
    | "required"
    | { type: "function"; function: { name: string } };
  responseFormat?:
    | { type: "json_object" }
    | {
        type: "json_schema";
        json_schema: {
          schema: Record<string, unknown>;
          name?: string;
          strict?: boolean;
        };
      };
  signal?: AbortSignal;
}

export interface LLMClient {
  chat(messages: ChatMessage[], options?: ChatOptions): Promise<LLMResponse>;
  chatStream?(
    messages: ChatMessage[],
    onChunk: (token: string) => void,
    options?: ChatOptions,
  ): Promise<LLMResponse>;
  listModels(): Promise<string[]>;
}
