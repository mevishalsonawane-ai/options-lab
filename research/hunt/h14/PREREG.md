# h14 pre-registration: execution policies for the Liquidity 15+5 plan (written 2026-10-07, BEFORE any h14 P&L)

Question: h10's Rs 5,000/day plan (Liquidity 15+5 unchanged, 1-ITM nearest expiry, flat BN 26 / FIN 3 / MIDCP 11 lots)
loses 55-65% of gross to spread + impact + charges and turns negative at κ = 0.04. Can HOW we execute (not what we
trade) raise realistic net Rs/day? Signals, strike, books, exit decisions and their timing are the arm's, UNCHANGED
(h4/h7 port, h10 trade list). Option BUYING only.

Known before writing this: h10 / h12 / h13 results for the incumbent (market orders, h10 model). No h14 numbers.

## Fill models
Common: 'real' fills (app 5 bps market / 10 bps stop + 1-4 tick half-spread from 5-min volume, stops x2), app charges
per child order (Rs 20 brokerage each), participation cap 15% (per child: max(1, floor(0.15 × v5)) for aggressive
children, max(1, floor(0.15 × that minute's traded lots)) for passive fills), square-root impact capped at 10%,
κ ∈ {0.01, 0.02, 0.04}. Entry children never fill at or after the arm's exit bar; unfilled entry lots are dropped
(no chasing) and a trade with 0 lots is a MISSED trade (P&L 0, counted; its foregone baseline P&L is reported).
Exit lots always leave (worked to 15:29 as h10).

- **M1 (h10)**: each aggressive child pays κ·sqrt(q / v5 at that minute), independently (reproduces h10 exactly for the
  baseline).
- **M2 (conservative meta-order, THE CHOICE MODEL)**: aggressive child j of one parent order (an entry, or an exit)
  pays κ·sqrt(Qcum_j / Vacc_j): Qcum_j = aggressive lots of this parent so far including child j; Vacc_j = v5 at the
  parent's first child + the contract's traded lots from that minute up to the minute before child j. (A one-shot
  order = h10; slicing helps only by as much as the market trades meanwhile; no impact decay is credited.)
- **Passive (resting limit) fills**: a resting BUY at L fills in minute k only if that bar's LOW <= L − 1 tick (traded
  THROUGH the limit; a touch is not a fill); a resting SELL at L only if HIGH >= L + 1 tick. Fill price = L exactly
  (no improvement), quantity <= max(1, floor(15% × minute's traded lots)), no spread, no impact, charges per minute's
  fill. The minute in which the order is placed never fills passively (we do not know where inside it we were).
  Adverse selection is therefore in the data: a buy that never trades back to L (the runaway winners) is missed.
- GROSS (headline) = the same filled quantities at raw reference prints (market child at the bar's open or the h10
  worked-close print; passive fill at L), no bps / spread / impact / charges.

## Policies (all counted; tick 0.05; c0 = arm's entry bar, xc = arm's exit bar, P0 = O[c0])
Entry family
- E0 baseline: one market order at c0 (h10).
- E1 passive join: buy limit at L = P0, rest c0+1 .. c0+3; unfilled lots cancelled.
- E2 marketable limit: L = P0 × 1.005 (tick-rounded). At c0 take the largest q with model avg price <= L (M1/M2 price
  of a market child: O·(1+bps) + spread ticks + O·κ·sqrt(q/v5)), capped as h10; the rest rests at L for c0+1 .. c0+3,
  then cancelled.
- E3 = E2, but lots still unfilled after c0+3 are bought at market at c0+4 (h10 cap; excess dropped).
- E4 TWAP-3: three market children of ceil(n/3) (last = rest) at the opens of c0, c0+1, c0+2.
- E5 TWAP-5: five children over c0 .. c0+4.
Exit family (urgent exits = premium stop, index stop: ALWAYS the baseline h10 exit)
- X0 baseline: h10 (first child at the arm's exit fill, rest worked at minute closes).
- X1 passive non-urgent exit: for index_target / time_stop / square_off exits, sell limit at L = last print before xc
  (forward-filled close of minute xc−1), resting xc .. xc+2; remainder exits from xc+3 as h10 (market child at the
  minute's close, cap, worked to 15:29).
- X2 pre-placed target: when the arm has an index target, at the entry bar place a sell limit for all lots at
  P* = e + Δ̂·|target − ref| (tick-rounded up), Δ̂ = (ITM1 − ATM premium)/strike step at the last valid minute <= c0−1,
  clipped to [0.2, 1.0] (missing → 0.6). It rests c0+1 .. xc−1 (+ through xc only for minute-decided exits' decision
  minute, i.e. strictly before the arm's exit bar); remainder leaves with the arm's own exit (X0).
- X3 TWAP-3 non-urgent exit: children of ceil(n/3) at xc (arm's fill print), xc+1, xc+2 (minute opens), rest worked h10.
Strike split
- K1: split the lots between the 1-ITM and the ATM option (same expiry) in proportion to their v5 at c0
  (n_ATM = round(n·vA/(vI+vA)), n_ITM = n − n_ATM, ATM leg 0 if it has no bar at c0). Each leg: own market entry, own
  cap/impact/spread, exits at the arm's exit (stop exits: ATM leg sells at market at the next bar's open), h10 working.

## Choice rule (pre-holdout only; HOLDOUT 2025-10-01 .. latest tested ONCE after choice.json)
Metric: net Rs/day of the h10 plan size (flat BN 26 / FIN 3 / MIDCP 11, no loss limits), model M2, κ = 0.02, on the
REGIME window 2024-12-01 .. 2025-09-30 (monthly-only market). Per family (entry E1-E5, exit X1-X3, K1), the best policy is
adopted only if ALL hold vs the baseline E0/X0 under M2:
 (a) REGIME net/day uplift >= Rs 250;  (b) uplift > 0 on LONG (2023-06-01 .. 2025-09-30);
 (c) uplift > 0 in >= 2 of the 3 anchored walk-forward years (2023, 2024, 2025 to Sep);  (d) uplift > 0 at κ = 0.04 REGIME.
The final policy = the adopted entry + exit + split components combined (if none adopted: baseline). If the combination's
REGIME net/day is below the best single adopted component's, the best single component is the final policy.
Counted: 9 single policies + 1 combination, × 3 κ × 2 models. SPA / White RC over the REGIME daily uplift series
(policy − baseline, M2, κ 0.02) of the 9 singles; random-entry control (the h7 pool, same exits, same policy, same lots)
for baseline and final, BH over those p's.

## Reported (not chosen)
M1 vs M2; κ 0.01/0.04; fill rates and missed trades (count, their foregone baseline net); cost split; loss-limit plan
(h10 -75k/day, -2.5L/month); max realistic Rs/day: BN scanned {26, 50, 75, 100, 150, 200, 287} with FIN 3 / MIDCP 11 for
baseline and final policy (κ 0.02 and 0.04); per year, worst day / month, max DD, P(losing month), premium tied up.
