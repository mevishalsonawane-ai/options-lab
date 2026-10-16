# R69 pre-registration: +5% stop at +25%, then +1 point of stop for every +1 point of gain

Written 16 Oct 2026, **before any R69 result was computed** (R65 - R68 results were known: the best rule so far was R66's
"+5% stop once +25%", which beat A4 by an amount within luck). Research only (no `android/**`); the app change is
Boss's decision.

Boss, 16 Oct, after R68: *"Ladder should not be 5 percent each, start should be 5%, then after wards if the target
reached 26, the make stoploss +1"*.

## 1. Reading (Boss can correct it)

On top of today's A4 rules (own stop, 30/60/100 ladder, no fixed target, own time exit and square-off). Once the option
premium has traded **+25%** above the fill, the stop goes to **+5%**. After that, every further **+1 point** of best gain
lifts the stop **1 point**: best +26% -> stop +6%, +27% -> +7%, ..., +60% -> +40%, +100% -> +80%. The stop stays 20
points below the best gain, in 1-point steps up to +1000%. A4's ladder never binds above +25% (it is always lower).
Locks only rise. A lock set by a minute's high acts from the next minute, and a gap through it fills at the open (the
worse price).

## 2. Variants (5 per arm, 15 tests)

| id | rule |
|---|---|
| **B0** | research rules (baseline 1) |
| **A4** | app today (baseline 2; the plain counterpart of every X variant) |
| **X25** | **Boss's rule**: A4 + stop +5% at +25%, then +1 point per +1 point (20 points below the best) |
| X20 | neighbour: +5% at +20%, then +1 per +1 (15 points below) |
| X30 | neighbour: +5% at +30%, then +1 per +1 (25 points below) |

R66's L25 (+5% at +25%, then held there until A4's ladder takes over at +60%) is the rule X25 must beat to be worth a
change. It is reported beside X25 from R66's own results, not re-run and not counted here.

## 3. Everything else as R65 - R68

Same arms (signals on the index, the option bought by the arm's strike rule), engine, stores, costs, periods (design 20
Nov 2024 - 31 Mar 2026, walk-forward quarters from Apr 2025, final 1 Apr - 13/14 Oct 2026), statistics, twins (500,
seed 69), gates (as R51; BH across these 15 tests; exit twins BH across the 9 X tests) and decision: recommended only if
it passes every gate AND its exit-twin q <= 0.10 AND its net is at least both B0's and A4's in design, walk-forward and
final. Otherwise A4 stays.

**Honest note:** the final period has been read by R51, R52, R57 and R64 - R68 (weak evidence). This is the fifth
trade-management study of the day (96 tests across R65 - R69), logged as such.
