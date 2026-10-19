# R86: the big players' footprints in our data (Parts B and C)

*19 Oct 2026. Research and knowledge only; nothing in the app changes. Part A, the cited guide to who the big players
are and how they work, is `research/BIG_PLAYERS_GUIDE.md`. Pre-registration: `research/hunt/r86/PREREG.md` (commit
65267ec, pushed before any outcome). Code: `research/hunt/r86/` (`r86lib.py` pure functions, `r86data.py` loaders,
`r86study.py` runner, `r86entries.py`, tests `test_r86.py`). Results: `research/hunt/r86/results/design.json`.*

Boss, 19 Oct: *"Find a way, how the big traders who have the ability to move the market up and down, how do they think
or work, means we will understand how actually the market works, it will be very helpful for our bot."*

## The answer for Boss

**We can see the big players' footprints, but none of them tells our bot which way to trade. 0 of 23 pre-registered
tests passed.** Because nothing passed the design gates, the locked final window was never read (as pre-registered),
and there is no APP_SPEC.

- **FII positioning (NSE participant OI, 2012-2022):** FIIs being extremely long or short, shifting sharply over 5
  days, or holding extreme option positions did not call NIFTY's next 5 days. The three trading rules lost Rs 0.65 to
  5.31 lakh on 120-184 trades, none significantly better than random days (twin p 0.37-0.99). Client option writing and FII "crowding" add nothing to a
  VIX-based range forecast either.
- **CFTC managed money (COMEX/NYMEX, weekly):** the nearest miss. Leaning against extreme managed-money positions made
  +Rs 2.14 lakh on 47 SILVERM trades (twin p 0.006), but all of it came from the 5 best trades (without them
  -Rs 27,528) and BH q was 0.115. Gold and crude: smaller, also carried by a few trades.
- **Stop runs (a poke beyond yesterday's high/low, a round number or the opening range that closes back within 10
  minutes):** after the poke fails, the index does NOT reverse more than at a random minute (average move in the fade
  direction -2.9 to +1.4 basis points in 60 minutes). Fading it lost on every level and both indices (Rs -2.3 to -5.5 lakh on
  295-804 trades, 2022-24). This agrees with h18 and with Osler's research on stop cascades.
- **VWAP algos:** on high-volume days the NIFTY does NOT snap back to VWAP more. Midday stretches from VWAP tend to
  CONTINUE on every kind of day, least on busy days. A fade to VWAP lost Rs 64,786 on 71 trades.
- **Dealer gamma (GEX):** once VIX and the range so far are known, GEX does not improve the rest-of-day range forecast
  (NIFTY -0.95%, BANKNIFTY -2.8% worse error, 492 days each). R28's split is most likely already carried by those inputs.
  So GEX cannot size stops better than VIX + the morning range.
- **Rolls and expiry:** NIFTY rollover % did not call next month (32 trades, carried by its top 5). The gold roll
  window (5 sessions before a GOLDM contract roll) is about 14% busier than other days (620 sessions, p 0.010, 8 of 11
  years), a near miss on BH (q 0.115): a size fact, "not proven".
- **The phone's own tick recordings:** not reachable from this session (private IraAlgo repo); idea 7 was not run.

**For the bot: nothing new to switch on. Keep signals on the underlying, keep the Liquidity arm trading breaks (not
fades), keep sizing stops from VIX and the recent range, and treat FII/COT/GEX as context Jarvis can explain, not as
signals.**

## What was tested (pinned before any result)

23 tests in one Benjamini-Hochberg family. Each idea tested a NEW angle; the failed old ones (OF1's one-day
participant changes, OI walls, PCR, max pain, GEX as direction, 75 order-flow rules) were not repeated.

Signal source of every rule: the underlying (NIFTY / BANKNIFTY index, the MCX future) or regulator/exchange positioning
data about it. No rule reads an option's own price chart. Trades were priced on the underlying's future with real costs
(an option version is built only for a pass, and would only be harder).

