# R194 pre-registration: does support / resistance help R51, R72 and Liquidity 15+5?

Written 18 Nov 2026, **before any R194 number was computed** (no reproduction, no new trade, no score). Committed and
pushed on `claude/r194-sr-study` before the code's first run on data. Research only: nothing in `android/**` changes
(except the knowledge pack, if `build_pack.py --check` asks for a rebuild). Owner: Boss (they/them).

Boss asked (18 Nov 2026): *"Does support/resistance help R51, R72 and Liquidity?"* and chose *"Yes start a deep study"*.
R194 was free when this was written (no `research/hunt/r194*` folder, no `r194` log entry, no `r194` branch).

**Signal source of every rule: the index.** The arms' own 5-minute index breaks (R51, R72) and 15 / 5-minute index bars
(Liquidity); every support / resistance (S/R) level is built from index minutes known at the decision minute. Options are
only bought; stops, breakevens and locks sit on the option premium; the S/R target in family D is an **index** touch, sold
at the next minute's open. No rule reads an option's own chart. No option is sold short.

## 0. What was seen before (honest context)

- **R150** (5-minute S/R levels on R51 and R72; the same level builder R151's features use) already tested "skip when a
  3+-turn level is within 0.25% ahead" (W5 / W20), "take only breaks that crossed a level" (B5 / B20) and a fake-breakout
  exit (FX). All failed their gates (R51's arm stopped making money on the unseen Dec 2022 - Nov 2024; R72's W20 had twin
  p 0.35). W20 on R51 stays a label (R150 / R151 Amendment B). Family C below re-asks the same kind of question on a
  different span (Nov 2024 - Nov 2026), with distances 0.15% / 0.30% fixed here (not 0.25%), on own exits and on top of the
  picker; its results are not independent of R150's and that is said in the report.
- **R151's picker** carries five S/R features (`sr_ahead`, `sr_behind`, `sr_touch_ahead`, `sr_crossed`, `piv_ahead`).
  Their coefficients are public in `results/r72_picker_model.json` / `r51_picker_model.json` (seen while writing this).
  Nothing has measured what they add.
- **R193** (17 Nov 2026) rebuilt R72, R51 and Liquidity 15+5 on this exact span; its totals are known: R72 own exits
  4,571 trades / Rs 3,90,101, + picker 2,256 / Rs 6,52,008; R51 own exits Rs 1,91,394, + picker Rs 4,88,964; Liquidity own
  exits 1,323 / Rs 2,55,293. The picker was R193's only robust help; V5 (the live step lock) made less than the arm's own
  exits in W2.
- R177 (X1 / X2) tested value-area trap exits; R186 / R179 the breakeven and locks. Not repeated.

## 1. Data and engines (nothing raw in git)

Exactly R193's (its PREREG section 1): R190's lake copy `~/.cache/iraalgo/r190/lake`, R190's R51 clean stores
(3 Aug 2020 - 13 Nov 2026) linked read-only under `~/.cache/iraalgo/r193/r51/clean`, read with `python3 -I`. Every R194
cache goes to `~/.cache/iraalgo/r194` (never git). R194 **imports R193's modules** (`r193cfg`, `r193lib`, `r193study`)
and, through them, R186's engine (`r186engine.price`, `forced_nets`), R179's pricing of the arms' own exits, R176's units
and walk, R152's picker module (R151's frozen app numbers) and R151's feature code (`study.features`, R150's `levels`).
Nothing in those files changes. Dated Zerodha charges and R17 slippage throughout; **1 lot** for every number.

## 2. Span and windows (R193's)

| window | dates | what it is |
|---|---|---|
| **W1** | 1 Nov 2024 - 31 Mar 2026 | R151's pickers were trained on 20 Nov 2024 - 31 Mar 2026: **in-sample** for every picker here (the real one and family B's retrain). |
| **W2** | 1 Apr - 13 Nov 2026 | **out-of-sample for the pickers, but NOT fresh**: R151's second look, R152, R176, R179, R186, R190 and R193 have all read it. |
| **total** | 1 Nov 2024 - 13 Nov 2026 | both |

