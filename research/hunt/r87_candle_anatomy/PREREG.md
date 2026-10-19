# R87 pre-registration: candle anatomy (every candle shape, its context, its next 1-50 candles) and at most 3 rules

Written 19 Oct 2026, committed BEFORE any candle statistic, outcome average or P&L was computed. Done before this
commit: the data download (`fetch.py`), the loader / candle-table build on NIFTY index minutes (row counts and family
counts only), and the unit tests on synthetic data. Research only: nothing in `android/**` or `.github/**` changes.

Boss, 19 Oct: *"understand all the candle patterns, like what happens when a candle has a big tail, or a big head, or
small both, or both big tail and head; their behaviour, their position, what big players are doing at that time, when
these candles work; what the 50 candles before and after do. Not only these candles but all candle shapes, their
behaviour and output, the reason for the candle and the conclusion. Then we will see whether this helps us create a
strategy or helps the agents/Jarvis train. All the minor knowledge is essential: OI, volume, vertical volume, POC,
gamma, order flow, etc."*

## 0. Prior work (read; reused, not repeated)

- **R85 (MCX candle anatomy, branch `claude/r85-mcx-candles`)** covers MCX crude, natural gas, gold and silver candles in
  depth (Part 1 atlas, 4 option rules). R87 does NOT redo MCX: the write-up quotes R85 for MCX. R85's lesson D1 (a
  pooled baseline is fooled by drift; compare with candles of the SAME colour) is built into R87's baseline from the
  start (section 6).
- **R86 (big players, branch `claude/r86-big-players`)**: stop runs at prior-day high/low, round numbers and the opening
  range do not reverse; VWAP stretches continue; GEX adds nothing to the range forecast beyond VIX and the morning
  range. R87 measures the same footprints at the CANDLE level (section 5) as context, and does not re-test R86's fades.
- h18, R71 (chart patterns), R77 (absorption proxy), R61B (traps / order flow), R62 (volume profile / SMC), GREEN_CANDLES,
  RED_CANDLES, BIG_BARS: candle-adjacent findings; R87 is the first full shape x context x outcome atlas on the indices.

## 1. Data (nothing committed; all under ~/.cache/iraalgo/r87)

The Drive lake is not reachable from this session and its GitHub release is not published (404), so `fetch.py` calls
the same Dhan v2 endpoints the lake was built from (token only from the environment, never printed).

| series | what | span | used for |
|---|---|---|---|
| index 1-minute OHLC | NIFTY, BANKNIFTY, SENSEX spot (and INDIA VIX) | 4 Aug 2021 - 16 Oct 2026 | every candle (1m, 5m, 15m) |
| rolling options ATM-3..ATM+3, 1-minute | nearest expiry: NIFTY weekly, SENSEX weekly (exists from May 2023), BANKNIFTY weekly to 20 Nov 2024 then monthly | Aug 2021 - 16 Oct 2026 | activity proxy, OI, PCR, gamma proxy, profile, option trades |
| index / MCX futures 1-minute | contracts live on 19 Oct 2026 only (Dhan serves no expired futures minutes) | about Jul - Oct 2026 | descriptive only, inside the holdout |

- **Volume.** The index has no volume. The activity proxy `act` = the traded volume of the 14 option series ATM-3..+3
  in that minute. It drives "volume vs its usual level", the activity-weighted VWAP, the volume profile and the
  order-flow proxies. Every use is labelled "option activity, not index volume".
- **Missing, stated, not invented:** true index/futures volume history, tick data / true aggressor delta (the phone's
  recordings are in the private IraAlgo repo, not reachable), market depth, SENSEX options before May 2023, MCX (R85),
  expired futures minutes. Order flow is therefore only PROXIES (section 5).

## 2. Split

- **Atlas design: 4 Aug 2021 - 14 Oct 2025.** Walk-forward stability folds inside design: Aug 2021 - Dec 2022, 2023,
  2024, Jan - 14 Oct 2025 (a finding must keep its sign in at least 3 of the 4).
- **Locked holdout: 15 Oct 2025 - 16 Oct 2026 (the last 12 months), read ONCE** by `final.py`, guarded by
  `results/FINAL_READ` (a second read raises). It is read for the atlas confirmation whatever the rules do.
