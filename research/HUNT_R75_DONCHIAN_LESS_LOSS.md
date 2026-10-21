# R75: Donchian, less loss (the last Donchian round)

16 Oct 2026. Research only: nothing in the app changes (no `android/**`). Pre-registered in `research/hunt/r75/PREREG.md`
(committed 63daa3e before any result); design results frozen before the final read (680162e). Full tables:
`research/hunt/r75/results/report.md`.

Boss, 16 Oct: *"Should we make Donchian more powerful, less loss and more win rate?"* We agreed on one last focused round,
aimed at **less loss and a smaller drawdown**. Win rate was not a goal in itself: R64/R65 showed that taking profit early
raises the win rate but cuts total P&L.

## The answer

**No. None of the 40 tests passed, so nothing changes on paper.** We tried 20 loss-cutting rules on each of the two
Donchian books paper runs today:
- **B**: Donchian 20 with the Supertrend agreement.
- **C**: Breadth Donchian.

Every rule failed the pre-registered gate. Most rules that cut losses also cut about as much profit, and did no better
than skipping the same number of trades at random. A few looked good in one period but not in the others. The best
random-twin result (p 0.006) is only q 0.20 once you account for the 40 tests tried, and the gate needs q 0.10 or less.

**Recommendation: keep B and C exactly as they are on paper and freeze Donchian now.** Judge it on the paper record from
14 Oct. Do not re-tune it again: every extra study reads the same final window, which makes any "win" there less
trustworthy.

## The table (final period 1 Apr - 13 Oct 2026, 133 sessions, read once; 1 lot, net after all charges and slippage)

"Acct stop" = months worse than -Rs 13k / times the drawdown went deeper than Rs 40k. "Qtrs" = how many of the 4
walk-forward quarters the rule was better in (3 needed).

| book + rule | trades | net | win | avg win / loss | worst day | max DD | green days | acct stop | losers avoided / winners missed | design net | qtrs | twin p (q) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B Donchian + Supertrend (paper)** | 1,670 | Rs 1,34,743 | 45% | Rs 1,651 / -Rs 1,197 | -Rs 21,578 | Rs 81,683 | 46% | 1 / 2 | - | Rs 50,425 | - | - | baseline |
| B + L4-OR skip after 09:45 on a narrow opening | 776 | Rs 1,57,748 | 49% | Rs 1,765 / -Rs 1,280 | -Rs 14,064 | **Rs 38,693** | 53% | 1 / 0 | 523 (Rs 5.93 L) / 371 (Rs 5.70 L) | Rs 2,98,603 | 2 | 0.010 (0.20) | FAIL (quarters, BH) |
| B + L2-WEAK drop the weakest index | 1,300 | Rs 1,30,091 | 44% | Rs 1,640 / -Rs 1,114 | -Rs 15,131 | Rs 54,178 | 46% | 1 / 2 | 194 / 176 | Rs 1,13,555 | 3 | 0.23 (0.67) | FAIL (twin, BH) |
| B + L3-L60 no entries after 14:10 | 1,488 | Rs 1,63,112 | 46% | Rs 1,664 / -Rs 1,220 | -Rs 21,578 | Rs 64,903 | 47% | 1 / 1 | 119 / 63 | Rs 38,088 | 1 | 0.040 (0.30) | FAIL (design, quarters, BH) |
| B + L6-EXP skip all expiry days | 1,129 | Rs 1,41,733 | 46% | Rs 1,644 / -Rs 1,176 | -Rs 16,666 | Rs 71,640 | 46% | 1 / 2 | 313 / 228 | Rs 1,48,294 | 2 | 0.060 (0.30) | FAIL |
| B + L1-K2 stop after 2 losses in a row | 794 | Rs 82,774 | 46% | Rs 1,725 / -Rs 1,289 | -Rs 14,064 | Rs 69,279 | 44% | 1 / 2 | 494 / 382 | Rs 86,186 | 1 | 0.29 (0.77) | FAIL |
| B + L7-DD20 half size in a Rs 20k drawdown | 1,670 | Rs 26,311 | 44% | Rs 1,005 / -Rs 749 | -Rs 21,578 | Rs 62,695 | 45% | 2 / 2 | 761 losers halved | -Rs 91,208 | 0 | 0.93 (1.0) | FAIL |
| **C Breadth Donchian (paper)** | 1,202 | Rs 1,71,366 | 46% | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Rs 61,884 | 52% | 2 / 2 | - | Rs 2,16,929 | - | - | baseline |
| C + L1-X5 stop for the day at -Rs 5,000 | 1,020 | Rs 2,11,949 | 47% | Rs 1,765 / -Rs 1,177 | -Rs 14,064 | Rs 57,047 | 49% | 1 / 2 | 111 (Rs 1.57 L) / 71 (Rs 1.16 L) | Rs 1,85,757 | 0 | 0.006 (0.20) | FAIL (design, quarters, BH) |
| C + L3-L60 no entries after 14:10 | 1,024 | Rs 1,90,573 | 47% | Rs 1,790 / -Rs 1,251 | -Rs 20,556 | Rs 55,645 | 52% | 1 / 1 | 111 / 67 | Rs 1,86,023 | 1 | 0.034 (0.30) | FAIL (design, quarters, BH) |
| C + L4-OR skip after 09:45 on a narrow opening | 586 | Rs 1,45,476 | 50% | Rs 1,793 / -Rs 1,275 | -Rs 17,394 | Rs 43,796 | 54% | 1 / 1 | 356 / 260 | Rs 3,07,082 | 1 | 0.058 (0.30) | FAIL |
| C + L6-EXP skip all expiry days | 828 | Rs 1,28,302 | 47% | Rs 1,680 / -Rs 1,197 | -Rs 22,571 | Rs 54,212 | 49% | 0 / 1 | 212 / 162 | Rs 2,98,949 | 3 | 0.39 (0.79) | FAIL (final net -25%) |
| C + L1-K2 stop after 2 losses in a row | 751 | Rs 1,61,409 | 46% | Rs 1,834 / -Rs 1,183 | -Rs 14,064 | Rs 55,350 | 47% | 1 / 2 | 248 / 203 | Rs 1,54,346 | 1 | 0.048 (0.30) | FAIL |
| *A plain Donchian 20 (reference, not gated)* | 1,781 | Rs 81,845 | 44% | Rs 1,609 / -Rs 1,183 | -Rs 23,012 | Rs 91,626 | 47% | 1 / 1 | - | Rs 9,998 | - | - | - |

