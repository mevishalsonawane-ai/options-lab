# R32: the 1-minute anatomy of zero-to-hero launches, with every signal family at once

Written 14 Oct 2026, for Boss:
- "check last 30 candles and find all the zero [to hero] and find if there is any pattern for one minute";
- "add oi, vwap, ema, all the patterns like 2, 3, 4 ... gamma exposure, find the relation between them ... add them all
  at once and then find the pattern".

- **Pre-registration:** `research/hunt/r32/PREREG.md`, commit `df487d9`, pushed before any label or outcome was
  computed.
  - Review fixes (`383b80b`), with all rows rebuilt and everything re-run:
    - the label's "confirmed" price now ignores untraded bars (it could have counted a freak opening print);
    - P2 reuses P1's entries, as the plan says.
  - Every number came out identical: the label case never occurred in this data.
  - Amendment 1 (`b58e244`) fixed how P1/P2's score threshold is set; the first run gave them 0 trades. It was made
    before any P1/P2 outcome existed.
- **Code, tests and tables:** `research/hunt/r32/` (`results/results.json`). The 936,286 one-minute rows are on the
  cache, not in git.
- **Experiment entries:** `research/experiments/entries/r32-*.yaml`: 4 rules (all rejected), the event study, the model.
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **There IS a recognisable 1-minute picture before a zero-to-hero launch. But it is not a breakout picture, and it
  cannot be traded profitably. 0 of 4 rules pass** (0 of 82 across R21-R32).
- **What the 30 candles before a launch look like.** This covers 487 launches on 118 expiries: an option at Rs 1-30
  that later reached a confirmed 10×, taken at its lowest point. They are compared with options at the same minute and
  price that never doubled.
  - **The option was crashing, not rising.** 30 minutes before the launch it was 2.67× its launch price. Ordinary
    options were 1.65×, which is normal decay. The launch option had lost about 63% in half an hour.
  - **The index was moving against it.** Index EMA9 above EMA21 in the option's direction was present on 34% of
    launches, against 61% of ordinary options. The index was beyond its average in that direction on 38% against 54%.
  - **Every "confirmation" pattern is RARER at the launch:** 6-16× for the candle, Supertrend, EMA and VWAP patterns,
    2.3× for heavyweight agreement.

    | pattern at the launch minute | launches | ordinary options |
    |---|---|---|
    | 2 green candles in a row | 1.4% | 14.6% |
    | 3 green candles in a row | 0.6% | 4.9% |
    | 4 green candles in a row | 0.2% | 2.1% |
    | bullish engulfing | 0.4% | 6.3% |
    | both Supertrends green | 2.5% | 15.9% |
    | option EMA9 above EMA21 | 3.3% | 21.3% |
    | option above its VWAP | 1.0% | 7.5% |
    | all 3 heavyweights agree | 8.6% | 19.7% |

  - **Only three things are MORE common at a launch:**
    - the option priced cheaply against the move NIFTY is actually making (R27's clue): 33% vs 19%, a lift of 1.78;
    - a volume burst (≥ 2× the 10-bar EMA): 10% vs 6%, a lift of 1.64;
    - the strike's OI rising: 55% vs 46%, a lift of 1.19, as R25 found.
  - **No relation for:** OI walls within one expected move, gamma exposure (77% vs 70%), ADX, range compression.
  - **In one sentence:** a zero-to-hero starts from a cheap, beaten-down option with a burst of volume, as the index
    turns. Green candles, Supertrends, EMA crosses and heavyweight confirmation only appear *after* it has already
    jumped. That is why every momentum rule in R26-R31 ended up chasing.
- **Can it be found live? Only weakly.**
  - **The walk-forward model** (every signal family, each year predicted only from earlier years) ranks rows better
    than chance: AUC 0.70 (2023), 0.61 (2024), 0.73 (2025), 0.59 (2026). Its top 1% of rows hit 10× 1.4-6.7× more often
    than average.
  - **But that lift comes from a few big days.** 2 Sep 2025 alone gave 195 of 2025's top-0.1% rows.
  - Take only the model's best pick each expiry: it hits 10× on **3.9-7.5% of expiries** (2023 4.7%, 2024 3.9%, 2025
    4.8%, 2026 7.5%). An average option on those expiries hits 10× 1.5-2.9% of the time, and 21-29% of expiries have
    *some* option going 10×. So the model's pick is 1.3-4× better than a random option, but its pick still fails about
    19 times in 20. That is too little to pay for the losers.
  - **What the model leans on** (the AUC drop in 2026 when a family is scrambled):
    - pricing (iv_rv, distance, time, price): **0.111**, by far the biggest;
    - index trend: 0.015;
    - chain OI: 0.007;
    - everything else, including gamma exposure, candle patterns and heavyweights: about 0 or negative.
