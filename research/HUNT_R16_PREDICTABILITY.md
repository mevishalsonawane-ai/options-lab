# R16: What can old data predict? (BANKNIFTY and NIFTY)

Written 12 Oct 2026. Boss asked: "See something with which we could predict the future, by checking the old data."
Pre-registration: `research/hunt/r16/PREREG.md` (commit 3274470, before any model was fitted). Model choices were
frozen and committed (44796e8) before the holdout was read. The holdout was read once (flag file in the scratchpad).
Code: `research/hunt/r16/` (`data.py`, `build.py`, `run.py`, `seq.py`, `describe.py`). Every test result:
`r16/results/design.json`, `hold.json`; one experiment entry per test: `r16/experiments.json`. Raw data stays out of git.

## Verdict (read this first)

**Only one thing is predictable: how much the index will move in the next 30-60 minutes (not which way).**
Everything else we tried, including direction, failed in the locked holdout.

| target | verdict | holdout effect (best model vs the baseline it had to beat) |
|---|---|---|
| a1 range of the next 30 min | **PREDICTABLE** (both indices) | error 18-20% smaller than the time-of-day average; 14-15% smaller than the best plain baseline (VIX) |
| a2 range of the next 60 min | **PREDICTABLE** (both indices) | 15-18% smaller; 10-11% smaller than VIX |
| a3 next-day range (pre-open) | **not beyond VIX** | our model = VIX alone (-3.5% BANKNIFTY, -0.1% NIFTY). VIX itself is the forecast to use |
| a3b rest-of-day range at 09:30 | not predictable beyond VIX | BANKNIFTY +0.6% vs VIX, NIFTY -3% |
| b direction, next 15/30/60 min | **not predictable** | AUC 0.509-0.536 (0.5 = coin), no test passes; every trading version loses money after costs |
| c trend day vs range day from the first 30/45 min | not predictable | AUC 0.46-0.52; "same as yesterday" is also a coin (46-53%) |
| d expiry close pinned to max pain | **not predictable (max pain is worse than no forecast)** | error 0.74% vs 0.43% (BANKNIFTY), 0.58% vs 0.52% (NIFTY) |
| e "days like today" (25 nearest past days) | not predictable | the analog days forecast WORSE than all past days pooled (skill -0.6% to -5.5%) |

Multiple testing: 24 primary holdout tests, one Benjamini-Hochberg family. 4 pass (a1 and a2 on both indices, q =
0.003). The next best q is 0.17. Nothing else is close.

## Data

| source | what | span | used for |
|---|---|---|---|
| repo release `research-data` | BANKNIFTY 1-min index + nearest-expiry options (20 strikes, OI) | 13 Feb 2024 - 23 Feb 2026, 498 sessions | intraday targets, option prices for the cost test |
| same release | NIFTY, same layout | 1 Apr 2024 - 13 Apr 2026, 499 sessions | intraday targets |
| Yahoo daily `^NSEI`, `^NSEBANK`, `^INDIAVIX` | daily OHLC, VIX close | Jan 2010 - 9 Oct 2026 | next-day range, VIX feature (previous close only) |
| NSE F&O bhavcopy | end-of-day OI of every NIFTY/BANKNIFTY option, the session before each expiry | Jan 2022 - Oct 2026 (251 NIFTY, 168 BANKNIFTY expiries) | max pain |

No `data-*` release exists on the repo yet and no broker token was used.

Locked holdouts (last ~6 months of each set): BANKNIFTY intraday 25 Aug 2025 - 23 Feb 2026 (122 sessions); NIFTY
intraday 13 Oct 2025 - 13 Apr 2026 (120); daily and expiry 9 Apr - 9 Oct 2026 (126 sessions; 26 NIFTY and 6
BANKNIFTY expiries). Design = everything before, tested by monthly walk-forward (train on all earlier months, 5-session
embargo), and the holdout continues the same monthly refit with the frozen model choice.

## a) Volatility: PREDICTABLE for the next 30-60 minutes

