# R103 (deep): the standard option-selling strategies as Boss's brief listed them (22 Oct 2026)

Research only. Pre-registered in `research/hunt/r103_selling/PREREG.md` (two dated amendments); code, tests and every
result table in `research/hunt/r103_selling/`. The plain-English guide is `research/DEEP_OPTIONS_SELLING.md` (its section
10 summarises this file). Nothing goes to paper or live; a selling arm needs Boss's explicit decision and the safety rails
listed in that guide first.

**How this relates to the other R103 study.** Another session answered a different request of Boss's ("best win rate and
more profit") with a defined-risk study under the same number: iron fly, wide iron fly and iron condor at 09:21, 24 tests
(`research/HUNT_R103_OPTIONS_SELLING.md`, code in `research/hunt/r103_sell/`). This study is the one Boss's options-selling
brief described: the NAKED short straddle and strangle as well as the hedged structures, three entry times, per-leg and
combined stops, a 50% target, an event blackout, expiry-day variants, random-entry twins and the BUY side. The two share
no code. Where they overlap (hedged structures; the naked 09:20 straddle in the tail tables) they agree.

**Signal source of every rule:** none. Each cell sells at a fixed time, centred on the index's own ATM strike; stops and
targets are read on the option premium (CLAUDE.md allows that).

## The answer

- **480 cells** (2 indices × 4 structures × 3 entry times × 5 stops × 2 targets × 2 day sets), Design A = 20 Nov 2024 -
  14 Jul 2026 (395 sessions per index after the 12 event days; 86 NIFTY and 20 BANKNIFTY expiry days).
- 219 cells made money after costs. Only **8** of them have a BH q ≤ 0.10, and **3** pass every pre-registered gate. All 3
  are NIFTY short strangles on expiry days (09:45 with a combined stop and 50% target; 10:15 with per-leg stops of 1.5×
  and 2×).
- **The locked holdout** (15 Jul - 19 Oct 2026), read once, for those 3 cells only, 14 expiry days each:

| cell (NIFTY short strangle, expiry days) | design: trades, win, net | holdout: trades, win, net | holdout p vs zero | holds? |
|---|---|---|---|---|
| 09:45, combined stop 1x credit, target 50% | 86, 86%, +Rs 50,636 | 14, 64%, **−Rs 3,987** | 0.53 | no |
| 10:15, leg stop 1.5x | 86, 71%, +Rs 31,842 | 14, 71%, +Rs 694 | 0.85 | yes, by the letter |
| 10:15, leg stop 2x | 86, 55%, +Rs 40,713 | 14, 57%, +Rs 1,293 | 0.83 | yes, by the letter |

  The two that "hold" made Rs 50 and Rs 92 a trade over 14 trades: indistinguishable from zero. The strongest design cell
  lost. **Verdict: no selling rule is confirmed.** The NIFTY expiry-day strangle is logged as inconclusive (it would need a
  long forward record, on paper and only after Boss decides); everything else is rejected.
- **The naked straddle makes money more often than not, but not reliably enough to pass.** At 09:20 on all days with no
  stop: NIFTY +Rs 71,535 (394 trades, 63% wins) and BANKNIFTY +Rs 77,090 (394, 61%), but p 0.43 and 0.20, a worst day of
  −Rs 30,676 on NIFTY, and 3 months each below −Rs 13k. In the earlier regime (Aug 2021 - Nov 2024) the same NIFTY trade
  lost −Rs 60,282 (786 trades). It is not a stable edge.
- **The hedged structures lose on all days**, in every one of their 120 all-days cells and in both regimes (NIFTY iron
  condor −Rs 91,000, iron fly −Rs 75,165 at 09:20 with no stop; BANKNIFTY far worse, see caveat 2). A few expiry-day
  hedged cells are positive, but none comes near significance (best q 0.44). Four legs of charges and wide spreads on the
  wings cost more than the tail they insure.
- **Every BUY-side mirror loses too** (32 of 32 rows at 09:20): costs and the volatility premium both work against the
  buyer.
- **Stops cost money on the straddle, but they are what keeps it inside Boss's account stop.** A per-leg stop at 2× cut
  NIFTY's 09:20 straddle from +Rs 71,535 to +Rs 27,467, because it buys back the losing leg near the top of a move that
  often comes back. A combined stop at 100% of the credit kept +Rs 88,578 and cut the worst day from −Rs 30,676 to
  −Rs 17,929. Even then one month fell below −Rs 13k and there were 5 days worse than −Rs 13k.

## Results tables

Columns: one lot of that date's size; net after charges and slippage; "months < −13k" counts calendar months below Boss's
monthly stop; p is the sign-flip test against zero; q is Benjamini-Hochberg over the 480 cells; "twin p" is the share of
random-entry twins that did at least as well (a small value means the fixed entry time beat a random time that day, which
for a seller mostly means "entering earlier collects more decay"); "modelled" is the share of trades in which a leg had to
be valued by the model because the lake had no print (PREREG amendment 1).

### 1. 09:20 entry, all days, no target

