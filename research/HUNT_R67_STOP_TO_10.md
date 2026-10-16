# R67: move the stop from -15% to -10% once the option is +25% (on top of today's A4 ladder)

16 Oct 2026. Research only (no `android/**`). Pre-registered in `research/hunt/r67/PREREG.md` before any result. It runs
on R65's study code (`HUNT_CFG=r67cfg HUNT_CFG_DIR=research/hunt/r67`). Full tables: `research/hunt/r67/results/report.md`.

Boss, 16 Oct, correcting R66's reading: *"Actually i meant moving the -15% stop to -10%"* (once the option is +25%).

## Answer

**It makes almost no difference, so A4 stays.** Across the three arms (1 lot each, net after all charges):

| rule | design 20 Nov 24 - 31 Mar 26 (5,370 trades) | walk-forward Apr 25 - Mar 26 (3,961) | final 1 Apr - 13 Oct 26 (2,202) | final worst day | final max DD |
|---|---|---|---|---|---|
| B0 research rules | Rs 3,41,327 | Rs 4,52,221 | Rs 2,03,566 | -Rs 41,682 | Rs 1,25,279 |
| **A4 app today** | **Rs 3,51,167** | Rs 4,63,434 | Rs 2,22,063 | -Rs 41,682 | Rs 1,23,702 |
| **M25 Boss: -10% once +25%** | Rs 3,46,324 | **Rs 4,65,800** | **Rs 2,23,295** | -Rs 41,682 | Rs 1,24,079 |
| M20 -10% once +20% | Rs 3,42,799 | Rs 4,62,474 | Rs 2,18,120 | -Rs 41,682 | Rs 1,23,368 |
| M15 -10% once +15% | Rs 3,21,655 | Rs 4,30,594 | Rs 2,17,507 | -Rs 41,682 | Rs 1,22,385 |

M25 minus A4 on the same days: design -Rs 4,843 (paired bootstrap p 0.67), final +Rs 1,232 (p 0.12). That is noise.

**Why so little:** the rule only matters for a trade that reaches +25% and then falls all the way past -10%. That is rare:
between 0 and 5 trades a period per arm changed outcome. And it saves at most 5 points (Rs 300-800 a lot) on each. It
also has a cost. Some trades dip to -10% and then recover to a smaller loss or a profit by the time exit. Example: SENSEX
call, 30 Jun 2026 (Donchian), stopped at -10% (-Rs 1,043) where A4 would have exited at -6% (-Rs 667). The saves and
the costs about cancel.

Per arm (net Rs, design / walk-forward / final): Donchian A4 7,474 / 1,73,115 / 78,777 vs M25 9,234 / 1,74,876 /
78,425; OOV A4 2,85,053 / 2,31,678 / 45,734 vs M25 2,79,999 / 2,33,833 / 47,318; Solo 2.0 A4 58,641 / 97,552 vs M25
57,091 / 97,552 (final trades 1,781 / 293 / 128).

Gates: all 15 tests pass R51's gates (random-entry p <= 0.024, BH q <= 0.024). None beats both baselines everywhere or
random early exits (exit-twin q 0.75), so none is recommended.

**R66 (stop to +5% once +25%) did better than R67** (design +Rs 8,042, final +Rs 5,396 vs A4), because it turns a
reversal into a small profit instead of a slightly smaller loss. Neither is proven.

Limits as R65 / R66: the final period has been read by six studies (weak evidence); signals on the index, stops and
locks on the premium; one lot; the 8-of-10 paper trades after 13 Oct are not in the data.

Files: `research/hunt/r67/` (PREREG.md, r67cfg.py, r67entries.py, test_r67.py, results/),
`research/experiments/entries/r67-*.yaml` (15 entries).