| family | data (all point in time) | design / walk-forward folds | tests |
|---|---|---|---|
| P: NSE participant OI | NSE participant files 2012 - 13 Oct 2026 (evening of t, used from t+1) | 2012-2022 / years 2015-2022 | P1-P5 |
| C: CFTC COT | CFTC Disaggregated Futures-Only, 2006 - 13 Oct 2026; used from report + 7 days; shutdown weeks dropped | 2012-2022 / 2015-2022 | C1, C2 x gold, silver, crude |
| S: stop runs | NIFTY 1-min 2017-, BANKNIFTY Aug 2021- | to 2024 / 2022, 2023, 2024 | S1-S3 x 2 indices |
| V: VWAP algos | NIFTY 1-min + rupee turnover of 59 current constituents | 2019-2024 / 2022-2024 | V1 |
| G: dealer gamma | R28's minute GEX, Aug 2021 - Oct 2026 | to 2023 / 2022, 2023 | G1 x 2 indices |
| E: rolls | NSE F&O bhavcopy (NIFTY futures OI); MCX continuous futures | 2012-2022 / 2015-2022 | E1, E2 x gold, silver |

Costs: NSE futures with Rs 40 brokerage, dated STT, exchange, SEBI, stamp, GST, 0.02% slippage a side (0.04% on stops)
and 5.5% a year carry; MCX with McxCosts charges, 0.03% slippage (0.10% on stops). 1 lot in force. Every trading rule
has a stop. Random twins: 500 each (seed 86).

Pass rule (as R84/R85): walk-forward net > 0 and > the twins' mean, twin p < 0.10, BH q < 0.10, and still > 0 without
its 5 best trades; then the same in the final window, read once.

## Results (design walk-forward)

### P: NSE participant-wise OI (FII / Client), NIFTY

| test | what | n | net (Rs) | without top 5 | random twins | twin p | BH q |
|---|---|---|---|---|---|---|---|
| P1 | FII long share at a 250-day extreme, 5-day trade | 184 | -5,30,986 | -7,18,159 | -75,376 | 0.986 | 0.998 |
| P2 | sharp 5-day shift in FII long share | 120 | -1,88,828 | -4,85,729 | -56,003 | 0.762 | 0.964 |
| P3 | FII index-option stance extreme | 154 | -64,947 | -2,71,187 | -1,40,886 | 0.369 | 0.667 |

| test | range input | forecasts | error change vs VIX baseline | DM t | twin p | BH q |
|---|---|---|---|---|---|---|
| P4 | Client net option writing | 1,972 | 0.19% worse | -0.68 | 0.836 | 0.964 |
| P5 | FII crowding (long share far from its median) | 1,972 | 0.48% worse | -0.89 | 0.998 | 0.998 |

- The walk-forward sign for P1 was "fade the FII extreme" in every year (FIIs extremely long -> sell). It lost in 5 of
  8 years; 2020 alone cost Rs 2.96 lakh (32 trades). P2's sign flipped in 2016. A signal whose best direction changes
  from year to year is not a footprint we can trade.
- This matches OF1 (one-day changes: 0 of 15 held out of sample). FIIs follow trends on the same day (OF1: +0.44), but
  their positioning does not lead the index.

### C: CFTC managed money -> MCX

| test | what | n | net (Rs) | without top 5 | random twins | twin p | BH q |
|---|---|---|---|---|---|---|---|
| C1-GOLD | COT index extreme, 20 sessions (GOLDM) | 38 | +66,343 | -71,893 | -33,882 | 0.056 | 0.321 |
| C1-SILVER | same (SILVERM) | 47 | +2,13,962 | -27,528 | -77,873 | 0.006 | 0.115 |
| C1-CRUDE | same (CRUDEOILM) | 22 | +28,814 | -18,328 | +12,723 | 0.198 | 0.667 |
| C2-GOLD | weekly change z, 5 sessions | 52 | -36,452 | -98,627 | -59,911 | 0.345 | 0.667 |
| C2-SILVER | same | 53 | -62,503 | -2,17,574 | -7,467 | 0.733 | 0.964 |
| C2-CRUDE | same | 59 | +26,363 | -925 | -3,380 | 0.038 | 0.291 |

