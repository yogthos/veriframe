;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.verdict
  "Constrained answer-set parsing for sub-LLM judgements.

  `audit` and `review` ask a model a yes-or-no question and the harness acts on
  the answer, so how that answer is read is a correctness question, not a
  formatting one.

  dirge's parser read the first non-empty line and substring-matched, which
  meant `COMPLETE` matched inside `INCOMPLETE` and `MET` inside `UNMET`. On a
  27-row corpus of real judge phrasings it got 11 wrong, and every one failed
  the same direction: reporting work complete that the judge had called
  incomplete. Three rules come out of that.

  No answer may be a substring of another, checked at construction rather than
  hoped for. `PASS` and `FAIL` rather than `MET` and `UNMET`.

  Matching is word-bounded and line-anchored, and a `VERDICT:` marker anywhere
  in the text wins over a bare token, because a judge that follows the format
  is more trustworthy than one that happened to use the word.

  Ambiguity is not resolved, it is reported. Two different answers in one
  response means the judge did not answer, and guessing which it meant is how
  the substring bug did its damage. `:unparseable` and `:ambiguous` both fail
  closed: the gate stays shut.

  A response may also declare gaps (`GAP: <text>` lines, or `GAPS: none` for
  an explicit none) and patchable defects (`MINOR: <text> FIX: <text>` lines,
  where a MINOR line without a FIX reads as a blocking GAP). A verdict that
  contradicts its own declarations is recorded in `:disagreement`, never
  repaired: UCLA's FirstProof scorer_v4 converged on the same fail-closed
  enum and added these checks, with the verdict winning over the declarations
  in every inconsistency direction."
  (:require [clojure.string :as str]))

(defn- validate-answer-set!
  "No token may contain another token from a different answer. This is the
  check that would have caught COMPLETE inside INCOMPLETE before it shipped."
  [answer-set]
  (let [pairs (for [[a1 ts1] answer-set, t1 ts1
                    [a2 ts2] answer-set, t2 ts2
                    :when (and (not= a1 a2) (str/includes? (str/lower-case t1)
                                                           (str/lower-case t2)))]
                [a1 t1 a2 t2])]
    (when (seq pairs)
      (throw (ex-info (str "Answer tokens overlap, which is the substring bug: "
                           (pr-str (first pairs)))
                      {:overlaps pairs}))))
  answer-set)

