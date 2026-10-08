# h47 pre-registration: "TQE 9 EMA + VWAP" guide on BTC and ETH (Delta Exchange India)

Written 2026-10-08, before any P&L was computed. Data was being fetched while this was written.
Only feature distributions (not results) may be looked at before the run, to set the percentile thresholds below.

## Data and proxy
- Prices and volume: Binance USDT-M perpetual 1-minute klines (data.binance.vision), BTCUSDT and ETHUSDT,
  2020-01-01 to the last day in the archive (about 2026-10-07).
- Proxy: Delta Exchange India BTCUSD / ETHUSD perpetuals track Binance (1-hour return correlation 0.987 / 0.994,
  median gap 1-2 bp; research/NN_CRYPTO.md). VWAP uses Binance volume (Delta's own volume is much smaller; the
  VWAP level is what matters).
- Funding: Binance 8-hourly funding (00/08/16 UTC). Delta's is slightly higher (kind to longs).
- Options: Black-Scholes at Deribit DVOL (hourly) x Delta India's live ask-IV / bid-IV to DVOL ratio for the
  tenor used (measured from a fresh Delta chain snapshot). This is a MODEL price, not traded prices. It carries
  Delta's real bid/ask (through the bid/ask IV ratio). DVOL starts 2021-03-24, so options start 2021-04.
- USD/INR fixed at 96.78 (8 Oct 2026) for "1 contract" rupees.

## Periods
- In-sample (all choosing): 2020-01-01 .. 2026-03-31. Walk-forward test years 2021..2025 (options 2022..2025),
  anchored: choose on all earlier in-sample years.
- LOCKED HOLDOUT: 2026-04-01 .. end of data. Run ONCE (script refuses a second run), for the primary version and
  for survivors only.

