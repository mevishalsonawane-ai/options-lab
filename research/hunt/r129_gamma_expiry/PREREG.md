# R129 pre-registration: the "Gamma-Driven Hero-to-Zero" expiry-day option buying strategy, as stated

Written 28 Oct 2026, before any R129 number was computed. Research only: nothing in the app changes, nothing trades.

Boss, 28 Oct: pasted a strategy description (VIX filter, a 3x OI imbalance wall, a 5-minute index box breakout through the wall,
heavyweight stocks above VWAP, a dual Supertrend on the option's 1-minute premium chart, ₹15-30 premiums, a 4x target), then:
*"Okay, now test the gamma strategy"*. This is one test of a rule from a source; nothing is fitted.

## 1. Signal source (CLAUDE.md, 14 Oct)

- **As written, the trigger is read from the option's own price chart:** a dual Supertrend on the 1-minute premium. That is NOT
  the default. Those variants are labelled "option-chart signal" and reported apart.
- **An index-signal version is tested beside it (the default):** the same rules, with the dual Supertrend read on the index's
  1-minute chart.
- In both, the OI wall, the premium corridor and the option's volume are option-chain context around the index's breakout.

## 2. Data

- **Index and VIX:** lake `index_1m`, all five indices (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX) and India VIX, from the
  first expiry with all data to 19 Oct 2026.
- **Options:** lake `options_rolling_1m` WEEK files (the nearest expiry), cleaned (lakeclean steps 1-3, stale = True). A minute
  with no trade (volume 0) is never priced. OI is read per strike.
- **Heavyweights:** lake `equity_1m` (1-minute bars with volume), with VWAP since 09:15.
- **Expiry days:** each index's own, from the rolling file (`LakeOptions.expiry_days`). After Nov 2024 these are monthly for
  BANKNIFTY, FINNIFTY and MIDCPNIFTY.
- **Money:** 1 lot at today's lots (NIFTY 65, BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20), `Costs("dated")` and
  `Fills("liq")`; also 2x slippage.

## 3. The rule (each index, each of its expiry days; one trade at most)

1. **VIX at 13:15:** India VIX's last close at or before 13:15 must be above 12; else no trade.
2. **The wall at 13:45:**
   - Take the last known OI at or before 13:45 of every listed strike within ±10 strikes of the money.
   - The wall is the strike with the largest call + put OI.
   - **Bullish wall** (short covering expected above it): its call OI ≥ 3 × its put OI.
   - **Bearish wall** (long unwinding expected below it): its put OI ≥ 3 × its call OI.
   - Otherwise no trade.
3. **The box:** the index's highest high and lowest low (wicks) from 13:15 to 13:59.
4. **The breakout, 14:00-15:00:**
   - The index's 13:59 close must still be on the near side of the wall: at or below it for bullish, at or above it for bearish.
   - The setup is the first 5-minute bar (closing 14:05 … 15:00) whose close is beyond both the box and the wall in the wall's
     direction: above max(box high, wall) for bullish, below min(box low, wall) for bearish.
   - That close must also be above (bullish) or below (bearish) the index's TWAP: the mean of its 1-minute closes since 09:15.
5. **Heavyweights at the setup bar's close:** each stock's 1-minute close above (bullish) or below (bearish) its VWAP since 09:15.
   A missing stock fails the check.
   - BANKNIFTY and FINNIFTY: HDFCBANK, ICICIBANK, AXISBANK.
   - NIFTY and SENSEX: HDFCBANK, RELIANCE, ICICIBANK.
   - MIDCPNIFTY: no stated heavyweights. The check is skipped, and MIDCPNIFTY is reported apart.
6. **The option:**
   - Strike: the CE one strike above the ATM (bullish), or the PE one strike below it (bearish). The ATM is the index close at the
     setup, half up to the strike step.
   - Its last traded close at the setup must be within ₹15-30 ("strict"); else no trade.
   - Variant "near": the nearest out-of-the-money strike from ATM±1 outward (up to ±8) whose close is within ₹15-30.
7. **The trigger, from the setup minute to 15:00:**
   - The first minute where both Supertrend(7, 3) and Supertrend(10, 2) point the trade's way, AND the option's volume that minute
     exceeds the mean of its previous 10 traded minutes.
   - Buy at the next traded minute's open.
   - **Option-chart variants:** both Supertrends are on the option's 1-minute premium bars (its traded minutes since 09:15) and must
     be "up".
   - **Index-signal variants:** both are on the index's 1-minute bars and must point the wall's way.
   - Supertrend is R73's `supertrend_dir` (Wilder ATR, hl2 bands; the app's).
8. **Exits:**
   - Target: a resting sell at 4 × the raw entry premium, filled when a bar's high reaches it.
   - Signal exit: when either Supertrend turns against the trade at a minute's close, sell at the next minute's open. Option-chart
     variants read the premium's Supertrends; index-signal variants read the index's.
   - Time exit: the cap bar's close, 15:25 or 15:05 by variant.
   - A target touched in the same bar as a flip counts first (the resting order).
   - There is no premium stop in the stated rule. The Supertrend flip is the stop.

## 4. Variants (one Benjamini-Hochberg family of 6)

| id | trigger read on | strike | cap |
|---|---|---|---|
| G1 | the option's premium chart (as written) | strict ATM±1 | 15:25 |
| G2 | the option's premium chart | strict ATM±1 | 15:05 (the app's expiry square-off) |
| G3 | the index (default) | strict ATM±1 | 15:25 |
| G4 | the index | strict ATM±1 | 15:05 |
| G5 | the option's premium chart | near (₹15-30) | 15:25 |
| G6 | the index | near (₹15-30) | 15:25 |

## 5. Twins, gates, reports

- **Random twins (200 a trade):** the same day, index, contract and exit rules, with the entry at a random traded minute from
  14:00 to 15:00. Twins that find no trade are resampled from that trade's valid twins. p = share of twin totals ≥ the rule's
  total; BH over the 6 variants.
- **Gates (all must hold):**
  - ≥ 300 trades;
  - average net > 0 at 1x and 2x slippage;
  - positive in ≥ 3 of the 5 years 2022-2026;
  - BH q ≤ 0.10;
  - Boss's account stop not breached on 1 lot (drawdown above −₹40,000, no month below −₹13,000).
- **This is a single test of a stated rule, not a search.** All data is used once, with a breakdown by year and by index;
  Apr-Oct 2026 is reported apart. It was read for other questions, never for this rule.
- **Also reported:**
  - how often each filter stops a day;
  - the share of trades reaching 4x;
  - win rate, average win and loss;
  - MIDCPNIFTY apart;
  - weekly vs monthly expiries;
  - the claim "a 3x wall crossed brings an explosive move" (index points after the setup vs other expiry afternoons);
  - the 2% sizing as lots per capital (₹2, 5 and 10 lakh) by premium, as a table.
- **Outputs:**
  - code and tests: `research/hunt/r129_gamma_expiry/`;
  - write-up: `research/HUNT_R129_GAMMA_EXPIRY.md`;
  - entries: `research/experiments/entries/r129-*.yaml`;
  - RESULT.md.
