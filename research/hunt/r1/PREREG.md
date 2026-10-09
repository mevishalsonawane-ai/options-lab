# R1 pre-registration: internet option-buying ideas not tested before (written 9 Oct 2026, before any R1 P&L)

Frame (fixed): option BUYING only, Rs 1,00,000, 1 lot fixed (NIFTY 65, BANKNIFTY 30 = today's lots), 1-ITM strike of
the nearest expiry (NIFTY weekly; BANKNIFTY weekly to Nov 2024, then monthly), decided on the CLOSE of index minute s,
bought at the OPEN of minute s+1 (first printed bar within 3 minutes, volume > 0, else no trade).
Exits are checked on the option's 1-minute high/low (premium stop / target first-passage; stop counted first when both
hit in one bar; fill = min(level, bar open) for stops, max(level, bar open) for targets), index-based exits leave at the
next minute's open, everything is out at 15:10 (column 355).
Costs: app charges at today's rates (obuy.costs 'app') + h24 real half-spread 0.16% a side (NIFTY and BANKNIFTY); the
app's own +-5 bps fill is NOT stacked on the spread (X3 issue #2): each side pays max(half-spread, 5 bps); stop exits
pay 5 bps more.

Periods: DESIGN = everything before 1 Oct 2025 (NIFTY from Aug 2020, BANKNIFTY from 2021; breadth from Oct 2024; VIX
minute from 2022). HOLDOUT = 1 Oct 2025 - 6 Oct 2026, opened ONCE by `holdout.py` after `design.py` has written
`frozen.json`.

## Ideas and every variant (all counted in the correction)

N1 NOISE (Barbon, Aziz, Zarattini 2024): sigma(t) = mean |close(t)/open - 1| over the previous 14 sessions at the same
   minute; upper = max(open, prev close)(1 + m sigma), lower = min(open, prev close)(1 - m sigma). Checks at
   10:00, 10:30 ... 14:30 closes; first close outside -> CE (above) / PE (below), one trade a day.
   m in {1.0, 1.5}; exit in {trail30 = at a half-hour check the close is back inside max(band, TWAP) (CE) / min(band,
   TWAP) (PE); trail1 = same check every minute; EOD; LIQ}.  8 variants / index.
N2 WVB (Larry Williams volatility breakout): up = open + k (prev high - prev low), down = open - k range; first 1-min
   close beyond (09:16 - 14:30) -> CE / PE. k in {0.25, 0.5, 0.75}; exit in {native = index back to the open or EOD,
   EOD, LIQ}. 9.
N3 HKS (Heston, Korajczyk, Sadka 2010 intraday periodicity): 30-min slots from 09:15; at each slot start, mean and t of
   the same slot's index return over the last L sessions; trade the sign if |t| > thr, exit at the slot end.
   L in {20, 40}; thr in {0, 1}. 4.
