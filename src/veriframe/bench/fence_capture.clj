;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU General Public License as
;; published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public
;; License along with this program. If not, see
;; <https://www.gnu.org/licenses/>.

(ns veriframe.bench.fence-capture
  "Phase 2's empirical check: capture real model responses and measure how
  often the fence parser has to do something other than parse cleanly.

  The point is the auto-repair rate, not the pass rate. The capability tier
  reads that number later, and a tier built on a rate nobody measured is
  exactly the counter with zero production callers that dirge PR 740 found.
  Measuring it once here means the tier's thresholds have a basis.

  Responses are written to a corpus file, so every later parser change can be
  replayed against real model output offline instead of against invented
  fixtures. The corpus only grows.

      jolt -A:dev -e \"(require 'veriframe.bench.fence-capture)
                       (veriframe.bench.fence-capture/run 200)\"
      # or offline, against what was already captured:
      (veriframe.bench.fence-capture/replay)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [veriframe.config :as config]
            [veriframe.llm.client :as llm]
            [veriframe.llm.fence :as fence]
            [veriframe.llm.registry :as registry]))

(def corpus-path ".cache/fence-corpus.jsonl")

(def system-prompt
  (str "You call tools by emitting exactly one fenced block:\n"
       "```tool-call\n{\"name\": \"...\", \"args\": {...}}\n```\n"
       "Available tools:\n"
       "  verify_smt({claim, smtlib})    Z3 sat/unsat check\n"
       "  verify({claim, check})         a Prolog goal that succeeds iff the claim holds\n"
       "  add_rule({name, code})         Prolog facts and rules\n"
       "  verify_lean({claim, lean})     a Lean 4 snippet against Mathlib\n"
       "  thesis({goal, subClaims, technique})\n"
       "  done({answer})\n"
       "Emit the fenced block and nothing after it."))

;; Prompts chosen to make the model write multi-line code into a JSON string
;; value, because that is where the parser actually gets stressed. A prompt
;; whose answer is one short line tests nothing.
(def prompts
  ["Check with Z3 whether positive integers a,b,c exist with a^2+b^2=c^2 and a+b+c=1000."
   "Check with Z3 whether {1,2,5,11,13} has all pairwise sums distinct. Write it out fully."
   "Encode the zebra puzzle's house-colour constraints as Prolog rules with add_rule."
   "Use verify to check in Prolog that in a knights-and-knaves puzzle, if A says B is a knave, A and B cannot both be knights."
   "Prove in Lean 4 that for all natural numbers n, 2^n > n. Use verify_lean."
   "Use Z3 to check whether a 4-colouring of [1,20] exists with no monochromatic x+y=z."
   "State a thesis for proving that the sum 1+2+...+n equals n(n+1)/2."
   "Use verify_smt to check whether x^3 - 2x + 1 has a real root between 0 and 1."
   "Write Prolog rules with add_rule for the transitive closure of an edge relation, then say you are done."
   "Use verify_lean to show that the empty set is a subset of every set."
   "Check with Z3 whether there are integers x,y with 3x + 5y = 1 and 0 < x < 100."
   "Encode with verify_smt: does a 5-element subset of [1,20] exist with no three-term arithmetic progression?"])

(defn- capture-one [adapter cfg idx]
  (let [prompt (nth prompts (mod idx (count prompts)))
        ;; Vary the phrasing by index so identical prompts do not collapse to
        ;; identical responses under caching.
        user (str prompt (when (>= idx (count prompts))
                           (str "\n(attempt " (inc (quot idx (count prompts))) ")")))]
    (try
      ;; No max-tokens override: measure at the budget the harness actually
      ;; runs with, or the truncation rate describes the bench rather than the
      ;; harness. At 3000 tokens this same set truncated 25% of the time.
      (let [r (llm/chat adapter cfg
                        [{:role :system :content system-prompt}
                         {:role :user :content user}])
            parsed (fence/parse-tool-call (:content r))]
        {:idx idx
         :prompt user
         :content (:content r)
         :finish-reason (:finish-reason r)
         :elapsed-ms (:elapsed-ms r)
         :usage (:usage r)
         :tool (:name parsed)
         :signals (fence/signals r parsed)})
      (catch Throwable e
        {:idx idx :prompt user :error (ex-message e)}))))

