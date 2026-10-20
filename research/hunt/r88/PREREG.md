# R88 pre-registration: what moves the candle (every candle, every parameter), 20 Oct 2026

Boss, 20 Oct 2026: "As all data has been downloaded, I want to deep study/analyse each and every candle and analyse the
behaviour, and also other parameters which affect the candle movement."

Written and pushed before any statistic of this study was computed. Research only: nothing in `android/**` or
`.github/**` changes, and no trading rule is proposed here (a rule would need its own pre-registration).

## 0. What is already known (not repeated)

- R87 (candle shapes, NIFTY/BANKNIFTY/SENSEX) and R85 (MCX candles): a candle's SHAPE does not predict direction;
  "reversal" candles mostly continue; wicks are not defended levels. R87 had the option chain for only 59% of NIFTY
  and 0% of BANKNIFTY/SENSEX candles; R85 priced MCX on stand-in series.
- R16 (NIFTY/BANKNIFTY to Feb/Apr 2026): the size of the next 30-60 minutes is predictable, the direction is not.
- New since then: the lake now has the full rolling option chain (ATM±10) for all 5 indices to 19 Oct 2026, FINNIFTY
  and MIDCPNIFTY, India VIX, and 10 MCX option books (ATM±5, Aug 2025 on) whose `spot` is the MCX future.

So R88 is about PARAMETERS: which measurable things move with a candle, and which known BEFORE a candle change its size
or its direction, per instrument, with a locked holdout.

## 1. Data and period (signal source: the underlying)

- Index candles: Dhan index 1-minute OHLC (`index_1m_<SYM>_<YEAR>`), NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX;
  INDIA VIX 1-minute.
