# R20c: what happens after a support / resistance break confirmed by 2 candle closes

Written 13 Oct 2026 for Boss's question: "Analyse what happens if the support and resistance line is broken, like if 2
candles are above resistance and 2 candles are below support." Research only, no app code.

Pre-registration `research/hunt/r20c/PREREG.md`: part A (event study protocol, trading-test framework, gates, twins, BH
family) committed in **43dd472** before any number was computed; part B (the 4 trading rules, picked from the design
event study) committed in **cc0f7b3** before any trading test; design table frozen and experiment entries logged in
**c5ac17f** before the one holdout read. Code: `research/hunt/r20c/` (`levels.py`, `events.py`, `study.py`,
`trades.py`, `oi.py`, tests `test_r20c.py`). Tables: `results/events_design.md|json`, `events_holdout.md|json`,
`design.json`, `holdout.json`.

## Verdict for Boss (read this first)

1. **After 2 candles close beyond a level, price does NOT usually keep going.** It is a coin toss: on the design period
   (3 indices, 1,121 sessions, 7,548 two-close breaks) price was further in the break's direction at 15:29 in **51%** of
   cases, and the average move to the close was **+0.02 x ATR14** (about +7 to +9 NIFTY points, about 0 BANKNIFTY points). In
   **32% (15-minute candles) to 49% (5-minute)** of breaks a 1-minute close was back through the level within 30
   minutes. The best it went on average in the next hour (+0.19 ATR) was about the same as the worst (-0.18 ATR). On the
   holdout (334 sessions, 2,495 two-close breaks) the move to the close was slightly negative (-0.004 to -0.020 ATR).
