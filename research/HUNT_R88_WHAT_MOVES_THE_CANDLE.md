# R88: what moves the candle (every candle, every parameter), 5 indices and 10 MCX books

*20 Oct 2026. Research and knowledge only: nothing in the app changes and no trading rule is proposed. Pre-registration:
`research/hunt/r88/PREREG.md` (commit 8eb9206, pushed before any statistic; amendments A1-A3 before any statistic).
Code and tests: `research/hunt/r88/` (look-ahead guard in `tests/test_r88.py`). Results: `research/hunt/r88/results/`.
The holdout (1 Jun - 19 Oct 2026) was read once (`results/FINAL_READ`), after the design results were committed
(28ac3a5).*

Boss, 20 Oct: *"As all data has been downloaded, I want to deep study/analyse each and every candle and analyse the
behaviour, and also other parameters which affect the candle movement."*

**Signal source:** every candle and every factor here is on the underlying (the index, or the MCX future read from its
option book's `spot`). Option-chain data (OI, PCR, IV, skew, option volume) is used only as context around the
underlying candle, as CLAUDE.md allows.

## The answer for Boss

**1. How BIG the next candle will be is predictable. Which WAY it goes is not.** Out of sample (Jun-Oct 2026), 225 of
305 "size" findings held; 0 of 8 "direction" findings held. This matches R16, R85 and R87, now on all 5 indices and
10 MCX contracts with the full option chain.

**2. What makes the next candle bigger or smaller (known before it starts; holdout medians, Q5 vs Q1 = top fifth vs
bottom fifth):**

| what you can see before the candle | effect on the next candle's size | held out of sample |
|---|---|---|
| the last candle was big | **1.5x bigger** (indices), 1.65x (MCX) | 10 of 10 index, 20 of 20 MCX cells |
| the last hour was volatile (12-candle realised volatility) | **1.4x** (indices), 1.7x (MCX) | 10/10, 19/19 |
| yesterday's range was big | 1.3x (indices), 1.4x (MCX) | 10/10, 18/18 |
| VIX high for its last 60 days (indices) / ATM IV high (MCX) | 1.3x | 10/10, 10/18 |
| put/call OI ratio high (indices) | 1.4x | 6/6 |
| option volume surge in the last candle | 1.2-1.4x | 3/3 index, 3/7 MCX |
| expiry day (indices) | 1.27x | 2/2 |
| options priced rich vs recent movement (implied / realised high) | 0.85-0.9x (smaller: the market has been calm) | 10/10, 18/18 |
| calls traded more than puts in the last candle (indices) | 0.87x | 8/10 |
| price near the day's high (indices) | 0.89x | 6/10 |
| big put skew (MCX) | 0.84x | 4/4 |
| yesterday was an up day | 0.85x (indices), 1.14x (MCX) | 6/10, 3/4 |

In plain words: **big candles follow big candles, volatile hours follow volatile hours, fear (high VIX, high PCR, a
volume rush) means bigger candles, and calm, call-heavy, near-the-high markets give smaller ones.** All of these
together (a gradient-boosted model, walk-forward) predict the next candle's size 19-22% better than the time-of-day
average on the indices in the holdout (5m; 13-19% on 15m), and 2-7% better than a simple "last candle's size + recent
volatility" rule (CI above 0 in 23 of 30 holdout cells). The simple rule does most of the work.

**3. Direction: nothing passes.**
- 8 of 841 design tests on direction passed (all tiny, 0.07-0.10 of a normal candle). In the holdout none held.
  The two SENSEX 5m ones kept their sign and size but were not significant (q 0.15): after NIFTY's last candle rose the
  most, SENSEX's next body was +0.07 of a normal candle larger (but green 49% vs 49%); after the put/call ratio rose
  the most, +0.06 (green 52% vs 46%).
- The all-parameters model calls direction at AUC 0.485-0.535 (0.50 = coin) in the holdout. The 10% most confident
  calls moved +3.3 points on SENSEX 5m against a 6.4-point option round trip, and +0.2 points on NIFTY 5m against 1.9.
  MCX metals are the "best" (GOLDM 5m AUC 0.533, n 15,253 candles; SILVERM 0.523), but their top calls moved 5.7 and
  12 points: a GOLDM point is Rs 10 a lot and an MCX option round trip costs Rs 340-450 (R85), so no.

**4. What moves WITH the candle (same minute, not a forecast):**
- **Indices move together.** NIFTY's same candle alone explains 88-90% of SENSEX's, 78-79% of FINNIFTY's, 67-68% of
  BANKNIFTY's and 51-52% of MIDCPNIFTY's variance (R², holdout, 5m and 15m); BANKNIFTY's explains 67-68% of NIFTY's.
  A move that is not shared across the indices is rare.
- **VIX falls on up candles and rises on down candles** (alone 17-36% of the variance on the indices).
- **On MCX the option chain moves with the price:** the put/call OI ratio falls on up candles (crude: 20-28% of the
  variance alone; gold/silver 4-22%), call OI rises on up candles. Part of this is mechanical: the rolling book keeps
  ATM±5, so when price rises, strikes shift and the OI in the window changes even with no new positions.

**5. When candles are big (census, design; medians):**
- Indices: the first 15 minutes are the biggest by far (NIFTY 15m: 36 bps at 09:15 vs 11-12 bps at midday; BANKNIFTY
  46 vs 14). SENSEX expiry days are 25-29% bigger (5m: 8.5 vs 6.6 bps, holdout); BANKNIFTY 4% (design) to 15% (holdout).
- MCX: the US-open hours (18:30-21:00 IST) are 2-4x the morning (GOLDM 15m 18 bps at 19:00-20:00 vs 9 at 10:15;
  crude 37 vs 12; natural gas 68 vs 18).
- Typical 5-minute candle: NIFTY 7.8 bps (19 points), BANKNIFTY 9.7 bps (54), SENSEX 7.7 bps (62), crude 10.8 bps,
  natural gas 15.7 bps, gold 5.0 bps (n in the table below).
- Colour: about 50% green on every index (up-share 0.498-0.503); MCX 50.3-52.4% green in design (the metals' rise).
  Returns have no memory candle to candle (lag-1 autocorrelation -0.04 to +0.06); sizes do (0.30-0.80).

**For the bot and Jarvis:** use size, not direction. Concretely (ideas for a separate pre-registered study, not
changes): size stops, targets and position size by the predicted candle size (previous candle, recent volatility, VIX,
expiry day); expect the US-open hour on MCX and the first 15 minutes on NSE to be 2-3x a normal candle; do not read
PCR, OI, skew or volume as a direction signal.

## Data and method

| | indices | MCX |
|---|---|---|
| instruments | NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX | GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, COPPER, ZINC |
| price | Dhan index 1-minute OHLC | the median `spot` of the option book each minute = the MCX future (close only; 1-minute returns correlate 0.87 with the real futures minutes, median difference 0 bps on 11,309 GOLDM minutes) |
| option context | rolling chain ATM±10 (nearest expiry), rows of another underlying dropped (spot > 3% off) | ATM±5 nearest monthly |
| design | 20 Nov 2024 - 31 May 2026: 373 sessions per index (after the 20-session warm-up), 27,975 5m candles | 5 Aug 2025 - 31 May 2026: 180-208 sessions (ZINC 26) |
| holdout | 1 Jun - 19 Oct 2026: 98 sessions, 7,350 5m candles per index | 89-100 sessions (ZINC 31) |

- Size target: log(range / median range of the same time slot over the previous 20 sessions). Direction target: the
  candle's body in the same units, and its colour.
- 22 factors, each known strictly before the candle (the look-ahead guard perturbs and deletes every row from the
  candle's first minute on and checks no factor changes; it caught one: today's gap is now blank for a day's first
  candle). Continuous factors: top fifth vs bottom fifth, cut points from design only; categories vs the rest;
  session-clustered standard errors; Benjamini-Hochberg q < 0.05 within each target family in design, then each
  survivor re-measured once in the holdout (same sign and BH q < 0.10 over the survivors).
- Model: scikit-learn HistGradientBoosting (200 trees, learning rate 0.05), monthly walk-forward in design; one model
  fitted on all of design and scored once on the holdout; day-block bootstrap CIs (1,000).
- Costs: no P&L is computed (no rule). Direction is compared with an option round trip in points: NIFTY 1.9, SENSEX
  6.4 (R87); MCX Rs 340-450 a GOLDM trade (R85); the others are not priced.

## Caveats

- MCX candles are built from minute closes, so their high/low are the highest/lowest minute close: ranges are slightly
  smaller and body/range larger (0.70-0.76 on 5m) than on a true OHLC chart. ZINC's options trade thinly: 26 design
  sessions, and the size model is worse than the simple rule there (holdout -11% / -25%).
- CRUDEOILM and NATGASMINI options sit on the same prices as CRUDEOIL and NATURALGAS: they are near-copies, counted
  separately in the BH families (this makes the gates slightly stricter, not looser).
- Option OI in a rolling ATM window changes when strikes re-centre; OI changes are "OI in the window", not new
  positions.
- Dhan's option volume wraps past 2^32 on a few expiry-afternoon minutes (amendment A1; treated as missing).
- The "simple rule" baseline is an OLS of the size target on the previous candle's size and the log of recent
  volatility (`run.walk_forward`; the log was in the code committed before the design run, 8a76c38, but the
  pre-registration says only "a linear fit on F02+F03").
- The holdout is one period (Jun-Oct 2026). Size effects held in 74% of cells; they are a property of volatility,
  not of a strategy.

## Every number

- Census: `results/partA_census_design.csv`, `partA_census_holdout.csv`; by time slot: `partA_slots_design.csv`.
- With the candle: `results/partB_design.json`, `partB_holdout.json`.
- Factor tests: `results/partC_design.csv` (1,682 tests), `partC_holdout.csv` (313 survivors),
  `size_factor_summary.csv` (the table above, by factor and market), `cuts.json` (frozen cut points).
- Models: `results/partD_design.json`, `partD_holdout.json`.
- Experiment log: `research/experiments/entries/r88-a.yaml` .. `r88-d.yaml`.