N4 ROD (Baltussen et al. 2021 / Gao et al. 2018 SLH): decision at 14:40 (or 14:30) close; r = return from prev close
   (or from today's open) to the decision; |r| > thr -> buy in r's direction, exit 15:10. decision {14:30, 14:40} x
   base {prev close, open} x thr {0, 0.5%} = 8, plus ROD 14:40 / prev close / thr 0 on EXPIRY days only = 9.
N5 NOON (Bhat, Pandey, Rao 2024 day/night and pre/post-noon asymmetry in NIFTY options): buy at 12:00 or 13:00 and hold
   to 15:10; leg in {CE, PE, straddle (both)}. 6.
N6 FVG (ICT fair value gap, "first FVG of the day"): 5-min bars from 09:15; first 3-bar gap formed with bar 3 closing at
   or after T0 in {09:30, 10:00}, gap size >= g in {0, 0.05%} of price; then the first 1-min bar that trades into the
   gap (before 14:30) -> buy in the gap's direction. Index stop beyond bar 1's far end; exit in {native = stop or 2R
   index target, else EOD; EOD (with the index stop); LIQ}. 12.
N7 TURTLE (Connors/Raschke turtle soup on the previous day's low/high): after 09:30 the index trades below PDL and a
   bar of size B in {5, 15} min closes back above PDL -> CE (mirror at PDH -> PE), first setup only, before 14:30.
   Index stop = session extreme; exit in {native = stop or 2R, EOD with stop, LIQ}. 6.
N8 IBS (Pagonidis internal bar strength): yesterday's IBS = (C - L)/(H - L) < lo -> CE at 09:16, > hi -> PE.
   (lo, hi) in {(0.2, 0.8), (0.1, 0.9)}; exit in {EOD, 60 min, LIQ}. 6.
N9 VIXSPK (VIX spike -> buy calls): yesterday's India VIX close change > 10% / > 15% / VIX close above its trailing
   250-day 90th percentile -> CE at {09:16, 09:45}; exit {EOD, LIQ}. 12.
N10 MIDDAY (lunch box breakout): box = index high/low from B0 in {11:30, 12:00} to 13:29; first 5-min close outside
   the box from 13:30 to 14:45 -> CE / PE. Exit {native = opposite side of box or 1x box target, else EOD; EOD; LIQ}. 6.
N11 FIRST2 (Kotak / IIFL first two 5-min candles): bars 09:15 and 09:20 both green (both red) -> CE when the index
   trades above bar 2's high (PE below its low), 09:25 - 11:00. Native = stop at bar 2's other end, target 2x bar-2
   height on the index, else EOD; EOD with stop; LIQ. 3.
N12 RVOL-ORB (Zarattini & Aziz 2023 5-min ORB "in play"): first 5-min candle green -> CE at 09:20, red -> PE, doji
   skipped; stop = index beyond bar 1's other end, else EOD. Filter: relative option volume of 09:15-09:19 (near-ATM
   CE+PE volume) vs its mean over the previous 14 sessions >= {none, 1.0, 1.5}; exit {native, LIQ}. 6.
N13 VIXDIV (VIX/index divergence): at 10:30, 11:30, 12:30, 13:30: index up > a since open AND VIX up > b% since open
   -> PE; index down > a AND VIX down > b% -> CE; first signal of the day. (a, b) in {(0.3%, 3%), (0.2%, 2%)};
   exit {60 min, EOD, LIQ}. 6.
N14 LUNCHREV (midday reversal): at 12:00 the move from the open > thr -> PE, < -thr -> CE. thr in {0.3%, 0.6%};
   exit {13:30, EOD, LIQ}. 6.
N15 BREADTH (advance/decline): at 10:00 or 11:00 the share of the available NIFTY-50 stocks above their day open > hi
   -> CE, < 1 - hi -> PE. hi in {0.8, 0.7}; exit {EOD, LIQ}. 8.

LIQ exit = the app's Liquidity arm exit: -15% resting premium stop, out after 20 minutes unless the premium closes
>= +5%, then held to 15:10. EOD = 15:10.

Indices: NIFTY and BANKNIFTY (the two with a measured 0.16% spread). Total = 2 x 113 = 226 variants.

## Statistics
- Per variant: trades, win%, Rs/trade, Rs/day (over all sessions of the period, zero on no-trade days), max drawdown of
  the daily equity, day-clustered t of mean Rs/trade.
- Random-entry baseline: for each real trade, 20 random trades on the same day, random side, random decision minute in
  the idea's entry window, same exit TYPE (same premium stop/target rules; index-based exits replaced by holding the
  same number of minutes). p_rand = one-sided Welch test real mean > random mean.
- Multiple testing: Benjamini-Hochberg over all 226 design p-values (t vs zero), and a White Reality Check style
  max-t stationary bootstrap (mean block 5 days, B = 1000) over the daily P&L matrix of all design variants.
- Holdout pick: per idea per index the design variant with the highest t (pre-registered, whatever its sign).
  These ~30 picks are run once on the holdout. Nothing is re-tuned after.
- PASS = design Rs/day > 0 AND design BH q < 0.10 AND design p_rand < 0.05 AND holdout Rs/day > 0 AND holdout
  p_rand < 0.10. Anything short of that is FAIL (positive holdout alone = "paper-only lead" at most).
