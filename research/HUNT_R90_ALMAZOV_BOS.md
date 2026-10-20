# R90: "Almazov Trade: MTF Structure & BOS Entries" (20 Oct 2026)

Boss, 20 Oct: sent the Pine v6 source of the TradingView indicator "Almazov Trade: MTF Structure & BOS Entries"
(© Algorithms_ATS, MPL 2.0) and asked whether it works. The rules, written out from the source, are in
`hunt/r90_almazov_bos/SOURCE.md`; they were rebuilt in Python and tested with real option prices and costs. Research
only: nothing in `android/**` or `.github/**` changed.

- Plan: `hunt/r90_almazov_bos/PREREG.md`, committed and pushed (439876c) before any statistic. Code, tests and notes N1
  (a827a6b) before any P&L. Design results, the arms comparison and the 11 experiment entries (fccfde5) before the one
  holdout read (`results/FINAL_READ`).
- Code: `hunt/r90_almazov_bos/` (`structure.py` bars / swings / CHoCH / BOS; `model.py` setups, the indicator's own
  bar-path run and the session-rules run; `price.py` options and twins; `study.py`; `arms.py`; `report.py`;
  `entries.py`). Tests: `tests/test_r90.py` (24: hand-made structure, setup, fill and exit cases, plus the look-ahead
  guard on 7 chart / HTF settings).
- Every table: `results/report.md`. JSON: `design.json`, `final.json`, `arms.json`.
- **Signal source of every rule: the UNDERLYING.** Swings, trend, CHoCH, BOS, the entry level, the stop and the target
  are all read on the index spot (1-minute OHLC) or, for MCX, on the near future (close-only). The trade is buying the
  1-ITM call / put on its real minute prints. No rule reads an option's chart; no option-chain data.

## The answer for Boss

**It does not pay after costs, on any instrument or setting. 0 of 99 pre-registered cells passed design; the holdout
(1 Apr - 19 Oct 2026, read once) confirmed it: the design leader lost, and no setting was better than random entry
times. Nothing goes to paper. It is fine as a chart overlay (structure and levels only), not as a signal.**

1. **On the indicator's own terms it looks fine; held the way Boss trades it, it does not.** The chart's model (fills on
   bar OHLC, no fees, positions held for days) shows the five indices +Rs 0.2 to +3.3 lakh in design on 9 of 11 settings
   (e.g. 5m chart / 60m HTF: +Rs 3.30 lakh on 223 trades, 1 lot, no costs). But 52-81% of those 5m trades are held
   overnight: the target is the higher-timeframe swing, typically 2x the risk away (NIFTY 5m: risk 99, target 215 points).
   Flat by 15:15, the same signals on the index itself (no costs) made -Rs 12,512 (5m, 76 trades) to +Rs 2.70 lakh (15m
   chart / day HTF, 154 trades) in design, and as the bought 1-ITM option after real costs -Rs 1.19 lakh to +Rs 41,833.
2. **The target is too far for an intraday option.** On the default 5m chart only 3 of 76 index trades reached the
   target before 15:15; 54 ended at the square-off after holding 2-3 hours. The option loses time value meanwhile: on
   NIFTY 5m the index made +3.5 points a trade while the option's gross was -14.3 points. On the 1m chart the target is
   nearer (about a quarter of trades reach it), but the index made only -0.7 to +18 points a trade against an option
   round trip of 1.1 (NIFTY) to 3.6 (SENSEX) points, the same wall as in R89.
