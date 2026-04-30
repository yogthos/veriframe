# reasoning-harness

OpenAI-compatible HTTP server that wraps a local GGUF model in a Z3-verified reasoning loop. The model translates natural-language problems to SMT-LIB; Z3 does the search and verifies the answer with a machine-checkable certificate (a model + uniqueness proof, or a minimal unsat core).

## Why this exists

Demonstrated on a 3-disk Tower of Hanoi puzzle modified to forbid disk D2 from peg B — solvable in 11 moves but with a tempting impossibility trap (see [docs/benchmark.md](docs/benchmark.md), problem 18):

- **Direct Qwen 3.6 35B** confidently emits a 10-move "solution" with a self-described "verification" step. The sequence's move 6 is illegal (it moves D2 while D1 sits on top), but the model's verification only checks which pegs D2 visited, missing the move-legality violation. *Wrong answer (sequence illegal), no signal of trouble.*
- **The step-based harness** forces the model to commit to encoded constraints step by step. On the same prompt, the harness's prose argues the puzzle is *impossible*: "D3 must reach C, which requires C empty and D1+D2 elsewhere; D2 can't be on B; ergo D2 blocks either D3's source or destination." This argument silently assumes D3 moves A→C directly — but D3 can route through B. *Wrong answer (claimed impossible).*
- **The REPL-style agent with the `setup_planning` tool** ([`mode: "agent"`](#agent-mode-repl-style) in the API) handles state-transition planning by factoring the work between the model and the harness: the model provides the domain content (state variables, action specs, legality predicates, goal); the harness handles the universal planning machinery (per-timestep variable replication, transition disjunctions, frame axioms, K-iteration). On the same prompt the agent iterates K = 9 → 10 → 11, finds SAT at K=11, and the harness extracts a Z3-verified 11-move plan that confirms unique on negation. *Right answer with a machine-checkable certificate.*

The factoring matters: prompt-only agents either skip the transition machinery (vacuous one-step horizons) or write fragile encodings Z3 returns `unknown` on. Pure tool injection without model content would just be a hard-coded planner. Splitting the work — universal structure in tools, domain interpretation in the model — is what lets the system handle a puzzle class the model fundamentally can't autoformalize on its own.

For routine puzzles where direct already gets it right, the harness's value is the machine-checkable certificate it adds (Z3 model + uniqueness proof, or a minimal unsat core) — useful when you need to trust the answer downstream.

## Install

```bash
npm install
```

Drop a GGUF model into `models/`. The default config expects `models/Qwen3.6-35B-A3B-Q8_0.gguf`; any node-llama-cpp-compatible GGUF will work.

## Run the server

```bash
HARNESS_MODEL_PATH=models/Qwen3.6-35B-A3B-Q8_0.gguf \
HARNESS_MAX_TOKENS=49152 \
HARNESS_PORT=3001 \
npm start
```

Or use the included `./start.sh` which sets reasonable defaults. The server preloads the model at startup; first request after `Listening on http://0.0.0.0:3001` will run instantly.

## Make a request

The server exposes the standard OpenAI shape at `/v1/chat/completions`:

```bash
curl -sS -X POST http://localhost:3001/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "local-model",
    "messages": [{"role": "user", "content": "Sally has 3 brothers. Each brother has 2 sisters. How many sisters does Sally have?"}]
  }' | jq
```

The response body has the usual `choices[].message.content` (the verified prose answer), plus a non-standard `harness` field with the Z3 trace:

```json
{
  "id": "chatcmpl-…",
  "choices": [{"message": {"role": "assistant", "content": "Step 1: …\nZ3-verified assignment (UNIQUE …):\n  sally_sisters = 1"}, "finish_reason": "stop"}],
  "harness": {
    "status": "completed",
    "steps": 2,
    "trace": [{"stepNumber": 1, "assertions": ["(declare-const …)", …], "status": "accepted"}, …],
    "verification": {"model": {"sally_sisters": "1", …}, "unique": true}
  }
}
```

`status` is `"completed"` (SAT, with model + uniqueness flag), `"unsat"` (constraints mutually inconsistent, with `unsatCore`), or `"failed"` (parse / retry exhaustion).

### How the harness loop works

The model issues one tool call per turn against a persistent Z3 solver — the way a programmer iterates against a REPL. Tools available:

- `add_smt({code})` — append SMT-LIB to the solver. Anything goes: declarations, assertions, multi-statement chunks. Use `(assert (! ... :named foo))` to make assertions retractable later.
- `view_smt()` — show all chunks added so far.
- `retract({name})` — remove the chunk containing the named assertion; solver is rebuilt from the remaining chunks.
- `check_sat()` — runs `(check-sat)`. Returns `sat` plus the model, `unsat` plus the unsat core, or `unknown`.
- `eval({expr})` — evaluate a variable in the current model (after a sat check).
- `setup_planning({spec})` — generate the boilerplate for a bounded state-transition planning problem (per-timestep variables, transition disjunctions with frame axioms, invariants, initial/goal). The agent provides a structured spec; the harness lays down the universal planning machinery so the model only writes the legality predicates per action. Re-calling with a higher horizon auto-retracts the prior planning chunk, so the standard UNSAT-on-K iteration is just `setup_planning K=N → check_sat → setup_planning K=N+1`.
- `done({answer})` — finalize. The harness then re-runs `(check-sat)` and a uniqueness probe (asserting the negation of the model in a temporary frame); the verification verdict is appended to the response.
- `give_up({reason})` — stop with a stated reason.

The conversation is maintained as proper multi-turn messages so the local LLM provider's KV cache extends across turns. There's no imposed workflow — the model uses the tools the way it would naturally use a REPL. See [docs/benchmark.md](docs/benchmark.md) for the case studies, especially problem 18 (modified Tower of Hanoi) where this approach is the only one that produces a correct + Z3-verified answer.

#### `setup_planning` spec shape

```json
{
  "horizon": 11,
  "state_vars": [
    {"name": "d1", "sort": "Int", "domain": [0, 2]},
    {"name": "d2", "sort": "Int", "domain": [0, 2]}
  ],
  "initial": {"d1": 0, "d2": 0},
  "goal":    {"d1": 2, "d2": 2},
  "invariants": ["(not (= d2_t 1))"],
  "actions": [
    {"name": "move_d1", "changes": ["d1"], "predicate": "(not (= d1_t d1_tp1))"},
    {"name": "move_d2", "changes": ["d2"], "predicate": "(and (not (= d2_t d2_tp1)) (not (= d1_t d2_t)) (not (= d1_t d2_tp1)))"}
  ]
}
```

In each action `predicate` and in `invariants`, reference state variables with the suffix `_t` (current state) and `_tp1` (next state). The harness substitutes the concrete timestep numbers and emits frame axioms `(= var_t var_tp1)` for each state var NOT in the action's `changes` list.

### Bypass the harness

Send `"raw": true` in the body to call the model directly without the Z3 loop:

```bash
curl -sS -X POST http://localhost:3001/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages": [{"role": "user", "content": "..."}], "raw": true}'
```

Useful for A/B comparison.

### Other endpoints

```
GET  /health           → {"status": "ok"}
GET  /v1/models        → OpenAI-format model list
```

## Run the benchmark suite

`scripts/compare.ts` posts a chosen problem twice — once raw, once harnessed — and prints both outputs side by side.

```bash
# pick from the problems registered in scripts/problems.ts
npx tsx scripts/compare.ts sudoku-hard
npx tsx scripts/compare.ts zebra-5x5
npx tsx scripts/compare.ts car-wash-decision
```

Results land in `/tmp/bench-<id>.log` (full transcript) and `/tmp/bench-<id>.json` (parsed result + harness trace). Full per-problem write-up in [docs/benchmark.md](docs/benchmark.md).

## Configuration

All settings via env vars (with sensible defaults):

| Variable | Default | Notes |
| --- | --- | --- |
| `HARNESS_MODEL_PATH` | — | Path to the GGUF file. Required. |
| `HARNESS_PORT` | `3000` | HTTP port. |
| `HARNESS_HOST` | `0.0.0.0` | Bind address. |
| `HARNESS_MAX_TOKENS` | `4096` | Max output tokens per LLM call. **Bump to 24576+ for hard puzzles** (Sudoku, Zebra) — Qwen's `<think>` block can be long. |
| `HARNESS_CONTEXT_WINDOW` | `131072` | Context size passed to llama.cpp. |
| `HARNESS_TEMPERATURE` | `0.7` | Sampling temperature. |
| `HARNESS_PRESERVE_THINKING` | `true` | Keep prior `<think>` blocks across turns (Qwen 3.x). |
| `HARNESS_GPU_LAYERS` | auto | Offloaded layers; `-1` = all, `0` = CPU only. |

Harness-loop knobs (set per-request via JSON body):

| Field | Default | Notes |
| --- | --- | --- |
| `max_turns` | `40` | Hard cap on tool-call turns the agent gets before the run is failed as "exhausted". |
| `raw` | `false` | Bypass the harness; call the model directly. |

## Tests

```bash
npm test           # vitest watch mode
npm run test:run   # one-shot
npm run typecheck
```

## Project layout

```
src/
  bin/server.ts          Entry: preload model + start HTTP server
  server.ts              OpenAI-compatible HTTP routes
  config.ts              env-var → ServerConfig
  harness/
    agent.ts             REPL-style tool-call loop, verification, persistent solver
    agent-planning.ts    setup_planning skeleton generator
    solver.ts            Z3 incremental wrapper
  llm/
    local.ts             node-llama-cpp provider with KV-cache reuse
    types.ts             ChatMessage, LLMResponse, etc.
    tool-calls.ts        OpenAI tools ↔ llama.cpp functions bridge
scripts/
  compare.ts             Direct-vs-harness comparison driver
  agent-only.ts          Skip-direct driver for fast agent-only iteration
  problems.ts            Benchmark problem registry
docs/
  benchmark.md           Per-problem benchmark write-up
tests/                   Vitest unit tests for solver and planning generator
```

## License

Apache-2.0
