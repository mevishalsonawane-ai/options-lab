# HUNT R3: the outside report's GOLDM "morning only" filter, tested on all the data we have

Date: 9 Oct 2026. Subject: `time_of_day_loss_analysis_2026-10-09.md`. It says the GOLDM system (EMA(8)/EMA(21) cross + ADX>15,
1 lot, CE on an up cross / PE on a down cross, 25% stop, 50% target, 60-min hold, 23:15 square-off, max 2 a day) made all of
its profit on entries from 09:00 to 14:59 IST: 10 of 10 wins, +Rs 16,425. It says entries from 15:00 on lost: 1 of 8 wins,
-Rs 4,115. It proposes taking morning signals only.

## Verdict

**It is a short-sample artifact. It is not a tradable edge for an option buyer with Rs 1 lakh and 1 lot.**
**Do not adopt "morning only". Do not open a paper arm on the strength of this.**

1. **The window was real, but it was a lucky one.** We re-ran their 24 Sep - 8 Oct 2026 window. Our signals matched 10 of their
   18 trades exactly (same date, same bar, same side). Mornings really were good: our 18 morning trades made +Rs 11.5k at
   their Rs 100 cost, with 14 wins.
   - Across 293 rolling 11-session windows in the past year, "mornings green, evenings red" showed up in **about 1 window in 4**.
   - A morning total of Rs 10k or more showed up in **4-5% of windows**. Their window is one of the best of the year.
   - A morning win rate of 80% or more almost never happens: 0% of windows with their costs, 5% with ours.
2. **Over a full year, morning-only loses money after real costs.** We used 1 Oct 2025 - 8 Oct 2026, 5-min bars, ATM, clean
   fills, Zerodha charges, and a spread of 0.80% before 17:00 and 0.40% after.
   - Morning-only: **222 trades, 43% wins, -Rs 274 a trade, -Rs 232 a day, -Rs 60.8k in total.**
   - Worst drawdown -Rs 70k. Only 3 of 13 months were green.
   - With 1-ITM it is -Rs 187 a trade (-Rs 39k). Every timeframe (5, 15 and 60 min) and both strikes lose.
   - Aug-Sep 2025 loses too: morning -Rs 3.3k on 18 trades.
3. **Before costs, the morning edge is about zero.** Gross per morning trade was +Rs 35 (ATM) and +Rs 123 (1-ITM). Costs were
   about Rs 310 a trade in the morning. That is Rs 89 of charges plus about Rs 220 of spread, on a Rs 26k median premium.
   - Even with zero spread (charges only), morning ATM is -Rs 54 a trade. 1-ITM is +Rs 34 a trade, CI -164 to +240. That
     is break-even at best.
4. **Morning is not reliably better than evening at the option level.** Over the year, the morning-minus-evening gap per
   trade is -114 (5m ATM), +115 (5m 1-ITM), +19 (15m ATM), -256 (15m 1-ITM), -220 (60m ATM) and -185 (60m 1-ITM).
   Every 95% CI spans zero, and the sign flips between configurations.
5. **At the futures level the gap is small, and it is just gold's daytime drift.** GOLDM 5-min, Aug 2025 - Oct 2026: morning
   signals average **+1.0 bp** at 60 minutes (CI -0.7 to +2.7, n=529). Evening signals average **-0.1 bp** (n=862). The
   gap is +1.1 bp, CI -1.3 to +3.5.
   - The report's "+5.0 bp vs -3.0 bp" is the size we also see in its own window (+6.8 vs +1.8 bp on GOLDM NOV). It is not
     the size over a year.
   - The morning gain comes from longs: +2.4 bp on longs against -0.6 bp on shorts. A **random LONG** at the same morning
     bar times earned +1.2 bp. So "morning signals work" is mostly "gold drifted up in Indian daytime during the 2025-26
     bull run".
   - Even if the gap were real, 1 bp of futures is about 0.2% of an ATM premium (delta 0.5 x leverage about 37). Costs
     are about 1.2% of premium in the morning.
6. **Long history (XAUUSD proxy, MCX hours in IST) shows no morning effect.** 
   - XAUUSD 2015 - Sep 2026, every MCX session (2,976 days), 60-min forward return after the same signal:
   - 5-min: morning -0.0 bp (n=7189) vs evening -0.3 bp (n=10448); morning beat evening in 8 of 12 years (AM-PM bp by year: 2015 +0.3, 2016 +0.2, 2017 +0.8, 2018 +0.1, 2019 +1.3, 2020 +1.1, 2021 -0.1, 2022 -0.7, 2023 -1.2, 2024 -0.8, 2025 +0.4, 2026 +1.2)
   - 15-min: morning -0.4 bp (n=2231) vs evening +0.1 bp (n=3590); morning beat evening in 5 of 12 years (AM-PM bp by year: 2015 +0.0, 2016 +0.6, 2017 +1.7, 2018 -0.8, 2019 -1.0, 2020 +0.5, 2021 -2.6, 2022 +2.4, 2023 -0.8, 2024 -0.2, 2025 -0.0, 2026 -7.2)
   - 60-min: morning -0.7 bp (n=633) vs evening -0.8 bp (n=844); morning beat evening in 9 of 12 years (AM-PM bp by year: 2015 +0.0, 2016 +5.2, 2017 +4.5, 2018 +2.8, 2019 +0.3, 2020 +1.4, 2021 -5.6, 2022 +5.7, 2023 -7.2, 2024 +2.5, 2025 +1.4, 2026 -9.9)
   - On 5-min bars (the report's timeframe), every year's gap sits between -1.2 and +1.3 bp. Only 2019 clears zero (+1.3 bp, CI +0.1 to +2.6), which is about 1 year in 12 by chance.
   - On 60-min bars the yearly gaps swing from -10 to +6 bp, with few signals and CIs that include 0 in almost every year. 2023 goes clearly the other way (CI excludes 0), and 2026 nearly does.
   - Pooled over 12 years the gap is +0.2 bp (5m), -0.5 bp (15m) and +0.2 bp (60m). None of these is an edge.
7. **The report's own caveats are right, and they decide the case.**
   - 19 trades is a tiny sample.
   - The 15:00 cutoff was picked after seeing the data.
   - The "stronger" futures check (50 vs 72 signals) came from the same short bull-run stretch.
   - Two of their trades exit at exactly the entry price (6,190 to 6,190 and 4,350 to 4,350). That looks like stale prints.
   - Their 24-25 Sep premiums (Rs 6,190 / 6,500 per 10 g) are not the near-month series. That series had 1 day left and
     traded at Rs 600-900. Their PE premiums (about Rs 4,000) are far above our near-month ATM / 1-ITM PEs (Rs 2,350-3,050).
     So their rupee numbers cannot be checked trade for trade.

**Paper-arm rules:** none. The filter fails, so no paper arm is proposed. If Boss still wants a no-money shadow log, freeze
the spec in the "Frozen spec" section below and judge it only on 40+ new morning trades at real costs. Kill it if morning
net/trade < 0 after 40 trades.

### Practical points found on the way
- **Fills.** GOLDM options had **no trade in the entry minute (or the next 2) for 37% of signals**: 224 of 603 in the year,
  ATM. A real order there either fills at a stale, wide price or not at all. The RAW-fill rows (enter at whatever print)
  are worse, not better. Stale prints hurt the naive backtest.
- **The 25% stop and 50% target almost never trigger within 60 minutes.** 95% of exits are the 60-minute time exit. So the
  system is really "hold 60 minutes".
- **Signal source matters a little.** On 7 May - 8 Oct 2026, signals from the real-OHLC GOLDM NOV futures did better than
  our close-only continuous series. Morning 1-ITM: +Rs 140 a trade (CI -185 to +481) against -61. This is one of 36+
  cells, it is post-hoc, its CI spans zero, and it sits in the same bull stretch. It is not evidence of an edge.

## Method (fixed before results)
- **Signal.** Bars anchored at 09:00 IST. Labelled by bar start, as the report's times are (all are multiples of 5 min,
  which points to 5-min bars). EMA = ewm(span, adjust=False) on closes. ADX(14) Wilder. Indicators carry over night.
  A cross is a sign change of EMA8-EMA21 on the bar close with ADX > 15. Morning = signal bar starting 09:00-14:55.
  Evening = 15:00 on.