3. **Whipsaws are not the problem.** The stop sits at the opposite chart swing, so only 0-9% of index trades (0-14% of
   MCX trades; one 5-trade cell 20%) are stopped within 3 chart bars (R89's candle stop: about a third). The trades lose
   slowly, not by false breaks.
4. **Not better than random entry times.** Against the hindsight-free after-trigger twin (same side, stop and target
   distances, entry any minute after the signal), no cell passed BH (lowest q 0.099 for four cells of 6-59 trades, all
   of which fail other gates). The best-looking design cell, MIDCPNIFTY 15m chart / day HTF, made +Rs 48,830 on 34 trades
   (twin p 0.068), Rs 42,617 of it from 5 trades in Jan-Mar 2026; in the holdout it **lost Rs 25,462 on 9 trades**.
5. **Signals are rare.** A setup needs the chart trend to agree with the HTF trend AND the HTF swing to lie beyond the
   chart swing; usually they are the same price (the HTF bar's high is a chart bar's high). The default 5m chart gives
   0.03-0.06 trades a day per index (10-21 in 16 months); only the 1m chart reaches 0.3-0.4 a day. 79 of 99 design cells
   have under 40 trades.
6. **MCX is worse.** Every MCX setting lost in total in both windows (design pooled -Rs 44,169 on 95 trades for 5m;
   holdout -Rs 75,465 on 97). GOLDM / SILVERM 1-ITM options print too rarely to fill many signals (GOLDM 1m: 107 of
   174 design signals unfilled); GOLDM 1m lost Rs 1.36 lakh (67) in design and Rs 1.58 lakh (164) in the holdout, worst
   month -Rs 65,190.
7. **Boss's account stop.** The 1m chart on all five indices breaks -Rs 13,000 in 8 of 17 design months (worst
   -Rs 37,909, Jan 2025) and 2 of 7 holdout months. The 5m chart stays inside it only because it trades about once a week.
8. **Different from the arms, not better.** 82% of 5m trades fall on Breadth Donchian days (76% same side), 96% on ORB
   signal days, 61% on R89's days; the daily P&L correlation with Breadth Donchian is -0.08 to +0.17. It lost on the arms'
   days and on the other days alike (design 5m: -Rs 19,585 on 62 arm-day trades, -Rs 26,151 on 14 others). Breadth
   Donchian on the same stores: +Rs 2.17 lakh (3,071) design, +Rs 1.70 lakh (1,225) holdout.

Plain answer: the indicator draws market structure correctly and without look-ahead, but its BOS-to-BOS trade is a
multi-day swing trade. Cut to an intraday option buy, the far higher-timeframe target is rarely reached, the option
bleeds time value until 15:15, and the entry timing is no better than random. Keep it as a chart overlay if Boss likes
reading structure (swing highs / lows, trend, BOS / CHoCH marks); do not trade its entry boxes.

## How costs and session rules change it (pooled, 1 lot)

Five indices, design (20 Nov 2024 - 31 Mar 2026):

| variant | chart / HTF | as written (no costs, overnight): trades, Rs | session rules on the index (no costs): Rs | option after costs: trades, net | after-trigger twins mean | holdout option: trades, net |
|---|---|---|---|---|---|---|
| A1 | 1m / 4m | 661, -1,95,540 | +81,705 | 590, -1,05,147 | -54,476 | 214, -36,278 |
| A3 | 3m / 12m | 180, +70,704 | +58,854 | 177, -26,724 | -47,183 | 66, -22,595 |
| A5 | 5m / 20m | 91, +19,760 | -12,512 | 76, -45,735 | -51,725 | 42, -17,573 |
| A15 | 15m / 60m | 47, +1,54,818 | +3,272 | 45, -17,398 | -26,734 | 10, -2,400 |
| H60 | 5m / 60m | 223, +3,29,783 | +19,829 | 231, -1,19,236 | -1,26,140 | 92, +19,679 |
| H75 | 15m / 75m | 64, +90,954 | +67,836 | 66, +8,965 | -16,538 | 17, -10,476 |
| HD | 15m / day | 99, +75,148 | +2,69,871 | 154, +41,833 | +33,365 | 65, -47,236 |
| S3 | 5m / 20m, swing 3 | 72, +55,580 | +24,361 | 79, -14,587 | -37,261 | 34, +13,804 |
| RR2 | 5m / 20m, RR 2 | 48, +1,14,242 | +41,553 | 40, -9,766 | -24,553 | 30, -8,102 |
| W30 | 5m / 20m, no fill before 09:30 | 91, +19,760 | -12,512 | 76, -45,735 | -51,725 | 42, -17,573 |
| DT | 5m / 20m, with the daily trend | 44, -56,869 | +20,541 | 34, -5,121 | -28,863 | 24, -15,948 |

