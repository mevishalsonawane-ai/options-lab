## Jarvis's own trades: which exit rule? 30/60 against 11 fixed alternatives (research/jarvis_exits.py)

### Verdict

**Nothing beats 30/60 robustly.** No candidate passed every test (better than 30/60 pooled, in at least 2 of the 3 real entry sets and in most years, drawdown no worse, and positive). 30/60: Rs +11 a trade, Rs +3,963 a year, drawdown Rs -63,667, 3 of 7 years positive. Best by net: X1 40-pt stop / +80 / ladder on 80 Rs +4,506 a year (drawdown Rs -77,857).

**In plain words.** Keep the 30-point stop, +60 target and ladder for now: none of the 11 alternatives was better across the entry sets, the years and the walk-forward, and the only one ahead on total (X1, 40/80 + ladder) is ahead by about Rs +1 a trade, risks a third more per lot, has a deeper drawdown, loses on the pattern ideas, and gains MORE on random entries than on real ones - an exit effect, not a better fit to Jarvis's ideas. The percentage stops (-15/-20/-25%) lose more in total and on the pattern and momentum sets (with the ladder a few are slightly ahead on the level breaks only, with drawdowns twice as deep), and on BANKNIFTY they risk about twice 30/60's points (adds risk). The Pine % trail (T1) is behind 30/60 on all three sets (it only cuts the drawdown on the momentum set). The bigger finding is that the exit is not what decides Jarvis's results: with any rule, random entries lose about the charges (30/60: Rs -77 a trade, charges about Rs 70), and the real entry sets with 30/60 make Rs -28 (pattern ideas), Rs -5 (level breaks) and Rs +56 (momentum) a trade. Only the momentum proxy is clearly positive; the pattern ideas lose with every rule tried.

Walk-forward (each year trade the rule best on all earlier years, pooled real sets; 2022-2026): Rs -46,335 (drawdown Rs -108,579) against Rs +15,009 (drawdown Rs -63,667) for 30/60. Picks: 2022 P6, 2023 P5, 2024 X1, 2025 X1, 2026 C0.