| und | struct | entry | stop | target | dayset | trades | win_rate | net | avg_win | avg_loss | worst_day | max_dd | rom | days_below_13k | months_below_13k | blocks_positive | p_zero | q | twin_p | modelled_share |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | iron condor | 0920 | no stop | no target | ALL | 390 | 13% | -334,532 | +1,057 | -1,146 | -8,524 | -335,889 | -918% | 0 | 15 | 0 | 0.000 | 0.001 | 0.287 | 95% |
| BANKNIFTY | iron condor | 0920 | leg stop 2x | no target | ALL | 390 | 9% | -376,905 | +986 | -1,165 | -6,615 | -377,843 | -1034% | 0 | 16 | 0 | 0.000 | 0.001 | 1.000 | 95% |
| BANKNIFTY | iron condor | 0920 | combined stop 1x credit | no target | ALL | 390 | 12% | -346,991 | +996 | -1,154 | -4,766 | -348,349 | -952% | 0 | 15 | 0 | 0.000 | 0.001 | 0.703 | 95% |
| BANKNIFTY | iron fly | 0920 | no stop | no target | ALL | 390 | 21% | -233,507 | +1,508 | -1,160 | -11,344 | -239,094 | -641% | 0 | 9 | 0 | 0.000 | 0.001 | 0.010 | 96% |
| BANKNIFTY | iron fly | 0920 | leg stop 2x | no target | ALL | 390 | 19% | -291,621 | +1,186 | -1,201 | -11,997 | -293,477 | -800% | 0 | 11 | 0 | 0.000 | 0.001 | 0.990 | 96% |
| BANKNIFTY | iron fly | 0920 | combined stop 1x credit | no target | ALL | 390 | 21% | -250,260 | +1,485 | -1,191 | -9,847 | -255,846 | -687% | 0 | 11 | 0 | 0.000 | 0.001 | 0.059 | 95% |
| BANKNIFTY | short strangle | 0920 | no stop | no target | ALL | 394 | 57% | -29,867 | +1,489 | -2,137 | -11,844 | -83,987 | -17% | 0 | 3 | 3 | 0.558 | 0.821 | 0.010 | 30% |
| BANKNIFTY | short strangle | 0920 | leg stop 2x | no target | ALL | 394 | 53% | -112,943 | +1,440 | -2,198 | -13,864 | -136,147 | -64% | 1 | 4 | 1 | 0.027 | 0.110 | 0.772 | 30% |
| BANKNIFTY | short strangle | 0920 | combined stop 1x credit | no target | ALL | 394 | 56% | -47,309 | +1,475 | -2,179 | -11,844 | -101,429 | -27% | 0 | 4 | 3 | 0.358 | 0.682 | 0.050 | 30% |
| BANKNIFTY | short straddle | 0920 | no stop | no target | ALL | 394 | 61% | +77,090 | +1,936 | -2,487 | -12,079 | -63,893 | 40% | 0 | 3 | 3 | 0.201 | 0.496 | 0.010 | 2% |
| BANKNIFTY | short straddle | 0920 | leg stop 2x | no target | ALL | 394 | 58% | -10,821 | +1,795 | -2,557 | -21,061 | -61,491 | -6% | 1 | 2 | 2 | 0.859 | 0.961 | 0.584 | 2% |
| BANKNIFTY | short straddle | 0920 | combined stop 1x credit | no target | ALL | 394 | 60% | +65,586 | +1,920 | -2,509 | -12,079 | -63,893 | 34% | 0 | 3 | 3 | 0.277 | 0.588 | 0.010 | 2% |
| NIFTY | iron condor | 0920 | no stop | no target | ALL | 394 | 52% | -91,000 | +1,060 | -1,631 | -13,420 | -94,506 | -214% | 1 | 3 | 0 | 0.035 | 0.136 | 0.267 | 63% |
| NIFTY | iron condor | 0920 | leg stop 2x | no target | ALL | 394 | 41% | -130,018 | +827 | -1,138 | -5,825 | -134,501 | -306% | 0 | 3 | 0 | 0.000 | 0.001 | 0.752 | 61% |
| NIFTY | iron condor | 0920 | combined stop 1x credit | no target | ALL | 394 | 48% | -102,929 | +1,013 | -1,460 | -5,777 | -106,435 | -242% | 0 | 2 | 0 | 0.001 | 0.008 | 0.366 | 60% |
| NIFTY | iron fly | 0920 | no stop | no target | ALL | 394 | 54% | -75,165 | +1,882 | -2,580 | -16,637 | -88,917 | -177% | 1 | 2 | 2 | 0.238 | 0.549 | 0.376 | 63% |
| NIFTY | iron fly | 0920 | leg stop 2x | no target | ALL | 394 | 51% | -101,444 | +1,689 | -2,264 | -19,720 | -114,346 | -239% | 2 | 5 | 1 | 0.091 | 0.289 | 0.475 | 63% |
| NIFTY | iron fly | 0920 | combined stop 1x credit | no target | ALL | 394 | 54% | -64,629 | +1,882 | -2,523 | -17,187 | -78,984 | -152% | 1 | 2 | 1 | 0.300 | 0.618 | 0.188 | 63% |
| NIFTY | short strangle | 0920 | no stop | no target | ALL | 394 | 66% | +54,224 | +1,719 | -2,897 | -27,742 | -42,381 | 30% | 3 | 3 | 3 | 0.422 | 0.726 | 0.020 | 12% |
| NIFTY | short strangle | 0920 | leg stop 2x | no target | ALL | 394 | 55% | -14,015 | +1,486 | -1,882 | -11,878 | -51,643 | -8% | 0 | 3 | 2 | 0.768 | 0.924 | 0.198 | 11% |
| NIFTY | short strangle | 0920 | combined stop 1x credit | no target | ALL | 394 | 64% | +80,641 | +1,694 | -2,409 | -10,101 | -44,912 | 44% | 0 | 1 | 3 | 0.115 | 0.343 | 0.010 | 10% |
| NIFTY | short straddle | 0920 | no stop | no target | ALL | 394 | 63% | +71,535 | +2,550 | -3,841 | -30,676 | -58,692 | 36% | 8 | 3 | 3 | 0.429 | 0.728 | 0.119 | 0% |
| NIFTY | short straddle | 0920 | leg stop 2x | no target | ALL | 394 | 58% | +27,467 | +2,366 | -3,117 | -27,657 | -60,593 | 14% | 4 | 4 | 2 | 0.721 | 0.898 | 0.267 | 0% |
| NIFTY | short straddle | 0920 | combined stop 1x credit | no target | ALL | 394 | 62% | +88,578 | +2,567 | -3,627 | -17,929 | -60,089 | 44% | 5 | 1 | 3 | 0.283 | 0.593 | 0.040 | 0% |

### 2. 09:20 entry, expiry days only, no target