The other 26 tests (time-of-day starts, VIX, prior-day range, ATM / 2-ITM strikes, gap days, volatility and drawdown
sizing) were worse. They are all in `results/report.md` and in `research/experiments/entries/r75-*.yaml`.

## What we learned

- **Daily loss caps (L1) don't help.** On C, the -Rs 5k cap looked great in the final (Rs 2.12 lakh vs Rs 1.71 lakh),
  but it was not better in design or in any of the 4 design quarters. The trades a cap skips are not mostly losers. For
  example, "2 losses in a row" on B skipped 1,260 losers (Rs 14.6 lakh) but also 928 winners (Rs 14.3 lakh) in design;
  on C it missed more in winners (Rs 7.96 lakh) than it saved in losers (Rs 7.34 lakh).
- **Cutting size in a drawdown or when volatility is high (L7) is the worst idea here.** Half size during a Rs 20k
  drawdown turned B's design Rs 50k into -Rs 91k. That means the trades taken while the book was down made about
  Rs 2.8 lakh at full size: Donchian earned much of its money in the recoveries, and half size removes them.
- **Time of day (L3).** Skipping the first 10 or 25 minutes loses badly. On B, no entries before 09:30 skipped
  winners worth Rs 10.9 lakh to avoid losers worth Rs 9.2 lakh in design. The early breaks pay. Skipping the last hour helped in the final (B Rs 1.63 lakh, C Rs 1.91 lakh) but not in design, and in only 1
  quarter of 4. That looks like luck in the recent months, not a rule.
- **The opening-range chop filter (L4-OR) came closest.** The rule: if an index's first 30 minutes (09:15-09:44) moved
  less than half its normal daily range, take no Donchian trade on it after 09:45.
  - On B it halved the final drawdown (Rs 38.7k vs Rs 81.7k), made more money (Rs 1.58 lakh vs Rs 1.35 lakh) and beat
    random skipping (p 0.010). Design was strong too (Rs 2.99 lakh vs Rs 50k, drawdown Rs 1.10 lakh vs Rs 2.14 lakh).
  - But it was better in only 2 of the 4 walk-forward quarters. In Apr-Jun 2025 it made Rs 1.6k vs Rs 13.8k, and in
    Jan-Mar 2026 Rs 97k vs Rs 1.20 lakh, a give-up beyond the 10% allowed. It also failed BH (q 0.20).
  - On C it failed outright: the final made Rs 1.45 lakh vs Rs 1.71 lakh.
  - It stays a **note only**, as the rules require for anything that failed the gates. If it is real, the paper record of
    narrow-opening days will show it with no extra study.
- **Dropping the weakest index (L2-WEAK) on B** was better in design and in 3 of 4 quarters, and lowered the final
  drawdown (Rs 54k vs Rs 82k) at almost the same P&L. But it did no better than dropping the same number of trades at
  random (p 0.23). The lower drawdown comes from trading less, not from picking the right index.
