# HUNT R77: the "absorption bubble" strategy, tested as a proxy on 1-minute index bars

Written 18 Oct 2026 for Boss (asked 18 Oct: "a quick test of an absorption bubble strategy"). Research only; nothing
changes paper or live.

- Pre-registration: `research/hunt/r77/PREREG.md`, committed (1e2123eb) before any number was computed.
- Code: `research/hunt/r77/` (`r77rules.py` the detector, `r77study.py` the study, `test_r77.py` the tests,
  `r77entries.py` the experiment log). Results (small JSON): `research/hunt/r77/results/`.
- Data: the Drive lake (`gdrive:IraAlgo-data-lake`: `index_1m`, `options_rolling_1m` WEEK files, and
  `fut_1m_live_index` for the proxy check only), turned into R51's Amendment A clean stores, 20 Nov 2024 - 13 Oct 2026.
  The downloaded data was deleted after the run.

## The answer in plain English

**No. The absorption-bubble proxy loses money after costs, in every one of the 8 pre-registered versions, in design
and in the final period. It does no better than buying at random at the same time of day.**

- All 8 versions lose in design (20 Nov 2024 - 31 Mar 2026): from -Rs 9,020 (324 trades) to -Rs 1,00,386 (502
  trades). This fails the first gate, so no version passes.
- In the final period (1 Apr - 13 Oct 2026), all 8 lose again, from -Rs 8,942 (100 trades) to -Rs 1,61,911 (265
  trades). Against random entries at the same minute of the day on other days, none is close: twin p 0.24 - 1.00, BH
  q 0.998 for all 8.
- The only hint: the strictest versions, with a very small range (C and D), made a little money in the walk-forward
  quarters (+Rs 4,926 to +Rs 11,090 on 132-235 trades). They then lost in the final period. They also lost money over
  the whole design period.
- **The pure event study shows no edge.** After an event, the index moves 5, 15 and 30 minutes by between -0.7 and +1.0
  basis points. That is the same as a plain swing reversal without the volume condition, and the same as the same
  minute on random days. Every 95% interval includes zero. One option round trip costs about 2 bp of the index (R7),
  so even a real +1 bp would not pay.
- **The proxy itself is weak.** The index has no volume of its own, and expired futures minutes are not in the lake.
  So "volume" here is the index's option volume. On the 16-53 days where real futures minutes exist, the per-minute
  rank correlation between the two is only 0.18-0.38. Only 14-48% of the minutes the proxy flags as "very high
  volume" are among the top 10% of futures minutes. This test therefore cannot rule out true absorption read from
  ticks and the order book. It does rule out the candle-and-volume shortcut.

Verdict: **rejected** (all 8 versions). This idea stays a note. The way to test true absorption is the phone's own
tick recordings (R10 section 4.2, test 4) once 20 sessions are recorded.

## 1. What was tested (the signal source)

**Signal source: the UNDERLYING.** The event and its confirmation are read from the index's 1-minute bars (range, a
new 20-bar low or high, and the next bar). The volume condition uses the index's nearest-expiry option volume as
context: the sum of CE + PE volume over the strikes in the store, in lots. The trade is the 1-ITM option chosen by
the strike rule. No signal is read from the traded option's own chart.

- **Event (bar i):** the bar's volume is at or above the V-th percentile of the same minute over the previous 20
  sessions, its range is at or below the R-th percentile, and its low is a new 20-bar low (a downside event) or its
  high is a new 20-bar high (an upside event).
- **Confirmation (bar i + 1):** the absorption low holds (no lower low). The bar then either closes back above the
  swept 20-bar low or closes above the absorption bar's high. Upside events use the mirror image.
- **Entry:** at the open of minute i + 2, between 09:37 and 14:45. Long CE after a downside event, long PE after an
  upside one. One open position per side, at most 2 a side a day. Expiry days are skipped.
