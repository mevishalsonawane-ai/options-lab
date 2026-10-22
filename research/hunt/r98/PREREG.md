# R98 pre-registration: hero-zero candles on all five indices, and the "75% accuracy" question

Written 21 Oct 2026, before any outcome of the predictive part (sections 4-6) was computed. Boss asked: "deep analysis
hero zero candles from the options, why, when and how can we get hero zero trades with 75% accuracy. For all index."

Earlier work this builds on (branch `claude/r25-hero-deep`, `research/HUNT_R25_HERO_DEEP.md`): R25 covered NIFTY,
BANKNIFTY weeklies and SENSEX, found 0 of 9 rules profitable on unseen data, and never asked the accuracy question.
R98 adds FINNIFTY, MIDCPNIFTY and the BANKNIFTY / FINNIFTY / MIDCPNIFTY monthlies after Nov 2024, the newer lake (to
19 Oct 2026, so 13 Oct 2026 is now in the data), the realised-versus-implied explanation, and a direct test of 75%.

## 1. Data (done before this file: `r98data.py`)

- Lake on Drive, refreshed to 19 Oct 2026: `opt_<UND>_WEEK_*` (Dhan rolling nearest expiry, ATM±10, 1-minute) and index
  / India VIX minutes.
- Expiry days from the data (R25's rule: ATM time value at 15:15 < 0.6% of spot, next session's > 2×), with two fixes:
  adjacent-session candidates keep the smaller time value; FINNIFTY from 18 Oct 2021 (earlier prints are stale).
- Expiries: NIFTY 273 (5 Aug 2021 – 19 Oct 2026), BANKNIFTY 195 (to 29 Sep 2026), FINNIFTY 167 (9 Nov 2021 –),
  MIDCPNIFTY 100 (7 Jun 2023 –; its 2022 data is too thin), SENSEX 176 (19 May 2023 – 15 Oct 2026). 911 in all.
- R25's same-minute bad-print cap is applied.

## 2. Definitions (fixed)

- **Cheap option at minute t:** OTM at t, close at t in Rs 1-10, traded (volume > 0) in t-2..t.
- **Hero multiple:** the best close after the entry divided by the entry price. Closes, not highs, so single freak
  prints do not count. "Hero" = ≥ 10×; also 5×, 20×, 33× reported.
- **Expected move EM(t):** the ATM straddle (call + put at the strike nearest spot) at t, in index points.
- **Realised excursion:** from t to 15:20, the highest high minus spot (up) and spot minus the lowest low (down), in EM
  units.

## 3. Descriptive parts (census, anatomy, why, when)

No rule is chosen from these. They are reported for all expiry days.

## 4. Decision rows and labels (the predictive part)

- Every 5 minutes from 11:00 to 14:55, every cheap option is one row.
- **Entry:** the next minute's open plus 1 tick (Rs 0.05). Skipped if that minute has no bar.
- **Outcome window:** to 15:20 (the usual broker auto square-off), by minute closes.
- **Labels:** y2 / y5 / y10 = the best close in (entry minute, 15:20] reaches ≥ 2× / 5× / 10× the entry.
- **Money:** a Rs 5,000 ticket (fractional quantity; lot rounding ignored) with real charges:
  - Rs 20 brokerage an order;
  - GST 18% on brokerage and exchange fees;
  - STT 0.15% of the sell value (the Apr 2026 rate, applied to all years, which is conservative for earlier years);
  - exchange 0.0355% (NSE) / 0.0325% (BSE) of turnover;
  - stamp 0.003% of the buy;
  - the sell fills at the price minus 1 tick.
  - An option worth under Rs 0.10 at the exit is treated as expired worthless: no sell order, no sell charges.

## 5. Features and model (fixed)

- **Features are point-in-time only** (data up to and including minute t):
  - index, side, minutes left, price, strikes OTM;
  - distance in EM, % and day-range units; EM as % of spot;
  - VIX, VIX change vs the previous close and over 30 minutes;
  - index returns over 5 / 15 / 30 / 60 minutes and since the open, signed to the option's side; the gap, signed;
  - position in the day's range, signed; a break of the day high/low within 15 minutes; an opening-range (09:15-09:30)
    break, signed;
  - straddle now / its low since 11:00; straddle change over 15 minutes;
  - option return over 15 minutes; volume in the last 5 minutes vs its mean since 11:00;
  - own OI change since 11:00 and over 15 minutes; IV and IV change over 15 minutes;
  - day range so far / previous day range; weekday; monthly-expiry flag.
- **Model:** scikit-learn HistGradientBoostingClassifier (max_iter 300, learning_rate 0.05, max_leaf_nodes 31,
  l2_regularization 1.0, early stopping off, random_state 98), one model per label.
- **Walk-forward by calendar year:** test 2023, 2024, 2025, 2026; each trained only on rows from earlier years. No
  tuning on test years.
- **Logistic regression on the same standardised features** as a second, simpler model.

## 6. The 75% test (fixed)

A setup "gets 75% accuracy" only if, out of sample (pooled 2023-26 walk-forward), it has:
1. precision (share of picks that reach the label) ≥ 75%;
2. at least 100 picks on at least 30 different expiry days;
3. a positive mean net Rs per ticket after charges with the stated exit.

Checked for:
- **(a) the classifier picks** at every score threshold (y2, y5, y10), and one pick per day (the top-scored row);
- **(b) the win-rate frontier:** every target × stop exit grid on all rows and on the model's top 10%:
  - targets 1.1, 1.2, 1.3, 1.5, 2, 3, 5, 10, 20×;
  - stops 0.2, 0.4, 0.5, 0.6, 0.8× of entry, or none;
  - "accuracy" there means the share of tickets with net P&L > 0;
- **(c) day-level:** whether an expiry day will have any ≥ 10× cheap option after 13:00, predicted at 13:00 from the
  morning's data (same model, walk-forward).

If nothing meets 1-3, the answer to "can we get 75%" is no, and the report says what accuracy IS reachable and at what
cost.

## 7. Not a strategy test

R98 does not propose a trading rule for the app and changes nothing in it. The "zero side" (the share of cheap options
that expire near zero) is reported as a mirror to explain where high accuracy lives. Option selling is not tested: no
margin, no slippage on a squeeze, and no tail cost.
