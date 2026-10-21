# R94 notes (dated, each written before the numbers it affects)

## N1 (21 Oct 2026, after PREREG commit 8b6b326, before any idea-1 / idea-2 / idea-4 statistic)

1. **Twins of the mean-reversion rules (ideas 1 and 2).** PREREG 6 says the random-entry twins use "the same exits and
   stop". A twin's side is random, so the rule's own exit ("z crosses 0") would often fire at the first observation
   (a twin short with z already below 0), and the twin would lose only its costs. That would make any rule look better
   than chance. So each twin trade keeps the rule's hard stop (2 x the entry sd against it; idea 2: |z| >= 3.5 on the
   twin's adverse side), its time stop and the expiry close, and replaces the z-cross exit by a holding time drawn at
   random from the rule's own trades in the same cell and window (observations for idea 1, rows for idea 2). The same
   fills and costs apply. This is stricter on the rule, not looser.
2. **Idea 1 fill timing.** Signals (the z or carry entry, the z-cross / carry-normal exit) fill at the NEXT observation
   (PREREG 3.1). The hard stop, the time stop and the expiry close fill at the observation that triggers them (a
   resting stop, a known clock time). The time stop: the first observation of the session >= entry session + 2 (Z1),
   + 5 (Z2), + 10 (C1). Sessions = dates on which either leg printed.
3. **Idea 1 expiry rule in sessions.** Future sessions are not in the data (most near legs expire after 19 Oct), so
   "7 / 5 sessions before the near expiry" is counted in Monday-Friday business days (`numpy.busday_count`); MCX
   holidays are ignored (at most a day early).
4. **Idea 1 twins' valid entries:** observations where the rule itself could have entered (z or carry defined, entry
   allowed by the expiry rule, a next observation exists, in the window).
