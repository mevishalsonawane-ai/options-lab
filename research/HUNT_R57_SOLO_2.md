# R57 "Solo 2.0": a trained Solo that picks its own paper trades (15 Oct 2026)

Boss, 15 Oct: "Now we have all data, can you train the solo options, and make it more powerful, smart to take the
trades on his own." Solo is Jarvis's own trader and stays **paper only, forever**. Pre-registered in
`hunt/r57/PREREG.md` (commit a2278995, before any P&L or model score). Code: `research/hunt/r57/`. Research only:
nothing in the app changed.

## The answer

**Yes, one trained version beat plain SoloMidday on the unseen period, and it passed every gate.** It is A2, a
"meta-labeller": a small logistic model that looks at the day's candidate trades from four index rules and decides
take or skip, and which index. It takes at most one trade a day.

Final test (1 Apr - 14 Oct 2026, read once):
- A2: 128 trades, 59% won, **net +Rs 99,172 (+Rs 775 a trade)**. Twin p 0.002, BH q 0.01. Max drawdown Rs 18,056,
  6 of 7 months green. Its 5 best trades are 40% of the net. At 2x costs it still makes +Rs 80,849.
- **Plain SoloMidday, same period: 36 trades, -Rs 21,589**, p 0.84. On the 36 days SoloMidday traded, A2 made
  +Rs 43,857.

**It is not proven, and the caveats are real:**
1. **72% of A2's final profit is MIDCPNIFTY** (+Rs 71,586 on 82 trades). In the design walk-forward A2 made nothing on
   MIDCPNIFTY (90 trades, -Rs 153). Without MIDCPNIFTY the final is +Rs 27,586 on 46 trades.
2. **92 of A2's 128 final trades come from setup S3, R52's H37-10** ("open outside value and go"). R52 chose that
   rule's 60-minute exit and then read it on this same final window (+Rs 48,772, MIDCPNIFTY +46k). So the final window
   is not fresh for S3, and A2 partly re-finds R52's result. What is new is the selection: A2 beat random choices from
   the same day's candidates (selection-twin p 0.002). The setups unfiltered made: S3 +Rs 49,360 on 232 trades, all
   four together far less.
3. Model skill is small. Out-of-sample AUC is 0.52-0.54; the leaky random split shows 0.56-0.61. The profit comes from
   skipping the worst setups and days, not from reading direction.

The direction model (family B) failed in every version. Its AUC was about 0.49-0.53, the same as R41, B2 and the old
learner: the next 30-60 minutes of an index are a coin flip after costs.

**Recommendation:** A2 is a paper candidate only. It should go to the coordinator's app build and run on paper next to
plain SoloMidday, with a pre-registered forward bar. Live paper from 15 Oct 2026 is the only truly unseen data.

## What was tested

**Data.** Drive lake, 20 Nov 2024 (first session 21 Nov) to 14 Oct 2026 (NIFTY, BANKNIFTY) / 13 Oct (FINNIFTY,
MIDCPNIFTY). Inputs:
- index 1-minute bars and India VIX;
- WEEK option files only (for BANKNIFTY, FINNIFTY and MIDCPNIFTY the MONTH file is the same contract, so it is never
  read or counted twice).

Indices: the four NSE F&O indices the paper account can trade. SENSEX is out: it is BSE, and the account can't trade
it.

Splits:
- Design: 21 Nov 2024 - 31 Mar 2026, purged walk-forward with monthly refits. Test months were Jul 2025 - Mar 2026;
  each month was trained on everything before it, minus a 1-session embargo.
- Final: 1 Apr - 14 Oct 2026, read once (guard file). The monthly refits continued, as the retrain job would do.

**Measurement (never relaxed).** Every label and P&L is the HELD contract's (R51's engine): the strike bought at entry,
priced to its exit, never the rolling ATM series. Other rules:
- next-minute fills;
- R17 liq spread and bps, doubled on stops;
- dated charges (STT 0.15% from 1 Apr 2026);
- lots in force on each date, 1 lot;
- expiry days skipped: Solo never trades an index on its own expiry day.

Cleaning before any contract was built (rows dropped):

