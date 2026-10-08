# h28 pre-registration: calendar / scheduled-event regularities for OPTION BUYING
(written 2026-10-08 BEFORE any h28 index statistic or option P&L was computed)

Question: do calendar or scheduled-event days carry a direction bias or a range expansion in Indian index moves large
enough that BUYING a 1-ITM nearest-expiry CE or PE (1 lot, fixed, Rs 1,00,000 capital) beats real costs? And does any
calendar flag, used as a FILTER, improve the app's Liquidity 15+5 trades? Buying only. No straddles (h-gc tested event
straddles; they lost). Known before writing: OBUY_GC (event straddles/post-event breakouts lost), OBUY_GA (time-of-day,
ORB lose), HUNT_H24 (real half-spreads), H17/H23/H24 Liquidity results incl. holdouts. No h28 numbers seen.

## Data and periods
- Daily index candles (Dhan IDX_I daily, 2006-01 .. ) for direction priors and the daily study.
- Index minutes (obuy `Index`, NIFTY 2020-08+, BN/FIN 2021-08+, MIDCP / SENSEX from their option eras) for the
  intraday timing study and option trades. Options: obuy `Options` (nearest expiry, ATM+-10, real minute bars).
- PRE = before 2025-10-01. HOLDOUT = 2025-10-01 .. latest (2026-10-05), used ONCE at the end, only for survivors.
- Weekend sessions (DR / Muhurat / special) and weekday Muhurat (Diwali) sessions are dropped, except Budget sessions.

## Calendar flags (42), built in `cal.py` from rules or public/recall lists, before any return was looked at
DOW: dow_mon .. dow_fri (5). MOY: moy_01 .. moy_12 (12).
Turn of month: tom_last2 (last 2 sessions of the month), tom_first3 (first 3), dom_01_10 (calendar day 1-10: salary /
SIP debit days) (3).
Holidays (exchange holidays = weekdays with no session in the data): pre_hol (next weekday is a holiday), post_hol (2).
Expiry (per index; option era: the data's expiry flag; before: last-Thursday monthly rule (+ weekly Thursdays NIFTY from
2019-02-11, BANKNIFTY from 2016-05-27), holiday -> previous session): exp_day, exp_week_m (sessions of the calendar week
holding the monthly expiry, up to it), post_exp (session after any expiry), roll3 (the 3 sessions before the monthly
expiry), post_mexp (first session after the monthly expiry) (5). Monthly expiry in the option era = last expiry day of
the calendar month.
Rebalancing (rules, approximate): msci (last session of Feb/May/Aug/Nov), ftse (third Friday of Mar/Jun/Sep/Dec, or
the session before), nse_rebal (last session of Mar/Sep) (3).
Macro: rbi (policy day; MPC dates 2016-10+), pre_rbi (session before), budget (speech day), election (counting /
results session, general + major state), fomc_next (session after a scheduled FOMC decision; Fed website), uscpi_next
(session after US CPI release; ALFRED vintage dates), incpi_next (session after India CPI: the 12th, or the next
weekday if the 12th is a weekend/holiday, released 17:30 IST), gdp_next (session after India GDP: last weekday of
Feb/May/Aug/Nov, 17:30 IST) (8).
Results (gc_common list, recalled, 2021-2025 only): res_next (session after RELIANCE/HDFCBANK/INFY/TCS results),
res_bank_next (HDFCBANK), res_it_next (INFY or TCS) (3).
Festival: diwali_wk (3 sessions before and 3 after Diwali) (1).

## Part A: index study (descriptive + priors). PRE only.
Daily (2006 .. 2025-09; MIDCP from 2022): per flag vs non-flag days: mean open->close %, mean gap %, mean
close->close %, % up days, mean (high-low)/open %, range ratio = (high-low) / 20-day mean range before the day; Welch t
and BH over all (flag x index x metric). Intraday (minute era, PRE): mean |move| per hour bucket (09:15-10:00, 10-11,
11-12, 12-13, 13-14, 14-15:30) in % and as a ratio to the same bucket on non-flag days, and the signed mean per bucket.

