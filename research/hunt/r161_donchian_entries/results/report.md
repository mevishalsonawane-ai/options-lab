# R161: which Donchian breaks to take (entry quality). Nothing raised the win rate enough; nothing changes

12 Nov 2026. Research only: nothing in the app changes. Pre-registration: `research/hunt/r161_donchian_entries/PREREG.md`
(commit 0fa3d8e, pushed before any number). Code, 25 tests and the design results were frozen in 179aa55 before the one
final read (`results/FINAL_READ`).

Boss, 12 Nov: *"I want to increase my win rate [of the] Donchian family, try your best ... use all resources you can."*

## For Boss (short)

**Answer: no. 0 of 168 tests passed. No entry filter raised the win rate by 5 points without losing money.**

- **What we tried:** 13 new ways to pick the better breaks, plus the best two together. They cover: a strong close,
  waiting one bar, a big bar, a far break, a narrow channel first, the 15-min and 60-min trend, a break of yesterday's
  high or low, a busy market, VIX moving our way, 4 of 5 indices with us, no lunch-time entries, and a break of the
  first hour's range.
- **Where we tried them:** on R72 and R51, at 1, 2 and 3 lots. Once on today's rules, and once on top of the live picker.
- **The best gain in the final months was +2.5 points,** half of what was needed.
  - It was R51 with the picker plus "busy market" (E9): 52.9% instead of 50.4%.
  - That one also made Rs 10,021 less.
- **Most filters cost money.** They skip winners as often as losers, so the win rate barely moves and the profit falls.
- **One filter made more money in the final months:** R72 "wait one bar" (E2), +Rs 49,760 at 1 lot.
  - It gained only +1.7 points of win rate.
  - In the walk-forward year it lost Rs 2.06 lakh against today's rules.
  - Not safe to use.
- **The picker is already doing this job.** On top of it, no filter added 5 points either.

