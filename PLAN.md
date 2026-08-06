# veriframe on Jolt

A port of `~/src/veriframe` (TypeScript, ~11.9k LOC) to Jolt, the Clojure dialect
on Chez Scheme. The port is not a transliteration. The TypeScript version grew
its steering mechanisms one at a time, and several of them now conflict with each
other. This plan keeps the verification architecture and rebuilds the control
layer around the findings from dirge's recent loop-control work and the three
papers those PRs were derived from.

## What the original does

An OpenAI-compatible HTTP server wraps a model in a claim-first verification
loop. Three engines back it: SWI-Prolog with `library(clpfd)` for relational and
finite-domain problems, Z3 for arithmetic and theory-rich constraints, and Lean 4
plus Mathlib through a long-lived `leanprover-community/repl` subprocess for
theorems. Nothing the model asserts ships unless an engine confirmed it.

The loop is a beam search. Five branches run in parallel, each with its own
Prolog session, Lean environment, message history, and turn log. They share one
global failure log so an approach disproved on one branch is not retried on
another. A branch that fails three consecutive verifications is culled unless it
produced a confirmed artifact recently. The `branch_theses` tool lets a branch
fork into siblings, capped at fifteen branches total. The first branch to call
`done` wins.

The model does not use provider-native tool calling. It emits a fenced
` ```tool-call ` block containing JSON, and the harness parses it with a repair
pass for unescaped control characters. That is worth keeping, because it means
the port never has to care whether a provider implements tool calling correctly.

Shipping is gated: `thesis` then a `verify_*` call then `review` (or
`verify_template`, which bakes the cross-check in) then `audit` then `done`.
`done` refuses unless the most recent audit passed against a matching
`proposedAnswer`.

## Why the control layer gets rebuilt rather than ported

The five dirge PRs the user pointed at (738, 739, 740, 749, 750) plus the three
papers behind them (DS1 Remote Agent validation, Behavior Trees for LM agents,
Agentic Harness Engineering) converge on a small number of claims that apply
directly to what `agent.ts` currently does.

**Steers compete.** `agent.ts` can inject `MILESTONE_PROMPT`,
`EMERGENCY_REVIEW_PROMPT`, and `STUCK_HINT` in the same turn, each from its own
independent conditional, each pushing the branch a different direction. dirge
found up to five harness messages landing before a single assistant turn and
replaced the chain with one arbiter per boundary that picks exactly one message
in strict priority. That is the behavior-tree fallback node, and it is the single
highest-value structural change available here.

**Verdict parsing inverts.** dirge's `parse_verdict` read `COMPLETE` out of `NOT
COMPLETE` because one answer was a substring of the other, and got eleven of
twenty-seven real judge phrasings wrong, every one in the same direction. The
`audit` and `review` tools here both ask a sub-LLM for a verdict and both parse
free text. This is the same bug waiting to happen against the gate that decides
whether an unverified answer ships.

**Verification failures dominate steering failures.** Six for six in dirge's
audit. The analogue in veriframe is not a masked shell exit code but a confirmed
artifact that does not support the claim: the existential-SAT bucket in
`VerifiedArtifact` exists precisely because a branch shipped "Z3 says a coloring
exists" as though it were a coloring. Mechanisms that make an artifact prove what
it claims are worth more than mechanisms that make the model try harder.

**Prose loses to mechanism.** In the AHE ablation the evolved system prompt alone
was −2.3pp while evolved memory, tools, and middleware were each positive. The
current `SYSTEM_PROMPT` is around 250 lines carrying a large share of the
steering. Anything in it that can become a gate, a tool precondition, or a
refusal should become one.

**Guards must be tunable only by signals that measure the same thing.** PR 740's
rule. A capability estimate built from tool-call mechanics (parse errors, repaired
arguments, invented tool names) may relax argument-hygiene budgets and may never
relax a verification gate. The two failures dirge most needed guards for both came
from models the estimator read as strong.

**Every edit is a falsifiable contract.** AHE's decision observability. When a
gate fires it should record what it expects to happen next, and the next turn
should settle that prediction deterministically. This is cheap to build and it is
the only way to know whether a gate earns its place.

**Layered testing and an out-of-band stop.** From the DS1 report. The Remote Agent
flew because the team built a fidelity pyramid from unit tests up to full flight
scenarios, and because the RAX manager could always stop the Lisp task no matter
what the agent believed. Both translate: a scripted fake model driving the real
engines is most of the test suite, and a supervisor that can kill every engine
subprocess independent of branch state is a requirement, not a nicety.

## Platform mapping

Jolt covers everything this needs, with three substitutions.

| Original | Jolt |
|---|---|
| `prolog-wasm-full` (npm, WASM SWI 10.1.4) | real `swipl` as a long-lived subprocess over `jolt.process` (babashka.process) |
| `spawnSync` to `z3` | `jolt.process/sh` to `z3`, unchanged in shape |
| `spawn` of `leanprover-community/repl` | `jolt.process/process` with the same JSON-over-stdin protocol |
| `node:http` server | `jolt-lang/ring-chez-adapter` |
| `undici` / fetch to providers | `jolt-lang/http-client` (clj-http-lite over `jolt.ffi` sockets and OpenSSL) |
| `JSON.parse` / `stringify` | `org.clojure/data.json` as a git dep |
| nothing; runs are in-memory only | `jolt-lang/db` (libsqlite3 via `jolt.ffi`) for durable sessions |
| `node-llama-cpp` in-process GGUF | dropped; point at any OpenAI-compatible endpoint including `llama-server` |
| `Promise.all` over branches | `future` / `pmap`; Jolt has real Chez threads |

The Prolog substitution is an upgrade, not a compromise. The WASM build has no
`library(time)`, so `prolog.ts` fakes wall-clock limits with
`call_with_inference_limit/3` and a marker atom. Real `swipl` has
`call_with_time_limit/2` and `library(http/json)`, so the session wrapper can ask
for answers as JSON on stdout and enforce an actual timeout.

One constraint it brings, found in the Phase 0 probe: `#>` and `#<` are operators
`library(clpfd)` defines at load, and a Prolog term is read in full before it
runs, so loading the library and using it inside one term is a syntax error. The
session has to consult a bootstrap file that loads clpfd before any clpfd term
reaches the reader. That bootstrap file is the natural home for the read-eval-
print server the session speaks to anyway.

The SQLite row is new capability rather than a port. veriframe holds a run
entirely in memory and returns it in one HTTP response, so a crash, a client
disconnect, or the 80-turn cap loses everything except what came back on the
wire. See "Durable sessions" below.

Dropping in-process GGUF inference is the one real capability loss. It is worth
taking: `local.ts` is 446 lines of node-llama-cpp-specific chat-wrapper and
thinking-tag handling, all of it replaced by pointing `HARNESS_BASE_URL` at
`llama-server`. The Qwen thinking-preservation behaviour moves into the
OpenAI-compatible client alongside the GLM `reasoning_content` merge that already
lives there.

