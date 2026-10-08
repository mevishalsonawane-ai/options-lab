# h27 pre-registration (written 2026-10-08 BEFORE any cue was compared with any Indian index outcome or option P&L)

Question: can OUTSIDE information known at or before 09:15 IST predict the direction of the Indian index day (or its
first 1-2 hours) well enough to beat option-buying costs? Option BUYING only, 1 lot, Rs 1 lakh.

## Data (downloaded 2026-10-08, raw under scratchpad/hunt/h27/raw/, untrusted, parsed with python -I)
- Yahoo chart API daily bars 2015+ (stooq: connection reset; FRED: timeout; investing.com: 403; Yahoo needs a
  cookie+crumb session, then works): ^GSPC ^NDX ^DJI ES=F NQ=F ^N225 ^HSI ^KS11 ^AXJO ^TWII 000001.SS ^STI CL=F BZ=F
  INR=X DX-Y.NYB ^TNX GC=F HDB IBN INFY WIT INDA EPI ^VIX ^NSEI ^NSEBANK ^INDIAVIX ^BSESN. Yahoo 1h bars (last 730
  days only): ES=F NQ=F ^N225 ^HSI ^KS11 ^NSEI.
- NSE participant-wise OI CSVs (nsearchives, published each evening) 2019+: FII index-futures long/short.
- FII/DII CASH flow history: NOT obtainable free in bulk (NSE API serves the latest day only; NSDL archive is an
  ASP.NET form). Participant OI is used as the FII proxy.
- GIFT NIFTY: Dhan's daily GIFTNIFTY bars exist (2017+) but the session boundaries are undocumented; used only if a
  timing check (below) shows its close is the night-session close known before 09:15.
- Index minutes / option minutes / India VIX: Dhan via research/obuy.

## Timing rule (no look-ahead). India day D, decision at 09:20 (fill 09:21 open) or 09:30 (fill 09:31).
- US instruments (indices, ADRs, INDA, ES/NQ daily, CL, GC, DXY, TNX, VIX): the last daily bar dated < D (it closed
  at 01:30/02:30 IST on D at the latest). Return = that close / previous close - 1. "US open->close" uses the same bar.
- Asian indices Nikkei, Kospi (open 05:30 IST), Hang Seng (07:00 IST): ONLY the OPEN of day D vs the previous close
  (their D close comes after 09:15 IST and is never used). ASX/Taiwan/Shanghai/STI not used (redundant, fixed now).
- INR=X: bar dated < D (ends before 09:15 IST D).
- India VIX: close D-1 vs close D-2. Participant OI: file of D-1 (published D-1 evening).
- The underlying's own gap: 09:15 open vs prev close (known at 09:15).
- If a market was shut, its cue is 0 for that day (no stale reuse beyond 4 calendar days).

## Cues (fixed list, 13 + composite)
SPX, NDX (prev US close-close); SPXOC (prev US open->close); ADR (mean of HDB, IBN, INFY close-close); INDARES (INDA
close-close minus NIFTY D-1 close-close); ASIA (mean of Nikkei/Kospi/HSI open gaps on D); CRUDE (CL=F, sign flipped:
oil up = bad for India); USDINR (sign flipped); DXY (flipped); US10Y (^TNX change, flipped); GOLD (flipped); USVIX
(flipped); INVIX (India VIX D-1 change, flipped); FII (FII index futures net long change D-1, z-scored on its own
trailing 60 days). Each cue is z-scored by its trailing 250-day std (no future data).
COMPOSITE Z = mean of z(SPX), z(ADR), z(ASIA). Fixed now, no weights fitted.

## Part A: index-level screen (no option prices). Pre-holdout days only (< 2025-10-01).
Outcome: index return from the 09:20 close to the 10:15 / 11:15 / 15:10 close, for NIFTY, BANKNIFTY, FINNIFTY,
MIDCPNIFTY, SENSEX. Statistic: Pearson correlation of cue with outcome, and also of cue with the RESIDUAL gap
(gap - walk-forward fitted beta x cue; tests gap-fill vs gap-go). 14 cues x 5 indices x 3 horizons = 210 tests, +
residual-gap 5 x 3 = 15; BH at q=0.05. Also reported (not tested): corr(cue, gap) as the alignment sanity check
(it should be strongly positive: the gap already prices the news).