- **The trading rules all lose** over the test years 2023-26:

  | rule | trades | hit % | net Rs | random entries, same days | p |
  |---|---|---|---|---|---|
  | **P1:** the model's pick, held with Boss's 25% trail | 34 | 26.5 | **−3,917** | −8,895 | 0.15 |
  | P2: P1's picks held to 15:05 | 34 | 5.9 | −48,025 | −36,602 | 0.65 |
  | P3: the best mined 2-3-signal pattern, 25% trail | 119 | 16.0 | −37,413 | −20,821 | 0.80 |
  | P4: P3 held to 15:05 | 119 | 3.4 | −1,78,255 | −94,181 | 0.98 |

  - **P1 is the closest yet**, and better than random entries, but negative in every year it traded:
    - 2023: 3 trades, −Rs 1,698;
    - 2024: 5 trades, −Rs 1,963;
    - 2025: 28 trades, −Rs 257;
    - 2026: no score cleared the threshold.
    - It fails the gates. The smallest BH q over 82 tests is 0.164.
  - **The mined patterns collapse out of sample.** The best pattern on the training years had 5-8× the base 10× rate;
    on the next year it had 0-0.8× (2026's pattern: 197 test rows, none reached 10×).
- **What to do: no app change.** Two things carry forward:
  1. **Don't buy hero options on green candles, Supertrend flips, EMA crosses or heavyweight confirmation.** By then the
     move has happened; those signals mark the chase, not the start.
  2. **The only consistent clue across R25, R27 and R32 is price.** A cheap option priced *below* what NIFTY's actual
     movement justifies, with OI building and a volume burst, is where launches start. That is a candidate for the
     forward shadow log (R27's plan), not for money now.

## Data and method (PREREG.md has every detail)

- **Data:** NIFTY 271 and SENSEX 175 weekly expiries, plus the lake's HDFC Bank, Reliance and ICICI Bank minutes.
- **Rows:** every 1-minute bar close 12:00-14:55, for every traded OTM option at Rs 1-30. That gives **936,286 rows**,
  with 68 point-in-time features in 12 families:
  - 2/3/4-candle patterns;
  - option momentum, volume, OI and IV;
  - option EMA, Supertrend and VWAP;
  - index trend (returns, EMA, ADX, TWAP);
  - index volatility and VIX;
  - chain OI (PCR, walls, max pain);
  - gamma exposure;
  - pricing (iv_rv, distance, time, price);
  - heavyweights.
- **Labels:** a confirmed 10× before 15:05 (`y10`): 1.71% of rows. 4×: 6.36%. 2×: 17.06%.
- **Event study:** the launch is the lowest-priced `y10` row of each run. Controls are rows on the same expiry, within
  ±2 minutes, in the same price band, that never reached 2×.
- **Walk-forward:** test years 2023, 2024, 2025, 2026, each fitted on earlier expiries only.
  - The model is gradient boosting with fixed settings.
  - P1's score threshold comes from the inner fold's out-of-sample scores (Amendment 1).
  - The mined patterns are the best 2- or 3-signal tercile combination on the training years, with ≥ 2,000 rows on
    ≥ 50 expiries.
- **Trades:** Rs 2,000, whole lots, R26's fills and charges; exits are Boss's 25% trail (R31) or hold to 15:05.

## Honest notes

- **The launch is picked with hindsight** (the run's lowest point). That pushes momentum features negative by
  construction, so the event-study contrasts describe; they don't predict. The walk-forward model and the rules are the
  point-in-time test, and they show only a weak, day-clustered edge.
- **No virgin holdout exists**: these expiries were read by R25-R31. The forward shadow log is the only clean test left.
- **The index has no volume**, so its VWAP is a time-weighted average.
- **GEX is a proxy** (OI × Black-Scholes gamma at the ATM implied vol).
- **Mining pairs and triples on training years** shows how easily a 5-8× pattern appears in-sample and vanishes the next
  year. That is the reason for the walk-forward design.

## Checks run

- `python -m pytest research/hunt/r32/test_r32.py -q` passes 6 tests. They cover:
  - the past-only helpers (lag, EMA, rolling, streak, since, forward-fill);
  - ADX in a trend;
  - the option, index and chain features reading no future (with the hindsight label checked to differ);
  - the row filter (OTM, Rs 1-30, 12:00-14:55);
  - an untraded bar never confirming the next open in the label.
- R26-R31 tests, the exit audit, the brain tests and the catalog check still pass. `python research/experiments/validate.py`,
  and `python3 research/knowledge/build_pack.py` then `--check`.
