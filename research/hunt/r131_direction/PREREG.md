# R131 pre-registration: a better way to call the direction? Every dataset, several horizons, at least 60%

Written 28 Oct 2026, before any R131 number was computed. Boss (28 Oct 2026): "find a better way to find a direction,
check all the data we have, at least 60". Research only: no app code, no orders.

Up to this file only data SCHEMAS and date coverage were looked at (column names, first and last dates, file layouts);
no target, hit rate, correlation or model output of any kind.

## 1. The question (my reading of the ask)

Is there a way to call the **direction (up or down) of an Indian index** (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY,
SENSEX) that is right **at least 60% of the time out of sample**, using every dataset we have? "No" is a valid answer.

What is already known (R16, R123-R126, the 2026 DIRECTION_MODEL page, OF1): the direction over the next 5-60 minutes
from index minutes, VIX, breadth, chain state, levels, flow proxies and indicators is a coin (AUC 0.51-0.52); move
SIZE is predictable. OF1 found that "follow the FII futures" next-day signals passed 2012-2025 but 13 of 15 flipped
sign on the last 12 months. So R131 looks at three things not yet combined:

1. **sources** never used together for direction: participants' positioning (FII/DII/Pro/Client index futures and
   options OI and their changes), NSDL FPI cash flows, the futures basis / OI / rollover, the end-of-day option chain
   (bhavcopy), breadth and OI build-up of the 59 large stocks, the rupee and the 10-year G-sec (NSE index files), MCX
   gold / silver / crude / copper overnight (the only "global" source we hold, see section 9);
2. **horizons** other than 15-60 minutes: the overnight gap, next-day close-to-close, the day session, rest-of-day;
3. **selective calls**: only the most confident fraction of decisions, with honest coverage.

## 2. Targets and decision times (signal on the underlying; direction = sign of the index move)

All targets are moves of the index spot itself. A move of exactly 0 is dropped (neither right nor wrong).

