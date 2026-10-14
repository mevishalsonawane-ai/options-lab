# HUNT R29: many EMAs at once (EMA ribbons), 14 Oct 2026

Boss (14 Oct 2026): "Try to use multiple EMAs at once, and check the patterns."
Code: `research/hunt/r29/`. Pre-registration: `research/hunt/r29/PREREG.md`. Part A (commit 917651e) was pushed before
any outcome was computed. Part B (commit c52eab0) was pushed after mining the design data and before any walk-forward
or holdout read. Generated tables: `research/hunt/r29/results/report.md` and `mining.csv`. Big trade tables are on Drive
(`IraAlgo-data-lake/derived/r29`). Research only: nothing in the app changed.

## Verdict for Boss

**No. No multi-EMA pattern makes money after costs on unseen data. Nothing goes to paper, and nothing goes live.**

- **What was tested.** EMA ribbons on 1, 3, 5 and 15-minute bars of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX,
  2021-2026. The ribbon is EMA 5/8/13/21/34/55/89/200; the common set is EMA 9/21/50/200. 12 pattern types were tested:
  - the ribbon fully lining up (stacking);
  - a full order flip ("twist");
  - a pullback to the 21/34 band that closes back;
  - a squeeze then fan-out;
  - price breaking out of the ribbon;
  - the ribbon starting to fan out;
  - 1/5/15-minute agreement;
  - combinations, e.g. a 5-minute pullback while the 15-minute ribbon is stacked.

  Each pattern was traded both ways, with the trend (follow) and against it (fade): 140 candidates. Each trade bought
  1 lot of the ATM option at the next minute's price, with the app's charges and a realistic spread. The main exit was
  your lock (stop −30%; once the trade is +Rs 1,000, keep at least +Rs 500; out after 60 minutes).
- **Mining (2021-22, then checked on 2024): only 1 of 140 made money in both periods, on 2 and 9 trades, which is
  noise.** 228,105 trades in total. An average pattern trade lost Rs 56 (follow) to Rs 123 (fade) per
  lot on 2024. A random-minute trade lost Rs 99.
- **Your three ideas, plainly:**
  - **15-min fully stacked + 5-min pullback, buy with the trend:** Rs −62 a trade on up-trends (771 trades) and Rs −209
    on down-trends (340) in 2024. Noise.
  - **Squeeze then fan-out breakout:** Rs −4 to −209 a trade on 1/3/5-minute bars (9 cells). The one positive cell
    (15-minute, down, Rs +123 on 146 trades) lost Rs −297 a trade in 2021-22. Noise.
  - **1/5/15-minute alignment:** Rs −72 (up) and Rs −78 (down) a trade, 1,988 trades. Noise.
- **The 10 strongest candidates still lost on unseen years.** These are the 10 that beat their random twins by the
  most. All 10 are "fade" trades, and every one lost money on its own even while beating its twins. They were run
  unchanged on 2025, 2026 and the locked 2023 holdout (read once). Results:
  - **Walk-forward 2025: 1 of 10 made money** (R2, +Rs 5 a trade on 170 trades). **2026: 1 of 10** (R3, +Rs 33 on 106).
    No rule made money in both years.
  - **Holdout 2023: 1 of 10 made money pooled.** That was R6 (fade a bearish 5-minute pullback inside a bearish
    15-minute ribbon, i.e. buy CE): +Rs 21 a trade on 264 trades, about +Rs 23 a day over all five indices.
  - R6 is not an edge: on every index its result flips sign from period to period. On BANKNIFTY it went +106, −70,
    −168, +42, +62 a trade; on FINNIFTY −489, −82, −250, −266, +58. **0 of 55 rule × index tests passed Part A's gate**
    (needs holdout profit, p < 0.05, BH q ≤ 0.10, both walk-forward years positive and profit at 2× spread).
- **The plain EMA 9 × 21 cross (5-min, buy with the cross) lost on all five indices in every period.** Holdout Rs −99
  a trade on 2,313 trades, about Rs −1,030 a day across the five indices.
- **Why this keeps failing.** A ribbon is just smoothed past prices: by the time 8 EMAs line up, the move they describe
  has happened. The index moved only about ±2 bps more than random over the next 30 minutes after a ribbon event
  (median, 2024). An ATM option's round trip costs far more than that.
  - This repeats H25 (every MA, cross and ribbon alone: Rs −146 a trade). Combining many EMAs adds nothing that a
    single one did not.
