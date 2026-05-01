# reasoning-harness

OpenAI-compatible HTTP server that wraps an LLM in a **claim-first verification loop** backed by three reasoning engines: **SWI-Prolog** (with CLP(FD)), **Z3 SMT**, and **Lean 4 + Mathlib**. The model commits to a one-sentence claim, the harness checks it, the model accumulates verified facts. The architecture follows the literature's settled findings on LLM-driven theorem proving and constraint solving (LeanDojo, Magnushammer, ReProver, ProB+Z3, Logic-LM).

Three classes of problem are first-class:

- **Relational / finite-domain CSPs** (knights & knaves, zebra, sudoku) — SWI-Prolog with `library(clpfd)`.
- **Numerical / theory-rich constraints** (linear/nonlinear arithmetic, bitvectors, optimisation) — Z3 4.15 via `execSync`.
- **Mathematical theorems** (induction, ε-δ, real analysis, group theory, number theory) — Lean 4 + Mathlib via a long-lived `leanprover-community/repl` subprocess. Proofs run **stepwise**, the way a human writes one in tactic mode.

## Tool surface

The model picks tools per claim:

```
add_rule({name?, code})        — Prolog facts/rules; named = retractable, anonymous = permanent
retract_rule({name})           — undo a tentative named rule
commit({name})                 — lock a named rule in (no longer retractable)
verify({claim, check})         — Prolog goal that succeeds iff the claim holds
verify_smt({claim, smtlib})    — Z3 sat/unsat check
verify_lean({claim, lean})     — one-shot Lean snippet against Mathlib
lean_search({query, top_k?})   — retrieval over Mathlib's ~235k declarations
proof_start({claim, theorem})  — open a stateful Lean proof session
proof_step({tactic, claim?})   — apply ONE tactic; returns new goal state
proof_state()                  — inspect current proof
proof_undo({steps?})           — roll back N tactics (sub-second; REPL retains state)
proof_close()                  — verify all goals discharged (optional — auto-finalised on close)
proof_abandon()                — drop the active proof session
assume({name, fact})           — open a hypothetical scope (for "if A then B" proofs)
discharge({name})              — close the scope
done({answer})                 — submit the final answer (with the verified Lean proof appended)
give_up({reason})              — bail
```

A **stuck-detection heuristic** injects a "rethink the step / retract / decompose" hint after 3 consecutive failed verifies; auto-suggests Mathlib lemmas drawn from the *failed proof goal*, not the natural-language claim (per the ReProver / Magnushammer signal).

## Validated benchmarks

Six-for-six on math theorems with **GLM-5.1**:

| problem | turns | time | tools used |
|---|---|---|---|
| AM-GM (∀ x y ≥ 0, 4xy ≤ (x+y)²) | 3 | 42s | 1× verify_lean (`nlinarith [sq_nonneg (x-y)]`) |
| sum of evens is even | 6 | 55s | 1× verify_lean (`obtain`/`use`/`linarith`) |
| Euclid (∞ many primes) | 5 | 43s | 2× lean_search + verify_lean |
| 2^n > n by induction | 7 | 84s | proof_start + 5× proof_step |
| √2 is irrational | 9 | 174s | 2× lean_search + 3× verify_lean |
| Gauss `2·Σi = n(n+1)` | 17 | 94s | 8× lean_search + proof_start + 5× proof_step |

The Gauss run especially illustrates the literature's premise-selection pattern: the model spent half its turns on `lean_search` narrowing in on `Finset.range_succ`, then proved the theorem in 4 tactics.

## Install

```bash
npm install
```

### Lean toolchain (required for theorem proving)

```bash
# 1. Install elan (Lean's version manager)
curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh
source $HOME/.elan/env

# 2. Fetch + build Mathlib in the workspace (~10GB, one-time)
cd tools/lean-workspace && lake update && cd ../..

# 3. Fetch + build leanprover-community/repl (one-time, ~30s)
./tools/setup-lean-repl.sh
```

### Z3

Already on most macOS dev machines. If not:

```bash
brew install z3
```

### LLM provider

**Local (Qwen via node-llama-cpp)**: drop a GGUF into `models/`. Default config expects `models/Qwen3.6-35B-A3B-Q8_0.gguf`. Run with `./start.sh`.

**GLM-5.1 (Zhipu BigModel)**: export `ZHIPU_API_KEY`, then `./start-glm.sh`.

## Run

```bash
./start-glm.sh   # GLM-5.1, default port 3001
# or
./start.sh       # local Qwen
```

The server preloads the LLM (local) or validates the API key (GLM), then exposes the standard OpenAI shape at `/v1/chat/completions`. Lean's REPL is spawned lazily on the first proof tool call (~10-30s warm-up for `import Mathlib`); subsequent proof steps are sub-second.

## Make a request

```bash
curl -sS -X POST http://localhost:3001/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "glm-5.1",
    "messages": [{"role": "user", "content": "Prove that 2^n > n for all natural numbers n."}],
    "mode": "agent"
  }' | jq
```

