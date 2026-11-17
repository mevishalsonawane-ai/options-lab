# R192 pre-registration: longer history and replicated patterns

Written 17 Nov 2026, BEFORE any R192 profit, loss or signal statistic was computed. Research only; nothing under
`android/` changes (except a knowledge-pack rebuild if the checker asks for one).

Boss (owner, they/them) after R190 found nothing reliable: *"Try more harder"*. R190's main problem: real option
minutes only start in Aug 2020 and every era has been seen, so the eras are short and nothing could be confirmed.

**R192's angle:** test ideas that have published, replicated evidence elsewhere, on the LONG underlying history
(NIFTY minutes from 3 Apr 2017, NIFTY daily from 2006, India VIX daily from Nov 2007), price the option trade with
REAL option minutes wherever they exist, and use a clearly labelled SYNTHETIC option price before that, as a check of
the underlying edge only. Synthetic money is never added to real money.

Number check (17 Nov): no branch, folder or entry named r191 / r192 existed on origin (the sister study takes R191).

## 1. Data (nothing raw goes into git)

R190's copies, unchanged (`research/hunt/r190_best_hunt/r190data.py`, `~/.cache/iraalgo/r190`):
- `lake/index_1m_NIFTY_*.parquet`: NIFTY 1-minute index, 3 Apr 2017 - 13 Nov 2026 (read with R147's `index_days`).
- `lake/index_daily.parquet`: daily OHLC, NIFTY from 2 Jan 2006, INDIAVIX from 1 Nov 2007 (VIX closes <= 1 are
  treated as missing; a few zero rows exist).
- `r51/clean/day_NIFTY.pkl`: R51's clean NIFTY nearest-WEEK option store (each day: (side, strike) -> 1-minute
  O/H/L/C/volume, the ATM path, the expiry flag). Its option minutes start 3 Aug 2020: Aug 2020 - Jul 2021 come from
  the dhan-data branch (R190 checked them identical to the lake on the overlap), the rest from the lake.
Data files are read with `python3 -I`; caches go to `~/.cache/iraalgo/r192`.

Only NIFTY is used (the only index with minutes before 2021 and real options before Aug 2021).

## 2. Eras (all fixed now)

| era | dates | prices | role |
|---|---|---|---|
| **S0** | 1 Jan 2008 - 31 Mar 2017 | SYNTHETIC, daily bars | underlying check, daily-capable rules only |
| **S** | 3 Apr 2017 - 31 Jul 2020 | SYNTHETIC, index minutes | underlying check, every rule |
| **A** | 3 Aug 2020 - 30 Jul 2021 | REAL NIFTY option minutes | out of sample for the rules |
| (gap) | 2 Aug - 30 Nov 2021 | | not counted (as R190) |
| **B** | 1 Dec 2021 - 31 Oct 2024 | REAL | out of sample for the rules |
| **C** | 1 Nov 2024 - 13 Nov 2026 | REAL | out of sample for the rules |

**Honest disclosure.** No rule below is fitted on any era (the numbers come from the papers or are round numbers fixed
here), except K4's filter, which is chosen on 3 Apr 2017 - 31 Dec 2018 only (section 4). So every era is out of
sample for the RULES. But eras A, B and C were all seen at the data level by R190 and earlier hunts (h22-h47, R1-R190),
and several ideas below are relatives of rules tested there (named per idea). There is no virgin option data left.
All eras are computed in one run after this file is committed; nothing is re-run or re-tuned after the numbers.

## 3. Ideas already in the log, DROPPED (searched `research/experiments/entries` by keyword, 17 Nov)

- **Day of week**: R102 B3 (weekday close-to-close signs, 2006 on, failed) and h28 (dow flags x option buys, failed).
- **Budget / RBI policy days**: h28 (rbi, pre_rbi, budget flags x option buys, failed); R102 B3 (descriptive only;
  6 Budget days, too few for 40 trades in any era).
- **Expiry-day late moves**: h28 tested exp_day x entry 14:00 x "mom" side (close vs open) x 6 exits, failed.
- **End-of-day intraday momentum**: R190 M1 / M2 / O1 / O2, failed.
- **IBS, RSI(2), NR7, 200-day band as stand-alone rules**: R52 R1-8, SWING_DEEP-3, book-sadowski NR4/NR7, Focardi
  200-day band (all tested). The 200-day average appears below only inside K4's menu and K9.

