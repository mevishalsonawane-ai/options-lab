# R202 + R203: sell when the trend turns, and other ways to lose less (R72, R51, Liquidity 15+5) (20 Nov 2026)

Boss, 20 Nov: *"check that if any trend is change in between. Means if I have already bought a trade and trend changes, or
if the agent sees that the market is in the opposite direction, we should sell the trades automatically without any
permission. And also I want to check if there are any more enhancements we could do to reduce the losses of the R51, R72
and liquidity."*

Pre-registered in `research/hunt/r202_trend_exit/PREREG.md` (commit 13a3351b, before any result). Code and design results
frozen in ddd6ad57 before the one holdout read. Code: `research/hunt/r202_trend_exit/`. Every table:
`results/report.md`; numbers: `results/design.json`, `results/final.json`, `results/decision.json`. Research only:
**nothing in the app changed.**

## Verdict for Boss (read this first)

**Nothing passed. 0 of 60 tests clear the research gates** (27 trend-turn exits, 33 other loss-cutting ideas).

1. **Selling when the trend turns does not help these three arms. It costs money.** No trend exit made more money than
   today's rules in both periods on any arm. The four that fire often (back through the entry level, the day's average
   price, breadth, NIFTY's trend) lost between ₹1,500 and ₹56,000 in each period on each arm (one tie: Liquidity, day's
   average price, holdout). They do make the average loss smaller (R72, back through the entry level, holdout: from
   -₹1,475 to -₹1,022 a losing trade), but they turn many winners into small losers: on R72 and R51 the win rate falls
   by 3 to 9 points, and the money given up on winners is bigger than the money saved on losers (same rule: 245 trades
   changed, ₹88,971 saved on losers, ₹1,06,923 given up on winners). Cutting the same trades at **random** minutes did at
   least as well almost every time (twin p near 1.0). The other five almost never fire (point 2).
2. **Why: the arms are already out before a trend can turn.** With today's rules every R72 and R51 trade is closed within
   30 minutes (half within 14-15 minutes; winners in about 10). Liquidity's typical trade lasts 10 minutes. An opposite
   Donchian or breadth signal arrived while a trade was still open only 0 to 3 times in the 16 design months on R72 /
   R51, and 10 times on Liquidity. The breakeven at +10%, the step lock and the -15% stop already do the job; a second "trend"
   opinion on a 15-minute trade is mostly noise.
3. **The other loss-cutting ideas also fail.** None was better than today's rules in both periods. Skipping the
   "worst" hour, weekday, VIX regime, gap size, volatility bucket or trend bucket, each chosen only from the past, mostly
   lost money afterwards (the bucket that was worst in the past is often not the worst later). A -10% stop instead of
   -15% lost ₹40,000 to ₹64,000 in the design period on every arm.
4. **Three ideas are worth watching, not building:**
   - **A day loss cap on R72** (no new R72 entry once R72 is down ₹3,000 closed that day): in Apr - Oct 2026 it made
     ₹35,723 more, cut the max drawdown by ₹12,606 and the worst month from -₹13,690 to -₹1,875, and beat random skips
     (p 0.001). But in Nov 2024 - Mar 2026 the same rule **lost** ₹34,904 (after a bad start R72 used to recover the same
     day). One good half-year after a bad 16 months is not evidence. Fails.
   - **R51 without SENSEX** (the walk-forward "skip the worst index" picked SENSEX in every quarter): +₹1,752 in design,
     +₹7,675 in the holdout, drawdown ₹15,007 smaller, worst month -₹29,871 to -₹14,841, better in 3 of 4 quarters. It
     fails only the statistics (random skips did as well 17% of the time). The closest to a pass.
   - **R51, stop after two losers in a row on an index**: +₹16,103 design, +₹2,230 holdout, but only 15 trades changed
     in the holdout and the drawdown did not fall. Fails.

**My recommendation: build none of them.** No trend exit, on paper or live. If you want something, the only honest step
is to **log** (shadow, no action) the R72 day loss cap and "R51 without SENSEX" so they can be judged on new days; that
needs your OK because it is app work. A failed idea can act only as your explicit exception, as before.

## What was tested, and how

- **Baseline = what the arms run today (V5)**, 1 lot, after real charges and slippage. R72 / R51: the picker, the -15%
  stop, the ladder, the 30-minute time stop, breakeven at +10%, the ₹500 step lock from +₹1,000. Liquidity: its own exits,
  breakeven at +10% or +₹1,000.
- Every idea is added **on top**; the first exit wins. Every trend signal is read on the **index** (never on the option's
  own chart), on a completed bar that began after the entry, and the option is sold at the next minute's open.