## Part B: option trades. 1-ITM, nearest expiry ('near'), expiry days allowed, 1 lot (lot as of the date).
Rules (side +1 = buy CE, -1 = buy PE):
- R1 CUE-GO: side = sign(Z) when |Z| >= 0.5; entry 09:21 open.
- R2 CUE-GO-CONFIRMED: |Z| >= 0.5 and at the 09:30 close the index is on the cue's side of the 09:15 open; entry 09:31.
- R3 GAP-GO-AGREE: gap and Z same sign, |gap| >= 0.2%, |Z| >= 0.5; trade the gap direction at 09:21.
- R4 GAP-FILL-DISAGREE: gap and Z opposite signs, |gap| >= 0.2%; trade AGAINST the gap at 09:21.
- R5 RESIDUAL-FADE: residual = gap - beta x Z (beta: anchored walk-forward OLS on prior days, min 120 days);
  trade -sign(residual) when |residual| >= 1 trailing-250-day std of the residual; entry 09:21.
Exits (menu fixed now; all square off by 15:10 at the latest):
- E1 LIQ: -15% premium stop, 20-min time stop unless premium >= +5% (the Liquidity arm's premium exits).
- E2 15/30: -15% stop, +30% target.
- E3 LADDER: -15% stop, +40% target, profit-lock ladder (ProfitLock.LADDER) on R = 40% of entry.
- E4 T1015: -30% stop, out at 10:15.   E5 T1115: -30% stop, out at 11:15.   E6 T1510: -30% stop, out at 15:10.
Variants: 5 rules x 5 indices x 6 exits = 150 (all counted). Any single cue that survives Part A BH adds
1 rule (CUE-GO on that cue alone) x its index x 6 exits, also counted.

Costs. GROSS = raw option prices, no charges. NET = app charges with dated STT (Costs('dated')) + the REAL half-spread
paid at entry and exit: BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.20% (assumed; no
snapshot), + 5 bps extra on stop fills. STRESS = 1.5 x those half-spreads.

Random baseline: the SAME days and SAME entry minute with the side drawn by coin flip, identical exits (exact: both
sides are simulated for every day). p = share of 2,000 random side draws whose net total >= the rule's.

Decision rule (fixed now). A variant is PROMOTED only if, on pre-holdout data (< 2025-10-01): net (real spread) > 0;
random-side p < 0.05 after BH over all variants; positive net in >= 60% of calendar years 2021-2025(Sep) with >= 10
trades; Hansen SPA p < 0.10 over all variants (net daily P&L vs 0). Promoted variants (at most the best 3 by pre net)
are run ONCE on the holdout 2025-10-01 .. latest. If none is promoted, the single best pre-holdout variant is run on
the holdout for information only.
Reported: hit rate, Rs/trade, Rs/day (over all trading days), lots needed for Rs 5,000/day, max drawdown, worst month,
per year, gross/net/stress.

## Amendment 1 (2026-10-08, after Part A index-level results, BEFORE any option P&L was computed)
Part A (pre-holdout) found the OPPOSITE sign of what R1/R5 assume: the cues are NEGATIVELY correlated with the move
after 09:20 (the gap over-reacts to overseas news and gives part of it back), and the gap residual not explained by
the cues CONTINUES (corr +0.08..+0.16). 10 of 240 tests pass BH q<0.05 (all at the 11:15/15:10 horizons).
Added rules (all counted as extra variants, same exits, same decision rule):
- R6 CUE-FADE: side = -sign(Z_COMP) when |Z| >= 0.5; entry 09:21.
- R7 RESID-GO: side = +sign(residual) when |residual z| >= 1; entry 09:21.
- Single-cue survivors (BH q<0.05) as CUE-FADE on that index only: BANKNIFTY ADR, NDX, SPX; MIDCPNIFTY INDARES.
R1..R5 are still run and reported. Total variants: 7 x 5 x 6 + 4 x 6 = 234.
Part A will also be re-run on the holdout once at the end (index-level replication of the sign).
