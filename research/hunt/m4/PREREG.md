# M4 pre-registration: MCX options for a BUYER (Zerodha, Rs 1 lakh, fixed lots)

Written 8 Oct 2026, 18:40 UTC, BEFORE any P&L or any implied-vs-realised number was computed by M4.
Only data inspected so far: file formats, row counts, contract lots (Kite instrument master), Zerodha charges page.

## Data

- MCX near-month option minutes from Dhan `rollingoption` (expiryCode 1), strikes ATM-3..ATM+3, call and put, with
  IV, OI, volume, strike and the near-futures price (`spot`).
  - CRUDEOIL: `scratchpad/hunt/strad_crude/cache/` (already fetched; also next month ATM, expiryCode 2).
  - NATURALGAS / NATGASMINI / GOLD / GOLDM / SILVER / SILVERM / COPPER: M3's `scratchpad/hunt/m3/raw/opt_<SYM>.parquet`
    and `spot_<SYM>.parquet`, read only. Whatever M3 has finished by the time I run is used; missing ones are reported
    as missing. If both a big and a mini contract exist, the big one is the research series (same underlying price);
    the mini is used for sizing where the big lot costs more than Rs 1 lakh.
- Period: from 5 Aug 2025 (first available) to 8 Oct 2026.
- **Design = trading days up to 7 Jul 2026. Holdout = 8 Jul - 8 Oct 2026 (last 3 months), opened once, at the end.**
  Note: crude Apr-Oct 2026 was already seen by NN-CRUDE and STRAD-CRUDE; for crude the holdout is "partly seen".
- Spread: live Dhan chain snapshots where they exist (crude: 204 snapshots 8 Oct, by strike offset). For commodities
  without measured spread, assumed relative spread per side-to-side: NATURALGAS 0.6%, GOLD/GOLDM/SILVER/SILVERM 1.0%,
  COPPER 1.5%, minis x1.5. Before 17:00 IST all spreads x2 (thinner morning). Stress: x2 everywhere.
  If M3 or I log live snapshots for these later, measured values replace the assumptions (amendment to be noted).
- Costs (Zerodha MCX options, verified on zerodha.com/charges 8 Oct 2026): Rs 20 per executed order; CTT 0.05% of
  sell premium; MCX transaction 0.0418% of premium; SEBI Rs 10/crore; GST 18% on brokerage+transaction+SEBI; stamp
  0.003% of buy premium. Plus half the relative spread paid on entry and on exit.
- Multipliers (units per lot): CRUDEOIL 100, CRUDEOILM 10, NATURALGAS 1250, NATGASMINI 250, GOLD 100 (price per 10 g,
  1 kg), GOLDM 10 (100 g), SILVER 30 (price per kg), SILVERM 5, COPPER 2500.

## Definitions

- Grid: every 15 minutes from 09:15 to 23:10 IST. Expiry day is its own DTE bucket (reported, not mixed in).
- Entry option: each of the 14 series (offset -3..+3, call/put) at the grid minute. A held contract is followed by its
  fixed strike across the rolling series; a missing minute is priced with Black-76 at its last IV (share reported).
- Moneyness: offset in "ITM steps" = -k for calls, +k for puts (positive = ITM). Buckets: ITM2-3, ITM1, ATM, OTM1, OTM2-3.
- DTE buckets (business days left after today): 0 (expiry day), 1-3, 4-10, 11-20, 21+.
- Time-of-day buckets (entry): morning 09:15-13:59, afternoon 14:00-17:29, evening 17:30-23:25.
- Events (in the holding window, or on the day): EIA (crude, Wed 10:30 ET with official exceptions), EIA natural gas
  storage (Thu 10:30 ET; holiday shifts ignored), US CPI and FOMC days (h28 calendar), FOMC next day, OPEC Monday
  (approximate, as STRAD-CRUDE). "None" = no event that day.
- Horizons H: 15 min, 60 min, 240 min (all capped at the session end), and EOD (exit 23:25 or the last bar).

## Q1. Cheap or dear (descriptive; same tables in design and holdout)

Per entry option and horizon:
1. **VR (variance ratio)** = sum of realised squared 1-min futures log returns over (t, t+H] divided by the implied
   variance for the same minutes, IV(option)^2 * tau / N_rem (trading-minute convention, as STRAD-CRUDE). Reported as
   sqrt(sum realised / sum implied) per bucket. Direction-free.
2. **DH (delta-hedged buyer P&L)**: long 1 option, short Black-76 delta in futures, re-hedged every 15 minutes, no
   costs, divided by entry premium. Mean per bucket, t-stat clustered by day. DH > 0 = option was underpriced vs the
   realised path (includes strike skew because each option uses its own IV).