- Option context: rolling option books `opt_<UND>_<WEEK|MONTH>_<YEAR>` (nearest expiry; NIFTY and SENSEX: WEEK; the
  three monthly-only indices: MONTH). Rows whose `spot` is more than 3% from the index close at that minute (another
  underlying's rows, R47/lakeclean) are dropped.
- MCX: the 10 option books (GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, COPPER, ZINC).
  The underlying minute price = the median `spot` of the book's rows in that minute (the near future, close only), so
  MCX candles are built from minute closes (high/low = highest/lowest minute close). The real futures minutes
  (`fut_1m_mcx_continuous`, near month only from mid-2026) are a descriptive cross-check only.
- Sessions: weekdays only; a session needs at least 120 minutes and fewer than half of them flat (R87 A2). NSE/BSE
  09:15-15:29; MCX 09:00 to 23:29 (US summer) / 23:54 (US winter), `research/data/mcx.py`.
- Period: index **20 Nov 2024** (NSE weekly-expiry change; Boss's "from November 2024") to **19 Oct 2026**; MCX
  **5 Aug 2025** to 19 Oct 2026.
- **Design: up to 31 May 2026. Locked holdout: 1 Jun - 19 Oct 2026, read ONCE** (flag `results/FINAL_READ`).

## 2. Candles and targets

- Timeframes: 5 and 15 minutes (anchored at the session open) for the tests; 1-minute in the census (Part A) only.
- For candle t: range R_t = high - low; body = close - open; return r_t = close_t / close_{t-1} - 1 (previous
  candle's close; the first candle of a day uses the previous session's close, i.e. it includes the gap).
- Baseline size B_t = median range of the same time-of-day slot over the previous 20 sessions (known before t; needs
  >= 10 of them).
- **Size target** S_t = log(R_t / B_t). **Direction target** D_t = (close_t - open_t) / B_t (signed body in baseline
  units) and its sign (up = close > open; doji excluded from the up-share only).

## 3. Part A: census (descriptive, no test)

Per instrument x timeframe (1, 5, 15 min): range in bps and points by time-of-day slot, body/range, upper and lower
wick shares, up-share, lag-1 autocorrelation of returns and of log ranges, tail sizes (99th percentile), by weekday,
expiry day vs others, event days (`research/brain/calendar_data/events.csv`).

## 4. Part B: what moves WITH the candle (concurrent, descriptive)

OLS per instrument x timeframe of the candle return (bps) on same-candle changes: ATM IV change, PCR (OI) change, call
OI change %, put OI change %, call-minus-put option volume imbalance, VIX change (indices), the same-candle return of
NIFTY (for the other indices; BANKNIFTY for NIFTY), each standardised; report betas and R², design and holdout.
Labelled "moves with the candle", never "predicts".

## 5. Part C: what is known BEFORE the candle (the tests)

Factors, each computed from data strictly before the candle's first minute:

| id | factor |
|---|---|
| F01 | previous candle's return (in baseline units) |
| F02 | previous candle's size S_{t-1} |
| F03 | realised volatility of the last 12 candles (std of returns) / its 20-session median at that slot |
| F04 | VIX level (indices) / ATM IV (MCX), z-scored over the previous 60 sessions |
| F05 | VIX (indices) / ATM IV (MCX) change over the last 30 minutes |
| F06 | today's gap: open vs previous close (constant through the day) |
| F07 | position in the day's range so far: (close - day low) / (day high - day low) |
| F08 | distance of the close from the session's average price so far (TWAP), in baseline units |
| F09 | position vs the opening range (first 15 minutes): above / inside / below (categorical, from 09:30 / 09:15 MCX) |
| F10 | put/call OI ratio (all offsets) |
| F11 | change of the put/call OI ratio over 30 minutes |
| F12 | call OI change over 30 minutes (%) |
| F13 | put OI change over 30 minutes (%) |
| F14 | IV skew: mean IV of OTM puts (offsets -5..-1) minus mean IV of OTM calls (+1..+5) |
| F15 | implied move: ATM straddle / spot, divided by the realised move of the last 12 candles |
| F16 | option volume surge: ATM±3 volume of the last candle / its 20-session median at that slot |
| F17 | call-minus-put volume imbalance of the last candle (log ratio) |
| F18 | days to expiry (0, 1, 2, 3-5, 6+ trading days; categorical) |
| F19 | weekday (categorical) |
| F20 | event day (budget, RBI policy; categorical, indices only) |
| F21 | the leader's previous candle return: NIFTY for the other indices, BANKNIFTY for NIFTY (indices only) |
| F22 | yesterday's return and yesterday's range vs its 20-day median (two factors: F22a, F22b) |
| F23 | time of day (slot; size only: shown, not tested, since B_t already removes it) |

Expiry days are detected from the book (Part E of `data.py`): a day d is an expiry when the median ATM straddle of the
next session's first 10 minutes is more than 2x that of d's last 10 minutes, at most one per 4 sessions. The NSE/BSE
schedule is public in advance, so DTE uses no hidden future information.

**Test.** For each instrument x timeframe x factor x target (S and D): continuous factors are cut into quintiles with
cut points from the design period only; effect = mean target in Q5 minus Q1. Categorical factors: each category vs the
rest. Standard errors are clustered by session (OLS with cluster-robust variance). Design gate: Benjamini-Hochberg
q < 0.05 within each target family (size; direction) over all instruments, timeframes and factors.
**Holdout:** each design survivor is re-measured with the frozen cut points; it HOLDS when the sign is the same and
the one-sided p-value passes BH at q < 0.10 over the survivors of its family.

## 6. Part D: everything together (walk-forward)

Per instrument x timeframe, a gradient-boosted model (scikit-learn HistGradientBoosting, default settings, max_iter
200, learning rate 0.05, fixed seed) on all factors, refitted every calendar month on all earlier design months (first
6 months training only):
- size: mean squared error of S vs two baselines: 0 (the time-of-day baseline alone) and a linear fit on F02+F03;
- direction: AUC of up vs down; and the mean D of the 10% most confident calls each way, in index points, against an
  option round-trip cost in points (NIFTY ~1.9, SENSEX ~6.4 from R87; others are reported as not priced).
Holdout: one model trained on the whole design period, scored once on the holdout. Day-block bootstrap (1,000) for
the CIs. Size counts as predictable when the MSE gain vs the F02+F03 baseline has a CI above 0; direction when the AUC
CI excludes 0.5 AND the top-decile move exceeds the cost.

## 7. What will be reported

Every number with its sample size (candles and sessions). Findings that fail the gates are reported as such. The
experiment log gets one entry per part (`research/experiments/entries/r88-*.yaml`). Tests: `research/hunt/r88/tests/`
including a look-ahead guard (every factor recomputed with the rows after the candle's open deleted and perturbed must
not change).

## 8. Not allowed

No change of factor definitions, cut points, model settings or gates after a design result is seen, except by a
dated amendment below, before the holdout is read; the holdout is read once.

## Amendments

- **A1 (20 Oct 2026, before any statistic):** option volumes below 0 are treated as missing. Dhan's per-minute
  volume wraps past 2^32 on a few expiry-afternoon ATM minutes (16 of 3.9M NIFTY 2025 rows read as about -4.25
  billion), found while checking factor coverage.
- **A2 (20 Oct 2026, before any statistic):** F06 (today's gap) is left blank for the first candle of each session:
  the day's open is part of that candle, so it is not known strictly before it (found by the look-ahead guard). The
  guard's deletion check skips F18 only, as section 5 allows (the expiry schedule is public in advance).
