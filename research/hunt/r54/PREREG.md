# R54 pre-registration: the Rs 1,000 hero ticket, entered at 14:30 instead of 12:30, and exits at 15:10 / 15:15

Written 15 Oct 2026, BEFORE any R54 trade or P&L was computed. Committed and pushed on its own.

## Why

Boss (15 Oct): "What if we enter at 2:30 pm and exit at 3:10/3:15?" This is about the Rs 1,000 hero ticket Boss chose
after R50. On each NIFTY and SENSEX expiry day it buys 1 lot of a call and 1 lot of a put. It exits on Boss's 25% trail,
else at the end time. The arm being built enters at 12:30 and squares off at 15:05.

## Data and window

- Drive lake `gdrive:IraAlgo-data-lake` (`options_rolling_1m` WEEK files and `index_1m` for NIFTY, SENSEX and INDIAVIX,
  2024-2026), copied with rclone to `$IRAALGO_LAKE` (never committed).
- **Window: 20 Nov 2024 - 13 Oct 2026 only.** No trade before 20 Nov 2024 is simulated or reported (Boss: nothing before
  Nov 2024).
- Expiry days: R50's list (`r50data.expiry_days_with_last`, R25's time-value rule plus the lake's last session). Only
  NIFTY and SENSEX own expiry days are used (each index on its own expiry day only). The list is checked against
  `research/hunt/r50/results/expiry_days.json`, and any difference is reported.
