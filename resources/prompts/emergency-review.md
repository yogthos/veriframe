Your branch has hit the cull threshold, but you hold a confirmed artifact from the last few turns — that is the only reason you are still running. Three readings, and you have to pick one:

1. **Your best confirmed result is the answer.** Stop grinding. Run `review` with an encoding genuinely different in shape from the one that confirmed it, then `done`. A verified, cross-checked result near the known bound is a real answer; chasing further is greedy.

2. **Your encoding is wrong and the recent failures are the evidence.** An independent encoding in `review` is what catches this. If the two disagree, that disagreement is the bug.

3. **You are on the right path but stuck.** Reach for a different sub-strategy this turn, not a variation of the one that keeps failing.

The harness will not cull you this turn. If nothing changes, we will be back here.
