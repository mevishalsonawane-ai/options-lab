# R65: Boss's step trail (+10% -> stop +5%, +15% -> +10%, ...) on the paper arms

16 Oct 2026. Research only: nothing in the app changes (no `android/**`). Whether anything changes is Boss's call.
Pre-registered in `research/hunt/r65/PREREG.md` (committed before any result, Amendment 1 also before any result). Code:
`research/hunt/r65/`. Full tables: `research/hunt/r65/results/report.md`.

Boss, 16 Oct: *"When a profit moves to 10% profit we could set stoploss at 5, then if it reaches 15, we could have set it
to 10, what will happen in this scenario."* Later, mid-task: *"Also check if we could move 5% stop loss u"* (the message
was cut off; read as "start with a 5% stop loss and move it up").

## Answer in one paragraph

**No. Boss's rule does not beat what the paper arms run today (R64 A4, the 30/60/100 ladder), or the research rules.**
It makes you right more often (win rate goes from 46% to 53-54% across the three arms) but each win gets much smaller.
A +10% move is a normal wiggle for an option premium, so the trail sells many trades at +5% that would have gone on to
+25%, +50% or +88%. Across the three arms, in the period used to design the rules (20 Nov 2024 - 31 Mar 2026), Boss's
rule made **Rs 1.23 lakh against Rs 3.51 lakh** for today's A4 lock. In the final period (1 Apr - 13 Oct 2026) it made
**Rs 1.31 lakh against Rs 2.22 lakh**. The "move a -5% stop up" version is worse still: it **loses money** (-Rs 2.06 lakh
design, -Rs 64k final) because a -5% stop is hit by ordinary noise on about half of all trades. Wider gaps hurt less: a
10-point gap (breakeven at +10%) comes closest to today's results, but it still loses to A4 in design on every arm. And
the trail's timing is no better than selling the same number of trades at random minutes. **Keep A4.**

## Before / after in plain numbers (the three arms together, 1 lot each)

Net Rs after all charges and slippage. Sample: design 5,370-5,450 trades on 337 days, walk-forward 3,960-4,050 trades on 247
days, final 2,200-2,250 trades on 134 days.

| rule | design (20 Nov 24 - 31 Mar 26) | walk-forward (Apr 25 - Mar 26) | final (1 Apr - 13 Oct 26) | final win rate | final worst day | final max drawdown |
|---|---|---|---|---|---|---|
| **B0** research rules (own stop + fixed target) | Rs 3,41,327 | Rs 4,52,221 | Rs 2,03,566 | 46% | -Rs 41,682 | Rs 1,25,279 |
| **A4** app today (own stop + 30/60/100 ladder) | **Rs 3,51,167** | **Rs 4,63,434** | Rs 2,22,063 | 45% | -Rs 41,682 | Rs 1,23,702 |
| **S5** Boss's rule exactly (+5% at +10%, trail 5 pts, target kept) | Rs 1,23,197 | Rs 2,36,496 | Rs 1,31,424 | 53% | -Rs 39,934 | Rs 1,28,302 |
| **S5n** Boss's rule, no fixed target | Rs 1,24,128 | Rs 2,49,438 | Rs 1,52,629 | 53% | -Rs 39,934 | Rs 1,29,601 |
| **S75** +2.5% at +10%, trail 7.5 pts | Rs 1,84,532 | Rs 3,11,842 | Rs 1,67,849 | 52% | -Rs 40,266 | Rs 1,18,931 |
| **S75n** same, no target | Rs 1,82,765 | Rs 3,27,012 | Rs 1,94,049 | 52% | -Rs 40,266 | Rs 1,18,208 |
| **S10** breakeven at +10%, trail 10 pts | Rs 2,72,347 | Rs 3,65,678 | Rs 2,05,650 | 46% | -Rs 40,511 | Rs 1,25,937 |
| **S10n** same, no target | Rs 2,62,359 | Rs 3,72,936 | **Rs 2,31,357** | 45% | -Rs 40,511 | Rs 1,20,262 |
| **T5** -5% stop moved up (0 at +5%, +5% at +10%, ...) | -Rs 2,06,247 | -Rs 85,564 | -Rs 63,828 | 32% | -Rs 26,069 | Rs 89,460 |
| **T5n** same, no target | -Rs 2,08,969 | -Rs 83,339 | -Rs 53,099 | 32% | -Rs 26,069 | Rs 90,520 |

