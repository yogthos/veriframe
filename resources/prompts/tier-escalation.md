**Everything you have verified is from the fast tier.** One-shot checks — a single Z3 query, a single Prolog goal — are cheap and they are also the ones that produce false positives, because a wrong encoding is confirmed just as readily as a right one.

Before you ship, run something from the slow tier against your main result: `verify_template` if the problem shape has one (its cross-check is built in), otherwise `review` with an independent encoding followed by `audit`.

If your two encodings disagree, that disagreement is the finding.
