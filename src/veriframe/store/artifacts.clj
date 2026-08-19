;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.store.artifacts
  "The run-scoped shared confirmed-artifact log, the twin of the failure log.

  Branches already share what was DISPROVEN; this shares what an engine
  CONFIRMED, with provenance inline — which branch proved it, with which
  engine, at which tier — so a consumer can see where a lemma came from.
  The difference from UCLA's harness is the entry condition: there, proven
  results were self-reported and trust had to be taken on faith. Here only
  engine-confirmed claim-statuses ever enter, so a branch cannot talk its own
  results into the pool. The cost is beam diversity: branches may converge on
  shared lemmas instead of exploring. That is why the flag defaults to off and
  why sweep-widths runs the beam both ways.

  Like the failure log this is FTS5-backed, a query for the lemmas most like
  what a branch is about to try rather than a vector re-rendered whole into
  every context. The FTS table is standalone, and sync is app-managed here."
  (:require [clojure.set]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [veriframe.llm.message :as message]
            [veriframe.store.db :as db]
            [veriframe.store.journal :as journal]))

(defn record!
  "A confirmed artifact into the shared log. Callers gate on claim-status and
  the config flag; this function only writes."
  [conn run-id {:keys [branch-id turn kind tier claim code]}]
  (db/with-writer
    (db/execute! conn
                   ["INSERT INTO shared_artifacts (run_id, branch_id, turn, kind, tier,
                                                   claim, code, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    run-id branch-id turn (name kind) (name (or tier :fast))
                    (str claim) (str code) (db/now)])
    (let [id (db/last-insert-id conn)]
      (db/execute! conn
                     ["INSERT INTO shared_artifacts_fts (rowid, claim) VALUES (?, ?)"
                      id (str claim)]))))

