# R103 deep, idea 1: sell only when options are expensive (volatility risk premium filter)

Written 22 Oct 2026, before any number of this idea was computed. Boss (22 Oct) agreed to test "selling only when
options are expensive" first, then "selling in the direction of your own signals". Research only: nothing trades.

## Why this could work

A seller is paid the gap between implied volatility (what options price) and the volatility that then happens. R102:
India VIX overstated NIFTY's next-month realised volatility on 81% of days (n 4,507). Every rule tested so far sold on
every day, cheap or rich. A rule that sells only when implied is high compared with recent realised volatility should
collect a fatter premium on the days it trades and skip the days when options are cheap for a reason.

**Signal source:** the filter compares an option-implied volatility with the index's own realised volatility, which
CLAUDE.md allows as context around a rule (option-chain data as a filter; the entry is at a fixed time, centred on the
index's ATM strike). Stops and the lock are on the premium.

## The filter (point-in-time, read at the entry minute)

- **RV20:** the standard deviation of the index's last 20 daily log returns, up to and including the PREVIOUS session's
  close (index_daily), times sqrt(252).
- **IV, two sources:** (a) ATMIV: the implied volatility of the ATM call and put at the entry minute, solved under the
  study's convention (r103sim.implied_vol), averaged; (b) VIX: India VIX at the entry minute (forward-filled up to 5
  minutes), / 100.
- **Ratio** = IV / RV20. Sell only when the ratio is at least **1.0, 1.25 or 1.5**. 2 sources x 3 thresholds = 6 filters.

## The cells (144, one BH family)

2 indices (NIFTY, BANKNIFTY) x 3 structures (STR naked straddle, STG naked strangle, IC iron condor; PREREG.md section 3)
x 2 exits (X3: combined stop at 100% of the credit; X3L: X3 plus the 30/20 profit lock) x 2 day sets (ALL, EXPIRY) x 6
filters. Entry 09:20, close 15:10 (15:05 on expiry days), event days skipped, one lot, the same charges, slippage and
model as PREREG.md. The unfiltered version of each cell is reported beside it as the baseline (it is not a new test).

## Gates (all must hold)

On Design A (20 Nov 2024 - 14 Jul 2026):
1. at least 40 trades;
2. net > 0, sign-flip p <= 0.05, BH q <= 0.10 over the 144 cells;
3. **the filter adds value:** a random-subset twin (1,000 draws, seed 103: the same number of days drawn at random from
   the unfiltered cell's trades) has p <= 0.05 (share of draws with a net at least the filtered net);
4. at least 3 of 4 walk-forward blocks positive;
5. max drawdown no worse than -Rs 40,000 and no month below -Rs 13,000.
On Design B (5 Aug 2021 - 19 Nov 2024):
6. net > 0 with at least 20 trades (the edge must exist in the earlier regime too).

Then, for passers only, the 15 Jul - 19 Oct 2026 holdout is read once. It is **partly seen**: the unfiltered NIFTY
expiry-day strangle and the other study's BANKNIFTY hedged structures were already read on it, and a filter selects a
subset of those days. A holdout pass therefore only promotes a rule to the forward test (PREREG_FWD.md section 3 rules,
40 forward expiries or, for ALL-day cells, 60 forward sessions), never to paper.
