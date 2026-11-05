# R151: can the two Donchian arms reach a 60%+ win rate with profit? (5 Nov 2026) - 0 of 18 pass

Boss (5 Nov): "Use all the resources you can, and try to both donchain winrate to 60%+", then "Try to change level, or
parameters in the indicator too", then "Try adding W20 skip on top of picker".

Pre-registration: `hunt/r151_donchian_winrate60/PREREG.md` (with Amendments A and B, each committed before its results).
Code: `study.py`, `pick.py`, `make_entries.py`; tests: `tests/test_r151.py` (5). Results: `results/*.json`.
Entries: `research/experiments/entries/r151-*.yaml` (18). Research only; nothing under `android/` changed.

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
| R51 30/4 M1 | 2,536, 47.7%, Rs 84 | 48.8% (1,083, Rs 70) | 48.8% |
| R51 30/4 M2 | | 49.6% (1,051, Rs 101) | 49.6% |
| R51 20/3 M1 + W20 | W20 alone 1,689, 47.3% | 48.0% (1,542, Rs 124) | 48.1% |
| R51 20/3 M2 + W20 | | 50.6% (892, Rs 235) | 52.5% (282) |
| R51 30/4 M1 + W20 | W20 alone 1,459, 48.0% | 48.3% (1,232, Rs 124) | 48.3% |
| R51 30/4 M2 + W20 | | 49.1% (873, Rs 163) | 52.6% (171) |
| R72 20/4 M1 | 2,238, 49.0%, Rs 150 | 50.1% (581, Rs 285) | 50.1% |
| R72 20/4 M2 | | 53.7% (598, Rs 389) | 56.7% (208) |
| R72 30/5 M1 | 1,083, 52.8%, Rs 210 | 53.4% (380, Rs 242) | 53.6% |
| R72 30/5 M2 | | 52.8% (1,083, Rs 210) | 52.8% |
| R72 20/4 M1 + W20 | W20 alone 1,098, 51.8% | 51.8% (1,098, Rs 242) | 51.9% |
| R72 20/4 M2 + W20 | | 52.6% (943, Rs 276) | 57.6% (139) |
| R72 30/5 M1 / M2 + W20 | W20 alone 546, 54.0% | (546 < 560, 25% of the arm) | 54.0% |

- **The best any picker reached is 57.6%**, on 139 trades out of 2,238 (6%). Even the narrowest slice the models can
  pick does not reach 60%.
- **The models sort profit better than wins.** R72 20/4 M2 keeps 598 trades at Rs 389 a trade vs the arm's Rs 150,
  while winning only 53.7%. The arms' edge is in the size of the winners, which a win-rate target cannot capture.
  - This is out-of-fold design data only; it was never read on FINAL or OLD.
  - A future study could pre-register "a picker that raises per trade", and test it then.
- **Ties to R123:** networks that say "70%" are right about 58% of the time.

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
  - BH over all 16 pickers: q = 1.0 (no picker reached a twin test).

## 4. What this means for Boss

- **Keep the arms' exits.** A 60% win rate is the wrong target for a breakout-and-trail arm. At about 45% wins, these
  arms make money in 2025-26 because winners are several times the size of losers. Every way of raising the win rate
  either cuts those winners (R105C) or finds nothing (R151).
- **Nothing here goes into the app.** Possible next steps, each only on Boss's word and each as a new pre-registered
  study:
  1. R51 at Donchian 30 / Supertrend 4 as a profit and drawdown test.
  2. A picker judged on per trade, not on win rate.
  3. W20 on R51 as a shadow log (from R150).
