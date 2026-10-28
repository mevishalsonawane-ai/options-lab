# R127: the CPR (Central Pivot Range) video strategy, backtested as stated

28 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss sent a video's rules:
- the CPR from yesterday: P = (H + L + C) / 3, BC = (H + L) / 2, TC = 2P - BC;
- a narrow CPR means a trend day, a wide one a sideways day;
- open above the CPR → wait for a pullback to TC; if it holds and bounces, buy, with the stop just below BC and the
  target R1;
- below the CPR → sell (mirror); inside → wait.

## The answer: it loses money, in every version and every year

**On all five indices from Aug 2021 to Oct 2026, buying the ATM option on the video's signal lost money after
charges and slippage: in every year, on every index, and in all three versions. Two of the video's claims are also
wrong in the data.**

| version | trades | win rate | avg win / avg loss | avg net per trade | total (1 lot) | years positive (2022-26) | vs random entries |
|---|---|---|---|---|---|---|---|
| **V1, the rule as stated** (5-min pullback) | 2,495 | 42% | Rs 2,106 / -Rs 2,233 | **-Rs 428** | **-Rs 10.67 lakh** | 0 of 5 | no better (p 0.25) |
| V2, narrow-CPR days only | 809 | 43% | Rs 1,409 / -Rs 1,485 | -Rs 240 | -Rs 1.94 lakh | 0 of 5 | no better (p 0.56) |
| V3, 1-minute pullback | 2,602 | 33% | Rs 2,629 / -Rs 1,843 | -Rs 353 | -Rs 9.18 lakh | 0 of 5 | better than random (p 0.005, q 0.015), but still loses |

- **By year (V1):** 2022 -Rs 1.61 lakh, 2023 -Rs 1.07 lakh, 2024 -Rs 4.77 lakh, 2025 -Rs 2.21 lakh, 2026 to 19 Oct
  -Rs 0.74 lakh.
- **By index (V1):** NIFTY -Rs 1.06 lakh (605 trades, 43% won), BANKNIFTY -Rs 2.81 lakh, FINNIFTY -Rs 1.69 lakh,
  MIDCPNIFTY -Rs 4.12 lakh, SENSEX -Rs 1.00 lakh.
- **How the trades end (V1):** 1,103 stops, 921 targets, 471 at the 15:20 square-off.
  - The stop (just below BC) and the target (R1) are about the same distance away, so the trade is roughly a coin
    flip on the index: the average V1 trade loses 6.4 index points.
  - Then the option's charges, spread and time decay make it a clear loss.
- **Risk on 1 lot (V1):**
  - worst month -Rs 1,03,618 (Jan 2024);
  - drawdown -Rs 11.2 lakh;
  - only 10 of 63 months green.

  It would have broken Boss's account stop (-Rs 40k drawdown, -Rs 13k a month) many times.
- **Latest half-year (Apr - Oct 2026):** V1 288 trades, -Rs 153 each; V3 281 trades, -Rs 157. V2 made +Rs 155 a trade
  on 109 trades (54% won), the only positive slice anywhere. It is too small to mean anything after V2 lost in every
  full year before it.

**Nothing passes the gates. Nothing goes on paper. Nothing changes in the app.**

## The video's claims, checked on the data (wicks only)

1,433 sessions; 6,112 index-days across the five indices.

| claim | what the data shows |
|---|---|
| a narrow CPR → a trending day | **No, the opposite.** Narrow-CPR days were *quieter*: day range 0.97× the 20-day average vs 1.09× on wide-CPR days (2,075 narrow days). "Trend days" (the open within the bottom or top 20% of the day's range) were 49% on narrow days vs 47% on wide days, about the same. This matches FINDINGS (BANKNIFTY: narrow 513 vs wide 583 points). |
| open above the CPR → bullish | **No, slightly the opposite.** After an open above the CPR (3,270 days), the day went further down than up from the open 59% of the time (further up only 41%, against 45% on all days). After an open below the CPR (2,099 days), it went further up 52% of the time. Opening outside the CPR is followed by a pull back towards it, not a run away from it. |

## How it was done

- **Pre-registration:** `research/hunt/r127_cpr/PREREG.md`, commit `b15aba4`, before any level or trade was computed.
- **Code and 5 tests:** `research/hunt/r127_cpr/` (`r127cpr.py`, `test_r127.py`).
- **Results:** `results/results.json`. The trade list is on Drive (`derived/r127/`).
- **Levels:**
  - Yesterday's high, low and last close give P, BC = (H + L) / 2, TC = 2P - BC, with top / bottom = the higher /
    lower of TC and BC.
  - R1 = 2P - L, R2 = P + (H - L), S1 = 2P - H, S2 = P - (H - L).
  - The formula needs yesterday's close, and the bias needs today's open, as the video defines them. Every other
    check reads candle wicks.
- **Bias:** today's first-minute open above the top (buy), below the bottom (sell), or inside (no trade).
- **Entry:**
  - Pullback and hold: a completed 5-minute candle (V3: 1-minute) whose low touches TC (within 0.03%) and stays above
    BC.
  - Bounce: within the next 3 candles, a high above that candle's high, with no low below BC first.
  - Buy the ATM call at the next minute's open; the first setup of the day only; decisions 09:20-14:30.
  - Sells mirror this and buy the ATM put.
- **Exits** (index wicks):
  - stop when a low reaches 0.03% below BC;
  - target at R1 (or R2 if R1 is already passed);
  - a stop and a target in the same minute count as the stop;
  - otherwise out at 15:20 (15:05 on expiry days).
- **Money:** 1 lot at today's lots, dated charges and liquidity-aware slippage. The option is sold at the exit minute's
  last traded price (cleaned lake bars; minutes with no trade are never priced).
- **Random twins (200 per trade):** the same index, day and side; a random entry time; the same stop and target
  distances in points.
- **Gates:** ≥ 300 trades; positive at 1x and 2x slippage; positive in 3 of 5 years; BH q ≤ 0.10 over the 3 versions;
  Boss's account stop not breached.

## Honest notes

0. **Corrected 28 Oct (R128 T5):** the first run counted random twins that found no trade as Rs 0, which pulled the
   twins' totals toward zero. They are now resampled from the trade's valid twins. V1's p moved 0.43 → 0.25 and V2's
   0.77 → 0.56; V3 is unchanged (0.005). No result or gate changed.

1. **"Holds and bounces" and "just below BC" are not exact in the video.** These choices were fixed before any
   result: a touch within 0.03%, no low below BC, a high above the touching candle's high within 3 candles, and a stop
   0.03% below BC. A stricter or looser reading could change the trade count, not the picture: even V2, on the days
   the video says work best, lost in every year.
2. **This was a single test on all the data, not a search.** The rule came from the video and was not fitted, so no
   train / test split was needed. Apr - Oct 2026 had been read today for other questions, never for CPR.
3. **Earlier CPR tests agree:**
   - OBUY_GA LV-02: narrow-CPR breakouts with R1/R2 targets, -Rs 62.3k walk-forward;
   - FINDINGS: narrow-CPR days are not more trending.
