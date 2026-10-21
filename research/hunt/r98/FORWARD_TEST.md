# R98 forward paper test: the 4 hero-zero near-misses, frozen 21 Oct 2026

Every rule below was the best of its family on 2021-26 data. Each was positive in both halves, and none survived the
luck tests (SPA) or the removal of its best days. The past cannot settle it, so only new days can. These rules are now
FROZEN: no parameter changes after today. Any change starts a new test.

- **Paper only.** Nothing goes live and nothing changes in the app until a rule passes below.
- **Common to all four:**
  - the five indices' expiry days;
  - Rs 5,000 a ticket, bought at the next minute's open after the signal, plus 1 tick;
  - out at 15:20 unless stopped;
  - charges as R98 (Rs 20 an order + GST, STT 0.15% on the sell, exchange fees, stamp);
  - an option under Rs 0.10 at the exit expires worthless.

| id | rule (from) | entry | exit | past result (2021-26) |
|---|---|---|---|---|
| FT1 | US-event basket (R98d) | On an expiry that falls on the first Indian session after a scheduled US Fed decision or a US CPI release: at 09:20, the OTM call and the OTM put priced nearest Rs 5 (Rs 2-10, traded in the last 3 minutes) | −20% resting stop | +Rs 3,354 a ticket, 182 tickets / 98 days; −Rs 256 without the 3 best days |
| FT2 | order-flow ignition (R98g) | 11:00-14:55, the first minute (per index-day) when a cheap option meets all three: its lowest close since 11:00 is Rs 1-10 (OTM, price ≤ Rs 15); its 5-minute volume is ≥ 5× its mean 5-minute volume since 11:00; its close is ≥ 1.5× that low; and ≥ 60% of the 5-minute volume is on up-close minutes | −20% resting stop | +Rs 998, 405 tickets; both halves > 0; −Rs 684 without the 5 best days |
| FT3 | near-ATM morning (R98e) | The first 5-minute mark 11:00-12:55 (per index-day) with an OTM option priced Rs 6-10, IV ≤ 1.03 × the ATM IV, and strike within 1.55 ATM straddles of spot | hold to 15:20 | +Rs 2,571, 474 tickets; both halves > 0; +Rs 1,032 without the 5 best days |
| FT4 | model daily pick (R98) | R98's logistic 10× model, refit once on all 2021-26 rows and frozen. Per index-day, the top-scored cheap option among the 11:00-14:55 five-minute rows | hold to 15:20 | +Rs 714 (2023-26 walk-forward, 716 tickets); −Rs 808 without the 5 best days |

## How it is judged (fixed now)

- **When:** read ONCE, at the later of 12 months from today and ≥ 100 tickets per rule. FT1 is read at 12 months
  whatever its count; it gets only about 15-30 tickets a year.
- **A rule passes only if all of these hold:**
  - mean net per ticket > 0;
  - the day-bootstrap 95% lower bound > 0;
  - > 0 without its 3 best days;
  - Holm-adjusted p < 0.05 across the 4 rules (one-sided t-test of daily P&L > 0).
- **Logging:** every signal is logged as it happens, including the losing tickets: index, time, strike, side, entry,
  exit, reason, and net.
- **Who checks:** the replay against the lake once the days are fetched is the check.
