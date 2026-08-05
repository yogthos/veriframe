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
  closed: the gate stays shut."
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

(defn parse
  "Read a verdict out of `text`.

  Returns {:verdict :pass|:fail|:ambiguous|:unparseable, :via, :reason,
  :drafts n}. Anything other than :pass or :fail means the gate does not open.

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
  it happened."
  ([text] (parse text pass-fail))
  ([text answer-set]
   (let [raw (or text "")
         ;; Drop <think>…</think>, and an unclosed <think> to the end, since a
         ;; truncated reasoning stream has no answer in it at all.
         text (-> raw
                  (str/replace #"(?s)<think>.*?</think>" "")
                  (str/replace #"(?s)<think>.*\z" "")
                  str/trim)
         markers (marker-hits text answer-set)
         bares (bare-hits text answer-set)
         distinct-markers (distinct markers)
         distinct-bares (distinct bares)]
     (cond
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
                     " exactly `VERDICT: PASS` or `VERDICT: FAIL`.")}))))

(defn passed?
  "True only for an unambiguous pass. Everything else fails closed."
  [parsed]
  (= :pass (:verdict parsed)))

(def instruction
  "What to append to any prompt whose answer this parser reads."
  (str "End your response with a line reading exactly `VERDICT: PASS` or"
       " `VERDICT: FAIL`, and nothing else on that line. Emit exactly one such"
       " line. If you are unsure, answer FAIL."))
