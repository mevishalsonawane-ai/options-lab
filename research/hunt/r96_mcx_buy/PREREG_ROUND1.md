# R96 round 1 pre-registration: MCX commodity option BUYING (21 Oct 2026)

Boss, 21 Oct: "I want the best commodities strategy developed ... Run until we find something: a commodities options
BUYING strategy." Research only: nothing in `android/**` or `.github/**` changes. Written and committed **before any
statistic of any rule below was computed** (only data-shape checks were run: file dates, columns, strike spacing, the
share of minutes with an ATM print).

## 1. What we already know (not repeated)

FINDINGS.md; R42 (SILVERM morning, no edge on cleaned prices); R82 precious; R83 energy; R85 MCX candles (0 of 4);
MB1 (next-hour SIZE predictable, DIRECTION not; 0 of 16); R89 (straddles on the size forecast: 18 of 18 lost; options
price the move); R93 (Breadth Donchian MCX: gross ~0 vs Rs 100-520 of costs a trade); R94 (calendar spreads, pairs,
seasonals, COMEX gap: 0 pass; option selling out of scope). Internet scan: `INTERNET.md` (option buyers pay a variance
risk premium on average; edges must come from timing).

Lesson carried in: intraday moves of minutes are too small for MCX option costs. Round 1 therefore favours (a)
multi-day holds, where a 0.6-1.8% round-trip spread is small next to the move, (b) moments when the move should beat
the implied move (after compression, IV below a realised-vol forecast, big-volume sessions, scheduled events).

## 2. Periods (fixed for every round; never changed)

