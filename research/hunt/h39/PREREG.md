# h39 pre-registration: do heavyweight STOCK OPTIONS lead the index enough to pay an index option buyer?

Written 2026-10-08 before any data was fetched and before any P&L. Option BUYING only. Locked holdout = 2025-10-01 .. latest.

## 0. What happened at the data step (recorded before analysis)
- The Dhan token in `scratchpad/secrets/dhan.env` has JWT exp 2026-10-07 04:38 UTC. Now is 2026-10-08 04:50 UTC.
- One request each to `POST /v2/charts/rollingoption` (RELIANCE OPTSTK MONTH ATM CALL, and NIFTY OPTIDX WEEK as a
  control), 2025-09-01..03: both **HTTP 401 DH-901 "Client ID or user generated access token is invalid or expired."**
- So NO stock-option history could be fetched. Whether the endpoint serves OPTSTK at all is not settled by this call
  (it fails on auth before that). The earlier fetcher notes (fetch.py, 2026-10-06, valid token) record that RELIANCE
  OPTSTK ATM+3 returned data and ATM+5/+10 were empty, i.e. stocks are served at ATM±3.
- No stock-option history exists anywhere in the scratchpad (only one option-chain snapshot of 2026-10-06 11:03).

## 1. Stage A (runs now): proxy diagnostic on what exists — NO P&L, NO rule selection
Data: nearest stock FUTURES (minute, with OI) of the 11 heavyweights + their NSE_EQ minute candles + NIFTY/BANKNIFTY
index minutes + index nearest futures. Only one contract per name exists: 2026-07-29 .. 2026-10-06 (≈48 sessions),
entirely INSIDE the locked holdout. Therefore Stage A is descriptive only; nothing is chosen from it and no rule is
traded on it. It is the closest available stand-in for two of the requested option features
(synthetic forward vs stock price → futures basis; OI build-up per stock → futures OI).

Features per stock (minute closes), aggregated with h26's static weights (W_BN for BANKNIFTY over HDFCBANK, ICICIBANK,
SBIN, KOTAKBANK, AXISBANK; W_NF for NIFTY over HDFCBANK, ICICIBANK, RELIANCE, INFY, BHARTIARTL, LT, ITC, TCS, SBIN,
AXISBANK), weights renormalised to the names present:
- `FRET1`: futures 1-min return. `SRET1`: stock 1-min return (h26 control).
- `dBASIS1`, `dBASIS5`: change in futures/spot - 1 over 1 and 5 minutes (bps).
- `BU5`: sign(futures 5-min change) x max(OI 5-min % change, 0) (build-up only, h26 BU style).
- `FVI5`: futures 5-min signed volume share, sum(vol*sign(close-open))/sum(vol).
Targets: index forward return from the close of minute t to t+k, k = 1..15; same-minute k=0 shown for reference.
Window 09:20-15:15. Reported: Pearson IC per k, t-stat with Newey-West-like overlap deflation (n/k).
Tradability check (fixed before looking): for each feature, the top 1% |value| minutes, signed; mean index move in the
signal direction over 5/10/15 min, in index points, against the break-even move of a 1-ITM nearest option round trip:
2 x real half-spread (h24: 0.16% BN/NIFTY) + app fills (5 bps in, 5 bps out) + app charges at 1 lot, divided by delta 0.6.
Gate to proceed to Stage C: a feature must show IC with |t| >= 3 at some k >= 2 (k=1 is within the fill delay) AND a
top-1% mean move >= 1.0 x break-even. Stage A cannot pass a rule into trading by itself (holdout data).

## 2. Stage B (runs when a valid token exists): fetch
`research/hunt/h39/fetch_stockopt.py`: OPTSTK, MONTH, expiryCode 1 (nearest monthly), ATM-3..ATM+3, CALL+PUT,
interval 1, 30-day windows from 2021-01-01 (walks back to the first served window), 11 stocks.
≈ 11 x 14 x 70 = 10.8k calls at <= 3 req/s (≈ 1 h), stops at the first 401/403. Responses are parsed in memory and
written as float32 zstd parquet per stock-year; nothing raw is written; a hard 700 MB cap on scratchpad/hunt/h39/data.

## 3. Stage C (on fetched data; choices on data < 2025-10-01 only)
Per minute per stock (ATM±3 of the nearest monthly):
- `PFLOW`: (sum call premium x volume - sum put premium x volume) / (sum of both), 1 and 5 min.
- `VIMB`: (call vol - put vol)/(call vol + put vol), 5 min.
- `OIB`: (call OI change - put OI change) over 15 min / total OI (calls written = bearish → sign as h26 DOIPC: put OI
  build minus call OI build).
