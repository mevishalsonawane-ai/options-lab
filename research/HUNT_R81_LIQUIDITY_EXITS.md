# R81: better exits and trade management for Liquidity 15+5, and which indices should run it

18 Oct 2026. Research only (no `android/**`, no `.github/**`). Pre-registered in `research/hunt/r81/PREREG.md`
(90c050f, pushed before any result). The code and the design results were committed (cf31ada) before the final period
was read once. Code: `research/hunt/r81/`.

Boss, 18 Oct: *"use multiple agents/sessions to enhance the liquidity strategies ... use all the existing knowledge."*
R81 covers what happens **after** the fill. R80 (entry filters) and R79 (direction filters) are separate.

## Answer

**None of the 11 new exit and trade-management ideas passed, so Liquidity keeps its own exits on every index.** The
pass rule was: ahead of the plain rules in the walk-forward and final periods, BH q < 0.10 across all 66 tests, twin
p < 0.10, and a gain that does not depend on its top 5 trades.

- The near miss is **X1: dropping the -15% premium stop** (the index stop beyond the broken level still protects the
  trade). It never made less than the plain rules: +Rs 2,558 in design (881 trades), +Rs 286 in the walk-forward year,
  +Rs 23,178 in the final period (357 trades). Its sign-flip twin p was 0.01, but its BH q was 0.165. Most of the final
  gain is one MIDCPNIFTY put (24 Sep 2026: stopped at -15% under the plain rules, then +93%). With its top 5 gains
  removed it is -Rs 9,582 in design. It also adds risk. **Not recommended**; at most a labelled paper note.
- **Re-entering after a stop (X6)** beat random re-entries (twin p 0.002) but lost Rs 33,831 in design: not passed.
- **Adding a lot at 50% of the way to the target (X7)** made +Rs 58k in design, then lost Rs 19,925 in the final
  period. It also doubles the size on those trades. Not passed.
- **Earlier exits** (an index ATR trail, an index "no progress" time stop, half off at +15%, a 14:30 square-off) and
  **holding overnight** all lost money in design. This repeats the old lesson: **Liquidity earns on a few runners, and
  anything that sells them early, or swaps them for overnight gap risk, costs more than it saves.**
- **Which indices:** by the pre-registered rule (positive in design, walk-forward and final, and still positive in
  design and final without the 5 best trades), **no index qualifies on the newest data.** In the final period
  (1 Apr - 13 Oct 2026) every index's profit, where there was any, came from its top 5 trades, and on no index did the
  arm beat random entries (twin p 0.51-0.97). By index:
  - **BANKNIFTY:** positive in all three periods (Rs 1,10,094 / 63,994 / 10,978). Stays the paper and 26 Oct
    candidate on its **plain rules**, unchanged.
  - **FINNIFTY (30+5):** positive in all three periods. Can stay parked or on paper.
  - **MIDCPNIFTY:** lost Rs 4,674 in the final period. In design, random entries made more than the arm (Rs 1.45 lakh
    against Rs 57,504). It is on paper only. Boss should know it is the weakest of the three app books.
  - **NIFTY:** lost in design and final (-Rs 9,522 / -Rs 23,134). Do not add.
  - **SENSEX:** small gains in every period (Rs 5,877 / 22,728 / 5,263), but all of it is its top 5 trades
    (-Rs 40,462 in design without them). Do not add.
- No exit change is recommended for any single index either: 0 of 55 per-index tests passed.

**For the 26 Oct decision this changes nothing.** BANKNIFTY's plain-rules record is untouched. No APP_SPEC is written
because nothing passed.

## The pooled arm (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5), 1 lot, net after all charges and slippage

The same trades in every row: 881 in design, of which 657 are in the walk-forward year, and 357 in the final period.
X6 adds re-entries (134 design / 72 final). X7 adds lots (281 / 87).

