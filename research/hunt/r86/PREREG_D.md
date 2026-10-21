# R86d pre-registration: the BANKNIFTY seller detector, improved, tested on UNSEEN data (committed before any run)

Written 19 Oct 2026. Research only: nothing in `android/**` or `.github/**`.

Boss, 19 Oct: *"improve the BANKNIFTY seller detector and test again"*.

## Why the test must be on unseen data

R86c's C6-BANKNIFTY (13 shorts, +Rs 36,188, twin p 0.002, BH q 0.024) failed only because its top 5 trades carried
it. Any "improvement" chosen after seeing the 2019-2024 results is fitted to them, so design numbers can no longer
prove it. The only honest test is the period nobody has read for this idea: **1 Jan 2025 - 14 Oct 2026** (R86c's
final, never opened: `results/FINAL_C_READ` does not exist). It is read ONCE, by `r86d.py final`, for every variant
below together (guard `results/FINAL_D_READ`). Design walk-forward numbers are reported but labelled **post hoc**.

## Variants (fixed now; each reasoned from a mechanism, not from a search)

Common to all: signal on the underlying (BANKNIFTY index minutes, the 14 bank stocks' minute turnover/direction,
INDIA VIX); decision at 09:45 from 09:15-09:44 only; SHORT the BANKNIFTY future at the 09:45 open; stop = entry +
max(0.5 x the 09:15-09:44 range, 0.15%); R86's futures costs; one trade a day; never on a day without 09:45 data.

| id | change vs C6 | why |
|---|---|---|
| D0 | none: C6 exactly (BANKNIFTY-only training, top 10% of training probabilities, exit 11:15) | the reference; reported, NOT gated |
| D1 | train on NIFTY + BANKNIFTY days pooled (same 15 features, P10 dropped for both) | C6 had ~100-300 labelled BANKNIFTY days; pooling gives 2019- and a 2022 fold |
| D2 | C6, but exit at 15:15 | the catalogue: BANKNIFTY keeps falling to 15:15 when the gap is not recovered (-23 bps, 45 days) |
| D3 | no model: gap down <= -0.5% AND 09:15-09:44 down (pattern P2), exit 15:15 | the simplest, most transparent form of the same idea (lift 3.2) |
| D4 | D1 + D2 + the top 20% of training probabilities (more trades) | the combined version |

Training for D0/D1/D2/D4: logistic regression (standardised, C = 1) on all labelled days before the test period
(FII-SELL label as R86b: the FII change in index-futures net in the bottom 10% of its previous 250 days). Final model:
trained on every labelled day to 31 Dec 2024. Threshold from the training days' own probabilities.

Also reported for every variant (not gated): the same signal buying the ATM BANKNIFTY put (strike nearest the 09:44
spot, nearest expiry in the lake's rolling series) at the 09:45 open and selling the same contract at the variant's
exit minute (or the index stop's minute), dated option charges + 5 bps + Rs 0.05 a side. This is how the app would
trade it (signal on the underlying, buy the option).

## Random twins and pass rule (final window)

500 twins (seed 864): random BANKNIFTY sessions of the same period, the same count, short at 09:45, the same stop %
and exit rule. Gated: D1-D4 (4 tests, BH within R86d). A variant passes if, in the FINAL window: net > 0, net > the
twins' mean, twin p < 0.10, BH q < 0.10, and net without its 5 best trades > 0. Its post-hoc design walk-forward
(folds 2022-2024) must also be net > 0. A pass is a PAPER-ONLY candidate for Boss (logged in the Autopilot timeline,
undoable, never live by itself); an APP_SPEC (not built) is written only then.

## Tests and logging

`research/hunt/r86/test_r86d.py`: the pooled training table has the same columns for both indices; D3's rule on
hand-made days; the final read guard (a second read is refused); the put pricing holds the same strike. One
`research/experiments/entries/r86d-*.yaml` per variant with `holdout_read: true`; a FINDINGS section; the write-up
`research/HUNT_R86D_SELLER_DETECTOR.md`.