- Day records: R50's builder (R43 / R25 `Day`, every strike's held-contract grid), with two measurement filters added:
  - **R47's foreign-rows filter.** Option rows whose own `spot` is more than 3% off the index close at that minute are
    dropped (the index's last close that session is used if that minute has none). Same rule as
    `r47data.foreign_rows`.
  - **R47's stray-print guard** (amendment 1 of R47). Take the median of the contract's closes on the rows before, at
    and after the fill row. When the fill row's open is more than 20% (and 2 ticks) away from that median, the fill
    takes the WORSE of the open and the median. The same applies to "reached 2x / 5x / 10x": a high more than 20%
    above that median cannot count. This guard can only lower results.
- R25's same-minute bad-print clean (`r25data.clean`) is applied, as in R50.
- Every trade is priced on ONE held contract (the strike bought), never the rolling ATM series.

## The ticket (fixed)

- **Signal source: none (time only, both sides).** It is a fixed-time basket, not read from any chart. The strike rule
  reads the option chain's prices.
- On each NIFTY / SENSEX expiry day, at the entry time T (the 1-minute bar that CLOSES at T), one CE and one PE.
- Strike for each side: R50's "cheap" picker, with the band capped at Rs 1,000 a lot. That is the nearest-the-money
  eligible OTM strike (R25 `eligible`: within 5% of spot, traded in the last 5 bars, passing the stale-slack rule)
  whose last price is in Rs 5 to min(30, 1000 / lot). So NIFTY gets Rs 5-13.33 (lot 75) or Rs 5-15.38 (lot 65), and
  SENSEX gets Rs 5-30 (lot 20).
- Size: exactly 1 lot. If the fill price x lot is over Rs 1,000, the ticket is skipped (premium only; charges are on
  top). No eligible strike or no fill also means no ticket that side.
- Fill: R25's `enter`. A limit at the ask is capped at ref x 1.2 + 0.10 and worked for 3 minutes from the next minute
  (next-minute fills). It never chases. The stray-print guard applies to the open used.
- Spread and slippage: `Fills("liq")` (R17 liq spread). That is 5 bps plus 1 / 2 / 4 ticks a side by the contract's
  5-minute volume (>= 20 lots / > 0 / none). A 2x stress (twice the ticks) is reported for every cell.
- **Late-minute liquidity (measured before any P&L, on DESIGN days only).** The lake has no bid / ask, so two proxies
  are measured on every OTM contract priced Rs 0.05-30 on design expiry days, per 5-minute bucket from 12:30 to 15:30:
  - the median 1-minute bar range (high - low) / close;
  - the share of minutes with no trade.
  - Rule, fixed now: for each 5-minute bucket b from 15:00 on, the late multiplier is
    `max(1, median range in b / median range over 14:00-14:59)`, rounded up to the next 0.25.
  - It multiplies the tick count of every fill in that bucket, on top of the 1 / 2 / 4 ticks and on top of the 2x
    stress.
  - Buckets before 15:00 keep 1.0.
  - It can only widen costs. The table goes to `results/liquidity.json` and the write-up.
- Charges: the charges in force on the trade date (`obuy Costs("dated")`: STT 0.1% to 31 Mar 2026, 0.15% after; BSE
  rates for SENSEX). A worthless exit pays no sell charges. Lots in force on each date (R50 `r50cfg.LOTS`).

## Exits (fixed)

- **tr25 (Boss's 25% trail, R50's code exactly).** From the minute after the fill, a minute close <= 0.75 x the highest
  close since the fill sells everything. The highest close starts at the fill price, so the trail is also a 25% stop.
  It sells at the NEXT minute's open (a gap fills at the worse price). Stops read the closes of bars that close at or
  before end - 1 minute.
- **hold.** No trail. Hold to the end time.
- **End time E.** Whatever is still held sells at the open of the bar that STARTS at E (R50's 15:05 rule, generalised):
  - 15:05: the app's expiry square-off rule (CLAUDE.md);
  - 15:10 and 15:15: Boss's question.
  - If that bar is missing, the next bar's open is used, and if no bar is left, the last traded price.
  - The late multiplier applies to that sell.
- Expiry-day timing (for the write-up): NSE and BSE index options trade until 15:30 on expiry day. Both settle in cash
  on the index's closing price, which is the average of its last 30 minutes (15:00-15:30). So 15:10 and 15:15 exits
  are allowed by the exchanges. The 15:05 square-off is the app's own rule. Changing it is Boss's decision, not
  R54's.

## Cells (24) and tests

- Entry T in {12:30 (E1, the arm being built), 13:30, 14:00, 14:30 (E2, Boss's question)} x end E in {15:05, 15:10,
  15:15} x exit in {tr25, hold}.
- 13:30 and 14:00 are context only.
- Each cell is run on DESIGN and on the FINAL TEST separately.
- Per cell and period, the table reports:
  - tickets, and hit rate (net > 0);
  - how many tickets reached 2x / 5x / 10x of the fill (confirmed high, stray-guarded);
  - net, and net per Rs 1,000 staked (staked = premium + buy charges);
  - worst day and max drawdown (daily P&L);
  - top-1 and top-5 share of net, and net without the top 5;
  - the 2x-spread net, p and q;
  - the 2 Apr 2026 SENSEX and 13 Oct 2026 NIFTY tickets, called out.
- **Random twins:**
  - 500 twin sets per cell.
  - Each ticket is re-entered at a uniformly random minute of the afternoon window, with the same day, side, picker,
    1-lot rule, end time and exit.
  - The window is bars closing 12:00-14:45, R50's fixed-time twin window, the same for every cell. Up to 20 draws
    are allowed for a minute that trades, else 0.
  - p = (1 + twins with net >= the cell's) / 501. So p asks "is this entry time better than a random afternoon
    minute", not "is there an edge".
- **BH** (Benjamini-Hochberg) runs over all 24 cells, separately in design and in the final test.
- **Paired comparisons (the questions asked):**
  - Day by day, for each index-expiry-day, the summed net of the CE and PE tickets, with a day without a ticket
    counted as 0. Comparisons:
    - 14:30 minus 12:30, at each end time and exit;
    - 15:10 minus 15:05 and 15:15 minus 15:05, at each entry and exit.
  - Each comparison is reported with its mean per day and a 95% bootstrap CI (5,000 resamples of days, seed 54), in
    design and final separately.
  - **Reading, fixed now:** "X is better than Y" only if the paired difference is > 0 in design AND in the final test
    AND the design CI excludes 0. "X is worse" uses the same rule with < 0. Anything else reads "no clear
    difference".
- **Paper gate (the same as R50, for any cell to be called worth paper)**, all of:
  - final net > 0;
  - p < 0.05 and q <= 0.10;
  - 40+ final tickets;
  - final net without the top 5 > 0;
  - design net > 0.

## Split

- **DESIGN:** expiry days 20 Nov 2024 - 31 Mar 2026.
- **FINAL TEST:** expiry days 1 Apr - 13 Oct 2026. It is read once: `r54study.py final` refuses a second run.
- **Honest note:** this final test is not pristine.
  - R43 (holdout 2025-26), R44, R45, R47 and R50 have already read 1 Apr - 13 Oct 2026.
  - R50's 12:30 basket and its 13:30 / 14:30 lottery cells read these very days, with a different band and size.
  - R50 already found the 2 Apr 2026 SENSEX call (bought around 12:30) and the 13 Oct 2026 NIFTY call.
  - Only live paper from 15 Oct 2026 is truly unseen.
- Nothing is selected on the final test. All 24 cells are reported in both periods.

## What R54 does not do

- Nothing in the app changes.
- The 15:05 square-off stays as it is unless Boss decides otherwise.
- Any change to the live square-off would be a risk change for Boss to approve. Under CLAUDE.md, anything that could
  resize or delay live exits starts in shadow.

## Amendment 1 (15 Oct 2026, after the design run, BEFORE the final test was read)

- **What the hand check found.** A spot check of design trades showed that R47's stray-print guard can refuse a GENUINE
  fill. It compares the fill row's open with the median of the closes on rows r-1, r and r+1, so it reads the next
  bar's close. On NIFTY 13 Jan 2026 the 25,650 CE opened at 6.90 at 14:30, on heavy volume, and closed 24.70 the next
  minute. The guard priced the buy at 9.70 (over the cap), so there was no ticket.
- **Why it matters.** The guard was built for R47's slower trades. On cheap expiry options it removes some of the
  biggest winners, and it can only lower results.
- **What changes.** The guard stays the primary measurement, as pre-registered. Each cell also reports its net with the
  guard switched off (`no_guard_net`), in design and in the final test. That figure is informational only: no gate and
  no reading uses it.
- **No other change.** Every cell, the twins, BH, the readings and the gates are unchanged.
- **Also found in design (no change).** Every 25% trail ticket was out before 15:05 (the latest at 14:53). So with the
  trail, the 15:05 / 15:10 / 15:15 end times give the same trades in design.
