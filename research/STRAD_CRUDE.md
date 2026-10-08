# STRAD-CRUDE: buy the crude straddle when a big move is forecast and the straddle looks cheap?

Written 8 Oct 2026 by STRAD-CRUDE. Follow-up to `research/NN_CRUDE.md`. Boss: "trade the SIZE of the move, not the direction."

- Plan, written before any P&L: `research/hunt/strad_crude/PREREG.md` (with its amendments, all made before any P&L).
- Code: `research/hunt/strad_crude/` (`fetch_crude.py`, `snap_chain.py`, `build.py`, `analyze.py`, `dhan_io.py`).
- Result tables: `research/hunt/strad_crude/results/*.csv|json`.
- Data and logs: `scratchpad/hunt/strad_crude/` (about 140 MB: option minutes, entry paths, chain snapshots, logs).
- Nothing committed. `android/` untouched. The Dhan token was never printed or saved anywhere else.

## Verdict

**NO. Buying the MCX crude straddle or strangle does not pay after costs. That holds when the size forecast fires,
when the straddle looks cheap, when both happen, and around EIA.**

1. **The size forecast works. The trade does not.**
   - The HAR forecast tracks the realised move well. Out-of-sample correlation (log scale) is 0.80-0.90.
     My MLP did worse (0.17-0.36): it extrapolated badly into the March 2026 shock.
   - But a bigger move is already in the option price. Gross P&L per trade is about zero.
2. **Costs are about Rs 300 per straddle round trip.** That is 4 orders, CTT, exchange fee, GST and the bid/ask
   spread on two legs. Gross per trade is about Rs 0 for "always buy", so every rule loses.
3. **Design period (Oct 2025 - Mar 2026, walk-forward):**
   - **0 of 1,488 variants made money after costs.**
   - 555 of 1,380 variants with at least 30 trades were gross-positive. None was significant: the lowest BH q is 1.00.
   - Picking a rule each month from the earlier months lost Rs 39.6k over 6 months (-Rs 327/day).
4. **Holdout (1 Apr - 8 Oct 2026, run once, "partly seen"):** the frozen rule lost.
   - The rule: "size AND cheap", HAR, 30 min, strangle, stop -25%.
   - It made 52 trades and won 37% of them.
   - Gross was **+Rs 171/trade**. Net was **-Rs 348/trade**, or **-Rs 140/day**.
   - Max drawdown was Rs 26k. 1 of 5 months was green. Random p = 0.10.
   - Every other frozen comparison rule lost more.
5. **Is the crude straddle cheap or dear?**
   - **Intraday it is dear.** Over 15-240 minutes the realised move was 0.60-0.66x the move implied by IV in design,
     and 0.65-0.74x in the holdout. It is dearest in the Indian morning (0.46-0.60x).
   - **Held to expiry it is roughly fair.** The payoff was 0.91x the premium in design and 1.04x in the holdout
     crisis.
   - Even so, gross intraday P&L of "always buy" is about Rs 0, not deeply negative. Option decay is not spread
     evenly over trading minutes, so the 0.6x ratio overstates how dear it really is.
6. **EIA Wednesday (enter 15 minutes before 20:00/21:00 IST, exit at the release + 5/15/30/60 min): NO.**
   - Design: -Rs 368 to -695 net per trade on 16-20 trades.
   - Holdout (best design variant, straddle out at +5 min): Rs -2 net per trade on 20 trades.
     That is better than the same clock on other days (p = 0.015), but the net is zero.
7. **Rs 1 lakh blocks the one month that made money.** In Mar-Apr 2026, 85% of ATM straddles cost more than
   Rs 1 lakh per lot (median Rs 1.4-1.6 lakh). Those entries are skipped. So the March 2026 "long vol" windfall seen
   by NN-CRUDE is not tradable at this capital.

**Honest variant count: 1,496.** That is 1,488 grid variants (4 horizons x 2 structures x 6 exits x 31 filters) plus
8 EIA variants. All of them were run. BH was done across all 1,496: the minimum q is 1.00.

## Honesty notes (read before the numbers)

- **The holdout is "partly seen".** NN-CRUDE had already opened 1 Apr - 8 Oct 2026 once, for direction, and saw the
  size AUC there (0.57-0.58). I chose everything on Aug 2025 - Mar 2026. Then I ran the frozen rules on the holdout
  once.
