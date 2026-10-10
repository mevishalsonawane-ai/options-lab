# HUNT h30: trading the OPTION's own chart (premium breakouts, premium VWAP, premium EMAs ...)

Code: `research/hunt/h30/`. The files are `PREREG.md` (written before any P&L, with amendment A1), `build.py`
(cuts the charts), `sig.py` (54 signals), `sim.py` (fills, exits, random entries), `an.py` (statistics and holdout)
and `ties.py`. Logs are in `scratchpad/hunt/h30/`: `an_pre.log`, `an_holdout.log`, `ties.log`, `run.log` and
`variants_pre.csv` (every variant).

Option BUYING only. Fixed 1 lot. Real Dhan 1-minute option bars with real volume. App charges at today's rates (STT
0.15%). Measured half-spreads from h24 are added on both sides.

## Verdict

**NO. Signals on the premium's own chart do not beat costs. Most of them do worse than buying at a random minute.**

- **Variants:** 3,078 tested on 1,204 pre-holdout days (Aug 2020 to Sep 2025), all decided in advance. That is 54
  signals x 3 strikes x 19 exits.
- **Average trade:**
  - Net: **-Rs 137** per trade.
  - Random entry on the same contract, same day, same exit: -Rs 83.
  - Even BEFORE costs, the average trade is -Rs 16. There is no raw edge for costs to eat.
- **Variants that made money:** only 5 of 3,078 were net positive pre-holdout. All 5 are one signal (HHHL, 15-min,
  ATM) with point exits.
  - Best t-statistic: 0.92.
  - Hansen SPA p = 0.98. White RC p = 1.00. Smallest BH q = 1.00. **Zero survivors.**
- **Walk-forward by year** (2022-2025, each year trades the best variant so far): **-Rs 3.33 lakh**.
- **Locked holdout, run once** (Oct 2025 to Oct 2026), on the best pre-holdout variant:
  - Result: **-Rs 74/day net** (gross +Rs 33/day). Pre-holdout it made +Rs 20/day.
  - Only 13 of the 3,078 variants were positive in the holdout.
- **Rs 5,000/day at Rs 1 lakh:** impossible.
  - Even the pre-holdout best (+Rs 20/day) would need about 250 lots, and it then lost money in the holdout.
- **Why it fails:**
  1. **Costs.** Each round trip costs about Rs 100-120 (charges plus spread).
  2. **Late entries.** A premium breakout fires AFTER the option has already run up. Buying there is worse than buying
     at a random minute for 11 of the 14 signal families. Theta and mean reversion take it back.

## The question and the rules (pre-registered)

**Charts.** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, nearest expiry. On each day there are six charts: ATM,
1-ITM and 1-OTM, CE and PE. The strike is fixed at 09:15 from the index open, which is the chart a trader opens in
the morning.

**Rules for every trade.**
- Expiry days are skipped.
- Premium candles of 1, 3, 5 and 15 minutes.
- The trade fills at the next minute's open after the candle close.
- One trade per chart per day (the first signal).
- Entries between 09:20 and 14:30.
- Square-off at 15:10.

**Signal families** (CE charts buy CE, PE charts buy PE):

| family | what fires |
|---|---|
| ORB5/15/30 | premium breaks its own 5/15/30-min opening-range high |
| PDH | premium breaks the same contract's prior-day high |
| VWAPRECL | premium reclaims its true volume-weighted VWAP |
| VWAPHOLD | 3 closes above VWAP, green candle |
| EMAX | premium EMA 9 crosses above EMA 20 |
| EMAPB | pullback to EMA 9 in an EMA 9 > 20 trend, green candle |
| HHHL | higher highs and higher lows, then a break of the last swing high |
| VOLSPK | candle volume >= 3x the last 10 candles' average, green candle |
| STRADBRK | CE and PE both fell over 30 min, then this side breaks its 30-min high |
| ROUND | crosses a round number above the day's open |
| SWINGHI | breaks the previous swing high |
| OPPCOLL | premium makes a new day high while the opposite option fell >= 10% in 15 min |

