# R140: does higher-high / lower-low structure help our paper arms lose less? No. But one fake-breakout filter passed

31 Oct 2026. Research only: nothing in the app changes.

Boss, 31 Oct:
- *"Will the higher high and lower low, and equity shift work for our existing strategies"*;
- *"yes run it as R140"*;
- *"profit is not important, losing money is important, we should always check how could we lose less"*;
- *"If possible deep analysis the trades, and try to filter fake Breakout"*.

**Signal source:** the index for every rule. The arms keep buying their own options with their own stops and locks.

## The answer

**1. Market structure does not help the arms (0 of 12).**
- **Skipping trades when the 15-min or 5-min trend (HH+HL / LH+LL) is against them** does cut losses. But it cuts them
  only as much as skipping the same number of trades at random would (final p 0.17-0.99), and it usually costs a lot
  of profit.
  - The closest was the 5-minute filter on Liquidity (T5-L). It cut losses in every period, the arm stayed in profit,
    and it even made more. But random skipping did as well (p 0.59).
- **Exiting when the index makes a structure shift against the trade** barely changes anything: the arms' own stops
  usually come first. Only 12-42 of 1,202-1,670 final trades changed. The 15-min exit made losses slightly worse on
  both arms; the 5-min exit cut them a little, by no more than random exit times would (p 0.99 and 1.0).
- **In the trade analysis, a trade "against" the 15-min trend was not worse.** R72's trades against the trend made
  +Rs 148 each (948 trades), with the trend +Rs 121 (942). The worst were trades when the trend was **unclear**
  (-Rs 32, 1,181).

**2. The fake-breakout hunt found one filter that passes every lose-less gate (FB-R72-A10).**
- **The rule:** on R72 Breadth Donchian, skip the day's breakouts when **yesterday's range was in the top fifth of its
  20-day average** (more than about 1.32x the average daily range). On days after a very wide day, breakouts more
  often fail.
- **Final period (1 Apr - 13 Oct 2026), against today's R72:**
  - losing days: -Rs 3,00,084 instead of -Rs 3,89,644 (23% less);
  - drawdown: Rs 56,200 instead of Rs 61,884;
  - worst month: -Rs 30,518 instead of -Rs 40,908;
  - profit: **Rs 1,93,840 instead of Rs 1,71,366** (it also made more);
  - 1,015 trades instead of 1,202.
  - Random skipping of the same number of trades did this well only 1 time in 2,000 (p 0.001, q 0.002 over the 7).
- **It held in every period:**
  - design: losing days -Rs 8.61 lakh vs -Rs 11.54 lakh, profit Rs 2.65 lakh vs Rs 2.17 lakh;
  - walk-forward year: losing days -Rs 4.97 lakh vs -Rs 7.08 lakh, profit Rs 3.43 lakh vs Rs 3.36 lakh.
- **The same idea on R51** (Donchian 20 + Supertrend) did as well on losses and profit, with final p 0.0004. It
  missed by one gate: its worst month in the walk-forward year was -Rs 58,186 against -Rs 53,654.

**Honest caveats on the pass:**
- **The slice was chosen on the design data** by the pre-registered rule. The design and walk-forward numbers are
  in-sample; the final is the real test.
- **The final months are not fresh:** earlier studies read them, though never with this filter.
- **With every earlier family counted** (R115's convention, about 9.3 million tests), its q would be about 1. Under
  R140's own family of 7, q is 0.002.
- **It wins 46.5% of trades**, far from Boss's 65%. It is a loss cut, not a win-rate change.
- **R72 alone still has a -Rs 30,518 month** on 1 lot, past the -Rs 13k monthly account stop.

## Proposed (not built): FB-R72-A10 as a paper switch for Boss

- **On paper (CLAUDE.md: a passed idea may act on a paper arm):** on R72 Breadth Donchian, skip new entries when the
  index's previous session range is more than its top-fifth cut of the 20-session average range. That is about 1.32x,
  using the design cut; the exact cut per index is in `results/design_a.json`.
  - It would be logged in the Autopilot change timeline, labelled, undoable in one tap and shown to Boss.
  - R72's plain-rules record must stay separable.
- **Live:** it only skips trades, so it lowers risk, but it would start in **shadow** (logged only), behind a switch
  that is off by default.
- **Also worth a forward look:** the same rule on R51, which failed by one gate.
- Building it is an app change for a separate session, with its own tests and review.

## The trade analysis (design, 20 Nov 2024 - 31 Mar 2026; today's rules)

**Most trades lose:** R72 53% of 3,071, R51 56% of 4,208, Liquidity 56% of 881. The arms make money because winners
are bigger. What separates the fake breakouts (losers), in average net per trade by fifth (lowest to highest), and
what was found:

- **Yesterday was a wide day.** This is the strongest and most orderly pattern.
  - R72: +Rs 306, +Rs 85, +Rs 49, -Rs 8, -Rs 78.
  - R51: +Rs 100, +Rs 75, +Rs 71, -Rs 58, -Rs 129.
  - The widest fifth lost in both halves of the period on both arms.
- **The push into the entry.** Breakouts with little momentum fail more.
  - R72: -Rs 90, -Rs 179, -Rs 1, +Rs 270, +Rs 352.
  - Liquidity: +Rs 54 up to +Rs 616.
  - Not chosen: the weakest fifth was not the worst in both halves on every arm.
- **Fake pokes earlier today.** If the index had already poked to a new day extreme and closed back, breakouts later
  that day do worse.
  - R72: 0 pokes +Rs 190 (1,638 trades); 1, 2 and 3+ pokes -Rs 45, -Rs 151, -Rs 21.
  - R51: 0 pokes +Rs 118; 1 poke -Rs 193.
- **Not yet beyond yesterday's high or low** (for a call, still below yesterday's high).
  - R72: -Rs 87.
  - R51: -Rs 88 and -Rs 103 in the two lowest fifths.
