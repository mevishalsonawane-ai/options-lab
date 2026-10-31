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

### Amendment B, part L (added 31 Oct 2026, still before any Amendment B number): stop-loss and profit-lock settings

Boss, 31 Oct, mid-amendment: *"Stoploss profit lock."* Every rule above already has a stop-loss and a profit lock.
Part L tests other fixed stop / lock settings on the two simplest entries. The lock is the same trailing kind: once
the open profit reaches the trigger, exit if it falls to "keep" x its peak. BH now runs over **36** (12 + 6 + 18).

| id | entries | stop | lock trigger | keep |
|---|---|---|---|---|
| L1 | E5's (XAUUSD vote), 06:00 -> 08:00 | 0.5 ATR15 | +0.5 ATR15 | 60% |
| L2 | E5's | 1.5 ATR15 | +0.5 ATR15 | 50% |
| L3 | E5's | 1.0 ATR15 | +0.5 ATR15 | 75% |
| L4 | B1's (GOLDM follows W), 09:05 -> 10:00 | 0.20% | +0.10% | 60% |
| L5 | B1's | 0.50% | +0.10% | 50% |
| L6 | B1's | 0.30% | +0.10% | 75% |

Gates as their parts (L1-L3 as E5, L4-L6 as B1).

## Amendment D (31 Oct 2026, before any Amendment D number): bigger profit per trade on US gold, and sizing for $1,000

Boss, 31 Oct: *"For 1000 dollar if we are getting 1-2 dollars then it is not worth it, enhance it at your best."* US
gold (XAUUSD) only; information only (not tradable via Zerodha).

What is already seen: every R140 result so far. That includes L1-L3 / E7 / E8 on 1 Sep - 30 Oct 2026
(`recent_look.lock`). These new rules build on E5's vote because L1 (E5's vote, tight stop, early lock) was the only
rule that beat its twins in design. **That choice is itself data-driven, so it is counted:** BH runs over **42**
(12 + 6 + 18 + 6). The design gates are those of the A-rules (s6). The holdout for these rules is **1 Apr - 31 Aug
2026**, still unseen for them, read once and only for a rule passing every design gate. Sep - Oct 2026 is reported
separately and labelled already seen.

**Entries at 06:00 IST, from the 15-minute candle completed by 06:00:** "vote 4/4" = E5 (EMA20 vs EMA50, RSI vs 50,
MACD histogram, and C3, the move from the US close to 06:00, all agree). "vote 3/4" = at least 3 of those 4 agree, in
the majority direction.

| id | entry | stop | lock | time exit (IST) | extra |
|---|---|---|---|---|---|
| G1 | vote 4/4 | 0.5 ATR15 | after +0.5 ATR15, keep 60% | **12:30** (let winners run) | |
| G2 | vote 4/4 | 0.5 ATR15 | after **+1.0** ATR15, keep 60% | 12:30 | |
| G3 | vote 4/4 | 0.5 ATR15 | after +0.5 ATR15, keep **50%** | **17:30** (into London) | |
| G4 | vote 4/4 | 0.5 ATR15 | after +0.5 ATR15, keep 60% | 08:00 (L1) | only when ATR15 at 06:00 >= the median of all earlier days (busy days) |
| G5 | **vote 3/4** | 0.5 ATR15 | after +0.5 ATR15, keep 60% | 08:00 (L1) | more trades |
| G6 | vote 3/4 | 0.5 ATR15 | after +0.5 ATR15, keep 60% | 12:30 | |

Costs as s5 ($0.25 spread + $7 a lot round trip, scaled to the size; 2x also). Twins use each rule's own exits and
time exit. Gates as A-rules (>= 100 trades; G1-G4 >= 50; folds: >= 4 of 5 positive).

**Sizing for a $1,000 account (reported for every rule, no gate; sizing never changes whether a rule has an edge):**
- (a) fixed 5x: $5,000 of gold a trade;
- (b) risk 1% of the current balance a trade: ounces = 1% x balance / the stop distance in $, capped at 20x;
- (c) risk 2%, same cap.
Reported: end balance, return, the worst drawdown in % and the worst month, for design, and for Sep - Oct 2026
(labelled seen).

## Amendment E (31 Oct 2026, before any Amendment E number): the whole day, hour by hour

