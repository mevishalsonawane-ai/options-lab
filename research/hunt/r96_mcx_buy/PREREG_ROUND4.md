# R96 round 4 pre-registration: the crude NR7 lead tested five more ways + two literature ideas (21 Oct 2026)

Boss, 21 Oct: "Run until we found something, commodities options buying strategy". Written and committed **before any
round-4 statistic**. Everything in PREREG_ROUND1.md s2-s5 and s7, PREREG_ROUND2.md s2-s5 and PREREG_ROUND3.md s2-s4
still holds unless changed here: the same design (5 Aug 2025 - 14 Jul 2026), the same four walk-forward folds, the
**same LOCKED holdout (15 Jul - 19 Oct 2026, never read so far)**, the same costs (Zerodha MCX option charges + 0.30% /
0.60% half-spread, natural gas 0.45% / 0.90%; gates also at 2x), twins (500, seed 96, same book and calendar month), the
-40% hard stop, the round-1 lock ladder and the round-2 edge take-profit on every rule (gate 8 limits model-priced
exits), and BH over the **cumulative ledger: rounds 1-3's 29 ideas + these 7 = 36**.

Signal source of every rule: **the MCX future** (minute closes of the book's near future; daily rules: the book's
near-month daily continuous future, back-adjusted). R4G also reads the ATM implied volatility of the option chain, as a
filter only (CLAUDE.md allows option-chain data as context). Every trade buys one option, 1-ITM unless said; every trade
has a hard stop (-40% of the premium and a stop on the future) and a profit lock (the ladder +30% -> breakeven, +60% ->
+30%, +100% -> +60%, then 40% behind, plus the edge take-profit).

R3A's crude NR7 rule is **not** re-run on the holdout (Boss has not changed that rule). R97 (the gold reverse-engineering
session) has not logged any idea on main as of this commit (checked `origin/main`), so BH counts R96's ideas only;
if R97 lands first, round 5 adds them.

## 1. Data check (21 Oct, no statistic)

- rclone was again missing from the container; installed from downloads.rclone.org (v1.75.2) as in round 3 (N6). Lake
  copied with plain `rclone copy gdrive:IraAlgo-data-lake/<folder> ~/lake/<folder> --include ...`
  (options_rolling_1m MONTH books of CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, GOLDM, SILVERM, GOLD, SILVER;
  fut_daily_continuous; fut_1m_mcx_continuous; fut_1m_live_mcx; fut_mcx_contracts; fut_mcx_rolls; xauusd_1m). No
  credential or config was read or printed.
- The lake's STATUS.txt says "MCX options ATM-5..+5", but every MCX book still stores offsets **-3..+3 only** on every
  month of the design period (checked: offset min / max / count per month). So the pre-registered **R4-0 (NR7 on
  ATM+/-10 real prints) cannot run again** and is not counted as a test.
- fut_1m_mcx_continuous starts 20 Oct 2025 and fut_1m_live_mcx holds live contracts only, so there is no longer
  intraday futures history for a long-history check of the intraday rules.

## 2. Books

crude = CRUDEOILM (x10) + CRUDEOIL (x100); metals = GOLDM (x10) + SILVERM (x5). Pooled rupees are dominated by the big
book; the median 1-lot stop risk and the capital for 2% are reported per rule.

## 3. The seven rules (each one BH test, pooled over its books)

NR7 rules = R3A exactly (NR7 day on the book's daily future -> the next session's first minute close beyond the NR7
day's minute-future high / low, 09:15-22:00 -> enter the next minute; stop on the future at the NR7 day's opposite
extreme; 3 sessions; no entry within 3 sessions of the option expiry) with one change each. Twin T-tod = the same
clock, side and ATR-scaled stop on a random session of the same month, **without the filter**.

