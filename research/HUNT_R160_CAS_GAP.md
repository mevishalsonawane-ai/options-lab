# R160: does the closing-auction gap get corrected the next morning?

Written 11 Nov 2026 for Boss's question: "Using [the Closing Auction Session] can we predict the next day opening?"

**The full report is `research/hunt/r160_cas_gap/results/report.md`.** Its pre-registration is
`research/hunt/r160_cas_gap/PREREG.md`.

## In short

- **The auction leaves a bigger gap.** Since 3 Aug 2026 the official close sits further from where index futures and
  options trade at 15:20-15:27.
  - NIFTY: 12 bps (about 30 points) on average over 53 sessions, against 5 bps under the old VWAP close (392 sessions).
- **About half of that gap is corrected at the next open** (slope 0.47-0.59 across NIFTY, BANKNIFTY and SENSEX). But:
  - it was the same before the auction;
  - the direction is right only 51-59% of nights;
  - nothing passes BH once the 2,154 earlier tests are counted.
- **A buyer at 15:3x already pays the late price.** From there the open gave back about half the late move.
  - Buying toward the correction lost Rs 32 a trade over 30 NIFTY trades, after costs.
  - R146's Night arm made Rs 480 a trade.
- **Verdict:** a market fact, not a trade. Nothing changes in the app.
- **The rules change on 7 Dec 2026:** CAS moves to 15:30-15:40, and expiry settles on the 15:00-15:30 VWAP again.
- **Data:** the lake ends on 19 Oct 2026 and drops the 15:30-15:39 bars. Dhan's token is expired.
