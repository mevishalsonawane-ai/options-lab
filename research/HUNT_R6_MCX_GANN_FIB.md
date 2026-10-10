# HUNT R6: Gann, Fibonacci, "magic numbers" and harmonics on MCX commodities (R4 repeated on MCX)

Written 9 Oct 2026 for Boss. Frame: Rs 1,00,000 capital, FIXED 1 lot, real MCX costs, same tests as R4.

- **Option buys**: 1-ITM near-month CE/PE, 1 lot. Books: CRUDEOIL, NATURALGAS, NATGASMINI, GOLDM, SILVERM.
  - Bought at the next printed minute's open.
  - Exits on the option's own 1-minute high/low.
  - Expiry days skipped. Flat by 23:15.
- **Mini/micro futures**: 1 lot, both directions. Books: CRUDEOILM, NATGASMINI, SILVERMIC, GOLDPETAL.
  - Cost: 0.03% of notional per round trip, plus Zerodha charges.
- **Option costs**: McxCosts.kt (Zerodha) charges, plus half the bid-ask spread on each side.
  - Crude and natural gas: 0.60% before 17:00, 0.30% after.
  - GOLDM, SILVERM and NATGASMINI: 0.80% / 0.40%.
- **Tests (the R4 set)**:
  - placebo levels;
  - 10 random-entry twins per trade;
  - BH correction across all versions;
  - White Reality Check;
  - frozen picks, then the locked year opened once.
- Plan written before any result: `research/hunt/r6/PREREG.md`.
- Code: `research/hunt/r6/`:
  - `lib6.py`: data, engines, costs, statistics;
  - `signals6.py`: all rules;
  - `design.py`: design and holdout;
  - `placebo6.py`: the level test;
  - `tables6.py`: the tables;
  - `posthoc_evetime.py`: the POST-HOC daylight-time split;
  - `fetch_tail.py`: Dhan top-up of 8-9 Oct;
  - `fetch_hd.py`: XAG/WTI proxies.
- Small results: `research/hunt/r6/results/`. `all_variants.csv` holds every version, design and holdout.
  `placebo_merged.csv` holds every level test.
- Logs and trade files: `scratchpad/hunt/r6/` (about 100 MB).
- Nothing was committed. The Dhan token was never printed.

## Verdict (plain English)

**No. On MCX, with Rs 1 lakh and 1 lot, none of the four topics makes money after real costs. This holds for every
commodity, as option buys and as mini/micro futures. The "special" price levels mostly do not make MCX prices turn
more often than random levels with the same spacing.** The only real exceptions are:
- a small silver round-number effect (about +1 to +3.5 percentage points more reversals at Rs 500 / Rs 1,000 levels,
  also seen in XAGUSD 2018-2025);
- a negligible crude swing-Fibonacci effect (+0.5 points);
- "Gann" evening times, which are really US-session news minutes.

None of them pays for costs. Every rule built on them lost.

| commodity | 1. magic numbers (Square-of-9 buy-above/sell-below from 09:00 / 09:25 / prev close / 17:00, round Rs levels, ORB + Fib extensions) | 2. Gann (HiLo, swing chart, 50% / quarters, angles, 45/90/180-min marks, seasonal dates) | 3. Fibonacci (pivots, prev-day retracements, golden pocket, Upstox 61.8%, time zones, confluence) | 4. harmonics (3/5/15/60-min) | do the levels matter more than random ones? |
|---|---|---|---|---|---|
| **CRUDEOIL** options / **CRUDEOILM** futures | **No.** Options: 45 versions, 0 positive in the locked year (median -Rs 589/day). Futures: 3 of 45 positive (best +Rs 14/day) | **No.** Options: 2 of 38 positive (best +Rs 24/day). Futures: 3 of 38 (best +Rs 86/day, swing chart, BH q 1.0) | **No.** Options: 0 of 33 positive. Futures: 1 of 33 | **No.** Rare trades, median about -Rs 31/day (options) and -Rs 7/day (futures) | **No.** Square-of-9, Gann points, pivots and round 50s / 100s react like random levels. The only raw "hit", multiples of 9 (+2.8 points), is mostly a tick-size artefact. Made fair, it shrinks to +0.6 points (p 0.06). Swing-Fibonacci levels: +0.5 points (confirmed with WTI, but negligible). Evening "Gann" marks: +5 points more swing turns, which is the US news clock |
| **NATURALGAS** options / **NATGASMINI** options and futures | **No.** NATURALGAS options: 2 of 45 positive (best +Rs 29/day). NATGASMINI options: 0 of 45 | **No.** Best: 60-min swing chart, +Rs 143/day on 94 trades. It had only 8 design trades (too few to be picked), t 0.8, BH q 1.0 | **No.** 3 of 33 positive on options, 1 of 33 on futures | **No** | **No.** Two holdout hits (Gann points, Fib time zones) were negative or flat in design. Made tick-fair, the Gann-point hit is +1.5 points, p 0.25 |
| **GOLDM** options / **GOLDPETAL** futures | **No.** Options: 0 of 45 positive (median -Rs 255/day). GOLDPETAL: 0 of 45 | **No.** 2 of 38 futures versions were positive. Best: 15-min swing chart, +Rs 12/day per GOLDPETAL (about +Rs 1,200/day on a GOLDM lot). It lost in design (-Rs 17/day) and has BH q 1.0 | **No.** 0 of 33 positive (options and futures) | **No** | **No.** Rs 1,000 levels showed +24 points in design (34 touches). In the locked year that fell to +0.7 points (489 touches). Gann points +3.3 points in holdout (p 0.005), but q 0.52 in design and q 0.12 holdout tick-fair: not confirmed. Round numbers: random-like on MCX and WORSE than random in XAUUSD 2015-2025 (-6 to -13 points). Evening "Gann" marks +6.7 points (US clock) |
| **SILVERM** options / **SILVERMIC** futures | **No.** Options: 1 of 45 positive. SILVERMIC: 5 of 45 (best: Square-of-9 from prev close, +Rs 89/day, design -Rs 101/day) | **No.** SILVERMIC 15-min swing chart made +Rs 439/day (11 of 12 green months). But all of it came from **longs** during silver's run from Rs 1.1 lakh to Rs 3 lakh: longs +Rs 833 a trade, shorts +Rs 4. It lost in design (-Rs 94/day), t 1.3, BH q 1.0 | **No.** 2 of 33 positive on options (best +Rs 52/day). 5 of 33 on futures | **No** | **A little, for round numbers only.** Rs 1,000 levels reversed +3.5 points more often in the locked year (28.2% vs 24.7%, p 0.004), and Rs 500 levels +0.9 points. XAGUSD round levels did the same in 2018-2025 (+2-3 points, q < 0.05), although MCX's 37-day design showed -7. Rs 5,000 levels near the open were reached more often (90% vs 74%, 51 levels). The edge is too small to trade: every silver round-number rule lost. Square-of-9, Fibonacci and Gann levels: random |

