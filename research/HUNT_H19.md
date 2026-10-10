# HUNT h19: did the retired ORB arms carry Boss's good days? (recent-regime check, 1 lot each, app paper fills)

Code: `research/hunt/h19/`.
- `sim.py`: the arms replayed and their minute-by-minute paths.
- `recent.py`: the 28 Sep to 6 Oct trade lists.
- `variants.py`: the rules in force on each date.
- `analysis.py`: the long-run statistics, recent-window tests, streaks, correlation and day-level protection.

Caches are in `scratchpad/hunt/h19/`. All of it is option BUYING.

## Plain verdict for Boss

1. **The old arms did NOT make the +Rs 5,032 (1 Oct) or the +Rs 4,955 (5 Oct).** I replayed the app's own rules on
   Dhan's real minutes, with paper fills and charges, 1 lot each. ORB, ORB Fresh, ORB Sweep and Range Fade together
   **lost Rs 3,618 on 1 Oct and Rs 6,282 on 5 Oct**. Liquidity 15+5 made +Rs 341 (old ATM rule) or -Rs 568 (current
   1-ITM rule) on 1 Oct, and lost Rs 3,078 to 3,707 on 5 Oct.
   - **The green on those days came from something else in the paper account.** The likely sources are Pine
     auto-trade scripts, Jarvis's own trades, Solo, Hero, manual paper trades, or the "Jarvis plans the day" arming.
   - The trade counts point the same way. Boss's 38 and 48 trades are about twice what these arms can produce (19 to
     26 trades a day here). They may also be counting orders (buy + sell) rather than round trips.
   - To settle it, export the paper trade log by arm for 1 and 5 Oct. The answer is in that log, not in a backtest.
2. **The long run is unambiguous, and the recent months are not different.** I looked at the last 1, 3 and 6 months
   to 5 Oct 2026 for all four old arms.
   - All four lost in every one of those windows.
   - None of the windows is statistically different from the 2021-2026 average (bootstrap and permutation
     p = 0.10 to 0.99).
   - ORB had 3 winning months out of 63 and never two in a row. Fresh, Sweep and Fade each had 9 or 10 winning months
     out of 63, and none had a 3-month winning streak.
   - There is **no recent regime in which they turned profitable**.
   - Liquidity BANKNIFTY is the arm with an unusually good recent stretch: +Rs 34k in the last 3 months, bootstrap
     p = 0.04. It is not one of the old arms.
3. **Do the old arms win on Liquidity's days? No.** The daily correlation with Liquidity is about 0 (|r| ≤ 0.09). On
   Liquidity's best 10% of days, the old arms still lost Rs 1,188 a day on average, against -Rs 1,430 on other days.
   - On the best 20% of combined days the old arms were usually green: 75% of those days, +Rs 1,170 on average.
     That is because they are the noisiest part of the book.
   - On the worst 20% of days they were **never** green and averaged **-Rs 5,000**. They make the bad days far more
     than they make the good ones.
4. **Judge them on the long run.** Their losses are mostly charges from over-trading. ORB took 930 trades in the last
   6 months: gross -Rs 10k, charges Rs 1.03 lakh. A short winning stretch is exactly what this kind of arm shows by
   chance. An arm with Fresh/Sweep/Fade's long-run edge has a 10-16% chance of a winning month, but only about a
   1-3% chance of two in a row and under 1% of three. A good week proves nothing.
5. **What forward record would settle it, judged on paper trades entered from 7 Oct only:**

   | arm | days of paper (trades) to show its long-run loss at 80% power | chance its long-run edge shows green after 20 / 60 days |
   |---|---|---|
   | ORB | about 35 days (about 255 trades) | 3% / 0% |
   | ORB Fresh | about 130 days (about 250 trades) | 15% / 3% |
   | ORB Sweep | about 120 days (about 155 trades) | 16% / 4% |
   | Range Fade | about 75 days (about 110 trades) | 10% / 2% |

   Practical rule: run the app's own PassRule (60 closed trades or 40 days; net > 0, t > 2, green on up AND down days,
   green without the best 3).
   - If an arm is still red after **40 trading days (about 2 months)**, switch it off for good.
   - Do not decide on 1-2 weeks.
6. **Boss's "up 3-4k, then backfired to negative":** this is real, and it is common in the replay.
   - On days when the combined book reached +Rs 4,000, it gave back Rs 4,200 on average by the close, and 12% of
     those days closed red.
   - **No day-level lock fixes it.** I chose all 16 pre-registered rules on pre-Oct-2025 data, then tested them once
     on the holdout. Each helped only as much as stopping at a *random* time on a random day (placebo p = 0.20 to
     0.99). That help comes from the arms losing money on average, not from timing.
   - The best rule before the holdout ("no new entries once the day is +Rs 2,000") gives +Rs 432/day in the holdout.
     Stopping at random times gives +Rs 424/day.
   - The locks did not save 30 Sep or 5 Oct. Those days were never green by Rs 2,000 (peaks +1,683 and 0); they were
     bad from the start.
   - What would actually stop the giveback is not running the losing arms. The ORB arms re-enter every 10 minutes in
     chop, and each stop costs about Rs 1,350.

## Method and checks

**Rules.** I ported `ArmsBacktest.kt`, `OrbRules`, `SweepRules`, `RangeFadeRules` and `ProfitLock` line by line.
- **Range and strike.** 5-minute BANKNIFTY bars. The opening range is 09:15-10:00. The strike is ATM from the 09:20
  bar. The contract is the nearest expiry on or after the day.
- **ORB and ORB Fresh.** They decide on bars after 10:00 and before 14:00, with unlimited entries. Fresh needs a fresh
  break.
- **ORB Sweep.** After 10:00 and before 14:30, at most 2 a day, target +80.
- **Range Fade.** 10:30 to before 14:00, the 10% edge of the range, at most 2 a day.
- **Cooldown.** After an exit, an arm may decide again only from the bar after the exit's bar.
- **Exits.** -40 stop, the profit-lock ladder (25/50/75% of the target → entry / +25% / +50%), +40 target, square-off
  at 15:10. Entries at a premium of Rs 40 or less are refused.
- **Fills and charges.** The app's paper fills (market ±5 bps, stops -10 bps), and `SandboxCosts` charges.

**Liquidity 15+5.** BANKNIFTY 15m + 5m and FINNIFTY 30m + 5m, using the h4 port through obuy's engine.
- It reproduces the validated **Rs 242,421 over 1,651 trades exactly**.

