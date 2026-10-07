# h13 pre-registration: the option choice for Liquidity 15+5 (written 2026-10-07, BEFORE any h13 P&L was computed)

Question: the app's Liquidity 15+5 arm buys the 1-ITM nearest-expiry option. Keep its entries, exits, index logic and
books UNCHANGED (h4/h7 port: BANKNIFTY 15m+5m, FINNIFTY 30m+5m, MIDCPNIFTY 15m+5m), and change ONLY which option is
bought. Does another strike or contract raise the realistic Rs/day once fills are limited by traded volume (h10's
capacity/impact model)? Option BUYING only.

Known before writing this: h10's results for the incumbent (1-ITM, near, -15%) and h10's volume table. Nothing else.

## Grid (16 variants, all counted)
- money: OTM1 (-1), ATM (0), ITM1 (+1, the incumbent), ITM2 (+2), on the index's strike step from the signal close.
- series: `near` (weekly when Dhan has one that day, else the monthly; = what the app trades) and `month` (the nearest
  monthly, i.e. the longer-dated contract while weeklies existed).
  Since Nov 2024 BANKNIFTY / FINNIFTY / MIDCPNIFTY have monthlies only, so near == month there. A NEXT-month contract is
  not in the data (Dhan's rolling endpoint served the nearest expiry only), so "next month" is NOT testable in the
  monthly era; the weekly-era near-vs-month comparison is the closest test of "shorter vs longer-dated".
- premium-% exits: `fixed` (the arm's -15% stop and +5% time-stop gain, on the bought option's own premium) and
  `scaled` (same index move): f = λ_k / λ_ref, λ = Δ/P of the contract at the signal-minute close, Δ by a central
  finite difference over the neighbouring strikes of the same right and series, ref = the 1-ITM near contract at the
  same minute and side. stop = clip(0.15·f, 0.05, 0.60) rounded to 0.01, time-stop gain = stop/3. Where Δ cannot be
  computed, f = the median f of that (index, variant) on pre-holdout trades. ITM1/near/scaled ≡ the incumbent.
- Everything else as the arm (index stop, target, failed break, new level, 20-min time stop, 15:10, expiry days skipped,
  one position per book). The same rule applies to all three indices (no per-index picking).

## Model (h10, unchanged; research/hunt/h10/cap.py logic)
Participation cap 15% of the contract's lots traded in the 5 minutes before the order (min 1 lot), excess entry lots
dropped, exits worked over following minutes; 'real' fills (app bps + 1-4 tick half-spread) + square-root impact
κ·sqrt(q/v5), κ = 0.02 (also 0.01 / 0.04 reported), capped 10%; app charges per order slice. GROSS = same trades and
quantities at bar prints, no costs. NET and GROSS always reported side by side.

## Windows
- PRE = signals' start (Oct 2021) .. 2025-09-30. Eras: W = .. 2024-11-30 (weeklies existed), M = 2024-12-01 .. 2025-09-30
  (monthly only; the market that exists now). HOLDOUT = 2025-10-01 .. latest, run ONCE after choice.json is written.

## Sizes
- Capacity per variant and index: N = floor(0.15 × P25 of the contract's exit-minute 5-min lots), on era M (and on era W
  for the weekly-era table). Computed mechanically from volume.
- Reference size (for comparisons, random baseline, SPA, walk-forward): h10 plan lots BN 26 / FIN 3 / MIDCP 11
  (the simulator clips each order to the variant's own volume).
- "Max realistic Rs/day" of a variant: FIN and MIDCP at their era-M capacity (min 1), BN scanned over
  {10, 20, 30, 50, 75, 100, 150, 200, 287, 400} capped at its era-M capacity; the peak era-M net/day at κ = 0.02
  (κ 0.01 / 0.04 reported).
- Rs 5,000/day size: FIN / MIDCP at capacity, BN = the integer giving era-M net/day closest to Rs 5,000.

## Choice rule (on PRE only)
1. Eligible: reference-size net (κ 0.02) > 0 in BOTH era W and era M, and random-entry BH q < 0.05 over the 16
   (p from h4-style pools: 5 random minutes / coin-flip side per signal, same strike rule, same exits, same model,
   same lots, whole PRE).
2. Pick the eligible variant with the highest max-realistic Rs/day (era M). Variants that are identical in era M
   (near vs month) are separated by the same score on era W (with era-W capacities).
3. Simplicity tie-break: if the incumbent ITM1/near/fixed is eligible and within 15% of the best score, keep it.
4. Also reported (not used for the choice): anchored yearly walk-forward (test 2023, 2024, 2025-pre; pick the best
   reference-size net/day over all earlier PRE years), SPA/White RC over the 16 reference-size daily series (era M and
   PRE), per-year, worst day / month, max DD, bootstrap P(losing month), premium tied up.

## Holdout (once)
The chosen variant and the incumbent at their pre-solved Rs 5,000/day sizes (no limits, and with h10's daily -75k /
monthly -2.5L limits), plus each one's max-realistic curve. Any other holdout numbers are post-hoc and labelled so.
