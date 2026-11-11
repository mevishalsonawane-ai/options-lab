# R160 notes: deviations and things learnt while running (PREREG section 12)

## Before any result

- **A1 (11 Nov, from the unit tests, before the data run).** `measure.expiry_days` moved a weekday that falls after the
  data's last day back onto the last day. For monthly expiries it also marked the last weekday seen in a month as
  "last of the month" when the data ends mid-month. That would have made 13 Oct 2026 a false BANKNIFTY expiry.
  - Fixed: such targets are skipped. Two tests cover it.
  - This only affects the expiry flag (secondary (b) and T1's skip), not H1 / H2.
- **A2.** The pinned research requirements do not install on this session's default Python 3.13, so everything ran on
  Python 3.11 with `research/brain/requirements.txt`.

## After the result (reporting only; nothing in the tests or the bar changed)

- **A3. H2 is H1 seen from the late level.**
  - Because R = G - D, the slope of R on D is exactly the slope of G on D minus 1.
  - So H2's test of "gamma = 0" is a test of "beta = 1": does the open land on the late level?
  - This was implied by the definitions in PREREG but not said there. The report says it.
- **A4. On expiry days the expiring options price the settlement, not the "fair" level.**
  - On NIFTY expiry days the option-implied late level sat much closer to the official close than the futures did.
    For example, on 15 Sep 2026 D was +1.6 bps from options and +34.7 bps from futures. Over the 11 expiry days the
    correlation of the two measures was 0.59, against 0.99 on the other 42 days.
  - This fits expiring contracts settling on the auction-made close in this period: they trade toward the expected
    settlement.
  - Futures are therefore the cleaner measure on expiry days. The pre-registered futures robustness covers this.
- **A5. Post-hoc sensitivity, information only (`results/posthoc.json`).**
  - Dropping 3 Aug 2026 (the first auction day, D = -77 bps on NIFTY) leaves the H1 slope at 0.53-0.56 on all three
    indices, with both measures.
  - These are not tests and are not in the BH family.
- **A6. T1 drops.** 2 of 32 eligible nights were dropped by R146's fill guard (a stray 09:16 print). T1 kept 30 trades.
- **Run once.** `study.py` ran once on the data (`results/POST_READ.flag`). There was no re-run with changed settings.
