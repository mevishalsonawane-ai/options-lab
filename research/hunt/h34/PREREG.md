# h34 pre-registration: premium-POINT exits on every catalog entry (written 2026-10-08, before any h34 P&L)

Question (Boss): "points" = absolute PREMIUM points (Rs of option price): buy at 150, target 170 = +20 points. Most
earlier studies used premium-% stops/targets, index-point stops, or each arm's own exits. Does a fixed premium-POINT
exit menu change any verdict?

Option BUYING only. Holdout = every day on/after 2025-10-01: never used for any choice; read once at the end.

## 1. Exit menu (fixed now; 128 exits)

- Target: +15 / +20 / +25 / +30 premium points (resting limit on the option's own minute high).
- Stop: -10 / -15 / -20 / -30 premium points (resting SL-M on the option's minute low; gap -> bar open, -10 bps).
  A stop that would sit at or below Rs 0.05 is not placed (cheap options): engine rule, unchanged.
- Time stop: out after 15 / 30 / 60 minutes regardless of P&L (MARKET at the next open), or none (= 15:10).
- Profit lock: off / on. On = +10 -> stop to breakeven (after charges, obuy `be_floor`), +20 -> lock +10,
  +30 -> lock +20 (obuy ladder rungs (1,0),(2,1),(3,2) on R = 10 points; peak from the bars BEFORE the current one).
- Square-off 15:10 for every exit. The signal's own structural exits (index stop / index target / exit_at) are NOT
  used (`sig_levels=False`): the menu is pure premium points + time.
- 4 x 4 x 4 x 2 = 128 exits.

## 2. Entries (not re-optimised)

- Every strategy in `research/obuy/strategies/registry.py` MODULES (63 strategies), plus:
  - `liq_bnfin`: the app's Liquidity 15+5 entries (BANKNIFTY 15+5, FINNIFTY 30+5; validated port);
  - `liq_ext`: the same rules on NIFTY / SENSEX / MIDCPNIFTY (h4 `comps.liq_ext_signals`, a-priori index stops);
  - `h18_r60`: h18's "close beyond the rolling 60-min high/low" rule (levels R60, no gamma filter), 5 indices.
- Per strategy, two entry parameter sets (deduplicated if equal):
  - DEFAULT = `sig_grid[0]` (the first declared);
  - IS-BEST = the sig params of the variant with the highest PRE-HOLDOUT net in the earlier catalog runs
    (obuy_cache runs ga_all / gb_all chunks / gc_all / grids / singles; computed by `select_is.py` from days
    < 2025-10-01 only). Single-variant strategies: both = the one set.
- Strike: ATM and 1-ITM, nearest expiry (`series='near'`) for every strategy (prem_band / OTM / monthly rules of the
  originals are replaced). Explicit strikes in signals are dropped; the strike is taken from the signal's ref_spot.
- Multi-day signals (`exit_day`) are turned into same-day trades (the column is dropped); entries at/after 15:09
  cannot trade (BTST-type rules then have no trades and are reported as n/a).
- Straddle-type signals (side 0): the points apply to the PAIR's combined premium (closes only, engine rule).
- Each strategy keeps its own execution (expiry skip/allow/only, filters) and position rules (one at a time,
  max per day). Indices: the catalog strategies keep the indices their signal functions trade (mostly NIFTY /
  BANKNIFTY / FINNIFTY / SENSEX); Liquidity, h18 and the random baseline cover all five.
- Menu variants per strategy: up to 2 sig x 2 strikes x 128 exits = 512. Every one is counted.

References (not part of the menu count, not selectable): each strategy's ORIGINAL exits on its IS-best variant
(sig + rule + exits from the earlier run) and on its default variant (s0, r0, x0), same costs.

## 3. Costs and money

- GROSS (Boss's headline): mid-price points x quantity (fills' +-bps backed out), no charges, no spread.
- NET: obuy app fills (+-5 bps, stops -10 bps) + `Costs('dated')` charges (rates in force on each date) + a
  half-spread on entry and exit: BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.20%
  (h24 snapshot; SENSEX assumed). STRESS: 1.5x those spreads. NET is the decision metric.
- 1 lot of the day's size (lot as of each date). Rs per premium point per lot = the lot size (latest: NIFTY 65,
  BANKNIFTY 30, FINNIFTY 60, SENSEX 20, MIDCPNIFTY 120).
- Premium bands for reporting: < 100, 100-200, 200-400, >= 400 Rs entry premium.

## 4. Random-entry baseline (per index / time)

- Per index-day (all days, expiry days included): 36 entries at uniform random minutes 09:16-15:04 with a coin-flip
  side, ATM and 1-ITM nearest expiry, every one of the 128 exits, same costs. Seed fixed (34).
- Cells = (index, day, hour bucket: 09:15-10:14, 10:15-11:14, 11:15-12:14, 12:15-13:14, 13:15-14:14, 14:15-15:09).
- Matched test per variant: each real trade is compared with the random entries of its cell, same strike rule and
  same exit. Null = mean of one random draw per trade; CLT approximation with the cells' mean and variance
  (z test, one-sided p). Trades in cells with < 2 random entries are left out of the test.

## 5. Tests and pass rule (pre-holdout data only)

Per menu variant (NET):
- G1 pre-holdout net > 0 with >= 30 trades;
- G2 one-sided t-test of mean daily net > 0, BH q <= 0.10 across ALL menu variants of all strategies;
- G3 matched random-entry p, BH q <= 0.10 across all menu variants;
- G4 (family) Hansen SPA_c over all menu variants' daily net (stationary bootstrap, block 5, B = 1000) p <= 0.05.
Per strategy:
- G5 anchored walk-forward by year over its menu (pick the variant with the best NET on all earlier years, >= 20
  training trades; first two calendar years of the strategy's data only train; test years up to 2025 Jan-Sep):
  WF net > 0 and positive in more than half the test years.
A strategy PASSES if G4 holds, G5 holds and its full-pre-holdout best variant passes G1-G3. For each passing
strategy the variant with the best pre-holdout NET among its G1-G3 passers is run ONCE on the holdout.
If nothing passes, nothing is run on the holdout as a candidate. As REFERENCE only (already public in earlier hunts,
not a choice): the holdout of Liquidity 15+5 BN+FIN with its original exits and with the menu exit the
walk-forward picks for 2025, and the random baseline's holdout mean.

"Did points change the verdict?" per strategy: original exits (IS-best and default references) vs the point menu
(WF result, menu median, share of menu variants with positive NET, best-of-menu with its random p). The verdict
changes only if the point menu passes where the original failed, or flips the sign of the walk-forward.

## 6. Reported

Rs/day at 1 lot (WF net / trading days of the strategy's indices in the WF period), lots needed for Rs 5,000/day
(if positive), max drawdown at 1 lot, per year, gross and net, by index and premium band; variant counts.