- **Detectors:** A = V 95 / R 40 (Boss's example), B = 90 / 40, C = 95 / 25, D = 90 / 25.
- **Exits** (both square off at 15:15):
  - X1 (structure): an index stop just beyond the absorption bar's extreme, and an index target at the opposite end
    of the swept 20-bar range. Both sell at the next minute's open. Also a -25% premium stop and a 30-minute time stop.
  - X2 (the app's arms): a -15% premium stop, a +30% premium target (2R) and a 30-minute time stop.
- **Pricing:** R64's engine on R51's clean stores, as R71 used it. Fills are at the next minute with R17 "liq"
  slippage. Stops are hit on the minute's low, and a gap fills at the worse open. Charges are `Costs('dated')` (STT
  0.15% from 1 Apr 2026), with the lots in force, 1 lot.
- **Indices:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, pooled.

## 2. Results (Rs, 1 lot, after all charges and slippage)

Each cell shows trades / win rate / **net** / worst day / max drawdown. The walk-forward column is the design
rule's out-of-sample quarters (nothing was tuned). Twins: 500 random entries on other days at the same minute row,
same side, strike rule and exits. BH is applied across the 8 tests.

| test | design (20 Nov 24 - 31 Mar 26) | walk-forward (Apr 25 - Mar 26) | final (1 Apr - 13 Oct 26, read once) | final without the stale print* | twin p (final) | BH q | design twin p (info) |
|---|---|---|---|---|---|---|---|
| A-X1 | 328 / 27% / **-45,965** / -7,391 / 45,965 | 229 / 27% / **-26,059** / -7,391 / 28,058 | 153 / 27% / **-1,13,096** / -99,624 / 1,21,884 | 152 / -13,070 | 0.996 | 0.998 | 0.53 |
| A-X2 | 315 / 41% / **-67,577** / -9,630 / 67,577 | 220 / 40% / **-40,914** / -9,630 / 48,454 | 152 / 41% / **-1,25,017** / -1,01,002 / 1,37,165 | 151 / -24,990 | 0.998 | 0.998 | 0.68 |
| B-X1 | 520 / 27% / **-58,188** / -7,391 / 58,188 | 378 / 28% / **-33,159** / -7,391 / 33,159 | 273 / 24% / **-1,38,732** / -99,624 / 1,39,047 | 272 / -38,705 | 0.998 | 0.998 | 0.29 |
| B-X2 | 502 / 42% / **-1,00,386** / -11,330 / 1,00,386 | 362 / 42% / **-65,978** / -9,559 / 65,978 | 265 / 38% / **-1,61,911** / -1,01,002 / 1,62,897 | 264 / -61,884 | 0.998 | 0.998 | 0.68 |
| C-X1 | 208 / 31% / **-9,722** / -3,699 / 18,042 | 139 / 33% / **+4,926** / -3,699 / 13,975 | 102 / 28% / **-10,869** / -5,891 / 14,132 | same | 0.34 | 0.998 | 0.064 |
| C-X2 | 200 / 49% / **-9,054** / -7,071 / 20,506 | 132 / 51% / **+5,483** / -6,814 / 18,792 | 100 / 43% / **-8,942** / -7,233 / 18,641 | same | 0.24 | 0.998 | 0.18 |
| D-X1 | 333 / 30% / **-11,675** / -4,677 / 27,976 | 235 / 32% / **+11,090** / -4,068 / 11,175 | 190 / 26% / **-23,808** / -5,891 / 23,808 | same | 0.43 | 0.998 | 0.014 |
| D-X2 | 324 / 48% / **-9,020** / -12,773 / 20,975 | 227 / 49% / **+7,565** / -6,814 / 14,120 | 186 / 39% / **-37,053** / -8,221 / 44,955 | same | 0.50 | 0.998 | 0.11 |

Gates: net > 0 in design, walk-forward and final; at least 100 design trades; final twin q <= 0.10. **All 8 fail.**
A, B: all three periods and the twins. C, D: design, final and the twins.

\* *Not pre-registered (found after the final read).* One FINNIFTY trade (28 Sep 2026, 24650 CE) filled at Rs 1,855.85.
That price was a stale, zero-volume print repeated for 13 minutes; the real price was about Rs 192, and R51's
stray-print cleaning misses a repeated print like this. The trade cost -Rs 1,00,027 in A and B. Without it, A and B
still lose in the final period. The verdict does not depend on it. The defect belongs to the lake/cleaning code and
can hit other studies' fills (`results/posthoc.json`).

Walk-forward quarters (net, trades):

| test | Apr-Jun 25 | Jul-Sep 25 | Oct-Dec 25 | Jan-Mar 26 |
|---|---|---|---|---|
| A-X1 | -2,258 (57) | -12,161 (78) | -7,334 (58) | -4,305 (36) |
| A-X2 | -3,353 (53) | -17,259 (76) | -9,172 (55) | -11,129 (36) |
| B-X1 | -10,483 (94) | -7,967 (125) | -8,874 (88) | -5,835 (71) |
| B-X2 | -21,216 (90) | -25,079 (120) | +147 (86) | -19,830 (66) |
| C-X1 | +10,473 (41) | -1,724 (37) | -5,734 (43) | +1,911 (18) |
| C-X2 | +13,359 (40) | -830 (37) | -10,630 (37) | +3,585 (18) |
| D-X1 | +6,822 (64) | +1,894 (69) | -446 (62) | +2,819 (40) |
| D-X2 | +7,804 (64) | +3,548 (69) | +995 (55) | -4,783 (39) |

By index (design trades: net / final trades: net):

| index | A-X1 | A-X2 | B-X1 | B-X2 | C-X1 | C-X2 | D-X1 | D-X2 |
|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | 95: -83 / 49: -1,998 | 91: -10,047 / 48: +114 | 152: -2,550 / 82: -10,723 | 148: -35,675 / 79: -3,973 | 64: +6,997 / 32: +220 | 63: +288 / 32: +12,414 | 99: +4,160 / 56: -4,928 | 98: -4,397 / 55: +6,932 |
| NIFTY | 18: -550 / 6: -3,934 | 18: -5,463 / 6: -4,335 | 38: +1,529 / 15: -5,283 | 36: +4,454 / 15: -5,875 | 11: +2,108 / 3: -1,679 | 11: +1,725 / 3: -1,968 | 19: +6,676 / 8: -2,889 | 19: +11,480 / 8: -3,744 |
| FINNIFTY | 102: -9,114 / 43: -98,364* | 97: -8,487 / 43: -1,00,999* | 159: -8,120 / 78: -1,06,396* | 151: -18,399 / 78: -1,20,368* | 63: +4,194 / 31: -1,641 | 59: +13,514 / 31: -544 | 100: +9,413 / 55: -7,277 | 96: +14,019 / 55: -16,126 |
| MIDCPNIFTY | 112: -35,930 / 53: -7,643 | 108: -42,777 / 53: -18,912 | 167: -49,335 / 91: -13,482 | 163: -50,339 / 87: -28,238 | 70: -23,022 / 36: -7,769 | 67: -24,581 / 34: -18,845 | 113: -31,542 / 67: -7,316 | 109: -29,539 / 64: -20,644 |
| SENSEX | 1: -289 / 2: -1,157 | 1: -803 / 2: -883 | 4: +289 / 7: -2,847 | 4: -427 / 6: -3,456 | 0 / 0 | 0 / 0 | 2: -382 / 4: -1,399 | 2: -583 / 4: -3,471 |

BANKNIFTY alone is near zero: between -Rs 35,675 and +Rs 6,997 in design on 63-152 trades, and between -Rs 10,723
and +Rs 12,414 in the final period on 32-82 trades. The signs flip from version to version, so this is noise, not an
edge. NIFTY and SENSEX produce very few events (3-38 per version and period). Their option volume swings with the
weekly expiry cycle, so a "95th percentile minute" there is mostly an expiry-week minute. This weakness comes from the
proxy, not from the market.

D-X1's design twin p of 0.014 means it lost less than random entries at the same minutes did (twins averaged -Rs
44,054). It still lost money, though. In the final period this did not repeat (p 0.43), and the design twin test was
information only.