| und | struct | entry | stop | target | dayset | trades | win_rate | net | avg_win | avg_loss | worst_day | max_dd | rom | days_below_13k | months_below_13k | blocks_positive | p_zero | q | twin_p | modelled_share |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | iron condor | 0920 | no stop | no target | EXPIRY | 20 | 75% | +13,258 | +2,091 | -3,622 | -8,524 | -8,524 | 20% | 0 | 0 | 3 | 0.335 | 0.667 | 0.010 | 55% |
| BANKNIFTY | iron condor | 0920 | leg stop 2x | no target | EXPIRY | 20 | 35% | -11,100 | +2,197 | -2,037 | -6,615 | -22,041 | -16% | 0 | 0 | 1 | 0.343 | 0.673 | 0.950 | 45% |
| BANKNIFTY | iron condor | 0920 | combined stop 1x credit | no target | EXPIRY | 20 | 60% | +2,718 | +2,105 | -2,818 | -4,766 | -12,909 | 4% | 0 | 0 | 3 | 0.814 | 0.946 | 0.347 | 40% |
| BANKNIFTY | iron fly | 0920 | no stop | no target | EXPIRY | 20 | 75% | +20,801 | +3,714 | -6,982 | -11,344 | -18,520 | 31% | 0 | 0 | 2 | 0.391 | 0.706 | 0.020 | 50% |
| BANKNIFTY | iron fly | 0920 | leg stop 2x | no target | EXPIRY | 20 | 45% | -10,189 | +2,612 | -3,063 | -9,645 | -24,935 | -15% | 0 | 0 | 3 | 0.606 | 0.833 | 0.832 | 45% |
| BANKNIFTY | iron fly | 0920 | combined stop 1x credit | no target | EXPIRY | 20 | 65% | +4,049 | +3,911 | -6,685 | -9,847 | -23,229 | 6% | 0 | 0 | 2 | 0.869 | 0.961 | 0.089 | 40% |
| BANKNIFTY | short strangle | 0920 | no stop | no target | EXPIRY | 20 | 80% | +35,213 | +2,980 | -3,116 | -7,860 | -7,860 | 17% | 0 | 0 | 4 | 0.016 | 0.069 | 0.010 | 10% |
| BANKNIFTY | short strangle | 0920 | leg stop 2x | no target | EXPIRY | 20 | 45% | -2,119 | +2,624 | -2,340 | -7,016 | -20,076 | -1% | 0 | 0 | 1 | 0.878 | 0.961 | 0.970 | 5% |
| BANKNIFTY | short strangle | 0920 | combined stop 1x credit | no target | EXPIRY | 20 | 70% | +26,001 | +2,977 | -2,614 | -3,492 | -9,331 | 13% | 0 | 0 | 3 | 0.056 | 0.202 | 0.030 | 10% |
| BANKNIFTY | short straddle | 0920 | no stop | no target | EXPIRY | 20 | 75% | +45,620 | +4,997 | -5,868 | -11,572 | -11,696 | 21% | 0 | 0 | 4 | 0.077 | 0.265 | 0.010 | 0% |
| BANKNIFTY | short straddle | 0920 | leg stop 2x | no target | EXPIRY | 20 | 50% | +1,954 | +3,217 | -3,022 | -10,458 | -24,664 | 1% | 0 | 0 | 3 | 0.934 | 0.987 | 0.743 | 0% |
| BANKNIFTY | short straddle | 0920 | combined stop 1x credit | no target | EXPIRY | 20 | 70% | +34,116 | +4,952 | -5,868 | -9,815 | -17,440 | 15% | 0 | 0 | 3 | 0.187 | 0.478 | 0.010 | 0% |
| NIFTY | iron condor | 0920 | no stop | no target | EXPIRY | 86 | 73% | +504 | +1,998 | -5,451 | -13,420 | -34,104 | 1% | 1 | 0 | 1 | 0.990 | 0.999 | 0.337 | 28% |
| NIFTY | iron condor | 0920 | leg stop 2x | no target | EXPIRY | 86 | 41% | -5,978 | +1,459 | -1,119 | -4,541 | -14,862 | -9% | 0 | 0 | 2 | 0.707 | 0.896 | 0.475 | 23% |
| NIFTY | iron condor | 0920 | combined stop 1x credit | no target | EXPIRY | 86 | 58% | -378 | +2,062 | -2,875 | -5,777 | -26,450 | -1% | 0 | 0 | 3 | 0.988 | 0.999 | 0.257 | 15% |
| NIFTY | iron fly | 0920 | no stop | no target | EXPIRY | 86 | 55% | -24,973 | +3,428 | -4,772 | -16,637 | -49,750 | -36% | 1 | 2 | 1 | 0.615 | 0.841 | 0.663 | 20% |
| NIFTY | iron fly | 0920 | leg stop 2x | no target | EXPIRY | 86 | 52% | +5,636 | +2,536 | -2,646 | -14,991 | -30,458 | 8% | 1 | 2 | 1 | 0.880 | 0.961 | 0.208 | 20% |
| NIFTY | iron fly | 0920 | combined stop 1x credit | no target | EXPIRY | 86 | 55% | -14,437 | +3,428 | -4,502 | -17,187 | -43,342 | -21% | 1 | 1 | 1 | 0.761 | 0.923 | 0.416 | 19% |
| NIFTY | short strangle | 0920 | no stop | no target | EXPIRY | 86 | 76% | +35,174 | +2,710 | -6,712 | -27,742 | -46,504 | 17% | 3 | 2 | 2 | 0.504 | 0.795 | 0.337 | 8% |
| NIFTY | short strangle | 0920 | leg stop 2x | no target | EXPIRY | 86 | 55% | +25,499 | +1,646 | -1,329 | -6,142 | -12,905 | 12% | 0 | 0 | 3 | 0.202 | 0.496 | 0.337 | 3% |
| NIFTY | short strangle | 0920 | combined stop 1x credit | no target | EXPIRY | 86 | 67% | +63,930 | +2,713 | -3,336 | -6,785 | -13,686 | 31% | 0 | 0 | 4 | 0.031 | 0.123 | 0.010 | 1% |
| NIFTY | short straddle | 0920 | no stop | no target | EXPIRY | 86 | 64% | +12,111 | +4,054 | -6,802 | -30,676 | -59,205 | 5% | 4 | 4 | 2 | 0.860 | 0.961 | 0.574 | 0% |
| NIFTY | short straddle | 0920 | leg stop 2x | no target | EXPIRY | 86 | 58% | +44,740 | +3,022 | -2,955 | -17,625 | -25,734 | 20% | 2 | 1 | 3 | 0.302 | 0.619 | 0.109 | 0% |
| NIFTY | short straddle | 0920 | combined stop 1x credit | no target | EXPIRY | 86 | 60% | +29,153 | +4,222 | -5,600 | -17,929 | -42,106 | 13% | 1 | 3 | 3 | 0.600 | 0.833 | 0.327 | 0% |

### 3. The best cell per index, structure and day set (chosen after the fact: optimistic)