- **Futures level.** Return from the signal bar close to the close 60 minutes later, capped at 23:15, in bp, signed by
  side. 95% CIs use a day-block bootstrap.
  - Baseline: the mean 60-min return of every bar at the same bar time in the same calendar year, signed by side. This is
    the "base" column in the scripts. Excess is around 0 everywhere.
  - "Random LONG" = the unsigned mean at the morning or evening bar times.
- **Series.**
  - GOLDM_C / GOLD_C: Dhan rollingoption `spot` (continuous near-month futures), Aug 2025 - 9 Oct 2026. Close only, so bar
    high/low come from minute closes. Rolls are back-adjusted by the jump net of the XAUUSD (or GOLDM NOV) overnight gap.
    The GOLD_C roll adjustment is noisy; treat GOLD as a weak check.
  - GOLDM NOV and GOLDPETAL OCT: single live contracts with real OHLC, May - Oct 2026. Dhan serves no expired MCX futures,
    and rollingoption for MCX starts in late Jul 2025, so **no Dhan series goes further back**.
  - Long history uses XAUUSD 1-min in MCX hours (09:00 - 23:30 IST in US summer, 23:55 in US winter). Sources:
    histdata.com Jan 2015 - Sep 2023 (New York time with DST; it checks out against Dukascopy UTC at corr 0.94 per minute)
    and Dukascopy bid Oct 2023 - Sep 2026. Its hourly returns correlate 0.80 with GOLDM over 293 common days. It has no
    USDINR and no MCX microstructure.
- **Options.** GOLDM near-month ATM-3..+3 1-min OHLC+volume: M3's file plus a fresh Dhan pull of 20 Sep - 9 Oct 2026.
  - Strike: ATM = Dhan's ATM strike at the entry minute; 1-ITM = one strike in the money. Put-call parity checks out: the
    implied forward sits within 0.05% of spot.
  - Entry: the open of the minute after the signal bar closes. CLEAN = that minute (or one of the next 2) must have
    volume; else no trade.
  - Exits: low <= 75% / high >= 150% of entry, on traded minutes only. The stop is checked first. A gap fills at the open.
    Otherwise the last traded close at <= entry + 60 min, or at 23:15.
  - One position at a time, max 2 entries a day. ALL, AM and PM are each run as their own system.
  - Costs: McxCosts.kt (Rs 20/order, CTT 0.05% sell, MCX 0.0418%, SEBI, stamp 0.003% buy, GST 18%) on premium x 10. Plus
    half the spread on each side: 0.80% of premium before 17:00 and 0.40% after (MCX_OPTIONS), with 0.60/0.30% and 0 as
    sensitivity. `hunt/m3/work/spreads.json` is still a placeholder (0.58/0.29%), so no measured GOLDM spread exists yet.
  - Expiry days are kept (the report trades them). A skip-expiry variant is shown.

Scripts: `research/hunt/r3/` (series.py, fut.py, fut_md.py, opt.py, opt_tables.py, opt_report.py, windows.py,
fetch_dhan.py, fetch_histdata.py, assemble.py; fetch_duka_xau*.py were too slow under throttling and are not used). Data and work files: `scratchpad/hunt/r3/`.

## Frozen spec (only if a no-money shadow log is wanted)
GOLDM near-month futures, 5-min bars from 09:00 IST, EMA8/EMA21 cross on the bar close with ADX(14) > 15. Only bars starting
09:00-14:55. Buy 1 lot of the 1-ITM near-month CE (up) / PE (down) at the next minute; skip if no trade within 3 minutes.
Exit at -25% / +50% on the option, or 60 minutes, or 23:15. Max 2 a day, one at a time. Log the real fill, the real exit
and the bid/ask at both.


## Tables

### A. Futures level: 60-min forward return after the signal, morning vs evening (bp)


#### GOLDM near-month futures (Dhan, close-only bars), Aug 2025 - Oct 2026

| bars | year | AM n | AM bp [95% CI] | AM hit | PM n | PM bp [95% CI] | PM hit | AM-PM [95% CI] | AM longs / shorts bp | random LONG at AM bar times | random LONG PM |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 5m | 2025 | 176 | +1.2 [-1.6, +4.5] | 45% | 327 | -0.1 [-2.0, +2.0] | 43% | +1.3 [-2.3, +5.2] | +3.4 / -1.0 | +1.96 | +0.58 |
| 5m | 2026 | 353 | +0.8 [-1.1, +2.8] | 52% | 535 | -0.2 [-2.7, +2.4] | 48% | +1.0 [-2.3, +4.3] | +2.0 / -0.4 | +0.75 | -0.15 |
| 5m | all | 529 | +1.0 [-0.7, +2.7] | 50% | 862 | -0.1 [-1.9, +1.7] | 46% | +1.1 [-1.3, +3.5] | +2.4 / -0.6 | +1.17 | +0.11 |
| 15m | 2025 | 65 | +6.1 [+0.0, +14.0] | 52% | 97 | +1.0 [-3.8, +6.3] | 51% | +5.2 [-3.0, +13.8] | +6.6 / +5.5 | +1.88 | +0.64 |
| 15m | 2026 | 135 | -0.2 [-3.7, +3.6] | 44% | 204 | -4.1 [-8.8, +0.5] | 40% | +3.9 [-2.1, +9.6] | +1.6 / -2.3 | +1.01 | -0.43 |
| 15m | all | 200 | +1.8 [-1.5, +5.3] | 47% | 301 | -2.5 [-6.0, +1.1] | 44% | +4.3 [-0.5, +9.5] | +3.3 / +0.1 | +1.31 | -0.06 |
| 60m | 2025 | 18 | +5.7 [-2.5, +14.2] | 50% | 18 | +4.7 [-8.1, +18.6] | 50% | +1.0 [-15.3, +16.8] | +15.9 / -0.7 | +1.75 | +0.66 |
| 60m | 2026 | 54 | +0.4 [-5.5, +6.6] | 50% | 51 | +2.4 [-7.5, +13.7] | 43% | -2.1 [-15.1, +9.5] | -3.1 / +4.1 | +0.80 | -0.46 |
| 60m | all | 72 | +1.7 [-3.3, +6.8] | 50% | 69 | +3.0 [-4.7, +11.6] | 45% | -1.3 [-11.3, +7.9] | +0.7 / +2.6 | +1.12 | -0.08 |

5-min, by entry hour (all years): 09h +2.5 bp (n=111), 10h -3.1 bp (n=44), 11h +1.8 bp (n=101), 12h +1.5 bp (n=82), 13h +0.2 bp (n=100), 14h +0.4 bp (n=91), 15h -2.1 bp (n=109), 16h +1.0 bp (n=121), 17h -2.9 bp (n=130), 18h +1.0 bp (n=104), 19h -2.2 bp (n=137), 20h +8.7 bp (n=82), 21h +2.0 bp (n=86), 22h -3.3 bp (n=81), 23h -5.6 bp (n=12)

