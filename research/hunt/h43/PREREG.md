# h43 pre-registration (written before any part-B P&L)

Subject: the 5 "positive" BANKNIFTY ATM option-buying strategies of an outside bake-off report (25 sessions,
2026-08-31 .. 2026-10-07, Rs 95/lot round trip). Rules are taken from the report and are NOT tuned here.

## Note on part A
Part A (reproduction on the report's own window) uses data from 2026-08-31 to 2026-10-05, which sits inside our locked
holdout. Nothing is chosen from it: every rule is fixed by the report, and the ambiguities below were resolved before
any P&L was seen. Part A is a reproduction check only.

## Fixed rules (all strategies)
- BANKNIFTY spot 1-min data; bars of tf minutes anchored at 09:15. Indicators on the continuous bar series (all
  sessions back to back, carried over night). EMA = pandas ewm(span, adjust=False). ADX(14) = Wilder (ewm alpha 1/14).
  Bollinger(20, 2) = rolling mean +- 2 x population std of closes.
- Signal on a bar's close; buy the ATM (nearest 100 strike to the spot close at the signal) option of the nearest
  expiry (weekly while it existed, else monthly) at the next minute's open (= next bar open).
- Exits: 30% premium stop, 60% premium target, checked on the option's 1-min HIGH / LOW, stop first when both are hit
  in one minute. Time stops measured from the entry minute. Square-off 15:15. Expiry days skipped.
- One position at a time; max trades a day as the report says. Entries need a signal at or before 15:13.
- Lot: today's BANKNIFTY lot (30) for all history, so every year is in today's rupees.
- Costs, two columns always: THEIRS = Rs 95 per lot round trip (part B: on the app-fill gross plus the app slippage
  added back, i.e. about raw prints); OURS = app fills (+-5 bps, stops -10 bps) + app charges (Costs('app'), today's
  rates incl. STT 0.15%) + real half-spread 0.16% of premium on entry and on exit (h24). Gross = app fills, no charges.

| # | strategy | signal | side | max/day | exits |
|---|---|---|---|---|---|
| 1 | ema | EMA9 crosses EMA21 on the bar close and ADX(14) > 20 | up CE / down PE | 2 | 30/60, 40-min time stop |
| 2 | bb | close above upper / below lower BB(20,2); any such close while flat (state) | CE / PE | 2 | 30/60, 15-min time stop |
| 3 | orb15 | range = 09:15-09:29 high/low; first bar starting >= 09:30 that closes beyond | CE / PE | 1 | 30/60, 40-min time stop |
| 4 | pe | bar closing at/after 14:00 with 15 <= ADX(14) <= 25 (state) | PE only | 2 | 30/60, 30-min time stop |
| 5 | orb30 | range 09:15-09:44; first bar starting >= 09:45 that closes beyond | CE / PE | 1 | 30/60 and 30/120, no time stop |

Timeframes for each: 1, 5 and 15 minutes. That is 15 + 3 (orb30 with 120% target) = 18 variants tested here. The
report's headline numbers are the 5-min versions of 1, 3, 4, 5 and the 1-min version of 2.

## Part B (pre-holdout: 2021-08-04 .. 2025-09-30; BANKNIFTY options start Aug 2021)
- Per year P&L, gross / net-theirs / net-ours. Rules are fixed, so walk-forward = the per-year results of the fixed rule.
- Random baseline, time-of-day matched: for each real trade, draws of the same entry minute and same side on a
  random non-expiry day of the same calendar year, identical exits, B = 2000 (raised from 200 before any P&L: 200 cannot reach BH significance at m = 40). p = share of draws whose mean net per trade
  >= the strategy's (one-sided). Done on net-ours.
- Multiple testing: BH over m = 40 (the report's 40 strategy x timeframe combos are the search the winners came from;
  untested ones counted as p = 1, which only makes BH stricter) on the random-baseline p of the 18 variants.
  White RC / Hansen SPA (stationary bootstrap, block 5 days, B = 2000) over the 18 variants' daily net-ours P&L vs not
  trading. Also a plain t-test of mean net per trade vs 0.
- Verdict rule (per strategy, headline variant = the report's timeframe): REAL EDGE only if pre-holdout net-ours > 0,
  > half the years positive, BH q < 0.05 vs random, and then holdout net-ours > 0. Otherwise "25-day luck".

## Holdout (2025-10-01 .. data end 2026-10-05), run once after part B is written down
Same 18 variants, same costs. Report Rs/day at 1 lot (per non-expiry trading day), max drawdown, % green months,
worst month, and lots needed for Rs 5,000/day vs lots Rs 1 lakh can carry (1 lakh / (ATM premium x 30)).

## Amendment 1 (after part A, before any part-B P&L)
Part A could only reproduce orb30's flat result (+Rs 912, "target never hit") if the report's orb30 also used the
40-minute time stop of orb15: with no time stop our orb30 5-min loses Rs -17k on the window, with a 40-min stop it makes
+Rs 4k. So orb30 is ALSO tested with 30/60 + 40-min time stop at 1/5/15 min. Total variants tested: 21 (BH still over
m = max(40, 21) = 40, SPA over the 21).

## Part D: ENHANCEMENT search (declared after part-B pre-holdout results, BEFORE the part-B holdout run and before any part-D P&L)
Boss asked to "enhance the search and the strategy to the best". Done as a nested walk-forward search, per family.

**Families and signal grids** (all indicators on the continuous index bar series, bars anchored at 09:15):
- EMA cross (+ADX): EMA pairs (5,13) (5,21) (9,21) (9,34) (13,34) x ADX(14) threshold none/15/20/25/30 x tf 3/5/10/15
  = 100 signal sets.
- Bollinger breakout: length 14/20/30 x width 1.5/2/2.5 x tf 1/3/5/10/15 = 45.
- ORB: window 15/30/45/60 min x confirmation close tf 1/3/5/10/15 = 20 (first breakout of the day only).
- Afternoon PE drift: start 13:00/13:30/14:00/14:30 x ADX band 10-20/15-25/20-30/any x tf 3/5/10/15 = 64 (PE only).

**Entry filters** (11, each applied alone, info up to the signal minute only): F0 none; F1 VIX low / F2 VIX high
(previous day India VIX close vs its trailing 250-day median); F3 calm / F4 wide day so far (range since 09:15 /
average daily range of the previous 20 days < / >= 0.5); F5 beyond the prior-day high (CE) / low (PE) in the trade
direction; F6 inside the prior-day range; F7 higher-timeframe trend aligned (side x (close - EMA(1-min, span 300)) > 0);
F8 OI build-up aligned (change since 09:15 of total PE OI minus total CE OI over the nearest-expiry ATM+-10 chain has
the trade's sign); F9 morning only (signal before 11:30); F10 afternoon only (11:30 or later).

**Strike**: ATM / 1-ITM / 1-OTM, nearest expiry. **Exits** (10, all on the option's 1-min HIGH/LOW, stop first on ties,
square-off 15:15): 30%/60% with time stop 15/30/40/60 min or none; premium points +15/-10, +20/-15, +30/-20;
profit-lock ladder (-15% stop, ladder on R = 15%, +30% target); trailing (-30% stop, 15% trail once +10%).
**Max trades a day**: 1 / 2 / 4. **Indices**: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (data ranges as available).
One position at a time per variant. Today's lot per index. Costs: app fills + app charges + real half-spread
(BN/NIFTY/SENSEX 0.16%, MIDCP 0.21%, FIN 0.42%) on entry and exit.

Variant count = 229 signal sets x 11 filters x 10 exits x 3 max/day x 3 strikes x 5 indices (minus impossible
combinations) ~ 1.1 million; every one counts in White RC / Hansen SPA_c (pre-holdout daily net P&L on the common
calendar, stationary bootstrap block 5, B = 500, chunked).

**Nested walk-forward** per family: for each test year Y in 2022, 2023, 2024, 2025 (Jan-Sep), choose the variant
with the highest net (ours) over all pre-holdout data before Y (at least 30 training trades), trade it in Y. Report
per-year OOS P&L next to the in-sample best's P&L, so the in-sample fitting is visible.
**Enhanced version for the holdout** = the variant with the highest pre-holdout net in each family (same rule, all
pre-holdout data). The locked holdout (2025-10-01 .. data end) is run ONCE for those 5. Holdout sums for the whole grid
are stored sealed and are not used for any choice.

## Amendment 2 (Boss's companion parameter document; written before any history P&L of these items)
The document gives each combo's time stop in BARS, so the report's non-headline timeframes used different minute
time stops than I assumed (I had used the headline variant's minutes for every timeframe). Added to part B, exactly as
the document lists them: ema 15m T60 (4 bars), bb 5m T30 (6 bars), bb 15m T45 (3 bars), orb15 15m T60 (4 bars),
pe 15m T45 (3 bars), orb30 1m T20 (20 bars). It also confirms orb30 5m used an 8-bar = 40-min time stop (amendment 1).
**CHAMPION** added: 5-min spot, EMA(8) x EMA(21) cross on the bar close with ADX(14, Wilder) > 15 -> ATM CE / PE at
the next minute open; 25% stop, 50% target, profit lock: once the peak (1-min highs) exceeds +25 / +50 / +75 / +100% of
the entry, the stop moves to that level (engine ladder R = 25% of entry, rungs (1,1) (2,2) (3,3) (4,4)); time stop
60 min; square-off 15:15; max 2/day; expiry days skipped. Tested on BOTH the nearest expiry (weekly while it existed)
and the nearest MONTHLY (the document's instrument). Same costs, same random baseline (B = 2000), and the holdout
reported both including and excluding 2026-08-31 .. 2026-10-07 (the document's tuning window lies inside our holdout).
Multiple testing: BH over m = 41 (the document's 41 combos; the champion's own tuning grid is not disclosed, so the true
search is larger and m = 41 is generous to it). Part B now tests 29 variants; SPA over all 29. Part B pre and holdout are
re-run with these additions; the 21 earlier variants are unchanged (same data, same code).
Part D grid adds the EMA pair (8, 21), the CHAMP exit, and ATM MONTHLY as a 4th strike / series choice.