3. **UG (unhedged gross return)**: option exit/entry - 1, what a plain buyer gets before costs. And **UN** = net of
   costs and spread at 1 lot.
4. **To expiry**: from the 15:00 grid each non-expiry day, each offset held to the last bar of expiry day; intrinsic
   payoff using the last futures price / entry premium.

**"Systematically underpriced" bucket (pre-registered gate):** commodity x moneyness x DTE x time-of-day x event cell,
horizon 60/240/EOD, with at least 300 entries on at least 20 days in design, and
(a) mean DH > 0 with BH q < 0.10 across all cells tested (day-clustered t),
(b) DH > 0 in at least 2/3 of design months with data,
(c) mean UN > 0 (net of costs and spread).
The number of cells tested is counted and reported.

## Q2. Convexity per rupee for a directional buyer (descriptive, no choice is made)

Per commodity x moneyness x expiry (near; next month only for crude ATM), at evening grid times in design:
median premium per lot, |delta|, elasticity (delta*F/premium), Rs gain for a 1% favourable futures move per Rs 1,000
of premium (Black-76 repricing, same IV), theta per trading hour (Rs/lot and % of premium; Black-76 calendar theta
converted to trading hours), spread %, round-trip cost % of premium. Empirical: median 60-min option return when the
futures moved >= +1 sigma (60-min, trailing) in the option's favour, and when |move| < 0.25 sigma (the cost of waiting).
"Best convexity per rupee" = highest median return per rupee given the favourable move, net of round-trip cost.

## Q3. Do skew / IV / OI changes predict the futures? (h29-style)

Signals at each 15-min grid (near month): S1 risk reversal rr = IV(ATM+2 call) - IV(ATM-2 put); S2 15-min change in rr;
S3 15-min change in ATM IV (mean of ATM call and put); S4 5-min change in synthetic-forward basis
(K_atm + C - P) / F - 1; S5 term structure next-month ATM IV - near ATM IV (crude only); S6 60-min change in
(put OI - call OI) over ATM+-3, divided by total OI; S7 60-min change in total OI x sign of the 60-min futures return.
Targets: futures log return over the next 15 and 60 minutes. Statistic: pooled Spearman IC per commodity; t from
daily-mean IC (Newey-West not needed: days are independent blocks). BH across all signal x horizon x commodity tests.
Gate: BH q < 0.05 in design AND same sign with |t| > 2 in the holdout. A passing signal is then tested as a buy
(ATM option in the signal direction, 60-min time exit, net), design then holdout once.

## Q4. Direction-free buy test

- Candidates: the cells passing the Q1 gate. If none pass, the test is still run on the single **least-dear** cell
  (highest design UN at 60/240/EOD with >= 300 entries, 20 days), labelled "least dear, not cheap".
- Trade: buy 1 lot of that cell's option at every grid time the cell condition holds, one position at a time per
  commodity, skip if premium > Rs 1 lakh (use the mini contract where it fits). Exits, fixed: (E1) time exit at the
  cell's H; (E2) the same with stop -30% and target +50% of premium on 1-min closes. 2 exit variants per cell.
- Random baseline: same number of trades at random grid times (same commodity, same option offset/side rule, same
  exits, same months), 2,000 draws; p = share of draws with net >= the rule's net.
- Walk-forward by month (design): anchored; at each month pick the candidate cell/exit with the best net on earlier
  months only; trade the month.
- Holdout (8 Jul - 8 Oct 2026): the cell/exit chosen on all design data, run once. Report gross and net per trade,
  per day, by month, max drawdown, worst day, random p.
- Verdict YES only if: holdout net > 0, random p < 0.05, walk-forward net > 0, and >= 60% green months.

## Count

Every cell, signal and variant tried is counted in the report. No parameter is tuned on the holdout.

## Amendment 1 (8 Oct 2026, ~18:50 UTC, before any P&L or ratio was computed)

- Moneyness cells keep call and put apart (e.g. "OTM1 put" and "OTM1 call" are separate cells): skew makes them
  different instruments. Main grid per commodity = 10 (moneyness x side) x 5 DTE x 3 time-of-day x 3 horizons
  (60/240/EOD) = 450 cells. Event cells = commodity x event x 10 x 3 horizons, all days pooled across DTE/time.
- Expiry dates are inferred from the data (Dhan-IV tau gives a rough date; the ATM straddle jump at the contract
  switch fixes the day). For crude this reproduces STRAD-CRUDE's 15 expiries exactly. Last expiry from the Kite master.
