# R143 pre-registration: the three candidates on two years nobody has tested them on (Dec 2022 - Nov 2024)

Written 31 Oct 2026, before any R143 number. Research only. **Nothing in the app or any arm changes** (Boss: no
strategy update without approval).

Boss, 31 Oct: *"Try on last 2yrs of data"*.

- **Why the earlier two years:** our arm tests already used the last two years (20 Nov 2024 - 13 Oct 2026). Those
  months were also used to find the ideas (R140 / R141), so they cannot prove them.
- **The two years before them, 1 Dec 2022 - 19 Nov 2024,** have never been used by R139-R142. They are the honest
  test of the three candidates.
  - Sep - Nov 2022 is warm-up only: indicators, never a trade.
  - The arms themselves were built after studies that may have seen some of those years. What is new is that **no
    candidate rule has ever been run on them.**

## The three candidates, frozen exactly as found (no cut is re-fitted)

| id | arm | rule | frozen cut |
|---|---|---|---|
| **FB-R72-A10** (R140) | R72 Breadth Donchian | skip entries when yesterday's range / the 20-session average daily range is above the design top-fifth cut | 1.3206 |
| **NP5-52** (R142) | R52 Open outside value | sell at fill + 5 minutes if the option's minute highs have not reached the entry open + 5% | 5 min, +5% |
| **BBX-51** (R142) | R51 Donchian 20 + Supertrend | skip after an earlier fake poke today, or a push (A1) below the cut, or a signal-bar range (A7) below the cut | A1 < 1.6274, A7 < 0.9947 |

**Reported only (no gates):** BBX-72 (cuts A1 < 1.8094, A7 < 1.0673), BB1-51 and NP10-52.

## Data and engine

- **Engine:** R115's engine and R101's arm rules, unchanged. R51's clean stores are rebuilt into a separate folder
  (`$R51_OUT/r143_old`) for Sep 2022 - 19 Nov 2024, from the same Drive lake.
- **Lots:** each date's own lot (R51's dated lot table).
- **Charges:** dated charges and R17 fills, 1 lot.
- **Before Nov 2024 the index weeklies expired on different weekdays.** The arms' own rules (expiry square-off,
  R52's no-trade on expiry) apply as written.
- **Data gaps:**
  - SENSEX options start in 2023;
  - an index with no option data on a day has no trade, as the arms already handle.
- **The engine is checked first:** today's rules on the new store reproduce the published 20 Nov 2024 - 31 Mar 2026
  trades. If the separate store cannot cover that, the check is on R72's trades rebuilt from the old store's last
  weeks against the published ones; whichever was done is written in the results.

## Gates (R140's lose-less gates, in the unseen period)

**On 1 Dec 2022 - 19 Nov 2024 as a whole:**
1. the sum of losing days is smaller than today's rules;
2. the max drawdown, worst day and worst month are no worse;
3. the arm still makes money (net > 0);
4. it beats 2,000 random twins on the sum of losing days, p < 0.05:
   - filters: drop as many candidates at random;
   - the exit: sell at a random minute between the fill and today's exit;
5. BH q <= 0.10 over the 3;
6. >= 40 trades, >= 30 that differ.

**Reported, not gated:**
- each year separately (Dec 2022 - Nov 2023, Dec 2023 - Nov 2024);
- the win rate and its change; net and its change.

**If a candidate passes here,** it has passed on data it never saw. That is the evidence Boss needs before deciding
on a paper switch. Nothing is built without Boss's approval.

## Outputs

- `r143.py`, `test_r143.py`, `results/r143.json`.
- `research/HUNT_R143_UNSEEN_TWO_YEARS.md`.
- Entries `research/experiments/entries/r143-*.yaml`.