| index | foreign rows (spot > 3% off the index, R47) | bad ticks (spikes.py rule) | strike > 12 steps from spot |
|---|---|---|---|
| NIFTY | 0 | 4,905 | 2 |
| BANKNIFTY | 440 | 590 | 5 |
| FINNIFTY | 115,698 | 1,323 | 43,571 |
| MIDCPNIFTY | 0 | 1,207 | 15,463 |

**Candidate setups for the meta-labeller.** The signal source is stated for each. Every candidate carries a stop and a
profit lock. A rule that had none got the Solo layer: -30% premium stop, and +10% locked once +40% is reached.

| id | setup | signal source | strike | exits |
|---|---|---|---|---|
| S1 | SoloMidday, exactly as SoloMidday.kt | index 1-min | 4-ITM | index stop 0.3 ATR14 (1-min close), breakeven at 0.45 ATR, 14:30 |
| S2 | R51 Donchian-20-RT | index 5-min | 1-ITM | -15% / +30%, 30 min, 15:15, max 2 a side a day |
| S3 | R52 H37-10, open outside the previous day's 70% value area | index 5-min + previous day's TPO profile | 1-ITM | 60 min + Solo layer |
| S4 | 15-min opening-range break, first 5-min close beyond it | index 5-min | 1-ITM | -25% / +50%, 60 min, + lock |
| C4 | R47 BANKNIFTY PCR follow (**non-default**: option-chain signal) | option chain OI | 3-ITM | -30%, +30% locked once +60%, 15:15 |

**Features**, read at the signal bar's close; a look-ahead test covers each one:
- 12 brain index features (`ret_15m`, `ret_60m`, `ret_day`, `gap_pct`, `day_range_vs_adr10`, `pos_in_day_range`,
  `rv_30m`, `er_day`, `dist_tpavg_pct`, `range_30m_pct`, `dist_prev_close_pct`, `or30_state`), computed with
  `research.brain.features` itself;
- daily ATR14 as a % of price, the minute of the day, sessions to expiry;
- India VIX and its change from the previous close;
- option-chain context only: put/call OI ratio (ATM+-5), ATM IV, and IV skew (put at -3 minus call at +3);
- index one-hots;
- family A also gets the side, the setup one-hot, and the directional features signed by the trade's side.

**Models: 10 pre-registered variants, one BH family.**
- A1-A4 meta-labeller:
  - A1: boosted trees, depth 3, 150 trees;
  - A2: logistic, C = 0.1;
  - A3: boosted Huber regression on net return;
  - A4: A1 with C4 included.
  - Take rule: the day's candidates in time order; the first whose score is at least the 70th percentile of that
    month's training scores (A3: predicted net > 0); then nothing more that day.
- B1-B6 direction model at 09:45 / 10:30 / 12:00 / 13:30:
  - each predicts the index up or down over 30 or 60 minutes;
  - it trades only if `(2p - 1) x the usual move x delta x lot` beats the expected round trip;
  - exits: index stop 0.3 ATR, -25% premium stop, +10% locked at +30%, time exit;
  - GBT or logistic, 1-ITM or ATM.

## Results

AUC (held-contract labels: win or lose for family A; call / put trade net > 0 for family B):

| variant | random split (leaks) | design walk-forward | final |
|---|---|---|---|
| A1 GBT | 0.582 | 0.521 | 0.533 |
| A2 logistic | 0.556 | 0.525 | 0.538 |
| A3 GBT Huber | 0.582 | 0.521 | 0.516 |
| A4 GBT + C4 | 0.591 | 0.519 | 0.528 |
| B1 / B5 (30 min) | 0.609 (index direction) | 0.514 (index), 0.522 / 0.522 (held) | 0.494 (index), 0.501 / 0.499 (held) |
| B2 / B6 (60 min) | 0.603 | 0.513, 0.523 / 0.519 | 0.512, 0.513 / 0.509 |
| B3 (logistic, 30 min) | 0.519 | 0.492, 0.495 | 0.515, 0.516 |
| B4 (logistic, 60 min) | 0.494 | 0.472, 0.476 | 0.531, 0.520 |

The random split leaks: neighbouring and same-day rows land on both sides. Its 0.56-0.61 falls to 0.47-0.54 once the
split is purged by time.

