# reasoning-harness

OpenAI-compatible HTTP server that wraps a local GGUF model in a Z3-verified reasoning loop. The model translates natural-language problems to SMT-LIB; Z3 does the search and verifies the answer with a machine-checkable certificate (a model + uniqueness proof, or a minimal unsat core).

## Why this exists

Demonstrated on a 3-disk Tower of Hanoi puzzle modified to forbid disk D2 from peg B — solvable in 11 moves but with a tempting impossibility trap (see [docs/benchmark.md](docs/benchmark.md), problem 18):

- **Direct Qwen 3.6 35B** confidently emits a 10-move "solution" with a self-described "verification" step. The sequence's move 6 is illegal (it moves D2 while D1 sits on top), but the model's verification only checks which pegs D2 visited, missing the move-legality violation. *Wrong answer (sequence illegal), no signal of trouble.*
- **The step-based harness** forces the model to commit to encoded constraints step by step. On the same prompt, the harness's prose argues the puzzle is *impossible*: "D3 must reach C, which requires C empty and D1+D2 elsewhere; D2 can't be on B; ergo D2 blocks either D3's source or destination." This argument silently assumes D3 moves A→C directly — but D3 can route through B (A→B then B→C). *Wrong answer (claimed impossible), but the read-back pass caught the SMT encoding gap and warned the user about the missing formal certificate, which is partial recovery.*
- **The REPL-style agent** (added on the `repl-style` branch — see `mode: "agent"` in the API) issues one tool call per turn against a persistent solver, the way a programmer iterates against a REPL. On the same prompt it emits the correct 11-move sequence, which traces cleanly: every move is the top disk, no larger-on-smaller, D2 visits C→A→C and never B. *Right answer.*

The agent's win here is from a different framing of *thinking*, not from heavier engagement with Z3 (it actually engages the solver less than the step-based harness). The hypothesis: a turn-by-turn REPL framing nudges the model toward concrete move-by-move reasoning, where pattern-match traps lose their grip. We're still characterizing where the agent helps and where the step-based harness's structured verification is the better tool. See [docs/benchmark.md](docs/benchmark.md) for the full 18-problem comparison.

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
| `max_steps` | `20` | Hard cap on the harness's step iterations. |
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
  bin/server.ts        Entry: preload model + start HTTP server
  server.ts            OpenAI-compatible HTTP routes
  config.ts            env-var → ServerConfig
  harness/
    harness.ts         Step loop, read-back, verification
    solver.ts          Z3 incremental wrapper
    parser.ts          JSON-from-prose extraction
    prompts.ts         System + step + fix + read-back prompts
  llm/
    local.ts           node-llama-cpp provider with KV-cache reuse
    types.ts           ChatMessage, LLMResponse, etc.
    tool-calls.ts      OpenAI tools ↔ llama.cpp functions bridge
scripts/
  compare.ts           Direct-vs-harness comparison driver
  problems.ts          Benchmark problem registry
docs/
  benchmark.md         Per-problem benchmark write-up
tests/                 Vitest unit tests for solver / parser / prompts
```

## License

Apache-2.0
