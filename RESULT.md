R96 round 4: LEADS (continue)

Research only (no app or workflow change, nothing on paper or live). Pre-registered before any result (commit ed8697b);
full write-up: research/HUNT_R96_ROUND4.md; numbers: research/hunt/r96_mcx_buy/results/report_round4.md.

## For Boss, in plain English

- I tested 7 more MCX option-buying ideas (36 in R96 so far). **None passed every check**, so the untouched test period
  (15 Jul - 19 Oct 2026) stays locked and nothing goes to paper.
- **The best result so far: crude oil NR7, but only on days when the options are cheap.** The rule: when crude's daily
  range is the narrowest of the last 7 days, and the at-the-money options' implied volatility that day is below its
  usual level (the median of the last 20 sessions), buy a 1-in-the-money call or put on the next session's break of
  that day's high or low. The signal is on the crude future; the trade is the option. Stop -40% of the premium or the
  future back through the other side of the NR7 day; lock profits as the premium rises; out within 3 sessions.
  - Design period (Aug 2025 - mid-Jul 2026), 1 lot, after all charges: **+Rs 49,381 on 28 trades, 86% winners**;
    worst month -Rs 4,030; random days made -Rs 5,700 on average and the rule beat 499 of 500 random copies.
  - **Why it is not proven yet:** 28 trades are really 14 signal days (CRUDEOIL and CRUDEOILM trade the same days), one
    big winner (22 Oct 2025, +Rs 18,128) is priced by a model because our data only stores 3 strikes each side, and
    most of its trades are the same crude NR7 trades from round 3. Counted as one idea family (as it should be), it
    misses the multiple-testing bar (0.124 vs 0.10). It also has fewer than the 30 trades the rules ask for.
  - Real money at 2% risk per trade would need about Rs 6.0 lakh per CRUDEOIL lot (median stop risk Rs 11,923) or
    about Rs 63,000 per CRUDEOILM lot (Rs 1,257).
- What lost: entering the crude NR7 at the US open (no better than random days), adding a 12-month or 1-month trend
  filter (too few trades), a deeper 2-in-the-money strike (-Rs 901), fading the EIA spike (only 2 trades in 11 months),
  and gold/silver momentum after 17:00 (-Rs 1.10 lakh on 214 trades, lost before costs).
- **What would settle it:** fresh data the rule has never seen. Round 5 proposes logging every crude NR7 signal from
  20 Oct 2026 and reading the result once when 15 signal days exist (about 8 months), plus wider strikes in the data
  lake (it still has 3 each side, although its status file says 5).
- Your earlier rule stands: the crude NR7 lead is not read on the locked period unless you decide otherwise.

## Checks

- R96 tests 60 passed (incl. look-ahead guards for the trend columns and the IV filter); research/brain tests passed;
  `features catalog --check` up to date; `research.experiments.validate` ok; secret grep shows only CLAUDE.md's own
  pattern lines (as on main); no raw data in git.
- R97's gold ideas were not on main at pre-registration, so BH counts R96's 36 ideas only.
