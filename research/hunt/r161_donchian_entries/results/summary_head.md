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
