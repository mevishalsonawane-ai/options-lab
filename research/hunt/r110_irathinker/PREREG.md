# R110: IraThinker's strategies on every index (pre-registration)

*Written 26 Oct 2026, before any result was computed. Boss, 26 Oct: "test each and every strategy for all the index and
provide me the results from IraThinker".*

## What is tested

The signals come from **IraThinker's own Java code** (`irathinker-app/`, v59 plus the 26 Oct review fixes), run on
historical bars through `R110Runner.java`. Nothing about a rule is re-implemented in Python. Python only prices the trades
the Java code names.

| ID | Strategy (IraThinker's form) | Signal source | Engine call |
|---|---|---|---|
| S1 | **ORB alert**: alert-grade ORB plans only (lite IraScore > 65, 10-point floor, narrow range, breakdowns only, India VIX already broken up). Entry at the open of the minute after the 5-minute break bar; index stop at the range midpoint; exit at the 2R target (the alert's "target 1, book"). | Underlying index | `Analysis.signalHistory(5m, sym, vix5m)`, rows `kind=ORB, alertGrade` |
| S1R | the same alert at the **3R runner** target | Underlying | same rows, `alertTarget3` |
| S1A | **ORB, every plan** (both sides, no gates), at 2R: the raw family the gates select from | Underlying | same, all ORB rows |
| S2 | **Gap fade**: gap of 1% or more, filling by 09:45; 1R target; index stop and target as IraThinker plans them | Underlying | `signalHistory` rows `kind=FADE` |
| S3 | **mom60**: \|60-minute return\| at or above the index's threshold on a closed 1-minute bar; buy the ATM option in the move's direction; hold 60 minutes; exit early if the premium is 30% under the entry | Underlying; stop on the premium | `Mom60.Watcher.process`, bar by bar |
| S4 | **BANKNIFTY short strangle** (paper track): Monday 09:30 bar, sell CE ATM+200 and PE ATM−200 (monthly), hold to Thursday's close, or settle at expiry in an expiry week. **Option selling.** | Underlying sets the strikes | `Strangle.buildEntry` |
| S5 | **Prediction engine 60-minute vote** (display only in the app): at 10:30 each day, if the vote is UP or DOWN, buy the ATM option that way and hold 60 minutes | Underlying | `Predict.forecast(5m)` |

### Not tested as trade rules, and why

- **SMC, Market Meter / BullBear (the GOC-style gauge) and Time Machine** are displays in the app, not trade rules. They
  name no entry, exit or side.
- The Meter also needs the live option-chain OI (all strikes) and market breadth, which this data does not hold.
- **Price alerts** are Boss's own levels.
- **Market flush** is a breadth message with no trade.

## Indices and data

The research-data release (`research-data` tag), downloaded 26 Oct 2026:

| Index | Index 1-minute | Real option 1-minute (nearest expiry, 20 strikes about ATM) |
|---|---|---|
| NIFTY | 2024-04-01 to 2026-04-13 | yes, same period |
| BANKNIFTY | 2024-02-13 to 2026-02-23 | yes, same period (strikes within about 1,000 points) |
| FINNIFTY | 2024-02-12 to 2026-02-24 | **no** |
| SENSEX | 2024-02-12 to 2026-02-24 | **no** |
| INDIA VIX | 2024-02-01 to 2026-02-27 (for S1's gate) | n/a |

There is no MIDCPNIFTY data, so it is not tested.

mom60 thresholds:
- BANKNIFTY 0.50%, FINNIFTY 0.50% and SENSEX 0.47% are IraThinker's frozen values.
- NIFTY has no mom60 track in IraThinker. Its threshold is fixed here as the 90th percentile of \|ret60\| over NIFTY's
  design period: the same q90 recipe IraThinker used. It is computed once by the script, before any trade is priced.
- For S4 the strangle is BANKNIFTY-only in IraThinker, so S4 is BANKNIFTY only.

## Pricing (the result that counts)

- **NIFTY and BANKNIFTY, real options:**
  - The traded contract is the file's nearest expiry, at the strike in the file closest to the strike IraThinker names.
  - Fills are at the next minute's open with `obuy.costs` 'liq' slippage (5 bps plus 1–4 ticks by 5-minute volume; stops
    2x).
  - Charges are `Costs("dated")` (STT, exchange, SEBI, stamp, GST, Rs 20 an order).
  - Size is one lot at the lot in force (`obuy.config.LOT_SCHEDULE`).
  - Exits are on the index's stop or target crossing (S1, S2), on the minute's close for the premium stop or time (S3, S5),
    or at Thursday's close or expiry intrinsic (S4).
  - Square-off 15:15 (IraAlgo's ntfy arm), 15:05 on an expiry day.
- mom60 and the strangle trade the **monthly** contract in IraThinker. The real data holds only the nearest expiry, so a
  trade is priced on real options only when the nearest expiry IS that index's monthly contract. Otherwise it is counted
  as "no real price". Before Dec 2024, BANKNIFTY's nearest expiry was a weekly.
- **FINNIFTY and SENSEX: index points × lot**, before option costs. This is descriptive only and can never pass the
  gates.
- Also reported: IraThinker's own Black-Scholes paper tape (what its paper log would show) for S3 and S4, so the gap
  between its tape and real prices is visible.

## Split and gates

- **Design:** every day up to 2025-09-30. **Holdout:** 2025-10-01 to the end of the data, read once, only for tests that
  pass design.
- A test passes design when all of these hold:
  1. at least 40 trades;
  2. mean net per trade > 0 with a day-block bootstrap p < 0.05 (10,000 draws, seed 107);
  3. Benjamini–Hochberg q ≤ 0.10 across every real-price test in this study;
  4. net > 0 at 2x slippage;
  5. net > 0 without the 5 best days;
  6. no month worse than −Rs 13,000 (Boss's monthly stop rule).
- Holdout pass: mean net > 0 on the holdout's trades (at least 10).
- No parameter of any IraThinker rule is changed. Every test is logged in `research/experiments/entries/`, failures
  included.

## Amendment 1 (26 Oct 2026, after the first run)

- **What went wrong:** the first run's monthly-contract check ("no later listed expiry in the same month") read
  holiday-moved weeklies as monthlies. Examples: BANKNIFTY 30 Apr 2024 and NIFTY 30 Apr 2025, both weeklies moved back
  from a 1 May holiday. So S3 and S4 priced some weekly contracts.
- **New check:**
  - BANKNIFTY counts as the monthly only from 14 Nov 2024, after its weeklies ended.
  - NIFTY counts as the monthly when the expiry falls within 3 days before the month's last Thursday (to Aug 2025) or last
    Tuesday (from Sep 2025).
- This corrects which contract is priced. No rule, threshold or gate changed. The first run's numbers are superseded and
  not reported.
- **Values computed by the script before pricing:** NIFTY's mom60 threshold is 0.38%. That is the design-period q90 of
  |ret60|, 0.3795%, rounded to two decimals as IraThinker does (117,173 minutes).
- **Data limit found while running:** INDIA VIX ends 27 Feb 2026. NIFTY days after that cannot be alert-grade for S1,
  because IraThinker's gate needs VIX.
