# R166: six checks on the Donchian arms, one by one - results

## For Boss: short answer (12 Nov 2026, Nov 2024 onward only)

**I checked all six, one by one: none passed every gate, so nothing changes in the app. One is a real lead: skipping
breaks when the option itself is not being traded (V1).** 24 tests (12 checks x R72 and R51, 1 lot, today's exits).

Today, for comparison (1 lot): R72 walk-forward year (Apr 2025 - Mar 2026) 2,238 trades, 49.0% won, Rs 3,35,882; final
(Apr - Oct 2026) 1,202 trades, 45.8%, Rs 1,71,366. R51: 3,044 / 45.9% / Rs 2,13,748 and 1,670 / 44.9% / Rs 1,34,743.

1. **Delta.** R165 (a sibling study) covers delta in depth. I added two: delta 0.60 but at most 2-ITM, and delta 0.60 on
   NIFTY / SENSEX only. Neither beat picking a strike at random (walk-forward p 0.42-0.71). The capped one made more in the
   final (R72 Rs 2,09,565, R51 Rs 2,01,715) but less in the walk-forward year, and that final was already seen when I
   picked 0.60, so it proves nothing.
2. **Support / resistance (previous day's high / low).** "Skip when the level is just ahead" made less everywhere.
   "Only trade beyond yesterday's high / low" looked great in design (R51 Rs 2,26,478 vs Rs 50,425) and then lost in the
   final (R72 Rs 9,247, R51 -Rs 6,280, about 550-680 trades).
3. **Fake breakout (sell if the index has not gone past the signal candle within 3 / 5 minutes).** Clearly worse: the win
   rate fell 5-7 points and most of the profit went. Many good breaks pause before they run.
4. **Fake volume.**
   - **V1: skip when the bought option's volume in the signal candle is below its usual for the day.** It made more on
     both arms in all three periods:
     - R72: design Rs 2,77,103 vs Rs 2,16,929; walk-forward Rs 3,59,030 vs Rs 3,35,882; final Rs 2,03,445 vs
       Rs 1,71,366 (1,115 trades), drawdown no worse;
     - R51: Rs 1,08,408 vs Rs 50,425; Rs 2,37,672 vs Rs 2,13,748; Rs 1,63,243 vs Rs 1,34,743 (1,461 trades);
     - it beat random skips of the same number (R72 p 0.006, R51 p 0.038).
     - **Why it still fails:** after correcting for all 2,290 tests run so far, the evidence is not strong enough
       (q 1.0; within R166 alone 0.07 for R72), and the final months were seen by many studies. The win rate barely
       moves (-0.1 to +0.8 points): it skips poor trades, not losers in particular.
   - V2 (skip a volume climax) made less in design and walk-forward.
5. **Fake buyer / seller.** Skipping when the option's implied vol fell (E1) raised the walk-forward win rate (51.2% vs
   49.0% on R72) but made less money; skipping when the other side's option got dearer (E2) changed nothing.
6. **Traps.** Skipping after a break the other way within 30 minutes (F1) changed nothing. Skipping an outside "sweep"
   candle (F2) did well in the final (R72 Rs 2,04,415 vs Rs 1,71,366, p 0.002) but made less in the walk-forward year,
   so it is not consistent.

**My suggestion:** V1 is the one worth watching. If you want, I can pre-register it as a **shadow label** (the app only
logs "low option volume" on each R72 / R51 break; it changes no trade) and judge it on the new days from today.
Honesty notes: Apr - Oct 2026 has been read by many studies, so it is weak evidence. As you asked, nothing before Nov
2024 was used.

## How it was tested

Pre-registered in `PREREG.md` (plus Amendment 1, a count correction), both pushed before any number. Nov 2024 onward only (Boss). 1 lot, today's exits; entries unchanged (signal on the index). Engine, charges, slippage and twins as R162. A skipped break still holds its side and counts in the 2-a-side cap. Design 20 Nov 2024 - 31 Mar 2026 (walk-forward Apr 2025 - Mar 2026); final 1 Apr - 13 Oct 2026, read once (weak evidence: read by many studies; for the delta rules not a fresh test, so they are judged on the walk-forward year). BH m = 2290. Raw p-values: `results/raw_pvalues.csv`.

**Tests: 24; passed every gate: 0.**

## A. Delta

**R72 Breadth Donchian**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1202 | 45.8% | Rs 1,71,366 | Rs 61,884 | | | |
| B0 today | walk-forward | 2238 | 49.0% | Rs 3,35,882 | Rs 86,529 | | | |
| B0 today | design | 3071 | 46.8% | Rs 2,16,929 | Rs 1,99,077 | | | |
| D60C2: strike nearest delta 0.60, at most 2-ITM | final | 1207 | 47.6% | Rs 2,09,565 | Rs 77,026 | 0.715 / 0.559 | 1.000 | fail: better by neither route (wf); BH q 1 > 0.1 |
| D60C2: strike nearest delta 0.60, at most 2-ITM | walk-forward | 2250 | 48.9% | Rs 3,04,862 | Rs 1,42,691 | | | |
| D60C2: strike nearest delta 0.60, at most 2-ITM | design | 3083 | 46.9% | Rs 1,90,116 | Rs 1,99,263 | | | |
| D60W: delta 0.60 on NIFTY / SENSEX only, 1-ITM elsewhere | final | 1202 | 46.2% | Rs 1,75,848 | Rs 63,483 | 0.415 / 0.303 | 1.000 | fail: BH q 1 > 0.1 |
| D60W: delta 0.60 on NIFTY / SENSEX only, 1-ITM elsewhere | walk-forward | 2237 | 49.0% | Rs 3,47,752 | Rs 88,659 | | | |
| D60W: delta 0.60 on NIFTY / SENSEX only, 1-ITM elsewhere | design | 3070 | 46.9% | Rs 2,35,180 | Rs 1,94,490 | | | |



**R51 Donchian 20 (+ Supertrend 10, 3)**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1670 | 44.9% | Rs 1,34,743 | Rs 81,683 | | | |
| B0 today | walk-forward | 3044 | 45.9% | Rs 2,13,748 | Rs 94,176 | | | |
| B0 today | design | 4208 | 44.5% | Rs 50,425 | Rs 2,14,239 | | | |
| D60C2: strike nearest delta 0.60, at most 2-ITM | final | 1673 | 46.3% | Rs 2,01,715 | Rs 80,329 | 0.703 / 0.812 | 1.000 | fail: better by neither route (wf); design: net not positive; BH q 1 > 0.1 |
| D60C2: strike nearest delta 0.60, at most 2-ITM | walk-forward | 3054 | 45.8% | Rs 1,56,537 | Rs 1,49,807 | | | |
| D60C2: strike nearest delta 0.60, at most 2-ITM | design | 4218 | 44.5% | -Rs 19,735 | Rs 2,33,816 | | | |
| D60W: delta 0.60 on NIFTY / SENSEX only, 1-ITM elsewhere | final | 1670 | 45.4% | Rs 1,40,277 | Rs 82,033 | 0.429 / 0.741 | 1.000 | fail: better by neither route (wf); BH q 1 > 0.1 |
| D60W: delta 0.60 on NIFTY / SENSEX only, 1-ITM elsewhere | walk-forward | 3043 | 45.8% | Rs 2,10,973 | Rs 97,515 | | | |
| D60W: delta 0.60 on NIFTY / SENSEX only, 1-ITM elsewhere | design | 4207 | 44.5% | Rs 33,865 | Rs 2,36,366 | | | |



## B. Support / resistance

**R72 Breadth Donchian**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1202 | 45.8% | Rs 1,71,366 | Rs 61,884 | | | |
| B0 today | walk-forward | 2238 | 49.0% | Rs 3,35,882 | Rs 86,529 | | | |
| B0 today | design | 3071 | 46.8% | Rs 2,16,929 | Rs 1,99,077 | | | |
| SR1: skip when the previous day's high / low is within 0.25% ahead | final | 1054 | 46.2% | Rs 1,48,568 | Rs 60,080 | 0.555 / 0.245 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| SR1: skip when the previous day's high / low is within 0.25% ahead | walk-forward | 1898 | 48.8% | Rs 2,78,017 | Rs 89,009 | | | |
| SR1: skip when the previous day's high / low is within 0.25% ahead | design | 2634 | 46.8% | Rs 1,89,194 | Rs 1,72,789 | | | |
| SR2: calls only above the previous day's high, puts only below its low | final | 547 | 42.2% | Rs 9,247 | Rs 87,656 | 0.992 / 0.998 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| SR2: calls only above the previous day's high, puts only below its low | walk-forward | 977 | 51.0% | Rs 2,33,471 | Rs 61,051 | | | |
| SR2: calls only above the previous day's high, puts only below its low | design | 1389 | 49.2% | Rs 2,43,151 | Rs 61,051 | | | |

Share of breaks each check marks (design): SR1 11.9%, SR2 45.6%

**R51 Donchian 20 (+ Supertrend 10, 3)**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1670 | 44.9% | Rs 1,34,743 | Rs 81,683 | | | |
| B0 today | walk-forward | 3044 | 45.9% | Rs 2,13,748 | Rs 94,176 | | | |
| B0 today | design | 4208 | 44.5% | Rs 50,425 | Rs 2,14,239 | | | |
| SR1: skip when the previous day's high / low is within 0.25% ahead | final | 1437 | 45.0% | Rs 1,14,181 | Rs 56,617 | 0.497 / 0.345 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| SR1: skip when the previous day's high / low is within 0.25% ahead | walk-forward | 2517 | 46.2% | Rs 1,84,021 | Rs 1,04,779 | | | |
| SR1: skip when the previous day's high / low is within 0.25% ahead | design | 3517 | 44.8% | Rs 47,324 | Rs 2,09,134 | | | |
| SR2: calls only above the previous day's high, puts only below its low | final | 678 | 43.4% | -Rs 6,280 | Rs 68,909 | 0.978 / 0.904 | 1.000 | fail: better by neither route (wf and final); final: net not positive; BH q 1 > 0.1 |
| SR2: calls only above the previous day's high, puts only below its low | walk-forward | 1174 | 49.1% | Rs 2,00,064 | Rs 47,149 | | | |
| SR2: calls only above the previous day's high, puts only below its low | design | 1655 | 47.7% | Rs 2,26,478 | Rs 58,877 | | | |

Share of breaks each check marks (design): SR1 12.7%, SR2 44.8%

## C. Fake breakout

**R72 Breadth Donchian**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1202 | 45.8% | Rs 1,71,366 | Rs 61,884 | | | |
| B0 today | walk-forward | 2238 | 49.0% | Rs 3,35,882 | Rs 86,529 | | | |
| B0 today | design | 3071 | 46.8% | Rs 2,16,929 | Rs 1,99,077 | | | |
| FT3: sell at +3 min if the index has not gone past the signal bar | final | 1224 | 38.5% | Rs 52,065 | Rs 89,298 | 1.000 / 1.000 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| FT3: sell at +3 min if the index has not gone past the signal bar | walk-forward | 2272 | 43.9% | Rs 2,01,338 | Rs 83,025 | | | |
| FT3: sell at +3 min if the index has not gone past the signal bar | design | 3122 | 41.6% | Rs 1,02,154 | Rs 1,88,119 | | | |
| FT5: the same at +5 min | final | 1222 | 39.6% | Rs 48,162 | Rs 76,999 | 1.000 / 1.000 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| FT5: the same at +5 min | walk-forward | 2264 | 45.0% | Rs 2,28,192 | Rs 85,266 | | | |
| FT5: the same at +5 min | design | 3112 | 42.5% | Rs 1,14,053 | Rs 2,02,135 | | | |



**R51 Donchian 20 (+ Supertrend 10, 3)**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1670 | 44.9% | Rs 1,34,743 | Rs 81,683 | | | |
| B0 today | walk-forward | 3044 | 45.9% | Rs 2,13,748 | Rs 94,176 | | | |
| B0 today | design | 4208 | 44.5% | Rs 50,425 | Rs 2,14,239 | | | |
| FT3: sell at +3 min if the index has not gone past the signal bar | final | 1704 | 38.3% | Rs 39,707 | Rs 74,709 | 1.000 / 1.000 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| FT3: sell at +3 min if the index has not gone past the signal bar | walk-forward | 3089 | 41.0% | Rs 1,34,970 | Rs 89,125 | | | |
| FT3: sell at +3 min if the index has not gone past the signal bar | design | 4267 | 39.6% | Rs 36,862 | Rs 1,68,330 | | | |
| FT5: the same at +5 min | final | 1699 | 39.7% | Rs 30,169 | Rs 74,263 | 1.000 / 1.000 | 1.000 | fail: better by neither route (wf and final); design: net not positive; BH q 1 > 0.1 |
| FT5: the same at +5 min | walk-forward | 3077 | 41.4% | Rs 86,381 | Rs 94,592 | | | |
| FT5: the same at +5 min | design | 4248 | 40.4% | -Rs 24,815 | Rs 1,86,393 | | | |



## D. Fake volume

**R72 Breadth Donchian**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1202 | 45.8% | Rs 1,71,366 | Rs 61,884 | | | |
| B0 today | walk-forward | 2238 | 49.0% | Rs 3,35,882 | Rs 86,529 | | | |
| B0 today | design | 3071 | 46.8% | Rs 2,16,929 | Rs 1,99,077 | | | |
| V1: skip when the option's signal-bar volume is below its usual (ratio < 1) | final | 1115 | 46.2% | Rs 2,03,445 | Rs 61,019 | 0.006 / 0.186 | 1.000 | fail: BH q 1 > 0.1 |
| V1: skip when the option's signal-bar volume is below its usual (ratio < 1) | walk-forward | 2044 | 49.7% | Rs 3,59,030 | Rs 73,834 | | | |
| V1: skip when the option's signal-bar volume is below its usual (ratio < 1) | design | 2827 | 47.5% | Rs 2,77,103 | Rs 1,74,027 | | | |
| V2: skip a volume climax in the option (ratio >= 3) | final | 932 | 47.5% | Rs 1,58,024 | Rs 71,640 | 0.080 / 0.034 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| V2: skip a volume climax in the option (ratio >= 3) | walk-forward | 1773 | 48.0% | Rs 2,68,609 | Rs 71,095 | | | |
| V2: skip a volume climax in the option (ratio >= 3) | design | 2444 | 45.2% | Rs 1,18,495 | Rs 2,15,396 | | | |

Share of breaks each check marks (design): V1 7.8%, V2 26.6%

**R51 Donchian 20 (+ Supertrend 10, 3)**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1670 | 44.9% | Rs 1,34,743 | Rs 81,683 | | | |
| B0 today | walk-forward | 3044 | 45.9% | Rs 2,13,748 | Rs 94,176 | | | |
| B0 today | design | 4208 | 44.5% | Rs 50,425 | Rs 2,14,239 | | | |
| V1: skip when the option's signal-bar volume is below its usual (ratio < 1) | final | 1461 | 45.2% | Rs 1,63,243 | Rs 71,864 | 0.038 / 0.186 | 1.000 | fail: BH q 1 > 0.1 |
| V1: skip when the option's signal-bar volume is below its usual (ratio < 1) | walk-forward | 2653 | 45.8% | Rs 2,37,672 | Rs 83,906 | | | |
| V1: skip when the option's signal-bar volume is below its usual (ratio < 1) | design | 3716 | 44.4% | Rs 1,08,408 | Rs 1,81,062 | | | |
| V2: skip a volume climax in the option (ratio >= 3) | final | 1376 | 45.4% | Rs 1,60,889 | Rs 61,857 | 0.042 / 0.208 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| V2: skip a volume climax in the option (ratio >= 3) | walk-forward | 2599 | 45.9% | Rs 1,68,906 | Rs 1,05,598 | | | |
| V2: skip a volume climax in the option (ratio >= 3) | design | 3570 | 44.1% | Rs 10,636 | Rs 2,07,996 | | | |

Share of breaks each check marks (design): V1 13.8%, V2 21.4%

## E. Fake buyer / seller

**R72 Breadth Donchian**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1202 | 45.8% | Rs 1,71,366 | Rs 61,884 | | | |
| B0 today | walk-forward | 2238 | 49.0% | Rs 3,35,882 | Rs 86,529 | | | |
| B0 today | design | 3071 | 46.8% | Rs 2,16,929 | Rs 1,99,077 | | | |
| E1: skip when the option's implied vol fell over the last 6 minutes | final | 817 | 45.8% | Rs 1,40,373 | Rs 52,504 | 0.214 / 0.529 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| E1: skip when the option's implied vol fell over the last 6 minutes | walk-forward | 1391 | 51.2% | Rs 2,54,981 | Rs 44,226 | | | |
| E1: skip when the option's implied vol fell over the last 6 minutes | design | 1933 | 48.1% | Rs 1,94,984 | Rs 1,45,898 | | | |
| E2: skip when the opposite ATM option got dearer over the last 6 minutes | final | 1185 | 45.7% | Rs 1,65,239 | Rs 63,648 | 0.627 / 0.703 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| E2: skip when the opposite ATM option got dearer over the last 6 minutes | walk-forward | 2202 | 49.0% | Rs 3,40,425 | Rs 85,196 | | | |
| E2: skip when the opposite ATM option got dearer over the last 6 minutes | design | 3023 | 46.7% | Rs 2,26,185 | Rs 1,91,709 | | | |

Share of breaks each check marks (design): E1 39.9%, E2 2.5%

**R51 Donchian 20 (+ Supertrend 10, 3)**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1670 | 44.9% | Rs 1,34,743 | Rs 81,683 | | | |
| B0 today | walk-forward | 3044 | 45.9% | Rs 2,13,748 | Rs 94,176 | | | |
| B0 today | design | 4208 | 44.5% | Rs 50,425 | Rs 2,14,239 | | | |
| E1: skip when the option's implied vol fell over the last 6 minutes | final | 1065 | 46.5% | Rs 1,27,680 | Rs 55,071 | 0.098 / 0.060 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| E1: skip when the option's implied vol fell over the last 6 minutes | walk-forward | 1855 | 47.5% | Rs 1,71,247 | Rs 87,029 | | | |
| E1: skip when the option's implied vol fell over the last 6 minutes | design | 2599 | 45.8% | Rs 72,867 | Rs 1,53,710 | | | |
| E2: skip when the opposite ATM option got dearer over the last 6 minutes | final | 1641 | 44.9% | Rs 1,33,548 | Rs 83,561 | 0.385 / 0.463 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| E2: skip when the opposite ATM option got dearer over the last 6 minutes | walk-forward | 2987 | 46.0% | Rs 2,04,244 | Rs 91,836 | | | |
| E2: skip when the opposite ATM option got dearer over the last 6 minutes | design | 4137 | 44.6% | Rs 52,762 | Rs 2,13,223 | | | |

Share of breaks each check marks (design): E1 44.0%, E2 4.2%

## F. Traps

**R72 Breadth Donchian**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1202 | 45.8% | Rs 1,71,366 | Rs 61,884 | | | |
| B0 today | walk-forward | 2238 | 49.0% | Rs 3,35,882 | Rs 86,529 | | | |
| B0 today | design | 3071 | 46.8% | Rs 2,16,929 | Rs 1,99,077 | | | |
| F1: skip when the index broke the other way within 30 minutes | final | 1182 | 45.9% | Rs 1,70,520 | Rs 71,039 | 0.495 / 0.423 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| F1: skip when the index broke the other way within 30 minutes | walk-forward | 2220 | 49.0% | Rs 3,35,957 | Rs 84,670 | | | |
| F1: skip when the index broke the other way within 30 minutes | design | 3035 | 46.8% | Rs 2,42,669 | Rs 1,77,342 | | | |
| F2: skip when the signal bar is an outside (sweep) bar | final | 1164 | 46.7% | Rs 2,04,415 | Rs 49,424 | 0.002 / 0.004 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| F2: skip when the signal bar is an outside (sweep) bar | walk-forward | 2108 | 49.1% | Rs 3,10,385 | Rs 82,084 | | | |
| F2: skip when the signal bar is an outside (sweep) bar | design | 2879 | 47.2% | Rs 2,20,036 | Rs 1,72,873 | | | |

Share of breaks each check marks (design): F1 0.9%, F2 6.3%

**R51 Donchian 20 (+ Supertrend 10, 3)**

| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |
|---|---|---|---|---|---|---|---|---|
| B0 today | final | 1670 | 44.9% | Rs 1,34,743 | Rs 81,683 | | | |
| B0 today | walk-forward | 3044 | 45.9% | Rs 2,13,748 | Rs 94,176 | | | |
| B0 today | design | 4208 | 44.5% | Rs 50,425 | Rs 2,14,239 | | | |
| F1: skip when the index broke the other way within 30 minutes | final | 1638 | 44.6% | Rs 1,34,381 | Rs 87,171 | 0.491 / 0.960 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| F1: skip when the index broke the other way within 30 minutes | walk-forward | 2980 | 45.7% | Rs 1,69,836 | Rs 92,314 | | | |
| F1: skip when the index broke the other way within 30 minutes | design | 4095 | 44.5% | Rs 37,443 | Rs 1,74,327 | | | |
| F2: skip when the signal bar is an outside (sweep) bar | final | 1587 | 45.5% | Rs 1,63,970 | Rs 77,402 | 0.024 / 0.026 | 1.000 | fail: better by neither route (wf and final); BH q 1 > 0.1 |
| F2: skip when the signal bar is an outside (sweep) bar | walk-forward | 2866 | 45.7% | Rs 1,44,356 | Rs 1,19,046 | | | |
| F2: skip when the signal bar is an outside (sweep) bar | design | 3950 | 44.5% | Rs 32,877 | Rs 1,74,056 | | | |

Share of breaks each check marks (design): F1 1.6%, F2 6.3%

## Full detail, final period (1 lot)

**R72 Breadth Donchian**

| rule | trades | win % | break-even win % | net | avg win | avg loss | worst day | max DD | net without 5 best |
|---|---|---|---|---|---|---|---|---|---|
| B0 today | 1202 | 45.8% | 41.0% | Rs 1,71,366 | Rs 1,748 | -Rs 1,217 | -Rs 22,571 | Rs 61,884 | Rs 1,12,623 |
| D60C2 | 1207 | 47.6% | 41.8% | Rs 2,09,565 | Rs 1,755 | -Rs 1,260 | -Rs 22,142 | Rs 77,026 | Rs 1,54,829 |
| D60W | 1202 | 46.2% | 41.4% | Rs 1,75,848 | Rs 1,790 | -Rs 1,264 | -Rs 23,327 | Rs 63,483 | Rs 1,16,708 |
| SR1 | 1054 | 46.2% | 41.5% | Rs 1,48,568 | Rs 1,750 | -Rs 1,241 | -Rs 20,401 | Rs 60,080 | Rs 89,921 |
| SR2 | 547 | 42.2% | 41.7% | Rs 9,247 | Rs 1,762 | -Rs 1,259 | -Rs 22,367 | Rs 87,656 | -Rs 41,311 |
| FT3 | 1224 | 38.5% | 36.6% | Rs 52,065 | Rs 1,438 | -Rs 831 | -Rs 14,064 | Rs 89,298 | -Rs 6,678 |
| FT5 | 1222 | 39.6% | 38.0% | Rs 48,162 | Rs 1,523 | -Rs 934 | -Rs 15,170 | Rs 76,999 | -Rs 10,581 |
| V1 | 1115 | 46.2% | 40.2% | Rs 2,03,445 | Rs 1,819 | -Rs 1,222 | -Rs 22,571 | Rs 61,019 | Rs 1,44,702 |
| V2 | 932 | 47.5% | 41.7% | Rs 1,58,024 | Rs 1,688 | -Rs 1,206 | -Rs 18,868 | Rs 71,640 | Rs 1,11,119 |
| E1 | 817 | 45.8% | 40.4% | Rs 1,40,373 | Rs 1,902 | -Rs 1,289 | -Rs 20,440 | Rs 52,504 | Rs 83,754 |
| E2 | 1185 | 45.7% | 41.1% | Rs 1,65,239 | Rs 1,755 | -Rs 1,223 | -Rs 21,414 | Rs 63,648 | Rs 1,06,496 |
| F1 | 1182 | 45.9% | 41.0% | Rs 1,70,520 | Rs 1,755 | -Rs 1,220 | -Rs 26,006 | Rs 71,039 | Rs 1,11,777 |
| F2 | 1164 | 46.7% | 40.7% | Rs 2,04,415 | Rs 1,754 | -Rs 1,205 | -Rs 22,571 | Rs 49,424 | Rs 1,45,672 |

**R51 Donchian 20 (+ Supertrend 10, 3)**

| rule | trades | win % | break-even win % | net | avg win | avg loss | worst day | max DD | net without 5 best |
|---|---|---|---|---|---|---|---|---|---|
| B0 today | 1670 | 44.9% | 42.0% | Rs 1,34,743 | Rs 1,651 | -Rs 1,197 | -Rs 21,578 | Rs 81,683 | Rs 76,711 |
| D60C2 | 1673 | 46.3% | 42.2% | Rs 2,01,715 | Rs 1,704 | -Rs 1,246 | -Rs 20,721 | Rs 80,329 | Rs 1,49,055 |
| D60W | 1670 | 45.4% | 42.5% | Rs 1,40,277 | Rs 1,680 | -Rs 1,243 | -Rs 22,917 | Rs 82,033 | Rs 81,848 |
| SR1 | 1437 | 45.0% | 42.2% | Rs 1,14,181 | Rs 1,645 | -Rs 1,203 | -Rs 18,723 | Rs 56,617 | Rs 57,720 |
| SR2 | 678 | 43.4% | 43.7% | -Rs 6,280 | Rs 1,549 | -Rs 1,202 | -Rs 19,114 | Rs 68,909 | -Rs 53,996 |
| FT3 | 1704 | 38.3% | 37.2% | Rs 39,707 | Rs 1,416 | -Rs 840 | -Rs 14,997 | Rs 74,709 | -Rs 18,325 |
| FT5 | 1699 | 39.7% | 38.9% | Rs 30,169 | Rs 1,464 | -Rs 933 | -Rs 15,960 | Rs 74,263 | -Rs 27,863 |
| V1 | 1461 | 45.2% | 41.4% | Rs 1,63,243 | Rs 1,726 | -Rs 1,222 | -Rs 16,689 | Rs 71,864 | Rs 1,05,210 |
| V2 | 1376 | 45.4% | 41.3% | Rs 1,60,889 | Rs 1,657 | -Rs 1,165 | -Rs 20,164 | Rs 61,857 | Rs 1,16,503 |
| E1 | 1065 | 46.5% | 42.6% | Rs 1,27,680 | Rs 1,794 | -Rs 1,334 | -Rs 17,247 | Rs 55,071 | Rs 73,300 |
| E2 | 1641 | 44.9% | 42.0% | Rs 1,33,548 | Rs 1,662 | -Rs 1,204 | -Rs 21,896 | Rs 83,561 | Rs 75,516 |
| F1 | 1638 | 44.6% | 41.7% | Rs 1,34,381 | Rs 1,658 | -Rs 1,188 | -Rs 21,578 | Rs 87,171 | Rs 76,349 |
| F2 | 1587 | 45.5% | 41.9% | Rs 1,63,970 | Rs 1,673 | -Rs 1,207 | -Rs 18,558 | Rs 77,402 | Rs 1,05,938 |

## Notes

- Option-feature coverage (design candidates): {"R72": {"candidates": 5875, "volume_ratio_known": 4943, "iv_known": 5294}, "R51": {"candidates": 12441, "volume_ratio_known": 10698, "iv_known": 11607}}. Unknown values never skip.
- Not run: the unseen two years (Dec 2022 - Nov 2024), dropped on Boss's instruction (Nov 2024 onward only), before any number.
- Delta beyond these two variants is R165's study (sibling); order flow from the phone's recordings is R164's.