**Exits (19, fixed in advance):**
- Liquidity-arm exits: 15% stop, out after 20 min unless up 5%.
- 15% stop / 30% target.
- Profit-lock ladder.
- Premium-VWAP loss, with a 30% hard stop.
- Time exits at 15, 30 and 60 min.
- Amendment A1, added before any P&L: point targets of +15/+20/+25/+30 premium points with point stops of
  -10/-15/-20 (12 combinations, which include 20/20).

**How stops and targets are checked.** Every premium target and stop is checked minute by minute on the option's own
1-minute HIGH and LOW (the obuy engine), even when the signal uses 3, 5 or 15-minute candles. If both are hit in the
same minute, the STOP is taken.
- Same-minute target/stop ties were 0.09% to 0.95% of trades for the point exits. The worst is PT15_10, with 9,508 of
  1.0 million trades.
- The 15%/30% exit had 32 ties.
- So ties do not change any result.

**Costs.**
- Entry is the open x (1 + 5 bps) x (1 + half-spread). Exit is the price x (1 - 5 bps, or 10 bps for stops) x
  (1 - half-spread).
- Half-spreads: BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.20%. The SENSEX figure is
  assumed, not measured.
- Stress test: 1.5x those spreads.

**Random baseline.** On every chart-day, 20 random entry minutes between 09:20 and 14:30 on the SAME contract, with
the same exits.

## Results by signal family (pre-holdout, all variants of the family pooled)

| family | trades per variant | gross Rs/trade | net Rs/trade | random entry Rs/trade (same charts) | best variant Rs/day |
|---|---|---|---|---|---|
| VWAPHOLD | 5,425 | +2.8 | -118 | -94 | -302 |
| EMAX | 5,018 | -9.9 | -126 | -117 | -96 |
| SWINGHI | 5,721 | -10.2 | -126 | -109 | -126 |
| VWAPRECL | 6,043 | -10.1 | -125 | -131 | -368 |
| STRADBRK | 2,108 | -9.2 | -128 | -85 | -79 |
| VOLSPK | 3,645 | -8.5 | -130 | -110 | -21 |
| HHHL | 3,491 | -11.5 | -131 | -67 | **+20** |
| ORB15 | 3,712 | -6.5 | -136 | -34 | -211 |
| OPPCOLL | 3,035 | -17.0 | -140 | -4 | -203 |
| ORB5 | 4,219 | -15.4 | -141 | -69 | -307 |
| EMAPB | 5,173 | -30.8 | -150 | -94 | -300 |
| ROUND | 4,747 | -28.8 | -151 | -87 | -326 |
| ORB30 | 3,341 | -39.2 | -171 | -1 | -262 |
| PDH | 1,507 | -53.9 | -189 | -28 | -126 |

How to read it:
- **Gross is about zero or negative for every family.** The premium chart signals carry no raw edge.
- **Most families lose MORE than random entries on the same days.**
  - Examples: ORB30 -171 vs -1, OPPCOLL -140 vs -4, PDH -189 vs -28.
  - Chasing a premium breakout buys the top of a burst.
- **190 variants beat random at BH q < 0.10.**
  - They are mostly 1-minute SWINGHI, EMAX and VWAP signals with point exits.
  - Every one still loses Rs 353-792 a day. They just lose less than the random entry does, because the random entry
    pays the same costs.
- **A caveat on the random baseline.** It uses only the charts and days where the signal fired. For rare, late
  signals such as HHHL on 15-minute candles, those are trending days, so random entries look very good (+Rs 438 per
  trade). That makes the "vs random" test harsh for those signals. The "vs zero" test does not have this bias, and it
  fails too.

By other cuts (median net Rs/trade):

| cut | values |
|---|---|
| timeframe | 1m -127, 3m -128, 5m -140, 15m -134 |
| moneyness | ATM -128, ITM1 -143, OTM1 -121 |
| best exits | PT30_10 -113, PT25_10 -116, PT30_15 -115 |
| worst exits | VWAPX -185, P15_30 -184, ARM -171 |