5-min, report window 24 Sep - 8 Oct 2026: AM n=22, +6.9 bp, hit 59%; PM n=31, +1.5 bp, hit 48%

#### GOLDM NOV futures (real OHLC), May - Oct 2026

| bars | year | AM n | AM bp [95% CI] | AM hit | PM n | PM bp [95% CI] | PM hit | AM-PM [95% CI] | AM longs / shorts bp | random LONG at AM bar times | random LONG PM |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 5m | 2026 | 240 | +2.0 [-0.3, +4.5] | 50% | 374 | -1.1 [-3.4, +1.3] | 44% | +3.1 [-0.5, +6.3] | +0.8 / +3.2 | -0.09 | +0.32 |
| 5m | all | 240 | +2.0 [-0.3, +4.5] | 50% | 374 | -1.1 [-3.4, +1.3] | 44% | +3.1 [-0.5, +6.3] | +0.8 / +3.2 | -0.09 | +0.32 |
| 15m | 2026 | 92 | +0.7 [-2.6, +4.2] | 51% | 127 | -1.7 [-6.4, +2.9] | 42% | +2.4 [-3.6, +8.2] | +0.1 / +1.3 | +0.06 | +0.31 |
| 15m | all | 92 | +0.7 [-2.6, +4.2] | 51% | 127 | -1.7 [-6.4, +2.9] | 42% | +2.4 [-3.6, +8.2] | +0.1 / +1.3 | +0.06 | +0.31 |
| 60m | 2026 | 21 | -1.9 [-8.6, +5.0] | 48% | 26 | +5.1 [-4.4, +15.9] | 42% | -7.0 [-19.7, +4.6] | -3.0 / -0.6 | +0.26 | +0.10 |
| 60m | all | 21 | -1.9 [-8.6, +5.0] | 48% | 26 | +5.1 [-4.4, +15.9] | 42% | -7.0 [-19.7, +4.6] | -3.0 / -0.6 | +0.26 | +0.10 |

5-min, by entry hour (all years): 09h +1.4 bp (n=45), 10h +2.9 bp (n=26), 11h +1.5 bp (n=49), 12h +3.9 bp (n=39), 13h -2.4 bp (n=49), 14h +7.1 bp (n=32), 15h -1.6 bp (n=43), 16h -1.6 bp (n=43), 17h +2.3 bp (n=58), 18h -1.3 bp (n=41), 19h +1.0 bp (n=56), 20h -2.5 bp (n=49), 21h -1.4 bp (n=32), 22h -6.2 bp (n=46), 23h +4.8 bp (n=6)

5-min, report window 24 Sep - 8 Oct 2026: AM n=25, +6.8 bp, hit 68%; PM n=36, +1.8 bp, hit 50%

#### GOLD near-month futures (Dhan, close-only), Aug 2025 - Oct 2026

| bars | year | AM n | AM bp [95% CI] | AM hit | PM n | PM bp [95% CI] | PM hit | AM-PM [95% CI] | AM longs / shorts bp | random LONG at AM bar times | random LONG PM |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 5m | 2025 | 154 | +3.1 [+0.2, +6.7] | 47% | 266 | +0.6 [-1.7, +3.2] | 41% | +2.5 [-1.5, +7.0] | +5.3 / +0.9 | +2.04 | +1.36 |
| 5m | 2026 | 175 | +0.8 [-3.0, +4.7] | 49% | 283 | +2.5 [-0.8, +6.9] | 49% | -1.7 [-7.6, +3.7] | +4.8 / -3.2 | +0.68 | -0.45 |
| 5m | all | 329 | +1.9 [-0.5, +4.4] | 48% | 549 | +1.6 [-0.5, +4.1] | 46% | +0.3 [-3.2, +3.7] | +5.0 / -1.3 | +1.40 | +0.44 |
| 15m | 2025 | 63 | +3.8 [-0.5, +8.9] | 60% | 84 | -1.9 [-7.2, +4.1] | 40% | +5.7 [-1.9, +13.1] | +4.6 / +2.9 | +1.88 | +1.17 |
| 15m | 2026 | 81 | +0.5 [-2.6, +3.6] | 51% | 129 | -5.1 [-15.0, +2.1] | 39% | +5.5 [-2.2, +16.2] | -0.4 / +1.3 | +1.27 | -0.35 |
| 15m | all | 144 | +1.9 [-0.7, +4.8] | 55% | 213 | -3.8 [-10.3, +1.3] | 39% | +5.8 [-0.4, +12.4] | +1.9 / +2.0 | +1.57 | +0.33 |
| 60m | 2025 | 20 | -3.7 [-15.0, +7.7] | 35% | 19 | -0.1 [-12.6, +15.1] | 47% | -3.6 [-22.9, +13.7] | +7.1 / -14.5 | +1.98 | +1.28 |
| 60m | 2026 | 27 | +0.4 [-8.0, +9.1] | 41% | 38 | -14.5 [-45.4, +7.6] | 34% | +14.9 [-8.3, +48.4] | +2.3 / -4.3 | +1.32 | -0.69 |
| 60m | all | 47 | -1.4 [-8.1, +6.0] | 38% | 57 | -9.7 [-30.7, +5.9] | 39% | +8.3 [-9.1, +30.3] | +4.0 / -10.0 | +1.59 | +0.09 |

5-min, by entry hour (all years): 09h +0.3 bp (n=71), 10h +2.3 bp (n=43), 11h +3.3 bp (n=64), 12h +1.8 bp (n=45), 13h +2.8 bp (n=47), 14h +1.4 bp (n=59), 15h -3.7 bp (n=53), 16h +1.4 bp (n=59), 17h +0.9 bp (n=82), 18h +3.2 bp (n=83), 19h +5.5 bp (n=92), 20h +3.1 bp (n=68), 21h -0.7 bp (n=57), 22h -0.5 bp (n=47), 23h -1.6 bp (n=8)

5-min, report window 24 Sep - 8 Oct 2026: AM n=15, -4.0 bp, hit 33%; PM n=26, +1.1 bp, hit 50%

#### GOLDPETAL OCT futures (real OHLC), May - Oct 2026

| bars | year | AM n | AM bp [95% CI] | AM hit | PM n | PM bp [95% CI] | PM hit | AM-PM [95% CI] | AM longs / shorts bp | random LONG at AM bar times | random LONG PM |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 5m | 2026 | 287 | -0.3 [-2.1, +1.6] | 49% | 443 | -1.7 [-3.1, -0.2] | 40% | +1.4 [-0.9, +3.8] | -0.7 / +0.1 | -0.13 | +0.10 |
| 5m | all | 287 | -0.3 [-2.1, +1.6] | 49% | 443 | -1.7 [-3.1, -0.2] | 40% | +1.4 [-0.9, +3.8] | -0.7 / +0.1 | -0.13 | +0.10 |
| 15m | 2026 | 86 | +0.7 [-2.6, +4.4] | 55% | 126 | -2.0 [-5.9, +2.0] | 44% | +2.8 [-2.5, +8.0] | -0.8 / +2.3 | +0.00 | +0.09 |
| 15m | all | 86 | +0.7 [-2.6, +4.4] | 55% | 126 | -2.0 [-5.9, +2.0] | 44% | +2.8 [-2.5, +8.0] | -0.8 / +2.3 | +0.00 | +0.09 |
| 60m | 2026 | 23 | +1.0 [-5.8, +7.7] | 52% | 32 | +0.8 [-8.1, +11.8] | 44% | +0.2 [-12.4, +11.7] | -0.4 / +2.1 | +0.67 | -0.09 |
| 60m | all | 23 | +1.0 [-5.8, +7.7] | 52% | 32 | +0.8 [-8.1, +11.8] | 44% | +0.2 [-12.4, +11.7] | -0.4 / +2.1 | +0.67 | -0.09 |

