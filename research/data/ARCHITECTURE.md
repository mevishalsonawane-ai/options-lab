# IraAlgo Data Brain: architecture

*Design, 12 Oct 2026. Status: proposed. Nothing here trades: models only inform, filter or rank. Every change that
touches trading goes shadow → paper → live with Boss's PIN, and automatic changes may only lower risk.*

The goal: every piece of data we have, from 2020 history to today's fills, should flow through one pipeline that cleans
it, describes it, learns from it and explains what it learned. This must hold for Boss, for the app's agents and for
Jarvis.

It is built as **eight layers**. Data flows up; what is learned flows back down into the app, and the app's own results
flow back in as new data. The "neural network" part is layer 5: a stack of models from small specialists up to a market
encoder. It is not one giant model.

```
                         ┌──────────────────────────────────────────────────────────┐
  L8 FEEDBACK  ◄─────────┤ the app's fills, slippage, order review, paper/live P&L  │◄────────────┐
        │                └──────────────────────────────────────────────────────────┘             │
        ▼                                                                                          │
  L0 SOURCES      Dhan history · Zerodha live stream · NSE bhavcopy/OI/FII · news · app records     │
        │                                                                                          │
  L1 RAW          immutable Parquet, GitHub Releases, sha256 catalogue (never edited)              │
        │                                                                                          │
  L2 CLEAN        validated vs NSE · deduped · IST · gaps marked · quality flags                   │
        │                                                                                          │
  L3 MARKET CUBE  one minute-grid: index · futures · option chain ATM±10 · OI · IV · Greeks ·      │
        │         VIX · breadth · events (expiry, RBI, results)                                     │
        │                                                                                          │
  L4 FEATURES     point-in-time feature store (no look-ahead), versioned                           │
        │                                                                                          │
  L5 BRAIN        ┌ Level 4  Knowledge: facts, findings, "days like today" (for Jarvis)            │
        │         ├ Level 3  Committee: combines, calibrates, abstains when unsure                │
        │         ├ Level 2  Market encoder: sequence network → market-state vector               │
        │         └ Level 1  Specialists: small models, one question each                         │
        │                                                                                          │
  L6 GATES        walk-forward · locked holdout · real costs · multiple-testing · model cards      │
        │                                                                                          │
  L7 SERVING      phone: tiny models + knowledge pack; training: GitHub Actions (free) ────────────┘
```

---

## L0: Sources