**Validation of the ORB port.** Over Aug 2021 to 5 Oct 2026 it gives these net losses:

| arm | net (Rs) | RetiredArms.kt figure, from the 06 Oct research replay |
|---|---|---|
| ORB | -977,341 | 977k |
| ORB Fresh | -252,721 | 253k |
| ORB Sweep | -291,445 | 291k |
| Range Fade | -273,592 | 274k |

So this is the same replay the app's own record was built from.

**Data.** Dhan index and option minutes.
- On **6 Oct the BANKNIFTY options stop at 12:12 and the index at 13:39**, so 6 Oct is only replayable to about 12:10.
  Boss's 40 trades and -Rs 4,551 that day cannot be checked after that point.
- 2-5 Oct had no session in the data (2 Oct is a holiday).

### Rules in force on each date (repo history)

| change | when |
|---|---|
| Profit-lock ladder added to the ORB arms | 1 Oct |
| Liquidity switched to 1-ITM with the room filter | 6 Oct 09:39 (it was ATM with no room filter before) |
| ORB family switched to the same-day option on expiry days | 1 Oct |
| ORB Sweep and Range Fade chosen | 29 Sep (so they may not have been live on 28-29 Sep) |
| "Jarvis plans the day: paper arms fitted to the regime" | 4 Oct |

I ran both rule sets:
- **"As-of"** uses the rules in force on each date: no ladder before 1 Oct, and Liquidity ATM with no room filter
  before 6 Oct.
- **"Current"** uses today's rules on every date.

On 29 Sep (an expiry day) the pre-1-Oct rule traded the NEXT expiry, which the data does not hold. The replay uses
the same-day option there.

## 1. 28 Sep - 6 Oct 2026, per arm per day (net Rs, 1 lot each, app paper fills and charges)

Boss's paper account, for comparison:

| | 28 Sep | 29 Sep | 30 Sep | 1 Oct | 5 Oct | 6 Oct |
|---|---|---|---|---|---|---|
| trades | 2 | 2 | 12 | 38 | 48 | 40 |
| Rs | -56 | +589 | -6,014 | **+5,032** | **+4,955** | -4,551 |

**As-of rules:**

| day | ORB | ORB Fresh | ORB Sweep | Range Fade | old 4 | Liq BN | Liq FIN | Liquidity | total | trades | gross |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 28 Sep | -1,618 | -1,282 | 0 | 0 | -2,900 | +11,483 | +4,667 | +16,150 | +13,250 | 9 | +13,913 |
| 29 Sep (expiry) | 0 | 0 | +2,337 | +2,285 | +4,622 | 0 | 0 | 0 | +4,622 | 3 | +4,793 |
| 30 Sep | +2,055 | -1,666 | -2,629 | -2,629 | -4,869 | +1,094 | +2,411 | +3,505 | -1,364 | 18 | +855 |
| **1 Oct** | -3,796 | -2,635 | +2,789 | +24 | **-3,618** | -353 | +694 | +341 | **-3,277** | 26 | -338 |
| **5 Oct** | -3,650 | -1,339 | -145 | -1,148 | **-6,282** | -1,748 | -1,959 | -3,707 | **-9,989** | 23 | -7,366 |
| 6 Oct (to ~12:10) | 0 | 0 | 0 | +991 | +991 | 0 | 0 | 0 | +991 | 2 | +1,180 |

**Current rules on every day:**

| day | old 4 | Liquidity | total | trades |
|---|---|---|---|---|
| 28 Sep | -2,229 | +18,855 | +16,626 | 20 |
| 29 Sep | +1,006 | 0 | +1,006 | 3 |
| 30 Sep | -4,556 | +2,489 | -2,067 | 25 |
| 1 Oct | -3,618 | -568 | -4,186 | 23 |
| 5 Oct | -6,282 | -3,078 | -9,360 | 22 |
| 6 Oct (to ~12:10) | +991 | 0 | +991 | 2 |

**Mismatches with Boss's figures, and the likely causes:**
- **1 Oct and 5 Oct.** The replay is red by Rs 3-10k where the account is green by about Rs 5k. The arms replayed
  cannot be the source of that profit (see the verdict).
- **28 Sep.** The replay shows a big Liquidity day (+Rs 16-19k: the PE shorts from 09:20 held to 12:00-15:10). Boss
  booked 2 trades and -Rs 56, so Liquidity and almost all of the ORB arms were evidently **not armed** in his app that
  day.
- **30 Sep.** Boss had 12 trades and -Rs 6,014; the replay has 18-25 trades and -Rs 1.4k to -2.1k. A subset of arms
  plus other strategies fits that. The replay's own drawdown that day was -Rs 8,364 at 11:17.
- **6 Oct.** The data ends before the afternoon, so most of that day cannot be checked.
- **Fills.** The live paper fills use the LTP when the phone polls, not minute opens. That changes single trades by a
  few rupees a unit, not by Rs 9-15k a day.

### Why the red days went red (current-rules replay; full trade lists below)

- **30 Sep.** BANKNIFTY opened at the day's low (54,176) and ran 515 points to the 10:00 range high (54,690). It then
  chopped between 54,650 and 54,830 from 10:15 to 11:15.
  - ORB and Fresh bought CE breaks above 54,690 and were **premium-stopped** at 10:29 and 10:44 (about -Rs 1,360
    each).
  - At the same time ORB Sweep and Range Fade **bought PEs** at the range top: 3 stops and a lock, -Rs 4.05k.
  - So the four arms were long CE and long PE on the same index within minutes. That is the "one index one side" fix
    of 06 Oct.
  - The trough was -Rs 8,364 at 11:17. The trend up to 55,135 (13:30) let ORB recover about Rs 5k through five +40 targets.
    The day closed at -Rs 2,067.
- **1 Oct.** The index rose to 55,091 in the morning and then **reversed about 1,000 points** to 54,067 at 14:00.
  - ORB and Fresh bought CE breaks at 11:45 and 12:00 just as it turned, and were **premium-stopped** 4 times
    (-Rs 5.45k).
  - ORB Sweep's PE (the reversal) hit **+80, +Rs 2,292**.
  - On the way down ORB's PEs whipsawed: one -40 stop (-Rs 1,350), many **profit-lock exits near breakeven**
    (-Rs 80 to -534 each, mostly charges) and one +40 target.
  - The peak was +Rs 2,554 at 12:58; the day closed at -Rs 4,185. The giveback came from the 13:00 PE stop and the
    breakeven-lock churn while the index bounced 13:15-13:30.
