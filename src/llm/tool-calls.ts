/**
 * Tool calling support — bridge between OpenAI's function calling API
 * and node-llama-cpp's ChatSessionModelFunctions.
 */

import crypto from "node:crypto";

export interface OpenAITool {
  type: "function";
  function: {
    name: string;
    description?: string;
    parameters?: Record<string, unknown>;
  };
}

export interface OpenAIToolCall {
  id: string;
  type: "function";
  function: {
    name: string;
    arguments: string;
  };
}

export interface CapturedCall {
  name: string;
  params: unknown;
}

export function convertToolsToFunctions(
  tools: OpenAITool[],
  captures?: CapturedCall[],
): Record<
  string,
  { description?: string; params?: Record<string, unknown>; handler: Function }
> {
  const result: Record<
    string,
    {
      description?: string;
      params?: Record<string, unknown>;
      handler: Function;
    }
  > = {};

  for (const tool of tools) {
    if (tool.type !== "function" || !tool.function?.name) continue;
    const { name, description, parameters } = tool.function;

    const handler = (params: unknown) => {
      captures?.push({ name, params });
      return { __pending__: true, name };
    };

    result[name] = {
      description,
      params: parameters as Record<string, unknown> | undefined,
      handler,
    };
  }

  return result;
}

export function convertToolCallToOpenAI(call: CapturedCall): OpenAIToolCall {
  const id = `call_${crypto.randomUUID().replace(/-/g, "").slice(0, 24)}`;
  return {
    id,
    type: "function",
    function: {
      name: call.name,
      arguments: JSON.stringify(call.params ?? {}),
    },
  };
}
