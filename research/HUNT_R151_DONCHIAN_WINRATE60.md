# R151: can the two Donchian arms reach a 60%+ win rate with profit? (5 Nov 2026) - 0 of 20 pass

Boss (5 Nov): "Use all the resources you can, and try to both donchain winrate to 60%+", then "Try to change level, or
parameters in the indicator too", then "Try adding W20 skip on top of picker".

Pre-registration: `hunt/r151_donchian_winrate60/PREREG.md` (with Amendments A-E, each committed before its results).
Code: `study.py`, `pick.py`, `make_entries.py`; tests: `tests/test_r151.py` (8). Results: `results/*.json`.
Entries: `research/experiments/entries/r151-*.yaml` (20). The research changed nothing in the app. On 6 Nov (Boss's choices) the
two pickers, and R150's W20 on R51, went into the app as SHADOW LOGS only: each break is labelled and nothing is skipped or
changed (`android/engine/.../R72Picker.kt`, `R51Picker.kt`, `W20Rule.kt`).

**Signal source:** the index (5-min Donchian on the index, plus Supertrend for R51 and 5-index breadth for R72). The
option is bought by the arm's strike rule (1-ITM). Exits are exactly the app's (-15% stop, the 30-60-100 lock with L25,
the time cap), priced by R101 / R106's engine with dated charges and app fills.

## Answer

**No.** Nothing tried gets either arm to 60% wins while it still makes money.

- **The indicator settings (12 per arm)** move the win rate by 2 to 4 points at most, and the gain does not hold on
  2026 or on 2022-24.
- **A trade picker (a model that scores each signal and takes only the best)** never finds a group of trades that wins
  60%, even in its top slice. W20 underneath it does not change that.
- **R105C already showed** that exits reach 60% only by selling at +5%, and then both arms lose.

So 60% is not reachable on these two arms without losing money. Both arms make money from a few big winners at about
45-50% wins, not from winning often.

## 1. Settings sweep (Amendment A, Part P)

**Design, 20 Nov 2024 - 31 Mar 2026** (all 5 indices):

| R51: Donchian / Supertrend | trades | won | net | per trade |
|---|---|---|---|---|
| 10 / 2 | 5,201 | 43.7% | -Rs 1,07,806 | -21 |
| 10 / 3 | 4,627 | 44.2% | -Rs 15,135 | -3 |
| 10 / 4 | 4,090 | 45.9% | Rs 48,246 | 12 |
| 20 / 2 | 4,465 | 44.8% | Rs 19,462 | 4 |
| **20 / 3 (today)** | 4,208 | 44.5% | Rs 50,425 | 12 |
| 20 / 4 | 3,812 | 46.4% | Rs 89,368 | 23 |
| 30 / 2 | 3,789 | 45.8% | Rs 1,30,619 | 34 |
| 30 / 3 | 3,729 | 45.7% | Rs 1,37,521 | 37 |
| **30 / 4 (chosen)** | 3,516 | 46.4% | Rs 1,08,472 | 31 |
| 55 / 2 | 2,829 | 45.6% | Rs 21,931 | 8 |
| 55 / 3 | 2,829 | 45.6% | Rs 33,636 | 12 |
| 55 / 4 | 2,800 | 45.7% | Rs 30,074 | 11 |

| R72: Donchian / indices breaking together | trades | won | net | per trade |
|---|---|---|---|---|
| 10 / 3 | 4,838 | 44.0% | Rs 66,509 | 14 |
| 10 / 4 | 4,307 | 43.8% | Rs 31,557 | 7 |
| 10 / 5 | 3,102 | 45.9% | Rs 1,19,577 | 39 |
| 20 / 3 | 3,670 | 44.6% | Rs 1,60,803 | 44 |
| **20 / 4 (today)** | 3,071 | 46.8% | Rs 2,16,929 | 71 |
| 20 / 5 | 1,997 | 48.3% | Rs 1,72,555 | 86 |
| 30 / 3 | 2,984 | 46.1% | Rs 2,93,215 | 98 |
| 30 / 4 | 2,414 | 48.4% | Rs 3,19,679 | 132 |
| **30 / 5 (chosen)** | 1,479 | 50.5% | Rs 2,25,575 | 153 |
| 55 / 3 | 2,095 | 46.3% | Rs 2,13,819 | 102 |
| 55 / 4 | 1,642 | 48.1% | Rs 2,16,512 | 132 |
| 55 / 5 | 972 | 50.3% | Rs 1,66,089 | 171 |

**The rule (set before the run):** the highest design win rate with per trade >= today's and >= 300 trades. That
setting was then read once on FINAL and OLD:

| | FINAL Apr - Oct 2026 | OLD Dec 2022 - Nov 2024 |
|---|---|---|
| R51 today 20 / 3 | 1,670 trades, 44.9%, Rs 1,34,743, worst month -Rs 54.6k, DD Rs 81.7k | 4,815, 39.2%, -Rs 4,00,363 |
| R51 chosen 30 / 4 | 1,420, 45.1%, Rs 1,66,729, worst month -Rs 15.3k, DD Rs 48.8k | 4,103, 39.0%, -Rs 3,39,625 |
| R72 today 20 / 4 | 1,202, 45.8%, Rs 1,71,366, worst month -Rs 40.9k, DD Rs 61.9k | 3,395, 39.0%, -Rs 3,06,931 |
| R72 chosen 30 / 5 | 632, 45.3%, Rs 95,221, worst month -Rs 33.1k, DD Rs 99.5k | 1,589, 39.1%, -Rs 1,58,534 |

- **Win rate:** the design gain is gone. Both chosen settings win the same as today's (about 45% in 2026, 39% in
  2022-24). Both fail.