- **What R29 leaves behind.** 77 point-in-time EMA-ribbon features in the Data Brain (`rib*`, `cls*`,
  `rib_mtf_align`), checked by the look-ahead guard, for future models to use as context. They are not a trading rule.
- **On paper:** nothing. CLAUDE.md says ideas that failed the gates stay notes only. **Live:** never from this study.

## Coordination and the BH family

- F1 (`claude/f1-india-factory`) had pushed nothing when R29's Part A was written. My `git fetch` at about 06:10 UTC
  found no branch. R29 Part A was committed at 06:16:15 and F1's pre-registration at 06:16:55, 40 seconds later. So R29
  chose its own holdout from the inventory of every earlier locked holdout: **calendar 2023**, which had never been one.
- F1's holdout is **1 Jan 2023 – 30 Jun 2024**. R29's 2023 holdout lies inside it, so for 2023 both use the same clean
  block.
- **Overlap to note for F1:** R29's selection split (2024) includes 1 Jan – 30 Jun 2024. R29 read multi-EMA trade
  outcomes there (ATM, lock exit) before it knew F1's choice. No F1 rule or single-indicator result was computed, so
  F1's rules are not contaminated. Still, F1's write-up should say that another study read outcomes on the first half
  of 2024.
- R29's MIDCPNIFTY: F1 dropped it because its expiry dates are unreliable. R29 uses expiries only to set the 15:05
  square-off, so a wrong expiry date only moves that exit time.
- **R29's tests:** 140 mining candidates + 55 confirmatory (10 rules + B0, × 5 indices) = **195**. F1's own count is
  up to 20 holdout rules plus its screen tests, still unknown. The combined family is 195 + F1's count.
- On R29's own 55-test holdout family, 19 rule × index results reach BH q ≤ 0.10 against their twins. Each is listed
  with the largest combined family it would survive (`max_family_for_q10` in `results/holdout.json`; best 200, for R6
  BANKNIFTY and R5 FINNIFTY). It makes no difference: 14 of the 19 lose money on their own. The 5 that make money (R2
  BANKNIFTY, R4 NIFTY, R6 BANKNIFTY / FINNIFTY / MIDCPNIFTY) each lost in at least one walk-forward year.

## Why "beats random twins" is not "makes money" here

- Part A's twin keeps the trade's day and direction (as in H25), and picks a random minute.
- A ribbon event's sign tells you which way the day has been going. So:
  - a follow trade's twin is a random entry in the day's own direction (+Rs 41 a trade on 2024);
  - a fade trade's twin is a random entry against it (−Rs 241).
- The twin test therefore flatters fades and is harsh on follows. Every one of the 45 mining survivors is a fade that
  loses less than its twins.
- Part A's pass gate also needs profit on its own, so this could not let a losing rule through. From Part B on, the
  report also shows a random minute with a random direction on the same day, as a description only. In the holdout that
  lost Rs 70–90 a trade; R6's +21 is the only rule above it.

## What was tested

- **Data:** the Dhan lake on Drive.
  - Index minutes: NIFTY from 2017; BANKNIFTY / FINNIFTY / SENSEX from Aug 2021; MIDCPNIFTY from Jan 2022; all to
    12 Oct 2026.
  - Nearest-expiry options ATM±10. They start Aug 2021 (MIDCPNIFTY Jan 2022, SENSEX 15 May 2023).
  - After Nov 2024, BANKNIFTY / FINNIFTY / MIDCPNIFTY have only monthlies, so the nearest monthly is bought.
- **Features** (`research/brain/features/ribbon.py`, family "indicators", 77 features), per bar size 1/3/5/15:
  - stack (+1 / −1 / 0) and order score;
  - width in ATRs, and its change over 5 bars;
  - the slope of each EMA in ATRs;
  - the price's position vs the ribbon (above / inside / below, and as a fraction);
  - twist, pullback (low touches EMA21 in a bull stack and the bar closes back above EMA21/34; the mirror for bears);
  - squeeze-break (narrowest width of 50 bars within the last 5, widening 2 bars, ≥ 6 of 7 pairs in order, close
    outside the ribbon);
  - the CLS 9/21/50/200 stack and order;
  - and `rib_mtf_align` = 1 + 5 + 15-minute stacks.

  Bars are session-anchored and used only once complete. The EMAs run across sessions like a chart and are NaN for
  the first 200 bars.
