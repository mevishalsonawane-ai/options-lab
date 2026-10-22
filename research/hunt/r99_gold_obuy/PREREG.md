# R99 pre-registration: GOLDM option BUYING, intraday, "catch the 100-point moves" (21 Oct 2026)

Boss, 21 Oct: "Now do same for commodities, find the best strategy, the markets moves 100+ multiple times per day, we
need to catch it, run it loop same as hero strategy find the best strategy, dont stop until best is found." Then "Only
options buyying in the scope" and "Focus on gold options buyying".

Research only: nothing in `android/**` or `.github/**` changes. This file is committed **before any trade result of
any rule below is computed**.

## 0. What was looked at before writing this (disclosed)

Before this was written, three things were computed, all on the design period only (5 Aug 2025 – 14 Jul 2026, GOLDM
near-month future, minute closes). No option P&L was computed, and the holdout was not touched.
- **100-point legs.** On a median day the future makes 68 zig-zag legs of ≥ 100 points (quartiles 40-91). Its median
  daily range is 1,794 points; the median price is 141,823. Boss's "100+ many times a day" is right.
- **Follow.** After a ≥ 100-point move in 15 minutes, the future went another +100 before giving back 50 in 34.8% of
  5,424 cases. A driftless random walk gives 33.3%.
- **Fade.** The same check against the move ("back T before another T/2"):

  | impulse | T = 100 | T = 200 | T = 300 | T = 500 |
  |---|---|---|---|---|
  | ≥ 100 in 5 minutes | 0.423 | 0.387 | 0.372 | 0.343 |
  | ≥ 200 in 15 minutes | 0.436 | 0.406 | 0.384 | 0.355 |

  The random walk gives 0.333 everywhere. Following the move gave 0.32-0.36 in every cell.

So the FADE family (A) below was suggested by the design data. Its design result is not independent of that look. Only
the luck test across the whole grid (SPA) and the holdout can judge it.

## 1. What we already know (not repeated)