The numbers behind it:
- **Design (5 Aug - 30 Sep 2025, about 37 sessions; thin, it is all the MCX data there is):**
  - 1,711 versions over 9 books;
  - 305 positive;
  - smallest BH q **1.00**; against the twins, also 1.00;
  - best Reality Check p 0.56;
  - **0 candidates.**
- **Locked year (1 Oct 2025 - 8 Oct 2026, about 250 sessions), opened once:**
  - **124 frozen picks: 4 positive, 120 negative. Median -Rs 146/day.**
  - All 33 picks that were positive in design lost money in the locked year.
  - The crude option picks lost Rs 200-2,400 a day.
- **Pre-registered second test** (every version run on the locked year, because design is thin):
  - 2,034 versions, 240 positive;
  - smallest BH q 1.00; against the twins, 0.11;
  - best Reality Check p 0.91.
- **PASS-A = 0. PASS-B = 0.**
- **Rs 5,000/day is not reachable.** No rule makes money, so no lot count gets there.
- **Paper-trade bot worth adding: none.**
- **Pooled over every locked-year trade, the methods were no better than random entries at the same time**
  (Rs per trade, 1 lot):

| book | trades | gross | net | costs | random twin gross | random twin net |
|---|---|---|---|---|---|---|
| CRUDEOIL option | 37,730 | -473 | -756 | 282 | -408 | -687 |
| NATURALGAS option | 36,799 | -120 | -301 | 181 | -97 | -278 |
| NATGASMINI option | 36,386 | -26 | -106 | 81 | -20 | -100 |
| GOLDM option | 24,813 | -249 | -520 | 270 | -200 | -468 |
| SILVERM option | 14,795 | -487 | -808 | 321 | -325 | -636 |
| CRUDEOILM future | 44,956 | -2 | -81 | 78 | +1 | -77 |
| NATGASMINI future | 43,172 | -1 | -84 | 83 | -2 | -85 |
| SILVERMIC future | 33,483 | +6 | -147 | 153 | +6 | -147 |
| GOLDPETAL future | 39,996 | +0.4 | -17 | 17 | +0.2 | -17 |

How to read it:
- **Futures remove option decay**, and the gross result becomes about zero, the same as a coin flip. The 0.03% slippage
  plus Zerodha charges then make every futures book lose.
- **Options are worse.** Gross is already negative: decay, plus the evening premium that prices the US-session move.
  Costs then add Rs 80-320 a trade.
- **Futures beat options for Boss only because they lose less.** A crude option buy loses about Rs 760 a trade on
  these rules. A CRUDEOILM future loses about Rs 80.

## Why traders still believe in them (what MCX shows)

1. **There is always a level near the turn.** On MCX the claimed grids are dense: multiples of 9 put dozens to
   hundreds of levels in a day's range on gold and silver. The swing-Fibonacci levels alone were touched 2,700-15,600
   times per commodity in the locked year.
   - Levels within 0.5% of the open are reached about 62-93% of the time, claimed or placebo alike.
   - The exceptions are the touch rows for Rs 50 natural gas, Rs 1,000 gold and Rs 5,000 silver, on 17-132 levels.
   - So a chart always "shows" a level at the turn, whichever method is drawn.