| und | struct | entry | stop | target | dayset | trades | win_rate | net | avg_win | avg_loss | worst_day | max_dd | rom | days_below_13k | months_below_13k | blocks_positive | p_zero | q | twin_p | modelled_share |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | short straddle | 0945 | no stop | target 50% | ALL | 394 | 64% | +98,710 | +1,630 | -2,252 | -11,548 | -44,898 | 51% | 0 | 2 | 3 | 0.053 | 0.196 | 0.010 | 1% |
| BANKNIFTY | short strangle | 0945 | no stop | no target | ALL | 394 | 60% | -4,025 | +1,270 | -1,923 | -10,564 | -62,812 | -2% | 0 | 2 | 3 | 0.927 | 0.987 | 0.010 | 24% |
| BANKNIFTY | iron fly | 0920 | no stop | no target | ALL | 390 | 21% | -233,507 | +1,508 | -1,160 | -11,344 | -239,094 | -641% | 0 | 9 | 0 | 0.000 | 0.001 | 0.010 | 96% |
| BANKNIFTY | iron condor | 1015 | combined stop 1x credit | no target | ALL | 391 | 13% | -326,741 | +847 | -1,077 | -4,421 | -326,741 | -897% | 0 | 10 | 0 | 0.000 | 0.001 | 0.020 | 94% |
| BANKNIFTY | short straddle | 0920 | no stop | no target | EXPIRY | 20 | 75% | +45,620 | +4,997 | -5,868 | -11,572 | -11,696 | 21% | 0 | 0 | 4 | 0.077 | 0.265 | 0.010 | 0% |
| BANKNIFTY | short strangle | 0920 | combined stop 3% margin | no target | EXPIRY | 20 | 80% | +36,700 | +2,980 | -2,744 | -6,372 | -6,372 | 18% | 0 | 0 | 4 | 0.007 | 0.034 | 0.010 | 10% |
| BANKNIFTY | iron fly | 1015 | combined stop 3% margin | target 50% | EXPIRY | 20 | 60% | +20,966 | +3,517 | -2,655 | -3,777 | -10,874 | 31% | 0 | 0 | 3 | 0.172 | 0.458 | 0.030 | 20% |
| BANKNIFTY | iron condor | 1015 | combined stop 1x credit | no target | EXPIRY | 20 | 75% | +13,719 | +1,813 | -2,694 | -3,709 | -7,190 | 20% | 0 | 0 | 2 | 0.158 | 0.437 | 0.010 | 30% |
| NIFTY | short straddle | 0945 | combined stop 1x credit | no target | ALL | 394 | 61% | +104,124 | +2,485 | -3,197 | -15,747 | -63,996 | 52% | 1 | 2 | 3 | 0.173 | 0.458 | 0.020 | 0% |
| NIFTY | short strangle | 0920 | combined stop 1x credit | no target | ALL | 394 | 64% | +80,641 | +1,694 | -2,409 | -10,101 | -44,912 | 44% | 0 | 1 | 3 | 0.115 | 0.343 | 0.010 | 10% |
| NIFTY | iron fly | 0920 | combined stop 1x credit | target 50% | ALL | 394 | 54% | -42,638 | +1,926 | -2,526 | -17,187 | -58,898 | -100% | 1 | 2 | 2 | 0.494 | 0.793 | 0.030 | 63% |
| NIFTY | iron condor | 0945 | no stop | target 50% | ALL | 394 | 54% | -89,850 | +676 | -1,270 | -12,298 | -91,085 | -212% | 0 | 4 | 0 | 0.003 | 0.015 | 0.178 | 57% |
| NIFTY | short straddle | 0945 | leg stop 2x | no target | EXPIRY | 86 | 64% | +82,270 | +2,925 | -2,537 | -10,424 | -32,582 | 36% | 0 | 0 | 2 | 0.024 | 0.099 | 0.020 | 1% |
| NIFTY | short strangle | 0920 | combined stop 1x credit | no target | EXPIRY | 86 | 67% | +63,930 | +2,713 | -3,336 | -6,785 | -13,686 | 31% | 0 | 0 | 4 | 0.031 | 0.123 | 0.010 | 1% |
| NIFTY | iron fly | 0945 | leg stop 2x | no target | EXPIRY | 86 | 51% | +36,006 | +2,750 | -2,023 | -10,233 | -35,543 | 52% | 0 | 0 | 2 | 0.269 | 0.577 | 0.040 | 16% |
| NIFTY | iron condor | 0920 | combined stop 3% margin | no target | EXPIRY | 86 | 58% | +12,382 | +1,998 | -2,432 | -4,007 | -20,691 | 18% | 0 | 0 | 3 | 0.565 | 0.824 | 0.099 | 13% |

### 4. The BUY side, 09:20 entry

