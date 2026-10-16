# R71: chart patterns on the index, buy the option (is there a new arm next to Donchian?)

16 Oct 2026. Research only: nothing in the app changes (no `android/**`). Pre-registered in `research/hunt/r71/PREREG.md`
(committed before any result, 8ae52e0); design picks frozen and committed before the final read (ec76c5b). Full tables:
`research/hunt/r71/results/report.md`.

Boss, 16 Oct: *"Now find something new like Donchian, find chart patterns, and provide me a good strategy back."*

## Answer in one paragraph

**Nothing beat the gates. There is no new chart-pattern arm to add; Donchian stays the only one.** Eight new mechanical
chart patterns were tested on the index (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), each buying the 1-ITM option
exactly the way Donchian takes its trades, with real charges and slippage, on the same days as Donchian. Six of the eight
already lost money in the design period (20 Nov 2024 - 31 Mar 2026). The two that made money there, the **squeeze
release** and the **swing structure (higher high + higher low)**, both **lost money in the final period** (1 Apr - 13 Oct
2026), while Donchian made Rs 55,439 on the same days. The squeeze is also mostly Donchian in disguise: two thirds of
its trades are the same trade as Donchian's. All 16 tests failed (BH q 0.016-0.99), so **nothing goes on paper**.

## The results (final period 1 Apr - 13 Oct 2026, 133 sessions, 1 lot each, net after all charges and slippage)

For each pattern: the original setting (fixed before any result) and the walk-forward re-tune's pick. "Twin p" compares
the rule with 500 random entries on the same days, index and side. "Same as Donchian" = trades with a Donchian trade on
the same index, day and side within 15 minutes.

| strategy (signal on the index) | setting | trades (a month) | net | win | avg win / loss | worst day | max DD | green days | design net | walk-fwd net | twin p | same as Donchian | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Donchian 20** (paper arm, reference) | -15% / +30% / 30 min | 1,787 (278) | **Rs 55,439** | 44% | Rs 1,566 / -Rs 1,188 | -Rs 23,012 | Rs 92,452 | 47% | Rs 8,281 | (R51 passed) | (R51 passed) | - | paper today |
| Donchian 20 with L25 lock | ladder + L25 | 1,781 (277) | Rs 81,845 | 44% | Rs 1,609 / -Rs 1,183 | -Rs 23,012 | Rs 91,626 | 47% | Rs 9,998 | | | - | (R66) |
| P1 Inside bar | 15-min, L25 | 1,075 (167) | -Rs 1,23,667 | 42% | Rs 1,170 / -Rs 1,058 | -Rs 11,375 | Rs 1,42,459 | 30% | -Rs 2,99,782 | = | 0.72 | 31% | fail |
| P1 re-tune | 30-min, -15/+30 | 403 (63) | -Rs 43,603 | 39% | Rs 1,278 / -Rs 993 | -Rs 8,929 | Rs 51,685 | 36% | -Rs 74,277 | -Rs 40,963 | 0.82 | 45% | fail |
| P2 Narrowest bar (NR7) | N 7, L25 | 1,341 (208) | -Rs 1,68,820 | 39% | Rs 1,291 / -Rs 1,037 | -Rs 21,758 | Rs 2,14,246 | 32% | -Rs 6,50,195 | = | 0.53 | 19% | fail |
| P2 re-tune | N 7, index stop | 1,286 (200) | -Rs 49,790 | 35% | Rs 1,987 / -Rs 1,144 | -Rs 21,694 | Rs 1,51,125 | 32% | -Rs 5,91,344 | -Rs 3,75,731 | 0.26 | 18% | fail |
| **P3 Squeeze release** | 6 bars, L25 | 305 (47) | -Rs 41,281 | 40% | Rs 1,315 / -Rs 1,089 | -Rs 11,394 | Rs 65,810 | 38% | **Rs 57,763** | **Rs 57,763** | 0.91 | **67%** | fail |
| P3 re-tune | 12 bars, -15/+30 | 152 (24) | -Rs 39,967 | 38% | Rs 1,198 / -Rs 1,164 | -Rs 9,694 | Rs 55,958 | 39% | Rs 63,228 | Rs 59,559 | 0.98 | 66% | fail |
| P4 Flag | pole 2 ATR, L25 | 32 (5) | -Rs 442 | 44% | Rs 1,147 / -Rs 917 | -Rs 2,618 | Rs 8,511 | 46% | -Rs 27,378 | = | 0.79 | 44% | fail (too few) |
| P4 re-tune | pole 2 ATR, index stop | 32 (5) | Rs 5,805 | 53% | Rs 1,527 / -Rs 1,344 | -Rs 3,946 | Rs 8,556 | 57% | -Rs 12,110 | -Rs 3,480 | 0.82 | 44% | fail (too few) |
| P5 Double bottom / top | 0.25 ATR, L25 | 502 (78) | -Rs 12,091 | 42% | Rs 1,321 / -Rs 1,000 | -Rs 10,553 | Rs 59,040 | 41% | -Rs 94,311 | = | 0.21 | 28% | fail |
| P5 re-tune | 0.25 ATR, -15/+30 | 502 (78) | -Rs 20,669 | 42% | Rs 1,301 / -Rs 999 | -Rs 9,030 | Rs 70,370 | 40% | -Rs 89,511 | -Rs 49,081 | 0.25 | 28% | fail |
| P6 Heikin-Ashi flip in trend | EMA 20, L25 | 357 (55) | -Rs 79,173 | 44% | Rs 1,243 / -Rs 1,385 | -Rs 17,305 | Rs 88,217 | 43% | -Rs 8,797 | = | 0.81 | 48% | fail |
| P6 re-tune | EMA 50, L25 | 323 (50) | -Rs 78,971 | 44% | Rs 1,240 / -Rs 1,395 | -Rs 26,160 | Rs 91,454 | 40% | -Rs 7,772 | -Rs 10,965 | 0.82 | 46% | fail |
| P7 Three-bar reversal at day high / low | from 09:45, L25 | 855 (133) | -Rs 1,67,689 | 38% | Rs 1,085 / -Rs 989 | -Rs 20,918 | Rs 1,82,990 | 39% | -Rs 3,21,930 | = | 0.002 | 4% | fail |
| P7 re-tune | from 10:30, index stop | 675 (105) | -Rs 83,990 | 35% | Rs 1,738 / -Rs 1,119 | -Rs 18,038 | Rs 1,02,893 | 43% | -Rs 1,11,487 | -Rs 1,96,420 | 0.002 | 5% | fail |
| **P8 Swing structure (HH + HL)** | 2-bar pivots, L25 | 374 (58) | -Rs 40,109 | 45% | Rs 1,260 / -Rs 1,223 | -Rs 13,949 | Rs 56,346 | 41% | Rs 2,320 | = | 0.88 | 41% | fail |
| P8 re-tune | 2-bar pivots, index stop | 372 (58) | -Rs 34,242 | 41% | Rs 1,854 / -Rs 1,437 | -Rs 13,045 | Rs 75,561 | 39% | **Rs 1,04,022** | **Rs 60,676** | 0.99 | 41% | fail |