- **5 Oct.** Red from the first minutes.
  - Liquidity BN 5m and FIN 5m bought CEs at 09:20 on a break above the level. The index made its day high (55,193)
    and reversed, so both hit **index stops** within 2 minutes; a second FIN entry at 09:35 was index-stopped too
    (-Rs 3,078 in all).
  - BANKNIFTY then slid 55,190 → 54,370. Range Fade **bought CEs** at the range bottom: one -40 stop, -Rs 1,329.
  - ORB bought PE breaks below 54,719: 4 targets (+Rs 4.8k), then 6 premium stops (-Rs 8.2k) as the index went
    sideways and bounced (54,520 → 54,800 by 13:45).
  - The trough was -Rs 9,717 at 14:12; no point of the day was above zero.
- **The common thread.** ORB has no daily cap and decides again 5 minutes after every exit. In a chop it re-buys every
  10-15 minutes, and each premium stop costs about Rs 1,350 at 30 units against a Rs 1,000-1,200 premium.
  - The ladder turns many trades into near-breakeven exits, which each cost about Rs 80 in charges.
  - Of the six arms, only Liquidity has index-based exits. The ORB family's stops are fixed premium points.

## 2. The long run and the recent regime, per arm (net Rs, 1 lot, app paper fills and charges)

The holdout used by the other hunts (1 Oct 2025 onward) is reported separately for consistency. Nothing here was
tuned, so nothing is in-sample.

### By year

| year | ORB | ORB Fresh | ORB Sweep | Range Fade | Liq BN | Liq FIN | old 4 | Liquidity |
|---|---|---|---|---|---|---|---|---|
| 2021 (Aug-Dec) | -81,162 | -23,715 | -10,484 | -15,559 | -9,474 | 0 | -130,920 | -9,474 |
| 2022 | -206,943 | -60,889 | -52,006 | -46,157 | -5,413 | +104 | -365,995 | -5,309 |
| 2023 | -120,649 | -39,573 | -55,729 | -53,825 | +10,815 | +8,569 | -269,776 | +19,384 |
| 2024 | -172,675 | -38,298 | -20,993 | -32,551 | +44,615 | -171 | -264,517 | +44,444 |
| 2025 | -221,726 | -63,213 | -86,670 | -76,613 | +45,320 | +60,551 | -448,223 | +105,870 |
| 2026 (to 5 Oct) | -174,186 | -27,032 | -65,563 | -48,887 | +60,947 | +26,557 | -315,668 | +87,504 |

### Holdout and recent gross vs net

| period | ORB | ORB Fresh | ORB Sweep | Range Fade | Liq BN | Liq FIN |
|---|---|---|---|---|---|---|
| Holdout (1 Oct 2025 - 5 Oct 2026), gross | -26,264 | +6,110 | -55,969 | -32,932 | +84,700 | +42,394 |
| Holdout, net | -232,385 | -43,650 | -86,477 | -67,578 | +59,956 | +26,332 |
| Last 6 months: gross | -9,972 | +14,301 | -28,109 | -5,032 | +31,298 | +18,937 |
| Last 6 months: charges | 103,057 (930 trades) | 26,162 (242) | 15,519 (164) | 18,180 (194) | 12,533 | 8,618 |
| Last 6 months: net | -113,029 | -11,861 | -43,628 | -23,212 | +18,766 | +10,320 |

### Quarters (net)

Every old-arm quarter from 2023 on is red. The only green old-arm quarters are ORB Fresh / Sweep in 2021Q3, Sweep
2022Q2, Fade 2022Q1, and Sweep in 2026Q4 so far (+2,644, 3 days).

| quarter | ORB | ORB Fresh | ORB Sweep | Range Fade | Liq BN | Liq FIN |
|---|---|---|---|---|---|---|
| 2025Q1 | -77,773 | -25,386 | -5,741 | -12,141 | +18,375 | +6,375 |
| 2025Q2 | -42,760 | -784 | -19,745 | -19,622 | +15,956 | +53,885 |
| 2025Q3 | -42,995 | -20,425 | -40,270 | -26,160 | +11,980 | +515 |
| 2025Q4 | -58,199 | -16,618 | -20,914 | -18,691 | -992 | -224 |
| 2026Q1 | -56,985 | -13,911 | -19,515 | -24,262 | +42,182 | +16,237 |
| 2026Q2 | -63,037 | -3,637 | -33,634 | -9,724 | -13,383 | -1,457 |
| 2026Q3 | -46,718 | -5,510 | -15,059 | -13,777 | +34,559 | +13,012 |
| 2026Q4 (1-5 Oct) | -7,445 | -3,974 | +2,644 | -1,124 | -2,410 | -1,235 |

### Last 12 months (net)

| month | ORB | ORB Fresh | ORB Sweep | Range Fade | Liq BN | Liq FIN |
|---|---|---|---|---|---|---|
| 2025-11 | -27,741 | -7,591 | -1,317 | -515 | +2,192 | +2,672 |
| 2025-12 | -16,450 | -11,665 | -13,399 | -8,086 | +1,269 | -8,689 |
| 2026-01 | -22,948 | -2,377 | -10,254 | -9,615 | +22,742 | +8,444 |
| 2026-02 | -7,086 | -5,130 | -5,898 | -10,515 | +10,672 | +5,971 |
| 2026-03 | -26,951 | -6,404 | -3,363 | -4,132 | +8,768 | +1,823 |
| 2026-04 | -28,166 | -3,911 | -14,653 | -5,887 | -10,902 | +931 |
| 2026-05 | -20,850 | +1,934 | -12,745 | +658 | -8,617 | +1,543 |
| 2026-06 | -14,021 | -1,659 | -6,235 | -4,495 | +6,136 | -3,930 |
| 2026-07 | -24,624 | -2,210 | -9,376 | -7,971 | +14,840 | +8,886 |
| 2026-08 | -11,537 | -7,100 | **+5,823** | -3,799 | +5,428 | -2,966 |
| 2026-09 | -10,558 | **+3,800** | -11,506 | -2,007 | +14,291 | +7,093 |
| 2026-10 (1-5) | -7,445 | -3,974 | +2,644 | -1,124 | -2,410 | -1,235 |

### Is the recent window different from the long run?