S10n's final Rs 2,31,357 is the only final number above A4's (by Rs 9,294, paired day bootstrap p = 0.44, i.e. noise).
In design it is Rs 88,808 below A4. The T5 rows have the smallest drawdown because they lose a little on most days
instead of a lot on a few.

## Per arm (net Rs: design / walk-forward / final; trades in the final)

| arm (final trades) | B0 research | A4 app today | S5 Boss | S5n | S75n | S10n | T5 -5% moved up |
|---|---|---|---|---|---|---|---|
| **Donchian** (1,781-1,832) | 8,281 / 1,69,661 / 55,439 | 7,474 / 1,73,115 / **78,777** | -1,33,615 / 7,113 / 2,349 | -1,32,684 / 20,054 / 23,554 | -76,507 / 91,876 / 57,644 | -25,282 / 1,31,895 / 55,424 | -2,63,852 / -1,41,117 / -83,817 |
| **OOV** (293) | 2,77,137 / 2,26,650 / 48,955 | **2,85,053 / 2,31,678** / 45,734 | 2,21,972 / 1,94,543 / 47,662 | = S5 | 2,27,546 / 2,03,411 / 49,315 | 2,55,936 / 2,09,336 / **76,100** | 16,188 / 14,136 / -4,700 |
| **Solo 2.0** (128) | 55,910 / = / **99,172** | **58,641** / = / 97,552 | 34,840 / = / 81,413 | = S5 | 31,725 / = / 87,089 | 31,705 / = / 99,833 | 41,418 / = / 24,689 |

OOV has no fixed target, so its "n" rows equal the others. Solo 2.0's targets are hit only 3 times in design under B0
and never under the 5-point trail, so its S5 and S5n come out the same. Solo 2.0's design period is its walk-forward period (Jul 2025 - Mar 2026).

### Plain-English verdict per arm

- **Donchian: no.** Boss's rule turns the design period from +Rs 8k into -Rs 1.34 lakh, and the final from Rs 79k (A4)
  into Rs 2k. In the final period the trail closed 398 trades. Against the same trades under the research rules it gave
  up Rs 1.96 lakh and saved Rs 1.48 lakh (net -Rs 48k). 200 of those 398 options went on to trade +30% above the entry
  after the trail had sold them, and 49 of them were trades the research rules sold at +30% or more. Example:
  MIDCPNIFTY put, 5 Jun 2026: the trail sold at +4% (Rs 1,156); the plain rules held to the 30-minute exit at +25%
  (Rs 7,481). The trail does help sometimes: MIDCPNIFTY put, 21 May 2026: trail +19% (Rs 3,034) vs plain +1% (Rs 10).
  The wider gaps are less bad but still lose to A4 everywhere.
- **OOV: no, though the 10-point gap is close.** Boss's exact rule is about level in the final period (Rs 47,662 vs
  Rs 45,734 for A4), and below in design (Rs 2.22 lakh vs Rs 2.85 lakh). S10 (breakeven at +10%, then 10 points below the
  best) made the most in the final (Rs 76,100), but less in design (Rs 2.56 lakh vs 2.85) and walk-forward (Rs 2.09
  lakh vs 2.32), so the pre-registered rule does not recommend it. Example of what the 5-point trail costs: MIDCPNIFTY
  put, 24 Sep 2026: the trail sold at +5% (Rs 431); the plain trade ran to +88% (Rs 9,393). Example of what it saves:
  MIDCPNIFTY put, 9 Oct 2026: trail +5% (Rs 1,096) vs plain -23% at the time exit (-Rs 6,120).
