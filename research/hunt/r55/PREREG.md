# R55 pre-registration: when should the hero ticket's 25% trail start, and what if it is held to expiry settlement?

Written 15 Oct 2026, BEFORE any R55 trade or P&L was computed. Committed and pushed on its own.

## Why

- Boss chose the Rs 1,000 hero ticket: on each NIFTY and SENSEX own expiry day, 1 lot of a CE and 1 lot of a PE in
  R54's cheap band, entered at 12:30, with "the 25% trail".
- R54 found that its trail (R50's tr25) starts at the fill. So it is also a 25% stop, and it usually fires within
  minutes. Example (R54 final test): on 2 Apr 2026 it sold the SENSEX 72,500 CE at 2.7x for +Rs 280. Held to 15:05,
  that ticket made +Rs 13,958.
- Boss had understood the trail to start "once the option has run up". R55 tests trails that arm only after the
  option has doubled (2x), tripled (3x) or gone 5x.
- Boss also asked what happens if the 15:05 square-off is removed for hero tickets only. R55 tests holding to 15:15
  and holding to expiry settlement.

## Data, window, days, ticket, pricing (unchanged from R54)

Everything below is R54's code, imported, not copied (`research/hunt/r54`): `r54data`, `r54lib`, `r54cfg` and
`results/liquidity.json`.

- Drive lake `gdrive:IraAlgo-data-lake`: `options_rolling_1m` WEEK files and `index_1m` for NIFTY, SENSEX and
  INDIAVIX, 2024-2026. Copied with rclone to `$IRAALGO_LAKE` (never committed).
- Window: expiry days 20 Nov 2024 - 13 Oct 2026. The days are R54's: R50's expiry rule, NIFTY and SENSEX own expiry
  days only.
- The day records are R54's: R43 / R25 `Day`, every strike's held-contract grid, R25's same-minute bad-print clean,
  R47's foreign-rows filter and R47's stray-print guard (on fills, on sells at an open and on "reached 2x / 5x / 10x").
- Ticket (signal source: none; time only, both sides; the strike rule reads the option chain's prices):
  - at the bar closing at the entry time, R50's "cheap" picker with the band capped at Rs 1,000 a lot;
  - exactly 1 lot each of the CE and the PE, skipped if fill x lot > Rs 1,000;
  - R25's next-minute limit fill (capped at ref x 1.2 + 0.10, 3 minutes, never chases).
- Spread: R17 liq (5 bps + 1 / 2 / 4 ticks by 5-minute volume), times R54's late-minute multipliers (x2.25 for
  15:00-15:09, x2.75 for 15:10-15:29, measured by R54 on design days). A 2x spread stress is reported per cell.
- Charges: the ones in force on the trade date (`Costs("dated")`, BSE rates for SENSEX). Lots in force on each date.
- Every trade is priced on ONE held contract.

## Exits (the trail start)

The trail always works on minute closes of the held contract. A close at or below 0.75 x the best close sells
everything at the NEXT minute's open (a gap fills at the worse price; R54's sell, guard and late multiplier). Stops
read the closes of bars that close at or before end - 1 minute.

- **T0, trail from the fill (R50 tr25 = R54).** The best close starts at the fill price, so it is also a 25% stop.
  This must reproduce R54's tr25 cells exactly (a test checks this on design).
- **T2, armed after 2x.** No stop. Nothing happens until a minute close (not a proxy row) is >= 2 x the fill price
  (the price paid, after spread). That close becomes the best close. From the next minute, a close <= 0.75 x the
  best close since arming sells everything. If it never arms, or never falls 25%, the ticket is held to the end.
- **T3, armed after 3x.** The same, armed at 3 x the fill.
- **T5, armed after 5x.** The same, armed at 5 x the fill.
- **H, hold.** No trail, held to the end.

## End times

