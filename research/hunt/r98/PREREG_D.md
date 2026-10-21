# R98d pre-registration: hero-zero on event days

Written 21 Oct 2026, before any outcome below was computed. Boss: "Do the event day study".

The idea: an event known in advance (an RBI decision, the Budget, election results, a US Fed decision or CPI print)
might make a 2.5-3 straddle move, and so a hero candle, more likely on that day. If so, it would be a prior that needs no
prediction. The calendar is fixed by public dates, not fitted to the data, so the whole sample (Aug 2021 – 19 Oct
2026) is used. No holdout is needed for the descriptive part. The one trading rule (M6) is fixed here, before it runs.

## 1. Calendar (from official / public sources, saved with links in `events.csv`)

| category | event | the Indian session it hits |
|---|---|---|
| RBI | RBI Monetary Policy Committee decision (announced about 10:00 IST) | that day |
| BUDGET | Union Budget speech (about 11:00 IST) | that day (special weekend sessions included if the market was open) |
| ELECTION | Lok Sabha 2024: the result day (4 Jun 2024) and the first session after the exit polls (3 Jun 2024) | those days |
| FOMC | US Federal Reserve decision (14:00 ET = 23:30 / 00:30 IST) | the next Indian session |
| USCPI | US CPI release (08:30 ET = 18:00 / 19:00 IST) | the next Indian session |

- **Groups:** DOMESTIC = RBI + BUDGET + ELECTION (the event falls inside Indian hours); US = FOMC + USCPI (overnight,
  so it shows in the open); ANY = all of them.
- An index-day counts as an event day when its expiry falls on an event session.

## 2. Metrics, event expiry-index-days against all other expiry-index-days (fixed)

- **M1:** hero day: some Rs 1-10 OTM option closes ≥ 10× from an entry 10:00-15:00, by 15:20 (R98 census).
- **M2:** afternoon hero: some option cheap at 13:00 reaches 10× by 15:20.
- **M3:** realised against priced: the best one-way move to 15:20 in ATM straddles, at 11:00 and at 13:00 (median, and
  the share ≥ 2.5).
- **M4:** the priced move: the 11:00 and 13:00 ATM straddle as % of spot (median). Does the market already price the
  event?
- **M5:** R98's tickets (every cheap option every 5 minutes, 11:00-14:55):
  - P(10×);
  - the mean net per Rs 5,000 held to 15:20, and with a −20% stop.
- **Tests:**
  - Fisher's exact test (two-sided) for M1 and M2;
  - Mann-Whitney U for the M3 move at 13:00;
  - Benjamini-Hochberg over the 8 groups/categories × these 3 metrics (24 p-values). A finding needs BH q < 0.05.

## 3. M6, the one trading rule (fixed)

- **Event lottery basket:** on every event expiry-index-day, buy the OTM call and the OTM put priced nearest Rs 5
  (within Rs 2-10, traded in the last 3 minutes) at a fixed minute:
  - RBI 09:55;
  - BUDGET 10:55;
  - ELECTION 09:20;
  - FOMC and USCPI 09:20 (the overnight news is already in the open).
- Rs 5,000 a leg, entry at the next open + 1 tick, R98 charges.
- **Exits:** hold to 15:20; a −20% resting stop; or a free roll (half at 2×, −50% stop before).
- **Pass** (per group and exit): mean net > 0, the bootstrap 95% lower bound (by day, 2,000, seed 98) > 0, > 0 without
  the 3 best days, and > 0 in every calendar year with trades.
- **Power warning:** DOMESTIC has about 30-40 expiry-index-days in 5 years. A real effect smaller than about Rs 1,500 a
  ticket cannot be told from zero, so a fail is weak evidence.