2. **Tick size makes round numbers look special.** MCX moves in whole rupees (crude, gold, silver) or 10 paise
   (natural gas).
   - A round level can be hit exactly, while a randomly shifted level can only be crossed. That alone made the
     pre-registered test score crude multiples-of-9 as +6.8 points in design and +2.8 in the locked year (q < 0.001).
   - With every level moved onto the tick grid (POST-HOC fix, both sides alike), those shrink to +3.2 (q 0.28) and
     +0.6 (p 0.06).
   - Silver and gold multiples of 9 shrink from +0.1-0.2 points to +0.0.
   - The pre-registered "confirmations" of digital-root-9 levels are this artefact, not numerology.
3. **The "Gann evening times" are US clock times.**
   - Marks 45/90/180 min after 17:00 (17:45, 18:30, 20:00 IST) sit next to 5-min swing turns more often than marks
     shifted 10-30 minutes. In the locked year:
     - gold, 49.6% vs 42.9%;
     - silver, 51.7% vs 46.0%;
     - crude, 45.6% vs 40.5%.
   - The world proxies show it too: WTI +9.8 points, XAU +3.4, XAG +3.8.
   - Those clock minutes are the US session:
     - 08:00-10:30 New York, with data at 08:30, the COMEX/NYMEX opens, the NYSE open at 09:30 ET (20:00 IST in US
       winter) and EIA at 10:30 ET (20:00 IST in US summer);
     - turns cluster at scheduled minutes, and shifted marks miss them.
   - POST-HOC (`posthoc_evetime.py`), split by US daylight time:
     - gold and silver: +14 / +11 points in US winter, +2 points in summer;
     - crude: +6 points in both.
     - A fixed "Gann count from 17:00" cannot produce a seasonal difference like that. Scheduled news can.
   - Gann's own 09:00-based marks (09:45, 10:30, 12:00) show nothing: -3.8 to +1.5 points.
   - The marks give no direction. All 27 trading versions of this rule (fade the move at the mark) lost in the locked
     year: -Rs 11 to -Rs 1,420 a day.
4. **Swing-chart "wins" are the bull market.**
   - The only big holdout numbers come from the Gann swing chart on futures: SILVERMIC +Rs 439/day; GOLDPETAL +Rs 12
     (about Rs 1,200 on a GOLDM lot); NATGASMINI and CRUDEOILM 60-min, +Rs 86-122.
   - Each is a slow breakout rule held about 5-6 hours.
   - In silver, all of the profit came from longs while silver nearly tripled.
   - None had a positive design over 30+ trades. The 60-min versions had only 8-9 design trades; the 15-min versions
     lost in design.
   - None survives BH: q 1.0 with 2,034 versions. With that many tries, a few t of 1.3-1.6 are expected by chance.
5. **Costs and decay do the rest.** A random 1-ITM entry loses Rs 100-690 a trade before any rule is applied.
   - The methods add nothing to that: -Rs 6 to -Rs 172 a trade versus their twins on the option books, about 0 on
     futures.
   - **R4 found the same on NIFTY/BANKNIFTY/FINNIFTY**: the methods were Rs 17 a trade worse than random.

## Placebo-level test (the main science)

Method: R4's, unchanged.
- Claimed levels against the same construction shifted at random, 20 draws a day.
- The first touch of each level per day, 09:05-23:00.
- REVERSAL means the price comes back d (0.10% or 0.25%) before it goes d through, within 60 minutes.
- Day-cluster bootstrap, one-sided.
- Rule: "matters" means BH q < 0.05 in design (MCX, or the matching world proxy) AND p < 0.05 with the same sign in
  the MCX locked year.
- Rupee grids per contract:

| commodity | "50s" | "00s" | major | Gann points |
|---|---|---|---|---|
| crude | Rs 50 | Rs 100 | Rs 500 | 45 / 90 / 144 / 180 / 360 |
| natural gas | Rs 5 | Rs 10 | Rs 50 | the same / 10 |
| gold | Rs 100 | Rs 500 | Rs 1,000 | x10 |
| silver | Rs 500 | Rs 1,000 | Rs 5,000 | x10 |

**Result.** By the pre-registered rule, which also accepts a world-proxy design, **12 of 216 MCX tests are
"confirmed"**. With the tick-size fix (POST-HOC), **7** remain. Every survivor is either tiny, a clock effect, or a
small-sample touch count. None is a Gann/Fibonacci/Square-of-9 reversal level, and none could be traded at a profit.

| # | commodity | test | locked year (tick-fair) | design support | what it is |
|---|---|---|---|---|---|
| 1 | SILVERM | round Rs 1,000 levels, reversal at 0.10% | +3.5 points (28.2% vs 24.7%), p 0.004, 1,089 touches | XAGUSD round $0.50: +3.4 points, q 0.03 (2018-2025). MCX design: -7.1 points (37 days) | small silver round-number effect |
| 2 | SILVERM | round Rs 500 levels, reversal at 0.25% | +0.9 points, p 0.04 | XAGUSD $0.25: +2.6, q 0.04 | the same, smaller |
| 3 | SILVER | Rs 5,000 levels near the open, touch | 90% vs 74%, p 0.004, **51 levels** | MCX design **6 of 6** vs 81% | small-sample magnet count, no extra reversal (-0.2 to +4.6 points, not significant) |
| 4 | CRUDEOIL | swing Fibonacci 38.2-161.8%, reversal at 0.10% | **+0.5 points**, p 0.02, 15,129 touches | WTI +0.3 points on 100,000 touches, q < 0.001 | real but negligible: 36.5% instead of 36.0% |
| 5-7 | CRUDEOIL, GOLDM, SILVERM | "Gann" marks 45/90/180 min after 17:00 | +5.1 / +6.7 / +5.7 points more swing turns nearby | WTI +9.8, XAU +3.4, XAG +3.8 (q < 0.001) | the US-session news clock (see "why traders believe", point 3) |

