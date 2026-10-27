# R122: sell the opening premium, buy it back at 10:30 (research loop, round 2)

Written 27 Oct 2026, for Boss (*"find the best strategy you can with max winrate"*).

- **Pre-registration:** `research/hunt/r122_morning_premium/PREREG.md`, commit `012da2f`, pushed before any number.
- **Code and tests:** pushed before the run (`dd172f2`).
- **Tables:** `research/hunt/r122_morning_premium/results/report.md`.
- **Experiment entries:** `research/experiments/entries/r122-u1.yaml`, `r122-u2.yaml` (both failures).
- **Research only.**
- **Signal source:** none directional (the clock); strikes from the index's ATM strike and straddle.

## Verdict for Boss

**0 of 2 pass.**

- **The idea:** R121 showed options are dearest at the open. So sell an ATM iron fly (wings 2 straddles out) on the
  09:20 close and buy it back at the 10:30 open, after the morning premium fades. Only 70 minutes of risk.
- **On normal (non-expiry) days there is no morning premium to sell.**
  - Before costs it makes about nothing: −Rs 19, −Rs 14, −Rs 68 a trade in design, walk-forward and final.
  - After 8 orders' charges it loses −Rs 230 / −Rs 260 / −Rs 355 a trade (3,491 trades).
  - Win rate 34%. Every index loses in every period.
- **On expiry days the opening premium is real, and has grown in recent years.**

  | period | trades | win rate | before costs / trade | after costs / trade | worst day |
  |---|---|---|---|---|---|
  | design 2021-23 | 372 | 46% | −Rs 39 | −Rs 238 | −Rs 7,842 |
  | walk-forward 2024-25 | 376 | 52% | +Rs 134 | −Rs 69 | −Rs 13,108 |
  | final 2026 | 104 | 62% | +Rs 337 | **+Rs 127** | −Rs 4,986 |

  - In 2026 it passes almost everything:
    - positive after costs, and at 2x slippage (+Rs 107);
    - positive without its 3 best days;
    - no month at the stop; drawdown Rs 12,221;
    - it beats the same trade at random later times (twin p 0.005).
  - **But it lost in 2021-23 and 2024-25, so it fails the first gate.** Its q is 1.0 over 9.3 million logged tests.
- **What this suggests (an observation, not a claim):** the expiry-morning premium got bigger after the late-2024
  changes. Those were the move to one weekly expiry per exchange and the bigger lots. A rule that only works in the
  newest regime cannot be proven on 104 trades of history; only new days can test it.

## What was tested

- **Every session 2021 to 19 Oct 2026, five indices** (NIFTY and BANKNIFTY from Aug 2021, FINNIFTY from Nov 2021,
  MIDCPNIFTY from Jun 2023, SENSEX from May 2023).
  - 5,440 sessions in all; 1,097 were skipped because a leg had no print at 09:21. That is mostly thin FINNIFTY and
    MIDCPNIFTY wings.
- **Entry:** on the 09:20 bar's close, sell C(K0) and P(K0); buy C(K0 + 2S) and P(K0 − 2S). K0 is the ATM strike, S
  the ATM straddle. 1 lot each; fills at the 09:21 open.
- **Exit:** the 10:30 open. A leg with no print is repriced by Black-76 at its last IV, with calendar time to expiry.
- **Costs:** R119's engine (volume-based slippage, dated charges per order).
- **Gates:** R121's, with a random twin instead of the bootstrap. Each trade was replaced by the same structure
  entered at a random bar 10:30-14:00 and held 70 minutes (drawn from 30 random rows a day).

## What it adds to the loop

- On normal days the morning premium is a myth after costs. On expiry days it is the same short-premium edge as
  R119 / R120: real, recent, and not stable across the years.
- Scoreboard: `research/LOOP_SCOREBOARD.md`.
