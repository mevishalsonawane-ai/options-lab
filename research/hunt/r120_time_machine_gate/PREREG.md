# R120 pre-registration: would the time machine's verdict, read BEFORE entry, have improved each arm?

Written 28 Oct 2026, **before any R120 verdict, P&L or statistic was computed** (committed and pushed alone on
`claude/r120-time-machine-gate` ahead of the study's first run). Research only; nothing in `android/**` changes.

Boss, 28 Oct: *"Are we using the time machine to get the trades' knowledge before all strategies?"* and, on the plan,
*"Yes run the test."*

Today the app's time machine (engine `risk/TimeMachine.kt`, app `data/TimeMachineHost.kt`, log entry
`research/experiments/entries/app-time-machine.yaml`) runs only AFTER each entry, shadow only. R120 asks whether its
verdict, worked out before the entry from sessions before the entry day only, would have made each arm better as a
**filter**. The arms' signals are unchanged: **signal source of every rule = each arm's own** (the underlying for every
arm tested here except Hero, whose strike is an option-price pick, labelled so). The time machine itself reads only the
underlying's own past bars.

## 1. The verdict (a faithful port; nothing tuned)

`tm.py` ports `TimeMachine.kt` line for line, including Kotlin's own random-number generator (XorWow, as
`kotlin.random.Random(seed)`), so the same seed draws the same sessions and slots as the phone. It is unit-tested
against every case of `TimeMachineTest.kt` and against exact numbers printed by the Kotlin engine itself.

Per historical entry, exactly the app's settings (no parameter is chosen by R120):

- **Sessions**: the underlying's own 1-minute bars from the Drive lake (`index_1m`, the same NSE/BSE index minutes the
  phone harvests), folded to the arm's bar interval as `TimeMachineHost.fold` does; only sessions dated **before the
  entry day** (look-ahead guard, tested), the newest **60** (the app's `KEEP_SESSIONS`). R120 gives every underlying the
  full 60 sessions, the best case: the phone today holds a full store for NIFTY and BANKNIFTY (FINNIFTY and SENSEX on
  Jarvis builds) and only about five history-keeper days for the others (so on the phone those say "unknown"). Said in
  the write-up.
- **Draws** 2,000; slot = the entry minute floored to the bar interval, **±2 bars**; in at that bar's open; the stop
  checked **before** the target inside a bar; the arm's own time exit as `exitAt`.
- **Stop and target in underlying points** exactly as each arm's `TimeMachineHost.Entry` call passes them today (commit
  `9c71644`), premium points divided by the at-the-money delta **0.5** when the arm gives no delta; no target = target
  set to the stop (R = 1), as `TimeMachineHost.points`. Per arm, from the app code:
  - R51 Donchian 20 (+ Supertrend), R72 Breadth Donchian: premium stop 15%; the 30-60-100 lock is on for paper since
    16 Oct, which removes the fixed target, so target = stop (R 1); exit at the 15:15 square-off.
  - R52 Open outside value: premium stop 30%, no target (R 1); 15:15.
  - Liquidity 15+5: index stop 30 points (FINNIFTY 15, MIDCPNIFTY 8) and the pool target |target - level| in index
    points; 15:15 (ORB square-off). An entry with no pool target: R 1.
  - Solo 2.0: S1 the index stop and target (|index - stop|, |target - index|); S2 to S4 their premium stops (15%, 30%,
    25%) with the lock on (R 1) as the passed-arms host passes them.
  - Hero Rs 1,000 ticket (`HeroTicketArm`): premium stop = fill x its 25% trail, target = its 2x / 3x level minus the
    fill; 5-minute bars (the call's default); exit 15:05.
  - IraThinker mom60 (ntfy arm): premium stop and target; its square-off.
  - ORB, ORB Fresh, ORB Sweep, Range Fade (BANKNIFTY): the arm's premium `stopPoints` / `targetPoints`; 15:15.
  - VIX divergence: NIFTY its premium stop (15%), no target (R 1); BANKNIFTY passes no stop, so always "unknown".
  - Pine Supertrend+EMA 5m / 15m, Pine Matrix 5m (BANKNIFTY): premium stop, no target (R 1); 15:15.
  - The MCX arms (NG evening, SILVERM morning, US-night silver, 12-month trend): the future's own stop in points, no
    target (R 1), their flat-by time, on the near-month continuous future's 1-minute bars.
  - **Not testable by construction** (the app's call gives no verdict, ever): FT3 near-ATM morning (passes no stop and
    no target: always "unknown") and Night (R3) (held overnight: skipped). Listed, not tested.
- **Seed**: `java_hash(ref)` as the app (`ref.hashCode().toLong()`), with ref = `r120|<arm>|<underlying>|<entry minute>`.

Each arm's exact mapping is code in `r120arms.py`, written and committed before the first verdict is computed. An arm
whose trade list cannot be rebuilt on the current lake, or whose stop or target cannot be recovered per trade, is
listed as **not tested, with the reason**, and still counted in the BH family (as p = 1).

## 2. Trade lists (reused, not re-searched)

The historical trades of every current arm come from R113's adapters (`research/hunt/r113_time_of_day/adapters/`,
each re-running the arm's own research code unchanged: R101 units priced by R106's engine for R72 / R51+ST / R52 /
Liquidity; R57 for Solo; R110 for mom60; R27 for the ORB family; R1 for VIX divergence; R49/R92 Pine; R93-R96 MCX;
R55 for Hero) and R117's Hero list where it is the same ticket. Real charges and slippage as in those studies (dated
Zerodha charges, R17 fills, next-minute fills, 1 lot). R120 only adds, per trade, what the time machine needs (the
entry premium, the stop and target) by reading the same objects the adapters already build; the net of each trade is
the adapter's own, unchanged. Parity with R113's published counts and nets is reported per arm.

