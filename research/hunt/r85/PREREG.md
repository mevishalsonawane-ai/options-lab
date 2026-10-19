# R85 pre-registration: MCX candle anatomy, then option-buying rules (written 19 Oct 2026, before any statistic)

Boss, 19 Oct: "Create a new session for mcx study, study each and candle, find the patterns, find why when and how the
candle moves, each green candles and red candle has its significance, find everything and create a good strategy".
Boss, 19 Oct (later, same session): **"And options buying is only what i want."**
Research only: nothing in `android/**` or `.github/**` changes. Signal on the underlying MCX future, trade = BUY a call
or a put (CLAUDE.md "signal on the underlying, trade the option"). No futures trades and no option selling are tested
as strategies; the futures move appears only as a diagnostic column (how much of the signal the option kept).

Committed BEFORE any candle statistic or P&L was computed. Done before this commit: data download, the loader build
(row counts, date ranges, option-expiry detection) and one check that the lake's `spot` column is the MCX future
under the nearest option (it equals that contract's 1-minute close within 2 bp on 75-100% of shared minutes in the
months the contract is the option's underlying, and never in other months).

## 0. Prior knowledge (read; not repeated)

- M3 (MCX_INTRADAY): 90 rule x commodity cells, none pass; gross about zero, costs decide. M4 (MCX_OPTIONS): the average
  MCX option loses for a buyer (realised move 0.73-0.93 of priced); the buyer bleeds least in the morning (09:15-14:00),
  11-20 days to expiry; most on expiry day, 1-3 days before it, and in the evening.
- R53, R82, R83: 0 of 44, 0 of 16, 0 of 13 pass. NG evening breakout loses after costs. US-hour gold/silver agreement
  (U2) passed design and lost the final. Liquidity on MCX loses. Crude options were worse than futures in every R83
  replay.
- Gold candle work (GOLD_CANDLES, GOLD_5M/15M/30M_1H_PATTERNS) is on XAUUSD, not MCX; nothing passed there either.
- So R85 asks a narrower question: does any MCX candle shape or context predict the next 1-10 candles by MORE than an
  option buyer's cost? Part 1 measures; Part 2 tests at most 6 option-buying rules built from Part 1 design findings.

## 1. Data (nothing committed; all in /dev/shm)

| series | what | span | used for |
|---|---|---|---|
| near future `F` = per-minute median of `spot` in the lake's `opt_<SYM>_MONTH_*.parquet` (R53 `Market`) | GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, NATURALGAS; the future under the nearest monthly option; CLOSE-only minutes | 5 Aug 2025 - 13 Oct 2026 (to the newest lake day at the final read) | Part 1 (5m, 15m, 1h, 1m direction) and Part 2 signals |
| MCX option minutes ATM-3..+3 (same files) | printed legs, R42 F1 bad-tick filter (R53 `Market`) | same | Part 2 trades; Part 1 "option activity" (volume of ATM-3..+3 per minute) |
| `fut_daily_continuous` | MCX near-month continuous daily OHLC + OI, not back-adjusted | 2010 - 13 Oct 2026 | Part 1 day candles |
| `fut_1m_live_mcx` (and the 19 Oct 11-commodity refresh when it reaches the lake) | true 1-minute OHLC, volume, OI of listed contracts | from each contract's listing; the ACTIVE contract only from about Sep 2026 | Part 1 descriptive only: 1m wicks, futures volume, OI change; the extra commodities (CRUDEOILM, NATGASMINI, COPPER, ZINC, ALUMINIUM) |

- Close-only candles: a 5m / 15m / 1h candle's open is the last minute close before the candle (the first close of the
  day for the day's first candle), high / low are the max / min of that open and the candle's minute closes, close is
  its last minute close. So wicks miss intra-minute extremes; this is stated with every wick number.
- Candles are anchored at the 09:00 session open; a candle has at least half its minutes or it is dropped. Session end
  23:30 (US summer time) / 23:55 (US winter time), McxSession rule.
- Contract rolls: the near future switches the session after the option expiry. Gaps on roll days are excluded from
  gap statistics; no intraday forward window crosses a day.
- Volume and OI of the active future exist only for about Sep-Oct 2026 (Dhan serves listed contracts only), all inside
  the final period. They are reported descriptively and are NOT used to build any Part 2 rule. Option activity (from
  the option minutes) is the design-period volume measure.

## 2. Split

- **Design: 5 Aug 2025 - 31 Mar 2026** (R53's MCX design). All Part 1 tables and every choice in Part 2 use design only.
- Walk-forward folds inside design (R53 MCX_FOLDS): Nov-Dec 2025, Jan-Feb 2026, Mar 2026. Each fold's variant is picked
  on design data before the fold (from 5 Aug 2025).
- **Final: 1 Apr 2026 - newest lake day (13 Oct 2026 now; 19 Oct if the refreshed option books land first), read ONCE**,
  after the Part 2 rules are frozen in an amendment to this file. Lock file `results/FINAL_READ` (second read raises).
- Day candles: design 2010 - 2022, confirmation 2023 - 31 Mar 2026; Apr 2026 onward is final.
- Disclosure: the final window overlaps R53 / M3 / M4 / R83 final windows. None ran these rules.

## 3. Part 1: candle anatomy (descriptive, design only)

Per instrument (6) x timeframe (5m, 15m, 1h; 1m direction only; day):
- **Measures:** colour (green close > open, red <, doji |body| <= 10% of range), body % of range, upper / lower wick %,
  range / ATR14 (same timeframe, through the candle), close location in range, gap from the previous close (first
  candle of the day; roll days excluded), option activity vs its median for that minute of the session over the
  previous 20 days (no look-ahead), session bucket, day of week, days to option expiry, expiry week.
- **Session buckets (IST; US parts DST-exact):** MORNING 09:00-11:00; MIDDAY 11:00-14:00; LULL 14:00-17:00; PRE-US
  17:00 - US cash open (09:30 ET); US-OPEN overlap: US cash open + 150 min; LATE: after that to the close. Event days:
  US CPI / NFP / FOMC (R82 lists), EIA crude Wednesday and NG storage Thursday (R83 calendars).
- **Shapes (one candle):** marubozu (body >= 80% of range, range >= 0.5 ATR), big body (body >= 1 ATR), doji, hammer
  (lower wick >= 60% of range and >= 2 x body, upper wick <= 15% of range, range >= 0.5 ATR), shooting star (mirror),
  small (range < 0.5 ATR, not doji). **Two and three candles:** bullish / bearish engulfing (body engulfs the previous opposite body),
  inside bar, outside bar, three same-colour in a row, colour flip after 3 same.
- **Context (known at the candle close):** trend state (close vs EMA20 of the timeframe and EMA20 slope sign), distance
  from the session VWAP-less mean (no volume in design: the day's average of minute closes so far, "session mean"),
  within 0.25 ATR of the day's high / low so far, previous-day high / low broken this candle, volatility regime (ATR14
  of the timeframe vs its own 100-candle median: high > 1.3x, low < 0.8x), option activity high (>= 2x usual).
- **Outcome:** for h = 1, 3, 5, 10 candles of the same timeframe, the same day only: signed move s_h = colour x (C_{t+h}
  - C_t) / ATR_t (positive = continuation), continuation rate P(s_h > 0), and forward range (max high - min low over
  the h candles) / ATR_t.
- **Baseline ("random time"):** every candle of the same instrument, timeframe and session bucket, signed by its own
  colour. A cell's effect = its mean s_h minus the baseline mean s_h. Doji have no colour: excluded from signed tests.
- **Test:** Welch-type z on the effect with a day-clustered standard error (forward windows overlap). Cells with n < 30
  are shown but not tested. **Primary family: h = 3, every tested cell of every instrument x timeframe x (shape or
  context) x session bucket; Benjamini-Hochberg q < 0.10.** Other horizons are reported with their own BH, descriptive.
- **Plain-words output:** "a <shape> <colour> <tf> candle on <instrument> in <bucket> continues X% of the time over the
  next 3 candles (n = N), against Y% for any candle at that time", plus the mean move in rupees per lot of the mini
  future and as a share of a typical 1-ITM premium (option cost check).
- Day candles: the same tests on design 2010-2022 days, the confirmation 2023 - Mar 2026 shown beside them.

## 4. Part 2: option-buying rules (frozen in an amendment BEFORE any rule P&L)

- At most 6 rules, each built only from Part 1 design cells with BH q < 0.10 (h = 3), preferring cells that hold the
  same sign in both halves of design (Aug-Nov 2025 and Dec 2025 - Mar 2026) and whose mean move exceeds the option
  round-trip cost in underlying points. If fewer than 6 qualify, fewer rules are tested; if none qualifies, at most 3
  "best design effect" rules are tested and labelled "no Part 1 finding behind it".
- **Instrument: BUY 1 lot of the 1-ITM call (continuation up / reversal up) or put, nearest monthly option**, strike
  read at the signal minute (R53 `sim_option`), entry = the open of the first printed minute within 2 minutes after
  the signal candle closes, else no trade. No option trade on an option-expiry day. Books: GOLDM and SILVERM options
  (mini lots, 10 g / 5 kg units in the premium); CRUDEOIL and NATURALGAS options priced per mini lot (CRUDEOILM 10 bbl,
  NATGASMINI 250 mmBtu) from the big contract's premium per unit if the mini option books are not in the lake with
  Aug 2025 history (labelled); the GOLD / SILVER big-lot books are too thin (75-78% of ATM minutes unprinted) and are
  used only as signals.
- **Every rule has a stop and a profit lock:** stop = option premium -X% on its low, or the underlying back through the
  signal candle's far end (whichever first); profit lock = once the premium is +L%, the stop moves to entry + L/2, then
  trails at (peak - L/2); time exit at the finding's horizon; flat 10 minutes before the close. X and L are chosen from
  at most 2 x 2 values per rule by walk-forward (variants counted in the experiment log).
- **Costs (strict):** McxCosts option charges; half-spread on the premium, paid on entry and exit: CRUDEOIL 0.30% /
  0.60%, NATURALGAS 0.45% / 0.90%, GOLDM and SILVERM 0.30% / 0.60% (from / before 17:00 IST). Exits at a stale price are
  marked.
- **Gates (all needed to pass):** net > 0 in walk-forward (sum of the three out-of-sample folds) AND in the final;
  random-entry twin p < 0.10 in the final (500 twins: each trade moved to a random day of the same period at the same
  minute of day, same side and exits); BH q < 0.10 across the R85 Part 2 rules (on the final twin p); final net still
  > 0 with the 5 best trades removed; at least 30 final trades. Also reported: worst day, max drawdown, months worse
  than -Rs 13,000, win rate, the futures diagnostic.
- A pass gets `research/hunt/r85/APP_SPEC.md` (paper only, not built). A fail stays a note.

## 5. Logging

One experiments entry per Part 2 rule (failures included) and one "not-a-test" entry for Part 1, family `mcx-crypto`,
so Benjamini-Hochberg counts with the sibling "MCX Data Brain" session. Write-up: `research/HUNT_R85_MCX_CANDLES.md`;
a FINDINGS line; RESULT.md at the repo root.

## Amendment A1 (19 Oct 2026, after Part 1 on design data, BEFORE any Part 2 P&L): the frozen rules

Part 1 (design only, `results/part1_report.md`): 7,564 cells tested at h = 3; 59 pass BH q < 0.10 on the
pre-registered baseline; 18 also pass the colour-matched baseline (NOTES.md D1, stricter); 4 of the 18 have a mean move
larger than an option buyer's round trip (1 lot 1-ITM, evening spread, delta 0.6). No 1-minute cell comes close to
the cost (the cost is 1.5-4.5 one-minute ATRs). So Part 2 tests exactly these 4 rules, nothing else:

| rule | signal (underlying future, candle close) | direction | option bought | hold | underlying stop |
|---|---|---|---|---|---|
| R1 | SILVER 5m red candle, body >= 1 ATR AND option activity >= 2x usual, LATE bucket | reversal | SILVERM 1-ITM CALL | 15 min (3 candles) | the candle's low |
| R2 | GOLDM 5m red candle in the high-volatility regime (5m ATR > 1.3x its 100-candle median), LULL 14:00-17:00 | reversal | GOLDM 1-ITM CALL | 15 min | the candle's low |
| R3 | SILVERM 15m red outside bar, PRE-US bucket (17:00 - US open) | continuation | SILVERM 1-ITM PUT | 45 min (3 candles) | the candle's high |
| R4 | SILVERM 5m red hammer (lower wick >= 60% of range), US-OPEN bucket | reversal | SILVERM 1-ITM CALL | 15 min | the candle's low |

- Signal source of every rule: the MCX future (`spot` of the lake option rows), close-only candles. R1's signal is
  the big SILVER future, traded through the SILVERM option book (SILVER options are too thin); R2-R4 trade the option
  on their own future. Option activity (R1) is option-chain context around an underlying signal (CLAUDE.md allows it).
- One open trade per rule; a signal while a trade is open is skipped. No trade on an option expiry day.
- Variants (walk-forward picks among them; all counted): premium stop X in {10%, 5%} x profit lock L in {10%, 5%};
  primary X = 10%, L = 10% (lock: at +L the stop rises to entry + L/2, then trails at peak - L/2).
- Exits, costs, twins and gates exactly as section 4. BH across the 4 rules on the final twin p. Frozen variant for
  the final = the best design net (ties -> primary). The final is read once for all 4 rules together.
- Expected weakness, written down now: 3 of the 4 rules buy calls after red candles on gold / silver, and design
  (Aug 2025 - Mar 2026) was a strong rally. The colour-matched test removes the simple drift, but a regime change in
  the final (e.g. silver's July 2026 fall) is the obvious way these can fail.