5-min, by entry hour (all years): 09h -2.5 bp (n=55), 10h +3.6 bp (n=23), 11h +0.9 bp (n=51), 12h -1.0 bp (n=59), 13h +0.9 bp (n=48), 14h -1.4 bp (n=51), 15h -1.1 bp (n=61), 16h -2.0 bp (n=53), 17h -1.9 bp (n=75), 18h +1.0 bp (n=49), 19h -2.0 bp (n=51), 20h -3.4 bp (n=51), 21h -1.9 bp (n=51), 22h -3.3 bp (n=40), 23h +1.3 bp (n=12)

5-min, report window 24 Sep - 8 Oct 2026: AM n=21, +6.8 bp, hit 71%; PM n=39, +1.8 bp, hit 51%

#### XAUUSD spot proxy, 1-min (histdata.com Jan 2015 - Sep 2023, Dukascopy Oct 2023 - Sep 2026), MCX hours IST

| bars | year | AM n | AM bp [95% CI] | AM hit | PM n | PM bp [95% CI] | PM hit | AM-PM [95% CI] | AM longs / shorts bp | random LONG at AM bar times | random LONG PM |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 5m | 2015 | 598 | -0.3 [-1.4, +0.8] | 48% | 923 | -0.6 [-1.8, +0.7] | 48% | +0.3 [-1.3, +2.0] | -1.9 / +1.1 | -0.62 | -0.38 |
| 5m | 2016 | 640 | -0.2 [-1.2, +0.8] | 48% | 981 | -0.4 [-1.4, +0.7] | 47% | +0.2 [-1.3, +1.6] | +0.4 / -0.9 | +0.07 | -0.06 |
| 5m | 2017 | 622 | -0.3 [-0.9, +0.4] | 48% | 1002 | -1.1 [-1.8, -0.4] | 43% | +0.8 [-0.1, +1.8] | -0.4 / -0.1 | -0.11 | +0.33 |
| 5m | 2018 | 648 | +0.1 [-0.6, +0.7] | 51% | 905 | -0.0 [-0.8, +0.8] | 49% | +0.1 [-0.9, +1.1] | -0.2 / +0.4 | -0.02 | -0.21 |
| 5m | 2019 | 600 | +0.8 [-0.1, +1.7] | 52% | 916 | -0.6 [-1.5, +0.4] | 47% | +1.3 [+0.1, +2.6] | +1.1 / +0.5 | +0.24 | +0.16 |
| 5m | 2020 | 643 | -0.1 [-1.5, +1.4] | 48% | 898 | -1.2 [-2.7, +0.4] | 46% | +1.1 [-1.1, +3.2] | -0.2 / -0.0 | +0.72 | +0.18 |
| 5m | 2021 | 623 | -0.3 [-1.2, +0.7] | 47% | 849 | -0.2 [-1.6, +1.3] | 46% | -0.1 [-2.0, +1.6] | -1.2 / +0.7 | -0.13 | -0.41 |
| 5m | 2022 | 602 | -0.0 [-1.1, +1.1] | 49% | 921 | +0.6 [-0.8, +2.1] | 49% | -0.7 [-2.5, +1.2] | -0.9 / +0.8 | -0.06 | +0.00 |
| 5m | 2023 | 608 | -0.4 [-1.1, +0.4] | 48% | 676 | +0.8 [-0.4, +2.2] | 47% | -1.2 [-2.8, +0.3] | -0.3 / -0.5 | +0.07 | -0.46 |
| 5m | 2024 | 522 | -0.3 [-1.2, +0.8] | 46% | 798 | +0.6 [-0.7, +1.7] | 52% | -0.8 [-2.3, +0.8] | -0.5 / -0.0 | +0.07 | -0.05 |
| 5m | 2025 | 615 | +0.2 [-1.1, +1.5] | 50% | 889 | -0.2 [-1.6, +1.2] | 46% | +0.4 [-1.6, +2.3] | +0.9 / -0.6 | +0.63 | +0.63 |
| 5m | 2026 | 468 | +0.5 [-1.6, +2.7] | 50% | 690 | -0.7 [-3.0, +1.8] | 51% | +1.2 [-2.0, +4.3] | +0.8 / +0.1 | -0.57 | -0.05 |
| 5m | all | 7189 | -0.0 [-0.4, +0.3] | 49% | 10448 | -0.3 [-0.6, +0.1] | 47% | +0.2 [-0.3, +0.7] | -0.2 / +0.1 | +0.03 | -0.02 |
| 15m | 2015 | 210 | -0.1 [-1.7, +1.6] | 47% | 332 | -0.2 [-2.6, +2.5] | 48% | +0.0 [-2.9, +3.1] | -1.2 / +1.0 | -0.60 | -0.39 |
| 15m | 2016 | 189 | -0.3 [-2.4, +1.9] | 43% | 302 | -0.8 [-3.4, +1.9] | 46% | +0.6 [-2.8, +4.0] | +0.6 / -1.1 | +0.09 | -0.07 |
| 15m | 2017 | 163 | +0.4 [-0.9, +1.6] | 51% | 338 | -1.3 [-3.1, +0.6] | 43% | +1.7 [-0.7, +3.8] | +0.2 / +0.6 | -0.10 | +0.32 |
| 15m | 2018 | 218 | -0.4 [-1.6, +1.0] | 50% | 299 | +0.4 [-1.3, +2.3] | 50% | -0.8 [-3.1, +1.5] | -1.4 / +0.8 | -0.02 | -0.22 |
| 15m | 2019 | 199 | +0.8 [-0.5, +2.2] | 51% | 331 | +1.8 [-0.3, +4.2] | 52% | -1.0 [-3.7, +1.5] | +1.0 / +0.7 | +0.25 | +0.15 |
| 15m | 2020 | 197 | -1.7 [-4.7, +1.6] | 44% | 350 | -2.2 [-5.4, +1.3] | 45% | +0.5 [-4.2, +5.1] | -1.2 / -2.2 | +0.68 | +0.21 |
| 15m | 2021 | 190 | -1.9 [-3.6, -0.2] | 41% | 296 | +0.7 [-2.0, +3.5] | 47% | -2.6 [-5.8, +0.6] | -2.2 / -1.6 | -0.09 | -0.43 |
| 15m | 2022 | 184 | +2.0 [+0.1, +3.9] | 55% | 303 | -0.4 [-2.7, +2.1] | 51% | +2.4 [-0.8, +5.5] | +1.8 / +2.1 | -0.05 | +0.02 |
| 15m | 2023 | 152 | -0.7 [-2.4, +1.1] | 52% | 246 | +0.1 [-2.6, +2.8] | 45% | -0.8 [-4.0, +2.5] | -0.4 / -1.1 | +0.04 | -0.44 |
| 15m | 2024 | 169 | -0.5 [-2.6, +2.0] | 50% | 270 | -0.3 [-3.5, +2.8] | 46% | -0.2 [-4.3, +3.7] | -0.2 / -0.8 | +0.10 | -0.09 |
| 15m | 2025 | 206 | -0.0 [-2.7, +2.9] | 47% | 280 | -0.0 [-2.7, +3.0] | 47% | -0.0 [-4.1, +3.8] | -0.5 / +0.5 | +0.63 | +0.65 |
| 15m | 2026 | 154 | -3.1 [-6.6, +0.4] | 48% | 243 | +4.2 [-2.4, +11.3] | 55% | -7.2 [-15.4, +0.1] | -4.6 / -1.5 | -0.58 | -0.02 |
| 15m | all | 2231 | -0.4 [-1.0, +0.2] | 48% | 3590 | +0.1 [-0.8, +1.0] | 48% | -0.5 [-1.6, +0.6] | -0.7 / -0.1 | +0.04 | -0.02 |
| 60m | 2015 | 54 | -1.0 [-4.2, +2.5] | 43% | 67 | -1.0 [-5.0, +3.3] | 43% | +0.0 [-5.5, +5.3] | -1.2 / -0.8 | -0.81 | -0.25 |
| 60m | 2016 | 60 | -0.6 [-4.9, +4.6] | 40% | 70 | -5.8 [-11.2, -0.5] | 39% | +5.2 [-1.8, +12.9] | -3.0 / +2.6 | +0.11 | -0.07 |
| 60m | 2017 | 73 | +0.4 [-1.6, +2.6] | 48% | 76 | -4.0 [-8.6, +0.5] | 42% | +4.5 [-0.4, +9.5] | -1.6 / +2.4 | -0.04 | +0.28 |
| 60m | 2018 | 55 | +0.0 [-2.9, +2.9] | 38% | 74 | -2.8 [-6.0, +0.1] | 45% | +2.8 [-1.5, +7.1] | -0.3 / +0.3 | +0.03 | -0.19 |
| 60m | 2019 | 51 | -0.9 [-2.6, +1.0] | 53% | 74 | -1.1 [-4.8, +2.9] | 42% | +0.3 [-4.3, +4.3] | +0.3 / -2.0 | +0.30 | +0.11 |
| 60m | 2020 | 51 | +0.7 [-5.7, +6.7] | 57% | 81 | -0.7 [-6.8, +6.5] | 46% | +1.4 [-8.2, +10.0] | +6.2 / -4.1 | +0.52 | +0.40 |
| 60m | 2021 | 53 | +0.2 [-3.1, +4.2] | 49% | 77 | +5.8 [+0.7, +11.1] | 51% | -5.6 [-11.8, +0.7] | -2.8 / +4.2 | -0.12 | -0.33 |
| 60m | 2022 | 48 | +4.1 [-0.3, +8.5] | 54% | 79 | -1.6 [-6.2, +3.6] | 44% | +5.7 [-0.8, +12.2] | +4.1 / +4.1 | -0.08 | +0.14 |
| 60m | 2023 | 53 | -3.5 [-7.9, +0.3] | 45% | 62 | +3.8 [-0.7, +8.7] | 50% | -7.2 [-14.0, -1.2] | -1.6 / -5.4 | +0.11 | -0.17 |
| 60m | 2024 | 40 | +0.2 [-3.4, +4.0] | 50% | 76 | -2.2 [-7.6, +3.5] | 45% | +2.5 [-4.0, +9.1] | +0.7 / -0.2 | +0.20 | -0.22 |
| 60m | 2025 | 45 | +1.2 [-4.9, +7.9] | 49% | 57 | -0.2 [-6.6, +7.2] | 47% | +1.4 [-8.0, +10.6] | +6.1 / -4.0 | +0.77 | +0.55 |
| 60m | 2026 | 50 | -8.9 [-16.1, -2.0] | 38% | 51 | +1.1 [-7.4, +10.9] | 39% | -9.9 [-22.3, +1.4] | -11.0 / -7.1 | -0.58 | -0.06 |
| 60m | all | 633 | -0.7 [-1.9, +0.6] | 47% | 844 | -0.8 [-2.3, +0.8] | 44% | +0.2 [-1.8, +2.2] | -0.5 / -0.8 | +0.04 | +0.02 |

