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

## N2 (21 Oct 2026, before any holdout read): option selling is out of scope

Boss, 21 Oct: "Option selling is out of scope." Idea 3 stops at the design run (`results/design_idea3.json`,
`NOTES_idea3.md`): its holdout is NOT read, it gets no gate verdict, no APP_SPEC and no paper arm.
- Its 88 design cells were tried, so they stay in the one Benjamini-Hochberg family (152 cells): dropping them after
  their design p-values were seen (all >= 0.36) would lower every other idea's q. The stricter family is kept.
- The idea-3 code and tests stay in the repo as the record of what was tried (CLAUDE.md: log every idea, failures
  included); its experiment entries say "out of scope, design only, holdout not read".

## N3 (21 Oct 2026 12:0x UTC, before any holdout number was seen): an aborted first holdout call

`study.py holdout` first recorded the read of every in-scope cell (ideas 1, 2, 4, 5) in `results/HOLDOUT_READ.json`,
then called each idea's `cells("holdout")`. Ideas 1, 2 and 4 were computed in memory; then `usgap.cells("holdout")`
tried to take the same lock itself and raised `HoldoutReadError`. The exception discarded everything: nothing was
printed, saved or pickled (`holdout_all.json` and `holdout_trades.pkl` were never written), so no holdout number of any
cell was seen by anyone. Fix: `usgap.cells(part, locked=True)` skips its own lock when the orchestrator holds it; the
aborted lock file is kept as `results/HOLDOUT_READ_aborted_2026-10-21T1200.json` and the read is run once more. That
run is the one and only read whose numbers exist.
