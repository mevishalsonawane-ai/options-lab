# R186: a hard fixed profit (Rs 1,000 - 1,500 a lot) and a Rs 500 step lock on Liquidity, R51 and R72

Boss, 16 Nov 2026: *"I want you to check if i had a hard 1-1.5 fix profit what will be effect on liquidity,r51,r72"*, and
*"the profit of a lot is 1000, then ends of negative, then its 1.5k again negative, again 2k again negative, this has
happened every single day"*.

**Short answer (all eight rules tested, none helps): a hard target cuts the profit by about half to nine tenths on every arm. It raises the win rate (to 60-66%
on R72 / R51), but the wins become small (about Rs 1,000) while the losses stay the same size (about Rs 1,500). The Rs 500
step lock is less bad but still costs Rs 81k - 1.12 L a year. Your observation is real (about 1 in 5 losing trades had been
+Rs 1,000 first), but the trades that keep running pay for it. Nothing passed. Keep today's rule.**

How it was tested: the rule in the app today (each arm's own stop + breakeven at +10%; the picker on R72 / R51) is the
base. Each new exit was added on top; every other exit stayed; the first exit wins. Last year, 20 Oct 2025 - 19 Oct 2026,
1 lot, after Zerodha charges and slippage. Signals on the index; only the option's exit changes. The target sells at the
price that nets the amount after charges (or at the opening price if the option jumps over it).

## The year, 1 lot

| | today (base) | target Rs 1,000 | target Rs 1,250 | target Rs 1,500 | step lock Rs 500 | breakeven at +Rs 1,000 |
|---|---|---|---|---|---|---|
| **R72** trades | 1,069 | 1,123 | 1,113 | 1,105 | 1,097 | 1,076 |
| win rate | 56.6% | 65.6% | 62.6% | 60.3% | 64.4% | 59.6% |
| net | **Rs 3.25 L** | Rs 1.20 L | Rs 1.38 L | Rs 1.52 L | Rs 2.13 L | Rs 2.66 L |
| worst month | -Rs 18,110 | -Rs 21,942 | -Rs 29,976 | -Rs 33,645 | -Rs 13,690 | -Rs 20,310 |
| max drawdown | Rs 39,246 | Rs 43,128 | Rs 38,453 | Rs 44,259 | Rs 42,286 | Rs 47,515 |
| **R51** trades | 1,367 | 1,497 | 1,472 | 1,448 | 1,430 | 1,376 |
| win rate | 55.5% | 64.2% | 60.6% | 59.2% | 62.7% | 58.4% |
| net | **Rs 2.50 L** | Rs 81,803 | Rs 1.03 L | Rs 1.46 L | Rs 1.63 L | Rs 2.06 L |
| worst month | -Rs 20,990 | -Rs 34,418 | -Rs 27,679 | -Rs 30,532 | -Rs 29,871 | -Rs 16,923 |
| max drawdown | Rs 36,021 | Rs 54,177 | Rs 53,811 | Rs 50,598 | Rs 54,101 | Rs 64,840 |
| **Liquidity** trades | 688 | 691 | 691 | 691 | 690 | 688 |
| win rate | 41.3% | 49.6% | 46.3% | 45.1% | 48.7% | 43.7% |
| net | **Rs 1.00 L** | Rs 10,282 | Rs 8,348 | Rs 11,998 | Rs 19,404 | Rs 98,239 |
| worst month | -Rs 37,773 | -Rs 28,680 | -Rs 35,294 | -Rs 35,519 | -Rs 30,655 | -Rs 32,670 |
| max drawdown | Rs 67,497 | Rs 47,663 | Rs 57,810 | Rs 56,991 | Rs 58,857 | Rs 59,441 |

Today's plain rules without the picker or the breakeven (for comparison): R72 2,281 trades, 46.3%, Rs 2.98 L; R51 3,084,
45.1%, Rs 2.21 L; Liquidity 688, 39.2%, Rs 1.18 L.

## Apr - Oct 2026 (the cleaner test), 1 lot

| net | today (base) | T 1,000 | T 1,250 | T 1,500 | step lock | BE at +1,000 |
|---|---|---|---|---|---|---|
| R72 (604 base trades) | Rs 1.67 L | Rs 25,536 | Rs 41,394 | Rs 43,037 | Rs 1.11 L | Rs 1.36 L |
| R51 (655) | Rs 1.45 L | Rs 5,363 | Rs 22,973 | Rs 41,302 | Rs 57,450 | Rs 1.02 L |
| Liquidity (357) | Rs 6,432 | -Rs 26,449 | -Rs 33,815 | -Rs 30,885 | -Rs 21,862 | Rs 6,424 |

## Why the target loses money

- **Wins shrink, losses do not.** R72 with the Rs 1,000 target: average win Rs 1,011 (today Rs 1,675), average loss
  Rs 1,618 (today Rs 1,484). At those sizes about 62% wins only breaks even.
- **About half the trades the target closes would have made more.** R72, Rs 1,000 target, year: the target closed 648
  trades; 310 of them would have made more under today's rule (Rs 5.89 L given up in total); on the other 338 it saved
  Rs 4.26 L. R51: 818 closed, 380 would have made more, Rs 6.58 L given up vs Rs 5.26 L saved.
- **The runners pay for everything.** Today's average win is well above Rs 1,000 because some trades run far; a hard
  target caps every one of them. On Liquidity this removes nearly all of its profit (Rs 1.00 L -> about Rs 10k).
- The step lock keeps the runners but sells many trades at about +Rs 500 on a normal wobble, so it also earns less.
- At 3 lots, the same picture: R72 Rs 10.81 L today -> Rs 4.30 L (target Rs 1,000), Rs 7.31 L (step lock); R51 Rs 8.88 L
  -> Rs 3.29 L / Rs 5.80 L; Liquidity Rs 3.58 L -> Rs 80,537 / Rs 1.05 L.

## Your observation: "+Rs 1,000, then negative"

It is real. Under today's rule, 1 lot, last year:

| | trades | losing trades | reached +Rs 1,000 | of those, ended in a loss | given back on them | losers that had been +Rs 1,000 first | lost after +1,500 / +2,000 |
|---|---|---|---|---|---|---|---|
| R72 | 1,069 | 464 | 621 | 102 | Rs 2.98 L | 22.0% | 44 / 19 |
| R51 | 1,367 | 609 | 737 | 120 | Rs 3.41 L | 19.7% | 51 / 18 |
| Liquidity | 688 | 404 | 253 | 56 | Rs 1.46 L | 13.9% | 25 / 13 |

**The month you watched (21 Oct - 13 Nov 2026)**, rebuilt from the lake's gap files (no new download, not your app's
records), today's rule, 1 lot:

