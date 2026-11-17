# R186: a hard fixed profit (Rs 1,000 - 1,500 a lot) and a Rs 500 step lock on Liquidity, R51 and R72

Boss, 16 Nov 2026: *"I want you to check if i had a hard 1-1.5 fix profit what will be effect on liquidity,r51,r72"*, and
*"the profit of a lot is 1000, then ends of negative, then its 1.5k again negative, again 2k again negative, this has
happened every single day"*.

**Short answer (all eight rules tested, none helps): a hard target cuts the profit by about half to nine tenths on every arm. It raises the win rate (to 60-66%
on R72 / R51), but the wins become small (about Rs 1,000) while the losses stay the same size (about Rs 1,500). The Rs 500
step lock is less bad but still costs Rs 81k - 1.12 L a year. Your observation is real (about 1 in 5 losing trades had been
+Rs 1,000 first), but the trades that keep running pay for it. Nothing passed. Keep today's rule.**

**16 Nov, the Rs 1,000 stop question (Amendment C, below): keep V5 as it is. Adding a Rs 1,000 a lot stop makes less on every arm in the year (R72 Rs 2.13 L -> Rs 1.37 L, R51 Rs 1.63 L -> Rs 49,797, Liquidity Rs 98,239 -> Rs 76,543); a Rs 1,500 stop also makes less (Rs 1.52 L, Rs 72,074, Rs 89,180).**

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
| max drawdown | Rs 67,497 | Rs 47,663 | Rs 57,810 | Rs 56,991 | Rs 58,857 | Rs 60,281 |

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

## Amendment C: a Rs 1,000 instead of Rs 1,500 stop on today's app rule (V5)

Boss, 16 Nov 2026: *"instead of 1.5k stop loss what will happen if we make it 1k, also show me detailed difference before
and after"*.

**Short answer: do not install a Rs 1,000 stop, and the Rs 1,500 stop also costs money. On today's app rule (V5) both stops
earn less than having no rupee stop at all, on all three arms, in the year. The Rs 1,000 stop is the worst: it closes about
4 in 10 R72 / R51 trades, and about a third of those would have ended in profit. 0 of 6 pass.**

V5 = the app now: R72 / R51 = picker + own -15% stop + breakeven at +10% + Rs 500 step lock (L500); Liquidity = own stop +
breakeven at +10% + breakeven once +Rs 1,000 (LBE1000). S1500 / S1000 = V5 plus a stop at the price that loses Rs 1,500 /
Rs 1,000 a lot after charges and expected slippage (only where tighter than -15%). It is a real stop order: if the option
opens below it, it sells at that open, so a trade can still lose more. Year 20 Oct 2025 - 19 Oct 2026; Apr - Oct = 1 Apr -
13 Oct 2026. Signal on the index; only the option's stop changes.

### 1 lot, year (Apr - Oct 2026 in brackets)

| | V5 (today) | V5 + Rs 1,500 stop | V5 + Rs 1,000 stop |
|---|---|---|---|
| **R72** trades | 1,097 (624) | 1,098 (626) | 1,099 (627) |
| win rate | 64.4% (60.4%) | 58.0% (56.4%) | 50.4% (47.9%) |
| net | **Rs 2,13,110** (Rs 1,11,117) | Rs 1,52,306 (Rs 1,00,492) | Rs 1,36,891 (Rs 76,919) |
| average win / loss | Rs 1,166 / 1,560 | Rs 1,181 / 1,301 | Rs 1,200 / 969 |
| biggest loss | -Rs 6,315 | -Rs 3,781 | -Rs 3,781 |
| trades the stop closed | - | 316 (173) | 479 (279) |
| of those, V5 would have won | - | 70, Rs 1.84 L profit lost | 152, Rs 3.24 L profit lost |
| trades losing over Rs 1,000 | 256 (156) | 367 (208) | 96 (55) |
| max drawdown | Rs 42,286 | Rs 76,403 | Rs 63,832 |
| worst month | -Rs 13,690 (Apr) | -Rs 15,048 (Apr) | -Rs 12,017 (Mar) |
| worst day | -Rs 23,042 | -Rs 19,627 | -Rs 12,709 |
| **R51** trades | 1,430 (684) | 1,442 (693) | 1,445 (695) |
| win rate | 62.7% (60.7%) | 56.9% (55.6%) | 50.0% (48.1%) |
| net | **Rs 1,62,959** (Rs 57,450) | Rs 72,074 (Rs 20,594) | Rs 49,797 (**-Rs 7,484**) |
| average win / loss | Rs 1,067 / 1,489 | Rs 1,056 / 1,277 | Rs 1,048 / 978 |
| biggest loss | -Rs 8,269 | -Rs 8,269 | -Rs 8,269 (a gap) |
| trades the stop closed | - | 402 (193) | 621 (307) |
| of those, V5 would have won | - | 84, Rs 2.22 L profit lost | 186, Rs 4.05 L profit lost |
| trades losing over Rs 1,000 | 319 (156) | 466 (225) | 146 (77) |
| max drawdown | Rs 54,101 | Rs 78,678 | Rs 68,830 |
| worst month | -Rs 29,871 (Apr) | -Rs 33,166 (Apr) | -Rs 26,604 (Sep) |
| worst day | -Rs 16,957 | -Rs 16,048 | -Rs 11,729 |
| **Liquidity** trades | 688 (357) | 689 (357) | 689 (357) |
| win rate | 44.5% (43.7%) | 43.0% (42.3%) | 39.5% (38.1%) |
| net | **Rs 98,239** (Rs 6,424) | Rs 89,180 (Rs 17,752) | Rs 76,543 (Rs 8,410) |
| average win / loss | Rs 1,471 / 921 | Rs 1,437 / 856 | Rs 1,443 / 758 |
| biggest loss | -Rs 4,757 | -Rs 2,715 | -Rs 2,565 |
| trades the stop closed | - | 102 (54) | 222 (120) |
| of those, V5 would have won | - | 10, Rs 40,233 profit lost | 34, Rs 91,942 profit lost |
| trades losing over Rs 1,000 | 132 (69) | 156 (79) | 51 (25) |
| max drawdown | Rs 60,281 | Rs 67,263 | Rs 57,852 |
| worst month | -Rs 32,670 (Apr) | -Rs 23,346 (Apr) | -Rs 16,216 (May) |
| worst day | -Rs 9,918 | -Rs 6,233 | -Rs 6,147 |