5-min, by entry hour (all years): 09h -0.1 bp (n=1201), 10h -0.7 bp (n=539), 11h +0.2 bp (n=1353), 12h -0.7 bp (n=1440), 13h +1.1 bp (n=1461), 14h -0.6 bp (n=1195), 15h -0.6 bp (n=1097), 16h -0.5 bp (n=1219), 17h -0.4 bp (n=1577), 18h -0.9 bp (n=1658), 19h +0.7 bp (n=1577), 20h +0.0 bp (n=1244), 21h -0.4 bp (n=942), 22h -0.0 bp (n=929), 23h -1.3 bp (n=205)

5-min, report window 24 Sep - 8 Oct 2026: AM n=6, -11.6 bp, hit 33%; PM n=8, +1.1 bp, hit 62%

### B. Option level (GOLDM, 1 lot = 10 x premium)


### Oct25-Oct26: base costs (charges + spread 0.80% before 17:00 / 0.40% after), clean fills

| system | trades | win% | Rs/trade [95% CI] | Rs/day | total Rs | worst DD | green months | gross/trade | cost/trade |
|---|---|---|---|---|---|---|---|---|---|
| 5m ATM ALL | 379 | 39% | -322 [-503, -123] | -466 | -122,222 | -131,902 | 2/13 | -47 | 275 |
| 5m ATM AM | 222 | 43% | -274 [-487, -60] | -232 | -60,793 | -69,980 | 3/13 | +35 | 308 |
| 5m ATM PM | 295 | 39% | -160 [-396, +84] | -180 | -47,148 | -55,283 | 5/13 | +59 | 219 |
| 5m ITM1 ALL | 359 | 42% | -269 [-470, -63] | -369 | -96,599 | -108,618 | 2/13 | +12 | 281 |
| 5m ITM1 AM | 210 | 46% | -187 [-385, +18] | -150 | -39,320 | -49,599 | 5/13 | +123 | 310 |
| 5m ITM1 PM | 282 | 38% | -302 [-534, -46] | -325 | -85,122 | -93,224 | 4/13 | -69 | 233 |
| 15m ATM ALL | 224 | 40% | -415 [-622, -192] | -355 | -92,950 | -106,201 | 3/13 | -167 | 248 |
| 15m ATM AM | 99 | 46% | -311 [-590, -14] | -118 | -30,794 | -35,443 | 3/13 | -5 | 306 |
| 15m ATM PM | 152 | 38% | -330 [-658, +19] | -191 | -50,138 | -65,491 | 4/13 | -126 | 204 |
| 15m ITM1 ALL | 212 | 43% | -301 [-594, +49] | -243 | -63,753 | -92,557 | 3/13 | -35 | 266 |
| 15m ITM1 AM | 96 | 46% | -438 [-735, -136] | -160 | -42,026 | -45,813 | 4/13 | -103 | 335 |
| 15m ITM1 PM | 145 | 42% | -182 [-600, +362] | -101 | -26,401 | -55,445 | 4/13 | +33 | 215 |
| 60m ATM ALL | 82 | 33% | -444 [-793, -49] | -139 | -36,393 | -36,393 | 3/12 | -192 | 251 |
| 60m ATM AM | 39 | 33% | -595 [-1,013, -169] | -89 | -23,211 | -23,211 | 2/9 | -287 | 308 |
| 60m ATM PM | 45 | 31% | -376 [-913, +275] | -65 | -16,902 | -16,902 | 2/11 | -176 | 199 |
| 60m ITM1 ALL | 76 | 36% | -453 [-883, +14] | -132 | -34,464 | -36,694 | 6/12 | -203 | 251 |
| 60m ITM1 AM | 34 | 32% | -605 [-1,142, -76] | -79 | -20,583 | -21,290 | 3/11 | -277 | 329 |
| 60m ITM1 PM | 45 | 36% | -420 [-1,004, +234] | -72 | -18,903 | -21,834 | 5/11 | -234 | 186 |