Target: log of (high - low) over the next 30 or 60 minutes, decided every 15 minutes 09:45-14:45. Error = mean
absolute error of the log range (0.30 means typically off by a factor of about 1.35).

| test | model (frozen) | holdout error | time-of-day avg | last 30/60 min | VIX-implied | skill vs frozen baseline | skill vs best in holdout | q |
|---|---|---|---|---|---|---|---|---|
| a1 BANKNIFTY 30 min | small CNN | **0.274** | 0.333 | 0.359 | 0.318 | 17.8% | 14.1% | 0.003 |
| a2 BANKNIFTY 60 min | linear | **0.289** | 0.341 | 0.392 | 0.323 | 15.1% | 10.4% | 0.003 |
| a1 NIFTY 30 min | small CNN | **0.284** | 0.354 | 0.374 | 0.334 | 19.8% | 15.0% | 0.003 |
| a2 NIFTY 60 min | small CNN | **0.286** | 0.348 | 0.386 | 0.323 | 17.7% | 11.4% | 0.003 |

- DESIGN skill was 15-19%, holdout 15-20%: it held up. The VIX baseline did better in the holdout than in design (we
  froze "time-of-day" as the baseline to beat, as pre-registered); the table shows both comparisons and both pass the
  5% bar.
- What drives it: the range of the last 60 and 30 minutes, the range so far today, the usual range for that time of
  day, and VIX. A **4-input linear formula** (usual range at this time, last 30-min range, last 60-min range, VIX) keeps
  most of it: 13-14% better than time-of-day in the holdout, vs 18-20% for the full model. The CNN only beat linear
  by 0.6-2.3% in design.
- In points (holdout, recent price level): BANKNIFTY next-30-min forecast median **114 pts** (10-90%: 84-157);
  NIFTY **47 pts** (31-87). Realised range by forecast fifth, lowest to highest: BANKNIFTY 80, 101, 118, 124, 154 pts;
  NIFTY 31, 42, 49, 62, 88 pts. So the forecast separates quiet from busy half-hours by about 2x.
- Error band (holdout): actual / forecast 10th-90th percentile = 0.67-1.58 (BANKNIFTY 30 min), 0.68-1.67 (NIFTY);
  the time-of-day baseline 0.59-1.76 / 0.61-1.88. Typical error is still about +-35-60%: useful for sizing, not exact.
- Next-day range (a3, daily data 2014-2026): over 12 design years the model beat VIX by 3-6% (q 0.002), but in the
  holdout it did not (-3.5%, -0.1%). Verdict: VIX already holds the information. A VIX-implied forecast puts the
  median day at 1.09% (BANKNIFTY) and 0.80% (NIFTY) high-low; actual / forecast 10-90% = 0.61-1.57.
- Rest-of-day range from 09:30 (a3b): no better than VIX (BANKNIFTY 0.310 vs 0.312, NIFTY 0.306 vs 0.297; q 0.20 /
  0.89). After the first 15 minutes there is not enough new information to beat VIX for the whole day.
- Expiry days (descriptive, no test): median full-day range BANKNIFTY 0.93% on expiry vs 1.02% other days (54 expiry
  days); NIFTY 0.89% vs 0.83% (106). No large expiry-day effect.

## b) Direction: NOT predictable (proved as well as two years allow)

Target: up or down over the next 15/30/60 min, every 15 min. Baseline: base rate (about 50%).

| test | model | holdout AUC | accuracy (base) | log-loss vs base | q | trades (p >= .55 / <= .45) | net Rs / trade, 1 lot | beats random twins |
|---|---|---|---|---|---|---|---|---|
| BANKNIFTY 15 min | LightGBM | 0.513 | 50.3% (50.4%) | worse | 0.28 | 872 | **-115** | 70% |
| BANKNIFTY 30 min | LightGBM | 0.509 | 50.4% (51.2%) | worse | 0.64 | 1070 | **-142** | 49% |
| BANKNIFTY 60 min | LightGBM | 0.509 | 50.7% (51.1%) | worse | 0.65 | 1123 | **-136** | 81% |
| NIFTY 15 min | logistic | 0.513 | 51.1% (50.1%) | equal | 0.36 | 357 | **-112** | 44% |
| NIFTY 30 min | logistic | 0.518 | 51.4% (49.7%) | equal | 0.28 | 524 | **-14** | 96% |
| NIFTY 60 min | logistic | 0.536 | 52.1% (50.4%) | slightly better | 0.17 | 646 | **-12** | 98% |