| id | idea (source) | change from R3A / rule | books | twin | history gate |
|---|---|---|---|---|---|
| **R4A** | NR7 crude, US-open entry (R3A x R3G; **chosen after rounds 2-3**) | a break from 09:15 to the minute before the US cash open (09:30 ET = 19:00 / 20:00 IST) that is still beyond the level at that minute enters at the US open | crude | T-tod at the twin day's own US open | NR7 daily (cannot test the clock) |
| **R4B** | NR7 crude + 12-month trend (Moskowitz, Ooi & Pedersen, JFE 2012) | only breaks whose side equals the sign of the back-adjusted close change over the last **250** daily rows, read on the NR7 day's row (0 / unknown = skip) | crude | T-tod (no filter) | NR7 with the same filter |
| **R4C** | EIA spike fade (Li & Geman, J. Energy Markets 2018; Rousse & Sevi, Energy Journal 40(2)) | on EIA crude days (R1 event calendar, release minute IST): move = the future's close at release + 5 min (last minute at or before, within 2) minus its close at release - 1 (within 5); if abs(move) >= **0.20 x ATR14** (ATR known before the day) -> 1-ITM **against** the move from the next minute; stop on the future = a close **beyond** the future's extreme between those two minutes; out **60 minutes** after the entry minute (or the square-off if earlier); ladder, edge, -40% as usual | crude | same clock and side on a non-EIA session of the month, no size filter | none (intraday) |
| **R4D** | MCX 17:00-17:30 IST momentum in metals (Bouri, Ma, Xu & Zhou; Ma et al., GFJ 64, 2025: night-session first half-hour predicts the rest) | the future's close at 17:30 minus its close at 17:00 (last minutes at or before, within 5) -> 1-ITM that way from 17:31; stop on the future = the 17:00 price; out at the session square-off (close - 15); every session with a non-zero move trades | GOLDM, SILVERM | **same clock and same side** on a random session of the month (tests the direction; the rule trades every day) | none (intraday) |
| **R4E** | NR7 crude, 2-ITM (round-3 R4-5) | the strike 2 steps in the money (from the stored strikes at the signal minute) | crude | T-tod | NR7 daily |
| **R4F** | NR7 crude + 1-month trend (Journal of Futures Markets 39(12) 2019, Chinese commodity TSMOM best at 1-month lookback; J. Futures Markets 45(11) 2025, commodity factor momentum strongest at 1 month) | as R4B with **20** rows | crude | T-tod (no filter) | NR7 with the same filter |
| **R4G** | NR7 crude when options are cheap (VRP is time-varying: Chevallier & Sevi; Da Fonseca & Xu) | only when the NR7 day's ATM IV (median over its minutes of the mean of the offset-0 call and put IV) is **below the median** of the previous **20** option sessions' values (>= 10 known) | crude | T-tod (no filter) | NR7 daily (no IV history) |

Implementation (frozen with this file): `rules.py` (r4_daily, value_before, atm_iv_by_day, iv_cheap_before, NR7Var
side_col / iv_cheap / itm, EIASpikeFade, EveningMomentum, ORDER4, NR7_CRUDE_FAMILY), `engine.sim_single(i_end_fixed=)`,
`study.family_q`, `history.trade_nr7(side_col=)`. Commands: `IRAALGO_LAKE=~/lake python -m
research.hunt.r96_mcx_buy.study design 4`, then `... .report 4`, `... .entries 4`.

## 4. Gates (unchanged, plus s5)

Round 1 gates 1-7 (>= 30 trades, net > 0, net at 2x spreads > 0, twin p < 0.05, BH q <= 0.10 over all 36, >= 3 of 4
folds positive, worst month > -Rs 13,000, max drawdown > -Rs 40,000, net without the best 3 > 0, the long-history
check positive where it exists) and round 2 gate 8 (model-priced exits <= 10%, real-exit net > 0). Only a rule passing
every gate is read on the locked holdout, once (study.py refuses a second read). Lead = net > 0 with >= 2 positive
folds, or twin p < 0.10.

## 5. New gate for the NR7-crude family (stated now because round 4 adds five close copies of R3A)

R3A, R4A, R4B, R4E, R4F and R4G trade mostly the same crude NR7 days. BH is valid under positive dependence, but
adding near-copies of one idea with low p values would lower every q without adding independent evidence. So a family
member must ALSO pass **gate 9: BH q <= 0.10 with the whole family collapsed into one test** (its smallest p times 6,
Bonferroni, then BH over that one test + the other 30 ideas). `study.family_q`. This only makes passing harder.

## 6. Known in advance (stated so it can't be explained away later)

- Five of the seven rules are R3A variants on the same 23 signal days per crude book; their results will be close to
  R3A's and to each other. R4B and R4F keep only the trend-agreeing subset, R4G about half of the days, so they will
  have fewer than 46 trades and may fail the 30-trade gate.
- R4A re-uses R3G's clock on crude only (R3G's crude half: +Rs 33,169 on 19); its p is optimistic by construction.
- R4C has about 45-50 EIA days per book in design, fewer after the 0.20 ATR filter; all entries are after 17:00 IST
  (0.30% half-spread). The fade conflicts with R3H (momentum after the release, which lost); both are logged.
- R4D trades almost every session on two books (about 450 trades); a 1-ITM metal option needs the future to move about
  0.6% of the premium's value to pay its spreads. Round 1's U1 (US-session momentum) lost on these books.
- The literature effect sizes in INTERNET.md come from abstracts; the full texts were not read.
