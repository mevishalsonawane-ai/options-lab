# R149: Night on expiry nights: is there a case?

Written 5 Nov 2026 for Boss's "Check on expiry date, is there any case?".

**Files:**
- Pre-registration: `research/hunt/r149_night_expiry/PREREG.md`, commit `d4d88d0`, before any number.
- Code and 4 tests: `research/hunt/r149_night_expiry/`.
- Results: `results/results.json` and `results/since_nov2024.json`.
- Experiment entries: `r149-*.yaml`.

**Research only.**

## Short answer for Boss

**No. Keep Night's expiry rule as it is.** Night skips the night before its contract expires and the expiry day
itself. That is about 2 nights in 5 on NIFTY. None of the three ways of trading those nights passed:

| case | 2022-24 (design) | Jan 2025 - Oct 2026 (check) | since Nov 2024 (info) | verdict |
|---|---|---|---|---|
| E1: hold the EXPIRING option into expiry morning, sell 09:16 | 197 trades, +Rs 164 a trade, but 2023 and 2024 negative; 31% of trades lost more than 30% of premium | 95 trades, -Rs 101 a trade; 2026 -Rs 27k | 108 trades, +Rs 133 | fail: expiry-morning options lose value fast, and a third of trades lose a third or more |
| E2: buy the MONTHLY option instead, the night before a weekly expiry | 85 trades, +Rs 231 (twin p 0.03) | 65 trades, -Rs 13 (-Rs 47 at 1.5x spread) | 71 trades, +Rs 193 (NIFTY +Rs 36, SENSEX +Rs 374) | fail in check |
| E3: buy the MONTHLY option ON a weekly expiry day | 76 trades, +Rs 287, but not better than random sides (p 0.19); 2022 negative | 64 trades, +Rs 374 (p 0.10) | 71 trades, +Rs 360 (NIFTY 34 trades +Rs 907; SENSEX 36 trades -Rs 169) | fail |

- **The one lead is E3 on NIFTY:** on a weekly expiry day, Night's signal bought in the monthly option.
  - check period: 31 trades, +Rs 824 a trade;
  - since Nov 2024: 34 trades, +Rs 907 a trade.
- **It is a small sample and failed the design test**, so it is a NOTE only. If Boss wants it, the honest route is a
  SHADOW log in the app: record the would-be monthly trade on NIFTY expiry days, and judge it after about 50 new
  ones.
- **Not testable:** next month's option on a MONTHLY expiry day. The lake has only the expiring month that day. Since
  Nov 2024 that covers every BANKNIFTY / FINNIFTY expiry.

## Notes

- **Night's signal is unchanged on these nights.** Only the contract changes; costs are R147's.
- **E1's risk:**
  - its worst trade was -Rs 10.8k (design) and -Rs 11.7k (check) on 1 lot;
  - 27-31% of trades lost more than 30% of premium overnight, vs 4-22% for Night's normal nights.
- **Periods:** the check period is R147's holdout, a second look at those dates. These expiry trades had never been
  priced before.
