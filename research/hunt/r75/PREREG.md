# R75 pre-registration: Donchian, LESS LOSS (the last Donchian round)

Written 16 Oct 2026, committed BEFORE any lever is priced. Research only: nothing in `android/**` or `.github/**`.

Boss, 16 Oct: *"Should we make Donchian more powerful, less loss and more win rate?"* -> one LAST focused round, aimed at
**less loss and lower drawdown** (not win rate for its own sake: R64/R65 showed early profit-taking lifts the win rate but
cuts total P&L). After R75, Donchian is frozen and judged on paper.

## 0. Signal source

Every rule here keeps the signal on the **index** (Donchian 20 on the index's 5-minute bars; R73's Supertrend and R72's
breadth count are also on the index). Every lever below is read from the index, India VIX, the arm's own closed trades or
the calendar. The option is only what is bought. No rule reads an option's own chart.

## 1. Baselines (match exactly; R71/R72/R73 code, R64 engine, R51 Amendment A clean stores, dated charges, R17 fills)

| id | baseline | design net (published) | final net (published) |
|---|---|---|---|
| A | Donchian 20, paper exits P (-15%, 30/60/100 ladder + L25, 30 min, square-off 15:15) | Rs 9,998 | Rs 81,845 |
| B | A + R73's Supertrend (10, 3) agreement on the 5-minute index bar (T7-P; Boss added it on paper) | Rs 50,425 | Rs 1,34,743 |
| C | R72's C1 Breadth Donchian (4 of 5 indices break on the same bar), exits P | Rs 2,16,929 | Rs 1,71,366 |

The study refuses to run if its baselines do not reproduce these numbers (to the rupee). A is reported only; **every lever
is run on B and on C** (what paper runs). Trade-taking is Donchian's: 1-ITM, entries 09:20-15:10, one open a side per
index, at most 2 a side a day, never on that index's own expiry day, 1 lot, lots in force. R73's code is used read-only
(`r73rules.py` copied verbatim from `origin/claude/r73-donchian-trend-wait` 9a2c911, not yet on integ).

Periods (as R51-R73): design 20 Nov 2024 - 31 Mar 2026; walk-forward quarters Apr-Jun 2025, Jul-Sep 2025, Oct-Dec
2025, Jan-Mar 2026; final 1 Apr - 13 Oct 2026, **read once** by `r75study.py final` (guarded by `results/FINAL_READ`).

**Disclosure:** this final window is NOT a clean holdout. It has been read by R51-R73 (Donchian itself was chosen on it,
and B's and C's filters were judged on it). That is why the gate below is stricter than usual. Only paper from 14 Oct is
truly unseen.

## 2. Already tested, skipped here

- **ADX "chop" filter** (part of L4): R73 T5 (ADX 14 > 20) and T6 (ADX 14 > 25) on Donchian, both failed. Not re-run.
- **Expiry day of the traded index** (part of L6): Donchian already never trades an index on its own expiry day (R51 rule).
  Only the market-wide expiry day is new (L6a).
- **ATM vs 1-ITM** (part of L5): R51's Donchian re-tune chose 1-ITM over ATM under the old exits (-15% / +30%). Never
  tested under the paper exits P or on B / C, and 2-ITM was never tested, so L5 runs both.
- Early profit-taking / locks / trails / stop distance (R63-R69) and trend and wait filters (R73): not repeated.
- Nothing in `research/experiments/entries/` or `HUNT_*.md` tests a daily loss cap, index selection, time-of-day slice,
  VIX / opening-range / prior-day-range chop filter, gap-day handling or arm-level sizing on Donchian.

## 3. Levers (20 per baseline, 40 tests; every grid point is its own test, all counted in BH)

"Arm" = the whole book of a baseline (all five indices). Every lever decides with data known at the decision moment only.

| test | rule |
|---|---|
| **L1 daily loss cap per arm** | stop NEW entries on all indices for the rest of the day once the arm's CLOSED trades that day show ... |
| L1-K2 | 2 losses in a row (net <= 0, in exit order) |
| L1-K3 | 3 losses in a row |
| L1-X5 | a day net <= -Rs 5,000 |
| L1-X10 | a day net <= -Rs 10,000 |
| **L2 index selection** (decided on data before the period only) | |
| L2-NEG | drop every index whose net on the training days is < 0 |
| L2-WEAK | drop the one index with the lowest net on the training days |
| **L3 time of day** (on the entry minute) | |
| L3-F10 | no entries before 09:30 (skips 09:20-09:29) |
| L3-F25 | no entries before 09:45 |
| L3-L60 | no entries after 14:10 (the last hour of the 09:20-15:10 window) |
| **L4 chop filter** | |
| L4-VIX | skip the day if India VIX's previous close is in the lowest 30% of its previous 120 sessions' closes |
| L4-OR | skip entries from 09:45 on if the 09:15-09:44 range of that index is < 0.5 x its daily ATR 14 (previous days); entries before 09:45 are untouched |
| L4-PD | skip the day for an index whose previous-day range (high - low) is < 0.7 x its daily ATR 14 (the days before it) |
| **L5 strike** | |
| L5-ATM | buy the ATM strike instead of 1-ITM |
| L5-ITM2 | buy 2-ITM |
| **L6 expiry and gap days** | |
| L6-EXP | skip every index on a day when ANY of the five indices expires |
| L6-GAP | skip the day for an index whose open gap (09:15 open vs the previous close) is > 0.5 x its daily ATR 14 |
| L6-GAPH | the same days at HALF size |
| **L7 sizing at the arm level** (half size = 0.5 lot equivalent; at 1 lot this needs a 2-lot base to be real, see 6) | |
| L7-VOL | half size on an index whose 10-session realised volatility (daily close-to-close) is > 1.25 x its median over the previous 60 sessions |
| L7-DD20 | half size on every index while the arm's own (sized) closed equity up to yesterday is >= Rs 20,000 below its peak |
| L7-DD40 | the same at Rs 40,000 |