- **15:05:** the app's expiry square-off rule (CLAUDE.md). The ticket sells at the open of the bar that starts at
  15:05 (R54's rule, with its fallbacks).
- **15:15:** Boss's earlier question, R54's rule. The trail reads closes up to 15:14.
- **SETTLE, hold to expiry settlement.** Nothing is sold at the end. The trail stays active until the close: it reads
  closes of bars closing up to 15:29 and sells at the next open (the 15:29 bar at the latest). What is still held at
  15:30 is settled by the exchange:
  - **Settlement price.** NSE / BSE index options are cash-settled at the index's closing price, which is a weighted
    average of the index over its last 30 minutes. The lake has no index volume, so R55 uses the plain mean of the
    index's 1-minute closes on the bars starting 15:00 to 15:29 (up to 30 bars). If fewer than 20 of those bars exist,
    the day's last index close is used instead, and the day is flagged.
  - **ITM (CE: price > strike; PE: price < strike).** It pays the intrinsic value, |price - strike| x quantity. No
    sell-side spread applies.
  - **OTM or at the money.** It expires worthless. Value 0, no sell charges.
  - **Charges on an exercised ITM option** (on the settlement value = intrinsic x quantity):
    - STT on exercise, at the rate in force on the expiry date: 0.125% up to 31 Mar 2026, 0.15% from 1 Apr 2026
      (Budget 2026 raised the exercise rate with the premium rate; Budget 2024 left it at 0.125%);
    - plus, to stay conservative, brokerage Rs 20, the exchange transaction charge and the SEBI fee on the settlement
      value at the dated rates, and GST 18% on brokerage + exchange + SEBI;
    - no stamp duty (there is no buy).
    These can only lower the result.

## Cells (30) and tests

- Entry {12:30 (the arm being built; primary), 14:30 (reference)} x exit {T0, T2, T3, T5, H} x end {15:05, 15:15,
  SETTLE}.
- Each cell runs on DESIGN and on the FINAL TEST separately.
- Per cell and period, the table reports:
  - tickets and hit rate (net > 0);
  - how many tickets reached 2x / 5x / 10x of the fill while held (R54's stray-guarded confirmed high);
  - net and net per Rs 1,000 staked (premium + buy charges);
  - worst day and max drawdown (daily P&L);
  - top-1 and top-5 share of net, and net without the top 5;
  - the 2x-spread net, p and q;
  - for SETTLE cells, how many tickets were settled ITM and how many expired worthless.
- Called out in every cell: the 2 Apr 2026 SENSEX and 13 Oct 2026 NIFTY tickets.
- **Random twins:** R54's method. 500 twin sets per cell. Each ticket is re-entered at a uniformly random minute of
  the bars closing 12:00-14:45 (same day, side, picker, 1-lot rule, end and exit), with up to 20 draws for a minute
  that trades, else 0. p = (1 + twins with net >= the cell's) / 501.
- **BH** over all 30 cells, separately in design and in the final test.
- **Paired day-by-day comparisons** (R54's method: summed CE + PE net per index-expiry-day, a day without a ticket
  counts as 0; mean and 95% bootstrap CI over days, 5,000 resamples, seed 55):
  - T2, T3, T5 and H, each minus T0, at each entry and end;
  - SETTLE minus 15:05 and 15:15 minus 15:05, at each entry and exit.
- **Reading (R54's rule, fixed now):**
  - "X is better than Y" only if the paired difference is > 0 in design AND in the final test AND the design CI
    excludes 0.
  - "X is worse" uses the same rule with < 0.
  - Anything else reads "no clear difference".
- **Which trail start is best (decided on DESIGN only).** At entry 12:30, separately for each end time, the best
  trail start is the one among T0 / T2 / T3 / T5 / H with the highest design net. The final test only confirms. It
  is called "clearly better than T0" only under the reading rule above.
- **Paper gate (R50 / R54's):** final net > 0, p < 0.05, q <= 0.10, 40+ final tickets, final net without the top 5 >
  0, and design net > 0.

## Split

- **DESIGN:** expiry days 20 Nov 2024 - 31 Mar 2026.
- **FINAL TEST:** expiry days 1 Apr - 13 Oct 2026. It is read once: `r55study.py final` refuses a second run.
- **Honest note:** this final test is not pristine.
  - R43, R44, R45, R47, R50 and R54 have already read 1 Apr - 13 Oct 2026.
  - R54 read these exact tickets with T0 and H at 15:05 and 15:15, and reported the 2 Apr 2026 SENSEX call (held to
    15:05: +Rs 13,958) and the 13 Oct 2026 NIFTY call.
  - So the armed trails were proposed AFTER a final-test day was seen.
  - Only live paper from 15 Oct 2026 is truly unseen.
- Nothing is selected on the final test. All 30 cells are reported in both periods.

## What R55 does not do

- Nothing in the app changes.
- The 15:05 square-off stays for every arm and for anything live.
- Holding the hero PAPER ticket past 15:05, or to settlement, is Boss's call alone. Any such change would be logged
  in the Autopilot timeline and undoable, and would never carry over to live.
- Settlement is modelled, not observed: the index's last-30-minute mean stands in for the official closing price.
