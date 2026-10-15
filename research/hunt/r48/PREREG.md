# R48 pre-registration: high-win-rate NSE index option strategies that also make money

Written 15 Oct 2026, BEFORE any R48 trade or P&L was computed. Committed and pushed on its own. Only data facts were
looked at before this file (sessions per index, inferred expiry days, strike steps, the share of zero-volume rows).

## Why

Boss, 15 Oct: "I don't care about rules now, I want results with a good win rate." Goal: NSE index option strategies
(NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX) with a win rate of 65% or more that ALSO make money after real costs
on unseen data, for a Rs 1,00,000 account. A high win rate that loses money is a trap (small target, wide stop), so
every result is shown with net, average win vs average loss, worst trade, worst day and max drawdown.

Earlier work this builds on: FINDINGS.md "Not yet tested: defined-risk premium selling"; NONDIRECTIONAL.md (naked ATM
short straddle positive in two years, hedged versions untrustworthy under its fill model); SELL_LEVELS.md (100/200-wide
BANKNIFTY spreads after big candles lose, mostly to a ~Rs 140 round trip); MANUAL_METHOD.md (buy, +10% target, no
stop: 70-81% winners but average loss ~4x average win, nets about zero). Family C of the brief (earlier high hit-rate
ideas) is covered by the buying grid below (targets +5/10/20%, no stop allowed), not re-run separately.

## Data and periods

- Drive lake `gdrive:IraAlgo-data-lake`: `options_rolling_1m/opt_<UND>_WEEK_<YEAR>.parquet` (1-minute rows of the
  nearest expiry, ATM-10..ATM+10), `index_1m` (signals), `index_daily` (INDIAVIX, prior closes, EMA).
- Only the nearest-expiry (WEEK) file is used for every index. For BANKNIFTY, FINNIFTY and MIDCPNIFTY it follows the
  monthly contract after Nov 2024 (WEEK and MONTH are then the same contract; MONTH is never added, so nothing is
  counted twice). NIFTY and SENSEX MONTH files are not used: every entry here is 0-4 sessions before expiry, and in
  a monthly-expiry week the monthly contract IS the nearest one.
- **Design:** entries from the first session (Aug 2021; MIDCPNIFTY Jan 2022; SENSEX May 2023) to **17 Oct 2025**.
- **Embargo:** 20-31 Oct 2025 (no entries; design trades entered by 17 Oct exit before 3 Nov).
- **Locked holdout:** entries from **3 Nov 2025** to the last session (13 Oct 2026), about 11.4 months. Read ONCE by
  `r48study.py holdout`, which refuses to run if `results/holdout.json` exists, and only for the at most 10 candidates
  that the rule below picks. Note: 3 Nov 2025 - 13 Apr 2026 lay in the design period of earlier (buying) studies;
  27 Apr 2026 onwards is also the Data Brain's untouched holdout. The holdout is reported whole and split at 27 Apr.
- Expiry days are inferred from the series' own rows (R25 / brain `expiry_infer` method). A session with no known
  next expiry (after the last inferred one) is not traded. DTE = sessions from entry day to the contract's expiry
  (0 = expiry day).
- Contract-switch guard: a multi-day trade is dropped (and counted) if, between two consecutive sessions of its hold,
  the ATM straddle's time value at 15:00 more than doubles overnight without an inferred expiry in between (the
  signature of the rolling series switching contracts, seen for MIDCPNIFTY in 2023-24).

## How every trade is priced (no relaxing)

- **Held contracts.** Every leg is one strike + side of the nearest expiry, looked up in the rows of every offset that
  carried it (never the rolling ATM series, R45). A leg with no row in a minute while its strike is inside the window
  keeps its last close. A leg whose strike has LEFT the ATM+-10 window is bounded: a short leg is valued at
  min(last close, price of the window-edge strike of the same side that minute) (an upper bound of its true price,
  so conservative for buying it back); a long (hedge) leg outside the window is valued at 0 and is not sold (it is
  left to expire; conservative). Entry needs every leg to have a real row at the fill minute; otherwise no trade
  (counted).
- **Next-minute fills.** Every decision is taken on a minute's close; every order fills at the NEXT minute's open of
  that contract (its close if the open is missing; its last close if it has no row). An overnight decision (a
  stop seen on the first minute of a new session) fills at that session's second minute: gaps are paid in full.
