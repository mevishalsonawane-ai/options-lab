# R121 pre-registration: the overnight option-selling premium (research loop, round 1)

Written 27 Oct 2026, **before any R121 number was computed** (pushed alone on `claude/r117-zero-hero-lock` before the
code's first run). Boss, 27 Oct: *"Do all the research you can on loop ... find the best strategy you can with max
winrate."* Research only; nothing in `android/**` changes; nothing goes to paper or live.

## Why this idea (and why a high win rate is not enough)

- **The maths of win rate.** If an entry has no edge, no exit can create one (optional stopping): targets and stops only
  trade win rate against the size of wins and losses. A high win rate AND a profit needs an edge in the price.
- **R117-R120's only real edge** was on the option seller's side: on expiry days the straddle prices more movement
  than usually comes. It lost to the flat per-order costs and to fat tails.
- **Out-of-sample evidence from elsewhere:** Muravyev & Ni (2020, "Why do option returns change sign from day to
  night?", J. Financial Economics) find that option sellers earn the premium mostly OVERNIGHT; intraday it can reverse.
  Overnight time decay on a short-dated straddle is large against Rs 190 of charges, and most nights are quiet, so
  win rate should be high. The risk is the overnight gap; wings cap it.
- **Not tested before here.** The experiment log has one "day before expiry" entry and no overnight short-premium test
  (R103's sellers were intraday, 09:20 to the close).

**Signal source:** none directional (the clock); the structure is centred on the index's ATM strike (underlying).

## Data

- The lake (copied 27 Oct, to 19 Oct 2026): `opt_<UND>_WEEK_<YEAR>` (rolling nearest expiry, ATM±10, 1-minute, real
  strikes) and index minutes, for every session.
- A "night" is a pair (d, n): a session d that is NOT one of the index's expiry days (R98's list), and the next session
  n. Both then trade the same expiry, so each leg is the same contract on both days.
- Indices and start dates as R98: NIFTY, BANKNIFTY, FINNIFTY (from 9 Nov 2021), MIDCPNIFTY (from 7 Jun 2023), SENSEX
  (from 19 May 2023).
- Periods by d: DESIGN to 31 Dec 2023; WALK-FORWARD 2024-25; FINAL 1 Jan - 19 Oct 2026, read once.

## The rule (one structure, two tests)

- **Entry:** decision on the 15:15 bar's close of d. K0 = the index close rounded to the strike step; S = the ATM call
  + put (each its last close within 3 minutes). Sell C(K0) and P(K0); buy C(K0 + 2S) and P(K0 - 2S) (rounded to the
  step, at least 1 step, at most 10). 1 lot each, the lot in force on d. Fills at the 15:16 open (or the next of 3
  bars with one); a leg that cannot fill skips the night.
- **Exit:** the open of the 09:20 bar on n (or the next of 3 bars with one). A leg with no print there is priced by
  Black-76 at its last own IV on d, the index at n's 09:20 open, and calendar time to its expiry (15:30 on the expiry
  date), then given 4 ticks of slippage. Counted.
- **T1:** every night (d, n) as defined.
- **T2:** only the night before expiry (n is an expiry day): the largest overnight decay.

## Pricing

R119's engine pieces:
- R25's 'liq' slippage (5 bps + 1 / 2 / 4 ticks by the leg's lots in the 5 minutes before), with R54's late multiplier
  at 15:16.
- R50 / obuy dated charges per order; options under Rs 0.05 at exit are not traded.
- Margin: R103's stated estimate (2.5% of the short legs' notional; + 2% ELM when the exit day is an expiry from
  20 Nov 2024). Reported, not a gate; real overnight SPAN margin is higher.

## Gates (all needed; never relaxed)

1. Mean net per trade > 0 in DESIGN, WALK-FORWARD and FINAL.
2. FINAL > 0 without its 3 best days, and FINAL mean > 0 at 2x slippage.
3. Day-bootstrap one-sided p < 0.05 that the FINAL mean is <= 0 (5,000 resamples of nights, seed 121); R103's
   convention for a seller, where random-time twins do not apply to a fixed overnight hold.
4. Benjamini-Hochberg q <= 0.10, m = every logged family to 27 Oct 2026 + R121's 2.
5. At least 40 FINAL trades.
6. Boss's limits at 1 lot, FINAL: no month at or below -Rs 13,000, and max drawdown < Rs 40,000.

Reported: trades, WIN RATE, mean, total, worst night, max drawdown, months at the stop, stale legs, margin, by night
type (weekday / weekend-or-holiday, nights before expiry vs earlier).

## What a pass would mean

PAPER only, as a separate labelled arm, with Boss's explicit decision (it sells options and holds overnight); logged,
undoable in one tap, never live from R121. Entries: `research/experiments/entries/r121-*.yaml`.
