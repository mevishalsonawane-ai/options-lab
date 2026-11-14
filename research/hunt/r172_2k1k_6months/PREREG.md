# R172 pre-registration: the last 6 months with Boss's fixed +Rs 2,000 / -Rs 1,000 on three arms (a reporting backtest)

Written 14 Nov 2026, **before any R172 trade or P&L number was computed** (committed on `claude/r172-2k1k-6months` before
the code's first run). Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 14 Nov, chose (as a **"failed gates, Boss's exception"**, being built into the app) a fixed +Rs 2,000 target and
-Rs 1,000 stop on Liquidity 15+5, R51 Donchian 20 (+ Supertrend) and R72 Breadth Donchian, and asked: *"provide me results
of last 6 months"*.

**What this is and is not.** A reporting backtest of a rule Boss has already chosen. It is NOT a gate test: there is no
pass / fail, no random-exit twin and no Benjamini-Hochberg step (nothing is being selected from many tries). The window
overlaps the final period (1 Apr - 13 Oct 2026) that R51-R171 have read many times, and R152 / R106 already read rupee
targets and stops on these arms there; so the numbers describe what the rule would have done, not evidence that it works.

Signal source for every rule: **the index** (CLAUDE.md default). Entries are unchanged; only the option's exit changes (a
rupee target and stop on the premium, which CLAUDE.md allows).

## 1. Window (fixed now)

The lake (`gdrive:IraAlgo-data-lake`, copied 14 Nov 2026) ends on **19 Oct 2026** (index_1m and options_rolling_1m WEEK,
last minute 15:29; checked before any P&L). The last 6 months: **20 Apr - 19 Oct 2026**, every session in it. Months
reported: Apr (20-30 only), May, Jun, Jul, Aug, Sep, Oct (1-19 only).

Stores: R51's Amendment A clean stores built to 19 Oct (R106's `clean_post` folder, `R106_POST=1` / `R152_POST=1`).
Engine check first (reported, not a P&L claim): on these stores, today's rules at 1 lot over 1 Apr - 13 Oct must give
the published finals (Liquidity 357 / Rs 11,155; R72 1,202 / Rs 1,71,366; R51 + Supertrend 1,670 / Rs 1,34,743) and R151's
picker walk (R72 652 / Rs 2,19,057; R51 693 / Rs 1,59,040). Any difference is reported with its size.

## 2. The arms and the variants (fixed now)

Engine, candidates, fills, charges and taking rules exactly as R152 (`research/hunt/r152_donchian_2k/`, R106's harness):
R64's held-contract simulator, R17 liquidity fills (slippage from the 5-minute volume per position, stops -10 bps and twice
the half-spread, gaps fill at the open), dated Zerodha charges on the whole quantity, next-minute fills, stops on the
minute low, limits at their price. qty = lots x the index's lot size on that date; lots 1, 2, 3.

| arm | candidates and taking | today's exits |
|---|---|---|
| Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5) | R101's units (as R106): one position a book, expiry days skipped | -15% premium stop, 20-min time stop unless +5%, index exits, 15:10 |
| R72 Breadth Donchian (4 of 5) | R101's units (as R106 / R152) | XL: -15% stop, 30-60-100 ladder + L25, 30-min time stop, 15:15 |
| R51 Donchian 20 + Supertrend (10, 3) | R112 / R115 units (as R152) | XL |
Donchian arms: one open position a side, at most 2 fresh fills a side a day.

Variants, every arm x lots 1-3:

- **(a) A** = today's rules.
- **(b) B** = (a) + R151's picker as live (R72 and R51 only; Liquidity has no picker, so B = A for it): each candidate
  scored with the app's exported numbers (`r72_picker_model.json` 0.4656, `r51_picker_model.json` 0.4469); a break scored
  below is skipped but still holds its side and counts in the day's caps (walked, then removed); a break that cannot be
  scored is taken (R152's second look, `r152picker.kept`).
- **(c) A+2k1k** and **B+2k1k** = (a) / (b) with Boss's new rule:
  - P&L of the whole position before charges = (option price - entry fill) x qty, entry fill = the trade's real fill
    (R17, slippage included).
  - **Target**: a resting limit at `T = entry fill + 2,000 / qty`, rounded UP to the 0.05 tick; it fills at T when the
    minute's high reaches T (a gap above fills at T too, as every limit in this harness).
  - **Stop**: a stop at `S = entry fill - 1,000 / qty`, rounded UP to the tick (the planned loss before charges is at most
    Rs 1,000); triggered when the minute's low reaches S; sold as every stop here (R17 stop fill; a gap through S fills
    at the minute's open), so the realised loss can exceed Rs 1,000 (reported). **It acts only when S is above the arm's
    own -15% stop level** (the tighter one); otherwise the arm's own stop acts as today. If S <= 0 it does not act.
  - Every existing exit stays (-15% stop, ladder / L25 locks, time stops, index exits, square-offs); whichever comes first.
    The Rs 2,000 and Rs 1,000 are for the whole position at every lot size (Rs 667 / Rs 333 a lot at 3 lots).

## 3. What is reported

For every arm x lots x variant, and for the three arms together (their trades summed; B together = Liquidity A + R72 B +
R51 B): trades, win % (net > 0), net after charges and slippage, average net win / average net loss, the exit mix
(Rs 2,000 target / Rs 1,000 stop / other, other split into the -15% stop and the rest), the largest single loss, worst
day, worst month, max drawdown (on daily net), and net month by month. A short plain-English summary for Boss first.

Nothing is gated, so no multiple-testing correction is applied; each number comes with its trade count. Entries in
`research/experiments/entries/r172-*.yaml` (verdict `not-a-test`: a report of Boss's chosen rule).
