# R140 pre-registration: US gold from 06:00 to 08:00 IST (15-minute candles), and what it tells MCX gold after 09:00

Written 31 Oct 2026, before any R140 outcome number (the only thing looked at so far is data coverage: rows, first
and last minute, minutes per window). Research only: no app code changes.

Boss, 31 Oct: *"check for US gold from morning 6 a.m. to 8 a.m. IST [on 15 minute] and find a strategy. I think so.
There is something we could catch."*

**Signal source of every rule: the underlying.** For part A it is XAUUSD spot (Dukascopy BID 1-minute, built into
15-minute candles). For part B it is XAUUSD's 06:00-08:00 move (the signal) and the MCX GOLDM near future (the traded
instrument, minute closes). Nothing is read from an option's chart. Every rule has a stop-loss AND a profit lock.

## 1. What the earlier gold work already says (so we know what is new)

- GOLD_15M_PATTERNS / GOLD_30M_1H_PATTERNS: unconditional hour tables. 01:00 UTC (06:30 IST) 15m candles are green
  50.8%, 02:00 UTC 50.7%; the 01:00 UTC hour has a larger range than its neighbours (median $5.35 per 15m). No
  conditioning on the US session, the first candle or the DST regime, and nothing about MCX.
- GOLD_SESSION_STRATS: the Asian-range SWEEP (fade at the London open) lost in every setting (t < 2).
- GOLD_1H_PLUS: dropping Asian-session buys changed little.
- GOLD_REOPEN: 03:35-06:25 IST reopen entries: +$19.7k over 3 years on 479 trades, t 0.55 (no edge).
- HUNT_R2_US_MCX: the US overnight move is mostly priced into MCX's 09:00 gap; from a tradable 09:05 entry, gold
  "follow the US" made +Rs 37 a trade (no edge). R3: no GOLDM morning effect. R97 / R99: no GOLDM option-buying rule.
- **New in R140:** the 06:00-08:00 IST window itself (conditioned on the US session, the prior day, the first candle,
  US release days), the range of that window as levels after 08:00, a split by US daylight-saving regime, and the
  window -> MCX GOLDM futures link (09:05 entries, 1 lot, with a stop and a lock).

## 2. Data and periods

- **XAUUSD:** the options-lab `research-data` release `xauusd_m1_bid.csv.gz` (Dukascopy BID 1-minute, 1 Oct 2023 -
  30 Sep 2026, sha256 c4195fa6...) plus the Dukascopy tail to 30 Oct 2026 fetched by `fetch_xau.py`. The Drive lake's
  `xauusd_1m/` holds no price file. Older Dukascopy years were not fetched: Dukascopy answers HTTP 429 even to single
  requests from this container (about 7 days a minute), so 2012-2023 would take more than 6 hours. History: about 3
  years (about 770 window days).
- **MCX GOLDM:** the near future's minute close = the per-minute median `spot` of the GOLDM option book
  (options_rolling_1m, R53's loader), 5 Aug 2025 - 19 Oct 2026, about 309 sessions. (The true futures file
  fut_1m_mcx_continuous starts only in May 2026.) Close-only: every MCX fill is a minute close.
- **USD/INR:** not in the lake. During 06:00-08:00 IST the onshore rupee market is shut, so the window's move in
  rupee terms is XAUUSD's % move. The overnight USD/INR change is part of the MCX 09:00 gap and is treated as noise
  (B4 only).
- **Time:** every window is in IST. 06:00-08:00 IST = 00:30-02:30 UTC all year. **DST regimes:** "US summer"
  (New York on EDT that UTC date: the US close is 02:30 IST, gold reopens 03:30 IST) and "US winter" (EST: close 03:30
  IST, reopen 04:30 IST). Every table is reported for both.
- **Window day:** an IST weekday Mon-Fri whose 06:00-08:00 window has at least 110 of 120 minutes.
- **Periods (fixed):**
  - XAUUSD design 2 Oct 2023 - 31 Mar 2026; folds F1 Oct 2023 - Mar 2024, F2 Apr - Sep 2024, F3 Oct 2024 - Mar 2025,
    F4 Apr - Sep 2025, F5 Oct 2025 - Mar 2026.
  - MCX design 5 Aug 2025 - 31 Mar 2026; folds Aug-Sep 2025, Oct-Nov 2025, Dec 2025-Jan 2026, Feb-Mar 2026.
  - **Holdout (locked, read once): 1 Apr 2026 - 30 Oct 2026 (XAUUSD), 1 Apr - 19 Oct 2026 (MCX).** Read only for
    variants that pass every design gate, guarded by `results/holdout.lock`.

