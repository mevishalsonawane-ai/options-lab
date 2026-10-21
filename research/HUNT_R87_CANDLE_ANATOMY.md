# R87: candle anatomy — every candle shape, where it forms, and what the next 1-50 candles do

*19 Oct 2026. Research and knowledge only; nothing in the app changes. Pre-registration:
`research/hunt/r87_candle_anatomy/PREREG.md` (commit faa91cd, pushed before any statistic; amendments A0 f5b1df2 /
1335afa, A1 992bf86, A2 2d6a6bf, A3 ef33def). Code and tests: `research/hunt/r87_candle_anatomy/` (look-ahead guard in
`tests/test_lookahead.py`). Results: `research/hunt/r87_candle_anatomy/results/`. Knowledge pack for Jarvis and the
agents: `research/hunt/r87_candle_anatomy/knowledge/candle_pack.json` and `candle_pack.md`.*

Boss, 19 Oct: *"understand all the candle patterns ... a big tail, or a big head, or small both, or both big ... their
behaviour, their position, what big players are doing ... what the 50 candles before and after do ... then we will see
whether this helps us create a strategy or helps the agents/Jarvis train."*

## The answer for Boss

**A candle's shape, on its own, does not tell you where the index goes next.** Over 4 years of NIFTY, BANKNIFTY and
SENSEX (about 1,033 sessions; 77,000 five-minute and 25,700 fifteen-minute candles per index in design), after every
shape we measured the index went up over the next 5 candles 43-62% of the time, and for 92% of the 234 tested
shape x index x timeframe cells (5m and 15m) it was between 45% and 55%, against about 51% for ordinary candles
formed at the same time of day, in the same trend and volatility, with the same colour.

- **Big tail (long lower wick), big head (long upper wick), both, or neither: no direction edge.** For example on
  NIFTY 15m: big tail up 51% vs 52% matched (n 949), big head 53% vs 51% (n 592), both big 52% vs 52% (n 467), small
  both 52% vs 52% (n 1,345). None passes Benjamini-Hochberg (q 0.79-0.95), and the last 12 months (holdout) look the
  same.
- **A long wick is NOT a defended level.** A big tail's low is broken within 10 candles 59% of the time on NIFTY 5m
  (n 3,314) — and 57% for ANY candle whose low is just as far below its close. The "defence" in charts is geometry: a
  long wick puts the low far away. Same for big heads, hammers, shooting stars, dojis, on all three indices, in design
  and in the holdout. The one shape whose far end holds a little better than distance explains is the **green
  marubozu** (a full-body green candle): its low is broken 38% vs 42% (NIFTY 15m, n 881) and 29% vs 36% on BANKNIFTY
  15m in the holdout (n 185): momentum, not a wick.
- **"Reversal" candles mostly mark continuation.** The 50 candles before a NIFTY 15m hammer fell about 4.5 ATR; after
  it the index fell a further 0.47 ATR over 20 candles (matched candles: +0.19). Shooting stars come after a ~5.9 ATR
  rise that kept going (+0.64 ATR after 20 vs +0.20). A stop run ABOVE the day's high (a wick above it that closes back
  below) was followed by MORE upside on NIFTY 15m (+0.13 ATR vs matched, n 1,666) — the opposite of the textbook — in
  line with R86 ("stop runs do not reverse").
