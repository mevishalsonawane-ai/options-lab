# R78: "Gautam Jha's Daily High/Low Reversal" on XAUUSD gold

*18 Oct 2026. Research only; nothing in the app changes. Pre-registration: `research/hunt/r78/PREREG.md` (commit
33239c3, pushed before any result). Code: `research/hunt/r78/` (engine `r78engine.py`, runner `r78study.py`, tests
`test_r78.py`). Results: `research/hunt/r78/results/design.json`, `final.json` (final window read once:
`results/FINAL_READ`).*

## The answer for Boss

**No. The Daily High/Low Reversal does not work on gold after costs, as written or in any of the 7 variations, long,
short or both together.** None of the 24 lines (8 versions x long / short / combined) passed.

- **As Boss described it (V1, both sides):** 353 trades over 2.5 years of design data (about 12 a month), 30% winners,
  **-$9,239** a lot. Then **-$10,710** on 75 trades in the unseen final window (Apr - mid-Oct 2026).
- **Shorts** (break above yesterday's high, a red candle, a close below its low) lost in every version, every period.
- **Longs** looked promising in design: four versions made +$11k to +$13k a lot on 122-170 trades (the 1.5R target, V5:
  +$13,371 on 156 trades). But with 24 lines tried, that is not enough evidence (best BH q = 0.17, the bar is 0.10), and
  **every long version lost in the final window** (-$3,951 to -$18,789 a lot on 33-51 trades). Much of the long design
  profit came from one quarter, Jan-Mar 2026 (V2: all of it; V5 and V7: most of it).
- Why it fails: the stop under the green candle's low is tight (typically $1.60-$5 an ounce), so 60-80% of trades are
  stopped out, and gold's $0.30 spread plus commission is a large part of each small stop. Winners pay 1.6-3 times the
  losers, which is not enough at a 20-40% hit rate.
- The profit lock (V7) cut drawdowns but did not fix it: long design +$11,831 / final -$3,951.

Verdict: **rejected** (note only, no paper arm). Signal source: XAUUSD itself (spot gold 1-minute and daily bars).

## What was tested (pinned before any result)

- **Data:** Dukascopy XAUUSD 1-minute BID, 2 Oct 2023 - 16 Oct 2026 (the `research-data` release file used by every
  GOLD_* study and R53, plus 98 real Dukascopy day files for the release's missing weekdays and 1-16 Oct 2026, decoded by
  R53's own decoder; three Good Fridays are empty at the source and stay missing, exactly as R53 found). 872,575 design
  minutes, 191,686 final minutes. Nothing invented or forward-filled. The Drive lake's `xauusd_1m/` folder holds only its
  README and event calendar, not the minute data, so it was not used.
- **Daily candle: the UTC calendar day (00:00-24:00 UTC), Mon-Fri**, the definition of the earlier gold studies and of
  the app's Gold paper arms. Not the broker / New York 17:00 day. Monday uses Friday's candle; a missing previous
  weekday means no trade.
- **Rule (long; short is the mirror):** a 1-minute close below yesterday's low (fresh cross, bar starting 07:00-18:59
  UTC); the first green candle within K bars; the next candle closes above the green candle's high; buy at the next
  bar's open (by 19:00 UTC). Stop $0.10 under the lowest low from the break to the green candle. Target = the highest
  high of the 60 bars up to the break (where the down move started). Out by 20:40 UTC; never held overnight. One trade
  per side per day. Skipped: stops under $0.50 and targets with no room.
- **Costs:** ask = bid + $0.30, $7 a lot round trip, $0.05 an ounce slippage on every stop fill. Money is USD per
  1 standard lot (100 oz).
- **Variants:** V1 Boss as written (K 3); V2 green candle must be the very next bar (K 1); V3 within 10 bars; V4 break by
  0.03% beyond the level; V5 1.5R target; V6 2R target; V7 swing target + step profit lock (+1R -> stop to entry
  +$0.10, then +0.5R per 0.5R); V8 buy-stop at the green candle's high (no close needed).
- **Split:** design 2 Oct 2023 - 31 Mar 2026; walk-forward = the eight out-of-sample quarters Apr 2024 - Mar 2026; final
  1 Apr - 16 Oct 2026, read once.
- **Twins:** 1,000 random-entry books per line, same side, same minute of day, same stop and target distance, same lock
  and cut-off, random weekday of the same period. Benjamini-Hochberg across all 24 lines.
- **Pass:** net > 0 in design, walk-forward and final; BH q <= 0.10 against the twins; >= 100 design trades.

## Results (USD per lot, after costs)

| line | design trades (/month) | win | avg win / avg loss | PF | design net | worst day | max DD | WF trades | WF net (quarters +) | twin p | BH q | final trades | final net | final PF | final max DD | pass |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| V1 long | 154 (5.1) | 35% | +927 / -475 | 1.05 | +2,578 | -3,158 | -12,453 | 120 | +3,103 (5/8) | 0.241 | 0.58 | 45 | -7,885 | 0.60 | -12,565 | no |
| V1 short | 199 (6.6) | 26% | +585 / -287 | 0.72 | -11,817 | -945 | -20,539 | 152 | -7,689 (2/8) | 0.670 | 0.85 | 30 | -2,825 | 0.81 | -6,454 | no |
| V1 combined | 353 (11.8) | 30% | +759 / -363 | 0.90 | -9,239 | -3,158 | -13,720 | 272 | -4,586 (4/8) | 0.377 | 0.60 | 75 | -10,710 | 0.69 | -12,722 | no |
| V2 long | 122 (4.1) | 31% | +1,161 / -390 | 1.35 | +11,320 | -2,050 | -5,769 | 97 | +13,623 (5/8) | 0.029 | 0.17 | 33 | -7,676 | 0.45 | -8,505 | no |
| V2 short | 137 (4.6) | 23% | +517 / -271 | 0.58 | -11,929 | -826 | -15,114 | 109 | -9,724 (2/8) | 0.862 | 0.86 | 22 | -390 | 0.95 | -2,754 | no |
| V2 combined | 259 (8.6) | 27% | +867 / -324 | 0.99 | -609 | -2,050 | -18,507 | 206 | +3,899 (3/8) | 0.149 | 0.47 | 55 | -8,066 | 0.62 | -8,980 | no |
| V3 long | 174 (5.8) | 34% | +1,013 / -516 | 1.01 | +380 | -3,158 | -17,003 | 134 | -434 (4/8) | 0.317 | 0.60 | 51 | -18,789 | 0.39 | -21,826 | no |
| V3 short | 239 (8.0) | 23% | +869 / -312 | 0.85 | -8,427 | -1,275 | -18,217 | 187 | -3,828 (2/8) | 0.355 | 0.60 | 34 | -2,566 | 0.86 | -6,165 | no |
| V3 combined | 413 (13.8) | 28% | +943 / -391 | 0.93 | -8,047 | -3,158 | -19,774 | 321 | -4,262 (4/8) | 0.306 | 0.60 | 85 | -21,355 | 0.56 | -23,852 | no |
| V4 long | 141 (4.7) | 33% | +970 / -498 | 0.97 | -1,230 | -3,158 | -13,157 | 107 | -1,932 (5/8) | 0.410 | 0.61 | 46 | -5,814 | 0.73 | -16,022 | no |
| V4 short | 197 (6.6) | 25% | +609 / -281 | 0.72 | -11,673 | -1,275 | -21,228 | 157 | -6,168 (3/8) | 0.637 | 0.85 | 33 | -2,732 | 0.82 | -7,509 | no |
| V4 combined | 338 (11.3) | 28% | +786 / -365 | 0.85 | -12,903 | -3,158 | -18,451 | 264 | -8,100 (4/8) | 0.515 | 0.73 | 79 | -8,546 | 0.77 | -16,489 | no |
| V5 long | 156 (5.2) | 40% | +871 / -432 | 1.33 | +13,371 | -2,757 | -8,050 | 121 | +13,305 (5/8) | 0.008 | 0.17 | 45 | -4,172 | 0.79 | -11,188 | no |
| V5 short | 201 (6.7) | 28% | +498 / -294 | 0.65 | -14,811 | -945 | -18,884 | 154 | -11,746 (2/8) | 0.841 | 0.86 | 30 | -1,250 | 0.91 | -5,251 | no |
| V5 combined | 357 (11.9) | 33% | +694 / -349 | 0.98 | -1,440 | -2,757 | -14,165 | 275 | +1,559 (4/8) | 0.109 | 0.47 | 75 | -5,422 | 0.84 | -9,767 | no |
| V6 long | 156 (5.2) | 37% | +910 / -481 | 1.09 | +4,269 | -3,158 | -9,766 | 121 | +3,827 (6/8) | 0.161 | 0.47 | 45 | -5,138 | 0.76 | -11,432 | no |
| V6 short | 201 (6.7) | 24% | +666 / -296 | 0.71 | -13,296 | -945 | -20,198 | 154 | -10,629 (2/8) | 0.753 | 0.86 | 30 | -3,330 | 0.79 | -6,505 | no |
| V6 combined | 357 (11.9) | 29% | +798 / -368 | 0.90 | -9,027 | -3,158 | -17,719 | 275 | -6,802 (4/8) | 0.370 | 0.60 | 75 | -8,468 | 0.77 | -12,467 | no |
| V7 long | 154 (5.1) | 36% | +796 / -323 | 1.37 | +11,831 | -1,702 | -4,551 | 120 | +13,371 (6/8) | 0.020 | 0.17 | 45 | -3,951 | 0.76 | -10,128 | no |
| V7 short | 199 (6.6) | 33% | +332 / -268 | 0.62 | -13,650 | -945 | -15,850 | 152 | -11,029 (1/8) | 0.827 | 0.86 | 30 | -8,796 | 0.36 | -9,629 | no |
| V7 combined | 353 (11.8) | 34% | +543 / -291 | 0.97 | -1,819 | -1,702 | -12,806 | 272 | +2,342 (4/8) | 0.123 | 0.47 | 75 | -12,746 | 0.58 | -16,385 | no |
| V8 long | 170 (5.7) | 30% | +1,105 / -370 | 1.28 | +12,374 | -2,943 | -10,464 | 130 | +11,951 (6/8) | 0.024 | 0.17 | 50 | -8,909 | 0.51 | -12,637 | no |
| V8 short | 231 (7.7) | 20% | +590 / -252 | 0.58 | -19,436 | -2,785 | -19,436 | 179 | -13,835 (4/8) | 0.861 | 0.86 | 34 | -3,874 | 0.75 | -7,429 | no |
| V8 combined | 401 (13.4) | 24% | +861 / -298 | 0.92 | -7,061 | -2,943 | -16,516 | 309 | -1,884 (4/8) | 0.177 | 0.47 | 84 | -12,783 | 0.62 | -14,868 | no |

Final window, more detail:

| line | final win | final avg win / avg loss | final worst day | final trades/month | final twin p | final twin mean |
|---|---|---|---|---|---|---|
| V1 long | 31% | +848 / -637 | -2,683 | 6.9 | 0.632 | -4,954 |
| V1 short | 37% | +1,088 / -779 | -1,735 | 4.6 | 0.668 | +586 |
| V1 combined | 33% | +954 / -691 | -2,683 | 11.5 | 0.713 | -4,772 |
| V2 long | 27% | +699 / -582 | -1,506 | 5.0 | 0.772 | -3,657 |
| V2 short | 32% | +991 / -488 | -1,735 | 3.4 | 0.447 | -676 |
| V2 combined | 29% | +827 / -546 | -1,735 | 8.4 | 0.683 | -4,201 |
| V3 long | 22% | +1,091 / -770 | -2,683 | 7.8 | 0.927 | -6,950 |
| V3 short | 32% | +1,407 / -785 | -1,735 | 5.2 | 0.595 | -451 |
| V3 combined | 26% | +1,249 / -775 | -2,683 | 13.0 | 0.893 | -7,115 |
| V4 long | 28% | +1,200 / -649 | -2,737 | 7.0 | 0.512 | -5,366 |
| V4 short | 42% | +893 / -802 | -1,735 | 5.0 | 0.637 | +93 |
| V4 combined | 34% | +1,041 / -705 | -3,135 | 12.1 | 0.581 | -5,792 |
| V5 long | 29% | +1,233 / -631 | -2,683 | 6.9 | 0.498 | -4,085 |
| V5 short | 37% | +1,214 / -768 | -1,735 | 4.6 | 0.625 | +777 |
| V5 combined | 32% | +1,224 / -682 | -2,683 | 11.5 | 0.581 | -3,374 |
| V6 long | 22% | +1,627 / -612 | -2,683 | 6.9 | 0.525 | -4,590 |
| V6 short | 27% | +1,580 / -726 | -1,735 | 4.6 | 0.733 | +857 |
| V6 combined | 24% | +1,606 / -656 | -2,683 | 11.5 | 0.688 | -3,552 |
| V7 long | 38% | +753 / -598 | -2,683 | 6.9 | 0.500 | -3,738 |
| V7 short | 33% | +505 / -692 | -1,735 | 4.6 | 0.956 | +541 |
| V7 combined | 36% | +661 / -637 | -2,683 | 11.5 | 0.886 | -2,958 |
| V8 long | 30% | +616 / -519 | -1,560 | 7.6 | 0.686 | -4,912 |
| V8 short | 24% | +1,426 / -588 | -1,569 | 5.2 | 0.688 | +161 |
| V8 combined | 27% | +898 / -548 | -1,837 | 12.8 | 0.770 | -4,906 |

**Re-tune line** (before each walk-forward quarter, pick the variant with the best net so far): long +$7,136, short
-$21,327, combined -$9,537 over the eight quarters. Picking the best-so-far version does not rescue it.

**Twins:** random entries with the same stops and targets lose on average (design twin means -$3k to -$17k a line),
mostly the spread on tight stops. The rule's long side beat them in design (raw p 0.008-0.03 for V2, V5, V7, V8), but
not after correcting for 24 tries, and not in the final window (final twin p 0.50-0.93 for every long line).

**Exit reasons, design, V1 combined:** 244 stops, 95 targets, 14 time exits (353 trades).

### Example trades (V1, design)

| side | break | green | entry | exit | yesterday's level | entry | stop | target | exit | why | net / lot |
|---|---|---|---|---|---|---|---|---|---|---|---|
| long | 30 Dec 2024 08:52 | 08:53 | 08:55 | 09:30 | low 2611.29 | 2611.70 | 2609.03 | 2617.23 | 2617.23 | target | +$546 |
| long | 5 Dec 2024 15:42 | 15:43 | 15:45 | 17:12 | low 2632.32 | 2633.93 | 2631.79 | 2647.43 | 2631.74 | stop | -$226 |
| short | 7 Nov 2025 16:43 | 16:45 | 16:47 | 20:40 | high 4019.28 | 4015.94 | 4022.28 (ask) | 3984.16 (ask) | 4002.77 | time | +$1,310 |

Times UTC (add 5:30 for IST). Long entries are at the ask, short exits at the ask.

## For Jarvis

- "Gautam Jha's Daily High/Low Reversal" (break of yesterday's 1-day high/low on the 1-minute chart, a reversal candle,
  a close through it) was tested on XAUUSD 2 Oct 2023 - 16 Oct 2026 with real costs: **rejected**. As described: -$9,239 a
  lot on 353 design trades and -$10,710 on 75 final trades. Shorts lose everywhere; longs looked good in design
  (+$11k-13k) but lost in the final window in all 8 versions.
- If Boss asks for it on paper: it failed the research gates, so it stays a note (CLAUDE.md). Do not add it to a gold arm.
- If Boss asks "what about only longs": design yes, final no, and the design edge was not significant after 24 tries.
- Signal source: XAUUSD itself. Daily candle: UTC day.

## Limits

- One price source (Dukascopy BID, one ECN). A broker with a wider spread or a different day boundary (17:00 New York)
  would give different levels; the broker-day version was not tested (pinned to the UTC day used by the app).
- A flat $0.30 spread is used; real spreads widen at news and the 21:00 UTC rollover (entries end 19:00, so the
  rollover is avoided), which would make results worse, not better.
- 1-minute bars: the order of the high and low inside a bar is unknown; stops are taken first when both are touched.
- Sessions limited to 07:00-19:00 UTC entries (London + New York), as the earlier gold studies; Asian-session breaks
  were not tested.
- The final window was seen by earlier gold studies (other rules); this rule was never run on it before.
- Sample sizes: 122-239 design trades per side line, 22-51 final trades per side line. The final window alone is small,
  but it agrees with the design for shorts and contradicts the design for longs.
- Data plumbing: two brain tests (`test_volume_profile_level_features_point_in_time`,
  `test_every_feature_is_point_in_time`) fail in this sandbox on a clean `main` too (library versions); R78 does not
  touch them.
