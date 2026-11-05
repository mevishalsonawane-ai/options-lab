# R146: can we predict tomorrow's opening gap at the close, and does buy-today-sell-tomorrow (BTST) pay?

Written 5 Nov 2026 for Boss's question: "Can you properly study, as most professional traders are able to tell the
prediction, and keep today's orders open, and then sell it tomorrow with good profit."

- **Pre-registration:** `research/hunt/r146_gap_btst/PREREG.md`, commit `133c5c2`, pushed before any data was
  processed. Its sha256 is stamped in every result file.
- **Amendments:** `research/hunt/r146_gap_btst/NOTES.md`. A1-A3 were written before any result; A4 is a reporting
  fix after the holdout.
- **Code and tests:** `research/hunt/r146_gap_btst/`, with 32 tests including the look-ahead guard.
- **Results:** `results/` holds `design.json`, `holdout.json`, `report.md` (every table), `build.json` and
  `HOLDOUT_READ.flag`. The holdout was read once.
- **Experiment entries:** `research/experiments/entries/r146-*.yaml`, 12 of them, all failures.
- **Research only:** nothing in the app changes.

## Short answer for Boss

**1. Can the close predict tomorrow's gap? Only a little. The gap's direction is mostly decided overnight.**

- **What is known by 15:20 explains almost none of the gap.** This covers the day's move, where it closes in its range,
  the last 30-60 minutes, breadth, India VIX, option OI, expiry, events and Asian closes.
  - Out-of-sample R² of the gap from all of it is -0.37 to +0.04, across 4 indices and two periods.
- **The US session that trades while India sleeps explains far more.** The S&P 500's overnight move alone has an R²
  of 0.08-0.24 and a correlation of 0.34-0.50 with the gap.
  - That is 2-6 times the holdout's "known at close" R²; in design the close explained nothing at all.
  - That information does not exist at 15:20.
  - "Professional" gap calls are made at 08:30-09:00, from the US close and GIFT NIFTY, not at the Indian close.
- **The usual direction is "up", and that is hard to beat.**
  - 2012 to Feb 2025: NIFTY opened above the previous close on 67% of days (3,231 days).
  - No rule or model beat simply saying "gap up" (hit rates 52-71% vs the base rate's 61-71% on the same nights),
    although the momentum signals were real (better than random sides, p < 0.001).
- **In the last 20 months the up-drift faded** (gaps up on 50-55% of days, 412 days).
  - On BANKNIFTY and FINNIFTY, "the day's direction / close location continues overnight" was then right 56-59% of
    the time vs 50-51% for "always up".
  - On NIFTY and SENSEX it was barely different from "up" (53-56% vs 54-55%).
  - On BANKNIFTY, Night's own strong-close + OI rule was right 64% of the time vs 54% (126 nights).
  - That is a tilt, not a prediction. These numbers come from the locked holdout and passed nothing in design, so they
    are information only.
- **The SIZE of the gap is more predictable than its direction.** On daily data, the R² for the gap's size (from
  India VIX and recent range) is 0.14-0.30 on BANKNIFTY, FINNIFTY and SENSEX, and 0.01-0.12 on NIFTY. It was not
  predictable on the 15:19 minute sample.

**2. Does BTST on such a prediction pay after real costs? Not better than the Night arm already does.**

- **Every BTST rule held the 1-ITM option overnight with Night's contract rules.** That means 1 lot, bought at 15:20,
  sold at 09:16, after app fills, charges (Rs 20 an order, STT 0.15%, exchange, stamp, GST) and the 0.16% half-spread.
- **Carrying any option overnight costs about 2-4% of its premium.** Bought on both sides (a call and a put together),
  the pair lost:
  - Rs 258-415 a night in design (Aug 2024 to Feb 2025);
  - Rs 790-835 a night in the holdout.

  So a side guess must be right by enough to beat that.
- **"Always buy the call"**, the gap-up base rate, **loses**: Rs -289 to -728 a trade in every period (82-212 trades).
- **The new models do not pass.**
  - Logistic, gradient trees and last-30-minutes failed design: Rs -563 to +166 a trade.
  - None passed the pre-registered gates.
- **The strong close without the OI check (R17)** made money in both periods: Rs +209 to +512 a trade. It failed the
  design gate (BH q 0.38, and less per session than Night), so it stays a note.