About 15 earlier studies tested MCX gold option buying (survey: M3, M4, R2, R3, R53, R85, R89, R90, R91, R93, R94, R95,
MB1, R96). **None passed.** The recurring pattern:
- several ideas were positive before costs (R93 I-B +Rs 28.9k gross on 337 trades, R89's 15-minute straddle +Rs 24.3k
  on 105, MB1's futures);
- costs turned all of them negative.

The leads that failed a gate:
- M4: GOLDM OTM calls, 11-20 days to expiry, morning entry, 4-hour hold (calls in a bull market).
- R2 C4: the XAUUSD catch-up. It needs XAUUSD minutes that are not in this lake, and R53 killed it.
- R96's P1 pullback: 6 GOLDM trades.

Not repeated here:
- daily-signal rules (R96: NR7, squeeze, pullback, IV versus HAR);
- event straddles and event follow (R96 E1 / E2);
- US-session momentum (R96 U1);
- previous-day high / low breaks (R89);
- candle patterns (R85);
- market structure (R90 / R91);
- Donchian (R93);
- CCI (R95).

## 2. Periods (fixed)

- **Data:** GOLDM near-month options, 5 Aug 2025 – 19 Oct 2026 (ATM±3 stored strikes, real 1-minute prints), and the
  future from the option rows (R53 `Market`).
- **Design: 5 Aug 2025 – 14 Jul 2026.** Every trade is intraday and closes the same session.
- **Holdout: 15 Jul – 19 Oct 2026**, the same window R96 locked for its own rules.
  - It is not pristine: R2, R53, R85 and R89-R95 read parts of it for other rules.
  - No R99 rule has seen it.
  - R99 reads it ONCE, by `study.py holdout`, which writes `results/HOLDOUT_READ.lock` and refuses a second read. It is
    read for every design passer (s7), and, if none passes, for the single SPA-best variant as a description only (it
    cannot make that variant pass).
- **Walk-forward folds (fixed rules, nothing tuned):**
  - F1: 5 Aug – 31 Oct 2025;
  - F2: 1 Nov 2025 – 31 Jan 2026;
  - F3: 1 Feb – 30 Apr 2026;
  - F4: 1 May – 14 Jul 2026.

## 3. Book, signal source, strikes, fills, marks

- **Book:** GOLDM options (×10 a point, 1 lot). The 1 kg GOLD options print 10-43% of minutes, too thin to trade.
- **Signal source: the GOLDM future** (signal on the underlying). The option's own price is never a signal. The only
  premium rule is the hard stop.
- **Strikes:** from the future's close at the signal minute, chosen from the set of stored strikes (R96 `pick_strike`):
  - **ATM** (nearest the future), or
  - **1-ITM** (the next stored strike below for a call, above for a put).
- **No entry:**
  - on the option expiry day;
  - before 09:15;
  - in the last 30 minutes of the session.
- **Fill:** the OPEN of the chosen contract's first printed minute within 5 minutes after the signal minute, plus the
  half-spread. No print: no trade (counted).
- **Marks and exit prices: R96's engine** (`contract_marks`, `exit_price`).
  - A printed close is used where there is one.
  - Else the next print's open within 10 minutes.
  - Else, for a strike outside the stored window, a Black-76 model mark; that exit pays 2× the half-spread.
  - Else the last print, counted as stale.
- **Hard stop:** −40% of the premium on the option's printed lows (R96 safety). The underlying exits below are the
  rules' own.
- **Square-off:** the session close − 15 minutes.
- **One position at a time per variant.** A new signal is taken only after the previous trade has closed. A signal that
  cannot fill does not block the next one.

## 4. Costs

- Zerodha MCX option charges (R53 `mcx_opt_charges`).
- Plus a half-spread on each fill: 0.30% of the premium from 17:00 IST, 0.60% before ("R85 strict", as R89-R96).
- **Every gate is checked again at 2× the half-spreads.**
- 0.5× is reported as a sensitivity (the GOLDM spread was never measured live). It is never a gate.

## 5. The grid (one BH family, m = 104)

Notation:
- the impulse at signal minute i is mv = F[i] − F[i−W] (minute closes, no gap in the W minutes);
- "the move" is the sign of mv;
- T is a target in future points measured from F[i];
- the stop is T/2 the other way;
- the time exit is H.

| family | rule | trade | grid | count |
|---|---|---|---|---|
| **A** fade | \|mv\| ≥ X within W minutes | buy AGAINST the move (a put after an up-move); out when the future has come back T, or gone T/2 further, or after H = 60 min | (X, W) ∈ {(100, 5), (150, 15), (200, 15), (300, 30)} × T ∈ {100, 200, 300} × session ∈ {all, evening 17:00+} × strike ∈ {ATM, 1-ITM} | 48 |
| **B** follow | as A | buy WITH the move; out at +T, −T/2 or after 60 min | the same grid | 48 |
| **C** range break | the 09:00-10:00 range (C1) or the 17:00-18:30 range (C2); the first minute close above its high (long) or below its low (short), until 22:00 (C1) / 22:30 (C2); one trade per range a day | buy in the break's direction; T = k × the range height, k ∈ {1, 2}; stop T/2 back from the break close; out at the square-off | 2 ranges × 2 k × 2 strikes | 8 |

- The evening session uses signals from 17:00 to the close − 30.
- A cool-down of W minutes after each exit applies to A and B.
- **Expiry-day cheap options** ("gold hero-zero") are reported as a census only, not tested: OTM multiples by 18:15 on
  the 11 design expiry days. With 11 days, no rule could reach 30 trades.

## 6. Twins and the luck test

- **Twins** (200 sets, seed 99): each trade is replaced by the same trade (side rule, strike rule, exits) at a random
  signal minute of the same session, inside the variant's window. The p-value is the share of twin sets whose total
  net is ≥ the variant's.
  - Twins are run only for variants that pass gates 1, 2, 4 and 6 below.
  - Every other variant gets p = 1 (conservative).
- **BH** over the 104 twin p-values.
- **Hansen SPA and White's Reality Check** (`obuy.overfit.spa`, B = 2,000, mean block 5, seed 99) over the 104 variants'
  daily net (design sessions; 0 on a day with no trade).
- **PBO** (`obuy.overfit.pbo`, S = 16) over the same matrix.

## 7. Gates

**Design** (all must hold; 1 lot; 1× costs unless said):
1. ≥ 30 trades.
2. Net > 0, and net > 0 at 2× the half-spreads.
3. Twin p < 0.05 and BH q ≤ 0.10.
4. Net > 0 in ≥ 3 of the 4 folds.
5. Worst calendar month > −Rs 13,000 and max drawdown > −Rs 40,000 (Boss's account stop rule).
6. Net > 0 without the best 3 trades.
7. Family level: the SPA p of the grid < 0.05.

**Holdout** (once, for a variant passing 1-7): net > 0 on ≥ 10 trades, twin p ≤ 0.10, worst month > −Rs 13,000, max
drawdown > −Rs 40,000, and net > 0 at 2× spread.

Only a holdout passer gets an APP_SPEC for a PAPER arm.

If nothing passes, the honest answer is "no rule found". The variants that are positive on design at 1× and 2× in
≥ 3 of 4 folds are then listed as near-misses. The best of them may be frozen for a forward paper test (as R98's
FT1-FT4), and only if Boss asks.

## 8. Reporting

Per variant:
- trades, win rate, average win / loss;
- gross, costs, net, at 2× and 0.5× spread;
- by fold;
- worst month, max drawdown;
- without the best 3 trades and without the best 5 days;
- twin p, BH q;
- the shares of model-priced, stale and unfilled exits;
- the median premium and the stop risk.

Family-level: SPA / RC p and PBO. Every number carries its trade count.

Experiment entries: `research/experiments/entries/r99-*.yaml`, family `mcx-crypto` (one entry per family A / B / C,
with variants_tried).