## Part B: option-buying trades (PRE for choice; holdout once)
Universe: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX. Every session in the option era, every entry time
T in {09:20, 10:15, 12:00, 14:00} (signal on the bar closing at T-1, fill at T's open), BOTH sides, 1-ITM nearest
expiry (`StrikeRule(1, 'near')`), expiry days allowed, app fills + app charges (`Costs('app')`), lot as of the date,
1 lot. Exit menu (fixed now, 6):
  E1 liq     -15% premium stop, 20-min time stop unless +5%, 15:10 square-off (the Liquidity arm's premium exits)
  E2 s15t30  -15% stop, +30% target, 15:10
  E3 s30t60  -30% stop, +60% target, 15:10
  E4 lock    -15% stop + profit-lock ladder (LADDER, R = 15% of entry), 15:10
  E5 t60     time exit after 60 minutes, no stop
  E6 close   hold to 15:10, no stop
Spread on top of the app fills: half-spread hs x (entry + exit) x qty, hs = NIFTY 0.16%, BANKNIFTY 0.16%, FINNIFTY
0.42%, MIDCPNIFTY 0.21%, SENSEX 0.20% (assumed; not measured). Stress = 1.5 x hs. GROSS = fills without charges or
spread.
Direction modes (2):
  prior  side for year Y = sign of the mean daily open->close return on flag days over 2006-01-01 .. Y-1 (own index;
         MIDCPNIFTY uses NIFTY's); no trade if < 8 flag days in that history (walk-forward, no look-ahead).
  mom    side = sign(index close at T-1 - day's open); only T in {10:15, 12:00, 14:00}.
Variant = flag x index x T x exit x mode = 42 x 5 x 7 x 6 = 8,820. All are counted. A variant with < 20 PRE trades
gets p = 1.
Per variant (PRE, net at 1x hs): trades, net, Rs/trade, PF, win %, net per year, max DD, net at 1.5x hs, gross.
Baselines (B = 2,000): (i) random direction: each trade's side replaced by a coin flip (the opposite-side trade at the
same time/exit is in the table); (ii) random day: each trade replaced by a random NON-flag session of the same index
and year at the same T/exit, with the side the mode would give there (prior: that year's flag side; mom: that day's
mom side). p = (1 + #{random mean/trade >= real}) / (B + 1).
Multiple testing: BH over all 8,820 on each p; White RC and Hansen SPA (obuy.overfit.spa, block 5) on the
(session x variant) daily net matrix of the variants with >= 20 PRE trades (0 on non-trade days).
Walk-forward: anchored; for test year Y in 2022, 2023, 2024, 2025(Jan-Sep): pick the variant with the highest net over
option-era years < Y (>= 20 trades in training); trade it in Y. Also the top-10 equal-weight pick. Report WF net per year.
SURVIVOR = PRE net > 0 at 1.5x hs AND BH q < 0.10 on BOTH baselines AND positive in >= 3 of the 4 years 2022..2025 AND
the SPA p < 0.10 for the variant set. Survivors (max 3, by PRE net) go to the holdout once.

## Part C: flags as a FILTER on Liquidity 15+5 (the app's arm, unchanged)
Trades: h24 `trades24.parquet` model flat_x1 (real spread), kappa 0.02, traded lots > 0, for BANKNIFTY and MIDCPNIFTY;
h23 `trades.parquet` net_0.02 (Roll spread) for NIFTY, FINNIFTY, SENSEX (info). Variants: 42 flags x {skip, only} x 5
indices = 420. Test: mean net/trade on flag days vs the rest, permutation of the flag label over sessions (B = 2,000),
BH over 420. A filter SURVIVES if BH q < 0.10, the filtered plan's PRE Rs/day beats the unfiltered by > Rs 20/day,
and it beats it in >= 3 of the PRE years (BN 2021-2025, MIDCP 2023-2025). Survivors: holdout once.

## Report
Rs/day at 1 lot (net / sessions of the index in the period), lots for Rs 5,000/day, capital (premium of that many lots)
vs Rs 1,00,000, max DD, worst month, P(losing month) by month bootstrap, honest variant count (8,820 + 420 + the Part A
tests). Calendars that could not be built are listed.
