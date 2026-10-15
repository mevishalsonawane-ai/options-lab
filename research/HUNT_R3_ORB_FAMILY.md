# R3: the ORB family deep study (ORB, ORB Fresh, ORB Sweep, Range Fade) on the 2021-2026 lake

Written 14 Oct 2026 for Boss ("We closed mostly all the arms, not a single trade from morning, what should we do? Do.").
TODO.md R3: the four arms were PAUSED on 13 Oct after losing in every backtest year. This study rebuilds them on the full
Dhan lake, finds when they win and lose, and tests 16 pre-registered enhanced versions on unseen data. Research only:
no app code changed.

Pre-registration `research/hunt/r3orb/PREREG.md`:
- Part A (data, the exact rebuild, the holdout choice, the 16 variant slots, the gates, BH m = 183) was committed in
  **5a210ca** before any outcome was computed.
- Part B (each arm's filter, chosen by Part A's fixed rule on 2021-2024 design trades) was committed in **e688758**
  before any variant was run.
- The code was frozen in **6b5cee3** before the one holdout read (14 Oct 2026 06:24 UTC).

Code: `research/hunt/r3orb/` (`r3data.py`, `r3engine.py`, `study.py`, `report.py`, `sens.py`, `entries.py`, tests
`test_r3orb.py`). Tables: `results/rebuild.md`, `results/anatomy.md`, `results/report.md`; JSON beside them.

## Verdict for Boss (read this first)

1. **No version of the four arms can come back on paper.** All 16 enhanced versions failed. They lost money on the
   design data (BANKNIFTY + NIFTY, Aug 2021 - Oct 2026) in every year. They also lost on the unseen indices (FINNIFTY,
   MIDCPNIFTY, SENSEX). Not one passed a single money gate. **ORB, ORB Fresh, ORB Sweep and Range Fade stay paused.**
   Keep their code and settings as they are. Re-arming any of them would lose money on paper too.
2. **How much they lose, as the app has them today** (1 lot, after real charges and slippage):

   | arm | design: BANKNIFTY + NIFTY, 5 years | per day | years in profit | unseen indices (FINNIFTY, MIDCPNIFTY, SENSEX) |
   |---|---|---|---|---|
   | ORB | Rs -2,23,040 (2,489 trades) | Rs -174 | 0 of 6 | Rs -1,13,993 (2,572 trades) |
   | ORB Fresh | Rs -2,23,812 (2,489) | Rs -174 | 0 of 6 | Rs -1,49,434 (2,556) |
   | ORB Sweep | Rs -5,05,954 (3,156) | Rs -394 | 0 of 6 | Rs -5,85,798 (3,303) |
   | Range Fade | Rs -5,10,978 (3,512) | Rs -398 | 0 of 6 | Rs -5,77,344 (3,794) |

   On BANKNIFTY alone, the app's ORB lost in every year from 2021 to 2026. Its losses by year match the earlier R14
   study within a few percent.
3. **Why they lose: the entry, not the exit.**
   - The ORB buys after the market has already broken out. The move has mostly happened, so the option is bought high.
   - Even with hindsight it does not work. On days that really trended in the trade's direction, the ORB still lost
     Rs 52 a trade (1,094 trades).
   - Nothing known at the moment of entry separates good trades from bad ones well enough:
     - VIX level or VIX change, the gap, the width of the opening range, the time of day, expiry day, and how far price
       is from its morning average all fall short;
     - split into three equal groups by any of these, even the BEST group still loses, for every arm.
   - Sweep and Range Fade bet that the break will fail. They make money only on trend days traded WITH the trend, and
     that is known only after the close.
