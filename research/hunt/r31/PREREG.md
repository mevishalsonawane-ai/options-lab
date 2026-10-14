# R31 pre-registration: Boss's 25% trailing exit on every earlier hero study's entries

Written 14 Oct 2026, BEFORE any R31 outcome was computed. Committed and pushed on its own.

Boss (14 Oct): "the target could be set by the highest of the candles ... the trailing profit [lock] should be 25% of
that highest". Clarified the same day by Boss's answers: **give back 25% from the peak**, **running from the entry**, on
**all earlier studies' entries**.

## The exit (TR25)

- After the entry fill e, the **peak** is the highest CONFIRMED price since the fill: each minute's high counts only up
  to max(its close, the next minute's open) (the exit audit's lesson: a single freak print must not raise the stop).
  The peak starts at e.
- **Stop = 75% of the peak.** From the minute after the fill, a minute close at or below the stop sells everything at
  the next minute's open (spread and charges as always). The stop is raised after each minute's check, so it applies
  from the next minute.
- From the entry, the stop is 0.75 × e: a loser is cut at about −25%.
- Otherwise the position is sold at the study's own end of day: 15:05 for every study except R27's K rules (15:25, the
  time those rules were tested with).
- Reported as a sensitivity: the same trail with the peak taken from raw highs.

## Entries (exactly as each study traded them; same days, strikes, signal minutes, sizes)

| study | rules (unique entry sets) |
|---|---|
| R26 OI wall blast | P, A1, A2, A3, A7, B1 (A4-A6 share P's entries; A8 never traded) |
| R27 NIFTY hunt | M1, M2, F1, G1, Q1, Q2 (walk-forward picks), K1, K2, K3 (Boss's late range breakout) |
| R28 Dual-Supertrend | S1, S3, S4, S5 (S2 shares S1's entries) |
| R29 gamma exposure | X1, X2, X3, X4 (each leg of the pair, Rs 1,000 a leg) |
| R30 gamma flip | G1, G2, G4 (G3 shares G1's entries) |

**26 rules.** Sizes are each study's own: Rs 2,000 a trade, or Rs 1,000 a leg for R29; whole lots. R26, R27 and R29 use
at least 1 lot, R28 and R30 skip a lot above Rs 2,000, as those studies did.

## Tests and gates

- **p:** a one-sided bootstrap over the rule's traded expiry days (10,000 resamples) of the mean per-day net > 0.
- **BH family: 52 (R21-R30) + 26 = 78.**
- **Gates (all):** ≥ 30 trades; net > 0; net > 0 in every R25 block with trades (2021-22, 2023-26); BH q < 0.05;
  net > 0 at 2× the half-spread; net > 0 without the best day; max drawdown smaller than Rs 40,000.
- Also reported for every rule: the published exit's net on the same entries (the comparison Boss asked for), hit rate,
  ≥ 2× / 4× / 10× shares of the peak, the average give-back from the peak at the exit.

## If a rule passes

Paper only, after Boss's go-ahead (CLAUDE.md). Nothing live. No app change in R31.