2. **No level type is worth trusting on its own.** Previous-day high/low, R1/S1, liquidity zones, OI walls and the
   opening range all gave about the same small moves, and **random levels at the same distance did almost as well**
   (table T1). R1/S1 on 5-minute candles looked best in design (+0.03 ATR in 30 minutes), but on the holdout random
   levels did as well. Opening-range breaks (today's ORB levels) were among the weakest: +0.01 ATR to the close, half of
   them faked out within 30 minutes. That fits the ORB arm's long losing record. 15-minute candles fake out less than
   5-minute ones, but the move after them is no bigger.
3. **The 2-candle wait does not help.** The 2nd close does sort the breaks: on the same crossing candle, breaks that got a
   2nd close went on +18 points (5-min) / +22 points (15-min) from the 1st close, and the ones that failed lost 25 / 50
   points. But waiting for the 2nd close costs **about 14 points on 5-minute candles and 18 on 15-minute** (BANKNIFTY 23 /
   30, NIFTY 8 / 11), which eats almost all of that: from the 2nd close only about +4 points were left. A 3rd candle
   costs more again (23 / 32 points) and gains nothing. In money (rule `piv5n1` vs `piv5n2`): 1 close made Rs +36,785 in
   design and Rs +5,225 in the holdout, 2 closes lost Rs -21,292 in design and made Rs +25,603 in the holdout. The order
   flipped between periods, so neither is reliably better.
4. **No trading rule passes the gates.** 0 of 4. Nothing survives Benjamini-Hochberg over the 40 holdout p-values
   (R20c's 12 plus R20's and R20b's 28); the best q is 0.16. The one with a good holdout (2-close R1/S1 on 5-minute
   candles, +Rs 167 a session) lost money in design, failed 3 of 4 design blocks, and random levels at the same distance
   made almost as much (twin mean +Rs 19,734; p 0.20). **No app change is proposed.**

Plain answer: a 2-candle close above resistance or below support is not a signal to buy options on these indices. It is
as likely to come back as to go on, and the confirmation costs as much as it saves.

## Data

| source | what | span | used for |
|---|---|---|---|
| `research-data` release via `research/hunt/r17/data.py` | BANKNIFTY and NIFTY 1-minute index + nearest-expiry options (minute OHLC, volume, OI) | BANKNIFTY 13 Feb 2024 - 23 Feb 2026 (498 sessions), NIFTY 1 Apr 2024 - 13 Apr 2026 (499) | events, trades |
| release `finnifty_index.parquet` | FINNIFTY 1-minute index (no options) | 12 Feb 2024 - 24 Feb 2026 (503 sessions) | events only |
| NSE F&O bhavcopy (public, R20b's `oi.py`) | previous-day OI per strike, BANKNIFTY and NIFTY options | 15 Jan 2024 - 13 Apr 2026, 551 trading days each, none missing | OI walls |
| Yahoo `^INDIAVIX` daily | VIX previous close where the release's minutes end | 2024-2026 | VIX buckets, the low-VIX rule |

MIDCPNIFTY is not in the release (skipped). FINNIFTY has no options and no expiry calendar in the repo, so it is in the
event study only, and its expiry column says n/a. Holdout cuts: BANKNIFTY from 25 Aug 2025, NIFTY and FINNIFTY from 13 Oct
2025 (R16-R20b's). Design sessions: BANKNIFTY 361, NIFTY 364, FINNIFTY 396 (after 15 sessions for ATR14). Holdout:
122 / 120 / 92.

## Method (short; the full rules are in PREREG.md)

- **Levels**, each known before the first candle of the break starts: previous day's high / low (PDHL); floor-pivot R1 /
  S1 (PIV); the edges of active `LiquidityRules` zones on the same timeframe (LIQ; h4's verbatim port, a zone counts only
  if confirmed and not taken before the candle); the biggest put-OI strike (support) and call-OI strike (resistance) of
  the nearest expiry in the PREVIOUS day's bhavcopy (OI); the ORB opening range 09:15-10:04 (OR, first candle 10:05 /
  10:15).
- **Event**: a candle closes beyond the level after the previous close was on the other side, and the next N - 1 candles
  close beyond it too (N = 1, 2, 3; 5- and 15-minute candles). Only the first such event per level type, side, timeframe
  and day. Gap breaks (the crossing candle opened beyond the level) are included and also shown apart (T3).
- **Measured** from the close of the N-th candle: moves after 15 / 30 / 60 minutes and to 15:29 (ATR14 units, points
  where named); "fake-out" = a 1-minute close back through the level within 30 minutes; best / worst excursion (MFE /
  MAE); the wait cost against the 1st close. "Excess" = the move minus the average move from the same clock minute on all
  sessions (removes time-of-day drift). `_rnd` rows = the same levels mirrored around the 09:15 open on a random side.
- **Look-ahead guard** (test_r20c.py): for random synthetic days and cut minutes, every level a candle may cross and every
  event confirmed by the cut are identical when every minute after the cut is replaced by noise; the one-pass liquidity
  map is checked against a recompute on the truncated bars.
- t-values in the tables treat events as independent; events of different level types on the same day overlap, so they
  overstate significance. They are descriptive only (no event-study number enters the gates).

## Step 1: event study (design period)

Columns: `mv15_a` .. `mvc_a` = move after the event over 15 / 30 / 60 minutes / to the close, in ATR14; `exc_a` = to the
close minus the same-minute baseline; `fake30` = fake-out share; `mfe60_a` / `mae60_a` = best / worst excursion in 60
minutes; `win_c` = share further in the break's direction at the close; `wait` = points paid for entering at the N-th
close instead of the 1st. Flag "n<30" marks cells with fewer than 30 events. Median ATR14 in design: BANKNIFTY 655
points, NIFTY 247, FINNIFTY 290.

### T1. N=2 by level type and timeframe (design; _rnd = random-level twin)

| type | tf | events | mv15_a | mv30_a | mv60_a | mvc_a | exc_a | fake30 | mfe60_a | mae60_a | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LIQ | 5 | 1,452 | -0.004 | +0.001 | +0.004 | +0.021 | +0.021 | 54% | +0.188 | -0.189 | 50% |  |
| LIQ | 15 | 684 | +0.011 | +0.014 | +0.015 | +0.017 | +0.018 | 32% | +0.190 | -0.171 | 50% |  |
| OI | 5 | 125 | +0.002 | +0.009 | +0.005 | +0.024 | +0.025 | 48% | +0.199 | -0.186 | 51% |  |
| OI | 15 | 101 | +0.013 | +0.023 | +0.003 | +0.019 | +0.020 | 24% | +0.187 | -0.157 | 52% |  |
| OI_rnd | 5 | 112 | -0.004 | +0.001 | +0.003 | +0.012 | +0.013 | 50% | +0.189 | -0.190 | 46% |  |
| OI_rnd | 15 | 105 | -0.000 | -0.001 | -0.008 | +0.053 | +0.054 | 31% | +0.169 | -0.162 | 54% |  |
| OR | 5 | 1,050 | +0.001 | +0.004 | +0.016 | +0.011 | +0.011 | 54% | +0.172 | -0.161 | 50% |  |
| OR | 15 | 841 | -0.004 | +0.008 | +0.011 | +0.008 | +0.008 | 38% | +0.168 | -0.161 | 50% |  |
| PDHL | 5 | 907 | -0.001 | +0.001 | +0.009 | +0.020 | +0.020 | 41% | +0.206 | -0.203 | 50% |  |
| PDHL | 15 | 828 | +0.004 | +0.005 | +0.013 | +0.022 | +0.022 | 27% | +0.187 | -0.178 | 51% |  |
| PDHL_rnd | 5 | 791 | -0.005 | -0.008 | -0.000 | +0.004 | +0.005 | 43% | +0.195 | -0.202 | 49% |  |
| PDHL_rnd | 15 | 675 | +0.001 | -0.000 | +0.007 | +0.013 | +0.013 | 27% | +0.181 | -0.177 | 50% |  |
| PIV | 5 | 812 | +0.017 | +0.030 | +0.037 | +0.036 | +0.036 | 42% | +0.222 | -0.179 | 52% |  |
| PIV | 15 | 748 | +0.006 | +0.013 | +0.010 | +0.029 | +0.029 | 30% | +0.193 | -0.181 | 51% |  |
| PIV_rnd | 5 | 734 | +0.011 | +0.021 | +0.017 | +0.008 | +0.008 | 44% | +0.214 | -0.186 | 49% |  |
| PIV_rnd | 15 | 650 | +0.006 | +0.014 | +0.014 | +0.025 | +0.025 | 29% | +0.198 | -0.178 | 50% |  |

### T2. 1 vs 2 vs 3 closes by index (design; mv30/mvc/wait in index points)

| und | tf | n | events | mv30 | mvc | mvc_a | fake30 | wait | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | 5 | 1 | 1,622 | +4.3 | +2.1 | +0.006 | 64% | +0.0 | 47% |  |
| BANKNIFTY | 5 | 2 | 1,434 | +3.0 | -0.5 | +0.005 | 50% | +22.6 | 49% |  |
| BANKNIFTY | 5 | 3 | 1,321 | +5.5 | -1.1 | +0.006 | 41% | +37.7 | 49% |  |
| BANKNIFTY | 15 | 1 | 1,245 | +4.4 | -0.5 | +0.004 | 50% | +0.0 | 48% |  |
| BANKNIFTY | 15 | 2 | 1,061 | +3.6 | -1.7 | +0.004 | 33% | +30.4 | 48% |  |
| BANKNIFTY | 15 | 3 | 962 | -3.0 | -4.8 | -0.001 | 27% | +52.0 | 48% |  |
| FINNIFTY | 5 | 1 | 1,663 | +5.0 | +8.3 | +0.030 | 62% | +0.0 | 50% |  |
| FINNIFTY | 5 | 2 | 1,471 | +3.4 | +6.5 | +0.025 | 48% | +11.3 | 51% |  |
| FINNIFTY | 5 | 3 | 1,352 | +4.2 | +7.6 | +0.029 | 39% | +18.3 | 52% |  |
| FINNIFTY | 15 | 1 | 1,263 | +3.1 | +5.3 | +0.022 | 49% | +0.0 | 51% |  |
| FINNIFTY | 15 | 2 | 1,087 | +3.6 | +3.1 | +0.013 | 32% | +13.1 | 50% |  |
| FINNIFTY | 15 | 3 | 988 | +1.6 | -1.8 | -0.001 | 26% | +24.6 | 48% |  |
| NIFTY | 5 | 1 | 1,586 | +2.7 | +9.4 | +0.044 | 60% | +0.0 | 53% |  |
| NIFTY | 5 | 2 | 1,441 | +0.9 | +6.8 | +0.034 | 48% | +8.2 | 52% |  |
| NIFTY | 5 | 3 | 1,337 | +0.9 | +6.7 | +0.032 | 38% | +13.5 | 53% |  |
| NIFTY | 15 | 1 | 1,231 | +1.4 | +7.6 | +0.035 | 46% | +0.0 | 53% |  |
| NIFTY | 15 | 2 | 1,054 | +2.3 | +9.1 | +0.039 | 31% | +10.8 | 54% |  |
| NIFTY | 15 | 3 | 958 | +3.6 | +8.7 | +0.035 | 24% | +18.5 | 56% |  |

### T2b. 1 vs 2 vs 3 closes, all real levels (design)

| tf | n | events | mv30_a | mvc_a | exc_a | fake30 | wait_a | wait | flag |
|---|---|---|---|---|---|---|---|---|---|
| 5 | 1 | 4,871 | +0.012 | +0.027 | +0.027 | 62% | +0.000 | +0.0 |  |
| 5 | 2 | 4,346 | +0.008 | +0.021 | +0.022 | 49% | +0.035 | +14.0 |  |
| 5 | 3 | 4,010 | +0.010 | +0.022 | +0.022 | 39% | +0.058 | +23.1 |  |
| 15 | 1 | 3,739 | +0.009 | +0.020 | +0.020 | 49% | +0.000 | +0.0 |  |
| 15 | 2 | 3,202 | +0.010 | +0.019 | +0.019 | 32% | +0.046 | +18.1 |  |
| 15 | 3 | 2,908 | +0.008 | +0.011 | +0.011 | 26% | +0.079 | +31.7 |  |

### T3. N=2 by direction (design, all real level types)

| dir | events | mv30_a | mvc_a | exc_a | fake30 | mfe60_a | mae60_a | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|
| down | 3,638 | +0.011 | +0.019 | +0.024 | 43% | +0.208 | -0.183 | 52% |  |
| up | 3,910 | +0.007 | +0.021 | +0.017 | 40% | +0.174 | -0.175 | 50% |  |

### T3. N=2 by time of the event (design, all real level types)

| tod | events | mv30_a | mvc_a | exc_a | fake30 | mfe60_a | mae60_a | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|
| 09:15-10:30 | 3,507 | +0.006 | +0.051 | +0.051 | 36% | +0.212 | -0.201 | 52% |  |
| 10:30-12:00 | 1,929 | +0.008 | -0.018 | -0.018 | 45% | +0.169 | -0.166 | 47% |  |
| 12:00-13:30 | 1,041 | +0.014 | +0.018 | +0.018 | 49% | +0.178 | -0.153 | 53% |  |
| 13:30-15:15 | 1,071 | +0.012 | -0.009 | -0.008 | 46% | +0.169 | -0.153 | 52% |  |

### T3. N=2 by VIX previous close (design, all real level types)

| vixb | events | mv30_a | mvc_a | exc_a | fake30 | mfe60_a | mae60_a | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|
| 13-16 | 3,836 | +0.001 | -0.014 | -0.014 | 43% | +0.185 | -0.189 | 47% |  |
| <13 | 2,176 | +0.022 | +0.064 | +0.064 | 37% | +0.199 | -0.158 | 56% |  |
| >=16 | 1,536 | +0.009 | +0.043 | +0.044 | 43% | +0.191 | -0.182 | 53% |  |

### T3. N=2 by expiry day (design, all real level types)

| exp | events | mv30_a | mvc_a | exc_a | fake30 | mfe60_a | mae60_a | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|
| expiry | 818 | +0.014 | +0.003 | +0.004 | 40% | +0.190 | -0.179 | 50% |  |
| n/a (FINNIFTY) | 2,558 | +0.012 | +0.020 | +0.020 | 41% | +0.194 | -0.178 | 51% |  |
| other | 4,172 | +0.006 | +0.024 | +0.024 | 42% | +0.188 | -0.179 | 51% |  |

### T3. N=2 by index (design, all real level types)

| und | events | mv30_a | mvc_a | exc_a | fake30 | mfe60_a | mae60_a | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | 2,495 | +0.006 | +0.005 | +0.005 | 43% | +0.188 | -0.181 | 49% |  |
| FINNIFTY | 2,558 | +0.012 | +0.020 | +0.020 | 41% | +0.194 | -0.178 | 51% |  |
| NIFTY | 2,495 | +0.008 | +0.036 | +0.036 | 40% | +0.188 | -0.176 | 53% |  |

### T3. N=2 by gap (crossing candle opened beyond the level) (design, all real level types)

| gap | events | mv30_a | mvc_a | exc_a | fake30 | mfe60_a | mae60_a | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|
| False | 6,326 | +0.009 | +0.009 | +0.009 | 45% | +0.184 | -0.172 | 50% |  |
| True | 1,222 | +0.008 | +0.081 | +0.081 | 25% | +0.223 | -0.211 | 54% |  |

### T4. N=2 level type x time of day (design, 5- and 15-min pooled)

| type | tod | events | mvc_a | exc_a | fake30 | win_c | flag |
|---|---|---|---|---|---|---|---|
| LIQ | 09:15-10:30 | 1,038 | +0.041 | +0.041 | 41% | 51% |  |
| LIQ | 10:30-12:00 | 359 | -0.007 | -0.006 | 51% | 46% |  |
| LIQ | 12:00-13:30 | 310 | +0.016 | +0.016 | 55% | 50% |  |
| LIQ | 13:30-15:15 | 429 | -0.006 | -0.006 | 51% | 52% |  |
| OI | 09:15-10:30 | 125 | +0.020 | +0.019 | 39% | 47% |  |
| OI | 10:30-12:00 | 51 | +0.024 | +0.028 | 25% | 55% |  |
| OI | 12:00-13:30 | 18 | +0.072 | +0.075 | 50% | 61% | n<30 |
| OI | 13:30-15:15 | 32 | -0.005 | -0.004 | 41% | 59% |  |
| OR | 09:15-10:30 | 292 | +0.047 | +0.047 | 55% | 53% |  |
| OR | 10:30-12:00 | 923 | -0.008 | -0.009 | 45% | 48% |  |
| OR | 12:00-13:30 | 387 | +0.037 | +0.038 | 47% | 53% |  |
| OR | 13:30-15:15 | 289 | -0.007 | -0.006 | 43% | 51% |  |
| PDHL | 09:15-10:30 | 1,149 | +0.048 | +0.048 | 30% | 52% |  |
| PDHL | 10:30-12:00 | 297 | -0.051 | -0.051 | 43% | 45% |  |
| PDHL | 12:00-13:30 | 153 | -0.004 | -0.004 | 42% | 53% |  |
| PDHL | 13:30-15:15 | 136 | -0.018 | -0.017 | 41% | 54% |  |
| PIV | 09:15-10:30 | 903 | +0.072 | +0.072 | 30% | 54% |  |
| PIV | 10:30-12:00 | 299 | -0.035 | -0.034 | 44% | 46% |  |
| PIV | 12:00-13:30 | 173 | -0.009 | -0.009 | 47% | 53% |  |
| PIV | 13:30-15:15 | 185 | -0.012 | -0.010 | 43% | 50% |  |

### T5. N=2 level type x VIX (design, 5- and 15-min pooled)

| type | vixb | events | mvc_a | exc_a | fake30 | win_c | flag |
|---|---|---|---|---|---|---|---|
| LIQ | 13-16 | 1,082 | -0.010 | -0.009 | 48% | 47% |  |
| LIQ | <13 | 622 | +0.058 | +0.058 | 44% | 55% |  |
| LIQ | >=16 | 432 | +0.040 | +0.041 | 47% | 51% |  |
| OI | 13-16 | 120 | -0.044 | -0.044 | 40% | 45% |  |
| OI | <13 | 67 | +0.074 | +0.074 | 31% | 57% |  |
| OI | >=16 | 39 | +0.133 | +0.137 | 38% | 64% |  |
| OR | 13-16 | 955 | -0.012 | -0.012 | 47% | 46% |  |
| OR | <13 | 546 | +0.061 | +0.061 | 45% | 58% |  |
| OR | >=16 | 390 | -0.009 | -0.008 | 48% | 50% |  |
| PDHL | 13-16 | 872 | -0.009 | -0.009 | 35% | 48% |  |
| PDHL | <13 | 498 | +0.045 | +0.044 | 30% | 53% |  |
| PDHL | >=16 | 365 | +0.061 | +0.061 | 38% | 55% |  |
| PIV | 13-16 | 807 | -0.022 | -0.022 | 39% | 47% |  |
| PIV | <13 | 443 | +0.098 | +0.098 | 28% | 58% |  |
| PIV | >=16 | 310 | +0.080 | +0.081 | 40% | 55% |  |

### T6. N=2 level type x expiry (design, 5- and 15-min pooled)

| type | exp | events | mvc_a | exc_a | fake30 | win_c | flag |
|---|---|---|---|---|---|---|---|
| LIQ | expiry | 230 | +0.009 | +0.009 | 45% | 51% |  |
| LIQ | n/a (FINNIFTY) | 740 | +0.023 | +0.023 | 45% | 51% |  |
| LIQ | other | 1,166 | +0.020 | +0.021 | 48% | 49% |  |
| OI | expiry | 39 | +0.090 | +0.091 | 33% | 56% |  |
| OI | other | 187 | +0.007 | +0.008 | 38% | 51% |  |
| OR | expiry | 194 | +0.012 | +0.012 | 49% | 47% |  |
| OR | n/a (FINNIFTY) | 638 | +0.008 | +0.007 | 47% | 49% |  |
| OR | other | 1,059 | +0.011 | +0.011 | 46% | 51% |  |
| PDHL | expiry | 177 | +0.012 | +0.013 | 29% | 51% |  |
| PDHL | n/a (FINNIFTY) | 626 | +0.017 | +0.017 | 35% | 51% |  |
| PDHL | other | 932 | +0.025 | +0.026 | 35% | 51% |  |
| PIV | expiry | 178 | -0.040 | -0.040 | 35% | 47% |  |
| PIV | n/a (FINNIFTY) | 554 | +0.034 | +0.034 | 36% | 51% |  |
| PIV | other | 828 | +0.047 | +0.047 | 36% | 53% |  |

### T7. HOLDOUT N=2 by level type and timeframe (information, read after the one holdout read)

| type | tf | events | mv15_a | mv30_a | mv60_a | mvc_a | exc_a | fake30 | mfe60_a | mae60_a | win_c | flag |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LIQ | 5 | 451 | +0.008 | +0.019 | +0.022 | +0.012 | +0.010 | 50% | +0.198 | -0.164 | 51% |  |
| LIQ | 15 | 215 | -0.010 | -0.019 | -0.009 | -0.026 | -0.026 | 31% | +0.155 | -0.173 | 49% |  |
| OI | 5 | 72 | +0.010 | +0.006 | -0.011 | +0.025 | +0.023 | 42% | +0.184 | -0.172 | 54% |  |
| OI | 15 | 64 | +0.001 | +0.000 | +0.007 | +0.010 | +0.009 | 28% | +0.160 | -0.154 | 56% |  |
| OI_rnd | 5 | 65 | +0.013 | +0.002 | +0.009 | +0.017 | +0.016 | 45% | +0.189 | -0.166 | 55% |  |
| OI_rnd | 15 | 55 | +0.021 | +0.008 | +0.008 | +0.034 | +0.035 | 27% | +0.171 | -0.147 | 56% |  |
| OR | 5 | 343 | +0.013 | +0.012 | +0.009 | -0.003 | -0.000 | 54% | +0.169 | -0.149 | 52% |  |
| OR | 15 | 265 | +0.006 | -0.008 | +0.001 | +0.004 | +0.006 | 42% | +0.170 | -0.166 | 55% |  |
| PDHL | 5 | 296 | -0.008 | -0.005 | +0.006 | -0.038 | -0.039 | 39% | +0.192 | -0.191 | 52% |  |
| PDHL | 15 | 269 | -0.001 | +0.006 | +0.000 | -0.031 | -0.032 | 23% | +0.176 | -0.170 | 55% |  |
| PDHL_rnd | 5 | 252 | -0.002 | -0.005 | -0.009 | -0.038 | -0.039 | 41% | +0.194 | -0.195 | 50% |  |
| PDHL_rnd | 15 | 236 | -0.003 | -0.008 | -0.015 | -0.026 | -0.026 | 29% | +0.171 | -0.179 | 55% |  |
| PIV | 5 | 271 | +0.021 | +0.020 | +0.037 | -0.002 | -0.003 | 39% | +0.200 | -0.158 | 51% |  |
| PIV | 15 | 249 | +0.002 | +0.002 | +0.011 | -0.036 | -0.037 | 31% | +0.172 | -0.165 | 52% |  |
| PIV_rnd | 5 | 232 | +0.024 | +0.017 | +0.033 | +0.023 | +0.022 | 42% | +0.201 | -0.154 | 54% |  |
| PIV_rnd | 15 | 197 | -0.005 | -0.002 | +0.020 | +0.007 | +0.006 | 29% | +0.186 | -0.172 | 55% |  |

### T7b. HOLDOUT 1 vs 2 vs 3 closes

| tf | n | events | mv30_a | mvc_a | exc_a | fake30 | wait_a | wait | flag |
|---|---|---|---|---|---|---|---|---|---|
| 5 | 1 | 1,559 | +0.020 | +0.007 | +0.007 | 60% | +0.000 | +0.0 |  |
| 5 | 2 | 1,433 | +0.012 | -0.004 | -0.004 | 46% | +0.032 | +11.3 |  |
| 5 | 3 | 1,336 | +0.003 | -0.015 | -0.015 | 37% | +0.060 | +21.6 |  |
| 15 | 1 | 1,259 | +0.006 | -0.003 | -0.004 | 48% | +0.000 | +0.0 |  |
| 15 | 2 | 1,062 | -0.004 | -0.020 | -0.020 | 32% | +0.050 | +17.3 |  |
| 15 | 3 | 941 | -0.006 | -0.018 | -0.017 | 26% | +0.077 | +26.4 |  |


### Paired: the same crossing candle, 1st close vs 2nd close (design, all real levels)

| candles | breaks with a 2nd close | wait cost (pts) | move to close from the 1st close | from the 2nd close | breaks with NO 2nd close | their move from the 1st close |
|---|---|---|---|---|---|---|
| 5-min | 3,598 | +14.1 | +18.0 | +3.9 | 1,273 | -25.5 |
| 15-min | 2,823 | +18.2 | +21.8 | +3.7 | 916 | -50.4 |

(The "from the 1st close" column for confirmed breaks includes the 2nd candle itself, which is only known afterwards; it
shows what the 2nd close sorts, not what anyone could earn.)

What stood out (design), all small:
- Time of day: breaks before 10:30 carried to the close (+0.05 ATR, mostly gap mornings); breaks at 10:30-12:00 drifted
  back (-0.02).
- VIX < 13: +0.06 ATR to the close; VIX 13-16: -0.01. Low-VIX days clustered in a few months, so this is mostly one regime.
- Expiry days: no better (+0.004 ATR) than other days (+0.024).
- Index: NIFTY +0.036 ATR, FINNIFTY +0.020, BANKNIFTY +0.005 (about 0 points).
- Up vs down: the same (+0.021 / +0.019).
- Gap breaks: +0.08 ATR to the close, 25% fake-out, against +0.01 and 45% for breaks inside the candle. That is the
  known "gap-and-go" morning, not something the level adds.
- OI walls: positive on NIFTY (34-41 events) and negative on BANKNIFTY (67-84 events); too few events to use.

## Step 2: the 4 pre-registered rules (PREREG.md part B)

BANKNIFTY and NIFTY together, both sides, at most one trade a side a day (the first confirmed break), 1 lot, the app's
paper fills and charges.
- `r20brk-piv5n2`: R1/S1 break, 2 closing 5-minute candles, decided by 14:00, ATM option, ORB exits (-40 / +40 premium
  points on BANKNIFTY, -20 / +20 on NIFTY, profit-lock ladder, 15:10).
- `r20brk-piv5n1`: the same with 1 close (the "is the 2nd candle worth it" twin).
- `r20brk-early15`: R1/S1/PDH/PDL break, 2 closing 15-minute candles, decided by 10:30, 1-ITM option, Liquidity exits
  (15% premium stop, failed break on a 15-minute close, 20-minute +5% time stop, 15:10).
- `r20brk-lowvix15`: R1/S1 break, 2 closing 15-minute candles, only when the VIX previous close is below 13, decided by
  14:00, Liquidity exits.

## Step 3: trading test (walk-forward design blocks + the one holdout read)

| rule | period | sessions | trades | trades / session | Rs / session | % green trade days | worst day | max drawdown | net | BANKNIFTY / NIFTY net |
|---|---|---|---|---|---|---|---|---|---|---|
| r20brk-piv5n2 | design | 395 | 491 | 1.24 | -54 | 45% (302 days) | -2,947 | -40,006 | -21,292 | -14,075 / -7,217 |
| r20brk-piv5n2 | holdout | 153 | 183 | 1.20 | +167 | 53% (121 days) | -2,893 | -6,213 | +25,603 | +12,124 / +13,478 |
| r20brk-piv5n1 | design | 395 | 532 | 1.35 | +93 | 49% (322 days) | -2,921 | -9,436 | +36,785 | +14,207 / +22,578 |
| r20brk-piv5n1 | holdout | 153 | 195 | 1.27 | +34 | 49% (127 days) | -2,965 | -6,600 | +5,225 | +1,519 / +3,706 |
| r20brk-early15 | design | 395 | 331 | 0.84 | +85 | 27% (226 days) | -5,267 | -46,405 | +33,393 | +4,904 / +28,489 |
| r20brk-early15 | holdout | 153 | 127 | 0.83 | -47 | 31% (95 days) | -4,814 | -34,095 | -7,117 | -17,908 / +10,791 |
| r20brk-lowvix15 | design | 395 | 115 | 0.29 | +167 | 38% (84 days) | -3,017 | -9,999 | +66,057 | +20,010 / +46,047 |
| r20brk-lowvix15 | holdout | 153 | 127 | 0.83 | -217 | 30% (83 days) | -4,814 | -43,493 | -33,153 | -38,629 / +5,475 |

Gates (G1 design net > 0; G2 >= 3 of 4 design blocks > 0; G3 holdout t-test; G4 random-level twin; G5 random-time twin; G3-G5 must survive BH at q 0.05 over 40 p-values; G6 holdout net > 0 with doubled spread ticks). Twins: 1,000 each.

| rule | design blocks 1-4 (Rs) | G1 | G2 | G3 holdout p (BH q) | G4 random-level p (q); twin mean Rs | G5 random-time p (q); twin mean Rs | G6 holdout at 2x ticks | PASS |
|---|---|---|---|---|---|---|---|---|
| r20brk-piv5n2 | -7,171 / -7,630 / -17,157 / +10,667 | FAIL | FAIL | 0.021 (0.33) | 0.195 (1.00); +19,734 | 0.004 (0.16); -4,212 | +23,546 | **no** |
| r20brk-piv5n1 | -1,138 / +3,296 / +22,914 / +11,713 | pass | pass | 0.342 (1.00) | 0.955 (1.00); +17,127 | 0.189 (1.00); -5,300 | +3,860 | **no** |
| r20brk-early15 | +3,662 / +7,048 / +25,195 / -2,511 | pass | pass | 0.591 (1.00) | 0.132 (1.00); -23,079 | 0.507 (1.00); -7,062 | -7,814 | **no** |
| r20brk-lowvix15 | -5,366 / +13,794 / +31,236 / +26,393 | pass | pass | 0.922 (1.00) | 0.784 (1.00); -25,241 | 0.968 (1.00); -4,829 | -33,826 | **no** |

Design blocks (quarters of the 395 design dates): 5 Mar - 1 Aug 2024, 2 Aug - 26 Dec 2024, 27 Dec 2024 - 22 May 2025,
23 May - 10 Oct 2025. Holdout: 153 calendar dates (BANKNIFTY 25 Aug 2025 - 23 Feb 2026, NIFTY 13 Oct 2025 - 13 Apr 2026).

Reading the results:
- **piv5n2** (Boss's exact idea on R1/S1, 5-minute candles): lost in 3 of 4 design blocks (Rs -21,292 over 491 trades),
  then made Rs +25,603 over 183 holdout trades (53% green days, worst day Rs -2,893). It beat the random-TIME twin (p 0.004),
  so the timing of the break mattered in that window, but random LEVELS at the same distance made almost as much (+Rs 19,734
  on average, p 0.20). It is momentum in a trending window, not the level. Its best q is 0.16; it fails G1, G2, G4 and BH.
- **piv5n1** (1 close): good design (+Rs 36,785, 3 of 4 blocks), weak holdout (+Rs 5,225, p 0.34; random levels +Rs
  17,127 on average, p 0.96).
- **early15**: one huge design day (NIFTY 12 May 2025, +Rs 20,796) carried it; without its best day design was +Rs 5,671.
  Holdout Rs -7,117 (BANKNIFTY -17,908, NIFTY +10,791).
- **lowvix15**: the design's best (+Rs 66,057 on 115 trades) became the holdout's worst (Rs -33,153 on 127 trades). The
  holdout months had low VIX on most days, so the filter let through far more trades than in design, and they lost.
- BH family (40): the smallest q values are 0.16 (piv5n2 G5) and 0.33 (piv5n2 G3, R20 B0.5 G4). Nothing rejected.

## Holdout event study (information only, read after the trades)

Two-close breaks on the holdout: 2,495 events. To the close: -0.004 ATR (5-min) and -0.020 (15-min); 30 minutes:
+0.012 / -0.004; fake-outs 46% / 32%; wait cost 11 / 17 points. Every real level type's to-close move was within +-0.04
ATR, and the random-level rows were the same (tables T7, T7b; full tables `results/events_holdout.md`).

## Limits (stated, not fixed)

- Options are the release's nearest expiry AFTER the day, so on expiry days the rules trade next week's / month's
  contract (the app trades today's). The Liquidity-family exits leave out "next liquidity" and "new liquidity" (they need a
  zone map that PIV / PDHL levels do not have).
- Two years of minute data. The holdout is 4-6 months (153 dates). Small windows swing a lot: piv5n2's design and holdout
  records point in opposite directions.
- R20 / R20b / R20c share the same holdout window. R17 had already read it for the plain ORB; none of R20c's rules or
  level breaks had been run on it before the one read here.

## What this means for the app

Nothing to build. Do not add a "2 candles beyond support / resistance" entry, filter or exit. The levels are still useful as
a map to show Boss, but a confirmed break is not an edge on its own. If the idea is ever re-opened, it needs fresh data
(the Dhan lake, 2020-2026) and a new pre-registration: the honest lead is "5-minute momentum for 30 minutes after a
break", which random levels share, so it is a momentum idea, not a level idea.
