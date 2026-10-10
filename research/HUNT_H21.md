# HUNT h21: can "smart" entries and an adaptive profit lock make the old ORB arms pay? (option buying only)

Code: `research/hunt/h21/`.
- `PREREG.md` was written before any result.
- `sim.py` holds the packs, features and exits; `rules.py` the variants and position engines; `analyse.py` runs
  `pre` and then `holdout` (run once); `info.py` builds the information table.
- Results: `pre_variants.csv`, `pre_report.json`, `holdout_report.json`, `info_report.json`.

## Plain verdict

**No. No "smart" version of ORB, ORB Fresh, ORB Sweep or Range Fade is profitable after costs.** I tested every
pre-registered entry rule and adaptive lock, on each arm alone and on all four combined. Every one loses before the
holdout, and every pick loses again in the locked holdout.

**Switch all four off.** They are not additive to Liquidity 15+5. Under the app's one-index guard they also take
Liquidity's place on BANKNIFTY: in the holdout they blocked 31-68 Liquidity trades worth +Rs 7.7k to +18k.

- **Scope.** 31 variants plus the guard comparison (V09), each on 4 arms and the combined book: **156 trials**.
  - The PREREG says 33 variants / 165 trials. That was a counting slip: R0 is a reference, and V09 exists only for
    the combined book. BH and SPA were run over the 156 trials actually tested.
- **Before the holdout (Aug 2021 to Sep 2025, 996 sessions):**
  - 156 of 156 trials are net negative.
  - BH q = 1.0 for all of them.
  - SPA / White RC p = 1.0 per arm and over all 156.
  - Walk-forward (test years 2023, 2024 and 2025 to Sep): out-of-sample net is negative for every arm. Only ORB
    and Fresh had a positive test year (2024, +Rs 4.2k).
  - **Nothing was promoted.**
- **Holdout (1 Oct 2025 to 5 Oct 2026, 248 sessions, run once).** These are the best-before-holdout picks, net of app
  costs and h10 impact at κ 0.02, 1 lot (30 units):

| arm | pick (chosen before the holdout) | trades | gross Rs/day | **net Rs/day** | net, κ 0 / 0.04 | max DD | worst month | random-entry p | P(losing month) |
|---|---|---|---|---|---|---|---|---|---|
| ORB | V32: 1 trade/day, signal ≤ 11:30, strong-open days only | 75 | -19 | **-70** | -63 / -80 | -19.5k | -3.7k | 0.64 | 77% |
| ORB Fresh | V32 (identical trades to ORB; see note 1) | 75 | -19 | **-70** | -63 / -80 | -19.5k | -3.7k | 0.67 | 77% |
| ORB Sweep | V31: PKG + opening direction + daily trend + 1×ATR trail | 66 | -50 | **-93** | -89 / -99 | -25.8k | -7.0k | 0.89 | 72% |
| Range Fade | V31 | 77 | -67 | **-115** | -116 / -124 | -29.3k | -5.9k | 0.98 | 79% |
| All four combined (guard) | V32 | 229 | -111 | **-262** | -235 / -291 | -65.0k | -13.7k | 0.96 | 89% |

Note 1: with at most 1 trade a day, ORB and Fresh take the same trade. The day's first close beyond the opening range
is always a "fresh" break.

**Rs 5,000/day from these arms: NO.** No version of them makes money at any size, and a bigger size only adds impact.

## Why "smarter" does not fix them

**The discipline rules work only by trading less.** Combined book with the guard, before the holdout:

| combined book (κ 0.02, 1 lot) | trades | Rs/day before holdout | Rs/day in holdout (information only) |
|---|---|---|---|
| App today (ladder app-side), no guard | 10,917 | -1,413 | -2,210 |
| App today + one-index guard (06 Oct) | 7,125 | -884 | -1,790 |
| Fixed lock (h20 F1/F2) + guard | 8,521 | -893 | -1,668 |
| PKG (max 2/day, off after 2 losses, 30-min cooldown) + guard | 3,920 | -476 | -723 |
| Max 1 trade per arm per day + guard | 2,637 | -340 | -530 |
| Best smart combination (V32) | 1,009 | -130 | -262 |
| **Arms off** | 0 | **0** | **0** |

