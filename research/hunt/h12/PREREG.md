# h12 pre-registration (written 2026-10-07 BEFORE any h12 P&L by filter/feature was computed)

Question: can a simple pre-trade FILTER or REGIME-SIZING rule raise Liquidity 15+5's net edge per trade (and so its
Rs/day at the same risk, under h10's capacity/impact model)? Option BUYING only. Entries, strikes (1-ITM) and exits
are the app arm's, unchanged (h4/h7 port; packs rebuilt with research/hunt/h7/build.py into scratchpad/hunt/h12/cache).
Books: BANKNIFTY 15m+5m, FINNIFTY 30m+5m, MIDCPNIFTY 15m+5m; one position at a time per book (h7 base_trades).

Prior knowledge (not from h12 data): h8 reported that the arm's spot-repriced edge sits mostly on the short (put)
side; h7 found room sizing helped a little. No other per-feature P&L was looked at.

## Features (all known at the signal minute's close; computed identically for random-entry pool trades)
- side: +1 call, -1 put.
- dtrend: sign(previous day's close - SMA20 of the previous 20 daily closes); agree = dtrend == side.
- m60: sign(index close at signal minute - close 60 index-minutes earlier, crossing into the previous day if needed);
  agree = m60 == side.
- intraday: sign(close at signal minute - today's open); agree = intraday == side.
- vixlvl: India VIX previous close > median of the previous 250 daily closes -> "high".
- vixchg: VIX minute close at the signal minute (last available) vs VIX previous close: up / down
  (minute VIX exists from Oct 2021; missing -> daily open vs prev close).
- tod: signal minute < 11:00 or >= 11:00.
- dow: Monday or Friday vs Tue-Thu.
- dte: trading days to the next expiry day of the near series (Index exp flag); <= 3 vs > 3.
- gap: |today's open - previous close| / ATR14 (daily, previous 14 days, true range); >= 0.3 = "large".
- rng: (today's high - low from the open up to the signal minute) / ATR14; >= 0.5 = "wide".
- room: distance to the next liquidity level in index stops (h7 definition; no level = infinite).

## Rules (28, all counted; plus BASE = every trade at 1 unit)
Filters (trade only when the condition holds, 1 unit):
 F01 puts only           F02 calls only
 F03 dtrend agree        F04 dtrend disagree
 F05 m60 agree           F06 intraday agree
 F07 VIX high            F08 VIX low
 F09 VIX up today        F10 VIX down today
 F11 tod < 11:00         F12 tod >= 11:00
 F13 Tue-Thu only        F14 dte <= 3
 F15 dte > 3             F16 gap small (< 0.3 ATR)
 F17 gap large           F18 rng narrow (< 0.5 ATR)
 F19 rng wide            F20 room >= 3 stops
 F21 room >= 6 stops     F22 puts OR dtrend agree (skip counter-trend calls)
Regime sizing (2 units when the condition holds, else 1 unit):
 S23 puts                S24 dtrend agree
 S25 VIX high            S26 m60 agree
 S27 room >= 3 stops     S28 score: units = 1 + #{dtrend agree, m60 agree, VIX up}

## Measurement
- Trade P&L at 1 lot per unit with the 'real' fills (app bps + 1-4 tick spread) and app charges (net); GROSS = bar
  prints, no costs. Both always reported.
- Daily series over the h4 trading calendar (zero on no-trade days), three indices pooled.
- PRIMARY selection metric = daily net Sharpe on pre-holdout data (Oct 2021 .. 2025-09-30). At 1 lot without impact,
  max Sharpe == max Rs/day at the same daily risk (risk-matched: scale each rule to BASE's daily sd).
- Per-trade metric (the question asked): net Rs per trade per lot, and per-trade uplift vs BASE.
- Walk-forward, anchored by year: for test year Y in 2023, 2024, 2025(Jan-Sep), pick the rule with the best Sharpe on
  data < Y; record OOS risk-matched Rs/day vs BASE.
- Multiple testing: SPA / White RC over the 28 risk-matched excess series (rule scaled to BASE sd minus BASE),
  pre-holdout; BH over 28 per-rule bootstrap p's (excess mean > 0, block bootstrap).
- Random-entry control: the same rule applied to the random pool (5 random entries per signal, same exits, filtered
  on the random entry's OWN features). A rule is credited only if its uplift on real entries exceeds its uplift on
  random entries.
- Regime check: the monthly-only window 2024-12-01 .. 2025-09-30 reported separately (not used to choose).

## Choice (fixed)
CHOSEN = the rule with the highest pre-holdout Sharpe, IF (a) its walk-forward OOS excess is > 0 in aggregate and in
at least 2 of the 3 test years, (b) SPA p < 0.10, and (c) real uplift > random uplift. Otherwise CHOSEN = BASE (no
filter), and the top-Sharpe rule is still run once in the holdout for information only.

## h10 plan evaluation (fixed)
- h10 capacity/impact model (cap 15%, κ 0.02 central, 0.01/0.04 reported), research/hunt/h10/cap.py unchanged.
- BASE plan = h10 flat BN 26 / FIN 3 / MIDCP 11 lots, no loss limits (and with h10's limits reported).
- Rule plan: units x lots (FIN 3, MIDCP 11 per unit, at capacity, never more than 2x capacity); BN lots per unit solved
  so that the regime-window (2024-12..2025-09) daily net sd equals the BASE plan's ("same risk"); report net/day,
  gross/day, worst day/month, max DD, P(losing month) pre and holdout.
- Holdout 2025-10-01 .. latest: run ONCE for BASE, CHOSEN and the top-Sharpe rule.