## 3. Example trades (design period)

| version | index, day | side, strike | absorption bar | entry -> exit | fill -> out | why | net |
|---|---|---|---|---|---|---|---|
| A-X1 best | FINNIFTY, 23 May 2025 | CE 26250 | 10:35 (index stop 26,290.5, target 26,384.55) | 10:37 -> 10:48 | 263.45 -> 340.00 | index target | +Rs 4,860 |
| A-X1 median | FINNIFTY, 26 Sep 2025 | CE 25950 | 14:05 (stop 25,973.2) | 14:07 -> 14:08 | 156.50 -> 152.65 | index stop after 1 minute | -Rs 333 |
| A-X1 worst | FINNIFTY, 28 Jan 2025 | PE 22900 | 11:43 (stop 22,861.05) | 11:45 -> 11:54 | 249.10 -> 220.00 | index stop | -Rs 1,988 |
| A-X2 worst | FINNIFTY, 25 Mar 2026 | CE 25100 | 14:07 | 14:09 -> 14:22 | 380.50 -> 323.43 | -15% stop | -Rs 3,549 |

The median trade is typical. The absorption bar's range is small by definition, so the X1 index stop sits only a few
points away. The index often taps through it within 1-3 minutes, and the trade pays the round-trip cost for nothing.
Of the 328 A-X1 design trades, 291 ended on an index exit and 37 on the time stop. The win rate is 27%.

## 4. Event study (the index alone, no option, no costs)

The table shows the signed move of the index after the confirmation bar's close, in basis points (+ = the expected
reversal). Each move is compared with two placebos matched on the same minute row, side and period:
- P-time: the same minute on 200 random other days.
- P-swing: the same swing reversal without the volume or range condition, within 15 minutes of the event's time of day.

The 95% intervals come from a day-block bootstrap (2,000 resamples).

