# R159 pre-registration: support / resistance lines, W50, Supertrend and the rest ON TOP of R151's Breadth Donchian picker

Written 10 Nov 2026, **before any R159 number was computed** (committed and pushed on `claude/r159-r151-plus-filters`
ahead of the code's first run). Research only; nothing in `android/**` changes.

Boss, 10 Nov: *"If we add multiple support and resistance and w50, and multiple things on r151, what is the output, and
also the supertrend."*

**Signal source: the index** (R72's 4-of-5 Donchian break on 5-minute index bars; R151's picker features; the filters
read the index's own bars, or the option chain as context). Exits are R72's own (XL), unchanged.

## 0. What is known

- **The base = what the app runs:** R72 Breadth Donchian with R151's logistic picker (threshold 0.4656). Published on
  1 Apr - 13 Oct 2026 (R151's walk, 1 lot): 652 trades, 48.9% won, Rs 2,19,057, against plain R72's 1,202 trades, 46%,
  Rs 1,71,366. Reproduced first (asserted).
- R150 C: W20 / W5 walls alone on R72 failed. R151's picker ALREADY reads the distance to the nearest W20 level ahead
  and behind, its touches, "crossed a level" and the floor pivots, so some of this is inside the picker. Supertrend, W50,
  the 3-month majors, OI and wicks are not in it.

## 1. The rules (fixed now): a base trade is kept only when the extra filter also passes

The filters are R155's, exactly (`hunt/r155_scalp_filters/r155feat.py`, read at the decision minute = the entry minute -
1; a filter that cannot be read fails): F1 W5, F2 W20, F3 **W50**, F4 W3M (15-minute 3-month majors), F5 PIV (floor
pivots), F6 **ALLSR (all lines at once: W20 + W50 + W3M + pivots)**, F7 B20 (crossed a level), F8 EMA20, F9 **ST
(Supertrend 10, 3 on 5-minute bars)**, F10 TWAP, F11 OIB, F12 PCR, F13 FT3, F14 WKA5, F15 WKA1, F16 WKF5, F17 CLOC, F18
BODY, F19 STACK (5 of 7). Plus three combinations Boss named:

| id | kept only when |
|---|---|
| C1 | W50 AND Supertrend |
| C2 | ALLSR AND Supertrend |
| C3 | ALLSR AND Supertrend AND EMA20 AND OIB ("multiple things") |

22 tests. A filtered-out break is removed before R151's walk (as R151 removes its own skips), 1 lot, 5 indices.

## 2. Periods and gates

- **P2** Apr 2025 - Mar 2026 (the picker's model was trained on these trades, so the BASE is in-sample here; the filters
  are not fitted to anything, and each is compared with the base on the same trades).
- **P3** 1 Apr - 13 Oct 2026, the picker's out-of-sample period, **read many times (R72 - R158): weak evidence**; read
  once by R159 (`results/FINAL_READ`).
1. **Better than the base on both counts: a higher win rate AND a higher net than R72 + R151 in P2 and in P3.**
2. Net > 0 in P2 and P3.
3. Random-skip twin, P3: 2,000 random subsets of the base's P3 trades of the same size (seed 159); p on the total net;
   BH q <= 0.10 with m = 2,132 earlier (R158's tally) + 22 = 2,154.
4. At least 40 final trades.

Reported for every rule: trades, win rate, net, net a trade, worst day, max drawdown, net without the 5 best trades, in
P2 and P3, next to the base and plain R72; the same filters on plain R72 (no picker), descriptive.

## 3. What a pass means

A passing filter only removes trades (risk-lowering): a PAPER candidate on top of the picker (logged, one tap off); live
it starts in shadow behind a switch that is off by default. Failures stay notes here and in
`research/experiments/entries/r159-*.yaml`.

## 4. Tests

`test_r159.py`: the combined keep rule (picker AND filter; unreadable = skipped), the combinations, the better-than-base
gate, base parity with R151's published picks. The filters and their look-ahead guard are R155's tests.
