# R144 pre-registration: the other arms. Liquidity's health on the unseen years, and the two rules that held up, tried on the arms not yet tested

Written 1 Nov 2026, before any R144 number. Research only. **Nothing in the app or any arm changes without Boss's
approval.**

Boss: *"And what about other strategies? Liquidity and other strategies, have to tested it"*.

## Where each strategy stands (from R140-R143 and earlier studies)

| strategy | status before R144 |
|---|---|
| R72, R51, R52 | full R140-R143 treatment, including the unseen Dec 2022 - Nov 2024 |
| **Liquidity 15+5** | R140-R142 filters failed; **never run on Dec 2022 - Nov 2024**; the two rules that held up never tried on it |
| S5 Trail (R128), S3-V2-E3 (R133) | already tested on 2023 - Oct 2026 by their own studies: their money was mostly in 2023-24. Not re-run here |
| ORB, Night (R3), Solo, MCX / gold arms | need their own engines; a later study (said in the write-up) |

## Part A: health check (descriptive)

Liquidity 15+5 as it runs today, on Dec 2022 - Nov 2024, from R143's older store (engine check already passed for
Liquidity: identical trades on 20 Nov - 30 Dec 2024). Reported per year:
- trades, win rate, net;
- losing days, drawdown, worst day, worst month.

## Part B: the two rules that held up, on the arms not yet tested with them (4 tests, one BH family)

| id | arm | rule | cut (fixed now) |
|---|---|---|---|
| W-51 | R51 | skip entries after a very wide day (R140's A10: yesterday's range / 20-session ADR) | >= 1.2907, R51's design top-fifth cut (R140A) |
| W-L | Liquidity | the same | >= 1.1961, Liquidity's design top-fifth cut (R140A) |
| W-52 | R52 | the same | R52's design top-fifth cut, computed by the code from R52's 20 Nov 2024 - 31 Mar 2026 trades (80th percentile) before any test number is printed |
| NP5-L | Liquidity | sell at fill + 5 min if the option's minute highs have not reached the entry open + 5% (R142's NP5; option premium, not R81's index rule) | 5 min, +5% |

- W-51 was R140A's FB-R51-A10. It missed one gate there (the walk-forward worst month). **Here it gets its first
  unseen test.**

## Gates (all must hold)

**In each of the four periods:**
- Dec 2022 - Nov 2024 (unseen);
- 20 Nov 2024 - 31 Mar 2026 (design);
- 1 Apr 2025 - 31 Mar 2026 (walk-forward);
- 1 Apr - 13 Oct 2026 (final; not fresh).

**Every period must show:**
1. the sum of losing days smaller than today's rules;
2. max drawdown, worst day and worst month no worse;
3. net:
   - in the three recent periods: net > 0 (the arm still makes money);
   - in the unseen years: net no lower than today's rules (R143 showed an arm may itself lose there).

**Plus, on the unseen years:**
- 2,000 random twins (random drops of as many candidates; random sell minutes for the exit), p < 0.05;
- BH q <= 0.10 over the 4;
- >= 40 trades and >= 30 that differ.

Win rate before and after is reported for every test and period.

## Outputs

- `r144.py`, `test_r144.py`, `results/recent.json`, `results/unseen.json`.
- `research/HUNT_R144_OTHER_ARMS.md`, with before / after tables for every period.
- Entries `research/experiments/entries/r144-*.yaml`.
- A pass is proposed to Boss, not built.