- **The Night (R3) rule, rebuilt here, is still the best.** (The rebuild uses a cross-index breadth proxy.)

  | Night (R3) proxy | trades | per trade | win | total | worst month | months up | max drawdown | random-side twin p |
  |---|---|---|---|---|---|---|---|---|
  | design NIFTY | 47 | Rs 188 | 57% | Rs 8,813 | Rs -8,143 | 4/7 | Rs -10,357 | 0.17 |
  | design BANKNIFTY | 50 | Rs 816 | 66% | Rs 40,812 | Rs -5,934 | 5/7 | Rs -9,648 | 0.013 |
  | holdout NIFTY | 84 | Rs 480 | 52% | Rs 40,346 | Rs -15,419 (Aug 2025) | 7/12 | Rs -21,648 | 0.008 |
  | holdout BANKNIFTY | 110 | Rs 458 | 55% | Rs 50,420 | Rs -8,579 (Jan 2026) | 9/12 | Rs -20,162 | 0.002 |

- **Holding longer does not help (as R27 found).** Selling at 09:30 instead of 09:16 cut Night's holdout result from
  Rs 480 to Rs 74 a trade on NIFTY and from Rs 458 to Rs 34 on BANKNIFTY. The gap is already in the 09:16 price.
- **A -30% stop changes little.** Rs 484 / 464 a trade vs Rs 480 / 458 without one.
- **Gaps can jump through a stop.** In the holdout the NIFTY weekly option opened through its stop on 9 of 84 Night
  trades.
  - 17% of NIFTY Night trades lost more than 30% of the premium overnight; 5% on BANKNIFTY's monthly.
  - The worst single nights lost Rs 5,700 (NIFTY) and Rs 6,700 (BANKNIFTY) on 1 lot.

**3. What I would change in the app: nothing now.** Notes only:

- **Keep the Night (R3) arm as it is** (15:20 buy, 09:16 sale, 1-ITM, the OI check). R146 tested 5 new overnight
  trade rules and 36 gap predictors, and nothing beat it in both periods.
- **Do not add an "always buy the call overnight" arm or a machine-learning gap model.** Both lose after the overnight
  carry cost.
- **A possible later forward test (not a change).** Log, in SHADOW only, the side that Night's strong close would take
  WITHOUT the OI check on BANKNIFTY. In the holdout it took 147 trades vs Night's 110 at a similar Rs 398 a trade. It
  failed the design gate, so it would need its own pre-registration and about 100 new nights before any paper use.
- **For research: never trust the 09:15 option open in minute data.** It is often a stale print that equals the bar's
  high, 20-50% above where the option really trades (NOTES A3). The app's 09:16 sale avoids it.

## What was asked, and how it was tested

**Two parts, one locked holdout (entry days from 1 Mar 2025), read once:**

| part | data | design (walk-forward) | holdout |
|---|---|---|---|
| D: "is the gap predictable at all?" | Yahoo daily bars 2007 to 4 Nov 2026: NIFTY, BANKNIFTY, FINNIFTY, SENSEX, India VIX, Nikkei, Hang Seng, KOSPI, S&P 500, USD/INR | test years 2012 to Feb 2025, retrained every year (3,227-3,242 nights per index) | 1 Mar 2025 to 3 Nov 2026 (411-412 nights) |
| M: "at 15:19 exactly, and as a trade" | the repo's `research-data` release: NIFTY and BANKNIFTY index + nearest-expiry option minutes, FINNIFTY, SENSEX and India VIX minutes (Feb 2024 to Feb 2026) | test months Aug 2024 to Feb 2025, retrained every month (145-146 nights per index) | 1 Mar 2025 to 20 Feb 2026 (238-239 nights) |

- **Why two parts.** The Drive data lake cannot be read from this session (its rclone build has no Drive backend), so
  the minute part covers only the two years in the release.
- **D's caveat (stated in advance).** D uses the 15:30 close, 10 minutes after the 15:20 decision, so D slightly
  flatters what is known at 15:20. D still found almost nothing in direction, so the "no" is firm.
- **MIDCPNIFTY** has no daily history on Yahoo and is not in the release, so it was dropped (NOTES A1).

**Predictors known by 15:19, pre-registered:**
- the day's move and close location;
- the last 30 / 60 minutes, the open-to-now move and today's gap;
- breadth across NIFTY, BANKNIFTY, FINNIFTY and SENSEX;
- India VIX and its change;
- Night's OI build-up and its z-score, put/call OI and its change, the ATM straddle's priced move;
- sessions to expiry, expiry day and the day after;
- weekday and weekend;
- scheduled overnight US events (FOMC, US CPI) and next-day Indian events (RBI, Budget, election results);
- Nikkei, Hang Seng and KOSPI closes (all before 15:20 IST);
- the previous S&P session.