- **Solo 2.0: no.** Boss's rule: Rs 34,840 in design vs Rs 58,641 for A4, and Rs 81,413 vs Rs 97,552 in the final. S10 is
  level with A4 in the final (Rs 99,833 vs 97,552) but far below it in design (Rs 30,070 vs 58,641). The two examples are
  the same MIDCPNIFTY trades as OOV's: Solo 2.0's S3 setup *is* OOV, and it picked those two days.
- **The -5% stop moved up (T5): no, on every arm.** It loses money on Donchian and OOV and cuts Solo 2.0's final from
  Rs 97k to Rs 25k. Win rate falls to 31-41%. Options move more than 5% against you in the first minutes of most
  trades, so the stop is hit before the trade has a chance (981 stops out of 2,253 trades in the final, vs 258 for A4).

## Did the trail save money on give-backs? (final period, three arms, against the same trades without the trail)

| rule | trades the trail sold | Rs given up (trades) | Rs saved (trades) | net | trail sold a trade the plain rules sold at +30% or more | the option went on to +30% after the trail sold |
|---|---|---|---|---|---|---|
| S5 Boss | 603 | Rs 4,21,662 (285) | Rs 3,68,697 (279) | **-Rs 52,965** | 77 | 312 |
| S5n | 642 | Rs 4,69,986 (295) | Rs 3,93,975 (294) | **-Rs 76,011** | 82 | 345 |
| S75 | 496 | Rs 3,55,154 (239) | Rs 3,06,806 (236) | -Rs 48,348 | 65 | 259 |
| S75n | 545 | Rs 3,93,703 (253) | Rs 3,34,675 (254) | -Rs 59,028 | 77 | 303 |
| S10 | 408 | Rs 2,52,482 (186) | Rs 2,53,478 (212) | Rs 996 | 51 | 214 |
| S10n | 453 | Rs 2,80,932 (204) | Rs 2,78,297 (229) | -Rs 2,635 | 67 | 257 |
| T5 | 733 | Rs 5,03,424 (321) | Rs 4,21,813 (326) | -Rs 81,611 | 62 | 294 |
| T5n | 758 | Rs 5,26,049 (326) | Rs 4,35,142 (337) | -Rs 90,908 | 60 | 316 |
| A4 app today | 17 | Rs 8,423 (9) | Rs 4,553 (8) | -Rs 3,870 | 2 | 14 |