Point exits lose least because they cut losers in absolute rupees on cheap options. They still lose.

## The "best" one (HHHL, 15-min, ATM, +30 / -20 premium points): a fluke

| | pre-holdout (Aug 2020 - Sep 2025) | LOCKED HOLDOUT (Oct 2025 - Oct 2026) |
|---|---|---|
| trades | 362 | 112 |
| gross Rs/trade | +204 | +74 |
| net Rs/trade | +67 | **-164** |
| net Rs/day, 1 lot | +20 | **-74** |
| net Rs/day at 1.5x spread | +13 | -98 |
| t vs zero | 0.92 | -0.93 |
| max drawdown | -Rs 33,477 | -Rs 27,937 |
| worst day / worst month | -4,717 / -14,949 | -4,892 / -15,012 |
| months positive | 35/62 | 5/13 |
| P(losing month), bootstrap | 48% | 63% |
| lots for Rs 5,000/day | about 247 (impossible) | never |

More detail on this variant:
- **By year (pre-holdout net):** 2021 +22.6k, 2022 -20.0k, 2023 -2.2k, 2024 +12.4k, 2025 (Jan-Sep) +11.8k.
- **By index:** pre-holdout it made money on NIFTY and MIDCP and lost on BANKNIFTY, FINNIFTY and SENSEX. In the
  holdout only MIDCP was positive.
- **Capital:** the average ticket is about Rs 11,000 at 1 lot, which fits in Rs 1 lakh.
- It was chosen as the best of 3,078 variants, so its pre-holdout number is flattered.

## Walk-forward (anchored by year; each year trades the variant with the best record so far)

| test year | variant picked | net that year |
|---|---|---|
| 2022 | SWINGHI 15m ITM1 VWAP-exit | -1,98,244 |
| 2023 | VOLSPK 3m OTM1 VWAP-exit | -17,146 |
| 2024 | VOLSPK 3m OTM1 VWAP-exit | -1,29,304 |
| 2025 (Jan-Sep) | HHHL 15m ATM PT30_20 | +11,754 |
| **total** | | **-3,32,940** |

Each pick trades all 5 indices and both rights at 1 lot, so a year can mean many trades.

## Rs per premium point per lot

These are the latest lots in the data:

| index | Rs per point per lot |
|---|---|
| NIFTY | 65 |
| BANKNIFTY | 30 (data-derived; check the current contract) |
| FINNIFTY | 60 |
| MIDCPNIFTY | 120 |
| SENSEX | 20 |

A +20-point target is therefore Rs 1,300 on NIFTY, Rs 600 on BANKNIFTY and Rs 2,400 on MIDCP. Round-trip costs are
about Rs 100-120 a trade, plus the spread. MIDCP gets the most rupees per point because its lot is large. That does
not make the signals work: MIDCP's median variant still lost Rs 70/day.

## Honest count and limits

- **Variants:** 3,078 pre-registered and all counted (2,268 originally, plus 810 from amendment A1, made before any
  P&L).
- **Checks run:** one smoke test on 600 charts to check the mechanics.
- **Holdout looks:** exactly one, on one variant, plus a descriptive line for the whole grid.
- **Limits:**
  - Strikes are fixed at 09:15. A trader who re-picks ATM during the day sees different charts.
  - One trade per chart per day.
  - The EMAs are intraday only. They do not carry over from the previous day.
  - Expiry days are excluded.
  - The SENSEX spread is assumed.
  - Data starts Aug 2020 for NIFTY, Aug 2021 for BANKNIFTY and FINNIFTY, Sep 2022 for MIDCP, and May 2023 for SENSEX.

None of these limits is likely to turn a gross of about zero into a profit. The cheapest test of all, gross before
costs, already fails.

**Bottom line for Boss:** reading the option's own chart (premium ORB, premium VWAP, premium EMAs, volume spikes,
straddle squeeze, PE-collapse confirmation) is not an edge. It loses about Rs 120-190 a trade after costs at 1 lot.
It usually does worse than a random entry, because it buys after the premium has already jumped. **Rs 5,000/day: NO.**