- Each cut in trades cuts the loss roughly in proportion, but the loss per trade does not improve: about -Rs 100 to
  -150 before the holdout and -Rs 230 to -370 in it at today's lot. That is the signature of no edge.
- Gross per trade is around zero or negative for every arm. Charges are about Rs 72 a trade before the holdout and
  Rs 92-109 in it. Impact at κ 0.02 adds Rs 10 before the holdout and up to Rs 85 a trade in it, for ORB at today's
  premiums.

**Regime filters (each on top of PKG; before the holdout, net Rs).** None turned an arm green.

| filter | ORB | Fresh | Sweep | Fade |
|---|---|---|---|---|
| PKG alone | -179k | -160k | -197k | -222k |
| skip narrow OR (vs ATR) | -110k | -89k | -143k | -165k |
| skip wide OR | -123k | -116k | -113k | -154k |
| middle-third OR only | -54k | -45k | -59k | -97k |
| first-30-min strength, arm-aligned | -92k | -78k | -96k | -123k |
| side = first-30-min direction | -122k | -119k | -80k | -96k |
| VIX not falling / arm-aligned | -113k | -73k | -121k / -103k | -131k / -125k |
| dealer gamma, arm-aligned / skip high (h18 B_lvl) | -155k | -131k | -114k / -107k | -154k / -142k |
| daily trend agreement (SMA20) | -111k | -101k | -114k | -114k |
| 60-min trend agreement | -179k | -154k | -128k | -124k |
| entries ≤ 11:30 / > 11:30 | -116k / -171k | -93k / -77k | -117k / -129k | -155k / -117k |

- The filters "help" only in proportion to how many trades they remove.
- Random entries with identical exits on the same days do as well or better: p = 0.18 to 1.0. The one exception is
  ORB/Fresh V32, with p = 0.01 / 0.048 before the holdout. Its gross was +Rs 19k over 351 trades, which is entry
  content too small to pay the charges. **It vanished in the holdout:** gross -Rs 4.7k, p = 0.64.

**Adaptive locks (FIXED mechanics: resting stop moved to each rung on minute highs; on top of PKG).** All of them
were worse than or equal to PKG with the plain ladder:

| lock | ORB | Fresh | Sweep | Fade |
|---|---|---|---|---|
| plain ladder (PKG) | -179k | -160k | -197k | -222k |
| premium-ATR ladder 1A/2A/3A, target 4A | -217k | -205k | -199k | -259k |
| half-ATR ladder, target 2A | -176k | -187k | -179k | -252k |
| ladder only after 10 / 20 min in trade | -175k / -211k | -162k / -181k | -210k / -203k | -236k / -239k |
| trail 1×ATR (target kept) | -181k | -186k | -218k | -262k |
| trail 2× / 1.5×ATR, no target | -187k / -225k | -195k / -184k | -178k / -180k | -235k / -230k |

**The h20 lock fix itself is neutral for P&L.** Its job is to make the paper record honest, not to make money:

| arm | app today | fixed lock |
|---|---|---|
| ORB (before the holdout, κ 0.02) | -704k | -724k |
| Fresh | -237k | -209k |
| Sweep | -228k | -205k |
| Fade | -238k | -219k |

- In the holdout the fixed lock makes the combined book -Rs 1,668/day against -Rs 1,790/day today.
- With the fixed lock, 69% of ORB exits are lock exits. Gross improves (ORB +Rs 60k before the holdout), but the
  extra re-entries pay more in charges.
- The h20 fixes (F1-F3) are still worth applying so the paper record is honest. They will not turn the arms green.

## Additive to Liquidity 15+5? No: they subtract

The combined book was run with Liquidity's BANKNIFTY positions inside the app's guard, which allows one automatic BN
position at a time (no opposite side, at most one per side). Liquidity FINNIFTY was added unchanged.

| | Liquidity alone | + combined V32 | + ORB/Fresh V32 | + Sweep V31 | + Fade V31 |
|---|---|---|---|---|---|
| before the holdout, Rs/day (Sharpe) | **+157 (1.01)** | +14 (0.10) | +122 (0.89) | +102 (0.74) | +72 (0.52) |
| holdout, Rs/day (Sharpe) | **+348 (1.68)** | +31 (0.17) | +200 to +206 (1.14-1.17) | +185 (1.00) | +175 (0.96) |
| Liquidity BN trades lost to the guard (holdout) | – | 38 (+Rs 17.5k) | 31 (+Rs 18.3k) | 33 (+Rs 17.3k) | 33 (+Rs 14.5k) |

