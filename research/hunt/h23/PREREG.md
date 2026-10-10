# h23 pre-registration: Liquidity 15+5 on BANKEX / NIFTYNXT50 (and FIN / NIFTY / SENSEX) added at FIXED 1 lot to h17's plan
(written 2026-10-07, BEFORE any h23 P&L was computed)

Question: (1) does the app's Liquidity 15+5 arm, unchanged, make money net on BANKEX and NIFTYNXT50 options (BUYING
ONLY)? (2) does adding any of BANKEX, NIFTYNXT50, FINNIFTY, NIFTY, SENSEX at a FIXED 1 lot to h17's plan (BANKNIFTY 1 lot
+ MIDCPNIFTY 1 lot, Rs 1,00,000, free-cash check) raise Rs/day? No Kelly, no growing lots, no option selling.

Known before writing (disclosed): h10/h11/h13/h14/h15/h17 reports incl. holdouts. In particular h11: NIFTY/SENSEX 15+5
had no edge after costs pre-holdout (market entry model); h15: FIN 1-lot mean net/trade +209 in Dec24-Sep25 and -71 in
the holdout; h17: BN1+M1 pre Rs 111/day, holdout Rs 308/day. Nothing about BANKEX or NIFTYNXT50 P&L. Data facts looked at
(no P&L): BANKEX options exist from 2023-10-05 (weekly to Nov 2024, monthly after); NIFTYNXT50 options exist ONLY from
2025-11-26 (monthly), i.e. entirely inside the holdout; 80% (BANKEX monthly 2025-26) and ~95% (NIFTYNXT50) of the
option minute rows have volume 0 (carried quotes, not trades). Strike step 100 for both.

## Rules (unchanged arm)
Liquidity 15+5 exactly as h4's port (`research/hunt/h4/comps.py`, rules = LiquidityRules.kt): pool-on-swing break,
15-min + 5-min books (FINNIFTY 30+5 as the app), room >= 1 index stop, 1-ITM, nearest expiry (monthly now), -15% premium
stop, 20-min/+5% time stop, index stop, next-level target, failed break, new level, 15:10, expiry days skipped.
The only new setting, the index stop for a new index, is fixed a priori by h4's rule (~0.065% of the index, rounded to
5): round5(0.00065 x median daily close over its pre-holdout option era) -> **BANKEX 40** (median 57,747 over
2023-10..2025-09), **NIFTYNXT50 45** (median 67,101 over 2024-10..2025-09). BN 30, FIN 15, MIDCP 8, NIFTY 15, SENSEX 50
(unchanged). Signals for BN/FIN/MIDCP/NIFTY/SENSEX = h4's cached signals; BANKEX/NIFTYNXT50 = h4's `liq_ext_signals`.

## Data honesty ("prints only") - applied to ALL indices, base included
- Every option minute with volume 0 (or NaN) is treated as NO PRINT (price NaN). Entry needs a print of the 1-ITM
  contract within sig_min+1 .. sig_min+3 (engine max_delay 2), else the signal is skipped ("no print").
- Liquidity check: skip the signal if the contract printed < 1 lot in the 5 minutes before the entry bar ("thin").
- Exits are the arm's, evaluated on prints only; market exits fill on the next printed bar (engine) and are worked by
  h10's slicer (15% of each minute's printed lots), anything left at 15:29.
- Spread from the data: Roll estimator per (index, calendar month) on 1-minute log returns of consecutive PRINTS of the
  traded 1-ITM contracts (whole day). Effective half-spread hs = Roll spread / 2, applied POINT-IN-TIME: a trade uses the
  previous month's estimate of its index (first month of an index: its own month, disclosed). Cost model = h10 'liq'
  (5 bps + 1-4 ticks by volume) PLUS the excess of hs over that, on every aggressive buy and sell.
- Execution: h14 E2 (limit buy at entry print +0.5%, immediate part capped by h10 sqrt impact, rest rests 3 minutes on
  printed bars, cancel, no chase), M2 impact, kappa 0.02 central and 0.04 pessimistic (0.01 info). If hs > 0.5% the
  limit cannot be marketable: NO immediate fill, only the passive fills.
- Charges: app costs; BSE costs for BANKEX/SENSEX. GROSS = same fills at bar prints, no costs.
- The h17 baseline is also reproduced on the old (no-mask) h15 table as a validation (expect Rs 111/day pre).

## Per-index stats (1 lot, each index alone, kappa 0.02 and 0.04)
Trades, signals skipped (no print / thin / no contract), net & gross Rs, Rs/day, Rs/trade, t of daily net, bootstrap p
(day-block bootstrap, P(mean <= 0)), random-entry baseline (5 random-minute/random-side alternatives per signal from the
engine's pool, same exits/fills/costs; 2,000 draws of one alternative per trade; p = share of draws with mean net/trade >=
the real one), max DD, worst day, % green months; pre (< 2025-10-01) and holdout separately; coverage (signal days with a
chain, share of minutes with prints near ATM).

## Selection rule (pre-holdout data only, kappa 0.02)
Candidate X in {BANKEX, NIFTYNXT50, FINNIFTY, NIFTY, SENSEX} is ELIGIBLE iff all of:
 (a) >= 30 pre-holdout trades;
 (b) pre-holdout net > 0 over all its pre data AND net > 0 in the monthly-only era Dec 2024 - Sep 2025;
 (c) random-entry p < 0.05 after BH over the 5 candidates (q < 0.05);
 (d) portfolio: base BN1+M1 + X1 (capital walk below) has higher Rs/day than base BN1+M1 on 2021-10-01..2025-09-30.
Then greedy: start from base; repeatedly add the eligible candidate with the largest Rs/day gain (same walk, same window)
while the gain is > 0. The result is THE plan. If none is eligible, the plan stays BN1+M1. NIFTYNXT50 has no pre-holdout
options, so it fails (a) by construction; it is reported in the holdout as information only.

## Capital walk (as h17, fixed lots)
Start Rs 1,00,000; 1 lot per index, always; one position per book; trades in time order; capital E = realised; free
cash = E - premium of open positions; a signal is SKIPPED FOR CASH (counted) if its premium + Rs 100 > free cash. No
daily stops (h17's choice). Rs/day = net / trading days in the window (BANKNIFTY trading calendar).

## Holdout (2025-10-01 .. latest), run ONCE
The chosen plan and the base, kappa 0.02 and 0.04 (0.01 info): the same stats + ending capital, min capital, skipped for
cash. Info only (no re-choice): every base + X and every index alone. Multiple testing: 5 candidates (BH above); all
variants reported.