Trading results, net Rs, 1 lot, after real costs. "Design WF" is the 9 out-of-fold months, Jul 2025 - Mar 2026.

| variant | design WF: trades, net | final: trades, win | final net (Rs/trade) | 2x costs | max DD | worst day | months green | top-5 share | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **A2** | 166, +55,910 | 128, 59% | **+99,172 (+775)** | +80,849 | 18,056 | -6,120 | 6/7 | 40% | 0.002 | 0.010 | **PASS** |
| A3 | 172, +45,116 | 128, 53% | +39,756 (+311) | +23,431 | 20,435 | -9,049 | 6/7 | 73% | 0.004 | 0.013 | PASS |
| A4 (non-default) | 158, +76,221 | 121, 56% | +59,706 (+493) | +44,069 | 12,788 | -5,749 | 5/7 | 50% | 0.002 | 0.010 | PASS |
| A1 | 164, +40,567 | 120, 55% | +27,421 (+229) | +11,762 | 13,571 | -9,049 | 4/7 | 105% | 0.008 | 0.020 | FAIL: top-5 >= net |
| B1 | 176, -41,424 | 129, 40% | -31,504 | -47,634 | 40,562 | -6,492 | 0/7 | - | 0.62 | 0.83 | FAIL |
| B2 | 176, +31,732 | 130, 50% | -24,829 | -41,899 | 33,836 | -6,742 | 3/7 | - | 0.55 | 0.83 | FAIL |
| B3 | 176, -50,403 | 117, 38% | -50,784 | -65,601 | 51,770 | -5,805 | 0/7 | - | 0.94 | 0.94 | FAIL |
| B4 | 174, -24,181 | 122, 42% | -19,266 | -34,537 | 37,491 | -6,069 | 3/7 | - | 0.45 | 0.83 | FAIL |
| B5 | 176, -22,886 | 127, 41% | -27,882 | -43,064 | 41,147 | -6,063 | 0/7 | - | 0.66 | 0.83 | FAIL |
| B6 | 176, +18,342 | 130, 45% | -38,466 | -54,893 | 47,971 | -6,416 | 1/7 | - | 0.78 | 0.87 | FAIL |
| plain SoloMidday (reference) | 64, +19,497 | 36, 42% | **-21,589 (-600)** | -26,635 | 28,815 | -6,235 | 4/7 | - | 0.84 | - | reference |

What the p means: 500 random-entry twins per variant. Each twin trade keeps the day, index, strike rule and exits, but
draws its minute at random and its side by coin flip. Those twins averaged about -Rs 20k to -25k per variant in the
final test: buying a random option once a day loses money.

