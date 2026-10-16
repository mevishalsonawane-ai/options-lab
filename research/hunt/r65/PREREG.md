# R65 pre-registration: Boss's step trail (+10% -> stop +5%, +15% -> +10%, ...) on the new paper arms

Written 16 Oct 2026, **before any R65 profit or loss was computed**. Commit this file first; results come after. Research
only: nothing here changes the app (no `android/**`, no `.github/**`); the app change is Boss's decision.

Boss, 16 Oct: "When a profit moves to 10% profit we could set stoploss at 5, then if it reaches 15, we could have set it
to 10, what will happen in this scenario."

Data rule (Boss): trades only from 20 Nov 2024 onwards (the R51 / R57 stores keep their Sep-Nov 2024 indicator warm-up
rows, which never trade, exactly as R64).

## 1. Arms (signals unchanged, on the underlying) and machinery: R64's, reused as is

The three arms of R64 PREREG section 1, unchanged: **Donchian** (R51 Donchian 20), **OOV** (R52 Open outside value) and
**Solo 2.0** (R57 A2's held picks). Every entry signal is read from the **index** (the underlying); the option chosen by
the arm's strike rule is only what is bought, and only its exits change here. Stops, targets and the trail are on the
option premium (allowed by CLAUDE.md).

Engine: `hunt/r64/r64engine.py` (R51's held-contract simulator plus the breakeven step): next-minute fill, R17 "liq"
slippage (doubled for stops), stops on the minute low with gap fills at the worse open, the stop wins a tie with the
target, a lock is set by a minute's high and acts from the NEXT minute, `Costs('dated')`, lots in force on the date,
1 lot. Stores: Donchian / OOV on R51's Amendment A clean stores (stray prints and foreign rows removed); Solo 2.0 on
R57's stores. Arm code: `hunt/r64/r64arms.py`. No new simulator code: the step trail is a list of lock steps.

## 2. Boss's rule as R65 reads it (Boss can correct this)

A **step trail on the premium, in 5-point steps of the entry price** (Boss's words: at +10% the stop goes to +5%; at
+15% to +10%). With gap `g`:

- once the option has traded **+10%** above the raw fill, the stop is at least **+(10 - g)%**;
- every further **+5 points** of best gain (+15%, +20%, +25%, ...) lifts the stop 5 points: best +15% -> +(15 - g)%,
  best +20% -> +(20 - g)%, ...; between steps the stop stays at the last step (best +13% with g = 5 -> stop +5%);
- the steps run to +1000% (199 steps; no intraday option in these arms is expected beyond that, and a trade above it
  keeps the +1000% step);
- locks only rise; a lock acts from the minute after the high that set it; a gap through it fills at the open (the
  worse price) with stop-sell slippage, so a locked trade can still close below its lock;
- **g = 10**: the +10% step locks **breakeven + charges** (`real entry + charges(entry, entry) / quantity`, R64's
  breakeven) instead of the raw fill; its later steps are +5% at +15%, +10% at +20%, ...

The trail is added on top of everything else the arm has: each arm keeps its **own stop, own time exit, own square-off
and own index exits** (S1). An arm's own profit lock (OOV / S3 / S4: +10% once +40%) is kept too; it never binds,
because every trail step at +40% is at least +30%.

## 3. Variants (fixed here; 10 per arm, 30 tests)

| id | stop | profit lock | fixed target | time exit / square-off |
|---|---|---|---|---|
| **B0** baseline 1: research rules | own | own | own | own |
| **A4** baseline 2: what the app runs now (R64 A4) | own | 30/60/100 ladder (breakeven at +30%, +30% at +60%, +60% at +100%) | none | own |
| **S5** Boss's rule exactly | own | step trail g = 5 (+5% at +10%) | own (Donchian / S2 +30%, S4 +50%; OOV, S1, S3 have none) | own |
| **S5n** | own | step trail g = 5 | none | own |
| **S75** | own | step trail g = 7.5 (+2.5% at +10%) | own | own |
| **S75n** | own | step trail g = 7.5 | none | own |
| **S10** | own | step trail g = 10 (breakeven + charges at +10%) | own | own |
| **S10n** | own | step trail g = 10 | none | own |
| **T5** Amendment 1: a -5% stop moved up | **-5%** (replaces the arm's own premium stop) | step trail g = 5 started at +5%: breakeven + charges at +5%, +5% at +10%, +10% at +15%, ... | own | own |
| **T5n** | -5% | as T5 | none | own |

**Amendment 1 (16 Oct 2026, still before any R65 result was computed).** Boss, mid-task: "Also check if we could move
5% stop loss u" (the message was cut off; read as "a 5% stop loss moved up"). R65 reads it as: the stop starts **5%
below the fill** and is moved up in the same 5-point steps, always 5 points below the best gain (breakeven + charges at
+5%, then as S5). T5 / T5n replace the arm's own premium stop with -5% (S1 gets the -5% premium stop on top of its index
exits); everything else as S5 / S5n. Plain counterparts: T5 against B0, T5n against N0. Boss can correct the reading.

For OOV (no fixed target) S5 = S5n, S75 = S75n, S10 = S10n by construction; they are still run and counted.

**Plain counterparts** (references, not candidates, not gated): S5 / S75 / S10 against **B0**; S5n / S75n / S10n against
**N0** = the arm's own rules without the fixed target and without any trail (OOV: N0 = B0). They answer "what would the
same trade have done without the trail".

## 4. Measurement (as R64)

Per arm and for the three together, per period: trades, win rate, net Rs, per trade, average win, average loss, biggest
loss, worst day, max drawdown (daily), % green days, exit reasons, sample sizes (trades, days); R64's big movers
(+30% above the fill at some minute before 15:15) kept / cut.

**Trail accounting** (per variant, against its plain counterpart; trades matched by index, day, side and entry row;
unmatched trades counted and reported):
- how many trades the trail closed (exit reason "lock"; the arms' own +10%-at-+40% lock can never bind, section 2);
- Rs given up: the sum of (plain net - trail net) over trail-closed trades where the plain outcome was better;
- Rs saved on give-backs: the sum of (trail net - plain net) where the trail was better;
- winners cut short: trail-closed trades whose plain exit was at least +30% above the fill, and trail-closed trades whose
  option went on to trade +30% above the fill after the trail exit and before 15:15;
- examples: the biggest give-up and the biggest save in the final period.

## 5. Periods

- **Design: 20 Nov 2024 - 31 Mar 2026**; **walk-forward: the four quarters Apr 2025 - Mar 2026** reported one by one
  (nothing is fitted; the gate is their sum > 0). Solo 2.0's out-of-fold picks start Jul 2025, so its design = its
  walk-forward = Jul 2025 - Mar 2026.
- **Final: 1 Apr 2026 - the lake's last session** (13 Oct 2026; 14 Oct for NIFTY in R57's stores), read once by
  `r65study.py final` (guard `results/FINAL_READ`). **Honest note: this window was already read by R51, R52, R57 and
  R64** (the arms and today's A4 lock were chosen on it), so it is weak evidence. Only paper trading from 15 Oct on is
  unseen.

## 6. Gates (as R51 / R64, never relaxed) and the decision

Per variant x arm, final period: net > 0; random-ENTRY twin p < 0.05 (R64's twins, 500, seed 65); Benjamini-Hochberg
q <= 0.10 across **all 30 tests (10 variants x 3 arms)**; >= 40 trades; the top 5 trades < 100% of the net; net > 0 in
design and in the walk-forward sum.

**Random-EXIT twins** (the trail's timing against luck), final period, for each trail variant: take its plain
counterpart's trades; pick at random as many trades as the trail closed (among plain trades held at least 2 minutes),
and exit each at the open of a uniformly random minute strictly after its fill row and up to its plain exit row (the
plain stops, targets and time exits still act before that minute); 500 sets, seed 65; p = (1 + #sets with total net >=
the variant's total net) / 501. BH across the 24 trail tests (8 variants x 3 arms). For T5 / T5n the random-exit twin keeps the plain
counterpart's own stop, so it tests the -5% stop and the trail together against random early exits.

**Decision (per arm):** a trail variant is recommended only if it passes every gate above AND its exit-twin q <= 0.10
AND its net is at least **both** B0's and A4's in design, walk-forward and final. Otherwise the arm keeps what it has
(A4 on paper today); a variant with a lower max drawdown or worst day at a lower net is reported as a risk trade-off for
Boss, not a recommendation. Information only: a paired day bootstrap (2,000 draws, seed 65) of variant - B0 and
variant - A4 on the same days.

Every variant gets an experiment entry in `research/experiments/entries/`, failures included. Nothing here changes
paper or live by itself.