- Rows 1-3 are the only level effects of any size. They are silver only, and in gold the round numbers went the other
  way (XAU round $50/$100: -6 to -13 points in 2015-2025, as R4 found).
  - The trading versions built on them all lost in the locked year: silver round-number fades / magnets / confluence,
    15 option and 15 futures versions, from -Rs 12 to -Rs 2,383 a day.
  - One exception: SILVERMIC confluence with the futures stop, +Rs 15/day on 75 trades.
- The 5 dropped by the tick fix: multiples-of-9 reversal rows on CRUDEOIL, SILVERM (2) and SILVER, plus the SILVERM
  multiples-of-9 touch row. Those were the tick-size artefact.

- **For a trader, the claim is "price reacts / turns at the level". That claim failed in every commodity, for every
  family.** The locked year had 132 tick-fair reversal tests (6 commodities x 11 families x 2 d values):
  - **none survives BH.** 12 have p < 0.05, against about 7 expected by chance, and they are scattered with no
    pattern;
  - Square-of-9: -3.6 to +1.7 points;
  - Fibonacci pivots / previous-day range / Gann quarters: -5.7 to +5.1 points;
  - round levels: -9.8 to +4.8 points;
  - Gann points: -2.6 to +4.1 points;
  - multiples of 9: -1.5 to +0.7 points;
  - swing Fibonacci: -0.4 to +0.6 points on 2,700-15,600 touches each.
- **Round numbers in the locked year: reversal minus placebo, points, d 0.10% / 0.25% (tick-fair):**

| commodity | Rs grid | 0.10% | 0.25% |
|---|---|---|---|
| crude | 50 | -0.3 | -2.1 |
| crude | 100 | +0.5 | -1.3 |
| crude | 500 | -6.7 | -4.3 |
| natural gas | 5 | +3.0 | +2.4 |
| natural gas | 10 | +1.5 | +1.5 |
| natural gas | 50 | -9.8 | -5.0 |
| GOLDM | 100 | +0.2 | +0.3 |
| GOLDM | 500 | +2.1 | +2.7 |
| GOLDM | 1,000 | +0.5 | +2.1 |
| SILVERM | 500 | +0.8 | +0.9 |
| SILVERM | 1,000 | +3.4 | +0.8 |
| SILVERM | 5,000 | +3.5 | +2.8 |

- **Crude's big round numbers (Rs 500) and gas's (Rs 50) were broken more often than random levels.** This matches
  R4's finding on indices and gold (stops cluster beyond round numbers). In design, crude Rs 500 levels were -11 /
  -38 points; NIFTY/BANKNIFTY in R4 were -1 to -6.

### Reversal rate, claimed minus placebo (points). Each cell: design / locked year / locked year tick-fair (locked-year touches)

d = 0.10%:

