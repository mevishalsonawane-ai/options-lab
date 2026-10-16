# R67 pre-registration: move the stop from -15% to -10% once the option is +25% (on top of today's A4 ladder)

Written 16 Oct 2026, **before any R67 result was computed**. Research only (no `android/**`); the app change is Boss's
decision.

Boss, 16 Oct, correcting the reading R66 used: *"Actually i meant moving the -15% stop to -10%"*, i.e. "if the option
moves +25%, move the stop up by 5 points, from -15% to -10%". (R66 tested "stop to +5% above the fill".)

## 1. Reading (Boss can correct it)

Once the option premium has traded **+25% above the fill**, the stop rises to **10% below the fill** (-10%), on top of
what the paper arms run today (R64 A4: own stop, the 30/60/100 ladder: breakeven + charges at +30%, +30% at +60%, +60%
at +100%; no fixed target; own time exit and square-off). At +30% the ladder's breakeven step takes over (it is higher).
Locks only rise. A lock set by a minute's high acts from the next minute, and a gap through it fills at the open (the
worse price).

Boss named Donchian's -15% stop. The same rule, "-10% once +25%", is applied to every arm. OOV / S3 (own stop -30%),
S4 (-25%) and S2 (-15%) all rise to -10%. S1 has no premium stop, so it gets -10% once +25%.

## 2. Variants (5 per arm, 15 tests)

| id | rule |
|---|---|
| **B0** | research rules (baseline 1) |
| **A4** | app today (baseline 2, and the plain counterpart of every M variant) |
| **M25** | **Boss's rule**: A4 + stop at -10% once +25% |
| M20 | neighbour: A4 + stop at -10% once +20% |
| M15 | neighbour: A4 + stop at -10% once +15% |

## 3. Everything else as R66 / R65

Same arms, signals on the index, engine, stores, costs, periods, statistics, trail accounting (against A4), twins (500,
seed 67), gates (as R51; BH across these 15 tests; exit twins BH across the 9 M tests) and decision: a variant is
recommended only if it passes every gate AND its exit-twin q <= 0.10 AND its net is at least both B0's and A4's in
design, walk-forward and final. Otherwise A4 stays.

**Honest note:** the final period has now been read by R51, R52, R57, R64, R65 and R66 (weak evidence), and this is the
third trade-management idea of the day (60 tests across R65-R67), logged as such.