| id | rule (signal: the index in every row) | exit source | design (881) | walk-forward (657) | final (357) | gain vs B0 design / final | top-5 removed design / final | twin p (kind) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | own exits (today) | premium + index | **Rs 2,57,249** | **Rs 2,38,064** | **Rs 11,155** | | | | | keep |
| X1 | no -15% premium stop | index | Rs 2,59,808 | Rs 2,38,350 | Rs 34,333 | +2,558 / +23,178 | -9,582 / +421 | 0.010 (flip) | 0.165 | near miss, not recommended |
| X2 | index stop beyond the breaking bar | index | Rs 2,32,915 | Rs 2,37,881 | Rs 21,530 | -24,334 / +10,375 | -38,540 / -2,448 | 0.166 (flip) | 0.72 | fail |
| X3 | index chandelier (arm 1 ATR, trail 1.5 ATR) | index | Rs 1,95,136 | Rs 2,14,671 | -Rs 5,795 | -62,114 / -16,950 | | 0.99 (exit) | 1.00 | fail |
| X4 | index no-progress stop (10 min, 0.25 ATR) | index | Rs 1,72,147 | Rs 1,55,772 | Rs 4,121 | -85,102 / -7,034 | | 0.95 (exit) | 1.00 | fail |
| X5 | half at +15% (1R), runner to breakeven | premium | Rs 1,78,523 | Rs 1,60,436 | -Rs 23,896 | -78,726 / -35,051 | | 0.96 (flip) | 1.00 | fail |
| X6 | one re-entry after a stop, on a 5-min re-close beyond the level | index | Rs 2,23,418 | Rs 2,26,911 | Rs 23,145 | -33,831 / +11,990 | -71,223 / -11,139 | 0.002 (random re-entries) | 0.132 | fail |
| X7 | +1 lot at 50% of the way to the target (option above its fill) | index trigger | Rs 3,15,441 | Rs 2,84,092 | -Rs 8,770 | +58,192 / -19,925 | +21,892 / -31,151 | 0.43 (random adds) | 0.88 | fail |
| X8 | square-off 14:30 | time | Rs 2,60,720 | Rs 2,33,925 | Rs 17,224 | +3,471 / +6,069 | -16,285 / -10,121 | 0.158 (exit) | 0.72 | fail |
| X9 | square-off 15:20 | time | Rs 2,60,254 | Rs 2,38,755 | Rs 14,526 | +3,004 / +3,371 | -3,789 / -3,273 | 0.198 (flip) | 0.72 | fail, "harmless" |
| X10 | hold runners (+20% at 15:09) overnight, GTT at the fill | premium + time | Rs 2,22,282 | Rs 2,05,541 | -Rs 12,612 | -34,967 / -23,767 | | 0.92 (flip) | 1.00 | fail |
| X11 | breakeven (+1%) after a 5-min close 0.5 ATR past the level and the option +3% | index trigger, premium stop | Rs 2,06,910 | Rs 1,96,253 | Rs 25,651 | -50,339 / +14,496 | -68,804 / +3,996 | 0.188 (exit) | 0.72 | fail |

Win rate, worst day and max drawdown hardly moved for the closest rows. B0's final period: 37% wins, worst day
-Rs 11,405, max DD Rs 61,062. X1: worst day -Rs 11,405, max DD Rs 56,307. X6 raised the design max DD from Rs 32,537 to
Rs 46,230.

"Twin p" kinds: *exit* = as many random early exits as the rule changed (R65 / R76); *flip* = each day's difference given
a random sign (used where a "random version" of the rule does not exist); *random re-entries / adds* = the same number
at random minutes on randomly chosen eligible trades. 500 twins each, final period. BH runs across all 66 tests (11
pooled + 55 per index).

## Per index: should the arm run there? (B0, the plain rules)

| index (books, index stop) | design | walk-forward | final | design / final without top 5 | final worst day / max DD | random-entry twin p (final) | verdict |
|---|---|---|---|---|---|---|---|
| BANKNIFTY (15+5, 30 pts) | Rs 1,10,094 (336) | Rs 63,994 (242) | Rs 10,978 (131) | +51,193 / -33,992 | -Rs 6,910 / Rs 24,873 | 0.68 | keep on plain rules (go-live candidate) |
| FINNIFTY (30+5, 15 pts) | Rs 89,651 (252) | Rs 85,442 (189) | Rs 4,852 (95) | +32,697 / -26,880 | -Rs 4,362 / Rs 12,371 | 0.51 | parked / paper, as now |
| MIDCPNIFTY (15+5, 8 pts) | Rs 57,504 (293) | Rs 88,628 (226) | -Rs 4,674 (131) | -13,828 / -48,028 | -Rs 6,682 / Rs 35,514 | 0.84 | weakest; paper only, flag to Boss |
| NIFTY (15+5, 15 pts) | -Rs 9,522 (235) | Rs 6,313 (166) | -Rs 23,134 (94) | -63,392 / -46,371 | -Rs 4,612 / Rs 23,567 | 0.97 | do not add |
| SENSEX (15+5, 50 pts) | Rs 5,877 (229) | Rs 22,728 (160) | Rs 5,263 (72) | -40,462 / -19,281 | -Rs 3,621 / Rs 13,860 | 0.81 | do not add |