- **Slippage per leg, per side** (no historical bid/ask exists): the price is moved against us by 5 bps (the app's
  paper fill) plus n ticks of Rs 0.05, where n = 1 if that contract traded >= 20 lots in the 5 minutes before the
  order, 2 if it traded less, 4 if nothing; +1 tick if the strike is 6 or more steps from the ATM strike (far OTM);
  all ticks DOUBLED on stop exits (credit stop, index stop, buy stop). A sell fills at no less than Rs 0.05. Sensitivity: the finalists are also shown with all ticks
  doubled.
- **Charges:** `research/obuy/costs.py` `Costs("dated")` per order (each leg, each side): brokerage Rs 20, STT on
  every SOLD option premium at the rate in force (0.05% to Mar 2023, 0.0625% to Sep 2024, 0.1% to Mar 2026, 0.15%
  from Apr 2026), exchange (NSE, or BSE for SENSEX), SEBI, stamp on buys, GST.
- **Expiry:** short legs are always bought back by the app's expiry square-off, 15:05 on expiry day (no settlement,
  no assignment; NSE/BSE index options are European and cash-settled, so there is no early assignment). A long hedge
  leg priced Rs 1 or less at that time is left to expire: it settles at intrinsic value from the index's 15:00-15:29
  average, and if it ends in the money STT on exercise (0.125% of intrinsic value; 0.15% from 1 Apr 2026, stated
  assumption) is charged.
- **Lots:** 1 lot per trade at the lot size in force (`research/obuy/config.LOT_SCHEDULE`). The Rs 1 lakh account
  never trades more than 1 lot here.
- **Margin (stated assumption; the lake has no SPAN files).** Per lot: hedged SPAN taken as the spread's maximum loss
  (width x lot; for an iron condor / fly, the wider side only) + exposure margin 2% of the short strike's notional
  (spot x lot), counted once per position (only one side of a condor / fly can lose at expiry) + on an expiry day
  from 20 Nov 2024 an extra 2% of notional (SEBI's expiry-day ELM). Buys: the premium. A trade whose margin is over
  Rs 1,00,000 is skipped (counted); margin is reported per strategy.
- **P&L of a day** is mark-to-market: positions held overnight are valued at that session's last closes (the leg
  rules above, no slippage); realised P&L with all costs lands on the exit day. Worst day, worst week, worst month and
  max drawdown come from this daily series.

## Search space (design data only)

Signal source: every entry filter and direction signal below is computed on the INDEX (index_1m closes,
index_daily), the default. The ATM straddle (option-chain context) only sets the strike distance. Stops and targets
may sit on the spread or premium value. Nothing reads a signal from an option's own chart.

**A. Defined-risk selling** (the hedge leg is always bought; never naked). Entry decision at 09:20, 10:00, 11:00 or
13:00; DTE 0, 1, 2, 3 or 4; every index.
- Structures: BPS (bull put credit spread), BCS (bear call credit spread), IC (BPS + BCS at the same distance and
  width), IB (iron butterfly: short ATM call + put, long wings).
- Short strike: distance m x E from spot, E = the ATM straddle price at the decision minute, m in {0.5, 1.0, 1.5}
  (nearest listed strike at least that far; IB: the ATM strike). Width W in {2, 4, 8} strike steps.
- Exits (checked on every minute's close, filled next minute): profit target p of the credit in {none, 30%, 50%,
  70%} (cost to close <= (1 - p) x credit); credit stop k in {none, 1x, 2x} (cost to close >= (1 + k) x credit);
  index stop {off, on} (index closes beyond a short strike; IB: beyond ATM +- credit); time {same day 15:15, hold to
  expiry 15:05} (on DTE 0 both are 15:05).
- Direction for spreads (index signals at the decision minute): none (BPS or BCS always), or "with" (BPS when up,
  BCS when down) / "fade" (the opposite) on one of: gap = index vs prior session close; ema = prior close vs EMA20 of
  daily closes; twap = index vs its average since 09:15 (VWAP stand-in; the index has no volume); orb = index beyond
  its 09:15-09:29 high / low of closes (entries at 10:00 or later; inside the range: no trade).
- Filters on every selling rule: VIX {all, prior INDIAVIX close <= design median, > design median}; events {all, skip
  when an RBI policy, Union Budget or general-election-result day falls between entry and planned exit} (dates as
  in research/swing_deep.py).