("=": an original has no fitted setting, so its walk-forward is its design.) Gates (as Donchian's R51): final net > 0,
twin p < 0.05, BH q <= 0.10 across all 16, at least 40 trades, the top 5 trades < the net, design and walk-forward > 0.

## What the numbers say, pattern by pattern

- **Squeeze release (P3): looked good, then failed, and it copies Donchian.** Bollinger bands squeezed inside the Keltner
  channel for 6+ bars, then a close outside the band. Design +Rs 57,763 on 803 trades and positive in all four walk-forward
  quarters with 12 bars. Final: -Rs 41,281 on 305 trades, worse than random entries on the same days (twin p 0.91). And
  67% of its trades are the same trade Donchian already takes (a close beyond a band after a quiet spell is very often also
  a 20-bar breakout), so even if it had worked it would mostly have doubled Donchian's risk.
- **Swing structure (P8): the best design result, then lost.** Higher high + higher low on 15-minute swings, buy the
  break of the last swing high; with the stop on the index at the last swing low it made Rs 1,04,022 in design and Rs 60,676
  walk-forward. Final: -Rs 34,242 on 372 trades, while random entries on the same days and sides would have made
  Rs 41,639 on average (twin p 0.99). Timing the break was worse than luck in the final.
- **Three-bar reversal at the day's high / low (P7): smart, but still a loser.** It beat random timing hard (twin p 0.002:
  random entries on the same days and sides lost 2-4 times as much), so fading a fresh day extreme after a 3-bar turn is
  better than buying at a random minute. But it still lost Rs 84k-1.68 lakh in the final and Rs 1.1-3.2 lakh in design after
  option costs. A fact worth keeping, not a strategy.
