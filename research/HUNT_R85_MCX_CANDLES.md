# R85: MCX candles, what each green and red candle means, and an option-buying strategy from them (19 Oct 2026)

Boss, 19 Oct: "study each and every candle, find the patterns, find why when and how the candle moves, each green and
red candle has its significance, find everything and create a good strategy" and, later the same day, "**options
buying is only what I want**". Research only: nothing in `android/**` or `.github/**` changed.

- Plan: `research/hunt/r85/PREREG.md`, committed (4ec89fc) before any statistic; amendment A1 (6c5e7f3) froze the 4
  rules after Part 1 and before any rule P&L. Deviation D1 (a stricter, drift-neutral test): `hunt/r85/NOTES.md`.
- Code: `research/hunt/r85/` (`candles.py` candles and measures, `anatomy.py` Part 1 statistics, `report1.py`,
  `strategy.py` option-buying harness, `part2.py` rules, `part1_final.py` out-of-sample check, `recent.py` true 1-minute
  futures, `entries.py`). Tests: `tests/` (29, including a look-ahead guard and a planted-drift test).
- Results: `research/hunt/r85/results/` (`part1_report.md` holds every table; `part2_design.json`, `part2_final.json`,
  `part2_final_trades.csv`, `part1_final_check.*`, `recent_true_candles.json`). The final was read once (`FINAL_READ`).