## 3. Reproduction first (before any new number)

1. **R193's baselines** on this span, 1 lot: R72 A 4,571 / Rs 3,90,101 and R72-P 2,256 / Rs 6,52,008; R51 A and R51-P as
   R193's `span.json`; R72-V5 and R51-V5 as R193's; Liquidity A 1,323 / Rs 2,55,293. Liquidity is rebuilt by R194's copy of
   `fam_liq` signals (section 6 D) at room 1.0 with the new-level exit on: it must give R193's Liquidity A to the rupee.
2. **R151's model** (family B only): refit R72-20-4-M2 and R51-20-3.0-M2 exactly as `export_picker.py fit` does (all
   design trades 20 Nov 2024 - 31 Mar 2026, R151's features, median imputer + scaler + L2 logistic C 0.1) on R193's
   stores, and compare with the app's numbers (`r72_picker_model.json`, `r51_picker_model.json`). **Reproduced** = every
   median, mean, scale and coefficient and the intercept within 1e-6 (absolute) of the published value. Also R151's
   out-of-fold curve (4 walk-forward folds, Apr 2025 - Mar 2026) is recomputed and its best point checked against the
   threshold rule below (R51: 0.4469; R72: 0.4640 on the clean curve, see section 6 B).
   **If either model does not reproduce, family B stops for that arm and this is said; nothing is tuned to make it fit.**

Any gap in item 1 is reported with its size; tables then carry it in their heading (R193's rule).

## 4. The baselines

| id | arm | what it is |
|---|---|---|
| A | R72 / R51 | the arm's own exits (R179's A: -15%, ladder / L25, time stop, index exits, 15:05 / 15:15), no picker |
| P | R72 / R51 | A + R151's picker (R72 skip < 0.4656, R51 skip < 0.4469), applied the app's way (walk all, then remove) |
| V5 | R72 / R51 | the live rule: picker + breakeven at +10% + R186's Rs 500 step lock (R193's R72-V5 / R51-V5) |
| L1 | Liquidity | the live rule's entry and index exits: room >= 1.0 index stop to the next level, new-level exit on, own exits (R193's Liquidity A) |

## 5. Definitions used by every family

- **Levels (R51, R72)**: R150 / R151's: 5-minute index pivots (6 bars each side), from today and the 19 sessions before,
  a pivot known only once its 6 later bars have closed, grouped within 0.15%; a level's touches = its pivots. "Ahead" = above
  the price for a call, below for a put; "price" = the last index close before the entry minute (R151's `features`, the
  look-ahead wall at the decision minute). `sr_ahead` = the distance (fraction of price) to the nearest 3+-touch level
  ahead (0.05 when none); `sr_crossed` = 1 when the last 5 minutes' move crossed a 3+-touch level.
- **App-style skip**: as the picker in the app (CLAUDE.md fourth exception): every candidate is walked; a skipped break
  still holds its side and counts in the day's caps; it is then removed from the record.

## 6. The tests (fixed now: **24**)

### A. Ablation of the frozen pickers (2 tests)

| id | arm | variant (with S/R) | baseline (without S/R) |
|---|---|---|---|
| A-R72 | R72 | P (the real picker) | P-abl: the same frozen picker with `sr_ahead`, `sr_behind`, `sr_touch_ahead`, `sr_crossed`, `piv_ahead` set to their training medians (what the model does for a missing value), re-scored, same threshold 0.4656 |
| A-R51 | R51 | P | P-abl, threshold 0.4469 |

Both on the arm's own exits. Reported per window: trades, win %, net, max DD of P and P-abl, and the **flips** (taken by P
and skipped by P-abl, and the reverse).

### B. Retrain without S/R (2 tests; only if section 3.2 reproduces)

