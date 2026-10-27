# R122 pre-registration: sell the opening premium, buy it back at 10:30 (research loop, round 2)

Written 27 Oct 2026, **before any R122 number was computed** (pushed alone on `claude/r117-zero-hero-lock` before the
code's first run). Boss, 27 Oct: *"Do all the research you can on loop ... max winrate."* Research only; nothing in
`android/**` changes; nothing goes to paper or live.

## Why

- R121: selling overnight loses, because buying back at 09:20 pays for the opening IV premium (options are dearest at
  the open; R118: NIFTY ATM IV 21.8% at 09:30, 19.8% at 10:30 on expiry days).
- The mirror idea: SELL into that opening premium, and buy back at 10:30 once IV has settled and 70 minutes of time
  decay have passed. A short hold keeps the fat-tail exposure small; the wings cap it.
- Not tested before: R103 and R119 sold at 09:20 / 09:30 but held to the close; nothing in the log exits at 10:30.
- **Honest note:** the idea comes from R118's and R121's descriptive findings on the same 2021-26 data. No period is
  pristine for the IDEA; the R122 P&L has never been computed.

**Signal source:** none directional (the clock); the structure is centred on the index's ATM strike (underlying).

## Data

- The lake (copied 27 Oct, to 19 Oct 2026): `opt_<UND>_WEEK_<YEAR>` (rolling nearest expiry, ATM±10, real strikes)
  and index minutes, every session 09:15-10:40.
- Indices and start dates as R121.
- Periods: DESIGN to 31 Dec 2023; WALK-FORWARD 2024-25; FINAL 1 Jan - 19 Oct 2026, read once.

## The rule (one structure, two tests)

- **Entry:** decision on the 09:20 bar's close. K0 = the index close rounded to the strike step; S = the ATM call +
  put (last close within 3 minutes). Sell C(K0) and P(K0); buy C(K0 + 2S) and P(K0 - 2S) (rounded, 1-10 steps). 1 lot
  each, the day's lot. Fills at the 09:21 open (or the next of 3 bars); a leg that cannot fill skips the day.
- **Exit:** the open of the 10:30 bar (or the next of 3 bars). A leg with no print there is priced by Black-76 at its
  last own IV that morning, the index then, and calendar time to its expiry's 15:30; 4 ticks of slippage. Counted.
- **U1:** every session that is NOT the index's expiry day.
- **U2:** the index's own expiry days.

## Pricing

R119's engine pieces: R25 'liq' slippage x R54's late multiplier (1 in the morning), R50 / obuy dated charges per
order (8 orders), options under Rs 0.05 not traded. Margin: R103's estimate (reported).

## Gates (all needed; never relaxed)

1. Mean net per trade > 0 in DESIGN, WALK-FORWARD and FINAL.
2. FINAL > 0 without its 3 best days, and FINAL mean > 0 at 2x slippage.
3. **Random twin**, FINAL, 2,000 twins (seed 122): each trade is replaced by the same structure decided at a uniformly
   random bar close 10:30-14:00 that day and held the same 70 minutes. p = (1 + twins >= actual) / 2,001 (normal tail
   when none). p < 0.05. This asks whether the OPEN is special.
4. Benjamini-Hochberg q <= 0.10, m = every logged family to 27 Oct 2026 + R122's 2.
5. At least 40 FINAL trades.
6. Boss's limits at 1 lot, FINAL: no month at or below -Rs 13,000; max drawdown < Rs 40,000.

Reported: trades, WIN RATE, mean, total, gross and charges per trade, worst day, max drawdown, months at the stop,
stale legs, by index.

## What a pass would mean

PAPER only, as a separate labelled arm, with Boss's explicit decision (it sells options); logged, undoable in one tap,
never live from R122. Entries: `research/experiments/entries/r122-*.yaml`.
