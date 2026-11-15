# R176: the Rs 500 step trail (per lot), last year of data

**Boss, in short (15 Nov 2026).** I replayed the last year the data covers, **20 Oct 2025 - 19 Oct 2026** (247 trading
days), on Liquidity 15+5, R72 Breadth Donchian and R51 Donchian 20 + Supertrend. I ran each at 1, 2 and 3 lots, with
and without your new stop rule, and with and without the R151 picker. All money is after real Zerodha charges and
slippage; every stop is sold as a real stop order, below its level, not at it.

The rule: a stop at -Rs 500 a lot at entry; once the trade is +Rs 1,000 a lot, the stop sits Rs 500 a lot under each
Rs 500-a-lot step of the best profit; every existing exit stays.

**It failed every gate on every arm at every lot size.** Today's rules made money on all three arms; with the new rule,
R72 and R51 lose money, and Liquidity makes almost nothing.

Net over the year (today's rules -> with the new rule):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| Liquidity 15+5 | Rs 1.18 L -> **-Rs 15k** | Rs 2.67 L -> **Rs 2k** | Rs 4.16 L -> **Rs 18k** |
| R72 Breadth Donchian | Rs 2.98 L -> **-Rs 1.32 L** | Rs 7.02 L -> **-Rs 1.53 L** | Rs 11.05 L -> **-Rs 1.80 L** |
| R51 Donchian 20 | Rs 2.21 L -> **-Rs 1.76 L** | Rs 5.85 L -> **-Rs 2.03 L** | Rs 9.47 L -> **-Rs 2.35 L** |
| **All three** | **Rs 6.37 L -> -Rs 3.23 L** | **Rs 15.54 L -> -Rs 3.54 L** | **Rs 24.67 L -> -Rs 3.97 L** |

With the R151 picker on R72 and R51, as live today (Liquidity has no picker):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| R72 + picker | Rs 3.72 L -> **Rs 13k** | Rs 7.92 L -> **Rs 76k** | Rs 12.13 L -> **Rs 1.39 L** |
| R51 + picker | Rs 2.93 L -> **-Rs 62k** | Rs 6.49 L -> **-Rs 56k** | Rs 10.04 L -> **-Rs 51k** |
| **All three** | **Rs 7.83 L -> -Rs 65k** | **Rs 17.09 L -> Rs 22k** | **Rs 26.33 L -> Rs 1.07 L** |

**Why it loses.**
- The Rs 500 entry stop is only about 7-8 premium points. It was the exit on 60% of all trades (3,838 of 6,348, all arms,
  1 lot), against today's -15% stop, which is about twice as far away.
- The win rate falls from 45-46% to 34-36%.
- Losses get smaller (average Rs 1,230 -> Rs 637 at 1 lot), but wins shrink about as much (Rs 1,744 -> Rs 1,041),
  because the step trail sells good trades on the first Rs 500 pull-back, and there are far fewer wins.
- The same per-lot rule scales with lots, so more lots do not fix it: R72 and R51 lose at 1, 2 and 3 lots.

**What it does do.**
- **Smaller single bad days.** Worst day for all three arms: 1 lot -Rs 54k -> -Rs 24k; 3 lots -Rs 1.59 L -> -Rs 69k.
- **But the losses keep adding up.** Max drawdown, all three arms at 1 lot: Rs 1.59 L -> **Rs 3.50 L**; at 3 lots
  Rs 4.33 L -> Rs 6.85 L. Far past the account stop rule (-Rs 40k drawdown / -Rs 13k a month).
- Note: today's rules also break the account stop rule at these lots on this year (e.g. all three at 1 lot, worst month
  -Rs 1.29 L), so that gate alone would fail them too.

**Honest notes.**
- You asked for the last year only (Amendment A, written before any number). One year is a short sample, and Apr - Oct
  2026 has been read by many studies already.
- The random-exit twins (500 per test) all beat the rule (p = 1.0 everywhere; BH q = 1.0 with 3,979 tests counted). In
  this harness the twins re-sell only the trades the rule cut early, mostly losing trades, so they do well; R152's
  stop-only rule showed the same.
- This rule is being built anyway, by your choice. Nothing in the app depends on this study. If you want to keep it,
  one option is to use it only with the R72 picker, the one place it stayed positive at every lot size this year
  (still far below today's rules).
