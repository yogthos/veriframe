import type { LLMStepOutput } from "../types.js";

export function validateAssertion(expr: string): string | null {
  const trimmed = expr.trim();
  if (!trimmed) {
    return "Assertion is empty";
  }

  if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) {
    return "Assertion must be a parenthesized SMT-LIB expression";
  }

  let depth = 0;
  for (let i = 0; i < trimmed.length; i++) {
    if (trimmed[i] === "(") depth++;
    if (trimmed[i] === ")") depth--;
    if (depth < 0) break;
  }

  if (depth !== 0) {
    return "Unbalanced parentheses";
  }

  return null;
}

function extractJson(text: string): string | null {
  const trimmed = text.trim();

  // Try extracting from markdown code fence
  const fenceMatch = trimmed.match(/```(?:json)?\s*\n([\s\S]*?)\n\s*```/);
  if (fenceMatch) {
    return fenceMatch[1].trim();
  }

  // Try finding JSON object in the text
  const jsonMatch = trimmed.match(/\{[\s\S]*\}/);
  if (jsonMatch) {
    return jsonMatch[0];
  }

  return trimmed;
}

export function parseLLMOutput(text: string): LLMStepOutput | null {
  const jsonText = extractJson(text);
  if (!jsonText) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(jsonText);
  } catch {
    return null;
  }

  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return null;
  }

  const obj = parsed as Record<string, unknown>;

  if (!("assertions" in obj) || !Array.isArray(obj.assertions)) {
    return null;
  }

  const assertions: string[] = [];
  for (const item of obj.assertions) {
    if (typeof item !== "string") return null;
    const err = validateAssertion(item);
    if (err) return null;
    assertions.push(item);
  }

  const explanation =
    typeof obj.explanation === "string" ? obj.explanation : "";
  const complete =
    typeof obj.complete === "boolean" ? obj.complete : false;

  return { explanation, assertions, complete };
}