(def pass-fail
  "The one answer set the harness uses. Deliberately not yes/no: a judge
  writing prose says \"no\" constantly in passing."
  (validate-answer-set! {:pass #{"PASS"} :fail #{"FAIL"}}))

(defn strip-reasoning
  "Drop a <think>…</think> block, and an unclosed <think> to the end.

  Used for two different jobs, which is why it is public. Matching a verdict
  needs it because a judge that thinks out loud restates the wrong answer while
  reasoning toward the right one. Quoting a judge back to a branch needs it for
  a different reason: the branch's next turn is a fresh model call over a
  history that would otherwise contain reviewer-voice reasoning, and the model
  imitates it — it answers the NEXT turn as a reviewer, in prose, with no tool
  call at all. Observed as nine wasted turns in a twenty-turn run."
  [text]
  (-> (or text "")
      (str/replace #"(?s)<think>.*?</think>" "")
      (str/replace #"(?s)<think>.*\z" "")
      str/trim))

(defn- token-re [token]
  (re-pattern (str "(?i)(?<![A-Za-z])" (java.util.regex.Pattern/quote token) "(?![A-Za-z])")))

(defn- marker-hits
  "Answers appearing as `VERDICT: TOKEN`, in order."
  [text answer-set]
  (for [[_ tok] (re-seq #"(?i)\bVERDICT\s*:\s*([A-Za-z]+)" text)
        [answer tokens] answer-set
        :when (some #(.equalsIgnoreCase ^String % tok) tokens)]
    answer))

(defn- bare-hits
  "Answers appearing as a whole word on a line of their own, in order. A token
  buried in prose does not count — a judge writing \"this would fail if\" is
  not returning FAIL."
  [text answer-set]
  (for [line (str/split-lines text)
        :let [t (str/trim line)]
        [answer tokens] answer-set
        :when (some #(re-matches (token-re %) t) tokens)]
    answer))

(defn- gap-lines
  "The text after `GAP:` or `GAPS:` on each gap line, in order. A `MINOR:`
  line without a `FIX:` segment is a gap too — the claim that a defect is
  patchable is substantiated only by the patch itself. Line-anchored and
  case-insensitive, like a bare verdict token: prose that mentions a gap is
  not a declaration."
  [text]
  (keep (fn [line]
          (let [m (re-matches #"(?i)(GAPS?|MINOR)\s*:\s*(.+)" (str/trim line))]
            (when m
              (let [prefix (str/lower-case (second m))
                    body (nth m 2)]
                (when-not (and (= "minor" prefix)
                               (re-matches #"(?i).+?\s*FIX\s*:.+" body))
                  (str/trim body))))))
        (str/split-lines text)))

(defn- minor-lines
  "The patchable defects a judge declared, in order: `MINOR: <description>`
  lines that also carry a `FIX: <fix>` segment.

  Patch-existence is the severity criterion because severity claims are not
  artifacts a harness can check — a judge calling a defect \"minor\" asserts
  nothing verifiable — while a stated fix is an artifact the next turn can act
  on. This is UCLA's FirstProof scorer_v4 critical rule made mechanical: if
  you can supply the missing argument the defect is MINOR even while it is
  still absent, and it is MAJOR only when you cannot. A MINOR line without a
  FIX segment is read as a blocking gap (see gap-lines); the claim of
  patchability is substantiated only by the patch itself. Line-anchored and
  case-insensitive, like a bare verdict token: prose that mentions a minor is
  not a declaration."
  [text]
  (keep (fn [line]
          (when-let [m (re-matches #"(?i)MINOR\s*:\s*(.+)" (str/trim line))]
            (when-let [f (re-matches #"(?i)(.+?)\s*FIX\s*:\s*(.+)" (second m))]
              {:description (str/trim (second f))
               :patch (str/trim (nth f 2))})))
        (str/split-lines text)))

(defn- gap-summary
  "What the judge declared about gaps: {:gaps [...] :declared? bool}.

  A line reading `GAP: <text>` or `GAPS: <text>` declares a gap; the text
  after the colon is it. A line whose text is exactly `none` declares that
  there are no gaps — `GAPS: none` is the judge engaging with the question,
  which is different from never mentioning gaps at all."
  [text]
  (let [contents (gap-lines text)
        none? (some #(= "none" (str/lower-case %)) contents)]
    {:gaps (vec (remove #(= "none" (str/lower-case %)) contents))
     :declared? (boolean (or none? (seq contents)))}))

(defn- verdict-disagreement
  "The verdict-vs-declarations contradiction, if any.

  All three directions come from UCLA's FirstProof scorer_v4, which converged
  on the same fail-closed enum this parser uses and added the declaration
  lines on top. The verdict wins in every case — the harness acts on it, and
  this only records that the judge contradicted itself so the run is
  measurable. PASS beside listed gaps, FAIL beside `GAPS: none`, and FAIL
  beside nothing but patchable MINOR lines (every defect the judge found was
  fixable, yet it refused anyway) each get a direction."
  [verdict gaps declared? minors]
  (cond
    (and (= :pass verdict) (seq gaps)) :pass-with-gaps
    (and (= :fail verdict) (empty? gaps) (seq minors)) :fail-with-only-patchables
    (and (= :fail verdict) declared? (empty? gaps)) :fail-without-gaps
    :else nil))

(defn parse
  "Read a verdict out of `text`.

  Returns {:verdict :pass|:fail|:ambiguous|:unparseable, :via, :reason,
  :drafts n, :gaps-declared bool, and — when the judge engaged with gaps —
  :gaps [...], plus :minors [...] when it carried fixes for patchable
  defects, and :disagreement when the verdict contradicts its own
  declarations}. Anything other than :pass or :fail means the gate does not
  open.

  Two things happen before matching, and both were forced by a live run in
  which every single review came back :ambiguous.

  The reasoning stream is dropped. A judge that thinks out loud restates the
  question — including the instruction's own `VERDICT: PASS or VERDICT: FAIL`
  — inside <think>, so scanning the whole response finds both answers every
  time. The reasoning is where a model considers answers; it is not where it
  gives one.

  If markers still compete after that, the LAST wins rather than the response
  being rejected. This is the same rule the fence parser earned against a
  measured 20.5% of responses: models draft, then commit, and the commitment is
  last. It is not the substring guess dirge PR 739 found — competing answers
  can no longer be substrings of each other, that is checked at construction —
  it is a tie-break between two well-formed answers, and `:drafts` records that
  it happened.

  Gap and MINOR lines are collected after the reasoning is dropped, so a
  declaration inside <think> is the judge weighing a worry, not making one. A
  verdict that contradicts its own declarations — PASS beside listed gaps,
  FAIL beside `GAPS: none`, FAIL beside nothing but patchable MINOR lines —
  is recorded in :disagreement rather than flipped. The verdict wins, and the
  contradiction is the judge's, not the parser's."
  ([text] (parse text pass-fail))
  ([text answer-set]
   (let [raw (or text "")
         text (strip-reasoning raw)
         markers (marker-hits text answer-set)
         bares (bare-hits text answer-set)
         distinct-markers (distinct markers)
         distinct-bares (distinct bares)
         {:keys [gaps declared?]} (gap-summary text)
         minors (minor-lines text)
         result (cond
                  (seq markers)
                  (cond-> {:verdict (last markers) :via :marker}
                    (> (count distinct-markers) 1)
                    (assoc :drafts (dec (count markers))
                           :reason (str "The response carried " (count markers)
                                        " VERDICT lines; taking the last.")))

                  (seq bares)
                  (cond-> {:verdict (last bares) :via :bare}
                    (> (count distinct-bares) 1)
                    (assoc :drafts (dec (count bares))
                           :reason (str "The response carried " (count bares)
                                        " bare verdict tokens and no VERDICT line;"
                                        " taking the last.")))

                  (str/blank? text)
                  {:verdict :unparseable
                   :reason (str "The judge produced only reasoning and no answer, which"
                                " usually means it hit the token cap while thinking.")}

                  :else
                  {:verdict :unparseable
                   :reason (str "No verdict found. End the response with a line reading"
                                " exactly `VERDICT: PASS` or `VERDICT: FAIL`.")})
         disagreement (when (or (= :pass (:verdict result))
                                (= :fail (:verdict result)))
                        (verdict-disagreement (:verdict result) gaps
                                              declared? minors))]
     (cond-> (assoc result :gaps-declared declared?)
       declared? (assoc :gaps gaps)
       (seq minors) (assoc :minors minors)
       disagreement (assoc :disagreement disagreement)))))

(defn passed?
  "True only for an unambiguous pass. Everything else fails closed."
  [parsed]
  (= :pass (:verdict parsed)))

(def instruction
  "What to append to any prompt whose answer this parser reads.

  The GAP and MINOR sentences are the declaration contract from UCLA's
  FirstProof scorer_v4, which converged on the same fail-closed enum and made
  severity part of the answer: a defect is MINOR only when its concrete fix
  can be stated, and a judge that lists a blocking gap and still answers PASS
  is contradicting itself. The instruction tells it to pick FAIL; the parser
  only records the contradiction in :disagreement and never flips the
  verdict."
  (str "End your response with a line reading exactly `VERDICT: PASS` or"
       " `VERDICT: FAIL`, and nothing else on that line. Emit exactly one such"
       " line. If you are unsure, answer FAIL."
       " Optionally, list the claim's gaps before the verdict line, one per"
       " line, each reading `GAP: <text>`, or `GAPS: none` if you checked and"
       " found none."
       " For each defect you find, decide whether you can supply the concrete"
       " fix. If you can, list it as `MINOR: <defect> FIX: <fix>` — the FIX"
       " part is mandatory, and a MINOR line without FIX is read as blocking."
       " If you cannot state the fix, list `GAP: <defect>` instead, and your"
       " verdict must be `VERDICT: FAIL` — a defect is blocking only when its"
       " fix cannot be stated."
       " If any GAP line blocks the claim, your verdict must be"
       " `VERDICT: FAIL` — writing PASS alongside a blocking GAP is"
       " self-contradictory; pick FAIL."))