- **When I started, the NN-CRUDE files were not on disk.** The container had been restored. So:
  - I re-fetched the data myself: Dhan `rollingoption`, MCX CRUDEOIL. Near month ATM-3..ATM+3 calls and puts, plus
    next month ATM. 5 Aug 2025 - 8 Oct 2026, 1-minute, with IV, OI and spot. That is 16 series of about 256k rows.
  - My "size MLP" is my own: an sklearn MLPRegressor (16,8) on the HAR inputs. **It is not NN-CRUDE's classifier.**
    It was trained walk-forward on data before each test month only.
  - Later the NN-CRUDE code reappeared. I took its official EIA exception calendar and h28's CPI/FOMC dates
    (amendment made before any P&L).
- **WTI, Brent and INR proxies were not used.** NN-CRUDE showed MCX crude is WTI x USD/INR (5-minute correlation 0.94).
  The minute futures price already carries them.
- **Spread:** the minute data has no bid/ask. I logged 204 live option-chain snapshots on 8 Oct 2026 between 22:24
  and 23:28 IST.
  - Median relative spread: **ATM 0.29%** (Rs 0.70), 1 strike out 0.31%.
  - NN-CRUDE measured 0.19% earlier the same evening, so my number is the more cautious one.
  - Pre-registered: 1x spread after 17:00 and 2x before 17:00 (the morning is thinner). Stress case: 2x everywhere.
- **Pricing gaps:** when a held strike drifted out of the ATM±3 window, I priced that leg with Black-76 at its last
  IV. This applied to 4.5% of leg-minutes overall and 11-16% in Mar-May 2026.
- I skipped expiry days. A trade is skipped if the entry cost is above Rs 1 lakh. Exits use 1-minute closes.

## Setup (pre-registered)

- 1 lot (100 bbl), Rs 1 lakh, one position at a time. Decisions every 15 minutes from 09:15. Forced exit by 23:25.
- Structures:
  - **Straddle:** ATM call + ATM put. ATM is the strike nearest the future.
  - **Strangle:** ATM+1 call + ATM-1 put.
- Forecasts, refit every month on earlier data only (anchored, 1-day purge):
  - **HAR:** OLS on log realised vol over the last 15m, 60m, today, yesterday and 5 days. Plus a clock-slot seasonal
    term, EIA/CPI-in-window flags, FOMC next day, OPEC Monday and days to expiry.
  - **MLP:** the same inputs.
- **Implied move:** IV²·τ spread evenly over the trading minutes left to expiry, for the same horizon.
- Filters:
  - **A:** always.
  - **B:** size only. The forecast is at or above the q-quantile for that clock slot.
  - **C:** cheap only. Forecast / implied is at or above the q-quantile.
  - **D:** both B and C.
  - q is 0.7, 0.8 or 0.9.
  - **Random:** the same number of trades at random grid times, same exits.
- Exits: time only, or +PT20%, +PT40%, -SL25%, -SL40%, or PT40+SL40, on the combined premium.
- **Costs (Zerodha MCX options):**
  - Rs 20 per order. CTT 0.05% of sell premium. MCX fee 0.0418%. SEBI Rs 10/crore.
  - GST 18% on brokerage + fees. Stamp 0.003% on the buy.
  - Plus the measured spread on every leg, both ways.

## Results: design (Oct 2025 - Mar 2026 test months, 121 sessions)

### Same horizon, straddle, time exit: does each filter help? (HAR, q = 0.8; per lot)

| horizon | rule | trades | hit | gross Rs/trade | net Rs/trade | net Rs/day | max DD Rs | green months | random p |
|---|---|---|---|---|---|---|---|---|---|
| 15 m | A always | 5,599 | 9% | -1 | -311 | -14,386 | 17.4 L | 0/6 | - |
| 15 m | B size only | 1,915 | 12% | +9 | -377 | -5,964 | 7.2 L | 0/6 | 1.00 |
| 15 m | C cheap only | 681 | 13% | -19 | -263 | -1,481 | 1.8 L | 0/6 | 0.002 |
| 15 m | D size + cheap | 253 | 17% | -9 | -295 | -616 | 75k | 0/6 | 0.25 |
| 30 m | A always | 2,798 | 14% | -4 | -316 | -7,296 | 8.8 L | 0/6 | 0.59 |
| 30 m | B size only | 1,061 | 17% | +14 | -370 | -3,245 | 3.9 L | 0/6 | 1.00 |
| 30 m | C cheap only | 371 | 16% | -35 | -280 | -858 | 1.0 L | 1/6 | 0.13 |
| 30 m | D size + cheap | 147 | 20% | -38 | -323 | -392 | 49k | 1/6 | 0.57 |
| 60 m | A always | 1,398 | 19% | -13 | -325 | -3,753 | 4.5 L | 0/6 | 0.63 |
| 60 m | B size only | 564 | 24% | +31 | -359 | -1,672 | 2.0 L | 0/6 | 0.92 |
| 60 m | C cheap only | 200 | 18% | -100 | -339 | -561 | 68k | 0/6 | 0.66 |
| 60 m | D size + cheap | 66 | 15% | -145 | -426 | -233 | 28k | 0/6 | 0.88 |
| 240 m | A always | 299 | 28% | +6 | -316 | -781 | 1.05 L | 0/6 | 0.56 |
| 240 m | B size only | 131 | 34% | +140 | -263 | -285 | 48k | 0/6 | 0.41 |
| 240 m | C cheap only | 60 | 17% | -367 | -656 | -325 | 40k | 0/6 | 0.96 |
| 240 m | D size + cheap | 17 | 24% | -454 | -799 | -112 | 14k | 1/5 | 0.94 |

