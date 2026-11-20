# R204 + R205: earlier breakeven, earlier time stop, Liquidity without the MIDCPNIFTY 5-minute book, and account sizes (20 Nov 2026)

Boss, 20 Nov, after the diagnosis (`research/HUNT_R202_ARMS_DIAGNOSIS.md`): *"Run 2 3 4"*.

Pre-registered in `research/hunt/r204_be_time_funds/PREREG.md` (commit 4a8950a7, before any number); code and design
results frozen in bd70e4a7 before the full run. Every table: `results/report.md`; numbers: `results/final.json`,
`decision.json`, `funds.json`. Research only: **nothing in the app changed.** Today's rules (V5), 1 lot, after real
charges and slippage, 21 Nov 2024 - 5 Oct 2026 (the `dhan-data` build; not the lake to 13 Nov).

**One honest limit first.** These ideas came from my own look at the whole record, the Apr - Oct 2026 holdout included.
So that period is a **second look**, not a fresh test; I kept the same gate so the numbers read the same way.

## Verdict for Boss

**Items 4 and 2: nothing clears the gate (0 of 10).** **Item 3: the account size matters more than any rule I tested.**

### Item 4a: breakeven once a trade is up Rs 500 a lot - it kills the winners

| arm | trades changed (design / holdout) | money, design | money, holdout | win rate, holdout | average loss, holdout |
|---|---|---|---|---|---|
| R72 | 473 / 167 | **-Rs 78,522** | -Rs 32,165 | 60.4% -> 61.2% | -Rs 1,475 -> -Rs 1,173 |
| R51 | 653 / 175 | **-Rs 68,401** | +Rs 227 | 60.4% -> 63.9% | -Rs 1,429 -> -Rs 1,157 |
| Liquidity | 214 / 58 | **-Rs 80,417** | +Rs 5,643 | 43.9% -> 48.5% | -Rs 913 -> -Rs 826 |

It does what it says (more wins, smaller average loss), but a trade that is up Rs 500 and dips is very often a trade that
then runs. On R72 in the design period it saved Rs 2,67,315 on losers and gave up Rs 3,53,848 on winners. Same story as
R176 / R179 / R186: every tighter lock costs money on these arms. **Fails.**

### Item 4b: sell at 10 or 15 minutes if the trade was never up Rs 250 a lot - worse in both periods

| arm | rule | trades changed (design / holdout) | money, design | money, holdout | winners it turned into losers (design / holdout) |
|---|---|---|---|---|---|
| R72 | 10 min | 276 / 150 | -Rs 43,124 | -Rs 25,128 | 90 / 40 |
| R72 | 15 min | 202 / 113 | -Rs 32,062 | -Rs 16,169 | 56 / 19 |
| R51 | 10 min | 408 / 177 | -Rs 29,134 | -Rs 40,734 | 107 / 46 |
| R51 | 15 min | 316 / 138 | -Rs 13,573 | -Rs 26,348 | 72 / 24 |
| Liquidity | 10 min | 82 / 54 | -Rs 12,013 | -Rs 3,099 | 15 / 11 |
| Liquidity | 15 min | 48 / 29 | -Rs 19,603 | +Rs 700 | 6 / 4 |

The slow trades look like dead weight in hindsight (they lost Rs 1.86 lakh on R72), but a third of the trades that had
not moved by minute 10 went on to win. Cutting them at random minutes did at least as well (twin p 0.96 - 1.00).
**Fails**, as R142's no-progress exit did.

### Item 2: Liquidity without the MIDCPNIFTY 5-minute book - no case for switching it off

- The book made **+Rs 8,177 in the design period** (212 trades) and **-Rs 7,765 in Apr - Oct 2026** (81 trades): flat,
  not broken. Without it the holdout drawdown is Rs 15,301 smaller and the worst month -Rs 18,889 instead of -Rs 32,670,
  but random skips of 81 trades did as well 28% of the time. **Fails** (4 items).