- Data: MCX option books 5 Aug 2025 - 19 Oct 2026 (near-month, ATM-3..+3, real 1-minute prints, IV, OI).
- **Design: 5 Aug 2025 - 14 Jul 2026.** Every trade of a design run is cut at the design end (exit at the last
  design session's square-off at the latest), so nothing after 14 Jul is seen.
- **LOCKED FINAL HOLDOUT: 15 Jul 2026 - 19 Oct 2026** (67 sessions). Never touched by any round. Read ONCE, only for a
  candidate that passed every design gate (s7), by `study.py holdout <rule>`, which writes a lock file and refuses a
  second read.
- Walk-forward folds inside design (rules are fixed in advance, nothing is tuned; each fold is an out-of-sample
  period for fixed rules; the HAR model in V1 is refitted only on data before each forecast):
  F1 5 Aug - 31 Oct 2025, F2 1 Nov 2025 - 31 Jan 2026, F3 1 Feb - 30 Apr 2026, F4 1 May - 14 Jul 2026.
- Long-history check of the underlying signal (directional daily rules only): daily continuous futures
  1 Jan 2012 - 31 Jul 2025 (before any option data).

## 3. Books, signal source, strikes, expiry

- Books (mini lots, the only ones whose stop risk can fit 1-2% of a small account): **CRUDEOILM** (x10), **NATGASMINI**
  (x250), **GOLDM** (x10), **SILVERM** (x5) near-month options on MCX.
- **Signal source of every rule: the MCX future** (SIGNAL ON THE UNDERLYING): intraday rules read the option book's
  near future (per-minute median of the option rows' `spot`, close-only, R53 `Market`); daily rules read the book's
  near-month daily continuous future (CRUDEOIL for CRUDEOILM, NATURALGAS for NATGASMINI, GOLDM, SILVERM), back-adjusted
  at causal open-interest rolls (R93 `daily_series`). ATM IV is used only as a filter (V1). No rule reads an option's
  own chart; stops and locks on the premium are allowed (CLAUDE.md).
- Strikes: directional rules buy the **1-ITM** strike (call ATM-1, put ATM+1) chosen from the future's close in the
  minute before entry; straddles buy the **ATM** call and put. Expiry: the near month (the lake has no other).
  No entry on the option expiry day or when fewer than 3 sessions remain before it; every position is out by the
  square-off (close - 15 min) of the session before the expiry day.
- Fill: the OPEN of the first printed minute of the chosen contract within 15 minutes after the entry time
  (straddles: both legs must fill), else no trade (counted). R42's F1 bad-print filter (R53 `Market`) applies.
- Marks: the held contract is priced on its own printed minutes. When its strike has left the ATM+/-3 window (the
  lake keeps only those), it is marked with Black-76 at the future's price, the time to expiry, and the IV of the
  nearest stored strike of the same side; an exit priced that way pays **2x the half-spread** and is counted
  ("model exits", reported per rule and book). An exit at a minute without a print uses the next print within 10
  minutes the same session, else the model mark when out of the window, else the last print (stale, counted).

## 4. Exits common to all rules (hard stop + profit lock, Boss's crypto-style safety)

- Single legs: **hard stop -40% of the premium**; **underlying stop** per rule (checked on future minute closes; out
  at the option's exit price that minute); **profit lock ladder** on the premium: peak +30% -> lock breakeven (fill +
  round-trip costs), +60% -> lock +30%, +100% -> lock +60%, and above +100% a trail at peak gain - 40 points of the
  fill (whichever lock is higher); **time exit** per rule.
- Multi-day straddles: the same on the two legs' combined value, with the hard stop at -30%.
- Event straddle (E1): stop -25%, ladder +15% -> breakeven, +30% -> +15%, +50% -> +30%, trail peak - 20 above +50%.
- Order inside a minute (conservative, R93): stop / lock check on the minute's low first, then the peak update.
- Sessions: MCX 09:00 - 23:30 (US summer time) / 23:55 (winter); square-off at close - 15. Multi-day holds keep the
  position overnight (Boss's paper arms trade MCX intraday today; overnight risk is part of what is measured).

## 5. Costs and size

- Zerodha MCX option charges (R53 `mcx_opt_charges`: Rs 40 brokerage, CTT 0.05% of the sale premium, exchange, SEBI,
  stamp, GST) + a half-spread on each fill: 0.30% of the premium from 17:00 IST / 0.60% before (NATGASMINI 0.45% /
  0.90%), as R85 / R93. Every gate again at **2x the half-spreads**.
- Size: **1 lot** per trade (straddle: 1 lot each leg). Reported: the stop risk per trade (premium stop distance x lot
  + costs) and the capital at which that risk is 2% (and 1%). Boss's account stop: -Rs 13k a month, -Rs 40k drawdown.

## 6. The nine rules (round 1). Each is ONE test in the BH family, pooled over its books.

Daily indicators are computed on the signal series up to and including the signal day's close (session end);
entry is the next MCX session at the first print at or after 09:15 unless said otherwise.

| id | name | signal on the future | trade | underlying stop | time exit | twin |
|---|---|---|---|---|---|---|
| **S1** | squeeze release, straddle | Bollinger(20, 2) inside Keltner(EMA20 +/- 1.5 x ATR20) on >= 5 of the previous 6 sessions and on the previous session; today it is not (the squeeze releases) | ATM straddle | none (straddle stop / lock) | 5 sessions | T-day |
| **S2** | squeeze release, direction | as S1; side = sign(close - SMA20) on the release day | 1-ITM call / put | 1.0 x ATR14 from the entry future | 7 sessions | T-day |
| **N1** | NR7 breakout | the signal day's range (high - low) is the smallest of the last 7 sessions; the next session, the first future minute close above that day's high (long) / below its low (short), 09:15 - 22:00, levels from the book's own minute closes of the NR7 day | 1-ITM in the break's direction, entry the first print within 15 min after the breaking minute | the NR7 day's opposite extreme | 3 sessions | T-tod |
| **W1** | wide-range continuation | range >= 1.8 x ATR14 (previous day's) and the close in the top 25% of the range (long) / bottom 25% (short) | 1-ITM | 1.0 x ATR14 | 5 sessions | T-day |
| **P1** | pullback in trend | 20-day and 60-day returns have the same sign (trend side); the last 2 closes both moved against it | 1-ITM on the trend side | 1.5 x ATR14 | 5 sessions | T-day |
| **V1** | IV below forecast (VRP < 0) | at 17:00 the ATM IV (median of call and put offset-0 IV, 16:45-17:00) < the HAR forecast of the next 5 sessions' realised vol (Garman-Klass daily, HAR on 1 / 5 / 22-day RV, OLS refitted daily on all earlier history from 2012; annualised x sqrt(252)) | ATM straddle, entry the first prints at or after 17:01 the same session | none | 5 sessions | T-day (17:01) |
| **E1** | event straddle | scheduled release: EIA crude (CRUDEOILM), EIA gas storage (NATGASMINI), US CPI and NFP (GOLDM, SILVERM); calendar from MB1 `events.py` | ATM straddle filled in [release - 20, release - 5) minutes | none | release + 45 min | T-event (random non-release session of the same month, same clock time) |
| **E2** | event follow | the future's move from the minute before the release to release + 15 (sign) | 1-ITM in that direction, filled in [release + 16, release + 31) | the future's price the minute before the release | square-off (close - 15) the same session | T-event (same clock and follow logic on a non-release session) |
| **U1** | US-session momentum | the future's move over the first half-hour of US cash trading (09:30-10:00 ET in IST), only when its size is >= 1.5 x the median of the same window over the previous 20 sessions (>= 10 needed) | 1-ITM in that direction, filled in [10:00 ET + 1, + 16) | the future's price at 09:30 ET | square-off the same session | T-tod (random session of the same month, same clock; side = that day's half-hour sign) |

Twins (500 sets, seed 96): each trade is replaced by the same trade on a random eligible session of the same book in
the same calendar month (not the trade's own day): T-day = the same side and entry clock; T-tod = the same clock and
side, with the underlying stop at the same distance in ATR14 units; T-event = as described. p = share of twin sets
whose total net >= the rule's total net. A twin draw that cannot fill is redrawn (up to 20 times, then dropped).

One position per rule and book at a time (a new signal while a position is open is skipped). Straddles count as one
trade.

## 7. Gates

Design (all must hold, pooled over the rule's books, 1x costs unless said):
1. >= 30 trades;
2. net > 0, and net > 0 at 2x the half-spreads;
3. twin p < 0.05 **and** BH q <= 0.10 across the **cumulative ledger of every idea tried in every R96 round**
   (round 1: m = 9);
4. net > 0 in >= 3 of the 4 walk-forward folds;
5. worst calendar month > -Rs 13,000 and max drawdown > -Rs 40,000 (1 lot);
6. net > 0 without the best 3 trades;
7. directional daily rules (S2, N1, W1, P1): the long-history underlying check (2012 - Jul 2025 daily futures, the
   same signal, next-open entry, the same ATR stop on closes and time exit, 0.10% round-trip cost) has a mean > 0.

A rule that passes 1-7 is read on the locked holdout ONCE: it passes if net > 0, >= 10 trades, twin p <= 0.10, worst
month > -Rs 13k and max drawdown > -Rs 40k. Only then an APP_SPEC for a PAPER-only arm (logged, undoable, behind a
switch, hard stop + lock); nothing goes live.

**Lead** (refine in round 2, not a pass): design net > 0 at 1x costs with >= 2 of 4 folds positive, or twin p < 0.10.

## 8. Reporting

Every number with its trade count. Per rule: trades, win rate, avg win / loss, gross, costs, net, at 2x spread,
by book, by fold, worst month, max drawdown, without the top 3, twin mean and p, BH q, model / stale exit shares, the
stop risk and the capital for 2%. The cumulative ledger `LEDGER.md` lists every idea of every round with its p and q.
Experiment entries `research/experiments/entries/r96-*.yaml` (one per idea, checked for id clashes).

## 9. Honest limits stated in advance

14.5 months of options (design 11.3 months); near month and ATM+/-3 only (metals strikes are 100 / 1,000 points
apart, so GOLDM / SILVERM multi-day trades will often be model-marked); no bid/ask (assumed spreads, stressed at 2x);
close-only minutes; earlier studies (R53, R83, R85, R89, R93, R94, MB1) already looked at parts of this window, so the
holdout is "unseen by R96", not unseen by everyone.