- C1's fitted sign for gold and silver was contrarian in every year: when managed money (hedge funds, CTAs) is at a
  3-year extreme, MCX gold and silver tended to move against them over the next 4 weeks. That fits the CFTC literature
  on crowded speculative positions, but here it rests on a handful of big trades (SILVERM 2017 +Rs 1.03 lakh on 5
  trades; 2020 -Rs 1.15 lakh on 4). Only 1-11 trades a year: too few to prove. **Not proven.**
- C2 (weekly changes) carries nothing.

### S: stop runs at obvious levels (failed pokes), fade trade

A poke = a minute (09:30-14:30) trading at least 0.03% beyond the level after closing on the other side; it fails when
a minute closes back within 10 minutes. Fade: next minute, stop beyond the poke's extreme, target 2R, out after 60 min.

| test | level | trades | net (Rs) | win | random-minute twins | twin p | BH q | fade move after 60 min |
|---|---|---|---|---|---|---|---|---|
| S1-NIFTY | previous-day high/low | 295 | -2,25,994 | 33% | -1,99,669 | 0.826 | 0.964 | -1.0 bps (293) |
| S1-BANKNIFTY | previous-day high/low | 407 | -2,72,566 | 35% | -2,86,522 | 0.345 | 0.667 | -2.9 bps (402) |
| S2-NIFTY | round 100s | 721 | -5,28,361 | 31% | -5,15,252 | 0.621 | 0.952 | -1.3 bps (716) |
| S2-BANKNIFTY | round 500s | 528 | -3,52,217 | 34% | -4,17,253 | 0.082 | 0.376 | +1.4 bps (529) |
| S3-NIFTY | opening-range edge | 674 | -4,73,958 | 33% | -4,69,549 | 0.559 | 0.918 | +1.1 bps (682) |
| S3-BANKNIFTY | opening-range edge | 804 | -5,51,415 | 33% | -5,63,283 | 0.377 | 0.667 | -0.5 bps (813) |

- What happens after a failed poke: nothing special. The average move in the fade direction over the next 30-60
  minutes is between -2.9 and +1.4 basis points, against an average absolute move of 16-30 basis points. The "trap"
  is no better than a random minute of the same day.
- The fade's stop is tight (median 0.10-0.12% of price), so the trade costs (about 0.06-0.10% a round trip incl.
  slippage) eat it; 59-61% of trades hit the stop.
- **What is new versus earlier work:** h18 T4 had found, descriptively, that pokes beyond prior highs/lows drift on and
  do not reverse; R80 found round numbers and beyond-yesterday's-range do not improve the Liquidity arm's BREAK
  entries; R78 found the previous-day-high/low reversal fails on gold. R86 adds the costed fade on three level types and
  two indices against random-minute twins: the failed poke is not a tradeable reversal on NSE either. The Liquidity arm's
  choice to trade breaks, not fades, stands.

### V: VWAP-algo footprint

| test | what | trades | net (Rs) | without top 5 | random stretch days | twin p | BH q |
|---|---|---|---|---|---|---|---|
| V1 | 12:00 fade to VWAP when stretched >= 0.25%, high-early-volume days | 71 | -64,786 | -91,702 | -76,702 | 0.323 | 0.667 |

- Same fade on all stretch days: -Rs 2,03,846 on 190 trades.
- Does price mean-revert to VWAP more on busy days? No. The slope of the 12:00 -> 15:15 return on the 12:00 stretch is
  POSITIVE in every early-volume third (low 0.70, middle 0.33, high 0.25; 488-489 days each, 2019-2024): a stretch from
  VWAP at noon tends to continue, a little less on busy days. VWAP is a benchmark big funds execute against, not a
  magnet.
- Is a busy day predictable early? Yes, partly: the 09:15-10:14 turnover ratio correlates 0.67 with the full day's
  (1,465 days). This is size information, not direction.