### Aug-Sep25: base costs (charges + spread 0.80% before 17:00 / 0.40% after), clean fills

| system | trades | win% | Rs/trade [95% CI] | Rs/day | total Rs | worst DD | green months | gross/trade | cost/trade |
|---|---|---|---|---|---|---|---|---|---|
| 5m ATM ALL | 51 | 31% | -253 [-453, -29] | -323 | -12,923 | -15,959 | 0/2 | -114 | 139 |
| 5m ATM AM | 18 | 28% | -182 [-485, +165] | -82 | -3,275 | -6,310 | 1/2 | -22 | 159 |
| 5m ATM PM | 40 | 32% | -248 [-475, +23] | -248 | -9,934 | -12,856 | 1/2 | -120 | 129 |
| 5m ITM1 ALL | 47 | 40% | -116 [-322, +80] | -136 | -5,452 | -6,231 | 0/2 | +33 | 149 |
| 5m ITM1 AM | 20 | 35% | -166 [-421, +98] | -83 | -3,317 | -4,143 | 1/2 | -5 | 161 |
| 5m ITM1 PM | 33 | 45% | -169 [-505, +136] | -139 | -5,564 | -7,220 | 1/2 | -37 | 131 |
| 15m ATM ALL | 17 | 35% | -103 [-428, +255] | -44 | -1,756 | -4,739 | 1/2 | +32 | 135 |
| 15m ATM AM | 7 | 14% | -347 [-719, +157] | -61 | -2,427 | -2,610 | 0/2 | -174 | 173 |
| 15m ATM PM | 11 | 55% | +89 [-354, +506] | +24 | +975 | -2,129 | 1/2 | +199 | 110 |
| 15m ITM1 ALL | 19 | 47% | -91 [-468, +293] | -43 | -1,725 | -3,478 | 0/2 | +60 | 151 |
| 15m ITM1 AM | 9 | 56% | +14 [-414, +555] | +3 | +129 | -1,719 | 1/2 | +179 | 165 |
| 15m ITM1 PM | 11 | 45% | -149 [-709, +346] | -41 | -1,641 | -2,859 | 1/2 | -6 | 143 |
| 60m ATM ALL | 3 | 67% | -67 [-652, +314] | -5 | -200 | -652 | 1/2 | +48 | 115 |
| 60m ATM AM | 1 | 100% | +138 [+nan, +nan] | +3 | +138 | +0 | 1/1 | +290 | 152 |
| 60m ATM PM | 2 | 50% | -169 [+nan, +nan] | -8 | -338 | -652 | 0/1 | -72 | 97 |
| 60m ITM1 ALL | 4 | 25% | -231 [-904, +288] | -23 | -922 | -1,498 | 1/2 | -104 | 127 |
| 60m ITM1 AM | 2 | 0% | -300 [+nan, +nan] | -15 | -600 | -600 | 0/2 | -142 | 157 |
| 60m ITM1 PM | 2 | 50% | -161 [+nan, +nan] | -8 | -322 | -904 | 1/2 | -65 | 96 |

### Cost / fill sensitivity, 5-min, 1 Oct 2025 - 8 Oct 2026

| system | trades | win% | Rs/trade [95% CI] | Rs/day | total Rs | worst DD | green months | gross/trade | cost/trade |
|---|---|---|---|---|---|---|---|---|---|
| ATM ALL - base (0.80/0.40% spread) | 379 | 39% | -322 [-503, -123] | -466 | -122,222 | -131,902 | 2/13 | -47 | 275 |
| ATM AM - base (0.80/0.40% spread) | 222 | 43% | -274 [-487, -60] | -232 | -60,793 | -69,980 | 3/13 | +35 | 308 |
| ATM PM - base (0.80/0.40% spread) | 295 | 39% | -160 [-396, +84] | -180 | -47,148 | -55,283 | 5/13 | +59 | 219 |
| ATM ALL - M4 spread (0.60/0.30%) | 379 | 40% | -276 [-457, -77] | -399 | -104,532 | -115,009 | 2/13 | -47 | 229 |
| ATM AM - M4 spread (0.60/0.30%) | 222 | 45% | -219 [-433, -4] | -186 | -48,608 | -59,022 | 5/13 | +35 | 254 |
| ATM PM - M4 spread (0.60/0.30%) | 295 | 40% | -127 [-364, +119] | -143 | -37,476 | -46,072 | 5/13 | +59 | 186 |
| ATM ALL - charges only, no spread | 379 | 43% | -136 [-318, +68] | -196 | -51,463 | -66,447 | 5/13 | -47 | 88 |
| ATM AM - charges only, no spread | 222 | 48% | -54 [-270, +163] | -46 | -12,055 | -38,884 | 7/13 | +35 | 89 |
| ATM PM - charges only, no spread | 295 | 42% | -29 [-265, +222] | -32 | -8,459 | -39,049 | 5/13 | +59 | 87 |
| ATM ALL - RAW fills, charges only | 466 | 35% | -328 [-628, -61] | -583 | -152,657 | -173,528 | 4/13 | -240 | 88 |
| ATM AM - RAW fills, charges only | 289 | 40% | -121 [-374, +138] | -134 | -35,041 | -63,676 | 7/13 | -32 | 89 |
| ATM PM - RAW fills, charges only | 380 | 34% | -332 [-720, -5] | -481 | -126,017 | -136,584 | 5/13 | -244 | 87 |
| ATM ALL - RAW fills, report's flat Rs 100 | 466 | 35% | -340 [-640, -73] | -604 | -158,218 | -178,438 | 4/13 | -240 | 88 |
| ATM AM - RAW fills, report's flat Rs 100 | 289 | 40% | -132 [-384, +128] | -146 | -38,146 | -65,051 | 7/13 | -32 | 89 |
| ATM PM - RAW fills, report's flat Rs 100 | 380 | 34% | -344 [-733, -16] | -499 | -130,826 | -141,246 | 5/13 | -244 | 87 |
| ATM ALL - base, expiry days skipped | 360 | 39% | -314 [-488, -127] | -432 | -113,068 | -122,748 | 2/13 | -31 | 283 |
| ATM AM - base, expiry days skipped | 210 | 43% | -288 [-511, -76] | -231 | -60,548 | -69,425 | 3/13 | +31 | 319 |
| ATM PM - base, expiry days skipped | 282 | 40% | -194 [-421, +43] | -209 | -54,724 | -63,723 | 4/13 | +28 | 222 |
| ITM1 ALL - base (0.80/0.40% spread) | 359 | 42% | -269 [-470, -63] | -369 | -96,599 | -108,618 | 2/13 | +12 | 281 |
| ITM1 AM - base (0.80/0.40% spread) | 210 | 46% | -187 [-385, +18] | -150 | -39,320 | -49,599 | 5/13 | +123 | 310 |
| ITM1 PM - base (0.80/0.40% spread) | 282 | 38% | -302 [-534, -46] | -325 | -85,122 | -93,224 | 4/13 | -69 | 233 |
| ITM1 ALL - M4 spread (0.60/0.30%) | 359 | 43% | -221 [-423, -16] | -303 | -79,395 | -92,568 | 2/13 | +12 | 233 |
| ITM1 AM - M4 spread (0.60/0.30%) | 210 | 47% | -132 [-329, +73] | -106 | -27,705 | -44,495 | 6/13 | +123 | 255 |
| ITM1 PM - M4 spread (0.60/0.30%) | 282 | 38% | -266 [-498, -11] | -286 | -75,060 | -83,664 | 4/13 | -69 | 197 |
| ITM1 ALL - charges only, no spread | 359 | 47% | -77 [-280, +128] | -106 | -27,784 | -53,267 | 5/13 | +12 | 90 |
| ITM1 AM - charges only, no spread | 210 | 51% | +34 [-164, +240] | +27 | +7,138 | -29,463 | 6/13 | +123 | 89 |
| ITM1 PM - charges only, no spread | 282 | 41% | -159 [-391, +97] | -171 | -44,876 | -55,428 | 5/13 | -69 | 90 |
| ITM1 ALL - RAW fills, charges only | 448 | 38% | -181 [-392, +29] | -310 | -81,293 | -108,164 | 6/13 | -91 | 90 |
| ITM1 AM - RAW fills, charges only | 282 | 43% | -100 [-357, +139] | -108 | -28,319 | -81,970 | 5/13 | -11 | 90 |
| ITM1 PM - RAW fills, charges only | 376 | 34% | -329 [-582, -95] | -472 | -123,783 | -133,664 | 5/13 | -239 | 90 |
| ITM1 ALL - RAW fills, report's flat Rs 100 | 448 | 38% | -191 [-401, +20] | -327 | -85,554 | -109,826 | 6/13 | -91 | 90 |
| ITM1 AM - RAW fills, report's flat Rs 100 | 282 | 42% | -111 [-366, +131] | -119 | -31,179 | -83,368 | 5/13 | -11 | 90 |
| ITM1 PM - RAW fills, report's flat Rs 100 | 376 | 34% | -339 [-592, -104] | -487 | -127,542 | -137,288 | 5/13 | -239 | 90 |
| ITM1 ALL - base, expiry days skipped | 339 | 42% | -322 [-503, -141] | -417 | -109,235 | -121,254 | 1/13 | -33 | 289 |
| ITM1 AM - base, expiry days skipped | 200 | 46% | -214 [-412, -15] | -163 | -42,784 | -53,064 | 3/13 | +105 | 319 |
| ITM1 PM - base, expiry days skipped | 266 | 38% | -333 [-565, -91] | -338 | -88,468 | -97,920 | 2/13 | -94 | 239 |