| id | decision time | target move | tradeable? | info allowed |
|---|---|---|---|---|
| GAP | close of day D (15:29) | close D -> open D+1 | yes (overnight hold) | "close" set of D (section 3) |
| C2C | close of day D (15:29) | close D -> close D+1 | yes (overnight hold) | "close" set of D |
| PRE | pre-open of D+1 (09:00) | close D -> open D+1 | **no** (the gap is priced at the open); informational only, can never count as a tradeable answer | "evening" set of D (incl. MCX 23:30 close) |
| DAY | 09:30 of D+1 (09:29 bar's close) | 09:30 -> 15:30 close (last bar's close) | yes | evening set of D + D+1 bars <= 09:29 |
| R11 | 11:00 (10:59 bar's close) | 11:00 -> 15:30 close | yes | evening set of D + bars <= 10:59 |
| R13 | 13:00 (12:59 bar's close) | 13:00 -> 15:30 close | yes | evening set of D + bars <= 12:59 |
| M60 | 10:00, 11:00, 12:00, 13:00, 14:00 | the next 60 minutes (close at t -> close at t+60) | yes | evening set of D + bars <= t |

- GAP, C2C and PRE use the daily OHLC file (`index_daily`, 2006-2026) and run from **2012-04-02** (participants data
  start Jan 2012, plus warm-up). Their "close" is the daily close; their "open" the daily open. Data check fixed now:
  where NSE's own index file (`participants/indices`) has the same index and day, a daily close that differs by more
  than 0.5% drops that day; an open equal to the previous close to the paisa (a stale open) makes GAP/PRE missing
  that day. Counts are reported.
- DAY, R11, R13 and M60 use the minute grid (`index_1m`) and R123's cached decision rows (2021-08-02 onward).
- **Tiny moves (fixed now):** besides the all-moves result, every hit rate is also reported excluding |move| < 0.10%
  (GAP, PRE, C2C, DAY) or < 0.05% (R11, R13, M60). The gates use the all-moves result.

## 3. Inputs, grouped by source, and when each is known

Publication times (the rule that keeps everything point-in-time): index, VIX, stock and futures PRICES of day D are
"known at the close of D" (class `close`). Participants' OI, the F&O bhavcopy (OI, settle prices, rollover), NSE's
index file (rupee, G-sec, midcap/smallcap) and stock-futures OI of D are published in the evening of D (class
`evening`). MCX futures of D close at 23:30-23:55 IST (class `evening`). NSDL FPI rows are filed by reporting date R
(trades of R-1); to be safe a row is used only from the trading day AFTER R (class `evening+1`).

- A decision at the **close of D** (GAP, C2C) uses: `close` features of D, `evening` features of D-1, `evening+1`
  features of D-2.
- A decision on **day D+1** (PRE, DAY, R11, R13, M60) uses: `close` and `evening` features of D, `evening+1` of D-1,
  and (intraday horizons) the bars of D+1 up to the decision bar.

Sources (each a feature set; per index unless said; ~5-10 features each, listed in `r131feat.py`):

| set | daily features (as of D) | intraday additions (DAY/R11/R13/M60, at the decision bar) |
|---|---|---|
| IDX index/VIX history | returns 1, 2-5, 20 days; distance to 20/50/200-day averages; RSI14; day range; close location; day's gap and open->close; 20-day realised vol; up/down streak; VIX, VIX change, VIX vs its 20-day mean; NIFTY's and BANKNIFTY's own 1-day return | R123's index rows: gap, from_open, ret5/15/30/60, rv30, day range, distance to day high/low, VIX now and its changes |
| BRD breadth (59 stocks) | share up, mean and dispersion of returns, share above the 20-day average, 5-day mean share up (equity_daily); share of stock futures with long build-up and with short build-up (stock_fut_daily_continuous, `evening`); midcap-100 and smallcap-100 vs NIFTY 50 (index file, `evening`) | share of the 59 stocks above the previous close, their mean return since the previous close, since the open and over the last 15 minutes (equity_1m); R123's other-index breadth (br_up, br_from_open, br_ret15) |
| PART participants | FII, DII, Pro, Client: index-futures net (long-short)/(long+short) and its 1-day change scaled by its 20-day spread; FII and Client index-options direction ((call long - call short) - (put long - put short)) scaled by their index-options OI; FII net-futures 5-day change; FPI cash flow and its 5-day sum, each scaled by a 60-day spread (`evening+1`) | - |
| FUT futures | basis (near future close / index close - 1) and its 1-day change; near-future OI 1-day change (fut_daily_continuous); rollover share (next / (near+next) index-futures OI, bhavcopy; NSE indices only) | - (the 1-minute futures file only starts Jul 2026, too short to train on) |
| CHAIN option chain | from the bhavcopy, nearest expiry after D: put/call OI ratio and its change, put/call contracts ratio, distance of the max-OI call and put strikes from spot, ATM straddle settle as % of spot and its change, a skew proxy (log of the ~3% OTM put settle / ~3% OTM call settle). NSE indices only (SENSEX has no NSE chain) | R123's intraday chain: ATM call/put/straddle % of spot, ATM IV, IV skew, PCR and its 15-minute change, call/put OI change, call/put volume ratio |
| GLOB rupee, bonds, MCX | USDINR change implied by NIFTY50-USD vs NIFTY 50 (1 and 5 days), 10-year G-sec index change (1 and 5 days) (index file from 2016); MCX GOLD, SILVER, CRUDEOIL, COPPER near-future 1-day returns (clipped at +-4% to blunt roll jumps) | - |
| CAL calendar | weekday of the target day, trading days to the index's next expiry, expiry day flag, calendar days to the next session, month-turn flag (last 2 / first 3 trading days), budget-day flag | R123's tod, weekday, to_expiry, is_expiry, is_budget |

Every set also carries the index identity (one-hot). Feature sets run: **IDX, IDX+BRD, IDX+PART, IDX+FUT, IDX+CHAIN,
IDX+GLOB, IDX+CAL, ALL** (8 sets). "What each source added" = the set's result minus IDX's.

Signal source: every model's target and every price input is the index (the underlying) or market-wide positioning;
option-chain state enters only as context inputs (CLAUDE.md allows chain data as context around an underlying
signal). Nothing reads an option's own price chart as the signal. The money check buys the option the call's way.

## 4. Models (fixed now) and baselines

- **LR**: logistic regression, L2, C = 0.05, inputs median-imputed (training medians) and standardised (training
  mean/sd), clipped at +-5 sd, max_iter 2000.
- **GBM**: sklearn HistGradientBoostingClassifier(max_iter=200, learning_rate=0.05, max_leaf_nodes=15,
  min_samples_leaf=50, l2_regularization=1.0, random_state=0), no early stopping, NaN handled natively.
- **MLP**: sklearn MLPClassifier(hidden_layer_sizes=(32, 16), alpha=1e-3, learning_rate_init=1e-3, max_iter=200,
  early_stopping=True, validation_fraction=0.2, random_state=0), same imputing/scaling as LR.
- Each model outputs p = P(up); the call is UP if p >= 0.5, else DOWN; confidence = |p - 0.5|.
- **Baselines** (no confidence, 100% coverage only): **B0 majority** (the training rows' majority direction);
  **B1 follow** and **B2 fade** of the latest move: GAP/PRE/C2C: day D's close-to-close; DAY: the move from D's close
  to 09:30 (gap + first 15 min); R11/R13: the move from the day's open to the decision; M60: the last 60 minutes.

## 5. Walk-forward, holdout

- Folds (R123's): **F1** 2023, **F2** 2024, **F3** 2025, **F4** 1 Jan-15 Mar 2026. Each fold's models are trained on
  every row whose target day is at least 10 calendar days before the fold starts (expanding window; daily horizons
  from 2012-04-02, intraday from 2021-08-02). 16-31 Mar 2026 is an embargo, never used.
- **Holdout: 1 Apr-19 Oct 2026, read once**, with models trained on all rows up to 15 Mar 2026. This window has
  already been read by R123-R130 and other studies, so it is not virgin: it can only confirm or reject.
  The daily sources end earlier (participants and bhavcopy 13 Oct, MCX 16 Oct, stocks 12 Oct); later holdout rows get
  those inputs as missing (imputed / NaN as in training). A flag file makes a second read fail.

## 6. Metrics, the test, multiple testing

For every (horizon, set, model, coverage k in {100%, 50%, 20%, 10%}) and every baseline (k = 100%):

- **Selective calls:** the confidence cut for coverage k is the (1-k) quantile of the model's confidence on its OWN
  TRAINING rows (in-sample), so the cut is fixed before the test rows are seen; the realised test coverage is
  reported (it can differ from k).
- **hit** = share of calls whose direction was right; **n** = number of calls; **days** = distinct days.
- **base rate p0** = max(u, 1-u), u = the up share of ALL test rows of that horizon in the same period (what the best
  constant call would get in hindsight).
- **p-value** (one-sided, hit > p0): the five indices move together, so calls are clustered by day: z = sum_d (hits_d
  - p0 n_d) / sqrt(sum_d (hits_d - p0 n_d)^2), normal tail. The plain binomial p is also printed.
- **AUC** of p against the realised direction (all rows, k = 100%).
- **Benjamini-Hochberg over every test** (8 sets x 3 models x 4 coverages x 7 horizons = 672 model tests + 21
  baseline tests = 693), on the pooled F1-F4 p-values, q <= 0.10.
- Also printed (not tested): each fold, each index, and the hit excluding tiny moves.

**A call counts as ">= 60%" only if ALL of these hold** (anything less is reported as "no"):

1. pooled F1-F4 out of sample: **n >= 100** calls and **hit >= 60.0%**;
2. BH q <= 0.10 (beats the base rate p0);
3. hit >= 60% in **>= 3 of the 4 folds** (a fold with fewer than 10 calls counts as a miss);
4. on the **holdout**: hit >= 60% and above the holdout's base rate (n stated);
5. horizon is tradeable (PRE can never pass).

## 7. Money check (CLAUDE.md: signal on the underlying, trade the option)

For every call that passes section 6, and descriptively (fixed now, not a gate, not selected on results) for the
**ALL-set GBM at 20% coverage** at DAY, R11, R13, M60, GAP and C2C:

- **Intraday horizons (DAY, R11, R13, M60):** buy the ATM option of the called side (CE for UP, PE for DOWN; strike
  = spot rounded half-up to the step) of the lake's nearest weekly expiry at the decision bar's close, 1 lot (R123's
  lot sizes), and sell at the horizon's end (DAY/R11/R13: the 15:19 bar's close, 15:04 on expiry days; M60: the bar
  at t+60), no stop or target. Lake rows cleaned with lakeclean steps 1-3; fills Fills("liq"); charges
  Costs("dated"); a minute without a traded print is not priced (the trade is skipped and counted).
- **Overnight horizons (GAP, C2C):** an option bought at 15:29 and held overnight cannot be priced at the next open
  reliably from the lake on expiry eves, so the check uses the **near-month index future** (bhavcopy FUT, nearest
  expiry after D+1; NSE indices only), 1 lot, entry at D's futures close, exit at D+1's futures open (GAP) or close
  (C2C); charges: brokerage Rs 20 an order + 18% GST on brokerage and exchange fees, STT on the sell (0.01% before
  1 Apr 2023, 0.0125% to 30 Sep 2024, 0.02% after), exchange 0.0019%, SEBI Rs 10/crore, stamp 0.002% on the buy;
  slippage 0.01% of price each side.
- Reported: trades, win share, mean and total net Rs, worst month, by fold and holdout.

## 8. Tests (pytest, `test_r131.py`, synthetic data)

- **Look-ahead guard:** changing or shuffling every value dated after the decision (later bars of the day, later
  days of every source, later evening files) never changes any feature row or prediction for that decision.
- Publication lags: an `evening` value of D is never visible to a close-of-D decision; an `evening+1` FPI row is never
  visible before the next trading day.
- Targets read only bars after the decision; tiny-move masks; ties dropped.
- The confidence cut uses training rows only; the day-clustered test, BH, the gate logic; the holdout lock.
- Futures and option money arithmetic on hand-made cases.

## 9. Data inventory (to be filled in the write-up with exact spans and counts)

Used: index_daily (5 indices + VIX, 2006-2026), index_1m (2021-2026), R123 decision rows (2021-08-02 onward, 37
point-in-time features), equity_daily and equity_1m (59 large stocks), stock_fut_daily_continuous, fut_daily_continuous
(index and MCX), participants/ (participant OI 2012-2026, NSDL FPI, NSE index files 2012-2026, index bhavcopy
2012-2026), options_rolling_1m (money check). Looked at and not used, with the reason in the write-up:
fut_1m_live_index (Jul-Oct 2026 only), xauusd_1m (the Drive folder holds only the README and a US events calendar,
no prices), fii_stats (XLS, same information as participant OI), participant volume files, stock_options_rolling_1m,
nse_bhavcopy weekly zips (index rows already in bhav_index), MCX minute data (MCX daily used instead).

Known biases stated up front: the 59 stocks are today's large caps (survivorship); continuous MCX futures have roll
jumps (clipped); India has no US-market data in the lake, so the overnight US session reaches us only through MCX.

## 10. Logging

Every horizon x source result goes into the write-up `research/HUNT_R131_DIRECTION.md` and the experiment log
(`research/experiments/entries/r131-*.yaml`), failures included. Big intermediate files go to Drive
`derived/r131/`, never git.