| Source | What | How often |
|---|---|---|
| **Dhan API** (Boss's token, env var only) | index/futures 1-min & daily, expired options by ATM offset with OI/IV, constituents, MCX | full history once, then a nightly top-up |
| **Zerodha (the app)** | live ticks, market depth, option chain, Boss's orders and fills | live, recorded on the phone |
| **NSE public** | F&O bhavcopy (every contract's daily close/OI), participant OI, FII/DII, holidays | daily, free |
| **News** | the RSS feeds the app already reads | live | Not built |
| **The app's own records** | fills with bid/ask and depth, slippage, Order Review flaws, trade-manager votes, Jarvis lessons, paper/live P&L | every trade |

The app's own records are the most valuable source we have. Nobody else has Boss's fills, latency and slippage, and they
are the only source of truth on real fills.

## L1: Raw zone (immutable)

- Parquet files (zstd), partitioned `source / instrument / year`, each under 1.9 GB, stored as **GitHub Release assets**
  on options-lab (Boss's choice). `research/data/CATALOG.json` in git lists every file: rows, date range, sha256, source
  and download date.
- **Never edited.** A correction is a new file version, so any result can be reproduced exactly.
- Secrets never enter this layer. Tokens live only in environment variables.

## L2: Clean zone

- Every file is checked against NSE bhavcopy (daily close, OI) and index closes. Results go to
  `research/data/VALIDATION.md` with % matching and every mismatch.
- Timestamps are IST, duplicates removed, holidays aligned, and futures rolls marked (an adjusted series plus the raw
  one).
- **Gaps are marked, never filled with invented values.** Each row carries a quality flag (`ok`, `stale`, `gap`,
  `suspect`), so a model can skip bad rows instead of learning from them.

## L3: Market cube

One aligned, per-minute picture of the market. It is what every model and Jarvis read:

- index OHLCV for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and VIX, plus futures with basis;
- the option chain at ATM ±10: LTP, OI, OI change, IV, and computed Greeks (Black-Scholes, VIX/IV based);
- breadth from the constituents (advance/decline, the share above VWAP);
- an events calendar: expiry days, RBI, budget, results, US CPI, holidays;
- MCX crude, natural gas, gold and silver for the evening session.

It is stored as one table per day (about 375 minutes × instruments). Typical size is a few MB per day compressed, so the
whole of 2020–2026 fits comfortably.

## L4: Feature store

Features computed only from data available at that minute (**point-in-time**: no look-ahead, the most common way
backtests lie). Each feature has a version and a definition file.

| Family | Examples |
|---|---|
| Price | returns over 1/5/15/60 min, ranges, ATR, gap, distance from VWAP/OR/pivots |
| Structure | opening range, liquidity levels (the arm's own), swing highs/lows, inside days |
| Options | PCR, IV level & skew, OI build-up/unwinding by strike, max-pain distance, ATM straddle decay |
| Market | VIX change, breadth, futures basis, FII/DII previous day, global cues (GIFT Nifty) |
| Calendar | time of day, day of week, days to expiry, event flags |
| Execution (from L8) | expected spread, depth, slippage for this strike/time, latency |
| Labels (targets) | next-15/30/60-min move, max adverse/favourable excursion, "Liquidity signal won after costs" |

## L5: The Brain (four levels)

### Level 1: Specialists (small, focused models)

Each answers **one question** and is tested on its own. Methods are gradient-boosted trees (LightGBM) and small neural
nets. These are fast, explainable (feature importance) and trainable on a CPU.

| Specialist | Question | Used for |
|---|---|---|
| Regime | trend / range / volatile day? (from the first 30–45 min) | which strategies to arm; Jarvis's morning read |
| Move size | expected range of the next 30/60 min | position sizing context; stop distance sanity |
| Signal quality | will this Liquidity BANKNIFTY signal win after costs? | a skip/take filter, in shadow first |
| Fill quality | expected slippage/spread for this order now | the Order Review baseline; "wait 1 min" hints |
| Trap | is this break likely a stop hunt/fake-out? | the trade manager's Traps specialist, data-driven |
| Expiry | how much will this option lose to theta by 15:05? | exit-time hints on expiry days |

### Level 2: Market encoder (the "neural network like an LLM" part)

- **A sequence network** reads the last N minutes of the market cube as a sequence: the way an LLM reads words, it reads
  minutes. It is a small transformer or temporal-convolution network.
- **Self-supervised training.** It learns by hiding parts of the sequence and predicting them (masked-minute
  prediction), and by learning which days resemble each other (contrastive). It needs no labels, so it can learn from all
  six years of minutes.
- **Output: a market-state vector** (e.g. 64 numbers) describing "what the market is like right now". It powers:
  - **"days like today"**: the nearest past days by vector, with what happened next and how each strategy did on them;
  - better inputs for the Level 1 specialists;
  - anomaly alerts: "today looks unlike anything in 6 years".
- **Honest expectation:** it gives a good description of the market state, not a crystal ball. Its value is in grouping
  similar days and feeding the specialists. Any trading use goes through L6 like everything else.

### Level 3: Committee

- It combines the specialists' outputs with the strategy's own rules (the same pattern as the trade manager's chair).
- **Calibration:** "70% confident" must be right about 70% of the time, measured on the holdout.
- **Abstains** when the models disagree or the market cube shows bad data, and the strategy then trades its plain rules.
- It can only **filter or lower** risk (skip a trade, smaller size, tighter exit). It never adds trades or raises risk
  on its own.

### Level 4: Knowledge layer (for Jarvis and for Boss)

- **A knowledge base:** every research finding (FINDINGS.md, the HUNT docs), strategy statistics, model cards, and
  daily/weekly statistics tables, all with sources and dates.
- **Retrieval:** Jarvis's on-device model (Qwen) answers from retrieved facts rather than from memory. Examples: "How
  does BankNifty behave on expiry days after a gap-down?" or "How did Liquidity do on days like today?" Answers are
  number-checked, as Jarvis already is.
- A **knowledge pack** (a few MB) ships to the phone and is refreshed nightly.

## L6: Gates (what stops the brain fooling us)

Nothing reaches the app without passing these gates:

1. **Walk-forward** training (train on the past, test on the next block, roll forward), **purged** so there is no
   overlap leakage.
2. **A locked holdout**: the last 12 months, read **once** per model idea, as in all earlier research.
3. **Real costs** (charges, spread, slippage from L8) in every trading evaluation.
4. **Multiple-testing control** (Benjamini–Hochberg), with every tried idea logged, including failures.
5. **A random-twin check**: the model must beat the same number of randomly chosen trades.
6. **Model cards**: data used, period, metrics, known weaknesses, owner and date, kept in git.
7. **Drift monitoring**: when live inputs drift away from the training data, the model abstains and an Open Problem is
   raised.
8. **Rollout:** shadow (log only) → paper → live (Boss's PIN). Automatic changes may only lower risk.

## L7: Serving

| Where | What | Why |
|---|---|---|
| **Phone** | tiny exported models (ONNX/TFLite, a few MB), the knowledge pack, the "days like today" index | runs offline, fast, private; orders stay on the phone |
| **GitHub Actions** (free) | nightly: top up data, rebuild the cube and features, retrain specialists, run the gates, publish the model and knowledge pack as release assets | no server to pay for; reproducible |
| **Later (when profitable)** | a GPU box or VPS for the bigger encoder | only if the encoder earns its keep |

The app downloads new models only after the gates pass. It keeps the previous version, so it can roll back in one tap.

## L8: Feedback loop

The app's fills, slippage, Order Review flaws, trade-manager votes and paper/live results are uploaded each evening as
new raw data (L1). So the brain learns from **real execution**, not just from history. This is how it keeps improving.

Since 13 Oct (TODO G3) each evening upload also carries the day's redacted diagnostics (`diag-YYYY-MM-DD.json.gz` in
IraAlgo's release `diag-YYYY-MM`): every arm's signals taken or skipped with the reason's kind, its trades in points and
percent, the app's health and the change timeline. Not sealed (nothing personal is in it); `research/data/read_diagnostics.py`
reads it, and MAINTENANCE.md ("Daily diagnostics") has the schema.

## Data dictionary: everything traders look at (12 Oct, Boss)

The table has three kinds of row. **R** means stored raw from a source. **D** means derived, calculated in L3/L4 from
the raw data (point-in-time, versioned), so it is available for all history. **L** means live-only: it exists only if
the app records it from Zerodha's stream, so its history starts on the day recording starts. That is why live recording
began first.

| Group | Field | Kind | Notes | Built in research/brain (12 Oct) |
|---|---|---|---|---|
| **Price** | open, high, low, close per minute; day OHLC; previous close; gap | R / D | index, futures, options, stocks, MCX | **Built**: index and futures OHLC per minute, day OHLC, previous close, gap (cube, `price`). Stocks and MCX: no source yet |
| **Volume** | volume per minute; day volume; relative volume (vs same minute, 20 days) | R / D | futures and options carry volume (the index itself has none) | **Built** (`volume`): futures volume per minute and for the day so far, relative volume (same minute, 20 sessions). Precision `estimate`; NaN on release days (no futures volume) |
| **Volume profile ("vertical volume")** | volume at each price: POC, value area high/low (70%), high/low-volume nodes; day, week and developing profile | D | built from 1-min OHLCV (estimate); exact from ticks once recorded (L) | **Built**: L3 `profiles` table (developing, day, week; POC, VAH/VAL 70%, HVN/LVN) and `profile` features. `estimate` |
| **VWAP** | session VWAP, anchored VWAPs (from day open, gap, swing), ±1/2σ bands; distance from VWAP | D (L exact) | futures VWAP from 1-min data; Zerodha's "average traded price" recorded live is exact | **Built** (`volume`): session VWAP (= anchored at the open), ±1σ/±2σ bands, distance, VWAPs anchored at the previous day's high and low. Gap and swing anchors not yet |
| **Open interest** | OI per strike/minute, OI change, build-up type (long build-up, short build-up, short covering, long unwinding), futures OI | R / D | from the Dhan rolling options + bhavcopy | **Built**: OI per strike/minute, OI change (cube chain), build-up % (`options`), futures OI where the lake has it. Build-up *type* labels not yet |
| **Option chain** | ATM ±10 LTP, OI, IV, Greeks (Δ Γ Θ Vega), PCR (OI and volume), max pain, IV skew, IV rank/percentile, ATM straddle price and decay | R / D | IV from Dhan when given, otherwise Black-Scholes | **Built**: all listed, incl. PCR by volume, IV rank/percentile (252 sessions), Greeks at ATM and ATM±2 (`options`) |
| **Order flow** | bid/ask, spread, depth (5 levels), total buy vs sell quantity, order-book imbalance, trade-side estimate (at bid/ask), **delta & cumulative delta (CVD)**, large prints, absorption, pulled orders | **L** | **No vendor history from Dhan.** It exists only from the app's live recording (started 12 Oct), so each day recorded adds to it | Not built: needs the live recording (L) |
| **Support & resistance** | previous day/week/month high-low-close, pivots (classic and CPR), swing highs/lows, round numbers, opening range, the Liquidity arm's levels, volume-profile POC/VAH/VAL, max-OI call (resistance) and put (support) strikes, gap fill levels, VWAP bands | D | each level stored with how often it held/broke historically | **Built**: L3 `levels` table (all listed types except VWAP bands, which are `volume` features, and the Liquidity arm's levels, which are `structure` features) with held/broke counts from earlier sessions; `levels` features |
| **Trend & indicators** | EMAs (9/20/50/200), Supertrend, RSI, MACD, ADX, ATR, Bollinger, Donchian; higher-timeframe trend (15m/1h/day) | D | computed per minute, point-in-time | **Built** (`indicators`): all listed, per minute |
| **Candle patterns** | inside/outside bars, engulfing, big candles, wicks, consecutive colours (the app already has a pattern library) | D | | **Built** (`candles`): 5- and 15-minute inside/outside, engulfing, big bars, wick ratios, colour runs |
| **Volatility** | India VIX and its change, realised volatility (5/30/day), ATR %, expected move from VIX/straddle | R / D | | **Built**: VIX and its change, realised vol 30 m, ATR %, expected move from VIX and the straddle. Realised vol 5 m / day not yet |
| **Breadth** | advance/decline of NIFTY 50 / BANKNIFTY constituents, % above VWAP, heavyweight contribution (HDFC Bank, ICICI and others for BankNifty) | D | from the constituents' data | **Pending data**: registered in FEATURES.md, not computed (no adapter loads constituent bars) |
| **Futures** | basis (futures − spot), rollover %, cost of carry | R / D | | **Built**: basis and its change; roll day marked. Rollover % and cost of carry not yet |
| **Participants** | FII/DII cash flows, participant-wise OI (FII, Pro, Client, DII long/short) | R | NSE daily | Not built: no source yet |
| **Global & macro** | GIFT Nifty, US futures, USD/INR, crude, US 10Y, Dow/Nasdaq previous close | R | daily/pre-open; free sources | Not built: no source yet |
| **Calendar & events** | expiry days (weekly/monthly), days to expiry, holidays, RBI policy, budget, results of heavyweights, US CPI/Fed, F&O ban list | R | | **Built**: expiries, DTE, holidays (inferred), events.csv (budget, RBI). Results, US CPI, ban list: add rows / not yet |
| **News** | headline, source, time, tone, instruments it concerns | R / L | the app's news feeds |
| **Execution (Boss's own)** | signal time, order sent/ack/fill times, intended vs fill price, slippage, spread paid, charges | L (encrypted) | the most valuable data, from the app only | Not built here (L8 recording) |
| **Outcomes / labels** | next 5/15/30/60-min move, MFE/MAE, the day's regime, whether each strategy's signal won after costs | D | used only as training targets, never as inputs | **Built** (`labels.py`): 15/30/60-min move, range, MFE/MAE; kept apart from features |

The last column says what `research/brain` builds today (catalogue: `research/brain/FEATURES.md`; the L3 `levels` and
`profiles` tables: `research/brain/README.md`).

**Rules for all of it:**

- Every derived field records its formula version.
- Every field is calculated only from data available at that minute (the look-ahead guard tests this).
- Missing data is marked, never invented.
- Order flow and exact VWAP/volume profile need tick data, so they **start from live recording**. History before 12 Oct
  has only the 1-minute estimates.

## Changes the data dictionary forces (12 Oct)

The new fields don't all fit the original "one table per minute" design. Eight changes:

1. **A tick zone (new L1b).** Order flow, exact VWAP and exact volume profile need ticks and depth snapshots, not
   minutes. The phone records them for a fixed watch-list: BANKNIFTY and NIFTY near futures, ATM ±3 options of the
   current expiry, and India VIX. Each is stored as a per-second snapshot (LTP, volume, average traded price, best 5
   bid/ask with quantities, total buy/sell quantity, OI).
   - Size: about 2–5 MB per instrument per day compressed. About 20 instruments is roughly 60–100 MB a day.
   - Per-minute order-flow aggregates are computed on the phone and uploaded every evening, about 1 MB. The raw
     per-second files stay on the phone for 10 days and upload weekly, on Wi-Fi only, to monthly release assets.
   - Built 12 Oct (`TickZone` in ira-core, `TickZoneRecorder` in the app): `ticks.jsonl.gz` (per-second rows, gap runs)
     and `orderflow_1m.jsonl.gz` (tick-rule delta and CVD, book imbalance average and close, spread, large prints above
     the day's earlier p99, absorption) in each day's recordings folder. The upload was built 13 Oct (TODO G1, GitHub
     sync): each day's folder goes up after 15:45, on Wi-Fi while charging, sealed with Boss's backup passphrase, to the
     release `live-YYYY-MM` of the private data-only repository `mevishalsonawane-ai/IraAlgo` (never options-lab: Boss,
     13 Oct). That repository's nightly job checks each upload and lists it in its own `CATALOG.json`;
     `research/data/decrypt_live.py` opens a day on a PC.
2. **Recording must never slow trading.** Ticks are written by a background writer through a bounded queue. If it
   falls behind, it drops snapshots and counts the drops; it never blocks. Nothing touches the order or tick path:
   OrderPathRound2Test stays at 0. Each dropped second is marked `gap` in L2.
3. **Three new L3 tables beside the per-minute cube:**
   - `levels`: one row per support/resistance level per day, with its price, type, source, formula version, first
     valid time, and its held/broke record so far.
   - `profiles`: the volume-at-price histogram per day and per week (POC, value area, nodes).
   - `orderflow_1m`: the per-minute order-flow aggregates from live recording.
4. **"Estimate" vs "exact" kept apart.** VWAP and volume profile before 12 Oct are estimates from 1-min futures data.
   From 12 Oct they are exact, from ticks. Each value carries a `precision` flag. A model may not be trained on estimates
   and then tested on exact values (or the reverse) without that check passing. Index VWAP and profile use the near-month
   future's volume (the index has none), and switch on the roll day.
5. **Two model families.** The **history family** uses only R and D fields (2020 onward, enough data now). The **order-flow
   family** may also use L fields, which exist only from 12 Oct. It is shadow-only until it has at least 60 recorded
   trading days and a holdout of its own (about January). L6 refuses any order-flow model before that. The encoder takes
   L channels with a "missing" mask, so older days still train it.
6. **Stricter point-in-time rules.** A day's final profile and POC are known only at the close. During the day only the
   *developing* profile may be used. A level's "held N times" counts only earlier days. Global data (US close, GIFT
   Nifty) is joined "as of" IST time, never by calendar date. The look-ahead guard tests each of these.
7. **More fields means a stricter multiple-testing count.** Feature families are pre-registered before a model is
   tried, and BH counts every family tried. Adding indicators "to see what works" counts as tries.
8. **Store raw, recompute derived.** L1 keeps only raw data. Derived fields (indicators, levels, profiles) are
   recomputed from code plus a formula version, and cached by version. So adding a field never re-downloads data, and an
   old result can always be rebuilt.

## Additions before building (12 Oct)

1. **Record more live data now.** At every signal and fill the app saves the option chain (ATM ±10: LTP, OI, IV), market
   depth and the order's timing. No vendor sells Boss's execution history, so each unrecorded day is lost for good. It
   has no trading effect.
2. **A guard against seeing the future.** An automatic test fails any feature or model that reads information from after
   the minute it describes.
3. **An experiment log in git.** Every model tried is logged with its data version, settings and results, failures
   included. This feeds the multiple-testing count.
4. **Privacy.** Boss's own records (fills, P&L) are encrypted before they enter the data lake, even in the private repo.
5. **A compute budget.** Nightly GitHub Actions jobs stay small (the free minutes on private repos are limited, and each
   job is capped at 6 hours). Large trainings run occasionally.

## Crypto (13 Oct 2026, Boss: "the same model ... for crypto")

The same eight layers for Binance crypto, built by reuse in `research/brain/crypto` (README there has the commands).
Only the market rules differ; `research/brain/markets.py` describes both markets the same way.

| layer | Indian | crypto |
|---|---|---|
| L0 sources | Dhan, Zerodha, NSE | Binance bulk archive (data.binance.vision): spot + USD-M perp 1-minute klines, mark / index / premium klines, funding, 5-minute OI and long/short metrics; Deribit DVOL (repo `crypto/`) |
| L1 raw | Parquet release assets | the archive's own files, in the Drive crypto lake (sibling session) or fetched a few months at a time; never in git |
| L2 clean | IST, gaps marked, flags | UTC; the archive's ms -> microsecond switch (1 Jan 2025) fixed per value; gaps marked; the same `quality` flags; slow series placed when known, with their age |
| L3 cube | 375 minutes a session, chain ATM +-10 | 1440 minutes a UTC day, 24x7: spot, perp, mark / index, basis, funding (+ minutes to the next 00/08/16 UTC settlement), OI, long/short, DVOL |
| L4 features | 149, session-bound windows | 64, windows across midnight and weekends; day features on a UTC (or IST) analysis day; the same guard and catalogue check |
| L5 study + knowledge | R16 and the hunts | C1 (`research/CRYPTO_C1_PREDICTABILITY.md`): size of the next hour / day predictable, direction not; in FINDINGS.md and the knowledge pack |
| L6 gates | walk-forward, last 12 months locked | walk-forward, **holdout from 1 Apr 2026 locked** (`crypto/config.HOLDOUT_START`), BH, perp fees + slippage + funding |
| L7-L8 | the phone | nothing yet: crypto is research only; the app does not trade crypto |

---

## Roadmap (honest sizes)

| Phase | What | When | Value |
|---|---|---|---|
| **P1** | L1–L3: data lake, validation, market cube (+ levels/profiles tables) and the tick zone recorder | this week | every later step depends on it |
| **P2** | L5 Level 4: Jarvis knowledge base + "days like today" (statistics, no model yet) | next | immediate: Jarvis explains the market with real numbers |
| **P3** | L4 features + Level 1 specialists (regime, fill quality, Liquidity signal quality), in shadow | 2–3 weeks | the first chance of a measurable edge or saving |
| **P4** | Level 2 market encoder (self-supervised), better "days like today", anomaly alerts | after P3 | better state description; feeds the specialists |
| **P5** | Level 3 committee in shadow → paper | when a specialist passes the gates | the only route by which a model affects trades |
| **P6** | Nightly pipeline on GitHub Actions + phone download | alongside P3 | keeps everything fresh automatically |

**Expectations, plainly:**

- Most models will **fail** the gates. That is normal, and it is what protects Boss's money.
- The likely wins are **avoiding bad trades and bad fills**, a **better Jarvis**, and **faster research**. A model that
  predicts the market outright is unlikely.
- Over-fitting is the main risk with this much data. The gates in L6 are the most important part of this design.