- **Liquidity from 1 April 2026:** 330 trades, 43.9% wins, +Rs 11,087, drawdown Rs 59,441.

  | month | trades | win rate | net |
  |---|---|---|---|
  | Apr 2026 | 54 | 33.3% | -Rs 32,670 |
  | May 2026 | 47 | 40.4% | -Rs 14,657 |
  | Jun 2026 | 51 | 47.1% | +Rs 707 |
  | Jul 2026 | 66 | 45.5% | +Rs 23,012 |
  | Aug 2026 | 53 | 41.5% | +Rs 2,100 |
  | Sep 2026 | 50 | 58.0% | +Rs 33,938 |
  | 1 - 5 Oct 2026 | 9 | 33.3% | -Rs 1,343 |

  So I correct what I said in the diagnosis: Liquidity is not steadily fading. It had two bad months (-Rs 47,327) and
  then made Rs 58,414 from June to 5 October. It still lives on a few big days.

### Item 3: what your account size can really take (R205)

All three arms, 1 lot each, one shared pot of money, whole span (22.5 months). A trade the free cash cannot pay for is
not taken; R72 gets the cash first on a shared break, then Liquidity, then R51.

| account | trades taken | not taken | net | max drawdown | drawdown as a share of the account | worst month |
|---|---|---|---|---|---|---|
| Rs 56,000 | 3,830 | 2,357 | Rs 2,93,761 | Rs 83,161 | **148%** | -Rs 35,493 |
| Rs 1,00,000 | 5,077 | 1,110 | Rs 4,73,505 | Rs 1,27,843 | **128%** | -Rs 33,076 |
| Rs 1,50,000 | 5,736 | 451 | Rs 6,77,031 | Rs 1,44,633 | 96% | -Rs 45,412 |
| Rs 2,00,000 | 6,016 | 171 | Rs 7,60,124 | Rs 1,50,211 | 75% | -Rs 55,092 |
| Rs 3,00,000 | 6,175 | 12 | Rs 7,65,856 | Rs 1,48,047 | 49% | -Rs 74,463 |
| Rs 5,00,000 | 6,187 | 0 | Rs 7,65,411 | Rs 1,48,047 | 30% | -Rs 76,231 |

- **Below about Rs 1.5 lakh the drawdown is bigger than the account.** The table assumes the account is topped back up
  every morning; a real account of Rs 56,000 or Rs 1 lakh running all three arms at 1 lot would have been emptied in a
  bad stretch before the good months came. The profit column for those sizes is therefore not reachable.
- About **Rs 3 lakh** is where almost every trade is funded and the worst drawdown is half the account; **Rs 5 lakh**
  for under a third.
- **R51 with a small account loses money**, because R72 takes the shared breaks first and R51 is left with its own:

  | account | R51's net, all three running | all three arms: net / drawdown | R72 + Liquidity only: net / drawdown |
  |---|---|---|---|
  | Rs 56,000 | **-Rs 38,194** (1,496 trades taken) | Rs 2,93,761 / Rs 83,161 | Rs 3,32,502 / Rs 90,902 |
  | Rs 1,00,000 | +Rs 38,682 (2,053) | Rs 4,73,505 / Rs 1,27,843 | Rs 4,67,084 / Rs 1,03,339 |
  | Rs 1,50,000 | +Rs 1,68,325 (2,460) | Rs 6,77,031 / Rs 1,44,633 | Rs 5,02,302 / Rs 1,03,957 |
  | Rs 3,00,000 | +Rs 1,89,543 (2,812) | Rs 7,65,856 / Rs 1,48,047 | Rs 5,74,045 / Rs 1,05,406 |

  Up to about Rs 1 lakh, R51 adds risk and no money. From about Rs 1.5 lakh it adds money, as a second lot on R72's breaks.

Limits of this replay: 1 lot a trade (your lots setting and the 3-2-1 lots cut are not modelled); a refused trade does
not free the arm for its next signal; cash resets each morning; data to 5 Oct.

## My recommendation

1. **Do not build the earlier breakeven or the earlier time stop.** They fail clearly and for the same reason as every
   earlier lock test: these arms earn on the trades that dip and then run.
2. **Leave the MIDCPNIFTY 5-minute book on.** Nothing here supports switching it off.
3. **Size is the real lever.** Tell me the money actually in the account and your lots setting and I will re-run this
   replay for exactly that. If it is under about Rs 1.5 lakh, the honest advice is fewer arms live (R72 first; R51 adds
   nothing at that size) rather than any new exit rule.