| family | CRUDEOIL | NATURALGAS | GOLDM | SILVERM | GOLD | SILVER |
|---|---|---|---|---|---|---|
| Square-of-9 0.125 | -2.8 / +0.3 / -0.1 (2,888) | -3.7 / -2.5 / -3.6 (747) | +1.7 / -0.4 / -0.3 (3,847) | -0.2 / +0.1 / +0.0 (4,318) | -0.2 / +0.7 / +0.5 (1,850) | +0.7 / -0.5 / -0.3 (2,225) |
| Square-of-9 0.25 | -2.7 / -1.7 / -2.2 (1,453) | +0.0 / -0.3 / -2.0 (381) | +1.6 / -1.0 / -0.9 (1,946) | +1.5 / +0.1 / +0.0 (2,196) | +4.6 / -0.1 / -0.2 (931) | -2.5 / -0.9 / -0.8 (1,128) |
| Round "50s" | +6.7 / +1.9 / -0.3 (1,228) | +7.9 / +4.2 / +3.0 (559) | +0.0 / +0.4 / +0.2 (4,856) | -3.4 / +0.9 / +0.8 (2,161) | +0.7 / +0.0 / -0.1 (2,063) | +0.5 / -0.1 / -0.1 (913) |
| Round "00s" | +8.4 / +2.4 / +0.5 (637) | -1.6 / +2.2 / +1.5 (288) | +12.8 / +2.2 / +2.1 (978) | -7.1 / +3.5 / +3.4 (1,089) | -7.0 / -0.1 / -0.3 (434) | -1.0 / -2.2 / -2.3 (464) |
| Round major | -11.3 / -5.0 / -6.7 (136) | +27.3 / -9.2 / -9.8 (72) | +24.0 / +0.7 / +0.5 (489) | -14.6 / +3.6 / +3.5 (223) | -14.3 / +2.0 / +1.8 (227) | -12.1 / +4.6 / +4.6 (107) |
| Multiples of 9 | +6.8 / +2.8 / +0.6 (4,149) | -3.0 / -1.9 / -1.5 (1,987) | +0.3 / +0.1 / -0.0 (46,157) | +0.2 / +0.1 / +0.0 (79,610) | +0.1 / +0.1 / -0.0 (20,933) | +0.1 / +0.1 / +0.0 (36,214) |
| Gann points from the open | -3.2 / -0.4 / -2.6 (946) | -3.4 / +6.1 / +1.5 (437) | +11.8 / +3.3 / +3.2 (744) | +1.7 / +1.1 / +1.0 (1,150) | -1.1 / +2.3 / +2.1 (332) | -3.2 / -1.1 / -1.2 (550) |
| Fibonacci pivots | -6.9 / +1.2 / +0.3 (501) | +0.2 / +5.4 / +4.6 (536) | +5.5 / +0.2 / +0.2 (432) | -9.0 / -1.5 / -1.6 (370) | +1.9 / +2.3 / +2.9 (223) | +0.1 / -2.4 / -2.5 (216) |
| Prev-day range Fib | +4.6 / +1.6 / +0.3 (644) | +6.7 / +1.0 / +0.7 (613) | -1.6 / -1.5 / -1.4 (474) | -0.8 / +2.8 / +2.8 (427) | +2.1 / +1.3 / +1.1 (223) | +5.5 / -0.4 / -0.4 (203) |
| Gann 25/50/75% | +9.1 / +0.7 / +0.9 (382) | +7.4 / -1.5 / -2.7 (368) | -3.5 / -2.7 / -2.5 (285) | -11.4 / +3.4 / +3.4 (252) | -3.3 / +4.6 / +5.1 (135) | -11.0 / -2.1 / -2.2 (122) |
| Swing Fibonacci (38.2-161.8%) | +0.6 / +0.7 / +0.5 (15,129) | -0.9 / +0.2 / -0.1 (15,611) | -0.0 / -0.3 / -0.4 (13,598) | -0.7 / +0.1 / +0.1 (12,221) | +0.1 / +0.5 / +0.5 (5,971) | +0.6 / +0.0 / -0.0 (6,210) |

d = 0.25%:

| family | CRUDEOIL | NATURALGAS | GOLDM | SILVERM | GOLD | SILVER |
|---|---|---|---|---|---|---|
| Square-of-9 0.125 | -3.3 / +0.4 / +0.1 | +3.6 / +0.6 / -1.2 | +2.3 / +0.1 / +0.2 | -0.9 / +0.1 / +0.0 | +0.7 / -0.5 / -0.5 | +1.2 / -0.1 / -0.0 |
| Square-of-9 0.25 | -7.5 / -2.9 / -3.0 | +3.1 / +2.3 / +1.7 | +3.9 / -1.1 / -1.0 | +0.0 / -0.0 / -0.1 | +6.6 / -0.7 / -0.7 | -0.2 / -0.2 / -0.1 |
| Round "00s" | -12.2 / -0.2 / -1.3 | -8.4 / +2.1 / +1.5 | +16.2 / +2.8 / +2.7 | -7.3 / +0.9 / +0.8 | -10.2 / -0.5 / -0.5 | +2.0 / -4.4 / -4.5 |
| Round major | -38.1 / -3.2 / -4.3 | +6.6 / -5.1 / -5.0 | +17.4 / +2.2 / +2.1 | -13.3 / +2.8 / +2.8 | -4.5 / +5.0 / +4.8 | +2.9 / -0.2 / -0.3 |
| Fibonacci pivots | -2.3 / +1.8 / +1.5 | -5.8 / +2.6 / +3.3 | -3.3 / +3.1 / +3.6 | -5.3 / -3.0 / -3.0 | +8.5 / +3.4 / +3.3 | -0.4 / -3.0 / -3.0 |
| Prev-day range Fib | -1.4 / -1.4 / -1.8 | +0.4 / -1.4 / -2.1 | +3.9 / -5.8 / -5.7 | +3.9 / -1.4 / -1.4 | +4.5 / +3.3 / +3.2 | +7.4 / -0.3 / -0.9 |
| Gann 25/50/75% | +4.3 / -3.8 / -2.0 | +6.7 / -1.3 / -2.1 | +1.8 / -4.5 / -3.9 | -10.0 / -3.8 / -3.8 | -12.3 / -1.5 / -0.4 | +13.3 / -0.3 / -0.3 |
| Swing Fibonacci | +0.5 / +0.1 / +0.0 | +0.3 / -0.0 / -0.1 | -0.9 / +0.2 / +0.2 | +0.4 / -0.2 / -0.3 | +0.3 / +0.6 / +0.6 | -0.2 / +0.4 / +0.3 |

Design cells rest on 37 sessions, so a ±10-point design swing on 30-600 touches is noise. Every design cell above
+8 points fell to between -9.2 and +3.4 points in the locked year. Examples: gold Rs 1,000 from +24 to +0.7;
natural gas Rs 50 from +27 to -9.

### Time placebo (share of marks within ±5 min of a 5-min swing turn, claimed vs marks shifted ±10-30 min), locked year

