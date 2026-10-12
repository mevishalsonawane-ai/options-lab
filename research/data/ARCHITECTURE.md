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
| **News** | the RSS feeds the app already reads | live |
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

---

## Roadmap (honest sizes)

| Phase | What | When | Value |
|---|---|---|---|
| **P1** | L1–L3: data lake, validation, market cube (the session running now) | this week | every later step depends on it |
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