All BH q values are 1.00.

- **"Size only" raises gross a little at every horizon:** +9 to +140 Rs/trade against about 0 for "always". So the
  size forecast does find bigger moves.
- But net gets worse. Those times have higher premiums (higher IV), so costs and spread are bigger.
- **"Cheap only" lowers gross.** When forecast/implied is high, the straddle is not underpriced. It is usually just
  a quiet IV moment before a slow period.
- The "random p 0.002" for C at 15 minutes means it lost less than random times did. It did not make money.
- Exits barely matter. Within 15-60 minutes the premium rarely moves 20-40%, so PT and stop almost never trigger.

### The family bests (chosen on design; these were frozen for the holdout)

| rule | trades | hit | gross/trade | net/trade | gross/day | net/day | max DD | green months | random p |
|---|---|---|---|---|---|---|---|---|---|
| **FINAL = D:** HAR size q0.9 + cheap q0.9, 30 m, strangle, SL25 | 49 | 25% | +21 | -258 | +8 | -104 | 17.7k | 1/6 | 0.31 |
| B: MLP size q0.8, 240 m, strangle, PT20 (best gross: +276) | 170 | 41% | +276 | -100 | +388 | -141 | 48.1k | 1/6 | 0.13 |
| C: HAR cheap q0.9, 240 m, strangle, PT40 | 34 | 18% | -487 | -762 | -137 | -214 | 25.9k | 0/6 | 0.97 |
| A: always, 240 m, strangle, SL25 | 305 | 30% | +41 | -274 | +103 | -690 | 95.0k | 0/6 | 0.58 |

- The "best" variant was the one that traded least. That is how ranking by Rs/day works when everything loses.
- At 2x spread everywhere, these lose -Rs 360 to -900 per trade.

### Walk-forward: pick each month from earlier months, trade the next

| month | rule picked | trades | gross Rs | net Rs |
|---|---|---|---|---|
| Oct 2025 | A 240 m strangle PT20 | 58 | +3,140 | -12,264 |
| Nov 2025 | D MLP 0.7/0.7 240 m strangle PT40+SL40 | 21 | -870 | -7,117 |
| Dec 2025 | D HAR 0.9/0.8 30 m strangle PT40 | 8 | -910 | -3,494 |
| Jan 2026 | B HAR 0.8 240 m strangle SL25 | 32 | +910 | -11,253 |
| Feb 2026 | D HAR 0.9/0.9 30 m straddle SL25 | 14 | -4,430 | -8,640 |
| Mar 2026 | D HAR 0.9/0.9 30 m strangle SL25 | 1 | +3,720 | +3,164 |
| **total** | | 134 | **+1,560** | **-39,604** |

## Results: holdout (1 Apr - 8 Oct 2026, 135 sessions, run once, PARTLY SEEN)

| rule (frozen) | spread | trades | hit | gross/trade | net/trade | gross/day | net/day | max DD | green months | random p |
|---|---|---|---|---|---|---|---|---|---|---|
| **FINAL (D, 30 m strangle SL25)** | 1x | 52 | 37% | **+171** | **-348** | +69 | **-140** | 26.1k | 1/5 | 0.10 |
| FINAL | 2x | 50 | 28% | -16 | -838 | -6 | -325 | 42.6k | 0/5 | 0.47 |
| B (MLP size, 240 m strangle PT20) | 1x | 67 | 27% | -964 | -1,569 | -500 | -815 | 1.27 L | 0/7 | 0.93 |
| C (cheap, 240 m strangle PT40) | 1x | 77 | 29% | -306 | -780 | -183 | -466 | 85.6k | 3/7 | 0.25 |
| A (always, 240 m strangle SL25) | 1x | 279 | 37% | -145 | -666 | -313 | -1,440 | 2.09 L | 0/7 | 0.04 |
| EIA (straddle, in 15 m before, out release+5) | 1x | 20 | 30% | +365 | -2 | +57 | -0.3 | 3.3k | 2/7 | 0.015 |