- The daily correlation with Liquidity is about 0 (|r| ≤ 0.15), so there is no hedge value either.
- Every addition lowers both total net and Sharpe, before the holdout and in it.
- With the app's current arms as they are (no smart rules), Liquidity plus the four arms is -Rs 726/day before the
  holdout and -Rs 1,428/day in it, against +Rs 157 and +Rs 348 for Liquidity alone.

## Recommendation for the app (precise)

1. **Switch off ORB, ORB Fresh, ORB Sweep and Range Fade as automatic arms, paper and live.**
   - ORB first: as it runs today it costs **-Rs 1,387/day** at 1 lot in the holdout (κ 0.02; -Rs 857 at κ 0).
   - Then Sweep (-335), Fade (-324) and Fresh (-164).
   - Keep Liquidity 15+5 alone. That alone frees Liquidity's blocked BN trades: about 30-70 a year, worth roughly
     Rs 8-18k a year at 1 lot.
2. **If Boss wants one kept on paper for observation only:**
   - Use ORB Fresh with **at most 1 trade a day**, only when the signal bar ends **by 11:30**, and only when **the
     first 30 minutes' move (|09:44 close − 09:15 open| / 14-day ATR) is at or above its past median**. That is V32.
   - It is the least-bad rule: -Rs 13/day before the holdout, -Rs 70/day in it.
   - **Exclude it from the AutoExposure guard against Liquidity, or give Liquidity priority.** Otherwise it costs
     Liquidity trades.
   - Judge it with the PassRule from h19 and switch it off if it is red after 40 trading days.
3. **Do not ship** the ATR-scaled ladder, the time-gated lock or the ATR trails. Each was worse than the plain ladder
   on every arm.
4. **Apply the h20 lock fixes (F1-F3)** for an honest paper record. They do not change the verdict.

## Method (short)

- **Rules.** The app's current rules are ported exactly as in h20.
  - App-mode output reproduces h20 to the rupee, before the holdout and in it: ORB -632,101 / -212,535, Fresh
    -216,779 / -24,228, Sweep -217,158 / -74,341, Fade -227,874 / -67,492.
  - BANKNIFTY ATM strike from the 09:20 bar, nearest expiry, entries refused at a premium of Rs 40 or less.
- **Fills and costs.** App fills (±5 bps; stops -10 bps), app SandboxCosts, lot as of each date. h10 square-root
  impact is applied on entry and exit (κ 0.02 central; 0.01, 0.04 and 0 reported).
  - "Gross" means the raw price move: no slippage, charges or impact.
- **Fixed lock.** -40 resting SL-M. When the best minute high since entry (counted from the next minute) earns a rung,
  the SL-M is raised to the rung. The rung is floored at entry plus round-trip charges and is never lowered. It fills
  at the lower of the level and the minute's open, -10 bps. The target is still an app-side check on the minute close,
  sold at the next open. Square-off is at 15:10.
- **Features.** All are known at the signal, with ranks from earlier sessions only.
  - Opening-range width and first-30-minute move, each scaled by ATR14.
  - VIX at the signal minute against the previous close.
  - h18's dealer-gamma level (B_lvl), with past-only terciles.
  - Daily SMA20 trend and the 60-minute index change.
- **Random baseline.** 5 alternatives per real signal: same day and arm, a random 5-minute decision bar in the arm's
  window, a coin-flip side, the same strike and the same exits. p comes from obuy `random_baseline`.
- **Statistics.** BH over the 156 trials (losing trials get p = 1); Hansen SPA / White RC on the daily-P&L matrix
  per unit and overall. P(losing month) is a stationary block bootstrap of 21-session months.
- **Caveats.**
  - There is no bid/ask in the data, so the spread is modelled by bps plus impact.
  - The h10 impact model was built for size. At 1 lot it may overstate costs in the holdout. The κ = 0 rows show the
    verdict does not depend on it.
  - The gamma panel ends 29 Sep 2026; later days pass that filter.
  - Liquidity trades come from h19's port with its costs and no impact.
