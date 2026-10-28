# R125: support and resistance, liquidity, order flow, volume profile, candle patterns, VWAP, moving averages, RSI and Supertrend, all inside the neural network

28 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss asked, over several messages on 28 Oct, to
add to the network:
- *"support and resistance from all the wicks"*;
- *"liquidity"*;
- *"order flow"*;
- *"all the parameters"*;
- *"volume data, vertical volume"*;
- *"candlestick patterns, wick to wick"*;
- *"VWAP and moving averages"*;
- *"RSI and Supertrend"*;
- *"keep it 2 yrs"*.

Every level, move and indicator here is measured on candle **highs and lows (wicks)**, never opens or closes.

## The answer: none of it helps

**Adding all of these to the network did not improve its forecast of which way the next 30-point move goes. It did
not make any trading rule work.**

- **Direction:** with the 74 new inputs (N3) and without them (R124's network, retrained on the same rows), the result
  is identical. Both are a coin.

  | which way the next 30-point move goes | with the 74 inputs | without them | difference |
  |---|---|---|---|
  | 15 minutes, holdout (25,652 moves) | AUC 0.523 | 0.524 | none (q 0.55) |
  | 30 minutes, holdout (33,933 moves) | AUC 0.520 | 0.523 | none (q 0.22) |
  | 15 minutes, walk-forward (36,108 moves) | 0.509 | 0.511 | none (q 1.0) |
  | 30 minutes, walk-forward (50,870 moves) | 0.507 | 0.510 | none (q 1.0) |

  (0.5 is a coin. 0.52 means the network's "more likely up" calls are right about 52% of the time when sorted.)
- **"A move is coming"** was already predictable (R124) and stays so: holdout AUC 0.832 / 0.841. The new inputs add
  nothing to it either (15 min: 0.832 vs 0.831; 30 min: 0.841 vs 0.836, q 0.55).
- **What the network uses:** we shuffled each group and measured how much worse the forecast got. On the holdout,
  shuffling the **last 2 hours of price** costs 0.105 in Brier. Shuffling any of the seven new groups costs at most
  0.0002, about 500 times less. They are nearly ignored:
  - support/resistance;
  - liquidity;
  - order flow;
  - volume profile;
  - candle patterns;
  - VWAP/EMAs;
  - RSI/Supertrend.

  For direction, the biggest new group (RSI/Supertrend) moved the log-loss by 0.0002. That is noise.
- **Trades:** the network is almost never even 60/40 sure of the direction, so the rules rarely fire. The holdout
  results after charges and slippage:

  | rule | 2-year walk-forward | holdout (Apr - Oct 2026) |
  |---|---|---|
  | M1: move likely + direction ≥ 60%, 15 min | 3 trades | 2 trades, both lost (-Rs 1,721 each) |
  | M2: the same, 30 min | 33 trades, -Rs 358 each | 17 trades, 29% won, -Rs 272 each |
  | M3: M1 at 09:20-09:30 | 2 trades | 1 trade, lost |
  | L1: at a wick level or after a sweep + direction ≥ 60% | 3 trades | 1 trade, lost |

  All four fail.

**Nothing passes. Nothing goes on paper. Nothing changes in the app.**

## Why this keeps happening (R16, B1, R41, R123, R124, R125)

- Every one of these indicators is built from the same past prices that the network already reads minute by minute.
  Wick levels, sweeps, candle shapes, VWAP, EMAs, RSI and Supertrend are all different summaries of the same history.
  So they cannot add information the price sequence did not already have, and the network finds them redundant.
- Order flow here is daily positions plus option volume and OI. The earlier studies found the same:
  - OF1: following FII / Pro positions did not pay after costs;
  - I12: support/resistance levels held no more often than random prices;
  - R91: liquidity sweeps;
  - LIQUIDITY.md: the swing / pool break rules.
- What *is* predictable, again, is **how much** the market will move: busy markets stay busy, and the morning is busy.
  **Which way** it moves, over 15-30 minutes, is a coin on six studies' evidence.

## What was built

- **Pre-registration:** `research/hunt/r125_levels_flow/PREREG.md`, commit `8e17788`, before any feature was computed.
  Each addition is a dated amendment committed before any comparison or rule was computed:
  - amendment 1 `d6d44a9`: two definition fixes;
  - amendment 2 `3d922d9`: candle patterns;
  - amendment 3 `7974b7e`: VWAP / moving averages, and 2 years;
  - amendment 4 `09fb043`: RSI / Supertrend.
- **Stopped runs:** two earlier runs were stopped when inputs were added. Their partial model files were never scored
  or looked at, and are set aside (disclosed in amendments 3-4).
- **Commits:**
  - walk-forward `8a039c0`, committed before the holdout;
  - holdout read **once**, `3c34b2a`: 28 Oct, 13:20-13:34 UTC, not resumed.
- **Code:** `research/hunt/r125_levels_flow/` (`r125feat.py` the inputs, `r125run.py`, `test_r125.py`: 18 tests,
  including past-only checks for every new input). It reuses R124's rows, labels, trades and network.
- **Results:** `results/wf.json`, `results/holdout.json`.
- **Big files:** `gdrive:IraAlgo-data-lake/derived/r125/`.

**The 74 new inputs (7 specialists in the multi-level network).** The move size m is 30 points on NIFTY, scaled by
price on the other indices.

| specialist | inputs |
|---|---|
| **sr** (8) | wick swing highs/lows (5 bars each side, used only once confirmed) from today and 5 sessions, grouped into levels; distance and strength of the nearest resistance and support; how many levels lie within one move; distance to the 5-session high and low |
| **liq** (7) | untaken equal-high/low pools above and below; sweeps (a wick through yesterday's high/low, a 5-day extreme, a pool or today's swing that fails within 3 bars); minutes since the sweep and its size; new highs minus new lows |
| **flow** (8) | yesterday's NSE participant positions (FII futures long share and its change; Pro and Client option stance); the last 5 and 15 minutes' call/put volume; option OI build-up (bought vs written) |
| **vol** (8) | option volume against its 20-day norm (5, 15 and 60 minutes); a volume-at-price profile (today + 5 sessions, each minute's option volume spread over its wick range): point of control, value-area high/low, inside the value area, high- or low-volume node |
| **candle** (24) | 1-, 5- and 15-minute wick patterns: inside, outside, higher-highs/higher-lows and their runs, two-candle wick reversal, three-candle swing, NR7, range vs the last 10 |
| **vwap_ma** (10) | session VWAP of the wick midpoints (option-volume weights): distance, slope, bands; EMA 9/20/50 (1-minute) and EMA 20 (5-minute) distances; their order; the EMA20 slope; time since the 9/20 cross |
| **osc** (9) | RSI(14) of wick midpoints (1- and 5-minute) and its change; Supertrend(10, 3) on wicks (1- and 5-minute): direction, distance, time since the flip |

**Data limits:**
- Index volume exists only from Jan 2026, so volume and VWAP use option volume.
- True tick order flow (bid/ask, absorption) exists only in the phone's recent recordings, so it cannot be backtested.

**Period (Boss: 2 years):** 20 Oct 2024 - 19 Oct 2026, all under NSE's current F&O rules.
- Design: to 15 Mar 2026, with quarterly walk-forward folds Apr 2025 - Mar 2026.
- Holdout: 1 Apr - 19 Oct 2026.
- Sample: 80,300 walk-forward rows over 237 sessions; 46,580 holdout rows over 137 sessions.

## Honest notes

1. **The holdout has now been read three times today** (R123, R124, R125). R125's question (do the new inputs add
   anything?) is a comparison on identical rows, so the earlier reads do not tilt it. But a positive result here would
   still have needed a fresh forward test. There was none.
2. **Two definition fixes** were made after looking at the new inputs alone (no outcome): levels had chained into
   blobs, and sweeps fired on 68% of rows. Even after the fix, sweeps occur on about 45% of rows on 1-minute data.
3. **With 2 years instead of 4.5,** the network has less to learn from. But the full-length R124 network, with more
   data, also found direction to be a coin. So the shorter window is not why the result is negative.
4. **Patterns that need opens and closes** (hammer, doji, body engulfing) were left out, as Boss asked. RSI and
   Supertrend use wick midpoints instead of closes.

## Reproduce

```bash
export IRAALGO_LAKE=<lake copy> R124_OUT=<R124 cache> R125_OUT=<cache> OF1_RAW=<participant files>
cd research/hunt/r125_levels_flow
python r125run.py features design
python r125run.py wf base && python r125run.py wf n3 && python r125run.py finish_wf
python r125run.py holdout        # refused: already read
python -m pytest test_r125.py -q
```
