# R98g pre-registration: order-flow "ignition" entries (the 13 Oct signature, tested on every year)

Written 21 Oct 2026, after R98f's 13 Oct minute table and before any number below was computed.

## The idea

On 13 Oct 2026, the 22650 CE's run began with an ignition phase (14:05-14:25):
- a volume burst on the strike;
- the price lifting off its low;
- mostly up-tick volume.

The explosive phase only followed from 14:35. If a trader can see the ignition live, can buying it pay?

## Rule (fixed)

- **Scan:** every expiry of the five indices (2021-26), every minute t from 11:00 to 14:55, every stored option.
- **Ignition at minute t, when all of these hold:**
  1. The option was cheap: its lowest close since 11:00 (L) is Rs 1-10, it is OTM at t, and it traded in t-2..t.
  2. **Volume burst:** its volume over t-4..t ≥ B × its mean 5-minute volume since 11:00, with B ∈ {3, 5}.
  3. **Lift-off:** its close at t ≥ R × L, with R ∈ {1.3, 1.5}, and ≤ Rs 15.
  4. **Up-ticks:** ≥ 60% of the t-4..t volume is on minutes that closed above the previous close.
- **One ticket per index-day:** the first ignition. Ties go to the larger volume burst.
- **Entry and size:** the next minute's open + 1 tick, Rs 5,000, R98 charges.
- **Exits** (R98b's code):
  - hold to 15:20;
  - a −20% resting stop;
  - a −50% resting stop;
  - a give-back trail armed at 2× keeping 50% of the gain, with a −50% stop before it.
- **Variants:** 2 B × 2 R × 4 exits = 16.

## Judged (fixed)

- **Per variant:**
  - tickets, win rate, mean;
  - the mean without the 5 best days;
  - years > 0;
  - the 2021-23 and 2024-26 halves.
- **Across the 16:** Hansen's SPA on daily P&L.
- **Passes only if:** SPA p < 0.05, the SPA-best variant is > 0 without its 5 best days, AND both halves are > 0.
- Also reported: what the rule did on 13 Oct.