MCX (GOLDM, SILVERM, CRUDEOIL mini, NATURALGAS mini), design 5 Aug 2025 - 31 Mar 2026 / holdout:

| variant | as written: trades, Rs | session, no costs: Rs | option design: trades, net | option holdout: trades, net |
|---|---|---|---|---|
| A1 | 782, +34,215 | +90,590 | 428, -2,19,803 | 644, -3,55,635 |
| A3 | 215, -86,095 | +7,710 | 140, -54,063 | 170, -1,50,905 |
| A5 | 124, -1,570 | +39,840 | 95, -44,169 | 97, -75,465 |
| A15 | 34, -14,680 | +13,635 | 26, -19,895 | 42, -7,304 |
| H60 | 203, +2,76,465 | +2,54,780 | 178, -97,805 | 196, -1,10,835 |
| H75 | 38, -17,800 | +16,770 | 26, -23,245 | 42, -457 |
| HD | 35, -1,31,120 | +79,870 | 32, -11,612 | 61, -37,126 |
| S3 | 81, +1,15,610 | -64,755 | 71, -32,275 | 89, -67,028 |
| RR2 | 77, -15,460 | +66,380 | 58, -32,733 | 53, -30,157 |
| W30 | 124, -1,570 | +39,790 | 95, -44,118 | 97, -75,465 |
| DT | 65, +16,505 | +26,065 | 46, -21,168 | 51, -27,870 |

(Rupees of the index / future = points x the lot in force. The as-written column counts every signal, including MCX
ones whose option did not print; the option column counts filled trades only.)

## Data

| market | signal | option prints | design | holdout |
|---|---|---|---|---|
| NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY | R51 day stores (lake `index_1m` spot 1m OHLC), structure from 2 Sep 2024 | lake `options_rolling_1m` WEEK, R51 loader (held contracts, clean + stale step) to 19 Oct 2026; 474 option days each | 20 Nov 2024 - 31 Mar 2026 (336 sessions) | 1 Apr - 19 Oct 2026 (137) |
| GOLDM, SILVERM, CRUDEOIL (mini), NATURALGAS (mini) | near future = median `spot` of the option rows (R53 Market; close-only) | lake MCX MONTH books (R53 loader, R42 spike flags) | 5 Aug 2025 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |

Copied from Drive `gdrive:IraAlgo-data-lake` on 20 Oct 2026 (nothing committed); the same stores as R89. They reproduce
earlier work to the rupee: Breadth Donchian +Rs 2,16,929 (3,071) and Liquidity BANKNIFTY 15+5 +Rs 1,10,094 (336) in
design, R89's base -Rs 3,07,305 (2,925).

## Rules (PREREG section 3; signal source: the underlying for every rule)

- **Bars:** chart bars of 1 / 3 / 5 / 15 minutes and HTF bars of 4x (or 12x, 5x, one session) built from 1-minute
  bars, epoch-aligned as in the Pine (`time / (60000 * tf * chart_minutes)`); never across sessions.
- **Swings:** a high with no higher high among the 2 (S3: 3) bars on each side, known only when the right-hand bars have
  closed. Lows mirrored. Per timeframe.
- **Structure (per timeframe):** the first swing low before the first swing high = uptrend (and the mirror). Uptrend: a
  new higher swing high starts a new leg and the lowest swing low since the last high becomes the protected low; price
  above the active high = BOS; price below the protected low = CHoCH (trend down; the top of the failed leg becomes the
  protected high). Mirror for downtrends. Both levels hit in one bar: the path open -> nearer extreme -> far extreme ->
  close orders them.
