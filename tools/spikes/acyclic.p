% Lemma (B)'s core, as a first-order consequence, with distractor axioms to
% see whether relevance filtering matters at this scale.
%
% Real content: if reach is transitive, every edge is a reach step, and no
% vertex reaches itself, then an edge a->b gives strict containment of
% reachable sets -- b's reachable set is inside a's, and a is in one but not
% the other. That is exactly the argument gen-26 a#788 made by hand.

fof(reach_trans, axiom,
    ![X,Y,Z] : ((reach(X,Y) & reach(Y,Z)) => reach(X,Z))).

fof(edge_is_reach, axiom,
    ![X,Y] : (edge(X,Y) => reach(X,Y))).

fof(acyclic, axiom,
    ![X] : ~reach(X,X)).

% --- distractors: true, irrelevant, and the kind of thing a premise selector
% --- would have to filter out of a 215k-declaration corpus.
fof(d1, axiom, ![X,Y] : (adj(X,Y) => adj(Y,X))).
fof(d2, axiom, ![X] : (colour(X,red) | colour(X,blue))).
fof(d3, axiom, ![X,Y] : ((colour(X,red) & colour(Y,red)) => same(X,Y))).
fof(d4, axiom, ![X,Y,Z] : ((between(X,Y,Z)) => between(Z,Y,X))).
fof(d5, axiom, ![X] : (weight(X) = zero | weight(X) = one)).

% Goal: an edge a->b means b reaches strictly less than a. Stated as: b's
% reachable set is contained in a's, and the containment is strict because a
% reaches b but b does not reach b.
fof(strict_containment, conjecture,
    ![A,B] : (edge(A,B) =>
      ( ![W] : (reach(B,W) => reach(A,W))
      & reach(A,B)
      & ~reach(B,B) ))).
