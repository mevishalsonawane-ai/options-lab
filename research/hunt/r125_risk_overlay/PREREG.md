# R125 pre-registration: fit R123's expiry-day straddle inside Boss's limits (research loop, round 5)

Written 27 Oct 2026, **before any R125 number was computed** (pushed alone on `claude/r117-zero-hero-lock` before the
code's first run). Research only; nothing in `android/**` changes; nothing goes to paper or live.

## Why, and what is honest about it

- R123 V2 (sell the ATM straddle at 09:30 on expiry days, close it when the index moves 1.5 straddles, else 15:05) is
  the best rule of R117-R124: positive after costs in every period (+Rs 277 / +668 / +273 a trade, 889 trades). It
  failed Boss's limits mostly because five indices were traded at once and their losses stacked in the same months
  (3 months of 2026 at or below -Rs 13k, drawdown Rs 44.7k).
- R125 tests two risk rules aimed only at that, keeping R123 V2's trades unchanged.
- **This is post hoc.** R123's trade list, by day and period, is known; the risk rules are chosen to fit Boss's stated
  limits (half the monthly stop), not fitted to the data, but the data is not pristine. A pass here would still need a
  frozen forward paper test before any use (CLAUDE.md).
- **Boss's BH rule:** with m of about 9.3 million earlier tests, a pass needs p near 1e-8. It is reported plainly; q
  within the loop's own rounds (R121-R125, 10 tests) is also reported, not a gate.

**Signal source:** R123's: the stop reads the index (the default); the entry is the clock.

## Data

R123's trade lists (`r123study.py`, cached; R98's expiry days, lake to 19 Oct 2026), recomputed by the same code if
missing. Periods: DESIGN to 31 Dec 2023; WALK-FORWARD 2024-25; FINAL 1 Jan - 19 Oct 2026.

## The rules (two tests)

- **Z1, one trade a day.** If more than one index expires on a day, take only the first in the fixed liquidity order
  NIFTY, SENSEX, BANKNIFTY, FINNIFTY, MIDCPNIFTY. Every other R123 V2 trade is kept.
- **Z2, Z1 plus a monthly circuit breaker.** Trades are taken in date order. Once the month's running total (Z1's
  trades that month, after costs) is at or below -Rs 6,500 (half Boss's -Rs 13,000 monthly stop), no more trades that
  calendar month.

## Gates (all needed; never relaxed)

1. Mean net per trade > 0 in DESIGN, WALK-FORWARD and FINAL.
2. FINAL > 0 without its 3 best days, and FINAL mean > 0 at 2x slippage (R123's 2x trade list, same rules).
3. Random twin, FINAL, 2,000 twins (seed 125): from R123 V2's FINAL trades, drop at random as many trades as the rule
   dropped (Z1: one random trade kept per multi-expiry day; Z2: the same number of trades dropped at random from Z1's
   book). p = (1 + twins >= the rule's FINAL total) / 2,001 (normal tail when none). p < 0.05.
4. Benjamini-Hochberg q <= 0.10, m = every logged family to 27 Oct 2026 + R125's 2.
5. At least 40 FINAL trades.
6. Boss's limits, FINAL AND over the whole 2021-26 record: no month at or below -Rs 13,000; max drawdown < Rs 40,000.

Reported: trades, win rate, mean, total, worst day, max drawdown, months at the stop, trades skipped by each rule.

## What a pass would mean

Because the data is not pristine, a pass would mean only: Boss may choose a FROZEN forward paper test of Z1 / Z2 (a
separate labelled paper arm, logged, undoable in one tap), as FT3 was. Never live from R125. Entries:
`research/experiments/entries/r125-*.yaml`.