Boss, 31 Oct: *"Check whole day then try again."* US gold (XAUUSD) only; information only (not tradable via Zerodha).
Already seen: every R140 result so far. The two rules carried to every hour are the best 06:00 rules, **L1** and
**G4**. That choice is data-driven, so every hour counts: **BH over 90** (the 42 R140 rules so far + 48 here).

**Entry hours:** every full hour of the IST day, 00:00 ... 23:00 IST (24 hours), on IST weekdays. In at that hour's
first minute, out at most **2 hours later**. A trade needs prices within 5 minutes of the entry and within 30 minutes
of the time exit, so hours inside or across gold's daily break (17:00-18:00 New York) and the weekend drop out by
themselves.

**Signals, from the 15-minute candle completed at the entry** (the same indicators and settings as Amendment B):
vote 4/4 = sign(EMA20 - EMA50), sign(RSI14 - 50), sign(MACD histogram) and sign(the move from the latest 17:00 New
York close before the entry to the entry) all agree. At 06:00 IST this is exactly E5.

| id | rule at hour H | stop | lock | out |
|---|---|---|---|---|
| H{HH}a (24) | vote 4/4 (= L1 at H) | 0.5 ATR15 | after +0.5 ATR15, keep 60% | H + 2 h |
| H{HH}b (24) | vote 4/4 on busy days only: ATR15 at H >= the median ATR15 at the same hour on all earlier days (= G4 at H) | 0.5 ATR15 | as a | H + 2 h |

**Costs:** $0.25 spread + $7 a lot round trip ($32 a 100-oz lot); 2x also. The spread is kept the same at every hour,
which flatters the quiet hours; this is said in the write-up.

**Gates (each of the 48), on design 2 Oct 2023 - 31 Mar 2026:** net > 0 at 1x and 2x; twins at the same hour with
the same exits, p <= 0.05; BH q <= 0.10 over 90; >= 50 trades; >= 4 of the 5 folds positive. **Holdout:** 1 Apr - 31
Aug 2026, read once, only for a rule passing every design gate. Sep - Oct 2026 is reported separately (already seen
for the 06:00 hour).

**Descriptive (no gates), design, by hour and by US summer / winter:** the median 2-hour range and move, the share of
up moves, and the share of days on which the vote fires.

**Sizing:** for every rule that passes design, $1,000 at fixed 5x and at risk 1% / 2% (as Amendment D).

## Amendment F (31 Oct 2026, before any Amendment F number): Boss's "5-Step Liquidity Sweep POI" Pine script on US gold

Boss pasted a Pine v6 indicator (31 Oct). It is ported as written and backtested on XAUUSD (information only; not
tradable via Zerodha). **Signal source: XAUUSD spot candles (the underlying).**

**Port (the script's own defaults):**
- chart candles of 15 minutes (variants: 5 minutes). The HTF trend is the 60-minute close vs the 60-minute EMA 50,
  from the last COMPLETED 60-minute candle (request.security with lookahead_off on history);
- swings are pivots of length 3: the candle 3 bars back is a pivot high when its high is above the 3 highs on each
  side (strictly). It is known 3 bars later, and then it becomes lastSwingHigh. Pivot lows are the mirror;
- ATR 14 (Wilder); the POI zone is the last swing +/- 0.5 ATR;
- BUY on a candle's close when: HTF bullish; the candle overlaps the demand zone; the sweep (low < lastSwingLow and
  close > lastSwingLow); the confirmation (green, close > previous high); and RR >= 1.5. RR uses: SL = that candle's
  low, TP = lastSwingHigh. SELL is the mirror;
- entry at the next 1-minute open after the signal candle closes; SL and TP checked on 1-minute highs and lows (both in
  one minute = the SL). One trade at a time. The script has no time exit, so a **12-hour cap** is added (out at the
  open of the first minute after 12 h).

