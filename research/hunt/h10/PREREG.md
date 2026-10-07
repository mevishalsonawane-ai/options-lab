# h10 pre-registration (written 2026-10-07 BEFORE any h10 P&L result was computed)

Question: what does the Liquidity 15+5 edge (BANKNIFTY 15m+5m, FINNIFTY 30m+5m, MIDCPNIFTY 15m+5m; 1-ITM; rules
unchanged, h4/h7 port) really deliver once fills are limited by each contract's traded volume, lots are allocated by
capacity, and daily/monthly loss limits are applied? Option BUYING only.

Looked at before writing this (volume only, no P&L, pre-holdout trades only): the contract's traded lots in the 5
minutes before entry/exit. Since BANKNIFTY/FINNIFTY/MIDCPNIFTY lost weekly expiries (Nov 2024) the 1-ITM contract is a
monthly and volumes fell 10-50x:

| index | P25 exit 5-min vol (lots), 2023-06..2024-11 | P25, 2024-12..2025-09 |
|---|---|---|
| BANKNIFTY | 33,583 | 1,914 |
| FINNIFTY | 2,389 | 25 |
| MIDCPNIFTY | 1,042 | 74 |

So the REGIME WINDOW 2024-12-01 .. 2025-09-30 (monthly-only, current lot sizes) is the window used for capacity and
sizing; 2023-06-01 .. 2025-09-30 is reported for context. Holdout 2025-10-01 .. latest, tested once.

## Fill model (fixed)
- v5(k) = the contract's traded lots in the 5 one-minute bars before minute k.
- Participation cap c = 15% (sensitivity 10% / 20%, reported, never chosen).
- ENTRY and each ADD: filled lots = min(desired, max(1, floor(c * v5))). Unfilled lots are dropped (no chasing).
- EXIT (all lots leave on the arm's own exit, unchanged): at the exit minute sell up to max(1, floor(c * v5)) lots;
  the rest is worked in the following minutes, each minute up to max(1, floor(c * that minute's traded lots)) (0 if
  the minute did not trade), at that minute's close; anything left at 15:29 goes at the last close.
- Price of every order slice = the 'real' fill (app bps + 1-4 tick half-spread, stops x2) worsened by a square-root
  impact: Δp/p = κ·sqrt(q / max(v5, 1)), κ = 0.02, capped at 10% (sensitivity κ 0.01 / 0.04).
- Charges: app SandboxCosts per ORDER SLICE (brokerage Rs 20 each slice).
- GROSS headline = the same trades and the same filled quantities/slices at bar prints, no slippage/impact/charges.

## Capacity and allocation (fixed)
- Capacity N_i = floor(c × P25 of exit-minute v5, regime window) = BANKNIFTY 287, FINNIFTY 3, MIDCPNIFTY 11 lots.
- FLAT plan: FINNIFTY 3 and MIDCPNIFTY 11 lots (at capacity), BANKNIFTY n_BN = the integer that brings regime-window
  real net/day closest to Rs 5,000 (if impossible within capacity, report the max).
- h7 plan (B_prem10m3_terc: room terciles 1/2/3 units, +1 unit at premium +10/+20/+30%): unit s_i =
  max(1, round(n_i / 2.45)) for FIN/MIDCP (= 1 and 4), s_BN solved like n_BN.
- Choice flat vs h7: higher Sharpe on the regime window at the Rs 5,000/day size; if |ΔSharpe| < 0.10, flat (simpler).
- MAX realistic Rs/day: scan n_BN (and s_BN) upward with FIN/MIDCP at capacity; the peak regime-window net/day, and
  the value at BANKNIFTY capacity.

## Loss limits (fixed, at the Rs 5,000/day size; scale linearly with target)
- Daily: no new entry once the day's REALISED net <= -Rs 75,000 (15 x target). Open trades keep their own stops.
- Monthly: no new entry for the rest of the calendar month once month-to-date realised net <= -Rs 2,50,000 (50 x).
- Both always part of the plan; effect measured (without / daily / monthly / both) pre and holdout.

## Honesty
- Random entries (h4/h7 pools, same exits, same capacity model, same lots) vs real entries: p pre and holdout.
- Variants counted: 2 sizing rules x 4 limit settings x (1 + 4 sensitivities) = 40 daily series; BH over the
  random-entry p's; SPA over the regime-window daily series of the variants.
- Report per-year, worst day / month, max DD, bootstrap P(losing month), premium tied up, capital.
- Step-up ladder rungs and criteria derived from pre-holdout numbers only.