4. **What the enhancements did (honestly):**
   - **Skipping the worst day types** (VIX / expiry filters) lost less, but never turned a profit:
     - ORB: Rs -1.75 lakh instead of -2.23 lakh on design;
     - ORB Sweep: Rs -3.91 lakh instead of -5.86 lakh on the unseen indices.
   - **Your consistency rules** were tested on all four arms: no fixed target, "+Rs 1,000 then keep Rs 500", the Rs 500
     give-back trail, and a 45-minute time stop. They made ORB and ORB Fresh worse (ORB unseen: Rs -2.66 lakh vs -1.14
     lakh). They made Sweep and Fade only a little less bad. Letting winners run does not help, because the trades rarely
     run.
   - **1 strike in the money with 1 entry a side a day** helped Sweep and Fade the most, because it cuts the number of
     trades. It is still a loss.
   - **All three together** was the least bad version of every arm, still a clear loss: ORB Rs -1.70 lakh on design
     and Rs -1.47 lakh unseen.
   - The best FINNIFTY result was ORB with the in-the-money strike: Rs +29,700 on FINNIFTY alone. It was still
     negative on MIDCPNIFTY and SENSEX, it lost on design, and it is one index out of five. It is noise, not an edge.
5. **Your 13 Oct case** (BANKNIFTY's 630-point opening range, no break until 14:35):
   - On BANKNIFTY days whose opening range is at least 0.8 x ATR14, price stayed inside the range until 13:55 on 28%
     of days (33 of 119);
   - on narrow days (under 0.4 x ATR14) it was 6% (26 of 421).
   - So "no trade on a wide day" is normal, and it saved money: the ORB loses on the days it does trade. Adapting the
     range (R20, R23) was already tested and did not help either.
6. **What to do instead:** nothing in the ORB family. The study is clear and the data set is the largest we have (5
   indices, 5 years, about 6,000 sessions). Spend paper time on the arms that are still being tested. The paper run of
   13-26 Oct keeps its plain-rules record for the 26 Oct decision; R3 adds no ORB change to it.

Plain answer: we tried the four ORB arms on every index and every year we have, and 16 sensible ways to fix them. Every
version loses money after costs, both on the data used to design the fixes and on the indices it had never seen. They
stay paused. Nothing goes on paper.

## Data

- Lake catalog `data-20261014` (Drive `IraAlgo-data-lake`), read straight from Parquet by `r3data.py`.
  - Options: `opt_<UND>_WEEK_<YEAR>` (Dhan "rollingoption": the nearest expiry on or after the day, ATM+-10 each minute).
    This is the ORB family's own contract rule (`OrbRules.expiryOnOrAfter`: today's option on an expiry day).
  - After Nov 2024, BANKNIFTY / FINNIFTY / MIDCPNIFTY trade their monthly, as the app would.
  - Index and India VIX: `index_1m_*`.

