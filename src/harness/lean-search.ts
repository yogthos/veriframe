/**
 * Premise-retrieval index over Mathlib source.
 *
 * Implements the literature's "mandatory" finding for LLM theorem
 * proving (Magnushammer ICLR'24, ReProver, LeanDojo): the model
 * needs a search-by-meaning tool to discover lemmas it didn't
 * memorise. Mathlib has ~100k named declarations; no LLM holds them
 * all by heart.
 *
 * MVP design:
 *   - Scan all .lean files under
 *     tools/lean-workspace/.lake/packages/mathlib/Mathlib/ once at
 *     first call; cache the resulting index to disk so subsequent
 *     starts are fast.
 *   - Extract each `theorem` / `lemma` / `def` / `abbrev` / `instance`
 *     with its first-line signature and any preceding `/-- ... -/`
 *     docstring.
 *   - Search is keyword/substring: tokenise both query and entry
 *     fields, score by token overlap with weights (name >> signature
 *     > docstring), return top-k.
 *
 * This is intentionally simpler than a dense embedding index. We can
 * upgrade to embeddings in Phase 2.5 if keyword recall isn't enough;
 * for now, matching the first-pass intent the model has ("a lemma
 * about Real.sqrt", "AM-GM for two variables") works well with
 * lexical signals because Mathlib's naming convention is descriptive
 * (e.g. `Real.sqrt_le_sqrt`, `Nat.add_comm`, `Finset.sum_pow`).
 */

import { readFile, writeFile, mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, "..", "..");
const MATHLIB_SOURCE = resolve(
  REPO_ROOT,
  "tools",
  "lean-workspace",
  ".lake",
  "packages",
  "mathlib",
  "Mathlib",
);
const INDEX_CACHE_PATH = resolve(REPO_ROOT, ".cache", "mathlib-index.json");

export interface LeanLemma {
  /** Fully-qualified Lean name, e.g. `Real.sqrt_le_sqrt`. */
  name: string;
  /** What kind of declaration (theorem, lemma, def, abbrev, instance). */
  kind: string;
  /** First line of the declaration (the type signature, truncated). */
  signature: string;
  /** Preceding `/-- ... -/` doc comment, if any. */
  doc: string;
  /** Mathlib-relative source path, e.g. `Analysis/SpecialFunctions/Pow/Real.lean`. */
  file: string;
  /** 1-based line number of the declaration. */
  line: number;
}

const DECL_KINDS = new Set([
  "theorem",
  "lemma",
  "def",
  "abbrev",
  "instance",
]);

// Modifiers that can appear before the kind word. We skip lines
// that contain `private` since those aren't user-facing.
const SKIP_MODIFIERS = new Set(["private"]);

function extractDocBlock(lines: string[], blockStart: number): string {
  // Walk backwards from the line *before* the declaration, collecting
  // a `/-- ... -/` block if it ends right before the decl.
  let end = blockStart - 1;
  // Skip blank lines and `@[...]` attribute lines between doc and decl.
  while (end >= 0) {
    const t = lines[end].trim();
    if (t === "" || t.startsWith("@[")) {
      end--;
      continue;
    }
    break;
  }
  if (end < 0) return "";
  if (!lines[end].trimEnd().endsWith("-/")) return "";
  // Single-line doc: `/-- foo -/`
  const single = lines[end].match(/\/--\s*([\s\S]*?)\s*-\/$/);
  if (single && lines[end].trim().startsWith("/--")) {
    return single[1].trim();
  }
  // Multi-line: walk back until the line that contains `/--`.
  let start = end;
  while (start >= 0 && !lines[start].includes("/--")) start--;
  if (start < 0) return "";
  const docLines = lines.slice(start, end + 1).join("\n");
  const m = docLines.match(/\/--\s*([\s\S]*?)\s*-\/$/);
  return (m ? m[1] : docLines).replace(/\s+/g, " ").trim();
}

