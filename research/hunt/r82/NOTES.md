# R82 implementation notes and deviations (written before the final read)

Code:
- `fetch_r82.py`: downloads to /dev/shm, never committed.
- `r82lib.py`: proxies, costs, indicators, metrics, BH.
- `daily.py`: T0-T4, D0-D2, G1, U1.
- `intraday.py`: L1-L3, E1, O1, U2, U3.
- `study.py`: the design / final runner.
- `report.py`: the tables.
- `entries.py`: the experiment entries.
- `test_r82.py`: 41 tests, including the look-ahead guard.

## Data, as used

- **Daily, 2003-2025:** Dukascopy XAUUSD and XAGUSD daily BID candles, one request a year (`fetch_r82.py days`; R53's
  decoder). Weekdays with volume only. Gold has 5,981 rows, 2 Jan 2003 - 31 Dec 2025; silver has 5,979.
- **Daily, 2026:** Dukascopy publishes a year's day file only after the year ends.
  - The fetcher reads the monthly hour files instead (`fetch_r82.py hours`). Dukascopy rate-limited it heavily (HTTP
    429).
  - Fallback for gold: the release's `xauusd_m1_bid.csv.gz` minutes (the same Dukascopy BID source), summed into UTC
    days: 180 weekdays, 1 Jan - 30 Sep 2026. The release lacks some weekdays, as R78 found; they stay missing.
  - What silver 2026 used is recorded in the write-up.
- **Hourly:** Yahoo GC=F / SI=F 1h with `includePrePost`.
  - Bars start at :00 UTC; the 20 bars at :30 and one at :59 were dropped.
  - Gold has 11,439 bars, 20 Oct 2024 - 16 Oct 2026.
  - Yahoo's front month rolls (every 2 months for gold, Mar/May/Jul/Sep/Dec for silver) put one jump bar into the
    series. A trade open across a roll bar carries that jump; no intraday trade is held overnight, and rolls happen at
    the daily session break, so the effect is small. Not corrected.
- **USDINR:** the INR=X daily close strictly before each bar's UTC date.
- **DXY:** DX-Y.NYB daily close, by its NY date.

## Deviations (minimal; none changes a pre-registered rule)

1. **The pre-July-2024 duty factor 1.10 understates the real 15% landed duty.** This was confirmed after the PREREG
   from Union Budget 2024 reports: 15% before 23 Jul 2024 (10% basic + 5% AIDC), 6% after. The factor only scales the
   rupee size of trades, by about 4.5% before Jul 2024. It does not change any signal, any trade's sign, or any pass /
   fail. Kept as pre-registered.
2. **Liquidity exits that fire on a close** (failed break, new level) fill at that bar's close, as in
   `liquidity_gold.py`.
3. **Intraday twins** hold for the real trade's bar count (counting the exit bar). They exit at the stop or the flat
   time if earlier.
4. **The design runner computed a T0 line for comparison.** It is not a test and is not in the BH count.