- **Rules design: 20 Nov 2024 - 14 Oct 2025** (Boss, 15 Oct: nothing before NSE's weekly-expiry change for option
  trades). Walk-forward folds: Feb-Apr 2025, May-Jul 2025, Aug - 14 Oct 2025; each fold's variant is picked on rule-design
  days before the fold. Rules holdout = the same locked holdout.
- Disclosure: the holdout overlaps windows other studies read (R51-R84 finals from 1 Apr 2026). None ran these rules.

## 3. Taxonomy (per candle, known at its close; `candles.py`)

- Candles of 1, 5 and 15 minutes anchored at 09:15, never spanning sessions, at least half their minutes present.
- Anatomy: range, body %, upper-wick %, lower-wick %, close location in range, colour (green / red / flat), range vs
  ATR14 of the same timeframe through the PREVIOUS candle (`size`), activity vs the same slot's median over the previous
  20 sessions (`act_ratio`).
- Named families (thresholds fixed in `candles.py`): doji (body <= 10% of range), dragonfly / gravestone / long-legged
  doji, spinning top, marubozu green / red (body >= 85%, size >= 0.8), big body green / red (body >= 1.2 ATR), hammer /
  hanging man (lower wick >= 60%, body 10-35%, upper <= 15%; hammer after a down trend, hanging man after an up trend,
  "flat" otherwise), inverted hammer / shooting star (mirror), **big upper wick ("big head", upper >= 50%, size >= 1),
  big lower wick ("big tail", lower >= 50%, size >= 1), both big wicks (both >= 30%, size >= 1), small both wicks (both
  <= 10%, size >= 0.5)**, tiny range (size < 0.5), huge range (size >= 2), green, red; two/three candles (same session):
  inside, outside, bull / bear engulfing, bull / bear harami, tweezer top / bottom, three white soldiers / three black
  crows, morning / evening star.
- Data-driven grid: body bin (<=10, 10-35, 35-70, >70%) x upper-wick bin (<15, 15-40, >40%) x lower-wick bin (same) x
  colour x size bin (<0.8, 0.8-1.5, >1.5 ATR). Cells with n >= 200 are tested.

## 4. Context (position; `context.py`)