(defn- in-batches
  "Run f over items, `width` at a time. Bounded rather than unbounded, since
  200 concurrent provider calls is a rate limit rather than throughput."
  [width f items]
  (vec (mapcat (fn [batch] (mapv deref (mapv #(future (f %)) batch)))
               (partition-all width items))))

(defn summarize [records]
  (let [ok (remove :error records)
        n (count ok)
        rate (fn [pred] (if (zero? n) 0.0 (/ (double (count (filter pred ok))) n)))
        sig (fn [k] (rate #(get-in % [:signals k])))]
    {:requested (count records)
     :succeeded n
     :failed (count (filter :error records))
     :no-fence (sig :no-fence)
     :truncated (sig :truncated)
     :parse-error (sig :parse-error)
     :auto-repaired (sig :auto-repaired)
     :multiple-fences (sig :multiple-fences)
     :tools (frequencies (keep :tool ok))
     :median-elapsed-ms (when (pos? n)
                          (nth (sort (map :elapsed-ms ok)) (quot n 2)))
     :total-completion-tokens (reduce + 0 (keep #(get-in % [:usage :completion-tokens]) ok))}))

(defn- report [label summary]
  (println)
  (println label)
  (println (format "  %-18s %d requested, %d succeeded, %d failed"
                   "calls" (:requested summary) (:succeeded summary) (:failed summary)))
  (doseq [k [:no-fence :truncated :parse-error :auto-repaired :multiple-fences]]
    (println (format "  %-18s %.1f%%" (name k) (* 100.0 (get summary k)))))
  (println (format "  %-18s %s" "tools" (pr-str (:tools summary))))
  (println (format "  %-18s %s ms" "median latency" (:median-elapsed-ms summary)))
  (println (format "  %-18s %s" "completion tokens" (:total-completion-tokens summary)))
  summary)

(defn append-corpus! [records]
  ;; Read-concat-write rather than an appending writer: clojure.java.io/writer
  ;; takes no options on this host. The corpus is small enough that rewriting
  ;; it is cheaper than the workaround would be to maintain.
  (io/make-parents corpus-path)
  (let [existing (if (.exists (io/file corpus-path)) (slurp corpus-path) "")
        added (->> (remove :error records)
                   (map #(json/write-str (select-keys % [:prompt :content :finish-reason])))
                   (str/join "\n"))]
    (spit corpus-path (str existing added "\n"))))

(defn run
  "Capture `n` live responses, append them to the corpus, and report."
  ([] (run 200))
  ([n] (run n 8))
  ([n width]
   (let [cfg (:llm (config/load-config))
         adapter (registry/adapter-for (:provider cfg))
         _ (println "capturing" n "responses from" (name (:provider cfg)) "/" (:model cfg))
         records (in-batches width #(capture-one adapter cfg %) (range n))]
     (append-corpus! records)
     (report (str "live capture (" (:model cfg) ")") (summarize records)))))

(defn replay
  "Re-parse the stored corpus. Offline, deterministic, and the way to check a
  parser change against real output rather than invented fixtures."
  []
  (if-not (.exists (io/file corpus-path))
    (println "no corpus at" corpus-path "— run `run` first")
    (let [records (with-open [r (io/reader corpus-path)]
                    (doall
                     (for [line (line-seq r)
                           :when (not (str/blank? line))
                           :let [rec (json/read-str line :key-fn keyword)
                                 parsed (fence/parse-tool-call (:content rec))]]
                       (assoc rec
                              :tool (:name parsed)
                              :signals (fence/signals rec parsed)))))]
      (report (str "corpus replay (" (count records) " stored responses)")
              (summarize records))))))
