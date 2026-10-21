# R94 idea 3 (option selling): implementation notes

## N3.1 (21 Oct 2026, after the code and tests, BEFORE any P&L; only coverage counts were looked at)

Choices PREREG 3.3 leaves open, fixed in `options.py` and tested in `tests/test_options.py`:

1. **IV reference minute.** The IV regime uses one ATM IV per session: the mean of the C0 and P0 `iv` at the first grid
   minute at or after 17:15 (the planned entry minute; the last C0 / P0 row within 5 minutes before it if that minute
   has none). The percentile of a session = share of the previous 40 sessions' values strictly below it, plus half the
   ties, x 100; it needs at least 30 non-missing values among those 40 sessions, else it is undefined (no HIGH / LOW
   trade). An entry delayed by the 30-minute wait keeps its session's 17:15 percentile.
2. **HIGH twin pool.** The random-subset twin draws from the ALL variant's trades of the same window whose percentile
   is defined (the comparable period), not from trades before the 40-session warm-up.
3. **Stop / lock cadence.** Checked on every grid minute (a minute with a near-future `spot`) after the entry minute up
   to and including the time-exit minute. Within one minute the hard stop wins over the take-profit, which wins over
   the lock. The lock's floor uses the peak including the current minute.
4. **Marks.** Every minute's cost to close uses that minute's clock for the half-spread (before 17:00 doubles). A long
   leg's buy-back value is max(0, price - half-spread).
5. **In / out of window.** A held strike is in window at a minute if a row of that side carries that strike at any
   offset -3..+3 in that minute (rows exist with zero volume). A minute with no row of that side at all counts as in
   window (last print used). Out of window: short leg = intrinsic (vs that minute's `spot`) + the time value of the
   window edge (+3 or -3 strike, whichever is nearer the held strike) of the same side = max(0, edge's last printed
   close - edge's intrinsic); 0 if the edge has never printed in the segment (counted). Long leg = intrinsic only.
6. **Stale.** A leg's minute is stale when its last print is 60+ minutes old; counted per trade, never skipped.
7. **Sessions** are the book's own grid days. "Session before expiry" = the grid day before the detected expiry day.
   D1's time exit = the first grid minute at or after 17:15 of the next session, else that session's last minute.
   CY: entry session = 10 grid days before the expiry day, exit session = 1 grid day before it.
8. **Entry wait.** Candidate minutes = grid minutes from the first at or after 17:15 up to 17:45 inclusive; offsets
   are re-read at each candidate; the first candidate where every leg printed (unflagged, close > 0) within its last 5
   minutes (j-4 .. j) is the entry; the entry fill uses each leg's most recent print up to j.
9. **Gross** = the P&L at last-print prices (no half-spread, no charges); **net** = fills (half-spreads) minus Zerodha
   charges per leg round trip (and the future's for CC).
10. **Risk per lot** = the stop amount (C for IC / IF / NS, capped at the IC / IF max loss; 1% of notional for CC) +
    the round-trip charges priced at the entry premiums.
11. **CC's future** = the book's `spot` close +/- `common.fut_half_spread(book, price)`, futures charges on its round
    trip; units = the option lot's units.
12. **Bad prints.** R42's F1 spike flag (as R53 / R85) is applied to printed rows before anything; it judges a print
    by its neighbours on both sides within 15 minutes (the house cleaning rule; a mild use of the next minutes'
    prints that every MCX study since R53 shares).
13. **Expiry segments.** R53's expiry detection on the ATM straddle; a contract key is (segment, side, strike) and a
    trade never holds past the session before its segment's expiry day. The last segment of the data (expiry after
    19 Oct) can still be traded by D1.

## A3.1 (21 Oct 2026, AFTER the first design run was seen; a valuation artefact, not a rule change)

**What happened.** The first design run used PREREG 3.3's out-of-window rule as written: a LONG leg that leaves the
ATM-3..+3 window is valued at intrinsic only. The lake books hold only ATM-3..+3, so an iron condor's +3 / -3 wings
leave the window as soon as spot moves ONE strike, and a month-dated crude wing worth ~119 points was then marked at 0.
Result: 148 of 149 CRUDEOIL IC D1 trades had out-of-window minutes, and trades lost up to Rs 15,302 against a maximum
possible loss of Rs 2,032 (wing width minus credit). An iron condor cannot lose more than that; the numbers measured the
bound, not the market. The IC / IF design numbers of that run were seen (all deeply negative, win rates 0-3%); the NS
and CC numbers were also seen (they use only the short-leg bound, which is the exact no-arbitrage bound, see below).

**Amendment (applies to both windows; the as-registered valuation is still reported next to it in
`extra.prereg_valuation`, so nothing is hidden):**
1. Long leg out of window: the larger of intrinsic and the no-arbitrage convexity bound: option prices are convex in
   the strike, so beyond the two nearest in-window strikes k1 < k2 of the same side (last printed closes p1, p2) the price
   is at least the straight-line extension, p_edge + (p2 - p1) / (k2 - k1) x (K - k_edge). Still a LOWER bound for an
   asset we hold (conservative), but not a loose one. If the two strikes have no print, intrinsic (counted in
   `edge_missing`).
2. Short leg out of window: unchanged. PREREG's rule (intrinsic + the edge's time value) is already the no-arbitrage
   upper bound (a call is worth at most the nearer in-window call when its strike is higher, and at most that call plus
   the strike difference when lower; puts mirrored).
3. IC / IF: each vertical (short leg + its same-side wing) costs at most its width (+ both half-spreads) to close: a
   vertical spread is never worth more than its strike width (no-arbitrage). And the whole IC / IF costs at most its
   LARGER width (+ every half-spread): at expiry only one side can pay, so the structure is never worth more than that
   (added the same day, when the A3.1 unit test showed the two short-leg upper bounds could together exceed it).
These three are facts about option prices, not tuning; the stop, lock, take-profit, entry and every other rule are as
pre-registered. The parent decides whether the amended or the as-registered numbers are primary in the write-up; the
gate (q <= 0.10) is applied the same way to both.

## A3.2 (21 Oct 2026, AFTER the second design run was seen; the same kind of artefact in the other direction)

The A3.1 run showed IC / IF trades winning far MORE than their credit (CRUDEOILM IF D1: +Rs 1,05,555 on a Rs 836 credit;
CRUDEOIL IC D1 +Rs 49,758 on Rs 7,977). A short-premium structure can never gain more than its credit. Cause: a long
wing marked above the short leg next to it (a stale or non-synchronous print, or a convexity extension from two
stale prints), so a vertical showed a negative value; and a few entries whose credit was larger than the wing width
(prints minutes apart that no real order could have filled at). Fixes, all no-arbitrage facts, applied to both windows
(the as-registered valuation stays reported in `extra.prereg_valuation`):
4. Each IC / IF vertical's value is clipped to [0, width] before half-spreads (a vertical is worth between nothing and
   its width); its close cost = that value + both legs' half-spreads; the whole structure is still capped at the larger
   width + every half-spread.
5. The convexity extension's slope is clipped to the no-arbitrage range (calls -1..0, puts 0..+1 per point of strike),
   and a long leg out of window is never valued above the short-leg upper bound of the same strike.
6. An IC / IF entry whose credit (after half-spreads) is >= its larger width x units is skipped as an untradable,
   non-synchronous quote (counted as `skips.arb_credit`).