## Durable sessions

dirge keeps everything in a per-project `state.db`: sessions, messages with an
FTS5 index, checkpoints, memory, and an issue board. The parts worth taking here
are the storage discipline rather than the feature set.

**One SQLite file per deployment**, opened once, owning its own schema through
numbered idempotent migrations against `PRAGMA user_version`. dirge's migration
list runs to v11 and each one carries a comment explaining what it fixed, which
is the reason its schema is legible years in. Same pattern here, migrations as
data in `resources/migrations/`.

**Append as it happens, not at the end.** Every turn, tool result, artifact, and
gate firing is written when it occurs. This is what makes a crashed or aborted
run inspectable, and it is also what makes the live UI below possible, because
the UI reads the same rows the loop writes.

**FTS5 over the message and failure text.** The global failure log is currently a
vector rebuilt into every branch's context each turn, which grows without bound.
Backed by FTS5 it becomes a query: give this branch the failures most similar to
what it is about to try. That is a smaller context and a better one.

Tables, roughly: `runs`, `branches`, `turns`, `tool_calls`, `artifacts`,
`failures` plus an FTS5 index, `gate_firings` with the declared prediction and its
settled outcome, `interventions`, and `schema_version`. Nothing here needs the
memory or skills tiers dirge carries, because a verification run's durable
knowledge is its artifacts.

One gotcha to design around from the start: `db.sqlite/query` calls
`sqlite3_prepare_v2` with a null tail pointer, so a multi-statement string
executes only its first statement and reports no error. Migrations are vectors of
single statements, and a test asserts every migration's statement count against
the tables it should have produced.

## Observability and intervention

The run has to be watchable and steerable from outside while it is running, with
a UI as one client of that surface and not a special case. Three pieces.

**A read API over the store.** `GET /v1/runs`, `/v1/runs/:id`,
`/v1/runs/:id/branches`, `/v1/runs/:id/journal`. These are queries against the
tables above, so they work identically for a live run and a finished one, and
they need no cooperation from the loop.

**A tail, and later a stream.** `GET /v1/runs/:id/journal?since=N` returns
everything after a cursor, which is all a UI needs and works against any HTTP
server. `GET /v1/runs/:id/events` as server-sent events is the upgrade, fed by the
same append path that writes to SQLite: every write publishes to a `core.async`
mult, the SSE handler taps it and replays from the journal on connect so a client
attaching mid-run sees the whole story, and the tap uses a sliding buffer so a
dropped subscriber cannot block the loop.

The ordering there is forced by the server. `ring-chez-adapter`'s accept loop
handles one connection at a time on one thread, and `response->string` realizes
the entire body to compute `Content-Length` before sending, with
`Connection: close`. As written it cannot stream, and worse, a `POST
/v1/chat/completions` running a multi-minute beam blocks `/health` and every other
request for the duration. Both are fixable in roughly the same place: hand each
accepted connection to a `future` so the loop returns to `accept` immediately, and
add a chunked write path that skips `Content-Length`. The accept loop already
runs on a background thread with its blocking calls bound `:blocking`, so
thread-per-connection is a small change rather than a redesign, and it is worth
offering upstream. Until it lands, the adapter is vendored under
`src/ring_chez/`, the cursor-tail endpoint carries the UI, and concurrency is a
Phase 0 deliverable rather than a Phase 6 surprise.

**An intervention queue.** `POST /v1/runs/:id/interventions` writes a directive
addressed to a run or a branch. The arbiter drains it at the same boundary where
it evaluates gates, and a human directive sits at priority zero, above every gate.
This is dirge PR 717's finding, that a pending question to the user has to outrank
the machine gates, arriving as a design property instead of a bug fix. Directive
kinds to start: inject a message into a branch, force review-and-ship, cull a
branch, fork a branch on a stated thesis, extend the turn budget, pause, resume,
abort. Every directive lands in `interventions` with who issued it and what the
arbiter did with it, so a human steer is as auditable as a gate firing.

Two consequences for the rest of the design. A run gets an identity and a
lifecycle independent of the HTTP request that started it, so `POST
/v1/chat/completions` starts a run and blocks on it for OpenAI compatibility while
`POST /v1/runs` starts one and returns immediately. And branch state stops being
a value threaded through the loop and becomes an entity with a durable id, which
is what lets a directive name it.

The UI is then a static page against those endpoints, and it is deliberately not
in the early phases. What the phases have to deliver is that adding it requires no
change to the loop.

## Development workflow

nREPL from the start rather than as an afterthought, because the slow parts of
this system are exactly the parts you do not want to restart: a `swipl` session,
a Lean REPL that spends thirty seconds importing Mathlib, and a provider call that
takes minutes.

`veriframe.core` starts an nREPL server alongside the HTTP server and writes
`.nrepl-port`, so CIDER attaches automatically. Jolt's nREPL runs in dev mode
where calls deref their var, so redefining a `defn` takes effect on the next call
with no restart. The design constraint that follows: **long-lived resources live
in vars behind an explicit lifecycle, and everything else is a function**. Engine
sessions, the DB connection, the HTTP server, and the event mult go in a system
map held by one atom with `start!` and `stop!`. Gate definitions, prompts, tool
methods, and parsers are redefinable in place. A tool implemented as a multimethod
method can be redefined mid-run and the next branch turn picks it up, which is the
tightest loop available for the part of the system that changes most.

`deepseek-v4-flash` is the test model, and the key is already in the environment.
It is cheap enough to run the beam repeatedly and, per dirge's tier measurements,
sits at a tool-call error rate near zero, which makes it a poor probe for the
repair paths and a good one for the steering paths. `deepseek-v4-pro` is the
second arm when a run needs a stronger model. Note that `config.ts` defaults
DeepSeek to `deepseek-reasoner`, which the API no longer serves.

## Layout