- **Setup (at a chart bar's close):** chart trend = HTF trend (HTF bars that have closed); long: buy-stop at the chart's
  active swing high (unbroken, close below it), stop at the chart's protected low - 1 tick, target the HTF active swing
  high (unbroken, above the entry), RR >= 1 (RR2: 2). Short mirrored. One trade at a time; a used level is not reused.
- **As written:** fills and exits on the chart bars with the path rule, continuous, overnight, no costs.
- **Session rules (tested):** the stop order fills at 1-minute resolution inside the next chart bar, only 09:16 - 14:45
  (W30: from 09:30; MCX to 22:30); exit on the index's 1-minute low / high through the stop / target, else flat at 15:15
  (15:05 on expiry; MCX the R53 session cut). DT: only with the daily structure trend (same engine on daily bars).
- **Option:** 1-ITM, nearest expiry, bought at the next minute's open (R51 engine: 5 bps + 1-4 ticks by volume, dated
  charges); sold at the open of the minute after the underlying stop / target, or at 15:15 / 15:05. MCX: R53 rules + R85
  strict half-spread.
- **Gates (never relaxed, as R89):** >= 40 trades, net > 0, after-trigger twin p < 0.05, BH q < 0.10 over 99, >= 3 of 4
  folds positive (MCX 2 of 3), positive without the top 5, worst month >= -Rs 13,000.

## Design results (option, 1 lot, after costs)

Default 4x HTF on the 5m chart (A5), per instrument:

| instrument | trades (a day) | win | avg win / loss | per trade | net | max DD | worst day | worst month | stopped <= 3 bars | twin after mean, p | q | index pts / trade (no costs) | option gross / cost (pts) | as written: n, pts / trade |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 14 (0.04) | 14% | 6,388 / -2,332 | -1,086 | -15,205 | 22,468 | -5,982 | -5,982 | 0% | -13,441, 0.58 | 1.00 | +3.5 | -14.3 / 1.1 | 16, +12.9 |
| BANKNIFTY | 10 (0.03) | 20% | 6,247 / -2,216 | -524 | -5,235 | 11,164 | -4,226 | -5,115 | 0% | -5,778, 0.45 | 0.98 | -33.8 | -15.1 / 3.2 | 9, +119.7 |
| SENSEX | 13 (0.04) | 15% | 4,558 / -1,244 | -351 | -4,565 | 13,147 | -2,643 | -2,997 | 0% | -8,637, 0.21 | 0.87 | +5.8 | -13.9 / 3.7 | 16, +53.5 |
| FINNIFTY | 21 (0.06) | 19% | 2,456 / -2,033 | -1,178 | -24,740 | 25,551 | -5,445 | -5,857 | 5% | -12,678, 0.97 | 1.00 | -34.0 | -16.9 / 1.7 | 29, -42.4 |
| MIDCPNIFTY | 18 (0.05) | 39% | 5,606 / -3,203 | +223 | +4,010 | 16,391 | -8,311 | -8,311 | 0% | -11,191, 0.054 | 0.56 | +18.0 | +3.7 / 1.1 | 21, +13.7 |
| GOLDM | 7 (0.05) | 14% | 299 / -1,654 | -1,375 | -9,624 | 9,624 | -5,366 | -5,366 | 0% | -5,929, 0.98 | 1.00 | -116.3 | -102.6 / 34.8 | 19, -124.3 |
| SILVERM | 12 (0.08) | 17% | 1,460 / -2,946 | -2,212 | -26,545 | 26,545 | -12,921 | -12,921 | 8% | -5,965, 1.00 | 1.00 | +476.7 | -369.5 / 72.9 | 22, +651.0 |
| CRUDEOIL (mini) | 41 (0.27) | 39% | 214 / -176 | -24 | -968 | 2,160 | -437 | -1,144 | 7% | -3,010, 0.064 | 0.56 | +18.2 | +4.5 / 6.9 | 40, +31.2 |
| NATURALGAS (mini) | 35 (0.23) | 31% | 694 / -611 | -201 | -7,032 | 7,354 | -1,098 | -4,887 | 0% | -7,319, 0.45 | 0.98 | -0.3 | -0.3 / 0.5 | 43, -5.8 |

1m chart / 4m HTF (A1, the busiest setting):