| und | struct | rule | dayset | trades | win_rate | net | avg_win | avg_loss | worst_day | max_dd | p_zero |
|---|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | iron condor | buy, no stop | ALL | 390 | 11% | -386,901 | +870 | -1,217 | -4,384 | -386,901 | 0.000 |
| BANKNIFTY | iron condor | buy, stop 50% / target 100% of debit | ALL | 390 | 10% | -382,400 | +699 | -1,172 | -3,879 | -382,400 | 0.000 |
| BANKNIFTY | iron fly | buy, no stop | ALL | 390 | 14% | -365,039 | +1,633 | -1,358 | -9,220 | -365,039 | 0.000 |
| BANKNIFTY | iron fly | buy, stop 50% / target 100% of debit | ALL | 390 | 15% | -346,219 | +1,761 | -1,341 | -7,493 | -346,219 | 0.000 |
| BANKNIFTY | short strangle | buy, no stop | ALL | 394 | 26% | -335,323 | +2,240 | -1,945 | -11,524 | -345,164 | 0.000 |
| BANKNIFTY | short strangle | buy, stop 50% / target 100% of debit | ALL | 394 | 26% | -307,175 | +2,297 | -1,869 | -8,312 | -317,016 | 0.000 |
| BANKNIFTY | short straddle | buy, no stop | ALL | 394 | 30% | -314,375 | +2,579 | -2,224 | -14,111 | -325,543 | 0.000 |
| BANKNIFTY | short straddle | buy, stop 50% / target 100% of debit | ALL | 394 | 30% | -294,169 | +2,604 | -2,179 | -14,111 | -305,337 | 0.000 |
| BANKNIFTY | iron condor | buy, no stop | EXPIRY | 20 | 25% | -22,534 | +3,092 | -2,533 | -3,492 | -22,534 | 0.095 |
| BANKNIFTY | iron condor | buy, stop 50% / target 100% of debit | EXPIRY | 20 | 15% | -19,125 | +2,330 | -1,536 | -2,084 | -19,125 | 0.011 |
| BANKNIFTY | iron fly | buy, no stop | EXPIRY | 20 | 25% | -30,811 | +6,415 | -4,192 | -9,220 | -40,767 | 0.204 |
| BANKNIFTY | iron fly | buy, stop 50% / target 100% of debit | EXPIRY | 20 | 35% | -11,992 | +6,097 | -4,206 | -7,493 | -19,824 | 0.616 |
| BANKNIFTY | short strangle | buy, no stop | EXPIRY | 20 | 15% | -40,221 | +3,757 | -3,029 | -4,762 | -40,221 | 0.006 |
| BANKNIFTY | short strangle | buy, stop 50% / target 100% of debit | EXPIRY | 20 | 15% | -24,714 | +3,090 | -1,999 | -3,283 | -24,714 | 0.014 |
| BANKNIFTY | short straddle | buy, no stop | EXPIRY | 20 | 25% | -51,320 | +5,524 | -5,262 | -10,152 | -54,191 | 0.048 |
| BANKNIFTY | short straddle | buy, stop 50% / target 100% of debit | EXPIRY | 20 | 30% | -31,114 | +5,519 | -4,588 | -7,901 | -31,114 | 0.191 |
| NIFTY | iron condor | buy, no stop | ALL | 394 | 26% | -169,016 | +1,983 | -1,260 | -4,331 | -183,444 | 0.000 |
| NIFTY | iron condor | buy, stop 50% / target 100% of debit | ALL | 394 | 26% | -150,211 | +1,512 | -1,052 | -3,700 | -160,170 | 0.000 |
| NIFTY | iron fly | buy, no stop | ALL | 394 | 34% | -188,152 | +2,672 | -2,082 | -11,999 | -202,069 | 0.002 |
| NIFTY | iron fly | buy, stop 50% / target 100% of debit | ALL | 394 | 33% | -218,908 | +2,619 | -2,119 | -8,811 | -231,690 | 0.000 |
| NIFTY | short strangle | buy, no stop | ALL | 394 | 29% | -195,196 | +3,013 | -1,906 | -8,728 | -222,501 | 0.003 |
| NIFTY | short strangle | buy, stop 50% / target 100% of debit | ALL | 394 | 28% | -184,233 | +2,423 | -1,601 | -7,944 | -201,166 | 0.000 |
| NIFTY | short straddle | buy, no stop | ALL | 394 | 33% | -216,050 | +3,871 | -2,750 | -12,838 | -252,253 | 0.016 |
| NIFTY | short straddle | buy, stop 50% / target 100% of debit | ALL | 394 | 34% | -238,496 | +3,658 | -2,753 | -12,383 | -265,407 | 0.002 |
| NIFTY | iron condor | buy, no stop | EXPIRY | 86 | 26% | -41,659 | +5,146 | -2,420 | -4,187 | -60,156 | 0.269 |
| NIFTY | iron condor | buy, stop 50% / target 100% of debit | EXPIRY | 86 | 27% | -30,617 | +2,666 | -1,459 | -2,371 | -42,685 | 0.094 |
| NIFTY | iron fly | buy, no stop | EXPIRY | 86 | 43% | -19,391 | +4,464 | -3,767 | -11,999 | -62,355 | 0.698 |
| NIFTY | iron fly | buy, stop 50% / target 100% of debit | EXPIRY | 86 | 40% | -50,147 | +4,418 | -3,853 | -8,811 | -75,188 | 0.282 |
| NIFTY | short strangle | buy, no stop | EXPIRY | 86 | 24% | -56,992 | +6,398 | -2,944 | -6,679 | -79,580 | 0.269 |
| NIFTY | short strangle | buy, stop 50% / target 100% of debit | EXPIRY | 86 | 22% | -42,929 | +3,650 | -1,676 | -3,499 | -53,460 | 0.047 |
| NIFTY | short straddle | buy, no stop | EXPIRY | 86 | 34% | -36,704 | +6,943 | -4,176 | -12,838 | -89,184 | 0.590 |
| NIFTY | short straddle | buy, stop 50% / target 100% of debit | EXPIRY | 86 | 35% | -59,151 | +5,900 | -4,217 | -10,155 | -82,779 | 0.267 |

### 5. The earlier regime: Design B, 5 Aug 2021 - 19 Nov 2024 (both indices weekly, small lots), 09:20, all days