Selection-twin p for family A (random picks from the same day's candidates; reported, not gated):

| A1 | A2 | A3 | A4 |
|---|---|---|---|
| 0.11 | 0.002 | 0.044 | 0.010 |

Per index, final test:

| variant | NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY |
|---|---|---|---|---|
| A2 | 8 trades, +9,973 | 12, +1,912 | 26, +15,701 | 82, +71,586 |
| A3 | 30, +11,294 | 26, -7,489 | 27, +4,618 | 45, +31,333 |
| A4 | 33, +14,957 | 25, +9,184 | 21, +16,677 | 42, +18,888 |
| plain SoloMidday | 11, -16,319 | 11, -7,975 | 14, +2,705 | - |

A2 by setup, final:

| S1 | S2 | S3 | S4 |
|---|---|---|---|
| 3 trades, +7,523 | 31, +26,255 | 92, +63,937 | 2, +1,457 |

A2 by month, Apr - Oct:

| Apr | May | Jun | Jul | Aug | Sep | Oct (to 14th) |
|---|---|---|---|---|---|---|
| +24,716 | +21,146 | +8,532 | +283 | +22,248 | +26,204 | -3,959 |

The setups unfiltered, final test (every candidate; several a day): S1 89 trades +1,329; S2 1,468 trades +49,733
(-1,38,763 at 2x costs); S3 232 trades +49,360; S4 459 trades -1,14,070; C4 53 trades +10,630. The 15-minute ORB (S4)
lost again, as in R49.

## What A2 is, exactly (the settings that passed)

- Candidates each day, on NIFTY, BANKNIFTY, FINNIFTY and MIDCPNIFTY (never on an index's own expiry day): S1-S4 as in
  the setup table above. All signals come from the index.
- Score: logistic regression (`results/models/A2.json`). Steps:
  - fill a missing input with its training median;
  - standardise with the stored mean and scale;
  - score = sigmoid(intercept + sum of coefficient x input).
- The 29 inputs are the feature list above, signed by side.
- Biggest weights:
  - for taking a candidate: VIX (+0.22), the last 15 minutes in the trade's direction (+0.14), MIDCPNIFTY (+0.09),
    S1 (+0.09), VIX rising (+0.08);
  - against: ATM IV (-0.21; dear options are skipped), NIFTY (-0.10), being far from its own side's edge of the day's
    range (-0.09), being stretched from the session's mean price (-0.09), S4 (-0.08).
- Take: candidates in time order (at the same minute, the highest score first). The first with a score of at least
  **0.4871** is bought, 1 lot. That is the 70th percentile of the training scores, refitted each month. Nothing more
  that day.
- Exits: the setup's own (table above). Every trade has a stop and a profit lock.

## Running it on the phone, and retraining

- `results/models/<variant>.json` holds the trained model. It is small: logistic 3 KB, trees 80 KB. It also holds the
  input names and the take rule.
- `r57eval.py` is a pure-Python reference scorer, with no numpy, no file and no network. The phone would load the
  JSON once at start-up; nothing touches disk or the network on the tick or order path.
- `results/models/<variant>_golden.json` holds 25 input rows and their scores, for a Kotlin parity test. The parity
  gap between the scorer and scikit-learn is at most 1.1e-16 for A1, A2, A3, A4 and B2. Tree models compare float32
  inputs as scikit-learn does.
- A port needs:
  - the four setups' signals on device (S1 exists; S2, S3 and S4 are new);
  - the 12 brain features from the index minutes;
  - VIX, and the option chain's OI and IV at the signal minute (context only).
- `r57retrain.py --variant A2 --deployed results/models/A2.json --out DIR`: a monthly, offline job.
  - It relabels the lake to its last session and runs the purged walk-forward over the latest 6 months.
  - It replaces the model only if net > 0, twin p < 0.05, 40+ trades, net without the top 5 > 0, and the JSON parity
    check all pass. Otherwise it keeps the deployed model (exit code 1).
  - Run on 15 Oct (May - Oct 2026): 109 trades, +Rs 74,455, p 0.002, so it would replace. That run overlaps the final
    test, so it shows the job works; it is not new evidence.

## Honesty notes and amendments

- Implementation choice, fixed in the code before any result: A3 uses scikit-learn's `GradientBoostingRegressor`
  (Huber; depth 3, 150 trees, learning rate 0.05, leaf 40, median-imputed inputs). HistGradientBoosting has no Huber
  loss. Everything else is as pre-registered.
- A dry run of the final pipeline on the last three design months, before the read, guarded against a crash burning
  the one read.
- After the read, the take rules were moved into small functions (`select_a`, `b_decision`) so they can be
  unit-tested. Re-running the design step gave byte-identical results.
- Not pristine: R47-R55 already read parts of Apr - Oct 2026, and S3 (H37-10) was chosen on this window by R52.
- Twins take the strike from the rolling ATM of the twin's minute (S1-type twins: 4 steps in the money from it), not
  from SoloMidday's own half-up rounding of the 12:00 price. The two differ by at most one step.
- The brain features were computed with pandas 3.0.5. The brain's own tests pass under the pinned pandas 2.2.3; under
  pandas 3 five of them fail, which is unrelated to R57.
- Tests: `python -m pytest research/hunt/r57/test_r57.py -q` (29 tests, synthetic data). They cover:
  - the look-ahead guard for every feature;
  - held-contract labels;
  - index exits and SoloMidday's signal;
  - purge / embargo;
  - take rules;
  - the period guard;
  - costs;
  - evaluator parity, including the committed golden vectors;
  - holidays, expiry day, missing minutes and no data.
- Re-run: `python r57data.py build`, then `python r57study.py design`. `final` refuses a second read.

Signal source of every rule: S1-S4 and family B read the underlying index. Option-chain data is context only, except
C4 (option chain, non-default, only in A4).