- `IVJ`: ATM IV 5-min change, call IV minus put IV jump (skew jump).
- `SYNF`: synthetic forward K + C - P (median over ATM±1) vs stock price, 1 and 5-min change in bps.
Aggregated with the weights above. Z-scored by a trailing 20-day per-minute-of-day median/MAD (no look-ahead).
C1 lead-lag: IC vs index forward return k = 1..15 and vs the 1-ITM index option premium forward return; pre-holdout.
C2 triggers (only for features passing the Stage-A-style gate on PRE-holdout data): |z| >= 2.0 / 2.5 / 3.0, side =
sign; buy 1-ITM nearest-expiry BANKNIFTY / NIFTY CE (bull) or PE (bear) at the next minute's open, app fills + h24
real half-spread, one position at a time per index, entries 09:20-14:45, no expiry-day entries.
Exits (fixed now, 18 + 4 = 22): targets +15/+20/+25/+30 premium points x stops -10/-15/-20 (12); profit-lock ladder
(obuy default rungs) with -15 stop (1); time stops 15/30/60 min with -20 stop (3); targets +20/+30 with -15 stop and a
60-min time stop (2); Liquidity-arm exits (app LiquidityRules exits via h4/comps.py) (4: the four arm variants h4 ships).
All checked on the option's 1-min HIGH/LOW, stop first when both are touched in one bar. Square-off 15:20.
C3 filters on the app's Liquidity 15+5 trades (h4 port): skip a trade when the aggregated feature opposes it
(|z| >= 1 / 2), 5 features x 2 thresholds = 10 filters. Adoption: pre-holdout Rs/day gain > 0 in both halves and BH q <= 0.10.
Controls: random entries matched by index, day and minute-of-day (5 per signal) with identical exits;
BH over every variant; White RC / Hansen SPA over the full set; anchored walk-forward by year (choose best exit on
years < Y, test on Y). Survivors (BH q <= 0.10, SPA p <= 0.10, walk-forward positive) run ONCE on the holdout.
Reporting: gross and net, Rs/day at 1 lot, lots for Rs 5,000/day, Rs 1 lakh fixed-lot fit, per year, worst day/month,
max DD, P(losing month) by bootstrap.
If no feature passes the C1 gate pre-holdout, Stage C stops there (no P&L is run) and the verdict is NO.

## Amendment 1 (2026-10-08 ~05:30 UTC, before any Stage C result)
- The coordinator supplied a fresh token (JWT exp 2026-10-09 04:57 UTC). Probe: OPTSTK MONTH works (HTTP 200,
  ~7,500 1-min bars per 30-day window per strike/side; HDFCBANK served back to at least 2020-09). Stage B now runs.
- Disk: open/high/low are NOT fetched (features use closes; exits are on the INDEX option, not the stock option);
  IV is stored as int16 (IV x 20, 0.05 vol-pt resolution). ~5.7 bytes/row.
- Fetch made concurrent (6 threads, still <= 3 req/s) because calls take ~4.6 s each.
- C1 adds the direct index-option check: top-1% |feature| minutes, 1-ITM nearest index option on the signal side,
  bought at the next minute's open, marked at close t+5/10/15, minus the round-trip cost (premium points), with an
  all-minutes baseline. The C1 gate is unchanged (|t| >= 3 at k >= 2 AND top-1% move >= 1.0 x break-even); the
  option check is reported next to it and must also be > 0 net for a feature to go to C2.

## Amendment 2 (after the first BANKNIFTY C1 run, before any P&L)
- Numerical defect: where a feature's trailing 20-day MAD at a minute-of-day is 0, z = +-inf; nan_to_num turned that
  into 1.8e308 and the weighted aggregate overflowed (RuntimeWarning), which made the ICs meaningless (e.g. SYNF1's
  same-minute IC ~0). Fix: z clipped to [-5, 5]. Nothing else changed; the gate is unchanged. The first run's output
  is kept as stage_c_BN_v1.log for the record.

## Amendment 3 (before any filter result)
- C3 (filters on Liquidity 15+5) is run even though no feature passed the C1 gate, because a filter does not need a
  lead large enough to pay a trade on its own. Exact set: 6 features (PFLOW5, VIMB5, OIB15, IVJ5, SYNF1, SYNF5) x
  skip-when-opposed thresholds 1 and 2 = 12 filters, on h4's Liquidity trades for BANKNIFTY and NIFTY (h26 cost
  model). Adoption: d_day > 0 overall, in both halves (< 2024, 2024-Sep 2025), with 1.5x spread, and BH q <= 0.10.
  Holdout once only for adopted filters.
- C2 (standalone triggers) stays off, as pre-registered: no feature passed the C1 gate.
