# reasoning-harness

OpenAI-compatible HTTP server that wraps an LLM in a **claim-first verification loop** backed by three reasoning engines:

- **SWI-Prolog** with `library(clpfd)` — relational and finite-domain CSPs (knights & knaves, zebra, sudoku).
- **Z3 SMT** — numerical and theory-rich constraints (linear/nonlinear arithmetic, bitvectors, optimisation).
- **Lean 4 + Mathlib** via a long-lived `leanprover-community/repl` subprocess — mathematical theorems, with proofs developed stepwise in tactic mode.

Every claim the model wants to land has to round-trip through one of these engines. Verified facts accumulate; unverified ones don't ship.

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

```bash
brew install z3   # or your package manager of choice
```

### Pick an LLM provider

- **Local (node-llama-cpp)** — drop a GGUF into `models/`. The default config expects `models/Qwen3.6-35B-A3B-Q8_0.gguf`. Run with `./start.sh`.
- **GLM-5.1 (Zhipu BigModel)** — `export ZHIPU_API_KEY=…`, then `./start-glm.sh`. The provider merges `reasoning_content` + `content` into `<think>...</think>` framing so the tool-call fence parser sees the fence wherever the model emits it.
- **DeepSeek** — `export DEEPSEEK_API_KEY=…`, then `./start-deepseek.sh`. Default model is `deepseek-chat`; set `HARNESS_MODEL=deepseek-reasoner` for the thinking variant.

## Run

```bash
./start-glm.sh   # GLM-5.1, default port 3001
# or ./start-deepseek.sh / ./start.sh
```

The server preloads the LLM (local) or validates the API key (remote), then exposes the standard OpenAI shape at `/v1/chat/completions`. Lean's REPL spawns lazily on the first proof tool call (~10–30s warm-up for `import Mathlib`); subsequent steps are sub-second.

### Send a request

```bash
curl -sS -X POST http://localhost:3001/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "glm-5.1",
    "messages": [{"role": "user", "content": "Prove that 2^n > n for all natural numbers n."}],
    "mode": "agent"
  }' | jq
```

The response body has `choices[].message.content` (the model's natural-language answer plus the verified Lean proof) and a non-standard `harness` field with the per-step trace.

To bypass the harness and call the model directly, send `"raw": true`:

```bash
curl -sS -X POST http://localhost:3001/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages": [{"role": "user", "content": "..."}], "raw": true}'
```

### Run a benchmark problem

```bash
npx tsx scripts/agent-only.ts math-induction-pow2-gt-n
npx tsx scripts/agent-only.ts math-gauss-sum
npx tsx scripts/agent-only.ts knights-3
npx tsx scripts/agent-only.ts zebra-5x5
```

The full registry lives in `scripts/problems.ts`.

## Tool surface

Tools the model can call inside the agent loop:

```
add_rule({name?, code})        Prolog facts/rules; named = retractable, anonymous = permanent
retract_rule({name})           undo a tentative named rule
commit({name})                 lock a named rule in
verify({claim, check})         Prolog goal that succeeds iff the claim holds
verify_smt({claim, smtlib})    Z3 sat/unsat check
verify_template({claim, template, slots})
                               vetted SMT template; primary + cross-check encodings must agree
verify_lean({claim, lean})     one-shot Lean snippet against Mathlib
lean_define({code})            extend the branch's persistent Lean env (defs / axioms / lemmas)
lean_search({query, top_k?})   retrieval over Mathlib's ~235k declarations
proof_start({claim, theorem})  open a stateful Lean proof session
proof_step({tactic, claim?})   apply ONE tactic; returns new goal state
proof_state() / proof_undo({steps?}) / proof_close() / proof_abandon()
assume({name, fact}) / discharge({name})
                               open / close a hypothetical scope
thesis({goal, subClaims, technique, nonFiniteJustification})
                               commit the structural plan before attacking the goal
audit({claim, proposedAnswer}) sub-LLM auditor; mandatory pre-`done` soundness gate
review({claim, rationale, ...}) independent cross-check of a confirmed artifact
done({answer}) / give_up({reason})
```

Shipping gates run **thesis → verify_\* → review (or verify_template, which has the cross-check baked in) → audit → done**. `done` is blocked unless the latest `audit` passed against a matching `proposedAnswer` and (for non-template confirmations) `review` ran.

Bundled SMT templates: `sidon_set`, `no_3ap_subset`, `cap_set_f3n`, `schur_coloring` (see `src/harness/smt-templates.ts`).

A stuck-detection heuristic injects a "rethink the step / retract / decompose" hint after 3 consecutive failed verifies and auto-suggests Mathlib lemmas drawn from the *failed proof goal* rather than the natural-language claim (the ReProver / Magnushammer signal).

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `HARNESS_PROVIDER` | auto-detect | `local` / `glm` / `deepseek`. Picks `glm` if `ZHIPU_API_KEY` is set, else `deepseek` if `DEEPSEEK_API_KEY` is set, else `local`. |
| `HARNESS_MODEL_PATH` | — | GGUF path (local provider only) |
| `HARNESS_MODEL` | per-provider default | Wire model name (`local-model` / `glm-5.1` / `deepseek-chat`) |
| `ZHIPU_API_KEY` | — | Required when `HARNESS_PROVIDER=glm` |
| `DEEPSEEK_API_KEY` | — | Required when `HARNESS_PROVIDER=deepseek` |
| `HARNESS_PORT` | `3000` | HTTP port |
| `HARNESS_MAX_TOKENS` | `4096` (`16384` for GLM) | Per-LLM-call output cap |
| `HARNESS_TIMEOUT_MS` | `300000` | Per-LLM-call wall clock |
| `HARNESS_LEAN_WORKSPACE` | `tools/lean-workspace` | Override the Lean workspace path |
| `HARNESS_LEAN_REPL_BIN` | `tools/lean-repl/.lake/build/bin/repl` | Override the Lean REPL binary path |
| `HARNESS_LEAN_TIMEOUT_MS` | `120000` | Per-Lean-call timeout (fallback `lake env lean` path) |

Per-request fields:

| Field | Default | Notes |
|---|---|---|
| `mode` | `agent` | `agent` runs the verification loop; `raw: true` bypasses |
| `max_turns` | `80` | Hard cap on tool-call turns before the run is failed |

## Tests

```bash
npm run test:run
npm run typecheck
```

## License

Apache-2.0
