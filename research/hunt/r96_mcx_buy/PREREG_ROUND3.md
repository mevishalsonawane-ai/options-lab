# R96 round 3 pre-registration: crude NR7 refinements + three literature ideas (21 Oct 2026)

Boss, 21 Oct: "Run until we found something, commodities options buying strategy". Written and committed **before any
round-3 statistic**. Everything in PREREG_ROUND1.md s2-s5 and s7 and PREREG_ROUND2.md s2-s5 still holds unless changed
here: the same design (5 Aug 2025 - 14 Jul 2026), the same four walk-forward folds, the **same LOCKED holdout
(15 Jul - 19 Oct 2026, never read so far)**, the same costs (Zerodha MCX option charges + 0.30% / 0.60% half-spread,
natural gas 0.45% / 0.90%; gates also at 2x), twins (500, seed 96, same book and calendar month), the -40% hard stop and
the round-1 lock ladder (except R3C), the round-2 **edge take-profit on every rule** (every exit on a real print where
one exists; model-priced exits flagged and limited by gate 8), and BH over the **cumulative ledger** (rounds 1-2's 17
ideas + these 10 = 27).

Signal source of every rule: the MCX future (minute closes of the book's near future; daily rules: the book's
near-month daily continuous future). Every trade buys one option (1-ITM: call ATM-1 / put ATM+1 from the stored
strikes); every trade has a hard stop and a profit lock (the ladder, or R3C's trail on the future).

## 1. Data check (21 Oct, no statistic)

- Lake copied with `rclone copy gdrive:IraAlgo-data-lake/...` (options_rolling_1m MONTH books of the 6 option
  underlyings to 19 Oct 2026; fut_daily_continuous). The lake still stores ATM+/-3 only (offsets -3..+3 on every
  book), so round 2's R3-0 (N1 on ATM+/-10 real prints) cannot run. No Dhan token was used or tested this round.

## 2. Books

- crude = CRUDEOILM (x10) + CRUDEOIL (x100); energy = CRUDEOIL (x100) + NATURALGAS (x1250) (R2D's books);
  4 minis = CRUDEOILM, NATGASMINI, GOLDM, SILVERM. The pooled rupee total is dominated by the big books; the median
  1-lot stop risk and the capital for 2% are reported.

## 3. The ten rules (each one BH test, pooled over its books)

Daily NR rules: the next session's first future minute close beyond the NR day's minute high / low (from 09:15 to the
break window's end) -> enter the next minute; underlying stop at the NR day's opposite extreme; 3 sessions; twin =
the same clock and ATR-scaled stop on a random session of the same month (round 1 T-tod).

| id | idea (source) | change from R2A / R2D | books | twin | history gate |
|---|---|---|---|---|---|
| **R3A** | NR7 on crude only (R3-1; **books chosen after seeing round 2**) | none (R2A exits) | crude | T-tod | NR7 daily |
| **R3B** | NR4 on crude (R3-2, Crabel) | NR4 = range below each of the previous 3 | crude | T-tod | NR4 daily |
| **R3C** | NR7 with a future trail (R3-3) | **no premium ladder**; the future's stop moves to the entry minute's price once the best minute close is +1.0 ATR14 in favour, then to best close - 1.5 ATR14 (never back); -40% stop and edge kept | energy | T-tod | NR7 daily |
| **R3D** | NR7 in a low-vol regime (R3-4) | only NR7 days whose ATR14 / close is in the bottom third of its min..max over the last 250 sessions | energy | T-tod (no filter) | NR7 + filter daily |
| **R3E** | weekly NR7 (R3-5) | the narrowest ISO week of 7 (back-adjusted high - low; a week with a roll never qualifies) -> the first minute close beyond that week's minute-future high / low during the next ISO week's sessions (09:15-22:00); stop at the week's opposite extreme; **5 sessions**; the NR week and the break in the same option contract segment | energy | T-tod | weekly NR7 daily (twin: a random week of the same year) |
| **R3F** | early breaks only (R3-6, Crabel) | breaks between 09:15 and **11:15** only | energy | T-tod | NR7 daily (cannot test the clock) |
| **R3G** | US-open entry (R3-7) | a break between 09:15 and the minute before the US cash open (09:30 ET = 19:00 / 20:00 IST) that is **still beyond the level at that minute** enters at the US open | energy | T-tod at the twin day's own US open | NR7 daily (cannot test the clock) |
| **R3H** | EIA-day intraday momentum (Wen, Indriawan, Lien & Xu, Energy Journal 44(5) 2023) | on EIA crude days (R1 event calendar) the future's 11:00 -> 11:30 ET move -> 1-ITM that way at the next minute; stop the 11:00 ET price; out at the session square-off | crude | same clock on a non-EIA session of the month, its own sign | none (intraday) |
| **R3I** | market intraday momentum, last half-hour (Baltussen, Da, Lammers & Martens, JFE 142 2021) | at session close - 45 min, the future's move since the previous session's last minute (same contract segment); trade when abs(move) >= the 67th percentile of the last 60 sessions' moves (>= 20 known) -> 1-ITM that way; stop 0.25 ATR14 behind; out at the square-off (close - 15, a ~30-minute hold) | 4 minis | random session of the month, its own sign, no size filter | none (intraday) |
| **R3J** | ORB in a high-vol regime (Holmberg, Loennbark & Lundstroem, FRL 10(1) 2013; Lundstroem WP 861) | only when the previous session's 5-day mean Garman-Klass variance is in the top third of its last-250 range; opening range = the future's minute closes 09:00-09:29 ET (>= 20 minutes); first minute close beyond it 09:30-11:00 ET -> 1-ITM; stop the range midpoint; out at the square-off | crude | same clock, random session of the month, no vol filter | none (intraday) |

Implementation: `rules.py` (NR7Var, WeeklyNR7, EIAMomentum, LastHalfHour, HighVolORB, r3_daily), `engine.trail_levels`,
`history.trade_wnr7`; frozen with this file. Commands: `IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study
design 3`, then `... .report 3`, `... .entries 3`.

## 4. Gates (unchanged)

Round 1 gates 1-7 (>= 30 trades, net > 0, net at 2x spreads > 0, twin p < 0.05, BH q <= 0.10 over all 27, >= 3 of 4
folds positive, worst month > -Rs 13,000, max drawdown > -Rs 40,000, net without the best 3 > 0, the long-history
check positive where it exists) and round 2 gate 8 (model-priced exits <= 10%, real-exit net > 0). Only a rule passing
every gate is read on the locked holdout, once (study.py refuses a second read). Lead = net > 0 with >= 2 positive
folds, or twin p < 0.10.

## 5. Known in advance (stated so it can't be explained away later)

- R3A re-uses round 2's crude trades (R2D's CRUDEOIL half is the same trades) and adds CRUDEOILM; its p is optimistic
  by construction. R3C-R3G change one thing each against R2D. All NR rules share the NR7 signal (BH is valid under
  positive dependence).
- R3H-R3J are intraday (30 minutes to ~3 hours); theta is small but the half-spread (0.30% each way after 17:00) is a
  large share of a short move. R3I's twin keeps its own sign without the filter, so it tests the size filter and the
  clock, not the direction rule alone. R3H has about 45-50 EIA days in design per book (the same days on both crude
  books).
- Ideas from the scan NOT tried this round (round-4 list): EIA spike fade (conflicts with R3H), MCX evening-session
  first half-hour momentum, TSMOM as a filter, gold Asian-hours drift, weekly reversal.
- Session ends: the square-off uses the day's last bar when the session ends early (holidays are on the exchange
  calendar in advance).
