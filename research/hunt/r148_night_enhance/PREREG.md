# R148 pre-registration: enhancing Night: big-gap nights, a risk cap across indices, safer exits

Written 5 Nov 2026, BEFORE any R148 number was computed. Boss (5 Nov): "Can we enhace it". Boss chose all four: build
FINNIFTY / SENSEX into Night, a big-gap-nights study, a risk cap across indices, and a better exit.

Research only; nothing under `android/` changes here.

**Builds on R147** (`HUNT_R147_NIGHT_PLUS.md`):
- Night's profit comes from big gaps.
- Big gaps are likelier on high-VIX and weekend nights.
- No filter at the close helps.
- FINNIFTY passed; SENSEX passed without the OI check.

## 1. Data and the Night book

- **Data:** R147's lake stores (`~/.cache/iraalgo/r147/nights_<UND>.pkl`), rebuilt with two extra fields per option
  leg (section 4): the next session's 09:15 bar (O H L C) and the entry day's lowest low after entry (15:21-15:29).
  Nothing else changes; the decision features are R147's.
- **The Night book R148 enhances** is the one R147 proposes for the app:

  | index | rule |
  |---|---|
  | NIFTY | Night's exact rule (r3) |
  | BANKNIFTY | Night's exact rule (r3) |
  | FINNIFTY | Night's exact rule (r3) |
  | SENSEX | the strong close without OI (s17) |

  It is called **NB** below. Fills, costs and the 09:16 sale are R147's.

## 2. Periods

| block | dates | status |
|---|---|---|
| DESIGN | 2022-2024 (yearly folds) | read by R147 for Night |
| CHECK | 1 Jan 2025 to 16 Oct 2026 | R147's holdout, already read for Night |

R148's variants were never computed on CHECK. It is still a SECOND look at those nights, so it is disclosed as such.
**The real test is forward:** anything that passes goes to the app as SHADOW (logged only) or paper. It is judged
again after about 100 new nights.

## 3. Ideas (fixed now)

### A. Big-gap nights (R146: the gap's SIZE is predictable, its direction is not)

**"Likely big" night (L):** the next session is more than 1 calendar day away, OR India VIX at 15:19 >= 16. Both are
known at 15:20.

| id | idea |
|---|---|
| A1 | NB trades a 2nd lot on L nights (paper only; it adds risk). Measured as the sum of NB's net on L nights. Random twin: the same number of NB trades picked at random get the 2nd lot |
| A2 | Buy the ATM call + ATM put (straddle, 1 lot each) at 15:20 on every L night, every index of NB, sell both at 09:16. Random twin: straddles on the same number of random non-expiry nights |
| A3 | As A2, but only on L nights where NB itself has no trade on that index |

### B. Risk cap across indices (only lowers risk)

On a night when NB buys on 3 or 4 indices:

| id | cap |
|---|---|
| B1 | keep at most 2, the two with the strongest close (abs(close location - 0.5)) |
| B2 | keep at most 1, the strongest |

- **Measured on NB's whole book** (all 4 indices, by night): max drawdown, worst month and worst night vs NB, and the
  net given up.
- **Random twin:** drop the same number of trades from the same crowded nights at random (2,000 twins).

### C. Exits (resting orders at the broker, as the app's live path already places)

| id | exit |
|---|---|
| C1 | a stop at -20% of the entry premium, resting overnight. Watched on 15:21-15:29 lows; a next-morning open under it fills at that open, -10 bps; a 09:15 low under it fills at the stop. Otherwise the 09:16 sale |
| C2 | the same at -40% (the app's live path rests -30%; R146 found -30% changed little, so the two sides of it are tested) |
| C3 | a resting target (sell limit) at +40% of the entry premium. A 09:15 open at or above it fills at that open. A 09:15 bar whose CLOSE is at or above it fills at the target (the HIGH is not trusted: R146 A3 stale prints). Otherwise the 09:16 sale |
| C4 | C1 and C3 together |

- **Paired against NB's 09:16 sale** on the same trades.
- The 09:15 OPEN follows R146 A3: when it fails the guard, the bar's close is used.

## 4. Gates

**A1-A3 and C1-C4** must have, in DESIGN:
- a total difference (A2 / A3: net) > 0, and > 0 in at least 2 of the 3 years;
- p <= 0.05 against the twin (A) or a paired sign-flip (C);
- BH q <= 0.10 over the 7.

And in CHECK: the difference > 0 with p <= 0.05.

**B1-B2 (risk-lowering)** must, in both DESIGN and CHECK:
- cut NB's max drawdown by at least 10%;
- not worsen its worst month;
- give up no more than 15% of NB's net;
- have a drawdown at least as good as 95% of the random-drop twins'.

## 5. Verdict rules (CLAUDE.md)

- **A1** adds risk: at most paper (Boss's call), never live without shadow first.
- **A2 / A3** are new trades: paper if passed, live shadow first.
- **B and C** lower risk:
  - paper if passed (logged, one-tap undo);
  - live as shadow first, behind a switch that is off by default.
- Everything that fails stays a note, unless Boss chooses an exception.

## 6. Tests and logging

- Pytest tests cover the stop / target fills (gap-through, stale open), the cap's ranking, the L-night rule and the
  twins.
- Experiment entries are `r148-*.yaml`, failures included.