**Recommendation: keep R72 and R51 exactly as they are (with R151's picker as live now).** Entry filters on the
index have now been tried many times (R73, R75, R101, R105B, R142, R151 and R161), and none adds a real edge. If Boss
wants a higher win rate, it would come from exits (R162 runs today), not from choosing breaks.

| 1 lot, final 1 Apr - 13 Oct 2026 | trades | win | net |
|---|---|---|---|
| R72 today | 1,202 | 45.8% | Rs 1,71,366 |
| R72 best filter (E9 busy market) | 720 | 47.4% (+1.5) | Rs 1,90,887 (walk-forward year: Rs 29,717 less than today) |
| R72 + picker (live) | 603 | 49.1% | Rs 1,69,674 |
| R72 + picker + E5 narrow channel | 407 | 50.1% (+1.0) | Rs 1,81,208 (walk-forward year: Rs 39,546 less) |
| R51 today | 1,670 | 44.9% | Rs 1,34,743 |
| R51 best filter (E9 busy market) | 941 | 46.4% (+1.6) | Rs 1,07,151 (less) |
| R51 + picker (live) | 653 | 50.4% | Rs 1,42,884 |
| R51 + picker + E9 busy market | 386 | 52.9% (+2.5) | Rs 1,32,863 (less) |

## What was done (one paragraph each)

- **Signal source: the index**, every rule. The arms' own 5-min index bars; the other four indices (E11); India VIX
  minutes (E10). The bought option (1-ITM weekly) and today's exits are unchanged: −15% stop, ladder + L25, 30-min time
  stop, 15:15 square-off.
- **Engine:**
  - R152's engine: dated Zerodha charges on the whole quantity, and R17 slippage sized to the order.
  - Today's rules reproduce the published records to the rupee: R72 3,071 / Rs 2,16,929 (design) and 1,202 / Rs 1,71,366
    (final); R51 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743.
  - The picker reference reproduces R152's second look: R72 603 / Rs 1,69,674; R51 653 / Rs 1,42,884.
- **Periods:**
  - Design: 20 Nov 2024 - 31 Mar 2026, with the walk-forward year Apr 2025 - Mar 2026.
  - Final: 1 Apr - 13 Oct 2026, read once.
  - **Honest note:** many studies have already read Apr - Oct 2026 for these arms, so even a pass there would have been
    weak evidence.
- **Not repeated (already tested):** gap side, any-index expiry day, the VIX regime, 15/60-min EMA rising, the daily
  trend, early / late time cuts, opening-range chop, futures volume, OI and option liquidity. See PREREG section 0.
- **C1 (the best two together)** was chosen on design data only, again before each walk-forward quarter.
  - R72's final choice: E8 + E9.
  - R51's final choice: E8 + E12.
  - In the final months it lowered the win rate on both arms.
- **Gates (all needed):**
  - win rate +5 points and net not lower, in the walk-forward year and the final;
  - net > 0 in every period;
  - beats random skips of the same size on both win rate and net;
  - BH q <= 0.10;
  - >= 40 final trades.
- **Multiple testing:**
  - Raw p-values of all 168 tests are in **`results/raw_pvalues.csv`**, so the coordinator can run one joint BH with R162
    and R163.
  - The smallest raw p is 0.008: R72 E2 at 3 lots, on net. It failed the walk-forward year anyway.
  - With m = 2,332 (R160's 2,164 + R161's 168) every q is 1.0.
- **Limits:**
  - The second look's walk-forward reference uses R151's out-of-fold scores with the app's thresholds, which R151 chose
    on those scores (in-sample for the threshold).
  - E2's moved breaks are scored by the picker at their new minute.
  - The post window (14-19 Oct) was not used.

## Full tables

Design (20 Nov 2024 - 31 Mar 2026), with the four walk-forward quarters, is in `design.json`; the final (1 Apr - 13 Oct 2026, read once) in `final.json`; every gate in `decision.json`; **all 168 raw p-values in `raw_pvalues.csv`** (for the joint BH with R162 and R163).

**C1's choices (made on design data only):**

- R72 Breadth Donchian: WF quarters q1 E8 + E3, q2 E8 + E3, q3 E8 + E3, q4 E8 + E9; final: E8 + E9.
- R51 Donchian 20 + Supertrend (10, 3): WF quarters q1 E8 + E12, q2 E8 + E12, q3 E8 + E7, q4 E8 + E13; final: E8 + E12.

**How often each filter could not be computed (taken as today), design candidates:**

- R72: E1 0, E3 0, E4 0, E5 0, E6 0, E7 0, E8 0, E9 0, E10 0, E11 0, E12 0, E13 0 (of 5903 candidates).
- R51: E1 0, E3 0, E4 0, E5 0, E6 0, E7 0, E8 0, E9 0, E10 6, E11 0, E12 0, E13 6 (of 12485 candidates).

### R72 Breadth Donchian, on today's rules

**Walk-forward year (Apr 2025 - Mar 2026) and final (1 Apr - 13 Oct 2026), 1 lot:**

| rule | what it takes | WF trades | WF win (change) | WF net (change) | final trades | final win (change) | final net (change) | p (win / net) | q (m = 2,332) | passes? |
|---|---|---|---|---|---|---|---|---|---|---|
| B0 | today's rules | 2238 | 49.0% | Rs 3,35,882 | 1202 | 45.8% | Rs 1,71,366 | - | - | - |
| E1 STRONGCLOSE | the signal bar closes in its outer 30% | 1889 | 48.1% (-0.9) | Rs 2,56,937 (-Rs 78,945) | 998 | 44.6% (-1.2) | Rs 1,25,773 (-Rs 45,593) | 0.898 / 0.755 | 1.000 | no |
| E2 HOLD1 | the next 5-min bar also closes beyond the broken level (enter one bar later) | 1931 | 46.8% (-2.2) | Rs 1,29,475 (-Rs 2,06,407) | 1018 | 47.5% (+1.7) | Rs 2,21,126 (+Rs 49,760) | 0.012 / 0.009 | 1.000 | no |
| E3 BIGBAR | the signal bar is at least 1 ATR tall | 2031 | 48.4% (-0.6) | Rs 2,64,074 (-Rs 71,808) | 1069 | 45.8% (+0.0) | Rs 2,06,662 (+Rs 35,296) | 0.453 / 0.031 | 1.000 | no |
| E4 FAR | the close is at least 0.2 ATR beyond the channel | 2058 | 49.4% (+0.4) | Rs 3,16,052 (-Rs 19,830) | 1115 | 45.6% (-0.3) | Rs 1,51,498 (-Rs 19,868) | 0.583 / 0.561 | 1.000 | no |
| E5 SQUEEZE | the channel was narrow (at or below its 100-bar median) before the break | 1472 | 47.1% (-1.9) | Rs 1,00,361 (-Rs 2,35,521) | 841 | 46.9% (+1.0) | Rs 1,99,016 (+Rs 27,650) | 0.143 / 0.013 | 1.000 | no |
| E6 HTF15 | the 15-min Donchian channel agrees (close on the trade's side of its midline) | 1786 | 47.7% (-1.3) | Rs 1,67,423 (-Rs 1,68,459) | 958 | 43.0% (-2.8) | Rs 26,695 (-Rs 1,44,671) | 1.000 / 1.000 | 1.000 | no |
| E7 HTF60 | the 60-min Donchian channel agrees | 1327 | 49.4% (+0.5) | Rs 2,35,719 (-Rs 1,00,163) | 696 | 43.0% (-2.9) | Rs 50,414 (-Rs 1,20,952) | 0.988 / 0.977 | 1.000 | no |
| E8 PDR | the break is beyond yesterday's high (call) / low (put) | 1133 | 50.7% (+1.7) | Rs 2,58,463 (-Rs 77,419) | 620 | 42.3% (-3.6) | Rs 26,929 (-Rs 1,44,437) | 0.996 / 0.992 | 1.000 | no |
| E9 ATRHOT | the 5-min ATR is at or above its usual level for that time of day | 1502 | 50.1% (+1.1) | Rs 3,06,165 (-Rs 29,717) | 720 | 47.4% (+1.5) | Rs 1,90,887 (+Rs 19,521) | 0.049 / 0.053 | 1.000 | no |
| E10 VIXWAY | India VIX moves the trade's way since 09:15 (down for a call, up for a put) | 1544 | 47.1% (-1.8) | Rs 1,60,727 (-Rs 1,75,155) | 825 | 45.9% (+0.1) | Rs 1,27,408 (-Rs 43,958) | 0.356 / 0.652 | 1.000 | no |
| E11 BREADTH5 | 4 of the 5 indices are on the trade's side of their day open | 1649 | 48.1% (-0.9) | Rs 1,83,041 (-Rs 1,52,841) | 886 | 45.3% (-0.6) | Rs 59,756 (-Rs 1,11,610) | 0.698 / 0.997 | 1.000 | no |
| E12 NOLUNCH | no entries from 11:30 to 13:29 | 1768 | 50.3% (+1.3) | Rs 3,09,904 (-Rs 25,978) | 900 | 45.1% (-0.7) | Rs 1,27,679 (-Rs 43,687) | 0.680 / 0.627 | 1.000 | no |
| E13 PASTHOUR | after 10:15, the break is beyond the first hour's high / low | 1653 | 49.4% (+0.4) | Rs 2,37,201 (-Rs 98,681) | 913 | 46.3% (+0.5) | Rs 77,212 (-Rs 94,154) | 0.214 / 0.978 | 1.000 | no |
| C1 COMBO | the best two of the above, chosen on design only (walk-forward) | 1015 | 49.7% (+0.7) | Rs 2,32,195 (-Rs 1,03,687) | 380 | 44.7% (-1.1) | Rs 77,575 (-Rs 93,791) | 0.727 / 0.584 | 1.000 | no |

**Final period, every lot size** (random-skip p = the larger of the win and net p, 1,000 twins):

| lots | rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | random-skip p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | B0 | 1202 | 45.8% | 41.0% | Rs 1,71,366 | Rs 1,748 | -Rs 1,217 | -Rs 22,571 | Rs 61,884 | Rs 1,12,623 | - |
| 1 | E1 STRONGCLOSE | 998 | 44.6% | 40.5% | Rs 1,25,773 | Rs 1,823 | -Rs 1,240 | -Rs 23,342 | Rs 95,042 | Rs 58,150 | 0.898 |
| 1 | E2 HOLD1 | 1018 | 47.5% | 40.0% | Rs 2,21,126 | Rs 1,730 | -Rs 1,154 | -Rs 20,964 | Rs 62,738 | Rs 1,56,811 | 0.012 |
| 1 | E3 BIGBAR | 1069 | 45.8% | 39.8% | Rs 2,06,662 | Rs 1,939 | -Rs 1,284 | -Rs 26,421 | Rs 84,884 | Rs 1,38,617 | 0.453 |
| 1 | E4 FAR | 1115 | 45.6% | 41.1% | Rs 1,51,498 | Rs 1,789 | -Rs 1,248 | -Rs 26,999 | Rs 94,684 | Rs 92,331 | 0.583 |
| 1 | E5 SQUEEZE | 841 | 46.9% | 39.2% | Rs 1,99,016 | Rs 1,875 | -Rs 1,208 | -Rs 20,401 | Rs 41,485 | Rs 1,32,461 | 0.143 |
| 1 | E6 HTF15 | 958 | 43.0% | 42.1% | Rs 26,695 | Rs 1,704 | -Rs 1,237 | -Rs 22,059 | Rs 88,031 | -Rs 29,538 | 1.000 |
| 1 | E7 HTF60 | 696 | 43.0% | 40.4% | Rs 50,414 | Rs 1,714 | -Rs 1,164 | -Rs 22,367 | Rs 63,124 | -Rs 5,228 | 0.988 |
| 1 | E8 PDR | 620 | 42.3% | 40.8% | Rs 26,929 | Rs 1,789 | -Rs 1,234 | -Rs 22,367 | Rs 89,844 | -Rs 27,717 | 0.996 |
| 1 | E9 ATRHOT | 720 | 47.4% | 39.3% | Rs 1,90,887 | Rs 1,995 | -Rs 1,291 | -Rs 19,303 | Rs 38,442 | Rs 1,24,333 | 0.053 |
| 1 | E10 VIXWAY | 825 | 45.9% | 40.8% | Rs 1,27,408 | Rs 1,776 | -Rs 1,223 | -Rs 21,483 | Rs 65,687 | Rs 68,665 | 0.652 |
| 1 | E11 BREADTH5 | 886 | 45.3% | 42.9% | Rs 59,756 | Rs 1,644 | -Rs 1,236 | -Rs 23,657 | Rs 65,478 | -Rs 6,798 | 0.997 |
| 1 | E12 NOLUNCH | 900 | 45.1% | 40.5% | Rs 1,27,679 | Rs 1,820 | -Rs 1,238 | -Rs 20,401 | Rs 78,925 | Rs 60,056 | 0.680 |
| 1 | E13 PASTHOUR | 913 | 46.3% | 43.5% | Rs 77,212 | Rs 1,673 | -Rs 1,287 | -Rs 22,571 | Rs 62,091 | Rs 23,291 | 0.978 |
| 1 | C1 COMBO | 380 | 44.7% | 38.9% | Rs 77,575 | Rs 2,123 | -Rs 1,349 | -Rs 20,929 | Rs 69,461 | Rs 17,042 | 0.727 |
| 2 | B0 | 1202 | 46.3% | 40.7% | Rs 3,98,405 | Rs 3,512 | -Rs 2,406 | -Rs 44,447 | Rs 1,11,087 | Rs 2,80,683 | - |
| 2 | E1 STRONGCLOSE | 998 | 45.0% | 40.1% | Rs 2,97,733 | Rs 3,660 | -Rs 2,451 | -Rs 45,822 | Rs 1,74,542 | Rs 1,62,252 | 0.909 |
| 2 | E2 HOLD1 | 1018 | 48.3% | 40.0% | Rs 4,89,367 | Rs 3,449 | -Rs 2,296 | -Rs 41,557 | Rs 1,09,911 | Rs 3,60,501 | 0.013 |
| 2 | E3 BIGBAR | 1069 | 46.1% | 39.4% | Rs 4,62,865 | Rs 3,901 | -Rs 2,535 | -Rs 51,998 | Rs 1,55,990 | Rs 3,26,538 | 0.475 |
| 2 | E4 FAR | 1115 | 45.9% | 40.7% | Rs 3,54,712 | Rs 3,597 | -Rs 2,466 | -Rs 53,113 | Rs 1,71,431 | Rs 2,36,143 | 0.637 |
| 2 | E5 SQUEEZE | 841 | 47.6% | 39.1% | Rs 4,37,007 | Rs 3,741 | -Rs 2,402 | -Rs 40,390 | Rs 74,247 | Rs 3,03,662 | 0.104 |
| 2 | E6 HTF15 | 958 | 43.4% | 41.7% | Rs 97,679 | Rs 3,420 | -Rs 2,445 | -Rs 43,417 | Rs 1,49,131 | -Rs 15,024 | 1.000 |
| 2 | E7 HTF60 | 696 | 43.4% | 40.1% | Rs 1,32,966 | Rs 3,440 | -Rs 2,300 | -Rs 44,434 | Rs 1,17,390 | Rs 21,458 | 0.989 |
| 2 | E8 PDR | 620 | 43.1% | 40.8% | Rs 82,564 | Rs 3,558 | -Rs 2,457 | -Rs 44,434 | Rs 1,71,383 | -Rs 26,951 | 0.993 |
| 2 | E9 ATRHOT | 720 | 47.4% | 38.6% | Rs 4,15,165 | Rs 4,036 | -Rs 2,536 | -Rs 37,822 | Rs 73,168 | Rs 2,81,820 | 0.107 |
| 2 | E10 VIXWAY | 825 | 46.3% | 40.4% | Rs 2,93,007 | Rs 3,570 | -Rs 2,417 | -Rs 42,335 | Rs 1,20,032 | Rs 1,75,285 | 0.714 |
| 2 | E11 BREADTH5 | 886 | 45.9% | 42.8% | Rs 1,60,553 | Rs 3,285 | -Rs 2,456 | -Rs 46,517 | Rs 1,19,467 | Rs 27,207 | 0.996 |
| 2 | E12 NOLUNCH | 900 | 45.7% | 40.2% | Rs 2,97,029 | Rs 3,643 | -Rs 2,454 | -Rs 40,390 | Rs 1,51,681 | Rs 1,61,547 | 0.670 |
| 2 | E13 PASTHOUR | 913 | 46.9% | 43.2% | Rs 1,96,678 | Rs 3,353 | -Rs 2,554 | -Rs 44,447 | Rs 1,02,625 | Rs 88,601 | 0.989 |
| 2 | C1 COMBO | 380 | 44.7% | 38.2% | Rs 1,72,757 | Rs 4,293 | -Rs 2,652 | -Rs 41,605 | Rs 1,34,408 | Rs 51,456 | 0.801 |
| 3 | B0 | 1202 | 46.5% | 40.6% | Rs 6,25,048 | Rs 5,262 | -Rs 3,603 | -Rs 66,325 | Rs 1,60,424 | Rs 4,48,347 | - |
| 3 | E1 STRONGCLOSE | 998 | 45.3% | 40.2% | Rs 4,69,397 | Rs 5,477 | -Rs 3,674 | -Rs 68,303 | Rs 2,54,190 | Rs 2,66,057 | 0.900 |
| 3 | E2 HOLD1 | 1018 | 48.7% | 40.1% | Rs 7,57,392 | Rs 5,155 | -Rs 3,447 | -Rs 62,123 | Rs 1,57,130 | Rs 5,63,975 | 0.008 |
| 3 | E3 BIGBAR | 1069 | 46.4% | 39.4% | Rs 7,18,707 | Rs 5,838 | -Rs 3,800 | -Rs 77,572 | Rs 2,27,213 | Rs 5,14,099 | 0.427 |
| 3 | E4 FAR | 1115 | 46.1% | 40.6% | Rs 5,57,573 | Rs 5,397 | -Rs 3,688 | -Rs 79,222 | Rs 2,49,139 | Rs 3,79,602 | 0.675 |
| 3 | E5 SQUEEZE | 841 | 47.8% | 39.1% | Rs 6,74,702 | Rs 5,606 | -Rs 3,596 | -Rs 60,372 | Rs 1,07,630 | Rs 4,74,566 | 0.100 |
| 3 | E6 HTF15 | 958 | 43.4% | 41.4% | Rs 1,68,358 | Rs 5,153 | -Rs 3,645 | -Rs 64,843 | Rs 2,15,186 | -Rs 814 | 1.000 |
| 3 | E7 HTF60 | 696 | 43.7% | 40.1% | Rs 2,15,425 | Rs 5,149 | -Rs 3,444 | -Rs 66,486 | Rs 1,71,710 | Rs 48,045 | 0.992 |
| 3 | E8 PDR | 620 | 43.2% | 40.8% | Rs 1,38,064 | Rs 5,339 | -Rs 3,673 | -Rs 66,486 | Rs 2,53,033 | -Rs 26,326 | 0.999 |
| 3 | E9 ATRHOT | 720 | 47.5% | 38.5% | Rs 6,39,137 | Rs 6,059 | -Rs 3,791 | -Rs 56,331 | Rs 1,07,948 | Rs 4,39,001 | 0.118 |
| 3 | E10 VIXWAY | 825 | 46.7% | 40.5% | Rs 4,58,288 | Rs 5,336 | -Rs 3,628 | -Rs 63,190 | Rs 1,74,521 | Rs 2,81,587 | 0.732 |
| 3 | E11 BREADTH5 | 886 | 46.2% | 42.7% | Rs 2,61,078 | Rs 4,926 | -Rs 3,676 | -Rs 69,374 | Rs 1,73,441 | Rs 60,942 | 0.997 |
| 3 | E12 NOLUNCH | 900 | 46.0% | 40.3% | Rs 4,66,050 | Rs 5,447 | -Rs 3,681 | -Rs 60,372 | Rs 2,24,418 | Rs 2,62,710 | 0.670 |
| 3 | E13 PASTHOUR | 913 | 47.0% | 43.1% | Rs 3,15,832 | Rs 5,041 | -Rs 3,816 | -Rs 66,325 | Rs 1,43,262 | Rs 1,53,597 | 0.980 |
| 3 | C1 COMBO | 380 | 44.7% | 38.0% | Rs 2,67,825 | Rs 6,462 | -Rs 3,956 | -Rs 62,265 | Rs 1,99,927 | Rs 85,754 | 0.826 |

**Walk-forward year, every lot size:**

| lots | rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | random-skip p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | B0 | 2238 | 49.0% | 43.9% | Rs 3,35,882 | Rs 1,669 | -Rs 1,307 | -Rs 25,837 | Rs 86,529 | Rs 2,79,428 | - |
| 1 | E1 STRONGCLOSE | 1889 | 48.1% | 43.5% | Rs 2,56,937 | Rs 1,668 | -Rs 1,282 | -Rs 31,124 | Rs 1,07,475 | Rs 2,00,483 | - |
| 1 | E2 HOLD1 | 1931 | 46.8% | 44.5% | Rs 1,29,475 | Rs 1,595 | -Rs 1,278 | -Rs 37,417 | Rs 1,45,649 | Rs 78,423 | - |
| 1 | E3 BIGBAR | 2031 | 48.4% | 44.1% | Rs 2,64,074 | Rs 1,685 | -Rs 1,329 | -Rs 30,620 | Rs 1,09,732 | Rs 2,07,755 | - |
| 1 | E4 FAR | 2058 | 49.4% | 44.3% | Rs 3,16,052 | Rs 1,682 | -Rs 1,339 | -Rs 27,917 | Rs 79,510 | Rs 2,61,104 | - |
| 1 | E5 SQUEEZE | 1472 | 47.1% | 44.6% | Rs 1,00,361 | Rs 1,511 | -Rs 1,215 | -Rs 26,715 | Rs 73,770 | Rs 47,757 | - |
| 1 | E6 HTF15 | 1786 | 47.7% | 44.5% | Rs 1,67,423 | Rs 1,625 | -Rs 1,303 | -Rs 23,163 | Rs 1,01,268 | Rs 1,10,969 | - |
| 1 | E7 HTF60 | 1327 | 49.4% | 43.2% | Rs 2,35,719 | Rs 1,627 | -Rs 1,239 | -Rs 20,395 | Rs 47,575 | Rs 1,80,880 | - |
| 1 | E8 PDR | 1133 | 50.7% | 43.3% | Rs 2,58,463 | Rs 1,758 | -Rs 1,343 | -Rs 20,706 | Rs 67,706 | Rs 2,09,330 | - |
| 1 | E9 ATRHOT | 1502 | 50.1% | 44.0% | Rs 3,06,165 | Rs 1,893 | -Rs 1,490 | -Rs 25,837 | Rs 75,672 | Rs 2,49,711 | - |
| 1 | E10 VIXWAY | 1544 | 47.1% | 43.8% | Rs 1,60,727 | Rs 1,721 | -Rs 1,339 | -Rs 26,196 | Rs 1,10,129 | Rs 1,04,273 | - |
| 1 | E11 BREADTH5 | 1649 | 48.1% | 44.3% | Rs 1,83,041 | Rs 1,635 | -Rs 1,301 | -Rs 34,360 | Rs 97,717 | Rs 1,27,428 | - |
| 1 | E12 NOLUNCH | 1768 | 50.3% | 44.5% | Rs 3,09,904 | Rs 1,694 | -Rs 1,360 | -Rs 30,159 | Rs 78,107 | Rs 2,58,176 | - |
| 1 | E13 PASTHOUR | 1653 | 49.4% | 44.7% | Rs 2,37,201 | Rs 1,691 | -Rs 1,366 | -Rs 29,891 | Rs 76,953 | Rs 1,82,865 | - |
| 1 | C1 COMBO | 1015 | 49.7% | 42.6% | Rs 2,32,195 | Rs 1,859 | -Rs 1,380 | -Rs 26,406 | Rs 74,064 | Rs 1,83,061 | - |
| 2 | B0 | 2238 | 49.5% | 43.6% | Rs 7,75,437 | Rs 3,350 | -Rs 2,594 | -Rs 51,084 | Rs 1,64,515 | Rs 6,62,293 | - |
| 2 | E1 STRONGCLOSE | 1889 | 48.5% | 43.1% | Rs 6,01,398 | Rs 3,354 | -Rs 2,539 | -Rs 61,894 | Rs 1,98,140 | Rs 4,88,254 | - |
| 2 | E2 HOLD1 | 1931 | 47.5% | 44.4% | Rs 3,48,044 | Rs 3,192 | -Rs 2,543 | -Rs 74,186 | Rs 2,55,221 | Rs 2,45,715 | - |
| 2 | E3 BIGBAR | 2031 | 48.9% | 43.8% | Rs 6,22,162 | Rs 3,383 | -Rs 2,637 | -Rs 60,368 | Rs 2,07,781 | Rs 5,09,288 | - |
| 2 | E4 FAR | 2058 | 49.9% | 44.0% | Rs 7,27,426 | Rs 3,380 | -Rs 2,656 | -Rs 55,480 | Rs 1,47,201 | Rs 6,17,295 | - |
| 2 | E5 SQUEEZE | 1472 | 47.7% | 44.3% | Rs 2,68,797 | Rs 3,029 | -Rs 2,412 | -Rs 52,619 | Rs 1,38,133 | Rs 1,63,358 | - |
| 2 | E6 HTF15 | 1786 | 48.2% | 44.2% | Rs 4,17,694 | Rs 3,262 | -Rs 2,585 | -Rs 45,984 | Rs 1,81,435 | Rs 3,04,551 | - |
| 2 | E7 HTF60 | 1327 | 49.7% | 42.7% | Rs 5,32,846 | Rs 3,280 | -Rs 2,447 | -Rs 40,459 | Rs 91,681 | Rs 4,22,932 | - |
| 2 | E8 PDR | 1133 | 50.8% | 42.7% | Rs 5,69,456 | Rs 3,550 | -Rs 2,648 | -Rs 41,035 | Rs 1,22,851 | Rs 4,70,952 | - |
| 2 | E9 ATRHOT | 1502 | 50.6% | 43.9% | Rs 6,81,908 | Rs 3,792 | -Rs 2,965 | -Rs 51,084 | Rs 1,43,296 | Rs 5,68,764 | - |
| 2 | E10 VIXWAY | 1544 | 47.7% | 43.5% | Rs 3,92,961 | Rs 3,451 | -Rs 2,657 | -Rs 51,987 | Rs 2,00,225 | Rs 2,79,818 | - |
| 2 | E11 BREADTH5 | 1649 | 48.5% | 43.9% | Rs 4,42,610 | Rs 3,287 | -Rs 2,576 | -Rs 68,366 | Rs 1,80,565 | Rs 3,31,148 | - |
| 2 | E12 NOLUNCH | 1768 | 50.7% | 44.2% | Rs 7,01,655 | Rs 3,407 | -Rs 2,696 | -Rs 59,677 | Rs 1,40,267 | Rs 5,97,961 | - |
| 2 | E13 PASTHOUR | 1653 | 49.7% | 44.3% | Rs 5,51,160 | Rs 3,404 | -Rs 2,704 | -Rs 59,452 | Rs 1,40,270 | Rs 4,42,252 | - |
| 2 | C1 COMBO | 1015 | 49.9% | 42.1% | Rs 5,11,426 | Rs 3,750 | -Rs 2,724 | -Rs 52,435 | Rs 1,37,413 | Rs 4,12,923 | - |
| 3 | B0 | 2238 | 49.7% | 43.6% | Rs 12,13,421 | Rs 5,026 | -Rs 3,885 | -Rs 76,355 | Rs 2,42,618 | Rs 10,43,587 | - |
| 3 | E1 STRONGCLOSE | 1889 | 48.8% | 43.1% | Rs 9,44,482 | Rs 5,021 | -Rs 3,810 | -Rs 92,651 | Rs 2,91,756 | Rs 7,74,648 | - |
| 3 | E2 HOLD1 | 1931 | 47.7% | 44.3% | Rs 5,65,597 | Rs 4,789 | -Rs 3,807 | -Rs 1,10,968 | Rs 3,65,129 | Rs 4,12,006 | - |
| 3 | E3 BIGBAR | 2031 | 49.1% | 43.8% | Rs 9,78,944 | Rs 5,072 | -Rs 3,952 | -Rs 90,142 | Rs 3,05,974 | Rs 8,09,515 | - |
| 3 | E4 FAR | 2058 | 50.0% | 43.9% | Rs 11,37,384 | Rs 5,078 | -Rs 3,973 | -Rs 83,031 | Rs 2,15,146 | Rs 9,72,070 | - |
| 3 | E5 SQUEEZE | 1472 | 47.8% | 44.2% | Rs 4,36,274 | Rs 4,553 | -Rs 3,605 | -Rs 78,531 | Rs 2,02,642 | Rs 2,77,998 | - |
| 3 | E6 HTF15 | 1786 | 48.4% | 44.2% | Rs 6,66,608 | Rs 4,893 | -Rs 3,872 | -Rs 68,786 | Rs 2,62,583 | Rs 4,96,774 | - |
| 3 | E7 HTF60 | 1327 | 49.9% | 42.6% | Rs 8,29,040 | Rs 4,928 | -Rs 3,659 | -Rs 60,552 | Rs 1,35,824 | Rs 6,64,050 | - |
| 3 | E8 PDR | 1133 | 51.2% | 42.8% | Rs 8,79,706 | Rs 5,311 | -Rs 3,979 | -Rs 61,364 | Rs 1,78,277 | Rs 7,31,832 | - |
| 3 | E9 ATRHOT | 1502 | 50.7% | 43.8% | Rs 10,56,566 | Rs 5,697 | -Rs 4,438 | -Rs 76,355 | Rs 2,11,102 | Rs 8,86,732 | - |
| 3 | E10 VIXWAY | 1544 | 47.9% | 43.5% | Rs 6,24,115 | Rs 5,178 | -Rs 3,978 | -Rs 77,778 | Rs 2,90,722 | Rs 4,54,281 | - |
| 3 | E11 BREADTH5 | 1649 | 48.8% | 44.0% | Rs 7,00,961 | Rs 4,923 | -Rs 3,865 | -Rs 1,02,361 | Rs 2,63,617 | Rs 5,33,650 | - |
| 3 | E12 NOLUNCH | 1768 | 50.8% | 44.1% | Rs 10,92,232 | Rs 5,116 | -Rs 4,036 | -Rs 89,205 | Rs 2,02,670 | Rs 9,36,573 | - |
| 3 | E13 PASTHOUR | 1653 | 50.0% | 44.3% | Rs 8,63,903 | Rs 5,104 | -Rs 4,054 | -Rs 89,012 | Rs 2,05,781 | Rs 7,00,423 | - |
| 3 | C1 COMBO | 1015 | 50.1% | 42.1% | Rs 7,89,990 | Rs 5,615 | -Rs 4,087 | -Rs 78,463 | Rs 2,01,037 | Rs 6,42,116 | - |

### R51 Donchian 20 + Supertrend (10, 3), on today's rules

**Walk-forward year (Apr 2025 - Mar 2026) and final (1 Apr - 13 Oct 2026), 1 lot:**

| rule | what it takes | WF trades | WF win (change) | WF net (change) | final trades | final win (change) | final net (change) | p (win / net) | q (m = 2,332) | passes? |
|---|---|---|---|---|---|---|---|---|---|---|
| B0 | today's rules | 3044 | 45.9% | Rs 2,13,748 | 1670 | 44.9% | Rs 1,34,743 | - | - | - |
| E1 STRONGCLOSE | the signal bar closes in its outer 30% | 2752 | 45.2% (-0.6) | Rs 2,07,847 (-Rs 5,901) | 1485 | 44.3% (-0.5) | Rs 88,857 (-Rs 45,886) | 0.708 / 0.709 | 1.000 | no |
| E2 HOLD1 | the next 5-min bar also closes beyond the broken level (enter one bar later) | 2646 | 45.2% (-0.6) | -Rs 174 (-Rs 2,13,922) | 1442 | 45.4% (+0.6) | Rs 1,61,286 (+Rs 26,543) | 0.157 / 0.083 | 1.000 | no |
| E3 BIGBAR | the signal bar is at least 1 ATR tall | 2804 | 45.9% (+0.0) | Rs 2,24,455 (+Rs 10,707) | 1518 | 45.8% (+0.9) | Rs 1,85,250 (+Rs 50,507) | 0.060 / 0.019 | 1.000 | no |
| E4 FAR | the close is at least 0.2 ATR beyond the channel | 2779 | 46.4% (+0.5) | Rs 2,41,467 (+Rs 27,719) | 1523 | 44.5% (-0.4) | Rs 1,23,433 (-Rs 11,310) | 0.597 / 0.318 | 1.000 | no |
| E5 SQUEEZE | the channel was narrow (at or below its 100-bar median) before the break | 2314 | 44.9% (-1.0) | Rs 9,248 (-Rs 2,04,500) | 1285 | 44.1% (-0.7) | Rs 1,62,755 (+Rs 28,012) | 0.573 / 0.023 | 1.000 | no |
| E6 HTF15 | the 15-min Donchian channel agrees (close on the trade's side of its midline) | 2528 | 45.5% (-0.4) | Rs 66,898 (-Rs 1,46,850) | 1416 | 42.2% (-2.6) | -Rs 48,727 (-Rs 1,83,470) | 1.000 / 1.000 | 1.000 | no |
| E7 HTF60 | the 60-min Donchian channel agrees | 1859 | 46.6% (+0.7) | Rs 1,11,979 (-Rs 1,01,769) | 1006 | 41.9% (-3.0) | -Rs 26,307 (-Rs 1,61,050) | 1.000 / 0.999 | 1.000 | no |
| E8 PDR | the break is beyond yesterday's high (call) / low (put) | 1564 | 48.3% (+2.4) | Rs 1,91,038 (-Rs 22,710) | 876 | 42.6% (-2.3) | -Rs 19,846 (-Rs 1,54,589) | 0.973 / 0.990 | 1.000 | no |
| E9 ATRHOT | the 5-min ATR is at or above its usual level for that time of day | 1971 | 47.7% (+1.9) | Rs 2,98,434 (+Rs 84,686) | 941 | 46.4% (+1.6) | Rs 1,07,151 (-Rs 27,592) | 0.026 / 0.246 | 1.000 | no |
| E10 VIXWAY | India VIX moves the trade's way since 09:15 (down for a call, up for a put) | 2067 | 45.3% (-0.6) | Rs 82,666 (-Rs 1,31,082) | 1128 | 45.5% (+0.6) | Rs 1,17,696 (-Rs 17,047) | 0.131 / 0.358 | 1.000 | no |
| E11 BREADTH5 | 4 of the 5 indices are on the trade's side of their day open | 2188 | 46.4% (+0.6) | Rs 1,44,289 (-Rs 69,459) | 1234 | 43.4% (-1.5) | -Rs 53,961 (-Rs 1,88,704) | 0.970 / 1.000 | 1.000 | no |
| E12 NOLUNCH | no entries from 11:30 to 13:29 | 2592 | 46.8% (+0.9) | Rs 1,84,777 (-Rs 28,971) | 1369 | 43.4% (-1.5) | Rs 76,248 (-Rs 58,495) | 0.945 / 0.725 | 1.000 | no |
| E13 PASTHOUR | after 10:15, the break is beyond the first hour's high / low | 2301 | 47.1% (+1.3) | Rs 91,808 (-Rs 1,21,940) | 1295 | 43.6% (-1.2) | Rs 12,237 (-Rs 1,22,506) | 0.934 / 0.996 | 1.000 | no |
| C1 COMBO | the best two of the above, chosen on design only (walk-forward) | 1386 | 48.5% (+2.6) | Rs 1,58,473 (-Rs 55,275) | 758 | 42.7% (-2.1) | Rs 47,471 (-Rs 87,272) | 0.894 / 0.579 | 1.000 | no |

**Final period, every lot size** (random-skip p = the larger of the win and net p, 1,000 twins):

| lots | rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | random-skip p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | B0 | 1670 | 44.9% | 42.0% | Rs 1,34,743 | Rs 1,651 | -Rs 1,197 | -Rs 21,578 | Rs 81,683 | Rs 76,711 | - |
| 1 | E1 STRONGCLOSE | 1485 | 44.3% | 42.1% | Rs 88,857 | Rs 1,574 | -Rs 1,145 | -Rs 18,107 | Rs 56,891 | Rs 30,148 | 0.709 |
| 1 | E2 HOLD1 | 1442 | 45.4% | 41.5% | Rs 1,61,286 | Rs 1,657 | -Rs 1,174 | -Rs 20,558 | Rs 1,00,846 | Rs 95,288 | 0.157 |
| 1 | E3 BIGBAR | 1518 | 45.8% | 41.7% | Rs 1,85,250 | Rs 1,741 | -Rs 1,245 | -Rs 20,638 | Rs 68,772 | Rs 1,17,204 | 0.060 |
| 1 | E4 FAR | 1523 | 44.5% | 41.6% | Rs 1,23,433 | Rs 1,688 | -Rs 1,205 | -Rs 19,821 | Rs 92,735 | Rs 64,724 | 0.597 |
| 1 | E5 SQUEEZE | 1285 | 44.1% | 39.7% | Rs 1,62,755 | Rs 1,730 | -Rs 1,139 | -Rs 17,078 | Rs 57,216 | Rs 96,200 | 0.573 |
| 1 | E6 HTF15 | 1416 | 42.2% | 43.5% | -Rs 48,727 | Rs 1,556 | -Rs 1,197 | -Rs 22,299 | Rs 1,42,301 | -Rs 1,03,320 | 1.000 |
| 1 | E7 HTF60 | 1006 | 41.9% | 42.8% | -Rs 26,307 | Rs 1,528 | -Rs 1,145 | -Rs 19,114 | Rs 1,08,516 | -Rs 79,107 | 1.000 |
| 1 | E8 PDR | 876 | 42.6% | 43.4% | -Rs 19,846 | Rs 1,539 | -Rs 1,181 | -Rs 19,114 | Rs 78,816 | -Rs 73,442 | 0.990 |
| 1 | E9 ATRHOT | 941 | 46.4% | 42.8% | Rs 1,07,151 | Rs 1,788 | -Rs 1,337 | -Rs 18,032 | Rs 56,004 | Rs 40,597 | 0.246 |
| 1 | E10 VIXWAY | 1128 | 45.5% | 41.8% | Rs 1,17,696 | Rs 1,665 | -Rs 1,198 | -Rs 17,702 | Rs 61,988 | Rs 59,664 | 0.358 |
| 1 | E11 BREADTH5 | 1234 | 43.4% | 45.0% | -Rs 53,961 | Rs 1,485 | -Rs 1,214 | -Rs 23,812 | Rs 1,12,308 | -Rs 1,02,833 | 1.000 |
| 1 | E12 NOLUNCH | 1369 | 43.4% | 41.5% | Rs 76,248 | Rs 1,729 | -Rs 1,227 | -Rs 21,311 | Rs 90,739 | Rs 8,625 | 0.945 |
| 1 | E13 PASTHOUR | 1295 | 43.6% | 43.3% | Rs 12,237 | Rs 1,629 | -Rs 1,244 | -Rs 15,996 | Rs 90,546 | -Rs 39,354 | 0.996 |
| 1 | C1 COMBO | 758 | 42.7% | 40.6% | Rs 47,471 | Rs 1,743 | -Rs 1,192 | -Rs 15,309 | Rs 71,962 | -Rs 16,742 | 0.894 |
| 2 | B0 | 1670 | 45.5% | 41.9% | Rs 3,46,548 | Rs 3,301 | -Rs 2,376 | -Rs 42,690 | Rs 1,45,608 | Rs 2,30,248 | - |
| 2 | E1 STRONGCLOSE | 1485 | 45.2% | 42.1% | Rs 2,46,230 | Rs 3,132 | -Rs 2,279 | -Rs 35,411 | Rs 99,864 | Rs 1,28,575 | 0.696 |
| 2 | E2 HOLD1 | 1442 | 46.0% | 41.2% | Rs 3,88,908 | Rs 3,319 | -Rs 2,326 | -Rs 40,291 | Rs 1,80,978 | Rs 2,56,677 | 0.241 |
| 2 | E3 BIGBAR | 1518 | 46.4% | 41.5% | Rs 4,40,502 | Rs 3,483 | -Rs 2,471 | -Rs 40,864 | Rs 1,18,795 | Rs 3,04,175 | 0.113 |
| 2 | E4 FAR | 1523 | 45.1% | 41.5% | Rs 3,17,188 | Rs 3,372 | -Rs 2,392 | -Rs 39,183 | Rs 1,62,467 | Rs 1,99,533 | 0.651 |
| 2 | E5 SQUEEZE | 1285 | 45.0% | 39.7% | Rs 3,84,746 | Rs 3,439 | -Rs 2,268 | -Rs 33,631 | Rs 1,02,919 | Rs 2,51,401 | 0.570 |
| 2 | E6 HTF15 | 1416 | 42.9% | 43.3% | -Rs 32,256 | Rs 3,111 | -Rs 2,374 | -Rs 43,960 | Rs 2,38,382 | -Rs 1,41,676 | 1.000 |
| 2 | E7 HTF60 | 1006 | 42.5% | 42.7% | -Rs 6,343 | Rs 3,052 | -Rs 2,271 | -Rs 37,928 | Rs 1,89,378 | -Rs 1,12,177 | 0.998 |
| 2 | E8 PDR | 876 | 43.6% | 43.6% | Rs 650 | Rs 3,052 | -Rs 2,359 | -Rs 37,928 | Rs 1,46,513 | -Rs 1,06,778 | 0.992 |
| 2 | E9 ATRHOT | 941 | 46.9% | 42.5% | Rs 2,57,771 | Rs 3,589 | -Rs 2,650 | -Rs 35,332 | Rs 99,623 | Rs 1,24,425 | 0.309 |
| 2 | E10 VIXWAY | 1128 | 46.1% | 41.6% | Rs 2,87,495 | Rs 3,332 | -Rs 2,377 | -Rs 35,081 | Rs 1,08,841 | Rs 1,71,195 | 0.419 |
| 2 | E11 BREADTH5 | 1234 | 44.2% | 44.9% | -Rs 50,979 | Rs 2,962 | -Rs 2,417 | -Rs 46,940 | Rs 1,88,923 | -Rs 1,48,959 | 1.000 |
| 2 | E12 NOLUNCH | 1369 | 44.1% | 41.4% | Rs 2,15,758 | Rs 3,448 | -Rs 2,440 | -Rs 41,872 | Rs 1,64,948 | Rs 80,277 | 0.951 |
| 2 | E13 PASTHOUR | 1295 | 44.5% | 43.3% | Rs 84,153 | Rs 3,241 | -Rs 2,479 | -Rs 31,697 | Rs 1,55,108 | -Rs 19,264 | 0.998 |
| 2 | C1 COMBO | 758 | 43.8% | 40.9% | Rs 1,29,923 | Rs 3,449 | -Rs 2,383 | -Rs 30,165 | Rs 1,34,340 | Rs 1,261 | 0.856 |
| 3 | B0 | 1670 | 46.0% | 42.1% | Rs 5,57,695 | Rs 4,922 | -Rs 3,572 | -Rs 63,776 | Rs 2,09,599 | Rs 3,83,127 | - |
| 3 | E1 STRONGCLOSE | 1485 | 45.7% | 42.4% | Rs 4,02,960 | Rs 4,665 | -Rs 3,430 | -Rs 52,715 | Rs 1,46,155 | Rs 2,26,360 | 0.705 |
| 3 | E2 HOLD1 | 1442 | 46.2% | 41.1% | Rs 6,15,918 | Rs 4,979 | -Rs 3,480 | -Rs 60,012 | Rs 2,63,786 | Rs 4,17,462 | 0.309 |
| 3 | E3 BIGBAR | 1518 | 46.8% | 41.6% | Rs 6,95,093 | Rs 5,203 | -Rs 3,712 | -Rs 61,060 | Rs 1,68,974 | Rs 4,90,485 | 0.112 |
| 3 | E4 FAR | 1523 | 45.4% | 41.6% | Rs 5,10,196 | Rs 5,044 | -Rs 3,587 | -Rs 58,515 | Rs 2,32,497 | Rs 3,33,596 | 0.707 |
| 3 | E5 SQUEEZE | 1285 | 45.5% | 39.9% | Rs 6,06,258 | Rs 5,128 | -Rs 3,408 | -Rs 50,163 | Rs 1,49,772 | Rs 4,06,122 | 0.546 |
| 3 | E6 HTF15 | 1416 | 43.2% | 43.4% | -Rs 16,356 | Rs 4,651 | -Rs 3,560 | -Rs 65,610 | Rs 3,34,873 | -Rs 1,80,604 | 1.000 |
| 3 | E7 HTF60 | 1006 | 43.2% | 43.1% | Rs 13,418 | Rs 4,526 | -Rs 3,425 | -Rs 56,727 | Rs 2,70,355 | -Rs 1,45,452 | 0.999 |
| 3 | E8 PDR | 876 | 44.0% | 43.6% | Rs 20,937 | Rs 4,565 | -Rs 3,537 | -Rs 56,727 | Rs 2,15,013 | -Rs 1,40,323 | 0.996 |
| 3 | E9 ATRHOT | 941 | 47.3% | 42.6% | Rs 4,07,937 | Rs 5,358 | -Rs 3,984 | -Rs 52,666 | Rs 1,43,240 | Rs 2,07,801 | 0.316 |
| 3 | E10 VIXWAY | 1128 | 46.6% | 41.9% | Rs 4,56,747 | Rs 4,963 | -Rs 3,578 | -Rs 52,433 | Rs 1,56,081 | Rs 2,82,179 | 0.471 |
| 3 | E11 BREADTH5 | 1234 | 44.4% | 44.9% | -Rs 48,627 | Rs 4,441 | -Rs 3,619 | -Rs 70,074 | Rs 2,76,175 | -Rs 1,95,716 | 1.000 |
| 3 | E12 NOLUNCH | 1369 | 44.6% | 41.6% | Rs 3,54,583 | Rs 5,143 | -Rs 3,666 | -Rs 62,467 | Rs 2,39,205 | Rs 1,51,243 | 0.951 |
| 3 | E13 PASTHOUR | 1295 | 44.9% | 43.5% | Rs 1,55,456 | Rs 4,842 | -Rs 3,722 | -Rs 47,381 | Rs 2,19,858 | Rs 213 | 0.999 |
| 3 | C1 COMBO | 758 | 44.2% | 41.0% | Rs 2,12,081 | Rs 5,149 | -Rs 3,576 | -Rs 45,020 | Rs 1,97,083 | Rs 18,970 | 0.854 |

**Walk-forward year, every lot size:**

| lots | rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | random-skip p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | B0 | 3044 | 45.9% | 43.6% | Rs 2,13,748 | Rs 1,716 | -Rs 1,326 | -Rs 28,942 | Rs 94,176 | Rs 1,45,431 | - |
| 1 | E1 STRONGCLOSE | 2752 | 45.2% | 42.7% | Rs 2,07,847 | Rs 1,685 | -Rs 1,254 | -Rs 31,278 | Rs 93,336 | Rs 1,36,828 | - |
| 1 | E2 HOLD1 | 2646 | 45.2% | 45.2% | -Rs 174 | Rs 1,564 | -Rs 1,292 | -Rs 29,882 | Rs 1,53,231 | -Rs 54,136 | - |
| 1 | E3 BIGBAR | 2804 | 45.9% | 43.3% | Rs 2,24,455 | Rs 1,733 | -Rs 1,324 | -Rs 26,170 | Rs 83,412 | Rs 1,56,138 | - |
| 1 | E4 FAR | 2779 | 46.4% | 43.5% | Rs 2,41,467 | Rs 1,716 | -Rs 1,321 | -Rs 24,745 | Rs 73,439 | Rs 1,76,045 | - |
| 1 | E5 SQUEEZE | 2314 | 44.9% | 44.7% | Rs 9,248 | Rs 1,501 | -Rs 1,214 | -Rs 23,760 | Rs 1,08,797 | -Rs 43,457 | - |
| 1 | E6 HTF15 | 2528 | 45.5% | 44.6% | Rs 66,898 | Rs 1,657 | -Rs 1,332 | -Rs 23,789 | Rs 1,16,370 | Rs 5,214 | - |
| 1 | E7 HTF60 | 1859 | 46.6% | 44.5% | Rs 1,11,979 | Rs 1,584 | -Rs 1,268 | -Rs 17,003 | Rs 95,559 | Rs 57,652 | - |
| 1 | E8 PDR | 1564 | 48.3% | 44.3% | Rs 1,91,038 | Rs 1,718 | -Rs 1,368 | -Rs 23,321 | Rs 59,586 | Rs 1,39,546 | - |
| 1 | E9 ATRHOT | 1971 | 47.7% | 43.2% | Rs 2,98,434 | Rs 1,911 | -Rs 1,456 | -Rs 28,942 | Rs 53,132 | Rs 2,30,117 | - |
| 1 | E10 VIXWAY | 2067 | 45.3% | 44.0% | Rs 82,666 | Rs 1,718 | -Rs 1,349 | -Rs 28,942 | Rs 1,16,389 | Rs 20,981 | - |
| 1 | E11 BREADTH5 | 2188 | 46.4% | 44.2% | Rs 1,44,289 | Rs 1,678 | -Rs 1,332 | -Rs 35,884 | Rs 85,085 | Rs 75,971 | - |
| 1 | E12 NOLUNCH | 2592 | 46.8% | 44.5% | Rs 1,84,777 | Rs 1,725 | -Rs 1,381 | -Rs 33,789 | Rs 90,909 | Rs 1,24,577 | - |
| 1 | E13 PASTHOUR | 2301 | 47.1% | 45.9% | Rs 91,808 | Rs 1,690 | -Rs 1,432 | -Rs 32,923 | Rs 85,703 | Rs 29,001 | - |
| 1 | C1 COMBO | 1386 | 48.5% | 44.8% | Rs 1,58,473 | Rs 1,728 | -Rs 1,404 | -Rs 31,082 | Rs 59,773 | Rs 1,08,003 | - |
| 2 | B0 | 3044 | 46.3% | 43.2% | Rs 5,68,032 | Rs 3,449 | -Rs 2,625 | -Rs 57,260 | Rs 1,79,011 | Rs 4,31,161 | - |
| 2 | E1 STRONGCLOSE | 2752 | 45.6% | 42.2% | Rs 5,42,579 | Rs 3,389 | -Rs 2,478 | -Rs 61,937 | Rs 1,74,313 | Rs 4,00,310 | - |
| 2 | E2 HOLD1 | 2646 | 45.9% | 45.1% | Rs 1,21,797 | Rs 3,131 | -Rs 2,569 | -Rs 59,174 | Rs 2,90,946 | Rs 13,641 | - |
| 2 | E3 BIGBAR | 2804 | 46.3% | 42.9% | Rs 5,78,402 | Rs 3,485 | -Rs 2,619 | -Rs 51,533 | Rs 1,56,788 | Rs 4,41,531 | - |
| 2 | E4 FAR | 2779 | 46.9% | 43.3% | Rs 6,11,243 | Rs 3,439 | -Rs 2,622 | -Rs 48,900 | Rs 1,39,335 | Rs 4,80,168 | - |
| 2 | E5 SQUEEZE | 2314 | 45.3% | 44.3% | Rs 1,25,058 | Rs 3,016 | -Rs 2,402 | -Rs 47,174 | Rs 2,00,644 | Rs 19,411 | - |
| 2 | E6 HTF15 | 2528 | 45.7% | 44.1% | Rs 2,50,399 | Rs 3,341 | -Rs 2,632 | -Rs 46,930 | Rs 2,21,080 | Rs 1,26,794 | - |
| 2 | E7 HTF60 | 1859 | 46.8% | 43.9% | Rs 3,09,678 | Rs 3,199 | -Rs 2,501 | -Rs 33,722 | Rs 1,82,096 | Rs 2,00,789 | - |
| 2 | E8 PDR | 1564 | 48.6% | 43.9% | Rs 4,54,478 | Rs 3,460 | -Rs 2,706 | -Rs 46,200 | Rs 1,04,591 | Rs 3,51,258 | - |
| 2 | E9 ATRHOT | 1971 | 48.1% | 42.9% | Rs 6,87,863 | Rs 3,840 | -Rs 2,886 | -Rs 57,260 | Rs 1,03,124 | Rs 5,50,992 | - |
| 2 | E10 VIXWAY | 2067 | 45.6% | 43.6% | Rs 2,60,710 | Rs 3,456 | -Rs 2,668 | -Rs 57,260 | Rs 2,00,807 | Rs 1,37,105 | - |
| 2 | E11 BREADTH5 | 2188 | 46.8% | 43.8% | Rs 3,89,667 | Rs 3,380 | -Rs 2,633 | -Rs 71,197 | Rs 1,62,377 | Rs 2,52,796 | - |
| 2 | E12 NOLUNCH | 2592 | 47.1% | 44.0% | Rs 4,89,251 | Rs 3,474 | -Rs 2,732 | -Rs 67,119 | Rs 1,72,871 | Rs 3,68,615 | - |
| 2 | E13 PASTHOUR | 2301 | 47.5% | 45.4% | Rs 2,89,964 | Rs 3,404 | -Rs 2,835 | -Rs 65,481 | Rs 1,59,208 | Rs 1,64,114 | - |
| 2 | C1 COMBO | 1386 | 48.9% | 44.5% | Rs 3,81,167 | Rs 3,471 | -Rs 2,786 | -Rs 61,880 | Rs 1,14,995 | Rs 2,79,990 | - |
| 3 | B0 | 3044 | 46.4% | 43.1% | Rs 9,20,100 | Rs 5,181 | -Rs 3,924 | -Rs 85,577 | Rs 2,63,933 | Rs 7,14,697 | - |
| 3 | E1 STRONGCLOSE | 2752 | 45.8% | 42.2% | Rs 8,75,386 | Rs 5,085 | -Rs 3,708 | -Rs 92,593 | Rs 2,58,317 | Rs 6,61,886 | - |
| 3 | E2 HOLD1 | 2646 | 46.2% | 45.1% | Rs 2,41,943 | Rs 4,688 | -Rs 3,853 | -Rs 88,499 | Rs 4,28,930 | Rs 79,612 | - |
| 3 | E3 BIGBAR | 2804 | 46.4% | 42.8% | Rs 9,30,427 | Rs 5,233 | -Rs 3,917 | -Rs 76,875 | Rs 2,30,269 | Rs 7,25,024 | - |
| 3 | E4 FAR | 2779 | 47.0% | 43.1% | Rs 9,79,338 | Rs 5,173 | -Rs 3,916 | -Rs 73,043 | Rs 2,05,187 | Rs 7,82,607 | - |
| 3 | E5 SQUEEZE | 2314 | 45.6% | 44.3% | Rs 2,39,296 | Rs 4,525 | -Rs 3,595 | -Rs 70,572 | Rs 2,92,835 | Rs 80,707 | - |
| 3 | E6 HTF15 | 2528 | 45.9% | 44.0% | Rs 4,31,959 | Rs 5,016 | -Rs 3,938 | -Rs 70,064 | Rs 3,25,928 | Rs 2,46,434 | - |
| 3 | E7 HTF60 | 1859 | 46.9% | 43.7% | Rs 5,06,122 | Rs 4,815 | -Rs 3,733 | -Rs 50,478 | Rs 2,68,692 | Rs 3,42,670 | - |
| 3 | E8 PDR | 1564 | 48.8% | 43.8% | Rs 7,16,856 | Rs 5,193 | -Rs 4,052 | -Rs 69,064 | Rs 1,49,764 | Rs 5,61,907 | - |
| 3 | E9 ATRHOT | 1971 | 48.2% | 42.8% | Rs 10,75,725 | Rs 5,771 | -Rs 4,316 | -Rs 85,577 | Rs 1,53,132 | Rs 8,70,322 | - |
| 3 | E10 VIXWAY | 2067 | 45.8% | 43.5% | Rs 4,37,386 | Rs 5,190 | -Rs 3,990 | -Rs 85,577 | Rs 2,85,459 | Rs 2,51,862 | - |
| 3 | E11 BREADTH5 | 2188 | 46.9% | 43.7% | Rs 6,33,356 | Rs 5,078 | -Rs 3,938 | -Rs 1,06,488 | Rs 2,39,957 | Rs 4,27,953 | - |
| 3 | E12 NOLUNCH | 2592 | 47.2% | 43.9% | Rs 7,91,841 | Rs 5,216 | -Rs 4,089 | -Rs 1,00,452 | Rs 2,54,975 | Rs 6,10,791 | - |
| 3 | E13 PASTHOUR | 2301 | 47.6% | 45.3% | Rs 4,86,528 | Rs 5,115 | -Rs 4,241 | -Rs 98,033 | Rs 2,35,403 | Rs 2,97,656 | - |
| 3 | C1 COMBO | 1386 | 49.1% | 44.5% | Rs 6,03,011 | Rs 5,207 | -Rs 4,174 | -Rs 92,678 | Rs 1,72,135 | Rs 4,51,128 | - |

### R72 Breadth Donchian, on top of R151's live picker

**Walk-forward year (Apr 2025 - Mar 2026) and final (1 Apr - 13 Oct 2026), 1 lot:**

| rule | what it takes | WF trades | WF win (change) | WF net (change) | final trades | final win (change) | final net (change) | p (win / net) | q (m = 2,332) | passes? |
|---|---|---|---|---|---|---|---|---|---|---|
| picker | today's rules + picker | 578 | 52.4% | Rs 1,57,192 | 603 | 49.1% | Rs 1,69,674 | - | - | - |
| E1 STRONGCLOSE | the signal bar closes in its outer 30% | 459 | 51.0% (-1.4) | Rs 1,16,914 (-Rs 40,278) | 466 | 48.1% (-1.0) | Rs 1,46,641 (-Rs 23,033) | 0.532 / 0.313 | 1.000 | no |
| E2 HOLD1 | the next 5-min bar also closes beyond the broken level (enter one bar later) | 480 | 49.4% (-3.0) | Rs 99,132 (-Rs 58,060) | 505 | 45.5% (-3.5) | Rs 1,13,373 (-Rs 56,301) | 0.993 / 0.820 | 1.000 | no |
| E3 BIGBAR | the signal bar is at least 1 ATR tall | 535 | 52.3% (-0.1) | Rs 1,53,501 (-Rs 3,691) | 558 | 47.5% (-1.6) | Rs 1,57,695 (-Rs 11,979) | 0.761 / 0.166 | 1.000 | no |
| E4 FAR | the close is at least 0.2 ATR beyond the channel | 549 | 53.0% (+0.6) | Rs 1,58,990 (+Rs 1,798) | 578 | 47.8% (-1.3) | Rs 1,26,499 (-Rs 43,175) | 0.786 / 0.812 | 1.000 | no |
| E5 SQUEEZE | the channel was narrow (at or below its 100-bar median) before the break | 320 | 55.0% (+2.6) | Rs 1,17,646 (-Rs 39,546) | 407 | 50.1% (+1.0) | Rs 1,81,208 (+Rs 11,534) | 0.098 / 0.004 | 1.000 | no |
| E6 HTF15 | the 15-min Donchian channel agrees (close on the trade's side of its midline) | 419 | 50.6% (-1.8) | Rs 1,30,078 (-Rs 27,114) | 408 | 43.9% (-5.2) | Rs 56,036 (-Rs 1,13,638) | 1.000 / 1.000 | 1.000 | no |
| E7 HTF60 | the 60-min Donchian channel agrees | 353 | 54.7% (+2.2) | Rs 1,79,549 (+Rs 22,357) | 336 | 47.9% (-1.2) | Rs 86,407 (-Rs 83,267) | 0.426 / 0.745 | 1.000 | no |
| E8 PDR | the break is beyond yesterday's high (call) / low (put) | 342 | 54.7% (+2.3) | Rs 1,59,800 (+Rs 2,608) | 323 | 45.2% (-3.9) | Rs 54,236 (-Rs 1,15,438) | 0.922 / 0.937 | 1.000 | no |
| E9 ATRHOT | the 5-min ATR is at or above its usual level for that time of day | 479 | 51.6% (-0.8) | Rs 1,39,070 (-Rs 18,122) | 390 | 49.5% (+0.4) | Rs 2,04,911 (+Rs 35,237) | 0.128 / 0.003 | 1.000 | no |
| E10 VIXWAY | India VIX moves the trade's way since 09:15 (down for a call, up for a put) | 369 | 48.0% (-4.4) | Rs 72,360 (-Rs 84,832) | 394 | 47.5% (-1.6) | Rs 1,17,471 (-Rs 52,203) | 0.644 / 0.585 | 1.000 | no |
| E11 BREADTH5 | 4 of the 5 indices are on the trade's side of their day open | 408 | 49.8% (-2.7) | Rs 59,119 (-Rs 98,073) | 428 | 47.0% (-2.1) | Rs 1,05,353 (-Rs 64,321) | 0.862 / 0.849 | 1.000 | no |
| E12 NOLUNCH | no entries from 11:30 to 13:29 | 452 | 54.4% (+2.0) | Rs 1,33,704 (-Rs 23,488) | 470 | 49.4% (+0.3) | Rs 1,33,132 (-Rs 36,542) | 0.121 / 0.322 | 1.000 | no |
| E13 PASTHOUR | after 10:15, the break is beyond the first hour's high / low | 455 | 51.2% (-1.2) | Rs 1,20,801 (-Rs 36,391) | 500 | 49.4% (+0.3) | Rs 1,31,314 (-Rs 38,360) | 0.143 / 0.473 | 1.000 | no |
| C1 COMBO | the best two of the above, chosen on design only (walk-forward) | 316 | 55.1% (+2.6) | Rs 1,58,527 (+Rs 1,335) | 226 | 47.3% (-1.7) | Rs 1,12,370 (-Rs 57,304) | 0.433 / 0.052 | 1.000 | no |

**Final period, every lot size** (random-skip p = the larger of the win and net p, 1,000 twins):

| lots | rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | random-skip p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | picker | 603 | 49.1% | 40.3% | Rs 1,69,674 | Rs 1,920 | -Rs 1,298 | -Rs 21,301 | Rs 31,620 | Rs 1,13,387 | - |
| 1 | E1 STRONGCLOSE | 466 | 48.1% | 39.0% | Rs 1,46,641 | Rs 2,111 | -Rs 1,348 | -Rs 23,342 | Rs 56,832 | Rs 80,086 | 0.532 |
| 1 | E2 HOLD1 | 505 | 45.5% | 38.2% | Rs 1,13,373 | Rs 1,881 | -Rs 1,161 | -Rs 18,083 | Rs 47,343 | Rs 64,054 | 0.993 |
| 1 | E3 BIGBAR | 558 | 47.5% | 39.6% | Rs 1,57,695 | Rs 2,151 | -Rs 1,407 | -Rs 26,501 | Rs 82,485 | Rs 91,140 | 0.761 |
| 1 | E4 FAR | 578 | 47.8% | 41.2% | Rs 1,26,499 | Rs 1,961 | -Rs 1,373 | -Rs 27,079 | Rs 74,664 | Rs 70,212 | 0.812 |
| 1 | E5 SQUEEZE | 407 | 50.1% | 37.3% | Rs 1,81,208 | Rs 2,180 | -Rs 1,298 | -Rs 17,520 | Rs 39,947 | Rs 1,14,653 | 0.098 |
| 1 | E6 HTF15 | 408 | 43.9% | 39.8% | Rs 56,036 | Rs 2,028 | -Rs 1,341 | -Rs 22,059 | Rs 46,559 | Rs 6,701 | 1.000 |
| 1 | E7 HTF60 | 336 | 47.9% | 39.5% | Rs 86,407 | Rs 1,849 | -Rs 1,207 | -Rs 14,064 | Rs 23,617 | Rs 42,170 | 0.745 |
| 1 | E8 PDR | 323 | 45.2% | 40.0% | Rs 54,236 | Rs 1,926 | -Rs 1,283 | -Rs 14,486 | Rs 60,285 | Rs 8,144 | 0.937 |
| 1 | E9 ATRHOT | 390 | 49.5% | 35.4% | Rs 2,04,911 | Rs 2,408 | -Rs 1,319 | -Rs 21,301 | Rs 26,499 | Rs 1,38,356 | 0.128 |
| 1 | E10 VIXWAY | 394 | 47.5% | 38.7% | Rs 1,17,471 | Rs 2,085 | -Rs 1,316 | -Rs 21,483 | Rs 66,947 | Rs 61,184 | 0.644 |
| 1 | E11 BREADTH5 | 428 | 47.0% | 39.8% | Rs 1,05,353 | Rs 2,065 | -Rs 1,365 | -Rs 21,440 | Rs 61,850 | Rs 38,798 | 0.862 |
| 1 | E12 NOLUNCH | 470 | 49.4% | 40.7% | Rs 1,33,132 | Rs 1,943 | -Rs 1,334 | -Rs 20,218 | Rs 71,933 | Rs 66,577 | 0.322 |
| 1 | E13 PASTHOUR | 500 | 49.4% | 41.4% | Rs 1,31,314 | Rs 1,933 | -Rs 1,368 | -Rs 21,238 | Rs 47,321 | Rs 77,393 | 0.473 |
| 1 | C1 COMBO | 226 | 47.3% | 34.3% | Rs 1,12,370 | Rs 2,510 | -Rs 1,312 | -Rs 14,064 | Rs 28,651 | Rs 51,837 | 0.433 |
| 2 | picker | 603 | 49.2% | 39.8% | Rs 3,67,222 | Rs 3,873 | -Rs 2,559 | -Rs 41,948 | Rs 60,657 | Rs 2,54,425 | - |
| 2 | E1 STRONGCLOSE | 466 | 48.3% | 38.5% | Rs 3,14,767 | Rs 4,249 | -Rs 2,661 | -Rs 45,822 | Rs 1,08,398 | Rs 1,81,422 | 0.537 |
| 2 | E2 HOLD1 | 505 | 45.9% | 37.8% | Rs 2,50,067 | Rs 3,776 | -Rs 2,293 | -Rs 35,700 | Rs 86,701 | Rs 1,51,193 | 0.988 |
| 2 | E3 BIGBAR | 558 | 47.7% | 39.1% | Rs 3,41,181 | Rs 4,332 | -Rs 2,778 | -Rs 52,347 | Rs 1,54,966 | Rs 2,07,836 | 0.745 |
| 2 | E4 FAR | 578 | 47.9% | 40.7% | Rs 2,79,758 | Rs 3,955 | -Rs 2,710 | -Rs 53,463 | Rs 1,34,859 | Rs 1,66,960 | 0.820 |
| 2 | E5 SQUEEZE | 407 | 50.4% | 36.9% | Rs 3,81,236 | Rs 4,385 | -Rs 2,563 | -Rs 34,662 | Rs 72,928 | Rs 2,47,891 | 0.063 |
| 2 | E6 HTF15 | 408 | 44.1% | 39.4% | Rs 1,30,885 | Rs 4,081 | -Rs 2,648 | -Rs 43,357 | Rs 85,528 | Rs 31,992 | 1.000 |
| 2 | E7 HTF60 | 336 | 47.9% | 38.8% | Rs 1,88,319 | Rs 3,745 | -Rs 2,369 | -Rs 27,904 | Rs 46,072 | Rs 99,621 | 0.748 |
| 2 | E8 PDR | 323 | 45.5% | 39.6% | Rs 1,23,412 | Rs 3,873 | -Rs 2,534 | -Rs 28,648 | Rs 1,13,262 | Rs 31,004 | 0.945 |
| 2 | E9 ATRHOT | 390 | 49.5% | 34.8% | Rs 4,27,948 | Rs 4,862 | -Rs 2,591 | -Rs 41,948 | Rs 47,935 | Rs 2,94,602 | 0.155 |
| 2 | E10 VIXWAY | 394 | 47.7% | 38.3% | Rs 2,53,107 | Rs 4,195 | -Rs 2,600 | -Rs 42,335 | Rs 1,23,697 | Rs 1,40,310 | 0.657 |
| 2 | E11 BREADTH5 | 428 | 47.4% | 39.6% | Rs 2,30,511 | Rs 4,137 | -Rs 2,708 | -Rs 42,226 | Rs 1,15,302 | Rs 97,166 | 0.862 |
| 2 | E12 NOLUNCH | 470 | 49.8% | 40.4% | Rs 2,88,010 | Rs 3,899 | -Rs 2,646 | -Rs 39,829 | Rs 1,34,511 | Rs 1,54,664 | 0.352 |
| 2 | E13 PASTHOUR | 500 | 49.6% | 40.9% | Rs 2,85,711 | Rs 3,896 | -Rs 2,701 | -Rs 41,898 | Rs 85,991 | Rs 1,77,634 | 0.474 |
| 2 | C1 COMBO | 226 | 47.3% | 33.7% | Rs 2,35,264 | Rs 5,066 | -Rs 2,578 | -Rs 27,904 | Rs 53,215 | Rs 1,13,962 | 0.475 |
| 3 | picker | 603 | 49.6% | 39.9% | Rs 5,64,570 | Rs 5,794 | -Rs 3,841 | -Rs 62,591 | Rs 89,763 | Rs 3,95,255 | - |
| 3 | E1 STRONGCLOSE | 466 | 48.7% | 38.7% | Rs 4,82,815 | Rs 6,341 | -Rs 4,002 | -Rs 68,303 | Rs 1,59,996 | Rs 2,82,679 | 0.492 |
| 3 | E2 HOLD1 | 505 | 46.3% | 37.9% | Rs 3,86,587 | Rs 5,638 | -Rs 3,441 | -Rs 53,362 | Rs 1,26,094 | Rs 2,38,158 | 0.982 |
| 3 | E3 BIGBAR | 558 | 48.0% | 39.2% | Rs 5,24,474 | Rs 6,472 | -Rs 4,173 | -Rs 78,191 | Rs 2,27,487 | Rs 3,24,338 | 0.717 |
| 3 | E4 FAR | 578 | 48.1% | 40.6% | Rs 4,32,819 | Rs 5,933 | -Rs 4,056 | -Rs 79,840 | Rs 1,95,196 | Rs 2,63,504 | 0.837 |
| 3 | E5 SQUEEZE | 407 | 50.6% | 36.9% | Rs 5,81,100 | Rs 6,568 | -Rs 3,841 | -Rs 51,805 | Rs 1,05,951 | Rs 3,80,964 | 0.064 |
| 3 | E6 HTF15 | 408 | 44.1% | 39.1% | Rs 2,05,566 | Rs 6,144 | -Rs 3,949 | -Rs 64,634 | Rs 1,24,497 | Rs 57,107 | 1.000 |
| 3 | E7 HTF60 | 336 | 48.5% | 39.1% | Rs 2,90,129 | Rs 5,571 | -Rs 3,572 | -Rs 41,737 | Rs 68,572 | Rs 1,56,965 | 0.755 |
| 3 | E8 PDR | 323 | 45.8% | 39.6% | Rs 1,92,516 | Rs 5,793 | -Rs 3,799 | -Rs 42,806 | Rs 1,66,316 | Rs 53,786 | 0.943 |
| 3 | E9 ATRHOT | 390 | 49.7% | 34.8% | Rs 6,50,802 | Rs 7,278 | -Rs 3,883 | -Rs 62,591 | Rs 70,269 | Rs 4,50,666 | 0.172 |
| 3 | E10 VIXWAY | 394 | 48.2% | 38.5% | Rs 3,88,627 | Rs 6,249 | -Rs 3,915 | -Rs 63,190 | Rs 1,80,545 | Rs 2,19,312 | 0.641 |
| 3 | E11 BREADTH5 | 428 | 47.7% | 39.6% | Rs 3,55,612 | Rs 6,197 | -Rs 4,056 | -Rs 63,009 | Rs 1,68,744 | Rs 1,55,476 | 0.869 |
| 3 | E12 NOLUNCH | 470 | 50.2% | 40.6% | Rs 4,42,725 | Rs 5,821 | -Rs 3,979 | -Rs 59,436 | Rs 1,97,139 | Rs 2,42,589 | 0.344 |
| 3 | E13 PASTHOUR | 500 | 49.8% | 40.9% | Rs 4,39,931 | Rs 5,843 | -Rs 4,044 | -Rs 62,540 | Rs 1,24,635 | Rs 2,77,697 | 0.489 |
| 3 | C1 COMBO | 226 | 47.3% | 33.5% | Rs 3,58,059 | Rs 7,622 | -Rs 3,845 | -Rs 41,737 | Rs 77,836 | Rs 1,75,988 | 0.502 |

**Walk-forward year, every lot size:**

| lots | rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | random-skip p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | picker | 578 | 52.4% | 45.1% | Rs 1,57,192 | Rs 2,037 | -Rs 1,673 | -Rs 21,691 | Rs 51,564 | Rs 1,01,980 | - |
| 1 | E1 STRONGCLOSE | 459 | 51.0% | 44.1% | Rs 1,16,914 | Rs 2,059 | -Rs 1,622 | -Rs 19,039 | Rs 58,997 | Rs 61,703 | - |
| 1 | E2 HOLD1 | 480 | 49.4% | 43.1% | Rs 99,132 | Rs 1,884 | -Rs 1,429 | -Rs 19,168 | Rs 71,322 | Rs 53,074 | - |
| 1 | E3 BIGBAR | 535 | 52.3% | 44.6% | Rs 1,53,501 | Rs 2,051 | -Rs 1,650 | -Rs 21,691 | Rs 51,911 | Rs 98,290 | - |
| 1 | E4 FAR | 549 | 53.0% | 45.2% | Rs 1,58,990 | Rs 2,029 | -Rs 1,673 | -Rs 19,235 | Rs 39,213 | Rs 1,05,700 | - |
| 1 | E5 SQUEEZE | 320 | 55.0% | 44.6% | Rs 1,17,646 | Rs 1,953 | -Rs 1,570 | -Rs 17,710 | Rs 38,819 | Rs 73,564 | - |
| 1 | E6 HTF15 | 419 | 50.6% | 42.2% | Rs 1,30,078 | Rs 2,148 | -Rs 1,572 | -Rs 17,773 | Rs 41,254 | Rs 74,866 | - |
| 1 | E7 HTF60 | 353 | 54.7% | 40.5% | Rs 1,79,549 | Rs 2,134 | -Rs 1,452 | -Rs 17,710 | Rs 23,614 | Rs 1,26,267 | - |
| 1 | E8 PDR | 342 | 54.7% | 42.3% | Rs 1,59,800 | Rs 2,175 | -Rs 1,593 | -Rs 14,009 | Rs 15,802 | Rs 1,17,089 | - |
| 1 | E9 ATRHOT | 479 | 51.6% | 44.3% | Rs 1,39,070 | Rs 2,221 | -Rs 1,765 | -Rs 21,691 | Rs 59,747 | Rs 83,858 | - |
| 1 | E10 VIXWAY | 369 | 48.0% | 42.8% | Rs 72,360 | Rs 2,168 | -Rs 1,621 | -Rs 19,039 | Rs 36,270 | Rs 17,149 | - |
| 1 | E11 BREADTH5 | 408 | 49.8% | 45.8% | Rs 59,119 | Rs 1,962 | -Rs 1,654 | -Rs 19,039 | Rs 50,408 | Rs 6,340 | - |
| 1 | E12 NOLUNCH | 452 | 54.4% | 46.5% | Rs 1,33,704 | Rs 1,985 | -Rs 1,722 | -Rs 19,523 | Rs 33,575 | Rs 91,923 | - |
| 1 | E13 PASTHOUR | 455 | 51.2% | 44.2% | Rs 1,20,801 | Rs 2,115 | -Rs 1,675 | -Rs 19,039 | Rs 44,518 | Rs 68,022 | - |
| 1 | C1 COMBO | 316 | 55.1% | 41.7% | Rs 1,58,527 | Rs 2,194 | -Rs 1,572 | -Rs 14,009 | Rs 21,307 | Rs 1,15,816 | - |
| 2 | picker | 578 | 52.8% | 44.8% | Rs 3,41,010 | Rs 4,093 | -Rs 3,324 | -Rs 42,982 | Rs 97,317 | Rs 2,30,356 | - |
| 2 | E1 STRONGCLOSE | 459 | 51.2% | 43.6% | Rs 2,54,959 | Rs 4,146 | -Rs 3,212 | -Rs 37,856 | Rs 1,13,051 | Rs 1,44,306 | - |
| 2 | E2 HOLD1 | 480 | 50.2% | 43.3% | Rs 2,20,298 | Rs 3,751 | -Rs 2,860 | -Rs 37,699 | Rs 1,37,736 | Rs 1,27,945 | - |
| 2 | E3 BIGBAR | 535 | 52.9% | 44.5% | Rs 3,31,601 | Rs 4,104 | -Rs 3,293 | -Rs 42,982 | Rs 98,147 | Rs 2,20,948 | - |
| 2 | E4 FAR | 549 | 53.4% | 44.9% | Rs 3,43,264 | Rs 4,077 | -Rs 3,325 | -Rs 38,058 | Rs 75,176 | Rs 2,36,455 | - |
| 2 | E5 SQUEEZE | 320 | 55.6% | 44.5% | Rs 2,49,958 | Rs 3,908 | -Rs 3,138 | -Rs 35,256 | Rs 74,466 | Rs 1,61,563 | - |
| 2 | E6 HTF15 | 419 | 50.8% | 41.9% | Rs 2,79,498 | Rs 4,323 | -Rs 3,113 | -Rs 35,393 | Rs 78,072 | Rs 1,68,844 | - |
| 2 | E7 HTF60 | 353 | 55.0% | 40.1% | Rs 3,75,336 | Rs 4,293 | -Rs 2,877 | -Rs 35,256 | Rs 46,468 | Rs 2,68,535 | - |
| 2 | E8 PDR | 342 | 54.7% | 41.7% | Rs 3,35,407 | Rs 4,397 | -Rs 3,140 | -Rs 27,711 | Rs 29,764 | Rs 2,49,750 | - |
| 2 | E9 ATRHOT | 479 | 52.0% | 44.1% | Rs 3,00,232 | Rs 4,453 | -Rs 3,515 | -Rs 42,982 | Rs 1,15,611 | Rs 1,89,578 | - |
| 2 | E10 VIXWAY | 369 | 48.8% | 43.0% | Rs 1,61,684 | Rs 4,308 | -Rs 3,248 | -Rs 37,856 | Rs 70,347 | Rs 51,031 | - |
| 2 | E11 BREADTH5 | 408 | 50.2% | 45.6% | Rs 1,37,065 | Rs 3,931 | -Rs 3,294 | -Rs 37,856 | Rs 97,005 | Rs 31,271 | - |
| 2 | E12 NOLUNCH | 452 | 54.9% | 46.3% | Rs 2,88,232 | Rs 3,985 | -Rs 3,431 | -Rs 38,762 | Rs 65,517 | Rs 2,04,435 | - |
| 2 | E13 PASTHOUR | 455 | 51.6% | 44.0% | Rs 2,62,613 | Rs 4,239 | -Rs 3,335 | -Rs 37,856 | Rs 85,651 | Rs 1,56,819 | - |
| 2 | C1 COMBO | 316 | 55.1% | 41.1% | Rs 3,31,636 | Rs 4,434 | -Rs 3,097 | -Rs 27,711 | Rs 40,907 | Rs 2,45,979 | - |
| 3 | picker | 578 | 53.1% | 45.0% | Rs 5,24,387 | Rs 6,122 | -Rs 5,001 | -Rs 64,270 | Rs 1,43,250 | Rs 3,58,289 | - |
| 3 | E1 STRONGCLOSE | 459 | 51.6% | 43.9% | Rs 3,92,600 | Rs 6,189 | -Rs 4,839 | -Rs 56,666 | Rs 1,67,281 | Rs 2,26,502 | - |
| 3 | E2 HOLD1 | 480 | 50.2% | 43.0% | Rs 3,41,105 | Rs 5,649 | -Rs 4,269 | -Rs 56,245 | Rs 2,04,110 | Rs 2,02,458 | - |
| 3 | E3 BIGBAR | 535 | 53.3% | 44.7% | Rs 5,09,269 | Rs 6,136 | -Rs 4,958 | -Rs 64,270 | Rs 1,44,529 | Rs 3,43,171 | - |
| 3 | E4 FAR | 549 | 53.7% | 45.1% | Rs 5,27,092 | Rs 6,097 | -Rs 5,006 | -Rs 56,957 | Rs 1,11,235 | Rs 3,66,761 | - |
| 3 | E5 SQUEEZE | 320 | 55.9% | 44.6% | Rs 3,82,065 | Rs 5,852 | -Rs 4,719 | -Rs 52,789 | Rs 1,10,173 | Rs 2,49,354 | - |
| 3 | E6 HTF15 | 419 | 51.3% | 42.1% | Rs 4,28,598 | Rs 6,447 | -Rs 4,693 | -Rs 52,995 | Rs 1,15,042 | Rs 2,62,500 | - |
| 3 | E7 HTF60 | 353 | 55.2% | 40.2% | Rs 5,70,955 | Rs 6,429 | -Rs 4,321 | -Rs 52,789 | Rs 69,301 | Rs 4,10,636 | - |
| 3 | E8 PDR | 342 | 55.0% | 41.8% | Rs 5,10,862 | Rs 6,583 | -Rs 4,719 | -Rs 41,411 | Rs 44,145 | Rs 3,82,258 | - |
| 3 | E9 ATRHOT | 479 | 52.2% | 44.1% | Rs 4,61,029 | Rs 6,675 | -Rs 5,274 | -Rs 64,270 | Rs 1,71,631 | Rs 2,94,931 | - |
| 3 | E10 VIXWAY | 369 | 49.0% | 43.0% | Rs 2,50,742 | Rs 6,449 | -Rs 4,875 | -Rs 56,666 | Rs 1,04,483 | Rs 84,644 | - |
| 3 | E11 BREADTH5 | 408 | 50.7% | 45.9% | Rs 2,14,699 | Rs 5,862 | -Rs 4,969 | -Rs 56,666 | Rs 1,43,710 | Rs 55,889 | - |
| 3 | E12 NOLUNCH | 452 | 55.1% | 46.3% | Rs 4,42,381 | Rs 5,976 | -Rs 5,151 | -Rs 58,011 | Rs 97,498 | Rs 3,16,568 | - |
| 3 | E13 PASTHOUR | 455 | 51.9% | 44.0% | Rs 4,04,015 | Rs 6,355 | -Rs 5,003 | -Rs 56,666 | Rs 1,26,937 | Rs 2,45,206 | - |
| 3 | C1 COMBO | 316 | 55.4% | 41.2% | Rs 5,04,592 | Rs 6,635 | -Rs 4,657 | -Rs 41,411 | Rs 60,542 | Rs 3,75,988 | - |

### R51 Donchian 20 + Supertrend (10, 3), on top of R151's live picker

**Walk-forward year (Apr 2025 - Mar 2026) and final (1 Apr - 13 Oct 2026), 1 lot:**

| rule | what it takes | WF trades | WF win (change) | WF net (change) | final trades | final win (change) | final net (change) | p (win / net) | q (m = 2,332) | passes? |
|---|---|---|---|---|---|---|---|---|---|---|
| picker | today's rules + picker | 720 | 49.4% | Rs 2,02,684 | 653 | 50.4% | Rs 1,42,884 | - | - | - |
| E1 STRONGCLOSE | the signal bar closes in its outer 30% | 613 | 47.5% (-2.0) | Rs 1,43,152 (-Rs 59,532) | 481 | 50.9% (+0.6) | Rs 90,110 (-Rs 52,774) | 0.119 / 0.801 | 1.000 | no |
| E2 HOLD1 | the next 5-min bar also closes beyond the broken level (enter one bar later) | 633 | 45.8% (-3.6) | Rs 61,272 (-Rs 1,41,412) | 503 | 45.1% (-5.3) | Rs 71,441 (-Rs 71,443) | 1.000 / 0.927 | 1.000 | no |
| E3 BIGBAR | the signal bar is at least 1 ATR tall | 651 | 51.3% (+1.9) | Rs 2,54,213 (+Rs 51,529) | 568 | 51.6% (+1.2) | Rs 1,98,234 (+Rs 55,350) | 0.032 / 9.8e-05 | 1.000 | no |
| E4 FAR | the close is at least 0.2 ATR beyond the channel | 659 | 49.6% (+0.2) | Rs 1,99,533 (-Rs 3,151) | 574 | 51.0% (+0.7) | Rs 1,65,365 (+Rs 22,481) | 0.088 / 0.009 | 1.000 | no |
| E5 SQUEEZE | the channel was narrow (at or below its 100-bar median) before the break | 457 | 52.9% (+3.5) | Rs 1,87,071 (-Rs 15,613) | 416 | 51.7% (+1.3) | Rs 1,68,626 (+Rs 25,742) | 0.045 / 4.4e-04 | 1.000 | no |
| E6 HTF15 | the 15-min Donchian channel agrees (close on the trade's side of its midline) | 569 | 47.8% (-1.6) | Rs 1,62,162 (-Rs 40,522) | 445 | 48.1% (-2.3) | Rs 77,647 (-Rs 65,237) | 0.977 / 0.979 | 1.000 | no |
| E7 HTF60 | the 60-min Donchian channel agrees | 494 | 47.6% (-1.9) | Rs 1,11,320 (-Rs 91,364) | 371 | 50.7% (+0.3) | Rs 1,02,110 (-Rs 40,774) | 0.110 / 0.335 | 1.000 | no |
| E8 PDR | the break is beyond yesterday's high (call) / low (put) | 436 | 50.5% (+1.0) | Rs 1,95,217 (-Rs 7,467) | 365 | 48.8% (-1.6) | Rs 53,812 (-Rs 89,072) | 0.516 / 0.828 | 1.000 | no |
| E9 ATRHOT | the 5-min ATR is at or above its usual level for that time of day | 476 | 51.3% (+1.8) | Rs 2,14,343 (+Rs 11,659) | 386 | 52.8% (+2.5) | Rs 1,32,863 (-Rs 10,021) | 0.010 / 0.029 | 1.000 | no |
| E10 VIXWAY | India VIX moves the trade's way since 09:15 (down for a call, up for a put) | 443 | 49.2% (-0.2) | Rs 1,32,493 (-Rs 70,191) | 464 | 52.2% (+1.8) | Rs 1,59,777 (+Rs 16,893) | 0.021 / 0.020 | 1.000 | no |
| E11 BREADTH5 | 4 of the 5 indices are on the trade's side of their day open | 457 | 48.6% (-0.9) | Rs 1,04,682 (-Rs 98,002) | 449 | 47.0% (-3.4) | Rs 17,034 (-Rs 1,25,850) | 0.986 / 1.000 | 1.000 | no |
| E12 NOLUNCH | no entries from 11:30 to 13:29 | 613 | 50.4% (+1.0) | Rs 1,74,112 (-Rs 28,572) | 555 | 51.0% (+0.6) | Rs 1,76,791 (+Rs 33,907) | 0.101 / 0.003 | 1.000 | no |
| E13 PASTHOUR | after 10:15, the break is beyond the first hour's high / low | 604 | 48.7% (-0.8) | Rs 1,31,256 (-Rs 71,428) | 557 | 49.5% (-0.8) | Rs 1,44,964 (+Rs 2,080) | 0.437 / 0.061 | 1.000 | no |
| C1 COMBO | the best two of the above, chosen on design only (walk-forward) | 410 | 51.9% (+2.5) | Rs 1,91,667 (-Rs 11,017) | 341 | 51.0% (+0.6) | Rs 1,04,818 (-Rs 38,066) | 0.117 / 0.071 | 1.000 | no |

**Final period, every lot size** (random-skip p = the larger of the win and net p, 1,000 twins):

| lots | rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | random-skip p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | picker | 653 | 50.4% | 43.2% | Rs 1,42,884 | Rs 1,733 | -Rs 1,319 | -Rs 14,642 | Rs 38,799 | Rs 1,03,038 | - |
| 1 | E1 STRONGCLOSE | 481 | 50.9% | 44.4% | Rs 90,110 | Rs 1,582 | -Rs 1,261 | -Rs 9,276 | Rs 25,312 | Rs 51,871 | 0.801 |
| 1 | E2 HOLD1 | 503 | 45.1% | 40.2% | Rs 71,441 | Rs 1,739 | -Rs 1,171 | -Rs 13,436 | Rs 42,746 | Rs 24,865 | 1.000 |
| 1 | E3 BIGBAR | 568 | 51.6% | 41.0% | Rs 1,98,234 | Rs 1,946 | -Rs 1,352 | -Rs 15,067 | Rs 27,454 | Rs 1,43,524 | 0.032 |
| 1 | E4 FAR | 574 | 51.0% | 41.6% | Rs 1,65,365 | Rs 1,787 | -Rs 1,275 | -Rs 13,928 | Rs 22,953 | Rs 1,25,520 | 0.088 |
| 1 | E5 SQUEEZE | 416 | 51.7% | 39.7% | Rs 1,68,626 | Rs 2,037 | -Rs 1,339 | -Rs 13,928 | Rs 20,722 | Rs 1,13,916 | 0.045 |
| 1 | E6 HTF15 | 445 | 48.1% | 42.2% | Rs 77,647 | Rs 1,703 | -Rs 1,242 | -Rs 14,642 | Rs 37,109 | Rs 39,049 | 0.979 |
| 1 | E7 HTF60 | 371 | 50.7% | 41.2% | Rs 1,02,110 | Rs 1,708 | -Rs 1,197 | -Rs 13,348 | Rs 27,104 | Rs 64,401 | 0.335 |
| 1 | E8 PDR | 365 | 48.8% | 43.6% | Rs 53,812 | Rs 1,619 | -Rs 1,253 | -Rs 13,348 | Rs 41,413 | Rs 16,242 | 0.828 |
| 1 | E9 ATRHOT | 386 | 52.8% | 42.6% | Rs 1,32,863 | Rs 1,935 | -Rs 1,439 | -Rs 14,292 | Rs 28,367 | Rs 78,609 | 0.029 |
| 1 | E10 VIXWAY | 464 | 52.2% | 40.7% | Rs 1,59,777 | Rs 1,783 | -Rs 1,224 | -Rs 14,642 | Rs 38,093 | Rs 1,19,931 | 0.021 |
| 1 | E11 BREADTH5 | 449 | 47.0% | 45.7% | Rs 17,034 | Rs 1,607 | -Rs 1,353 | -Rs 15,450 | Rs 51,950 | -Rs 22,088 | 1.000 |
| 1 | E12 NOLUNCH | 555 | 51.0% | 41.1% | Rs 1,76,791 | Rs 1,903 | -Rs 1,330 | -Rs 15,498 | Rs 42,683 | Rs 1,22,082 | 0.101 |
| 1 | E13 PASTHOUR | 557 | 49.5% | 41.3% | Rs 1,44,964 | Rs 1,845 | -Rs 1,296 | -Rs 14,644 | Rs 32,139 | Rs 95,056 | 0.437 |
| 1 | C1 COMBO | 341 | 51.0% | 41.1% | Rs 1,04,818 | Rs 1,829 | -Rs 1,279 | -Rs 14,064 | Rs 43,223 | Rs 50,713 | 0.117 |
| 2 | picker | 653 | 51.0% | 43.1% | Rs 3,15,804 | Rs 3,471 | -Rs 2,625 | -Rs 28,984 | Rs 73,811 | Rs 2,35,877 | - |
| 2 | E1 STRONGCLOSE | 481 | 51.8% | 44.4% | Rs 2,02,300 | Rs 3,159 | -Rs 2,518 | -Rs 18,080 | Rs 47,947 | Rs 1,25,585 | 0.820 |
| 2 | E2 HOLD1 | 503 | 45.9% | 40.2% | Rs 1,65,839 | Rs 3,463 | -Rs 2,331 | -Rs 26,529 | Rs 80,656 | Rs 72,450 | 0.998 |
| 2 | E3 BIGBAR | 568 | 51.9% | 40.6% | Rs 4,22,619 | Rs 3,911 | -Rs 2,679 | -Rs 29,886 | Rs 52,367 | Rs 3,12,963 | 0.081 |
| 2 | E4 FAR | 574 | 51.4% | 41.2% | Rs 3,57,140 | Rs 3,595 | -Rs 2,521 | -Rs 27,549 | Rs 44,023 | Rs 2,77,213 | 0.156 |
| 2 | E5 SQUEEZE | 416 | 51.9% | 39.2% | Rs 3,56,377 | Rs 4,100 | -Rs 2,646 | -Rs 27,549 | Rs 38,985 | Rs 2,46,722 | 0.085 |
| 2 | E6 HTF15 | 445 | 49.0% | 42.3% | Rs 1,75,699 | Rs 3,389 | -Rs 2,481 | -Rs 28,984 | Rs 71,475 | Rs 98,267 | 0.978 |
| 2 | E7 HTF60 | 371 | 51.2% | 40.9% | Rs 2,21,287 | Rs 3,426 | -Rs 2,374 | -Rs 26,436 | Rs 49,809 | Rs 1,45,639 | 0.338 |
| 2 | E8 PDR | 365 | 49.6% | 43.6% | Rs 1,24,385 | Rs 3,230 | -Rs 2,502 | -Rs 26,436 | Rs 81,459 | Rs 49,009 | 0.844 |
| 2 | E9 ATRHOT | 386 | 53.4% | 42.5% | Rs 2,83,527 | Rs 3,878 | -Rs 2,863 | -Rs 28,407 | Rs 53,968 | Rs 1,74,781 | 0.036 |
| 2 | E10 VIXWAY | 464 | 53.0% | 40.8% | Rs 3,40,945 | Rs 3,555 | -Rs 2,447 | -Rs 28,984 | Rs 72,965 | Rs 2,61,018 | 0.023 |
| 2 | E11 BREADTH5 | 449 | 48.3% | 46.3% | Rs 54,667 | Rs 3,170 | -Rs 2,729 | -Rs 30,398 | Rs 1,00,592 | -Rs 23,812 | 1.000 |
| 2 | E12 NOLUNCH | 555 | 51.9% | 41.3% | Rs 3,79,168 | Rs 3,786 | -Rs 2,664 | -Rs 30,524 | Rs 82,789 | Rs 2,69,512 | 0.082 |
| 2 | E13 PASTHOUR | 557 | 50.1% | 41.0% | Rs 3,15,571 | Rs 3,696 | -Rs 2,574 | -Rs 29,016 | Rs 61,747 | Rs 2,15,519 | 0.569 |
| 2 | C1 COMBO | 341 | 51.9% | 41.2% | Rs 2,25,361 | Rs 3,643 | -Rs 2,558 | -Rs 27,904 | Rs 85,220 | Rs 1,16,913 | 0.124 |
| 3 | picker | 653 | 51.1% | 43.0% | Rs 4,88,424 | Rs 5,213 | -Rs 3,927 | -Rs 43,311 | Rs 1,08,826 | Rs 3,68,416 | - |
| 3 | E1 STRONGCLOSE | 481 | 52.0% | 44.3% | Rs 3,14,244 | Rs 4,742 | -Rs 3,771 | -Rs 26,884 | Rs 70,569 | Rs 1,99,053 | 0.817 |
| 3 | E2 HOLD1 | 503 | 46.3% | 40.4% | Rs 2,59,937 | Rs 5,172 | -Rs 3,501 | -Rs 39,605 | Rs 1,18,600 | Rs 1,19,735 | 1.000 |
| 3 | E3 BIGBAR | 568 | 52.1% | 40.6% | Rs 6,46,704 | Rs 5,870 | -Rs 4,010 | -Rs 44,687 | Rs 77,309 | Rs 4,82,102 | 0.095 |
| 3 | E4 FAR | 574 | 51.6% | 41.1% | Rs 5,48,538 | Rs 5,397 | -Rs 3,773 | -Rs 41,158 | Rs 65,491 | Rs 4,28,529 | 0.158 |
| 3 | E5 SQUEEZE | 416 | 51.9% | 39.0% | Rs 5,43,943 | Rs 6,173 | -Rs 3,947 | -Rs 41,158 | Rs 57,650 | Rs 3,79,342 | 0.107 |
| 3 | E6 HTF15 | 445 | 49.0% | 42.0% | Rs 2,73,525 | Rs 5,106 | -Rs 3,699 | -Rs 43,311 | Rs 1,05,875 | Rs 1,57,267 | 0.983 |
| 3 | E7 HTF60 | 371 | 51.5% | 40.9% | Rs 3,40,291 | Rs 5,135 | -Rs 3,558 | -Rs 39,512 | Rs 72,519 | Rs 2,26,701 | 0.369 |
| 3 | E8 PDR | 365 | 49.9% | 43.6% | Rs 1,94,841 | Rs 4,841 | -Rs 3,750 | -Rs 39,512 | Rs 1,21,492 | Rs 81,659 | 0.819 |
| 3 | E9 ATRHOT | 386 | 53.4% | 42.2% | Rs 4,34,076 | Rs 5,840 | -Rs 4,272 | -Rs 42,525 | Rs 79,531 | Rs 2,70,839 | 0.039 |
| 3 | E10 VIXWAY | 464 | 53.2% | 40.7% | Rs 5,21,901 | Rs 5,333 | -Rs 3,665 | -Rs 43,311 | Rs 1,07,859 | Rs 4,01,892 | 0.014 |
| 3 | E11 BREADTH5 | 449 | 48.5% | 46.2% | Rs 92,152 | Rs 4,755 | -Rs 4,089 | -Rs 45,337 | Rs 1,49,196 | -Rs 25,685 | 1.000 |
| 3 | E12 NOLUNCH | 555 | 52.1% | 41.2% | Rs 5,81,288 | Rs 5,682 | -Rs 3,988 | -Rs 45,551 | Rs 1,22,893 | Rs 4,16,686 | 0.085 |
| 3 | E13 PASTHOUR | 557 | 50.3% | 41.0% | Rs 4,85,904 | Rs 5,546 | -Rs 3,852 | -Rs 43,383 | Rs 91,337 | Rs 3,35,708 | 0.594 |
| 3 | C1 COMBO | 341 | 52.2% | 41.3% | Rs 3,45,729 | Rs 5,456 | -Rs 3,837 | -Rs 41,737 | Rs 1,27,228 | Rs 1,82,949 | 0.100 |

**Walk-forward year, every lot size:**

| lots | rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | random-skip p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | picker | 720 | 49.4% | 41.3% | Rs 2,02,684 | Rs 2,036 | -Rs 1,434 | -Rs 16,716 | Rs 36,167 | Rs 1,39,963 | - |
| 1 | E1 STRONGCLOSE | 613 | 47.5% | 40.6% | Rs 1,43,152 | Rs 2,010 | -Rs 1,372 | -Rs 14,301 | Rs 36,373 | Rs 77,344 | - |
| 1 | E2 HOLD1 | 633 | 45.8% | 42.8% | Rs 61,272 | Rs 1,836 | -Rs 1,374 | -Rs 17,349 | Rs 79,350 | Rs 11,615 | - |
| 1 | E3 BIGBAR | 651 | 51.3% | 40.4% | Rs 2,54,213 | Rs 2,140 | -Rs 1,453 | -Rs 15,556 | Rs 31,970 | Rs 1,91,492 | - |
| 1 | E4 FAR | 659 | 49.6% | 41.0% | Rs 1,99,533 | Rs 2,080 | -Rs 1,448 | -Rs 15,716 | Rs 33,714 | Rs 1,34,111 | - |
| 1 | E5 SQUEEZE | 457 | 52.9% | 40.6% | Rs 1,87,071 | Rs 1,974 | -Rs 1,352 | -Rs 13,632 | Rs 23,881 | Rs 1,38,326 | - |
| 1 | E6 HTF15 | 569 | 47.8% | 39.5% | Rs 1,62,162 | Rs 2,074 | -Rs 1,353 | -Rs 10,749 | Rs 43,708 | Rs 1,01,290 | - |
| 1 | E7 HTF60 | 494 | 47.6% | 40.7% | Rs 1,11,320 | Rs 1,937 | -Rs 1,328 | -Rs 11,753 | Rs 44,992 | Rs 60,017 | - |
| 1 | E8 PDR | 436 | 50.5% | 37.1% | Rs 1,95,217 | Rs 2,112 | -Rs 1,248 | -Rs 10,295 | Rs 19,489 | Rs 1,46,801 | - |
| 1 | E9 ATRHOT | 476 | 51.3% | 39.6% | Rs 2,14,343 | Rs 2,339 | -Rs 1,536 | -Rs 16,716 | Rs 40,269 | Rs 1,51,622 | - |
| 1 | E10 VIXWAY | 443 | 49.2% | 40.9% | Rs 1,32,493 | Rs 2,130 | -Rs 1,475 | -Rs 16,716 | Rs 35,872 | Rs 71,621 | - |
| 1 | E11 BREADTH5 | 457 | 48.6% | 42.0% | Rs 1,04,682 | Rs 2,033 | -Rs 1,475 | -Rs 18,828 | Rs 37,237 | Rs 43,179 | - |
| 1 | E12 NOLUNCH | 613 | 50.4% | 42.2% | Rs 1,74,112 | Rs 1,996 | -Rs 1,456 | -Rs 20,158 | Rs 42,972 | Rs 1,27,243 | - |
| 1 | E13 PASTHOUR | 604 | 48.7% | 42.5% | Rs 1,31,256 | Rs 2,025 | -Rs 1,497 | -Rs 17,400 | Rs 31,804 | Rs 77,668 | - |
| 1 | C1 COMBO | 410 | 51.9% | 38.2% | Rs 1,91,667 | Rs 2,105 | -Rs 1,303 | -Rs 9,615 | Rs 17,898 | Rs 1,45,475 | - |
| 2 | picker | 720 | 50.1% | 41.3% | Rs 4,38,441 | Rs 4,061 | -Rs 2,862 | -Rs 33,231 | Rs 71,261 | Rs 3,12,764 | - |
| 2 | E1 STRONGCLOSE | 613 | 48.0% | 40.4% | Rs 3,14,415 | Rs 4,026 | -Rs 2,725 | -Rs 28,448 | Rs 63,655 | Rs 1,82,570 | - |
| 2 | E2 HOLD1 | 633 | 46.1% | 42.4% | Rs 1,51,624 | Rs 3,693 | -Rs 2,718 | -Rs 34,355 | Rs 1,52,881 | Rs 52,079 | - |
| 2 | E3 BIGBAR | 651 | 51.8% | 40.2% | Rs 5,38,359 | Rs 4,289 | -Rs 2,889 | -Rs 30,734 | Rs 62,997 | Rs 4,12,682 | - |
| 2 | E4 FAR | 659 | 50.2% | 41.0% | Rs 4,29,330 | Rs 4,156 | -Rs 2,885 | -Rs 31,173 | Rs 66,415 | Rs 2,98,255 | - |
| 2 | E5 SQUEEZE | 457 | 53.6% | 40.6% | Rs 3,95,066 | Rs 3,946 | -Rs 2,697 | -Rs 27,064 | Rs 45,583 | Rs 2,97,346 | - |
| 2 | E6 HTF15 | 569 | 48.3% | 39.3% | Rs 3,50,467 | Rs 4,149 | -Rs 2,689 | -Rs 21,320 | Rs 83,813 | Rs 2,28,488 | - |
| 2 | E7 HTF60 | 494 | 48.2% | 40.6% | Rs 2,45,318 | Rs 3,871 | -Rs 2,640 | -Rs 23,353 | Rs 87,065 | Rs 1,42,476 | - |
| 2 | E8 PDR | 436 | 51.1% | 37.1% | Rs 4,10,520 | Rs 4,214 | -Rs 2,484 | -Rs 20,306 | Rs 37,592 | Rs 3,13,452 | - |
| 2 | E9 ATRHOT | 476 | 51.9% | 39.6% | Rs 4,50,563 | Rs 4,667 | -Rs 3,066 | -Rs 33,231 | Rs 77,809 | Rs 3,24,886 | - |
| 2 | E10 VIXWAY | 443 | 49.9% | 40.9% | Rs 2,85,253 | Rs 4,249 | -Rs 2,945 | -Rs 33,231 | Rs 69,008 | Rs 1,63,274 | - |
| 2 | E11 BREADTH5 | 457 | 49.0% | 41.8% | Rs 2,30,328 | Rs 4,076 | -Rs 2,930 | -Rs 37,409 | Rs 67,086 | Rs 1,07,088 | - |
| 2 | E12 NOLUNCH | 613 | 51.1% | 42.1% | Rs 3,76,338 | Rs 3,988 | -Rs 2,906 | -Rs 39,997 | Rs 83,707 | Rs 2,82,364 | - |
| 2 | E13 PASTHOUR | 604 | 49.5% | 42.7% | Rs 2,90,274 | Rs 4,028 | -Rs 2,997 | -Rs 34,375 | Rs 61,763 | Rs 1,82,861 | - |
| 2 | C1 COMBO | 410 | 52.7% | 38.2% | Rs 4,02,229 | Rs 4,197 | -Rs 2,600 | -Rs 18,959 | Rs 34,487 | Rs 3,09,610 | - |
| 3 | picker | 720 | 50.3% | 41.3% | Rs 6,73,625 | Rs 6,097 | -Rs 4,284 | -Rs 49,747 | Rs 1,06,397 | Rs 4,84,991 | - |
| 3 | E1 STRONGCLOSE | 613 | 48.1% | 40.3% | Rs 4,85,253 | Rs 6,041 | -Rs 4,078 | -Rs 42,596 | Rs 91,089 | Rs 2,87,368 | - |
| 3 | E2 HOLD1 | 633 | 46.6% | 42.6% | Rs 2,41,404 | Rs 5,505 | -Rs 4,090 | -Rs 51,344 | Rs 2,26,525 | Rs 91,969 | - |
| 3 | E3 BIGBAR | 651 | 52.1% | 40.3% | Rs 8,21,994 | Rs 6,418 | -Rs 4,339 | -Rs 45,912 | Rs 94,054 | Rs 6,33,360 | - |
| 3 | E4 FAR | 659 | 50.2% | 40.8% | Rs 6,58,691 | Rs 6,256 | -Rs 4,305 | -Rs 46,617 | Rs 99,151 | Rs 4,61,961 | - |
| 3 | E5 SQUEEZE | 457 | 53.8% | 40.6% | Rs 6,02,705 | Rs 5,918 | -Rs 4,043 | -Rs 40,479 | Rs 67,329 | Rs 4,56,008 | - |
| 3 | E6 HTF15 | 569 | 48.7% | 39.4% | Rs 5,38,255 | Rs 6,200 | -Rs 4,039 | -Rs 31,886 | Rs 1,23,963 | Rs 3,55,168 | - |
| 3 | E7 HTF60 | 494 | 48.2% | 40.3% | Rs 3,79,044 | Rs 5,829 | -Rs 3,938 | -Rs 34,935 | Rs 1,29,109 | Rs 2,24,664 | - |
| 3 | E8 PDR | 436 | 51.6% | 37.3% | Rs 6,25,536 | Rs 6,287 | -Rs 3,739 | -Rs 30,318 | Rs 55,726 | Rs 4,79,816 | - |
| 3 | E9 ATRHOT | 476 | 52.1% | 39.7% | Rs 6,86,341 | Rs 6,994 | -Rs 4,598 | -Rs 49,747 | Rs 1,15,364 | Rs 4,97,707 | - |
| 3 | E10 VIXWAY | 443 | 50.1% | 40.9% | Rs 4,37,657 | Rs 6,367 | -Rs 4,415 | -Rs 49,747 | Rs 1,02,196 | Rs 2,54,570 | - |
| 3 | E11 BREADTH5 | 457 | 49.2% | 41.8% | Rs 3,55,659 | Rs 6,109 | -Rs 4,392 | -Rs 55,971 | Rs 97,126 | Rs 1,70,681 | - |
| 3 | E12 NOLUNCH | 613 | 51.2% | 42.1% | Rs 5,78,144 | Rs 5,985 | -Rs 4,352 | -Rs 59,866 | Rs 1,24,713 | Rs 4,37,066 | - |
| 3 | E13 PASTHOUR | 604 | 49.7% | 42.6% | Rs 4,48,843 | Rs 6,044 | -Rs 4,488 | -Rs 51,396 | Rs 91,764 | Rs 2,87,606 | - |
| 3 | C1 COMBO | 410 | 52.9% | 38.3% | Rs 6,12,544 | Rs 6,289 | -Rs 3,898 | -Rs 28,296 | Rs 51,115 | Rs 4,73,498 | - |