| instrument | trades (a day) | win | avg win / loss | per trade | net | max DD | worst day | worst month | stopped <= 3 bars | twin p | index pts / trade | option gross / cost (pts) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 127 (0.38) | 31% | 2,920 / -1,639 | -239 | -30,362 | 61,641 | -5,409 | -19,999 | 2% | 0.96 | -0.7 | -2.3 / 1.1 |
| BANKNIFTY | 110 (0.33) | 35% | 2,435 / -1,752 | -268 | -29,471 | 41,056 | -8,415 | -14,502 | 0% | 0.97 | +0.2 | -5.6 / 3.5 |
| SENSEX | 120 (0.36) | 41% | 2,256 / -1,482 | +44 | +5,293 | 43,426 | -5,860 | -12,236 | 1% | 0.49 | +18.2 | +5.8 / 3.6 |
| FINNIFTY | 100 (0.30) | 40% | 2,265 / -1,640 | -78 | -7,784 | 25,558 | -7,919 | -14,502 | 3% | 0.32 | +3.6 | +0.4 / 1.8 |
| MIDCPNIFTY | 133 (0.40) | 41% | 2,837 / -2,482 | -322 | -42,823 | 81,163 | -19,250 | -16,500 | 1% | 0.49 | +0.5 | -1.5 / 1.1 |
| GOLDM | 67 (0.45) | 19% | 421 / -2,622 | -2,032 | -1,36,115 | 1,36,437 | -51,921 | -65,190 | 1% | 1.00 | +12.6 | -167.8 / 35.4 |
| SILVERM | 62 (0.42) | 27% | 1,491 / -1,790 | -891 | -55,216 | 61,955 | -9,099 | -20,574 | 3% | 0.87 | +54.3 | -92.8 / 85.4 |
| CRUDEOIL (mini) | 163 (1.09) | 27% | 152 / -159 | -75 | -12,187 | 12,187 | -1,095 | -2,186 | 2% | 0.17 | +7.5 | +0.5 / 7.9 |
| NATURALGAS (mini) | 136 (0.91) | 33% | 250 / -302 | -120 | -16,285 | 16,285 | -970 | -4,159 | 2% | 0.32 | +0.3 | 0.0 / 0.5 |

Exit reasons, indices: 5m chart 54 time / 19 stop / 3 target (76); 1m chart 317 stop / 162 target / 111 time (590).
Average hold 115-189 minutes on 5m, 69-80 on 1m.

- 18 of 99 design cells were positive, 16 of them with under 40 trades; the two with >= 40 trades (MIDCPNIFTY 3m
  +Rs 11,180 on 43, SENSEX 1m +Rs 5,293 on 120) had twin p 0.39 / 0.49. 6 cells had twin p < 0.05 (all 5-59 trades;
  BANKNIFTY H75 6 trades, BANKNIFTY S3 9, MIDCPNIFTY DT 10, MIDCPNIFTY H60 59 with a net LOSS of -Rs 3,490, SENSEX RR2 5,
  NIFTY RR2 5); lowest q 0.099. **0 pass.**
- MIDCPNIFTY was the only index positive on several settings (3m, 5m, 15m, 75m, day HTF, swing 3, daily trend), but
  never with 40 trades and a significant twin; HD's +Rs 48,830 came mostly from Jan-Mar 2026 (folds -3,523 / -6,087 /
  +6,363 / +42,617).
- Selection walk-forward (pick the best variant before each fold): indices -Rs 63,100 out of fold vs A5 -Rs 29,837;
  MCX -Rs 1,75,108 vs A5 -Rs 34,021. Choosing settings makes it worse.

## Holdout (1 Apr - 19 Oct 2026, read once)

No design passer, so no paper candidate could arise. Read for all 99 cells, descriptively:

- Pooled indices: 1m -Rs 36,278 (214 trades), 5m -Rs 17,573 (42), 15m / day HTF -Rs 47,236 (65), 5m / 60m +Rs 19,679
  (92; it lost Rs 1.19 lakh in design), swing 3 +Rs 13,804 (34; lost in design). MCX: every setting negative (1m
  -Rs 3.56 lakh on 644; 5m -Rs 75,465 on 97).
