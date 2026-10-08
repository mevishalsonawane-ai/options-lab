# M2 pre-registration: MCX trend following + carry, Rs 1 lakh, fixed lots (Zerodha)

Written 2026-10-08 (UTC ~19:00) BEFORE any P&L was computed. Only data-quality checks were run before this
(row counts, outliers, OI jumps for roll detection, Zerodha margin table). Any later change is logged at the
bottom under "Deviations" with the reason.

## Periods
- Signal warm-up: 2012 (Dhan MCX daily starts 2 Jan 2012). Development: 2013-01-01 .. 2024-12-31.
- Walk-forward: anchored, by calendar year, test years 2014..2024 (train = 2013..Y-1).
- LOCKED HOLDOUT: 2025-01-01 .. latest (7 Oct 2026). Run ONCE, at the end, by `holdout.py`, which writes a lock
  file and refuses a second run. Nothing is chosen on it.
- Long proxy check (Yahoo CL, BZ, NG, GC, SI, HG front futures x USDINR): 2001..2024, holdout 2025+ also once.

## Data
- Dhan `/charts/historical` MCX_COMM FUTCOM expiryCode 0 = continuous near-month daily OHLC, volume, OI.
  expiryCode 1 and 2 return the SAME series (checked), so no next-contract series exists -> carry is estimated
  from the roll-day jump (below), and labelled approximate.
- Price series used for each tradable mini contract (same price unit, different lot):
  CRUDEOILM <- CRUDEOIL series (Rs/bbl, 10 bbl); NATGASMINI <- NATURALGAS (Rs/mmBtu, 250);
  GOLDPETAL <- GOLD/10 (Rs/g, 1 g); SILVERMIC <- SILVER (Rs/kg, 1 kg);
  ZINCMINI / LEADMINI / ALUMINI <- ZINC / LEAD / ALUMINIUM (Rs/kg, 1000 kg).
  COPPER (2500 kg, margin ~Rs 3 lakh), GOLDM futures (margin ~Rs 1.57 lakh), SILVERM futures (Rs 2.7 lakh) do
  not fit Rs 1 lakh and are excluded from the futures book. Current contract sizes are applied to all years.
- 20 Apr 2020: Dhan shows CRUDEOIL close 1.0; MCX settled the April contract at Rs -2,884. We use -2,884
  (P&L in rupee points, never log returns), so the negative-price loss is in the test.
- Roll days: the day the continuous series switches contract, found as the largest OI jump (ratio > 1.5) inside
  each 15-trading-day window. On a roll day the overnight gap (which contains the contract jump) is NOT earned:
  that day's P&L = close - open of the new contract. Each roll costs one round trip (charges + spread).
  Delivery-based contracts (bullion, base metals) must in reality be rolled ~5 days before expiry; the near
  contract is liquid then, so the date shift is ignored.

## Signals (computed on close of day t, traded at OPEN of day t+1; position +1 / -1 / 0)
1. TSM21, TSM63, TSM126, TSM252: sign of the N-day price change, evaluated on the last trading day of each
   month only (classic monthly TSMOM), held for the month.
2. DC20, DC50, DC100: Donchian. Go long when close > highest close of the previous N days, short when close <
   lowest close of previous N days, otherwise keep the position (always in after the first breakout).
3. MA20_100, MA50_200: +1 if SMA(fast) > SMA(slow), else -1.
4. CARRY: +1 if estimated annualised roll yield (mean of last 6 roll-day jumps, sign flipped: backwardation =
   positive) > 0, else -1. Evaluated monthly. Approximate (see Data).
5. ENS: sign of the average of the 9 trend signals (1-3); 0 if the average is exactly 0.
=> 11 signals.

## Implementations
- (a) LS: futures long AND short. Short futures = selling risk with unlimited loss (Boss must choose).
- (b) LO: futures long only (short signal -> flat).
- (c) OPT: option buying. On the signal (+1 call, -1 put) buy 1 lot of the 1-strike-ITM option of the next
  monthly expiry that has >= 20 days left; roll when <= 7 days left or when the signal flips; exit when the signal
  is 0 / flips. Contracts: CRUDEOIL (100 bbl), NATGASMINI (250), GOLDM (100 g), SILVERM (5 kg). Before Aug 2025
  prices are MODELLED with Black-76: IV = 20-day realised vol x ratio (ratio calibrated per commodity from Dhan
  rolling-option IV vs realised vol, Aug 2025+; sensitivity x0.85 / x1.25), round-trip spread 0.5% of premium
  (crude) / 1.5% (others), plus Zerodha option charges. Label: "modelled". Holdout uses real Dhan option prices
  where Dhan has them (Aug 2025+), modelled before.

## Universes
Single commodity: CRUDE, NATGAS, GOLD, SILVER, ZINC, LEAD, ALUMINIUM (7) + PORTFOLIO (all that fit) = 8.
For OPT only CRUDE, NATGAS, GOLD, SILVER + PORTFOLIO_OPT.