Kept, although a relative is in the log (named so the reader can judge the overlap):
- K1 turn of the month and K2 pre-holiday: h28 had tom / pre_hol flags but only INTRADAY entries (09:20-14:00, out by
  15:10). The published effects are close-to-close; K1 / K2 hold overnight. New as overnight holds.
- K3 overnight call: R146 t1 ("always the call", real 2021+); h31. Re-test on the long history.
- K4 Night proxy + filter: R146 t2 / R17 strong close; R147 f1-f8 tested filters on the REAL Night. New: the filter is
  chosen on 2017-18 only.
- K5 gap fade: R102 B4 (descriptive), R110 IraThinker gap fade (gap >= 1%, BN/NIFTY 2021+). K5 is a different band
  (0.3-1.0%) with an index target.
- K6 gap follow: only as a filter in R105B D1 / D10 (on arms). New as a stand-alone rule.
- K7 VIX spike: R52 R1-9 (intraday 15-minute VIX change at 09:16, EOD exit). K7 uses the daily close spike and a
  two-session hold. New.
- K8 52-week high: book-porwal-07 (a filter on the arms). New as a stand-alone rule.
- K9 trend + volatility: R105B D7 (200-day filter on arms). New as an overnight rule with a vol filter.
- K10 / K11 after a large move: R147 f3 (skip Night after |move| >= 1.5%). New as stand-alone rules.

## 4. The ideas (exact rules; signal on the NIFTY index, buy the NIFTY option)

Common to all:
- Signal source: the NIFTY index (minutes; daily closes; India VIX daily closes in K4 F3 and K7).
  No rule reads an option's own price. Option buying only (Boss, 21 Oct).
- Instrument: the **1-ITM** option of the nearest NIFTY expiry: call strike = ATM - 50, put strike = ATM + 50, ATM =
  the store's ATM at the decision minute (real) or round(index / 50) x 50 (synthetic, and real if the store has none).
  The same contract is held to the exit. 1 lot.
- Weekend sessions (special / Muhurat) are dropped everywhere. A holiday = a weekday with no NIFTY session.
- Times: a decision at hh:mm reads only minutes before hh:mm (the close of the minute before); the order fills at the
  hh:mm minute's open. "15:20 entry" = the 15:19 decision. Hold types:
  - **ON** (overnight): buy 15:20, sell at the next session's 09:16 open;
  - **CC** (close to close): buy 15:20, sell at 15:20 of the exit session;
  - **ID** (intraday): as stated per rule.