- **Look-ahead guard:**
  - every ribbon feature is recomputed with the future deleted and with it perturbed, and must not change
    (`research/brain/tests/test_ribbon.py`), and a leaky canary is caught;
  - the R29 events pass the same check (`test_r29.py`).
- **Patterns P1-P12** (PREREG Step 1 table): 35 pattern-timeframe events × bull/bear × follow/fade = 140. A pattern
  fires on its bar's close between 09:30 and 14:45, and the fill is at the next minute's open. Events count only after
  60 sessions of history. One trade at a time, at most 3 a day.
- **Labels:**
  - the index move over the next 15/30/60 minutes, in the trade direction;
  - the 60-minute MFE / MAE;
  - the option P&L under 4 exits × ATM / 1-ITM;
  - 20 random-minute twins per trade (mining) or 1,000 twin sets (rules).
- **Exits** (on the option's own 1-minute bars):
  - X1, your lock (above);
  - X2: stop −25%, then trail 15% under the best close after +20%, 90 minutes;
  - X3: stop −20%, target +40%, 30 minutes;
  - X4: out at 30 minutes, stop −40%.

  Square-off is at 15:20, or 15:05 on expiry days.
- **Costs:**
  - The app's charges at today's rates (Rs 20 an order, STT 0.15% of the sell, exchange, SEBI, stamp, GST).
  - Fills: the next open ±5 bps plus a 1/2/4-tick half-spread set by the last 5 minutes' volume; stops −10 bps and 2×
    the ticks.
  - Stress test: 2× the spread.
- **Splits:**

  | split | years | what it is for |
  |---|---|---|
  | discovery | 2021-22 | mining |
  | selection | 2024 | ranking |
  | walk-forward | 2025, 2026 | each year separately, with the rules unchanged |
  | holdout | 2023 | locked, read once (`results/HOLDOUT_READ`) |

## Step 1-2: mining (all 140 in `results/report.md`)

- Trades: discovery 93,650 and selection 134,455, over 4 indices (SENSEX has no options before May 2023, so it appears
  in selection only).
- Random-minute trade: Rs −169 (discovery) and Rs −99 (selection) a lot.
- By pattern family, the mean selection net per trade (Rs, ATM, lock):

| family | follow | fade |
|---|---|---|
| P1 ribbon stack onset | −54 | −145 |
| P2 9/21/50/200 stack onset | −71 | −108 |
| P3 twist (full flip) | +19 | −168 |
| P4 pullback to 21/34 | −94 | −105 |
| P5 squeeze → expansion | −68 | −132 |
| P6 price leaves the ribbon | −89 | −115 |
| P7 fan-out starts | −44 | −169 |
| P8 1/5/15-min alignment | −75 | −146 |
| P9 5-min pullback in 15-min stack | −135 | −73 |
| P10 1-min pullback in 5+15-min stacks | −119 | −130 |
| P11 5-min squeeze with 15-min order | −177 | −19 |
| P12 twist back with the 15-min stack | +52 | −60 |

- The two "positive" cells are noise:
  - P3 follow is driven by the 15-minute twist: +297 / +124 a trade on 64 / 63 trades in 2024, but −20 / −403 in
    2021-22. The 1/3/5-minute twists are −129 to +31.
  - P12 has 9-13 trades a cell (the 15-minute stack rarely flips back).
- **The index barely moves after these events.** The median move beyond random over 30 minutes is 2.0 bps. 56 of 140
  candidates are positive in both splits; chance alone gives about 35.
- After an average event the index moves as far for the trade as against it within the hour: mean MFE +22.1 bps,
  mean MAE −22.1 bps (2024, over the 140 candidates).

## Step 3-4: the 10 pre-registered rules + B0

Pooled over the indices, ATM, lock exit, net per trade in Rs (trades in brackets):

| rule | what | discovery | selection | WF 2025 | WF 2026 | holdout 2023 |
|---|---|---|---|---|---|---|
| R1 | fade bearish 3-min twist | −174 (162) | −85 (244) | −289 (249) | −305 (202) | −121 (197) |
| R2 | fade bullish 5-min twist | −226 (104) | −10 (157) | +5 (170) | −290 (138) | −43 (130) |
| R3 | fade bullish 15-min squeeze-break | −229 (111) | −37 (182) | −19 (160) | +33 (106) | −136 (169) |
| R4 | fade bearish 15-min 9/21/50/200 stack | −111 (166) | −69 (241) | −20 (246) | −159 (243) | −54 (173) |
| R5 | fade bullish 3-min squeeze-break | −141 (457) | −11 (603) | −251 (655) | −183 (487) | −97 (570) |
| R6 | fade bearish 5-min pullback in 15-min bear stack | −159 (206) | −29 (338) | −129 (405) | −168 (350) | **+21 (264)** |
| R7 | fade bullish 3-min twist | −143 (160) | −127 (232) | −199 (255) | −56 (185) | −223 (184) |
| R8 | fade bearish 1-min pullback in 5+15 stacks | −233 (388) | −128 (529) | −116 (625) | −193 (646) | −117 (402) |
| R9 | fade bearish 5-min 9/21/50/200 stack | −56 (486) | −111 (661) | −300 (728) | −345 (526) | −75 (505) |
| R10 | fade bullish 5-min ribbon stack | −111 (805) | −68 (1,267) | −140 (1,120) | −140 (789) | −111 (1,033) |
| B0 | EMA 9×21 cross, 5-min, follow | −132 (1,988) | −65 (2,787) | −159 (2,750) | −39 (1,943) | −99 (2,313) |

Holdout details per index (`results/report.md` has every column for every period):

- trades a day, win rate, average win / loss, P&L a day, % green days, worst day, max drawdown, big winners cut short,
  p vs twins, BH q, 2× spread.
- Win rates are 19-52% (one 7-trade cell 71%). Average wins are Rs 225-1,139 and average losses Rs −278 to −936.
- Worst days are Rs −549 to −8,173 a lot. The worst max drawdown among R1-R10 was Rs −29,225 (R10 BANKNIFTY); B0
  BANKNIFTY reached Rs −81,486.
- Big winners cut short (the option rose ≥ 50% of the entry price above the exit within 2 hours): 0-20% of trades,
  typically 10% (one 7-trade cell 57%). The lock is not what kills these rules: hold-longer exits (X2 trail, 1-ITM) lose too. The one
  exception is R6, positive under every exit in the holdout (best ITM1 + trail, +Rs 152 a day), which is the same
  noise as above.
- Holdout P&L a day summed over the 5 indices, by exit:
  - R10 (most active): Rs −333 to −690;
  - B0: Rs −955 to −1,219.

## Honest caveats

- 2023 was design data for other studies (H25 ran single MAs and ribbons on it; OBUY printed 2023; R24 mined it). No
  multi-EMA combination had been run on it before R29's one read.
- Options before Nov 2024 were weeklies for every index, monthlies after for BANKNIFTY / FINNIFTY / MIDCPNIFTY. Lot
  sizes changed; every number is per lot.
- Dhan minute data has no bid/ask, so the spread is modelled. The stress test at 2× spread only makes everything
  worse.
- A trade whose strike drifts out of ATM±10, or has no price at the exit minute, uses its last price. Such trades are
  counted in the `missing` column, a handful per rule.
- Only the nearest expiry was traded. Other expiries, futures or non-option instruments were not tested.

## Files

- `research/brain/features/ribbon.py` and `research/brain/tests/test_ribbon.py`: the features and their guard.
- `research/hunt/r29/`:
  - `r29data.py`: lake → index frame + option table; holdout and walk-forward guard;
  - `patterns.py`: P1-P12 and B0 events;
  - `sim.py`: numba trade simulator;
  - `engine.py`: trades, gate, twins;
  - `mine.py`: Steps 1-2;
  - `rules.py`: Part B;
  - `study.py`: design / wf / holdout;
  - `report.py`: tables and experiment entries;
  - `test_r29.py`.
- Experiment entries: `research/experiments/entries/r29-*.yaml` (features, mining, Boss's 3 named ideas, R1-R10, B0).
- Re-run:
  1. `rclone copy gdrive:IraAlgo-data-lake/{index_1m,options_rolling_1m} ~/.cache/iraalgo/lake`
  2. `python research/hunt/r29/r29data.py build`
  3. `python research/hunt/r29/mine.py events`, then `mine.py trades`, then `mine.py rank`
  4. `python research/hunt/r29/study.py design`, then `study.py wf`

  `study.py holdout` refuses a second read.