Details fixed now:
- L1: a trade blocks nothing until it has CLOSED; a trade entered at a minute after the triggering exit minute is
  skipped (same minute: allowed, the exit is not yet known at that minute's open). Losses in a row are counted across
  all five indices in exit order (ties: index order of the stores). Trades before the trigger are unchanged.
- L2: in design, the training days are all design days (in-sample, flagged); in each walk-forward quarter, every design
  day before it; for the final, all design days. Index nets are the baseline's own (B or C).
- L3, L4, L6-EXP, L6-GAP: the candidate is removed BEFORE trade-taking, so a later signal may use the freed slot (as the
  app would).
- ATR 14 = Wilder ATR of daily index bars (from the 1-minute candles, warm-up from 1 Sep 2024). VIX from the lake's
  `index_1m_INDIAVIX_*` daily last close (warm-up from Jan 2024). A missing input = no skip.
- Half size: net = 0.5 x (real exit - real entry) x qty - charges at 0.5 x qty, same fills.

## 4. Metrics (every lever, every period, 1 lot unless half size)

P&L, trades, win rate, avg win / avg loss, worst day, max drawdown (daily closed equity), % green days, **account-stop
breaches** (count of calendar months with net < -Rs 13,000; count of drawdown episodes deeper than Rs 40,000, an episode
ending at a new equity peak), **losers avoided vs winners missed** (trades of the baseline not taken by the lever, matched
on index, day, side and signal bar; for half size, the trades halved), trades affected, design / walk-forward quarters /
final, and at 2x slippage (E.SLIP2).

## 5. The gate (stricter than usual; ALL five must hold)

"Better than the baseline in a period" means (a) or (b):
- (a) net >= the baseline's net AND max drawdown <= 1.10 x the baseline's; or
- (b) less loss: (max drawdown <= 0.95 x the baseline's OR worst day >= the baseline's + 5% of its size) AND net >= the
  baseline's net - max(10% of |baseline net|, Rs 5,000).

1. Better in design AND in at least 3 of the 4 walk-forward quarters AND in the final.
2. Beats a random-skip twin in the final: 500 twins (seed 75), each drops k = (baseline trades - lever trades) baseline
   trades at random (for half-size levers: halves the same number of random baseline trades); p = (1 + #twins with
   net >= the lever's) / 501 < 0.05. For L5 (no trade dropped) the twin is a paired sign-flip of the daily differences
   (lever - baseline, 500 flips): p = (1 + #flips with sum >= actual) / 501.
3. Benjamini-Hochberg over all 40 tests' p: q <= 0.10.
4. >= 40 trades affected in the final (skipped, halved, or for L5 all trades).
5. At 2x slippage the lever is still better than the baseline at 2x slippage in the final, and its final net > 0.

## 6. Decision

- For each of B and C: of the passing levers, the best 2 by DESIGN net (ties: smaller design max drawdown) may be
  combined (at most 2). The combination is reported with every metric on design, walk-forward and final, labelled as
  chosen after the final read of the singles; it is recommended only if it is better (section 5) than the baseline AND
  than each of its two parts on both design and final. Otherwise the single best passing lever.
- A passing lever is a **paper-only** recommendation; Boss decides. Paper changes are logged in the Autopilot timeline,
  undoable in one tap, never carried to live. Half-size levers at 1 lot cannot be half a lot: on paper they would need
  a 2-lot base, otherwise they degrade to a skip; reported as such.
- Nothing passing -> Donchian (B and C) stays exactly as on paper. Either way, **Donchian is frozen after R75** and judged
  on paper.

## 7. Logging and tests

Every test in `research/experiments/entries/r75-*.yaml` (failures too). `research/hunt/r75/test_r75.py`: look-ahead
guards for every lever (changing data at or after the decision moment never changes the decision), each lever on
hand-made data, the gate and twin maths, and the baselines reproducing section 1 when the stores are present.
