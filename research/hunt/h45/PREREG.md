# h45 pre-registration: "1 lot, exit at +5% profit or -5% loss" (written 2026-10-08, before any h45 P&L)

Boss's question: a symmetric per-trade premium bracket, target +5% / stop -5% of the entry premium. Option BUYING only,
1 lot, ATM and 1-ITM, nearest expiry. Holdout = every day on/after 2025-10-01: never used for any choice; read once.

Nothing is chosen. This is a descriptive test of one named rule; the other brackets are context only.

- **Headline variant (fixed now):** +5% / -5%, time stop 15:10 (square-off), 1-ITM (the app's strike) and ATM.
- **Context brackets (target/stop, % of the entry fill):** 3/3, 5/5, 7/7, 10/10, 5/3, 10/5.
- **Time stops:** 15:10 only (none), 15, 30, 60 minutes after entry (out at the next open regardless of P&L).
- 6 x 4 = 24 exits x 2 strikes = 48 variants per entry set. All reported, all counted.
- **Exit mechanics:** obuy engine, resting target (option 1-min HIGH) and SL-M stop (1-min LOW, gap -> bar open);
  if both are hit in the same minute the STOP is taken (conservative). Tie rate reported, plus the result if ties
  were split 50/50 (expected value: half of the tied trades get the target instead).
  The signal's own index stop/target are switched off (`sig_levels=False`): pure % bracket + time.
- **Costs:** app fills (+-5 bps, stops -10 bps), `Costs('dated')` charges, real half-spread on entry and exit
  (BN 0.16%, NIFTY 0.16%, MIDCP 0.21%, FIN 0.42%, SENSEX 0.20%) - h34 `core.post`. GROSS = mid points x qty.
- **Entries (not changed):**
  1. Liquidity 15+5: app port `liquidity15_5` (BANKNIFTY + FINNIFTY) and `liq_ext` for MIDCPNIFTY; reported per index.
  2. Random: 12 random minutes per index-day (09:16-15:04, coin-flip side) on all 5 indices; results by hour bucket,
     and each strategy is compared with random entries reweighted to its own (index, hour) mix, same exit.
  3. h18 60-min-break rule (`h18_r60`, 5 indices, max 3/day) and the h43 CHAMPION (BANKNIFTY 5-min EMA8/21 +
     ADX>15, max 2/day, expiry days skipped).
  Each keeps one-position-at-a-time and its max/day.
- **Reported per variant:** target hit rate, stop rate, time-out rate, tie rate, break-even hit rate after costs
  ((stop% + cost%) / (target% + stop%), using the average round-trip cost as % of premium), net Rs/trade, gross
  Rs/trade, Rs/day at 1 lot (per trading day of the entry's indices), max drawdown, pre-holdout and holdout,
  trades/day needed for Rs 5,000/day at that per-trade net (if > 0).
- **Pass bar (for "is it an edge"):** pre-holdout net > 0, t-stat of daily net > 2 after BH over all variants
  (q <= 0.10), and better than matched random. Otherwise the answer is NO.
