# B2: every indicator, every setting, all at once: why candles turn green or red, and can it trade

Written 14 Oct 2026 for Boss:
- "add all the parameters / strategies / indicators at once, and find the pattern between them";
- "include OI, VWAP, volume, EMA and all the variants, order flow ... gamma, vertical volume ... add all you can think of";
- "check why we get green candles / red candles".

Research only. Nothing in the app changes.

Pre-registration `research/hunt/b2/PREREG.md`:
- the plan, committed **bb2faab** before any model was fitted;
- amendment 1, every indicator setting on 1/5/15-minute charts (**0529700**);
- amendment 2, the option volume profile, order flow, gamma, other indices and levels (**c17ff62**);
- amendment 3, importance by input family (**41d6f9e**, before any importance, trading or holdout result);
- design results committed in **26e9fd0** before the one holdout read (14 Oct 2026, `results/HOLDOUT_READ`).

Code: `research/hunt/b2/` (`b2data.py`, `b2sweep.py`, `b2model.py`, `study.py`, `holdout_rows.py`, tests `test_b2.py`).
Results: `results/design.json`, `results/anatomy.json`, `results/holdout.json`.

## Verdict for Boss (read this first)

1. **We put everything into one model at once.** That is 469 inputs on every 5-minute decision from 09:30 to 14:30, on
   NIFTY, BANKNIFTY, FINNIFTY and SENSEX, 2021-2026:
   - every brain feature: EMAs, VWAP and bands, Supertrend, RSI, MACD, ADX, Bollinger, Keltner, pivots, CPR, opening
     ranges, candles, OI and OI build-up per strike, PCR, max pain, OI walls, IV, skew, straddle, VIX, Greeks, the GEX
     proxy and breadth;
   - **every indicator in many settings** on 1-, 5- and 15-minute charts: EMA 5-200, 8 EMA crosses, RSI 7-21, 3 MACDs,
     4 Bollingers, 4 Supertrends, ATR, ADX, stochastic, Williams %R, CCI, momentum and Donchian;
   - the option-chain **volume profile** ("vertical volume": point of control, value area, call and put share);
   - an **order-flow** proxy (option volume signed by price ticks);
   - R28's **gamma exposure** (flip, walls);
   - the **other indices** at the same minute;
   - **Fibonacci** and round-number levels, multi-day returns and the calendar.

   Three kinds of model saw all of it together: a linear model, boosted trees (they find combinations and interactions),
   and a neural network.
2. **Which way the next candle goes (green or red) is still close to a coin flip.** This is on the unseen holdout
   (27 Apr - 12 Oct 2026, 116 days, about 27,700 decisions):

   | question | best model on design (AUC) | best on the holdout (AUC) | passes? |
   |---|---|---|---|
   | next 15-minute candle green? | 0.524 | 0.516 | **no** (below 0.52) |
   | next 30-minute candle | 0.515 | 0.511 | no |
   | next 60-minute candle | 0.514 | 0.518 | no |
   | does the day close above where it is now? | 0.520 | 0.530 (q 0.20) | no |

   AUC 0.5 is a coin flip and 1.0 is perfect. 0.516 means right about 51.6% of the time.
   - All 469 inputs together beat the 208 base features by at most 0.002-0.005.
   - **The neural network did worst** (0.50-0.51), and boosted trees and linear models were about equal. There are no
     hidden combinations: the combination-finding models found nothing the simple one missed.
3. **Trading on the predictions loses money, on design and on the holdout.** Each rule buys the ATM option when the
   model is in its most confident 10%, after real costs:

   | rule | design 2023 - Apr 2026 | holdout | beats random entries? |
   |---|---|---|---|
   | next 15-min direction | 2,726 trades, -Rs 1,65,022 | 527 trades, **-Rs 21,397** (Rs -184 a day) | no (p 0.06, q 0.25) |
   | next 30-min direction | 1,461, -Rs 1,62,098 | 271, -Rs 26,901 | no (p 0.26) |
   | next 60-min direction | 743, -Rs 21,151 | 106, -Rs 16,189 | no (p 0.66) |
   | "which option will pay" (call or put) | 1,949, -Rs 83,569 | 331, -Rs 23,289 | no (p 0.20) |

   0 of 4 pass. Nothing goes to paper or live.
