# R122 pre-registration: a context-aware time machine, searched in a bounded loop

Written 28 Oct 2026, **before any R122 number was computed** (committed and pushed alone first). Research only.

Boss, 28 Oct: *"We could also feed our strategies and chart pattern and all the other things to the timemachine"*,
*"Enhance it in loop until we get success"*, and on the bounded-loop proposal: *"yes start R122"*.

**Signal source of every rule: each arm's own** (the underlying; Hero's strike by the option's price, labelled). R122
only adds a pre-entry **filter** (skip or keep); every input is read from the underlying index, India VIX and the
arms' own earlier entries, at or before the entry minute. No option price is used as a signal.

## 1. What "success" means (fixed now)

1. **Search (design only).** At most **30 variants**, in rounds, on the design period (20 Nov 2024 - 31 Mar 2026),
   judged on a monthly **walk-forward** (each month Apr 2025 - Mar 2026 predicted by a model fitted only on trades
   before that month). Stop at 30 variants, or after **3 rounds** with no better pooled walk-forward p.
2. **Freeze** the best variant (pooled walk-forward p, ties by uplift). Its fit for the final uses design trades only.
3. **Final, read once** (1 Apr - 13 Oct 2026; not fresh: read by R51-R121). Gates below. A pass earns step 4 only.
4. **Forward test** (proposed only if step 3 passes): the phone logs the frozen gate's verdict on every new paper trade,
   shadow only, and it is judged after 30-60 signal days with its own pre-registration. Only a forward pass may become
   a paper gate (logged, one-tap undo); live is never touched by R122.

If step 3 fails, R122 ends with "no" and the loop stops (no new variants on the final).

## 2. Units, inputs, labels

- **Trades**: R120's lists of the 14 tested arms (R72, R51, R52, LIQ, SOLO, HERO, ORB_ORB, ORB_FRESH, ORB_SWEEP,
  ORB_FADE, VIXDIV, PINE x3), real charges, 1 lot. ORB_FRESH duplicates ORB on BANKNIFTY: evaluated, never trained on
  twice. Label: win = net > 0 after charges (models); the gate is judged on net Rs.
- **Inputs (point in time: bars before the entry minute, daily values from earlier sessions)**:
  - time machine: R120's app verdict numbers (decided win rate, target-first %) and R121's setup mean R and n;
  - context: opening gap % (vs the previous close), move from today's open to the last closed minute (in ATR14 of
    daily ranges), previous close vs its 20-day average (trend), 30-minute realised volatility, distance from today's
    high and low so far, minute of the day, weekday, expiry day;
  - India VIX: previous close (and its tercile from training), change since today's open;
  - chart patterns on the index: yesterday NR7 / inside day, price above yesterday's high or below its low, 5-minute
    Supertrend (10, 3) direction, 5-minute EMA20 vs EMA50, 5-minute Donchian-20 position, the last three 5-minute bars'
    direction;
  - the other strategies: entries of the OTHER arms on the same index in the 60 minutes before, same and opposite
    direction (their entries are known at their own fill time);
  - the arm (one-hot) and the trade's direction. Every input is signed by the trade's direction where it has one.
- Look-ahead guard (tested): no input changes when the entry minute's own bar or anything after it changes.

## 3. The variants (round 1 fixed now; later rounds built only from round results on design)

Round 1 (12): single rules that skip a trade when
- R1 TREND: the 20-day trend is against the direction;
- R2 INTRADAY: the move since the open is against it;
- R3 VIX_HIGH: the previous VIX close is in the top tercile (cut from training);
- R4 GAP_AGAINST: the opening gap is more than 0.25% against it;
- R5 OPPOSED: another arm entered the opposite way on the same index in the 60 minutes before;
- R6 SUPERTREND: 5-minute Supertrend is against it;
- R7 EMA: 5-minute EMA20 vs EMA50 is against it;
- R8 CHASE: the move since the open in its direction is more than 1.0 ATR14 (chasing);
- R9 TM_SETUP: R121's setup mean R (V_EXITS) below 0 with n >= 10;
- learned, skipping the trades whose predicted win probability is in the bottom 20% of the training predictions of
  their arm: L1 logistic regression (L2, C 0.1, standardised inputs); L2 nearest analogs (k 50, the share of
  winners among the 50 most similar past trades); L3 gradient-boosted trees (depth 3, 200 trees, rate 0.05, leaf 40).

Rounds 2 and 3 (at most 18 more): combinations of round-1 rules that helped (AND of skip conditions), the learned
models on the inputs that helped, and the skip share 10% / 30% for the best learned model. Every variant tried is
listed in `results/loop.json` with its walk-forward numbers and counted in the BH family.

## 4. Metrics and gates (never relaxed)

Per arm and pooled, per period: trades, skipped, net before / after, win rate, maximum drawdown. **Random-skip twin**:
20,000 draws per arm (seed 122) skipping the same number of that arm's trades; the pooled twin sums the arms' twins.
p is taken from the **normal approximation** of the twin distribution (z of the gate's net), so a strong effect can
reach a small p (the empirical twin percentile, floor 1 / 20,001, could never clear BH over ~9.3 million tests);
the empirical 95th percentile is reported too.

The frozen variant passes step 3 only if ALL hold:
1. pooled net after > pooled net before in the walk-forward window AND in the final;
2. final pooled gate net above the twin's 95th percentile, and normal-approximation p <= 0.05;
3. BH q <= 0.10 on that p over m = every logged test to 28 Oct (R121 included) + the R122 variants tried;
4. at least 40 final trades kept on every arm the gate would apply to, and pooled final drawdown not worse;
5. net after >= net before in the final on at least 2 of the 4 paper index arms (R72, R51, R52, LIQ) (no broad loss).

Per-arm results are shown for information; if the pooled gate passes, it would apply only to arms whose final net rose.

## 5. Outputs

`research/HUNT_R122_CONTEXT_GATE.md` (plain English, outcome first, n with every number, signal sources),
`research/hunt/r122_context_gate/results/` (loop.json, design, final, report), one experiment entry per round plus the
final (`r122-*.yaml`, variants counted), pytest tests (features point-in-time, rules, walk-forward never trains on its
own month, twins and the normal p), `RESULT.md`.
