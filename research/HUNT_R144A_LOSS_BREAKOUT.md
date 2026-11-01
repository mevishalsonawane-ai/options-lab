# R144a: why R72, R51 and R52 lose, looked at through volume, liquidity, OI, market context and order flow

1 Nov 2026. Research only. **Nothing in the app or any arm has changed.** One of four parallel studies (R144a-d); this
one covers **R72 Breadth Donchian, R51 Donchian 20 + Supertrend and R52 Open outside value**. Pre-registration:
`research/hunt/r144a_loss_breakout/PREREG.md` (pushed before any number, 387388a); the filter choice was frozen from
design data and pushed (6b8f6b4) before the final months or the unseen two years were read.

Boss, 1 Nov: *"Find all the lost trades ... find what all makes it lose money ... volume, liquidity, order flow ...
and also find patterns for each arm, so it could help us to ignore those trades."*

## The answer

1. **No new skip filter passed.** Four were chosen from design data (2 on R72, 2 on R51; none qualified on R52). Each
   failed in the final months (Apr - Oct 2026): none beat random skipping there (p 0.07-0.70), and three made the
   worst month worse. None passes BH (q 0.29-0.70).
   - Two looked strong on the unseen two years (Dec 2022 - Nov 2024) and then failed in the final months:
     - **R72, skip after a heavy futures-volume day** (yesterday's index-futures volume in the top fifth vs its 20-day
       average). Unseen: losing days -Rs 6.58 lakh instead of -Rs 8.87 lakh, beat random at p < 0.001. Final: profit
       Rs 1.34 lakh instead of Rs 1.71 lakh, a bigger drawdown and worst month, p 0.27.
     - **R51, skip when yesterday's futures showed short covering against the trade.** Unseen p < 0.001, but the
       worst month got worse in both test periods; final p 0.07.
   - **Win rates stay far below Boss's 65% rule:** today 39-47% on R72 and R51; the filters moved them by at most one
     point.
2. **The volume, liquidity, OI and order-flow numbers do not separate R72's losers in a steady way.** On R72 every
   steady losing sign is still one of R141's price signs: a weak push, a quiet signal bar, a very wide previous day,
   an earlier fake poke.
3. **R51 has steady losing slices on the new features too, but they are weak.** Losers 56-64% in every period,
   average losses of only Rs 2-174 a trade. The clearest:
   - the option's own tape is quiet (lowest fifth of minute range / price): loses 62% / 58% / 58%;
   - neutral option buying pressure (middle fifth of the volume-delta proxy): 61% / 60% / 64%.
   They describe "R51 on an ordinary, quiet break", not a separate kind of trade.
4. **R52 has one steady sign, R141's: a quiet first five minutes** (09:15-09:20 range in the lowest fifth vs ATR):
   loses 55% / 49% / 59%, about -Rs 76 to -Rs 241 a trade. High VIX makes R52 lose more often in every period (66% /
   49% / 48% in the top VIX fifth vs 49% / 29% / 32% in the bottom) but its winners are bigger then, so it is not a
   loss filter.
5. **Where the money goes (the flaws, plainly):**
   - **Stops** take 77% of R72's and R51's lost money in the unseen years, 52-54% in design and 42-43% in the final.
   - **Trades that never worked** (option never up 5%) are 44-50% of the lost money in design and final on R72 and
     R51, 30-31% in the unseen years.
   - **Give-backs** (up 15%+, then a loss) are 22-40%.
   - **R52 loses mostly at its time exit:** 63% of its lost money in design, 85% in the final.
   - **MIDCPNIFTY dominates the worst losses:** 13-15 of the 20 worst trades of each arm. Its 1-ITM premium and lot
     make single losses of about Rs 5,700-14,600.
   - **The opening break is the riskiest entry:** 9 of R72's and 12 of R51's 20 worst trades were filled at 09:20
     (R52 always enters then).
   - **Losses bunch:** 42-51% of R72's losers fall on index-days where every R72 trade lost; the longest losing runs
     are up to 29 trades (R72) and 20 (R51).
6. **The arms lost money in the unseen two years** (R72 -Rs 3.07 lakh, R51 -Rs 4.00 lakh, R52 -Rs 1.52 lakh; as R143).
   Loser rates were higher then: 61% on R72 and R51, 54% on R52.

## Follow-up (1 Nov): can we skip these trades? One test on a year no study had used (Nov 2021 - Nov 2022)