| und | struct | entry | stop | target | dayset | trades | win_rate | net | avg_win | avg_loss | worst_day | max_dd | rom | days_below_13k | months_below_13k | blocks_positive | p_zero | modelled_share |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | iron condor | 0920 | no stop | no target | ALL | 757 | 47% | -238,721 | +566 | -1,098 | -9,071 | -239,566 | -1130% | 0 | 2 | 0 | 0.000 | 78% |
| BANKNIFTY | iron condor | 0920 | leg stop 2x | no target | ALL | 757 | 32% | -217,306 | +439 | -631 | -3,169 | -217,710 | -1029% | 0 | 0 | 0 | 0.000 | 78% |
| BANKNIFTY | iron condor | 0920 | combined stop 1x credit | no target | ALL | 757 | 44% | -209,242 | +543 | -927 | -4,199 | -210,087 | -990% | 0 | 1 | 0 | 0.000 | 74% |
| BANKNIFTY | iron fly | 0920 | no stop | no target | ALL | 757 | 54% | -249,385 | +1,091 | -1,990 | -11,178 | -257,029 | -1180% | 0 | 7 | 0 | 0.000 | 77% |
| BANKNIFTY | iron fly | 0920 | leg stop 2x | no target | ALL | 757 | 49% | -175,947 | +864 | -1,303 | -8,736 | -190,026 | -833% | 0 | 3 | 0 | 0.000 | 77% |
| BANKNIFTY | iron fly | 0920 | combined stop 1x credit | no target | ALL | 757 | 54% | -249,275 | +1,091 | -1,981 | -11,178 | -258,782 | -1180% | 0 | 8 | 0 | 0.000 | 77% |
| BANKNIFTY | short strangle | 0920 | no stop | no target | ALL | 757 | 72% | +27,904 | +1,115 | -2,700 | -17,769 | -60,422 | 31% | 5 | 2 | 1 | 0.690 | 21% |
| BANKNIFTY | short strangle | 0920 | leg stop 2x | no target | ALL | 757 | 53% | +57,704 | +1,030 | -981 | -9,421 | -22,077 | 63% | 0 | 0 | 3 | 0.113 | 21% |
| BANKNIFTY | short strangle | 0920 | combined stop 1x credit | no target | ALL | 757 | 69% | +64,429 | +1,118 | -2,167 | -8,431 | -31,215 | 71% | 0 | 2 | 4 | 0.220 | 18% |
| BANKNIFTY | short straddle | 0920 | no stop | no target | ALL | 757 | 67% | +12,704 | +1,670 | -3,317 | -19,531 | -95,623 | 13% | 9 | 5 | 1 | 0.889 | 2% |
| BANKNIFTY | short straddle | 0920 | leg stop 2x | no target | ALL | 757 | 61% | +100,034 | +1,473 | -1,934 | -10,167 | -33,538 | 100% | 0 | 0 | 2 | 0.105 | 2% |
| BANKNIFTY | short straddle | 0920 | combined stop 1x credit | no target | ALL | 757 | 67% | +20,701 | +1,671 | -3,247 | -17,244 | -71,235 | 21% | 4 | 6 | 1 | 0.812 | 1% |
| NIFTY | iron condor | 0920 | no stop | no target | ALL | 786 | 50% | -216,720 | +474 | -1,018 | -8,572 | -218,488 | -1033% | 0 | 1 | 0 | 0.000 | 43% |
| NIFTY | iron condor | 0920 | leg stop 2x | no target | ALL | 786 | 37% | -191,711 | +380 | -605 | -3,061 | -191,711 | -914% | 0 | 0 | 0 | 0.000 | 42% |
| NIFTY | iron condor | 0920 | combined stop 1x credit | no target | ALL | 786 | 47% | -183,699 | +463 | -853 | -3,069 | -185,467 | -876% | 0 | 0 | 0 | 0.000 | 40% |
| NIFTY | iron fly | 0920 | no stop | no target | ALL | 786 | 52% | -237,453 | +873 | -1,597 | -10,416 | -240,030 | -1130% | 0 | 3 | 0 | 0.000 | 42% |
| NIFTY | iron fly | 0920 | leg stop 2x | no target | ALL | 786 | 47% | -196,670 | +756 | -1,159 | -7,098 | -196,869 | -936% | 0 | 3 | 0 | 0.000 | 42% |
| NIFTY | iron fly | 0920 | combined stop 1x credit | no target | ALL | 786 | 52% | -228,758 | +873 | -1,574 | -9,658 | -231,335 | -1089% | 0 | 3 | 0 | 0.000 | 42% |
| NIFTY | short strangle | 0920 | no stop | no target | ALL | 786 | 66% | -39,521 | +766 | -1,655 | -17,277 | -65,171 | -44% | 2 | 2 | 1 | 0.432 | 7% |
| NIFTY | short strangle | 0920 | leg stop 2x | no target | ALL | 786 | 52% | -10,748 | +694 | -774 | -3,889 | -38,308 | -12% | 0 | 0 | 1 | 0.683 | 7% |
| NIFTY | short strangle | 0920 | combined stop 1x credit | no target | ALL | 786 | 63% | -12,747 | +758 | -1,363 | -5,238 | -36,778 | -14% | 0 | 0 | 2 | 0.735 | 6% |
| NIFTY | short straddle | 0920 | no stop | no target | ALL | 786 | 60% | -60,282 | +1,255 | -2,100 | -18,642 | -87,734 | -61% | 3 | 3 | 1 | 0.380 | 1% |
| NIFTY | short straddle | 0920 | leg stop 2x | no target | ALL | 786 | 55% | -14,382 | +1,121 | -1,415 | -8,681 | -45,492 | -15% | 0 | 0 | 2 | 0.764 | 1% |
| NIFTY | short straddle | 0920 | combined stop 1x credit | no target | ALL | 786 | 60% | -58,870 | +1,253 | -2,060 | -14,568 | -80,121 | -59% | 1 | 2 | 0 | 0.364 | 0% |

### 6. BANKNIFTY sensitivity: Design A, only days within 5 sessions of the monthly expiry (outside the BH family)

| und | struct | entry | stop | target | dayset | trades | win_rate | net | avg_win | avg_loss | worst_day | max_dd | rom | days_below_13k | months_below_13k | blocks_positive | p_zero | modelled_share |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | iron condor | 0920 | no stop | no target | ALL | 118 | 42% | -28,828 | +1,075 | -1,215 | -8,524 | -36,159 | -72% | 0 | 0 | 1 | 0.098 | 85% |
| BANKNIFTY | iron condor | 0920 | leg stop 2x | no target | ALL | 118 | 30% | -64,726 | +1,010 | -1,206 | -6,615 | -67,996 | -162% | 0 | 1 | 0 | 0.000 | 83% |
| BANKNIFTY | iron condor | 0920 | combined stop 1x credit | no target | ALL | 118 | 40% | -41,288 | +1,014 | -1,253 | -4,766 | -48,619 | -104% | 0 | 0 | 1 | 0.012 | 82% |
| BANKNIFTY | iron fly | 0920 | no stop | no target | ALL | 118 | 52% | -2,744 | +1,935 | -2,119 | -11,344 | -39,695 | -7% | 0 | 2 | 2 | 0.933 | 86% |
| BANKNIFTY | iron fly | 0920 | leg stop 2x | no target | ALL | 118 | 45% | -50,117 | +1,550 | -2,035 | -9,645 | -63,794 | -126% | 0 | 0 | 1 | 0.073 | 86% |
| BANKNIFTY | iron fly | 0920 | combined stop 1x credit | no target | ALL | 118 | 50% | -19,497 | +1,918 | -2,249 | -9,847 | -47,414 | -49% | 0 | 1 | 2 | 0.540 | 85% |
| BANKNIFTY | short strangle | 0920 | no stop | no target | ALL | 118 | 63% | +54,939 | +2,091 | -2,268 | -10,067 | -18,147 | 31% | 0 | 0 | 4 | 0.075 | 32% |
| BANKNIFTY | short strangle | 0920 | leg stop 2x | no target | ALL | 118 | 49% | -7,992 | +2,077 | -2,141 | -7,016 | -35,523 | -5% | 0 | 0 | 1 | 0.784 | 31% |
| BANKNIFTY | short strangle | 0920 | combined stop 1x credit | no target | ALL | 118 | 61% | +37,497 | +2,066 | -2,418 | -10,483 | -24,819 | 21% | 0 | 0 | 4 | 0.240 | 32% |
| BANKNIFTY | short straddle | 0920 | no stop | no target | ALL | 118 | 61% | +83,215 | +3,120 | -3,075 | -11,815 | -26,626 | 43% | 0 | 0 | 3 | 0.058 | 1% |
| BANKNIFTY | short straddle | 0920 | leg stop 2x | no target | ALL | 118 | 53% | +15,337 | +2,770 | -2,894 | -10,458 | -28,170 | 8% | 0 | 1 | 2 | 0.713 | 1% |
| BANKNIFTY | short straddle | 0920 | combined stop 1x credit | no target | ALL | 118 | 60% | +71,711 | +3,085 | -3,134 | -11,815 | -26,626 | 37% | 0 | 0 | 3 | 0.101 | 1% |