- **Signal source of every rule: the MCX future itself** (the near future under the nearest monthly option). The trade is
  always **buying** a 1-ITM call or put (Boss's 19 Oct instruction). Futures appear only as a diagnostic column.

## The answer for Boss

**No candle strategy pays an MCX option buyer. 0 of 4 rules pass; all 4 lost money in the final (1 Apr - 13 Oct 2026),
-Rs 17k to -Rs 30k each. Nothing goes to paper.**

1. **Most of what candles "mean" on MCX is the trend, not the candle.**
   - Gold and silver rose hard from Aug 2025 to Mar 2026. In a rising market every red candle tends to be followed by an
     up-move, so "red candles reverse" and "green candles continue" show up whatever the candle's shape.
   - 59 of 7,564 candle cells looked significant (BH q < 0.10). Only 18 survive once each candle is compared with candles
     of the same colour at the same time of day. On day candles (2010-2026) **none** survives that test.
2. **A few patterns are real but tiny.** The 59 design findings kept their direction after 1 Apr more often than chance
   (41 of 59; 15 of 18 on the drift-neutral test), but their size shrank to about a quarter (median 0.28 -> 0.05-0.08 of
   a candle's ATR).
   - The ones that held are mostly 1-minute effects: small 1-minute GOLDM candles drift on in their colour; a green
     1-minute SILVERM candle near the day's high continues; crude's green 5-minute candles in the expiry-week morning
     fade (design -0.24 ATR, final -0.22 ATR).
   - An option buyer needs about 1.5-4.5 one-minute ATRs (0.4-1.7 five-minute ATRs) just to cover a round trip. None of
     these comes close.
3. **The 4 patterns big enough to cover option costs failed out of sample.** Three of them flipped sign after 1 Apr
   (R1, R3, R4); one shrank by two-thirds (R2). Walk-forward was negative for all four even inside design.
4. **The future often moved the right way; the option did not keep it.** In design the futures move over the same
   windows was positive for all 4 rules (R2 +Rs 37.5k, R3 +Rs 39.9k), but the option's gross was a fraction of it and
   costs (Rs 340-450 a GOLDM trade before 17:00) took the rest. This is M3/M4/R83's wall again: on MCX, costs decide.
5. **When candles move (useful, descriptive):** the biggest candles are in the US-open hour (19:00 IST in US summer time,
   20:00 in winter): 15-minute GOLDM candles there are 1.7x the 14:00-17:00 lull; natural gas from 17:00 is 2-3x its
   morning. The morning and the lull are the quietest.

## What each candle means (Boss's table)

"Continues" = the next 3 candles end in the candle's colour. Design, 5m / 15m / 1h, six instruments pooled, any session.
Random time = any candle of the same colour-blind set at the same time of day: 47-48%.

| candle | what it did next (design) | after 1 Apr 2026 | tradable for an option buyer? |
|---|---|---|---|
| any green candle | continues 50% (random 48%) | same | no: that is the up-trend |
| any red candle | continues 45%: in Aug 2025 - Mar 2026 red was followed by up-moves | weaker | no: the trend, not the candle |
| big body / marubozu | +0.05 to +0.10 ATR more continuation, never significant | | no |
| hammer / shooting star | no edge (median effect about 0) | | no |
| bullish / bearish engulfing | no reliable edge; GOLD 5m late-evening bearish engulfing reversed in design (n 254, q 0.001) | flipped (+0.10 ATR) | no |
| inside / outside bar | none; SILVERM 15m red outside bar before the US open fell on in design (75% of 57) | flipped | no (rule R3 lost) |
| three of a colour in a row | tiny | | no |
| first break of the previous day's high, green | continues 56% vs 49% (median of 18 cells), not significant alone | | a lead only |
| green candle at the day's high | SILVERM 1m: continues (n 4,892, +0.39 ATR) | held (+0.19 ATR) | no: 1m move << option cost |
| red candle in a high-volatility lull (GOLDM 5m) | bounced (31% continued, n 291) | weaker | no (rule R2 lost) |
| big red candle on 2x option activity, late evening (SILVER 5m) | bounced (26% continued, n 53) | flipped | no (rule R1 lost) |
| big green 5m candle on 2x true volume after 19:00 (SILVERM, Sep-Oct 2026 only) | continued only 30% of 33 times over the next 15 min (random 47%) | recent only | no (small n, no design data) |
| green candle on a Friday (SILVERM day) | continued 55% vs 49% (2010-2022) | also +0.25 ATR (2023-26) | drift: fails the same-colour test |
| red day in a gold up-trend | bounced over 3 days (2010-2022 and 2023-26) | | drift: fails the same-colour test |

## Part 1: candle anatomy (design 5 Aug 2025 - 31 Mar 2026)

**Data.** The MCX near future = the `spot` of the Drive lake's MCX option rows (checked: it equals the option's
underlying futures contract's 1-minute close within 2 bp). Six instruments: GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL,
NATURALGAS; 304-305 sessions each to 13 Oct 2026. Candles of 5m / 15m / 1h are built from minute closes ("close-only":
wicks miss intra-minute extremes; on the minis they capture about 78% of the true 5-minute range, `recent.py`). 1-minute
candles carry direction and size only. Day candles: MCX continuous daily, 2010-2022 design, 2023 - Mar 2026 confirmation.
Volume: the active future's volume and OI exist only from about Sep 2026 (Dhan serves listed contracts only), so design
uses option activity (printed volume of ATM-3..+3) instead.

**Method.** Each candle is classified by colour, 11 shapes and 22 contexts (trend, session mean, day high/low,
previous-day levels, volatility regime, option activity, close location, events, expiry, weekday) in 7 session buckets.
Outcome: the next 1, 3, 5, 10 candles' move in ATR units, signed by the candle's colour. Baseline: candles at the same
time of day. Day-clustered errors (forward windows overlap); BH within each horizon. Primary horizon: 3 candles.

| measure | count |
|---|---|
| intraday cells tested at h = 3 (n >= 30) | 7,564 |
| BH q < 0.10 on the pre-registered baseline | 59 (all with the same sign in both design halves) |
| ... and q < 0.10 on the colour-matched (drift-neutral) baseline | 18 |
| ... and a mean move larger than an option buyer's round trip | 4 (became R1-R4) |
| day-candle cells tested (2010-2022) | 259; 7 q < 0.10; 0 survive the colour-matched test |

The 18 cells that pass both tests, with their final-period effect (`results/part1_final_check.csv`):

| instrument | tf | candle | colour | session | n design | effect design (ATR) | effect final (ATR, n) |
|---|---|---|---|---|---|---|---|
| GOLD | 5m | bearish engulfing | red | late | 254 | -0.41 | +0.10 (279) |
| GOLDM | 1m | small candle | green | late | 1,979 | +0.21 | +0.10 (2,006) |
| SILVER | 5m | big body + option activity >= 2x | red | late | 53 | -0.76 | +0.07 (119) |
| SILVERM | 1m | at the day's high | green | any | 4,892 | +0.39 | +0.19 (3,232) |
| GOLDM | 5m | high-volatility regime | red | lull | 291 | -0.38 | -0.12 (110) |
| GOLDM | 1m | small candle | red | any | 17,516 | +0.09 | +0.09 (15,126) |
| CRUDEOIL | 5m | option expiry week | green | morning | 401 | -0.24 | -0.22 (318) |
| SILVERM | 15m | outside bar | red | pre-US | 57 | +1.11 | -0.04 (39) |
| CRUDEOIL | 1m | against the EMA20 trend | red | any | 19,559 | +0.09 | +0.03 (19,874) |
| GOLDM | 1m | against the EMA20 trend | red | lull | 4,399 | +0.17 | +0.16 (3,654) |
| NATURALGAS | 1m | colour flip after 3 same | green | any | 3,875 | +0.15 | +0.04 (2,041) |
| SILVERM | 5m | hammer | red | US open | 113 | -0.53 | +0.03 (85) |
| CRUDEOIL | 1m | range >= 1.5 ATR | green | any | 17,358 | -0.08 | -0.02 (13,462) |
| GOLDM | 1m | small candle | green | any | 17,674 | +0.09 | +0.07 (14,855) |
| CRUDEOIL | 1m | small candle | green | any | 8,484 | +0.11 | +0.07 (13,769) |
| SILVERM | 5m | third red in a row | red | lull | 430 | -0.31 | -0.12 (380) |
| SILVERM | 1m | at the day's high | green | midday | 1,191 | +0.49 | +0.11 (679) |
| NATURALGAS | 1m | colour flip after 3 same | green | midday | 619 | +0.36 | +0.25 (260) |

**Option cost in candle units** (1 lot 1-ITM, evening spread, delta 0.6; before 17:00 the spread doubles):

| signal | option book | median 1-ITM premium | round trip | needed move | in ATR: 1m / 5m / 15m / 1h |
|---|---|---|---|---|---|
| GOLDM | GOLDM | 2,390 | Rs 227 | 38 points | 1.46 / 0.42 / 0.21 / 0.09 |
| SILVERM | SILVERM | 6,473 | Rs 291 | 97 points | 1.46 / 0.40 / 0.19 / 0.08 |
| CRUDEOIL | priced per CRUDEOILM lot | 243 | Rs 65 | 10.9 points | 4.49 / 1.65 / 0.83 / 0.38 |
| NATURALGAS | priced per NATGASMINI lot | 19.9 | Rs 100 | 0.66 points | 2.74 / 1.00 / 0.46 / 0.21 |

**When candles move (median 15-minute range, Rs per mini lot, design):**

| instrument | morning 09-11 | midday 11-14 | lull 14-17 | pre-US | US open (2.5 h) | late |
|---|---|---|---|---|---|---|
| GOLDM | 1,550 | 1,510 | 1,360 | 1,810 | 2,330 | 1,415 |
| SILVERM | 1,965 | 1,940 | 1,822 | 2,585 | 3,050 | 1,865 |
| CRUDEOILM | 70 | 100 | 130 | 140 | 170 | 120 |
| NATGASMINI | 175 | 250 | 250 | 500 | 450 | 375 |

On 15-minute candles green is 43-49% and red 39-46% of candles in every session (the rest are doji); no session is
reliably green or red (the most lopsided cell, GOLD 1h 09:00-11:00, is 55% green on only 258 candles).

**Recent true 1-minute futures (Sep - 13 Oct 2026, 6-30 active days; descriptive, final-period data).** OI classes
(long build-up, short covering, short build-up, long unwinding) do not separate the next 15 minutes: continuation 41-51%
against a 43-48% baseline in every commodity. Big 5-minute candles on 2x usual volume tend to give back on silver
(SILVERM: 42% continue, n 241; SILVER 45%, n 196) and crude (44%, n 99). Samples are small and there is no design
period for these, so they are notes, not findings.

## Part 2: the four option-buying rules (amendment A1)

Every rule: buy 1 lot of the 1-ITM option at the next printed minute (within 2 minutes), no trade on expiry day, stop
on the premium (-10% or -5%) or the future back through the signal candle's far end, profit lock at +10% / +5% then a
trail, hold 3 candles, flat 10 minutes before the close. Strict costs (charges + half-spread 0.30% / 0.60% from / before
17:00 on GOLDM and SILVERM). One open trade per rule.

| rule | signal (MCX future) | option | design net (trades) | walk-forward | final net (trades) | final futures move | twin p | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| R1 | SILVER 5m big red candle + option activity >= 2x, late evening | SILVERM call | +Rs 392 (17) | -Rs 879 | **-Rs 17,053 (80)** | +Rs 4,735 | 0.19 | 0.75 | fail |
| R2 | GOLDM 5m red candle in high volatility, 14:00-17:00 | GOLDM call | -Rs 23,303 (83) | -Rs 21,301 | **-Rs 29,131 (74)** | +Rs 5,220 | 0.76 | 0.87 | fail |
| R3 | SILVERM 15m red outside bar, 17:00 - US open | SILVERM put | +Rs 3,078 (21) | -Rs 134 | **-Rs 18,125 (28)** | -Rs 10,305 | 0.82 | 0.87 | fail |
| R4 | SILVERM 5m red hammer, US-open hour | SILVERM call | -Rs 27,288 (44) | -Rs 31,982 | **-Rs 30,122 (56)** | -Rs 23,055 | 0.87 | 0.87 | fail |

- Final 1 Apr - 13 Oct 2026, read once. Win rates 16-36%. Max drawdowns Rs 19k-32k; worst day -Rs 2.3k to -Rs 4.0k; no
  month worse than -Rs 13k (worst -Rs 7.6k). All four are negative without their top 5 trades. R3 had 28 final trades
  (< 30).
- Costs in the final: R1 Rs 22.4k on 80 trades (Rs 280 a trade), R2 Rs 31.4k on 74 (Rs 424), R3 Rs 8.1k on 28, R4
  Rs 15.9k on 56.
- Futures were not tested as a strategy (Boss: options only). The diagnostic shows the signal itself did not survive in
  the final either (two small positives, two negatives), so a futures version would not rescue it.

## Honest limits

- Design is 8 months (Aug 2025 - Mar 2026) of real MCX minutes, one strong metals rally. The final (6.5 months) holds
  silver's July 2026 fall. Both are short; a finding that holds in one regime may not in the next.
- Close-only candles: shapes that need the true wick (hammer, star) are approximated; they capture about 78% of the true
  5-minute range on the minis.
- The 19 Oct refresh (11 MCX futures, mini option books) was not on Drive when the final was read, so the final ends on
  13 Oct, as pre-registered. CRUDEOIL and NATURALGAS options were priced per mini lot from the big contract's premium.
- MCX Data Brain (claude/mcx-brain) did not exist on the remote during this session; nothing was duplicated.
- Not tested: copper, zinc, aluminium, CRUDEOILM and NATGASMINI as signals (no history before Sep 2026); day-candle option
  rules (option history covers only 8 months of design).

## What would be worth testing next (not done, needs a new pre-registration)

- Nothing from this study is close enough to justify paper trading. If MCX candles are revisited, the only leads are
  (a) the first break of the previous day's high on a green candle (56% vs 49%, not significant alone) on a timeframe
  long enough to beat the option cost (1h), and (b) crude's expiry-week morning fade, as a filter that SKIPS existing
  crude buys rather than a new entry (a risk-lowering use).