So yes, the trail saves real money on trades that turn back (Rs 3.7 lakh for Boss's rule). But it gives up more on trades
that keep going (Rs 4.2 lakh). About half the trades it sells (312 of 603) trade +30% above the entry later that day.
The trades are matched on index, day, side and fill minute; 39 of 603 had no twin under the plain rules (an earlier exit
frees the side for one more Donchian entry), which is why the S-variant trade counts are a little higher than B0's.

**Random-exit twins (is the trail's timing better than luck?)** For each trail variant, 500 sets of the plain trades in
which the same number of trades (39-567 per arm) are sold at a random minute instead. The trail never beats them. Exit-twin
p ranges from 0.18 (OOV S10) to 1.00 (Donchian T5), and the BH q is 1.00 for all 24 tests. Selling the same number of
trades at random minutes would have done at least as well as the trail. The +10% trigger carries no information about
which trades will turn back.

## Gates (PREREG section 6; as R51 / R64, never relaxed)

Random-entry twins (500, seed 65), BH across all 30 tests. Every variant on every arm beats random entries in the final
(p 0.002-0.07), so the arms' **signals** still work under any of these exits. Gate results:
- Donchian: B0 and A4 pass. Every trail variant fails (design net <= 0, and for most of them the top 5 trades are worth
  more than the whole net). T5 / T5n also lose money in the final and the walk-forward.
- OOV: B0, A4 and S5-S10n pass. T5 / T5n fail (final net <= 0, p = 0.07, top-5 > net).
- Solo 2.0: B0, A4 and S5-S10n pass. T5 / T5n fail (top-5 > net). Solo 2.0 has only 128 final trades.

Passing the gates is not enough: a variant is recommended only if it also beats **both** B0 and A4 in design,
walk-forward and final AND beats random early exits (exit-twin q <= 0.10). **None does, on any arm.** Risk trade-offs vs
A4 (lower final drawdown or a better worst day, at a lower final net): Donchian all eight, OOV T5 / T5n, Solo 2.0 S5,
S5n, S75, S75n, T5, T5n. These are listed because they are lower-risk options, not as recommendations: each costs net
profit.

## How it was measured (R64's machinery, reused)

- **Signals on the underlying** (CLAUDE.md): Donchian = 5-min index Donchian-20 break; OOV = the index opens outside
  the previous day's value area; Solo 2.0 = R57 A2's picks of index setups S1-S4. The option is chosen by the arm's
  strike rule and only bought. Stops, targets and the trail are on the option premium.
- Engine `hunt/r64/r64engine.py` (R51's): one held contract, next-minute fill, R17 "liq" slippage (doubled for stops),
  stops on the minute low with gap fills at the worse open, a lock set by a minute's high acts from the next minute,
  `Costs('dated')` (STT 0.15% from 1 Apr 2026), 1 lot in force. Stores: R51 Amendment A clean stores (Donchian, OOV;
  stray prints and foreign rows removed), R57 stores (Solo 2.0). Trades from 20 Nov 2024 only. The stores keep Sep-Nov 2024
  rows for indicator warm-up only, as in R51 / R64; those rows never trade.
- The step trail is R64's stepped lock with 199 steps (+10%, +15%, ... +1000%). The stop sits at the last step reached:
  best +13% still has the stop at +5%. "Breakeven" = fill + round-trip charges. Each arm keeps its own stop, time exit,
  square-off and S1's index exits.
- The B0 and A4 numbers reproduce R64's exactly (all three arms, design and final), so R65 runs on the same machinery.
- Tests: `python -m pytest research/hunt/r65 -q`. They cover the step table, the variant table, the engine's
  next-minute locks, the staircase, gap fills, breakeven, target vs trail, a look-ahead guard (randomising every bar after
  the exit never changes the exit), trail accounting, the exit twins, the decision and the one-time final read.

## Honest limits

- **The final period is not fresh.** R51, R52, R57 and R64 already read it, and the arms and A4 were chosen on it. The
  design period carries most of the weight here, and it is where every trail variant loses most clearly. Only paper
  trading from 15 Oct on is unseen.
- The reading of Boss's rule is a staircase in 5-point steps (Boss's own numbers: 10 -> 5, 15 -> 10). A smooth trail
  (stop always exactly 5 points below the best) was not tested. It would sell even sooner (at best +12% the stop would
  be +7%, not +5%), so it is unlikely to do better. The "5% stop moved up" reading comes from a cut-off message. Boss
  can correct either reading, and a corrected rule would be a new pre-registered test.
- Donchian's trade list changes slightly with the exits (an earlier exit frees the side for a later signal). The exit
  twins keep the plain trade list.
- Solo 2.0's picks are held fixed (R57's walk-forward picks). Solo 2.0 is small: 128 final trades.

## Files

- `research/hunt/r65/PREREG.md`: the pre-registration (+ Amendment 1, T5 / T5n)
- `research/hunt/r65/r65cfg.py` (variants, step table), `r65study.py` (runs, accounting, twins, gates), `r65report.py`,
  `r65entries.py`, `test_r65.py`
- `research/hunt/r65/results/`: `summary.json`, `report.md`, per-group JSON, `FINAL_READ`
- `research/experiments/entries/r65-*.yaml`: 30 entries, one per arm and variant, failures included