- **A gap against the trade.**
  - R72: -Rs 133.
  - Liquidity: -Rs 248.
  - Chosen for R72 and Liquidity, but it **failed** on the final (p 0.48-0.50).
- **Time of day.**
  - Entries in the first fifth of the day made the most (R72 +Rs 301, Liquidity +Rs 587).
  - The middle fifth was the worst (R72 -Rs 184, R51 -Rs 161), as R113 found.
- **VIX regime (high / low)** and weekday made little difference.

Full slice tables per arm, per feature and per half: `research/hunt/r140_structure_arms/results/design_a.json`.

## Part 1: the 12 structure tests (lose-less gates; final 1 Apr - 13 Oct 2026)

"Losing days" is the sum of all losing days (Rs). "Today" is the arm as it runs on paper now.

| test | arm | rule | design: trades, net, losing days (today) | walk-forward: net, losing days, drawdown (today) | final: trades, net, losing days, drawdown (today) | p | result |
|---|---|---|---|---|---|---|---|
| T15-72 | R72 | skip if the 15-min trend is against | 2,263, Rs 81,166, Rs -933,143 (3,071, Rs 216,929, Rs -1,154,058) | Rs 163,699, Rs -594,632, Rs 85,169 (Rs 335,882, Rs -708,002, Rs 86,529) | 896, Rs 43,571, Rs -362,429, Rs 100,252 (1,202, Rs 171,366, Rs -389,644, Rs 61,884) | 0.99 | fails |
| T5-72 | R72 | skip if the 5-min trend is against | 2,577, Rs 168,281, Rs -942,638 (3,071, Rs 216,929, Rs -1,154,058) | Rs 321,830, Rs -546,726, Rs 105,431 (Rs 335,882, Rs -708,002, Rs 86,529) | 980, Rs 55,659, Rs -376,728, Rs 102,977 (1,202, Rs 171,366, Rs -389,644, Rs 61,884) | 0.98 | fails |
| T15-51 | R51 | skip if the 15-min trend is against | 3,187, Rs -41,726, Rs -1,097,636 (4,208, Rs 50,425, Rs -1,346,619) | Rs 58,933, Rs -717,126, Rs 83,723 (Rs 213,748, Rs -851,416, Rs 94,176) | 1,269, Rs -35,588, Rs -379,104, Rs 122,029 (1,670, Rs 134,743, Rs -395,932, Rs 81,683) | 0.79 | fails |
| T5-51 | R51 | skip if the 5-min trend is against | 3,638, Rs 64,706, Rs -1,103,027 (4,208, Rs 50,425, Rs -1,346,619) | Rs 199,820, Rs -687,000, Rs 89,982 (Rs 213,748, Rs -851,416, Rs 94,176) | 1,446, Rs 24,054, Rs -395,782, Rs 102,144 (1,670, Rs 134,743, Rs -395,932, Rs 81,683) | 0.87 | fails |
| T15-52 | R52 | skip if the 15-min trend is against | 565, Rs 136,099, Rs -539,691 (706, Rs 290,573, Rs -626,170) | Rs 117,137, Rs -374,505, Rs 50,771 (Rs 247,057, Rs -427,746, Rs 49,847) | 215, Rs 44,056, Rs -159,243, Rs 30,088 (293, Rs 48,612, Rs -223,320, Rs 47,194) | 0.17 | fails |
| T5-52 | R52 | skip if the 5-min trend is against | 580, Rs 208,650, Rs -540,391 (706, Rs 290,573, Rs -626,170) | Rs 211,054, Rs -361,040, Rs 47,929 (Rs 247,057, Rs -427,746, Rs 49,847) | 217, Rs 61,300, Rs -162,744, Rs 36,675 (293, Rs 48,612, Rs -223,320, Rs 47,194) | 0.24 | fails |
| T15-L | LIQ | skip if the 15-min trend is against | 679, Rs 177,877, Rs -266,334 (881, Rs 257,249, Rs -289,743) | Rs 154,408, Rs -179,645, Rs 30,328 (Rs 238,064, Rs -187,995, Rs 23,139) | 275, Rs -9,844, Rs -121,554, Rs 51,091 (357, Rs 11,155, Rs -144,353, Rs 61,062) | 0.60 | fails |
| T5-L | LIQ | skip if the 5-min trend is against | 723, Rs 275,142, Rs -238,423 (881, Rs 257,249, Rs -289,743) | Rs 237,955, Rs -159,293, Rs 17,926 (Rs 238,064, Rs -187,995, Rs 23,139) | 287, Rs 23,572, Rs -123,951, Rs 42,447 (357, Rs 11,155, Rs -144,353, Rs 61,062) | 0.59 | fails |
| M15-72 | R72 | exit on a 15-min structure shift | 3,078, Rs 198,144, Rs -1,140,349 (3,071, Rs 216,929, Rs -1,154,058) | Rs 312,769, Rs -700,003, Rs 86,465 (Rs 335,882, Rs -708,002, Rs 86,529) | 1,203, Rs 153,354, Rs -390,984, Rs 63,258 (1,202, Rs 171,366, Rs -389,644, Rs 61,884) | 0.91 | fails |
| M5-72 | R72 | exit on a 5-min structure shift | 3,071, Rs 214,518, Rs -1,146,355 (3,071, Rs 216,929, Rs -1,154,058) | Rs 329,619, Rs -704,134, Rs 87,417 (Rs 335,882, Rs -708,002, Rs 86,529) | 1,202, Rs 177,131, Rs -383,777, Rs 59,358 (1,202, Rs 171,366, Rs -389,644, Rs 61,884) | 0.99 | fails |
| M15-51 | R51 | exit on a 15-min structure shift | 4,215, Rs 40,148, Rs -1,328,806 (4,208, Rs 50,425, Rs -1,346,619) | Rs 205,657, Rs -838,481, Rs 94,176 (Rs 213,748, Rs -851,416, Rs 94,176) | 1,671, Rs 124,279, Rs -401,063, Rs 79,416 (1,670, Rs 134,743, Rs -395,932, Rs 81,683) | 1.00 | fails |
| M5-51 | R51 | exit on a 5-min structure shift | 4,209, Rs 34,686, Rs -1,344,121 (4,208, Rs 50,425, Rs -1,346,619) | Rs 193,669, Rs -852,340, Rs 95,492 (Rs 213,748, Rs -851,416, Rs 94,176) | 1,670, Rs 141,846, Rs -390,603, Rs 76,492 (1,670, Rs 134,743, Rs -395,932, Rs 81,683) | 1.00 | fails |