### 7. The event days the blackout removed (Design A, 09:20, no target)

| und | struct | stop | event_days | trades | net | worst |
|---|---|---|---|---|---|---|
| NIFTY | short straddle | no stop | 12 | 12 | +30,827 | -7,103 |
| NIFTY | short straddle | leg stop 2x | 12 | 12 | +34,978 | -6,503 |
| NIFTY | short straddle | combined stop 1x credit | 12 | 12 | +30,827 | -7,103 |
| NIFTY | short strangle | no stop | 12 | 12 | +19,899 | -5,133 |
| NIFTY | short strangle | leg stop 2x | 12 | 12 | +26,634 | -2,158 |
| NIFTY | short strangle | combined stop 1x credit | 12 | 12 | +12,936 | -12,096 |
| NIFTY | iron condor | no stop | 12 | 12 | +2,299 | -2,641 |
| NIFTY | iron condor | leg stop 2x | 12 | 12 | +6,011 | -1,952 |
| NIFTY | iron condor | combined stop 1x credit | 12 | 12 | +2,299 | -2,641 |
| NIFTY | iron fly | no stop | 12 | 12 | +13,501 | -5,110 |
| NIFTY | iron fly | leg stop 2x | 12 | 12 | +16,815 | -6,346 |
| NIFTY | iron fly | combined stop 1x credit | 12 | 12 | +13,501 | -5,110 |
| BANKNIFTY | short straddle | no stop | 12 | 12 | +6,916 | -6,464 |
| BANKNIFTY | short straddle | leg stop 2x | 12 | 12 | +6,916 | -6,464 |
| BANKNIFTY | short straddle | combined stop 1x credit | 12 | 12 | +6,916 | -6,464 |
| BANKNIFTY | short strangle | no stop | 12 | 12 | +2,294 | -6,460 |
| BANKNIFTY | short strangle | leg stop 2x | 12 | 12 | -354 | -6,460 |
| BANKNIFTY | short strangle | combined stop 1x credit | 12 | 12 | +2,294 | -6,460 |
| BANKNIFTY | iron condor | no stop | 12 | 12 | -16,040 | -2,536 |
| BANKNIFTY | iron condor | leg stop 2x | 12 | 12 | -16,587 | -2,536 |
| BANKNIFTY | iron condor | combined stop 1x credit | 12 | 12 | -16,040 | -2,536 |
| BANKNIFTY | iron fly | no stop | 12 | 12 | -11,418 | -2,854 |
| BANKNIFTY | iron fly | leg stop 2x | 12 | 12 | -11,418 | -2,854 |
| BANKNIFTY | iron fly | combined stop 1x credit | 12 | 12 | -11,418 | -2,854 |

On these 12 days (RBI decisions and the 2026 budget) the naked sellers made money: the scheduled event's premium was
crushed and the moves stayed inside it. Twelve days are too few to say more; the counting day of 2024 (4 Jun 2024,
Design B) is the counter-example, in the tail table of the guide.

## Follow-up (exploratory, after the holdout read): does a profit lock help?

Boss asked whether a stop-loss plus a profit lock rescues the NIFTY expiry-day strangle. Not pre-registered; the holdout
was not read again. 54 variants (3 entries x 3 stops x 6 locks: once the profit reaches 30 / 40 / 50% of the credit,
close if it falls back to 10-25%), 86 expiry days in Design A and the same rules on Design B
(`results/lock_explore.csv`, entry `r103-deep-lock-explore`):

- The lock beat the same cell without a lock in only **23 of 54** variants in Design A and **23 of 54** in Design B.
- It raises the win rate and lowers the profit: at 09:20 with the combined stop, a 30/20 lock took wins from 67% to 79%
  and the net from +Rs 63,930 to +Rs 28,651. It closes good days early and does nothing on the bad ones, which the stop
  already handles.
- The 18 variants with q <= 0.10 are all the 09:45 combined-stop cell, whose pre-registered version lost in the holdout.
- In Design B, 13 of the 54 lock variants lose money.

**Verdict: a profit lock does not rescue it.** Rejected.

## Follow-up 2: Boss prefers win rate to profit; the enhancement search and the forward test

Boss (22 Oct): "Less profit works for me, but the win rate is more important. Ok, and try to enhance it." The one lock
variant with a win rate of at least 70% and a profit in both periods (10:15, combined stop at 100% of the credit, lock
30/20) became the base rule. A search of 90 enhancements and a forward test were pre-registered before anything was run
(`research/hunt/r103_selling/PREREG_FWD.md`). Results (`results/fwd_search.csv`), one lot, after costs:

| rule | 2024-26: trades, win, net, a trade | 2021-24: trades, win, net, a trade | avg loss / avg win (2024-26) |
|---|---|---|---|
| base: 10:15, 1x width, exit 15:05, no filter | 86, 74%, +Rs 10,607, +Rs 123 | 165, 70%, +Rs 12,418, +Rs 75 | 2.5 |
| 11:00 entry, skip a gap open > 0.6% | 74, 77%, +Rs 1,168, +Rs 16 | 132, 73%, +Rs 7,659, +Rs 58 | 3.3 |
| 10:15, exit 14:30 | 86, 76%, +Rs 4,533, +Rs 53 | 165, 72%, +Rs 8,348, +Rs 51 | 2.9 |
| 10:15, exit 14:30, skip a gap open | 74, 76%, +Rs 4,377, +Rs 59 | 132, 72%, +Rs 6,650, +Rs 50 | 2.9 |

