# MCX options for a buyer: what is cheap, what is dear, and does anything pay?

Written 8 Oct 2026 by M4. Option BUYING only, Zerodha, Rs 1 lakh, 1 lot.
- Books: CRUDEOIL, NATURALGAS, GOLDM, SILVERM. These are the near-month books. Strikes ATM-3..ATM+3, calls and puts.
- Data: 1-minute bars from 5 Aug 2025 to 7-8 Oct 2026. Crude is from `scratchpad/hunt/strad_crude/cache`. The other
  three are M3's fetch (`scratchpad/hunt/m3/raw`).
- Plan: `research/hunt/m4/PREREG.md`. It was written before any P&L. It has 4 amendments, each dated, and each made
  before the numbers it affects.
- Code: `research/hunt/m4/` (`build.py`, `analyze.py`, `events.py`, `tables.py`).
- Results: `research/hunt/m4/results/*.csv|json`. Logs: `scratchpad/hunt/m4/` (61 MB).
- Nothing committed. `android/` untouched. I made no Dhan calls and never read the token.
- **Design** = 5 Aug 2025 to 7 Jul 2026. **Holdout** = 8 Jul to 8 Oct 2026 (65-66 sessions), run once at the end.
  For crude, part of this holdout was already seen by NN-CRUDE and STRAD-CRUDE.

## Verdict

**NO. No MCX option bucket is reliably underpriced for a buyer after costs. Nothing here is a trade yet.**

1. **The average MCX option loses money for a buyer, gross and net, in every book.**
   - Held 60 minutes at 1 lot, gross was -Rs 5 to -85 per trade.
   - Net was -Rs 150 to -340 per trade, in design and in the holdout.
   - Realised moves were 0.73-0.93 of what the options priced (60-minute window).
2. **Where the buyer bleeds most:** expiry day (-2% to -4.5% of premium per hour, delta-hedged), 1-3 days to expiry,
   and the evening session (-0.5% to -1.0% per hour).
3. **Where the buyer bleeds least:** morning entries (09:15-14:00), 11-20 days to expiry, and far-OTM strikes.
   There, delta-hedged P&L is about zero. It was slightly positive in design and mostly negative in the holdout.
   - "Mornings are dearest" (STRAD-CRUDE) holds on the realised/implied ratio (0.54-0.85).
   - But morning option prices hardly decay. So a morning buyer loses the least, not the most.
4. **The pre-registered test (Q4) did not pass.** 11 of 876 testable cells passed the design gate. All were morning
   cells, mostly calls in rising markets.
   - The frozen rule was: buy the SILVERM OTM2-3 call, 11-20 days to expiry, at the first morning grid time; exit
     after 4 hours.
   - Holdout: 51 trades, **+Rs 454 net per trade, +Rs 23.2k in 3 months (about Rs 350 a day)**. Max drawdown was
     Rs 13.8k. 3 of 4 months were green.
   - But it failed 2 of the 4 gates. Random p = 0.050 (the gate is < 0.05). Walk-forward lost Rs 3.5k.
   - The top 3 trades made 145% of the net. All the profit came from 09:15-10:45 entries. That is when spreads are
     unmeasured and probably widest. One extra 1% of spread at entry and exit turns it into -Rs 218 a trade.
5. **Skew, IV and OI do "predict" the next 15-60 minutes statistically, but nobody can trade it.**
   - Several signals passed the IC gate in design and held their sign in the holdout:
     - NATURALGAS: risk reversal, its change, and the synthetic-forward basis;
     - GOLDM: risk-reversal change;
     - all 4 books: change in put-minus-call OI.
   - Every one of them lost money as a trade. Net was -Rs 66 to -476 per trade in the holdout. This is h29 again: the
     options move first, and the buyer pays for the move.