- **Expiry:** if the held series expires on a session inside the hold (the entry session included), the trade is
  closed at 15:05 that session (the arms' expiry square-off); an entry at or after 15:05 on an expiry session is not
  taken. Real eras: the store's expiry flag. Synthetic: NIFTY weekly expiries on Thursday from 14 Feb 2019, before that
  the monthly expiry on the last Thursday of the month; a non-session Thursday moves to the previous session.
- Daily-era (S0) version of a rule: the 15:19 decision uses the daily close (it is 10 minutes late: labelled), 15:20
  fills use the close, 09:16 fills use the daily open, 09:20 fills use the daily open.
- Close location `loc` = (price - day low) / (day high - day low) from the minutes before the decision (S0: daily bar).
  `move` = price / previous session's last close - 1.

| id | idea (evidence) | rule |
|---|---|---|
| **K1** | Turn of the month (Ariel 1987; Lakonishok & Smidt 1988; replicated on Indian indices) | CALL, buy 15:20 on the LAST session of the calendar month, sell 15:20 on the 2nd session of the next month |
| **K2** | Pre-holiday (Ariel 1990; Kim & Park 1994) | event session H = a session whose next weekday is a holiday; CALL, buy 15:20 on the session before H, sell 15:20 on H |
| **K3** | Overnight drift (Cliff, Cooper & Gulen 2008; Lou, Polk & Skouras 2019) | CALL every session, ON hold |
| **K4** | Night proxy (strong close, R17) + one filter chosen on 2017-18 | base: move > 0 and loc >= 0.6 -> CALL; move < 0 and loc <= 0.4 -> PUT; ON hold; filter: see below |
| **K5** | Gap fade (price-gap anomaly, e.g. Plastun et al. 2019) | gap = 09:15 open / previous close - 1, 0.3% <= abs(gap) <= 1.0%; at 09:20 the index (close before 09:20) is still on the gap side of the previous close; buy AGAINST the gap at 09:20; exit: the index minute close reaches the previous close (sell next minute's open), or the option minute close <= 70% of the entry fill (sell next minute's open), else 15:20 |
| **K6** | Gap follow (gap-and-go) | abs(gap) >= 0.5%; at 09:30 the index is beyond the 09:15 open in the gap's direction; buy WITH the gap at 09:30; exit: option close <= 70% of the fill (next open), else 15:20 |
| **K7** | VIX spike reversal (Whaley 2000; Giot 2005: high fear, higher next returns) | VIX close(d-1) >= 1.25 x mean of the 10 VIX closes before it, and NIFTY close(d-1) < close(d-2); CALL, buy 09:20 on d, sell 15:20 on the next session |
| **K8** | 52-week high (George & Hwang 2004; Li & Yu 2012) | price at 15:19 > the highest of the previous 252 session closes -> CALL; < the lowest -> PUT; CC hold to the next session |
| **K9** | Trend + volatility filter (Moskowitz, Ooi & Pedersen 2012; Moreira & Muir 2017) | 20-session realised vol (daily log returns to d-1) <= its median over the previous 252 sessions, and price at 15:19 > the 200-session average of closes to d-1 -> CALL, < -> PUT; ON hold |
| **K10** | Reversal after a large day | abs(move at 15:19) >= 1.5% -> buy the OPPOSITE side; CC hold to the next session |
| **K11** | Continuation after a large day | abs(move at 15:19) >= 1.5% -> buy the SAME side; CC hold to the next session |

Daily-capable (have an S0 era): K1, K2, K3, K7, K8, K9, K10, K11. Minute-only: K4, K5, K6.

**K4's filter menu (6, fixed now):** F0 none; F1 skip a night whose next session is not the next calendar day (weekend
or holiday night); F2 trade only with the 200-session average of closes to d-1 (CALL above it, PUT below); F3 skip
when VIX's previous close is above the 80th percentile of its previous 252 closes; F4 skip when abs(move) >= 1.5%;
F5 only an extreme close (loc >= 0.8 for a CALL, <= 0.2 for a PUT).
**Choice:** the highest SYNTHETIC net per trade on 3 Apr 2017 - 31 Dec 2018 among filters with >= 60 trades there
(F0 if none beats it). Frozen before any later day is priced in the study output. K4's era S is then 1 Jan 2019 -
31 Jul 2020 only. All 6 menu members count in the multiple-testing m.

## 5. Pricing

**Real (A, B, C):** R190's conventions (`r190rules.price`), extended to multi-session holds:
- entry at the contract's open at the order minute, or the first traded minute up to 2 minutes later, else no trade;
- R17 "liq" fills: buys +5 bps plus 1 / 2 / 4 ticks by the contract's volume in the 5 minutes before (>= 20 lots /
  some / none), sells -5 bps minus the same; Rs 0.05 tick;
- exit at the exit minute's open or the next traded minute up to 10 minutes later, else the last traded price before
  the exit (searched back across sessions);
- stops (K5, K6) trigger on the option's minute close and sell at the next minute's open (a market sale, same fills);
- dated Zerodha charges (`research/obuy/costs.py` Costs("dated")), the exit leg on the exit day's rates;
- dated NIFTY lots (R190's table: 75 / 50 from 23 Jul 2021 / 25 / 75 / 65);
- 2x slippage stress reported.

**Synthetic (S0, S), clearly labelled:** Black-76 on the forward F = S e^(rT), r = 6.5%, T = calendar time to the
expiry's 15:30 (at least one minute), sigma = a x India VIX / 100, where VIX = the previous session's close for prices
before 15:00 and the same session's close for prices at or after 15:00. Fills: +-5 bps and 1 tick (the "liquid" R17
case), the same dated charges (the earliest rates before 2020), lot 75 fixed. `a` is ONE number fitted on the real
entry premiums of eras A + B (least squares on log premium, grid 0.50-1.50 step 0.01); era C is the out-of-sample
check of the pricing model.

**Calibration error (reported before any rule result is read):** on every real pool trade of eras A, B, C (all
eligible days, both sides, every hold type) the synthetic net at the same times vs the real net: mean real, mean
synthetic, mean error, mean absolute error, correlation, sign agreement, per hold type and era (A + B = fit, C =
check), and the entry-premium error. Synthetic eras are an underlying check only, never summed with real money.