| | trades | net | reached +Rs 1,000 | then lost | losers that had been +Rs 1,000 first | days with at least one |
|---|---|---|---|---|---|---|
| R72 | 118 (17 days) | -Rs 4,445 | 75 | 19 | 33.9% | 12 of 17 days |
| R51 | 124 (18 days) | Rs 16,108 | 89 | 14 | 31.1% | 11 of 18 days |
| Liquidity | 43 (13 days) | Rs 5,247 | 24 | 4 | 23.5% | 3 of 13 days |

This month was worse than usual (about a third of losers had been +Rs 1,000, against about a fifth over the year), and on
most days at least one R72 / R51 trade did it. But over the year, of the 621 R72 trades that reached +Rs 1,000, 519 did NOT
end in a loss, and the ones that ran on made more than the 102 gave back. Locking the gain early (any of the five rules)
cost more than it saved in every period.

## Three more answers (Amendment B): 60% lock, index lock, split exit

You asked for other ways to stop "+Rs 1,000, then negative". Same base, same year, 1 lot unless said.

- **P60**: once +Rs 1,000 a lot, the stop locks 60% of the best open profit (as S5 Trail).
- **IDX** (signal on the index): once +Rs 1,000 a lot, sell when a 1-minute index close goes back through the index level
  at the entry.
- **SPLIT** (2-3 lots): sell 1 lot at +Rs 1,000; the other lots' stop moves to breakeven.

