# R120 pre-registration: five out-of-the-box expiry-day tests (size, futures, skew, twins, the morning after)

Written 27 Oct 2026, **before any R120 number was computed** (pushed alone on `claude/r117-zero-hero-lock`, the session's
branch, before the code's first run). Boss, 27 Oct: *"Do more deep research, think out of the box."* Research only:
nothing in `android/**` or `.github/**` changes; nothing goes to paper or live.

## Why these five (and what is honest about each)

R119 left one clue: the 09:30 iron fly (P1) made +Rs 231 a trade BEFORE costs on 860 expiry days, positive in every
period, and lost it to about Rs 184 of charges, almost all of it the flat Rs 20 + GST on eight orders. The edge per
lot is real; the cost per ORDER is what kills it. That points at things nobody here has tested:

| id | idea | data seen before? |
|---|---|---|
| Q1 | **Size is the lever.** The same P1 iron fly at N lots: flat brokerage is spread over N lots. N is chosen on DESIGN only. | P1's P&L by period is known from R119: this is post hoc and said so |
| Q2 | **Trade the chop with the future.** R118: expiry days mean-revert minute to minute. R119's fade lost to the option's decay; a future has none | the pattern was seen in R118 (all years); the futures P&L was not |
| Q3 | **Skew as a trade.** Puts are dearer than calls at the same distance and calls run further (R118). Sell the OTM put, buy the OTM call, with a put wing for the tail | seen in R118 descriptively |
| Q4 | **The expiring index against its twin.** On an index's own expiry, its 15-minute move relative to a non-expiring index should revert (the expiry chop is local) | NEW: never measured |
| Q5 | **The morning after.** R118: the last 30 minutes of an expiry move more than normal days'. If that is hedging flow, it should give some of it back at the next open | NEW: never measured |

**Signal source:** Q2, Q4, Q5 read and trade the underlying (the index; the index future), the CLAUDE.md default.
Q1 and Q3 are centred on the index's ATM strike and straddle (underlying); stops and exits are none / time only.

## Data and periods

- R98's expiry-day records (lake copied again 27 Oct after a container restart, to 19 Oct 2026), five indices.
- The lake's index minutes for every session: Q4's twin index on the same day, Q5's next session.
- Periods: DESIGN = to 31 Dec 2023; WALK-FORWARD = 2024-25; FINAL = 1 Jan - 19 Oct 2026, read once. Q1's lot count is
  the only fitted number, fitted on DESIGN only.
- **Futures are proxied by the index itself.** The lake's futures minutes are not used: on a given day the basis moves
  far less than the moves traded here. Said as a limit; the 2x-cost stress covers part of it.

## The rules (fixed now)

Rows are 1-minute bars (09:15 = 0); a decision on a bar's close fills at the next bar's open. Lots are R50's dated lots.

- **Q1, sized iron fly.** Exactly R119's P1 (09:30 decision, sell C/P at K0, buy wings 2 straddles out, out at 15:05).
  - Size: N lots per leg, the smallest N in 1..10 whose DESIGN-period mean net per trade at N lots is > 0 AND whose
    DESIGN months at or below -Rs 13,000 are fewest (ties: the smaller N). If none is > 0 in design: N = 1 (and it fails).
  - Cost at N lots: dated charges on N x lot per order (Rs 20 brokerage stays per order); slippage from R25's liq
    model on the leg's volume as R119, PLUS 1 extra tick for every 5 lots beyond the first 5 (the order eats into the
    book).
  - Also reported (descriptive): the whole frontier N = 1..10 in every period, with worst day, max drawdown and months
    at the stop.
- **Q2, futures fade.** R119's P5 signal unchanged (bar closes 11:00 ... 14:30; |15-minute index move| >= 1.5 x
  (S / 0.798) x sqrt(15 / minutes left)). Trade 1 lot of the index future AGAINST the move at the next open; out at the
  open 15 bars later, never after 15:05. One position at a time.
- **Q3, skew risk reversal.** At 12:00 (decision on the bar's close): sell P(K0 - d), buy C(K0 + d), buy P(K0 - d - w),
  with d = round(1.0 x S) and w = round(1.0 x S) (in strikes, >= 1 step each). 1 lot each, out at 15:05. Option fills,
  charges and stale-leg repricing exactly as R119.
- **Q4, expiring index against its twin.** Twin: NIFTY for BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX; SENSEX for NIFTY
  (and NIFTY where SENSEX has no data). At the same bar closes as Q2: rel = (the expiring index's 15-minute % move) -
  (the twin's 15-minute % move). sd_rel = the standard deviation of rel at that clock time over the expiring index's
  previous 20 expiry days (point-in-time; a day with fewer than 10 earlier days is skipped). If |rel| >= 1.5 x sd_rel,
  trade 1 lot of the EXPIRING index's future against rel; out 15 bars later, never after 15:05. One position at a time.
- **Q5, the morning after.** On each expiry day: m30 = the index's move from the 15:00 bar's close to its last close, in
  % of spot. If |m30| >= the 80th percentile of |m30| over that index's previous 40 expiry days (point-in-time; fewer
  than 20 earlier days: skipped), then at the NEXT session's first bar (09:15) open trade 1 lot of the index future
  AGAINST m30; out at the open of the 10:15 bar that session.

## Pricing

- **Futures (Q2, Q4, Q5):** R104's dated NSE index-futures charges (`index_fut_charges`: Rs 40 brokerage a round trip,
  STT on the sell at the dated rate (0.01% to Mar 2023, 0.0125% to Sep 2024, 0.02% to Mar 2026, 0.05% from Apr 2026),
  exchange, SEBI, stamp, GST); BSE's SENSEX futures get the same model (said as a limit). R104's half-spread
  max(Rs 0.05, 0.5 bp) on each fill, plus 0.5 bp slippage on each fill.
- **Options (Q1, Q3):** exactly R119's engine.

## Gates (all needed; never relaxed)

R119's: mean net per trade > 0 in DESIGN, WALK-FORWARD and FINAL; FINAL > 0 without its 3 best days; FINAL mean > 0 at
2x costs (options: 2x slippage; futures: 2x spread and slippage); random twin p < 0.05 (FINAL, 2,000 twins, seed 120:
Q1 / Q3 random decision bar 09:30-14:30, same structure and exit; Q2 / Q4 / Q5 the same trades with a random
direction); Benjamini-Hochberg q <= 0.10 with m = every logged family to 27 Oct 2026 + R120's 5; >= 40 FINAL trades.
**Plus Boss's limits at the tested size:** FINAL months at or below -Rs 13,000 = 0 and FINAL max drawdown < Rs 40,000.

Reported for each: trades, win rate, mean, total, gross and costs per trade, worst day, max drawdown, months at the
stop, the result at 2x costs.

## What a pass would mean

PAPER only, as a separate labelled arm, logged in the Autopilot change timeline, undoable in one tap, shown to Boss;
never live from R120. Q1 / Q3 sell options: Boss's explicit decision as well. Every test gets
`research/experiments/entries/r120-*.yaml`.
