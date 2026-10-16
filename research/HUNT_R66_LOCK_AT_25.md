# R66: move the stop to +5% once the option is +25% (on top of today's A4 ladder)

16 Oct 2026. Research only: nothing in the app changes (no `android/**`). Whether anything changes is Boss's call.
Pre-registered in `research/hunt/r66/PREREG.md` (committed before any result). It runs on R65's study code with
`HUNT_CFG=r66cfg`. Full tables: `research/hunt/r66/results/report.md`.

Boss, 16 Oct, after 8 of their 10 recent paper trades went into profit and came back to the stop: *"What if we do 25% of
target, means if the target moves 25% then move stoploss 5 percent up."*

## Answer in one paragraph

**It is a small, safe improvement, not a proven one.** Unlike R65's +10% trail, it does not hurt: across the three arms it
made a little more than today's A4 lock in every period. Design: Rs 3,59,209 vs Rs 3,51,167. Walk-forward: Rs 4,81,484
vs Rs 4,63,434. Final: Rs 2,27,459 vs Rs 2,22,063. The worst day and drawdown were the same. But the gain is only about
Rs 5-8k over 2,200-5,400 trades, which is too small to tell apart from luck. The +5% step sells only about 1 trade in 40 (138 of
5,370 in design), and selling those trades at random minutes did about as well (exit-twin q 0.50). So by the
pre-registered rule nothing is *recommended*, and A4 stays. If Boss wants it anyway as a comfort rule ("once I have seen
+25% I don't want a loss"), the data says it costs nothing. Like every exit change it would go on paper only, logged and
undoable.

## Before / after (the three arms together, 1 lot each, net after all charges and slippage)

| rule | design 20 Nov 24 - 31 Mar 26 (5,370 trades, 337 days) | walk-forward Apr 25 - Mar 26 (3,961, 247) | final 1 Apr - 13 Oct 26 (2,202, 134) | final win rate | final worst day | final max DD |
|---|---|---|---|---|---|---|
| B0 research rules | Rs 3,41,327 | Rs 4,52,221 | Rs 2,03,566 | 46% | -Rs 41,682 | Rs 1,25,279 |
| **A4 app today** | Rs 3,51,167 | Rs 4,63,434 | Rs 2,22,063 | 45% | -Rs 41,682 | Rs 1,23,702 |
| **L25 Boss: +5% once +25%** | **Rs 3,59,209** | **Rs 4,81,484** | **Rs 2,27,459** | 46% | -Rs 41,682 | Rs 1,23,671 |
| L20 +5% once +20% | Rs 3,39,073 | Rs 4,70,617 | Rs 2,39,207 | 47% | -Rs 41,682 | Rs 1,19,091 |
| L30 +5% once +30% | Rs 3,60,625 | Rs 4,72,869 | Rs 2,26,403 | 46% | -Rs 41,682 | Rs 1,22,385 |

L25 minus A4 on the same days (paired day bootstrap, information only): design +Rs 8,042 (p 0.33), final +Rs 5,396
(p 0.23). Neither is significant.

## Per arm (net Rs: design / walk-forward / final)

| arm (final trades) | B0 research | A4 app today | **L25 Boss** | L20 | L30 |
|---|---|---|---|---|---|
| Donchian (1,781) | 8,281 / 1,69,661 / 55,439 | 7,474 / 1,73,115 / 78,777 | **9,998 / 1,75,789 / 81,845** | 14,125 / 1,80,746 / 83,559 | 9,724 / 1,73,960 / 81,102 |
| OOV (293) | 2,77,137 / 2,26,650 / 48,955 | 2,85,053 / 2,31,678 / 45,734 | **2,90,573 / 2,47,057 / 48,612** | 2,70,769 / 2,35,692 / 56,415 | 2,91,683 / 2,39,692 / 47,422 |
| Solo 2.0 (128) | 55,910 / = / 99,172 | 58,641 / = / 97,552 | **58,638 / = / 97,002** | 54,178 / = / 99,232 | 59,218 / = / 97,878 |

- **Donchian: a small yes.** L25 beats both A4 and B0 in design, walk-forward and final, by Rs 1.7k-3k each. That is the
  only arm where it clears the "beats both everywhere" bar. It still fails the random-exit check (q 0.50), so it is not
  recommended.
- **OOV: about level.** It beats A4 in every period, but is Rs 343 below the research rules in the final, so it misses
  the "beats both" bar by a hair.
- **Solo 2.0: no difference.** Only 5-7 trades a period reach +25%, so the rule barely acts; it is Rs 3 below A4 in design
  and Rs 550 below in the final.
- **The neighbours are not clearly better.** +20% is better in the final but worse in design, the same flip-flop that
  sank R65. +30% (the ladder's breakeven step raised to +5%) is the most consistent, with very few give-ups, but it acts
  on even fewer trades.

## What the +5% step did, trade by trade (three arms, against the same trades under A4)

| period | trades the +5% step sold | better than A4 (Rs saved) | worse than A4 (Rs given up) | net | went on to +30% after the sale |
|---|---|---|---|---|---|
| design | 138 | 79 (Rs 54,308) | 46 (Rs 51,773) | +Rs 2,535 | 70 |
| final | 43 | 24 (Rs 19,208) | 13 (Rs 11,783) | +Rs 7,424 | 25 |

So it does what Boss wants on most trades: a trade that reached +25% and then turned back now closes at about +5% instead
of a loss. Example from the final: FINNIFTY put, 11 Sep 2026 (OOV), +5% (Rs 768) instead of -14% at the time exit
(-Rs 2,574). The cost is trades that dip back to +5% and then run. Example: NIFTY put, 1 Jun 2026 (OOV), sold at +5% (Rs
323), where A4 would have held to +34% (Rs 2,710). The two roughly cancel, with a small edge to the +5% step.

**Why it is safer than R65's +10% trail:** a +25% move on the premium is less likely to be just noise than a +10% one,
so the step fires about 4 times less often (43 vs 603 trades in the final) and gives up far less.

## Gates (as R51 / R64 / R65)

All 15 tests pass R51's gates: final net > 0; random-entry twin p 0.002-0.014 (500, seed 66); BH q <= 0.014 over 15
tests; 128+ trades; top 5 < net; design and walk-forward > 0. So every variant keeps the arms' edge. None passes the
extra R65 / R66 bar. Random-exit twins: p 0.23-0.50, BH q 0.50 for all 9 L tests, so the step's timing is not shown
to beat luck. L25 also does not beat both baselines in all periods on OOV (B0 final) or Solo 2.0. Decision: **A4 stays**.
L25 is listed as an `inconclusive` idea that Boss may choose.

## Honest limits

- The final period has now been read by R51, R52, R57, R64, R65 and R66: weak evidence. The design period agrees in
  direction, but the gain is small.
- "Move stoploss 5 percent up" is read as "stop at +5% above entry". The other reading ("from -15% to -10%") is not
  tested; R64 A3 already showed a tighter loss stop costs money.
- Same machinery and limits as R65 (`research/HUNT_R65_STEP_TRAIL.md`): signals on the index, stops and locks on the
  premium, locks active from the next minute, gap fills at the worse open, dated charges, 1 lot.
- Boss's 8-of-10 paper trades are after 13 Oct and not in the data; they would be checked separately.

## Files

`research/hunt/r66/`: `PREREG.md`, `r66cfg.py` (variants), `r66entries.py`, `test_r66.py`, `results/` (summary.json,
report.md, per-group JSON, FINAL_READ). `research/experiments/entries/r66-*.yaml`: 15 entries. The study and report are
`research/hunt/r65/r65study.py` and `r65report.py`, now reading `HUNT_CFG` (default `r65cfg`, so R65 is unchanged).
