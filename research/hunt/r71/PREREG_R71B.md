# R71b pre-registration: a Donchian-channel trailing stop on the index, for the R71 patterns and for Donchian itself

Written 16 Oct 2026, **before any R71b profit or loss was computed**; committed first. Research only (no `android/**`).

Boss, 16 Oct, after R71 (no chart pattern passed): *"If we have stop trail like Donchian, what will be the results."*

## 1. Reading (Boss can correct it)

R71 already priced every pattern with Donchian's own exits (XB: -15% / +30% / 30 min; XL: the same with the ladder and
L25), so that reading is answered (`HUNT_R71_CHART_PATTERNS.md`). R71b tests the other reading: **a trailing stop made
from a Donchian channel on the index**. The trade is held, with no target and no time exit, until the index closes back
through the opposite side of the channel:

- a **call** is sold when a 5-minute index bar closes **below the lowest low of the previous N 5-minute bars** (put:
  above the highest high of the previous N); sold at the open of the minute after that bar closes;
- the channel is checked on every 5-minute bar that starts at or after the entry minute (continuous series, the
  previous N bars may include yesterday's); it only ever uses bars that have closed;
- plus Donchian's -15% premium stop (on the minute low, gap fills at the worse open), and square-off 15:15.

| id | N (trail channel) |
|---|---|
| **XD10** | 10 bars (50 minutes) |
| **XD20** | 20 bars (100 minutes, the same length as Donchian's entry channel) |

Signal source unchanged: the index; the trail is also on the index; only the -15% stop is on the premium.

## 2. What is tested (18 tests, BH across all 18)

- The 8 R71 patterns at their **original** setting (PREREG.md section 1), each with XD10 and XD20: 16 tests.
- **Donchian 20** itself (R51 signals, 1-ITM, its trade-taking) with XD10 and XD20: 2 tests. For Donchian this is an exit
  change: it is compared with its current exits XB and XL on the same days.

Nothing is fitted, so walk-forward = the four quarters Apr 2025 - Mar 2026 summed (gate > 0), design = 20 Nov 2024 -
31 Mar 2026, final = 1 Apr - 13 Oct 2026 read once by `r71bstudy.py final` (guard `results/FINAL_READ_R71B`).

## 3. Gates and decision

As R71 / R51: final net > 0; random-entry twin p < 0.05 (500, seed 711: same day, index and side, entry minute uniform
09:20-15:10, 1-ITM, the same exits, the trail recomputed from the twin's own entry); BH q <= 0.10 across the 18; >= 40
trades; top 5 < net; design and walk-forward > 0.

- A **pattern** with the trail is proposed for paper only if it passes every gate and stays > 0 at twice the slippage.
- **Donchian** with the trail is recommended over its current exits only if it passes every gate AND its net is at least
  both XB's and XL's in design, walk-forward and final (R66's rule). Paper only, never live.

**Honest note:** the final period was read for these same entries this morning (R71, with other exits), and for
Donchian many times. R71b is one more look at it, so the final is weak evidence; the design period carries most of the
weight. Logged as 18 more tests in `research/experiments/entries/` (families: patterns-indicators for the patterns,
trade-management for Donchian).