### By month, 5-min ATM, base costs, clean fills (net Rs; trades)

| month | ALL | AM only | PM only | AM gross |
|---|---|---|---|---|
| 2025-08 | -4,974 (21) | -3,956 (8) | +622 (15) | -2,870 |
| 2025-09 | -7,949 (30) | +681 (10) | -10,555 (25) | +2,465 |
| 2025-10 | -15,633 (19) | -2,109 (7) | -14,331 (13) | -430 |
| 2025-11 | -2,120 (21) | +2,419 (6) | -4,539 (15) | +3,818 |
| 2025-12 | -13,743 (24) | -8,325 (6) | -8,453 (20) | -6,939 |
| 2026-01 | -15,974 (24) | -409 (8) | +791 (19) | +2,105 |
| 2026-02 | +7,034 (23) | -4,968 (12) | +9,750 (13) | +180 |
| 2026-03 | -2,632 (20) | +4,375 (10) | -5,482 (12) | +9,675 |
| 2026-04 | -32,314 (35) | -20,598 (23) | -10,429 (31) | -13,070 |
| 2026-05 | -16,899 (40) | -22,461 (27) | +10,165 (33) | -15,050 |
| 2026-06 | -4,799 (39) | -470 (26) | -11,765 (34) | +7,385 |
| 2026-07 | -9,671 (42) | -2,715 (30) | +1,148 (29) | +5,655 |
| 2026-08 | -11,861 (39) | -3,396 (24) | -9,398 (31) | +3,432 |
| 2026-09 | -9,700 (43) | -8,225 (33) | -6,602 (37) | +1,612 |
| 2026-10 | +6,090 (10) | +6,090 (10) | +1,996 (8) | +9,305 |

### AM-only minus PM-only, Rs per trade, 1 Oct 2025 - 8 Oct 2026 (day-block bootstrap)

| config | AM net/tr | PM net/tr | diff [95% CI] | AM gross/tr | PM gross/tr |
|---|---|---|---|---|---|
| 5m ATM | -274 | -160 | -114 [-435, +202] | +35 | +59 |
| 5m ITM1 | -187 | -302 | +115 [-195, +428] | +123 | -69 |
| 15m ATM | -311 | -330 | +19 [-442, +464] | -5 | -126 |
| 15m ITM1 | -438 | -182 | -256 [-855, +254] | -103 | +33 |
| 60m ATM | -595 | -376 | -220 [-981, +469] | -287 | -176 |
| 60m ITM1 | -605 | -420 | -185 [-1,038, +637] | -277 | -234 |

### Report window 24 Sep - 8 Oct 2026, 5-min, signals from the GOLDM NOV futures (real OHLC)