4. **Three things ARE predictable, and none of them is direction:**
   - **How BIG the next 30 minutes will be**: AUC 0.64 on the holdout, all indices. This was already known (R16, B1, F1).
   - **Whether a bought call or put will make money**: AUC 0.59-0.63, and calls and puts score the same. It is the
     **when** (busy, moving markets), not the **which way**. Even the best moments lost money after costs (rule 4 above).
   - **"Will today be a green day?"**: AUC 0.70-0.73 at 09:30 / 10:15. **This is mostly an illusion.**
     - Its main input is how far the index has **already** moved since 09:15. If it is up at 10:15, it is more likely to
       close above the open, because it is already above it.
     - The honest question is "will it go further from here?" (the rest-of-day row above). That is a coin flip.
5. **Why candles turn green or red (`results/anatomy.json`, design data):**
   - **While a 15-minute candle is green, these move with it**:
     - option buying pressure on calls and selling on puts (order-flow proxy, AUC 0.83);
     - **put writing** and **call short covering** (OI build-up 0.79-0.80);
     - the share of stocks advancing (breadth 0.74);
     - gamma turning more positive (0.74);
     - **VIX and IV falling** (0.31, the mirror image);
     - the put-call ratio of volume falling (0.32).

     A red candle is the mirror. These describe the candle **as it forms**: it is green **because** buyers are lifting
     calls, put writers are adding and VIX is easing. None of it is known **before** the candle, so it cannot be traded.
   - **What comes before the candle** (the model's top input families):
     - the last few minutes' momentum (the 15-minute candle leans the **opposite** way: after a red 15 minutes the next
       is green 52.6% of the time vs 49.6% after a green one, 272,667 cases);
     - then IV and VIX;
     - EMA crosses, OI, gamma, volume profile and Fibonacci add almost nothing.
   - **Green and red days** (4,251 index-days, 48% green, open to close):
     - after a gap up the day (open to close) is green only 42-48% of the time; after a gap down 53-54% (the gap tends to fade);
     - after a very red yesterday, today is green 56%;
     - Monday 53%, Tuesday / Thursday / Friday 46-47%;
     - very high VIX 53%, mid VIX 42%;
     - March-April 55-56%, September 42%.

     Each of these is about 850 days, so the noise is about +-3.4 points. Several are inside it. None was tested as a
     rule here, and F1 / R16 found that gap and weekday tilts do not pay for options.

Plain answer: with every indicator, every setting, OI, VWAP, volume, order flow, gamma and the other indices all fed in
at once, a model still cannot tell which way the next candle will go well enough to pay an option's costs. The market
tells you how **much** it will move, not **which way**. A green candle is green because buyers, put writers and a
falling VIX push it while it forms, and you only see that once it is happening.

## Data

- Design: F1's feature rows and outcomes (the brain on the Dhan lake), every row F1 built. That is its design and its
  spent holdout: 2021-08-02 .. 2026-04-13.
  - 282,221 decision rows: NIFTY 1,139 days, BANKNIFTY 1,136, FINNIFTY 1,128, SENSEX 712.
  - 208 base inputs + 261 from the parameter-sweep bank = 469.
- Holdout: 27 Apr - 12 Oct 2026, built once with F1's own code (`holdout_rows.py`); 116 days per index, 27,694 rows.
  - Before the read, F1's code was checked on a design day: its outcomes and all about 200 feature values matched F1's
    table exactly, on all four indices.
  - Caveat (stated in PREREG): R24 and R3 had read option outcomes in that window. No model of direction or candle
    colour had been fitted or scored there.
- Costs: F1's option outcomes (ATM, -15% stop, +Rs 1,000 -> keep Rs 500 lock and Rs 500 trail, 30-minute time stop, app
  charges, liq fills, today's lots).

