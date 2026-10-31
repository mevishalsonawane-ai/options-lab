# R139 pre-registration: the multi-timeframe stack (4H -> 1H -> 15m -> 5m -> 3m), continuation version

Written 31 Oct 2026, before any R139 number. Research only (no app code).

Boss, 31 Oct, sharing a "Stop using the wrong timeframe" chart (4 hour: major trend; 1 hour: key levels; 15 min:
structure shift; 5 min: order block / FVG; 3 min: precise entry), then: *"Build R139 with continuation version and test
it"*. The chart's 15-minute step says "trend reversal". On Indian indices breaks continue more often than they reverse
(R135), so the 15-minute step here is a **break of the 1-hour level with the 4-hour trend** (continuation).

**Signal source: the index spot, every layer** (bars aligned to 09:15; wicks for levels, closes for the confirming
closes). The trade buys the ATM option. Written for the bullish side; the bearish side is R131's mirror (buy the put).

## 1. The rule

1. **4 hour: the major trend.**
   - NSE's 4-hour candles: 09:15-13:14 and 13:15-15:29 (two per session).
   - Trend up when the last **completed** 4-hour candle closed above the EMA(20) of 4-hour closes, and that EMA is
     above its value one candle earlier.
   - A 4-hour candle is known only after it closes: the morning one from 13:15, the afternoon one the next session.
2. **1 hour: the key level.**
   - Hourly candles: 09:15-10:14 ... 14:15-15:14 (the last 15 minutes are left out, as in R131's bars).
   - A swing high = an hourly high above the 2 hours before and at or above the 2 after. It is known once those 2
     hours have closed.
   - Levels: swing highs from today and the previous 4 sessions that no wick has gone above (by 1 tick) since they
     formed.
3. **15 min: the structure shift, with the trend.**
   - A 15-minute candle that **closes above** an untaken 1-hour level (the previous 15-minute close was at or below
     it) while the 4-hour trend is up.
   - The broken level is the highest one that close cleared.
4. **5 min: the setup.** The zone comes from the 5-minute candles of the break (the break candle's three and the
   three before them):
   - the **most recent bullish FVG** (a 5-minute candle whose low is above the high two candles before; zone = that
     gap);
   - if there is none, the **order block**: the last red 5-minute candle among those six (zone = its low to its high);
   - if neither, no setup.
   - Then, within the next **12 five-minute candles** (one hour), the first 5-minute candle whose low reaches the
     zone's top is the **pullback**. If a 5-minute candle closes below the zone's bottom first, the setup is cancelled.
5. **3 min: the entry.**
   - After the pullback candle closes, wait for the first 3-minute candle (within 12, i.e. 36 minutes) that closes
     above the previous 3-minute candle's high. Buy the ATM call at the next minute's open.
   - **Cancelled** if a 3-minute low goes below the zone's bottom first.
   - **Stop:** 0.02% below the lowest low from the pullback candle's start to the entry (index wicks).
   - Entries 09:15-15:00; out by 15:20.
   - One trade at a time per index per variant. If a buy and a sell come in the same minute, neither is taken.
6. **The option:**
   - The nearest expiry that does not expire that day (R131-R138). A day when the weekly and monthly expire together
     has no such contract in the lake and is left out.
   - 1 lot, today's lots.
   - Dated charges and liquidity-aware slippage (R131), also 2x slippage.

## 2. Variants (7, one BH family)

| variant | stack | exit |
|---|---|---|
| **C1** (primary) | full stack | target 2R |
| **C2** | full stack | target 3R (the chart's "high R:R") |
| **C3** | full stack | breakeven at 1R, then a 1R trail (R132's E4), no target |
| **C4** | **without the 4-hour trend** (layer test) | 2R |
| **C5** | **without the 5- and 3-minute steps:** buy at the minute after the 15-minute break close; stop 0.02% below that candle's low (layer test) | 2R |
| **C6** | full stack + R123's N1 network filter (R132 Amendment A; folds 2023 - 15 Mar 2026) | 2R |
| **C7** | the 4-hour trend read as **swing structure** instead: the last two confirmed 4-hour swing highs and the last two swing lows both rising (pivots of 2 candles each side) | 2R |

## 3. Tests and gates (R137 / R138's)

- **Random-entry twins:** 100 per trade, from the trade's entry minute to 15:00, with the same day, side, stop and
  target distances, and the same exit. p = (1 + twin totals >= the rule's total) / 101. C6 uses random subsets of
  C1's trades by fold instead.
- **BH over the 7.**
- **Design gates, 1 Aug 2021 - 15 Mar 2026:**
  - >= 300 trades;
  - average net > 0 at 1x and 2x slippage;
  - >= 3 of 4 folds positive (2023, 2024, 2025, 2026 Q1);
  - BH q <= 0.10;
  - max drawdown above -Rs 40,000 and no month below -Rs 13,000 on 1 lot.
- **Holdout 1 Apr - 19 Oct 2026, read once** after the design results are committed: positive average net on >= 50
  trades. Earlier studies read these months, but never for this rule.
- **Win rate against Boss's 65% rule:** reported, not a gate (Boss's own idea; R131 N3).
- **Also reported:**
  - how many breaks reach each layer (break -> zone -> pullback -> 3-minute entry);
  - for C1, the index points per trade before costs.
- **A variant that passes everything** is proposed (not built) as a paper arm for Boss to choose, after a forward
  test, because about 300 rules have now been tried on this data. Otherwise it is rejected.

## 4. Outputs

- `research/hunt/r139_mtf_continuation/`:
  - `r139.py`;
  - `test_r139.py`: each layer on hand-built minutes, the bearish mirror, and the look-ahead guard;
  - `results/design.json`, `results/holdout.json`.
- `research/HUNT_R139_MTF_CONTINUATION.md`; entries `research/experiments/entries/r139-*.yaml`; RESULT.md.
