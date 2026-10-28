# R127 pre-registration: Boss's CPR video strategy, as stated

Written 28 Oct 2026, before any CPR level, trade or statistic was computed. Research only: nothing in the app changes,
nothing trades.

Boss sent a video's rules, 28 Oct:

1. **CPR from yesterday's session:**
   - Pivot P = (H + L + C) / 3;
   - BC = (H + L) / 2;
   - TC = 2P - BC.
2. **Width:** a narrow CPR means a trending day is likely; a wide CPR means a sideways day.
3. **Bias:**
   - open above the CPR → bullish: wait for a pullback to TC; if it holds and bounces, enter;
   - open below the CPR → bearish (mirror);
   - open inside → wait.
4. **Trade:** buy above TC; stop just below BC; target R1.

**Earlier work.** None of these tested this exact pullback rule:
- OBUY_GA LV-02: a narrow-CPR *breakout* with R1/R2 targets lost (-Rs 62.3k walk-forward).
- FINDINGS (SCALP_1MIN): narrow-CPR days were not more trending on BANKNIFTY (day range 513 vs 583 points).
- H35: P / R1 / S1 were never tested as rules.

## 1. Definitions (every choice the video leaves open is fixed here)

**Levels** (classic floor pivots), from the previous session of the same index:
- H, L = its highest high and lowest low; C = its last minute's close.
- Top = max(TC, BC); Bottom = min(TC, BC).
- R1 = 2P - L, R2 = P + (H - L); S1 = 2P - H, S2 = P - (H - L).
- The formula needs yesterday's close, and the bias needs today's open: these are the only uses of a close or an
  open, as the video defines them.

**Width.** Width = (Top - Bottom) / P. "Narrow" is the lowest third of the previous 60 sessions' widths; "wide" is
the highest third. Earlier sessions only.

**Bias.** Today's first-minute open is above Top (bullish), below Bottom (bearish), or inside (no trade).

**The bullish entry (wicks only, 5-minute candles aligned to 09:15, decisions from 09:20 to 14:30):**
- *Pullback:* a completed 5-minute candle whose **low** touches Top (low ≤ Top × 1.0003).
- *Holds:* that candle's low stays above Bottom.
- *Bounce:* within the next 3 candles, the index's **high** goes above the touching candle's high, and no low goes
  below Bottom before that.
- *Entry:* buy the ATM call at the open of the minute after the high is taken out (the option is bought then).
- One trade per index per day: the first valid setup only.

**Exits** (on the index's wicks, minute by minute after the entry):
- *Stop:* a low at or below Bottom × (1 - 0.0003), i.e. just below the bottom line.
- *Target:* a high at or above the first of R1 or R2 that lies above the entry price. If neither does, there is no
  trade.
- If both happen in the same minute, the stop counts (worst case).
- Otherwise the trade is squared off at 15:20 (15:05 on expiry days).
- The option is sold at that minute's last traded close (cleaned bars; minutes with no trade are never priced).

**Bearish:** the mirror. Pullback to Bottom (high ≥ Bottom × 0.9997, holding below Top); buy the ATM put; stop above
Top × 1.0003; target the first of S1 or S2 below the entry.

**Money.** 1 lot at today's lots, dated charges, liquidity-aware slippage (Fills "liq"), also at 2x slippage. The
index-points result is reported too.

## 2. Variants (3, one BH family)

| variant | what |
|---|---|
| V1 | the video's rule, every day with a bias |
| V2 | V1 on narrow-CPR days only (the video says these trend) |
| V3 | V1 with 1-minute candles for the pullback and bounce, instead of 5-minute |

## 3. Data and checks

- **Data:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, from the first session with option bars (Aug 2021;
  MIDCPNIFTY / SENSEX later) to 19 Oct 2026. Lake as R123-R126 (lakeclean steps 1-3).
- **No fitting, so no train / test split:** the rule is fixed by the video. It is tested once on all the data, and
  reported by calendar year and per index. Apr - Oct 2026 is reported separately as "the latest half-year". It was
  read earlier today for other questions, never for CPR.
- **The video's claims, checked descriptively:**
  - narrow vs wide CPR days: day range divided by the 20-day average range, and the share of "trend days";
  - bias: after an open above / below the CPR, how often the day's move from the open goes further up than down;
  - both use **wicks only**. A trend day is one whose open lies in the bottom or top 20% of its high-low range (so
    nearly the whole range is on one side of the open).
- **Twins (200 per trade):** the same index, day and side; a random entry minute between 09:20 and 14:30; the same
  stop and target *distances* in index points from the entry; the same exit rules. p = (1 + twins with total net ≥
  the rule's) / 201.
- **Gates.** A variant passes only if:
  - ≥ 300 trades;
  - average net per trade > 0 at 1x and 2x slippage;
  - positive in ≥ 3 of the 5 calendar years 2022-2026;
  - BH q ≤ 0.10 over the 3 variants vs twins;
  - max drawdown above -Rs 40,000 and no calendar month below -Rs 13,000 on 1 lot.
- **If one passes,** it is proposed for a paper test (never live), because Boss sent the rule.

## 4. Outputs

- Code and tests: `research/hunt/r127_cpr/`.
- Write-up: `research/HUNT_R127_CPR.md`.
- Experiment entries: `research/experiments/entries/r127-*.yaml`.
- RESULT.md.
