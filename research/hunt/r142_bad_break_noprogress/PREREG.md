# R142 pre-registration: R141's leads tested. Bad-break filters and a "no progress" exit, gated on losing less

Written 31 Oct 2026, before any R142 number. Research only. **Nothing in the app or the arms changes**; Boss, 31 Oct:
*"dont push update strategies without taking my approval, and show me the results"*.

Boss, 31 Oct: *"test the R51 bad-break filter and no-progress exit, and also try to improve all, and let me know how
could we make this loss trades to win rate"*.

**Signal source:** the index for every filter (R140 Amendment A's features). The no-progress exit reads the option's
own premium after the fill (stops, targets, trails and locks may sit on the premium; CLAUDE.md).

## The tests (22, one BH family)

**Bad-break filters.** Skip the arm's candidate at entry. On R72, R51 and Liquidity (12) plus T11 (1):

| id | skip the trade when... |
|---|---|
| BB1-{72,51,L} | the index already made a **fake poke** earlier today: a 5-min bar made a new day extreme the trade's way and closed back in its own half against it (R140A feature A11 >= 1) |
| BB2-{72,51,L} | **weak push into the entry:** the last 3 closed 5-min bars' move the trade's way, in ATR14 (A1), is in the lowest two fifths of the arm's design trades |
| BB3-{72,51,L} | **quiet signal bar:** the last closed 5-min bar's range / ATR14 (A7) is in the lowest fifth of the arm's design trades |
| BBX-{72,51,L} | any of BB1, BB2, BB3 |
| T11-51 | R51 only: entries 10:30-12:00 |

**"No progress" exit.** After the fill, if the option's minute highs from the fill minute up to minute fill + N - 1
never reach the entry minute's open x (1 + X), sell at the open of minute fill + N. The arm's own exits stay on;
whichever comes first wins. On R72, R51 and R52 (9). Liquidity is left out: R81's index no-progress exit was its
worst idea.

| id | N minutes | X |
|---|---|---|
| NP5-{72,51,52} | 5 | +5% |
| NP10-{72,51,52} | 10 | +5% |
| NP15-{72,51,52} | 15 | +10% |

## Engine, periods, gates (R140's, unchanged)

- **Engine:** R115's (R101 units, R106 walk and forced exits), parity asserted. 1 lot.
- **Periods:**
  - design: 20 Nov 2024 - 31 Mar 2026;
  - walk-forward year: 1 Apr 2025 - 31 Mar 2026;
  - final: 1 Apr - 13 Oct 2026.
- **Gates, losing less:** in each of design, the walk-forward year and the final:
  - the sum of losing days smaller;
  - max drawdown, worst day and worst month no worse;
  - the arm still in profit.

  Plus:
  - 2,000 random twins in the final, p < 0.05: random skips of as many candidates (filters), or a random sell minute
    between the fill and today's exit (exits);
  - BH q <= 0.10 over the 22;
  - >= 40 final trades, >= 30 that differ.
- **Reported for every test:** the win rate and its change, net, average win and average loss.

## Honest limits, said now

- **The bad-break filters were found by R141, which used all three periods (including the final) to call them
  "steady".** No period here is unseen for them. Even a pass shows only that they are consistent, not that they are
  real. **A pass earns a forward log on new days, nothing more.**
- **The no-progress idea** came from R141's finding that about half the lost money is in trades that never worked, in
  every period. N and X are fixed here, before any number. Its final is not fresh either; the same rule holds.
- **R142 changes no app code and no arm.** Any change would be a separate step, shown to Boss first, made only with
  Boss's approval.

## Outputs

- `r142.py`, `test_r142.py`, `results/r142.json`.
- `research/HUNT_R142_BAD_BREAK_NO_PROGRESS.md`, with a plain answer to *"how could we make the losing trades
  win"*.
- Entries `research/experiments/entries/r142-*.yaml`.