| index | role | sessions (after 15 for ATR14) | first | last | expiry days |
|---|---|---|---|---|---|
| BANKNIFTY | design (the app's ORB index) | 1,255 | 2021-09-03 | 2026-10-12 | 189 |
| NIFTY | design | 1,284 | 2021-08-02 | 2026-10-12 | 271 |
| FINNIFTY | holdout | 1,254 | 2021-09-03 | 2026-10-12 | 171 |
| MIDCPNIFTY | holdout | 906 | 2022-02-21 | 2026-10-12 | 101 |
| SENSEX | holdout | 845 | 2023-05-15 | 2026-10-12 | 175 |

- Costs: the app's charges at today's rates (BSE rates for SENSEX). Fills are R17's "liq" model: +-5 bps plus 1-4 ticks
  of half-spread by the contract's traded lots; stops pay 10 bps and double ticks. 1 lot in force on the day.
- Stop / target points: the app's 40 on BANKNIFTY (Sweep target 80). Scaled by price level and fixed before any
  outcome: NIFTY 20, FINNIFTY 20, MIDCPNIFTY 10, SENSEX 60.

## The holdout: why the other indices, and what that is worth

The holdout choice was committed before any outcome was read (PREREG A4). **No block of the lake was unread for this
family.**
- R12, R14, H19 and H20 ran the app's four arms on BANKNIFTY from Aug 2021 to 6 Oct 2026.
- H43 ran a generic ORB grid on all five indices.
- R16-R23 used the release data, and R24 read the lake's last 6 months.

So R3's holdout is **out-of-instrument**: FINNIFTY, MIDCPNIFTY and SENSEX, the indices on which these four arms' exact
rules had never been run with option prices. Two caveats, stated in Part A:
- They are the same market days as the design data, and these indices move together.
- H43 had computed other ORB-type outcomes on them.

A pass here would have been weaker than a pass on a fresh time block. Since everything failed, the caveat does not
change the answer.

The read happened once (`results/HOLDOUT_READ`, 06:24 UTC). `r3data.load` refuses a holdout index without that flag, and
`study.py holdout` refuses a second read.

## Step 1: the rebuild (design indices; matches the earlier studies)

Every arm is rebuilt line by line from `OrbRules` / `SweepRules` / `RangeFadeRules` / `ProfitLock` / `OrbArms`:
- the range is 09:15-10:04;
- ORB / Fresh decide on bars 10:05-13:55, Sweep on 10:05-14:25, Fade on 10:30-13:55;
- the strike is the ATM of the 09:20 bar's close;
- entry at the next minute's open, refused at or below the stop points;
- -40 / +40 (Sweep +80), the profit-lock ladder from the next minute;
- out at 15:10, or 15:05 on an expiry day;
- cooldown to the bar after the exit;
- ORB / Fresh capped at 1 entry a side a day (paper since 13 Oct); Sweep / Fade at 2 a day.

Checks against earlier work:
- **ORB capped, BANKNIFTY, by year:**

  | year | R3 | R14 R1 |
  |---|---|---|
  | 2022 | -41,633 | -40,534 |
  | 2023 | -28,775 | -29,514 |
  | 2024 | -13,150 | -11,741 |

- **ORB uncapped, BANKNIFTY:** R3 9,492 trades / Rs -9.27 lakh; R12 / R14 9,724 trades / Rs -8.39 lakh (design + holdout,
  to 5 Oct 2026).
- **Sweep / Fade trade counts:** R3 1,677 / 1,843; R12 1,661 / 1,836.
- **ORB Fresh** equals ORB once both are capped at 1 a side: the first break of a side is always fresh. R18 and R19c
  saw the same. Its holdout differs a little, on days with missing bars.

Per year (Rs, trades in brackets):

| arm | index | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|
| ORB | BANKNIFTY | -4,106 (77) | -41,633 (247) | -28,775 (229) | -13,150 (236) | -26,485 (232) | -9,786 (186) |
| ORB | NIFTY | -3,934 (110) | -36,338 (259) | -32,159 (244) | -10,790 (248) | +3,119 (232) | -19,003 (189) |
| ORB Sweep | BANKNIFTY | -11,962 (99) | -49,516 (343) | -53,934 (318) | -22,196 (325) | -90,399 (335) | -71,421 (257) |
| ORB Sweep | NIFTY | -30,028 (125) | -53,853 (305) | -36,407 (259) | -21,470 (288) | -66,505 (261) | +1,738 (241) |
| Range Fade | BANKNIFTY | -19,105 (110) | -47,618 (383) | -50,997 (349) | -31,687 (337) | -83,166 (363) | -53,460 (301) |
| Range Fade | NIFTY | -28,037 (140) | -44,809 (336) | -36,304 (303) | -21,704 (296) | -68,237 (311) | -25,853 (283) |

Only 2 of 36 index-years are positive (NIFTY ORB 2025, NIFTY Sweep 2026), both small. ORB Fresh: `results/rebuild.md`.

## Step 2: anatomy (design indices only; `results/anatomy.md`)

Mean net Rs per trade (trades). "Day type" needs the whole day, so it is hindsight and never a filter.

| split | ORB | ORB Sweep | Range Fade |
|---|---|---|---|
| range day | -76 (1,047) | -122 (1,368) | -120 (1,504) |
| trend day, trade WITH the trend | -52 (1,094) | **+377** (528) | **+98** (594) |
| trend day, trade AGAINST the trend | -247 (348) | -427 (1,260) | -275 (1,414) |
| day closed inside the opening range | -149 (779) | -1 (1,162) | -19 (1,333) |
| day closed outside it | -62 (1,710) | -253 (1,994) | -223 (2,179) |
| expiry day / not | -61 (447) / -96 (2,042) | -185 / -157 | -155 / -144 |
| signal 10:00-10:59 / 11 / 12 / 13 h | -121 / -84 / **+28** (311) / -66 | -172 / -138 / -233 / -90 (14 h -117) | -140 / -153 / -154 / -124 |
| BANKNIFTY / NIFTY | -103 / -77 | -179 / -140 | -155 / -135 |
| CE / PE | -85 / -94 | -191 / -130 | -149 / -142 |
| 1st / 2nd entry of the day | -103 / +29 (258) | -158 / -165 | -156 / -130 |
| exits: stop | -1,131 (511) | -1,131 (1,154) | -1,137 (838) |
| exits: ladder lock | +39 (1,668) | +220 (1,601) | +73 (2,317) |
| exits: target | +951 (307) | +2,101 (233) | +1,024 (297) |

Terciles of each point-in-time feature (VIX previous close, VIX change since 09:15, gap in the trade's direction / ATR,
range width / ATR, distance from the morning TWAP / ATR), on the 2021-2024 trades that Part B used: the spread between the
best and worst third is Rs 4-85 a trade. **Every third of every feature loses for every arm**, in those years and over
all six years (`results/anatomy.md`, `results/partb.json`). The least bad is ORB with the VIX change in its top third
(2021-2024): Rs -63 a trade.

What this says:
- **ORB:** the stop costs Rs 1,131 a trade. The ladder locks breakeven at +10 and gets shaken out (1,668 lock exits
  averaging Rs +39). Even trades in the direction the day went lose on average. Entering on the 5-minute close after a
  break buys late, and the remaining move does not pay for the stop and the costs.
- **Sweep / Fade:** these are reversal bets. They pay only when the reversal becomes the day's trend, which a filter at
  entry cannot see.
- **Re-entries** (uncapped ORB): 9,492 BANKNIFTY trades, Rs -98 each. The cap of 1 a side is right (as R14 found), but a
  cap cannot make the first entry profitable.
- **Trade manager / specialists:** not rebuilt. R12 tested them on these arms; only ORB Fresh's paper ACT acts, and it
  did not make the arm positive.

**Wide opening ranges (13 Oct's case):** sessions with no 5-minute close beyond the range by 13:55.

| index | width < 0.4 ATR | 0.4-0.6 | 0.6-0.8 | >= 0.8 |
|---|---|---|---|---|
| BANKNIFTY | 26 of 421 (6%) | 68 of 492 (14%) | 41 of 222 (18%) | 33 of 119 (28%) |
| NIFTY | 38 of 479 (8%) | 49 of 511 (10%) | 24 of 188 (13%) | 26 of 105 (25%) |

About a third of those days broke later, between 14:00 and 15:10. R20 tested the later last entry (C1430) and it lost.

## Steps 3-5: the 16 pre-registered variants

Part B's filters, chosen by Part A's fixed rule on 2021-2024 design trades. The rule: the feature with the largest
best-minus-worst tercile spread, whose worst tercile is the same on BANKNIFTY and NIFTY.
- ORB / ORB Fresh: skip when VIX moved between -0.86% and +1.04% since 09:15 (a quiet VIX).
- ORB Sweep: skip when VIX fell more than 0.99%.
- Range Fade: skip expiry days.

The four versions of each arm:
- **E**: no fixed target, the ladder, +Rs 1,000 -> keep Rs 500, the Rs 500 give-back trail, a 45-minute time stop.
- **S**: 1 strike in the money, 1 entry a side a day.
- **C**: F + E + S together.

### Design (BANKNIFTY + NIFTY), Rs after costs

| rule | trades | trades/day | win rate | avg win / loss | net | Rs/day | green days | worst day | max DD | big winners cut short | years > 0 | 2025-26 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB (today) | 2,489 | 1.94 | 36% | +498 / -425 | -2,23,040 | -174 | 42% | -5,298 | -2,38,721 | 1,063 | 0 of 6 | -52,155 |
| ORB-F | 2,222 | | 36% | | -1,75,484 | -137 | | | | 943 | 0 of 6 | -53,943 |
| ORB-E | 2,489 | | 35% | | -2,30,006 | -179 | | | | 1,036 | 0 of 6 | -59,244 |
| ORB-S | 2,489 | | 37% | | -2,16,857 | -169 | | | | 1,108 | 0 of 6 | -55,118 |
| ORB-C | 2,221 | | 36% | | -1,69,567 | -132 | | | | 969 | 0 of 6 | -49,163 |
| ORB Fresh-F / E / S / C | 1,893 / 2,489 / 2,489 / 1,893 | | | | -1,71,694 / -2,30,773 / -2,17,509 / -1,63,199 | | | | | | 0 of 6 each | all < 0 |
| ORB Sweep (today) | 3,156 | 2.46 | 28% | +999 / -605 | -5,05,954 | -394 | 30% | -6,106 | -5,06,911 | 394 | 0 of 6 | -2,26,587 |
| Sweep-F / E / S / C | 2,172 / 3,387 / 2,500 / 1,769 | | | | -3,19,572 / -4,07,744 / -3,42,812 / -1,92,702 | | | | | | 0 of 6 each | all < 0 |
| Range Fade (today) | 3,512 | 2.74 | 30% | +484 / -419 | -5,10,978 | -398 | 36% | -4,751 | -5,11,141 | 1,016 | 0 of 6 | -2,30,716 |
| Fade-F / E / S / C | 3,081 / 3,630 / 2,585 / 2,141 | | | | -4,44,049 / -4,60,856 / -3,68,454 / -2,80,854 | | | | | | 0 of 6 each | all < 0 |

Every row's full numbers: `results/report.md` T1-T2. No variant passed D1-D4.

Walk-forward:
- The Part B filters were chosen on 2021-2024. In 2025-2026 every filtered version still lost.
- Nested selection (pick the best rule on the earlier years, trade it in the next year) lost in every test year:
  - 2023: Sweep-C, Rs -32,293;
  - 2024: Sweep-C, Rs -17,705;
  - 2025: Sweep-C, Rs -56,949, worse than plain ORB's -23,366;
  - 2026: ORB-C, Rs -46,067, worse than plain ORB's -28,789.

### Holdout (FINNIFTY + MIDCPNIFTY + SENSEX, read once), Rs after costs

| rule | trades | trades/day | win rate | avg win / loss | net | Rs/day | green days | worst day | max DD | FINNIFTY / MIDCPNIFTY / SENSEX | twin p | BH q (t / twin) | H1-H5 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB (today) | 2,572 | 2.05 | 41% | +697 / -550 | -1,13,993 | -91 | 44% | -21,250* | -1,36,582 | -28,468 / -53,819 / -31,706 | | | |
| ORB-F | 2,289 | 1.83 | 41% | +714 / -541 | -70,250 | -56 | 45% | -21,250* | -1,09,857 | -10,264 / -30,934 / -29,052 | 0.573 | 1.00 / 1.00 | n y n y n |
| ORB-E | 2,572 | 2.05 | 38% | +577 / -523 | -2,65,716 | -212 | 39% | -22,456 | -2,76,160 | -1,44,734 / -80,140 / -40,842 | 0.918 | 1.00 / 1.00 | n n n y n |
| ORB-S | 2,554 | 2.04 | 41% | +804 / -618 | -83,320 | -66 | 43% | -18,088 | -1,36,797 | **+29,700** / -63,597 / -49,424 | 0.298 | 1.00 / 1.00 | n y n y n |
| ORB-C | 2,275 | 1.81 | 39% | +665 / -533 | -1,46,770 | -117 | 41% | -8,062 | -1,60,642 | -32,168 / -81,387 / -33,215 | 0.484 | 1.00 / 1.00 | n n n y n |
| ORB Fresh (today) | 2,556 | 2.04 | 40% | +663 / -546 | -1,49,434 | -119 | 44% | -21,250* | -1,59,347 | -64,161 / -54,121 / -31,152 | | | |
| Fresh-F | 1,958 | 1.56 | 40% | +672 / -538 | -1,02,865 | -82 | 43% | -22,368* | -1,22,820 | -43,111 / -39,379 / -20,375 | 0.764 | 1.00 / 1.00 | n y n y n |
| Fresh-E | 2,556 | 2.04 | 38% | +567 / -519 | -2,71,695 | -217 | 39% | -22,456 | -2,82,534 | -1,50,226 / -80,443 / -41,026 | 0.942 | 1.00 / 1.00 | n n n y n |
| Fresh-S | 2,537 | 2.02 | 41% | +780 / -607 | -96,033 | -77 | 43% | -18,088 | -1,47,371 | **+19,886** / -65,446 / -50,472 | 0.425 | 1.00 / 1.00 | n y n y n |
| Fresh-C | 1,948 | 1.55 | 39% | +658 / -542 | -1,40,017 | -112 | 41% | -7,617 | -1,51,547 | -21,411 / -89,551 / -29,055 | 0.506 | 1.00 / 1.00 | n y n y n |
| ORB Sweep (today) | 3,303 | 2.63 | 29% | +1,016 / -666 | -5,85,798 | -467 | 31% | -27,672* | -5,87,468 | -2,71,182 / -1,92,949 / -1,21,667 | | | |
| Sweep-F | 2,240 | 1.79 | 29% | +1,012 / -650 | -3,91,289 | -312 | 31% | -27,672* | -4,06,734 | -1,88,096 / -1,17,820 / -85,373 | 0.008 | 1.00 / 0.16 | n y y y n |
| Sweep-E | 3,520 | 2.81 | 29% | +821 / -559 | -5,36,744 | -428 | 29% | -27,278 | -5,37,491 | -2,59,212 / -1,79,187 / -98,345 | 0.001 | 1.00 / 0.04 | n y y y n |
| Sweep-S | 2,535 | 2.02 | 28% | +1,033 / -637 | -4,48,350 | -358 | 28% | -16,711 | -4,63,700 | -1,40,640 / -1,82,706 / -1,25,004 | 0.014 | 1.00 / 0.21 | n y y y n |
| Sweep-C | 1,759 | 1.40 | 29% | +822 / -546 | -2,72,424 | -217 | 29% | -7,639 | -2,72,424 | -1,20,342 / -96,074 / -56,009 | 0.026 | 1.00 / 0.30 | n y y y n |
| Range Fade (today) | 3,794 | 3.03 | 33% | +570 / -507 | -5,77,344 | -460 | 34% | -16,044 | -5,77,344 | -2,75,075 / -1,83,077 / -1,19,192 | | | |
| Fade-F | 3,391 | 2.70 | 33% | +575 / -521 | -5,50,920 | -439 | 35% | -16,044 | -5,50,920 | -2,63,165 / -1,78,671 / -1,09,084 | 0.278 | 1.00 / 1.00 | n y n y n |
| Fade-E | 3,896 | 3.11 | 30% | +522 / -436 | -5,64,373 | -450 | 31% | -16,044 | -5,64,373 | -2,85,025 / -1,91,717 / -87,631 | 0.076 | 1.00 / 0.77 | n y n y n |
| Fade-S | 2,696 | 2.15 | 34% | +554 / -510 | -4,07,959 | -325 | 36% | -16,711 | -4,10,253 | -1,79,398 / -1,32,866 / -95,695 | 0.075 | 1.00 / 0.77 | n y n y n |
| Fade-C | 2,312 | 1.84 | 32% | +535 / -474 | -3,55,105 | -283 | 32% | -9,497 | -3,55,594 | -1,73,380 / -1,07,897 / -73,829 | 0.015 | 1.00 / 0.21 | n y y y n |

\* Data error (found after the read): the worst days are FINNIFTY prints that cannot be real. Example: 16 Feb 2022, an
ATM option entered at Rs 796 with the index at 17,600 (4.5% of spot).

Sample sizes: 1,254 holdout calendar days, 3,005 index-sessions. Big winners cut short, all per rule: `results/report.md`
T3.

Gates (H1 profit, H2 beats today's arm, H3 twin p < 0.05, H4 BH q < 0.10, H5 at least 2 of 3 indices > 0):
- **No variant passes H1, and none passes H5.**
- Sweep-F / E / S / C and Fade-C beat their random twins (twin p 0.001-0.026). That only means they lose LESS than
  random entries with the same side and day, which lose even more (twin means Rs -3.6 to -6.8 lakh).
- BH over m = 183 (R3's 32 holdout p-values plus 151 earlier ORB-family tests) leaves only Sweep-E's twin p at q 0.04.
  It is still a Rs -5.37 lakh loss.
- The t-test (mean net per session > 0) has p >= 0.89 for every rule.

### Post-hoc check: removing the bad prints (`sens.py`, not a gate)

Rule: drop every trade whose entry premium is above 3% of its strike.
- Holdout: 4-32 trades per rule are dropped, almost all FINNIFTY 2021-22.
- Design: 1-14 per rule.

After dropping them, **every rule is still negative on design and on the holdout**:
- ORB holdout: Rs -72,589 (FINNIFTY +3,604, MIDCPNIFTY -44,487, SENSEX -31,706);
- ORB-S: Rs -61,141 (FINNIFTY +51,468);
- Sweep-C: Rs -2,91,510;
- Fade-C: Rs -3,64,997.

Only FINNIFTY turns slightly positive for plain / filtered / ITM ORB. With two of three indices still losing, it fails
H5 either way. The verdict does not change. (`results/sens.json`)

### A note on the random twins (post hoc, not tested)

A twin keeps the real trade's side and day but enters at a random time in the window. On design NIFTY, one ORB twin made
Rs +58,307 against the real ORB's Rs -99,106 (1,282 trades). The ORB's direction is partly right; its timing (after the
break) is what loses.

This is NOT a rule anyone can trade: the twin can enter BEFORE the break in the direction the break later took, which
is hindsight. It is written down only as an idea for a future pre-registered study, with a fresh holdout. It is not
something to switch on.

## Multiple testing

BH at q 0.10 over m = 183:
- R3's 32 holdout p-values (16 variants x the t-test and the twin);
- the earlier ORB-family tests at their reported p-values: R20 14, R20b 14, R20c 12, R22 18, R23 22, R18 20, R19c 24;
- R17's 27 at p = 1.

Sources are in `research/hunt/r3orb/results/prior_p.json`. It does not matter here: no variant made money, so no q value
could pass it.

## Limits (stated, not fixed)

- Out-of-instrument holdout (see above). No fresh time block exists for this family.
- Stop / target points on the holdout indices are scaled by price level, not the app's (the app trades BANKNIFTY only).
- Each arm is simulated alone. Not modelled: Liquidity's priority on BANKNIFTY, one position per index and side across
  arms, the R12 manager's paper ACT on ORB Fresh, FlowGate.
- Lake data quality: FINNIFTY 2021-22 options are sparse (21 holdout ORB trades in 2021) and contain mislabelled prints.
  The sensitivity check shows the answer does not depend on them.
- Fills are modelled (no bid / ask in the lake). The target sells at the next minute's open; a stop fills at
  min(level, open).

## Tests and checks

- `python -m pytest research/hunt/r3orb/test_r3orb.py -q`: 51 tests. They cover:
  - the range, every arm's signal on hand-made bars, windows and ATM rounding;
  - the stop, target, ladder, Boss's lock and trail, the time stop, the 15:05 expiry exit, the premium refusal, the
    side cap and the cooldown;
  - charges, the Part B rule, BH, and the holdout refusals;
  - **the look-ahead guard**: 25 random days where every minute after each decision is replaced by noise. Range,
    signals, features and entry prices must not change, and exits must not peek. The numba and pure-Python exit walks
    must agree.
- `python -m pytest research/brain/tests -q` (94 passed) and `python -m research.brain.features catalog --check` (up to
  date), run with the pinned `research/brain/requirements.txt`.
- Experiment log: 21 entries `research/experiments/entries/r3-*.yaml`: 16 variants (rejected), 4 rebuilds, 1 anatomy.
- Reproduce:

  ```
  rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m <lake> --include "opt_*_WEEK_*"
  rclone copy gdrive:IraAlgo-data-lake/index_1m <lake>
  python3 research/hunt/r3orb/r3data.py build
  python3 research/hunt/r3orb/study.py {rebuild,anatomy,design}
  python3 research/hunt/r3orb/report.py
  ```

  The holdout read is spent: `study.py holdout` refuses to run again.