| id | chart | exits |
|---|---|---|
| F1 | 15m | the script's SL / TP (and the 12 h cap) |
| F2 | 15m | F1 + a profit lock: once the open profit reaches 1R, exit if it falls to 60% of its peak (house rule: every trade has a stop and a lock) |
| F3 | 5m | as F1 |
| F4 | 5m | as F2 |
| F5 | 15m | F2, only signals whose entry falls 06:00-08:00 IST (Boss's original window) |

**Costs:** $0.25 spread + $7 a lot round trip ($32 a 100-oz lot); 2x also. **Periods:** design 2 Oct 2023 - 31 Mar 2026
(5 folds as s2). **Holdout 1 Apr - 30 Oct 2026, never read for these rules**, read once, only for a rule passing
every design gate. **Twins:** for each trade, the same clock time on a random other day, a random side (same share of
longs), and the same SL and TP distances in $. **Gates:** net > 0 at 1x and 2x; twin p <= 0.05; **BH over 95** (90 +
5); >= 100 trades (F5 >= 30); >= 4 of 5 folds positive. Win rate against Boss's 65% rule. $1,000 sizing (5x, risk
1% / 2% of the balance on the SL distance, capped at 20x) reported for every rule.

## Amendment G (31 Oct 2026, before any Amendment G number): Boss's second setup, the internal-origin-low sweep after a BOS

Boss, 31 Oct, sent a diagram and this description (the Pine v5 code he mentioned did not arrive, so the rule is built
from the description): *"Uptrend: higher highs and higher lows. Bullish BOS: price breaks above the previous swing
high. Internal origin low: the exact higher low that produced the impulse move up to break structure. The trigger:
price pulls back from the high, dips briefly below the key line (sweeping the low), then recovers back above it.
Options for a strict sweep (wick below and close back above) or a simple structural break."* US gold (XAUUSD),
information only. **Signal source: XAUUSD spot candles (the underlying).**

**The rule (BUY; SELL is the exact mirror), on chart candles, swings = pivots of length 3 as in Amendment F:**
1. **BOS:** a candle closes above the last confirmed swing high SH.
2. **Origin low OL:** the lowest low from SH's candle to the BOS candle (the higher low that launched the break).
3. **Impulse high IH:** the highest high from the BOS candle on (updated while waiting).
4. **Trigger, within 30 candles of the BOS:**
   - **strict:** a candle's low < OL and its close > OL (wick below, close back above);
   - **simple:** a candle closes back above OL within 3 candles after a candle that traded below OL.
   - The setup is cancelled by a new BOS (a new setup starts), by 30 candles passing, or (strict) by a close below OL
     that is not recovered on that candle. Simple allows that one dip and the recovery within 3 candles.
5. **Entry** at the next minute's open. **SL** = the lowest low since price first went below OL. **TP** = IH. The trade
   needs RR >= 1.5 at the signal close, as in Boss's first script. **Profit lock** (house rule): once the open profit
   reaches 1R, exit if it falls to 60% of its peak. 12-hour cap. One trade at a time.

| id | chart | trigger | extra |
|---|---|---|---|
| K1 | 15m | strict | |
| K2 | 15m | simple | |
| K3 | 5m | strict | |
| K4 | 15m | strict | only with the 60-minute trend agreeing (close vs EMA 50, as Amendment F) |

Costs, periods (design Oct 2023 - Mar 2026; **holdout 1 Apr - 30 Oct 2026, never read for these rules**), twins (the
same clock time, a random day and side, the same $ SL / TP), gates (>= 100 trades; >= 4 of 5 folds) and $1,000 sizing
as Amendment F. **BH over 99** (95 + 4).

## Amendment H (31 Oct 2026, before any Amendment H number): Boss's refined Pine "5-Step Liquidity Sweep POI [Refined]"

Boss pasted the refined Pine v6 script (31 Oct). It is ported as written onto XAUUSD (information only). **Signal
source: XAUUSD spot candles.** The differences from Amendment F, all taken from the script:
- **HTF trend:** request.security(..., close[1] / ta.ema(close, 50)[1], lookahead_off). On history this is the 60-minute
  candle before the last completed one. If useHTF is off, both sides are allowed.
- **Swings:** pivot-3 as before. A new pivot high makes the previous active high the "target high"; the same for lows.
- **POI and sweep** on the active high / low, +/- 0.5 ATR; confirmation candle as before.
- **TP (buy):** the target high if it is above the close; else the active high if above the close; else close + 3 ATR.
  The mirror for sells.