## 3. Definitions

- 15-minute candles aligned to IST quarter hours, built from 1-minute BID bars. Window candles: 06:00, 06:15, ...,
  07:45 (8 candles). Window open = the 06:00 minute's open; window close = the 07:59 minute's close.
- **Window return** W = close / open - 1. Window range = its high - low.
- **US session move** U (day D-1 in New York): XAUUSD from 08:20 New York to 17:00 New York (the COMEX day session to
  the daily close), the last price at or before each time.
- **Prior-day trend** P: XAUUSD at 17:00 New York (D-1) against 17:00 New York (D-2).
- **ATR15** = the mean true range of the 14 completed 15-minute candles before the entry.
- **US release days:** CPI, NFP (08:30 ET) and FOMC (14:00 ET) from research/brain/mcx/events.py, extended back to
  Oct 2023 with the BLS / Fed dates (listed in r140.py). "Post-release window" = the window of the IST morning that
  follows the release's New York day.

## 4. The variants (12; every one counted in BH)

Direction learning (walk-forward): for A1, A2, A3 and A6 the sign "follow or fade" is not assumed. For each XAUUSD
fold k >= 2 it is the sign of the mean signed window outcome over all design days before fold k (expanding). F1 is
training only (no trades). The holdout uses the sign learned on the whole design period. All other variants have a
fixed direction stated below.

**Part A: XAUUSD (information only; Boss cannot trade spot gold through Zerodha).** 1 standard lot (100 oz).
Stop = 1.0 x ATR15 from the entry; profit lock: once the open profit reaches 1.0 x ATR15, exit if it falls back to 60%
of its peak; otherwise the time exit. Checked on 1-minute BID highs and lows; a minute that touches both the stop and
the lock level counts as the worse of the two. Entries and time exits at the minute's open.

| id | rule | entry / exit |
|---|---|---|
| A1 | first candle: the 06:00-06:15 candle's colour -> the rest of the window (WF follow/fade) | in 06:15, out 08:00 |
| A2 | US session: sign of U -> the window (WF follow/fade) | in 06:00, out 08:00 |
| A3 | prior day: sign of P -> the window (WF follow/fade) | in 06:00, out 08:00 |
| A4 | Asian-range BREAKOUT: first 15m candle between 08:00 and 11:00 that closes outside the 06:00-08:00 range; trade the break | in at that close, out 12:30 |
| A5 | Asian-range FADE: the same trigger, trade back into the range | in at that close, out 12:30 |
| A6 | post-release mornings only: sign of U -> the window (WF follow/fade) | in 06:00, out 08:00 |

**Part B: MCX GOLDM futures, 1 lot (10 x the quote).** Tradable by Boss. Direction = sign of W (the XAUUSD
06:00-08:00 return), unless stated. Entry at the 09:05 minute close. Stop = 0.30% of the entry; profit lock: once the
open profit reaches 0.20%, exit if it falls to 60% of its peak. Checked on minute closes; the fill is that close.
A session must open by 09:02; option-expiry days are traded (the future is not expiring).