## Lines
- Bars: N-minute bars, N in {1, 2, 3, 5}, aligned to the UTC epoch (every anchor is a multiple of 5 min).
- EMA 9 on bar closes, alpha = 2/10, continuous across sessions (as on a chart).
- VWAP: cumulative sum(hlc3 x volume) / sum(volume) of the bars since the anchor (TradingView's default source).
- Anchors (sessions):
  - U = 00:00 UTC, 24h session.
  - I = 00:00 IST = 18:30 UTC, 24h session.
  - E = US cash session 09:30-16:00 ET (DST-aware), Monday-Friday only (the guide's).
- Every position is closed at the session end (VWAP resets). Exit at the open of the first bar after the session.
- "Ignore the first 30 min": Setups A and C take no entry on a bar that closes less than 30 min after the anchor.
- ATR: 14-day average true range of UTC daily bars, from the previous 14 completed days (no look-ahead).

## Fills and exits (all setups)
- Signals use completed bar closes. Entry and exit fill at the open of the next bar (= the next 1-minute open).
- "Body close through the 9 EMA" exit: long exits on the first bar after entry whose close < EMA9 of that bar
  (short: close > EMA9). With continuous prices the open is on the other side in practice; a wick through the
  EMA alone never exits.
- One position at a time per coin per variant. Long and short are symmetric.

## Setup A, "the cross"
- Bullish cross at bar k: EMA9(k) > VWAP(k) and EMA9(k-1) <= VWAP(k-1), both bars in the same session.
- Entry bar j >= k: the first bar whose close > max(EMA9, VWAP) (above both lines: the zone rule and the side
  rule), with EMA9 still above VWAP on every bar since k. Without the hold filter, j <= k + 3 bars, else no trade.
- Once per cross: after an exit the next long needs a new bullish cross.
- Quality filters (variants):
  - slope S: off / "with" (long only if VWAP's 30-min change >= -f30 x ATR, i.e. flat or rising) /
    "literal" (the guide's words for the long cross: VWAP flat or falling, 30-min change <= +f30 x ATR).
    f30 = the 33rd percentile of |VWAP 30-min change| / ATR (in-sample, per coin and timeframe).
  - volume V: off / on (cross bar volume > mean of the previous 20 bars).
  - hold H: 0 / 15 / 30 min: enter only once the last H minutes of bar closes (at least 1 bar) are all above both
    lines and EMA9 > VWAP since the cross; window ends k + H/N + 3 bars.
  - hours: all / entries only 10:00-13:30 ET.
- Exit: body close back through EMA9; session end.

## Setup B, "the hold"
- Window: W1 = bars closing within the first 30 min after the anchor; W2 = within 30 min after any EMA9/VWAP
  cross in the trade's direction (any time of the session).
- Long signal at bar j: EMA9 > VWAP, close(j) > EMA9(j), low(j) <= EMA9(j) (the pullback tests the EMA and holds),
  close(j) > VWAP(j), close(j-1) > EMA9(j-1) (it was riding the EMA); in W1 also close(j) > the session's first
  open (it ran off the open).
- Entry next open. Exit: first close through EMA9; session end. Re-entry allowed inside the window.

## Setup C, "the snapback"
- Long at bar j: VWAP(j-1) - close(j-1) > k x ATR (stretched far below VWAP), close(j-1) < EMA9(j-1),
  close(j) > EMA9(j) and close(j) > open(j) (a green candle closes back over the EMA), close(j) < VWAP(j).
  Short is the mirror (stretched above). k in {0.3, 0.5, 0.75}.
- Entry next open. Exit, whichever first: VWAP touch (target = VWAP of the last completed bar; a bar that opens
  beyond it exits at its open, a bar whose high/low reaches it exits at the target), or the EMA9 body-close exit,
  or session end.
- Variants: k (3) x hours (all / 10:00-13:30 ET). The skip rules are not applied (the guide says the snapback
  breaks them on purpose).

## Skip rules (Setups A and B), variants: none / K1 / K2 / K3 / all three
- K1 EMA flat: the EMA9's range over the last 15 min < e15 x ATR. e15 = 20th percentile of that range / ATR.
- K2 chasing: |EMA9 - VWAP| at the entry bar > d80 x ATR. d80 = 80th percentile of |EMA9 - VWAP| / ATR.
- K3 chop: VWAP flat (|60-min VWAP change| < f60 x ATR, f60 = 33rd percentile) AND 3 or more EMA9/VWAP crosses
  earlier in the session.
- All percentiles are of in-sample (2020-01..2026-03) bar distributions, per coin and timeframe, anchor U.

## Variant count (signals)
- A: 3 anchors x 4 TF x 3 slope x 2 volume x 3 hold x 2 hours x 5 skip = 2,160
- B: 3 x 4 x 2 windows x 5 skip = 120
- C: 3 x 4 x 3 k x 2 hours = 72
- = 2,352 per coin, x 2 coins = 4,704 signal variants, x 3 instruments = 14,112 tested variants.

## Instruments and costs (per side unless stated)
- F-T: Delta India perp, taker 0.05% + 18% GST = 0.059% of notional, plus the measured half-spread (Delta L2
  snapshot), plus funding for each 00/08/16 UTC funding time held through (long pays when the rate > 0).
- F-M: maker entry: a limit at the signal bar's close, 0.02% + GST = 0.0236%, no spread; filled only if the next
  bar trades strictly through the limit (else no trade); exit taker as F-T.
- O: buy the ATM call (long) / ATM put (short), nearest Delta daily expiry (12:00 UTC) at least 6 h away; strike
  grid BTC 200 / ETH 20; buy at ask-IV, sell at bid-IV (model prices above); fee min(0.01% of underlying notional,
  3.5% of premium) + 18% GST each side.
- Size: Rs 1,00,000 of underlying notional per trade per coin (futures unleveraged; options the same number of
  contracts, i.e. Rs 1 lakh of underlying, premium outlay a few % of that). Also shown per 1 contract
  (BTC 0.001, ETH 0.01) at each trade's own price.

## Tax (shown under both readings)
- VDA strict: 30% + 4% cess = 31.2% of each winning trade's net profit; losses give nothing back.
- Business income: 31.2% of each Indian financial year's (Apr-Mar) net profit if positive; losses set off.

## Statistics
- Random baseline: for each real trade, a random other day of the same calendar year, the same UTC entry minute,
  the same side and the same holding time, same costs. B = 100 draws per variant; p-value one-sided by a normal
  approximation of the random means (B = 1,000 for the primary and any net-positive variant).
- BH over all 14,112 variants (p vs random on net per trade); SPA (Hansen, stationary bootstrap, mean block
  5 days, B = 500) on daily net Rs at Rs 1 lakh, benchmark 0, over all variants of each instrument.
- Walk-forward 2021..2025: per coin and instrument, each test year uses the variant with the best in-sample net
  Rs/day over all earlier years.
- Survivor (may go to the holdout): in-sample net (before tax) > 0, BH q < 0.10 vs random, and SPA p < 0.10.

## PRIMARY (fixed now)
Setup A, 3-min bars, anchor U (00:00 UTC), no quality filters, no skip rules, all hours, perpetual futures,
taker (F-T), both coins. Success = holdout net after the VDA tax > 0 on both coins AND beats random.

## Reported
Win rate; gross / net Rs per trade and per day at Rs 1 lakh; after each tax reading; max drawdown; trades/day;
% green months; per year; the guide's "best days" (best 5%, best 10, days >= Rs 5,000) vs the average and median day.