- **Where it forms (trend, day high/low, yesterday's levels, VWAP, opening range, gap, time, OI, gamma, volume):
  nothing.** 4,049 shape x context cells were tested; none passed BH in design (best q 0.31), none in the holdout.
- **Out of sample, 2 of 17 design findings held — both on 1-minute candles and both smaller than an option's cost:**
  a 1-minute stop run below the day's low (wick below, close back above) on NIFTY was followed by +0.20 ATR over 5
  minutes (+1.8 points, n 1,702 in the holdout, up 56% vs 50%); a 1-minute green marubozu on SENSEX continued +0.08 ATR
  (+1.6 points, n 5,851). A NIFTY option round trip costs about 1.9 index points, a SENSEX one 6.4.
- **The three pre-registered option rules all failed** (on modelled premiums, see "Data"): NIFTY 5m three white
  soldiers -> call: -Rs 3,910 on 160 trades; NIFTY 15m stop run above the day high -> call: -Rs 25,064 on 270; SENSEX
  15m gravestone doji -> put: +Rs 14,373 on 71 trades (random-entry twin p 0.026, BH q 0.078) but walk-forward
  -Rs 1,923 and -Rs 2,721 without its 5 best trades. The holdout was therefore not read for any rule.
- **MCX (R85, reused, not repeated):** 18 of 7,564 MCX candle cells passed a colour-matched test in design; the 4
  option rules built from them all lost in the final (-Rs 17,053 to -Rs 30,122 each).

**R87B enhancement (Boss: "Can we enhance it", 19 Oct; pre-registration `PREREG_B.md`, commit ac182c7, amendment B-A1
4776a91):** bigger candles (30m, 60m, daily since 2015) and 3-candle sequences show nothing either (0 of 200 and 0 of 25
cells), with one exception: on **SENSEX 15m, two medium green candles followed by a medium red one (a pullback after
a rise) is followed by MORE upside**: +0.18 ATR over 5 candles in design (n 840, q 0.034, 4 of 4 years) and +0.26 ATR
(+22 points, up 57% vs 49%, n 182, one-sided p 0.005) in the holdout. It is the first R87 shape fact that held out of
sample above an option's cost in points, but bought as a call with the pre-registered exits it LOST (-Rs 21,127 on 162
trades: the stop under the red candle's low was hit on 117 of them). Exiting the opening-range-breakout arm on a big
wick against the trade helped in design (+Rs 47,606, 813 trades, q 0.092) and again in the holdout (+Rs 16,107, 912
trades) but not significantly (p 0.40), and the ORB arm itself lost money in both windows. **Real option prices could
not be fetched for the design window (Dhan was degraded); everything is still priced on the calibrated model**, with
real 1-ITM prices only for SENSEX's last 3 months.

**For the bot and Jarvis: do not add a candle-shape arm or a candle filter.** Use the knowledge pack to EXPLAIN candles
(what the shape means, the textbook reason, and the honest number that it does not predict direction), and to stop
Jarvis or an agent from treating a wick as support/resistance. Signals stay on the underlying's trend/breakout rules
that have their own evidence.

## Data (what exists, what does not)

| series | span | used for |
|---|---|---|
| NIFTY, BANKNIFTY, SENSEX index 1-minute OHLC (Dhan v2) | 4 Aug 2021 - 16 Oct 2026; 1,034 / 1,033 / 1,033 design sessions | every candle (1m, 5m, 15m) |
| INDIA VIX 1-minute | same | the modelled premium (rules) |
| NIFTY rolling options, ATM pair (call + put) | parts of 2021-2026: option activity known on 59% of NIFTY design candles | activity proxy, OI, PCR, ATM OI tilt, profile, order-flow proxies |
| BANKNIFTY / SENSEX rolling options | a few months of 2026 only | effectively none (0% of design candles) |
| NIFTY, BANKNIFTY, SENSEX futures 1-minute (true volume, OI) | contracts live on 19 Oct 2026: 29 Jul - 16 Oct 2026 (54-56 sessions) | descriptive cross-check (holdout) |

- **Why so little option data:** the Drive lake is not reachable from this session and its GitHub release is not
  published; Dhan's rolling-option endpoint took 7-12 s per 30-day request and refused concurrent ones (HTTP 429 / 504),
  so the download was narrowed twice before any statistic (amendments A0, A1). The index side is complete.
- **Index volume does not exist.** The activity proxy = ATM call + put volume per minute. Checked against the true
  futures volume (Jul-Oct 2026): per-minute correlation only 0.14 (NIFTY), 0.07 (BANKNIFTY), 0.09 (SENSEX); 5-minute
  0.08-0.15. **So the "volume", "absorption", "climax", "volume at the wick" and activity-weighted VWAP / profile
  results are weak proxies,** and on BANKNIFTY / SENSEX they are missing. The futures cross-check's per-family volume
  ratios are not reported: with only 54-56 sessions and the near contract switching, its "usual volume" is unreliable.
- **Missing, not invented:** tick data and true aggressor delta (the phone's recordings are in the private IraAlgo
  repo, not reachable here), market depth, the option chain beyond the ATM pair (so the dealer-gamma proxy reduces to
  the ATM call-vs-put OI tilt), SENSEX options before May 2023, expired futures minutes. MCX is R85's.
- **Cleaning (A2):** Dhan serves weekend mock sessions with frozen prints (e.g. Sat 7 Aug 2021) that made ATR ~0 and
  moves of thousands of ATR. Dropped: weekend days, Muhurat, days < 120 minutes or > 50% flat minutes (15-17 days an
  index). The first atlas run, on uncleaned data, was discarded (its summary is kept as `atlas_design_v0_summary.json`).

## Method (pre-registered)

- **Candles** of 1, 5 and 15 minutes from 09:15, never spanning sessions. Each candle: body %, upper and lower wick %,
  close location, colour, range vs ATR14 of the previous candles, activity vs the same slot's usual level.
- **Families:** 23 single-candle shapes (doji types, spinning top, marubozu, big bodies, hammer / hanging man, inverted
  hammer / shooting star, big head, big tail, both big wicks, small both wicks, tiny, huge, green, red), 12 two/three-
  candle patterns (inside, outside, engulfing, harami, tweezers, three soldiers / crows, morning / evening star), 8
  "big player" footprints (absorption, climax, stop run up / down, heavy-volume wick up / down, delta divergence up /
  down) and a data-driven grid of body (4 bins) x upper wick (3) x lower wick (3) x colour x size (3) cells.
- **Context (30 conditions):** trend over the prior 50 candles, top / bottom of the 50-candle range, new day high / low,
  yesterday's high / low swept or broken, above / below the opening range, far from VWAP, first / last hour, gap day,
  high / low activity, rejection of yesterday's value-area high / low, ATM OI shift, ATM OI tilt (gamma proxy), expiry
  day, high / low volatility.
- **Outcomes** from the candle's close, in its own ATR: the move after 1-50 candles, MFE / MAE over 10, +1 ATR before
  -1 ATR within 20, low / high broken within 10; the path from 50 candles before to 50 after.
- **Matched random baseline:** all candles of the same index, timeframe, time-of-day bucket, trend state, volatility
  regime and colour in the same period (same colour removes market drift, R85's lesson). Errors clustered by day.
  BH q < 0.10 within each family of tests; a design finding also needs the same sign in >= 3 of 4 design years.
- **Split:** design 4 Aug 2021 - 14 Oct 2025; locked holdout 15 Oct 2025 - 16 Oct 2026, read once
  (`results/FINAL_READ`).

## Results: the atlas

| | design: tested / BH pass | holdout: tested / BH pass |
|---|---|---|
| P1 shape / pattern / footprint x index x timeframe | 349 / 18 (17 also 3-of-4 years) | 341 / 0 |
| P2 shape x context | 4,049 / 0 | 2,326 / 0 |
| P3 data-driven grid cell | 373 / 0 | 109 / 0 |

### The 17 design findings and what happened out of sample

| index | tf | family | design n | design effect (ATR, 5 candles) | holdout n | holdout effect | held? |
|---|---|---|---|---|---|---|---|
| NIFTY | 1m | stop run below the day's low | 6,612 | +0.156 | 1,702 | +0.195 (q 0.004) | **yes** |
| SENSEX | 1m | green marubozu | 26,981 | +0.063 | 5,851 | +0.076 (q 0.071) | **yes** |
| NIFTY | 1m | big head (upper wick) | 11,537 | -0.060 | 2,782 | -0.074 (q 0.12) | no |
| NIFTY | 1m | green marubozu | 20,421 | +0.048 | 3,563 | +0.048 (q 0.22) | no |
| NIFTY | 1m | bearish harami | 14,318 | -0.052 | 2,545 | -0.047 (q 0.22) | no |
| BANKNIFTY | 1m | bearish harami | 13,504 | -0.054 | 2,799 | -0.042 (q 0.22) | no |
| SENSEX | 1m | stop run below the day's low | 6,528 | +0.162 | 1,763 | +0.077 (q 0.20) | no |
| NIFTY | 1m | red marubozu | 20,044 | -0.061 | 4,996 | -0.015 | no |
| BANKNIFTY | 1m | bullish harami | 13,105 | +0.049 | 3,499 | +0.016 | no |
| NIFTY | 1m | gravestone doji | 3,935 | -0.090 | 749 | +0.010 | no |
| SENSEX | 1m | red marubozu | 26,144 | -0.047 | 5,777 | +0.003 | no |
| SENSEX | 1m | bullish harami | 14,902 | +0.057 | 3,452 | -0.014 | no |
| SENSEX | 1m | morning star | 3,466 | +0.090 | 837 | -0.083 | no |
| BANKNIFTY | 1m | stop run below the day's low | 6,309 | +0.077 | 1,629 | -0.005 | no |
| NIFTY | 5m | three white soldiers | 893 | +0.173 | 208 | +0.044 | no |
| NIFTY | 15m | stop run above the day's high | 1,666 | +0.129 | 372 | -0.001 | no |
| SENSEX | 15m | gravestone doji | 205 | -0.295 | 45 | -0.145 | no |

"Held" = same sign and BH q < 0.10 on the one-sided holdout p over the 17 findings. The pattern of the 1-minute
results is the ordinary microstructure of minute bars (a strong minute continues for a few minutes; a minute that
pokes below the day's low and closes back tends to bounce a little), worth 1-2 index points: below an option's cost.

### Boss's four wick families (15m, design; signal source: the index candle)

| family | NIFTY n / up% vs matched / q | BANKNIFTY | SENSEX | low broken in 10 vs same distance (NIFTY / BN / SX) |
|---|---|---|---|---|
| big tail (lower wick >= 50%, >= 1 ATR) | 949 / 51% vs 52% / 0.95 | 887 / 50% vs 50% / 1.00 | 875 / 47% vs 51% / 0.73 | 55% vs 50% / 53% vs 52% / 55% vs 51% |
| big head (upper wick >= 50%) | 592 / 53% vs 51% / 0.81 | 683 / 52% vs 50% / 0.94 | 578 / 50% vs 51% / 1.00 | high: 53% vs 51% / 51% vs 51% / 52% vs 52% |
| both big (each >= 30%) | 467 / 52% vs 52% / 0.81 | 503 / 48% vs 50% / 0.85 | 418 / 50% vs 51% / 0.84 | 66% vs 64% / 70% vs 65% / 67% vs 64% |
| small both (each <= 10%) | 1,345 / 52% vs 52% / 0.79 | 1,163 / 50% vs 50% / 0.90 | 1,532 / 52% vs 51% / 0.81 | 66% vs 69% / 67% vs 68% / 67% vs 69% |

Holdout (same families, 5m and 15m): up-rates 47-57% vs 48-51% matched, every q >= 0.98.

### Mechanism (why the candle forms) — what the data supports

The textbook reasons (kept in the pack as labelled hypotheses) describe who traded INSIDE the candle: a big tail means
sellers pushed and buyers took it back. That part is true by construction. The leap the data refuses is the next one:
that the side which won the candle keeps winning. On liquid index minutes, the next 5-50 candles are driven by the
trend that was already there and by volatility, not by the last candle's shape. Specifically:
- After big "reversal" shapes at trend extremes the trend usually continues (the path tables above).
- Wick levels are broken as often as any level at the same distance; there is no extra "defence".
- The only consistent shape effect is momentum: full-body candles continue for a few minutes, and a green marubozu's
  low holds slightly better than its distance implies.
- Stop runs (wick past the day's high or low that closes back) do not reverse on 5m / 15m; above the high they are, if
  anything, followed by more upside (consistent with R86 and h18).

### Order flow, OI, gamma, volume profile ("what big players are doing")

- Footprints on NIFTY (activity proxy only): absorption (activity >= 2x usual, range <= 0.7 ATR) 5m n 2,266, up 51.5%
  vs 51.5% matched (q 0.79); climax 5m n 1,386, 52.7% vs 51.1% (q 0.81); heavy-volume tail 5m n 5,314, 50.8% vs 51.3%;
  delta divergence (minute-rule delta, not true delta) 5m n 1,520 / 1,492, q 1.0 / 0.81. None is a signal.
- ATM OI shift, ATM OI tilt (gamma proxy), rejections of yesterday's value area, gap days, expiry days: no
  shape x context cell passed (P2, 0 of 4,049).
- These are proxies (correlation of the activity proxy with true futures volume 0.07-0.15). A real order-flow test
  needs the phone's tick recordings (IraAlgo repo) — the same conclusion as R86 and R61B.

## Results: the three option rules (pre-registered selection, A3)

Signal source of every rule: **the index's own candle** (spot). Trade: BUY 1 lot of the 1-ITM call (up) / put (down),
nearest expiry, entry next minute, entries 09:30-14:30; premium stop 15% / 25% (walk-forward picks), profit lock (+20%
-> floor +10%, then trail 10%), underlying stop beyond the candle's far end, time exit after 5 candles, square-off 15:10
(15:05 expiry). Charges dated (STT, exchange, SEBI, stamp, GST, Rs 20 a side), 5 bps + 1 tick fills (stops 10 bps + 2
ticks). **Priced on a modelled premium** (Black-Scholes, INDIA VIX x the index's realised-vol ratio, A1). Rules design
20 Nov 2024 - 14 Oct 2025, walk-forward folds Feb-Apr, May-Jul, Aug-Oct 2025, 500 random-entry twins each.

| rule | signal -> trade | design trades | win | net (Rs) | without top 5 | worst day | max DD | walk-forward | twins mean | twin p | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R87-1 | NIFTY 5m three white soldiers -> call | 160 | 42% | -3,910 | -20,429 | -3,520 | 14,722 | -2,932 (121) | -13,308 | 0.236 | 0.353 | fail |
| R87-2 | NIFTY 15m stop run above day high -> call | 270 | 30% | -25,064 | -56,524 | -4,935 | 28,791 | -28,993 (206) | -25,976 | 0.489 | 0.489 | fail |
| R87-3 | SENSEX 15m gravestone doji -> put | 71 | 48% | +14,373 | -2,721 | -2,585 | 17,221 | -1,923 (51) | -4,192 | 0.026 | 0.078 | fail (WF, top 5) |

- Model check: on 260 NIFTY ATM premiums at 10:30 with real minutes, model / real = 0.92 (median), 15% median error.
  Re-pricing the rules' trades on real minutes where they existed made them WORSE (R87-1: 21 trades, model -Rs 19,311
  vs real -Rs 26,641; R87-2: 25 trades, -Rs 11,146 vs -Rs 19,988). So the model is, if anything, generous to the rules.
- R87-3 is the only near miss: 71 trades in 11 months, profitable in 2 of 3 walk-forward folds, but the middle fold lost
  Rs 11,407 and the net rests on 5 trades. Not a candidate; it can be re-checked if a year of real SENSEX option minutes
  arrives (a note, not a pass).

## R87B: real option prices, bigger timeframes, sequences, candles as exits

*Pre-registered in `research/hunt/r87_candle_anatomy/PREREG_B.md` (commit ac182c7, before any R87B statistic); amendment
B-A1 (4776a91) froze the rule and recorded the option coverage before any rule P&L. Code: `batlas.py`, `exits.py`,
`brules.py`, `bfinal.py`; results `results/b/`. Holdout (15 Oct 2025 - 16 Oct 2026) read once for R87B's cells.*

### Real option prices (B4): mostly not obtained

Dhan's option-history endpoint was degraded all evening (80 s to 5.5 minutes a request, HTTP 504; the fetcher stalled at
21:37 UTC). At the pre-registered cutoff (23:30 UTC) the snapshot held real 1-ITM prices only for SENSEX's holdout
months (51 days) and the NIFTY ATM pair (199 design days). So the design window is still priced by the calibrated model:
real entry prints were 0.7-0.8% of the exit-study trades and 0-6% of the rule trades. Re-pricing R87's three rules
this way (real where printed, model elsewhere): R87-1 +Rs 4,936 (160 trades; 6% real entries), R87-2 -Rs 39,235 (270),
R87-3 +Rs 14,373 (71; no real SENSEX design prices, so unchanged). None changes R87's verdict. **This remains the main
open item: re-run R87/R87B on the full Drive lake (real 1-ITM minutes) when it is reachable.**

### B1: 30-minute, 60-minute and daily candles

| | design: tested / BH pass | holdout: tested / BH pass |
|---|---|---|
| 30m (primary: next 3 candles, same session) | 97 / 0 | |
| 60m (next 3 candles, same session) | 78 / 0 | |
| daily, 2015 - Oct 2025 (next 5 days) | 25 / 0 | |
| B1 total | 200 / 0 (best q 1.0) | 107 / 0 |

Bigger candles are no more informative: after every tested shape the next 3 candles (or 5 days) went up 37-67% of the
time with small samples (daily shapes: n 53-538 each), and none survives multiple testing.

### B2: 3-candle sequences and data-driven clusters

- Named sequences (each candle coded by colour, body size and wick tilt; n >= 200): 25 tested, 0 pass (best q 0.31:
  three strong green 5m candles on NIFTY, up 59% vs 51% matched, n 221). Holdout: 22 tested, 0 pass.
- Clusters (k-means, 24 per timeframe, fitted on design only): 144 tested, 1 passes in design:

| | design | holdout |
|---|---|---|
| SENSEX 15m cluster 13: green, green, red; each ~0.8 ATR with ~55% bodies and ~20% wicks | n 840, +0.184 ATR (+18 pts), up 58% vs 52%, q 0.034, 4 of 4 years | n 182, +0.259 ATR (+22 pts), up 57% vs 49%, one-sided p 0.005: **held** |

Mechanism (a hypothesis): a shallow red pullback after two steady up candles is profit-taking inside a trend, not a
reversal; the trend resumes. It fits R87's broader picture (trend continuation beats reversal shapes). The same
cluster on NIFTY and BANKNIFTY 15m pointed the same way but weakly (+0.06 ATR each, n 831 / 767, q 0.86), so it
is strongest on SENSEX and not proven elsewhere.

### B4: the rule built on it (R87B-1) lost

R87B-1 = buy the SENSEX 1-ITM call at the close of that cluster's third candle, out after 75 minutes; stop 15% / 25%,
lock, underlying stop under the red candle's low. Design (20 Nov 2024 - 14 Oct 2025, model-priced): 162 trades, win
22%, -Rs 21,127 (gross -Rs 9,143, charges Rs 10,160), walk-forward -Rs 17,894 (all 3 folds negative), twin p 0.75.
The underlying stop closed 117 of 162 trades: the red candle's low is too close for an option buyer. The holdout was not
read for it. Testing the same signal with a wider stop would be a NEW idea (a new pre-registration), not a re-run.

### B3: candles as exits (paper arms, reconstructed)

| arm x overlay | design trades / exits fired | design net base -> with overlay | q | holdout |
|---|---|---|---|---|
| ORB + exit on a big wick against | 813 / 130 | -Rs 50,071 -> -Rs 2,465 (+47,606; max DD 89k -> 53k) | 0.092 (pass) | 912 trades: -Rs 1,09,274 -> -Rs 93,166 (+16,107), p 0.40: **fails** |
| ORB + exit on a marubozu against | 813 / 130 | -> -Rs 82,960 (-32,888) | 0.092 (worse) | not read |
| ORB + exit on an engulfing against | 813 / 254 | -> -Rs 92,801 (-42,729) | 0.18 | not read |
| DON + big wick against | 1,722 / 229 | Rs 33,012 -> Rs 43,658 (+10,647) | 0.53 | not read |
| DON + marubozu against | 1,722 / 175 | -> Rs 18,755 (-14,257) | 0.30 | not read |
| DON + engulfing against | 1,722 / 309 | -> Rs 3,067 (-29,944) | 0.13 | not read |

- A big wick against the position is the only candle exit that ever helped (both arms, both windows, every index in
  the holdout), but its holdout gain is not significant, so it is **not** a paper candidate. Exiting on a strong
  opposite candle (marubozu, engulfing) HURT: after a strong candle against you, price tends to come back.
- Signal source: the index's 5m candles; arms are reconstructions, not the app's exact Donchian / ORB arms.

### What R87B adds to the knowledge pack

`knowledge/candle_pack.json` now carries an `r87b` block: the bigger-timeframe and sequence results, the SENSEX cluster
fact (with its failed option rule), and the exit-overlay table.

## What changes

Nothing in the app. For Jarvis and the agents: the pack (`knowledge/candle_pack.*`) gives, per candle family, its
definition, the textbook reason (labelled a hypothesis), the honest numbers (n, up-rate vs matched, effect, q, wick
break vs same distance, the 50-before / 50-after path) and whether anything held out of sample. Suggested use: explain
and educate; never as a trade trigger or a stop level.

## Honesty notes

- Tests: P1 349 + P2 4,049 + P3 373 cells in design; 3 rules x 2 stop variants; R87B 369 atlas cells, 6 exit
  overlays, 1 rule x 2 variants; all logged in `research/experiments/entries/r87-*.yaml` and `r87b-*.yaml`. The holdout was read once (atlas only; the rules failed design).
- The distance-matched wick check and the futures cross-check were added after the design atlas (labelled post-hoc,
  descriptive, no test).
- The holdout window overlaps windows read by other studies (R51-R86 finals); none ran these cells or rules.
- Option-derived context exists for NIFTY only (59% of design candles) and is a proxy; volume conclusions are weak.
- Modelled premiums miss the smile and event IV moves; the real-minute re-pricing is reported beside them.