| detector, period | n events (days) | +5 min | +15 min | +30 min | event - P-swing, 15 min [95% CI] | event - P-time, 15 min [95% CI] |
|---|---|---|---|---|---|---|
| A design | 506 (145) | -0.19 (48% up) | +0.20 (52%) | -0.37 (51%) | +0.31 [-0.66, +1.27] | +0.17 [-0.79, +1.09] |
| B design | 816 (232) | -0.09 (49%) | -0.05 (51%) | -0.72 (50%) | +0.06 [-0.81, +0.90] | -0.06 [-0.94, +0.76] |
| C design | 254 (109) | +0.37 (52%) | +0.69 (52%) | +0.98 (55%) | +0.78 [-0.67, +2.11] | +0.51 [-0.94, +2.01] |
| D design | 414 (173) | +0.31 (52%) | +0.61 (54%) | +0.49 (53%) | +0.71 [-0.55, +1.91] | +0.48 [-0.73, +1.73] |
| A final | 247 | +0.23 (51%) | +0.56 (55%) | -0.25 (50%) | +0.32 [-1.38, +1.98] | +0.61 [-1.08, +2.34] |
| B final | 483 | +0.22 (51%) | +0.30 (53%) | -0.44 (50%) | +0.13 [-0.97, +1.07] | +0.32 [-0.77, +1.28] |
| C final | 134 | +0.27 (53%) | +0.63 (57%) | +0.12 (52%) | +0.55 [-1.63, +2.67] | +0.59 [-1.61, +2.83] |
| D final | 289 | +0.37 (52%) | +0.25 (55%) | -0.53 (51%) | +0.19 [-1.13, +1.41] | +0.35 [-1.04, +1.66] |
| plain swing reversal (design) | 40,185 | +0.10 (51%) | -0.03 (51%) | -0.02 (51%) | | |

Every interval includes zero. The strictest versions (C, D) lean slightly positive at 15 minutes, by under 1 bp, in
both periods. One option round trip costs about 2 bp (R7), so the lean is far too small to pay. "Absorption" adds
nothing measurable to a plain 20-bar swing reversal, which is itself flat (51% up on 40,185 events).

## 5. The proxy check (no prices or P&L)

These are the days where the lake has real index-future minutes (29 Jul - 13 Oct 2026). The lake keeps the contract
that was live when it was saved, so for August-September it is often the next-month future.

| index | days | minutes | rank correlation, all minutes | median per day | proxy "95th pct" minutes | ... in the top 10% of futures minutes |
|---|---|---|---|---|---|---|
| NIFTY | 53 | 19,874 | 0.32 | 0.42 | 1,819 | 19% |
| BANKNIFTY | 53 | 19,864 | 0.38 | 0.44 | 1,879 | 14% |
| FINNIFTY | 16 | 5,501 | 0.18 | 0.21 | 1,190 | 48% |
| MIDCPNIFTY | 52 | 18,864 | 0.33 | 0.20 | 2,059 | 22% |
| SENSEX | 51 | 18,511 | 0.18 | 0.15 | 1,416 | 26% |

By chance, 10% of the flagged minutes would fall in the top futures decile. The proxy does better than chance, but
it is a poor stand-in for futures volume. The phone's own futures recordings are needed.

## For Jarvis

- If Boss asks "does absorption work?", say: "Not as a candle rule. R77 tested 8 pre-registered versions on 5
  indices (100-520 trades each per period). Every version lost after costs in design and in the final test, and none
  beat random entries at the same time of day (BH q 0.998)."
- The index's move after these events is under 1 bp, which is the same as any swing reversal and less than one
  option round trip (about 2 bp).
- The volume in this test was option volume, a weak proxy (rank correlation 0.18-0.38 with futures volume). True
  absorption needs ticks and the order book. The app records those (`OrderFlow.kt`, `FlowShadow.kt`), and after 20
  sessions R10 section 4.2 test 4 can be run on them.
- Do not offer absorption as an entry signal or a filter. R8 also found that absorption-flagged levels broke 58-68%
  of the time. At most, it can be a display or a confirmation note.
- Signal source: the underlying index bars (volume as context). Nothing here reads an option's own chart.

## Limits

- **It is a proxy.** The volume is the index's option volume, not futures order flow. A 1-minute bar's range says
  nothing about where inside the bar the volume traded, or who was passive. True absorption needs tick and book data
  from the phone's recordings.
- **The final period was already read** by R51, R63, R70, R71, R76 and others for other rules, so it is weak
  evidence. Here it agrees with design anyway.
- **The volume baseline mixes the expiry cycle.** NIFTY and SENSEX have weekly expiries, so their 20-session
  percentile is dominated by expiry-week minutes, and they produced few events.
- **Warm-up:** the store's option minutes start on 20 Nov 2024, so events start about 19 Dec 2024.
- **One lake defect was found after the final read** (a stale zero-volume print, FINNIFTY 28 Sep 2026). It is
  reported separately above and does not change the verdict.
- **Only 8 versions were tested** (4 detectors x 2 exits). Other swing lengths, confirmations or stops were not
  tried and will not be tried on this data.
- **Twins treat each trade independently** (no one-position rule), as R71 did.
- **Sample sizes:** 100-520 trades per version and period; 134-816 events per detector and period.
