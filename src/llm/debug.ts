/**
 * Lightweight debug logger gated by the DEBUG env var.
 * DEBUG=* enables every category; DEBUG=queue,model enables those two.
 */

const enabled = (() => {
  const raw = process.env.DEBUG;
  if (!raw) return new Set<string>();
  return new Set(raw.split(",").map((s) => s.trim()).filter(Boolean));
})();

const allOn = enabled.has("*");

export function debug(category: string, ...args: unknown[]): void {
  if (!allOn && !enabled.has(category)) return;
  console.error(`[${category}]`, ...args);
}