"Trades losing over Rs 1,000": with the Rs 1,500 stop this count includes its own stop exits (each loses about Rs 1,500).
With the Rs 1,000 stop, the trades it closed that still lost more than Rs 1,000 (the option opened below the stop, or the
fill was worse than expected) were R72 95 of 479, R51 145 of 621, Liquidity 44 of 222.

### 3 lots, year (Apr - Oct 2026 in brackets)

| net | V5 (today) | V5 + Rs 1,500 a lot | V5 + Rs 1,000 a lot |
|---|---|---|---|
| R72 | **Rs 7.31 L** (Rs 3.94 L) | Rs 5.89 L (Rs 3.68 L) | Rs 5.65 L (Rs 3.10 L) |
| R51 | **Rs 5.80 L** (Rs 2.13 L) | Rs 3.47 L (Rs 91,530) | Rs 2.95 L (Rs 27,022) |
| Liquidity | **Rs 3.57 L** (Rs 45,931) | Rs 3.25 L (Rs 74,652) | Rs 2.86 L (Rs 48,492) |

At 3 lots, year: win rate R72 64.2% / 58.5% / 51.7%, R51 63.5% / 57.9% / 51.4%, Liquidity 45.2% / 43.7% / 40.5%; max
drawdown R72 Rs 1.18 L / 2.10 L / 1.60 L, R51 Rs 1.62 L / 2.15 L / 1.94 L, Liquidity Rs 1.71 L / 1.93 L / 1.59 L; the
Rs 1,000 a lot stop closed R72 459, R51 596, Liquidity 216 trades (138 / 174 / 32 of them V5 would have won).

### The month you watched, 21 Oct - 13 Nov 2026, 1 lot (rebuilt from the lake's gap files; not your app's records)

| | V5 (today) | + Rs 1,500 stop | + Rs 1,000 stop |
|---|---|---|---|
| R72 | 126 trades, 61.9%, **Rs 9,481** | 127, 51.2%, -Rs 12,107 | 128, 37.5%, -Rs 20,812 |
| R51 | 142, 69.0%, **Rs 25,448** | 142, 61.3%, Rs 17,164 | 144, 50.7%, Rs 11,239 |
| Liquidity | 43, 62.8%, Rs 4,575 | 43, 62.8%, Rs 5,667 | 43, 60.5%, **Rs 9,038** |

The Rs 1,000 stop closed R72 72 trades (28 of them V5 would have won, Rs 53,821 profit lost), R51 67 (27, Rs 54,759),
Liquidity 11 (1, Rs 2,087). Worst day: R72 -Rs 14,547 / -13,046 / -14,822, R51 -Rs 7,242 / -8,153 / -5,723.

### Why a tighter stop loses money here

- Options wobble. Where it applies, a Rs 1,000 a lot stop sits much closer to the entry than -15%, so normal swings hit it:
  it closed 479 of 1,099 R72 trades and 621 of 1,445 R51 trades in the year. About a third of those (152 R72, 186 R51)
  would have come back and ended in profit under V5.
- The stop makes the average loss smaller (R72 Rs 1,560 -> Rs 969) but there are many more losses (win rate 64% -> 50%),
  so the net falls (R72 Rs 2.13 L -> Rs 1.37 L, R51 Rs 1.63 L -> Rs 49,797, and R51 goes negative in Apr - Oct).
- It does cut the worst day (R72 -Rs 23,042 -> -Rs 12,709) and the biggest single loss, but not the max drawdown, which is
  bigger on R72 and R51.
- Liquidity is the one place it nearly holds up: in Apr - Oct it was ahead (Rs 8,410 and Rs 17,752 vs Rs 6,424) and in the
  month you watched the Rs 1,000 stop was ahead (Rs 9,038 vs Rs 4,575), but over the year both lose (Rs 76,543 / 89,180 vs
  Rs 98,239).

### Gates (Amendment C)

0 of 6 pass (BH m = 4,882). Every stop makes less than V5 in the year; random exits on the same trades do better than the
stop (twin p 1.0 everywhere). None keeps the account stop rule in every period (V5 does not either).

### Honest notes (Amendment C)

- With the stop off, V5 reproduced this study's own numbers exactly: R72 1,097 trades / Rs 2,13,110, R51 1,430 /
  Rs 1,62,959, Liquidity 688 / Rs 98,239 (year, 1 lot).
- Data-led follow-up on the same year; Apr - Oct 2026 was read again. The amounts came from you.
- The month you watched is descriptive only (rebuilt stores, outside git; no new download).
- Nothing in the app changes. Every number: `year_c.json`, `decision_c.json`, `gap_month_c.json`.