- FINAL by month (net Rs): Apr -1,535 (6 trades), May -5,901 (27), Jun +508 (7), Sep -9,048 (10), Oct -2,140 (2).
  Jul and Aug had no signal.
- Gates (pre-registered): walk-forward net > 0 **failed**; holdout net > 0 **failed**; random p < 0.05 **failed**;
  BH q < 0.10 **failed**; 60% green months **failed**.
- The holdout per-trade costs are larger (Rs 500+). Crude premiums were Rs 55-140k a lot, so the percentage
  charges and the spread are bigger.

## Implied vs realised: is the crude straddle cheap or dear?

Realised mean |move| divided by the implied mean |move| for the same window. The implied move is IV²·τ spread
evenly over the trading minutes to expiry.

| horizon | design: morning 09:15-14:00 | afternoon 14:00-17:30 | evening 17:30-23:25 | design all | holdout all |
|---|---|---|---|---|---|
| 15 m | 0.46 | 0.65 | 0.67 | 0.60 | 0.65 |
| 30 m | 0.47 | 0.64 | 0.68 | 0.60 | 0.66 |
| 60 m | 0.49 | 0.65 | 0.68 | 0.61 | 0.68 |
| 240 m | 0.59 | 0.74 | 0.71 | 0.66 | 0.74 |

- Held to expiry, from a 15:00 entry each day, the payoff |F at expiry - K| was 0.91x the ATM straddle premium in
  design (151 days, average premium Rs 470/bbl). In the holdout it was 1.04x (108 days, Rs 866/bbl).
- So the crude straddle is a bit dear in calm months and about fair in a crisis. **It is never reliably cheap.**
- Intraday it looks dear (0.6x). Part of that is an artefact: overnight and weekends carry variance and decay that
  an intraday holder neither pays nor earns. "Always buy" ends near zero gross, not at -40%.

## Event days (always buy the straddle at every grid time, time exit; net Rs per trade)

| event | design 30 m | design 240 m | holdout 30 m | holdout 240 m |
|---|---|---|---|---|
| EIA release inside the window | -409 (38) | -718 (184) | -34 (40) | -1,549 (264) |
| EIA day, any time | -319 | -459 | -536 | -1,169 |
| OPEC Monday (approximate dates) | -350 | -461 | -561 | -1,276 |
| US CPI inside the window | -535 (8) | -689 | -1,058 (10) | -4,484 |
| FOMC day | -339 | -234 | -543 | -184 |
| FOMC next day | -281 | **+24** (123 entries, 3 days) | -620 | -1,735 |
| no event | -310 | -245 | -534 | -1,076 |

The number of entries is in brackets where it is small.

- Realised vs implied on the event slots is 0.5-1.0x, about the same as on normal days. Scheduled events are priced in.
- The only positive cell (FOMC next day, 240 m, design) is 3 days, and it turned negative in the holdout.

## What this means for the Boss

- "Trade the size, not the direction" is the right idea for crude: size is predictable and direction is not
  (NN-CRUDE). But a crude straddle buyer is paid only when realised moves beat what the market already charges. The
  market charges for the predictable part.
- At 1 lot the costs (about Rs 300-500 a round trip) eat everything. Gross edges are Rs 0-280 a trade at best, and
  they are not stable.
- **Rs 5,000/day from this: NO.** Nothing here makes Rs 1/day net.

## Files

- `research/hunt/strad_crude/PREREG.md`: plan plus amendments, all made before any P&L.
- `research/hunt/strad_crude/fetch_crude.py`: Dhan rollingoption fetch. `snap_chain.py`: live bid/ask snapshots.
  `dhan_io.py`: token-redacting wrapper.
- `research/hunt/strad_crude/build.py`: minutes, expiries, events, entries, leg price paths.
- `research/hunt/strad_crude/analyze.py`: `predict` (walk-forward HAR/MLP), `design`, `holdout`.
- `research/hunt/strad_crude/results/`:
  - `design_variants.csv` (all 1,488), `design_eia.csv`, `design_walkforward.csv`, `design_stress2x.csv`;
  - `frozen_rules.json`, `holdout_results.csv`, `holdout_final_trades.csv`, `holdout_final_months.csv`;
  - `gap_*.csv`, `events_*.csv`, `spread_model.json`, `forecast_quality_design.csv`.
- Logs and data: `scratchpad/hunt/strad_crude/` (`fetch*.log`, `build.log`, `predict.log`, `design*.log`,
  `holdout.log`, `chain_snaps.csv`, `cache/`, `work/`).