## Sizing (fixed lots, Rs 1,00,000 start, no compounding of lot counts)
- Futures: 1 lot each of CRUDEOILM, NATGASMINI, SILVERMIC, ZINCMINI, LEADMINI, ALUMINI; GOLDPETAL 5 lots.
  (Volatility scaling with fixed lots = this one-time choice of lot counts so each leg's typical daily rupee risk
  is roughly Rs 500-2,500; plus a skip rule: do not OPEN a leg whose 20-day daily rupee std per lot x lots >
  4% of current equity.)
- Margin model: margin% = max(floor, 0.45 x 60-day annualised realised vol), floors: crude 6%, natgas 8%,
  bullion 4%, base metals 5%; checked against Zerodha's 8 Oct 2026 numbers. Daily check: a new leg is opened
  only if total margin after opening <= equity (free cash). If margin > equity on any close, the leg with the
  largest margin is closed at the next open (margin call).
- Options: 1 lot per signal; open only if premium <= free cash (equity - premium already tied up).
- Ruin rule: equity < Rs 25,000 -> stop.

## Costs (Zerodha, verified 8 Oct 2026)
Futures: brokerage min(0.03%, Rs 20) per order; CTT 0.01% on sell; MCX txn 0.0021%; SEBI Rs 10/cr; stamp
0.002% on buy; GST 18% on brokerage+txn+SEBI. Options: Rs 20/order; CTT 0.05% sell premium; MCX 0.0418% of
premium; stamp 0.003% buy; SEBI; GST. Spread per round trip (Rs/lot): CRUDEOILM 20, NATGASMINI 50, GOLDPETAL 3,
SILVERMIC 15, ZINC/LEAD/ALUMINI mini 100; plus open slippage 0.02% of notional per side. Same at each roll.

## Metrics
CAGR on Rs 1 lakh, Rs/month, max drawdown (Rs and %), worst month, % green months, Sharpe (daily, annualised),
trades/year, average margin / premium used, lot counts possible.

## Inference
- Random-signal baseline: for each variant, 300 random position paths that keep the same run lengths (holding
  periods and flat periods) in random order, with random direction for LS/OPT (LO keeps long/flat). Same costs.
  Report the percentile of the real Sharpe.
- Multiple testing: all 11 x 3 x 8 (OPT: x5) variants counted. Hansen SPA / White Reality Check (stationary
  bootstrap, 1000 draws, mean block 20 days) on daily net P&L vs zero over development; Benjamini-Hochberg on
  per-variant p-values (t-stat of daily mean, Newey-West).
- Walk-forward selection: each test year picks the variant with best development Sharpe up to the prior year,
  among (i) all variants and (ii) PORTFOLIO variants only.
- PRIMARY (decided now, before results): PORTFOLIO x ENS x {LS, LO, OPT}. Secondary: the walk-forward picks.
  Holdout runs these, once.

## Deviations
(none yet)
- D1 (before any P&L, 19:20 UTC): margin model recalibrated against Zerodha's 8 Oct 2026 table because 0.45 x vol
  under-stated it (crude 23% vs 35%, silver 12% vs 21%, lead/alu 5% vs 7-11%). New: margin% = max(floor, 0.60 x
  60-day vol); floors crude 8%, natgas 10%, bullion 6%, base metals 7%. Still under Zerodha for silver/crude now
  (16%/31% vs 21%/35%), so margin results are mildly optimistic.
- D2 (clarification): all signals are computed on a roll-adjusted price line (cumulative earned points, roll-day
  gaps removed), so contract switches do not create fake breakouts.
- D3 (after the first dev run, because of obvious fake P&L): roll detection fixed. Base metals: the first trading
  day of each month is also a roll day (they expire on the last business day). CRUDE 21 Apr 2020 is a roll day
  (the April contract expired at -2,884 on 20 Apr; without this a short showed a fake Rs 42k loss). Overnight gaps
  more than 8 rolling standard deviations that are not roll days are treated as data errors (gap not earned).
- D4 (after the first dev run): the pre-registered 7-leg PORT (1 lot of every mini) ran into the Rs 25k ruin
  stop in 19 of 22 variants, mostly from the 1,000-kg base-metal minis. Added a smaller book PORT4 = CRUDEOILM +
  NATGASMINI + GOLDPETAL x5 + SILVERMIC. PORT4 is chosen after seeing PORT fail, so it is a secondary, labelled
  post-hoc universe; it is still judged by BH/SPA with all variants counted.
- D5: IV/RV ratios frozen at the first calibration (Dhan near-month ATM IV vs 20-day realised vol, Aug 2025 - Oct
  2026): crude 1.065, gold 0.961, silver 1.005; natgas 1.05 (placeholder; its Dhan option history had not
  downloaded yet). Pricing parameter only.
- D6 (after the first option dev run, on LIQUIDITY grounds from M1's order-book check, not P&L): next-month MCX
  option books are dead (17-97% spreads). Rule changed to near-month: buy the 1-ITM option of the nearest expiry
  with >= 8 days left; roll/exit when <= 6 days are left. The first run (next-month, >= 20 days, roll at 7) is
  kept in dev_results_opt_nextmonth.csv and both are counted in the variant total.
