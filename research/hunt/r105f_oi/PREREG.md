# R105F pre-registration: open-interest checks on the Donchian breaks

Written 26 Oct 2026, committed and pushed BEFORE any R105F number. Research only.

Boss, 26 Oct: *"What if we [use] OI data?"*

## 0. Signal source and what is known

- **The signal stays on the index** (Donchian 20 on 5-minute index bars; R72's breadth; R51's Supertrend). OI is used
  only as context around it (CLAUDE.md allows this). No entry comes from an option chart.
- **About 3,500 chain tests have been run earlier, all failed or shadow-only** (R74's map): PCR, OI walls, max pain,
  GEX, skew (H26, H38, OI_FILTER, R15, R24, R26, R28, R36, R37, IV1, R47).
  - R47 C4, "BANKNIFTY high PCR -> follow", is the only rule that stayed positive everywhere. It sits in shadow (q 0.50).
  - OI_FILTER's checks were run on Liquidity and ORB only. No OI check was ever run on the Donchian / Breadth Donchian
    breaks.
- **Data.** The lake's `options_rolling_1m` WEEK files carry each minute's OI for ATM-10 .. ATM+10 calls and puts (the
  nearest weekly / monthly expiry the arms trade). NSE updates OI every few minutes, so "OI at minute m" = the last
  printed value at or before m.

## 1. Arms, periods, engine (R105E's, unchanged)

- C = R72 Breadth Donchian, B = R51 Donchian 20 + Supertrend, exits P, 1-ITM, 1 lot, R105's candidates and prices.
- Periods:
  - design 20 Nov 2024 - 31 Mar 2026;
  - walk-forward = 4 quarters Apr 2025 - Mar 2026;
  - final 1 Apr - 13 Oct 2026, read once (about the 31st read);
  - OOS 14-19 Oct.

## 2. The 4 checks (8 tests). A candidate failing a check is skipped before the walk.

Everything is read at the minute before entry (row er-1) or earlier, on that day's chain.

| id | check | idea |
|---|---|---|
| O1 PCR | PCR = total put OI / total call OI over ATM-5 .. ATM+5. Calls only if PCR >= 1.0; puts only if PCR <= 0.8; between, both sides | "put writers support the market" (Indian practice, R47 C4) |
| O2 WRITERS | the change in OI since 09:20 over ATM-2 .. ATM+2. A call is kept only if the put OI added >= the call OI added; a put is kept only if the call OI added >= the put OI added | writers add on the side they expect to hold |
| O3 OWNOI | skip if the bought contract's OI rose by more than 5% over the last 15 minutes | fresh writing into the option we buy (OI_FILTER's "own OI piling") |
| O4 WALL | skip a call if the call strike with the largest OI among ATM .. ATM+5 is within 0.3% above the index; skip a put if the largest put-OI strike among ATM-5 .. ATM is within 0.3% below | an OI wall caps the break |

Unknown inputs (no OI printed yet) never block: the trade is taken as today.

## 3. Gate (R105E's; all must hold)

- better net in design, walk-forward (3 of 4 quarters) and final;
- random-skip twins p < 0.05 in design and final;
- BH q <= 0.10 over these 8 + R105E's family (+ R105E's 6);
- >= 40 final trades;
- better without the 5 best trades;
- final drawdown no worse by more than 10%.

A pass may go on paper (Boss's switch, logged, undoable, never live).

## 4. Tests

`tests/test_r105f.py`:
- each check on hand-made OI arrays (thresholds, unknowns, ties, sides);
- "OI at m" = last value at or before m;
- the look-ahead guard: OI at or after the entry row never changes a decision.
