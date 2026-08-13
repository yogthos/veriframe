;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.llm.message
  "Message shaping shared by every adapter.

  Two jobs, both of which have to happen the same way for every provider or
  the harness's context management differs by which model it is talking to."
  (:require [clojure.string :as str]))

(defn strip-think-blocks
  "Remove <think>…</think> from content.

  Applied to PRIOR assistant turns before they go back over the wire. Both
  DeepSeek and Zhipu document that `reasoning_content` must not be fed back on
  later turns — the model regenerates its thinking each turn — and
  accumulating it burns context for nothing. Tolerant of nesting and of an
  unmatched open tag."
  [content]
  (-> (or content "")
      (str/replace #"(?s)<think>.*?</think>" "")
      str/trim))

(defn merge-reasoning
  "Fold a provider's separate reasoning stream into the visible content with
  <think> framing.

  This is what lets one fence parser work across providers: a model that emits
  its tool call inside its reasoning is handled identically to one that emits
  it in the content, because after this they are the same string."
  [content reasoning]
  (let [c (or content "") r (or reasoning "")]
    (cond
      (and (seq r) (seq c)) (str "<think>" r "</think>\n" c)
      (seq r) (str "<think>" r "</think>")
      :else c)))

(def default-keep-pairs
  "How many recent turns stay verbatim when a branch's history is compacted.

  This is the branch's working memory: what it just tried, what the engine
  said, and the goal state it is mid-way through. Ten is enough to hold a
  proof attempt and the two or three failures that shaped it."
  10)

(def default-compaction-threshold
  "Total content characters before compaction engages.

  Deliberately high. A 19-turn branch carries about 26,000 characters and does
  not need this; the longest branch on record is 86 turns and about 211,000.
  Compaction is for the tail, and a branch that never reaches it is sent
  byte-identical messages to what it would have been sent before."
  50000)

(defn- digest-line
  "One line for a turn that has been unloaded: what was tried, how it came out.

  The minimum a branch needs about its own distant past. Anything more —  the
  encoding, the engine's full output, the reasoning — is in the journal, and
  confirmed results are in the settled-state block with ids to fetch."
  [{:keys [turn tool category error]}]
  (str "  t" turn " " (or tool "?") " → " (name (or category :neutral))
       (when (seq error)
         (let [e (first (str/split-lines (str error)))]
           (str ": " (if (> (count e) 90) (str (subs e 0 90) "…") e))))))

(defn compact
  "Replace a branch's older turns with a digest of what they tried.

  A branch's context is its own narrative, and past a certain length most of
  it is prose it will never consult again — while the part it genuinely needs,
  which approaches are already spent, is buried in that prose. This inverts
  that: recent turns stay whole, older ones become one line each.

  The frame is preserved exactly. The system prompt survives, the problem
  survives, and the digest is appended to the PROBLEM message rather than
  inserted as its own turn, so the conversation stays strictly alternating
  after it — a run of two user messages is tolerated by some providers and
  rejected by others, and this needs no provider to be forgiving.

  Nothing is lost, only unloaded: every turn is in the journal, every
  confirmed and refuted artifact is in the settled-state block, and the
  encodings are one `fetch_artifact` away. Applied on the way to the wire, so
  the branch's own history is untouched and a resume replays what was really
  sent at the time."
  ([messages turns] (compact messages turns nil))
  ([messages turns {:keys [keep-pairs threshold-chars]}]
   (let [keep-pairs (or keep-pairs default-keep-pairs)
         threshold (or threshold-chars default-compaction-threshold)
         total (reduce + 0 (map (comp count str :content) messages))
         [frame body] (split-at 2 (vec messages))
         pairs (partition-all 2 body)
         drop-n (- (count pairs) keep-pairs)]
     (if (or (< total threshold) (<= drop-n 0) (< (count frame) 2))
       (vec messages)
       (let [kept (apply concat (drop drop-n pairs))
             ;; The digest covers exactly the turns being unloaded, so a turn
             ;; kept verbatim is never also summarised.
             lines (keep digest-line (take drop-n turns))]
         (vec (concat [(first frame)
                       (update (second frame) :content
                               #(str % "\n\n## Earlier turns on this branch"
                                     " (unloaded — full detail is in the run journal)\n\n"
                                     (str/join "\n" lines)
                                     "\n\nReopen any of these in full with"
                                     " `fetch_turn` and its number. What they"
                                     " established or ruled out is in the"
                                     " settled-state block, and any encoding is"
                                     " one `fetch_artifact` away."))]
                      kept)))))))

(def ledger-open "<!--settled-state-->")
(def ledger-close "<!--/settled-state-->")

(def ^:private ledger-re
  (re-pattern (str "(?s)" (java.util.regex.Pattern/quote ledger-open)
                   ".*?" (java.util.regex.Pattern/quote ledger-close))))

(defn strip-stale-ledgers
  "Drop every settled-state block except the most recent.

  The ledger is regenerated from the artifacts table each turn and appended to
  that turn's result, so without this a branch accumulates one copy per turn.
  gen-18's ledger is roughly 6,800 tokens; eighty turns of it would dwarf the
  transcript it exists to summarise.

  A ledger is STATE. Only the newest is true, and an older copy is a strictly
  worse version of it — the same argument strip-think-blocks makes about
  reasoning, and the reason both live here rather than at the call site.

  Applied on the way to the wire only. The branch keeps every copy in its own
  message list, so the journal and a resume see exactly what was sent at the
  time."
  [messages]
  (let [last-idx (last (keep-indexed (fn [i m]
                                       (when (re-find ledger-re (str (:content m))) i))
                                     messages))]
    (if (nil? last-idx)
      messages
      (map-indexed (fn [i m]
                     (if (= i last-idx)
                       m
                       (update m :content #(str/replace (str %) ledger-re ""))))
                   messages))))

(defn prepare
  "Normalize a conversation for the wire: keyword roles become strings, prior
  assistant turns lose their think blocks, and every settled-state block but
  the newest is dropped. System and user messages are otherwise left alone."
  [messages]
  (mapv (fn [{:keys [role content] :as m}]
          (let [role (if (keyword? role) (clojure.core/name role) (str role))]
            (assoc (select-keys m [])
                   :role role
                   :content (-> (if (and (= "assistant" role) content)
                                  (strip-think-blocks content)
                                  (or content ""))
                                ;; The markers are harness framing and must not
                                ;; reach the model, which would otherwise learn
                                ;; to emit them.
                                (str/replace ledger-open "")
                                (str/replace ledger-close "")
                                str/trim))))
        (strip-stale-ledgers messages)))