- IV is my own Black-76 inversion of each option's close with calendar tau to 23:30 on expiry day (not Dhan's IV).
- S6/S7 (OI) use the same fixed strikes (ATM-3..ATM+3 at time t) at t and t-60, forward-filled OI.
- Q2 theta per trading hour = Black-76 price at tau minus price at tau*(1 - 60/N_rem) (the market's decay per trading
  hour under the trading-minute convention), same IV and F.
- Q4 walk-forward picks, each month, among ALL eligible cells (>= 300 entries on >= 20 days in earlier months) x 2
  exits, the one with the best mean net Rs per trade on earlier months. (With zero passing cells, a single-cell
  walk-forward would be empty, so the selection is made over all cells. This is stricter, not looser.)
- Q4 lot rule: the big contract if one lot's premium <= Rs 1 lakh, else the mini (same price, smaller multiplier,
  same Rs 20/order), else skip.

## Amendment 2 (8 Oct 2026 ~18:55 UTC; after crude Q1/Q3 descriptive tables, before any Q3 trade P&L)

- Q3 trade rule for a signal passing the gate (any commodity): at each 15-min grid, if the signal is above its design
  80th percentile or below its 20th, buy 1 lot of the ATM option in the direction implied by the sign of its design
  daily IC; 60-min time exit; one position at a time; same costs. Also reported: the same with the past-60-min return
  as the signal (to check the OI signal is not just a proxy for short-term reversal). Random baseline as in Q4.
- Observed on crude (design): the next-month ATM option has zero volume in 80% of minutes and its close is
  unchanged 78% of minutes. Next month is reported as illiquid; it is not tested as a trade.

## Amendment 3 (8 Oct 2026 ~19:05 UTC; coordinator focus; before any NATURALGAS/GOLDM/SILVERM number was computed)

- Books: CRUDEOIL, NATURALGAS, GOLDM, SILVERM near month (coordinator / M1 guide). GOLD, SILVER, COPPER, minis: not
  analysed (big-lot premiums Rs 1.6-2.7 lakh; minis ~2% round trip). Lot rule in Q4 therefore uses the book's own lot.
- Spreads for NATURALGAS / GOLDM / SILVERM are not measured live. Estimate from minute data: median of
  (high - low) / close of 1-min ATM bars with volume > 0, 17:30-23:00, design period; scale by crude's measured
  0.29% ATM spread / crude's same proxy. The per-offset shape is taken from crude. These replace the assumed numbers
  in the original plan. Morning (before 17:00) x2 as before; stress x2 everywhere.
- Extra event tests (M1 ideas #6 and #7), design then holdout once:
  - #6 NATURALGAS, EIA gas storage (Thu 10:30 ET). #7 GOLDM and SILVERM, US CPI (08:30 ET, h28 calendar). Payrolls
    are left out: the 2025-26 release dates were moved by the shutdown and I have no verified list.
  - Rule: at release time T, futures move m = log(F[T+e]/F[T-1]), e in {5, 15} min. If |m| > k x median |m| of the
    same clock window on the previous 20 non-event days, k in {1, 2}, buy the ATM option in the direction of m at the
    T+e close; time exit after x in {15, 60} min. 8 variants per book, 24 in total. Random baseline: same entry clock
    and exit on non-event days, same direction rule without the threshold.
- #9 volatility-gated straddle on NATURALGAS and SILVERM: buy ATM call + put when trailing 60-min realised vol /
  ATM implied (per minute) is at or above its design 80th percentile; 60-min time exit. 1 variant per book.
- Spread note (19:10 UTC, before any non-crude P&L): the high-low proxy also moves with volatility, so I also ran a
  Roll (1984) estimator on delta-adjusted 1-min ATM price changes, scaled the same way to crude's 0.29%. The larger of
  the two estimates is used (more cautious): see results/spread_estimates.json.

## Amendment 4 (8 Oct 2026 ~19:35 UTC; data cleaning after seeing broken prints in GOLDM/SILVERM; before any Q4 run on them)

- GOLDM and SILVERM options have no trade in 42-47% of minutes, and the Dhan rows repeat the last price (e.g. one
  GOLDM put printed Rs 289 for hours while the future moved; implied vol 1%). Fix, applied to all four books:
  a minute with volume 0 has no price (forward-fill up to 5 min, then Black-76 at the last IV, as before);
  an entry needs a traded price at the entry minute AND its implied vol within 0.5x-2x the median IV of the 14
  options at that minute; entries with a lot premium below Rs 500 are dropped.