| mark | CRUDEOIL | NATURALGAS | GOLDM | SILVERM | GOLD | SILVER |
|---|---|---|---|---|---|---|
| Gann 45/90/180 min from 09:00 | 25.0 vs 25.0 | 23.8 vs 25.6 | 30.4 vs 29.5 | 27.1 vs 30.9 | 27.9 vs 26.6 | 23.9 vs 22.4 |
| same from 17:00 (17:45 / 18:30 / 20:00 = US data, COMEX/NYMEX open, EIA) | **45.6 vs 40.5** (p 0.008) | 47.9 vs 45.5 | **49.6 vs 42.9** (q 0.04) | **51.7 vs 46.0** (q 0.045) | 40.2 vs 41.5 | 46.5 vs 44.9 |
| Gann 90/144 min after the morning extreme | 36.9 vs 39.0 | 39.1 vs 34.9 | 37.7 vs 34.4 | 38.5 vs 37.3 | 33.9 vs 30.3 | 39.6 vs 35.8 |
| Fibonacci time zones (5..55 bars) | 37.6 vs 37.6 | 40.3 vs 39.6 (q 0.045) | 38.6 vs 38.6 | 40.2 vs 40.3 | 37.8 vs 38.0 | 38.9 vs 39.5 |

### World proxies (USD levels, MCX hours in IST, labelled PROXY)

Proxy data:
- XAUUSD: design 2015 - Sep 2025 (2,705 days); holdout Oct 2025 - Sep 2026 (255 days).
- XAGUSD: design 2018 - Sep 2025 (1,995 days); holdout Oct - Dec 2025 (65 days).
- WTIUSD: design 2018 - Nov 2023 (1,522 days).

Levels:
- Round numbers: XAU $10 / $50 / $100; XAG $0.25 / $0.50 / $1; WTI $0.50 / $1 / $5.
- Gann points and multiples of 9 are scaled to the price.

Reversal, claimed minus placebo, points (d 0.10% / 0.25%):

| family | XAU design | XAU holdout | XAG design | XAG holdout (65 d) | WTI design |
|---|---|---|---|---|---|
| Square-of-9 0.125 | -0.6 / -0.7 | -0.5 / +0.6 | +3.2 / +4.0 | +5.2 / -3.8 | +1.5 / +1.1 |
| Square-of-9 0.25 | -0.0 / +1.1 | +0.4 / -1.1 | +4.4 / +3.6 | +12.9 / -3.4 | +0.2 / +1.2 |
| round small | -1.6 / -4.9 | -0.2 / +0.3 | **+2.2 / +2.6** | +0.1 / +2.6 | -2.8 / -2.0 |
| round mid | -6.2 / -10.3 | -0.4 / -2.2 | **+3.4** / +2.2 | +1.4 / +2.1 | -5.1 / -2.0 |
| round major | -7.1 / -12.9 | -1.7 / -6.2 | +2.9 / +2.4 | +3.6 / +1.4 | -6.6 / -2.9 |
| multiples of 9 | -0.3 / -1.8 | +0.7 / -0.2 | -0.6 / +0.0 | +0.3 / -1.2 | -0.9 / +0.6 |
| Gann points | -0.1 / -0.9 | -0.1 / +0.7 | -0.0 / -0.8 | +2.6 / +0.3 | -9.9 / -9.1 |
| Fibonacci pivots | +0.1 / -0.0 | +4.0 / +2.7 | +1.4 / +0.5 | +0.6 / -1.5 | -0.4 / -1.6 |
| prev-day range Fib | -0.4 / +0.2 | -0.2 / -0.1 | +0.5 / -0.4 | +3.1 / -0.3 | -1.1 / +0.6 |
| Gann 25/50/75% | +0.3 / +0.7 | -2.0 / +0.1 | +0.2 / -0.5 | +1.3 / -1.4 | +0.7 / +1.1 |
| swing Fibonacci | +0.2 / +0.0 | +0.2 / **+0.6** | +0.1 / +0.1 | +0.5 / +0.4 | **+0.3** / +0.1 |