Boss: *"Can we find any way to skip this trades"*, then chose a one-shot test on the only unused period. Pre-registered
in `research/hunt/r144a_fresh_2021/PREREG.md` (d7fdafd) before any number. Six rules, frozen exactly as found earlier.
**The arms lost money in this year too** (R72 -Rs 67,223, R51 -Rs 70,679, R52 -Rs 48,615; 1 lot). Only NIFTY,
BANKNIFTY and FINNIFTY traded (SENSEX had no options yet; MIDCPNIFTY's few option days gave no trades). The fresh store
reproduced R143's trades exactly on 1 - 30 Dec 2022 (R72 109, R51 153, R52 36).

**The answer: yes, two ways to skip or cut these losses keep working, and one more joins them as a candidate.**

| rule | trades | win rate | net | losing days (sum) | drawdown | worst day | worst month | beats random? | result |
|---|---|---|---|---|---|---|---|---|---|
| **R72: skip after a very wide day** (FB-R72-A10) | 1,169 → 970 | 44% → 44% | -Rs 67,223 → **-Rs 53,404** | -Rs 3,88,781 → **-Rs 3,15,129** | Rs 1,15,480 → Rs 86,456 | -Rs 13,602 → -Rs 13,602 | -Rs 23,441 → -Rs 21,333 | p < 0.001 | **passes (6th period in a row)** |
| **R52: sell at 09:25 if not up 5%** (NP5-52) | 292 → 292 | 46% → 36% | -Rs 48,615 → **-Rs 30,176** | -Rs 2,41,734 → **-Rs 1,87,123** | Rs 60,851 → Rs 44,010 | -Rs 8,341 → -Rs 6,185 | -Rs 17,157 → -Rs 13,899 | p 0.027 | **passes (6th period in a row)** |
| **R72: skip after a heavy futures-volume day** (R144A-72-V4) | 1,169 → 1,039 | 44% → 43% | -Rs 67,223 → -Rs 63,797 | -Rs 3,88,781 → **-Rs 3,31,389** | Rs 1,15,480 → Rs 98,343 | -Rs 13,602 → -Rs 8,179 | -Rs 23,441 → -Rs 22,449 | p < 0.001 | passes here; it **failed** Apr - Oct 2026, so a mixed record |
| R72: skip bad breaks (BBX-72) | 1,169 → 443 | 44% → 47% | -Rs 67,223 → Rs 427 | -Rs 3,88,781 → -Rs 1,74,143 | Rs 1,15,480 → Rs 34,280 | -Rs 13,602 → -Rs 6,552 | -Rs 23,441 → -Rs 10,790 | p 0.52 | fails: random skipping of 62% of trades does as well |
| R51: skip bad breaks (BBX-51) | 1,392 → 588 | 42% → 43% | -Rs 70,679 → -Rs 35,498 | -Rs 4,26,034 → -Rs 2,40,189 | Rs 1,55,398 → Rs 69,617 | -Rs 11,738 → -Rs 6,042 | -Rs 26,825 → -Rs 12,673 | p 0.985 | fails: random skipping does better |
| R51: skip after futures short covering against the trade (R144A-51-F4) | 1,392 → 1,226 | 42% → 42% | -Rs 70,679 → -Rs 73,899 | -Rs 4,26,034 → -Rs 4,01,235 | Rs 1,55,398 → Rs 1,54,553 | -Rs 11,738 → -Rs 11,738 | -Rs 26,825 → -Rs 33,437 | p 0.22 | fails |

BH over the six: q 0.0001 (A10), 0.0015 (V4), 0.054 (NP5), 0.33, 0.62, 0.985. Raw p-values:
`research/hunt/r144a_fresh_2021/results/pvalues.json`.

**What it means:**
- **R72's wide-day skip has now cut losses in six periods out of six**, including two blind tests. It is the most
  reliable way to skip R72's losing trades we have.
- **R52's 09:25 exit has also held in six of six.** It cuts the size of losses, not their number: the win rate drops
  (46% to 36% here).
- **Caveat, by half-year:** in Nov 2021 - May 2022 the wide-day skip made the net a little worse (-Rs 13,217 vs
  -Rs 9,696) and the worst month a little worse (-Rs 21,333 vs -Rs 20,123), though losing days fell. NP5-52's worst
  month was worse in Jun - Nov 2022 (-Rs 13,899 vs -Rs 5,673). The gates are on the whole year, as pre-registered.
- **R72's heavy-futures-day skip (V4)** passed here and on Dec 2022 - Nov 2024, but failed Apr - Oct 2026. As fixed in
  the pre-registration, that makes it a candidate for a **forward shadow log**, not a switch.
- **R51 still has no rule that works.** And no rule raises any arm near Boss's 65% win rate.
- **The arms lost money in every year before Nov 2024** (this year and both R143 years). Their edge is recent.

**Proposed (not built, Boss decides):**
1. FB-R72-A10 and NP5-52 as labelled **paper** switches: shadow log first, off by default, Autopilot timeline, one-tap
   undo, the arm's plain record kept separate.
2. R144A-72-V4 in **shadow only** (logged, no effect) on new days.
3. Live: all three only skip or exit earlier (they lower risk), and would still start in shadow behind a switch that is
   off by default.

## What is missing from the data (said plainly)

- **No bid-ask quotes, no market depth, no historical order book** anywhere in the lake. The liquidity and order-flow
  numbers here are **proxies**: the option's minute range / price (spread proxy), minutes with no print or zero volume,
  a signed-volume "delta" from the option's own minute bars, the index's up / down minute share, and the OI-price
  quadrant (long build-up, short build-up, short covering, long unwinding) on the traded option and on the previous
  day's index futures.
- **No index-futures minute data before 29 Jul 2026.** The "volume vs its usual for that minute" uses the ATM±2 option
  volume instead (V1), and futures volume is only used as yesterday's daily total (V4).
- **No expiry date in the rolling option files**, so the straddle-vs-expected-move ratio (O7) mixes expiry distance
  with richness.
- **The stale-minute count (Q3) is almost always 0** (traded strikes print every minute), so its fifths collapse; it
  says nothing here.
- **R41's range forecast was not rebuilt.** C12 (VIX-implied move / 20-day average range) stands in for it.
- **US overnight move:** S&P 500 daily closes from Yahoo (outside the lake, not committed).

## Summary

| arm | loser rate (unseen / design / final) | top flaws | filter result | win rate before -> after (vs 65% rule) |
|---|---|---|---|---|
| R72 Breadth Donchian | 61% / 53% / 54% | stops (43-77% of lost money); never-worked trades (30-50%); bunching (42-51% of losers on all-loss index-days); steady signs only R141's (weak push, quiet bar, wide previous day, fake poke) | Q2 quiet option tape: fails (final p 0.20, worst day and month worse); V4 heavy futures day: fails (unseen p < 0.001 but final p 0.27, drawdown and worst month worse) | unseen 39% -> 39-40%; final 46% -> 46%. Far below 65% |
| R51 Donchian 20 + Supertrend | 61% / 56% / 55% | stops (42-77%); never worked (31-48%); give-backs (25-40%); many weak steady slices (quiet option tape, neutral buying, mild index ticks) plus R141's | F4 futures covering against: fails (worst month worse in both; final p 0.07); F2 index ticking against: fails (final p 0.70) | unseen 39% -> 40%; final 45% -> 44%. Far below 65% |
| R52 Open outside value | 54% / 44% / 48% | time exit (39-85% of lost money); big single losses (MIDCPNIFTY); quiet first 5 minutes | none qualified on design | unchanged: 46% / 56% / 52%. Below 65% |

## Each arm

**R72 Breadth Donchian.** Loses on 53-61% of trades. Its flaws:
- **Stops are where the money goes,** most of all in the unseen years (77% of lost money) and in design (54%); in
  the final months the time exit takes the larger share (57%), because the stops were hit less often.
- **Half of the lost money in design and final is trades that never worked** (the option never rose 5%).
- **Losses bunch by day:** 42-51% of losers fall on index-days where every R72 trade lost.
- **Steady signs at entry (every period, >= 30 trades each): only R141's price signs.** A weak push into the entry
  (2nd and 3rd fifths: lose 56-63%), a quiet signal bar (lowest fifth: 56-65%), a wide previous day (top two fifths:
  54-67%; R140's wide-day skip already covers this and held up in R143), an earlier fake poke today (55-65%).
- **None of the new volume, liquidity, OI, context or order-flow features is steady on R72.** The quiet option tape
  (Q2 lowest fifth) loses more in every period (67% / 59% / 56%) but made money in the final months (+Rs 115 a trade).
- **Filters tested:** Q2 (quiet option tape) and V4 (heavy futures-volume day). Both fail (table below).

| period | trades | losers | loser rate | net | money lost (losing trades) | avg loss |
|---|---|---|---|---|---|---|
| unseen | 3,395 | 2,072 | 61% | -Rs 306,931 | -Rs 1,392,158 | -Rs 672 |
| design | 3,071 | 1,635 | 53% | Rs 216,929 | -Rs 2,246,151 | -Rs 1,374 |
| final | 1,202 | 651 | 54% | Rs 171,366 | -Rs 792,049 | -Rs 1,217 |

| money lost by exit | unseen | design | final |
|---|---|---|---|
| lock | 4 losers, -Rs 5,102 (0%) | 4 losers, -Rs 2,395 (0%) | - |
| other | - | 3 losers, -Rs 2,405 (0%) | - |
| stop | 1,174 losers, -Rs 1,076,301 (77%) | 568 losers, -Rs 1,203,025 (54%) | 174 losers, -Rs 339,653 (43%) |
| time / square-off | 894 losers, -Rs 310,756 (22%) | 1,060 losers, -Rs 1,038,326 (46%) | 477 losers, -Rs 452,396 (57%) |

| money lost by peak | unseen | design | final |
|---|---|---|---|
| gave it back (>=15%) | 851 losers, -Rs 532,851 (38%) | 464 losers, -Rs 589,070 (26%) | 175 losers, -Rs 178,206 (22%) |
| in between | 659 losers, -Rs 443,540 (32%) | 491 losers, -Rs 596,750 (27%) | 192 losers, -Rs 220,463 (28%) |
| never worked (<5%) | 562 losers, -Rs 415,768 (30%) | 680 losers, -Rs 1,060,331 (47%) | 284 losers, -Rs 393,379 (50%) |

| bunching | unseen | design | final |
|---|---|---|---|
| losing days | 277 | 176 | 62 |
| days traded | 453 | 323 | 129 |
| worst day | -Rs 16,757 | -Rs 58,613 | -Rs 22,571 |
| worst 5 days' share of all losing-day money | 8% | 14% | 26% |
| losers on index-days where every trade lost | 51% | 42% | 43% |
| longest losing streak (trades) | 28 | 29 | 15 |
| longest losing streak (days) | 10 | 9 | 5 |

**R51 Donchian 20 + Supertrend.** Loses on 55-61% of trades; the same shape as R72.
- **Stops:** 77% of lost money in the unseen years, 52% in design, 42% in the final.
- **Never worked:** 31% / 44% / 48% of the lost money. **Gave it back:** 40% / 28% / 25%.
- **Steady signs (every period):** R141's price signs again (weak push, quiet signal bar, wide previous day, a wide
  20-bar box, earlier fake pokes, a gap against the trade, mid-morning entries, Thursdays), plus some new ones:
  - a quiet option tape (Q2 lowest fifth): lose 62% / 58% / 58%, average -Rs 38 / -Rs 129 / -Rs 43;
  - neutral option buying pressure (F1 middle fifth): 61% / 60% / 64%, -Rs 88 / -Rs 145 / -Rs 124;
  - the index ticking only mildly the trade's way in the last 15 minutes (F2 middle fifths): 56-63%;
  - futures volume a little above usual (V4 4th fifth), middle premium (Q1), OI already up at the strike (O1 4th
    fifth), and a calm VIX against a wide recent range (C12 lowest fifth): 56-63%.
  - These are mostly middle fifths with small average losses. They show R51 loses a little in many ordinary
    situations; they do not point at one kind of trade to skip.
- **Filters tested:** F4 (yesterday's futures short covering against the trade) and F2 (index ticking against the
  trade in the last 15 minutes). Both fail.

| period | trades | losers | loser rate | net | money lost (losing trades) | avg loss |
|---|---|---|---|---|---|---|
| unseen | 4,815 | 2,927 | 61% | -Rs 400,363 | -Rs 1,943,132 | -Rs 664 |
| design | 4,208 | 2,337 | 56% | Rs 50,425 | -Rs 3,227,023 | -Rs 1,381 |
| final | 1,670 | 921 | 55% | Rs 134,743 | -Rs 1,102,074 | -Rs 1,197 |

| money lost by exit | unseen | design | final |
|---|---|---|---|
| lock | 5 losers, -Rs 6,212 (0%) | 1 losers, -Rs 185 (0%) | - |
| other | 2 losers, -Rs 136 (0%) | 5 losers, -Rs 4,007 (0%) | - |
| stop | 1,631 losers, -Rs 1,500,133 (77%) | 796 losers, -Rs 1,679,165 (52%) | 219 losers, -Rs 458,511 (42%) |
| time / square-off | 1,289 losers, -Rs 436,652 (22%) | 1,535 losers, -Rs 1,543,666 (48%) | 702 losers, -Rs 643,563 (58%) |

| money lost by peak | unseen | design | final |
|---|---|---|---|
| gave it back (>=15%) | 1,251 losers, -Rs 785,007 (40%) | 722 losers, -Rs 915,209 (28%) | 253 losers, -Rs 277,896 (25%) |
| in between | 881 losers, -Rs 558,663 (29%) | 705 losers, -Rs 900,820 (28%) | 279 losers, -Rs 289,483 (26%) |
| never worked (<5%) | 795 losers, -Rs 599,463 (31%) | 910 losers, -Rs 1,410,993 (44%) | 389 losers, -Rs 534,694 (48%) |

| bunching | unseen | design | final |
|---|---|---|---|
| losing days | 312 | 185 | 72 |
| days traded | 487 | 337 | 133 |
| worst day | -Rs 17,346 | -Rs 49,738 | -Rs 21,578 |
| worst 5 days' share of all losing-day money | 7% | 11% | 21% |
| losers on index-days where every trade lost | 37% | 32% | 31% |
| longest losing streak (trades) | 19 | 20 | 18 |
| longest losing streak (days) | 11 | 7 | 7 |

**R52 Open outside value.** One 09:20 trade a day per index; loses on 44-54%, with the largest losses per trade
(average loss -Rs 1,155 / -Rs 2,361 / -Rs 1,893).
- **The time exit takes most of the lost money** (39% / 63% / 85%). R142's 09:25 no-progress exit (NP5-52), which held
  up in R143, is aimed at exactly this.
- **Every losing trade is its index's whole day** (one trade a day), so "losses on all-loss days" is 100% by design.
- **One steady sign: a quiet first five minutes** (09:15-09:20 range in the lowest fifth vs ATR): lose 55% / 49% /
  59%, average -Rs 185 / -Rs 76 / -Rs 241 (460 / 141 / 51 trades).
- **High VIX** raises its loser rate in every period, but its winners are bigger too (not a loss sign).
- **No filter qualified on design** (no new feature's extreme fifth lost money in both design halves with >= 30
  trades), so none was tested.

| period | trades | losers | loser rate | net | money lost (losing trades) | avg loss |
|---|---|---|---|---|---|---|
| unseen | 973 | 527 | 54% | -Rs 152,248 | -Rs 608,892 | -Rs 1,155 |
| design | 706 | 313 | 44% | Rs 290,573 | -Rs 739,055 | -Rs 2,361 |
| final | 293 | 142 | 48% | Rs 48,612 | -Rs 268,790 | -Rs 1,893 |

| money lost by exit | unseen | design | final |
|---|---|---|---|
| lock | 5 losers, -Rs 2,177 (0%) | - | - |
| other | 1 losers, -Rs 173 (0%) | - | - |
| stop | 208 losers, -Rs 371,090 (61%) | 72 losers, -Rs 270,812 (37%) | 14 losers, -Rs 41,441 (15%) |
| time / square-off | 313 losers, -Rs 235,452 (39%) | 241 losers, -Rs 468,244 (63%) | 128 losers, -Rs 227,349 (85%) |

| money lost by peak | unseen | design | final |
|---|---|---|---|
| gave it back (>=15%) | 232 losers, -Rs 223,309 (37%) | 123 losers, -Rs 221,066 (30%) | 52 losers, -Rs 75,723 (28%) |
| in between | 159 losers, -Rs 189,987 (31%) | 117 losers, -Rs 258,033 (35%) | 47 losers, -Rs 89,545 (33%) |
| never worked (<5%) | 136 losers, -Rs 195,596 (32%) | 73 losers, -Rs 259,956 (35%) | 43 losers, -Rs 103,523 (38%) |

| bunching | unseen | design | final |
|---|---|---|---|
| losing days | 211 | 116 | 45 |
| days traded | 381 | 253 | 101 |
| worst day | -Rs 11,727 | -Rs 25,140 | -Rs 16,554 |
| worst 5 days' share of all losing-day money | 10% | 15% | 30% |
| losers on index-days where every trade lost | 100% | 100% | 100% |
| longest losing streak (trades) | 16 | 11 | 18 |
| longest losing streak (days) | 8 | 7 | 5 |

## The filter tests (1 lot; today's rules -> with the skip)

Gates (PREREG section 5), in BOTH test periods: losing days smaller; drawdown, worst day, worst month no worse; still
in profit where today's arm was; beats 2,000 random skips of the same number (p <= 0.05); then BH q <= 0.10 on the
larger of the two p values, within this group. Raw p values: `results/pvalues.json`.

| test | arm | skip | period | trades | win | net | losing days | drawdown | worst day | worst month | p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| R144A-72-Q2 | R72 | spread proxy: option minute range / price (last 5 min) = slice 0 | unseen | 3,395 -> 3,341 | 39% -> 39% | -Rs 306,931 -> -Rs 298,388 | -Rs 886,970 -> -Rs 873,433 | Rs 337,564 -> Rs 329,021 | -Rs 16,757 -> -Rs 16,757 | -Rs 54,175 -> -Rs 53,828 | 0.467 |
| R144A-72-Q2 | R72 | spread proxy: option minute range / price (last 5 min) = slice 0 | final | 1,202 -> 992 | 46% -> 46% | Rs 171,366 -> Rs 168,352 | -Rs 389,644 -> -Rs 325,432 | Rs 61,884 -> Rs 57,788 | -Rs 22,571 -> -Rs 23,034 | -Rs 40,908 -> -Rs 43,148 | 0.196 |
| R144A-72-Q2 | | q 0.623, fails: final: worst day worse; final: worst month worse; final: twin p 0.196 > 0.05; unseen: twin p 0.467 > 0.05; BH q 0.623 > 0.1 | | | | | | | | | |
| R144A-72-V4 | R72 | index futures volume yesterday vs its 20-day average = slice 4 | unseen | 3,395 -> 2,716 | 39% -> 40% | -Rs 306,931 -> -Rs 197,589 | -Rs 886,970 -> -Rs 658,393 | Rs 337,564 -> Rs 238,273 | -Rs 16,757 -> -Rs 9,770 | -Rs 54,175 -> -Rs 36,876 | 0.000 |
| R144A-72-V4 | R72 | index futures volume yesterday vs its 20-day average = slice 4 | final | 1,202 -> 1,037 | 46% -> 46% | Rs 171,366 -> Rs 133,998 | -Rs 389,644 -> -Rs 349,878 | Rs 61,884 -> Rs 66,886 | -Rs 22,571 -> -Rs 22,571 | -Rs 40,908 -> -Rs 45,772 | 0.272 |
| R144A-72-V4 | | q 0.5447, fails: final: drawdown worse; final: worst month worse; final: twin p 0.272 > 0.05; BH q 0.545 > 0.1 | | | | | | | | | |
| R144A-51-F4 | R51 | futures OI-price quadrant, previous session (trade's way) = slice covering against | unseen | 4,815 -> 4,263 | 39% -> 40% | -Rs 400,363 -> -Rs 338,223 | -Rs 1,012,120 -> -Rs 905,959 | Rs 423,510 -> Rs 358,907 | -Rs 17,346 -> -Rs 17,346 | -Rs 51,440 -> -Rs 52,027 | 0.000 |
| R144A-51-F4 | R51 | futures OI-price quadrant, previous session (trade's way) = slice covering against | final | 1,670 -> 1,427 | 45% -> 44% | Rs 134,743 -> Rs 93,182 | -Rs 395,932 -> -Rs 353,484 | Rs 81,683 -> Rs 80,220 | -Rs 21,578 -> -Rs 16,666 | -Rs 54,587 -> -Rs 66,017 | 0.072 |
| R144A-51-F4 | | q 0.2879, fails: final: worst month worse; final: twin p 0.072 > 0.05; unseen: worst month worse; BH q 0.288 > 0.1 | | | | | | | | | |
| R144A-51-F2 | R51 | index tick direction (last 15 min, trade's way) = slice 0 | unseen | 4,815 -> 4,689 | 39% -> 40% | -Rs 400,363 -> -Rs 374,149 | -Rs 1,012,120 -> -Rs 977,669 | Rs 423,510 -> Rs 395,101 | -Rs 17,346 -> -Rs 14,681 | -Rs 51,440 -> -Rs 43,533 | 0.024 |
| R144A-51-F2 | R51 | index tick direction (last 15 min, trade's way) = slice 0 | final | 1,670 -> 1,621 | 45% -> 44% | Rs 134,743 -> Rs 75,866 | -Rs 395,932 -> -Rs 395,385 | Rs 81,683 -> Rs 71,276 | -Rs 21,578 -> -Rs 16,666 | -Rs 54,587 -> -Rs 48,021 | 0.698 |
| R144A-51-F2 | | q 0.6982, fails: final: twin p 0.698 > 0.05; BH q 0.698 > 0.1 | | | | | | | | | |

## What is proposed

- **No new switch.** None of the four filters passed, so nothing is proposed for paper or shadow.
- **What already stands (from R140 / R142 / R143) and fits these flaws:** R72's wide-day skip (FB-R72-A10) and R52's
  09:25 no-progress exit (NP5-52). Both cut losses in every period including the unseen two years. They still wait for
  Boss's approval as labelled paper switches (shadow log first, off by default, Autopilot timeline, one-tap undo).
- **Worth a fresh, pre-registered look (not tested here):**
  - **MIDCPNIFTY's size on the Donchian arms and R52:** it supplies most of the worst single losses. A risk-lowering
    rule (smaller size or a rupee cap on MIDCPNIFTY) would only LOWER risk; it would need its own test and would start
    in shadow.
  - **The opening 09:20 break** on R72 / R51: R113 studied time of day; the worst trades here point at it again.
- **Not worth repeating:** volume-confirms-the-break ideas (R105D and V1 / V2 here), PCR, IV level, skew and the
  option OI-price quadrant: none separated losers steadily on any of the three arms.

## Method

- **Trades:** R115's engine (R101 units, R106 walk), today's rules, 1 lot, R17 fills, dated charges. Parity: design and
  final reproduce the published trades and net to the rupee for all three arms; R143's old store reproduces the main
  store's trades on 20 Nov - 30 Dec 2024.
- **Periods:** unseen 1 Dec 2022 - 19 Nov 2024; design 20 Nov 2024 - 31 Mar 2026 (H1 / H2 halves for the choice);
  final 1 Apr - 13 Oct 2026. Each test period read once (`results/FINAL_READ`, `results/UNSEEN_READ`).
  **Caveat:** R140-R143 had already read the arms' own trades in those periods; the new features had not been looked at
  there.
- **Features:** 22 new numbers and 2 categories per candidate, all read before the fill minute (look-ahead guard in
  `test_r144a.py`), plus R141's 17 price features. Fifths use design-trade edges in every period.
- **Deviation from the pre-registration (wording only):** F4 was listed with four labels; the code orients all six
  (build-up, covering, unwinding, each with / against the trade). The filter F4 uses "covering against", one of them.
- **Choice:** design only, at most 3 per arm, ranked by the t-score of the slice's design mean against the rest.
- **Twins:** 2,000 random skips of the same number of candidates, walked again, in each period (seeds 1441 / 1442).
- **Multiple testing:** BH within R144a's 4 tests (q 0.29-0.70). The coordinator applies BH across R144a-d from
  `results/pvalues.json`.
- **Code and results:** `research/hunt/r144a_loss_breakout/` (`r144a.py`, `r144a_feat.py`, `r144a_tables.py`,
  `test_r144a.py`, `results/`: design, final, unseen, report, selection, pvalues). Entries:
  `research/experiments/entries/r144a-*.yaml`.

## Appendix: the 20 worst trades of each arm

Q2 is the option's minute range / price over the last 5 minutes (0 = the strike barely traded before entry); "no OI
before entry" means the traded strike had no OI rows before the fill.

**R72: the 20 worst trades of all three periods**

| # | day | index | side | entry | held (min) | exit | best gain before exit | net | VIX | VIX vs prev close | option range proxy (Q2) | option OI-price |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2025-04-08 | MIDCPNIFTY | call | 09:20 | 15 | stop | 2% | -Rs 11,511 | 20.17 | -11.5% | 0.0 | no OI before entry |
| 2 | 2025-04-07 | NIFTY | put | 09:20 | 2 | stop | 20% | -Rs 9,603 | 21.35 | +55.2% | 0.204 | long build-up |
| 3 | 2025-12-31 | FINNIFTY | call | 09:20 | 7 | stop | 2% | -Rs 8,269 | 9.63 | -0.5% | 0.0 | no OI before entry |
| 4 | 2025-04-07 | MIDCPNIFTY | put | 09:20 | 3 | stop | 11% | -Rs 7,410 | 21.35 | +55.2% | 0.282 | long build-up |
| 5 | 2025-02-01 | MIDCPNIFTY | call | 11:35 | 14 | stop | 0% | -Rs 6,775 | 15.36 | -5.5% | 0.01 | long build-up |
| 6 | 2026-03-05 | MIDCPNIFTY | put | 14:10 | 29 | stop | 2% | -Rs 6,315 | 18.99 | -10.2% | 0.016 | short covering |
| 7 | 2025-05-07 | MIDCPNIFTY | call | 09:30 | 17 | stop | 25% | -Rs 6,291 | 19.32 | +1.7% | 0.05 | long build-up |
| 8 | 2026-05-13 | MIDCPNIFTY | put | 09:40 | 14 | stop | 0% | -Rs 6,245 | 19.8 | +2.7% | 0.034 | long build-up |
| 9 | 2026-03-16 | MIDCPNIFTY | put | 10:55 | 11 | stop | 3% | -Rs 6,241 | 22.34 | -1.4% | 0.019 | long build-up |
| 10 | 2025-02-01 | MIDCPNIFTY | put | 11:55 | 8 | stop | 15% | -Rs 6,124 | 15.45 | -4.9% | 0.113 | long build-up |
| 11 | 2025-03-03 | MIDCPNIFTY | call | 09:20 | 15 | stop | 11% | -Rs 6,067 | 13.98 | +0.5% | 0.037 | long build-up |
| 12 | 2025-12-05 | MIDCPNIFTY | call | 10:05 | 25 | stop | 15% | -Rs 5,954 | 10.67 | -1.4% | 0.036 | long build-up |
| 13 | 2025-02-03 | MIDCPNIFTY | put | 09:20 | 7 | stop | 7% | -Rs 5,940 | 14.83 | +5.2% | 0.033 | short build-up |
| 14 | 2025-02-01 | MIDCPNIFTY | call | 14:25 | 15 | stop | 3% | -Rs 5,900 | 14.19 | -12.7% | 0.016 | long build-up |
| 15 | 2025-04-29 | FINNIFTY | call | 09:20 | 23 | stop | 4% | -Rs 5,888 | 16.83 | -0.6% | 0.036 | long build-up |
| 16 | 2025-04-29 | BANKNIFTY | call | 09:20 | 22 | stop | 4% | -Rs 5,822 | 16.83 | -0.6% | 0.047 | long build-up |
| 17 | 2026-02-01 | MIDCPNIFTY | put | 12:05 | 2 | stop | 31% | -Rs 5,756 | 13.98 | +2.6% | 0.047 | short covering |
| 18 | 2025-06-02 | MIDCPNIFTY | put | 09:20 | 28 | stop | 3% | -Rs 5,739 | 17.17 | +6.8% | 0.059 | long build-up |
| 19 | 2025-02-01 | FINNIFTY | call | 11:35 | 16 | stop | 1% | -Rs 5,686 | 15.36 | -5.5% | 0.011 | short build-up |
| 20 | 2024-11-28 | FINNIFTY | call | 10:05 | 29 | stop | 1% | -Rs 5,453 | 14.52 | -0.8% | 0.018 | long build-up |

**R51: the 20 worst trades of all three periods**

| # | day | index | side | entry | held (min) | exit | best gain before exit | net | VIX | VIX vs prev close | option range proxy (Q2) | option OI-price |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2025-04-08 | MIDCPNIFTY | call | 09:20 | 15 | stop | 2% | -Rs 11,511 | 20.17 | -11.5% | 0.0 | no OI before entry |
| 2 | 2025-04-07 | NIFTY | put | 09:20 | 2 | stop | 20% | -Rs 9,603 | 21.35 | +55.2% | 0.204 | long build-up |
| 3 | 2025-12-31 | FINNIFTY | call | 09:20 | 7 | stop | 2% | -Rs 8,269 | 9.63 | -0.5% | 0.0 | no OI before entry |
| 4 | 2025-04-07 | MIDCPNIFTY | put | 09:20 | 3 | stop | 11% | -Rs 7,410 | 21.35 | +55.2% | 0.282 | long build-up |
| 5 | 2025-04-30 | MIDCPNIFTY | put | 09:20 | 13 | stop | 9% | -Rs 6,862 | 17.7 | +1.9% | 0.024 | long build-up |
| 6 | 2026-03-16 | MIDCPNIFTY | put | 09:25 | 10 | stop | 24% | -Rs 6,850 | 21.93 | -3.2% | 0.046 | long build-up |
| 7 | 2025-03-28 | MIDCPNIFTY | call | 09:20 | 6 | stop | 0% | -Rs 6,338 | 13.09 | -1.6% | 0.042 | long build-up |
| 8 | 2026-03-05 | MIDCPNIFTY | put | 14:15 | 24 | stop | 2% | -Rs 6,298 | 18.95 | -10.4% | 0.011 | short covering |
| 9 | 2025-02-01 | MIDCPNIFTY | put | 11:50 | 14 | stop | 23% | -Rs 6,105 | 15.5 | -4.6% | 0.018 | short covering |
| 10 | 2025-03-03 | MIDCPNIFTY | call | 09:20 | 15 | stop | 11% | -Rs 6,067 | 13.98 | +0.5% | 0.037 | long build-up |
| 11 | 2025-04-25 | FINNIFTY | call | 09:20 | 19 | stop | 2% | -Rs 6,050 | 16.04 | -1.3% | 0.0 | no OI before entry |
| 12 | 2025-02-01 | MIDCPNIFTY | put | 13:15 | 11 | stop | 5% | -Rs 5,995 | 14.82 | -8.8% | 0.04 | long build-up |
| 13 | 2026-04-09 | MIDCPNIFTY | call | 09:25 | 25 | stop | 14% | -Rs 5,985 | 20.04 | +1.7% | 0.026 | long build-up |
| 14 | 2026-02-02 | MIDCPNIFTY | call | 09:55 | 25 | stop | 10% | -Rs 5,967 | 14.98 | -0.8% | 0.03 | short covering |
| 15 | 2025-12-05 | MIDCPNIFTY | call | 10:05 | 25 | stop | 15% | -Rs 5,954 | 10.67 | -1.4% | 0.036 | long build-up |
| 16 | 2025-02-03 | MIDCPNIFTY | put | 09:20 | 7 | stop | 7% | -Rs 5,940 | 14.83 | +5.2% | 0.033 | short build-up |
| 17 | 2025-02-28 | MIDCPNIFTY | put | 10:05 | 10 | stop | 2% | -Rs 5,903 | 13.66 | +2.6% | 0.048 | short covering |
| 18 | 2025-04-29 | FINNIFTY | call | 09:20 | 23 | stop | 4% | -Rs 5,888 | 16.83 | -0.6% | 0.036 | long build-up |
| 19 | 2025-04-29 | BANKNIFTY | call | 09:20 | 22 | stop | 4% | -Rs 5,822 | 16.83 | -0.6% | 0.047 | long build-up |
| 20 | 2025-06-02 | MIDCPNIFTY | put | 09:20 | 28 | stop | 3% | -Rs 5,739 | 17.17 | +6.8% | 0.059 | long build-up |

**R52: the 20 worst trades of all three periods**

| # | day | index | side | entry | held (min) | exit | best gain before exit | net | VIX | VIX vs prev close | option range proxy (Q2) | option OI-price |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 2025-04-07 | MIDCPNIFTY | put | 09:20 | 9 | stop | 11% | -Rs 14,592 | 21.35 | +55.2% | 0.282 | long build-up |
| 2 | 2025-03-03 | MIDCPNIFTY | call | 09:20 | 54 | stop | 11% | -Rs 11,949 | 13.98 | +0.5% | 0.037 | long build-up |
| 3 | 2025-04-07 | NIFTY | put | 09:20 | 2 | stop | 20% | -Rs 9,603 | 21.35 | +55.2% | 0.204 | long build-up |
| 4 | 2026-05-27 | FINNIFTY | put | 09:20 | 59 | time | 0% | -Rs 9,049 | 15.44 | -4.3% | 0.0 | no OI before entry |
| 5 | 2025-03-12 | MIDCPNIFTY | call | 09:20 | 59 | time | 2% | -Rs 8,297 | 13.93 | -1.0% | 0.055 | short build-up |
| 6 | 2025-12-31 | FINNIFTY | call | 09:20 | 59 | time | 2% | -Rs 8,080 | 9.63 | -0.5% | 0.0 | no OI before entry |
| 7 | 2025-10-14 | MIDCPNIFTY | call | 09:20 | 50 | stop | 2% | -Rs 7,890 | 10.94 | -0.6% | 0.04 | long build-up |
| 8 | 2025-12-11 | MIDCPNIFTY | put | 09:20 | 55 | stop | 1% | -Rs 7,832 | 10.75 | -1.5% | 0.036 | long build-up |
| 9 | 2025-05-30 | MIDCPNIFTY | call | 09:20 | 59 | time | 0% | -Rs 7,731 | 16.31 | -0.7% | 0.028 | short build-up |
| 10 | 2025-04-08 | MIDCPNIFTY | call | 09:20 | 59 | time | 2% | -Rs 7,513 | 20.17 | -11.5% | 0.0 | no OI before entry |
| 11 | 2025-05-21 | MIDCPNIFTY | put | 09:20 | 55 | stop | 10% | -Rs 7,473 | 17.51 | +0.7% | 0.132 | short covering |
| 12 | 2026-01-13 | MIDCPNIFTY | call | 09:20 | 36 | stop | 2% | -Rs 7,465 | 11.33 | -0.4% | 0.038 | short build-up |
| 13 | 2026-03-18 | FINNIFTY | call | 09:20 | 59 | time | 10% | -Rs 6,820 | 18.93 | -4.3% | 0.038 | no OI before entry |
| 14 | 2026-03-24 | MIDCPNIFTY | call | 09:20 | 59 | time | 42% | -Rs 6,615 | 25.78 | -3.6% | 0.069 | long unwinding |
| 15 | 2024-12-26 | BANKNIFTY | call | 09:20 | 59 | time | 2% | -Rs 6,579 | 13.32 | +1.1% | 0.061 | long build-up |
| 16 | 2025-01-03 | FINNIFTY | call | 09:20 | 59 | time | 2% | -Rs 6,462 | 13.77 | +0.2% | 0.03 | long build-up |
| 17 | 2025-01-03 | BANKNIFTY | call | 09:20 | 59 | time | 0% | -Rs 6,341 | 13.77 | +0.2% | 0.039 | long build-up |
| 18 | 2025-08-14 | MIDCPNIFTY | call | 09:20 | 59 | time | 1% | -Rs 6,194 | 12.34 | +1.6% | 0.052 | short covering |
| 19 | 2025-03-28 | MIDCPNIFTY | call | 09:20 | 59 | time | 0% | -Rs 6,190 | 13.09 | -1.6% | 0.042 | long build-up |
| 20 | 2026-10-09 | MIDCPNIFTY | put | 09:20 | 59 | time | 15% | -Rs 6,120 | 14.67 | -4.0% | 0.092 | long build-up |