- Caveats: the turnover proxy uses today's 59 constituents (survivorship); index volume itself is not published.

### G: dealer gamma and the day's range

| test | forecasts | error change from adding GEX | DM t | twin p | BH q |
|---|---|---|---|---|---|
| G1-NIFTY | 492 | 0.95% worse | -0.99 | 0.154 | 0.964 |
| G1-BANKNIFTY | 492 | 2.8% worse | -1.57 | 0.657 | 0.998 |

- Baseline = VIX at 09:59, the 09:15-09:59 range, the last 20 days' rest-of-day range and expiry-day dummies. GEX
  sign and size add nothing on top: R28's "negative GEX -> bigger range" is already in VIX and the morning range.
- Calibration (descriptive): with or without GEX, the baseline's quietest-forecast fifth saw the rest-of-day range
  exceed the forecast on 62-68% of days, the busiest fifth on 28-37%. Ranges revert to normal more than the simple
  model says, so a stop sized from the forecast should be shrunk towards the usual range. GEX does not fix this.

### E: expiry and roll flows

| test | what | n | result | p | BH q |
|---|---|---|---|---|---|
| E1 | NIFTY futures rollover z at monthly expiry -> next month (trade) | 32 trades (131 expiries) | +Rs 1,81,885; without top 5 -Rs 2,58,791; twins +40,843 | 0.216 | 0.667 |
| E2-GOLD | GOLDM: abs daily move in the 5 sessions before a roll / other days | 620 vs 2,086 sessions | 1.141 (random windows 1.00); without top 5 1.089; 8 of 11 years above 1 | 0.010 | 0.115 |
| E2-SILVER | SILVERM, same | 275 vs 2,451 | 1.036; without top 5 0.944; 5 of 11 years | 0.343 | 0.667 |

- E1's profit is 2020 and 2022 (+Rs 2.63 lakh on 10 trades); 4 of the other 6 years lost. Not proven.
- E2-GOLD is a near miss: gold moves about 14% more in the 5 sessions before the GOLDM contract rolls (the old
  contract's last days, when holders roll or close). It fails only BH (0.115 vs 0.10). Size, not direction.

### Idea 7: large prints and absorption on the phone's tick recordings

Not run. The recordings are in the private repo `mevishalsonawane-ai/IraAlgo`, which this session could not reach
(OF1 found 0 releases there on 14 Oct). R8's only recording is one 34-minute MCX evening: an anecdote. Too little data
yet; `hunt/of1/footprints.py` is ready when weeks of NSE recordings exist.

## BH and the final window

All 23 p-values went into one BH family: the smallest q is 0.115 (C1-SILVER, E2-GOLD), so nothing passes. Per
PREREG section 2 the final windows (2023-Oct 2026 daily; 2025-Oct 2026 minutes; 2024-Oct 2026 GEX) were **not read**
(`r86study.py final` refuses: "no test passed the design gates"). No APP_SPEC is written.

## Part C: what the bot should do differently

| # | item | label | evidence |
|---|---|---|---|
| 1 | Keep every signal on the underlying; do not add FII, Client, COT or GEX as direction signals or filters | proven (as a negative: 0 of 23 here; 0 of 15 in OF1; 0 of 10 GEX filters in R28) | this study, OF1, R28 |
| 2 | Do not fade "stop runs" (failed pokes at yesterday's high/low, round numbers, opening range); keep the Liquidity arm trading breaks | proven as a negative (6 of 6 lose after costs, no better than random minutes) | S1-S3, h18, R78, R80 |
| 3 | Do not fade towards VWAP at midday, even on busy days | proven as a negative for this rule (V1 and the all-days version lose) | V1 |
| 4 | Size stops and targets from VIX + the range so far + the usual range (R16's nowcast); GEX adds nothing | proven that GEX adds nothing beyond VIX + morning range (2 of 2 worse) | G1, R16 |
| 5 | Shrink range-based stops towards the usual range on very quiet or very busy forecasts | not proven (descriptive calibration only) | G1 calibration |
| 6 | Expect gold to be a little busier in the 5 sessions before a GOLDM contract roll (e.g. allow for wider swings) | not proven (q 0.115) | E2-GOLD |
| 7 | Treat managed-money extremes on COMEX silver/gold as context ("speculators are crowded") | not proven (q 0.115; carried by its top 5) | C1 |
| 8 | Expiry-day rules stay as they are (15:05 square-off; Hero/expiry studies) | unchanged; nothing new tested here | EXPIRY_* studies |
| 9 | Early volume (09:15-10:14 vs usual) tells Jarvis whether the day will be busy (corr 0.67) | descriptive fact, not a trade signal | V family |

### Knowledge items (for the shared pack and Jarvis)

Tagged by `research/knowledge/build_pack.py`'s new `big-players` topic (plus the usual ones); they reach the pack from
FINDINGS.md (bullets), `research/experiments/entries/r86-*.yaml` and the two guides at the next pack build.

- "NSE participant data shows FIIs follow the trend on the same day, but their positioning levels and 5-day shifts did
  not predict NIFTY's next 5 days (R86: 3 rules, 120-184 trades each, all lost after costs, 2015-2022)." [big-players,
  fii, direction]
- "A poke beyond yesterday's high/low, a round number or the opening range that closes back inside within 10 minutes
  is NOT followed by a reversal: about 0 basis points of extra move in 60 minutes, and fading it lost on NIFTY and
  BANKNIFTY (R86, 295-804 trades per test, 2022-24)." [big-players, liquidity, traps, reversal]
- "On NIFTY a midday stretch from VWAP tends to continue, not revert, even on high-volume days (R86, 1,465 days
  2019-2024)." [big-players, vwap]
- "Dealer gamma (GEX) does not improve the day's range forecast once VIX and the morning range are known (R86, 492 days
  per index)." [big-players, gex, sizing]
