# R121 pre-registration: an enhanced time machine that replays the arm's own setup with its real exits

Written 28 Oct 2026, **before any R121 number was computed** (committed and pushed alone ahead of the first run).
Research only; the app's time machine is not changed by R121.

Boss, 28 Oct, after R120 (the app's time machine as a pre-entry gate passed on no arm; its odds did not predict
wins): *"Can we enhance it"* and, on the proposal, *"yes do both"*.

**Signal source:** each arm's own, unchanged (the underlying for every arm except Hero's option-price strike pick,
labelled). The enhanced time machine reads only the underlying's own past bars and the arm's own past entries.

## 1. What R120 found wrong, and the two changes

R120's time machine replays a **random** past session at the same clock time, so it measures how the index moves at
that time of day, not how this arm's setups go; and it plays every replay to the square-off with a stop and target,
while most arms leave on a 20-60 minute time stop or a lock. R121 changes exactly those two things:

1. **SETUP**: the sample is the arm's **own entries** on the same underlying in the 60 sessions before the entry day
   (dates strictly before it), each replayed from its own entry minute in its own direction, instead of 2,000 random
   draws at the clock slot. (The arm's taken entries stand for "the rule re-run on those sessions": the trade lists
   are the arms' own walks, so a signal its book did not take is left out; said.)
2. **EXITS**: each replay is walked with the arm's **real exits as the app runs them on paper today**, converted to
   underlying points at the at-the-money delta 0.5 (premium move = 0.5 x index move), on the underlying's 1-minute
   bars, the stop (or lock) checked before the target inside a bar:
   - R51 Donchian + Supertrend, R72 Breadth Donchian, Solo S2: stop 15% of the premium; the paper lock (R66 L25 then
     R64 30-60-100: +5% locked once +25%, breakeven once +30%, +30% once +60%, +60% once +100%), no fixed target;
     out 30 minutes after the fill; square-off 15:15.
   - R52 Open outside value, Solo S3: stop 30%; the same lock; out after 60 minutes; 15:10. Solo S4: stop 25%; lock;
     60 minutes; 15:10. Solo S1: no stop on the paper host, so no replay (unknown), as in R120.
   - Liquidity 15+5: index stop (30 points; FINNIFTY 15, MIDCPNIFTY 8), the pool target in index points (none: no
     target); at 20 minutes out unless the premium is 5% up; 15:10.
   - VIX divergence NIFTY: stop 15%, at 20 minutes out unless 5% up, 15:10. BANKNIFTY: no stop (unknown), as R120.
   - ORB, ORB Fresh, Range Fade: stop and target 40 premium points (ORB Sweep: target 80) with the owner's ladder on
     the target distance (lock breakeven once 25% of the way, 25% once 50%, 50% once 75%); 15:10.
   - Hero Rs 1,000 ticket: the 25% trail from the fill (from the best price so far), no target; 15:05.
   - Pine (3 scripts): the 30-point premium stop (none when the fill is under about 30: unknown); 15:15.

**Score**: each replay's result in R (index points at exit / the replay's own stop points: a stop = -1, a lock = its
locked fraction, a time or square-off exit = the index move then). The verdict compares the sample's mean R with 0:
**edge** if mean >= +margin, **against** if mean <= -margin, else **coin flip**, margin = max(0.05 R, 2 x standard
error over the replays); fewer than 10 replays: **unknown** (taken, as in R120).

## 2. Variants (the family)

- **V_SETUP**: change 1 only (the arm's own past entries, walked with stop and target to the square-off, as the app's
  walk), scored in R as above.
- **V_EXITS**: changes 1 and 2 (the arm's own past entries walked with its real exits).

Rule tested for both: **SKIP_AGAINST** (skip entries whose verdict is "against"). R120's "take only edge" is not
re-tested: it skipped about 95% of trades and lost on every profitable arm.

Arms: the 14 R120 tested (R72, R51, R52, LIQ, SOLO, HERO, ORB_ORB, ORB_FRESH, ORB_SWEEP, ORB_FADE, VIXDIV,
PINE_ST_EMA_5M, PINE_ST_EMA_15M, PINE_MATRIX_5M), with R120's trade lists unchanged. Family: 2 x 14 = **28 tests**.

## 3. Metrics, twins, gates (as R120, never relaxed)

Per arm, variant and period (design 20 Nov 2024 - 31 Mar 2026; walk-forward Apr 2025 - Mar 2026; final 1 Apr -
13 Oct 2026): trades, skipped, net before / after, win rate, max drawdown, verdict counts with each group's win rate
and mean net; random-skip twin (2,000 draws, same number skipped, p = (1 + twins >= the rule's net) / 2,001);
calibration of the replays' mean R against the trade's real win (correlation, ten bins with n; Brier is not used,
mean R is not a probability). The "R120 app" verdict is shown beside, for comparison.

Gates: net up in design, walk-forward and final; final above the twin's 95th percentile (p <= 0.05); BH q <= 0.10
over every logged test to 28 Oct (R120's 38 included) plus R121's 28; >= 40 final trades kept; final drawdown not
worse. Design and walk-forward are written to `results/design.json` and committed before the final is read once.

**The final is not fresh** (read by R51-R120, R120 included, and R121's variants were designed after seeing R120's
final). A pass here is therefore only a reason to **log** the passing variant's verdict on the phone in shadow (no
trade is skipped or resized, paper or live) and judge it on the phone's own forward log after 30 signal days, with a
new pre-registration. Nothing is implemented by R121.

## 4. Look-ahead guard

A trade's verdict reads only entries dated before its day and the underlying's bars of those days (tested: changing
the entry day's or any later day's bars or entries never changes the verdict). Each replay ends on its own day.

## 5. Outputs

`research/HUNT_R121_TIME_MACHINE_SETUP.md` (plain English, outcome first, n with every number),
`research/hunt/r121_time_machine_setup/results/`, `research/experiments/entries/r121-time-machine-setup.yaml`
(failure included), pytest tests (walk, lock, trail, time stops, score, verdict, look-ahead), `RESULT.md`.