The window is compared with the rest of the data (2021 to the window's start), one row per arm and window.
- **p(boot ≥)** is the chance that a stationary block bootstrap of the arm's long-run days (mean block 10 days) gives
  a window at least this good.
- **p(perm)** is a two-sided iid permutation test of the window's mean against the rest.
- **hist ≥** is the share of all earlier rolling windows of the same length that were at least this good.
- **hist > 0** is the share of earlier windows of that length that were green at all.

| arm | window | net | Rs/day | long-run Rs/day | p(boot ≥) | p(perm) | hist ≥ | hist > 0 |
|---|---|---|---|---|---|---|---|---|
| ORB | 1m | -19,207 | -915 | -763 | 0.65 | 0.70 | 0.65 | 0.03 |
| ORB | 3m | -48,529 | -770 | -765 | 0.51 | 0.98 | 0.55 | 0.00 |
| ORB | 6m | -113,527 | -901 | -750 | 0.82 | 0.37 | 0.73 | 0.00 |
| ORB Fresh | 1m | -1,895 | -90 | -200 | 0.29 | 0.59 | 0.29 | 0.13 |
| ORB Fresh | 3m | -9,044 | -144 | -201 | 0.32 | 0.63 | 0.32 | 0.02 |
| ORB Fresh | 6m | -13,725 | -109 | -208 | 0.10 | 0.25 | 0.06 | 0.00 |
| ORB Sweep | 1m | -10,882 | -518 | -223 | 0.89 | 0.18 | 0.87 | 0.17 |
| ORB Sweep | 3m | -9,747 | -155 | -232 | 0.28 | 0.55 | 0.39 | 0.04 |
| ORB Sweep | 6m | -43,233 | -343 | -216 | 0.90 | 0.18 | 0.83 | 0.00 |
| Range Fade | 1m | -2,752 | -131 | -216 | 0.32 | 0.60 | 0.32 | 0.11 |
| Range Fade | 3m | -13,560 | -215 | -214 | 0.51 | 0.99 | 0.52 | 0.01 |
| Range Fade | 6m | -23,023 | -183 | -218 | 0.32 | 0.62 | 0.41 | 0.00 |
| Liq BN | 1m | +11,881 | +566 | +107 | 0.11 | 0.25 | 0.11 | 0.52 |
| Liq BN | 3m | +33,965 | +539 | +93 | **0.04** | 0.08 | 0.06 | 0.57 |
| Liq BN | 6m | +18,766 | +149 | +111 | 0.38 | 0.85 | 0.37 | 0.69 |
| Liq FIN | 1m | +7,055 | +336 | +71 | 0.13 | 0.26 | 0.12 | 0.48 |
| Liq FIN | 3m | +11,098 | +176 | +70 | 0.18 | 0.49 | 0.16 | 0.62 |
| Liq FIN | 6m | +10,320 | +82 | +74 | 0.39 | 0.94 | 0.35 | 0.59 |

**Reading the table.**
- No old-arm window is green, and none differs from the long run.
- ORB Fresh's last 6 months (-Rs 109/day against -Rs 208) is the closest to "better lately" (p = 0.10). It is still
  red, and its gross was only +Rs 14k over 242 trades.
- Liquidity BN's last 3 months are unusually good (p = 0.04 before any correction for the 18 windows tested; not
  significant after one).

### How often a winning streak happens by chance

The table counts winning months, then winning streaks: the share of all starting months followed by k green months
in a row. The "boot" columns are from a stationary bootstrap of the arm's own long-run days.

| arm | green months | 1 month (data / boot) | 2 in a row (data / boot) | 3 in a row (data / boot) |
|---|---|---|---|---|
| ORB | 3 of 63 | 4.8% / 2.1% | 0% / 0.05% | 0% / 0% |
| ORB Fresh | 10 of 63 | 15.9% / 14.2% | 3.2% / 2.6% | 0% / 0.5% |
| ORB Sweep | 10 of 63 | 15.9% / 16.1% | 1.6% / 2.3% | 0% / 0.4% |
| Range Fade | 9 of 63 | 14.3% / 10.1% | 1.6% / 1.0% | 0% / 0.05% |
| Liq BN | 35 of 63 | 55.6% / 51.2% | 33.9% / 29.9% | 21.3% / 15.8% |
| Liq FIN | 29 of 63 | 46.0% / 51.2% | 27.4% / 27.6% | 14.8% / 14.3% |

- A green week, or even a green month, for Fresh, Sweep or Fade is a roughly 1-in-7 event under their losing edge.
  Across the four arms, that happens most months somewhere.
- A 2-month streak (about 1-3%) or a 3-month streak (under 1%) would be real news. None has happened in 5 years.

## 3. Correlation of daily P&L between arms (Aug 2021 - 5 Oct 2026, 1,277 sessions)

|  | ORB | Fresh | Sweep | Fade | Liq BN | Liq FIN |
|---|---|---|---|---|---|---|
| ORB | 1 | 0.48 | -0.04 | -0.04 | 0.04 | 0.03 |
| ORB Fresh | | 1 | -0.15 | -0.06 | 0.06 | 0.03 |
| ORB Sweep | | | 1 | 0.47 | -0.06 | 0.01 |
| Range Fade | | | | 1 | -0.09 | -0.05 |
| Liq BN | | | | | 1 | 0.22 |

- The old four together against Liquidity: r = 0.01.
- **On the best 20% of combined days:** the old 4 averaged +Rs 1,170 (green on 75% of those days) and Liquidity
  +Rs 2,506 (green on 56%).
- **On the worst 20%:** the old 4 averaged **-Rs 4,997 (never green)** and Liquidity -Rs 924.

So the old arms do take part in good days. They are the noisiest component. But they make the bad days about four
times as much as they help the good ones, and over all days they cost Rs 1,400 a day (1 lot each).
- Only 26% of days are green for the old four together.
- Sweep and Fade are paired (r 0.47), and so are ORB and Fresh (r 0.48). Sweep/Fade and ORB/Fresh often take
  **opposite** sides of the same index on the same morning (30 Sep, 5 Oct).

## 4. Day-level profit protection (Boss: "trades backfired after 4k, 3k to negative")

This uses the combined minute-by-minute mark-to-market of all six arms as the app runs them: ORB, Fresh, Sweep, Fade,
and Liquidity BN 15+5 and FIN 30+5, 1 lot each, current rules. Open positions are marked at the minute close, net of
the buy charges already paid. The period is Aug 2021 to 5 Oct 2026, 1,277 sessions.

**How often the day peaks and gives back:**

| day's peak ≥ | days | share of days | median close | average giveback (peak → close) | closed red | closed below half the threshold |
|---|---|---|---|---|---|---|
| +Rs 2,000 | 327 | 26% | +1,773 | 3,459 | 29% | 39% |
| +Rs 3,000 | 205 | 16% | +2,986 | 3,739 | 18% | 28% |
| +Rs 4,000 | 148 | 12% | +3,455 | 4,173 | 12% | 28% |
| +Rs 5,000 | 106 | 8% | +4,761 | 4,946 | 13% | 31% |

