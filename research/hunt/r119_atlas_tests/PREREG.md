# R119 pre-registration: R118's stable expiry-day patterns, tested as strategies

Written 27 Oct 2026, **before any R119 profit or loss was computed** (pushed alone on `claude/r117-zero-hero-lock`, the
session's branch, before the code's first run). Boss, 27 Oct: *"Test the stable patterns as pre-registered strategies,
and provide results."* Research only: nothing in `android/**` or `.github/**` changes; nothing goes to paper or live.

## Honest note before anything

- **No period is pristine.** R118 found these patterns by looking at every expiry day 2021 to 19 Oct 2026, both
  halves. So R119's "final" period (2026) was already seen by the pattern search. The only unseen data is the future.
- **R103 already tested** defined-risk iron flies / condors at 09:20 on NIFTY and BANKNIFTY expiry days (0 of 12 pass;
  costs beat the edge; stale far wings faked profits). R119 re-tests selling at the times R118 points to, on all five
  indices, with R103's stale-leg repricing from the start.
- The bar is therefore high and stated now: Benjamini-Hochberg over every logged family (about 9.3 million tests,
  earlier ones taken as p = 1). A pass needs p of about 1e-8. R119 will report plainly if nothing can reach it.

## Data

R98's expiry-day records (lake copied 27 Oct, to 19 Oct 2026): NIFTY 273, BANKNIFTY 195, FINNIFTY 167, MIDCPNIFTY 100,
SENSEX 176 expiry days; stored ATM±10 strikes, 1-minute OHLC / volume / OI; index minutes. Periods: DESIGN = expiry
days to 31 Dec 2023; WALK-FORWARD = 2024-25 (nothing is fitted: the fixed rule read again); FINAL = 1 Jan - 19 Oct
2026, read once.

## The six strategies (fixed now; no tuning afterwards)

Rows are 1-minute bars (09:15 = row 0). A decision on a bar's close fills at the next bar's open. K0 = the index close
rounded to the strike step; S = the ATM call + put close at the decision (each its last close within 3 minutes). Wing
and strike distances are rounded to the strike step (at least 1 step). 1 lot per leg, the lot in force on the day
(R50's dated lots).

| id | from R118 pattern | signal source | rule |
|---|---|---|---|
| P1 | 1, 2, 3: the straddle out-prices the typical move; tails are fat, so the risk is capped | underlying (ATM strike from the index) | **Iron fly at 09:30.** Decide on the 09:30 bar's close. Sell C(K0), P(K0); buy C(K0 + 2S), P(K0 - 2S). Hold to 15:05 (the app's expiry square-off). No stop |
| P2 | same, from 12:00 | underlying | **Iron fly at 12:00**, the same rule decided on the 12:00 bar's close, out at 15:05 |
| P3 | 3, 4: from 15:00 the straddle under-prices the move that comes | underlying | **Long straddle at 15:00.** Buy C(K0), P(K0) on the 15:00 bar's close; sell at the 15:20 bar's open. NOTE: holds past the app's 15:05 expiry square-off, so a pass would need Boss's decision to change that for this test only |
| P4 | 6: cheap OTM options are priced for far better odds than they get | option chain (non-default, labelled) | **Short longshot spreads at 12:00.** Each side: sell the OTM option (priced Rs 1+, traded in its last 3 minutes) whose Black-76 probability of ending ITM (its own IV, forward = spot, calendar time to 15:30) is nearest 3%; buy the option 2 strikes further out. Out at 15:05 |
| P5 | 5: on expiry days the index mean-reverts minute to minute | underlying (default) | **Fade sharp 15-minute moves.** At the bar closes 11:00, 11:15 ... 14:30: r = the index's 15-minute change; sd = (S / 0.798) x sqrt(15 / minutes left to 15:30). If |r| >= 1.5 sd, buy 1 lot of the ATM option AGAINST the move (put after a rise, call after a fall) at the next open; sell 15 minutes later (at the open of the bar 16 rows on), never after 15:05. One position at a time |
| P6 | 7: OTM calls reach 10x more often than puts at the same distance | underlying (strike from the index and the straddle) | **Buy the OTM call 1.25 straddles out at 12:00** (K = K0 + round(1.25 S)), hold to 15:05 |

## Pricing (the R54 / R55 engine's pieces, unchanged)

- Fills: the leg's open at the fill bar (or the next of 3 bars that has one), through R25's 'liq' fill model (5 bps
  plus 1 / 2 / 4 ticks by the leg's traded lots in the 5 minutes before) with R54's late-minute multiplier
  (results/liquidity.json). Buys pay up, sells receive less.
- Charges: R50 / obuy dated charges per order (Rs 20 brokerage, STT on sells at the dated rate, exchange, SEBI, stamp,
  GST; BSE rates for SENSEX).
- **Stale legs (R103 Amendment 2):** a leg with no open in its 3 fill bars is priced by Black-76 at its last own IV, the
  index at that bar and the time left, then given the widest (4-tick) slippage. Counted and reported.
- Options under Rs 0.05 at exit are not traded (worthless: no order, no charges).
- Margin (sellers, P1 / P2 / P4): R103's stated estimate, 2.5% of the short legs' notional, + 2% on expiry days from
  20 Nov 2024 (SEBI's expiry-day ELM). Reported, not a gate.

## Gates (all needed for a paper pass; never relaxed)

1. Mean net per trade > 0 in DESIGN, WALK-FORWARD and FINAL.
2. FINAL total > 0 without its 3 best days.
3. FINAL mean > 0 at 2x slippage (the tick count doubled).
4. Random twin, FINAL, 2,000 twins (seed 119): p < 0.05.
   - P1 / P2 / P4 / P6: each trade entered instead at a uniformly random decision bar 09:30-14:30, same structure and
     exit.
   - P3: entered at a random bar 11:00-14:40, held the same 20 minutes.
   - P5: the same signal times, with a random direction (a coin per trade).
5. Benjamini-Hochberg q <= 0.10 with m = every logged family to 27 Oct 2026 + R119's 6 (R115's count). Also
   reported, not gates: q over R119's 6 only.
6. At least 40 FINAL trades.

Reported for every strategy and period: trades, win rate, mean, total, worst day, max drawdown, months at or below
-Rs 13,000 (Boss's account stop), the stale-leg count, margin estimate, and the result at 2x slippage.

## What a pass would mean

A pass may go on PAPER only, as a separate labelled arm (CLAUDE.md): logged in the Autopilot change timeline, undoable
in one tap, shown to Boss, its record kept apart. Selling (P1 / P2 / P4) and the 15:20 hold (P3) would also need Boss's
explicit decision. Nothing goes live. Every test gets an entry in `research/experiments/entries/r119-*.yaml`.