Periods (R101-R119): design 20 Nov 2024 - 31 Mar 2026; walk-forward window 1 Apr 2025 - 31 Mar 2026 (inside design);
final 1 Apr - 13 Oct 2026. Arms with other study periods keep their own (stated). The final window has been read by
R51-R119, so a pass there is weaker evidence than a fresh holdout; said.

## 3. Hypotheses and rules (the family: 2 rules per arm)

- **H1 SKIP_AGAINST**: skip every entry whose pre-entry verdict is "against". Others taken as the arm took them.
- **H2 EDGE_ONLY**: take only entries whose verdict is "edge" (coin flip and unknown skipped).
- (H3 half size on "coin flip" is NOT tested: the family stays small, and most arms trade 1 lot, which cannot be halved.)

A skipped trade is removed from the arm's own trade list (its net becomes 0). The arm's book is not re-walked (a skip
could free it for a later signal in the app); the random twin is built the same way, so the comparison is fair. Said.

H0 for each arm x rule: the gate is no better than skipping the same number of the arm's trades at random.

## 4. Metrics (per arm, per period; n with every number)

Trades, trades skipped, net Rs before and after, win rate (net > 0 after charges) before and after, maximum drawdown
of the cumulative daily net before and after, the verdict counts (edge / coin flip / against / unknown) with each
group's win rate and mean net.

**Random-skip twin**: 2,000 draws per arm x rule x period (seed 120), each skipping exactly as many of that period's
trades as the gate skipped, chosen uniformly at random. p = (1 + twins with net >= the gate's net) / (1 + 2,000). The
twin's 95th percentile is reported.

**Calibration** (descriptive, no gate): does the time machine's decided win rate (target first / (target first + stop
first)) predict the trade's actual win (net > 0)? Per arm and pooled: Brier score of the predicted rate vs the outcome,
against the Brier score of the arm's own design-period win rate as a constant forecast; ten 10-point bins with n,
mean predicted and actual win rate; and the correlation of "target first %" with the win. Unknown verdicts with no
decided draws are left out (n said).

## 5. Gates (never relaxed)

A rule passes for an arm only if ALL hold:

1. net after > net before in design, in the walk-forward window AND in the final;
2. final: the gate's net above the random twin's 95th percentile (p <= 0.05);
3. Benjamini-Hochberg q <= 0.10 on the final p, over m = every logged test to 27 Oct (each family the larger of its
   entry count and its summed `variants_tried`, as R115-R119 count it) plus R120's own 2 x (arms planned), untested
   cells taken as p = 1; R120-alone q shown for information;
4. at least 40 trades kept in the final;
5. final maximum drawdown after not worse than before.

Design and walk-forward are computed first and written to `results/design.json`; the final is read once
(`results/FINAL_READ` is written and the study refuses a second read).

If a rule passes, it is proposed to Boss as a **PAPER-only** gate (logged in the Autopilot change timeline, one-tap
undo, off live); nothing is implemented in R120 and live stays untouched. A rule that fails stays a note.

## 6. What is NOT done

No change to any arm's signal, strike, size or exit; no new parameter (draws, tolerance, lookback, margin are the
app's); no live change; no app code change. Crypto: nothing in the app trades crypto; not in scope.

## 7. Outputs

`research/HUNT_R120_TIME_MACHINE_GATE.md` (plain English, outcome first, n with every number, signal sources),
`results/` small JSON / markdown, `research/experiments/entries/r120-time-machine-gate.yaml` (failure included), the
results summary of `app-time-machine.yaml` updated, pytest tests (port vs Kotlin, look-ahead guard, twins, BH, gates),
and `RESULT.md` at the repo root.
