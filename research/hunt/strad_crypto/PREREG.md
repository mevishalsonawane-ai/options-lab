# STRAD-CRYPTO pre-registration

Written 8 Oct 2026, **before any P&L was computed**. At the time of writing only these had been done: data downloads
(Deribit perp 5-min candles, funding, DVOL, hourly option-trade IV summaries), and the Delta Exchange India live-chain
spread poll. No straddle has been priced and no return has been looked at.

## What is missing from the brief, and what I use instead

(Update 17:35, still before any P&L: `research/NN_CRYPTO.md` and the code in `research/hunt/nn_crypto/` reappeared
after a container restore, but its data, models and predictions did not. The plan below stands; I only took its
USD/INR rate.)

The brief points to `research/NN_CRYPTO.md`, `research/hunt/nn_crypto/` and `scratchpad/hunt/nn_crypto/` (NN size
model, 2020-2026 1-min BTC/ETH perps, 536k sampled Deribit trades, Delta specs). **None of these exist on this machine**
(searched the repo and the scratchpad). So:

- Data is re-downloaded from **public Deribit APIs** (no keys): BTC-PERPETUAL and ETH-PERPETUAL 5-min candles,
  hourly funding, hourly DVOL, all from 2021-03-20 to today.
- Option prices come from **Deribit's public trade history**: for every hour H, the up-to-1000 most recent option
  trades in [H-60 min, H). Per expiry (<= 10 days) I keep the median IV of near-ATM trades
  (|ln(K/index)| <= 0.5 σ√T) and the median effective half-spread vs Deribit's mark.
- The "NN size model" is **rebuilt here**: a small MLP (sklearn) on the same features as the HAR model plus DVOL,
  funding and event flags. Its out-of-sample rank correlation is reported so it can be compared with the 0.35 claimed.
- Delta India specs from the live public API: contract 0.001 BTC / 0.01 ETH, daily expiries at 12:00 UTC (17:30 IST),
  taker fee 0.01% of notional capped at 3.5% of premium (`premium_commission_rate` 0.035), + 18% GST.

## Instrument and pricing (Black-76 on Deribit trade IV — never direct trade prices)

- Underlyings: BTC and ETH. Decision times: every hour on the hour (UTC).
- Straddle: Delta-style ATM call + put, strike = spot rounded to Delta's grid (BTC 0.25% of spot, ETH 0.8% of spot,
  rounded to a round number). Expiry = the nearest Delta daily expiry (12:00 UTC) with at least h + 2 hours left,
  where h is the horizon (1, 4 or 24 h). So 1 h / 4 h trades use the same-day or next-day expiry; 24 h trades use the
  next-day or day-after.
- IV for that expiry at hour H: total-variance interpolation (in calendar time) between the Deribit expiries that had
  near-ATM trades in [H-1h, H); flat extrapolation outside. If no short-dated trades that hour: DVOL × (trailing 30-day
  median of short-IV / DVOL), computed from earlier hours only. Each trade records which source priced it.
  Deribit expiries are 08:00 UTC, Delta's 12:00 UTC, so Deribit trades cannot price the Delta contract directly.
- Marks along the path every 5 minutes: Black-76 with the perp close as forward and the IV summary of the latest
  completed hour. At expiry: intrinsic value vs the perp at 12:00 UTC.
- Costs: each leg is bought at mid + half-spread and sold at mid − half-spread; no spread at cash settlement.
  Delta spread model, per leg: full spread / spot = a + b × (leg mid / spot), a linear fit to every near-ATM
  (±5%, ≤ 7 days) quote in Delta's public chain, polled every 10 min during this session (first fit: BTC
  a = 0.000066, b = 0.0161; ETH a = 0.000151, b = 0.0178, i.e. ~2.4% / ~2.9% of a 1-day ATM leg; refit on all polls
  at the end). Sensitivity: Deribit effective half-spread from trades vs mark, and 2× the Delta spread.
  Today's Delta book is used for the whole history: Delta India did not list these options in 2021-22, and spreads
  then were likely wider.
- Fees per leg per side: min(0.01% × spot × size, 3.5% × premium) × 1.18 (GST). Charged on entry, on exit, and on
  settlement of an in-the-money leg (conservative).
- Rs per USD = 96.78 (8 Oct 2026, the rate NN_CRYPTO.md used), fixed for the whole history.

## Size forecasts (fit walk-forward by quarter, expanding window, only data whose outcome is known before the quarter)

Target: realised move size RV_h = sqrt(sum of squared 5-min log returns over (t, t+h]), h = 1, 4, 24.
- **HAR**: OLS of log RV_h on log RV over the past 1 h, 4 h, 24 h, 7 d, and hour-of-day dummies.
- **NN**: sklearn MLPRegressor (2 hidden layers 32-16, early stopping, fixed seed) on HAR inputs + log DVOL,
  |funding 8h| z-score, event flags, day-of-week; target log RV_h. Ensembles not used.