| id | rule | exit |
|---|---|---|
| B1 | follow W | 10:00 |
| B2 | follow W | the session's end - 10 min (23:20 / 23:45) |
| B3 | follow W only when abs(W) >= the median abs(W) of all earlier window days (expanding, from Oct 2023) | session end - 10 min |
| B4 | follow W only when MCX's 09:00 gap UNDER-reacted: XAUUSD's % move from the previous MCX close to 09:00 minus the GOLDM gap (09:00 vs the previous session's last close) has W's sign; not on the first day after a contract roll | 14:00 |
| B5 | FADE W | 10:00 |
| B6 | Asian levels on MCX: the window's high and low scaled to GOLDM (x GOLDM(09:05) / XAUUSD(09:05)); the first GOLDM minute close from 09:06 to 12:00 that crosses out of that range (from inside), trade the break | 14:00 |

## 5. Costs

- **XAUUSD:** spread $0.25 an ounce plus $7 commission a 100-oz lot round trip = $32 a lot a round trip at 1x,
  $64 at 2x. No swap (every trade is intraday).
- **MCX GOLDM futures:** Zerodha charges (research/hunt/r94_mcx_advanced/common.py fut_leg_charges: brokerage 0.03%
  or Rs 20 an order, CTT 0.01% sell, MCX txn 0.0021%, SEBI, stamp 0.002% buy, GST 18%) plus slippage of half the
  spread per fill (max(1 tick, 1 bp of price) / 2; R53 / R94 rule), 1 lot. 2x = charges and slippage both doubled.

## 6. Gates (all on the design period)

1. Net > 0 at 1x and 2x costs.
2. Random-time twins: 1,000 twin sets; each twin trade uses the same entry clock time and the same exits on a random
   other eligible day of the same period, with a random direction (same share of longs as the rule). p = (1 + twin
   totals >= the rule's total) / 1,001. Gate p <= 0.05.
3. BH over all 12 variants on the twin p: q <= 0.10.
4. Enough trades: >= 100 (A1-A5), >= 50 (A6), >= 60 (B1-B6).
5. Walk-forward folds: >= 3 of the 4 traded folds positive (A1-A3, A6: F2-F5; A4, A5: at least 4 of F1-F5; B: 3 of 4).
6. MCX only: the worst drawdown above -Rs 40,000 and no calendar month below -Rs 13,000 (1 lot).
7. Holdout (only for a variant passing 1-6): net > 0 at 1x and 2x, and the MCX drawdown rule.

Every variant's win rate is reported against Boss's 65% rule (reported, not a gate).

## 7. Descriptive part (no gates)

What gold does 06:00-08:00 IST, design period, by DST regime: mean and median return, share up, mean absolute move,
range ($ and %), each 15-minute candle's green share and range, weekday table, post-release days vs others; the
correlations W vs U, W vs P, first candle vs the rest; W against MCX's 09:00 gap, 09:00-10:00, 09:05-close.
After the holdout step is final, the same tables are repeated on the full period for Boss (labelled).

## 8. Proposal rule

If any MCX variant passes every gate including the holdout, propose (not implement) a PAPER arm in the write-up. A
variant that fails stays a note.

## Amendment A (31 Oct 2026, before any Amendment A number): does the market that closed before 06:00 guess the direction?

Boss, 31 Oct: *"Check if there is any effect of that markets for guessing the direction."* "That market" = the US
session, which closes at 17:00 New York (02:30 IST in US summer, 03:30 IST in winter), and the hours after it until
06:00 IST (gold's reopen at 18:00 New York and the Sydney / Tokyo start).

What was already seen before this amendment: A2 (the US day session 08:20-17:00 New York -> the window) and A3 (the
prior day) in design (0 of 12 passed), and the descriptive full-period tables made after the holdout step, which
included corr(window, US day session) in the holdout (-0.05, 138 days). No other predictor below has been computed on
any period. The holdout stays locked for the new rules: read once, only for a rule passing every design gate.

**Predictors (all known at 06:00 IST; XAUUSD prices; "the US session" = the latest New York weekday whose 17:00 is
before 06:00 IST, as in s3):**

| id | predictor | from -> to |
|---|---|---|
| C1 | the last US hour into the close | 16:00 -> 17:00 New York |
| C2 | the US afternoon after the COMEX pit close | 13:30 -> 17:00 New York |
| C3 | after the US close to 06:00 IST (the reopen at 18:00 New York, Sydney and Tokyo starts) | 17:00 New York -> 06:00 IST |
| C4 | the last 30 minutes before the window (Tokyo's open at 05:30 IST) | 05:30 -> 06:00 IST |
| C5 | the whole night since MCX closed | MCX close (23:30 IST US summer / 23:55 IST winter, previous MCX weekday) -> 06:00 IST |
| C6 | a BIG US day only: the US day session (A2's U) when abs(U) >= the 80th percentile of abs(U) over all earlier window days (expanding) | 08:20 -> 17:00 New York |

**Rule for each (same as A2):** trade the 06:00-08:00 IST window in the direction of the predictor's sign, follow or
fade learned walk-forward (s4: sign of the mean signal x window return over all design days before the fold; F1
training only; the holdout uses the whole design period). XAUUSD 1 lot, stop 1 ATR15, 60% lock after +1 ATR15, in
06:00, out 08:00; $32 a round trip (2x $64).

**Gates:** as s6 for A-rules: net > 0 at 1x and 2x; twin p <= 0.05; **BH over 18 (R140's 12 + these 6)**, q <= 0.10;
>= 100 trades (C6: >= 50); >= 3 of the 4 traded folds positive. Win rate reported against the 65% rule.

**Descriptive (no gates):** for every predictor (C1-C6 plus A2's U and A3's P): the share of days whose window has the
same sign, the correlation, and the same split by US summer / winter; also each predictor's correlation with GOLDM
09:05-10:00 (MCX design days). US stock indices are not in the lake and Dukascopy is rate-limited from this container,
so US stocks are not tested; gold's own US session is the US market here.

## Amendment B (31 Oct 2026, before any Amendment B number): enhancements to raise the win rate

Boss, 31 Oct: *"Can we enhance it by adding some more parameters, strategies, indicators and more things which could
increase the win rate."* Already seen before this amendment: R140's 12 rules and Amendment A's 6 (all failed design;
no holdout read), and the descriptive tables (design and full period). No indicator, model or exit below has been
computed. A higher win rate alone is not a pass: every gate of s6 still applies, and **BH runs over all 30 R140 rules**
(12 + 6 + these 12). The holdout stays locked (read once, only for a rule passing every design gate).

**Signal source: XAUUSD spot 15-minute candles (the underlying), completed candles before 06:00 IST only**, plus
GOLDM's own minute closes for M1 / M2 (09:00-09:05). Indicators (standard settings, fixed, not tuned): EMA 20 and EMA
50 of closes; RSI 14 (Wilder); Bollinger 20, 2 sd; MACD 12/26/9 histogram; Supertrend 10, 3 (ATR 10).

**Part E: the XAUUSD window, in 06:00, out 08:00 IST (1 lot, $32 a round trip; 2x $64).**

| id | entry rule at 06:00 | exits |
|---|---|---|
| E1 | follow the EMA trend: sign(EMA20 - EMA50) | stop 1 ATR15, 60% lock after +1 ATR15 (A2's) |
| E2 | RSI extreme, revert: RSI < 30 buy, RSI > 70 sell, else no trade | A2's |
| E3 | Bollinger, revert: close below the lower band buy, above the upper band sell | A2's |
| E4 | follow the Supertrend's direction | A2's |
| E5 | vote: trade only when 4 agree: sign(EMA20 - EMA50), sign(RSI - 50), sign(MACD histogram), sign(the move from the US close to 06:00, C3) | A2's |
| E6 | walk-forward logistic model (sklearn, C = 1, standardised): U, P, C1-C5, RSI - 50, (EMA20 - EMA50) / ATR, MACD hist / ATR, Bollinger z, Supertrend direction, US summer flag. Label: the window's sign. Trained on design days before each fold (F1 training only; the holdout model on the whole design). Trade when P(up) >= 0.58 (buy) or <= 0.42 (sell) | A2's |
| E7 | E5's entries | **high win-rate exits:** target +0.5 ATR15, stop 1 ATR15, 60% lock after +0.3 ATR15, else 08:00 |
| E8 | E6's entries | E7's exits |

**Part M: MCX GOLDM futures, 1 lot, in 09:05 (costs as s5).**

| id | entry rule | exits |
|---|---|---|
| M1 | B1 + confirmation: trade W's sign only when the XAUUSD EMA trend (E1) has the same sign AND GOLDM's 09:00 -> 09:05 move has the same sign | B1's: stop 0.30%, 60% lock after +0.20%, out 10:00 |
| M2 | walk-forward logistic model on MCX days: W, the 09:00 gap, GOLDM 09:00 -> 09:05, U, P, C3, C5, RSI - 50, (EMA20 - EMA50) / ATR. Label: the sign of GOLDM 09:05 -> 10:00. Trained on design days before each MCX fold (fold 1 training only). Trade when P >= 0.58 / <= 0.42 | B1's |
| M3 | B1's entries (follow W) | **high win-rate exits:** target +0.15%, stop 0.30%, 60% lock after +0.10%, out 10:00 |
| M4 | M1's entries | M3's exits |

**Gates:** s6 unchanged (E-rules as A-rules: >= 100 trades, E2 / E3 / E5 / E7 >= 50; M-rules as B-rules: >= 60, M1 / M2
/ M4 >= 40, and the account stop). BH q <= 0.10 over 30. Twins use each rule's own exits. Every win rate is reported
against Boss's 65% rule.