Tried: 12 exit rules (C0, P1, P2, P3, P4, P5, P6, I1, X1, T1, T2, R1), fixed before any result was read (the script's header); no parameter was tuned. With 11 alternatives one of them beating 30/60 by luck in a year or a set is expected; the tests below ask for it across sets, years and walk-forward.

### Set-up

- Option: as Jarvis buys it (IraNewsTrades.contract): ATM on the strike step, the nearest expiry strictly after today, 1 lot of the day's size, MARKET buy at the entry minute's open; not bought at 35 or less (kept for every rule). Out by 15:15.
- Real Dhan option minute bars (expired contracts), Aug 2020 - 5 Oct 2026 (BANKNIFTY / FINNIFTY from Aug 2021). App fills (MARKET +-5 bps, resting stop -10 bps) and the app's F&O charges per leg. Stop before target inside a minute; the ladder / trail rungs count from the minute after the best price; their breakeven is after charges (Boss's 06 Oct fix). A strike that traded under 50 lots that day before the entry is not bought (the app's StrikeLiquidity check; here it also drops stale, carried-forward prints, common in FINNIFTY's first months).
- Expiry days skipped in every set: the data holds only the nearest contract (today's on an expiry day), not the next one Jarvis would buy.
- Entry sets (trades priced with 30/60; others within a few): PAT 100; LIQ 1573; SOLO 623; RND 3106. Set definitions in the script's header: PAT = PatternExpert.judge's rules on 5/15-min NIFTY, BANKNIFTY, FINNIFTY (index record held in the trailing two years, trend, room, 09:20-14:30, 2 a day; NOT the 30/60 option filter, which would select entries for the rule under test); LIQ = Liquidity 15+5's entries (arm replay), bought ATM; SOLO = every index's Solo midday signal at 12:00; RND = one random minute and side a day per index.

### All rules, the three real entry sets pooled (1 lot each trade, after costs)

| rule | trades | win | Rs / trade | Rs / year | net | PF | max DD | worst trade | years + | years better than C0 | sets better than C0 | gain vs C0 a trade: real / random | adds risk? |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C0 current 30-pt stop / +60 / ladder on 60 | 2296 | 35% | +11 | **+3,963** | +24,183 | 1.03 | -63,667 | -3,182 | 3/7 | - | - | - | no |
| P1 -15% / +30% | 2296 | 37% | -61 | **-22,874** | -139,593 | 0.93 | -218,444 | -5,794 | 2/7 | 1 of 7 | 0 of 3 | -71 / -44 | **yes - needs Boss's OK** |
| P2 -15% / +30% + ladder | 2296 | 35% | -7 | **-2,562** | -15,634 | 0.99 | -139,470 | -5,794 | 3/7 | 1 of 7 | 1 of 3 | -17 / -11 | **yes - needs Boss's OK** |
| P3 -20% / +40% | 2296 | 38% | -71 | **-26,785** | -163,462 | 0.93 | -260,346 | -7,440 | 2/7 | 2 of 7 | 0 of 3 | -82 / -39 | **yes - needs Boss's OK** |
| P4 -20% / +40% + ladder | 2296 | 35% | -4 | **-1,409** | -8,600 | 0.99 | -162,876 | -7,440 | 3/7 | 2 of 7 | 1 of 3 | -14 / -13 | **yes - needs Boss's OK** |
| P5 -25% / +50% | 2296 | 39% | -48 | **-17,908** | -109,287 | 0.96 | -278,826 | -9,413 | 3/7 | 3 of 7 | 0 of 3 | -58 / -14 | **yes - needs Boss's OK** |
| P6 -25% / +50% + ladder | 2296 | 35% | -16 | **-5,848** | -35,688 | 0.98 | -216,773 | -9,413 | 4/7 | 3 of 7 | 1 of 3 | -26 / -29 | **yes - needs Boss's OK** |
| I1 -15% + index stop + 20-min time stop + 2R (+30%) | 2296 | 25% | -35 | **-13,225** | -80,709 | 0.90 | -102,228 | -3,909 | 2/7 | 2 of 7 | 0 of 3 | -46 / -12 | **yes - needs Boss's OK** |
| X1 40-pt stop / +80 / ladder on 80 | 2296 | 36% | +12 | **+4,506** | +27,500 | 1.02 | -77,857 | -3,286 | 4/7 | 4 of 7 | 1 of 3 | +1 / +13 | **yes - needs Boss's OK** |
| T1 Pine: 30/60 + ladder 60 + % trail | 2296 | 41% | -20 | **-7,643** | -46,641 | 0.93 | -64,814 | -3,182 | 2/7 | 2 of 7 | 0 of 3 | -31 / +7 | no |
| T2 -20% stop + % trail, no target | 2296 | 47% | -23 | **-8,642** | -52,737 | 0.95 | -117,401 | -7,440 | 1/7 | 1 of 7 | 0 of 3 | -34 / +20 | **yes - needs Boss's OK** |
| R1 tighter of 30 pts / 15%, 2x target, ladder | 2296 | 35% | -11 | **-4,126** | -25,177 | 0.97 | -59,720 | -3,182 | 2/7 | 2 of 7 | 0 of 3 | -21 / -15 | no |

### PAT: Jarvis's pattern ideas

| rule | trades | win | Rs / trade | Rs / year | net | PF | max DD | worst trade | years + |
|---|---|---|---|---|---|---|---|---|---|
| C0 | 100 | 34% | -28 | -609 | -2,841 | 0.95 | -22,712 | -2,341 | 3/5 |
| P1 | 100 | 37% | -175 | -3,755 | -17,509 | 0.77 | -27,676 | -4,694 | 2/5 |
| P2 | 100 | 30% | -147 | -3,163 | -14,746 | 0.67 | -27,258 | -4,694 | 2/5 |
| P3 | 100 | 38% | -324 | -6,944 | -32,378 | 0.66 | -41,542 | -6,207 | 2/5 |
| P4 | 100 | 31% | -258 | -5,533 | -25,796 | 0.59 | -39,593 | -6,207 | 2/5 |
| P5 | 100 | 41% | -445 | -9,541 | -44,483 | 0.61 | -57,904 | -7,275 | 3/5 |
| P6 | 100 | 30% | -293 | -6,282 | -29,288 | 0.62 | -48,750 | -7,275 | 1/5 |
| I1 | 100 | 20% | -136 | -2,917 | -13,599 | 0.69 | -15,632 | -2,051 | 1/5 |
| X1 | 100 | 35% | -114 | -2,443 | -11,390 | 0.85 | -26,538 | -3,099 | 2/5 |
| T1 | 100 | 46% | -32 | -681 | -3,177 | 0.90 | -10,918 | -2,341 | 3/5 |
| T2 | 100 | 46% | -47 | -1,011 | -4,715 | 0.86 | -15,863 | -6,207 | 4/5 |
| R1 | 100 | 31% | -79 | -1,704 | -7,946 | 0.79 | -19,996 | -2,199 | 2/5 |

### LIQ: Liquidity 15+5 entries, bought ATM

| rule | trades | win | Rs / trade | Rs / year | net | PF | max DD | worst trade | years + |
|---|---|---|---|---|---|---|---|---|---|
| C0 | 1573 | 34% | -5 | -1,565 | -7,823 | 0.99 | -47,796 | -3,182 | 2/6 |
| P1 | 1573 | 38% | -55 | -17,299 | -86,483 | 0.94 | -126,719 | -5,794 | 0/6 |
| P2 | 1573 | 35% | -4 | -1,399 | -6,995 | 0.99 | -101,420 | -5,794 | 1/6 |
| P3 | 1573 | 38% | -47 | -14,744 | -73,709 | 0.96 | -156,413 | -7,440 | 1/6 |
| P4 | 1573 | 35% | +15 | +4,680 | +23,399 | 1.02 | -95,361 | -7,440 | 1/6 |
| P5 | 1573 | 39% | -11 | -3,529 | -17,643 | 0.99 | -208,747 | -9,258 | 2/6 |
| P6 | 1573 | 35% | -0 | -96 | -481 | 1.00 | -151,657 | -9,258 | 3/6 |
| I1 | 1573 | 25% | -20 | -6,397 | -31,979 | 0.95 | -63,220 | -2,684 | 1/6 |
| X1 | 1573 | 36% | +18 | +5,521 | +27,602 | 1.04 | -48,111 | -3,182 | 4/6 |
| T1 | 1573 | 40% | -36 | -11,403 | -57,010 | 0.88 | -66,475 | -3,182 | 1/6 |
| T2 | 1573 | 47% | -32 | -10,092 | -50,453 | 0.93 | -111,384 | -7,440 | 2/6 |
| R1 | 1573 | 35% | -14 | -4,294 | -21,465 | 0.96 | -45,143 | -3,182 | 1/6 |

### SOLO: Solo midday signals

| rule | trades | win | Rs / trade | Rs / year | net | PF | max DD | worst trade | years + |
|---|---|---|---|---|---|---|---|---|---|
| C0 | 623 | 36% | +56 | +5,720 | +34,846 | 1.12 | -43,618 | -2,529 | 4/7 |
| P1 | 623 | 37% | -57 | -5,844 | -35,601 | 0.93 | -90,953 | -5,374 | 3/7 |
| P2 | 623 | 34% | +10 | +1,002 | +6,107 | 1.02 | -51,480 | -5,374 | 2/7 |
| P3 | 623 | 38% | -92 | -9,418 | -57,375 | 0.90 | -145,644 | -7,298 | 3/7 |
| P4 | 623 | 35% | -10 | -1,018 | -6,203 | 0.98 | -84,449 | -7,298 | 2/7 |
| P5 | 623 | 39% | -76 | -7,742 | -47,161 | 0.92 | -147,173 | -9,413 | 3/7 |
| P6 | 623 | 36% | -10 | -972 | -5,920 | 0.99 | -97,157 | -9,413 | 2/7 |
| I1 | 623 | 27% | -56 | -5,767 | -35,131 | 0.83 | -66,348 | -3,909 | 2/7 |
| X1 | 623 | 36% | +18 | +1,853 | +11,288 | 1.03 | -63,099 | -3,286 | 4/7 |
| T1 | 623 | 44% | +22 | +2,224 | +13,546 | 1.08 | -25,446 | -2,529 | 3/7 |
| T2 | 623 | 47% | +4 | +399 | +2,431 | 1.01 | -45,407 | -7,298 | 3/7 |
| R1 | 623 | 35% | +7 | +695 | +4,234 | 1.02 | -34,088 | -2,529 | 3/7 |

### RND: random entries (the control)

| rule | trades | win | Rs / trade | Rs / year | net | PF | max DD | worst trade | years + |
|---|---|---|---|---|---|---|---|---|---|
| C0 | 3106 | 32% | -77 | -38,851 | -239,755 | 0.83 | -302,100 | -4,314 | 2/7 |
| P1 | 3106 | 35% | -122 | -61,244 | -377,944 | 0.84 | -409,506 | -7,471 | 0/7 |
| P2 | 3106 | 31% | -88 | -44,209 | -272,821 | 0.81 | -318,074 | -7,471 | 0/7 |
| P3 | 3106 | 37% | -116 | -58,328 | -359,951 | 0.86 | -458,597 | -9,892 | 1/7 |
| P4 | 3106 | 32% | -91 | -45,587 | -281,325 | 0.84 | -359,798 | -9,892 | 1/7 |
| P5 | 3106 | 38% | -91 | -45,891 | -283,199 | 0.90 | -400,214 | -12,312 | 1/7 |
| P6 | 3106 | 34% | -106 | -53,267 | -328,719 | 0.85 | -413,869 | -12,312 | 1/7 |
| I1 | 3106 | 26% | -89 | -44,808 | -276,513 | 0.73 | -287,654 | -4,674 | 0/7 |
| X1 | 3106 | 33% | -64 | -32,114 | -198,181 | 0.89 | -268,753 | -4,314 | 3/7 |
| T1 | 3106 | 43% | -71 | -35,552 | -219,393 | 0.78 | -236,419 | -4,314 | 0/7 |
| T2 | 3106 | 46% | -57 | -28,831 | -177,921 | 0.85 | -251,537 | -9,892 | 1/7 |
| R1 | 3106 | 30% | -92 | -46,150 | -284,794 | 0.74 | -304,326 | -4,314 | 0/7 |

### Net by year, real sets pooled (Rs)

| rule | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|
| C0 | -1,653 | +10,827 | +37,310 | -981 | -11,720 | +21,376 | -30,977 |
| P1 | -6,110 | +6,191 | **+37,391** | -23,367 | -30,067 | -52,724 | -70,907 |
| P2 | -4,404 | +10,111 | +31,923 | -22,904 | -18,800 | -24,762 | **+13,202** |
| P3 | -7,927 | **+12,193** | **+47,364** | -27,040 | -19,922 | -118,085 | -50,044 |
| P4 | -2,239 | +10,328 | **+37,955** | -20,010 | -32,636 | -71,096 | **+69,097** |
| P5 | -9,754 | **+27,853** | **+45,324** | -25,388 | -42,745 | -144,274 | **+39,697** |
| P6 | -8,067 | **+32,244** | +20,328 | **+12,007** | -29,893 | -133,554 | **+71,247** |
| I1 | **-1,100** | -4,227 | +9,676 | -28,783 | -27,015 | **+32,999** | -62,259 |
| X1 | **+4,003** | +9,312 | +36,606 | **+13,381** | **-6,189** | -4,109 | **-25,504** |
| T1 | -2,889 | -676 | +4,852 | -28,219 | **-399** | **+23,161** | -42,471 |
| T2 | -2,098 | +9,291 | -6,660 | -19,416 | -21,291 | -10,763 | **-1,800** |
| R1 | -4,404 | -2,122 | +18,679 | -22,770 | **-6,117** | **+22,708** | -31,151 |

Bold: better than C0 that year. 2020 is NIFTY only (Aug-Dec); 2026 to 5 Oct.

### Walk-forward

| test year | picked | its net before | C0 before | picked that year | C0 that year |
|---|---|---|---|---|---|
| 2022 | P6 | +24,177 | +9,173 | +20,328 | +37,310 |
| 2023 | P5 | +63,422 | +46,484 | -25,388 | -981 |
| 2024 | X1 | +63,302 | +45,503 | -6,189 | -11,720 |
| 2025 | X1 | +57,112 | +33,783 | -4,109 | +21,376 |
| 2026 | C0 | +55,159 | +55,159 | -30,977 | -30,977 |

Per entry set (picked on that set's own past): PAT: Rs -18,376 vs C0 Rs -14,660 (2024:C0, 2025:C0, 2026:T2); LIQ: Rs +29,595 vs C0 Rs -1,146 (2023:X1, 2024:X1, 2025:X1, 2026:X1); SOLO: Rs -62,460 vs C0 Rs +21,825 (2022:X1, 2023:P5, 2024:P6, 2025:P3, 2026:C0).

### Risk per trade (planned stop, median per index; all sets)

Median price paid (ATM, next expiry, at these entries): NIFTY Rs 104, BANKNIFTY Rs 382, FINNIFTY Rs 154.

| rule | NIFTY pts | NIFTY Rs / lot (p90) | BANKNIFTY pts | BANKNIFTY Rs / lot (p90) | FINNIFTY pts | FINNIFTY Rs / lot (p90) | worst single trade, any set | adds risk vs 30 pts? |
|---|---|---|---|---|---|---|---|---|
| C0 | 30 | 1,502 (2,252) | 30 | 751 (1,050) | 30 | 1,202 (1,951) | -4,314 | no |
| P1 | 16 | 851 (1,644) | 57 | 1,304 (3,845) | 23 | 939 (3,497) | -7,471 | **yes - needs Boss's OK** |
| P2 | 16 | 851 (1,644) | 57 | 1,304 (3,845) | 23 | 939 (3,497) | -7,471 | **yes - needs Boss's OK** |
| P3 | 21 | 1,133 (2,194) | 76 | 1,739 (5,127) | 31 | 1,251 (4,662) | -9,892 | **yes - needs Boss's OK** |
| P4 | 21 | 1,133 (2,194) | 76 | 1,739 (5,127) | 31 | 1,251 (4,662) | -9,892 | **yes - needs Boss's OK** |
| P5 | 26 | 1,417 (2,741) | 96 | 2,174 (6,408) | 38 | 1,563 (5,827) | -12,312 | **yes - needs Boss's OK** |
| P6 | 26 | 1,417 (2,741) | 96 | 2,174 (6,408) | 38 | 1,563 (5,827) | -12,312 | **yes - needs Boss's OK** |
| I1 | 16 | 851 (1,644) | 57 | 1,304 (3,845) | 23 | 939 (3,497) | -4,674 | **yes - needs Boss's OK** |
| X1 | 40 | 2,002 (3,002) | 40 | 1,001 (1,400) | 40 | 1,602 (2,601) | -4,314 | **yes - needs Boss's OK** |
| T1 | 30 | 1,502 (2,252) | 30 | 751 (1,050) | 30 | 1,202 (1,951) | -4,314 | no |
| T2 | 21 | 1,133 (2,194) | 76 | 1,739 (5,127) | 31 | 1,251 (4,662) | -9,892 | **yes - needs Boss's OK** |
| R1 | 16 | 841 (1,606) | 30 | 751 (1,050) | 23 | 849 (1,951) | -4,314 | no |

A rule 'adds risk' when its median planned stop per lot is larger than 30/60's on any index. T2 has no target and the trail; I1's index / time stops can only make a loss smaller than its -15% stop, except for gaps.

### How trades end (share; real sets pooled)

| rule | exits |
|---|---|
| C0 | lock 53%, stop 31%, target 12%, 15:15 4% |
| P1 | stop 54%, target 27%, 15:15 19% |
| P2 | lock 53%, stop 30%, target 10%, 15:15 7% |
| P3 | stop 46%, 15:15 30%, target 23% |
| P4 | lock 49%, stop 28%, 15:15 14%, target 9% |
| P5 | 15:15 40%, stop 40%, target 19% |
| P6 | lock 44%, stop 26%, 15:15 22%, target 9% |
| I1 | index_stop 63%, time_stop 19%, target 13%, stop 4%, 15:15 2% |
| X1 | lock 52%, stop 29%, target 10%, 15:15 9% |
| T1 | lock 66%, stop 25%, target 7%, 15:15 1% |
| T2 | lock 76%, stop 17%, 15:15 6% |
| R1 | lock 55%, stop 32%, target 12%, 15:15 1% |

### Caveats

- Expiry days are not in any set (no next-expiry prices on them). Jarvis may suggest on expiry days until 13:00.
- PAT is small (100 trades, 2022-2026: the held-record filter needs two years of history, and the trend / room / 2-a-day rules thin it); its per-rule differences are within noise. The live app also requires the 30/60 option record to be positive (Edge.optionHeld) - left out on purpose; with it the set would be chosen by the rule under test.
- LIQ and SOLO are proxies (level breaks and momentum ideas), bought the Jarvis way (ATM, next expiry, at the signal minute). SOLO takes every index's signal, not only the strongest. Trades are independent: no one-position limit, no daily loss limit.
- 'Per year' adds the three real sets together (one lot on every idea); each set alone is in its own table. Lot sizes are the day's (NIFTY 75/50/25/75/65, BANKNIFTY 25/15/30/35, FINNIFTY 40/25/65/60 over the years), so rupees weigh years differently; points per trade tell the same story.
- Fills are the paper account's; a real stop in a fast market can slip more, which hurts the tight point stops and the percentage stops on BANKNIFTY alike. Prices are minute bars: inside a minute the stop is assumed hit before the target.
- 12 rules were tried; a rule ahead in one set or a few years is expected by chance. The walk-forward (choose on the past, trade the next year) lost to simply keeping 30/60.