- Both are bias-corrected so the training-window mean of exp(forecast) equals the mean RV.
- First fit uses 2021-04-01..2021-12-31; forecasts start 2022-01-01.

## Signals (per asset, per hour, per horizon h)

- **BIG** (size model says bigger than usual): forecast RV_h > 70th percentile of that model's forecasts for the same
  hour-of-day over the previous 30 days.
- **CHEAP**: forecast RV_h ≥ (1 + m) × implied move, implied move = IV(position) × sqrt(h / 8760); m = 0 or 0.2.
- **EVENT**: a US CPI or FOMC release falls inside (t, t+h]. (Jobs reports dropped before any P&L: the jobs dates in
  the old events.csv follow a first-Friday rule and several are wrong, e.g. 2025-07-04, 2025-10-03, 2026-01-02.
  CPI and FOMC dates were checked against the BTC 5-min move at the release minute: median 7-8× a normal bar.)
- **IST**: the horizon (t, t+h] overlaps 19:00–21:00 IST (13:30–15:30 UTC).
- **FUND**: |8h funding| above its 95th percentile of the previous 90 days.

## Rule grid (all run, all counted)

Signal sets (13): ALWAYS; BIG_HAR; BIG_NN; CHEAP_HAR(m=0); CHEAP_HAR(m=0.2); CHEAP_NN(m=0); BIG_HAR+CHEAP_HAR(m=0);
BIG_NN+CHEAP_NN(m=0); EVENT; EVENT+CHEAP_HAR(m=0); IST; IST+CHEAP_HAR(m=0); FUND+CHEAP_HAR(m=0).
"ALWAYS" = buy at every eligible hour (subject to one open position per asset). A fixed-clock "same time every day"
baseline is also run: 13:00 UTC (18:30 IST) daily, horizons as above (counted as a 14th signal set).

Horizons (4): 1 h, 4 h, 24 h (time exit at h; the expiry is always at least h + 2 h away, so it is never sooner), and
X = hold to expiry (the same 26-50 h expiry as the 24 h trade; signals use the 24 h forecast).
Exits (5): time only; +30% / −30%; +30% / −50%; +60% / −30%; +60% / −50% (on the combined straddle value marked at
the bid vs premium paid at the ask; checked every 5 minutes; filled at that 5-min mark).

Total: 14 × 4 × 5 = **280 variants**.

## Position rules and money

- At most one open straddle per asset per rule; signals while a position is open are skipped.
- Capital Rs 1,00,000. Each straddle costs up to 5% of current capital (whole lots, at the ask, incl. fees);
  so at most 10% is tied up (two assets). P&L compounds.
- **Tax reading A (strict VDA):** 30% + 4% cess = 31.2% of each winning trade's net gain, paid when the trade closes;
  losses are ignored (no set-off, no carry-forward). TDS (1% of sale value under 194S) is reported as a cash drag
  only: it is a credit against the same tax, refundable at year end — it is not an extra cost.
- **Tax reading B (business income, non-speculative):** 31.2% of each Indian financial year's net profit (Apr–Mar),
  losses set off within the year and carried forward up to 8 years; paid at FY end. (Top slab assumed; lower slabs
  pay less.)

## Periods and honesty

- Model / rule evaluation period: 2022-01-01 .. 2026-03-31 (pre-holdout).
- **Holdout: 2026-04-01 .. latest data. It is labelled "partly seen"**: the earlier NN study (per the brief) already
  opened Apr–Oct 2026 once. I have not looked at it for this study. It will be run once, at the end.
- **Walk-forward by quarter:** for each test quarter 2023Q1..2026Q1, pick the variant with the highest mean net
  return per trade (after fees + spread, pre-tax, ≥ 40 trades) on all evaluated data before that quarter. If none is
  positive, stand aside that quarter. The concatenated test quarters are the honest pre-holdout result.
- **Final rule** = the best variant by the same criterion on all of 2022-01..2026-03. It alone is run on the holdout.
  The holdout result of every variant is also shown, as descriptive and marked so.
- **Random baseline:** for each variant, 2,000 draws of the same number of trades, per asset, at random eligible hours
  (same horizon, exit, pricing, costs). p = share of draws with mean net return ≥ the rule's. BH (FDR) across all
  280 variants. Also a one-sided bootstrap p of mean net return > 0.
- **Gate to call it "pays":** pre-holdout mean net > 0 after strict-VDA tax, BH q < 0.10 vs random, walk-forward net > 0,
  and holdout net > 0.

## Reported per rule

Trades, hit rate, gross (no fees/spread), net after fees + spread, after tax A, after tax B, Rs/day, max drawdown,
% green months, random p, BH q. Plus the implied-vs-realised gap: implied move vs realised RV by asset, horizon,
year and hour; and straddle price vs payoff at expiry (are short-dated BTC/ETH options cheap or dear?).
