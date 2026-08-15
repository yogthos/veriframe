# jolt issues found while running veriframe

Each one below has a minimal repro that was run on this machine. Where a
previous note in this repo was wrong, that is said plainly rather than quietly
dropped.

## FIRST: `destroy-tree` is NOT broken. The Lean leak was ours.

`vf-cfp` was filed as "Lean sessions leak because jolt's `destroy-tree` cannot
kill a grandchild (jolt-hpdu)", and that premise is wrong. Two experiments:

```clojure
;; 1. plain grandchild: jolt -> sh -> sleep
(let [proc (p/process ["sh" "-c" "sleep 300 & wait"])]
  (Thread/sleep 1500)
  (.descendants (.toHandle (:proc proc)))   ;; => 1 descendant, found
  (p/destroy-tree proc))
;; => sleep is DEAD. destroy-tree reached the grandchild.

;; 2. the exact veriframe spawn: jolt -> lake env -> repl
(let [proc (p/process [lake "env" repl] {:dir ws :shutdown p/destroy-tree})]
  (Thread/sleep 4000)
  (.descendants (.toHandle (:proc proc)))   ;; => 1 descendant, found
  (p/destroy-tree proc))
;; => lake alive: false, repl alive: false. Both dead.
```

So `descendants` returns the child and `destroy-tree` kills it. **No jolt fix
is needed for the Lean session leak.** Nineteen repl processes had accumulated
(2.1GB, oldest up 1 day 7 hours, five reparented to init) because veriframe
never called `dispose!` on them, not because the call failed. That is being
re-diagnosed on our side; nothing is needed from jolt.

The defensive change we made — collect descendant pids before killing the
parent and kill them by pid — is harmless and stays, but it was not the fix.

---

## 1. `_` as both a positional parameter and the rest parameter

**Severity: medium.** Legal Clojure, rejected by jolt. Cost an unnecessary
debugging detour when a test helper needed to ignore its first two arguments
and its trailing ones.

```clojure
;; FAILS
((fn [_ x & _] x) 1 :ok)
((fn [_ _ x & _] x) 1 2 :ok)
;; Exception: invalid parameter list in (lambda (_ x . _) ...)

;; WORKS — no rest argument
((fn [_ _ x] x) 1 2 :ok)   ;; => :ok
```

The emitted Scheme shows the cause. For `(fn [_ _ x & _] ...)` jolt emits:

```scheme
(lambda (_r$$__0 _ x . _) ...)
```

The *first* `_` was renamed to `_r$$__0`, the second was not, and the rest
parameter was not — so `_` is bound twice in one lambda and Scheme rejects the
list. The renaming pass is inconsistent: it appears to rename only the first
occurrence rather than every one.

Clojure permits duplicate parameter names generally (`(fn [x x] x)` is legal,
last binding wins), so the safe fix is to gensym **every** occurrence of a
duplicated parameter symbol, rest parameter included.

Workaround in our code: name them distinctly — `(fn [_a _b msgs & _rest] ...)`.

## 2. `jolt -M <file.clj>` is not supported

**Severity: low**, but it is a papercut for anyone porting a Clojure workflow.

```
$ jolt -A:dev -M /tmp/probe.clj
Unhandled exception: unsupported :main-opts ["/tmp/probe.clj"]
```

`clojure -M script.clj` runs the file. In jolt the only route is `-M -e '<forms>'`
or `-M -m <namespace>`, which means anything long has to be squeezed onto the
command line and quoting becomes the problem instead.

Related and already fixed on your side: the nrepl-client docstring used to show
`jolt -A:dev dev/nrepl_client.clj ...`, which loads the namespace without
calling `-main`, prints nothing, and exits 0 — indistinguishable from success.

## 3. `deref` with a timeout against `babashka.process/process`

**Severity: medium**, because it fails silently in the direction of hanging.

```clojure
(let [proc (p/process ["sleep" "5"])]
  (deref proc 300 ::timed-out))
;; => class babashka.process.Process cannot be cast to class
;;    clojure.lang.IBlockingDeref
```

In Clojure this is the standard way to wait on a process with a deadline. Two
things differ here:

- the record does not implement `IBlockingDeref`, so the three-arity throws;
- when a record *does* implement it, jolt's `clojure.core/deref` forwards no
  opts, so `(deref x ms ::timeout)` silently calls the blocking one-arity and
  waits forever.

The second is the dangerous one — the timeout appears to be honoured and is
not. We hit it in `veriframe.engine.proc/run` and worked around it with an
explicit `.waitFor` carrying the timeout; the comment there records the
symptom. Anyone porting Clojure code that uses `(deref future ms default)` will
hit the same thing.

## Not a bug, but worth knowing

`destroy-tree` and `destroy` print with the same object hash
(`babashka.process$destroy_tree@9bdd2eafc2` and
`babashka.process$destroy@9bdd2eafc2`) while being distinct functions —
`(= destroy-tree destroy)` is `false`. That identical hash is what led us to
believe they were aliased, and from there to a wrong diagnosis that stood for
several days. If the printed hash is a constant rather than an identity, making
it an identity would remove a real trap.
