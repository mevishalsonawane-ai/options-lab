# HUNT h25: every candlestick pattern, chart pattern, moving average and classic indicator, as an option-BUYING trigger

Code: `research/hunt/h25/`. Logs and CSVs: `scratchpad/hunt/h25/`. Pre-registration: `research/hunt/h25/PREREG.md`
(written before any P&L; Amendment 1 adds Boss's premium-point exits and was recorded before any P&L of those exits).
Everything here is option BUYING, 1 lot.

## Verdict

**NO. None of the 195 patterns or indicators is a usable trigger for buying options. None works as a filter on
Liquidity 15+5 either.**

- I tested **210,600 variants**: 195 signals × 2 directions (follow / fade) × 5 indices × 6 timeframes × 18 exits.
- Before costs, the average trade **loses Rs 29**. After app charges and the real spread, it **loses Rs 146**.
- **The patterns add nothing over random entries.** Their trades make on average **Rs +0.2 a trade more** than a
  random minute on the same day, same side and same exit. That is zero.
- **White's Reality Check p = 1.00 and Hansen SPA p = 1.00** over all 210,600 variants. Not one variant beats doing
  nothing after you allow for how many were tried.
- **Walk-forward:** I picked each signal's best index / timeframe / exit on earlier years only. 8 of 390 signal
  families made money out of sample. None passed the family gate.
- **The pre-registered variant gate passed 31 variants** (28 with the first 7 exits, 3 more with the point exits).
  **In the locked holdout (1 Oct 2025 – 6 Oct 2026), 28 of those 31 lost money.** Average: about **Rs −100 a day**.
  - The 3 that made money were tiny: Rs +90, +39 and +12 a day. With 31 tries, 3 small winners is what luck gives.
- **The top 20 by pre-holdout t-stat** made Rs +18 to +145 a day before. In the holdout, **19 of 20 lost money.**
  The best made Rs +3 a day.
- **Filters on Liquidity 15+5:** 4,680 filters tested and 1 passed (60-minute gravestone doji). In the holdout it kept
  31 of 922 trades and **threw away 85% of Liquidity's profit** (Rs 9,464 kept vs Rs 62,084 for the base).
- **Rs 5,000 a day with Rs 1 lakh:** not reachable with any of these. The best holdout result (+Rs 90/day at 1 SENSEX
  lot) would need about 56 lots. Rs 1 lakh buys about 10 SENSEX lots. And that number is most likely luck.
- **Stick with the existing plan:** Liquidity 15+5 on BANKNIFTY (see h17/h24).

How exits were checked (Boss's question):
- **Every premium target and stop is checked minute by minute on the option's own 1-minute HIGH and LOW. This holds
  even when the signal comes from 3–60 minute candles.**
- If both are hit in the same minute, the stop is taken first.
- Such ties are rare: **0.19% of trades** (2,340 of 1,229,462 sampled trades on the point / % exits; 0.07–0.56%
  depending on the exit).
- Counting every tie as a win instead would add only about **Rs 1.3 a trade**, against a Rs 146 average loss.

## What was tested

- **Indices:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX.
- **Timeframes:** 1, 3, 5, 15, 30 and 60-minute index candles, anchored at 09:15.
- **Signals (195):**
  - **All 61 TA-Lib candlestick patterns** (TA-Lib 0.8.1).
  - **9 chart patterns:** double top / bottom break, break of structure (higher-low / lower-high), triangle break,
    flag break, inside-bar break, NR7 and NR4 break, three-bar reversal, outside-bar reversal.
  - **Moving averages:** SMA, EMA, WMA, HMA, DEMA, TEMA and KAMA. For each:
    - price crossing MA 9 / 20 / 50 / 200;
    - MA slope turning (9 / 20 / 50);
    - crosses 5/13, 9/21, 13/34, 20/50 and 50/200;
    - pullback to MA 20 / 50.
  - **Ribbons:** EMA and SMA ribbons.
  - **25 other indicators:** MACD (signal and zero cross), Stochastic (2 rules), CCI (2), Williams %R, RSI (30/70 and
    50), Bollinger (breakout, re-entry, squeeze), Keltner, TTM squeeze, Donchian 20 / 55, Ichimoku (TK cross and
    cloud), Parabolic SAR, Aroon, ADX/DI, Supertrend, Heikin-Ashi, session TWAP and ROC.
- **Direction:** each signal was traded both ways. "Follow" trades the textbook direction; "fade" trades the
  opposite option.
- **Trade:**
  - Buy the 1-ITM nearest-expiry option (the monthly where the weekly no longer exists), 1 lot.
  - Fill at the next minute's open with the app's fills, the app's charges, plus the measured half-spread on entry
    and on exit: BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.16% (assumed).
  - Stress test: 1.5× those spreads.
  - Expiry days skipped. One position at a time, at most 5 trades a day.
- **18 exits, fixed before results.** All square off at 15:10.

  | exits | what |
  |---|---|
  | ARM | the Liquidity arm's exits |
  | P20 | −20 / +20 premium points |
  | PCT | −15% / +30% |
  | LAD | −15% with the profit-lock ladder, +30% |
  | T15 / T30 / T60 | time stops of 15, 30 and 60 minutes |
  | 11 point exits (Amendment 1) | targets +15 / +20 / +25 / +30 premium points × stops −10 / −15 / −20 |

- **Rupees per premium point per lot** (today's lots, read from the data):

  | index | Rs per point per lot | 1-ITM premium per lot | lots that Rs 1 lakh buys |
  |---|---|---|---|
  | NIFTY | 65 | ~Rs 9,000 | 11 |
  | BANKNIFTY | 30 | ~Rs 16,900 | 5 |
  | FINNIFTY | 60 | ~Rs 16,500 | 6 |
  | MIDCPNIFTY | 120 | ~Rs 20,800 | 4 |
  | SENSEX | 20 | ~Rs 8,300 | 12 |

- **Engine:** the validated `research/obuy` engine. I built an outcome table for every non-expiry minute 09:15–15:00,
  both sides and all 18 exits. Each signal is a lookup into that table. The lookup matches the engine run directly
  trade for trade (`check.py`: 4 of 4 identical).

## The bar every pattern has to clear: random entries lose about Rs 90–240 a trade

Pre-holdout, a random minute's average trade:

| index | gross / trade (best–worst exit) | net / trade at the real spread |
|---|---|---|
| NIFTY | −16 to −63 | −104 to −146 |
| BANKNIFTY | −19 to −66 | −123 to −170 |
| FINNIFTY | −18 to −79 | −165 to −225 |
| MIDCPNIFTY | −31 to −107 | −167 to −243 |
| SENSEX | −11 to −53 | −90 to −133 |

- A bought option loses its time value, and every trade pays about Rs 60–90 in charges plus the spread.
- **A pattern has to predict well enough to pay all of that. None does.**

## Summary by family (pre-holdout, all 210,600 variants)

"vs random" = net per trade minus a random entry's net on the same day, same side and same exit.
"q<.05" = beats random after Benjamini-Hochberg. "gate" = passed all the pre-registered variant gates.

| family | variants | % net > 0 | % gross > 0 | net / trade | gross / trade | vs random / trade | q<.05 | gate | walk-forward families positive |
|---|---|---|---|---|---|---|---|---|---|
| Candlesticks (61) | 65,880 | 9.7% | 24.3% | −148 | −39 | −2.0 | 13 | 4 | 6 of 122 |
| Chart patterns (9) | 9,720 | 3.3% | 19.4% | −148 | −39 | −1.6 | 9 | 1 | 0 of 18 |
| SMA | 15,120 | 2.5% | 18.1% | −145 | −35 | +2.6 | 8 | 2 | 1 of 28 |
| EMA | 15,120 | 2.7% | 20.3% | −144 | −34 | +3.3 | 24 | 8 | 0 of 28 |
| WMA | 15,120 | 1.9% | 18.4% | −145 | −36 | +2.6 | 3 | 1 | 0 of 28 |
| HMA | 15,120 | 0.9% | 12.0% | −148 | −38 | −1.9 | 4 | 3 | 0 of 28 |
| DEMA | 15,120 | 1.3% | 14.7% | −146 | −37 | 0.0 | 7 | 1 | 0 of 28 |
| TEMA | 15,120 | 1.1% | 13.5% | −147 | −38 | −0.9 | 5 | 1 | 0 of 28 |
| KAMA | 15,120 | 1.6% | 18.7% | −145 | −35 | +0.6 | 1 | 0 | 1 of 28 |
| Ribbons | 2,160 | 0.8% | 19.3% | −148 | −39 | +1.3 | 1 | 1 | 0 of 4 |
| Other indicators (25) | 27,000 | 1.9% | 19.1% | −146 | −36 | +1.0 | 14 | 4 | 0 of 50 |
| **ALL** | **210,600** | **4.3%** | **19.5%** | **−146** | **−29** | **+0.2** | **89** | **26** | **8 of 390** |

**By timeframe:**
- Longer candles look slightly better in-sample: 60-minute candles have 7.9% of variants net-positive, 1-minute only
  1.1%.
- That is only because longer candles give fewer trades and so fewer costs. The edge over random is the same, about
  zero.

**By direction:**
- Follow: −141 a trade, +2.7 vs random.
- Fade: −151 a trade, −2.3 vs random.
- 25 of the 26 gate passers were "fade" variants. Their holdout result is below.

**Exits:** no exit fixes it.
- The point exits (+15…+30 / −10…−20) lose Rs 136–158 a trade. Random entries lose about the same with them.
- Time stops lose the least gross, but they are still net-negative.

## Top 20 (all 210,600 variants, ranked by pre-holdout t-stat of net per trade)

Rs/day is at 1 lot, per trading day of that index. "Pre" = Aug 2020 – Sep 2025. "Hold" = 1 Oct 2025 – 6 Oct 2026,
looked at once. q = BH q-value vs random.

| # | index, candle | signal | dir | exit | pre trades | pre net / trade | t | q | pre Rs/day | hold trades | hold gross Rs/day | hold net Rs/day | hold net @1.5× spread |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | FINNIFTY 60m | EMA 13/34 cross | fade | T15 | 110 | 182 | 2.44 | 0.00 | 20 | 36 | 3 | −38 | −51 |
| 2 | MIDCPNIFTY 15m | price × WMA50 | follow | P20 | 539 | 158 | 2.13 | 1.00 | 122 | 273 | −114 | −355 | −415 |
| 3 | SENSEX 30m | price × DEMA200 | follow | T60 | 136 | 281 | 2.04 | 1.00 | 65 | 81 | −126 | −158 | −163 |
| 4 | MIDCPNIFTY 60m | CDL long line | follow | P30_20 | 385 | 225 | 2.02 | 1.00 | 124 | 190 | −29 | −200 | −243 |
| 5 | SENSEX 5m | SMA 50/200 cross | follow | T30 | 173 | 151 | 1.96 | 1.00 | 44 | 84 | 37 | 3 | −2 |
| 6 | MIDCPNIFTY 15m | price × WMA50 | follow | P25_20 | 519 | 165 | 1.94 | 1.00 | 123 | 268 | −17 | −254 | −314 |
| 7 | MIDCPNIFTY 5m | TEMA 50/200 cross | follow | PCT | 388 | 260 | 1.90 | 1.00 | 145 | 187 | −73 | −234 | −274 |
| 8 | MIDCPNIFTY 15m | RSI 50 cross | follow | P20 | 621 | 133 | 1.88 | 1.00 | 118 | 317 | −255 | −536 | −606 |
| 9 | MIDCPNIFTY 30m | triangle break | follow | P30_15 | 132 | 300 | 1.73 | 1.00 | 57 | 56 | 1 | −47 | −59 |
| 10 | MIDCPNIFTY 60m | TWAP cross | follow | P30_20 | 413 | 191 | 1.73 | 1.00 | 113 | 227 | 130 | −76 | −128 |
| 11 | NIFTY 15m | CDL advance block | fade | P25_20 | 334 | 115 | 1.72 | 1.00 | 30 | 65 | −36 | −66 | −72 |
| 12 | FINNIFTY 60m | EMA 13/34 cross | fade | LAD | 110 | 298 | 1.71 | 0.01 | 33 | 36 | −104 | −144 | −157 |
| 13 | BANKNIFTY 1m | CDL identical 3 crows | follow | T60 | 155 | 293 | 1.71 | 1.00 | 44 | 44 | −61 | −92 | −98 |
| 14 | MIDCPNIFTY 60m | CDL long line | follow | P25_20 | 389 | 175 | 1.70 | 1.00 | 98 | 194 | −37 | −211 | −255 |
| 15 | MIDCPNIFTY 3m | CDL thrusting | fade | ARM | 196 | 337 | 1.70 | 0.00 | 95 | 101 | 32 | −61 | −85 |
| 16 | NIFTY 60m | Supertrend flip | follow | T60 | 197 | 250 | 1.59 | 1.00 | 38 | 49 | −76 | −97 | −100 |
| 17 | FINNIFTY 60m | EMA 13/34 cross | fade | P25_10 | 110 | 165 | 1.57 | 0.01 | 18 | 36 | −35 | −75 | −88 |
| 18 | NIFTY 60m | ADX/DI | follow | T60 | 113 | 320 | 1.57 | 1.00 | 28 | 22 | −19 | −29 | −30 |
| 19 | MIDCPNIFTY 5m | WMA 50/200 cross | follow | P30_15 | 210 | 210 | 1.56 | 1.00 | 63 | 128 | −69 | −181 | −209 |
| 20 | MIDCPNIFTY 15m | price × WMA50 | follow | P15_20 | 557 | 96 | 1.54 | 1.00 | 76 | 276 | −198 | −440 | −501 |

Reading:
- **Most of the "best" are MIDCPNIFTY.** MIDCP options carry the biggest premium per lot, so luck shows up biggest
  there. Every MIDCP rule lost in the holdout, several by Rs 200–500 a day.
- Holdout: **0 of 20 made Rs 5,000/day; 1 of 20 was net-positive (+Rs 3/day); 0 of 20 survived the 1.5× spread.**
- Even the best pre-holdout rule, at about Rs 145/day, would need 35 MIDCP lots for Rs 5,000/day. Rs 1 lakh buys 4.

## The gate passers in the holdout (the one pre-registered test)

- **Variant gate:** net > 0 at 1.5× spread, BH q < 0.05 vs random, positive in more than half the years, ≥ 100 trades.
  - **31 variants passed it** across the two rounds: 28 under the 7-exit correction, plus 3 new ones under the
    210,600-variant correction. Under the full correction only 26 still pass. All are listed in `final.csv` (run7) and `final_new.csv`.
  - **Holdout: 28 of 31 lost money. Average about Rs −100/day at 1 lot. Total Rs −7.7 lakh across the 31.**
  - The three winners:

    | rule | holdout trades | holdout net |
    |---|---|---|
    | SENSEX 5m CDL separating lines (fade, ARM) | 76 | +Rs 90/day (gross +121) |
    | SENSEX 60m HMA 13/34 cross (fade, T30) | 83 | +Rs 39/day |
    | SENSEX 60m HMA 13/34 cross (fade, T15) | 83 | +Rs 12/day |

  - None is worth trading. 3 of 31 is chance level, and +Rs 90/day would need 56 SENSEX lots for Rs 5,000/day.
- **Family gate** (walk-forward net > 0, Holm p < 0.05 vs random, > half the years positive): **0 of 390 families
  passed.**
  - Best walk-forward family: CDL ladder-bottom fade, +Rs 21,934 over 144 trades in 4 years. Only 2 of its 4 years
    were positive, and its Holm p was 1.0.

### A warning about the random-entry test (post-hoc finding, stated so nobody is fooled by it)

The 31 "passers" beat random entries mostly because random entries lose so much. Many passers barely made money
themselves: ICHI cloud fade made +Rs 21 a trade with t = 0.21.

The random-entry p-value also assumes the signal's trades are as noisy as random minutes. They are not:
- 30–60 minute signals bunch at 09:44 / 10:14, where option moves are 2–3× bigger than at a typical minute.
- **Post-hoc fix:** a test that also uses the signal trades' own variance (a Welch-type test).
- **Result under that test:** 2 variants have q < 0.05 (BANKNIFTY 30m doji and long-legged doji, follow, PCT, about
  Rs +30 a trade). **0 pass the gate.**
- The obuy random baseline has the same weakness. Future studies should match random entries by time of day.

## Patterns as FILTERS on Liquidity 15+5

- **Base:**
  - Liquidity 15+5 on 5 indices, 1-ITM, arm exits, app costs plus the real spread.
  - Pre-holdout: 2,786 trades, +Rs 25 a trade, Rs +70,480 in total.
  - Holdout: 922 trades, +Rs 67 a trade, Rs +62,084.
- **Filters:** for every signal × timeframe, I kept only trades whose signal agreed with the trade, or dropped trades
  whose signal disagreed. Bases were all 5 indices pooled and BANKNIFTY alone: 4,680 filters.
- **One passed:** 60-minute gravestone doji agreeing (it only ever keeps PE trades).
  - Pre-holdout: kept 107 trades at +Rs 917 a trade.
  - Holdout: kept **31 trades, +Rs 305 a trade, Rs 9,464 in total**, against Rs 62,084 for the unfiltered base.
  - p = 0.28 against random subsets of the same size.
- **Using it would have cut Liquidity's holdout profit by 85%.**
- None of the broad filters helped either: the moving-average agree filters, MACD, Supertrend and so on.
- **Keep Liquidity unfiltered.**

## Honest count of tries

| what | count |
|---|---|
| Trigger variants (195 signals × 2 directions × 5 indices × 6 timeframes × 18 exits) | 210,600 |
| Filter variants | 4,680 |
| Total variants | **215,280** |
| Walk-forward families | 390 |
| Holdout looks: the first 7-exit run | 48 (28 gate passers + top 20) |
| Holdout looks: the amendment | 23 (3 new passers + top 20 of the point exits) |
| Holdout looks: filter | 1 |

- Nothing was chosen from a holdout result.
- The order is recorded in `PREREG.md`. The 7-exit run, its holdout look and the filter screen came before Boss's
  point-exit amendment. The amendment's exits were added before any of their P&L was computed, and every correction
  was redone over all 210,600 variants.

## Limits

- The spreads come from one snapshot on one calm day (h24). FINNIFTY's 0.42% is the worst of the five.
- The weekly contracts before Nov 2024 were probably tighter than this. That would make the pre-holdout less bad, but
  it does not change the vs-random result, which is about zero before costs.
- SENSEX's spread was not measured; I assumed 0.16%.
- Data limits:
  - Early 2021 BANKNIFTY / FINNIFTY option minutes start at 10:00.
  - MIDCPNIFTY begins in 2022 and SENSEX in May 2023, so their pre-holdout has only 3 years.
- Chart-pattern definitions are one objective version each. Pattern-recognition "art" cannot be tested.

## Files

- `research/hunt/h25/`:
  - `PREREG.md`: the plan and Amendment 1.
  - `outcomes.py`: outcome tables (`H25_SET=points` for the point exits).
  - `sigs.py`: all 195 signals.
  - `evaluate.py`: lookup, greedy positions and chunked RC/SPA.
  - `check.py`: engine parity check.
  - `analyze.py`: BH, gates and walk-forward.
  - `final.py`: the one holdout look.
  - `filt.py`: Liquidity filters.
  - `baseline.py`: random entries and Rs per point.
  - `ties.py`: same-minute target/stop ties.
- `scratchpad/hunt/h25/`:
  - `variants_pre.parquet` (every variant, pre-holdout), `hold_sealed.parquet`.
  - `top20.csv`, `top20_new.csv`, `passers.csv`, `final_new.csv`, `run7/final.csv`.
  - `wf.csv`, `wf_picks.csv`, `sig_summary.csv`, `fam_summary.csv`, `by_*.csv`.
  - `filt_pre.parquet`, `filt_hold.parquet`, `liq_trades.parquet`.
  - `baseline.csv`, `ties.csv`, `spa.json`.
  - Logs.
- The outcome tables (`out*_<U>.npz`, about 400 MB) were deleted to save disk. Rebuild them with `outcomes.py`
  (about 15 minutes per exit set).
- TA-Lib 0.8.1 is installed in `scratchpad/hunt/h25/pylib`, from the binary wheel.
