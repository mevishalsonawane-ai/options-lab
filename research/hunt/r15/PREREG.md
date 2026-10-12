# R15 pre-registration: daily (pre-open) filters on Liquidity 15+5 BANKNIFTY

Written 12 Oct 2026 BEFORE any Liquidity P&L was split by these features. Code: `research/hunt/r15/`
(`features.py` builds the day table from public data, no P&L; `run.py design` then `run.py hold` ONCE, which writes
`<scratch>/hunt/r15/HOLDOUT_RUN.flag` and refuses a second run). Nothing below may change after the first `design` run.

What I had seen before writing this: FINDINGS.md, HUNT_FINAL, X2, X3, R12-R14 (no study splits Liquidity 15+5 by
any of F1-F5 below; FINDINGS notes that high-IV days are wider and that wide-CPR days hurt a different scalp).

## Why this study, and why it is small
The Dhan deep-history token expired on 10 Oct 2026 and its cache is not in this container, so the 2020-2026 h24/h36
trade table cannot be rebuilt here. What IS available, legally and for free:
- the repo's `research-data` release: BANKNIFTY 1-minute index + near-the-money 1-minute options with OI
  (`banknifty_prev_year_wide.parquet` 2024-02 .. 2025-02, `banknifty_year.parquet` 2025-02 .. 2026-02; Kaggle
  broker-scraped option bars, not an official record);
- NSE F&O bhavcopy archives (daily OHLC / OI of every contract): `archives.nseindia.com` (old format, to Jul 2024) and
  `nsearchives.nseindia.com` (UDiFF format, from Jul 2024);
- India VIX daily closes (Yahoo `^INDIAVIX`, public chart endpoint).
So the only honest test possible is a two-year one: about 500 sessions, of which the last ~100 (from 1 Oct 2025) are
inside the locked holdout of earlier hunts.

## Base trades (fixed)
`research/liquidity_break.py` `simulate()` on BANKNIFTY, the 15-minute and 5-minute books side by side, source
"both" (pool break on a swing zone), failed-break stop on, the app's exits: 15% premium stop, index stop 30 points
back through the level, time stop "not +5% after 20 minutes", next-liquidity target, new-liquidity exit, 15:10.
Entries 09:20 .. 14:00 (`LAST_M = 285`, the app's window). Strike: the script's ATM of the nearest expiry (the app
buys 1-ITM; not changeable without rewriting the engine - disclosed). No room filter (the app has one - disclosed).
1 lot of 30 (today's lot) for every date.

Costs (replacing the script's Rs 40): fills at the option's minute price (script price minus its 0.5 slippage),
entry x (1 + 0.16%), exit x (1 - 0.16%) (h24 BANKNIFTY half-spread), stop exits a further 0.05%; charges
`obuy.costs.Costs('app')`: brokerage Rs 20 an order, STT 0.15% of sell premium, exchange 0.03503% + IPFT 0.0005%,
SEBI Rs 10/crore, stamp 0.003% of buys, GST 18% on brokerage + exchange + SEBI. Stress: 1.5x the half-spread.

## Periods
- DESIGN: first session .. 30 Sep 2025. All tests below.
- HOLDOUT: 1 Oct 2025 .. last session in the files (~23 Feb 2026). Opened once, only for filters that PASS DESIGN;
  all filters' holdout numbers are then printed as information.

## Filters (each known before 09:15 of day D; "prev" = the previous session; percentiles use sessions BEFORE prev)
Each SKIPS every Liquidity trade of a flagged day. Direction predicted in advance: flagged days are worse.
- F1 QUIET: prev-day BANKNIFTY range (high - low) / close below the 33rd percentile of the 60 sessions before it
  (min 20). Idea: size clusters (BIG_BARS), the arm lives on rare runners.
- F2 LOWVIX: India VIX prev close below the 33rd percentile of the 250 sessions before it (min 60). Same idea via
  implied vol. (The app's VolFilter tests the opposite, high-vol skip; both are reported.)
- F3 PCR_EXT: prev-day BANKNIFTY option PCR (total put OI / total call OI, nearest expiry after prev, bhavcopy)
  outside the 10th..90th percentile of the 120 sessions before it (min 40). Idea: crowded positioning -> pinned day.
- F4 FUTOI_UP: prev-day BANKNIFTY futures open-interest change (sum over expiries, % of the previous OI; expiry-day
  roll-offs included as reported) above the 67th percentile of the 60 sessions before it (min 20).
- F5 AFTERDROP (X2's "untested lead", information test): days after a prev-day close-to-close move <= -1%. Tested as
  a per-trade LIFT (mean net on flagged days minus mean on the others; predicted > 0). Even if it passes it is not a
  rule (it cannot lower risk except by skipping all other days); counted in the BH family to keep the bar honest.

## Tests (DESIGN), K = 5 counted together
- Gain per day (F1-F4) = - (net of skipped trades) / design sessions.
- Baseline p (one-sided): skip the same number of RANDOM sessions (drawn from all design sessions, B = 5,000; day
  clusters kept), p = share of random gains >= the filter's. F5: permutation of the day labels (B = 5,000).
- Benjamini-Hochberg over the 5 p-values.
- PASS needs all: BH q <= 0.10; gain > 0 in both design halves (split 1 Jan 2025); gain > 0 at 1.5x spread;
  at least 30 trades skipped.
- Reported for everything: trades, sessions, skipped trades and days, Rs/day base vs filtered, t, max drawdown,
  green months.

## Holdout bar (only for DESIGN passes)
Gain per day > 0 in the holdout. With ~100 sessions this has little power; a pass would only justify a SHADOW test
(research/PREREG_LIQUIDITY_ADDONS.md), never a live change by itself.