- **SL (buy):** min(the candle's low, active low - 0.25 ATR). The mirror for sells. RR >= 1.5.
- Entry at the next minute's open; SL / TP on 1-minute bars (both in one minute = SL); one trade at a time; 12-hour cap.

| id | chart | HTF filter | exits |
|---|---|---|---|
| R1 | 15m | on | the script's SL / TP |
| R2 | 15m | on | + 60% lock after 1R (house rule) |
| R3 | 5m | on | the script's SL / TP |
| R4 | 5m | on | + lock |
| R5 | 15m | **off** (the script's input) | + lock |

Costs, periods (design Oct 2023 - Mar 2026; **holdout 1 Apr - 30 Oct 2026, never read for these rules**), twins,
gates (>= 100 trades; >= 4 of 5 folds) and $1,000 sizing as Amendment F. **BH over 104** (99 + 5).

## Amendment I (31 Oct 2026, before any Amendment I number): break and retest (Boss's screenshot)

Boss sent a TradingView screenshot (31 Oct): XAUUSD 15m, a "BREAK" label, then "SELL RETEST, TP 3984.34, SL 4002.72"
on a zone about $6 wide that price broke and came back to. That is one good trade. The rule below is the plain
break-and-retest it shows, built from the picture (no code was sent), tested on 2.5 years. US gold; information only.
**Signal source: XAUUSD spot candles.**

**SELL (BUY is the exact mirror), chart candles, pivot-3 swings as Amendment F, ATR 14 (Wilder):**
1. **Level:** the last confirmed swing low S. **Zone:** S - 0.25 ATR to S + 0.25 ATR (about $5-6 at 15m, as in the
   screenshot).
2. **Break:** a candle closes below the zone's bottom.
3. **Retest, at least 2 candles after the break and within 30:** a candle's high reaches the zone (high >= the
   zone's bottom) and it closes back below S. Sell at that close.
4. **Cancel:** a close above the zone's top before the retest (the level is reclaimed), or 30 candles without one.
5. **SL** = the zone's top + 0.1 ATR. **TP** = 2R (the screenshot's TP is about 2.2R).

| id | chart | exits | extra |
|---|---|---|---|
| T1 | 15m | SL / TP 2R (as the indicator shows) | |
| T2 | 15m | + 60% lock after 1R (house rule) | |
| T3 | 5m | + lock | |
| T4 | 15m | + lock | only with the 60-minute trend agreeing (Amendment F's HTF) |

Entry at the next minute's open, 1-minute SL / TP checks (both in one minute = SL), one trade at a time, 12-hour cap.
Costs, periods (design Oct 2023 - Mar 2026; **holdout 1 Apr - 30 Oct 2026, never read for these**), twins, gates (>= 100
trades; >= 4 of 5 folds) and $1,000 sizing as Amendment F. **BH over 108** (104 + 4).

## Amendment J (31 Oct 2026, before any Amendment J number): Boss's "Internal SMC BOS & Sweep Signals (HL/LH)" (Pine v5)

Boss pasted the Pine v5 script (31 Oct). It is ported as written onto XAUUSD (information only). **Signal source: XAUUSD
spot candles.** The port follows the script:
- **Swings:** pivots of length 3 (strict). lastHi / lastLo are set when a pivot is confirmed, and set to na after a
  break.
- **Breaks:** with confirmClose (the default), breakHigh = crossover(close, lastHi), using the previous bar's values
  of close and lastHi (Pine's ta.crossover; na on the previous bar = no cross). With confirmClose off: crossover(high,
  lastHi). breakLow is the mirror.
- **Trend:** the first break against the trend is a silent CHoCH. It flips marketTrend and cancels the opposite
  pending setup. A break with the trend is a BOS: the origin HL = lastLo at that moment (lastHi for a bearish BOS), and
  the setup is armed. The bullish block runs before the bearish block, as in the script.
- **Signal (BUY):** an armed setup and a candle with low < HL and close > HL. A close < HL disarms it. SELL is the
  mirror. No time limit (as in the script).
- **Exits (the script has none; fixed here):** entry at the next minute's open. **SL** = the sweep candle's low
  (high for sells). **TP** = 2R. 12-hour cap. One trade at a time. Both SL and TP in one minute = SL.

| id | chart | BOS on | exits | extra |
|---|---|---|---|---|
| J1 | 15m | close | SL / TP 2R | |
| J2 | 15m | close | + 60% lock after 1R (house rule) | |
| J3 | 15m | wick (confirmClose off) | + lock | |
| J4 | 5m | close | + lock | |
| J5 | 15m | close | + lock | only with the 60-minute trend agreeing (Amendment F's HTF) |

Costs, periods (design Oct 2023 - Mar 2026; **holdout 1 Apr - 30 Oct 2026, never read for these**), twins, gates (>= 100
trades; >= 4 of 5 folds) and $1,000 sizing as Amendment F. **BH over 113** (108 + 5).

## Amendment K (31 Oct 2026, before any Amendment K number): Boss's indicators in 06:00-08:00 and 16:00-18:00 IST only

Boss, 31 Oct: *"Try 6 to 8 am IST and 4 to 6 pm IST only."* Boss's five setups, each in its 15-minute version with the
60% lock after 1R, are kept only when the signal candle closes inside the window (entry 06:00-07:59 or 16:00-17:59
IST). These are the setups: the first Pine (F2), the refined Pine (R2), the origin-low sweep (K1), break and retest
(T2) and the Internal SMC HL/LH sweep (J2). Everything else (exits, 12-hour cap, one trade at a time, costs, twins,
$1,000 sizing) is as in their amendments. The design / holdout periods are as before (holdout 1 Apr - 30 Oct 2026,
never read for these). 16:00-18:00 IST is the London afternoon, before New York.

| id | setup | window |
|---|---|---|
| W1 / W2 | first Pine (F2) | 06-08 / 16-18 |
| W3 / W4 | refined Pine (R2) | 06-08 / 16-18 |
| W5 / W6 | origin-low sweep (K1) | 06-08 / 16-18 |
| W7 / W8 | break and retest (T2) | 06-08 / 16-18 |
| W9 / W10 | Internal SMC sweep (J2) | 06-08 / 16-18 |

Gates: net > 0 at 1x and 2x; twin p <= 0.05; **BH over 123** (113 + 10); **>= 30 trades** (the windows are short);
>= 4 of 5 folds positive. Win rate against the 65% rule. W1 repeats Amendment F's F5 (the same rule); it is counted
again, not dropped.

## Amendment L (31 Oct 2026, before any Amendment L number): enhancing Boss's scripts for more profit and win rate

Boss, 31 Oct: *"Can we enhance the scripts to get more profit and more win rate."* US gold; information only.
**Signal source: XAUUSD spot candles.**

**Base = Boss's signals combined (15m):** every signal of his five setups, as ported in Amendments F-J (the first Pine,
the refined Pine, the origin-low sweep (strict), break and retest, the Internal SMC sweep; the First-Bar version is
the same as J). They are merged in time order, with duplicate (time, side) signals once. One trade at a time; each
signal keeps its script's SL / TP; 12-hour cap; 60% lock after 1R (house rule).

**Enhancements (from what R140 found; that choice is data-driven, so all of them count in BH):**
- **vote:** trade only when the 4-signal vote of H11b (sign of EMA20 - EMA50, RSI14 - 50, the MACD histogram, and the
  move from the last 17:00 New York close to the signal) all point the trade's way. Read from the 15m candle the
  signal closes on;
- **busy:** trade only when that candle's ATR14 >= the median ATR14 of all earlier 15m candles in the same IST hour;
- **high win-rate exits:** TP at 1R (instead of the script's) and the lock after +0.5R keeping 60%;
- **reverse:** take the opposite side (bet that the sweep / retest FAILS and price runs on). Same entry; SL at the
  script's risk distance on the other side; TP at 2R; 60% lock after 1R;
- **11-13:** keep only signals that close 11:00-12:59 IST (H11b's hours).

| id | base | enhancements |
|---|---|---|
| L1 | combined | none (base exits + lock) |
| L2 | combined | vote |
| L3 | combined | busy |
| L4 | combined | vote + busy |
| L5 | combined | vote + busy + high win-rate exits |
| L6 | combined | reverse |
| L7 | combined | reverse + vote + busy (the vote read in the reversed side's direction) |
| L8 | combined | vote + busy + 11-13 |

Costs, periods (design Oct 2023 - Mar 2026; **holdout 1 Apr - 30 Oct 2026, never read for these**), twins (the same
clock time, a random day and side, the same $ SL / TP and exits), $1,000 sizing as Amendment F. Gates: net > 0 at 1x
and 2x; twin p <= 0.05; **BH over 131** (123 + 8); >= 50 trades; >= 4 of 5 folds positive. Win rate against the 65%
rule.
