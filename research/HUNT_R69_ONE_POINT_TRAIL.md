# R69: +5% stop at +25%, then +1 point of stop for every +1 point of gain

16 Oct 2026. Research only (no `android/**`). Pre-registered in `research/hunt/r69/PREREG.md` before any result. It runs
on R65's study code (`HUNT_CFG=r69cfg HUNT_CFG_DIR=research/hunt/r69`). Full tables: `research/hunt/r69/results/report.md`.

Boss, 16 Oct: *"Ladder should not be 5 percent each, start should be 5%, then after wards if the target reached 26, the
make stoploss +1"*: once the option is +25% the stop goes to +5%, then +26% -> +6%, +27% -> +7%, and so on (always 20
points below the best), on top of today's A4 rules.

## Answer

**No: it is a little worse than A4, and worse than R66's simpler "+5% once +25%".** Three arms together, 1 lot each, net
after all charges:

| rule | design 20 Nov 24 - 31 Mar 26 (5,370 trades) | walk-forward Apr 25 - Mar 26 (~3,960) | final 1 Apr - 13 Oct 26 (2,202-2,207) | final max DD |
|---|---|---|---|---|
| B0 research rules | Rs 3,41,327 | Rs 4,52,221 | Rs 2,03,566 | Rs 1,25,279 |
| **A4 app today** | Rs 3,51,167 | Rs 4,63,434 | Rs 2,22,063 | Rs 1,23,702 |
| R66 L25: +5% once +25%, held (for comparison) | **Rs 3,59,209** | **Rs 4,81,484** | Rs 2,27,459 | Rs 1,23,671 |
| **X25 Boss: +5% at +25%, then +1 per +1** | Rs 3,29,261 | Rs 4,68,310 | Rs 2,16,304 | Rs 1,20,218 |
| X20: +5% at +20%, then +1 per +1 | Rs 2,85,592 | Rs 4,49,196 | **Rs 2,54,571** | Rs 1,09,630 |
| X30: +5% at +30%, then +1 per +1 | Rs 3,82,486 | Rs 4,90,991 | Rs 2,20,626 | Rs 1,22,784 |

X25 minus A4 on the same days: design -Rs 21,906 (paired bootstrap p 0.69), final -Rs 5,759 (p 0.60).

**Why:** following 20 points behind the best sells the big runners on their normal pullbacks. Example: NIFTY call, 18 May
2026 (Donchian), sold at +8% (Rs 584) after a +28% high; A4 held to the 30-minute exit at +48% (Rs 3,925). It helps
when a big move turns back hard. Example: MIDCPNIFTY put, 24 Sep 2026, sold at +54% (Rs 6,045) where A4's ladder gave
+24.5% (Rs 2,678). Over all trades the two roughly cancel, with a small loss.

**The neighbours flip-flop.** X20 is the best of the day in the final (+Rs 32.5k vs A4) but loses Rs 65.6k in design. X30
is the best in design (+Rs 31.3k, p 0.057) and walk-forward but a little behind in the final (-Rs 1.4k). Results that
change sign between periods are what luck looks like, so none of them is a finding.

Per arm (net Rs, design / walk-forward / final): Donchian A4 7,474 / 1,73,115 / 78,777, X25 -10,150 / 1,71,210 / 70,429
(fails the gates: design net < 0); OOV A4 2,85,053 / 2,31,678 / 45,734, X25 2,82,800 / 2,40,489 / 48,838; Solo 2.0 A4
58,641 / 97,552, X25 56,611 / 97,037 (final trades 1,781 / 293 / 128).

Gates: every variant passes R51's gates except X25 and X20 on Donchian (design net <= 0). None beats random early exits
(exit-twin q 0.65-0.84), and none beats both baselines everywhere. **Keep A4.** The best change found today is still R66's
"+5% once +25%" (`research/HUNT_R68_STAIRCASE.md` has every rule side by side), and it is not proven either.

Limits as R65 - R68: the final period has been read by R51, R52, R57 and R64 - R69 (weak evidence); 96 tests across
R65 - R69 today; signals on the index, stops and locks on the premium; 1 lot.

Files: `research/hunt/r69/` (PREREG.md, r69cfg.py, r69entries.py, test_r69.py, results/),
`research/experiments/entries/r69-*.yaml` (15 entries).