(defn retract!
  "Undo a confirmation that turned out not to be one. Returns true if a row
  changed, false if there was nothing there.

  The campaign has banked worthless artifacts as CONFIRMED four separate ways:
  three whose entire proof body was `classical`, two vacuous theorems, and
  gen-30 a#829, closed with `sorry`. Each hole was fixed where it was found,
  but a row already written stayed written, and a live run rereads the ledger
  every turn — so a run could spend hours building on a lemma the harness had
  already learned was void.

  seed-from-run!'s quarantine argues why prose cannot do this job: the ledger
  is generated from the table every turn and says CONFIRMED, so a paragraph
  saying otherwise is a contradiction the branch has to adjudicate. That is
  just as true inside a run as across the seeding boundary.

  Both tables, because they feed different readers: `artifacts` is the run's
  own ledger and what a later generation inherits, `shared_artifacts` is the
  block siblings read. The row is marked rather than deleted — a retracted
  result is a thing that happened, and the reason belongs in the record."
  [conn run-id artifact-id reason]
  (db/with-writer
    (let [n (db/execute! conn
                         ["UPDATE artifacts SET claim_status = 'retracted'
                           WHERE run_id = ? AND id = ? AND claim_status = 'confirmed'"
                          run-id artifact-id])
          claim (:claim (first (db/fetch conn ["SELECT claim FROM artifacts
                                                WHERE run_id = ? AND id = ?"
                                               run-id artifact-id])))]
      (if (and claim (pos? (if (number? n) n 0)))
        (do
          ;; Matched on claim text: the shared row is a copy with its own id,
          ;; and the boundary where ids stop lining up is exactly this one.
          (doseq [r (db/fetch conn ["SELECT id FROM shared_artifacts
                                     WHERE run_id = ? AND claim = ?" run-id claim])]
            (db/execute! conn ["DELETE FROM shared_artifacts_fts WHERE rowid = ?" (:id r)])
            (db/execute! conn ["DELETE FROM shared_artifacts WHERE id = ?" (:id r)]))
          (journal/note! conn run-id :artifact-retracted
                         {:data {:artifact-id artifact-id :claim claim :reason reason}})
          true)
        false))))

(defn mark-rotten!
  "Record that a cited artifact no longer elaborates (vf-ppt).

  verify_lean detects this exactly and used to discard it: the cited block is
  prepended, so its line range is known, and every error inside it means the
  branch's own snippet was never reached. That was worth one message to one
  branch. gen-33 rediscovered the same rotten artifacts 26 times across all
  six branches — B1 nine times, B2 seven — and the run's 20 journalled event
  kinds included none for it. What stopped it was a human auditing all 83
  citable artifacts by hand and delivering the result as a note.

  FIRST detection wins, and returns true; a later one is a no-op returning
  false. The reason is the error text the branch actually saw, which is more
  useful than a later branch's differently-worded encounter, and the branch
  credited stays the one that paid for it.

  Keyed on the handle: `a#12` and `s#7` are different tables, and this has to
  cover both."
  [conn run-id handle {:keys [claim reason branch-id turn]}]
  (db/with-writer
    (let [n (db/execute! conn
                         ["INSERT OR IGNORE INTO artifact_rot
                           (run_id, handle, claim, reason, branch_id, turn, created_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?)"
                          run-id (str handle) (str claim) (str reason)
                          (str branch-id) (or turn 0) (db/now)])]
      (if (pos? (if (number? n) n 0))
        (do (journal/note! conn run-id :artifact-rot
                           {:branch-id branch-id :turn turn
                            :data {:handle handle :claim claim :reason reason}})
            true)
        false))))

(defn rotten
  "Everything this run has learned does not compile, handle -> row."
  [conn run-id]
  (into {} (map (juxt :handle identity))
        (db/fetch conn ["SELECT handle, claim, reason, branch_id, turn
                         FROM artifact_rot WHERE run_id = ?" run-id])))

(defn- normalize-claim
  "Spelling-level normalisation only — case and punctuation — matching
  claims/normalize and consensus/normalize-claim. Two phrasings that group
  together there must group together here."
  [claim]
  (-> (str/lower-case (or claim ""))
      (str/replace #"[^a-z0-9]+" " ")
      str/trim))

(defn seed-from-run!
  "Copy `source-run-id`'s engine-confirmed artifacts into `run-id`'s shared
  log, for cross-run campaigns: a new run continues from a prior one's
  verified results without inheriting its dead ends.

  Claim AND code cross over — the code is what lets a branch re-confirm an
  inherited lemma in one cheap turn instead of reconstructing the encoding.
  The branch id is prefixed `seed:` so provenance is visible in the context
  block and no live branch's own-branch exclusion hides a seed. Only
  confirmed claim-statuses cross; refuted and existential stay behind. The
  done gate still requires in-run re-verification, so nothing inherited can
  ship on faith. Returns the number of artifacts seeded.

  `:quarantine` is a list of claim texts that must NOT cross, for a row marked
  confirmed that the harness has since learned was not. vf-4tw confirmed three
  artifacts on a Lean reply carrying no goal list, and those rows are still
  marked confirmed; seeding would carry them forward as inherited CONFIRMED
  lemmas. Prose in a problem statement cannot undo that — the ledger is
  generated from this table every turn and says CONFIRMED, so a paragraph
  saying otherwise is a contradiction the branch has to adjudicate, and the
  harness should not be handing out contradictions.

  Matched on normalised text rather than id, because ids are per-run and the
  boundary where they change is exactly the boundary this has to hold at."
  ([conn run-id source-run-id] (seed-from-run! conn run-id source-run-id nil))
  ([conn run-id source-run-id {:keys [quarantine]}]
  (let [;; What the source run LEARNED was void, on top of what a human
        ;; named. A row it proved does not elaborate must not arrive in the
        ;; next generation marked confirmed — that is how an 83-row corpus
        ;; came to hold 32 artifacts that no longer compile (vf-ppt, vf-6v7).
        blocked (into (into #{} (map normalize-claim) quarantine)
                      (comp (map :claim) (remove str/blank?) (map normalize-claim))
                      (vals (rotten conn source-run-id)))
        own (db/fetch conn
                      ["SELECT branch_id, kind, tier, claim, code FROM artifacts
                        WHERE run_id = ? AND claim_status = 'confirmed' ORDER BY id"
                       source-run-id])
        ;; TRANSITIVE. Reading only the source's own artifacts made each
        ;; generation pass on what it proved and drop everything it had
        ;; inherited: the phase-unwrapping campaign lost gen-19's 16 lemmas at
        ;; the gen-20 boundary and would have lost gen-20's 11 at the next —
        ;; a chain that forgets faster than it learns.
        inherited (db/fetch conn
                            ["SELECT branch_id, kind, tier, claim, code
                              FROM shared_artifacts
                              WHERE run_id = ? AND branch_id LIKE 'seed:%'
                              ORDER BY id" source-run-id])
        ;; Own first, so where a run re-proved something it inherited, its own
        ;; provenance is the one that crosses.
        rows (->> (concat own inherited)
                  (remove #(contains? blocked (normalize-claim (:claim %))))
                  (reduce (fn [acc r]
                            (if (contains? (:seen acc) (:claim r))
                              acc
                              (-> acc (update :seen conj (:claim r))
                                  (update :rows conj r))))
                          {:seen #{} :rows []})
                  :rows)]
    (doseq [r rows]
      (record! conn run-id {:branch-id (let [b (str (:branch_id r))]
                                         ;; Already-seeded rows keep their
                                         ;; original provenance rather than
                                         ;; accumulating seed:seed:seed:.
                                         (if (str/starts-with? b "seed:")
                                           b
                                           (str "seed:" b)))
                            :turn 0
                            :kind (keyword (:kind r))
                            :tier (keyword (:tier r))
                            :claim (:claim r)
                            :code (:code r)}))
    (journal/note! conn run-id :run-seeded
                   {:data {:source source-run-id :artifacts (count rows)
                           :quarantined (count blocked)}})
    (count rows))))

(defn- fts-query
  "Turn free text into an FTS5 OR query.

  FTS5's query language treats several characters as operators, so raw model
  prose is not a safe query string. Words are extracted and quoted; anything
  shorter than three characters is dropped as noise."
  [text]
  (->> (str/split (str/lower-case (or text "")) #"[^a-z0-9]+")
       (filter #(>= (count %) 3))
       distinct
       (take 12)
       (map #(str "\"" % "\""))
       (str/join " OR ")))

(defn similar
  "The shared artifacts most like `text`, best match first.

  Returns [] rather than throwing when the query has no usable terms, because
  an empty shared log is a normal state and a branch should not lose its turn
  to a search that found nothing."
  ([conn run-id text] (similar conn run-id text 5))
  ([conn run-id text limit]
   (let [q (fts-query text)]
     (if (str/blank? q)
       []
       (try
         (db/fetch conn
                     ["SELECT sa.id, sa.branch_id, sa.turn, sa.kind, sa.tier, sa.claim, sa.code
                       FROM shared_artifacts_fts fts
                       JOIN shared_artifacts sa ON sa.id = fts.rowid
                       WHERE shared_artifacts_fts MATCH ? AND sa.run_id = ?
                       ORDER BY bm25(shared_artifacts_fts) LIMIT ?"
                      q run-id limit])
         (catch Throwable _ []))))))

(defn recent
  ([conn run-id] (recent conn run-id 10))
  ([conn run-id limit]
   (db/fetch conn ["SELECT id, branch_id, turn, kind, tier, claim, code FROM shared_artifacts
                      WHERE run_id = ? ORDER BY id DESC LIMIT ?" run-id limit])))

(def ^:private max-shared-code-chars
  "How much of a shared artifact's code the block carries.

  A theorem STATEMENT is what a sibling needs in order to build on a lemma;
  the proof body is not. Generous enough for a Lean signature with a dozen
  hypotheses, small enough that five entries cannot crowd out the branch's own
  context — the block re-renders every turn, so its size is a per-turn cost."
  700)

(def ^:private max-ledger-claim-chars
  "How much of a claim the ledger shows.

  A claim is often a full mathematical statement — gen-18's average is around
  340 characters, and its 79 confirmed artifacts render to ~6,800 tokens
  unabridged. The ledger's job is to let a branch see WHAT is settled and
  decide what to try; the exact statement is one `fetch_artifact` away, so the
  headline is what belongs here."
  180)

(defn- shingles [s]
  (let [toks (re-seq #"[a-z0-9]+" (str/lower-case (str s)))]
    (if (< (count toks) 4)
      (set toks)
      (set (map #(str/join " " %) (partition 4 1 toks))))))

(defn near-duplicate?
  "Whether two claims say the same thing — 4-word shingle Jaccard at or
  above `threshold`, defaulting to the 0.6 `dedupe-claims` dedups at.

  Gates two things now. The ledger's dedupe collapses claims that are the
  same theorem differently worded (see dedupe-claims), and — since vf-eaw —
  the sketch tool's diversity gate refuses a sketch too close to a live
  sibling's. The sketch cutoff rides in gates.edn
  (:sketch-duplicate-threshold) so a run can record it; 0.6 was tuned on
  ledger CLAIMS, not on plans, so the sketch use is deliberately the same
  number until a generation of sketches exists to tune against."
  ([a b] (near-duplicate? a b 0.6))
  ([a b threshold]
   (let [x (shingles a) y (shingles b)]
     (and (seq x) (seq y)
          (let [i (count (clojure.set/intersection x y))]
            (>= (/ (double i) (count (clojure.set/union x y))) threshold))))))

(defn sibling-sketches
  "The plans LIVE siblings in the same run have already banked, newest last.

  vf-eaw's diversity gate reads this to refuse a sketch too close to one a
  sibling already made. Live means the sibling's branches row still says
  active — a culled or shipped branch is not competing for the line of
  attack. The branch's own sketches are excluded; a branch may refine its own
  plan freely.

  Returns [] rather than throwing when there are none, for the same reason
  `similar` does: an empty set is a normal state, and a branch should not
  lose its turn to a query that found nothing."
  [conn run-id branch-id]
  (db/fetch conn
            ["SELECT a.branch_id, a.turn, a.kind, a.claim, a.code
                FROM artifacts a
                JOIN branches b ON b.id = a.branch_id AND b.run_id = a.run_id
               WHERE a.run_id = ? AND a.claim_status = 'sketch'
                 AND a.branch_id != ? AND b.status = 'active'
               ORDER BY a.id DESC"
             run-id branch-id]))

(defn dedupe-claims
  "Collapse entries whose claims say the same thing, keeping the fullest.

  Read live off gen-22: three branches proved one scalarization inequality and
  the ledger listed it three times. A ledger is for seeing the state of a run
  at a glance, and three spellings of one lemma defeats that — while hiding
  the signal that actually helps, which is that the line is well covered and
  does not want a fourth attempt.

  The survivor carries `:also-proved-by`, so the collapse is stated rather
  than silent. Fuzzy rather than exact: the live duplicates differed only in
  variable names and an `i.e.` for a `so`."
  [rows]
  (reduce (fn [acc r]
            (if-let [i (first (keep-indexed
                               (fn [i k] (when (near-duplicate? (:claim k) (:claim r)) i))
                               acc))]
              (let [k (nth acc i)
                    ;; The longer statement is the more useful one to show.
                    winner (if (> (count (str (:claim r))) (count (str (:claim k)))) r k)]
                (assoc acc i (assoc winner :also-proved-by
                                    (conj (vec (:also-proved-by k)) (:branch_id r)))))
              (conj acc r)))
          []
          rows))

(defn- elide
  "Shorten a long claim while keeping BOTH ends.

  Cutting only the head threw away the conclusion: one live entry read
  \"…the flow on any edge leaving R is at …\", losing the bound, which is the
  only part a branch needs. The hypotheses say when a lemma applies and the
  tail says what it gives you; a middle is the safe thing to drop."
  [s]
  (let [c (str/trim (str s))]
    (if (<= (count c) max-ledger-claim-chars)
      c
      (let [head (quot (* 2 max-ledger-claim-chars) 3)
            tail (- max-ledger-claim-chars head)]
        (str (subs c 0 head) " … " (subs c (- (count c) tail)))))))

(defn- ledger-line
  "One entry. `prefix` selects the id space: `a#` indexes this run's own
  artifacts, `s#` the shared pool a seed was copied into. Two tables, one
  fetch tool, so the handles must not be confusable."
  [prefix {:keys [id branch_id kind tier claim also-proved-by]}]
  (str "- [" prefix id " " branch_id " " (name (or kind "?")) "/"
       (name (or tier "?")) "] " (elide claim)
       (when (seq also-proved-by)
         (str " (also proved by " (str/join ", " (distinct also-proved-by))
              " — " (inc (count (distinct also-proved-by)))
              " branches have this; it does not need another)"))))

(defn render-ledger
  "The run's settled state, as a block for a branch's next-turn context.

  Two sections, never one list with a status column: a refutation formatted
  like a confirmation is worse than not sharing it at all, and a model
  skimming a single list will merge them. Established first, because it is
  what a branch builds on; ruled out second, because it is what stops a branch
  repeating a closed line.

  Ids are handles, not decoration — `a#12` is what `fetch_artifact` takes, so
  the encodings stay out of the block and cost a turn only when wanted."
  [{:keys [established ruled-out sketches inherited rotten]}]
  ;; Anything the run has learned does not compile is pulled OUT of the
  ;; sections that mean verified and shown once, under its own heading, with
  ;; the error (vf-ppt). Annotating it in place would leave the branch a
  ;; contradiction to adjudicate — the same argument seed-from-run! makes
  ;; about prose that disagrees with a table saying CONFIRMED. A branch that
  ;; can see the line is rotten never spends the turn finding out.
  (let [rot? (fn [prefix e] (get rotten (str prefix (:id e))))
        rot-entries (concat (map #(assoc % ::prefix "a#") (filter #(rot? "a#" %) established))
                            (map #(assoc % ::prefix "s#") (filter #(rot? "s#" %) inherited)))
        established (remove #(rot? "a#" %) established)
        inherited (remove #(rot? "s#" %) inherited)]
  (when (or (seq established) (seq ruled-out) (seq sketches) (seq inherited)
            (seq rot-entries))
    (str message/ledger-open "\n"
         ;; NOT "what this run has settled": a sketch is precisely what it has
         ;; not settled, and a heading is what a model skimming reads. The
         ;; five worthless-but-confirmed artifacts this campaign has banked
         ;; were each a case of unverified content being taken for verified,
         ;; so the top line has to cover plans without endorsing them.
         "## What this run has settled, and what it has only planned\n\n"
         (when (seq established)
           (str "### Established — engine-verified in this run\n"
                (str/join "\n" (map (partial ledger-line "a#") (dedupe-claims established))) "\n\n"))
         (when (seq ruled-out)
           (str "### Ruled out — engine-REFUTED, do not re-attempt these\n"
                (str/join "\n" (map (partial ledger-line "a#") (dedupe-claims ruled-out))) "\n\n"))
         ;; Plans, kept out of both settled halves and under their own `p#`
         ;; prefix: a sketch elaborates and its citations exist, but every
         ;; `sorry` in it is a step still open, and a model skimming this
         ;; block must not mistake a plan for something an engine checked.
         ;; The same id space as `a#` (the artifacts table) — the prefix
         ;; exists so the STATUS is on the handle.
         (when (seq sketches)
           (str "### Sketches — UNVERIFIED PLANS, not results; every step is still open\n"
                (str/join "\n" (map (partial ledger-line "p#") (dedupe-claims sketches))) "\n\n"))
         ;; Last: inherited results are true but were established elsewhere,
         ;; and the done gate still requires in-run verification, so they are
         ;; a starting point rather than something to ship on.
         (when (seq inherited)
           (str "### Inherited — confirmed by the run this one was seeded from\n"
                (str/join "\n" (map (partial ledger-line "s#") (dedupe-claims inherited))) "\n\n"))
         (when (seq rot-entries)
           (str "### No longer compiles — DO NOT CITE; the harness has checked\n"
                (str/join "\n"
                          (for [e rot-entries]
                            (str (ledger-line (::prefix e) e) "\n      ↳ "
                                 (str/replace
                                  (str (:reason (get rotten (str (::prefix e) (:id e)))))
                                  #"\s*\n\s*" " / "))))
                "\n\nEach was cited and Lean rejected it before the citing branch's own"
                " snippet was reached. The statements may well be true; only the proofs"
                " have decayed. Citing one is refused, so prove what you need yourself.\n\n"))
         "Fetch any encoding with `fetch_artifact` and its id, e.g. `a#12`, `s#7` or `p#3`.\n"
         message/ledger-close))))

(defn prefer-in-run
  "In-run artifacts first, seeded ones after, order preserved within each.

  A completed run contributes its whole pool at turn 0 while the live run's
  starts empty, so without this seeds win on volume from the start and keep
  winning: gen-20 served 67 seeded artifacts against 24 of its own, spending
  three quarters of the channel re-telling a prior run's results.

  Ranked rather than filtered. A campaign that seeds without restating the
  prior results in its problem statement would lose them entirely, and
  `seed-from-run!` exists for exactly that case."
  [entries]
  (let [seed? #(str/starts-with? (str (:branch_id %)) "seed:")]
    (into (vec (remove seed? entries)) (filter seed? entries))))

(defn render
  "Shared artifacts as the block that goes into a branch's next-turn context:
  engine-confirmed by other branches, provenance inline.

  Carries the CODE as well as the claim. Three gen-20 branches proved the same
  scalarization lemma while being shown each other's claims five times each —
  a branch cannot cite a theorem statement it has never seen, so re-deriving
  was the only move open to it. The code was already being selected by
  `similar` and `recent`, and `seed-from-run!` copies it precisely so an
  inherited lemma can be re-confirmed in one cheap turn; only this function
  dropped it."
  [entries]
  (when (seq entries)
    (str "## Confirmed by other branches — engine-verified\n\n"
         "Cite these by name and re-verify in one call; do not re-derive them.\n\n"
         (str/join "\n"
                   (for [{:keys [branch_id kind tier claim code]} (prefer-in-run entries)]
                     (str "- [" branch_id " " (name kind) "/" (name tier) "] " claim
                          (when-not (str/blank? (str code))
                            (str "\n  ```\n  "
                                 (let [c (str/trim (str code))]
                                   (str/replace (if (> (count c) max-shared-code-chars)
                                                  (str (subs c 0 max-shared-code-chars)
                                                       "\n… [truncated]")
                                                  c)
                                                "\n" "\n  "))
                                 "\n  ```"))))))))