Bold = BH q < 0.05 within the proxy run.
- **Gold:** round numbers were again broken more often than random levels (R4's finding, now to 2025).
- **Silver:** round numbers reversed 2-3 points more often.
- **Crude (WTI):** round numbers were broken more often, and Gann point-numbers were broken much more often (-9 to -10).
- Square-of-9 on XAG looks positive but is not significant: XAG's Square-of-9 grid is very coarse at $15-35, so there
  are few touches.
- Time marks:
  - the evening marks are significant everywhere (+3.4 to +9.8 points);
  - the 09:00-based Gann marks are -1.6 to -5.6 points (worse than random);
  - Fibonacci time zones are -0.3 to -0.9 points.


## Trading tests

### Per book (versions = rule x parameters x exit; Rs/day over all sessions of the period, 1 lot, after costs)

| book | versions | positive in design | best design | positive in locked year | median locked year | best locked year | frozen picks (positive) | median pick | PASS-A | PASS-B |
|---|---|---|---|---|---|---|---|---|---|---|
| CRUDEOIL option | 236 | 56 | +783 | 23 | -226 | +52 | 17 (0) | -977 | 0 | 0 |
| NATURALGAS option | 230 | 51 | +783 | 34 | -101 | +143 | 16 (1) | -408 | 0 | 0 |
| NATGASMINI option | 233 | 25 | +92 | 24 | -46 | +14 | 15 (0) | -122 | 0 | 0 |
| GOLDM option | 218 | 31 | +140 | 23 | -138 | +65 | 4 (0) | -875 | 0 | 0 |
| SILVERM option | 203 | 15 | +109 | 16 | -119 | +52 | 4 (0) | -1,049 | 0 | 0 |
| CRUDEOILM future | 236 | 41 | +64 | 29 | -41 | +86 | 17 (0) | -127 | 0 | 0 |
| NATGASMINI future | 233 | 38 | +294 | 34 | -31 | +122 | 17 (0) | -130 | 0 | 0 |
| SILVERMIC future | 233 | 40 | +147 | 40 | -34 | +439 | 17 (2) | -138 | 0 | 0 |
| GOLDPETAL future | 227 | 8 | +6 | 17 | -10 | +12 | 17 (1) | -14 | 0 | 0 |

GOLDM and SILVERM options have only 4 frozen picks. Their morning books print rarely, so only 15-18 versions reached
30 design trades.

### By topic, locked year (median and best Rs/day across versions)

| book | 1 magic | 2 Gann | 3 Fibonacci | 4 harmonics |
|---|---|---|---|---|
| CRUDEOIL option | -589 / -60 | -622 / +24 | -836 / -11 | -31 / +52 |
| NATURALGAS option | -268 / +29 | -198 / +143 | -381 / +50 | -22 / +45 |
| NATGASMINI option | -101 / -15 | -76 / +14 | -120 / -3 | -4 / +13 |
| GOLDM option | -255 / -69 | -298 / +2 | -490 / -74 | -22 / +65 |
| SILVERM option | -163 / +45 | -268 / +13 | -491 / +52 | -20 / +27 |
| CRUDEOILM future | -82 / +14 | -79 / +86 | -137 / +47 | -7 / +15 |
| NATGASMINI future | -74 / +28 | -75 / +122 | -148 / +17 | -5 / +14 |
| SILVERMIC future | -149 / +89 | -133 / +439 | -261 / +77 | -8 / +23 |
| GOLDPETAL future | -17 / -1 | -13 / +12 | -24 / -6 | -1 / +1 |

Harmonics lose the least only because they rarely trade. They fire 1-100 times a year per pattern; in this table a
version with no trade scores Rs 0.

### What the frozen picks did (selected; all 124 are in `results/all_variants.csv`, `is_pick`)

| book | pick (family / exit) | design trades, Rs/day | locked-year trades | Rs/day | Rs/trade | twins Rs/trade | p vs twins | green months |
|---|---|---|---|---|---|---|---|---|
| CRUDEOIL option | Square-of-9 from the 09:00 open, OPT | 35, +783 | 233 | -2,029 | -2,159 | -1,857 | 0.73 | 1/13 |
| CRUDEOIL option | Fib pivot break, OPT | 36, +511 | 176 | -954 | -1,344 | -1,820 | 0.13 | 2/13 |
| CRUDEOIL option | Square-of-9 0.25 from 17:00, NATk1 | 35, +390 | 236 | -601 | -632 | -373 | 0.92 | 4/13 |
| NATURALGAS option | Square-of-9 from 17:00, NATk1 | 35, +783 | 234 | -406 | -429 | -382 | 0.59 | 5/13 |
| NATURALGAS option | Gann 50% break, NAT | 39, -57 | 186 | **+39** | +52 | -107 | 0.14 | 8/13 |
| NATGASMINI future | Square-of-9 from 17:00, NATk1 | 37, +294 | 246 | -140 | -141 | -62 | 0.87 | 4/13 |
| CRUDEOILM future | Square-of-9 from prev close, PCT | 37, +42 | 248 | -11 | -11 | -73 | 0.05 | 5/13 |
| SILVERMIC future | Gann swing chart 15-min, NAT | 48, -94 | 189 | **+439** | +434 | -118 | 0.05 | 11/12 |
| SILVERMIC future | Square-of-9 0.25 from prev close, PCT | 37, -101 | 187 | **+74** | +74 | -91 | 0.11 | 5/12 |
| GOLDPETAL future | Gann swing chart 15-min, NAT | 35, -17 | 225 | **+12** | +13 | -16 | 0.000 | 9/13 |
| GOLDM option | Gann HiLo 5-min, NAT | 87, -234 | 617 | -896 | -343 | -325 | 0.61 | 0/13 |
| SILVERM option | Fib time zones, OPT | 43, -379 | 195 | -2,162 | -2,073 | -1,252 | 0.99 | 0/12 |

- The 4 positive picks were all **negative in design**. They were picked only as the "least bad" in their family.
- None meets PASS-A (it needs design > 0 and q < 0.10) or PASS-B (it needs holdout BH q < 0.05; the best was 1.00).
- The GOLDPETAL swing chart beat its twins at p < 0.001. But among the 124 picks, BH on twins gives q 0.11. Its
  design was negative.
- **POST-HOC side split** of the swing charts:
  - SILVERMIC 15-min: longs +Rs 81,669 over 98 trades; shorts +Rs 342 over 91.
  - GOLDPETAL: longs +Rs 16.8 a trade, shorts +Rs 9.0.
  - CRUDEOILM 60-min: longs +Rs 62, shorts +Rs 433 a trade.
  - So it is a slow breakout follower that rode the 2025-26 trends, mainly silver's. It is not a Gann edge, and it
    did not work in design.

## Margin check (Rs 1 lakh, 1 lot)

| instrument | what 1 lot needs | fits? |
|---|---|---|
| CRUDEOIL 1-ITM option | premium: median Rs 31,250; 90th percentile Rs 75,300; 0.9% of entries above Rs 1 lakh (max Rs 1.75 lakh) | yes, mostly |
| NATURALGAS 1-ITM option | median Rs 20,375; 90th percentile Rs 33,700 | yes |
| NATGASMINI 1-ITM option | median Rs 4,100 | yes |
| GOLDM 1-ITM option | median Rs 27,400; max Rs 94,000 | yes |
| SILVERM 1-ITM option | median Rs 34,800; 90th percentile Rs 52,500 | yes |
| CRUDEOILM future | margin about Rs 31,500 (M3), notional about Rs 87,000 | yes |
| NATGASMINI future | margin about Rs 11,700 (M3), notional about Rs 79,000 | yes |
| SILVERMIC future | margin about Rs 54,000 (SILVERM Rs 2.7 lakh / 5), notional about Rs 2.26 lakh | yes, 1 lot only |
| GOLDPETAL future | margin about Rs 1,600 (GOLDM Rs 1.57 lakh / 100), notional about Rs 14,850 | yes |
| GOLDM / SILVERM futures | margin Rs 1.57 lakh / Rs 2.7 lakh | **no** |

Margins come from M3's Zerodha figures, scaled by lot size. They are estimates, not quoted today.

## Paper-bot rules

**None.** Nothing passed either pre-registered gate.
- The "closest" rule (Gann swing chart on 15-min SILVERMIC / GOLDPETAL futures) lost money in design. It made its
  locked-year money only on the long side of a silver bull run. It is 1 of 2,034 versions with t 1.3.
- Adding it as a bot would be fitting the locked year.
- If Boss wants to watch it anyway, it needs a fresh pre-registered forward test (from 9 Oct 2026, both commodities,
  shorts reported separately), not a bot.

## Data notes and limits

- **History is short.** Dhan serves no expired MCX futures, and MCX rollingoption starts 5 Aug 2025 (R3, M3). Every MCX
  series therefore starts 5 Aug 2025, and **design is only about 37 sessions (Aug-Sep 2025)**.
  - The frozen picks rest on 30-100 design trades.
  - That is why a second, pre-registered locked-year test over all versions was added (PASS-B). It also found nothing.
- **Futures prices are the rollingoption `spot`**: near-month futures, one CLOSE per minute, no OHLC. Bars and level
  touches are built from minute closes, and futures fills are at minute closes.
- **Mini/micro books trade the main contract's price series**, labelled as such:
  - CRUDEOILM and NATGASMINI share CRUDEOIL / NATURALGAS months and prices;
  - SILVERMIC trades SILVERM's months;
  - GOLDPETAL is GOLDM's per-10 g price / 10.
- **The series rolls at each option expiry.** The first session of each new contract is dropped (14 a year). Previous-day
  levels never mix contracts. GOLD and SILVER (big contracts, information only) have sparse minutes: 106-117 usable
  locked-year days.
- **Options:**
  - Dhan's ATM-3..ATM+3 near-month CE/PE, 1-minute OHLC with volume.
  - Only printed minutes (volume > 0) are used for fills and wick exits. 1-ITM means Dhan's ATM-1 call / ATM+1 put
    at the signal minute.
  - Morning books (GOLDM, SILVERM) print rarely, so fewer trades.
- **Spreads:** only crude's evening spread was ever measured live (0.30%). The others are the brief's assumptions:
  - crude/natural gas 0.60 / 0.30%;
  - GOLDM/SILVERM 0.80 / 0.40%;
  - NATGASMINI 0.80 / 0.40%, assumed.
  - `scratchpad/hunt/m3/work/spreads.json` is still the placeholder, and no 9 Oct chain snapshot for non-crude books
    was present.
  - Halving the spreads would not change the verdict: the option books are negative before costs.
- **Tick-size fix (POST-HOC):** found when the design table showed big "multiples of 9" effects, before the locked year
  was opened. Both versions are reported. The verdict uses the stricter (tick-fair) one only where it changes a
  conclusion: the multiples-of-9 rows.
- **Dhan:** today's only Dhan calls were the 8-9 Oct top-up (`fetch_tail.py`: 42 requests at 1 per second, for
  NATURALGAS, SILVERM and CRUDEOIL options). Everything else was already local in M3 / STRAD-CRUDE / R3. The live
  recorder was not touched.
- **No natural-gas world proxy** exists locally. WTI (histdata) stops in Nov 2023. The XAG proxy's locked-year part is
  only Oct-Dec 2025.
- The random twins share each trade's day and time ±30 min, so they carry the same decay and spread. That makes
  "method vs twin" the clean test of the rule itself.