- **A side note, not a pass:**
  - R51 at Donchian 30 / Supertrend 4 lost less in both periods. In 2026 it made Rs 1.67 lakh vs Rs 1.35 lakh, with a
    smaller worst month and drawdown; in 2022-24 it lost Rs 3.40 lakh vs Rs 4.00 lakh.
  - It was not the question asked and was not pre-registered as a profit test, so it stays a lead for a future study.
  - R72's 30 / 5 made less in 2026.

## 2. Trade pickers (sections 1-4, and Amendment B with W20)

**The method:**
- A model (M1 gradient trees, M2 logistic) scores each signal at its decision minute. Its inputs:
  - the day's move, the gap, the range and the TWAP distance;
  - the EMA slope, the bar size and the size of the break;
  - the distance to 5-min support / resistance and to the pivots;
  - India VIX and its change, and how many of the 5 indices are up.
- It was trained walk-forward by quarter. Each quarter from Apr 2025 to Mar 2026 was scored by a model trained only on
  earlier trades.
- **To pass design,** some score cut-off had to keep >= 25% of the arm's trades and win >= 60%, while making at least
  the arm's per trade.

**16 pickers were tried:** 2 arms x 2 models x (today's / chosen setting) x (with / without W20). All 16 fail at design,
so none was read on FINAL or OLD.

Apr 2025 - Mar 2026, out-of-fold:

| picker | arm alone | best win keeping >= 25% of trades | top slice (>= 50 trades) |
|---|---|---|---|
| R51 20/3 M1 | 3,044, 45.9%, Rs 70 | 46.3% (2,501 trades, Rs 77) | 46.3% |
| R51 20/3 M2 | | 49.1% (852, Rs 293) | 49.1% |
| R51 30/4 M1 | 2,536, 47.7%, Rs 84 | 48.3% (1,060, Rs 70) | 48.7% (298) |
| R51 30/4 M2 | | 49.8% (990, Rs 112) | 49.8% |
| R51 20/3 M1 + W20 | W20 alone 1,689, 47.3% | 48.6% (1,540, Rs 134) | 48.6% |
| R51 20/3 M2 + W20 | | 50.3% (837, Rs 249) | 54.3% (256) |
| R51 30/4 M1 + W20 | W20 alone 1,459, 48.0% | 48.1% (1,418, Rs 91) | 48.1% |
| R51 30/4 M2 + W20 | | 48.8% (818, Rs 153) | 55.6% (162) |
| R72 20/4 M1 | 2,238, 49.0%, Rs 150 | 50.2% (1,003, Rs 248) | 54.1% (181) |
| R72 20/4 M2 | | 52.9% (646, Rs 312) | 53.4% (307) |
| R72 30/5 M1 | 1,083, 52.8%, Rs 210 | 53.9% (271, Rs 342) | 58.9% (73) |
| R72 30/5 M2 | | 52.8% (1,083, Rs 210) | 55.5% (110) |
| R72 20/4 M1 + W20 | W20 alone 1,098, 51.8% | 52.1% (1,024, Rs 271) | 56.1% (114) |
| R72 20/4 M2 + W20 | | 52.7% (980, Rs 276) | 56.3% (126) |
| R72 30/5 M1 / M2 + W20 | W20 alone 546, 54.0% | (546 < 560, 25% of the arm) | 54.0% |

(Clean features, after the Amendment D fix. The first run's numbers, with R72 reading R51's features, are in
`results/bug_cache/`; every verdict is the same.)

- **The best any picker reached is 58.9%**, on 73 trades out of 1,083. Even the narrowest slice the models pick does
  not reach 60% on a useful number of trades.
- **The models sort profit better than wins.** R72 20/4 M2 keeps 646 trades at Rs 312 a trade vs the arm's Rs 150,
  while winning 52.9%. The arms' edge is in the size of the winners, which a win-rate target cannot capture.
- **Ties to R123:** networks that say "70%" are right about 58% of the time.

## 2b. The R72 logistic picker over Nov 2024 - Oct 2026 (Amendment C; Boss: "Test the R72 picker on nov 24 - Oct 2026")

**The rule, fixed before the run:** R72 as the app (Donchian 20, 4 of 5 indices). Take a signal only when the
logistic picker's score is >= 0.4656, the point quoted to Boss from the design curve. **Gate on Apr - Oct 2026:**
>= 50% wins, net > 0, per trade >= the arm's, >= 60 trades, and better than random skips (p <= 0.05).

| period (how scored) | picker | R72 alone |
|---|---|---|
| Nov 2024 - Mar 2025 (IN-SAMPLE: the model learned these months; not counted) | 508 trades, 46.1%, +Rs 2,550, worst month -Rs 20.0k | 833, 40.8%, -Rs 1,18,952, worst month -Rs 78.5k |
| Apr 2025 - Mar 2026 (walk-forward, each quarter by a model of earlier trades) | 634, 52.5%, +Rs 1,91,363, Rs 302 a trade, worst month -Rs 22.0k, DD Rs 57.1k | 2,238, 49.0%, +Rs 3,35,882, Rs 150, worst month -Rs 72.6k, DD Rs 86.5k |
| **Apr - Oct 2026 (frozen model, the test)** | **652, 48.9%, +Rs 2,19,057, Rs 336 a trade, worst month -Rs 8.3k, DD Rs 29.2k** | 1,202, 45.8%, +Rs 1,71,366, Rs 143, worst month -Rs 40.9k, DD Rs 61.9k |
| Apr 2025 - Oct 2026 (both out-of-sample periods) | 1,286, 50.7%, +Rs 4,10,420, Rs 319 | 3,440, 47.9%, +Rs 5,07,247, Rs 147 |

- **Verdict: FAILS, by 1.1 points of win rate** (48.9% < 50%).
- **It passes every other part of the gate on Apr - Oct 2026:**
  - It made more money than the arm (Rs 2.19 lakh vs Rs 1.71 lakh) with about half the trades.
  - It made 2.4 times the arm's per trade.
  - Its worst month was a fifth of the arm's, and its drawdown under half.
  - Random skips of the same size win as often in only 0.6% of 2,000 tries (p 0.006).
- **The cut-off curve on Apr - Oct 2026 tops out at 49.5%** (531 trades). No cut-off would have reached 50% there.
- **Disclosed:**
  - This is a second look at Apr - Oct 2026. The arm was read there by earlier studies, and this picker once by the
    buggy run (403 trades, 47.4%).
  - The cut-off was the best-looking point of the design curve, quoted to Boss before the run.
  - Dec 2022 - Nov 2024 was not part of Amendment C, so it was not read.
- **What it means:** the R72 picker does not raise the win rate to 50%. It does pick the bigger trades: more profit,
  far smaller bad months.
  - That is a different question from the one tested, and it was not the pre-registered gate.
  - It deserves its own pre-registered test, judged on per trade and drawdown, on Dec 2022 - Nov 2024 (not yet read
    for it) and as a shadow log on new days.

## 2d. The R51 logistic picker over Nov 2024 - Oct 2026 (Amendment E; Boss: "What about r51?" ... "Do whatever you recommend")

**The rule, fixed before the run:** R51 as the app (Donchian 20 + Supertrend (10, 3)). Take a signal only when R51's logistic
picker's score is >= 0.4469, the point quoted to Boss. **The gate:** Amendment C's.

| period (how scored) | picker | R51 alone |
|---|---|---|
| Nov 2024 - Mar 2025 (IN-SAMPLE, not counted) | 565 trades, 46.9%, +Rs 39,544 | 1,164, 40.7%, -Rs 1,63,323 |
| Apr 2025 - Mar 2026 (walk-forward) | 852, 49.1%, +Rs 2,49,385, Rs 293 a trade, worst month -Rs 18.4k, DD Rs 40.9k | 3,044, 45.9%, +Rs 2,13,748, Rs 70, worst month -Rs 53.7k, DD Rs 94.2k |
| **Apr - Oct 2026 (frozen model, the test)** | **693, 49.9%, +Rs 1,59,040, Rs 229 a trade, worst month -Rs 15.6k, DD Rs 33.6k** | 1,670, 44.9%, +Rs 1,34,743, Rs 81, worst month -Rs 54.6k, DD Rs 81.7k |
| Apr 2025 - Oct 2026 | 1,545, 49.4%, +Rs 4,08,425, Rs 264 | 4,714, 45.5%, +Rs 3,48,491, Rs 74 |

- **Verdict: FAILS, by 0.1 point of win rate** (49.9% < 50%).
- **Every other part of the gate passes, more strongly than R72's:**
  - more money than the arm with 41% of its trades;
  - 2.8 times its profit per trade;
  - a worst month and drawdown about 40% of the arm's;
  - better than random skips at p 0.0005.
- **Disclosed:**
  - Apr - Oct 2026 is a second look for R51.
  - The cut-off was the best-looking design point.
  - R72's result (Amendment C) was known when this was registered.
- **In the app (6 Nov, Boss's "Do whatever you recommend"):** it is a shadow log on R51 beside R150's W20, as R72's picker is on
  R72. Each R51 break is labelled and scored, and nothing is skipped or changed.
- **Two pickers, one holdout:** C and E are two tests on Apr - Oct 2026. BH over the two twin p values (0.0065, 0.0005) gives q
  0.0065 and 0.001, so both still beat random skips; both fail on the win rate, the pre-set bar.
- **R51 at Donchian 30 / Supertrend 4 is not tested:** every period with data has been read for it, and the data has only 4 new
  sessions. It stays a lead.

## 2c. The feature-cache bug (Amendment D)

- **What went wrong:** the first run cached features by (index, day, minute, side) across arms. R72 candidates on an R51
  minute took R51's features: no breadth count, and R51's "breaks today".
- **How it was found:** while running Amendment C (634 trades where `design.json` showed 598).
- **What was done:**
  - The cache is now per call, and W20 removes candidates only after features are built.
  - A test fails on the old code and passes on the fix.
  - Everything was re-run with no rule changed. The buggy files are kept in `results/bug_cache/`.
- **The verdicts did not change:** all 16 pickers fail at design either way.

## 3. Checks

- **Today's arms are reproduced exactly:**
  - design: R51 4,208 trades / Rs 50,425; R72 3,071 / Rs 2,16,929;
  - FINAL: R51 1,670 / Rs 1,34,743; R72 1,202 / Rs 1,71,366;
  - OLD: R51 4,815 / -Rs 4,00,363; R72 3,395 / -Rs 3,06,931.
  - The rebuilt signals at 20 / 3 and 20 / 4 match R101's trade for trade.
- **Tests:**
  - Look-ahead: every index's minutes from the decision on (and VIX's), plus that day's 5-min bars ending then or later,
    are scrambled, and the features do not change.
  - The threshold rule.
  - The fold cut: no training trade from the fold or later.
- **What counts as a second look, disclosed:**
  - Both FINAL and OLD were read by earlier studies of these arms (R105C, R140-R144a, R150).
  - The R51 sweep and the two R51 pickers at today's setting were printed before Amendment B was written.
  - BH over all 16 pickers: q = 1.0 (no picker reached a twin test). Amendment C is one test (no BH).

## 4. What this means for Boss

- **Keep the arms' exits.** A 60% win rate is the wrong target for a breakout-and-trail arm. At about 45% wins, these
  arms make money in 2025-26 because winners are several times the size of losers. Every way of raising the win rate
  either cuts those winners (R105C) or finds nothing (R151).
- **Nothing here goes into the app.** Possible next steps, each only on Boss's word and each as a new pre-registered
  study:
  1. R51 at Donchian 30 / Supertrend 4 as a profit and drawdown test.
  2. The R72 logistic picker judged on per trade and drawdown (section 2b): Dec 2022 - Nov 2024 first, then a shadow
     log.
  3. W20 on R51 as a shadow log (from R150).