- Only 6 of 90 variants won at least 70% and made money in both periods. The finalists, chosen by the fixed rule, add
  1-3 points of win rate and give up half or more of the profit a trade. Later entries, wider strikes and the RANGE / ALL
  filters mostly hurt. The base rule is still the best balance.
- All four rules go to the forward test unchanged: NIFTY expiry days from 20 Oct 2026, scored by `r103fwd.py score` from
  the lake. The decision comes after 40 forward expiries (about nine months): a win rate of at least 65% that beats a coin
  toss (binomial p <= 0.05, BH across the four), a profit, no month below -Rs 13k, and an average loss no more than 3x the
  average win. Nothing trades meanwhile.

## Idea 1: sell only when options are expensive (pre-registered `PREREG_VRP.md`, 144 cells)

Sell the straddle, strangle or condor at 09:20 only when the options' implied volatility (the ATM option's, or India
VIX) is at least 1.0, 1.25 or 1.5 times the index's realised volatility over the last 20 sessions. Combined stop, with
and without the 30/20 lock, all days and expiry days, both indices. `results/vrp_cells.csv`.

- **0 of 144 pass.** The holdout was not read.
- **In 2024-26 the filter is real.** It raised the profit a trade in 105 of 144 cells, and 27 beat a random choice of the
  same number of days. Example: NIFTY straddle, combined stop, ATM IV at least the realised volatility: 192 trades, 64%
  wins, +Rs 115,619, Rs 602 a trade against Rs 225 without the filter (twin p 0.04).
- **In 2021-24 it is not.** That same cell lost -Rs 38,672 over 373 trades, worse a trade than without the filter. Only
  20 of 144 cells made money in the earlier period, and only 3 cells survive the multiple-testing correction in 2024-26.
- Verdict: rejected. The effect depends on the period; it would be worth a forward test only with a reason why 2021-24
  behaved differently.

## Idea 2: a credit spread in the direction of Boss's own signals (pre-registered `PREREG_DIR.md`, 18 cells)

On each Liquidity 15+5 BANKNIFTY signal (the live arm) and each Donchian 20 signal on NIFTY and BANKNIFTY (the paper arm),
sell a 4-strike credit spread on the side the signal favours (a put spread on a bullish signal), short strike at the money
or 2 strikes out, with a combined stop, the 30/20 lock, or the arm's own exit. `results/dir_cells.csv`.

| signal | trades | win rate | net, one lot |
|---|---|---|---|
| Liquidity 15+5, BANKNIFTY | 238-339 | 17-35% | -Rs 75k to -Rs 125k |
| Donchian 20, BANKNIFTY | 375-1,185 | 16-39% | -Rs 98k to -Rs 460k |
| Donchian 20, NIFTY | 320-1,068 | 36-64% | -Rs 187k to +Rs 11k (best: at the money with the lock, 62% win, p 0.79) |

- **0 of 18 pass; 17 lose.**
- The signals do carry some direction: trading the opposite side lost more in every cell, and in 5 cells the real side
  beat a random side (p <= 0.05). But the spread cannot turn that into money.
- **Why: an intraday spread on an ordinary day earns almost no decay.** The theta table above shows the 09:20 straddle
  still worth 96-99% of its value at 15:05 three or more sessions before expiry. With so little decay, four orders of
  charges and spreads decide the result, and the win rate falls below 40% on BANKNIFTY.

## What the four tests together say

Every intraday version of selling has now been tried: at a fixed time, with stops and locks, with a volatility filter,
and in the direction of Boss's signals. The consistent finding is that **an option seller is paid for holding time,
especially overnight and over weekends, and intraday trades collect very little of it** except on expiry day, where the
gamma risk is largest. The one untested idea with a strong reason left is a hedged multi-day hold (for example a NIFTY
weekly iron condor or credit spread held to expiry), which earns the overnight decay and caps the gap risk with the wings.

## Caveats, stated plainly

1. **The lake keeps only ATM±10 strikes.** A leg that drifts out of that window has no price. The first run valued such
   legs at their entry price, which invented profit for the hedged structures (a BANKNIFTY iron fly showed +Rs 5.6 lakh).
   It was discarded. The final model uses the leg's own last implied volatility, scaled by the nearest printed strike's IV
   change; calibrated on 2025 prints it reads 0.98-1.00 of the actual price at the median when the IV is 30-120 minutes
   old. The other R103 study found the same trap independently.
2. **BANKNIFTY's hedged cells are mostly model-priced** (95% of trades), because its monthly straddle (1,500-1,900 points)
   is wider than the lake's window. They are less reliable than the NIFTY ones. The DTE ≤ 5 sensitivity (where the window
   is wide enough) still loses: iron condor −Rs 28,828 and iron fly −Rs 2,744 at 09:20 with no stop (118 days each).
3. **Crash days are model-priced too.** On 4 Jun 2024 the 09:20 ATM put left the window by 11:06. This study values the
   NIFTY straddle's loss that day at −Rs 7,167 (lot 25); the other study, with a slightly different model, at −Rs 10,846.
   Both are estimates. The naked straddle cells are model-priced on only 0-2% of trades, so their totals barely move.
4. **Spreads are modelled** from one real snapshot (0.16% of the premium a side at the money, h24); stop fills are charged
   double. A real panic is wider.
5. **What had been seen.** A shake-out run (discarded) executed the holdout branch for cells that passed only because of the
   entry-price bug, so the holdout window is not fully unseen for iron flies. One full run with a rejected IV model was
   seen before the final one (no cell passed; the broad picture was the same). The final run's rules and gates were fixed
   before it. The holdout was read once, for the 3 passers, before the other study's write-up was opened.
6. **The margin is an estimate** (11% of the contract for a naked straddle, 10% for a strangle, the max loss for hedged, +2%
   on expiry days). It only sets the 3%-of-margin stop and the return-on-margin column.

## Checks

`python -m pytest research/hunt/r103_selling/test_r103.py -q`: 31 tests, including the look-ahead guard on synthetic and
real lake days (every decision, every model value and every stopped trade is unchanged when everything after it is blanked
or replaced by absurd prices). `python -m pytest research/brain/tests -q` (183 passed), `python -m research.brain.features
catalog --check`, `python research/experiments/validate.py`, the CLAUDE.md secret grep: all clean.