- 26 of 99 holdout cells were positive, 19 of them design losers, all small (largest NIFTY swing 3 +Rs 19,627 on 10
  trades). One holdout cell had twin p < 0.05 (BANKNIFTY 5m / 60m, +Rs 442 on 18); BH q over the 99 is 1.00 everywhere.
- The design leader MIDCPNIFTY day HTF: -Rs 25,462 (9 trades). Every cell and fold: `results/report.md`.

## Same days as the running arms (`arms.json`; indices; "r89_trades" there means R90's trades)

| | design | holdout |
|---|---|---|
| Breadth Donchian (R72 C1) on the same stores | +Rs 2,16,929 (3,071 trades) | +Rs 1,69,712 (1,225) |
| Liquidity BANKNIFTY 15+5 | +Rs 1,10,094 (336) | +Rs 8,211 (136) |
| R89 base (PDH / PDL 1m break) | -Rs 3,07,305 (2,925) | -Rs 1,27,922 (1,210) |
| R90 5m on a Breadth Donchian day (same side / opposite) | 82% (76% / 64%) | 90% (74% / 50%) |
| R90 5m on an ORB-signal day / an R89 day | 96% / 61% | 93% / 69% |
| R90 5m daily correlation with Breadth Donchian, per index | +0.01 to +0.08 | -0.08 to +0.17 |
| R90 5m net on Breadth Donchian days / other days | -19,585 (62) / -26,151 (14) | -22,857 (38) / +5,284 (4) |
| R90 1m net on Breadth Donchian days / other days | -1,01,900 (482) / -3,248 (108) | -11,456 (173) / -24,823 (41) |

R90 trades on the same busy days as the arms (often with an arm trade on the other side that day too: 50-64%), with
near-zero daily correlation, and loses on both kinds of day. It would add losses, not diversification.

## Compared with earlier work

- **R89 / R89B (PDH / PDL break + candle):** the same wall. R89 lost on false breaks (a third stopped within 3 candles)
  and on a move smaller than the option's cost; R90 has few false breaks (0-9% on the indices) but a target too far to reach
  intraday, so the option bleeds until 15:15. Both are no better than random entry timing.
- **R52 R4-8 (Gann 2-bar swing break, 15m, 30-minute exit):** +Rs 38,895 on 242 trades, twin p 0.13, failed. R90's
  swing breaks with an HTF filter and a swing target do no better.
- **R85 / R87 / R88:** size is predictable, direction is not; a swing break is a direction call and shares that fate.
- **R86:** breaks of the day's extremes tend to continue; R90's BOS entries are continuation entries, but the
  continuation is too small and slow for a bought option within the day.

## Is it still useful?

- **As a chart overlay: yes, harmless and honest.** The structure engine (swing highs / lows, trend, BOS / CHoCH) is
  look-ahead-free once a swing's right-hand bars close (24 tests). Boss can use it to read context on the chart, like
  PDH / PDL. It should show structure and levels only: no entry / stop / target boxes, no alerts, no arm.
- **As a trading signal: no.** Nothing to send to paper, so no APP_SPEC. If Boss wants the swing-trade version it was
  designed for (held for days on futures), that is a different study (overnight risk, futures costs, Boss's no-overnight
  practice) and needs its own pre-registration.

## Honest limits

- SOURCE.md is a written-out rule set, not the Pine itself; where it was silent the choices are in PREREG 3 and NOTES N1
  (e.g. the protected level after a CHoCH is the failed leg's extreme; the protected low of a new leg is the lowest swing
  low between the two highs). A different reading of those details could change single trades, not the cost wall.
- MCX bars are close-only (wicks understated, about 78% of the true 5m range), so MCX swings and stops are approximate.
  The intraday structure resets at each MCX contract change; the daily trend (DT) does not.
- The option is bought one minute after the underlying fill and sold one minute after the stop / target touch.
- The index spot stands in for futures in the "no costs" columns; the as-written column holds overnight with no gap
  risk management, as the chart does.
- The holdout overlaps periods other studies read for other ideas; 14-19 Oct is new to all.
- 11 variants is a small declared slice; ATM strikes, premium stops and longer holds were not run.
