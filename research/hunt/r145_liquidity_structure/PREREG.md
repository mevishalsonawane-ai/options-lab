# R145 pre-registration: higher-high / lower-low structure, the structure shift and reversal logic on Liquidity 15+5

Written 1 Nov 2026, before any R145 number. Research only. **Nothing in the app or the arm changes.**

Boss: *"Also check when we use higher high/lower low equity shift, reversal on liquidity, what are the results"*.

**Already known (R140, not repeated as tests):** skipping Liquidity trades against the 15-min or 5-min Dow trend
(T15-L, T5-L) cut losses only as much as random skipping (final p 0.60 / 0.59). R145 only **reports** them on the
unseen Dec 2022 - Nov 2024.

## The 5 tests (one BH family)

**Signal source:** the index for every structure rule. The arm buys its own option with its own premium exits:
R101's `E.run_day` with `fam_liq.EX`, already checked to reproduce R101's prices.

| id | rule |
|---|---|
| **M15-L** | **structure-shift exit:** after the fill, the first 15-min index bar of the day that closes beyond the last 15-min swing point against the trade (below the last swing low for a call, above the last swing high for a put). Sell at the next minute's open, or at Liquidity's own forced exit if that comes first (R140's `mss_row`) |
| **M5-L** | the same on 5-min bars |
| **REV-S** | **stop-and-reverse after a stop:** when a Liquidity trade exits at its stop (why = stop), buy the opposite ATM option at the next minute (ATM = the last index close, rounded to the step), entry at or before 15:00. It is priced by Liquidity's own engine and premium exits (no forced index row). One reverse per trade. A reverse leg is skipped if the same book's next trade starts before the leg ends; the arm's own trades never change |
| **REV-F** | the same after a **failed breakout**: Liquidity's own rule exit (why = rule_exit) that lost money |
| **F-L** | **flip:** every Liquidity signal taken the other way. The opposite ATM option at the same minute, Liquidity's premium exits, no forced index row (the arm's index rule is one-sided); the arm's book rules unchanged |

## Periods, gates, twins

- **Periods:**
  - unseen Dec 2022 - Nov 2024 (R143's older store; engine check passed for Liquidity);
  - 20 Nov 2024 - 31 Mar 2026;
  - Apr 2025 - Mar 2026;
  - Apr - 13 Oct 2026.
- **Gates (R144's), in every period:**
  - losing days smaller;
  - drawdown, worst day and worst month no worse;
  - net > 0 in the recent periods, and no lower than today's in the unseen years.
- **Plus, on the unseen years:** better than random, p < 0.05; BH q <= 0.10 over the 5; >= 40 trades, >= 30 that
  differ.
- **Twins:**
  - **M15-L, M5-L:** 2,000 random sell minutes (R144's Liquidity exit twin);
  - **REV-S, REV-F:** 200 twins; each reverse leg's entry is moved to a uniformly random minute between the trigger
    and 15:00 (the same day, side and engine). p is on the total net of the reverse legs;
  - **F-L:** 200 twins; each flipped trade's entry is moved to a random minute between its signal and 15:00. p is on
    the flipped trades' total net.
  - 200 twins for the reversal tests because each twin re-prices every leg; this is said here so it is not chosen
    later.
- **Reported for every test and period:** before / after trades, win rate, net, losing days, drawdown, worst day,
  worst month.

## Outputs

- `r145.py`, `test_r145.py`, `results/recent.json`, `results/unseen.json`, `results/decision.json`.
- A section in `research/HUNT_R144_OTHER_ARMS.md`, or its own `research/HUNT_R145_LIQUIDITY_STRUCTURE.md`.
- Entries `research/experiments/entries/r145-*.yaml`.