| id | arm | variant | baseline |
|---|---|---|---|
| B-R72 | R72 | P (the app's picker, as live) | P-noSR: R151's procedure without the five S/R features |
| B-R51 | R51 | P | P-noSR |

R151's procedure, unchanged except for the feature list (23 features): design units 20 Nov 2024 - 31 Mar 2026 at today's
parameters (R72 Donchian 20 / 4 of 5; R51 Donchian 20 + Supertrend (10, 3)), trades from R151's walk with R101's prices,
label = net > 0; M2 = median imputer + StandardScaler + LogisticRegression(C = 0.1, max_iter 3000); out-of-fold scores on
the four quarterly folds Apr 2025 - Mar 2026 (each fold's model trained on every design trade before the fold); the curve
over 39 score quantiles (0 - 95%) with R151's pre-walk filter (`kept_walk`).

**Threshold rule (R151's own, written down here).** R151's PREREG rule (`pick.first_pass`: the lowest threshold whose
walk-forward trades win >= 60%, make >= the arm's per trade and keep >= 25%) found **no** threshold for either arm. The
thresholds the app uses were then taken as the curve's best point: **the threshold with the highest walk-forward win rate
among those that keep >= 25% of the arm's walk-forward trades and make >= the arm's per trade (ties: the lower
threshold)**. On R151's committed curves this rule gives exactly 0.4469 (R51, clean `design.json`) and 0.4656 (R72, the
pre-fix `bug_cache/design.json` from which 0.4656 was quoted); on R72's clean curve it gives 0.4640. P-noSR's threshold is
fixed by this rule on P-noSR's own out-of-fold curve (first_pass is applied first and used if it finds one). The final
P-noSR model is fit on all design trades and scores the span; applied app-style like P.
Also reported (descriptive, not a test): the reproduced 28-feature model at the rule's threshold on its own clean curve.

### C. Explicit S/R entry filters for R51 and R72 (12 tests)

| filter | rule (skip the break when ...) |
|---|---|
| WALL15 | a 3+-touch level lies ahead within 0.15% (`sr_ahead` <= 0.0015) |
| WALL30 | a 3+-touch level lies ahead within 0.30% (`sr_ahead` <= 0.0030) |
| CROSS | the break did NOT cross a 3+-touch level (`sr_crossed` = 0); i.e. take only breaks that crossed one |

Each filter x {R72, R51} x {on own exits: vs A; on top of the picker: vs P} = 12 tests, ids `C-<filter>-<arm>-A` and
`C-<filter>-<arm>-P`. Applied app-style. Features from R151's `features` (the same numbers the picker reads).

### D. S/R exits (8 tests)

**R51 and R72: the S/R target** (`D-TGT-<arm>-P` vs P, `D-TGT-<arm>-V5` vs V5; 4 tests). At the entry, the nearest 3+-touch
level ahead (the level behind `sr_ahead`) is the target. From the fill minute on, the first minute whose index high
reaches it (call; index low for a put) sells the option at the next minute's open as a market sale (R17 fill), on top of
the variant's own exits (whichever comes first). No level ahead: no target. The level is fixed at entry.

**Liquidity 15+5** (vs L1; 4 tests). R194 copies `fam_liq.signals` / `_day_signals` (R51's family code, credited) so the two
pieces can be varied; everything else is unchanged (books BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5; index stops 30 /
15 / 8; the -15% stop, 20-minute time stop unless +5%, 15:10; the index stop and next-level target; the failed-break exit).
The room check: skip a signal when a level exists ahead and its distance is < room x the index stop (no level ahead: taken,
as today). The new-level exit: sell at the end of the bar at which a new zone on the trade's side becomes known.

| id | change |
|---|---|
| D-LQ-R0 | room 0 (no room check) |
| D-LQ-R05 | room 0.5 index stops |
| D-LQ-R2 | room 2 index stops |
| D-LQ-NL0 | the new-level exit off (room 1) |

Room filters act on the signal, **before** the walk (a skipped signal never takes the book, as in the app).

## 7. Twins (2,000 each, seed 194) and p

- **Filter vs its base (C)**: on the variant's app-style walk, as many trades as the filter removed are removed at random
  instead (R193's filter twin). p = (1 + twins with net >= variant) / 2,001.
- **Picker vs picker (A, B)**: both are skips on the same own-exit walk. Twin: from the baseline's kept set remove at random
  as many trades as the variant drops from it, and add at random (from the baseline's skipped trades) as many as the
  variant adds. p as above.
- **Exit (D-TGT)**: R193's exit twin: every trade the target closes >= 2 minutes earlier than the baseline's exits would
  (same candidate) is sold at the open of a random minute strictly between its fill and the baseline's exit, with the
  baseline's exits active before it (`forced_nets`; for V5 with the breakeven and the L500 lock); other trades keep their net.
- **Liquidity, room 2** (stricter than L1): remove at random, before the walk, as many L1 signals as room 2 removes, and walk.
  p = (1 + twins with net >= variant) / 2,001.
- **Liquidity, room 0 / 0.5** (looser than L1: the variant adds signals back). The question is whether L1's extra skips are
  worse than random skips: from the variant's own signals remove at random, before the walk, as many as L1 removes beyond
  the variant, and walk. Evidence for the variant = L1's net unusually LOW among these twins: p = (1 + twins with net <= L1's
  net) / 2,001.
- **Liquidity, NL0** (the variant drops an exit): on the variant's walk, each trade that L1's new-level exit would close
  >= 2 minutes earlier (same signal, priced with the exit on) is instead sold at a random minute strictly between its fill
  and the variant's exit (the variant's exits active before it). Evidence for the variant = the with-exit total on that walk
  unusually LOW among these twins: p = (1 + twins with net <= that total) / 2,001.
- **BH input**: R190 / R193's normal-tail p of the same comparison (z from the twins' mean and sd, in the same direction),
  because an empirical p cannot go below 1/2,001.

## 8. The gate (R193's; fixed now, never loosened)

A variant **HELPS** (vs its baseline in section 6) only if ALL hold:

1. higher net than the baseline in **W1 and in W2**;
2. lower max drawdown (daily closed-trade equity, R106 / R193 `stats`) in **W1 and in W2**;
3. at least 40 trades in W1 and in W2;
4. over the total span, the variant's net **without its 5 best trades** is above the baseline's full net;
5. twin p < 0.05;
6. **BH q <= 0.10** with m = 4,784 (the entry files in `research/experiments/entries` at this branch's base, origin/main
   4398bd6c5) + 24 = **4,808**; earlier entries count as p = 1.

Reported for every row (per window): trades, win %, net, average win / loss, max DD, worst month, net without the top 5,
and Boss's account stop (DD <= Rs 40,000, worst month >= -Rs 13,000) - descriptive.

**How the answer is read.** For family A and B a pass means "the picker's S/R features help the picker". A fail means
this study cannot show they help; if the S/R-free picker is better in both windows, that is said plainly. For C and D a pass
means the explicit S/R rule helps on that base. For Liquidity a pass of R0 / R05 means the live room check costs money; a
pass of R2 means a stricter check helps; NL0 passing means the new-level exit costs money.

## 9. Honesty and order of work

1. This file is committed and pushed before any number.
2. Reproduction (section 3) first -> `results/repro.json`.
3. Then each family, split per arm so no run passes the 30-minute background limit, written incrementally to
   `results/span_<family>_<arm>.json`; then `results/decision.json` (BH over all 24), `results/summary.md`.
4. Every test is logged in `research/experiments/entries/` (R193's YAML format), failures included.
5. The span is read once per family / arm (`results/READ_<family>_<arm>` written before its run). A crash before its
   results file is written may be re-run unchanged; a finished read is never re-run with a changed rule.

## 10. Tests (pytest, synthetic, no data)

The look-ahead guard (the S/R target level and the filters use only levels known at the decision minute: the future
replaced by noise changes nothing; the target row only looks from the fill on), the target row (call / put, no level, a
gap through, the touch at the last minute), the Liquidity signal copy (room 0 / 0.5 / 1 / 2, no level ahead, the new-level
exit on / off), the ablation (S/R at the median = the score with those inputs missing), the threshold rule (reproduces
0.4469 / 0.4656 on R151's committed curves; ties; first_pass first), the swap twin, the reversed-tail p, BH with m = 4,808,
and the gate (each item fails alone).
