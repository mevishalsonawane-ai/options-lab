# R76: "25% - 35% of target" lock on Liquidity 15+5

18 Oct 2026. Research only (no `android/**`). Pre-registered in `research/hunt/r76/PREREG.md` (5acec0e7, pushed before
any result). Code and design results were committed (c0d4a511) before the final period was read once. Code:
`research/hunt/r76/`.

Boss, 18 Oct: *"What about 35-5 for liquidity"*, then *"25% -35% of target"*. The rule: once the **index** has covered
25% (or 35%) of the way from the entry index price to Liquidity's own target (the next liquidity level), the
**option's** stop rises to +5% above the fill.

## Answer

**No. It makes Liquidity worse, so Liquidity should keep its own exits.** In the design period (881 trades) every
target-progress version made less than today's exits: Rs 28k to Rs 49k less (T25 Rs 2,08,114, T35 Rs 2,28,775, against
Rs 2,57,249). It also made less in the walk-forward year (657 trades). In the final period (357 trades) it was mixed
(T35 +Rs 2,208, T25 -Rs 1,999), and that period has already been read by R51, R63 and R70, so it is weak evidence. No
version beat random early exits (BH q 0.94 for all five). The reason is simple. When the index is 25-35% of the way to
the target, the 1-ITM option is usually still under +5% (in about 4 of 5 changed trades). So "stop to +5%" puts the stop
**above** the price, and the trade is sold at the next minute. Liquidity earns its money on the few trades that run all
the way to the next level, and this rule cuts too many of them early. The premium reading (L35: +5% once the option is
+35%) barely acts: it changed 8 of 881 trades in design and 2 of 357 in the final, and it was ahead in design but
behind in the final. Nothing is recommended, and nothing is "harmless".

## Headline table (Liquidity 15+5, 1 lot, net after all charges and slippage)

Every row is the same trades: 881 design, of which 657 are in the walk-forward year; 357 final.

| rule | design 20 Nov 24 - 31 Mar 26 (881) | walk-forward Apr 25 - Mar 26 (657) | final 1 Apr - 13 Oct 26 (357) | final win | final worst day | final max DD |
|---|---|---|---|---|---|---|
| **B0 own exits (today)** | **Rs 2,57,249** | **Rs 2,38,064** | Rs 11,155 | 37% | -Rs 11,405 | Rs 61,062 |
| T25: +5% stop once 25% to target (Boss) | Rs 2,08,114 | Rs 2,17,292 | Rs 9,156 | 41% | -Rs 9,643 | Rs 51,801 |
| T30: 30% (neighbour) | Rs 2,27,558 | Rs 2,31,823 | **Rs 18,596** | 42% | -Rs 9,643 | Rs 51,325 |
| T35: 35% (Boss) | Rs 2,28,775 | Rs 2,21,300 | Rs 13,363 | 41% | -Rs 9,643 | Rs 53,383 |
| T50: 50% (neighbour) | Rs 2,20,719 | Rs 2,17,494 | Rs 9,477 | 42% | -Rs 11,405 | Rs 59,705 |
| L35: +5% once option +35% (side line) | Rs 2,64,578 | Rs 2,38,533 | Rs 8,832 | 37% | -Rs 11,405 | Rs 61,062 |

Design win rate, worst day and max DD (881 trades, 282 days): B0 44%, -Rs 8,416, Rs 32,537; T25 51%, -Rs 6,283,
Rs 40,300; T30 51%, -Rs 6,283, Rs 36,246; T35 51%, -Rs 6,348, Rs 31,195; T50 49%, -Rs 6,873, Rs 38,169; L35 44%,
-Rs 8,416, Rs 28,137. The T rules win more often and have smaller worst days, but make less money. They turn big wins
into small ones.

## What the lock changed (trade by trade against B0)

| rule | changed design / final | design: saved / given up | final: saved / given up | design diff (paired p) | final diff (paired p) | exit-twin p | BH q |
|---|---|---|---|---|---|---|---|
| T25 | 395 / 140 | Rs 2,06,044 on 197 / Rs 2,55,180 on 198 | Rs 62,500 on 69 / Rs 64,499 on 71 | -Rs 49,136 (0.93) | -Rs 1,999 (0.55) | 0.93 | 0.94 |
| T30 | 363 / 130 | Rs 1,87,626 on 182 / Rs 2,17,318 on 181 | Rs 61,707 on 63 / Rs 54,266 on 67 | -Rs 29,692 (0.83) | +Rs 7,441 (0.30) | 0.72 | 0.94 |
| T35 | 326 / 113 | Rs 1,53,584 on 162 / Rs 1,82,058 on 164 | Rs 48,071 on 53 / Rs 45,863 on 60 | -Rs 28,475 (0.88) | +Rs 2,208 (0.39) | 0.83 | 0.94 |
| T50 | 244 / 86 | Rs 1,06,408 on 106 / Rs 1,42,938 on 138 | Rs 29,076 on 38 / Rs 30,754 on 48 | -Rs 36,530 (0.95) | -Rs 1,678 (0.56) | 0.88 | 0.94 |
| L35 | 8 / 2 | Rs 10,367 on 6 / Rs 3,038 on 2 | Rs 0 / Rs 2,324 on 2 | +Rs 7,329 (0.14) | -Rs 2,324 (1.00) | 0.94 | 0.94 |