```
veriframe-clj/
  deps.edn
  src/veriframe/
    core.clj              -main, system start!/stop!, nREPL, supervisor
    config.clj            env to config map
    system.clj            the one atom: db, server, engines, event mult
    store/
      db.clj              connection, migration runner
      migrations.clj      numbered vectors of single statements
      runs.clj            runs, branches, turns, artifacts
      journal.clj         append-only events, gate firings, predictions
      failures.clj        failure log with FTS5 retrieval
      interventions.clj   directive queue
    events.clj            core.async mult, publish on every store append
    api/
      openai.clj          /v1/chat/completions, /v1/models
      runs.clj            /v1/runs read model
      stream.clj          /v1/runs/:id/events (SSE)
      control.clj         /v1/runs/:id/interventions
    server.clj            ring handler, route table, /health
    llm/
      client.clj          retry ladder, timeouts, error classification
      message.clj         think-block handling, conversation normalization
      adapter.clj         the Adapter protocol
      adapter/
        openai.clj        the OpenAI chat-completions family
        ollama.clj        Ollama's native /api/chat
      registry.clj        provider keyword -> adapter
      fence.clj           tool-call fence extraction, JSON control-char repair
    engine/
      prolog.clj          swipl session: assert, retract, commit, query, snapshot
      smt.clj             z3 shell-out, verdict + witness model parsing
      smt_templates.clj   sidon_set, no_3ap_subset, cap_set_f3n, schur_coloring
      lean_repl.clj       REPL subprocess, respawn, per-branch env ids
      lean.clj            one-shot snippet check
      lean_proof.clj      stateful tactic sessions
      lean_search.clj     Mathlib premise retrieval
      lint.clj            pre-execution linters for Prolog and SMT-LIB
    agent/
      state.clj           branch and run state
      tools.clj           tool dispatch multimethod, one method per tool
      arbiter.clj         the single boundary arbiter
      gates.clj           gate definitions, priority order, budgets
      verdict.clj         constrained answer-set parsing for audit/review
      tier.clj            capability estimate from tool-call mechanics only
      loop.clj            beam scheduler, culling, forking
    bench/
      problems.clj        the 53-problem registry
      runner.clj          agent-only and compare drivers
  resources/
    prompts/*.md          system prompt, gate messages, sub-LLM prompts
    gates.edn             thresholds and budgets
  test/
```

`resources/prompts` and `resources/gates.edn` are the AHE component-observability
idea applied cheaply. Every prompt and every threshold is a file, a run records a
digest of the set it used, and a later evolution loop has somewhere to write.

Tool dispatch as a multimethod keyed on tool name gives one method per tool with
its own precondition, which is what makes the gates below expressible as
preconditions rather than as prose in the system prompt.

## Provider adapters

One place holds everything that is the same whichever model is answering: the
retry ladder, the wall-clock bound, error classification, think-block handling,
and conversation normalization. An adapter carries only the deltas — where the
endpoint is, how it authenticates, what the request fields are called, and where
the content and the reasoning live in the reply.

Keeping the protocol small is the point. Every method an adapter may override is
a place a provider can quietly diverge from the retry and timeout discipline,
and a retry ladder that differs by provider is one nobody can reason about.

The OpenAI chat-completions family is one adapter, not four. OpenAI, DeepSeek,
Zhipu GLM, and a local llama-server all speak the same wire format, and their
real differences — base URL, whether a separate reasoning stream exists and
under what key, whether `max_tokens` was renamed — are fields on a record. A
namespace whose only content is a base URL is not an abstraction. Ollama gets
its own adapter because its native API genuinely differs: one object rather than
a choices array, generation settings nested under `options`, and `stream` false
required or the body arrives newline-delimited.

Two retry behaviours are worth naming because both came from dirge. A 429 that
means "slow down" and a 429 that means "you are out of credit" need opposite
handling, and providers signal the difference in the error text rather than the
status, so the adapter classifies it and a cap is never retried. And when the
provider says when the window reopens, through `Retry-After` or the
`x-ratelimit-reset-*` family, waiting exactly that long beats doubling a guess —
bounded by our own ceiling, since the number is the provider's opinion.

## The control layer

### One arbiter, one steer per boundary

`arbiter.clj` evaluates gates in strict priority at the end of every branch turn
and emits at most one message. Preconditions are re-evaluated each tick rather
than latched by one-shot counters. Draft order, highest first:

1. `done` blocked by a failing or missing audit
2. safe-state abort due
3. emergency review, meaning the branch is at the cull threshold but holds a
   recent confirmation
4. milestone, meaning first confirmed artifact on this branch
5. stuck hint after three unproductive verifications
6. exploration-prologue bound, meaning nothing produced at all after N turns
7. tier escalation, meaning fast checks only with the slow tier never run
8. turn-budget notice at 60% and 85%

