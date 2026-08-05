# veriframe

An OpenAI-compatible HTTP server that wraps a model in a claim-first verification
loop, written in [Jolt](https://github.com/jolt-lang/jolt) (Clojure on Chez
Scheme). Three engines back it:

- **SWI-Prolog** with `library(clpfd)` for relational and finite-domain problems.
- **Z3** for arithmetic and theory-rich constraints.
- **Lean 4 + Mathlib** through a long-lived `leanprover-community/repl`
  subprocess, with proofs developed a tactic at a time.

Nothing the model asserts ships unless an engine confirmed it, and the harness
checks that what the engine confirmed is actually what the answer claims.

This is a port of an earlier TypeScript version, and deliberately not a
transliteration: the control layer was rebuilt. `PLAN.md` has the reasoning, the
measurements, and every bug found along the way.

## Install

Needs `jolt`, `z3`, and `swipl`. Lean is optional and only the theorem-proving
paths use it.

```bash
brew install jolt-lang/jolt/jolt z3 swi-prolog     # or your package manager
```

Point it at a provider. Any OpenAI-compatible endpoint works, including a local
`llama-server` or Ollama.

```bash
export DEEPSEEK_API_KEY=…        # or ZHIPU_API_KEY, OPENAI_API_KEY
jolt serve                        # http on 3000, nREPL on 7888
```

For the Lean engine, fetch Mathlib and build the REPL. This pulls several GB of
prebuilt oleans and takes a while the first time.

```bash
./tools/setup-lean.sh
```

## Use it

The standard OpenAI shape:

```bash
curl -sS -X POST http://localhost:3000/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages": [{"role": "user", "content": "Prove that for every natural number n, n < 2^n."}]}'
```

`choices[].message.content` carries the answer plus the artifacts that earned
it. A non-standard `harness` field carries the run id, per-branch status, and
metrics. Send `"raw": true` to bypass the loop and forward straight to the
provider, which is the control arm worth having.

Runs also have a life of their own, so you can watch and steer one:

```bash
# start one without blocking
curl -sS -X POST localhost:3000/v1/runs -d '{"problem": "…", "beam_width": 3}'

# tail it by cursor; feed `next` back in
curl -sS "localhost:3000/v1/runs/$ID/journal?since=0"

# tell a branch something; it applies at that branch's next turn boundary
curl -sS -X POST "localhost:3000/v1/runs/$ID/interventions" \
  -d '{"branch_id": "B1", "kind": "message", "payload": "Ship what you have."}'

curl -sS -X POST "localhost:3000/v1/runs/$ID/abort"
```

`GET /v1/harness/gates` returns the gate table and every threshold, which is the
quickest way to see what the loop will do to you and why.

## How the loop works

A run is a beam: several branches attack the same problem in parallel, each with
its own Prolog session, Lean environment, and message history. The only thing
they share is a failure log, and that sharing is the point — an approach one
branch disproved is not retried by another. It is FTS5-ranked rather than
broadcast whole, so a branch sees the failures most like what it just tried.

Shipping is gated. `thesis` → a `verify_*` that confirms → `review` (or
`verify_template`, whose cross-check is built in) → `audit` → `done`. `done` is
refused unless the latest audit passed against the exact answer being shipped,
something was independently cross-checked, and every substantive claim in the
answer appears in an artifact an engine confirmed. Those checks are mechanical.

Four things about it are worth knowing before reading the code.

**One steer per boundary.** Several gates can hold at once; an arbiter picks
exactly one in strict priority and records what it outranked. A human directive
sits above every machine gate.

**Every gate declares a prediction**, which a later turn settles from the journal
with no model in the path. A gate whose predictions never settle is not steering
anything, and that is measurable rather than arguable. `stuck` currently fires
and is obeyed zero times, which is a real finding rather than a bug.

**A confirmation is not the same as progress.** An engine saying yes to "clpfd
is available" is not progress on a puzzle, and a guard that credits every
confirmation cannot see a branch verifying its own tooling.