6. **Events are priced in.**
   - Gas-storage Thursday breakout (M1 idea #6): it lost on all 12 design variants (-Rs 94 to -714 per trade). Then it
     won in the holdout on 9-13 trades. That is a sign flip on tiny samples.
   - The CPI breakout on GOLDM/SILVERM (#7) had only 2-9 trades per variant. It cannot be tested.
   - The volatility-gated straddle (#9) lost in every book and both periods.
7. **Next-month options are dead.** Crude next-month ATM has no trade in 80% of minutes. Its price is unchanged in
   78% of minutes.

**Rs 5,000/day from MCX option buying: NO.** The best frozen idea made about Rs 350 a day in the holdout, and it was not
statistically clean.

**Honest count.** Every variant tried, all run:
- Q1: 1,800 cells (876 with enough data);
- Q3: 50 signal tests, 8 signal trades and 3 control trades;
- Q4: 22 candidate rules, plus a walk-forward universe of 3,600 cell × exit combinations;
- events: 48 rule cells plus their non-event-day baselines;
- straddles: 8 rules.

## Costs and spreads used

- **Zerodha MCX options** (checked on zerodha.com/charges, 8 Oct): Rs 20 per order; CTT 0.05% of the sell premium;
  MCX 0.0418%; SEBI Rs 10/crore; GST 18%; stamp 0.003% on the buy.
- **Spread**: half the spread is paid on entry and half on exit. Before 17:00 the spread is doubled. The stress case
  doubles it everywhere.
  - Crude was measured live: 0.29% at ATM (204 snapshots, STRAD-CRUDE).
  - The other books were **never measured live**. I estimated them from minute data with two proxies (1-minute
    high-low, and a Roll estimator), both scaled to crude's measured number. I used the larger of the two:
    NATURALGAS 0.35%, GOLDM 0.30%, SILVERM 0.41%.
  - Treat these as rough. GOLDM and SILVERM options have **no trade in 42-47% of minutes**.
- **Data cleaning** (amendment 4). A minute with no volume has no price. An entry needs a real trade at that minute,
  with an IV inside 0.5-2x the median IV of the chain at that minute.
  - Without this, stale GOLDM/SILVERM prints made fake +1,000% "returns".
  - Crude's straddle-jump expiry detection matched STRAD-CRUDE's 15 expiries exactly. The other books' expiries were
    inferred the same way.
  - One GOLDM day (26 Mar 2026) was dropped because the data switches contract in the middle of the session.

Round-trip cost at 1 lot, evening, ATM: crude 0.68%, NATURALGAS 0.76%, GOLDM 0.64%, SILVERM 0.73% of premium.
In the morning it is about 1.0-1.2%.

## Q1. Cheap or dear?

Three measures:
- **VR**: realised move / implied move over the holding window. The implied move is spread evenly over trading
  minutes. VR below 1 means the option priced more movement than happened.
- **DH**: P&L of a long option, delta-hedged every 15 minutes, as a % of premium, before costs. This is the honest
  cheap/dear measure. Above 0 means the option was too cheap.
- **UN**: what a plain buyer gets, net of costs, as a % of premium.

### By book (all strikes, all times, non-expiry and expiry days pooled)

| book | VR 60m design / holdout | DH 60m % design / holdout | DH 240m % | net Rs/trade 60m (1 lot) design / holdout | median lot premium |
|---|---|---|---|---|---|
| CRUDEOIL | 0.75 / 0.77 | -0.29 / -0.30 | -1.21 / -1.31 | -273 / -328 | Rs 20k / 34k |
| NATURALGAS | 0.85 / 0.80 | -0.11 / -0.13 | -0.46 / -0.57 | -197 / -156 | Rs 18k / 13k |
| GOLDM | 0.89 / 0.78 | -0.45 / -0.20 | -1.57 / -1.13 | -253 / -236 | Rs 22k / 24k |
| SILVERM | 0.93 / 0.73 | -0.56 / -0.39 | -1.67 / -1.65 | -340 / -340 | Rs 23k / 29k |

- Natural gas is the "least dear" book. Its options lose the least per hour.
- My VR for crude (0.75) is higher than STRAD-CRUDE's 0.60. I use the square root of the summed variances. STRAD-CRUDE
  used the mean absolute move, which fat tails pull lower. The ranking is the same.

### By time of day (DH, % of premium; design / holdout)

| book | morning 60m | afternoon 60m | evening 60m | morning 240m | evening 240m | VR morning 60m |
|---|---|---|---|---|---|---|
| CRUDEOIL | +0.19 / +0.05 | +0.04 / -0.34 | -0.82 / -0.53 | +0.87 / -0.60 | -2.50 / -1.51 | 0.61 / 0.67 |
| NATURALGAS | +0.11 / -0.15 | +0.03 / -0.13 | -0.32 / -0.11 | +0.25 / -1.34 | -0.67 / -0.26 | 0.62 / 0.54 |
| GOLDM | +0.01 / +0.26 | +0.08 / -0.23 | -0.97 / -0.50 | +0.06 / -0.34 | -2.73 / -1.56 | 0.85 / 0.71 |
| SILVERM | +0.05 / +0.03 | -0.29 / -0.48 | -1.04 / -0.61 | -0.05 / -1.05 | -2.73 / -1.73 | 0.80 / 0.65 |

**The morning effect, in plain words.**
- In the morning the market moves less than the IV implies (VR 0.54-0.85). That is "mornings are dear".
- But the option price barely decays in the morning. Most of the decay is charged in the evening, when the US is
  open.
- So a buyer who enters in the morning and exits by mid-afternoon loses about nothing before costs. A buyer who holds
  through the evening loses 1-3% of premium.
- It is a timing effect. It was not strong enough to beat costs in the holdout.

### By days to expiry (DH 60m %, design / holdout)

| book | expiry day | 1-3 days | 4-10 | 11-20 | 21+ |
|---|---|---|---|---|---|
| CRUDEOIL | -2.91 / -4.02 | -0.69 / -0.53 | -0.11 / -0.10 | -0.02 / +0.01 | -0.11 / -0.35 |
| NATURALGAS | -2.41 / -0.95 | +0.27 / -0.50 | -0.09 / -0.15 | -0.05 / -0.03 | +0.23 / +1.44 |
| GOLDM | -4.24 / -2.23 | -0.09 / -0.13 | -0.33 / -0.16 | -0.12 / -0.02 | -0.24 / -0.13 |
| SILVERM | -4.47 / -3.86 | -0.39 / -0.00 | -0.19 / -0.33 | -0.17 / -0.07 | -0.01 / -0.03 |

- **Never buy on expiry day.** You lose 2-4.5% of premium per hour before costs.
- 11-20 days to expiry is the least dear in every book.
- NATURALGAS at 21+ days looked cheap in the holdout (+1.44%). That is 1-2 sessions; ignore it.

### By strike (DH 60m %, design / holdout)

| book | ITM2-3 | ITM1 | ATM | OTM1 | OTM2-3 |
|---|---|---|---|---|---|
| CRUDEOIL | -0.59 / -0.92 | -0.33 / -0.34 | -0.39 / -0.34 | -0.34 / -0.25 | +0.07 / +0.29 |
| NATURALGAS | -0.29 / -0.13 | -0.08 / -0.05 | -0.20 / -0.09 | -0.20 / -0.22 | +0.13 / -0.13 |
| GOLDM | -0.85 / -0.51 | -0.67 / -0.17 | -0.67 / -0.15 | -0.48 / -0.21 | +0.14 / +0.01 |
| SILVERM | -1.26 / -0.71 | -1.06 / -0.38 | -0.77 / -0.35 | -0.52 / -0.33 | +0.40 / -0.21 |

- Far OTM (2-3 strikes out) is the least dear before costs. A cheap option costs more to trade per rupee, though, so
  its net is no better: UN 60m is -0.7% to -2.1%.
- Deep ITM is the dearest per rupee here. That is the opposite of the index-option result in h13/h16, where 1-ITM won
  on cost.

### Events (DH 60m %, all strikes; the release is inside the 60-minute window)

| book | EIA crude in window | gas storage in window | US CPI in window | FOMC day | no event |
|---|---|---|---|---|---|
| CRUDEOIL | -0.5 / +0.3 (VR 1.03 / 0.86) | -0.5 / +0.7 | -2.1 / -0.4 | -0.0 / -0.5 | -0.2 / -0.0 |
| NATURALGAS | -0.4 / +0.1 | -0.2 / +1.1 (VR 1.25 / 1.49) | +0.1 / -0.1 | -0.1 / +0.0 | +0.1 / -0.2 |
| GOLDM | +0.2 / +0.3 | +0.9 / -1.5 | -0.5 / +0.3 (VR 1.33 / 2.31) | -0.2 / +0.3 | -0.3 / -0.3 |
| SILVERM | -0.0 / -0.2 | -0.5 / -0.4 | -1.0 / -0.2 (VR 1.74 / 2.05) | +0.4 / +0.2 | -0.3 / -0.3 |

- The release minutes move more than the IV spread evenly would imply (VR above 1 for gas storage and for bullion on
  CPI).
- But the option already carries that. Delta-hedged P&L stays within ±1%, with flipping signs.
- CPI on GOLDM/SILVERM covers only 5-6 days per period.
- None of the event cells passed the gate.

### Held to expiry (15:00 entry, all 14 options; payoff / premium)

| book | design | holdout |
|---|---|---|
| CRUDEOIL | 0.97 | 1.09 |
| NATURALGAS | 0.89 | 0.57 |
| GOLDM | 1.05 | 0.71 |
| SILVERM | 1.54 (silver's big rally) | 0.67 |

Held to expiry, options are roughly fair to dear. The good numbers come from one trending market.

## Q2. Best convexity per rupee for a directional buyer (evening, design, median)

| book, ATM call | lot premium | delta | leverage (delta·F/premium) | Rs gain per 1% move per Rs 1,000 | decay per hour (model) | flat-hour P&L (measured) | round trip cost | 60m return if right by ≥1σ, net |
|---|---|---|---|---|---|---|---|---|
| CRUDEOIL | Rs 19.8k | 0.52 | 15x | Rs 157 | 0.31% | -0.9% | 0.68% | +10.6% |
| NATURALGAS | Rs 18.4k | 0.53 | 11x | Rs 110 | 0.32% | -0.8% | 0.76% | +11.5% |
| GOLDM | Rs 25.2k | 0.51 | 29x | Rs 318 | 0.36% | -0.9% | 0.64% | +9.7% |
| SILVERM | Rs 29.1k | 0.52 | 17x | Rs 179 | 0.36% | -1.0% | 0.73% | +10.6% |

Across strikes (full table in `results/q2_convexity_*.csv`):
- **OTM2-3 gives the most per rupee when you are right**: +11-14% net after a 1σ favourable hour, against +7-10% for
  ITM2-3.
- **OTM2-3 also costs the most when nothing happens**: -1.0% to -1.3% per flat hour, against -0.5% to -1.4% for ITM2-3.
- **OTM2-3 has the highest cost share**: 0.8-1.0% round trip, against 0.65-0.8%.
- The near-month ATM±3 strikes are close together (about ±2.5% of the price), so the differences are small.
- For a buyer with a real directional edge, ATM to OTM1 near-month is the sensible middle. No option choice creates
  an edge.
- **A flat hour costs 2-3x what Black-76 theta says.** The market charges decay mostly during the active evening
  hours.
- **Next month: not usable.** Crude next-month ATM has no trade in 80% of minutes, and its 60-minute return is
  "0" (stale) in the median.

## Q3. Do skew, IV or OI changes predict the futures?

Spearman IC per day, averaged. The gate: BH q < 0.05 in design, then the same sign with |t| > 2 in the holdout.

| book | signal | horizon | daily IC design (t) | daily IC holdout (t) | trade: net Rs/trade design / holdout |
|---|---|---|---|---|---|
| CRUDEOIL | change in put-minus-call OI | 60m | -0.063 (-4.4) | -0.074 (-2.7) | -354 / -476 |
| NATURALGAS | risk reversal (25-35 delta) | 15m | +0.035 (3.5) | +0.090 (5.4) | -148 / -66 |
| NATURALGAS | 15-min change in risk reversal | 15m | +0.045 (4.6) | +0.066 (4.1) | -207 / -190 |
| NATURALGAS | 5-min synthetic-forward basis change | 15m | +0.060 (5.7) | +0.076 (4.6) | -175 / -138 |
| NATURALGAS | change in put-minus-call OI | 60m | -0.083 (-4.6) | -0.118 (-3.9) | -182 / -117 |
| GOLDM | 15-min change in risk reversal | 15m | +0.071 (2.9) | +0.086 (5.0) | -313 / -225 |
| GOLDM | change in put-minus-call OI | 60m | -0.059 (-3.3) | -0.098 (-4.2) | -370 / -225 |
| GOLDM | OI build-up × last-hour direction | 15m | -0.035 (-2.9) | -0.038 (-2.4) | -245 / -466 |
| control | fade the last hour's move (crude / gas / gold) | 60m | | | -279 / -485, -240 / -142, -233 / -319 |

- The trade rule: buy the ATM option when the signal is beyond its design 20th/80th percentile; 60-minute exit.
- The IC is real and repeats out of sample. It is also tiny: 4-12% rank correlation.
- Gross P&L per trade is near zero (-Rs 273 to +81). Costs of Rs 150-250 make every version lose.
- Random-entry p was 0.16-1.00. No signal trade beat random.
- The ATM IV change (S3) and the term structure (S5, crude only) predict nothing.
- **OI build-up does not help a buyer.** The puzzle in the "put-minus-call OI" signal is a negative daily IC but a
  positive pooled IC. That points to a within-day mechanical effect, not a usable flow signal.

## Q4. The direction-free buy test (pre-registered)

**Gate (design):**
- day-clustered DH > 0 with BH q < 0.10 across all 1,800 cells;
- DH > 0 in at least 2/3 of months;
- net > 0;
- at least 300 entries on 20 days.

11 cells passed.

| design cell (time exit) | trades | net Rs/trade | total | green months | random p |
|---|---|---|---|---|---|
| SILVERM OTM2-3 call, 11-20 d, morning, 4 h | 78 | **+1,439** | +1.12 L | 6/10 | 0.005 |
| GOLDM OTM2-3 call, 11-20 d, morning, 4 h | 112 | +464 | +51.9k | 8/12 | 0.04 |
| SILVERM OTM2-3 call, 4-10 d, morning, 4 h | 70 | +358 | +25.1k | 6/9 | 0.18 |
| CRUDEOIL OTM1 call, 11-20 d, morning, 4 h | 195 | +309 | +60.3k | 10/12 | 0.06 |
| SILVERM OTM2-3 call, 4-10 d, morning, 1 h | 153 | +258 | +39.5k | 5/9 | 0.02 |
| CRUDEOIL ITM1 / ATM / OTM2-3 call, same | 193-199 | +114 to +259 | | 8-10/12 | 0.04-0.12 |
| NATURALGAS ITM1 call 4-10 d / GOLDM OTM1 put / SILVERM afternoon | 93-147 | -65 to -384 | | | 0.28-0.35 |

- Almost all are morning calls in markets that rose in design: crude in March, silver and gold all year.
- The random baseline buys the same option at random times. It removes part of the trend, but not all.
- The stop/target exit (-30%/+50%) changed little.

**Walk-forward** (each month, pick the best of 3,600 cell × exit choices on earlier months only): it lost Rs 3.5k
net over Nov 2025 - Jul 2026 (gross +Rs 26.8k). It **failed** the gate.

**Holdout (run once): SILVERM OTM2-3 call, 11-20 days, morning, 4-hour exit, 1 lot**

| | trades | hit | gross/trade | net/trade | net total | max DD | worst day | green months | random p |
|---|---|---|---|---|---|---|---|---|---|
| spread 1x | 51 | 53% | +832 | **+454** | **+23,178** | 13,785 | -9,987 | 3/4 | 0.050 |
| spread 2x | 51 | 49% | +832 | +176 | +8,978 | 18,861 | -10,554 | 3/4 | 0.053 |

By month (net): Jul +3.7k, Aug +27.9k, Sep -10.8k, Oct (1 week) +2.4k.

**Gates:**
- holdout net > 0: **pass**;
- 60% green months: **pass**;
- random p < 0.05: **fail** (0.050);
- walk-forward > 0: **fail**.

**Verdict: not adopted.** Why I do not trust the holdout profit (post-hoc look, chooses nothing):
- Entries at 09:15-10:45 made +Rs 43k. Entries at 13:15-13:45 lost -Rs 19.8k. So the money is at the open.
- The open is the thinnest, least-measured moment for SILVERM options.
- The top 3 trades are 145% of the total.
- Adding 1% of premium of extra spread each way turns it into -Rs 218 a trade.

**What it would take to believe it:** log live SILVERM and GOLDM option quotes at 09:15-10:00 for a few weeks. If the
real spread at the open is under about 0.6%, paper-trade the rule for 3 months before using money.

## Extra tests asked by the coordinator (M1 ideas #6, #7, #9)

**#6 gas-storage Thursday breakout (NATURALGAS) and #7 CPI breakout (GOLDM, SILVERM).**
- The rule: if the futures move from T-1 to T+5 (or T+15) exceeds k times its usual size, buy the ATM option that way.
  Exit after 15 or 60 minutes.

| book | best design variant | design net/trade (n) | holdout net/trade (n) | same clock, non-event days, holdout |
|---|---|---|---|---|
| NATURALGAS storage | T+5, k=2, 15m | -94 (31) | +4 (9) | -50 (12) |
| NATURALGAS storage | T+15, k=2, 60m | -714 (25) | +2,328 (9) | -261 (12) |
| GOLDM CPI | T+15, k=2, 60m | +360 (4) | +640 (2) | -864 (18) |
| SILVERM CPI | T+15, k=1, 60m | +2,805 (2) | +1,391 (3) | -1,031 (26) |
| CRUDEOIL EIA (reference) | T+5, none, 60m | +33 (47) | +429 (14) | +622 (50) |

- Gas storage: all 12 design variants lost. The holdout won on 9-13 trades. A sign flip on tiny samples is noise, not
  an edge.
- CPI: 2-9 trades per variant (GOLDM/SILVERM options often had no trade near 18:05). It cannot be tested.
  Payroll days were left out: the 2025-26 release dates were moved by the US shutdown, and I had no verified list.

**#9 volatility-gated straddle** (buy the ATM straddle when the last hour's realised vol / implied is in its top 20%;
60-minute exit):

| book | always: net/trade design / holdout | gated: net/trade design / holdout |
|---|---|---|
| NATURALGAS | -348 / -284 | -434 / -162 |
| SILVERM | -847 / -665 | -1,082 / -911 |
| CRUDEOIL | -541 / -608 | -542 / -564 |
| GOLDM | -506 / -450 | -565 / -424 |

It fails everywhere, as it did on crude.

## What this means for the Boss

- Buying MCX options pays only if you can call direction. The options are not systematically cheap anywhere.
- If you buy:
  - buy in the morning or afternoon;
  - avoid holding through 18:00-23:30, when most of the decay is charged;
  - never buy on expiry day or in the last 3 days;
  - prefer 11-20 days to expiry;
  - use ATM-OTM1 on the near month;
  - natural gas is the least expensive book to hold.
- The cost of a flat hour is about 0.8-1.0% of premium, plus about 0.7% round trip. A directional signal must clear
  roughly 1.5-2% of premium per trade to make money.
- The only lead is "morning OTM call in SILVERM/GOLDM, 4-hour hold". It failed its gates. It needs live spread
  measurement at the open before anyone trades it.

## Files

- `research/hunt/m4/PREREG.md`: the plan and amendments 1-4.
- `research/hunt/m4/build.py`: the entry tables, IV, delta-hedged P&L, exits and signals.
- `research/hunt/m4/analyze.py`: q1, q2, q2next, q3, q3trade, q4design, q4hold.
- `research/hunt/m4/events.py`: spread proxies, event breakouts and gated straddles.
- `research/hunt/m4/results/`:
  - `q1_cells_*`, `q1_summary_*`, `q1_events_*`, `q1_expiry_*`;
  - `q2_convexity_*`, `q2_next_month_crude.csv`;
  - `q3_signals_*`, `q3_trades.csv`;
  - `q4_design_*`, `q4_walkforward_*`, `q4_cellmonths_*`, `q4_frozen_*.json`, `q4_holdout.csv`,
    `q4_holdout_trades.csv`;
  - `events_breakout*.csv`, `straddle_gated.csv`;
  - `spread_estimates.json`, `spread_roll.json`.
- Logs and work files: `scratchpad/hunt/m4/` (`build_*.log`, `q*.log`, `events.log`, `work/*.parquet`).