US index futures and USD/INR at 15:20 were dropped: there is no point-in-time intraday history for them.

**Prediction ideas:**
- rules: momentum, close location, last 30 minutes, Night's strong close, the Night (R3) proxy, Hang Seng;
- a logistic model and gradient trees;
- compared with the base rate (the training period's usual gap side) and with permutations.

That is 36 ideas, with BH across all 36.

**Trading:** 6 side rules (T0 = the Night proxy baseline, T1-T5) on Night's contract, sold at 09:16. Also reported:
09:15, 09:20 and 09:30 sales and a -30% resting stop. Random-side twins, 2,000 each. BH across the 10 tests.

**Signal source:** every rule reads the index (plus VIX, the option chain's OI and straddle as context, and the Asian
closes) and buys the option. None reads the option's own chart.

## Results

### 1. Direction: known at the close vs the base rate

**D design** (walk-forward 2012 to Feb 2025, about 3,230 nights per index):

| index | gaps up | momentum hit | close-location hit | logistic AUC / hit | best vs base rate |
|---|---|---|---|---|---|
| NIFTY | 66.8% | 55.1% (p < 0.001) | 55.1% | 0.552 / 66.6% | below |
| BANKNIFTY | 62.6% | 55.3% | 54.8% | 0.546 / 61.9% | below |
| FINNIFTY | 63.2% | 54.9% | 54.9% | 0.532 / 60.1% | below |
| SENSEX | 71.2% | 55.3% | 55.0% | 0.556 / 70.8% | below |

- **The signals are real but small.** Picking the side of the day's move gains about 5 basis points a night on average.
  That is far less than the gap-up drift of 11-14 basis points.
- **No idea passed design**, because none beat "always up".

**D holdout** (Mar 2025 to Nov 2026, 412 nights; information only):

- NIFTY momentum: 55.6% vs 53.6%;
- BANKNIFTY close location: 59.3% vs 50.1% (349 nights, p 0.001);
- FINNIFTY close location: 57.6% vs 50.0%;
- logistic AUC 0.57-0.61.

**M design at 15:19** (145-146 nights): nothing beats the base rate (NIFTY gaps up 59.6% from the 15:19 price).

**M holdout** (238-239 nights): NIFTY, nothing beats "up" (58.0%). BANKNIFTY:
- close location: 60.7% vs 53.2% (201 nights);
- Night's strong close: 62.3% vs 52.1% (167);
- the Night proxy: 64.3% vs 54.0% (126, p 0.001).

### 2. What arrives overnight (walk-forward ridge, out-of-sample R² vs the training mean)

| index | known at the close | S&P 500 overnight only | both | correlation of gap with S&P overnight |
|---|---|---|---|---|
| D NIFTY design / holdout | -0.02 / 0.03 | 0.16 / 0.15 | 0.16 / 0.21 | 0.47 / 0.40 |
| D BANKNIFTY | -0.02 / 0.04 | 0.17 / 0.09 | 0.17 / 0.11 | 0.42 / 0.34 |
| D FINNIFTY | -0.22 / 0.04 | 0.17 / 0.10 | 0.01 / 0.11 | 0.42 / 0.34 |
| D SENSEX | -0.08 / 0.03 | 0.20 / 0.14 | 0.15 / 0.19 | 0.46 / 0.38 |
| M NIFTY (15:19) | -0.21 / -0.00 | 0.24 / 0.20 | -0.00 / 0.18 | 0.50 / 0.45 |
| M BANKNIFTY (15:19) | -0.37 / 0.02 | 0.15 / 0.13 | -0.11 / 0.13 | 0.41 / 0.37 |

- **Even a perfect overnight S&P read explains only about 10-25% of the gap.** The rest is other overnight news, Asia's
  next morning and the pre-open auction.
- **The 15:29 close carries the same information as 15:19.** The gap measured from 15:19 and from 15:29 correlate 0.98-0.99.

### 3. BTST trades (1 lot, after all costs, sale at 09:16)

**Design** (Aug 2024 to Feb 2025):

| rule | NIFTY trades / per trade | BANKNIFTY trades / per trade | twin p (N / BN) | BH q |
|---|---|---|---|---|
| T0 Night (R3) proxy | 47 / Rs 188 | 50 / Rs 816 | 0.17 / 0.013 | baseline |
| T1 always the call | 82 / Rs -542 | 102 / Rs -728 | 0.89 / 0.98 | 0.98 |
| T2 strong close (R17) | 55 / Rs 324 | 68 / Rs 512 | 0.077 / 0.042 | 0.38 |
| T3 logistic | 74 / Rs -372 | 89 / Rs 166 | 0.70 / 0.20 | 0.67-0.98 |
| T4 gradient trees | 71 / Rs -563 | 82 / Rs -101 | 0.86 / 0.50 | 0.98 |
| T5 last 30 minutes | 52 / Rs -129 | 81 / Rs -458 | 0.38 / 0.83 | 0.94-0.98 |

**Holdout** (Mar 2025 to Feb 2026; read once, information only since nothing passed design):

| rule | NIFTY trades / per trade / Rs a session | BANKNIFTY trades / per trade / Rs a session | twin p (N / BN) |
|---|---|---|---|
| T0 Night (R3) proxy | 84 / Rs 480 / 169 | 110 / Rs 458 / 211 | 0.008 / 0.002 |
| T1 always the call | 131 / Rs -405 / -222 | 212 / Rs -289 / -256 | 0.49 / 0.28 |
| T2 strong close (R17) | 95 / Rs 209 / 83 | 147 / Rs 398 / 245 | 0.032 / 0.001 |
| T3 logistic | 100 / Rs 422 / 177 | 153 / Rs 173 / 111 | 0.011 / 0.003 |
| T4 gradient trees | 97 / Rs 190 / 77 | 138 / Rs -460 / -266 | 0.030 / 0.58 |
| T5 last 30 minutes | 82 / Rs 100 / 34 | 148 / Rs -165 / -102 | 0.070 / 0.20 |

`results/report.md` has every column: win rate, worst month, months up, drawdown, worst trade, the 1.5× spread stress,
and the 09:15 / 09:20 / 09:30 and stop variants.

- **The logistic model flips sign between periods** (NIFTY Rs -372 in design, Rs +422 in the holdout): it is not a rule
  to trust.
- **The strong close without the OI check is the only new rule positive in both periods on both indices**, but it is
  below Night per session in design and fails BH.

## Honesty notes

- **Pre-registered before any data was processed.** Three data amendments were made before any result, all from
  drop-reason counts only (NOTES A1-A3):
  - MIDCPNIFTY dropped;
  - the fill guard relaxed for real gaps;
  - the stale 09:15 option open handled.
- **Holdout read once** (flag in `results/`). Design results were committed before it (`f5ef0c5`).
- **The Night proxy is not the app's Night trade for trade.**
  - Breadth: the cross-index sign breadth, not 214 stocks.
  - Before Nov 2024 the release holds BANKNIFTY's weekly, not its monthly.
  - The app's live-only gates (thin option, kill switch, day lock) and R144c's paper VIX skip are not applied.
  - R27's lake rebuild of the real rule made Rs 245 a trade on Oct 2025 to Oct 2026 (173 trades); the proxy made
    Rs 458-480 on Mar 2025 to Feb 2026 (194 trades). They are different periods and inputs.
- **Small samples.** The minute design has 145-146 nights per index (7 months), so the design trading tests had 47-102
  trades. A rule needs about 100+ trades a side for a stable verdict.
- **Multiple testing.** BH was applied within R146: 36 prediction ideas and 10 trading tests. The overnight family
  already holds 10,269 logged variants. Against it, even p = 0.001 gives q near 1.
- **Costs.** Today's app charges (STT 0.15%) were applied to every date. This is a little harsher than the dated rates
  of 2024-25 (0.0625-0.1%): Rs 5-15 a trade.
- **One reporting bug after the holdout (NOTES A4).** The NIFTY holdout size-model summary is null because of one night
  with no next open. The same model's R² is in the decomposition table. Nothing was re-run.
- **No raw data in git.** The release files and Yahoo bars stay in `~/.cache/iraalgo/`.

## How to reproduce

```
gh api repos/mevishalsonawane-ai/options-lab/releases/tags/research-data   # then download the assets to ~/.cache/iraalgo/release
python -m research.hunt.r146_gap_btst.mdata
python -m research.hunt.r146_gap_btst.yahoo fetch
python -m research.hunt.r146_gap_btst.study build
python -m research.hunt.r146_gap_btst.study design
python -m research.hunt.r146_gap_btst.study holdout      # refuses a second read
python -m research.hunt.r146_gap_btst.study report
python -m pytest research/hunt/r146_gap_btst/tests -q
```
