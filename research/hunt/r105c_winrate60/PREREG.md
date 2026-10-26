# R105C pre-registration: a 60% win rate on Breadth Donchian and R51, even at less profit

Written 26 Oct 2026, committed and pushed BEFORE any R105C rule is priced. Research only: no `android/**`.

Boss, 26 Oct: *"Make the winrate to 60%, even the profit is less and provide me results."*

## 0. What is already known (not repeated)

- **R106 (26 Oct, `claude/r106-small-targets`) tested fixed RUPEE targets** (+Rs 500 / 1,000 / 1,500, with and without
  a 1:1 stop, and 5 trades x Rs 1,000) on Liquidity, R72, R51 and R52.
  - R52 reached 63% at +Rs 1,500 and still made Rs 31,274 in the final.
  - R72 / R51 reached 55-67% but lost money.
  - Liquidity never reached 60%.
- **R65 tested trails** (win rate 53-54%, less profit).
- **Not yet tested: a PERCENT-of-premium target on the Donchian arms with today's paper exits.** A percent target
  scales with the premium; a rupee target is +4% on BANKNIFTY but +11% on NIFTY. Also untested: a target with
  breakeven at half-way.

So R105C covers R72 and R51 + Supertrend (R51T) with percent targets. For Liquidity and R52 it reports R106's results.

Disclosure: the final window (1 Apr - 13 Oct 2026) has been read by about 27 studies. It is weak evidence.

## 1. Arms and baseline (R105's engine and candidates, unchanged)

| arm | baseline (paper exits P: -15%, L25, 30/60/100, 30 min, 15:15) | design | final |
|---|---|---|---|
| C = R72 Breadth Donchian | 4 of 5 indices break, 1-ITM | 3,071 / 47% / Rs 2,16,929 | 1,202 / 46% / Rs 1,71,366 |
| B = R51T Donchian 20 + Supertrend | as paper | 4,208 / 44% / Rs 50,425 | 1,670 / 45% / Rs 1,34,743 |

Signal on the index (unchanged); the target is a limit on the option premium (allowed by CLAUDE.md). Trades re-walk the
book (an earlier exit frees the side, as the app would).

## 2. The 12 rules per arm (24 tests), each added to exits P

- **Tk:** sell the whole lot at +k% of the fill (a limit, R64's target fill), for k = 5, 8, 10, 12, 15, 20.
- **TkB:** Tk, plus the stop moves to breakeven (fill + round-trip charges) once the premium is +k/2%.

## 3. Selection on design only, then one confirmation

For each arm:
1. Among the 12 rules, pick the one with the highest DESIGN net whose design win rate is >= 60%.
2. If none reaches 60% in design, pick the one with the highest design win rate, and report that 60% was not reached.
3. The pick is written to `results/pick.json` and committed BEFORE the final read.

**Result for Boss, per arm.** The pick's win rate and net in design, walk-forward (Apr 2025 - Mar 2026) and final,
next to today's rules, with trades, average win and loss, worst day and max drawdown.

**Verdict:**
- "meets 60%" only if the pick's win rate is >= 60% in walk-forward AND in the final, AND its net is > 0 in both;
- "60% but losing" if the win rate holds but the net is <= 0;
- otherwise "not reached".

This is a request to trade profit for win rate, so there is no "better than today" gate. A pick that meets 60% can go
on paper only if Boss switches it on (logged, undoable, never live).

All 24 rules are reported (design all; final all). BH is not used, because no claim of an edge is made: the claim is
only "this rule wins >= 60% of the time with a positive net", read on the one confirmation period.

## 4. Tests

`tests/test_r105c.py`:
- the rule table;
- the target and half-way breakeven fills (R105's engine);
- the selection rule (ties, nobody at 60%);
- the verdict;
- the read-once final.

## Amendment 1 (26 Oct 2026, pushed before any amendment number; the original 24 rules were already read)

Boss, after the first R105C results: *"Add a stoploss of 10%."*

- **6 more rules per arm (12 tests).** S10Tk: the stop is -10% of the premium instead of -15%, plus a limit target at
  +k%, for k = 5, 8, 10, 12, 15, 20. The rest of exits P (L25, the ladder, 30 minutes, 15:15) is unchanged.
- **Also S10 alone (no target), as a reference.** R64 already tested it as A3 on plain Donchian.
- **The same selection, run separately on these 6.** The highest design net with design win >= 60%, else the highest
  design win. It is fixed in `results/pick_s10.json` before their final read.
- **The same verdict.**
- **Disclosure.** The final window was already read for the 24 original rules. These 6 are read once in it, after
  their own design pick.

## Amendment 2 (26 Oct 2026, pushed before any Amendment 2 number)

Boss: *"Add profit as 15% and loss as 10%, and test multiple combinations, and check what's the output."*

- **Grid, on R72 and R51T.** Stop s in {5, 7.5, 10, 12.5, 15}% of the premium × target t in {10, 15, 20, 25, 30, none}%
  of the premium. That is 30 combinations per arm, 60 in all. Boss's pair is s = 10, t = 15.
- **Everything else stays exits P:** L25, the 30/60/100 ladder, out after 30 minutes, square-off 15:15. Same engine,
  candidates and re-walk.
- **Some cells repeat earlier rules:** s = 15 with t = 10 / 15 / 20 / none are R105C's T10 / T15 / T20 and today's
  rules; s = 10 with t = 10 / 15 / 20 / none are Amendment 1's. They are recomputed in the grid for one consistent
  table.
- **Reported for every cell:** trades, win rate, net, average win / loss, worst day and max drawdown, in design,
  walk-forward and final. Breakeven win rate = average loss / (average win + average loss).
- **This is a description for Boss, not an edge test.** No twin, no BH. A cell is marked "better than today" only if
  its net is higher than today's rules in design, walk-forward AND final; it is not proposed for paper on that alone.
- **Disclosure:** the final window is read again (about the 29th time).