Each gate declares whether its budget is a cost ceiling (bounds sub-LLM calls,
never scales up for a struggling run) or a re-fire guard (compensates for a
predicate that cannot distinguish "happened" from "happened and I already
reacted"). PR 739 found that distinction was what made the thresholds legible.

### Constrained verdicts

`verdict.clj` parses sub-LLM output from `audit` and `review` against an answer
set built so no answer is a substring of another, matched longest-first on a
dedicated line, with ambiguity returning `:unparseable` rather than a guess. A
corpus of real phrasings is checked into the tests, and an `:unparseable` verdict
fails closed, meaning `done` stays blocked.

### Evidence, not claims

Three mechanisms, all deterministic and none with an LLM in the path.

A **claim-evidence gate** before `done`: every substantive token in the proposed
answer must be covered by a confirmed artifact. `checkAnswerCoversArtifacts` is
the seed of this and it stays, extended so an artifact in the existential or
ambiguous bucket cannot substantiate a concrete-instance answer.

A **self-validator check**: a `verify` whose Prolog goal succeeds only because of
a rule the same branch added in the same turn without independent grounding does
not count as confirmation. This is the analogue of dirge refusing to let an
agent-authored `./check.sh` latch green.

A **publish guard**: `retract_rule` refuses on a named rule that a confirmed
artifact depends on. dirge's version blocks discarding but never modifying, and
the same split applies here.

### Safe-state abort

DS1's third failure rung, which dirge implemented as a git-backed restore. The
branch analogue is a snapshot of the Prolog session as the ordered log of
committed assertions plus the Lean env id at the last confirmation. Restoring
means replaying that log into a fresh `swipl` session. The coverage gate is the
part that matters: restore only if every mutation since the snapshot went through
a tracked path. An anonymous permanent `add_rule` is the analogue of dirge's
`sed -i`, meaning it is a mutation the snapshot store does not cover, and its
presence turns the abort advisory. Ships advisory by default.

### Progress monitor

`agent.ts` keys its stuck detection entirely on failed verifications. A branch
producing successful, varied, useless calls trips nothing. The monitor watches
turn boundaries for a real progress event, defined as a new confirmed artifact, a
thesis sub-claim discharged, or a proof goal count that went down. The
prologue bound handles the case the monitor cannot: a branch that never produced
anything never arms it.

### Tiered verification

The DS1 fidelity pyramid. Engine calls classify Fast (Prolog query, small-timeout
one-shot Z3, `verify_lean` snippet) or Slow (`verify_template` with its
cross-check, a closed proof session, `review` plus `audit`). Unknown classifies
Slow, so a misclassification costs a missed escalation rather than a false nag.
One mid-run nudge when several artifacts landed with nothing slow run, and one
end-of-run escalation when fast-green never became slow-green.

### Capability tier

Estimated from tool-call mechanics only: fence parse failures, JSON auto-repairs,
invented tool names, invalid arguments. It may tune the parse-and-repair budgets
and it may not touch any verification or progress gate. PR 740's rule goes in the
namespace docstring, and a test asserts the strong tier is bit-identical to
nominal so the estimator cannot quietly start relaxing guards.

### Journal and decision manifest

Every gate firing records the gate, branch, turn, and a declared expectation such
as "this branch calls `review` within two turns". The following turns settle it
from the trace with no LLM involved. The run response carries a gate tally with
co-occurrence, so the question "does this gate earn its place" has an answer
after any benchmark sweep. This is AHE's decision observability and dirge's
`gate_tally` arriving as the same mechanism.

### Residual objectives

When the beam exhausts, report each branch's undischarged thesis sub-claims and
its best confirmed artifact instead of the current bare "beam exhausted" string.

### Supervisor

The RAX manager pattern. A supervisor owns every engine subprocess and can kill
all of them on request abort, server shutdown, or run timeout, regardless of what
any branch believes. `agent.ts` does this in a `finally` block, which is fine
until an exception escapes the wrong way. Making it a separate owner with a
registry also gives the `/v1/harness/state` endpoint something honest to read.

## Phases

Each phase ends somewhere runnable, with tests, so a stall does not leave nothing
working.

**Phase 0, scaffolding and toolchain.** `deps.edn` with `jolt-lang/http-client`,
`jolt-lang/ring-chez-adapter`, `jolt-lang/db`, `org.clojure/data.json`,
`org.clojure/tools.logging`, and `jolt-lang/nrepl`. `system.clj` with `start!` and
`stop!`, `core.clj` bringing up nREPL and an HTTP server that serves `/health`,
and `store/db.clj` with the migration runner. `swipl` is now installed and z3 is
at `/opt/homebrew/bin/z3`; Lean and elan are not installed and only Phase 5 needs
them. Deliverable is a running process you can attach to, whose smoke check shells
`z3` and `swipl`, opens the SQLite file, runs migrations, and does one HTTPS GET.
Everything after this is developed against that live process.

**Phase 1, engines with no model. Done.** `proc.clj`, `lint.clj`, `smt.clj`,
`smt_templates.clj`, `prolog.clj`, and `resources/prolog/session.pl`. The Prolog
session speaks line-framed JSON to a `swipl` process that consults the bootstrap
first, and records its asserts in order so a snapshot is a replay script.
Deliverable met: knights-3, the zebra puzzle, all four SMT templates, and the
engine-agreement check all run with no LLM in the loop.

Three host details cost time and are worth writing down. `clojure.java.io/writer`
and `/reader` do not accept a raw stream here, so subprocess pipes are written as
bytes and wrapped in a `BufferedReader` by hand. `babashka.process/process` takes
the command vector first and the options map second, the opposite of `sh`, and
passing them the other way silently produces an argv[0] of `"[z3"`. And
`print_message/2` in SWI writes to `user_error` rather than the stream
`with_output_to/2` captures, so rendering a thrown term through it both pollutes
stderr and yields an empty string; the session names the common ISO error shapes
itself instead.

**Phase 2, model plumbing.** `llm/` as described under "Provider adapters", plus
`llm/fence.clj`. The fence parser gets its tests first, since it is the one
component whose bugs are invisible in a live run: a parser that quietly drops a
tool call looks exactly like a model that chose not to make one. Deliverable is a
one-shot call against `deepseek-v4-flash` returning a parsed tool call whose
SMT-LIB runs through the Phase 1 engine.

Two host findings landed here and both are load-bearing.

**Loading `jolt.nrepl` before any TLS handshake has completed breaks https for
the rest of the process.** Every `jolt.http-client` https call then throws an
opaque Chez condition, while plain http keeps working. A prior *successful*
https call immunizes the process; a failed one, a plain-http call, and
constructing an `SSL_CTX` by hand all do not, so it is initialization rather than
ongoing interference. The fix is that `veriframe.core` warms the TLS path against
the provider — which doubles as the API-key validation the TypeScript harness
also does at boot — and only then loads and starts nREPL. That makes require
order load-bearing, so `smoke/nrepl-load-order-check` spawns a subprocess in the
real startup order and fails if it regresses. Worth reporting upstream.

**`clojure.data.json` accepts raw control characters inside string values where
`JSON.parse` rejects them.** The repair pass therefore almost never fires, and
keying the `auto-repaired` signal off the fallback path would have left the
counter permanently zero — indistinguishable from the behaviour never happening,
which is exactly what dirge PR 740 found. The signal is now computed from the
text rather than from which parse path succeeded, so it measures what it claims
to: the model emitted unescaped control characters, whoever tolerated it.

**Phase 3, single-branch loop with the journal.** `state.clj`, `tools.clj`,
`store/journal.clj`, `events.clj`, `loop.clj` restricted to one branch, and the
Prolog and SMT tools only. The gates from `arbiter.clj` land here rather than
being bolted on later, because retrofitting an arbiter over a chain of
conditionals is the refactor dirge had to do twice, and the journal lands here
because a gate with no recorded firing cannot be evaluated. Deliverable is
`zebra-5x5` and `knights-3` solved, with the full run readable from SQLite
afterwards.

**Phase 4, beam.** Parallel branches over `future`, the failure log with FTS5
retrieval, culling with the recent-confirmation protection, `branch_theses`
forking under the total cap, safe-state snapshots. Deliverable is a beam run whose
journal shows a cull, a fork, and a shared-failure-log hit.

**Phase 5, Lean.** `lean_repl.clj`, `lean.clj`, `lean_proof.clj`,
`lean_search.clj`. Requires elan, a Mathlib build of roughly 10GB, and a built
`repl` binary. Deliverable is `math-induction-pow2-gt-n` proved.

**Phase 6, API surface.** `api/openai.clj` with the `raw` bypass, `api/runs.clj`,
`api/stream.clj`, `api/control.clj`, and the arbiter's priority-zero directive
drain. Deliverable is the README's curl example working, plus a second terminal
tailing `/v1/runs/:id/events` on a live run and successfully culling a branch
through the control endpoint.

**Phase 7, benchmarks and measurement.** `bench/problems.clj` with the 53-problem
registry, `bench/runner.clj`, and gate-tally reporting queried straight out of
`gate_firings`. Then a build with `jolt build -m veriframe.core`, which produces
one self-contained binary that needs no Chez, no JVM, and no source on disk.

**Later, the UI.** A static page over the Phase 6 endpoints. Out of scope here,
and the test that it is genuinely out of scope is that building it requires no
change to anything in Phases 0 through 6.

## Probe set

Benchmark problems chosen for difficulty tell you whether the harness is good.
Problems chosen to provoke a specific gate tell you whether the harness works.
The probe set is the second kind, and every entry names the gate it exists to
exercise plus the control case where that gate must stay silent. It lives in
`bench/problems.clj` alongside the 53 ported problems and runs on every phase from
3 onward.

**Liveness, meaning the model is not stuck.** `knights-3`, a three-person knights
and knaves puzzle that should close in a handful of turns through Prolog.
`zebra-5x5`, the classic zebra puzzle, which exercises a large CLP(FD) encoding
built across several `add_rule` calls. `pythag-1000`, find the Pythagorean triple
summing to 1000, which is one small Z3 call. These are the floor: if any of them
stops closing, something regressed, and they are cheap enough to run on every
change.

**False positives.** A problem whose answer does not exist, such as a Sidon set of
size 40 inside [1,100]. The correct outcomes are `give_up` or a refutation. A
confirmed artifact is a failure of the harness regardless of what the model said,
and this is the probe that catches a gate that was relaxed too far. Paired with an
existential trap: an SMT encoding with free variables where SAT means "some
solution exists" and the model will want to ship a witness it never pinned. The
artifact must land in the existential bucket and must not substantiate a
concrete-instance answer.

**Gate provocations, live.** Each is a problem shaped to make one gate fire.
Milestone, a problem where a small result verifies early but the goal asks for
more, so the first confirmation should produce exactly one milestone steer and not
one per subsequent turn. Stuck hint, a problem whose natural encoding keeps
returning unhelpful verdicts, which should trip within three. Exploration
prologue, a question phrased to invite searching rather than verifying, such as
asking what the largest known cap set in F_3^6 is, where a branch can burn turns
in `lean_search` and produce nothing, and the bound is the only guard that can
see it. Tier escalation, a problem closable with fast checks alone, where the
end-of-run escalation to the slow tier should fire once.

**Gate provocations, scripted.** Several gates are hard to provoke deliberately
with a live model, which is the known limitation dirge shipped `safe_state_abort`
with: it had never fired end to end. A scripted model replaying a fixed transcript
of tool-call fences fires them on demand and deterministically. The claim-evidence
gate gets a transcript that calls `done` with a number no artifact contains. The
self-validator check gets one that asserts the conclusion as a rule and then
verifies it. The publish guard gets one that confirms an artifact and then
retracts the rule underneath it. Safe-state abort gets a confirmation followed by
the failure streak that triggers the rung, plus a second transcript where an
untracked mutation is present so the coverage gate must decline. These are n=1
assertions and they hold at n=1, which is the point.

**Verdict phrasings.** The offline corpus for `verdict.clj`, seeded from dirge's
finding that eleven of twenty-seven real judge phrasings parsed wrong in the same
direction. Every live benchmark run logs the raw `audit` and `review` responses,
and any phrasing not already in the corpus gets added with its correct reading.
The corpus only grows.

## Empirical checks per phase

Each phase has a measurement that has to come out right before the next one
starts, so a wrong direction shows up in the phase that caused it.

**Phase 0. Done, `jolt smoke`.** Nine probes pass, Lean skips. Five concurrent
`swipl` processes agree. Five concurrent writers put 100 rows through the single
connection. FTS5 is present in the libsqlite3 the FFI binding loads, not only in
the CLI, and a `MATCH` returns the row. `deepseek-v4-flash` is reachable and a
1200-token completion round-trips in 3.9s. And `/health` answers in 12ms while a
three-second handler is running, which on the upstream adapter takes 2713ms.

That last pair is the shape every probe here should have. The 2713ms number comes
from deliberately reverting the vendored change and re-running, which is the only
way to know the probe measures anything. Mutation-check every guard the same way.

**Phase 1. Done, `jolt -M:test`.** 21 tests, 105 assertions, plus the nine Phase 0
probes still green. knights-3 solves to its unique answer, the zebra puzzle
returns Norwegian and Japanese, five concurrent `swipl` sessions hold, and a
session survives a timed-out goal.

The phase check is engine agreement, and it paid for itself twice on the day it
was written. The Pythagorean triple summing to 1000 comes back as 200/375/425
from both clpfd and Z3. The Sidon check disagreed, and both disagreements were
real:

- The `sidon_set` template's two encodings implement different definitions. A
  Sidon set has distinct pairwise sums for a ≤ b, and the a = b case bites:
  {1,2,3,5} has 1+3 = 2+2 = 4 and is not Sidon, while every sum over strictly
  distinct pairs is unique. The TypeScript original asserts `(< a b)` in the
  cross-check against a primary enumerating i ≤ j, so the two answer different
  questions and disagree on exactly the sets where it matters. This is a live
  bug in `smt-templates.ts`. Fixed here, with the four-set regression pinned.
- The Prolog side of the same check was rendering the candidate set with
  Clojure's space-separated vector printing. SWI accepts `[1 2 3 5]` without
  complaint and reads it as something other than a four-element list, so the
  malformed query returned a confident wrong answer rather than an error.

Both are the argument for the check. A disagreement is a harness bug, not a
model bug, and this is the last phase where that distinction is clean.

Both new guards were mutation-checked. Reverting the cross-check to `<` turns
the Sidon regression red; collapsing a migration into one multi-statement string
turns the migration guard red.

**Phase 2. Done, `veriframe.bench.fence-capture/run`.** 200 live
`deepseek-v4-flash` responses, all 200 returning cleanly, stored to
`.cache/fence-corpus.jsonl` so every later parser change replays against real
output rather than invented fixtures. Replay reproduces the live numbers exactly.

| signal | rate |
|---|---|
| parse error | 0.0% |
| auto-repaired | 0.0% |
| no fence | 1.5% |
| truncated | 2.0% |
| **multiple fences** | **20.5%** |

The headline is the last row, and it changed a design decision from a guess into
a measurement. One response in five carries more than one tool-call fence. In
all 41 cases the first fence sat inside `<think>` and the last carried at least
as many arguments — the model drafts, then commits. So the last fence wins.

The TypeScript original takes the first, because `String.match` without `/g`
returns the first match, and the GLM provider deliberately merges
`reasoning_content` into the same string so the parser can see a fence "wherever
the model emits it". Re-running the corpus under a first-fence rule costs 3.0%
of turns to outright parse errors — six responses where the first fence is the
model quoting the system prompt's own `{"name": "...", "args": {...}}` template
back at itself — plus six more calls dispatched to a draft instead of the final
call. That is a live bug in `agent.ts`, and it is worth more than any steering
change measured so far.

Two rates came back at zero and both are reported rather than quietly dropped.
`auto-repaired` is zero because `clojure.data.json` tolerates what `JSON.parse`
rejects; the repair pass stays (cheap, correct, and another provider may need
it) but the capability tier must derive nothing from a signal measured at zero.
`parse-error` is zero at this budget on this model, so it is a poor probe for
the repair paths and a good one for the steering paths, which is what dirge's
tier measurements predicted for a model at this error rate.

Truncation is budget-sensitive: 2.0% at the harness's 16384-token default, and
25% at 3000 on the same prompts. The loop's token budget is therefore a
correctness parameter, not a cost knob, and `:truncated` is kept separate from
`:no-fence` so a run that hit the cap mid-thought is never read as a model too
weak to emit a tool call.

**Phase 3. Done for the single branch.** 45 tests, 261 assertions. `knights-3`
solves end to end through the full gate chain: thesis, verify, review, audit
refused once, verify_smt for independent evidence, audit passed, done accepted.
The answer is correct. `zebra-5x5` exhausts its 16 turns, which is a legitimate
outcome and the more useful of the two runs.

Four bugs came out of live runs rather than the tests, which is the argument for
running them at all.

**Every `review` returned `:ambiguous`, so no branch could ever ship.** A
reasoning judge restates the question inside `<think>`, including the
instruction's own `VERDICT: PASS or VERDICT: FAIL`, so scanning the whole
response found both answers every time. The reasoning stream is now dropped
before matching, and if markers still compete the last wins — the same rule the
fence parser earned against a measured 20.5%, for the same reason. This is not
the substring guess dirge PR 739 found; competing answers can no longer be
substrings of each other, that is checked at construction.

**A Prolog goal can succeed with the variables the claim is about still
unbound.** `findall([A,B,C], …, Sols)` succeeds with A, B and C fresh, and the
first run banked a confirmed artifact whose witness read `A = _13726`. This is
the Prolog analogue of a SAT verdict over free variables, and it now classifies
as `:existential`, which cannot substantiate an answer. It fired twice on its
first live run.

**`record-gate!` returned the event id rather than the `gate_firings` id**, so
every settle updated a row that did not exist and the entire tally stayed
permanently open. Invisible except by reading a live run's journal.

**A confirmed artifact is not the same as progress.** The zebra branch spent
turn 11 verifying that `clpfd is available and supports a basic finite-domain
constraint`. An engine said yes, the harness recorded a confirmation, and the
milestone gate congratulated it. Nothing about the puzzle had been established.
A confirmation now only resets the stall counter when it shares content with the
branch's own registered plan, so a branch verifying its own tooling no longer
reads as a branch making progress.

One more was caught by a test rather than a run, and is worth recording because
of how it failed: **a stray closing paren silently truncated the tool
multimethod's method table.** The namespace loaded, `require` succeeded, and
seven of ten tools were simply absent until a live run dispatched to one. The
tool set is now pinned by a test.

The structural assertions hold and are mutation-checked: exactly one steer per
boundary with what it outranked recorded, gates silent when their preconditions
do not hold, budgets stopping re-fires, and predictions settling deterministically
with no model in the path. The A/A calibration moves to Phase 4, where the beam
makes it worth paying for.

**Phase 4. Done, including the width sweep.** 49
tests. A live width-3 run culled a branch that emitted three consecutive
non-calls, both survivors confirmed the correct answer independently, and
residual objectives came back per branch.

**The width sweep.** Not budget-matched, deliberately: the obvious design —
width N at T turns against width 1 at N×T turns — is confounded by the turn
floor below, so every arm gets the same turns per branch and cost is reported
rather than held fixed. Two sweeps, n=1 per arm, on knights-3:

| width | sweep 1 | sweep 2 | stuck fraction |
|---|---|---|---|
| 1 | failed (9 calls, culled) | failed (12 calls) | 89% → 67% |
| 2 | failed (16) | failed (19) | 69% → 53% |
| 3 / 4 | **completed** (28) | **completed** (18) | 46% → 44% |

Only the wide arms shipped, in both sweeps, and the stuck fraction falls
monotonically with width in both. At n=1 per arm against a ~2x noise floor the
turn counts are not results; what holds is the structural reading, that four
narrow arms failed and two wide arms succeeded, and that cross-branch failure
reuse was zero at width 1 and non-zero above it — the sharing mechanism is
genuinely used rather than decorative. **This does not establish an optimum.**
It is consistent with width ≥3 helping on this problem, and a real answer needs
several problems and several repetitions per arm.

Sweep 1 was confounded by a bug it exposed: **culling the only branch ends the
run.** The cull rule exists to reallocate the beam's budget to branches doing
better, and with nobody to reallocate to it is just an early exit with turns
left on the clock. The width-1 arm died at turn 9 of 12, which reads as evidence
against narrow beams and is actually a rule fired outside the situation it was
written for. The last branch standing is never culled now.

Three more findings, two from the live runs.

**A hung provider call froze the entire beam for fifteen minutes.** The
scheduler is a barrier per turn, so one branch waiting on a socket that never
returned held the other three still. The socket timeout did not fire; the
TypeScript harness carries a hard wall-clock race for exactly this and its
comment says why — the connection sits ESTABLISHED with zero throughput while
the caller waits forever. Each branch turn now runs under a hard deadline in the
scheduler, independent of the HTTP layer, and a branch that blows it forfeits
only its own turn. This is the RAX-manager principle applied one level down: the
stop path must not depend on the component's cooperation.

**The gate chain costs more turns than a small cap allows.** A branch found the
correct answer by turn 2, reached `done` at turn 8, and was refused because
thesis, review and audit had not all landed. The chain is doing its job, but a
turn cap under roughly ten makes shipping impossible even when the answer is
found immediately. That is a real interaction between two mechanisms that were
each specified separately, and it argues for expressing the cap in terms of
turns-after-first-confirmation rather than turns.

**The progress-stalled gate had never once worked.** It referenced
`resources/prompts/progress-stalled.md`, which did not exist, and `slurp` of a
nil resource throws — so the gate killed whatever branch it fired on, and
because a dying branch is abandoned rather than surfaced it simply never
appeared in any tally. Found by a warning buried in the sweep output. Every
gate's message is now rendered by a test, which is the general form of the
guard: a gate that cannot produce its text is not a quiet gate, it is a broken
one.

**The model did not fork on its own.** `branch_theses` was available and never
called across both live beams, so the fork path is proven by a scheduler test
rather than by a run. Worth watching: if it stays unused across the benchmark
sweep, that is evidence for the open question about whether forking survives at
all rather than evidence the mechanism is broken.

The stuck fraction — the share of turns producing no verified progress — is
collected by `bench.beam/run-metrics` and is the single number to watch across
later phases.

**Phase 5. Done.** `2^n > n` proved end to end through the stateful proof
session, recorded as a confirmed slow-tier artifact. Three things cost real time
and are worth writing down.

The repl must be pinned to the COMMIT whose toolchain matches the workspace, not
just handed a toolchain override: HEAD's `REPL/Lean/Replay.lean` uses options
v4.29.1 does not have, so the build fails. It must be spawned through `lake env`
from the workspace directory or it cannot find `lean` at all and exits 255. And
`lake` has to be named by absolute path, because a child's executable is
resolved against the PARENT's PATH and elan installs outside it.

The premise index is where the plan's stated fallback stopped being optional.
Scanning Mathlib's 7871 files in-process had not finished after thirty minutes.
Handing the same regex to `grep` does it in three seconds — but then decoding
215k results into maps and tokenizing them per query became the bottleneck in
turn. Both passes are now grep's job: the index is a plain text file that is
never loaded into memory, and a query greps it and ranks only the handful of
lines that match. 215k declarations, 4.7s to build, ~300ms to search. Note that
`rg` is not usable here — it is frequently a shell function rather than a
binary, and a subprocess cannot call one.

**Phase 6. Done.** A run started over `POST /v1/runs` returns its id
immediately, the journal tail serves it by cursor while it is still running, an
intervention submitted as `pending` came back `applied` at turn 2, and abort
stops it. The full surface: `/health` with per-engine availability, `/v1/models`,
`/v1/chat/completions` with the `raw` bypass, `/v1/harness/gates`, `/v1/runs`,
`/v1/runs/:id`, `/v1/runs/:id/journal?since=`, `/v1/runs/:id/branches/:branch`,
`/v1/runs/:id/interventions`, `/v1/runs/:id/abort`.

One bug the first API call found: the JSON body uses underscored keys and the
destructuring expected hyphens, so `beam_width: 2` silently became the config
default of 5. Both spellings are accepted now and the response echoes what was
actually used, because a request that is quietly ignored is worse than one that
is refused.

**Phase 7. Partly done.** `bench/problems.clj` carries 14 problems — the probe
set from this document plus a difficulty sample — each with a harness-level
expectation the runner checks against the journal rather than against a
transcript. `bench/runner.clj` reports pass, fail, INERT and error, where inert
means a probe expected a gate and the gate never fired: that is not a pass,
because a silent guard and a working guard look identical from outside. The full
53-problem port is mechanical and not done.

`jolt build` produces a 24MB self-contained binary that starts, serves, and
detects all three engines. Two limitations, both real:

**The binary cannot make HTTPS calls.** Plain http works, https throws the same
opaque Chez condition seen in the nREPL ordering bug. So the binary is usable
against a local plain-http endpoint and for the engine paths, and not against a
hosted provider. Reported upstream.

**`jolt build` embeds only what it can reach statically.** A `requiring-resolve`
in `system/start!` left the entire server and engine subtree out of the image,
which then failed at startup trying to compile namespaces off source roots that
do not exist. The handler is now passed in as a var from `core`, which is the
only namespace that can require both. The same constraint means nREPL cannot
load lazily in a binary, and since the lazy load is what keeps TLS working in
the interpreted path, the interpreted path wins: nREPL simply does not start in
a binary, which costs nothing given https does not work there either.

**The full sweep ran and is reported here as incomplete, because it is.** Eight
of fourteen problems reached a terminal state before the sweep wedged; the
remaining six were never reached. Two runs shipped verified answers, four
exhausted their turn cap, and two hung.

| gate | fired | met | unmet | open |
|---|---|---|---|---|
| milestone | 13 | 10 | 1 | 2 |
| emergency-review | 8 | 4 | 1 | 3 |
| stuck | 5 | 0 | 2 | 3 |
| turn-budget | 4 | 3 | 0 | 1 |
| safe-state | 3 | 0 | 0 | 3 |
| progress-stalled | 3 | 2 | 0 | 1 |
| done-blocked | 2 | 1 | 0 | 1 |

Eight of the ten gates fired, including `progress-stalled` (which had never once
worked before this session) and `safe-state` (which dirge shipped having never
seen fire end to end). `human-directive` and `prologue-cap` did not, and the
prologue probe was in the batch that was never reached. Artifacts split 27
confirmed, 2 existential, 2 ambiguous, 1 refuted, so the classification buckets
are all populated by real runs rather than only by tests. Cross-branch failure
sharing produced 41 entries across 7 runs.

`stuck` fired five times and met its prediction zero times, which is the most
interesting number in the table: the gate is firing on the right condition and
the branch is not doing what it asks. That is a steering finding rather than a
bug, and it is exactly what the prediction column exists to surface.

Two bugs and one platform limit came out of the attempt.

**All SQLite access has to be serialized, not just writes.** Every read also
calls `sqlite3_prepare_v2` on the same connection handle, and four concurrent
problems times three branches is twelve threads preparing at once. The first
sweep failed all fourteen problems with `sqlite prepare failed: not an error`
and then an invalid memory reference on close. The Phase 0 probe passed because
it only exercised writers, which were the half already serialized.

**An abandoned turn wedged its engine session permanently.** The beam abandons a
branch turn that blows its deadline, but the abandoned work keeps running and,
under `locking`, keeps the session monitor. The next turn blocked on that
monitor with no timeout, so the deadline fired exactly once and the branch
wedged forever, taking the per-turn barrier with it. Sessions now use a
compare-and-set flag and a busy session is killed rather than waited on, since
its unread reply would misframe every later one.

**A timed `deref` cannot preempt a thread parked in a blocking FFI call.** This
is the one that stopped the sweep and it is not fixable where it currently
sits. One run sat 31 minutes against a 420s deadline: the branch was inside a
socket read that never returned, `:socket-timeout` did not fire, and the timed
deref could not interrupt it. The same shape froze a Phase 4 beam for fifteen
minutes. A real bound has to come from outside the thread — a provider call in
a subprocess that can be killed, or a watchdog that closes the socket under it
— and both are more than a patch. Until then, concurrency above about six
in-flight provider calls should be treated as unsafe, and the sweep should run
at `:concurrency 2`.

## Testing

The DS1 layered approach, and dirge's measurement discipline on top of it.

Unit tests for the pure parts: fence parsing, verdict parsing against the
phrasing corpus, lint rules, artifact classification, gate preconditions.

Engine-in-the-loop tests with a **scripted fake model** that replays a fixed list
of tool-call fences. This is the layer that carries the weight. It asserts which
gate fired, in what order, and that exactly one steer landed per boundary. These
assertions hold at n=1, which is the point: PR 739 measured a roughly 2x
run-to-run noise floor on identical configurations, so any claim of the form "this
reduces turns" is unmeasurable at an affordable sample size, while "the mechanism
fired when it should and stayed silent otherwise" is checkable deterministically.

Every run records the same fields, all queried from the journal so they cost
nothing to collect: turns to first confirmed artifact, turns from first
confirmation to `done`, stuck fraction, fence parse failure and auto-repair rates,
engine error rate, the artifact bucket split across confirmed, refuted, ambiguous
and existential, gate firings with their prediction settle rate, branch culls and
forks, wall clock, and tokens. Comparisons are between these, never between
impressions of a transcript.

Live-model runs against the benchmark registry, reported per model, with a
mechanism check that refuses to interpret an arm whose gate-fire count is zero.
No steering change ships on a measured effect unless it clears the noise floor,
and the plan's assumption is that none will.

Mutation testing on every new guard, meaning break it deliberately and confirm
its test goes red. PR 749 did this and it is what separates a guard from a guard
with a test that passes for the wrong reason. PR 740's failure mode is worth
guarding against specifically: a counter with zero production callers and a test
that calls the recorder directly passes forever while measuring nothing. A
one-line check that every `record-*` in `journal.clj` has a caller outside its own
namespace and tests would have caught it.

## Live checks, 2026-08-06 (UCLA-findings epic, vf-1bx)

Two live runs against `deepseek-v4-flash` after the epic landed, read the only
way n=1 permits: did the mechanism fire when it should and stay silent
otherwise.

**Wind-down rung.** Provocation: `sidon-40-in-100` at `max-turns 8`. The rung
fired exactly once, at turn 7 (the first boundary past 0.85×8), its prediction
settled `met` at turn 8, and the run exhausted rather than shipping the
nonexistent set — the false-positive discipline held under budget pressure.
Control: `knights-3` at `max-turns 40` shipped at turn 29 with zero wind-down
firings. Both sides of the probe contract hold.

**Shared-artifact flag.** `sweep-widths` on knights-3, widths [1 2 4] at 12
turns per branch, flag on then off. On: 15 artifacts entered the pool and 100
`shared-artifact-hit` events were journaled across the width-2 and width-4
runs; both shipped. Off: zero events, zero pool rows, arms comparable — the
flag gates both the write and the read side. Width-2 shipped at 12 branch-turns
on versus 14 off, which is under the noise floor and is not a result. The
width question stays open; the instrument now demonstrably works.

Two findings came out of the runs. `emergency-review` re-fired on three
consecutive boundaries of the control run, every prediction unmet — it was the
one steer gate with no re-fire guard, and its precondition persists while the
branch is busy complying. It is now guarded (`:max-emergency-reviews`, fires
once). And a hit event is journaled every turn an artifact is re-served into a
context, so one 28-turn run produced 86 of them; correct but chatty, and
journaling only first-time artifact-to-branch servings would make the count
directly interpretable. Fixed (vf-emt): the branch now carries the served
artifact ids and only the first serving of each artifact to each branch is
journaled; artifacts still re-enter the context every turn. The set is branch
memory, so a resume can journal one duplicate hit per pair — same accepted
class as the claim registry.

## Live checks, 2026-08-06 (open-problem probes)

Two runs against `deepseek-v4-flash` probing how the harness behaves when the
problem itself is beyond reach.

**P != NP, beam 2.** Both branches called `give_up` on turn 1 with accurate
reasons ("open problem; no contradiction from P = NP is known"). No engine
call, no fabricated proof, run failed in 17 seconds. The honest exit is the
path of least resistance, which is the design goal.

**Magic square of squares, beam 2 at 30 turns.** The productive contrast. B2
was culled at turn 4 after three consecutive failures. B1 confirmed five
artifacts — the mod-8 obstruction (odd center forces every entry ≡ 1 mod 8,
even center forces all entries even squares) — in two engines with genuinely
different shapes: Z3 over the affine parametrization, Prolog exhausting all
3^9 residue grids. The gate story is the finding. `review` first FAILED the
cross-check as "the same reduction rewritten" and passed only a
different-shape re-verification — the independence requirement did real work.
`done` was refused once because the answer text differed from what the audit
approved — verbatim matching did real work. But three audits came back
unparseable (judge hit its token cap mid-reasoning), and the branch reached
an audit-approved answer exactly at the turn cap: exhausted, one turn short
of shipping. The residual report carries all five confirmed lemmas. Filed
vf-42e: unparseable judge verdicts should not cost the branch its turn.

A launch bug surfaced en route: any POST body with multibyte UTF-8 hung the
server — the vendored adapter judged request completeness by character count
against a byte-denominated Content-Length. Fixed with a regression test
(server-test), verified live.

## Risks

**Blocking pipe reads under Chez threads. Resolved.** Five branches each holding
a `swipl` session means concurrent blocking reads on subprocess pipes. Phase 1
runs five persistent sessions in parallel through the full JSON protocol and they
hold, so the Phase 4 beam can keep its branches genuinely concurrent. Lean adds a
second long-lived process per branch and is not covered by this; re-probe in
Phase 5. The fallback if it ever fails is a queue per engine, keeping branches
concurrent only in their provider calls, which is where the wall clock goes
anyway.

**Mathlib index size.** `lean-search.ts` scans every `.lean` file under Mathlib
and caches an index of roughly 235k declarations. Holding that in memory in a Chez
process is untested. The fallback is a ripgrep-backed search over the cached index
file instead of an in-memory structure, which loses ranking quality but not
recall.

**Long-lived HTTPS requests.** Provider calls run to a 300s timeout with 16k-token
responses. clj-http-lite over `jolt.ffi` sockets supports read and connect
timeouts, so the API is there, but a five-minute TLS read has not been exercised.
Probe in Phase 2 against a deliberately slow endpoint.

**Lean toolchain cost.** Roughly 10GB and a long first build, and neither elan nor
lake is installed. Phase 5 is isolated from the rest for exactly this reason, and
Phases 1 through 4 plus 6 deliver a working Prolog and Z3 harness without it.

**SQLite through FFI at concurrency. Resolved.** FTS5 is present in the
libsqlite3 the FFI binding loads, not only in the CLI, and five writers put 100
rows through one connection under a lock. Writes stay funnelled through a single
writer, which the event mult already implies: branches publish, one consumer
persists, readers open their own connections. What is still unproven is
contention at real journal volume rather than 100 rows, so watch it in Phase 3.

**Intervention semantics.** A directive arriving mid-turn cannot be applied
mid-turn, because a branch in the middle of a provider call or a Lean tactic is
not in a state anyone should mutate. Directives apply at the next boundary, which
means the UI has to show pending-versus-applied honestly rather than pretending a
click took effect. Abort is the exception and goes to the supervisor, not the
queue.

**Scope.** The original is 11.9k lines, of which 3.7k is the benchmark registry
(data, mechanical to convert) and 3.2k is `agent.ts` (the part being redesigned).
The genuinely new work is the control layer, and it is smaller than what it
replaces because the arbiter collapses a chain of conditionals into a table.

## Open questions

Whether to keep `BEAM_WIDTH` at 5. Five parallel branches is five concurrent
provider calls per turn, and the beam's value over a single branch with the same
token budget was never measured in the original. Phase 4's check is the
measurement, and the width should be treated as unjustified until it comes back.

Whether `branch_theses` survives. It adds a fork path, a total cap, a
cap-exceeded message, and child-spawn failure handling into the failure log, and
the AHE finding that mechanism beats prose does not obviously extend to mechanism
that mostly exists to let the model spend more tokens.

Whether the system prompt should be split per tier of model. The original writes
one prompt for every provider. Keeping prompts as files makes per-model variants
free to try and free to measure.
