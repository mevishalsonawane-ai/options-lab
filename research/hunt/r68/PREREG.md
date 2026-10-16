# R68 pre-registration: Boss's full staircase: -10% at +25%, then +5 points of stop for every +5 points of gain

Written 16 Oct 2026, **before any R68 result was computed** (R67's results were already known: "-10% once +25%" alone
made almost no difference). Research only (no `android/**`); the app change is Boss's decision.

Boss, 16 Oct, completing R67's rule: *"Then 5 percentage increase in stoploss as percentage move further to 30 and so on."*

## 1. Reading (Boss can correct it)

A step staircase on the premium, always 35 points below the best gain reached, starting at +25%:

| best gain reached | +25% | +30% | +35% | +40% | +45% | +50% | ... | +100% |
|---|---|---|---|---|---|---|---|---|
| stop (vs the fill) | -10% | -5% | 0 (breakeven + charges) | +5% | +10% | +15% | ... | +65% |

Steps every 5 points up to +1000%. Between steps the stop stays at the last step reached. A lock set by a minute's high
acts from the next minute, and a gap through it fills at the open. Locks only rise. The "0" step is breakeven + charges
(R64's breakeven).

## 2. Variants (5 per arm, 15 tests)

| id | rule |
|---|---|
| **B0** | research rules (baseline 1) |
| **A4** | app today: own stop + 30/60/100 ladder, no target (baseline 2) |
| **W** | **Boss's staircase alone**: own stop + the staircase, no fixed target, own time exit and square-off (replaces A4's ladder) |
| **WA** | Boss's staircase **plus** A4's ladder (the stop is whichever is higher: at +30% breakeven beats -5%, at +60% +30% beats +25%, at +100% the staircase's +65% beats +60%) |
| WT | W with the arm's own fixed target kept (Donchian / S2 +30%, S4 +50%; OOV, S1, S3 have none) |

Plain counterpart for the trade-by-trade accounting: A4 for W and WA; B0 for WT.

## 3. Everything else as R65 - R67

Same arms (signals on the index, the option bought by the arm's strike rule), engine, stores, costs, periods (design 20
Nov 2024 - 31 Mar 2026, walk-forward quarters from Apr 2025, final 1 Apr - 13/14 Oct 2026), statistics, twins (500,
seed 68), gates (as R51; BH across these 15 tests; exit twins BH across the 9 W tests) and decision: recommended only if
it passes every gate AND its exit-twin q <= 0.10 AND its net is at least both B0's and A4's in design, walk-forward and
final. Otherwise A4 stays.

**Honest note:** the final period has been read by R51, R52, R57 and R64 - R67 (weak evidence). This is the fourth
trade-management idea of the day (75 tests across R65 - R68), logged as such. R65 already showed that tight step trails
(5-10 points below the best) lose to A4; this one is much looser (35 points).