- Design (monthly walk-forward, about 12 months per index): AUC 0.505-0.527. BANKNIFTY 15 min had AUC 0.527 (q 0.002) but its
  probabilities were over-confident (log-loss worse than the base rate), so it failed the bar there already, and in
  the holdout it fell to 0.513. Every design trading version lost Rs 115-202 a trade (780-3,300 trades each).
- Why it cannot pay: a 1-lot ATM round trip costs about **Rs 133 on BANKNIFTY** (premium ~Rs 515: spread 0.16% a side +
  charges) and **Rs 98 on NIFTY** (premium ~Rs 141), before time decay. A 52-54% hit rate on 15-60-minute moves does
  not cover that. At 2x spread every version is worse (-Rs 43 to -194).
- NIFTY 60 min (AUC 0.536, -Rs 12 a trade, better than 98% of random-side twins) is the only near miss: it is not
  significant after BH (q 0.17), not positive after costs, and design was AUC 0.516 / -Rs 189. Not a rule. It could be
  re-checked when new data arrives, as a fresh pre-registered test.

## c) Trend day vs range day from the opening: NOT predictable

Trend day = the rest of the day closes at least half its high-low away from the decision price (roughly half of days).

| test | model | holdout AUC | accuracy | base-rate accuracy | "same as yesterday" | q |
|---|---|---|---|---|---|---|
| BANKNIFTY at 09:45 | LightGBM | 0.524 | 50.0% | 57.4% | 48.4% | 0.65 |
| BANKNIFTY at 10:00 | linear | 0.507 | 52.5% | 53.3% | 53.3% | 0.68 |
| NIFTY at 09:45 | linear | 0.463 | 47.5% | 55.8% | 52.5% | 0.91 |
| NIFTY at 10:00 | linear | 0.509 | 49.2% | 50.0% | 45.8% | 0.68 |

Design was the same (AUC 0.48-0.53). Neither the opening nor yesterday tells you what kind of day the rest will be.

## d) Expiry day and max pain: max pain is NOT a magnet

Forecast of the expiry close made at the open, max pain from the previous evening's full OI (bhavcopy).

| | expiries | error: close = open | close = max pain | best shrink | close within 0.25% of max pain / of open | q |
|---|---|---|---|---|---|---|
| NIFTY design (Jun 2022 - Apr 2026) | 197 | 0.49% | 0.65% | 0.50% | 25% / 30% | |
| NIFTY holdout | 26 | 0.52% | 0.58% | 0.52% | 27% / 35% | 0.68 |
| BANKNIFTY design | 140 | 0.62% | 0.78% | 0.62% | 25% / 29% | |
| BANKNIFTY holdout | 6 | 0.43% | 0.74% | 0.42% | 50% / 33% | 0.68 |

The fitted "shrink" weight is about 0.1 (the close moves on average a tenth of the way to max pain, not significant).
Using max pain as a target makes the forecast worse than assuming no change. Do not use it.

## e) "Days like today" (analogs): NOT predictable

At 10:15, the 25 past days whose first hour looked most alike (path shape scaled by recent range, gap, VIX level)
were compared with simply using all past days. CRPS skill (positive = analogs better):

| | rest-of-day return | rest-of-day range |
|---|---|---|
| BANKNIFTY design / holdout | -4.3% / -0.6% | -5.7% / -5.5% |
| NIFTY design / holdout | -2.6% / -2.5% | -3.3% / -2.8% |