- "CFTC managed-money extremes leaned contrarian for MCX silver and gold over 4 weeks, but too few trades carry it to
  call it proven (R86: SILVERM 47 trades, q 0.115)." [big-players, mcx, cot]
- "Gold moves about 14% more in the 5 sessions before a GOLDM contract roll (R86: 620 sessions 2012-2022, p 0.010,
  q 0.115, not proven)." [mcx, expiry]
- Part A facts (from `BIG_PLAYERS_GUIDE.md`, cited): who wins in Indian F&O (SEBI's studies), dealer hedging and
  pinning research, stop-cluster research (Osler), the CFTC COT categories and release timing, what SEBI forbids.

## Honest limits

- Daily signals are tested on index futures priced from the index plus carry; MCX on the roll-adjusted continuous
  future (the true overnight move on 5-12 roll days a year is dropped; the adjustment rescales older prices by the roll
  ratios, which changes no return).
- The participant file is aggregated across all index futures (NIFTY, BANKNIFTY, ...), not NIFTY alone.
- COT entries wait until report + 7 days (Tuesday after the release): conservative; a Monday entry was not tested.
- The S family's random twins pick a random minute of the same day with the same side and stop size; they do not keep
  the one-position-at-a-time rule.
- MCX category-wise OI (hedgers, VCPs, prop) and NSE index-rebalance days were not tested (Part A lists where they are
  published).

## Reproduce

```bash
# data into /dev/shm (never into git): the Drive lake folders participants/, index_daily, index_1m (NIFTY, BANKNIFTY,
# INDIAVIX), equity_1m (2019-2026), derived/r28_gex, fut_daily_continuous; CFTC history zips into /dev/shm/cftc:
#   https://www.cftc.gov/files/dea/history/fut_disagg_txt_hist_2006_2016.zip and fut_disagg_txt_<YEAR>.zip (2017-2026)
python -m research.hunt.r86.r86study design     # about 15 minutes
python -m research.hunt.r86.r86entries
python -m pytest research/hunt/r86/test_r86.py -q
```
