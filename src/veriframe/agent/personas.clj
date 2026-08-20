;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.personas
  "Per-branch method priors: a different opening move for each branch.

  WHY, measured rather than assumed. Across 29 runs with a beam of three or
  more, 64% of branches on average opened with the SAME first verification
  tool, and in 21% of runs every branch did. gen-38 was the degenerate case:
  all five branches independently encoded a 41-vertex adjacency matrix as 820
  SMT booleans, and z3 was killed on the timeout for every one of them. A beam
  that explores one idea five times has bought nothing but five bills.

  WHAT THE EVIDENCE SAYS THESE MUST BE. Zheng et al. (EMNLP Findings 2024)
  tested 162 personas across four model families on 2,410 factual questions and
  found that identity personas in the system prompt do not improve accuracy,
  and that several mildly hurt it. The follow-up retrieval study is blunter:
  role prompting `reshapes how models communicate expertise rather than
  improving underlying capability`, making answers longer, more jargon-heavy
  and less readable. On a harness that has already lost a turn to the
  completion-token cap, that is a cost, not a benefit.

  What the multi-agent literature does credit is DIVERSITY OF REASONING PATH —
  agents that search differently catch each other's errors and cover more of
  the space. So none of these say `you are a physicist`. Each carries a
  method: what to reach for first, what to distrust, and what counts as
  progress. The label is a mnemonic for the operator reading a journal, not a
  costume for the model.

  Placement matters and is not free choice: these ride on the PROBLEM message,
  never the system frame, because `loop/refresh-frame` re-renders messages[0]
  from disk on the way to the wire every turn and would overwrite anything
  per-branch put there. The problem message is where `note` interventions live
  for exactly the same reason.")

(def personas
  "One method prior per branch. Ordered; branch N takes index N-1, cycling."
  [{:name "counter"
    :method
    (str "Your first move is to count something two ways. Reach for an exact"
         " identity or inequality over integers before you reach for anything"
         " else: pick the quantity both sides of the problem must agree on and"
         " force them to. You distrust any argument that never produces a"
         " number, and you treat a bound with slack in it as unfinished work"
         " rather than a result. Progress, for you, is a strictly tighter"
         " inequality with the case that attains it named.")}

   {:name "constructor"
    :method
    (str "Your first move is to build the smallest concrete instance that could"
         " possibly exhibit the phenomenon, and measure it. You compute before"
         " you conjecture, and you would rather have one example you have"
         " actually checked than three plausible claims about all cases. You"
         " distrust reasoning about objects nobody has exhibited. Progress, for"
         " you, is an explicit object plus the check that it has the property"
         " claimed — and if the small case behaves unexpectedly, that is the"
         " result, not a distraction.")}

   {:name "formalist"
    :method
    (str "Your first move is to write the statement down formally and see what"
         " the type checker demands of it, before deciding whether you believe"
         " it. You hold that most disagreement about whether a step is valid"
         " dissolves once the step is stated precisely enough to compile. You"
         " distrust prose that has never been made to typecheck, especially"
         " your own. Progress, for you, is a named declaration that a machine"
         " has accepted, with every hypothesis load-bearing.")}

   {:name "asymptotician"
    :method
    (str "Your first move is to ask what scales with what, and to estimate the"
         " answer before proving anything. Find the dominant term, work out"
         " roughly where the truth must lie, and only then decide which exact"
         " argument is worth the effort. You distrust exact case analysis begun"
         " before anyone has checked whether the case is where the difficulty"
         " lives. Progress, for you, is knowing which quantity controls the"
         " problem and how much room the current bound has left.")}

   {:name "adversary"
    :method
    (str "Your first move is to assume the claim is FALSE and hunt for the"
         " counterexample, spending real effort on the hunt rather than a"
         " token gesture. A claim that has survived a genuine attempt to break"
         " it is worth more than one nobody attacked. You distrust any step"
         " whose failure mode has not been named. Progress, for you, is either"
         " a counterexample or a precise account of the obstruction that"
         " stopped you from finding one — and the second is a real finding,"
         " so report it rather than hiding it.")}

   {:name "reducer"
    :method
    (str "Your first move is to change the problem rather than attack it: find"
         " an equivalent statement that is smaller, or one about a structure"
         " that is better understood. You are suspicious of effort spent on the"
         " problem exactly as posed, because the posing was someone's guess at"
         " the right frame. You distrust a long computation that a change of"
         " representation would have made short. Progress, for you, is a"
         " reduction stated as an if-and-only-if, with both directions"
         " accounted for.")}])

(defn for-index
  "The method prior for branch index i, cycling so a beam wider than the
  catalogue reuses rather than handing back nil."
  [i]
  (nth personas (mod i (count personas))))

(defn render
  "The block appended to a branch's problem message. Nil persona renders to
  nothing, so the un-personified path stays byte-identical."
  [persona]
  (when persona
    (str "\n\n## How you work\n\n"
         "Other branches are working this same problem from deliberately"
         " different angles, so play your own rather than hedging toward"
         " theirs.\n\n"
         (:method persona)
         "\n\nThis is a prior on where to START, not a cage: if the problem"
         " plainly calls for something else, do that and say so.")))