Or use the per-problem driver against the registry:

```bash
npx tsx scripts/agent-only.ts math-induction-pow2-gt-n
npx tsx scripts/agent-only.ts math-gauss-sum
npx tsx scripts/agent-only.ts knights-3
npx tsx scripts/agent-only.ts zebra-5x5
```

The response body has `choices[].message.content` (the model's natural-language answer + the verified Lean proof) and a non-standard `harness` field with the per-step trace.

### Bypass the harness

Send `"raw": true` to call the model directly:

```bash
curl -sS -X POST http://localhost:3001/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages": [{"role": "user", "content": "..."}], "raw": true}'
```

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `HARNESS_PROVIDER` | `local` (or `glm` if `ZHIPU_API_KEY` is set) | `local` / `glm` |
| `HARNESS_MODEL_PATH` | — | GGUF path (local provider only) |
| `HARNESS_MODEL` | `local-model` / `glm-5.1` | Model name for the wire payload |
| `ZHIPU_API_KEY` | — | Required when `HARNESS_PROVIDER=glm` |
| `HARNESS_PORT` | `3000` | HTTP port |
| `HARNESS_MAX_TOKENS` | `4096` (`16384` for GLM) | Per-LLM-call output cap |
| `HARNESS_TIMEOUT_MS` | `300000` | Per-LLM-call wall clock |
| `HARNESS_LEAN_WORKSPACE` | `tools/lean-workspace` | Override the Lean workspace path |
| `HARNESS_LEAN_REPL_BIN` | `tools/lean-repl/.lake/build/bin/repl` | Override the Lean REPL binary path |
| `HARNESS_LEAN_TIMEOUT_MS` | `120000` | Per-Lean-call timeout (fallback for `lake env lean` path) |

Per-request fields (in the JSON body):

| Field | Default | Notes |
|---|---|---|
| `mode` | `agent` | `agent` runs the verification loop; `raw: true` bypasses |
| `max_turns` | `80` | Hard cap on tool-call turns before the run is failed |

## Tests

```bash
npm run test:run    # 69 tests across prolog / smt / lean / lean-search / lean-proof / agent helpers
npm run typecheck
```

## Project layout

```
src/
  bin/server.ts            Entry — preload + HTTP listen
  server.ts                OpenAI-compatible routes
  config.ts                env → ServerConfig
  harness/
    agent.ts               REPL-style tool-call loop + verification
    prolog.ts              SWI-Prolog wrapper (in-process WASM via prolog-wasm-full)
    smt.ts                 Z3 wrapper (execSync to system binary)
    lean.ts                One-shot Lean wrapper (lake env lean --json)
    lean-search.ts         Mathlib premise-retrieval index (keyword)
    lean-proof.ts          Stateful Lean proof sessions
    lean-repl.ts           Long-lived leanprover-community/repl subprocess
  llm/
    local.ts               node-llama-cpp provider (Qwen / Mistral / Llama family)
    glm.ts                 GLM-5.1 (Zhipu BigModel) HTTP provider
    types.ts               ChatMessage, LLMResponse, etc.
scripts/
  agent-only.ts            Run one benchmark problem against the harness
  compare.ts               Direct-vs-harness side-by-side
  problems.ts              Benchmark problem registry (puzzles + math theorems)
tools/
  lean-workspace/          Lean project pinning Mathlib v4.29.1
  lean-repl/               leanprover-community/repl (built by setup-lean-repl.sh)
  setup-lean-repl.sh       Idempotent setup script
tests/                     Vitest unit + integration tests
```

## Architectural notes

- **Claim-first verification.** Every `verify*` tool requires a one-sentence natural-language `claim` alongside the formal check. The model commits to the *idea* before writing the *check* — that's the lever that makes the rest work. Without it, the model defaults to "write a giant solver and hope" (well-documented failure mode in BiasBusters / Tool-Augmented LLMs).

- **Premise retrieval is mandatory, not optional.** Mathlib has ~235k declarations; no LLM holds them by heart. `lean_search` is the literature's "settled science" for LLM theorem proving, and we wire it both as a model-callable tool and as an automatic suggestion when `verify_lean` fails (the search query is built from Lean's diagnostic, not the NL claim — the ReProver / Magnushammer signal).

- **Stepwise proof state via long-lived REPL.** Each `proof_step` is sub-second after the one-time Mathlib import. The REPL retains all earlier `proofState` IDs by integer, so `proof_undo` is a no-execution rollback.

- **Multi-engine, not all-purpose.** SWI-Prolog handles relational reasoning best; Z3 handles theory-heavy SMT best; Lean handles math-with-named-lemmas best. The architecture lets the model route per problem class — Logic-LM showed deterministic dispatch outperforms LLM-choice, but in practice GLM-5.1 picks correctly when the tools are well-described.

## License

Apache-2.0
