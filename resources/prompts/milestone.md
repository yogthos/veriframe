**First verified result.** Your branch just produced a confirmed artifact. The harness is intervening here because runs that do not ship at this moment usually fail.

**Default action**: cross-check it with `review` using an *independent* encoding, then call `done`. Two turns.

**Exception**: if the verified result is clearly far below the goal — you verified size 5 on a problem asking for at least 20 — keep going, but only if the next step has a high chance of landing.

**The trap**: after a confirmation the instinct is to push for more. Try size+1, then size+2, then a different construction. That pattern usually loses the verified result you already have. If what you have is competitive, stop and ship it. Greedy does not pay here.

Your next move should be `review` unless you have a specific reason to push further.
