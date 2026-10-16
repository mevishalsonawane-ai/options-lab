# R66 pre-registration: lock +5% once the option is +25% (on top of today's A4 ladder)

Written 16 Oct 2026, **before any R66 result was computed**. Research only (no `android/**`); the app change is Boss's
decision.

Boss, 16 Oct, after R65 (the +10% -> +5% step trail lost to A4) and after 8 of their 10 recent paper trades went into
profit and then came back to the stop: *"What if we do 25% of target, means if the target moves 25% then move stoploss 5
percent up."*

## 1. Reading (Boss can correct it)

Once the option premium has traded **+25% above the fill**, the stop rises to **+5% above the fill**. R66 adds this one
step to what the paper arms run today (R64 A4: own stop, the 30/60/100 ladder (breakeven + charges at +30%, +30% at
+60%, +60% at +100%), no fixed target, own time exit and square-off). At +30% the ladder's breakeven step is below +5%,
so the stop stays at +5% until +60%. Locks only rise. A lock set by a minute's high acts from the next minute, and a gap
through it fills at the open (the worse price), so a locked trade can still close a little below +5%.

(The other reading, "move the stop up by 5 points, from -15% to -10%", is not tested. A tighter loss stop was already
tested in R64 A3 and lost to the arms' own stops.)

## 2. Variants (5 per arm, 15 tests)

| id | rule |
|---|---|
| **B0** | research rules (baseline 1) |
| **A4** | app today (baseline 2, and the plain counterpart of every L variant) |
| **L25** | **Boss's rule**: A4 + stop at +5% once +25% |
| L20 | neighbour: A4 + stop at +5% once +20% |
| L30 | neighbour: A4 + stop at +5% once +30% (the ladder's breakeven step raised to +5%) |

The neighbours are there to show whether the answer depends on the exact +25% trigger. Only L25 is Boss's rule, but all
three are gated the same way and counted in the multiple-testing correction.

## 3. Everything else as R65 (`hunt/r65/PREREG.md`)

Same arms (Donchian, OOV, Solo 2.0; signals on the index, options bought by the arm's strike rule), the same engine and
stores (R64 / R51 / R57), costs, periods (design 20 Nov 2024 - 31 Mar 2026, walk-forward quarters from Apr 2025, final
1 Apr - 13/14 Oct 2026), statistics and trail accounting (against A4, the same trades without the +25% step: Rs given
up, Rs saved, winners cut short). Random-entry twins (500, seed 66), BH q <= 0.10 across these **15 tests**. Random-exit
twins (500, seed 66; as many random early exits as the new step closed trades), BH across the 9 L tests. Gates as R51:
final net > 0, p < 0.05, q <= 0.10, >= 40 trades, top 5 < net, design and walk-forward net > 0.

**Decision:** a variant is recommended only if it passes every gate AND its exit-twin q <= 0.10 AND its net is at least
both B0's and A4's in design, walk-forward and final. Otherwise A4 stays.

**Honest note:** the final period has now been read by R51, R52, R57, R64 and R65. It is weak evidence; the design period
carries most of the weight. R66 is one more idea in the same family (trade management, 45 tests across R65 + R66), and
it is logged as such.