Trend state over the prior 50 candles (close vs EMA50 and EMA50's 10-candle slope), position in the 50-candle range,
the 50-candle move before, volatility regime (ATR vs its own 100-candle median: > 1.3 high, < 0.8 low), time of day
(open 09:15-10:00, morning to 11:30, midday to 13:30, afternoon to 14:45, close), distance to the activity-weighted VWAP
in ATR, new day high / low (vs the day so far before the candle), prior-day high / low swept (wick through, close back)
or broken (close through after a close inside), opening range (09:15-09:29) above / below, gap day (|gap| >= 0.3%),
prior-day volume profile (activity-weighted, `research/brain/profile.py`'s method): VAH / VAL rejected, high / low
activity, OI shift over the candle (ATM-3..+3: put OI up and call OI down = "bull shift", the reverse = "bear shift";
the strike set rolls with ATM, so this is noisy, labelled), the dealer-gamma proxy sign (`data.gex_sign_proxy`: ATM-3..+3
only, customers assumed long both sides; labelled an approximation), expiry day. The 30 conditions are listed in
`context.CONDITIONS`. Every column passes the look-ahead guard (`tests/test_lookahead.py`).

## 5. "What the big players are doing" (footprints as candle families; `context.footprints`)

Absorption (activity >= 2x usual with range <= 0.7 ATR: effort without result), climax (activity >= 2.5x and range >= 1.5
ATR), stop-run up / down (a wick through the day's high / low so far that closes back inside), heavy lower / upper wick
(>= 40% wick holding >= 50% of the candle's activity), delta divergence (a red candle whose minute-rule delta share is >
+0.2, or a green one < -0.2). Delta here is a minute-bar rule on option activity, NOT true aggressor delta (no ticks).

## 6. Outcomes and the matched random baseline (`outcome.py`, `stats.py`)

- Outcomes in units of the candle's ATR, from its close: the move after 1, 3, 5, 10, 20 and 50 candles (same session;
  and crossing sessions for the 50-before / 50-after path), MFE / MAE over the next 10, "+1 ATR before -1 ATR" within 20,
  and whether the candle's LOW (lower wick level) / HIGH is broken within the next 10 (defended or broken).
- **Baseline:** for every candle, the mean outcome of ALL candles of the same instrument, timeframe, time-of-day bucket,
  trend state, volatility regime and COLOUR in the same period (design or holdout). Effect = event outcome - its
  baseline; positive = more up than matched candles. Same colour removes market drift (R85 D1).
- Standard errors clustered by instrument-day (forward windows overlap); z, two-sided p.
- **Primary outcome: f_ret5** (the move over the next 5 candles of the same session).

## 7. Tests and multiple testing

- P1: instrument (3) x timeframe (1m, 5m, 15m) x family (35 shapes/patterns + 8 footprints); tested when n >= 50.
- P2: instrument x timeframe (5m, 15m) x family x condition (30); tested when n >= 50.
- P3: instrument x timeframe (5m, 15m) x grid cell; tested when n >= 200.
- Benjamini-Hochberg q < 0.10 within each of P1, P2, P3 on the primary outcome. Secondary outcomes: their own BH over
  P1, descriptive.
- A design finding = BH q < 0.10 AND the same sign in >= 3 of the 4 design folds.
- **Holdout (read once):** every design finding is re-measured on the holdout with a holdout-only baseline. It "holds out
  of sample" when the effect has the same sign and BH (over the design findings, one-sided p in the design direction)
  q < 0.10. Findings that hold and those that do not are reported in separate tables.
- Plain-words output per family: when it works, when it fails (the contexts where its effect flips or vanishes), the
  likely mechanism (stated as a hypothesis, labelled), n, effect, q, and whether it held out of sample.

## 8. Part 2: at most 3 candidate rules (selected MECHANICALLY, frozen in amendment A1 before any P&L)

1. **Selection (`rules.select`).** Eligible: a P1 or P2 cell on 5m or 15m with design q < 0.10, the same sign in >= 3 of
   4 folds, the same sign in the rules-design window (>= 30 events), and the mean move in the effect's direction over 5
   candles in the rules window larger than an option buyer's round trip in index points (median 1-ITM call premium at
   10:30 over the rules window x 2 x 5 bps + 2 ticks + dated charges per unit, divided by delta 0.6). Ranked by |z|,
   one rule per candle family. Empty slots are filled from cells failing ONLY the cost screen (labelled). If nothing is
   eligible, no rule is tested and that is the result.
2. **Trade.** Signal = the index candle (+ its condition) at its close; direction = the effect's sign. BUY 1 lot (in
   force) of the 1-ITM call (up) or put (down) of the nearest expiry, strike from the ATM at the signal minute; entry at
   the next minute's open (first print within 3 minutes, else no trade); entries 09:30-14:30; one open trade per rule.
3. **Exits (every trade has a stop and a profit lock):** premium stop -15% or -25% (the only variant; walk-forward
   picks), profit lock (at +20% the floor goes to +10%, then trails 10% of the fill under the peak), underlying stop (an
   index minute close beyond the signal candle's far end), time exit after 5 candles, square-off 15:10 (15:05 on expiry
   day). Fills and charges as `sim.py` (5 bps + liquidity ticks, stops 10 bps + 2x ticks; dated STT/exchange/SEBI/
   stamp/GST, Rs 20 a side).
4. **Random-entry twins:** 500 (seed 87): every trade moved to a random rules-window day with option data, same entry
   minute, side and exits, underlying stop = that day's candle's far end. p = (1 + #twins >= net) / 501.
5. **Design gates (all):** walk-forward net > 0; twin p < 0.10 and BH q < 0.10 over the tested rules; design net > 0
   without its 5 best trades; >= 30 design trades.
6. **Holdout (once, only rules that passed 5):** net > 0, twin p < 0.10, net without the 5 best > 0, >= 30 trades.
7. A pass is a paper-only candidate for Boss (APP_SPEC, not built; shadow/paper rules in CLAUDE.md apply). A fail stays
   a note. Every rule gets an experiments entry, failures included.

## 9. Deliverables

`research/HUNT_R87_CANDLE_ANATOMY.md` (signal source of every rule stated), a FINDINGS line, experiments entries, the
candle knowledge pack (`research/hunt/r87_candle_anatomy/knowledge/candle_pack.json` + `.md`), RESULT.md at the root.
