# R98 forward paper test: the 4 hero-zero near-misses, frozen 21 Oct 2026

Every rule below was the best of its family on 2021-26 data. Each was positive in both halves, and none survived the
luck tests (SPA) or the removal of its best days. The past cannot settle it, so only new days can. These rules are now
FROZEN: no parameter changes after today. Any change starts a new test.

- **Paper only.** Nothing goes live and nothing changes in the app until a rule passes below. The one exception is FT3,
  which Boss asked to have in the app on 21 Oct (see the amendment at the end). Its live path is the app's standard one,
  behind the PIN and Shadow.
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
| FT3 | near-ATM morning (R98e; app version, see the amendment below) | The first 5-minute mark 11:00-12:55 (per index-day) with an OTM option priced Rs 6-10, Black-76 IV ≤ 1.0333 × the ATM IV, and strike within 1.5466 ATM straddles of spot | out at 15:05 (the app's expiry square-off) | app version: +Rs 1,805, 478 tickets; 4 of 6 years > 0; +Rs 293 without the 5 best days (research version: +Rs 2,571, 474 tickets, +Rs 1,032) |
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

## Amendment, 21 Oct 2026 (same day, before any forward day): FT3 is tested as the app runs it

Boss asked for FT3 in the app ("Add FT3 to the app paper bot until live, when live is started then it should move to live").
The app cannot run FT3 exactly as researched, for two reasons:
- it has no vendor IV column, so it computes IV itself;
- it squares off every expiry-day position at 15:05.

So FT3 is re-frozen as the app runs it, and the forward test judges this version. FT1, FT2 and FT4 are unchanged.

- **IV:** Black-76 (`OptionMath.impliedVol`) with forward = spot, r = 0, and calendar time from the mark to 15:30.
  - The option's IV comes from its close at the mark.
  - The ATM IV is the mean of the ATM call's and put's IVs, each from its last close within 3 minutes (as the straddle).
- **Cut-offs** (the design-years 2021-23 10th percentiles the research rule used, to 4 decimals):
  - IV ratio ≤ 1.0333;
  - distance ≤ 1.5466 ATM straddles.
- **Ties at a mark:** the strike nearest spot, in straddles; then the lower IV ratio; then the call. The replay never met
  a tie beyond the first.
- **A mark is judged only on its own minute.** The app waits until the feed has the index's bar and each option's bar
  for that minute, up to 2 minutes (the feed's lag).
- **Entry:** a LIMIT at min(ask + 1 tick, the mark's price × 1.2 + 0.10), worked for 3 minutes after the mark's bar
  closes. Unfilled by then: no ticket that day. A missed or unfilled signal is never replaced by a later mark's.
- **Signal source:** the clock, and the option chain at the mark: the option's own 1-minute close and volume, its IV
  against the ATM's, and its distance in straddles.
  - It is labelled an option-price signal (CLAUDE.md), on by Boss's choice, with its record kept apart.
- **Exit:** out at 15:05, the app's expiry square-off. There is no stop on paper.
- **Size:** Rs 5,000 a ticket, as whole lots: floor(5,000 / (price × lot)).
- **Replay on 2021-26** (`ft3_app.py` → `results/ft3_app.md`):
  - It picks from the raw minutes, with no look-ahead. R98's decision rows kept only options with a next-minute open.
  - It uses the LIMIT cap and the 3-minute window, sells at the 15:04 bar's close, and uses fractional quantity as R98
    (the app buys whole lots).
  - Result: 478 tickets, win 16.3%; mean +Rs 1,805, total +Rs 8.6 lakh; +Rs 293 without the 5 best days; 4 of 6 years
    > 0 (2025: −Rs 1.75 lakh).
  - Filled one minute later (feed lag): 473 tickets, +Rs 1,579, +Rs 62 without the 5 best days.
  - It picks the same index-day as the research version on 470 of 474 days. The vendor and Black-76 IV ratios
    correlate 0.976.
- **Live path:** the app's standard one, used by every paper arm. The live (and Shadow) size is capped at the Bot
  settings' lot cap (2 lots by default; all its lots with the cap off), since a smaller size only lowers risk. The
  paper ticket keeps its Rs 5,000.
  - So the shadow and live records are a different size from the paper record. At 2 lots the flat Rs 20 an order
    weighs about 4% (NIFTY) to 15% (SENSEX) of the premium, against about 1% of a Rs 5,000 ticket. FT3 is judged on its
    paper record; the shadow / live record is read on its own, never added to it.
  - The paper ticket is mirrored at Zerodha only in Live mode (Boss's PIN), with "AI trades go live" on, with its own "Trade
    live" switch on, and with its Shadow off (the PIN). Until then Shadow records what it would have sent.
  - Live adds a −80% disaster stop resting at Zerodha (protection only); the paper rule has none.
- **The live record is not the test.** The forward test reads the paper record only.