The final period's profit leans on a few trades: the pooled arm's 5 best final trades made Rs 62,530 of its Rs 11,155
(Rs 20,223, 13,389, 11,642, 10,138, 7,138). In design, the 5 best made about Rs 81,500 of Rs 2,57,249. Two of the design
trades are the same contract bought by both books on one day. Not beating random entries is an entry question (R80's
area), so it is only reported here.

### Exits per index (net, final period; B0 first)

| variant | BANKNIFTY | FINNIFTY | MIDCPNIFTY | NIFTY | SENSEX |
|---|---|---|---|---|---|
| B0 | 10,978 | 4,852 | -4,674 | -23,134 | 5,263 |
| X1 no premium stop | 11,090 | 14,335 | 8,909 | -24,331 | 2,470 |
| X2 sweep stop | 16,916 | 7,700 | -3,086 | -20,286 | 4,949 |
| X3 ATR trail | 10,034 | 182 | -16,012 | -20,868 | -3,380 |
| X4 no-progress | 11,279 | 9,326 | -16,485 | -11,568 | 10,489 |
| X5 scale out | -1,498 | -3,132 | -19,265 | -27,583 | -5,340 |
| X6 re-entry | 12,309 | 7,180 | 3,656 | -24,242 | 205 |
| X7 pyramid | 651 | -1,559 | -7,862 | -30,538 | 5,338 |
| X8 14:30 | 21,166 | -3,312 | -629 | -22,402 | -65 |
| X9 15:20 | 13,462 | 7,037 | -5,973 | -24,678 | 4,440 |
| X10 overnight | -983 | 2,245 | -13,874 | -31,525 | 13,582 |
| X11 struct BE | 13,672 | 8,623 | 3,356 | -15,168 | 8,491 |

No cell passed. The closest were:
- BANKNIFTY X8 (14:30): twin p 0.004, q 0.132. It lost Rs 254 in the walk-forward year and ex-top-5 is negative.
- MIDCPNIFTY X6: twin p 0.006, q 0.132. It lost in design.
- NIFTY X11 and X3: "harmless", but NIFTY still loses with them.

Full numbers: `research/hunt/r81/results/summary.json`.

## Verdict per idea

