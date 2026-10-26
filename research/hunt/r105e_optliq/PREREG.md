# R105E pre-registration: buy only a liquid option (an option-liquidity check on the Donchian breaks)

Written 26 Oct 2026, committed and pushed BEFORE any R105E number. Research only.

Boss, 26 Oct: *"If we add liquidity check in this?"*, then *"Option liquidity"*. The question: does buying only when the
option itself is liquid make Breadth Donchian / R51 better? (Liquid means it traded enough just before entry, so fills
and stops are clean.)

## 0. Data and what is known

- **The lake has each option's 1-minute OHLC and volume, but no historical bid / ask.** The app's own spread check
  (half-spread <= 0.30%) is in shadow and cannot be back-tested. Liquidity is measured from the held contract's own
  trading before entry.
- **R105's engine already charges for thin options:** 1 tick of slippage at >= 20 lots traded in the 5 minutes before,
  2 ticks below that, 4 ticks with no trade. So the backtest already pays more for illiquid buys. The question is
  whether skipping them is better than paying.

## 1. Arms and periods (R105's, unchanged)

- C = R72 Breadth Donchian and B = R51 Donchian 20 + Supertrend, exits P, 1-ITM, 1 lot, R105's candidates.
- Design 20 Nov 2024 - 31 Mar 2026; walk-forward = the 4 quarters Apr 2025 - Mar 2026; final 1 Apr - 13 Oct 2026, read
  once (`results/FINAL_READ`, about the 30th read of that window); OOS 14-19 Oct, never gated.

## 2. The 3 rules (6 tests)

A candidate is skipped before the arm's walk, so a skipped signal frees the side. Each rule uses only the bought
contract's minutes BEFORE the entry row (rows er-5 .. er-1).

| id | rule |
|---|---|
| Q1 VOL20 | skip if the contract traded < 20 lots in those 5 minutes (the engine's "thin" line) |
| Q2 VOL100 | skip if it traded < 100 lots in those 5 minutes |
| Q3 LIVE5 | skip unless the contract traded in every one of those 5 minutes (no gap, no zero-volume minute: stale prices) |

## 3. Gate (as R105B; all must hold)

a. Net above the baseline in design, walk-forward and final; better in >= 3 of 4 quarters.
b. Random-skip twins (drop as many signals at random, walk again, 500 times): p < 0.05 in design and final.
c. BH q <= 0.10 over these 6 plus the earlier filter family on these arms (R105B's prior list plus R105B's 40).
d. At least 40 final trades.
e. Above the baseline without its 5 best trades.
f. Final drawdown no worse by more than 10%.

Win rate, average loss and worst day are reported next to the net. A pass may go on paper (Boss's switch, logged,
undoable, never live).

## 4. Tests

`tests/test_r105e.py`:
- each rule on hand-made volume rows (thresholds, NaN minutes, zero minutes);
- the look-ahead guard: volume at or after the entry row never changes the decision;
- the keep / walk / twin plumbing on R105B's tested helpers.