**SAT over free variables is not a witness**, and neither is a Prolog goal that
succeeds with its variables unbound. Both land in an `existential` bucket that
cannot substantiate a concrete answer.

## Durable runs

Everything is appended to SQLite as it happens rather than assembled at the end,
so a crashed or aborted run stays fully inspectable and the read API serves a
live run and a finished one with the same query. That is what lets a UI be a
client rather than a special case.

```bash
sqlite3 veriframe.sqlite3 "SELECT turn, tool_name, category FROM turns ORDER BY id"
sqlite3 veriframe.sqlite3 "SELECT gate, count(*), sum(outcome='met') FROM gate_firings GROUP BY gate"
```

## Development

Leave the process running and work against it. The slow parts are exactly the
ones you never want to restart: a `swipl` session, a Lean REPL that spends
thirty seconds importing Mathlib, and a provider call that takes minutes.

```bash
jolt dev          # serve with the dev + test trees on the path
```

Then attach an editor to the port in `.nrepl-port`. Everything except
`veriframe.system` is redefinable in place — the server holds the handler var,
not the function it currently contains, and a tool is a multimethod method the
next branch turn picks up.

```clojure
(veriframe.test-runner/run)              ; the suite, in-process
(veriframe.agent.gates/reload-config!)   ; re-read resources/gates.edn
```

Prompts and gate thresholds live in `resources/` as files rather than as
constants, so a run records a digest of the set it used and a pass-rate change
localizes to one file.

```bash
jolt -M:test      # 52 tests, offline and deterministic
jolt smoke        # platform probes, one per stated risk in PLAN.md
jolt build -m veriframe.core -o veriframe
```

## Benchmarks

```clojure
(veriframe.bench.runner/run-suite)                 ; the registry
(veriframe.bench.beam/sweep-widths problem)        ; does the beam earn its width
(veriframe.bench.fence-capture/replay)             ; parser vs stored real output
```

Problems come in two kinds. A difficulty problem asks whether the harness is any
good. A probe asks whether one mechanism works, and names the gate it targets
plus what the harness must *not* do — a probe that expected a gate and did not
get one is reported as INERT rather than as a pass, because a silent guard and a
working guard look identical from outside.

Read `PLAN.md` before trusting a number from any of these. The short version:
run-to-run variance is around 2x, so nothing sized under that is a result, and
what survives at n=1 is structural — did the mechanism fire when it should and
stay silent otherwise.

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `HARNESS_PROVIDER` | auto-detect | `deepseek`, `glm`, `openai`, `ollama`, `local` |
| `HARNESS_MODEL` | per provider | wire model name |
| `HARNESS_BASE_URL` | per provider | point at any OpenAI-compatible endpoint |
| `HARNESS_PORT` | `3000` | |
| `HARNESS_NREPL_PORT` | `7888` | |
| `HARNESS_DB` | `veriframe.sqlite3` | |
| `HARNESS_MAX_TURNS` | `80` | per branch |
| `HARNESS_BEAM_WIDTH` | `5` | treat as unjustified; see PLAN.md |
| `HARNESS_MAX_TOKENS` | `16384` | a correctness parameter, not a cost knob |
| `HARNESS_TIMEOUT_MS` | `300000` | per provider call |
| `DEEPSEEK_API_KEY` / `ZHIPU_API_KEY` / `OPENAI_API_KEY` | — | whichever provider |

## Known limitations

Tracked in `bd list`. The one that constrains what the harness can do:

**A hung provider call cannot be bounded from inside the process.** A timed
`deref` does not preempt a thread parked in a blocking FFI read, so the
scheduler's per-turn deadline is unenforceable in exactly the case it exists for,
and because scheduling is a barrier the stall spreads to every branch. Keep
concurrent provider calls under about six until this is fixed.

The other two are TLS-related. Loading `jolt.nrepl` before any TLS handshake
breaks https for the process, which is worked around by warming TLS at startup.
And a `jolt build` binary cannot complete a TLS handshake at all, so it is
usable for the engine paths and against a local plain-http provider but not
against a hosted one.

## License

Apache-2.0