The analog sets are worse than the pooled history in all 8 cases: 25 neighbours out of ~250-450 days are mostly noise.
This matters for the Data Brain plan (ARCHITECTURE.md P2): "days like today" can be shown to Boss as **context**, but
must not be presented as a forecast.

## What the app could use (and what not)

Build (all as shadow / display first; any trading rule built on them is a NEW hypothesis that needs its own test):
1. **Expected range, next 30/60 min** (the one real finding). The 4-input formula below, computed every minute, gives
   e.g. "BANKNIFTY likely moves about 110 pts in the next 30 min (typically 75-175)". Uses: index-stop and target sizing
   in points (a fixed 30-pt stop is about 0.2x the half-hour range in a busy window and 0.4x in a quiet
   one); a "too quiet to cover costs" warning when the forecast is small next to the ~9-point (BANKNIFTY) / ~3-point
   (NIFTY) cost of a round trip; Jarvis explaining "the market is busier/quieter than usual for this time".
2. **Jarvis "expected range today"** before the open from VIX alone: BANKNIFTY ~1.1%, NIFTY ~0.8% on a median-VIX day,
   scaled by VIX (fit `log range = a + b log VIX` on recent history); say plainly that 80% of days land between 0.6x
   and 1.6x of it.
3. **Honest "no" labels in Jarvis and the Lab:** direction, trend/range day, max pain and analog days are not
   forecasts. When Boss asks "will it go up?", the answer the data supports is "nobody's model here can tell; here is
   how far it is likely to move".

Do not build: a direction model (any horizon), a trend-day classifier for arming, a max-pain expiry strategy, or
analog-based forecasts.

Portable formula (fitted on DESIGN rows only; log = natural log; `rangeK` = (high - low) of the last K one-minute bars
/ last price; `tod` = mean log range of the same clock window over the previous 20 sessions; `vix` = India VIX
previous close). Predicted log range of the next window:

| | const | tod | log range30 | log range60 | log vix |
|---|---|---|---|---|---|
| BANKNIFTY next 30 min | -2.491 | 0.159 | 0.261 | 0.292 | 0.234 |
| BANKNIFTY next 60 min | -2.656 | 0.113 | 0.253 | 0.291 | 0.282 |
| NIFTY next 30 min | -2.702 | 0.101 | 0.272 | 0.320 | 0.239 |
| NIFTY next 60 min | -2.906 | 0.042 | 0.287 | 0.292 | 0.276 |

Points = exp(prediction) x price. It is a median-type forecast (half the windows come in above it).

## Deviations and caveats (all of them)

1. The release files hold the NEXT expiry's chain on expiry days, so the "expiry day" feature was always 0 and
   days-to-expiry is off on those days (harmless: a constant feature). The expiry-day range descriptive uses the
   bhavcopy calendar instead.
2. 19 bhavcopy expiry dates with no OI (contracts re-dated when NSE moved expiry days) were dropped; 3 Yahoo daily rows
   with high <= low or a >15% range were dropped as bad ticks. Neither was in the pre-registration text.
3. The pre-registered model ladder ran the CNN for a1/a2 (trees beat the bar in design); it won by 0.6-2.3% in 3 of 4
   and became the frozen choice there (one seed). The 4-input linear formula is reported post hoc as the simpler option.
4. Before reading the holdout, the hold code path was dry-run on DESIGN data only (a fake cut 3 months earlier) so a
   crash could not burn the one read. The dry-run read no holdout rows.
5. Option exits in the direction cost test use minute closes (forward-filled if a minute had no trade); the spread is
   R15's 0.16% a side, not measured bid/ask (the data has none); stressed at 2x.
6. Two years of intraday data and 122/120 holdout sessions: a weak direction effect (AUC < 0.53) cannot be ruled out,
   but one that size cannot pay for an option round trip anyway. The BANKNIFTY expiry holdout has only 6 expiries.
7. Post-hoc numbers (points, quintiles, error bands, formula, costs, expiry-day range: `describe.py`,
   `results/describe.json`) are descriptions, not tests.