- **ATM vs 2-ITM (L5).** 1-ITM (today's rule) stays.
  - ATM was worse on B (final Rs 86k vs Rs 1.35 lakh). On C it made a little more in the final (Rs 1.84 lakh) but with a
    bigger drawdown (Rs 78k vs Rs 62k), and was better in only 2 quarters.
  - 2-ITM made more on B in the final, but lost money in design and had a -Rs 1.01 lakh worst design day.
- **Every book still breaks the account stop at 1 lot on all five indices.**
  - B: 1 month worse than -Rs 13k and 2 drawdowns deeper than Rs 40k in the final; 8 months and 3 such drawdowns in design.
  - C: 2 months and 2 drawdowns in the final; 5 months and 5 drawdowns in design.
  - None of the levers fixed that while keeping the edge. The real way to meet the -Rs 40k / -Rs 13k stop is size: fewer
    indices or fewer lots. That is a separate live-sizing decision for Boss, not a filter.

## Exact rules that passed

**None.** B (Donchian 20 with Supertrend) and C (Breadth Donchian) stay exactly as they are on paper today:
- 1-ITM, entries 09:20-15:10;
- stop -15%, the 30/60/100 ladder plus L25, out after 30 minutes, square-off 15:15;
- never on the index's own expiry day.

No paper change is proposed, so nothing goes in the Autopilot timeline.

## Recommendation: freeze Donchian

R51 picked Donchian. R63-R69 tested exits and locks, R72 tested breadth, R73 trend filters and waits, and R75 these 40
loss levers. The final window (Apr-Oct 2026) has now been read by every one of those studies, so it can no longer tell a
real improvement from a lucky one. **Freeze both Donchian books as they are and judge them only on paper from 14 Oct.**
A useful review point is after about 200 paper trades per book. Stop a book if its paper net is below zero or below what
twice the spread would cost. Running B and C side by side on paper, as now, is also the cleanest test of Breadth vs
Supertrend.

## How it was measured

- **Engine and data:** R64's engine on R51's Amendment A clean stores, with one held contract, next-minute fills, R17
  liquidity fills, stops on the minute low with gap fills at the worse open, and dated charges (STT 0.15% from 1 Apr 2026).
  Lots are those in force on each date, 1 lot per trade.
- **Same trade-taking as paper:** Donchian's rules exactly (one open position a side, at most 2 entries a side a day).
- **Baselines checked:** all three reproduce the published numbers to the rupee:
  - A: design Rs 9,998, final Rs 81,845;
  - B: Rs 50,425 / Rs 1,34,743;
  - C: Rs 2,16,929 / Rs 1,71,366.
- **Signal source:** every signal is on the index, and every lever reads the index, India VIX, the arm's own closed
  trades or the calendar. No rule reads an option's chart.
- **Gate:** all five parts had to hold.
  1. Better (more P&L with no more than 10% extra drawdown, or a smaller drawdown or worst day while giving up at most
     10% or Rs 5k of P&L) in design, in at least 3 of 4 walk-forward quarters, and in the final.
  2. Beat 500 random twins that skip (or halve) the same number of trades, p < 0.05. For the strike lever the twin was a
     sign-flip of the daily differences.
  3. BH q <= 0.10 across all 40 tests.
  4. At least 40 trades affected.
  5. Still better at twice the slippage.
- **Tests:** `research/hunt/r75/test_r75.py` (24 tests). It has a look-ahead guard for every lever: changing anything at or
  after the decision moment never changes the decision. It also checks each lever on hand-made data, the gate, twin and
  breach maths, and that the baselines reproduce.
- **Already tested, so not repeated:** the ADX chop filter (R73 T5/T6, failed); skipping the traded index's own expiry
  day (already a Donchian rule); and exits, locks, trails, trend filters and waits (R63-R69, R73). ATM vs 1-ITM was only
  tested in R51 under the old exits, so it was run again here, with 2-ITM.

## Honest limits

- **The final window is reused, and we disclose it.** R51-R73 had all read it, so the gate was stricter than usual, and
  even so the single-period "wins" (L1-X5 and L3-L60 on C, L3-L60 on B) were treated as unproven.
- **Half size is only real with 2 lots.** The half-size rules (L6-GAPH, L7) are priced at 0.5 lot. At 1 lot on paper,
  "half" can only mean skip. That is moot here because none passed.
- **Some decisions use earlier data only.** L2's design result picks its indices in-sample, which is flagged. Its
  quarter and final results use only earlier data. L7-DD's drawdown counter starts at zero at the start of each period.
- **The study code:** `research/hunt/r75/r73rules.py` is R73's file copied verbatim (origin/claude/r73-donchian-trend-wait
  9a2c911), used read-only because R73 is not on integ yet.

## To re-run

```
rclone copy gdrive:IraAlgo-data-lake/index_1m $IRAALGO_LAKE --include "index_1m_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX,INDIAVIX}_202[456].parquet"
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m $IRAALGO_LAKE --include "opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX}_WEEK_202[456].parquet"
R51_CLEAN=1 python research/hunt/r51/r51data.py build
python research/hunt/r75/r75study.py design      # then `final` (refuses a second read), then `report`
python research/hunt/r75/r75entries.py
```