The paired p is a one-sided day bootstrap (2,000 resamples) of "variant beats B0": p near 1 means it did not. The
exit-twin p compares the rule's total with 500 sets of as many random early exits; BH is across the 5 tests.

**Why it fails: the stop is often above the price.** The index reached the trigger on 452 (T25) / 398 (T35) of 881
design trades (198 trades have no target ahead and never trigger). In most of them the option was still under +5% at
that moment, so the +5% stop sat above the market and sold at the next minute's open, below +5% (a real SL above the
price would also fire at once):

| rule | design changes sold under +5% (stop above price) | sold at +5% after being above it |
|---|---|---|
| T25 | 331 (-Rs 25,460 vs B0) | 64 (-Rs 23,676) |
| T35 | 269 (-Rs 7,985) | 57 (-Rs 20,490) |
| T50 | 190 (-Rs 5,323) | 54 (-Rs 31,207) |

Both halves lose in design. Selling a trade early because the index is part-way to the target is about a coin toss
(half the changed trades were saved, half gave up profit). Locking +5% on the trades that had gone above it cuts the
runners.

## Examples

- **BANKNIFTY 48600 call, 20 Jan 2025 (T25, design).** The own exits held it to the next-level target, +60% (Rs 13,887).
  T25 locked +5% when the index was a quarter of the way there, and a dip took it out at +5% (Rs 1,031).
- **MIDCPNIFTY 13875 put, 23 Apr 2026 (T30, final).** The index covered 30% of the way while the option had only
  reached +1.5% at most. The +5% stop was above the price, so it sold at the next minute at +1% (Rs 75). The own exits waited
  for the index exit and lost 17% (-Rs 4,049). This is the kind of trade the rule saves, but it is about as common as
  the kind it hurts.
- **BANKNIFTY 56400 put, 24 Jul 2026 (L35, final).** The option went above +35% (its day high was +36%), so L35 locked +5%. A later dip sold it there (Rs 476).
  The own exits waited for the index exit at +19.6% (Rs 2,156).

## Decision (pre-registered, as R70)

A variant is recommended only if its net is at least B0's in design, walk-forward and final AND its exit-twin q <= 0.10.
No variant passed: every T variant lost in design and walk-forward, L35 lost in the final, and every q is 0.94. None is
"harmless", because each made less than B0 in at least one period. **Liquidity keeps its own exits**, on paper and
live. This agrees with R19 (no lock passed on Liquidity) and R70 (L25 was noise there).

## For Jarvis

Boss asked about "35-5" / "25-35% of target" on Liquidity (R76, 18 Oct). Answer: no. Moving the stop to +5% once the
index is 25-35% of the way to Liquidity's target lost Rs 28k-49k over 881 design trades. It was mixed in the final
period, which is weak evidence (357 trades). It was no better than random early exits (q 0.94). The reason: at that
point the option is usually still under +5%, so the stop sits above the price and sells at once. The runners that make
Liquidity's money get cut. The premium version (+5% once the option is +35%) changed only 10 of 1,238 trades. Liquidity
keeps its own exits.

## Signal source and method

- **Signal:** the index (Liquidity's level breaks on 15/30/5-minute index bars), unchanged. **Trigger (T variants):** the
  index's 1-minute high (calls) or low (puts) against `entry + X * (target - entry)`, where entry is the index open of
  the fill minute and target is the trade's own next-level target. **Stop and lock:** the option premium (+5% over the
  raw fill). L35's trigger is on the option premium. It is a side line, not the default, and is reported separately.
- Trades as R63 / R70: `fam_liq.make` on R51's Amendment A clean stores, BANKNIFTY 15+5, FINNIFTY 30+5 (parked),
  MIDCPNIFTY 15+5 (index stop 8 points), 1-ITM, expiry days skipped. B0 reproduces R70 exactly (Rs 2,57,249 / 881
  design, Rs 11,155 / 357 final). Each trade is priced again from the same entry row and the same index-exit row.
  `r76engine.simulate` is R64's simulator plus an index-trigger flag. A test shows it equals R64's when the flag is off,
  and the study asserts B0 matches trade by trade.
- No look-ahead: the trigger reads only index bars from the fill minute on. It is judged on a minute's bar and acts
  from the next minute. Bars before the fill or after the exit are never read (tests in `test_r76.py`).
- Costs: dated charges, R17 liquidity fills, next-minute fills, stops on the minute low with gap fills at the worse
  open, one held contract, 1 lot.
- Limits: an earlier exit that would have let a book take a later signal is not modelled (up to 395 changed trades in
  design, so the T results are approximate). The final period was already read by earlier studies, so it is weak
  evidence. The "stop above price sells at the next open" behaviour follows the pre-registered rule. A rule that moves
  the stop to +5% only once the option is above +5% is a different idea and was not tested here.

Files: `research/hunt/r76/` (PREREG.md, r76engine.py, r76study.py, r76entries.py, test_r76.py, results/),
`research/experiments/entries/r76-*.yaml` (6 entries).