| idea (Boss's list + own) | id | verdict | why, in one line |
|---|---|---|---|
| structure stop beyond the swept level | X2 | rejected | wider index stop lets more losers run in design (-Rs 24k); final +Rs 10k is not enough (q 0.72) |
| ATR trail on the index | X3 | rejected | cuts runners: -Rs 62k design, -Rs 17k final; worse than random exits (p 0.99) |
| time stop, no progress in N minutes (index) | X4 | rejected | the worst idea: -Rs 85k design; slow starters include runners |
| scale out half at 1R, let the rest run | X5 | rejected | half of every runner is sold at +15%: -Rs 79k design, -Rs 35k final |
| re-entry after a stop if the level is re-swept | X6 | rejected | the re-entries lose in design (-Rs 34k, 134 of them); good timing only in the final |
| pyramiding into runners (paper) | X7 | rejected | +Rs 58k design, -Rs 20k final; doubles size; twin p 0.43 |
| end-of-day exit time 14:30 / 15:20 | X8 / X9 | rejected | tiny effects (29 / 23 trades changed in design); not significant; 15:20 "harmless" |
| hold the best runners overnight with a GTT | X10 | rejected | the overnight gap gives back more than it adds: -Rs 35k design, -Rs 24k final (13 / 6 trades) |
| breakeven only after a structure confirmation | X11 | rejected | still sells runners at breakeven on a pullback: -Rs 50k design |
| no premium stop, index stop only (own idea) | X1 | rejected (near miss) | harmless in all three periods, but q 0.165 and one trade carries the final gain |
| delta- or IV-aware strike roll | - | **not tested** | the stores hold one contract a trade, not the chain's greeks; no rule was specified |

## Method

- **Signal:** the index (Liquidity's level breaks on 15/30/5-minute index bars), unchanged. The 1-ITM option is bought.
  Exit sources are in the table. No rule reads an option's own chart for a signal.
- **Trades** are made as in R70 / R76: `fam_liq.make` on R51's Amendment A clean stores, rebuilt from the Drive lake
  (options to 13 Oct 2026). NIFTY / SENSEX use `h4 comps` IDX_STOP_EXT (15 / 50 pts) and BOOKS_EXT (15+5). Expiry days
  are skipped. B0 reproduces R76 exactly (Rs 2,57,249 / 881 design, Rs 11,155 / 357 final; the study asserts it). Each B0
  trade is priced again under each variant from the same entry row on R64's engine (held contract, liquidity
  slippage, stops on the minute low with gap fills at the worse open, locks from the next minute, dated charges, 1 lot).
- **ATR** = the mean true range of the last 14 five-minute index bars complete at the signal. Index rules fire on a
  1-minute bar and sell at the next minute's open.
- **Overnight (X10)** prices the same contract on the next session's 09:15-09:16 minutes with the app's exit slippage
  and charges.
- **Pyramid (X7):** the added lot is sold at the base's exit row. When the base left inside a minute (stop or time), it
  is sold at the next minute's open. This look-ahead fix was made in design, before the final read. The first design
  run sold it at that minute's open (+Rs 62k instead of +Rs 58k).
- **Look-ahead guard:** `test_r81.py` (17 tests) changes every bar after each rule's decision minute and checks that the
  decision stays the same. It also covers the edge cases: no ATR, missing bars, no target, a target behind the entry,
  the 14:00 cut-off, and a GTT gap.
- **Periods:** design 20 Nov 2024 - 31 Mar 2026; walk-forward Apr 2025 - Mar 2026 (nothing was fitted); final 1 Apr -
  13 Oct 2026, read once (`results/FINAL_READ`). **The final period had already been read by R51, R63, R70 and R76 for
  this arm, so it is weak evidence.** No fresher option data exists in the lake (it ends 13 Oct).
- **Limits:** a variant that holds longer or exits earlier would change which later signals a book could take. That is
  not modelled; the changed-trade counts bound it. X6 skips a re-entry that would overlap the book's next trade. X6's
  "new level" exit is not modelled.
- **Web sources (ideas only, never evidence):**
  - [Barchart: Chandelier Exit](https://www.barchart.com/education/technical-indicators/chandelier-exit) and
    [LuxAlgo: Chandelier stop](https://www.luxalgo.com/library/concept/chandelier-stop.md): LeBeau's ATR trail.
  - [ATAS: time stop](https://atas.net/blog/time-stop-how-to-use-the-time-factor-in-trading/) and
    [LuxAlgo: non-price exits](https://www.luxalgo.com/library/concept/non-price-exits.md): "a breakout that does not
    work quickly has failed".
  - The scale-out at 1R and the stop beyond the sweep candle are common trading-education rules.

## For Jarvis

Boss asked for better Liquidity exits (R81, 18 Oct). Answer: none of 11 ideas beat the plain exits. The ideas were:
- index ATR trail, no-progress time stop, stop beyond the sweep bar;
- half off at +15%, re-entry after a stop, adding a lot to runners;
- 14:30 or 15:20 square-off, holding runners overnight with a GTT;
- breakeven after a structure confirmation, no premium stop.

The closest was dropping the -15% premium stop: it was never worse, but the gain rests on one trade. Liquidity keeps
its exits. On the newest data, BANKNIFTY and FINNIFTY were positive in every period, MIDCPNIFTY lost in the final
period, and NIFTY and SENSEX should not be added. BANKNIFTY's 26 Oct plain-rules record is unchanged.

Files: `research/hunt/r81/` (PREREG.md, r81rules.py, r81study.py, test_r81.py, results/), `research/experiments/entries/r81-*.yaml`.
