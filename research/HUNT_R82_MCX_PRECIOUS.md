# R82: enhancing the MCX precious-metal strategies (GOLDM / SILVERM)

*18 Oct 2026. Research only; nothing in the app changes.*
- *Pre-registration: `research/hunt/r82/PREREG.md` (commit d37b9b5, pushed before any result).*
- *Code: `research/hunt/r82/`, with `NOTES.md` for deviations.*
- *Results: `results/design.json`, `final.json`, `report.md`.*
- *The final was read once (`results/FINAL_READ`).*

## The answer for Boss

**None of the 16 ideas passes. The app's MCX metal rules stay as they are, and nothing new goes on paper.**

**1. The 12-month trend arm with a stop-loss** (T1-T4).
- All four versions made money in design (2005-2022), walk-forward and the final (2023 - Sep 2026): **+Rs 10.1 to
  11.4 lakh** a lot-pair in the final, on 34-45 trades.
- But the app's own rule, with no stop, made **+Rs 11.0 lakh on 6 trades**. Two longs held from Apr 2023 to Sep 2026
  carried it.
- So none beat it clearly, and none was significant after 16 tries (design BH q 0.33).
- **The one useful lesson is risk, not profit.**
  - The app's rule sat through an open drawdown of **-Rs 13.4 lakh** (worst point 16 Jul 2026, silver's fall).
  - T4 (a 3-ATR trailing stop, and no new entry when 20-day volatility is above its 1-year 80th percentile) kept
    **92% of the final profit (+Rs 10.1 lakh on 34 trades) with a worst drawdown of -Rs 1.9 lakh**.
  - It also stayed profitable without its 5 best trades (+Rs 82k). Final twin p 0.080.
  - It failed only two gates: it did not out-earn T0, and its design q was 0.33.
  - It stays a **note** (CLAUDE.md: failed ideas stay notes). If Boss ever wants a risk-lowering change to the trend arm,
    this is the one to pre-register next, on real MCX data.

**2. Gold+silver agreement ("breadth", after R72)** helped the trend book in walk-forward (T3: +Rs 3.21 lakh vs T0's
+Rs 2.40 lakh) but not significantly.
- In the final, gold and silver agreed all the time, so T3 equalled T1.
- On Donchian (D2) and Liquidity (L2) it changed nothing or made things worse.

