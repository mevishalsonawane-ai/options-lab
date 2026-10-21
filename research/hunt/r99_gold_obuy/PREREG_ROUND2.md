# R99 round 2 pre-registration: GOLDM option buying, few trades and long holds (21 Oct 2026)

Boss: "run it loop same as hero strategy find the best strategy, dont stop until best is found" ... "Focus on gold
options buyying". Round 1 (PREREG.md, results/design.md) found nothing. This round is committed **before any trade result
of any rule below is computed**. Research only: nothing in `android/**` or `.github/**` changes.

## 0. What round 1 taught (design data only; disclosed, because it shaped this round)

- **Nothing passed.** Of 104 variants, none passed even the cheap gates. Hansen SPA p 0.715, White RC p 0.978.
- **Fading or following the 100-point moves loses before costs, as options.**
  - Those trades were held a median of **4 minutes**; the T/2 stop was hit almost at once.
  - The option moved only **0.33 × the future** over the trade, and lost **~14 option points** on the fill before
    charges (the first print after an impulse). Charges plus the half-spreads cost ~37 points more.
  - So a trade needs roughly **150-200 future points in its favour just to break even**. A 100-point move cannot pay
    for an option, however often it comes.
- **The one family positive before costs** was C, the opening-range break held for hours (median 82 minutes): the option
  moved 0.57 × the future. C1 (09:00-10:00 range, ATM, target 1× the range): +Rs 40,034 on 124 trades. It still failed
  at 2× the spread (-Rs 3,847), in 2 of 4 folds and without its 3 best trades. Its puts made the money (+Rs 43,617 on
  63), its calls did not (-Rs 3,584 on 61).

So round 2 tests one idea, suggested by round 1's design data: **trade rarely (at most once a day), hold for hours, and
aim at moves of several hundred points**. Because round 1 suggested it, its design result is not independent of that
look; the luck test is run over **rounds 1 and 2 together** (148 variants) and the holdout judges.

## 1. Unchanged from round 1 (PREREG.md s2-4, s6-7)

- **Book and data:** GOLDM near-month options, 1 lot (×10 a point), R96's book.
- **Periods:** design 5 Aug 2025 – 14 Jul 2026, and the same four folds.
- **Signal source: the GOLDM future** (minute closes). The option's own price is never a signal; the only premium rule
  is the hard stop.
- **Strikes:** ATM or 1-ITM (R96 `pick_strike`).
- **No entry** on the option expiry day, before 09:15, or in the last 30 minutes.
- **Fills:** the OPEN of the first print within 5 minutes after the signal minute, plus the half-spread.
- **Marks and exits:** R96's engine.
- **Costs:** Zerodha MCX option charges, plus a half-spread of 0.30% from 17:00 and 0.60% before; every gate again at 2×.
- **Hard stop:** -40% of the premium on printed lows.
- **Square-off:** the close − 15 minutes.
- **Twins:** 200 sets, seed 99; same side, strike rule and exit distances, at a random minute of the variant's entry
  window that session. Only for variants passing the cheap gates; others get p = 1.
- **Gates:** the same 1-7. Gate 7 is now the SPA p of the cumulative 148-variant grid < 0.05, and BH runs over all 148
  twin p-values.

New in round 2: **at most one trade a day per variant** (the first signal that fills). Stops and targets are distances in
future points from the future's close at the signal minute, so the twins reuse them.

## 2. The grid (m = 44; cumulative with round 1: 148)

Notation:
- F is the future's minute close; "the open" is its first close at or after 09:00.
- Each daily range is max − min of F over a session.
- "Range" below is the high − low of F inside the named window.

| family | signal | trade and exits | grid | count |
|---|---|---|---|---|
| **D1** morning ORB | the first minute close above the window's high (call) or below its low (put), from the window's end to 22:00 | stop: the window's other side; target: none (out at the square-off) or 2 × the range | windows 09:00-09:30, 09:00-10:00, 09:00-11:00 × exits {square-off, 2× range} × strike {ATM, 1-ITM} | 12 |
| **D2** evening ORB | as D1, until 22:30 | as D1 | windows 15:00-17:00 (London), 17:00-17:30 (US pre-market) × exits × strike | 8 |
| **E** day trend | at a fixed clock, the move since the open, \|F − open\| ≥ k × the median daily range of the 20 earlier sessions (≥ 10 needed); buy with it | stop: half the day's move so far, back toward the open; target: none; out at the square-off | clock {17:00, 19:00} × k {0.25, 0.5} × strike | 8 |
| **F** big-impulse follow | \|F[i] − F[i−W]\| ≥ X (no gap in the W minutes), from 09:15 | stop: the impulse's origin (\|mv\| back); target: none; out after H or at the square-off | (X, W) {(400, 30), (600, 60)} × H {120 min, square-off} × strike | 8 |
| **G** big-impulse fade | as F | buy against it; target 0.5 × \|mv\| back; stop 0.5 × \|mv\| further; out after H | (X, W) as F × H {60, 120 min} × strike | 8 |

- **Known overlaps:** E at 19:00 is close to R96 U1 (US-session momentum), which failed. D1 09:00-10:00 with a
  square-off exit is round 1's C1 with a wider stop, so they are not independent (BH stays valid under positive
  dependence).
- No variant is added after this file is committed. A bug fix that changes a result is reported as such.

## 3. Holdout (read once for the whole of R99)

The holdout (15 Jul – 19 Oct 2026) is still unread by any R99 rule. Round 1 did not read it.
- It is read ONCE, after this round, by `study2.py holdout`, which writes `results/HOLDOUT_READ.lock` (shared with
  `study.py`, so neither can read it again).
- It is read for every design passer of rounds 1-2 (the gates above).
- If none passes, it is read for the cumulative SPA-best variant as a description only; that cannot make it pass.

**Holdout gates:** as PREREG.md s7 (net > 0 on ≥ 10 trades, twin p ≤ 0.10, worst month > -Rs 13,000, max drawdown >
-Rs 40,000, net > 0 at 2×).

## 4. After round 2

- **Something passes the design and the holdout:** an APP_SPEC for a PAPER arm only.
- **Nothing passes:** the honest answer is "no GOLDM option-buying rule found" after rounds 1-2 and ~15 earlier
  studies.
  - The design near-misses are listed: positive at 1× and 2×, in ≥ 3 of 4 folds.
  - The best of them can be frozen for a forward paper test (as R98's FT1-FT4), and only if Boss asks.
- **No round 3** on the same design data: a third pass over the same 11 months would be fitting noise. A new round
  needs new data (forward days after 19 Oct 2026).