const NAME_TOKEN = /[A-Za-z_][A-Za-z0-9_'.]*/;

function parseDeclLine(
  line: string,
): { kind: string; name: string; rest: string } | null {
  // Strip leading whitespace + `@[...]` attribute groups + qualifiers
  // we want to keep but not name-as-kind.
  let s = line.trimStart();
  // Repeatedly peel attributes like `@[simp]`, `@[simp, norm_cast]`.
  while (s.startsWith("@[")) {
    const close = s.indexOf("]");
    if (close < 0) return null;
    s = s.slice(close + 1).trimStart();
  }
  // Peel qualifiers like `protected`, `noncomputable`, `partial`,
  // `unsafe`, `nonrec`. Skip the line entirely if `private`.
  for (;;) {
    const tok = s.match(/^([A-Za-z_]+)\s+/);
    if (!tok) break;
    const w = tok[1];
    if (SKIP_MODIFIERS.has(w)) return null;
    if (
      w === "protected" ||
      w === "noncomputable" ||
      w === "partial" ||
      w === "unsafe" ||
      w === "nonrec"
    ) {
      s = s.slice(tok[0].length);
      continue;
    }
    break;
  }
  // Now `s` should start with a kind keyword, then whitespace, then
  // a name.
  const m = s.match(/^([A-Za-z_]+)\s+(.+)$/);
  if (!m) return null;
  const kind = m[1];
  if (!DECL_KINDS.has(kind)) return null;
  const after = m[2];
  const nameMatch = after.match(NAME_TOKEN);
  if (!nameMatch) return null;
  const name = nameMatch[0];
  const rest = after.slice(nameMatch[0].length);
  return { kind, name, rest };
}

/**
 * Track open namespaces while scanning so we can emit qualified
 * names (e.g. `Real.sqrt_nonneg`, not just `sqrt_nonneg`). Mathlib
 * uses `namespace X ... end X` extensively; without this we lose
 * the disambiguation between four different `sqrt_nonneg` lemmas
 * across ℝ, ℚ, ℤ, and a generic ring.
 *
 * `_root_.foo` declares a name *outside* the current namespace —
 * we honour that by skipping the prefix.
 */
function applyNamespaceLine(
  stack: string[],
  line: string,
): void {
  const trimmed = line.trimStart();
  // `namespace Foo` (one token after the keyword)
  const nsOpen = trimmed.match(/^namespace\s+([A-Za-z_][A-Za-z0-9_'.]*)/);
  if (nsOpen) {
    stack.push(nsOpen[1]);
    return;
  }
  // `end Foo` matches the most recently-opened `Foo`. If the names
  // mismatch we still pop one — Lean enforces matching but we
  // tolerate noise rather than skip the file.
  const nsClose = trimmed.match(/^end(?:\s+([A-Za-z_][A-Za-z0-9_'.]*))?\s*$/);
  if (nsClose) {
    if (stack.length > 0) stack.pop();
  }
}

function qualifiedName(stack: string[], localName: string): string {
  if (localName.startsWith("_root_.")) return localName.slice("_root_.".length);
  if (stack.length === 0) return localName;
  return stack.join(".") + "." + localName;
}

async function scanFile(absPath: string, mathlibRoot: string, out: LeanLemma[]): Promise<void> {
  let text: string;
  try {
    text = await readFile(absPath, "utf8");
  } catch {
    return;
  }
  const lines = text.split("\n");
  const file = relative(mathlibRoot, absPath);
  const nsStack: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    // Update namespace stack first so the decl on the same line, if
    // any (unlikely), would inherit. In practice `namespace X` lives
    // on its own line.
    applyNamespaceLine(nsStack, lines[i]);
    const parsed = parseDeclLine(lines[i]);
    if (!parsed) continue;
    const fullName = qualifiedName(nsStack, parsed.name);
    let sig = (parsed.kind + " " + parsed.name + parsed.rest)
      .replace(/\s+/g, " ")
      .trim();
    if (sig.length < 60) {
      let j = i + 1;
      while (j < lines.length && j - i < 5 && sig.length < 280) {
        const next = lines[j].trim();
        if (!next || next.startsWith("/-") || next.startsWith("--")) break;
        sig = (sig + " " + next).replace(/\s+/g, " ");
        if (sig.includes(":=")) break;
        j++;
      }
    }
    if (sig.length > 280) sig = sig.slice(0, 280) + " …";
    const doc = extractDocBlock(lines, i);
    out.push({
      name: fullName,
      kind: parsed.kind,
      signature: sig,
      doc,
      file,
      line: i + 1,
    });
  }
}

async function walkLeanFiles(dir: string): Promise<string[]> {
  const { readdir } = await import("node:fs/promises");
  const acc: string[] = [];
  async function walk(d: string): Promise<void> {
    let entries: import("node:fs").Dirent[];
    try {
      entries = await readdir(d, { withFileTypes: true });
    } catch {
      return;
    }
    for (const ent of entries) {
      const full = join(d, ent.name);
      if (ent.isDirectory()) {
        await walk(full);
      } else if (ent.isFile() && ent.name.endsWith(".lean")) {
        acc.push(full);
      }
    }
  }
  await walk(dir);
  return acc;
}

let inMemoryIndex: LeanLemma[] | null = null;
let inFlight: Promise<LeanLemma[]> | null = null;

/**
 * Load the index. First call (per process) reads from disk cache if
 * present, otherwise scans Mathlib. Subsequent calls are O(1).
 */
export async function getIndex(opts?: { force?: boolean }): Promise<LeanLemma[]> {
  if (inMemoryIndex && !opts?.force) return inMemoryIndex;
  if (inFlight && !opts?.force) return inFlight;
  inFlight = (async () => {
    if (!opts?.force && existsSync(INDEX_CACHE_PATH)) {
      try {
        const buf = await readFile(INDEX_CACHE_PATH, "utf8");
        const parsed = JSON.parse(buf) as LeanLemma[];
        if (Array.isArray(parsed) && parsed.length > 0) {
          inMemoryIndex = parsed;
          return parsed;
        }
      } catch {
        /* fall through to a fresh scan */
      }
    }
    const idx = await rebuildIndex();
    try {
      await mkdir(dirname(INDEX_CACHE_PATH), { recursive: true });
      await writeFile(INDEX_CACHE_PATH, JSON.stringify(idx));
    } catch {
      /* best-effort cache write */
    }
    inMemoryIndex = idx;
    return idx;
  })();
  try {
    return await inFlight;
  } finally {
    inFlight = null;
  }
}

export async function rebuildIndex(): Promise<LeanLemma[]> {
  if (!existsSync(MATHLIB_SOURCE)) {
    throw new Error(
      `Mathlib source not found at ${MATHLIB_SOURCE} — run \`lake update\` in tools/lean-workspace first.`,
    );
  }
  const files = await walkLeanFiles(MATHLIB_SOURCE);
  const out: LeanLemma[] = [];
  // Process files in parallel with bounded concurrency to avoid
  // hammering the FS / blowing memory.
  const CONCURRENCY = 16;
  let cursor = 0;
  await Promise.all(
    Array.from({ length: CONCURRENCY }, async () => {
      while (cursor < files.length) {
        const i = cursor++;
        await scanFile(files[i], MATHLIB_SOURCE, out);
      }
    }),
  );
  return out;
}

// ---------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------

// Tokens that appear in nearly every signature and a fair chunk of
// natural-language queries — they shouldn't drive ranking. Without
// this filter, a query like "is there a lemma about X" would match
// every lemma in the index via the keyword "lemma".
const STOPWORDS = new Set([
  // Lean kind keywords (appear in every signature).
  "theorem",
  "lemma",
  "def",
  "abbrev",
  "instance",
  "example",
  // Tactic / proof-mode keywords.
  "by",
  "show",
  "have",
  "fun",
  "where",
  // English filler the model might include in queries.
  "a",
  "an",
  "the",
  "of",
  "for",
  "is",
  "are",
  "in",
  "on",
  "to",
  "and",
  "or",
  "with",
  "about",
  "that",
  "this",
]);

/**
 * Tokenise a string Mathlib-aware: split on whitespace, dots,
 * underscores, hyphens, *and* camelCase boundaries. So
 * `Real.sqrt_nonneg` and `addComm` both yield matchable tokens
 * even when the query writes them as separate words. Stopwords are
 * filtered out so they don't drown out signal.
 */
function tokenise(s: string): string[] {
  return s
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[._\-']+/g, " ")
    .toLowerCase()
    .split(/\s+/)
    .filter((t) => /^[a-z][a-z0-9]*$/.test(t))
    .filter((t) => !STOPWORDS.has(t));
}

/**
 * Score one lemma against query tokens. Weights:
 *   - name token match: 8
 *   - signature token match: 2
 *   - doc token match: 1
 * A tiny bonus for matching the *whole* query in the name (substring).
 */
function scoreLemma(lemma: LeanLemma, qTokens: string[], qStr: string): number {
  if (qTokens.length === 0) return 0;
  const nameTokens = new Set(tokenise(lemma.name));
  // Gate on the name: at least one query token must appear in the
  // name OR the raw query must be a substring of the name. Without
  // this, signature/doc matches alone surface false positives (e.g.
  // a query of nonsense words that happens to overlap a stopword).
  let nameMatches = 0;
  for (const q of qTokens) if (nameTokens.has(q)) nameMatches++;
  const nameSub = lemma.name.toLowerCase().includes(qStr.toLowerCase());
  if (nameMatches === 0 && !nameSub) return 0;

  const sigTokens = new Set(tokenise(lemma.signature));
  const docTokens = new Set(tokenise(lemma.doc));
  let s = 0;
  for (const q of qTokens) {
    if (nameTokens.has(q)) s += 8;
    if (sigTokens.has(q)) s += 2;
    if (docTokens.has(q)) s += 1;
  }
  if (nameSub) s += 4;
  // Penalise extremely generic names ("foo", "bar") — heuristic.
  if (lemma.name.length < 3) s = Math.max(0, s - 4);
  return s;
}

export interface SearchResult {
  lemma: LeanLemma;
  score: number;
}

export async function searchLemmas(
  query: string,
  topK: number = 10,
): Promise<SearchResult[]> {
  const idx = await getIndex();
  const qStr = query.trim();
  if (!qStr) return [];
  const qTokens = tokenise(qStr);
  if (qTokens.length === 0) {
    // Fallback: substring match on the raw query.
    const subs = idx
      .filter((l) => l.name.toLowerCase().includes(qStr.toLowerCase()))
      .slice(0, topK);
    return subs.map((l) => ({ lemma: l, score: 1 }));
  }
  // Score everything; pick top-k via partial sort.
  const scored: SearchResult[] = [];
  for (const lemma of idx) {
    const score = scoreLemma(lemma, qTokens, qStr);
    if (score > 0) scored.push({ lemma, score });
  }
  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, topK);
}

/**
 * Extract retrieval hints from a failed Lean check.
 *
 * Implements the literature's premise-selection signal: when Lean
 * rejects a proof, the *goal state* it surfaces is a far more
 * informative search query than the natural-language claim. ReProver
 * (Yang et al., NeurIPS '23) and Magnushammer (Mikuła et al., ICLR '24)
 * both retrieve from the proof obligation, not the high-level claim.
 *
 * We parse three diagnostic kinds:
 *   - `Tactic.unsolvedGoals`: the `⊢ <goal>` text — the actual statement
 *     that needs proving.
 *   - `*unknownIdentifier*`: the backticked missing name — direct lookup.
 *   - Type-mismatch: the expected type, if cleanly extractable.
 *
 * Returns an ordered list of (source, query) tuples; the agent runs
 * each as a separate `lean_search` and surfaces the union to the
 * model. If we can't parse any hint we return [] (the agent's
 * auto-suggest then doesn't fire — better than spamming irrelevant
 * lemmas on, e.g., a pure syntax error).
 */
export interface SearchHint {
  source: "goal" | "unknown_identifier" | "expected_type";
  query: string;
}

export function extractSearchHints(
  diagnostics: { severity: string; message: string; kind?: string }[],
): SearchHint[] {
  const seen = new Set<string>();
  const out: SearchHint[] = [];
  function push(h: SearchHint): void {
    const key = `${h.source}:${h.query}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push(h);
  }
  for (const d of diagnostics) {
    if (d.severity !== "error") continue;
    const kind = d.kind ?? "";
    const msg = d.message ?? "";
    // 1. Unsolved goals — extract the goal expression after ⊢.
    if (
      kind === "Tactic.unsolvedGoals" ||
      /^unsolved goals/i.test(msg)
    ) {
      // The first ⊢-prefixed line is the bare goal; if there are
      // multiple goals (`⊢ A` then `⊢ B`), each gives a separate hint.
      const goalLines = msg.match(/⊢\s*[^\n]+/g);
      if (goalLines) {
        for (const g of goalLines.slice(0, 2)) {
          const stripped = g.replace(/^⊢\s*/, "").trim();
          if (stripped) push({ source: "goal", query: stripped });
        }
      }
    }
    // 2. Unknown identifier — `kind` varies across Lean versions; we
    //    also scan the message text as a fallback.
    if (/unknownIdentifier/i.test(kind) || /Unknown identifier/i.test(msg)) {
      const m = msg.match(/Unknown identifier\s*[`'"]([^`'"]+)[`'"]/i);
      if (m) push({ source: "unknown_identifier", query: m[1] });
    }
    // 3. Type mismatch — pull the "expected to have type" target.
    if (/^Type mismatch/i.test(msg)) {
      // Format: "Type mismatch\n  <term>\nhas type\n  T1\nbut is expected to have type\n  T2"
      const m = msg.match(
        /expected to have type\s*\n?\s*([^\n]+)/i,
      );
      if (m) push({ source: "expected_type", query: m[1].trim() });
    }
  }
  return out;
}

/**
 * Pretty-print a search result for the model.
 */
export function formatLemma(l: LeanLemma): string {
  const docPart = l.doc ? `\n    ${l.doc.slice(0, 200)}${l.doc.length > 200 ? "…" : ""}` : "";
  return `${l.name}\n    ${l.signature}${docPart}\n    [${l.file}:${l.line}]`;
}
