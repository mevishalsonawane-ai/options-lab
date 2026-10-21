# R68: Boss's staircase stop in three readings, and the best of everything tested on 16 Oct

16 Oct 2026. Research only (no `android/**`). Pre-registered in `research/hunt/r68/PREREG.md` (+ Amendment 1), both
before any R68 result. It runs on R65's study code (`HUNT_CFG=r68cfg HUNT_CFG_DIR=research/hunt/r68`). Full tables:
`research/hunt/r68/results/report.md`.

Boss, 16 Oct: once the option is +25%, the stop goes from -15% to -10% (R67), *"then 5 percentage increase in stoploss
as percentage move further to 30 and so on"*, *"this should on on basis of target percentage"*, and when asked which
reading: *"Check all and find what is much better."*

## Answer

**Nothing is much better than what the paper arms run today (A4).** Across the 19 new exit rules tested in four studies today
(R65-R68, 81 tests over the three arms), only two made at least as much as A4 in the design, walk-forward and final periods together:
**R66's "+5% stop once +25%"** and its +30% neighbour. Both made only about Rs 5-9k more over thousands of trades,
which is within luck. None passed the pre-registered bar ("beats both baselines everywhere AND beats random early
exits"). **Keep A4.** If Boss wants one change anyway, the best candidate is R66's: once the option has been +25%, the
stop goes to +5%, so a trade that reached +25% can no longer end in a loss. It costs nothing measurable.

## The three readings of the staircase (three arms together, 1 lot each, net after all charges)

Example on a Rs 100 option with a Rs 130 (+30%) target:
- reading 1 (W) moves the stop at 125 / 130 / 135 / 140 to 90 / 95 / 100 / 105;
- reading 2 (WP) moves it at 107.5 / 109 / 110.5 / 112 to 90 / 95 / 100 / 105, then 5 below each 1.5-point step;
- reading 3 (WM) is reading 1 plus a target that starts at 130 and rises 5 with each step.

| rule | design 20 Nov 24 - 31 Mar 26 (5,370-5,450 trades) | walk-forward Apr 25 - Mar 26 (~3,970) | final 1 Apr - 13 Oct 26 (2,202-2,232) | final win | final max DD |
|---|---|---|---|---|---|
| B0 research rules | Rs 3,41,327 | Rs 4,52,221 | Rs 2,03,566 | 46% | Rs 1,25,279 |
| **A4 app today** | **Rs 3,51,167** | Rs 4,63,434 | **Rs 2,22,063** | 45% | Rs 1,23,702 |
| W reading 1: staircase on profit, no target | Rs 3,43,015 | Rs 4,75,027 | Rs 2,15,320 | 45% | Rs 1,26,461 |
| WA reading 1 + A4's ladder | Rs 3,50,517 | **Rs 4,80,604** | Rs 2,18,023 | 45% | Rs 1,26,478 |
| WT reading 1 + own fixed target | Rs 3,46,187 | Rs 4,68,772 | Rs 1,93,173 | 46% | Rs 1,34,205 |
| WP reading 2: on progress to the target | Rs 1,64,314 | Rs 3,28,617 | Rs 1,77,044 | 49% | Rs 1,14,442 |
| WM reading 3: target moves up too | Rs 2,89,185 | Rs 4,27,492 | Rs 1,92,780 | 46% | Rs 1,19,655 |

- **Reading 1 (W / WA) is about level with A4.** It is a little behind in design and final and a little ahead in
  walk-forward (WA vs A4: design -Rs 650, final -Rs 4,040). It barely acts: the staircase only matters for a trade that
  reaches +25% and then falls back past -10%, which happens to only a handful of trades a period.
- **Reading 2 (WP) is much worse** (design -Rs 1.87 lakh vs A4). Moving the stop at +7.5%, +9%, +10.5% is the same mistake
  as R65's +10% trail. Example: SENSEX call, 6 May 2026 (Donchian), sold at +8.5% (Rs 759) where A4 rode it to +93% at the
  time exit (Rs 9,186).
- **Reading 3 (WM) is worse** (design -Rs 62k, final -Rs 29k vs A4). The moving target sells the big runners that A4,
  which has no target, keeps. OOV and Solo 2.0 lose the most.
- **Keeping the fixed target (WT) is worse in the final** (-Rs 29k vs A4), for the same reason: R64 already showed that
  dropping the fixed target helps.

Per arm (net Rs, design / walk-forward / final): Donchian A4 7,474 / 1,73,115 / 78,777, WA 11,846 / 1,81,220 /
79,490 (the only arm where WA beats A4 in all three, by small amounts); OOV A4 2,85,053 / 2,31,678 / 45,734, W 2,76,001 /
2,37,206 / 42,981, WP 2,06,505 / 1,90,155 / 54,750; Solo 2.0 A4 58,641 / 97,552, W 61,397 / 95,105. Final trades: 1,781 /
293 / 128.

Gates: every variant except WP on Donchian passes R51's gates (random-entry p <= 0.034, BH q <= 0.034 over 21 tests). The
arms' signals keep their edge under all of them. None beats random early exits (exit-twin q 1.0 for all 15), and none beats
both baselines everywhere, so none is recommended.

## Every rule tested on 16 Oct, best first (three arms together; net Rs design / walk-forward / final)

| study | rule | design | walk-forward | final | at least A4 in all three? |
|---|---|---|---|---|---|
| R66 | **+5% stop once +25%** (on top of A4) | **3,59,209** | **4,81,484** | **2,27,459** | **yes (+8k / +18k / +5k)** |
| R66 | +5% stop once +30% | 3,60,625 | 4,72,869 | 2,26,403 | yes (+9k / +9k / +4k) |
| R67 | -10% stop once +25% | 3,46,324 | 4,65,800 | 2,23,295 | no (design -5k) |
| R64 | **A4 (app today)** | 3,51,167 | 4,63,434 | 2,22,063 | - |
| R68 | WA staircase + ladder | 3,50,517 | 4,80,604 | 2,18,023 | no |
| R66 | +5% stop once +20% | 3,39,073 | 4,70,617 | 2,39,207 | no (design -12k) |
| R68 | W staircase | 3,43,015 | 4,75,027 | 2,15,320 | no |
| R64 | B0 research rules | 3,41,327 | 4,52,221 | 2,03,566 | no |
| R65 | S10n trail 10 pts from +10% | 2,62,359 | 3,72,936 | 2,31,357 | no (design -89k) |
| R68 | WM moving target | 2,89,185 | 4,27,492 | 1,92,780 | no |
| R65 | S5 Boss's +10% -> +5% trail | 1,23,197 | 2,36,496 | 1,31,424 | no |
| R68 | WP staircase on progress | 1,64,314 | 3,28,617 | 1,77,044 | no |
| R65 | T5 -5% stop moved up | -2,06,247 | -85,564 | -63,828 | no |

**The pattern:** a lock that acts early (+10%, or a small share of the target) sells too many trades that would have
run, and loses. A lock that acts late (+25% or more) does little either way. The only lock that helps a little is one
that, once a trade has been well up (+25-30%), stops it from ending in a loss (+5%). Tightening the loss stop (-10%, -5%)
never helped.

## Honest limits

- 81 tests across R65-R68 in one day. With that many tries, a Rs 5-8k edge is exactly what luck produces. R66's rule is
  "harmless and maybe slightly better", not proven.
- The final period has been read by R51, R52, R57 and R64-R68: weak evidence.
- Reading 2 needs a target for every arm. OOV, S1 and S3 have none, so +30% was used (pre-registered).
- Same machinery as R65 (`research/HUNT_R65_STEP_TRAIL.md`). The moving target (WM) uses `hunt/r68/r68engine.py`, R64's
  simulator plus the moving target, with a parity test. Boss's recent 8-of-10 paper trades are after 13 Oct and not in
  the data.

Files: `research/hunt/r68/` (PREREG.md, r68cfg.py, r68engine.py, r68entries.py, test_r68.py, results/),
`research/experiments/entries/r68-*.yaml` (21 entries).