- **Design** 21 Nov 2024 - 31 Mar 2026 (with four walk-forward quarters); **holdout** 1 Apr - 5 Oct 2026, read once.
- **The gate** (fixed before any number): better than today's rule in design and in the holdout (more money, or within 5%
  with drawdown and worst month 20% smaller), better in 3 of 4 walk-forward quarters, no bigger drawdown, at least 40
  trades changed in each period, better than 2,000 random twins (p < 0.05), and Benjamini-Hochberg over all 4,868 ideas
  logged so far.
- **Data, a limit you should know:** this session could not copy the Drive lake (not permitted here) and the Dhan token
  has expired, so the study ran on the `dhan-data` branch (the same Dhan feed, fetched 6 Oct). **It ends on 5 Oct 2026,
  not 13 Nov.** It reproduces R193's published months within 2.5% (R72 and R51: 21 of 22 months to the rupee). The last
  year here is 24 Nov 2025 - 5 Oct 2026: R72 938 trades, 64.8% wins, ₹1,95,249, worst month -₹13,690; R51 1,165 trades,
  63.1%, ₹1,43,844, worst month -₹29,871; Liquidity 590 trades, 44.4%, ₹1,06,212, worst month -₹32,670 (your 13 Nov
  baseline: 1,099 / ₹2,17,628; 1,355 / ₹1,78,958; 675 / ₹97,384).
- The holdout is locked for these ideas only: the baseline's own numbers on it were read by earlier studies, and today's
  rules were designed on Oct 2025 - Oct 2026.

## Study A (R202): sell when the trend turns