## 6. Random-entry twins and multiple testing

- **Twins:** for each idea and real era, the same number of trades drawn without replacement from the era's ELIGIBLE
  days for that idea's timing (days where both 1-ITM legs price at the rule's entry and exit times, under the same
  expiry rule), each with a random side (call / put 50:50), with the idea's exact exit rule (K5's target is the
  previous close when it lies on the favourable side of the entry index level; otherwise no target). 5,000 twins per
  idea, seed 192. **Pooled p:** the twin total is the sum over A, B and C (each era drawn with its own count) against
  the rule's pooled real net; empirical p = (1 + twins >= rule) / 5,001, one-sided; the normal-tail p is also kept.
- **BH:** m = 4,755 entry files at the branch base (origin/main d1b1d57d8) + 16 R192 tests (K1-K11, K4's six menu
  members counted separately) = **4,771**; earlier entries count as p = 1; R192's q uses the normal-tail pooled p (the
  empirical p cannot go below 1 / 5,001).

## 7. The RELIABLE gate (fixed now; never loosened)

1. net > 0 with 40+ trades in EVERY era the rule has: S0 (daily-capable rules), S, A, B, C;
2. net without the top 5 trades > 0 in each real era (A, B, C);
3. pooled real twin empirical p < 0.05;
4. BH q <= 0.10 (m = 4,771).
Reported for every idea and era: trades, win %, average win / loss, net, net without the top 5, worst month, max
drawdown (daily, 1 lot), against Boss's account stop (-Rs 40k drawdown, -Rs 13k a month; account about Rs 56k).

Low-frequency ideas (K1 about 12 trades a year, K2 about 12, K7 a few) cannot reach 40 trades in era A (one year)
by construction; they are run and reported for the record and will fail item 1. This is stated now, not after.

## 8. Order of work

1. This file is committed before any number.
2. `r192build.py` builds the per-day tables (signals and both legs' nets for every timing) for every era.
3. `r192study.py calib` writes the calibration (fit on A + B, check on C) to `results/calibration.json`.
4. `r192study.py k4` chooses K4's filter on 2017-18 only and writes `results/k4_choice.json`.
5. `r192study.py run` computes every idea in every era, the twins, BH and the gate -> `results/r192.json`,
   `results/summary.md`, `results/report.md`, `decision.json`. Entries `research/experiments/entries/r192-*.yaml`.

## 9. Tests

`test_r192.py`: the look-ahead guard (every decision unchanged when the future, later minutes AND later days, is
replaced by noise), each rule on hand-made days, the calendars (month ends, holidays, synthetic expiries, holiday
Thursdays), the gap rules on missing minutes, Black-76 (put-call parity, limits), the real multi-session pricer
(missing minutes, expiry cut, stop), stats, twins, BH and the gate.

## 10. Amendment 1 (17 Nov 2026, after the calibration, BEFORE any rule result)

What was computed before this amendment: `results/calibration.json` only (premium fit `a` = 1.02 on 10,374 era A + B
entries, median absolute premium error 10.8%, era C 13.8%; and the synthetic vs real net of every pool trade by hold
type). No idea's trades, K4 choice, twin or gate number had been computed.

**Finding.** The pre-registered calendar-time clock puts too much decay on nights and too little on trading hours:
mean synthetic minus real net per pool trade in A + B: ON -Rs 771, CC1 -Rs 277, CC2 -Rs 261, D2 +Rs 318, G5 +Rs 89,
G6 +Rs 264 (era C the same signs). So the pre-registered synthetic would be biased against the overnight ideas and
for the intraday ones, by about the size of the edges being tested.

**Amendment (pricing only; no rule changes).** The synthetic price uses a trading-time clock: T = (trading minutes
left + w x non-trading minutes left) / (93,000 + w x 432,600) years (a year = 248 sessions x 375 minutes plus the rest
of its 525,600 minutes at weight w; w = 1 is close to the calendar clock). Sessions come from the NIFTY calendar.
`w` is chosen on the grid 0.00-1.00 (step 0.05) to minimise the sum over the six hold types of the squared mean net
error on the era A + B pool trades, with `a` re-fitted (premium least squares, as before) for each w. Era C remains
the out-of-sample check. **The gate's synthetic eras (S0, S) use the amended model.** The pre-registered calendar-clock
results of every idea are also reported in `report.md` next to them, and any verdict that would differ is named.
Nothing else changes.