**B. High-hit-rate buying** (index direction, then buy the option): signals gap / ema / twap / orb "with" (as above);
decision 09:20 (gap, ema, twap only), 10:00, 11:00, 13:00; DTE 0-4; strike ATM, 2 or 4 steps in the money; target
+5%, +10% or +20% of premium; stop none, -10%, -20% or -33% of premium; time exit 30 min, 60 min or 15:15 (15:05 on
expiry). Same fills, costs and filters (VIX / events) as A.

Every combination is run; the count of everything tried is reported and goes in the experiment entries.

## Candidate rule (fixed now, applied to design only)

Eligible if, on design: 100+ trades; win rate >= 65% (a win = net P&L after all costs > 0); net > 0; mean net per
trade > 0 in at least 75% of the calendar years with 10+ trades; net > 0 in the last design year (18 Oct 2024 -
17 Oct 2025) with at least 43 trades there (so 40+ holdout trades are expected; this rules out the monthly-only
BANKNIFTY / FINNIFTY / MIDCPNIFTY entries); worst day >= -Rs 13,000; max drawdown >= -Rs 40,000; net minus the top 5
trades > 0.
Rank by the t-statistic of the mean net per trade. Walk down the ranking and take a candidate unless an
already-taken one has the same index and entry (structure, distance, width, DTE, decision time, direction rule), or
two already-taken ones share its index and family (credit spread / iron condor / iron fly / buy). Stop at 10.
If fewer than 10 are eligible, take all; if none is eligible, the holdout is NOT read and the closest misses are
reported.

## Holdout gates (each candidate, read once)

win rate >= 60%; net > 0 after costs; 40+ trades; worst day >= -Rs 13,000; max drawdown >= -Rs 40,000; net minus top
5 trades > 0; p_twin < 0.05 and Benjamini-Hochberg q <= 0.10 across the candidates read.

**Random-entry twins** (500 per candidate): each holdout trade is replaced by one on a random holdout session with the
same DTE (same index), a random decision minute between 09:20 and 14:30, the same structure, strike rule and exits,
no filters, and for direction-based rules a coin-flip side. p_twin = (1 + twins with net >= the candidate's net) /
501. This asks whether the rule beats selling (or buying) the same thing at random times in the same market. A
bootstrap p of mean net per trade <= 0 (10,000 resamples) is shown too (not a gate).

## Also reported

- Walk-forward of the SELECTION (report only): for each test year 2023, 2024 and 1 Jan - 17 Oct 2025, the same rule
  (100+ trades lowered to 60, last-year check on the year before the test year) applied to design data before that
  year, top 10 taken, their pooled result in the test year.
- Tail risk: worst trade, day, week, month; the P&L on the biggest gap days in the data (24 Feb 2022, 4 Jun 2024,
  7 Apr 2025 and the holdout's largest index move); March 2020 is not in the option data, so the defined-risk bound
  (width x lot - credit + costs) is stated as the most one position can lose.
- Finalists with doubled ticks; holdout split at 27 Apr 2026; margin per lot.

## Safety

Research only. Selling options is a new kind of trade for the app: any paper arm for a passing rule needs Boss's yes
and app work (multi-leg orders, margin checks); live needs Boss's PIN and "AI trades go live". Nothing here changes
the app.

## Amendment 1 (15 Oct 2026, still before any P&L)

1. **Legs that leave the window on the IN-the-money side** (a big move through both strikes). The rule above bounds
   only out-of-the-money legs. Added: a short leg beyond the window on the in-the-money side is valued at its intrinsic
   value plus the time value of the nearest window-edge strike of the same side (an upper bound, since time value
   shrinks deeper in the money); a long leg there is valued at its intrinsic value (a lower bound) and sold at it
   (with the far-OTM slippage). The out-of-the-money rules stay as written.
2. **Contract-switch guard, made unbiased.** As written it would also drop real crash days (time value doubles when
   the index gaps). It now drops a multi-day trade only when the 15:00 ATM time value more than doubles overnight
   with no inferred expiry in between AND the index moved less than 1.5% close to close. Data fact behind it (no
   P&L): this flags 3 FINNIFTY sessions (12 Jan, 6 Jul, 2 Nov 2022) and 1 SENSEX session (8 Jan 2024); NIFTY,
   BANKNIFTY and MIDCPNIFTY have none.