## Walk-forward (design), AUC pooled over the 2023 / 2024 / 2025 / 2026 test blocks

| target | LR base / all | boosted trees base / all | neural net base / all | rows |
|---|---|---|---|---|
| G15 next 15 min green | 0.520 / 0.520 | 0.523 / 0.524 | 0.509 / 0.512 | 189,099 |
| G30 | 0.515 / 0.514 | 0.515 / 0.511 | 0.505 / 0.507 | 189,064 |
| G60 | 0.510 / 0.511 | 0.514 / 0.508 | 0.506 / 0.507 | 185,926 |
| GEOD close above now | 0.514 / 0.520 | 0.510 / 0.511 | 0.502 / 0.519 | 189,245 |
| CEW call buy pays | 0.614 / 0.602 | 0.603 / 0.600 | 0.544 / 0.541 | 186,908 |
| PEW put buy pays | 0.598 / 0.590 | 0.605 / 0.608 | 0.537 / 0.540 | 187,003 |
| SIZE (control) | 0.652 / 0.644 | 0.676 / 0.671 | 0.580 / 0.546 | 189,153 |
| DAY0930 day green | 0.615 / 0.599 | 0.660 / 0.649 | 0.628 / 0.623 | 3,108 days |
| DAY1015 | 0.688 / 0.663 | 0.722 / 0.726 | 0.685 / 0.673 | 3,108 days |

- Direction AUCs are the same on every index and every block (G15: 0.507-0.535 by block).
- Walk-forward p-values are tiny (with 189,000 rows even 0.51 is "real"). But 0.51-0.52 cannot pay a Rs 100-200 round
  trip, which is why the holdout gate needs at least 0.52.

**Readable rules** (a depth-3 tree on the boosted model's predictions; actual green share on the test blocks):
- The biggest G15 leaf: price below the 1-minute EMA21, RSI9 at or below 41.6, max pain not far below. 15,940 decisions,
  **53.5% green** (a small bounce after a drop).
- Other leaves are 47-52%.
- Some leaves use the raw level of an EMA (for example "EMA50 <= 19,990"). That is the index's level, i.e. which index /
  which year, not a pattern.

## Holdout (read once), 27 Apr - 12 Oct 2026

- Pattern gate (AUC >= 0.52, day-bootstrap p < 0.05, BH q < 0.10 over 54): **no direction target passes**. The closest
  is G15 boosted trees, all inputs: AUC 0.516, q 0.009, below 0.52.
- Passing: CEW / PEW (0.53-0.63), SIZE (0.57-0.64) and DAY0930 / DAY1015 (0.68-0.73). These are when / how big / "already
  moved", as explained above.
- Trading, all four rules: net negative.
  - On the most confident 15-minute calls: win rate 47%, average win Rs +812, average loss Rs -799.
  - Worst day Rs -5,688, max drawdown Rs -39,789.
  - None beats its random-entry twins at q < 0.10.

## Limits

- The index has no traded volume, so "volume" and "vertical volume" come from the option chain.
- True order flow (bid / ask, aggressor side) does not exist in our history. The order-flow proxy signs option volume by
  its price tick.
- Futures inputs exist only from Jul 2026.
- The holdout window was read before for option outcomes by R24 / R3, so it is not untouched market data.
- MIDCPNIFTY is out (no usable chain in the brain).
- Importance is by input family (amendment 3), on the 2025 / 2026 blocks only.
- **Forward test** (PREREG 2): the models are to be frozen and scored on sessions from 15 Oct 2026 once 40 new sessions
  exist. Nothing acts on them before that.

## Tests

`python -m pytest research/hunt/b2/test_b2.py -q` (14 tests). They cover:
- **the look-ahead guard**: every swept indicator on 1, 5 and 15-minute charts, and the context inputs, are unchanged
  when every minute after the decision is replaced by noise; a 5- or 15-minute bar is used only once it has closed;
- inputs never include a target or an outcome;
- the walk-forward never trains on or near its test block;
- AUC (weighted, ties), the day bootstrap, BH, the trading rule and twins, the readable rules, and the holdout refusal.