**3. Supertrend agreement on Donchian (R73's lesson)** did not carry over to daily MCX metals.
- D0, D1 and D2 lost in design (-Rs 1.4 to -1.9 lakh on 134-139 trades).
- On daily bars the Supertrend almost always agrees with a 55-day breakout, so D1 = D0.

**4. The US-session ideas looked best in design and failed worst in the final.**
- **U2** (both metals moving the same way in the first US-cash hour; trade both futures that way, stop at that hour's
  extreme):
  - design +Rs 1.35 lakh on 564 trades, twin p 0.002, **BH q 0.032** (the only design pass);
  - final **-Rs 5.84 lakh on 234 trades** (twin p 0.98);
  - its design profit was almost all silver in Jan-Mar 2026 (+Rs 2.87 lakh in that quarter), and it already lost money
    at twice the slippage (design -Rs 2.86 lakh).
- **O1** (the overnight COMEX move into the MCX morning, both metals agreeing): design +Rs 85k on 398 trades, final
  -Rs 1.29 lakh on 180.
- **U3** (the US hour on CPI / NFP / FOMC days only) lost in both.

**5. Liquidity 1h on MCX hours** (the gold arm's rule on GOLDM / SILVERM futures, with a 1-ATR stop) lost in design and
final.
- L1: -Rs 60k / -Rs 34k on 99 / 36 trades.
- Long and short (L3) was worse.
- Skipping US data days (E1) lost less, but still lost.
- This repeats what Liquidity taught us on the indices: the rule needs a deep, trending book; MCX metals in Indian hours
  are not that.

**6. The gold/silver ratio regime (G1) and the DXY filter (U1) both failed.**
- G1: final +Rs 2.8 lakh, far below holding the trend.
- U1: final -Rs 2.6 lakh. Skipping longs while the dollar rose kept it out of gold's best months.

**7. Not tested:**
- MCX options versus futures (no option history reachable);
- roll timing;
- the US real yield;
- USDINR as its own context;
- silver 15-minute Liquidity.

The reasons are in the table at the end.

## Results (Rs per 1 lot of GOLDM + 1 lot of SILVERM, rupee proxies, after costs)

Design = 2005-2022 (daily) or 21 Oct 2024 - Mar 2026 (hourly). WF = walk-forward out-of-sample blocks. Final = 2023 -
30 Sep 2026 (daily) or 1 Apr - 16 Oct 2026 (hourly), read once.
- "Twin p" = the share of 500 random-entry books that did as well.
- "BH q" = Benjamini-Hochberg across all 16 tests.
- Max DD is on closed trades.

| test | signal source | design trades | design win | design net | design max DD | WF net (blocks +) | twin p | BH q | final trades | final win | final net | final max DD | final twin p | final minus top 5 | baseline final | pass |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T0 | the metal's own rupee proxy price (MCX future proxy), daily | 60 | 43% | +333,266 | -117,359 | +239,965 (5/7) | nan | nan | 6 | 33% | +1,104,268 | -135,256 | nan | -39,375 | - | - |
| T1 | own proxy price, daily | 251 | 39% | +122,725 | -300,017 | +203,099 (4/7) | 0.263 | 0.422 | 45 | 47% | +1,120,729 | -879,363 | 0.098 | -464,156 | +1,104,268 | no |
| T2 | own proxy price, daily | 162 | 39% | +218,292 | -219,351 | +221,237 (3/7) | 0.162 | 0.334 | 43 | 49% | +1,140,391 | -879,363 | 0.086 | -444,493 | +1,104,268 | no |
| T3 | own and the other metal's proxy price, daily | 197 | 42% | +231,037 | -205,057 | +320,793 (5/7) | 0.100 | 0.334 | 45 | 47% | +1,120,729 | -879,363 | 0.098 | -464,156 | +1,104,268 | no |
| T4 | own proxy price, daily | 207 | 41% | +228,524 | -193,429 | +210,738 (4/7) | 0.096 | 0.334 | 34 | 47% | +1,010,840 | -189,803 | 0.080 | +82,484 | +1,104,268 | no |
| D0 | own proxy price, daily | 139 | 30% | -138,174 | -271,975 | -51,848 (3/7) | 0.699 | 0.819 | 28 | 25% | +533,959 | -288,486 | 0.228 | -583,267 | +1,104,268 | no |
| D1 | own proxy price, daily | 139 | 30% | -140,505 | -271,975 | -54,179 (3/7) | 0.705 | 0.819 | 28 | 25% | +533,959 | -288,486 | 0.228 | -583,267 | +1,104,268 | no |
| D2 | own and the other metal's proxy price, daily | 134 | 28% | -190,168 | -295,012 | -114,268 (3/7) | 0.804 | 0.858 | 27 | 22% | +499,283 | -288,486 | 0.246 | -564,136 | +1,104,268 | no |
| G1 | gold/silver price ratio + own proxy price, daily | 104 | 42% | +152,044 | -167,701 | +144,149 (3/7) | 0.186 | 0.334 | 30 | 40% | +277,105 | -865,887 | 0.467 | -923,857 | +1,104,268 | no |
| U1 | own proxy price + DXY (Yahoo DX-Y.NYB), daily | 109 | 34% | -28,721 | -296,971 | +23,543 (2/7) | 0.661 | 0.819 | 23 | 35% | -258,370 | -678,651 | 0.926 | -898,598 | +1,104,268 | no |
| L1 | COMEX GC=F / SI=F 1h (MCX future proxy) | 99 | 41% | -59,990 | -115,208 | -12,926 (2/4) | 0.188 | 0.334 | 36 | 36% | -34,031 | -65,823 | 0.363 | -98,251 | 0 | no |
| L2 | COMEX 1h, own and the other metal | 98 | 42% | -58,986 | -114,205 | -11,923 (2/4) | 0.186 | 0.334 | 36 | 36% | -34,031 | -65,823 | 0.363 | -98,251 | -34,031 | no |
| L3 | COMEX 1h | 221 | 42% | -269,204 | -329,863 | -229,678 (0/4) | 0.717 | 0.819 | 86 | 41% | -109,263 | -145,995 | 0.493 | -197,302 | -34,031 | no |
| E1 | COMEX 1h + US data calendar | 87 | 41% | -35,508 | -99,020 | +5,577 (2/4) | 0.112 | 0.334 | 28 | 39% | -7,954 | -39,745 | 0.228 | -71,246 | -34,031 | no |
| O1 | COMEX gold and silver overnight move (MCX future proxy) | 398 | 33% | +84,685 | -313,580 | +98,747 (2/4) | 0.028 | 0.224 | 180 | 32% | -128,693 | -316,662 | 0.349 | -420,715 | 0 | no |
| U2 | COMEX gold and silver first US hour | 564 | 43% | +134,590 | -264,506 | +231,191 (3/4) | 0.002 | 0.032 | 234 | 35% | -584,415 | -692,151 | 0.980 | -723,908 | 0 | no |
| U3 | COMEX gold and silver first US hour on CPI/NFP/FOMC days | 68 | 43% | -146,685 | -152,547 | -117,181 (1/4) | 0.884 | 0.884 | 32 | 50% | -59,083 | -126,130 | 0.659 | -137,370 | -584,415 | no |

T0 = the app's 12-month rule as it is (the baseline, not a test).
- T0's open-position drawdown in the final was -Rs 13,40,459, against -Rs 1,35,256 on closed trades.
- T1-T4's drawdowns are closer to their open drawdowns, because they close within weeks.

### Gates (all must be "yes")

| test | positive D/WF/F | beats baseline WF+F | design twin (q, p) | final twin | not top-5 | >= 30 trades |
|---|---|---|---|---|---|---|
| T1 | yes | no | no | yes | no | yes |
| T2 | yes | no | no | yes | no | yes |
| T3 | yes | yes | no | yes | no | yes |
| T4 | yes | no | no | yes | yes | yes |
| D0 | no | no | no | no | no | yes |
| D1 | no | no | no | no | no | yes |
| D2 | no | no | no | no | no | yes |
| G1 | yes | no | no | no | no | yes |
| U1 | no | no | no | no | no | yes |
| L1 | no | no | no | no | no | yes |
| L2 | no | no | no | no | no | yes |
| L3 | no | no | no | no | no | yes |
| E1 | no | yes | no | no | no | yes |
| O1 | no | no | no | no | no | yes |
| U2 | no | no | yes | no | no | yes |
| U3 | no | no | no | no | no | yes |

### Per metal, longs and shorts, exits (net, trades)

| test | design gold | design silver | design longs | design shorts | final gold | final silver | final longs | final shorts | final exits |
|---|---|---|---|---|---|---|---|---|---|
| T0 | +139,880 (24) | +193,386 (36) | +341,827 (30) | -8,562 (30) | +613,368 (3) | +490,900 (3) | +1,175,883 (4) | -71,615 (2) | flip 4, window_end 2 |
| T1 | -97,208 (139) | +219,932 (112) | +218,292 (162) | -95,567 (89) | +773,388 (21) | +347,340 (24) | +1,140,391 (43) | -19,662 (2) | stop 45 |
| T2 | +6,987 (100) | +211,304 (62) | +218,292 (162) | +0 (0) | +784,267 (20) | +356,124 (23) | +1,140,391 (43) | +0 (0) | stop 43 |
| T3 | -34,892 (111) | +265,930 (86) | +242,923 (136) | -11,886 (61) | +773,388 (21) | +347,340 (24) | +1,140,391 (43) | -19,662 (2) | stop 45 |
| T4 | -69,085 (116) | +297,610 (91) | +244,979 (129) | -16,455 (78) | +711,723 (17) | +299,117 (17) | +1,030,503 (32) | -19,662 (2) | stop 34 |
| D0 | +26,381 (67) | -164,555 (72) | +2,204 (82) | -140,379 (57) | +336,471 (14) | +197,488 (14) | +924,308 (16) | -390,349 (12) | stop 16, signal_exit 12 |
| D1 | +26,381 (67) | -166,886 (72) | -126 (82) | -140,379 (57) | +336,471 (14) | +197,488 (14) | +924,308 (16) | -390,349 (12) | stop 16, signal_exit 12 |
| D2 | -7,783 (64) | -182,385 (70) | -22,851 (79) | -167,317 (55) | +351,590 (13) | +147,694 (14) | +861,521 (16) | -362,238 (11) | stop 16, signal_exit 11 |
| G1 | +108,841 (58) | +43,203 (46) | +152,044 (104) | +0 (0) | +213,175 (11) | +63,930 (19) | +277,105 (30) | +0 (0) | stop 22, signal_exit 8 |
| U1 | +14,173 (59) | -42,893 (50) | -28,721 (109) | +0 (0) | +147,590 (11) | -405,961 (12) | -258,370 (23) | +0 (0) | stop 23 |
| L1 | -64,213 (45) | +4,224 (54) | -59,990 (99) | +0 (0) | -37,943 (16) | +3,912 (20) | -34,031 (36) | +0 (0) | target 12, failed_break 10, stop 9, flat 5 |
| L2 | -64,213 (45) | +5,227 (53) | -58,986 (98) | +0 (0) | -37,943 (16) | +3,912 (20) | -34,031 (36) | +0 (0) | target 12, failed_break 10, stop 9, flat 5 |
| L3 | -142,717 (100) | -126,487 (121) | -59,990 (99) | -209,214 (122) | -32,731 (39) | -76,532 (47) | -34,031 (36) | -75,233 (50) | target 23, stop 23, failed_break 21, flat 19 |
| E1 | -41,940 (38) | +6,433 (49) | -35,508 (87) | +0 (0) | -28,657 (12) | +20,704 (16) | -7,954 (28) | +0 (0) | target 10, failed_break 9, stop 5, flat 4 |
| O1 | -82,041 (199) | +166,726 (199) | +16,037 (244) | +68,648 (154) | -13,159 (90) | -115,534 (90) | -54,811 (80) | -73,882 (100) | stop 118, flat 62 |
| U2 | -113,146 (282) | +247,736 (282) | +13,077 (282) | +121,512 (282) | -314,757 (117) | -269,658 (117) | -329,915 (112) | -254,501 (122) | flat 149, stop 85 |
| U3 | -58,941 (34) | -87,744 (34) | -108,904 (34) | -37,781 (34) | -38,303 (16) | -20,779 (16) | -99,061 (14) | +39,978 (18) | flat 17, stop 15 |

### Walk-forward blocks (net, trades)

Daily: 2009-10, 2011-12, ..., 2021-22. Hourly: Apr-Jun 2025, Jul-Sep, Oct-Dec, Jan-Mar 2026.

- T0: +127,291 (5), +55,011 (6), +71,727 (8), -92,166 (10), +1,289 (8), +222,100 (4), -145,287 (12)
- T1: +91,804 (20), +65,850 (27), +112,059 (25), -100,649 (29), -14,221 (27), +261,680 (29), -213,424 (32)
- T2: +110,642 (17), +80,822 (24), -46,956 (5), -64,334 (15), -35,412 (12), +262,218 (27), -85,743 (16)
- T3: +120,018 (14), +90,161 (21), +73,180 (24), -106,526 (28), +3,725 (13), +258,699 (24), -118,464 (18)
- T4: +94,606 (19), +118,522 (23), +131,192 (21), -71,677 (25), -18,233 (25), +149,757 (21), -193,429 (25)
- D0: +56,662 (12), -34,631 (13), +52,116 (14), -78,601 (22), -65,583 (17), +130,702 (15), -112,513 (19)
- D1: +54,331 (12), -34,631 (13), +52,116 (14), -78,601 (22), -65,583 (17), +130,702 (15), -112,513 (19)
- D2: +54,811 (12), -73,263 (13), +44,724 (14), -81,943 (22), -82,884 (16), +117,378 (15), -93,091 (17)
- G1: +70,683 (10), +51,474 (14), -28,244 (3), -25,530 (9), -14,829 (10), +135,272 (19), -44,677 (11)
- U1: +128,482 (9), -25,732 (18), -43,883 (4), -40,229 (10), -25,177 (5), +126,206 (22), -96,124 (8)
- L1: +729 (10), -1,626 (25), -49,781 (17), +37,752 (9)
- L2: +1,732 (9), -1,626 (25), -49,781 (17), +37,752 (9)
- L3: -44,650 (38), -4,956 (38), -81,647 (36), -98,425 (27)
- E1: +729 (10), -4,203 (23), -44,052 (16), +53,103 (7)
- O1: +21,158 (72), -49,149 (50), -145,451 (72), +272,189 (96)
- U2: -117,375 (78), +32,413 (96), +15,703 (106), +300,450 (100)
- U3: +1,828 (10), -1,222 (12), -11,887 (10), -105,900 (12)

## Verdict per idea, with its signal source

| idea | test(s) | signal source | verdict |
|---|---|---|---|
| 12-month trend: trailing stop | T1 | the metal's own MCX future proxy (daily) | rejected (did not beat T0; q 0.42; top-5 carried) |
| 12-month trend: long only | T2 | own proxy | rejected (did not beat T0 in WF; q 0.33; top-5 carried) |
| 12-month trend: gold+silver agreement | T3 | own + other metal's proxy | rejected (q 0.33; top-5 carried); beat T0 in WF and final |
| 12-month trend: volatility skip ("sizing" at 1 lot) | T4 | own proxy | rejected (did not out-earn T0; q 0.33); **lowest-risk version**, kept as a note |
| Donchian 55/20 | D0 | own proxy | rejected (lost in design) |
| Donchian + Supertrend agreement (R73) | D1 | own proxy | rejected (= D0) |
| Donchian + Supertrend on both metals (R72 breadth) | D2 | own + other metal | rejected |
| Gold/silver ratio regime | G1 | gold/silver ratio + own proxy | rejected |
| DXY context | U1 | own proxy + DXY | rejected |
| Liquidity 1h on MCX hours, buys | L1 | COMEX 1h (MCX future proxy) | rejected |
| Liquidity 1h + gold+silver agreement | L2 | COMEX 1h, both metals | rejected |
| Liquidity 1h long and short | L3 | COMEX 1h | rejected |
| Liquidity 1h skipping US data days | E1 | COMEX 1h + US calendar | rejected |
| COMEX overnight move into the MCX open | O1 | COMEX overnight move, both metals | rejected |
| First US-cash hour, gold+silver agreement | U2 | COMEX first US hour, both metals | rejected (design pass, final -Rs 5.84 lakh) |
| US hour on CPI / NFP / FOMC days | U3 | COMEX US hour + US calendar | rejected |

No rule reads an option's own chart: every signal is on the underlying (the future's proxy, COMEX or spot), and every
trade is the future.

## What the app runs today (read first)

- **MCX arms** (`android/engine/.../mcx/`; all "Not proven — paper only", 1 lot, never cleared for Zerodha):
  - **"MCX 12-month trend"** (`McxTrendRules`):
    - the sign of the 252-day change, decided at each month end, long and short;
    - legs: CRUDEOILM, NATGASMINI, SILVERMIC, ZINCMINI, LEADMINI, ALUMINI, GOLDPETAL;
    - rolled before expiry; **no stop-loss**;
    - R53 (MA10): final -Rs 66.1k on 28 leg-months.
  - **"US-night silver"** (`McxUsSilverRules`):
    - the 09:05 sign of COMEX silver + USDINR since the last MCX close, on SILVERMIC;
    - R53 (MA2): final +Rs 62,850 on 109 trades, BH q 0.198, not proven.
  - The NG evening breakout (not a metal).
  - The SILVERM morning call, switched off on 14 Oct (R42).
- **Gold arms** (XAUUSD, not MCX): Liquidity 1h, Trend 4h Supertrend, Dip, TAS.
- **Logged failures not re-tested unchanged:**
  - every GOLDM / SILVERM intraday option rule (M3, M4, R3, R6, R53 MA1b / MA5-MA8);
  - R2's CPI/NFP first hour, the DXY / real-yield 20-day predictor, and the FOMC straddle;
  - the gold 15-minute Liquidity.

## Data: no real MCX price was reachable

The Drive data lake holds Dhan's MCX option minutes (Aug 2025 - Oct 2026), the live MCX future minutes and the
continuous MCX daily series. It needs rclone, which this sandbox does not have. The dated GitHub release of the lake was
never published. So every MCX price here is a **proxy**:

- **Daily:**
  - Dukascopy spot gold / silver (2003-2026) × USDINR (Yahoo, the previous day's close) × an import-duty factor
    (1.10 before 23 Jul 2024, 1.06 after);
  - in rupees per GOLDM quote (10 g) and per SILVERM quote (1 kg);
  - a 6% a year carry charged to held longs (paid to shorts).
- **Intraday:**
  - COMEX gold and silver front-month 1-hour bars (Yahoo, 20 Oct 2024 - 16 Oct 2026), converted the same way;
  - only MCX hours (09:30 IST to 22:30 / 23:30 IST), flat every day.
- **Costs:** the app's `McxCosts` futures charges, 0.03% slippage on every fill, 0.10% on stop fills.
- **Money:** Rs per 1 lot of GOLDM (100 g) + 1 lot of SILVERM (5 kg). The app's minis scale linearly: GOLDPETAL = GOLDM
  / 100, SILVERMIC = SILVERM / 5.

MCX's own gaps, its premium to the world price, its holidays and real MCX spreads are not in a proxy. A rule that
passes here would still need a real-MCX check (the lake) before paper.

## How it was tested (pre-registered, `research/hunt/r82/PREREG.md`, commit d37b9b5, before any result)

- **Split:**
  - Daily: design 2005-2022, walk-forward = seven out-of-sample 2-year blocks 2009-2022, final 2023 - Oct 2026.
  - Intraday: design 21 Oct 2024 - Mar 2026, walk-forward = four out-of-sample quarters Apr 2025 - Mar 2026, final
    1 Apr - 16 Oct 2026.
  - The final was read **once**, all 16 tests together (`results/FINAL_READ`).
- **Twins:** 500 random-entry books per test:
  - same metal, same side, same holding length (daily) or entry hour (intraday), and the same stop;
  - on random days of the same period.
- **Multiple testing:** Benjamini-Hochberg across all 16 tests.
- **Pass (all needed):**
  - net > 0 in design, walk-forward and final;
  - beats its baseline in walk-forward and final (the app's T0 for the daily ideas; the parent rule for the filters;
    0 for new rules);
  - design BH q < 0.10 and twin p < 0.10;
  - final twin p < 0.10;
  - final net still > 0 without its 5 best trades;
  - at least 30 design trades.
- **Every rule has a stop-loss** (T0, the app's rule as it is, is the only exception, as the baseline).


## Not tested

| idea | why |
|---|---|
| MCX options versus futures for the same signal | no MCX option history reachable (the Drive lake holds Aug 2025 - Oct 2026; no rclone here) |
| roll timing of the trend arm | needs contract-by-contract MCX futures history |
| US real-yield direction | FRED (DFII10) unreachable; Yahoo has no real-yield series |
| USDINR as its own context | dropped to keep the test count down; USDINR is inside every rupee proxy |
| silver 15-minute Liquidity | no XAGUSD minutes (Dukascopy rate-limited to about 1 day file a minute) |
| gold 15-minute Liquidity | a logged failure (GOLD_TIMEFRAMES, M3 LIQ15) |

## For Jarvis

- "Can we improve the MCX gold/silver strategies?"
  - R82 (18 Oct) tested 16 ideas on proxies of GOLDM and SILVERM: trend exits and sizing, gold+silver agreement,
    Donchian + Supertrend, the gold/silver ratio, DXY, Liquidity 1h on MCX hours, the overnight COMEX move, the US open
    hour and US data days.
  - **None passed.**
  - The app's 12-month trend rule is unchanged.
- If Boss asks about adding a stop to the trend arm:
  - the stop versions made about the same money as the app's rule (+Rs 10-11 lakh a GOLDM + SILVERM lot-pair, 2023 -
    Sep 2026);
  - the best one (T4) had a far smaller drawdown (-Rs 1.9 lakh vs -Rs 13.4 lakh open);
  - but it was not proven better, so it is a note only; a real-MCX re-test would come first.
- If Boss asks about the US-hour idea: it looked like the best idea in design and lost Rs 5.84 lakh in the final. Do
  not use it.
- Signal source of every rule: the underlying (MCX future proxy, COMEX gold/silver, spot), never an option chart.

## Limits

- **Proxies, not MCX prints** (see Data). Real MCX spreads, its premium to the world price, MCX holidays and the
  rupee's intraday moves are missing.
- The pre-July-2024 duty factor (1.10) understates the real 15% landed duty. That only scales rupee sizes, by about
  4.5%, and does not change any result (`NOTES.md`).
- Daily data end 30 Sep 2026: Dukascopy's October file is not out yet. Gold's 2026 days come from the release's
  1-minute file (180 days); silver's 2026 days come from Dukascopy hour candles (194 days).
- The daily book charges no separate monthly roll cost; the 6% carry stands in for it.
- Hourly COMEX front-month rolls put a jump bar into the series; no intraday trade is held overnight, so the effect is
  small.
- Windows overlap earlier studies (M2's holdout, R53's and R78's finals); the R82 rules had never been run on them.
- The trend books' finals are dominated by one long gold-and-silver boom (2023-2026). That favours holding (T0)
  and says little about a sideways market.
- Sample sizes: daily final 6-45 trades a test, intraday final 28-234.
- Data plumbing: five `research/brain` tests fail in this sandbox on a clean branch too. The cause is library versions
  (pandas 3's string dtype; R78 saw the same type). R82 does not touch them. `catalog --check` and
  `experiments.validate` pass.

Sources (market facts only; none is evidence of an edge):
- [Business Today, Budget 2024 cuts customs duty on gold and silver to 6%](https://www.businesstoday.in/amp/union-budget/story/union-budget-gold-and-silver-prices-fall-by-up-to-rs-4000-after-budget-cuts-customs-duty-to-6-438383-2024-07-23)
- [MCX contract specs and hours](https://onetradejournal.com/learn/mcx-trading-basics)
- [Upstox, silver on MCX](https://upstox.com/learning-center/commodity/what-is-silver-trading-on-mcx/article-1869/)
- federalreserve.gov FOMC calendars and bls.gov release schedules (event dates)
- MCX hours: `McxSession.kt`
