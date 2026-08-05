(ns veriframe.engine.smt
  "Z3 SMT verification: write SMT-LIB to the `z3` binary's stdin, read back
  sat / unsat / unknown, and on SAT parse the witness model.

  Three things here are not plumbing, and each exists because the harness
  shipped a false positive without it.

  The linter runs first and blocks execution, because a `;` that swallowed the
  assertions produces a formula Z3 honestly reports as SAT.

  A verdict is refused outright when Z3 emitted any parse or type error. Z3
  keeps going after an `(error ...)` line and will print `sat` for whatever
  subset of the formula parsed. The verdict applies to that subset, not to
  what the model wrote.

  A SAT witness is checked back against the constraints it is supposed to
  satisfy. If the witness violates a `(distinct ...)` or a range the formula
  asserts, then Z3 satisfied something other than what the model believed it
  asserted, and that is an encoding bug rather than a result."
  (:require [clojure.string :as str]
            [veriframe.engine.lint :as lint]
            [veriframe.engine.proc :as proc]
            [veriframe.engine.smt-templates :as templates]))

(def default-timeout-ms 30000)

;; --- witness model ----------------------------------------------------------

(defn parse-model
  "Parse `(get-model)` output into a name -> value map.

      ( (define-fun e01 () Bool true)
        (define-fun x () Int (- 5)) )

  Values contain nested parens, so each `(define-fun ...)` block is walked
  paren-balanced rather than matched with a greedy regex."
  [output]
  (loop [i 0, acc {}]
    (let [start (str/index-of output "(define-fun" i)]
      (if-not start
        acc
        (let [end (loop [j start, depth 0]
                    (cond
                      (>= j (count output)) nil
                      (= \( (nth output j)) (recur (inc j) (inc depth))
                      (= \) (nth output j)) (if (= 1 depth) (inc j) (recur (inc j) (dec depth)))
                      :else (recur (inc j) depth)))]
          (if-not end
            acc
            (let [inner (str/trim (subs output (+ start (count "(define-fun")) (dec end)))
                  m (re-find #"^([^\s()]+)\s+\(\s*\)\s+\S+\s+" inner)]
              (recur end
                     (if m
                       (assoc acc (second m) (str/trim (subs inner (count (first m)))))
                       acc)))))))))

(defn- parse-value
  "Read a witness value as a number. Z3 prints negatives as `(- 5)`."
  [raw]
  (when raw
    (let [s (str/trim raw)]
      (or (parse-long s)
          (some->> (re-find #"^\(\s*-\s+(\d+)\s*\)$" s) second parse-long -)))))

(defn check-witness
  "Re-read the formula's simple constraints and validate them against the
  concrete witness. Not an SMT-LIB parser: regexes over comment-stripped text,
  covering direct assertions, which is the shape every false positive here has
  had. Returns a vector of human-readable issues, empty when consistent."
  [smtlib model]
  (let [stripped (lint/strip-smt-comments smtlib)
        issues (transient [])]

    ;; (distinct v1 v2 ... vn) over bare identifiers. A witness with duplicates
    ;; means the constraint did not apply where the model thought it did.
    (doseq [[_ arglist] (re-seq #"\(\s*distinct\s+([^()]+?)\s*\)" stripped)]
      (let [vars (remove str/blank? (str/split (str/trim arglist) #"\s+"))]
        (when (and (>= (count vars) 2)
                   (every? #(re-matches #"[A-Za-z_][\w-]*" %) vars))
          (loop [vs vars, seen {}]
            (when-let [v (first vs)]
              (let [raw (get model v)]
                (if (nil? raw)
                  (recur (rest vs) seen)
                  (let [k (str/trim raw)
                        prev (get seen k)]
                    (if (and prev (not= prev v))
                      (conj! issues (str "(distinct " (str/join " " vars) ") was asserted,"
                                         " but the witness gives " prev "=" k " AND "
                                         v "=" k " — the constants are NOT distinct."))
                      (recur (rest vs) (assoc seen k v)))))))))))

    ;; Range and equality assertions over bare identifiers.
    (doseq [[re fmt pred]
            [[#"\(\s*>=\s+([A-Za-z_][\w-]*)\s+(-?\d+)\s*\)" ">=" <]
             [#"\(\s*<=\s+([A-Za-z_][\w-]*)\s+(-?\d+)\s*\)" "<=" >]
             [#"\(\s*=\s+([A-Za-z_][\w-]*)\s+(-?\d+)\s*\)" "=" not=]]]
      (doseq [[_ name bound] (re-seq re stripped)]
        (when-let [v (parse-value (get model name))]
          (let [b (parse-long bound)]
            (when (pred v b)
              (conj! issues (str "(" fmt " " name " " bound ") was asserted, but the"
                                 " witness gives " name "=" v
                                 (case fmt
                                   ">=" " which is below the bound."
                                   "<=" " which is above the bound."
                                   "."))))))))

    (persistent! issues)))

;; --- the call ---------------------------------------------------------------

(def ^:private benign-error #"model is not available")

(defn- z3-error-lines [lines]
  (filter (fn [l]
            (and (or (str/starts-with? l "(error ")
                     (re-find #"(?i)^error\b" l)
                     (re-find #"(?i)^unsupported\b" l))
                 (not (re-find benign-error l))))
          lines))

(defn run-smt
  "Check `smtlib` with Z3.

  Returns {:status :ok :verdict :sat|:unsat|:unknown :output s :model m} or
  {:status :error :error s}."
  ([smtlib] (run-smt smtlib nil))
  ([smtlib {:keys [bin timeout-ms] :or {bin "z3" timeout-ms default-timeout-ms}}]
   (let [{:keys [ok warnings]} (lint/lint-smt smtlib)]
     (if-not ok
       {:status :error
        :error (str "SMT lint rejected the input — execution skipped:\n  • "
                    (str/join "\n  • " warnings))}
       (let [code (cond-> smtlib
                    (not (re-find #"\(\s*check-sat\s*\)" smtlib)) (str "\n(check-sat)")
                    (not (re-find #"\(\s*get-model\s*\)" smtlib)) (str "\n(get-model)")
                    :always (str "\n"))
             {:keys [out err timeout]} (proc/run {:input code :timeout-ms timeout-ms}
                                                 bin "-smt2" "-in")]
         (cond
           timeout
           {:status :error :error (str "z3 exceeded " timeout-ms "ms and was killed.")}

           :else
           (let [lines (->> (str/split-lines out) (map str/trim) (remove str/blank?))
                 errs (z3-error-lines lines)
                 stderr (str/trim err)]
             (cond
               (or (seq errs) (seq stderr))
               {:status :error
                :error (str/join
                        "\n"
                        (concat
                         (when (seq errs)
                           (cons (str "Z3 emitted " (count errs) " parse/type error(s):")
                                 (concat (map #(str "  " %) (take 5 errs))
                                         (when (> (count errs) 5)
                                           [(str "  (+" (- (count errs) 5) " more)")]))))
                         (when (seq stderr)
                           [(str "stderr: " (subs stderr 0 (min 500 (count stderr))))])
                         [(str "Common cause: literal '...' ellipsis shorthand in SMT-LIB."
                               " Spell out every `(declare-const ...)` and every"
                               " `(+ a_i a_j)` explicitly — Z3 has no abbreviation syntax."
                               " The harness refuses to report a verdict when Z3 emitted"
                               " errors, because the verdict applies to the SUBSET of your"
                               " formula that parsed, not the formula you wrote.")]))}

               :else
               (let [verdict (->> (reverse lines)
                                  (some #{"sat" "unsat" "unknown"}))]
                 (cond
                   (nil? verdict)
                   {:status :error
                    :error (str "z3 produced no verdict. Output was:\n" (str/trim out))}

                   (not= "sat" verdict)
                   {:status :ok :verdict (keyword verdict) :output (str/trim out)}

                   :else
                   (let [model (parse-model out)
                         issues (check-witness smtlib model)]
                     (if (seq issues)
                       {:status :error
                        :error (str/join
                                "\n"
                                (concat
                                 [(str "Z3 returned SAT but the witness model is"
                                       " internally inconsistent with the formula's stated"
                                       " constraints. Z3 satisfied a SUBSET of what you"
                                       " asserted — usually because the witness violates a"
                                       " (distinct ...) you wrote, or falls outside a range"
                                       " you asserted. Common cause: missing"
                                       " `(distinct a_1 ... a_n)` for the underlying"
                                       " constants when you only asserted distinctness of"
                                       " derived sums.")]
                                 (map #(str "  • " %) issues)
                                 [(str "Witness: " (pr-str model))]))}
                       {:status :ok
                        :verdict :sat
                        :output (str/trim out)
                        :model model}))))))))))))

;; --- templates --------------------------------------------------------------

(defn run-template
  "Assemble a vetted template's two encodings, run both, and confirm only when
  they agree.

  The encodings have opposite polarity on purpose, so `:confirmed true` means
  two independent formulations reached the same conclusion. A disagreement is
  not a weak result, it is a bug in one of the encodings, and it is reported
  as one rather than resolved in favour of whichever came back first."
  ([template-name slots] (run-template template-name slots nil))
  ([template-name slots opts]
   (if-let [t (get templates/templates template-name)]
     (let [assembled (try
                       {:primary ((:primary t) slots)
                        :cross ((:cross t) slots)}
                       (catch Throwable e
                         {:error (ex-message e)}))]
       (if-let [err (:error assembled)]
         {:status :error
          :error (str "Template `" template-name "` rejected its slots: " err)}
         (let [pr* (run-smt (:primary assembled) opts)
               cr (run-smt (:cross assembled) opts)]
           (cond
             (= :error (:status pr*))
             {:status :error :error (str "Primary encoding failed: " (:error pr*))}

             (= :error (:status cr))
             {:status :error :error (str "Cross-check encoding failed: " (:error cr))}

             :else
             (let [primary-ok (= (:verdict pr*) (:primary-verdict t))
                   cross-ok (= (:verdict cr) (:cross-verdict t))]
               {:status :ok
                :confirmed (and primary-ok cross-ok)
                :agreed (= primary-ok cross-ok)
                :primary {:verdict (:verdict pr*) :expected (:primary-verdict t)
                          :smtlib (:primary assembled) :model (:model pr*)}
                :cross {:verdict (:verdict cr) :expected (:cross-verdict t)
                        :smtlib (:cross assembled)}
                :note (cond
                        (and primary-ok cross-ok)
                        "Both encodings confirm the claim."
                        (and (not primary-ok) (not cross-ok))
                        "Both encodings refute the claim."
                        :else
                        (str "The two encodings DISAGREE. Primary said "
                             (name (:verdict pr*)) " (expected "
                             (name (:primary-verdict t)) "), cross-check said "
                             (name (:verdict cr)) " (expected "
                             (name (:cross-verdict t)) "). One of them is wrong;"
                             " this is an encoding bug, not a weak result."))})))))
     {:status :error
      :error (str "Unknown template `" template-name "`. Available:\n"
                  (templates/list-templates))})))