| year (Apr - Oct 2026) | today (base) | P60 | IDX | SPLIT at 3 lots (base at 3 lots) |
|---|---|---|---|---|
| **R72** net | Rs 3.25 L (Rs 1.67 L) | Rs 2.03 L (Rs 1.01 L) | Rs 2.52 L (Rs 1.22 L) | Rs 7.38 L vs Rs 10.81 L (Rs 3.39 L vs Rs 5.53 L) |
| win rate | 56.6% | 64.0% | 54.9% | 65.1% vs 56.8% |
| worst month | -Rs 18,110 | -Rs 16,639 | -Rs 20,904 | -Rs 41,471 vs -Rs 41,504 |
| **R51** net | Rs 2.50 L (Rs 1.45 L) | Rs 1.64 L (Rs 55,093) | Rs 1.91 L (Rs 95,773) | Rs 5.93 L vs Rs 8.88 L (Rs 2.57 L vs Rs 4.89 L) |
| win rate | 55.5% | 63.0% | 54.4% | 63.3% vs 55.6% |
| worst month | -Rs 20,990 | -Rs 31,425 | -Rs 20,111 | -Rs 51,720 vs -Rs 57,548 |
| **Liquidity** net | Rs 1.00 L (Rs 6,432) | Rs 15,313 (-Rs 27,118) | Rs 85,036 (Rs 14,173) | Rs 2.06 L vs Rs 3.58 L (-Rs 2,157 vs Rs 47,831) |
| win rate | 41.3% | 48.7% | 40.8% | 49.1% vs 42.4% |
| worst month | -Rs 37,773 | -Rs 30,303 | -Rs 32,041 | -Rs 91,960 vs -Rs 1.09 L |

Trades in the year at 1 lot: R72 1,069 / 1,101 / 1,076; R51 1,367 / 1,439 / 1,372; Liquidity 688 / 690 / 688 (base /
P60 / IDX); SPLIT at 3 lots R72 1,076, R51 1,374, Liquidity 688.

- **All 9 fail** (BH m = 4,876). None makes as much as today's rule in the year. IDX is the gentlest (it loses
  Rs 15k-73k a year) and was the only one ahead of the base anywhere: Liquidity in Apr - Oct (Rs 14,173 vs Rs 6,432), but
  not in the year.
- **SPLIT** keeps more than a full target (R72 at 3 lots Rs 7.38 L vs Rs 4.30 L with the Rs 1,000 target) but still about
  a third less than today; moving the other lots to breakeven sells them on normal wobbles.
- P60 raises the win rate to 63-64% on R72 / R51 but, like the targets, shrinks the average win (R72 Rs 1,159 vs Rs 1,675).
- 2 lots, SPLIT (year): R72 Rs 4.11 L vs Rs 7.06 L, R51 Rs 3.19 L vs Rs 5.74 L, Liquidity Rs 72,841 vs Rs 2.29 L.

## Gates

All 15 first tests fail (BH m = 4,867), and all 9 of Amendment B (m = 4,876). Every rule makes less than today's rule in the year and in Apr - Oct 2026. The R72 / R51
targets and step lock beat random exit minutes (p 0.002-0.014) but not after counting all 4,867 tests (q 1.00), and beating
random exits is not beating today's rule. None keeps the account stop rule in every period (today's rule does not either).

## Honest notes

- The base reproduced R179's numbers exactly (R72 1,069 trades / Rs 3,24,629, R51 1,367 / Rs 2,49,903, Liquidity 688 /
  Rs 1,00,222 in the year; the same in Apr - Oct).
- Data-led, on the same year R179 read; Apr - Oct 2026 was read again. The amounts came from you, not from a search.
- When the option opens above the target, the sale is at the opening price (better than the target); 86-140 of each R72 /
  R51 target's exits were like that. That flatters the target a little; it still loses.
- The month you watched includes 8 Nov's Muhurat session (R72 5 trades, R51 4). The rebuilt stores match the 19 Oct
  stores on every day except 19 Oct itself (that session had no next day in the old stores).
- Nothing in the app changes. Every number: `report.md`.