**The 16 rules.** All were pre-registered.
- **The rules:**
  - (a) No new entries once the day is ≥ +X.
  - (b) Once the day reaches +X, close everything and stop if it falls back to +X/2.
  - (b') The same, falling back to breakeven.
  - (c) Once the peak reaches +X, close everything and stop if the day falls to 50% of the peak.
- X is Rs 2k, 3k, 4k or 5k.
- **Forced closes** sell at the next minute's open, -5 bps, with app charges.
- **Selection.** The rule was picked on pre-1-Oct-2025 data by Rs/day, then the holdout was run once.
- **Placebo.** The same action ("stop entries", or "close everything and stop") at the rule's own trigger minutes,
  but on randomly chosen days, with the same number of days, 200 draws. p is the chance the placebo saved at least as
  much as the rule did.

| rule | pre: fired | pre Rs/day (base -1,176) | pre p vs placebo | holdout: fired | holdout Rs/day (base -1,381) | holdout p vs placebo | P(losing day) holdout (base 62%) | P(losing month) holdout (base 92%) |
|---|---|---|---|---|---|---|---|---|
| **(a) stop entries at +2k (picked)** | 227 | -986 | 0.46 | 100 | **-949** | 0.46 | 55% | 92% |
| (a) +3k | 139 | -1,088 | 0.86 | 66 | -1,160 | 0.66 | 59% | 100% |
| (a) +4k | 101 | -1,104 | 0.72 | 47 | -1,161 | 0.32 | 61% | 92% |
| (a) +5k | 77 | -1,099 | 0.25 | 29 | -1,224 | 0.20 | 62% | 92% |
| (b) +2k → +1k | 128 | -1,093 | 0.62 | 68 | -1,192 | 0.61 | 54% | 100% |
| (b) +3k → +1.5k | 64 | -1,127 | 0.49 | 33 | -1,446 | 0.97 | 58% | 92% |
| (b) +4k → +2k | 45 | -1,143 | 0.36 | 24 | -1,403 | 0.82 | 61% | 92% |
| (b) +5k → +2.5k | 37 | -1,127 | 0.06 | 11 | -1,412 | 0.93 | 62% | 92% |
| (b') +2k → 0 | 93 | -1,117 | 0.37 | 47 | -1,232 | 0.48 | 69% | 100% |
| (b') +3k → 0 | 40 | -1,139 | 0.19 | 20 | -1,443 | 0.93 | 66% | 92% |
| (b') +4k → 0 | 25 | -1,153 | 0.23 | 11 | -1,469 | 0.99 | 64% | 92% |
| (b') +5k → 0 | 20 | -1,150 | 0.10 | 4 | -1,438 | 0.99 | 63% | 92% |
| (c) trail 50% from +2k | 161 | -1,083 | 0.77 | 83 | -1,200 | 0.73 | 53% | 100% |
| (c) trail 50% from +3k | 87 | -1,129 | 0.79 | 45 | -1,368 | 0.85 | 58% | 92% |
| (c) trail 50% from +4k | 61 | -1,144 | 0.70 | 30 | -1,351 | 0.66 | 61% | 100% |
| (c) trail 50% from +5k | 47 | -1,133 | 0.28 | 17 | -1,391 | 0.83 | 62% | 92% |

The worst day is unchanged by every rule: -Rs 11,284 before the holdout and -Rs 13,617 in it. Those days never got to
+Rs 2,000 first.

**What it means:**
- **Every rule "helps" only as much as stopping at a random time.**
  - The picked rule saved Rs 1.08 lakh in the holdout; random stops of the same kind saved Rs 1.06 lakh
    (p = 0.46).
  - The saving exists because the book loses about Rs 1,200-1,400 a day on average. Fewer minutes in the market
    means a smaller loss.
- **The fall-back and trailing locks (b, b', c) are worse than random in the holdout** (p 0.6-0.99). After a peak,
  the book is as likely to go on up as to fall back.
- **None of them makes the combined book profitable**, and the losing-month rate stays at 92-100%.
- **The named days:**
  - 30 Sep (peak +1,683) and 5 Oct (peak 0) **never reach +2k**, so no rule touches them.
  - 1 Oct (peak +2,554) is the one day saved: (c) trail 50% from +2k turns -4,185 into +1,611; (b)/(b') +2k turn it
    into -579.
  - **The same rules cut 28 Sep from +16,626 to +961**: the morning dip after the +2k peak triggers them before the
    Liquidity winners run.
- **Verdict on day locks.** A day-level lock would not fix Boss's problem and could cost him his best days. The
  giveback is the losing arms' expectancy showing up intraday, not a timing pattern a lock can harvest.

## 5. What to do (for Boss)

- **Judge the four old arms on the long run, not on 1-5 Oct.**
  - Five years of the app's own rules: about -Rs 3.5 lakh a year per lot together (-Rs 1,400 a day).
  - Not one green 3-month window, ever, in any of the four.
  - The last 6 months are no better than average.
  - The good 1 Oct and 5 Oct in the paper account were not theirs: they lost Rs 3.6k and Rs 6.3k those days.
- **If they stay on paper (Boss's choice, 7 Oct)**, judge them only on trades from 7 Oct, with the app's PassRule:
  - 60 closed trades or 40 trading days;
  - net > 0 after charges and t > 2;
  - green on up days AND down days;
  - green without the best 3 trades.
- **Kill rule:** red after 40 trading days (about 2 months). ORB's loss is detectable at 80% power in about 35 days.
  - At their long-run edge, the chance of looking green after 20 days is 3% (ORB) to 16% (Sweep). After 60 days it is
    0-4%.
  - So 40-60 days rejects them with about 95% confidence. A real edge would also have to show up in an un-cherry-picked
    2-3-month streak, which has never happened in 63 months.
- **To find out where the +Rs 5k days came from, export the paper trade log for 1 and 5 Oct by strategy.** No replay of
  ORB, Fresh, Sweep, Fade or Liquidity produces them.
- **A fix that does address the giveback** (a suggestion, not tested here as a rule): stop ORB re-entering every 10
  minutes in chop. A daily cap like Sweep/Fade's 2, plus "one index one side" (already added 06 Oct). Charges alone
  cost ORB Rs 1.03 lakh in the last 6 months.

## Appendix: trade-by-trade replay, 28 Sep - 6 Oct 2026 (current rules, app paper fills and charges, 1 lot)

- "MTM peak / trough" are the combined day's mark-to-market extremes.
- Times are the entry and exit minutes.
- Exit reasons:
  - `stop`: the -40 premium stop.
  - `profit_lock`: the ladder.
  - `target`: +40, or +80 for Sweep.
  - `index_stop`, `index_target`, `time_stop` (20 minutes without +5%) and `exit_at` (failed break / new level):
    Liquidity's exits.
  - `square_off`: 15:10.

### 2026-09-28  (Boss: (2, -56))
| entry | exit | arm/book | contract | entry Rs | exit Rs | why | net |
|---|---|---|---|---|---|---|---|
| 09:20 | 12:50 | liq5_BN | BANKNIFTY 55000 PE x30 | 241.47 | 443.53 | exit_at | +5,986 |
| 09:20 | 12:00 | liq5_FIN | FINNIFTY 24900 PE x60 | 121.66 | 218.39 | exit_at | +5,728 |
| 09:30 | 15:10 | liq15_BN | BANKNIFTY 55000 PE x30 | 268.43 | 509.15 | square_off | +7,141 |
| 10:10 | 10:18 | orb | BANKNIFTY 54900 PE x30 | 384.39 | 394.19 | profit_lock | +219 |
| 10:10 | 10:18 | orb_fresh | BANKNIFTY 54900 PE x30 | 384.39 | 394.19 | profit_lock | +219 |
| 10:25 | 10:28 | orb | BANKNIFTY 54900 PE x30 | 371.64 | 371.45 | profit_lock | -79 |
| 10:35 | 10:40 | orb | BANKNIFTY 54900 PE x30 | 380.89 | 380.70 | profit_lock | -80 |
| 10:50 | 10:51 | orb | BANKNIFTY 54900 PE x30 | 415.21 | 415.00 | profit_lock | -83 |
| 11:00 | 11:02 | orb | BANKNIFTY 54900 PE x30 | 394.00 | 393.80 | profit_lock | -81 |
| 11:10 | 11:14 | orb | BANKNIFTY 54900 PE x30 | 420.36 | 420.15 | profit_lock | -83 |
| 11:20 | 11:21 | orb | BANKNIFTY 54900 PE x30 | 399.05 | 398.85 | profit_lock | -82 |
| 11:30 | 11:36 | orb | BANKNIFTY 54900 PE x30 | 394.40 | 404.20 | profit_lock | +218 |
| 11:45 | 11:49 | orb | BANKNIFTY 54900 PE x30 | 366.53 | 376.34 | profit_lock | +220 |
| 11:55 | 11:59 | orb | BANKNIFTY 54900 PE x30 | 391.90 | 391.70 | profit_lock | -81 |
| 12:05 | 12:26 | orb | BANKNIFTY 54900 PE x30 | 395.15 | 354.79 | stop | -1,284 |
| 12:35 | 12:53 | orb | BANKNIFTY 54900 PE x30 | 385.79 | 385.60 | profit_lock | -80 |
| 13:00 | 13:06 | orb | BANKNIFTY 54900 PE x30 | 389.39 | 388.41 | profit_lock | -104 |
| 13:15 | 13:26 | orb | BANKNIFTY 54900 PE x30 | 387.74 | 347.40 | stop | -1,283 |
| 13:35 | 13:50 | orb | BANKNIFTY 54900 PE x30 | 394.20 | 386.56 | profit_lock | -304 |
| 14:00 | 14:10 | orb | BANKNIFTY 54900 PE x30 | 377.49 | 397.29 | profit_lock | +519 |

per arm: {'liq_bn': {'count': 2, 'sum': 13127.0}, 'liq_fin': {'count': 1, 'sum': 5728.0}, 'orb': {'count': 16, 'sum': -2448.0}, 'orb_fresh': {'count': 1, 'sum': 219.0}}
day: 20 trades, net +16,626, gross +18,130; MTM peak +22,007 at 11:10, trough +0 at 09:15
curve: 10:00 +11,798, 10:30 +16,021, 11:00 +19,160, 11:30 +17,497, 12:00 +17,567, 12:30 +14,741, 13:00 +16,335, 13:30 +14,202, 14:00 +14,434, 14:30 +16,000, 15:00 +17,156

### 2026-09-29  (Boss: (2, 589))
| entry | exit | arm/book | contract | entry Rs | exit Rs | why | net |
|---|---|---|---|---|---|---|---|
| 10:25 | 10:32 | orb_sweep | BANKNIFTY 54000 CE x30 | 114.66 | 114.60 | profit_lock | -57 |
| 10:35 | 10:37 | range_fade | BANKNIFTY 54000 CE x30 | 123.96 | 123.04 | profit_lock | -84 |
| 12:05 | 12:28 | range_fade | BANKNIFTY 54000 PE x30 | 43.32 | 83.28 | target | +1,146 |

per arm: {'orb_sweep': {'count': 1, 'sum': -57.0}, 'range_fade': {'count': 2, 'sum': 1063.0}}
day: 3 trades, net +1,005, gross +1,169; MTM peak +1,005 at 12:28, trough -287 at 12:16
curve: 10:00 +0, 10:30 +423, 11:00 -141, 11:30 -141, 12:00 -141, 12:30 +1,005, 13:00 +1,005, 13:30 +1,005, 14:00 +1,005, 14:30 +1,005, 15:00 +1,005

### 2026-09-30  (Boss: (12, -6014))
| entry | exit | arm/book | contract | entry Rs | exit Rs | why | net |
|---|---|---|---|---|---|---|---|
| 09:20 | 09:21 | liq5_BN | BANKNIFTY 54300 CE x30 | 1243.97 | 1294.45 | index_target | +1,376 |
| 10:10 | 10:12 | orb | BANKNIFTY 54600 CE x30 | 1155.58 | 1149.33 | profit_lock | -317 |
| 10:10 | 10:12 | orb_fresh | BANKNIFTY 54600 CE x30 | 1155.58 | 1149.33 | profit_lock | -317 |
| 10:20 | 10:29 | orb | BANKNIFTY 54600 CE x30 | 1184.54 | 1143.41 | stop | -1,363 |
| 10:35 | 10:40 | orb_sweep | BANKNIFTY 54600 PE x30 | 702.95 | 662.29 | stop | -1,315 |
| 10:35 | 10:36 | range_fade | BANKNIFTY 54600 PE x30 | 702.95 | 702.60 | profit_lock | -108 |
| 10:40 | 10:44 | orb | BANKNIFTY 54600 CE x30 | 1132.72 | 1091.61 | stop | -1,359 |
| 10:40 | 10:44 | orb_fresh | BANKNIFTY 54600 CE x30 | 1132.72 | 1091.61 | stop | -1,359 |
| 10:45 | 11:01 | range_fade | BANKNIFTY 54600 PE x30 | 699.25 | 658.59 | stop | -1,314 |
| 10:50 | 11:06 | orb | BANKNIFTY 54600 CE x30 | 1130.56 | 1129.99 | profit_lock | -145 |
| 10:50 | 11:06 | orb_fresh | BANKNIFTY 54600 CE x30 | 1130.56 | 1129.99 | profit_lock | -145 |
| 11:00 | 11:01 | orb_sweep | BANKNIFTY 54600 PE x30 | 698.80 | 658.14 | stop | -1,314 |
| 11:15 | 11:28 | orb | BANKNIFTY 54600 CE x30 | 1127.06 | 1166.48 | target | +1,053 |
| 11:35 | 11:39 | orb | BANKNIFTY 54600 CE x30 | 1171.59 | 1168.22 | profit_lock | -231 |
| 11:45 | 11:50 | orb | BANKNIFTY 54600 CE x30 | 1164.93 | 1162.17 | profit_lock | -213 |
| 12:00 | 12:05 | orb | BANKNIFTY 54600 CE x30 | 1179.59 | 1198.99 | profit_lock | +450 |
| 12:15 | 12:22 | orb | BANKNIFTY 54600 CE x30 | 1195.60 | 1234.98 | target | +1,047 |
| 12:25 | 12:45 | liq5_FIN | FINNIFTY 24700 CE x60 | 536.17 | 556.82 | time_stop | +1,113 |
| 12:30 | 12:35 | orb | BANKNIFTY 54600 CE x30 | 1252.38 | 1291.73 | target | +1,042 |
| 12:45 | 12:46 | orb | BANKNIFTY 54600 CE x30 | 1285.64 | 1324.98 | target | +1,039 |
| 12:55 | 12:57 | orb | BANKNIFTY 54600 CE x30 | 1327.26 | 1326.59 | profit_lock | -162 |
| 13:05 | 13:12 | orb | BANKNIFTY 54600 CE x30 | 1317.51 | 1316.85 | profit_lock | -161 |
| 13:20 | 13:28 | orb | BANKNIFTY 54600 CE x30 | 1316.71 | 1325.44 | profit_lock | +121 |
| 13:35 | 13:36 | orb | BANKNIFTY 54600 CE x30 | 1314.46 | 1293.70 | profit_lock | -762 |
| 13:45 | 13:56 | orb | BANKNIFTY 54600 CE x30 | 1232.07 | 1279.16 | target | +1,275 |

per arm: {'liq_bn': {'count': 1, 'sum': 1376.0}, 'liq_fin': {'count': 1, 'sum': 1113.0}, 'orb': {'count': 16, 'sum': 1315.0}, 'orb_fresh': {'count': 3, 'sum': -1820.0}, 'orb_sweep': {'count': 2, 'sum': -2629.0}, 'range_fade': {'count': 2, 'sum': -1422.0}}
day: 25 trades, net -2,067, gross +1,109; MTM peak +1,683 at 10:10, trough -8,364 at 11:17
curve: 10:00 +1,376, 10:30 -620, 11:00 -6,865, 11:30 -6,625, 12:00 -7,121, 12:30 -5,224, 13:00 -2,540, 13:30 -2,580, 14:00 -2,067, 14:30 -2,067, 15:00 -2,067

### 2026-10-01  (Boss: (38, 5032))
| entry | exit | arm/book | contract | entry Rs | exit Rs | why | net |
|---|---|---|---|---|---|---|---|
| 10:35 | 10:37 | orb | BANKNIFTY 54700 CE x30 | 1204.35 | 1210.09 | profit_lock | +39 |
| 10:35 | 10:37 | orb_fresh | BANKNIFTY 54700 CE x30 | 1204.35 | 1210.09 | profit_lock | +39 |
| 10:45 | 10:53 | orb | BANKNIFTY 54700 CE x30 | 1172.44 | 1171.85 | profit_lock | -148 |
| 10:50 | 11:17 | orb_sweep | BANKNIFTY 54700 PE x30 | 632.07 | 651.74 | profit_lock | +497 |
| 10:50 | 10:59 | range_fade | BANKNIFTY 54700 PE x30 | 632.07 | 631.75 | profit_lock | -102 |
| 11:10 | 11:15 | liq5_FIN | FINNIFTY 24750 PE x60 | 371.44 | 371.06 | exit_at | -123 |
| 11:40 | 11:57 | range_fade | BANKNIFTY 54700 PE x30 | 622.56 | 629.83 | profit_lock | +126 |
| 11:45 | 11:48 | orb | BANKNIFTY 54700 CE x30 | 1191.40 | 1150.25 | stop | -1,364 |
| 11:45 | 11:48 | orb_fresh | BANKNIFTY 54700 CE x30 | 1191.40 | 1150.25 | stop | -1,364 |
| 11:50 | 12:19 | orb_sweep | BANKNIFTY 54700 PE x30 | 631.82 | 711.46 | target | +2,292 |
| 12:00 | 12:06 | orb | BANKNIFTY 54700 CE x30 | 1172.14 | 1131.02 | stop | -1,362 |
| 12:00 | 12:06 | orb_fresh | BANKNIFTY 54700 CE x30 | 1172.14 | 1131.02 | stop | -1,362 |
| 12:20 | 12:40 | liq5_BN | BANKNIFTY 54900 PE x30 | 802.45 | 816.29 | time_stop | +310 |
| 12:50 | 13:10 | liq5_BN | BANKNIFTY 54600 PE x30 | 861.33 | 839.73 | time_stop | -755 |
| 12:50 | 12:51 | orb | BANKNIFTY 54700 PE x30 | 904.10 | 909.54 | profit_lock | +51 |
| 12:50 | 12:51 | orb_fresh | BANKNIFTY 54700 PE x30 | 904.10 | 909.54 | profit_lock | +51 |
| 13:00 | 13:01 | orb | BANKNIFTY 54700 PE x30 | 1047.72 | 1006.69 | stop | -1,350 |
| 13:10 | 13:11 | orb | BANKNIFTY 54700 PE x30 | 887.29 | 886.85 | profit_lock | -123 |
| 13:20 | 13:23 | orb | BANKNIFTY 54700 PE x30 | 954.73 | 954.25 | profit_lock | -129 |
| 13:30 | 13:31 | orb | BANKNIFTY 54700 PE x30 | 968.48 | 954.52 | profit_lock | -534 |
| 13:40 | 13:41 | orb | BANKNIFTY 54700 PE x30 | 941.87 | 981.38 | target | +1,069 |
| 13:50 | 13:53 | orb | BANKNIFTY 54700 PE x30 | 1023.96 | 1023.45 | profit_lock | -135 |
| 14:00 | 14:01 | orb | BANKNIFTY 54700 PE x30 | 1083.69 | 1094.25 | profit_lock | +192 |

per arm: {'liq_bn': {'count': 2, 'sum': -445.0}, 'liq_fin': {'count': 1, 'sum': -123.0}, 'orb': {'count': 12, 'sum': -3796.0}, 'orb_fresh': {'count': 4, 'sum': -2635.0}, 'orb_sweep': {'count': 2, 'sum': 2789.0}, 'range_fade': {'count': 2, 'sum': 24.0}}
day: 23 trades, net -4,185, gross -1,541; MTM peak +2,554 at 12:58, trough -5,311 at 13:31
curve: 10:00 +0, 10:30 +0, 11:00 +137, 11:30 +202, 12:00 -2,763, 12:30 -998, 13:00 -237, 13:30 -5,128, 14:00 -4,114, 14:30 -4,185, 15:00 -4,185

### 2026-10-05  (Boss: (48, 4955))
| entry | exit | arm/book | contract | entry Rs | exit Rs | why | net |
|---|---|---|---|---|---|---|---|
| 09:20 | 09:22 | liq5_BN | BANKNIFTY 55000 CE x30 | 1049.52 | 987.96 | index_stop | -1,965 |
| 09:20 | 09:22 | liq5_FIN | FINNIFTY 24750 CE x60 | 501.85 | 501.35 | index_stop | -149 |
| 09:35 | 09:38 | liq5_FIN | FINNIFTY 24800 CE x60 | 509.20 | 495.10 | index_stop | -964 |
| 10:40 | 10:44 | range_fade | BANKNIFTY 55100 CE x30 | 826.26 | 835.84 | profit_lock | +181 |
| 10:50 | 10:54 | range_fade | BANKNIFTY 55100 CE x30 | 841.92 | 801.10 | stop | -1,329 |
| 10:55 | 10:56 | orb | BANKNIFTY 55100 PE x30 | 934.22 | 893.31 | stop | -1,339 |
| 10:55 | 10:56 | orb_fresh | BANKNIFTY 55100 PE x30 | 934.22 | 893.31 | stop | -1,339 |
| 11:05 | 11:07 | orb | BANKNIFTY 55100 PE x30 | 908.30 | 907.85 | profit_lock | -125 |
| 11:15 | 11:16 | orb | BANKNIFTY 55100 PE x30 | 958.63 | 998.13 | target | +1,067 |
| 11:25 | 11:28 | orb | BANKNIFTY 55100 PE x30 | 971.99 | 971.50 | profit_lock | -131 |
| 11:35 | 11:36 | orb | BANKNIFTY 55100 PE x30 | 922.71 | 962.23 | target | +1,070 |
| 11:45 | 11:46 | orb | BANKNIFTY 55100 PE x30 | 995.45 | 1004.95 | profit_lock | +166 |
| 11:55 | 12:00 | orb | BANKNIFTY 55100 PE x30 | 996.95 | 1052.97 | target | +1,559 |
| 12:10 | 12:18 | orb | BANKNIFTY 55100 PE x30 | 1065.98 | 1024.97 | stop | -1,351 |
| 12:25 | 12:28 | orb | BANKNIFTY 55100 PE x30 | 1000.95 | 959.99 | stop | -1,345 |
| 12:35 | 12:40 | orb | BANKNIFTY 55100 PE x30 | 975.49 | 934.56 | stop | -1,342 |
| 12:50 | 12:53 | orb | BANKNIFTY 55100 PE x30 | 931.82 | 971.33 | target | +1,070 |
| 13:00 | 13:01 | orb | BANKNIFTY 55100 PE x30 | 967.93 | 967.45 | profit_lock | -130 |
| 13:10 | 13:22 | orb | BANKNIFTY 55100 PE x30 | 975.54 | 930.07 | stop | -1,478 |
| 13:30 | 13:36 | orb | BANKNIFTY 55100 PE x30 | 971.29 | 930.37 | stop | -1,342 |
| 13:45 | 13:46 | orb_sweep | BANKNIFTY 55100 CE x30 | 865.28 | 855.82 | profit_lock | -392 |
| 14:10 | 14:15 | orb_sweep | BANKNIFTY 55100 CE x30 | 833.42 | 845.23 | profit_lock | +247 |

per arm: {'liq_bn': {'count': 1, 'sum': -1965.0}, 'liq_fin': {'count': 2, 'sum': -1113.0}, 'orb': {'count': 14, 'sum': -3650.0}, 'orb_fresh': {'count': 1, 'sum': -1339.0}, 'orb_sweep': {'count': 2, 'sum': -145.0}, 'range_fade': {'count': 2, 'sum': -1148.0}}
day: 22 trades, net -9,359, gross -6,844; MTM peak +0 at 09:15, trough -9,717 at 14:12
curve: 10:00 -3,078, 10:30 -3,078, 11:00 -6,903, 11:30 -6,092, 12:00 -3,296, 12:30 -5,992, 13:00 -5,783, 13:30 -7,984, 14:00 -9,606, 14:30 -9,359, 15:00 -9,359

### 2026-10-06  (Boss: (40, -4551))
| entry | exit | arm/book | contract | entry Rs | exit Rs | why | net |
|---|---|---|---|---|---|---|---|
| 10:35 | 10:53 | range_fade | BANKNIFTY 54900 PE x30 | 655.33 | 674.99 | profit_lock | +495 |
| 11:10 | 11:34 | range_fade | BANKNIFTY 54900 PE x30 | 647.17 | 666.84 | profit_lock | +496 |

per arm: {'range_fade': {'count': 2, 'sum': 991.0}}
day: 2 trades, net +991, gross +1,180; MTM peak +1,279 at 11:31, trough -299 at 10:37
curve: 10:00 +0, 10:30 +0, 11:00 +495, 11:30 +1,252, 12:00 +991, 12:30 +991, 13:00 +991, 13:30 +991, 14:00 +991, 14:30 +991, 15:00 +991