- **Flag (P4): too rare to judge.** A strong 3-bar pole, a tight flag, then the break: only 93-133 trades in 16 months of
  design (-Rs 12k to -Rs 42k) and 32 in the final (+Rs 5,805 with the index stop, below random entries' +Rs 17,798).
- **Inside bar (P1), narrowest bar NR7 (P2), double bottom / top (P5), Heikin-Ashi flip (P6): clear losers** in design,
  walk-forward and final. NR7 and inside-bar breaks on 15-minute index bars fire about 8-10 times a session across the five
  indices and lose about Rs 40-130 a trade after costs.

## Compared with Donchian on the same days

- **No pattern beats Donchian.** Donchian XB made Rs 55,439 in the final (1,787 trades, 44% wins, worst day -Rs 23,012,
  max drawdown Rs 92,452, 47% green days, about 278 trades a month); with the L25 lock Rs 81,845. Every pattern except
  the 32-trade flag lost money on the same days.
- **Do they add or duplicate?** Daily correlation with Donchian is low for most (-0.14 to 0.35), so they would have added
  something different, but what they added was losses: Donchian + any pattern made less than Donchian alone in the final,
  except Donchian + the 32-trade flag (Rs 61,244 vs Rs 55,439, too few trades to mean anything). The squeeze (P3) is the
  one that duplicates (67% the same trades).

## Should anything go on paper?

**No.** Nothing passed the gates Donchian passed (none even made money in the final apart from the 32-trade flag), so per
the pre-registration nothing is proposed, and live is never touched. Donchian 20 stays the chart-pattern arm.

## Exact rules (for the record; none is recommended)

Every signal is read from the index (the underlying); the 1-ITM option is only what is bought. Trades as Donchian:
entries 09:20-15:10, one open a side, at most 2 a side a day, never on the index's expiry day. No option-chain data.

1. **P1 Inside bar**: a 15-min (30-min) bar inside the previous one of the same day; the first 5-min close beyond the previous bar within 30
   minutes; index stop: the other side of that bar.
2. **P2 NR7**: today's 15-min bar with the smallest range of the last 7 (4); the first 5-min close beyond it within 30
   minutes; index stop: its other side.
3. **P3 Squeeze release**: Bollinger(20, 2) inside Keltner(EMA20, 1.5 ATR20) on 5-min bars for 6 (12) bars in a row; within 6
   bars of the squeeze ending, the first close beyond a Bollinger band; index stop: the Bollinger middle.
4. **P4 Flag**: three 5-min bars with rising closes moving at least 2 (1.5) x ATR14; 3-10 flag bars that stay below the pole
   high and retrace at most half; the first close above the pole high; index stop: the flag's low.
5. **P5 Double bottom / top**: two 5-min swing lows today at least 4 bars apart and within 0.25 (0.5) ATR14, with a neckline
   at least 1 ATR above; the first close above the neckline; index stop: the lower low.
6. **P6 Heikin-Ashi flip**: a green 15-min HA bar with no lower wick after 2 red ones, the 15-min close above EMA 20 (50);
   index stop: the lowest low of the last 3 15-min bars.
7. **P7 Three-bar reversal**: a 5-min bar makes a new day low (from 09:45 / 10:30), the next makes no lower low, the third
   closes above the first bar's high with no new low; index stop: the first bar's low.
8. **P8 Swing structure**: 15-min swing points (2 / 3 bars each side): rising highs, rising lows, the last low after the last
   high; the first 5-min close above the last swing high; index stop: the last swing low.

Mirror for puts. Exits tested: XB (-15% / +30% / 30 min, Donchian's), XL (-15%, the 30/60/100 ladder plus +5% once +25%,
30 min), XI (the index stop above, -25% premium safety stop, ladder + L25, 60 min); square-off 15:15.

## How it was measured (same as Donchian, so the numbers compare)

R51's Amendment A clean stores of the Drive lake, R64's engine (one held contract, next-minute fill, R17 liquidity fills,
stops on the minute low with gap fills at the worse open, dated charges with STT 0.15% from 1 Apr 2026, lots in force,
1 lot). Design 20 Nov 2024 - 31 Mar 2026, walk-forward quarters Apr 2025 - Mar 2026 (each quarter traded with the setting
that was best before it), final 1 Apr - 13 Oct 2026 read once. Check that the plumbing is identical: Donchian priced by
this code gives exactly R66's numbers (design Rs 8,281 / Rs 9,998, final Rs 55,439 / Rs 81,845). Random-entry twins: 500
per test, seed 71. Benjamini-Hochberg across all 16 tests. Tests: `research/hunt/r71/test_r71.py` (38 tests, including a
look-ahead guard for every rule and setting).

## Boss's follow-up: "what if they had the 25% -> 5% stop rule, like Donchian?"

They already did. Exit XL is exactly Donchian's paper exit with Boss's L25 rule (stop -15%; once the option is +25%, the
stop moves to +5%; plus the 30/60/100 ladder; 30-minute time exit), and it is the **original** exit of every pattern
above. Each pattern with Donchian's plain exits (XB, no L25) next to it with L25 (XL), net Rs:

| strategy | design, plain (XB) | design, with L25 (XL) | final with L25: trades / net / win / worst day / max DD | trades the lock closed in the final |
|---|---|---|---|---|
| **Donchian 20** | Rs 8,281 | Rs 9,998 | 1,781 / **Rs 81,845** / 44% / -Rs 23,012 / Rs 91,626 | (R66: 43 by the +5% step) |
| P1 Inside bar | -Rs 2,93,054 | -Rs 2,99,782 | 1,075 / -Rs 1,23,667 / 42% / -Rs 11,375 / Rs 1,42,459 | 9 |
| P2 NR7 | -Rs 6,26,420 | -Rs 6,50,195 | 1,341 / -Rs 1,68,820 / 39% / -Rs 21,758 / Rs 2,14,246 | 6 |
| P3 Squeeze release | Rs 62,605 | Rs 57,763 | 305 / -Rs 41,281 / 40% / -Rs 11,394 / Rs 65,810 | 1 |
| P4 Flag | -Rs 24,519 | -Rs 27,378 | 32 / -Rs 442 / 44% / -Rs 2,618 / Rs 8,511 | 0 |
| P5 Double bottom / top | -Rs 89,511 | -Rs 94,311 | 502 / -Rs 12,091 / 42% / -Rs 10,553 / Rs 59,040 | 5 |
| P6 Heikin-Ashi flip | -Rs 35,153 | -Rs 8,797 | 357 / -Rs 79,173 / 44% / -Rs 17,305 / Rs 88,217 | 3 |
| P7 Three-bar reversal | -Rs 3,21,384 | -Rs 3,21,930 | 855 / -Rs 1,67,689 / 38% / -Rs 20,918 / Rs 1,82,990 | 7 |
| P8 Swing structure | -Rs 8,599 | Rs 2,320 | 374 / -Rs 40,109 / 45% / -Rs 13,949 / Rs 56,346 | 4 |

**What it says:** the L25 rule moves each pattern by only a few thousand rupees either way, because with a 30-minute time
exit few trades reach +25% and then fall back (0-9 lock exits per pattern in the final, about 1% of trades). It cannot
turn a losing entry into a winning one: the patterns lose because their entries are no better than random, not because
winners give back profit. With L25 every pattern still lost in the final, while Donchian with the same rule made
Rs 81,845. (A Donchian-channel trailing stop was also pre-registered as R71b, then withdrawn unrun once Boss clarified.)

## Where the code is narrower than the pre-registration's words (disclosed)

Found in the self-review after the final read. The code was written and committed (ec76c5b) before any profit or loss
was computed, and nothing was changed afterwards; the pre-registration's one-line descriptions were looser than the code:

- P1: the inside bar and its mother bar must be on the same day (no overnight mother bar).
- P3: the band break counts only within 6 five-minute bars of the squeeze ending, while the squeeze stays off.
- P7: the third bar must also make no new low (no new high for puts), as the first two.

Each only removes signals. The looser readings were not run, so the final period stays read once; whether they would
do better is not known.

## Honest limits

- The final window was already read for other ideas (R51-R70), and Donchian was chosen on it. It is fresh for these eight
  rules, but it is not a pristine holdout. Only paper trading from 14 Oct 2026 is truly unseen.
- These are mechanical versions of patterns that traders draw by eye; a different definition (another pivot width, flag
  shape or squeeze length) could behave differently. The grids were kept small on purpose (2 values each) so the result
  is not a product of searching.
- The index has no volume, so no VWAP or volume-confirmed pattern could be tested on the underlying.
- Gap patterns, opening-range and previous-day-level breaks were not re-tested: they already failed (list in PREREG
  section 0).
