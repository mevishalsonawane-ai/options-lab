# R105C: a 60% win rate on Breadth Donchian and R51, even at less profit (and with a 10% stop)

26 Oct 2026. Research only (no `android/**`). Pre-registered in `research/hunt/r105c_winrate60/PREREG.md` (1a2a9e5),
with Amendment 1 for Boss's 10% stop (0d414bb). Both were pushed before their numbers. Each pick was fixed on design
data and committed before its one final read. Code and tests: `research/hunt/r105c_winrate60/`.

Boss, 26 Oct: *"Make the winrate to 60%, even the profit is less and provide me results"*, then *"Add a stoploss of
10%."*

## The answer

**60% can be reached, but only by selling at +5%, and then the arms lose money.** With the 10% stop added, even that
falls just short of 60%.

| Breadth Donchian (R72), final period 1 Apr - 13 Oct 2026, 1 lot | trades | win rate | profit | avg win / avg loss | worst day | max drawdown |
|---|---|---|---|---|---|---|
| **today's rules** (-15% stop, locks, 30 min) | 1,202 | 46% | **Rs 1,71,366** | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Rs 61,884 |
| sell at +5% (-15% stop) | 1,273 | **61%** | **-Rs 1,18,373** | Rs 705 / -Rs 1,338 | -Rs 23,550 | Rs 1,48,087 |
| sell at +10% | 1,251 | 51% | -Rs 30,224 | Rs 1,188 / -Rs 1,294 | -Rs 22,604 | Rs 1,29,428 |
| sell at +20% | 1,221 | 46% | Rs 1,16,363 | Rs 1,627 / -Rs 1,228 | -Rs 22,571 | Rs 48,469 |
| **-10% stop only** | 1,203 | 43% | Rs 1,45,098 | Rs 1,776 / -Rs 1,148 | -Rs 18,996 | Rs 55,184 |
| -10% stop + sell at +5% | 1,273 | 58% | -Rs 1,30,860 | Rs 716 / -Rs 1,243 | -Rs 18,171 | Rs 1,50,241 |
| -10% stop + sell at +20% | 1,221 | 44% | Rs 1,06,630 | Rs 1,649 / -Rs 1,147 | -Rs 18,996 | Rs 43,043 |

| R51 Donchian 20 + Supertrend, final period | trades | win rate | profit | avg win / avg loss | worst day | max drawdown |
|---|---|---|---|---|---|---|
| **today's rules** | 1,670 | 45% | **Rs 1,34,743** | Rs 1,651 / -Rs 1,197 | -Rs 21,578 | Rs 81,683 |
| sell at +5% | 1,758 | **61%** | **-Rs 1,38,114** | Rs 723 / -Rs 1,349 | -Rs 22,092 | Rs 1,50,480 |
| **-10% stop only** | 1,676 | 42% | Rs 1,01,867 | Rs 1,671 / -Rs 1,123 | -Rs 17,777 | Rs 66,519 |
| -10% stop + sell at +5% | 1,759 | 59% | -Rs 1,32,110 | Rs 734 / -Rs 1,245 | -Rs 17,318 | Rs 1,43,510 |
| -10% stop + sell at +20% | 1,696 | 43% | Rs 55,072 | Rs 1,582 / -Rs 1,127 | -Rs 17,777 | Rs 59,949 |

Design (20 Nov 2024 - 31 Mar 2026) said the same:
- sell at +5%: 65% wins, but -Rs 1,69,192 on R72 and -Rs 3,01,570 on R51;
- with the 10% stop: 62% wins, -Rs 1,83,242 and -Rs 3,20,415.

Every one of the 36 rules is in `results/*.json` and `research/experiments/entries/r105c-*.yaml`.

## Why a high win rate loses money here

- **A +5% sale wins small and loses big.** The average win falls to about Rs 700, while the average loss stays about
  Rs 1,250-1,350. To break even, a trade with a Rs 700 win and a Rs 1,300 loss must win 65% of the time. The arms
  reach 61%.
- **The arms live on a few big winners.** Today's rules lose more often than they win (46%), but the average win
  (Rs 1,748) is much bigger than the average loss (Rs 1,217). A small target sells those big winners early.
- **A 10% stop makes the losses smaller (Rs 1,148 vs Rs 1,217), but it also lowers the win rate.** Ordinary
  wiggles hit it more often. With a +5% target the win rate falls from 61% to 58-59%. On its own the 10% stop is the
  least harmful change (R72 Rs 1,45,098 vs Rs 1,71,366), with a smaller worst day (-Rs 18,996 vs -Rs 22,571), but it
  does not raise the win rate. R64 found the same on plain Donchian.
- **Breakeven half-way to the target lowers the win rate further** (39-42%). Breakeven exits count as small losses
  after charges.

## The other arms (from R106, same day)

- **R52 Open outside value is the one arm that reaches 60% and stays profitable.** With a +Rs 1,500 target, the final
  period gave 293 trades, 63% wins, +Rs 31,274 (today's rules: 52%, Rs 48,612).
- Liquidity 15+5 never reached 60% with a profit.

See `research/HUNT_R106_SMALL_TARGETS.md` on branch `claude/r106-small-targets`.

## What this means for Boss

- **To have 60% winners on Breadth Donchian or R51, you would accept losing about Rs 1.2-1.4 lakh per half-year
  instead of making Rs 1.3-1.7 lakh.** Nothing is switched on.
- **If a calmer record matters more than the win rate,** two options cost less:
  - **The 10% stop alone** on R72 keeps most of the profit (Rs 1.45 lakh vs Rs 1.71 lakh). It gives a smaller worst day
    and drawdown, at 43% wins.
  - **A +20% target** keeps Rs 1.16 lakh with the smallest drawdown (Rs 48k), at 46% wins.

  Neither was tested against random exits (no edge is claimed). Either could go on paper only if Boss switches it on.
- **A 60% win rate with profit is only available on R52,** with a +Rs 1,500 target (R106).

## Honest limits

- **The final window has been read by about 28 studies.** It is weak evidence, though the direction (high win rate =
  loss) is the same in design and walk-forward.
- **The 10% stop rules were added after Boss saw the first results.** They were pre-registered as Amendment 1 and read
  once.
- **1 lot.** The baselines reproduce R75 to the rupee. The engine and fills are R105's (R64 simulator, next-minute
  fills, limit target fills, stops on the minute low with gap fills, dated charges).
- **Signal on the index.** Stops and targets are on the option premium.
