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

### Lean, if you want the theorem-proving engine

Install `elan`, Lean's toolchain manager. Do not install a Lean release
directly: the workspace pins its own toolchain and `elan` fetches that version
on demand.

```bash
curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf \
  | sh -s -- -y --default-toolchain none
source "$HOME/.elan/env"
```

Then fetch Mathlib and build the REPL the harness talks to:

```bash
./tools/setup-lean.sh
```

That pulls Mathlib's prebuilt oleans, which run to several GB, and builds
`leanprover-community/repl` against the matching toolchain. Expect ten to
twenty minutes on a cold run. It is idempotent, so re-running it is cheap.

Everything except the theorem-proving tools works without any of this;
`/health` reports which engines it can see, and Lean problems in the benchmark
skip rather than fail when the toolchain is absent.

`import Mathlib` costs anywhere from ten seconds to six minutes depending on
whether the oleans are in the page cache and whether the machine has room to
keep them there. They run to about 7GB, so a 16GB laptop re-faults most of them
on every import and lands at the slow end, while CI with a freshly written cache
lands at the fast one. The harness pays that at startup rather than inside a
branch turn, and `/health` reports `lean.warm_sessions` and `lean.warming`
alongside `engines.lean`, because installed and ready are different things and
conflating them is how every Lean call failed while the health check stayed
green. Warming does not block startup, and a branch that asks before it finishes
waits for the import already running rather than starting a second one. Set
`HARNESS_WARM_LEAN=0` to turn it off, which is reasonable if your oleans stay
cached.

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
jolt -M:test      # 72 tests, offline and deterministic
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
| `HARNESS_TIMEOUT_MS` | `300000` | per-read inactivity bound on a provider call |
| `HARNESS_MAX_RESPONSE_MS` | `600000` | total bound on one response; kept below the turn deadline |
| `HARNESS_TURN_DEADLINE_MS` | `900000` | sized to the worst legitimate turn, not the typical one |
| `HARNESS_Z3_TIMEOUT_MS` | `120000` | |
| `HARNESS_SWIPL_TIMEOUT_MS` | `120000` | |
| `HARNESS_LEAN_TIMEOUT_MS` | `300000` | per Lean command, not the import |
| `HARNESS_LEAN_IMPORT_TIMEOUT_MS` | `1200000` | `import Mathlib` alone; measured at ~378s idle |
| `HARNESS_WARM_LEAN` | `1` | `0` disables warming Lean at startup |
| `HARNESS_LEAN_WARM_SESSIONS` | `1` | warmed sessions to prepare at boot |
| `DEEPSEEK_API_KEY` / `ZHIPU_API_KEY` / `OPENAI_API_KEY` | — | whichever provider |

## Known limitations

Tracked in `bd list`. The three that used to be listed here were all fixed on
2026-08-05, upstream in `jolt-lang/http-client`, and two of them turned out to be
the same bug.

A hung provider call could not be bounded because `connect-stream` applied the
caller's read timeout only on the plaintext branch, so `:socket-timeout` did
nothing on https and every provider call is https. And https broke both after
loading `jolt.nrepl` and inside a `jolt build` binary because Chez resolves
foreign symbols most-recent-loaded-first: on macOS the process image links
LibreSSL, so anything that loaded the process's own symbols took `SSL_*` away
from OpenSSL, and the mismatched `SSL_CTX` layouts faulted. `jolt.http.tls` now
loads its own libraries immediately before its bindings resolve. A built binary
now completes a handshake, starts nREPL, and runs the full loop.

A provider call is now bounded from both ends. `SO_RCVTIMEO` covers silence,
and a total deadline in the read loop covers a peer that trickles a byte at a
time and would otherwise reset that timer forever. The total is deliberately
below the turn deadline, so the HTTP layer gives up first and unwinds the thread
rather than the scheduler abandoning a branch that stays parked in a read.

What remains is `:conn-timeout`, which is still ignored: setting `O_NONBLOCK`
needs variadic `fcntl`, and Apple arm64 passes variadic arguments on the stack,
so a fixed-arity binding corrupts them silently. `connect` is at least bounded by
the kernel's SYN retry limit, unlike `recv`.

## License

EPL-2.0, matching `jolt` and Clojure convention.