"Money" = the change against today's rule over the period (+ is better). For max drawdown a minus is better; for worst
month a plus is better. "Twin p" = how often random cuts of the same trades did at least as well (small is good; 1.000 =
random was always at least as good).
**R72 Breadth Donchian** (today's rule: design 1,561 trades, ₹232,769; holdout 596 trades, 60.4% wins, ₹98,609, average loss -₹1,475, max drawdown ₹36,477, worst month -₹13,690)

| idea | trades it changed (design / holdout) | money, design | money, holdout | holdout win rate | holdout average loss | holdout max drawdown | holdout worst month | better than random? (twin p) | gate |
|---|---|---|---|---|---|---|---|---|---|
| X1 Opposite Donchian-20 break | 2 / 0 | +₹1,333 | +₹0 | 60.4% | -₹1,475 | +₹0 | +₹0 | 1.000 | fail |
| X2 Opposite R72 breadth break | 0 / 0 | +₹0 | +₹0 | 60.4% | -₹1,475 | +₹0 | +₹0 | 1.000 | fail |
| X3 Opposite signal from any arm | 2 / 0 | +₹1,333 | +₹0 | 60.4% | -₹1,475 | +₹0 | +₹0 | 1.000 | fail |
| X4 Back through the entry level | 471 / 245 | −₹45,938 | −₹16,265 | 51.3% | -₹1,022 | −₹1,338 | −₹7,814 | 1.000 | fail |
| X5 5-min EMA20 against it | 38 / 14 | −₹6,726 | +₹3,133 | 60.4% | -₹1,461 | −₹172 | −₹744 | 1.000 | fail |
| X6 Day's average price against it | 130 / 102 | −₹56,448 | −₹5,477 | 56.3% | -₹1,273 | +₹162 | −₹12,865 | 1.000 | fail |
| X7 5-min Supertrend flips | 9 / 0 | +₹1,901 | +₹0 | 60.4% | -₹1,475 | +₹0 | +₹0 | 1.000 | fail |
| X8 Breadth turns (4 of 5 indices) | 89 / 88 | −₹48,058 | −₹16,413 | 57.1% | -₹1,319 | +₹5,392 | −₹16,094 | 1.000 | fail |
| X9 NIFTY 15-min EMA20 against it | 128 / 60 | −₹26,492 | −₹35,676 | 57.6% | -₹1,373 | −₹1,734 | −₹8,181 | 0.930 | fail |

**R51 Donchian 20 + Supertrend** (today's rule: design 2,170 trades, ₹147,686; holdout 652 trades, 60.4% wins, ₹43,680, average loss -₹1,429, max drawdown ₹45,825, worst month -₹29,871)

| idea | trades it changed (design / holdout) | money, design | money, holdout | holdout win rate | holdout average loss | holdout max drawdown | holdout worst month | better than random? (twin p) | gate |
|---|---|---|---|---|---|---|---|---|---|
| X1 Opposite Donchian-20 break | 3 / 1 | −₹5,182 | +₹120 | 60.4% | -₹1,428 | +₹0 | +₹0 | 0.700 | fail |
| X2 Opposite R72 breadth break | 1 / 1 | −₹6,466 | +₹562 | 60.4% | -₹1,427 | +₹0 | +₹0 | 0.411 | fail |
| X3 Opposite signal from any arm | 3 / 1 | −₹5,182 | +₹562 | 60.4% | -₹1,427 | +₹0 | +₹0 | 0.401 | fail |
| X4 Back through the entry level | 721 / 274 | −₹50,030 | −₹38,947 | 51.1% | -₹1,063 | +₹5,512 | −₹4,689 | 1.000 | fail |
| X5 5-min EMA20 against it | 64 / 21 | −₹17,132 | +₹1,702 | 60.4% | -₹1,422 | −₹1,457 | +₹1,457 | 1.000 | fail |
| X6 Day's average price against it | 231 / 123 | −₹47,442 | −₹19,460 | 56.0% | -₹1,259 | +₹1,446 | −₹1,374 | 1.000 | fail |
| X7 5-min Supertrend flips | 17 / 5 | −₹8,959 | +₹235 | 60.4% | -₹1,428 | +₹0 | +₹0 | 0.995 | fail |
| X8 Breadth turns (4 of 5 indices) | 192 / 118 | −₹48,101 | −₹29,716 | 57.0% | -₹1,295 | +₹5,981 | −₹5,185 | 1.000 | fail |
| X9 NIFTY 15-min EMA20 against it | 281 / 98 | −₹45,634 | −₹17,396 | 57.4% | -₹1,281 | +₹2,235 | −₹1,281 | 0.982 | fail |

**Liquidity 15+5** (today's rule: design 878 trades, ₹231,580; holdout 330 trades, 43.9% wins, ₹11,087, average loss -₹913, max drawdown ₹59,441, worst month -₹32,670)

| idea | trades it changed (design / holdout) | money, design | money, holdout | holdout win rate | holdout average loss | holdout max drawdown | holdout worst month | better than random? (twin p) | gate |
|---|---|---|---|---|---|---|---|---|---|
| X1 Opposite Donchian-20 break | 10 / 0 | −₹16,665 | +₹0 | 43.9% | -₹913 | +₹0 | +₹0 | 1.000 | fail |
| X2 Opposite R72 breadth break | 7 / 0 | −₹14,580 | +₹0 | 43.9% | -₹913 | +₹0 | +₹0 | 1.000 | fail |
| X3 Opposite signal from any arm | 10 / 0 | −₹16,665 | +₹0 | 43.9% | -₹913 | +₹0 | +₹0 | 1.000 | fail |
| X4 Back through the entry level | 110 / 52 | −₹37,366 | −₹3,089 | 41.2% | -₹828 | −₹3,662 | +₹5,324 | 0.997 | fail |
| X5 5-min EMA20 against it | 22 / 7 | +₹1,029 | −₹10,327 | 44.2% | -₹918 | +₹3,941 | +₹0 | 0.954 | fail |
| X6 Day's average price against it | 35 / 28 | −₹43,700 | +₹8 | 43.3% | -₹884 | −₹2,211 | +₹4,285 | 0.976 | fail |
| X7 5-min Supertrend flips | 13 / 2 | −₹16,764 | −₹404 | 43.9% | -₹913 | +₹0 | +₹0 | 0.587 | fail |
| X8 Breadth turns (4 of 5 indices) | 31 / 22 | −₹29,793 | −₹12,570 | 43.0% | -₹883 | +₹1,175 | +₹348 | 0.957 | fail |
| X9 NIFTY 15-min EMA20 against it | 27 / 6 | −₹32,058 | −₹1,493 | 43.9% | -₹908 | +₹252 | −₹252 | 0.434 | fail |

What each rule means: X1 R51's own signal the other way on the same index; X2 an R72 breadth break the other way; X3 an
opposite signal from any of the three arms; X4 two 5-minute closes back through the index level at entry; X5 two 5-minute
closes on the wrong side of EMA20; X6 two 5-minute closes on the wrong side of the day's average price; X7 the 5-minute
Supertrend(10, 3) flips; X8 on two 5-minute bars at least 4 of the 5 indices are on the wrong side of their day's
average; X9 a 15-minute NIFTY close on the wrong side of its EMA20 ("the market is the other way").

This agrees with R63 (16 Oct), which found the same on Liquidity and R51 with their older exits.

## Study B (R203): other ways to lose less
**R72 Breadth Donchian** (today's rule: design 1,561 trades, ₹232,769; holdout 596 trades, 60.4% wins, ₹98,609, average loss -₹1,475, max drawdown ₹36,477, worst month -₹13,690)

| idea | trades it changed (design / holdout) | money, design | money, holdout | holdout win rate | holdout average loss | holdout max drawdown | holdout worst month | better than random? (twin p) | gate |
|---|---|---|---|---|---|---|---|---|---|
| W-hour Skip the worst entry hour | 122 / 85 | +₹2,208 | −₹6,111 | 60.9% | -₹1,454 | +₹10,838 | −₹16,413 | 0.311 | fail |
| W-vix Skip the worst VIX regime | 401 / 0 | −₹156,121 | +₹0 | 60.4% | -₹1,475 | +₹0 | +₹0 | 1.000 | fail |
| W-gap Skip the worst gap size | 274 / 46 | −₹112,347 | −₹43,238 | 58.9% | -₹1,504 | +₹0 | +₹0 | 0.996 | fail |
| W-dow Skip the worst weekday | 184 / 132 | −₹95,284 | −₹46,868 | 60.3% | -₹1,462 | +₹10,394 | −₹10,356 | 0.905 | fail |
| W-rv Skip the worst volatility third | 446 / 122 | −₹182,948 | −₹82,351 | 58.0% | -₹1,542 | +₹0 | +₹0 | 1.000 | fail |
| W-und Skip the worst index | 114 / 0 | −₹42,191 | +₹0 | 60.4% | -₹1,475 | +₹0 | +₹0 | 1.000 | fail |
| W-trend Skip the worst trend-strength bucket | 254 / 0 | −₹57,769 | +₹0 | 60.4% | -₹1,475 | +₹0 | +₹0 | 1.000 | fail |
| S10 Stop at −10% instead of −15% | 358 / 130 | −₹47,086 | +₹2,343 | 57.6% | -₹1,306 | +₹249 | +₹3,441 | 1.000 | fail |
| DC3 Day loss cap ₹3,000 | 188 / 67 | −₹34,904 | +₹35,723 | 62.2% | -₹1,433 | −₹12,606 | +₹11,815 | 0.001 | fail |
| DC5 Day loss cap ₹5,000 | 134 / 37 | −₹39,510 | +₹26,389 | 61.7% | -₹1,448 | −₹13,049 | +₹8,283 | 0.001 | fail |
| L2 Stop after two losers in a row | 25 / 7 | −₹706 | +₹6,723 | 60.6% | -₹1,463 | −₹5,236 | +₹2,790 | 0.044 | fail |

**R51 Donchian 20 + Supertrend** (today's rule: design 2,170 trades, ₹147,686; holdout 652 trades, 60.4% wins, ₹43,680, average loss -₹1,429, max drawdown ₹45,825, worst month -₹29,871)

| idea | trades it changed (design / holdout) | money, design | money, holdout | holdout win rate | holdout average loss | holdout max drawdown | holdout worst month | better than random? (twin p) | gate |
|---|---|---|---|---|---|---|---|---|---|
| W-hour Skip the worst entry hour | 163 / 98 | −₹35,737 | +₹9,315 | 61.4% | -₹1,441 | −₹6,233 | +₹6,233 | 0.140 | fail |
| W-vix Skip the worst VIX regime | 767 / 0 | −₹34,629 | +₹0 | 60.4% | -₹1,429 | +₹0 | +₹0 | 1.000 | fail |
| W-gap Skip the worst gap size | 308 / 49 | −₹60,158 | −₹7,682 | 60.2% | -₹1,467 | +₹0 | +₹0 | 0.656 | fail |
| W-dow Skip the worst weekday | 259 / 131 | −₹3,280 | +₹15,760 | 61.4% | -₹1,369 | −₹5,578 | +₹2,240 | 0.076 | fail |
| W-rv Skip the worst volatility third | 593 / 148 | −₹64,417 | −₹55,677 | 59.1% | -₹1,553 | +₹0 | +₹0 | 0.995 | fail |
| W-und Skip the worst index | 281 / 121 | +₹1,752 | +₹7,675 | 61.4% | -₹1,512 | −₹15,007 | +₹15,030 | 0.166 | fail |
| W-trend Skip the worst trend-strength bucket | 264 / 0 | −₹61,138 | +₹0 | 60.4% | -₹1,429 | +₹0 | +₹0 | 1.000 | fail |
| S10 Stop at −10% instead of −15% | 522 / 143 | −₹39,886 | −₹20,031 | 56.8% | -₹1,309 | +₹2,438 | −₹1,197 | 1.000 | fail |
| DC3 Day loss cap ₹3,000 | 319 / 81 | −₹78,911 | +₹11,744 | 61.1% | -₹1,416 | −₹8,563 | +₹8,563 | 0.111 | fail |
| DC5 Day loss cap ₹5,000 | 207 / 45 | −₹59,231 | +₹11,834 | 61.0% | -₹1,420 | −₹8,979 | +₹8,980 | 0.076 | fail |
| L2 Stop after two losers in a row | 43 / 15 | +₹16,103 | +₹2,230 | 60.6% | -₹1,430 | +₹629 | −₹628 | 0.275 | fail |

**Liquidity 15+5** (today's rule: design 878 trades, ₹231,580; holdout 330 trades, 43.9% wins, ₹11,087, average loss -₹913, max drawdown ₹59,441, worst month -₹32,670)

| idea | trades it changed (design / holdout) | money, design | money, holdout | holdout win rate | holdout average loss | holdout max drawdown | holdout worst month | better than random? (twin p) | gate |
|---|---|---|---|---|---|---|---|---|---|
| W-hour Skip the worst entry hour | 47 / 45 | +₹11,688 | −₹302 | 44.2% | -₹948 | −₹7,504 | +₹1,327 | 0.487 | fail |
| W-vix Skip the worst VIX regime | 0 / 0 | +₹0 | +₹0 | 43.9% | -₹913 | +₹0 | +₹0 | 1.000 | fail |
| W-gap Skip the worst gap size | 0 / 0 | +₹0 | +₹0 | 43.9% | -₹913 | +₹0 | +₹0 | 1.000 | fail |
| W-dow Skip the worst weekday | 66 / 64 | −₹6,738 | +₹5,737 | 45.5% | -₹913 | −₹15,354 | +₹11,089 | 0.318 | fail |
| W-rv Skip the worst volatility third | 0 / 0 | +₹0 | +₹0 | 43.9% | -₹913 | +₹0 | +₹0 | 1.000 | fail |
| W-und Skip the worst index | 227 / 0 | −₹59,441 | +₹0 | 43.9% | -₹913 | +₹0 | +₹0 | 1.000 | fail |
| W-trend Skip the worst trend-strength bucket | 38 / 51 | −₹2,711 | −₹8,659 | 44.1% | -₹951 | +₹0 | +₹0 | 0.707 | fail |
| S10 Stop at −10% instead of −15% | 46 / 20 | −₹63,697 | +₹4,084 | 43.3% | -₹881 | −₹3,566 | +₹2,891 | 1.000 | fail |
| DC3 Day loss cap ₹3,000 | 28 / 15 | +₹6,972 | −₹3,162 | 43.2% | -₹921 | −₹2,947 | +₹2,189 | 0.726 | fail |
| DC5 Day loss cap ₹5,000 | 4 / 6 | +₹1,967 | +₹2,189 | 43.8% | -₹914 | −₹2,189 | +₹2,189 | 0.345 | fail |
| L2 Stop after two losers in a row | 15 / 12 | −₹1,492 | −₹10,092 | 43.4% | -₹919 | −₹2,846 | +₹1,300 | 0.917 | fail |

The "skip the worst ..." rules are walk-forward: each quarter skips the bucket that lost most on the arm's trades
**before** that quarter (at least 60 trades, losing on average), so nothing is chosen with hindsight. What they picked:
R72's worst hour was always 11:00-11:59; R51's worst index was always SENSEX and its worst weekday Thursday (once
Monday); Liquidity's worst hour moved from 13:00 to 11:00 and its worst weekday was Wednesday.

Not repeated here because earlier studies already tested them: rupee stops, step trails, targets and locks (R176, R179,
R186), the "no progress" exit and bad-break filters (R142, R81), the wide-day skip (R193), support / resistance (R194).

## What this means for the rules

- An exit that only sells earlier, or a skip, lowers risk, so one that **passed** could have gone to paper and, through
  every existing live gate, to live. None passed, so nothing changes by itself.
- Honest limits: one market regime (Nov 2024 on), 1 lot, minute bars (a real fill can be worse in a fast market), and
  the data stop on 5 Oct. When the Drive lake can be copied again, the same code can be re-run to 13 Nov
  (`r202data.py` is the only part to swap); that would be a second look, not a new holdout.

## For Boss to decide

1. Build nothing (my recommendation), or
2. log the R72 day loss cap (₹3,000) and "R51 without SENSEX" in **shadow** (logged only, no trade changes) for a few
   months of new days, or
3. make one of them your exception. If so: which, and on paper, live or both?