| system | trades | win% | Rs/trade [95% CI] | Rs/day | total Rs | worst DD | green months | gross/trade | cost/trade |
|---|---|---|---|---|---|---|---|---|---|
| ATM ALL - RAW fills, flat Rs 100 (report's costs) | 20 | 75% | +457 [-209, +1,075] | +914 | +9,135 | -6,090 | 1/2 | +557 | 86 |
| ATM AM - RAW fills, flat Rs 100 (report's costs) | 18 | 78% | +640 [+0, +1,198] | +1,152 | +11,520 | -3,705 | 2/2 | +740 | 85 |
| ATM PM - RAW fills, flat Rs 100 (report's costs) | 19 | 63% | +487 [-412, +1,390] | +926 | +9,256 | -5,554 | 2/2 | +587 | 86 |
| ATM ALL - clean fills, base costs | 20 | 65% | +270 [-367, +851] | +540 | +5,402 | -6,623 | 1/2 | +557 | 287 |
| ATM AM - clean fills, base costs | 18 | 72% | +459 [-119, +974] | +827 | +8,265 | -3,760 | 1/2 | +740 | 281 |
| ATM PM - clean fills, base costs | 19 | 58% | +323 [-562, +1,227] | +614 | +6,137 | -5,982 | 2/2 | +554 | 231 |
| ITM1 ALL - RAW fills, flat Rs 100 (report's costs) | 20 | 65% | +422 [-371, +1,134] | +844 | +8,445 | -7,915 | 1/2 | +522 | 90 |
| ITM1 AM - RAW fills, flat Rs 100 (report's costs) | 18 | 72% | +668 [-38, +1,287] | +1,202 | +12,025 | -4,335 | 2/2 | +768 | 88 |
| ITM1 PM - RAW fills, flat Rs 100 (report's costs) | 19 | 63% | +560 [-360, +1,462] | +1,064 | +10,641 | -5,769 | 2/2 | +660 | 90 |
| ITM1 ALL - clean fills, base costs | 20 | 65% | +201 [-555, +866] | +402 | +4,024 | -8,593 | 1/2 | +512 | 311 |
| ITM1 AM - clean fills, base costs | 18 | 72% | +451 [-184, +1,009] | +813 | +8,127 | -4,491 | 1/2 | +756 | 305 |
| ITM1 PM - clean fills, base costs | 19 | 58% | +207 [-693, +1,151] | +394 | +3,939 | -6,278 | 2/2 | +448 | 241 |

### Signal source check, 7 May - 8 Oct 2026: continuous close-only series (GOLDM_C) vs GOLDM NOV futures OHLC

| system | trades | win% | Rs/trade [95% CI] | Rs/day | total Rs | worst DD | green months | gross/trade | cost/trade |
|---|---|---|---|---|---|---|---|---|---|
| GOLDM_C ATM ALL | 207 | 43% | -207 [-438, +28] | -389 | -42,800 | -53,175 | 1/6 | +61 | 268 |
| GOLDM_C ATM AM | 147 | 44% | -169 [-431, +86] | -226 | -24,866 | -34,437 | 1/6 | +119 | 288 |
| GOLDM_C ATM PM | 166 | 43% | -92 [-405, +227] | -140 | -15,351 | -34,974 | 3/6 | +115 | 208 |
| GOLDM_C ITM1 ALL | 206 | 46% | -141 [-414, +139] | -264 | -29,027 | -39,966 | 1/6 | +141 | 282 |
| GOLDM_C ITM1 AM | 141 | 47% | -61 [-329, +202] | -78 | -8,542 | -16,032 | 4/6 | +246 | 307 |
| GOLDM_C ITM1 PM | 168 | 41% | -229 [-560, +109] | -349 | -38,418 | -56,549 | 3/6 | -2 | 226 |
| GOLDM_NOV ATM ALL | 213 | 46% | -105 [-361, +147] | -204 | -22,413 | -40,354 | 3/6 | +158 | 264 |
| GOLDM_NOV ATM AM | 156 | 49% | -4 [-300, +302] | -6 | -641 | -22,510 | 3/6 | +278 | 282 |
| GOLDM_NOV ATM PM | 192 | 39% | -163 [-446, +144] | -284 | -31,216 | -44,731 | 2/6 | +41 | 204 |
| GOLDM_NOV ITM1 ALL | 209 | 44% | -126 [-455, +193] | -239 | -26,271 | -41,067 | 3/6 | +154 | 279 |
| GOLDM_NOV ITM1 AM | 146 | 49% | +140 [-185, +481] | +186 | +20,456 | -15,189 | 4/6 | +440 | 300 |
| GOLDM_NOV ITM1 PM | 189 | 40% | -311 [-608, -19] | -534 | -58,695 | -68,403 | 2/6 | -90 | 220 |

### GOLDM NOV futures OHLC source, 7 May - 8 Oct 2026, RAW fills + flat Rs 100

| system | trades | win% | Rs/trade [95% CI] | Rs/day | total Rs | worst DD | green months | gross/trade | cost/trade |
|---|---|---|---|---|---|---|---|---|---|
| ATM ALL - RAW, flat Rs 100 | 214 | 50% | +107 [-154, +362] | +209 | +22,949 | -21,930 | 3/6 | +207 | 85 |
| ATM AM - RAW, flat Rs 100 | 163 | 53% | +228 [-45, +510] | +339 | +37,241 | -13,144 | 4/6 | +328 | 85 |
| ATM PM - RAW, flat Rs 100 | 193 | 42% | -24 [-310, +290] | -42 | -4,656 | -33,368 | 2/6 | +76 | 84 |
| ITM1 ALL - RAW, flat Rs 100 | 213 | 47% | +45 [-252, +337] | +88 | +9,639 | -21,738 | 3/6 | +145 | 88 |
| ITM1 AM - RAW, flat Rs 100 | 159 | 52% | +210 [-118, +534] | +304 | +33,421 | -13,264 | 5/6 | +310 | 88 |
| ITM1 PM - RAW, flat Rs 100 | 193 | 43% | -107 [-408, +199] | -188 | -20,709 | -37,992 | 2/6 | -7 | 88 |

### C. How often does an 11-session window look like the report? (all-signals system, 5-min, split by entry time)

```
GOLDM_C ATM RAW/net100: 293 rolling 11-session windows; AM>0 & PM<0 in 27%; AM<0 & PM>0 in 14%; AM >= +10k in 4%; AM <= -10k in 12%; AM win-rate >= 80% in 0%; median AM -1340 PM -1620
GOLDM_C ITM1 RAW/net100: 293 rolling 11-session windows; AM>0 & PM<0 in 25%; AM<0 & PM>0 in 14%; AM >= +10k in 5%; AM <= -10k in 11%; AM win-rate >= 80% in 0%; median AM -1125 PM -1650
GOLDM_C ATM CLEAN/net: 293 rolling 11-session windows; AM>0 & PM<0 in 21%; AM<0 & PM>0 in 12%; AM >= +10k in 0%; AM <= -10k in 8%; AM win-rate >= 80% in 5%; median AM -1794 PM -2991
proxy: hourly return corr GOLDM vs XAUUSD over 293 common days = 0.80 (n=3893)
```


### D. Our trades in the report window (GOLDM NOV futures signals, near-month ATM, raw prints, flat Rs 100)

| date | bar | side | strike | entry Rs | exit Rs | exit | P&L Rs | also in report? |
|---|---|---|---|---|---|---|---|---|
| 2026-09-24 | 09:00 | PE | 151000 | 939 | 860 | time | -890 |  |
| 2026-09-24 | 10:30 | CE | 151500 | 805 | 664 | time | -1,515 |  |
| 2026-09-25 | 09:00 | CE | 151000 | 602 | 537 | time | -750 |  |
| 2026-09-25 | 10:30 | PE | 150500 | 628 | 584 | time | -550 |  |
| 2026-09-28 | 15:20 | CE | 147500 | 3,966 | 4,000 | time | +245 | yes |
| 2026-09-28 | 16:40 | PE | 147000 | 2,814 | 2,562 | time | -2,630 |  |
| 2026-09-29 | 09:30 | PE | 146500 | 2,834 | 2,924 | time | +795 |  |
| 2026-09-29 | 13:35 | PE | 146500 | 2,806 | 2,862 | time | +450 |  |
| 2026-09-30 | 09:05 | CE | 147000 | 4,182 | 4,424 | time | +2,330 | yes |
| 2026-09-30 | 14:25 | PE | 147500 | 2,362 | 2,466 | time | +940 | yes |
| 2026-10-01 | 09:10 | CE | 147000 | 3,872 | 3,996 | time | +1,140 | yes |
| 2026-10-01 | 12:15 | PE | 147500 | 2,419 | 2,566 | time | +1,370 | yes |
| 2026-10-05 | 09:00 | PE | 147000 | 2,342 | 2,591 | time | +2,390 | yes |
| 2026-10-05 | 11:35 | CE | 147000 | 3,663 | 3,754 | time | +815 |  |
| 2026-10-06 | 09:00 | PE | 148000 | 2,751 | 2,765 | time | +40 | yes |
| 2026-10-06 | 11:50 | CE | 148000 | 2,840 | 2,936 | time | +865 | yes |
| 2026-10-07 | 09:00 | PE | 148500 | 2,678 | 2,745 | time | +570 | yes |
| 2026-10-07 | 12:00 | CE | 148500 | 2,610 | 2,692 | time | +725 |  |
| 2026-10-08 | 10:55 | PE | 148000 | 2,428 | 2,684 | time | +2,460 | yes |
| 2026-10-08 | 12:50 | CE | 148000 | 2,718 | 2,762 | time | +335 |  |

All the report's morning trades that we also took were winners for us too, except the 6 Oct 09:00 PE (+40). Its evening trades on 24-29 Sep mostly did not appear for us: our max-2-a-day was used up by morning signals.