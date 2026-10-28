# R126: every indicator at many settings (plus Bollinger Bands and MACD), and 54 rule settings

28 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss: *"Add Bollinger Bands and MACD too, and
for the existing indicators try to modify the parameters ... try multiple variables of each and every
indicator/data/strategy, try changing them and then test again."* Every indicator uses candle **wicks**: the midpoint
(high + low) / 2, and ranges from highs and lows. No open or close is used.

## The answer: no setting of anything helps

**We tried 118 settings of 15 indicator/data families (359 inputs) and 54 settings of the trading rule. None of them
improves the forecast of which way the next 30-point move goes, and none makes money after costs.**

| what was tried | how many | result (2-year walk-forward, Apr 2025 - Mar 2026) |
|---|---|---|
| each setting added to a price-only model (direction of the next 30-point move, 15 and 30 min) | 236 tests | **0 pass.** Only 73 of 236 improve at all: fewer than half, what chance gives. The best (Bollinger 50 / 2.5 on 5-minute candles) improves log-loss by 0.00025, a quarter of the 0.001 bar, q 1.0 |
| one network with all 359 inputs (N4) vs R124's network without them | 4 tests | no better: direction AUC 0.507 vs 0.512 (15 min); holdout 0.519 vs 0.518 (25,319 moves, q 0.996) |
| rule settings: direction confidence 55/60/65%, move threshold top 5/10/20%, 15/30 min, exits 20/30/45 NIFTY points | 54 | **0 of 54 had a positive average after costs.** Only 5 reached 300 trades. The most active lost Rs 195-200 a trade (317-374 trades) |

**Nothing passes, nothing goes on paper, and nothing changes in the app.** The holdout was read once, for the network
comparison only (no variant or rule passed the walk-forward, as pre-registered).

### Each family's best setting (walk-forward)

None of them reaches the pre-registered bar of a 0.001 log-loss gain at q ≤ 0.05 in 3 of 4 folds. All families' q
values are 1.0 after correcting for the 236 tests.

| family | settings tried | best setting (log-loss change; negative = better) |
|---|---|---|
| Bollinger Bands (new) | n 10/20/50 × k 1.5/2/2.5 × 1/5-min | n50 k2.5 5-min, -0.00025 |
| MACD (new) | 12/26/9, 8/21/5, 5/35/5 × 1/5-min | 5/35/5 1-min, -0.00017 |
| RSI | 7/14/21 × 1/5/15-min | 7 on 5-min, -0.00020 |
| Supertrend | ATR 7/10/14 × 2/3/4 × 1/5-min | 7 / 3 on 1-min, -0.00020 |
| EMA, EMA crosses, VWAP, volume windows, volume profile, support/resistance, sweeps, pools, wick candles, option flow, participant OI | 4-12 settings each | all within ±0.0002; participant OI made it worse (+0.004) |

(Full table: `research/hunt/r126_param_sweep/results/wf.json`, `screen`.)

**What the all-inputs network leaned on** (holdout; how much worse the forecast gets when a group is shuffled):
- for "a move is coming": the last 2 hours of price 0.079, then **Bollinger bandwidth 0.0045**, size 0.0019, the
  20-day history 0.0018, the volume profile 0.0013;
- for direction: price 0.001, and every new family below 0.0001.

Bollinger bandwidth is a volatility measure. It adds a little to "how much will it move", but nothing to "which way".

## What six studies today say together (R123-R126, after R16, B1, R41)

1. **How much NIFTY moves in the next 15-30 minutes is predictable.** Busy markets stay busy: recent range, VIX,
   straddle price, the open. The network and Bollinger bandwidth catch this (AUC about 0.83-0.85).
2. **Which way it moves is a coin.** It stays a coin with:
   - wick support/resistance, liquidity, order flow and volume profile;
   - candle patterns;
   - VWAP, EMAs, RSI, Supertrend, Bollinger Bands and MACD;
   - at any of 118 settings, alone or all together in a network.

   All of them are summaries of the same past prices, and the past prices do not hold the direction.
3. **Size alone does not pay with bought options:** R124's straddle lost Rs 188-211 a trade. A 30-point NIFTY move is
   too small to cover two legs' costs and decay.

## How it was done

- **Pre-registration:** `research/hunt/r126_param_sweep/PREREG.md`.
  - Commit `2d591c1`: before any input was computed.
  - Amendment 1, `90a85ea`: the variant count was mis-added (118, not 134), corrected before any input.
- **Commits:**
  - walk-forward `4b4f47b`, committed before the holdout;
  - holdout read **once**: 28 Oct, 14:25-14:38 UTC, not resumed. It was the fourth read of Apr - Oct 2026 today
    (R123-R125 first), so it carried no weight beyond confirmation; nothing reached it anyway.
- **Code:** `research/hunt/r126_param_sweep/` (`r126feat.py` the 118 variants and the trades at 3 exit sizes;
  `r126run.py`; `test_r126.py`: 7 tests, including past-only and completed-candle checks for every series indicator).
- **Data:**
  - R124's rows for 20 Oct 2024 - 19 Oct 2026, every 5 minutes, all five indices: 80,300 walk-forward rows over 237
    sessions, and 46,580 holdout rows over 137 sessions.
  - Labels: the wick-to-wick 30-point move.
  - Volume and VWAP use ATM±3 option volume, because the index has no volume before 2026.
- **Screening:** a logistic regression on a fixed price core (returns 5/15/30/60, volatility, day range, distances,
  gap, VIX, time), with and without each variant, trained per quarterly fold on all earlier data. The out-of-sample
  log-loss change for the direction of moves that happened, a day-block bootstrap, and BH over all 236 tests.
- **Network N4:** R124's multi-level network plus 15 specialists (one per family), the same seeds and folds, compared
  with R125's retrained R124 network on the same rows.
- **Rules:**
  - signal on the underlying (the network's direction share and P(move));
  - buy the ATM call or put;
  - exits when the index's high or low touches ±20/30/45 NIFTY points (scaled for the other indices), or at the time
    limit;
  - 1 lot at today's lots, dated charges, liquidity-aware slippage, at most 5 trades a day;
  - 1,000 random-entry twins each, BH over 54.
- **Big files:** `gdrive:IraAlgo-data-lake/derived/r126/`.
- **Experiment entries:** `research/experiments/entries/r126-*.yaml` (15 families with their real test counts, N4, and
  the rule sweep).

## Honest notes

1. **Searching 118 + 54 settings means some would look good by luck.** The BH correction and the 0.001 minimum gain
   are there for that. Here not even the raw numbers looked good: fewer than half of the settings improved anything.
2. **The holdout is spent.** Apr - Oct 2026 has been read four times today. A future idea needs either new sessions
   (from 20 Oct 2026 on) or a forward test.
3. **Participant OI (FII / Pro / Client) made the forecast worse** (+0.004 log-loss). OF1 found the same: following
   the groups does not pay.

## Reproduce

```bash
export IRAALGO_LAKE=<lake> R124_OUT=<R124 cache> R125_OUT=<R125 cache> R126_OUT=<cache> OF1_RAW=<participant files>
cd research/hunt/r126_param_sweep
python -c "import r126feat as F; from pathlib import Path; F.build('design', Path('$R124_OUT'))"
python r126run.py screen && python r126run.py n4 && python r126run.py finish_wf
python r126run.py holdout    # refused: already read
python -m pytest test_r126.py -q
```
