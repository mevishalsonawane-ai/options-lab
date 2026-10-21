# R98 pre-registration: "catch the 100+ rupee move in any condition" (21 Oct 2026)

Boss, 21 Oct: *"I want you to catch the 100+ rs movement in any condition, do all the deep analysis you want, study
the candles, study the market ... think out of the box."* Research only: nothing in `android/**` or `.github/**`
changes; nothing goes to paper or live.

Written and committed **before any rule below was simulated** (no rule P&L, no model fitted). Looked at first, on the
DESIGN window only (5 Aug 2025 - 14 Jul 2026), descriptively: the bracket base rates (`dataset.py`), the one-feature
scan with day-clustered p and BH (`scan.py`, `results/scan.json`), a few two-feature combinations and the XAU-lead
cell by fold, side, clock and with one minute of extra delay (numbers in s1). The rules were chosen BECAUSE of those
numbers, so the design test is not independent of the choice; the twins, BH and the untouched holdout decide.

## 1. What the descriptive steps found (design; GOLDM; a 5-minute grid of 73,432 entry moments, both sides)

- **N1, the reading of "100 rupees":** 100 points of the quote (rupees per 10 g). On the GOLDM future 100 points is
  Rs 1,000 a lot; on the option premium 100 points is Rs 1,000 a lot too, but needs ~220 points of the future.
- Gold is a fair coin at this scale: from a random moment, +100 before -100 within 60 minutes 46.8% of the time,
  -100 first 46.8%, gross 0.0 points. A futures round trip costs ~43 points (Zerodha charges, CTT 0.01% of the sale
  dominates, + R53's spread); the option bracket (+100 / -50 premium) loses 42.6 points net (37,117 fills).
- Tilts (one feature, day-clustered, 34 of 254 buckets at BH q <= 0.10): gold snaps back at this scale (stretched
  from the session average, fast 15-min moves, 5-min / 15-min candles all against the trade: +6 to +15 points); XAUUSD
  ahead of MCX: +12.6. Every option bucket lost (-33 to -58 net).
- XAUUSD ahead of the future by >= 10 bp over 15 min, with the future's last three 5-min candles not already moving
  that way: +42.5 points gross (3,197 moments, 204 days); folds +22 / +18 / +71 / +37; long +49, short +36; by start
  hour 09-12 +4, 12-15 +40, 15-18 +45, 18-21 +56, 21+ +97; +34.5 with one more minute of delay.
- Caveat on those labels: the descriptive bracket took the first minute CLOSE beyond the target (an overshoot in the
  trade's favour). The engine below exits at the target level itself.

## 2. Periods, book, signal source

R96 / R97's periods and folds, unchanged; **LOCKED HOLDOUT 15 Jul - 19 Oct 2026**, read once (`study.py holdout`)
only for a rule passing every design gate; its dataset is built only then. Signals: on the dataset's 5-minute grid
(an entry moment every 5 minutes, 09:15-22:30 IST, both sides), from causal features only (R97 `features`,
R98 `candles`; look-ahead guards tested). **Signal source: the MCX gold future** (GOLDM near future, minute closes);
XAUUSD (Dukascopy spot) only as a filter on the future's own move.

**Trade: the GOLDM FUTURE, 1 lot** (CLAUDE.md: "buys the option (or trades the future)"), except R98E which buys the
ATM GOLDM option on R98A's signal for comparison. A GOLDM futures lot needs exchange margin (roughly Rs 1.5-2 lakh at
these prices); its stop risk is ~Rs 1,000 + Rs 430 costs.

## 3. The six rules (each one test; BH family = R96 27 + R97 7 + these 6 = 40)

| id | signal (the trade's direction d; "against" = opposite to d) | instrument | window |
|---|---|---|---|
| R98A | XAUUSD's 15-min move beats the future's by >= 10 bp in direction d, and the future's 3-candle (5-min) momentum <= 0 in d | future | 09:15-22:30 |
| R98B | R98A, afternoon and evening only | future | 12:00-22:30 |
| R98C | walk-forward gradient boosting predicts the bracket's gross >= 55 points (cost + 12) | future | 09:15-22:30 |
| R98D | the future >= 600 points from the session average AGAINST d, and XAUUSD >= 5 bp ahead in d | future | 09:15-22:30 |
| R98E | R98A's signal | ATM option | 09:15-22:30 |
| R98F | candle fade: 2+ 15-min candles in a row against d, and the last 5-min candle's body >= 58 points against d | future | 09:15-22:30 |

R98C settings (fixed now): sklearn `HistGradientBoostingRegressor(learning_rate=0.05, max_iter=300,
max_leaf_nodes=15, min_samples_leaf=300, l2_regularization=1.0, random_state=98)` on every causal feature column of
the dataset plus side and clock (`model.columns`), label = the gross points of the +100 / -100 / 60-min future bracket.
Fold k (2-4) is predicted by a model trained on design rows before the fold's first day minus one day; fold 1 is
training only (R98C trades only F2-F4). Holdout: one model trained on all design rows.

At most 4 trades a session; one position at a time (a signal while a position is open is skipped).

## 4. Exits (a stop and a profit lock on every trade)

Future (`engine.sim_future`): fill at the close of the minute after the signal; then on each minute close: stop at
entry -100 (out at that close), target +100 (out at +100 exactly, a resting limit), **profit lock**: after a close
of +70 or better the stop moves to entry +20; time exit 60 minutes after entry or at the square-off (close - 15 min).
Option (R98E, `engine.sim_option`): ATM at the first print within 5 minutes; the same future-based target / stop /
lock / time decide when; sold at the option's exit price that minute; premium hard stop -15% on the minute's low.

## 5. Costs

Future: Zerodha MCX futures charges (R53 `mcx_fut_charges`) + R53's half-spread (max(tick, 0.01% of price) / 2) on
each fill (~43 points a round trip); gates also at 2x spread. Option: R96's costs (charges + 0.60% / 0.30% half-spread),
2x on model-priced exits.

## 6. Gates (R97's, unchanged)

Design: >= 30 trades; net > 0 at 1x and 2x spread; random-entry twins p < 0.05 (same month, clock minute, side,
instrument and exits; 500 sets); BH q <= 0.10 over 40; >= 3 of 4 folds positive (R98C: its fold 1 has no trades, so it
needs F2-F4 all positive); no month < -Rs 13,000; drawdown > -Rs 40,000; net without the top 3 > 0; model-priced exits
<= 10% and real-exit net > 0. Holdout: >= 10 trades, net > 0, twin p <= 0.10, month and drawdown limits.