## Part 2: the fake-breakout filters (Amendment A)

The rule that chose them was fixed in `PREREG_A.md` before any per-feature number. For each arm, take the extreme
fifth with the worst average net; keep it only if it lost money, and did worse than the arm's average, in both halves
of the design period separately. Then keep the 2 that cut the most losing days.

| test | what is skipped | design: trades, net, losing days (today) | walk-forward: net, losing days, drawdown, worst month (today) | final: trades, net, losing days, drawdown, worst month (today) | p | q | result |
|---|---|---|---|---|---|---|---|
| **FB-R72-A10** | days after a wide day (yesterday's range in the top fifth vs its 20-day average) | 2,455, Rs 264,802, Rs -861,407 (3,071, Rs 216,929, Rs -1,154,058) | Rs 343,121, Rs -497,421, Rs 69,351, Rs -55,426 (Rs 335,882, Rs -708,002, Rs 86,529, Rs -72,605) | 1,015, Rs 193,840, Rs -300,084, Rs 56,200, Rs -30,518 (1,202, Rs 171,366, Rs -389,644, Rs 61,884, Rs -40,908) | 0.0010 | 0.002 | **passes** |
| FB-R72-C1 | a gap against the trade (lowest fifth) | 2,457, Rs 298,559, Rs -933,994 (3,071, Rs 216,929, Rs -1,154,058) | Rs 369,470, Rs -572,746, Rs 54,849, Rs -40,285 (Rs 335,882, Rs -708,002, Rs 86,529, Rs -72,605) | 897, Rs 154,017, Rs -333,150, Rs 79,289, Rs -53,573 (1,202, Rs 171,366, Rs -389,644, Rs 61,884, Rs -40,908) | 0.4953 | 0.495 | fails: wf: worst day worse; final: drawdown worse; final: worst month worse; twin p 0.495 >= 0.05; BH q 0.495 > 0.1 |
| FB-R72-both | days after a wide day (yesterday's range in the top fifth vs its 20-day average) or a gap against the trade (lowest fifth) | 2,003, Rs 289,798, Rs -726,341 (3,071, Rs 216,929, Rs -1,154,058) | Rs 339,167, Rs -423,104, Rs 54,425, Rs -38,554 (Rs 335,882, Rs -708,002, Rs 86,529, Rs -72,605) | 763, Rs 172,143, Rs -255,206, Rs 57,810, Rs -34,222 (1,202, Rs 171,366, Rs -389,644, Rs 61,884, Rs -40,908) | 0.0050 | 0.009 | fails: wf: worst day worse |
| FB-R51-A10 | days after a wide day (yesterday's range in the top fifth vs its 20-day average) | 3,366, Rs 158,882, Rs -1,001,569 (4,208, Rs 50,425, Rs -1,346,619) | Rs 291,181, Rs -606,212, Rs 73,810, Rs -58,186 (Rs 213,748, Rs -851,416, Rs 94,176, Rs -53,654) | 1,394, Rs 157,384, Rs -316,936, Rs 70,047, Rs -48,813 (1,670, Rs 134,743, Rs -395,932, Rs 81,683, Rs -54,587) | 0.0004 | 0.002 | fails: wf: worst month worse |
| FB-R51-C3 | VIX 30-min change in its lowest fifth | 3,512, Rs 105,874, Rs -1,147,321 (4,208, Rs 50,425, Rs -1,346,619) | Rs 182,096, Rs -744,899, Rs 81,411, Rs -59,159 (Rs 213,748, Rs -851,416, Rs 94,176, Rs -53,654) | 1,154, Rs 52,182, Rs -316,417, Rs 87,973, Rs -43,836 (1,670, Rs 134,743, Rs -395,932, Rs 81,683, Rs -54,587) | 0.0375 | 0.052 | fails: design: worst month worse; wf: worst month worse; final: drawdown worse |
| FB-R51-both | days after a wide day (yesterday's range in the top fifth vs its 20-day average) or VIX 30-min change in its lowest fifth | 2,842, Rs 170,540, Rs -859,042 (4,208, Rs 50,425, Rs -1,346,619) | Rs 230,390, Rs -535,626, Rs 72,634, Rs -61,316 (Rs 213,748, Rs -851,416, Rs 94,176, Rs -53,654) | 993, Rs 67,497, Rs -261,054, Rs 58,851, Rs -38,197 (1,670, Rs 134,743, Rs -395,932, Rs 81,683, Rs -54,587) | 0.0010 | 0.002 | fails: wf: worst month worse |
| FB-LIQ-C1 | a gap against the trade (lowest fifth) | 705, Rs 300,980, Rs -214,859 (881, Rs 257,249, Rs -289,743) | Rs 254,925, Rs -148,508, Rs 19,839, Rs -13,591 (Rs 238,064, Rs -187,995, Rs 23,139, Rs -17,360) | 318, Rs 12,601, Rs -133,241, Rs 62,351, Rs -39,946 (357, Rs 11,155, Rs -144,353, Rs 61,062, Rs -37,821) | 0.4773 | 0.495 | fails: final: drawdown worse; final: worst month worse; twin p 0.477 >= 0.05; BH q 0.495 > 0.1 |

## Method and honesty notes

- **Pre-registered:**
  - `research/hunt/r140_structure_arms/PREREG.md` (commit bec2133) and `PREREG_A.md` (7fdb1c2);
  - R140's code and tests were in bec2133; Amendment A's code and tests in 9a231cc;
  - design results were pushed before each final read: 6429b13 and 3108fe8 (`results/FINAL_READ`, `FINAL_READ_A`).
- **Engine:** R115's engine reproduced all four arms' published trades and net to the rupee in design. The final
  matched too: R72 1,202 / Rs 1,71,366, Liquidity 357 / Rs 11,155.
- **Data:** the Drive lake, copied 31 Oct; R51's clean stores. 1 lot; R17 fills; dated Zerodha charges.
- **The gates were about losing less (Boss, 31 Oct).** In design, the walk-forward year and the final:
  - the sum of losing days smaller;
  - drawdown, worst day and worst month no worse;
  - the arm still in profit.

  Plus: better than 2,000 random-skip (or random-exit) twins, p < 0.05, and BH q <= 0.10.
- **Tests:** `test_r140.py`, `test_r140a.py` (11 tests, including look-ahead guards for every feature and rule).
- **Entries:** `research/experiments/entries/r140-*.yaml` (12, rejected) and `r140a-*.yaml` (7: 1 passed, 6
  rejected).
