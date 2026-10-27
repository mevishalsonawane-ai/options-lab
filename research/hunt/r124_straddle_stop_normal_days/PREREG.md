# R124 pre-registration: R123's frozen rule on normal (non-expiry) days (research loop, round 4)

Written 27 Oct 2026, **before any R124 number was computed** (pushed alone on `claude/r117-zero-hero-lock` before the
code's first run). Research only; nothing in `android/**` changes; nothing goes to paper or live.

## Why

R123's expiry-day short straddle with an index stop was positive after costs in every period but failed its gates (the
2026 profit in 3 days, no timing edge, the pooled limits, BH). The cleanest next question is whether the MECHANISM is
real: run the same rule, FROZEN, on days it was never run on, the indices' normal (non-expiry) sessions.

- **Honest note:** R103 reported a naked 09:20 straddle held to 15:10 on NIFTY / BANKNIFTY normal days (descriptive,
  no stop). The index-stop rule has never been run on normal days, and FINNIFTY / MIDCPNIFTY / SENSEX normal days
  have never been used for any straddle result.

**Signal source:** the stop reads the underlying (the index's minute closes), the default; the entry is the clock; the
strike is the index's ATM strike.

## Data

The lake (copied 27 Oct, to 19 Oct 2026): `opt_<UND>_WEEK_<YEAR>` (rolling nearest expiry, ATM±10, real strikes) and
index minutes; every session 09:15-15:15 that is NOT the index's own expiry day (R122's builder). Indices and start
dates as R121. Periods: DESIGN to 31 Dec 2023; WALK-FORWARD 2024-25; FINAL 1 Jan - 19 Oct 2026, read once.

## The rule: R123's, unchanged

- Decision on the 09:30 bar's close: sell 1 lot C(K0) and P(K0) (K0 = the index close rounded to the strike step;
  S = the ATM straddle). Fills at the 09:31 open; a leg that cannot fill skips the day.
- Stop: the first 1-minute index close from 09:31 to the 15:04 bar at least k x S from the 09:30 close: buy both back
  at the next open. Otherwise out at the 15:05 open (kept as in R123, though the app squares off normal days later).
- A leg with no print at the exit: Black-76 at its last own IV that day, the index then and calendar time to its
  expiry's 15:30 (R122's clock). 4 ticks of slippage.
- **W1:** k = 1.0. **W2:** k = 1.5.

## Pricing and gates

R123's, unchanged: R25 'liq' slippage x R54 late multiplier, dated charges, dated lots; margin (R103's estimate, no
ELM on normal days) reported. Gates (all needed): mean > 0 in DESIGN, WALK-FORWARD and FINAL; FINAL > 0 without its 3
best days and at 2x slippage; random twin p < 0.05 (FINAL, 2,000 twins, seed 124: the same rule entered at a random
bar close 09:30-14:30, drawn from 30 random bars a day); BH q <= 0.10 with m = every logged family to 27 Oct 2026 +
R124's 2; >= 40 FINAL trades; Boss's limits at 1 lot (FINAL: no month at or below -Rs 13,000, max drawdown < Rs 40,000).

## What a pass would mean

PAPER only, a separate labelled arm, with Boss's explicit decision (naked option selling); logged, undoable in one tap,
never live from R124. Entries: `research/experiments/entries/r124-*.yaml`.
