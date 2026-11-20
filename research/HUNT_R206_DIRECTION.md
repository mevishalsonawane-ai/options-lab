# R206: is there a more reliable direction call than the arms use today? (20 Nov 2026)

Boss, 20 Nov: *"Can you find a way more reliable then current for direction prediction?"*

Pre-registered in `research/hunt/r206_direction/PREREG.md` (commit 505e3d37, before any number); code and the recent-era
results frozen in 9c7d9207 before the older era was read once. Every table: `results/report.md`; numbers:
`results/recent.json`, `old.json`, `decision.json`. Research only: **nothing in the app changed.**

**Index-only.** No option file was read. A signal is "right" when the index's close 15 minutes later is beyond its close
at the signal, the signal's way. Data: the `dhan-data` branch's 1-minute index bars, 8 Oct 2021 - 5 Oct 2026. These are
the arms' **signals** (their own code), not their trades: no picker, no one-position rule, expiry days included.

## Verdict for Boss

**No. I could not find a more reliable direction call, and the honest reason is that today's call is already a coin
flip - on purpose or not, the arms do not earn from being right about direction.**

### 1. How reliable is the call today

| signals | older days, 1 Apr 2022 - 19 Nov 2024 | recent days, 21 Nov 2024 - 5 Oct 2026 |
|---|---|---|
| R72 Breadth Donchian | right **49.3%** of 12,709 (47.6 - 50.9%) | right **51.6%** of 9,132 (49.4 - 53.7%) |
| R51 Donchian 20 + Supertrend | right **49.2%** of 27,101 (48.1 - 50.3%) | right **50.2%** of 19,104 (48.9 - 51.4%) |
| Liquidity 15+5 | right **51.0%** of 1,598 (48.2 - 53.7%) | right **53.2%** of 1,250 (50.0 - 56.4%) |

- After 30 minutes it is the same picture (48.4 - 54.5%).
- The average move the signal's way after 15 minutes was about 0.001% in the older days and 0.01 - 0.02% in the recent
  days. That small difference is the difference between the years these arms lost money (R190: R72 lost Rs 4.07 lakh in
  Dec 2021 - Oct 2024) and the years they made it.
- So a break is right about half the time. The arms make money because a wrong call is cut (the -15% stop, the time
  stop) and a right one is locked, not because the call is good. This matches every earlier study here (R16: direction
  "not predictable", AUC 0.51 - 0.54; the direction model: 51 - 53%; three neural networks; the pickers).

### 2. The eight things that might have made it more reliable: none did

Tested on the older days, which the idea had never seen. "A" is the group that should be right more often.

| id | signals | A | older days: A right | older days: the rest right | difference | chance it is luck (p) | recent days: difference |
|---|---|---|---|---|---|---|---|
| H1 | R51 | R72 fires the same call | 49.4% of 12,272 | 49.0% of 14,829 | +0.4 points | 0.33 | +2.7 |
| H2 | Liquidity | R51 or R72 fires the same call | 51.4% of 1,111 | 50.1% of 487 | +1.3 | 0.31 | +0.5 |
| H3 | R72 | all 5 indices broke (not 4) | 50.3% of 6,145 | 48.3% of 6,564 | +2.0 | 0.10 | -0.1 |
| H4 | R72 | R51 fires the same call | 49.4% of 12,272 | 45.8% of 437 | +3.6 | 0.15 | +0.6 |
| H5 | R72 | with the day's direction | 49.2% of 10,359 | 49.4% of 2,347 | -0.1 | 0.52 | +1.7 |
| H6 | Liquidity | with the day's direction | 52.6% of 1,320 | 43.5% of 278 | **+9.1** | 0.006 | **-1.1** |
| H7 | R72 | with NIFTY's 15-minute trend | 49.4% of 10,881 | 48.3% of 1,828 | +1.1 | 0.32 | -2.4 |
| H8 | Liquidity | with NIFTY's 15-minute trend | 50.7% of 1,320 | 52.5% of 278 | -1.8 | 0.68 | -6.9 |

- **The lead from this morning's diagnosis did not hold.** "Two arms agreeing" looked better on the recent option
  trades (66% wins against 59.5%). On older days the agreed calls were right 49.4% of the time, the same coin.
- **H6 is the only one that looks like something** (Liquidity with the day: +9.1 points over the older days, and
  above zero in each of its three years), but it reversed in the recent days (-1.1), it lifts Liquidity's own rate by only 1.6 points, and it does not
  survive the correction for the 4,888 ideas tried. Not more reliable.

### 3. Two patterns worth knowing (looked at, not tested: treat as leads)

- **Breaks from 15:00 were right more often in both eras, on both Donchian arms:** R72 61.4% of 953 (older) and 57.4% of
  545 (recent); R51 60.4% of 1,843 and 55.2% of 1,104. It is the well-known last-half-hour drift. The arms cannot use it
  today: they stop entering at 15:10 and square off at 15:15, and part of that 15-minute move is after 15:15.
- **There is no stable "bad hour".** 10:00 - 10:59 was worst in the older days (R72 41.8%), 11:00 - 11:59 in the recent
  days (44.7%). That is why "skip the worst hour" failed in R203.
- Calls were right more often than puts in both eras (R72 52.0% against 46.4%, then 53.6% against 49.7%): the market
  drifted up. It is not a signal.

## What I suggest

1. **Stop spending effort on predicting direction at this horizon.** Ten studies and 4.5 years say the same thing.
2. **Put the effort where the money actually comes from:** how big the move will be, not which way. R16 found the size
   of the next 30 - 60 minutes is predictable (error 18 - 20% smaller than the time-of-day average), and the diagnosis
   showed the arms lose on quiet days and earn on busy ones. A "trade only when a big enough move is expected" gate is
   the one idea in this family with real evidence under it. The simple versions tried so far (R183's sideways filter,
   the realised-volatility skip in R203) did not pass, so this needs a proper pre-registered test, not a promise.
3. **If you want, I can test the late-day drift properly** (breaks from 15:00 held to about 15:25): on the index first,
   then on options with real costs. It is a small window, so I would not expect much money from it.

Limits: signals, not trades; the index's move, not the option's P&L; one horizon family (15 and 30 minutes); data to 5 Oct.
