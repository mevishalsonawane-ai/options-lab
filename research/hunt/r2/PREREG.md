# R2 pre-registration: does the US market give MCX direction an option buyer can monetise?

Written 9 Oct 2026, before any P&L or hit-rate was computed. Data downloaded only (Yahoo d/1h/5m/1m, FRED, EIA weekly,
CFTC disaggregated COT); MCX daily (Dhan, 2012-) and minute (Dhan rolling options + spot, Aug 2025-) already local.

## Periods
- DESIGN: everything before 1 Oct 2025 (daily tests from 2012; hourly-US tests from 17 May 2024; MCX-minute tests
  5 Aug - 30 Sep 2025; XAUUSD minute from 2 Oct 2023).
- HOLDOUT: 1 Oct 2025 - 6 Oct 2026. Locked. Opened once by `holdout.py`, which writes `holdout.lock`.

## Commodities
CRUDE (MCX CRUDEOIL vs CL=F), NATGAS (NATURALGAS vs NG=F), GOLD (GOLDM vs GC=F / XAUUSD), SILVER (SILVERM vs SI=F).

## Families (each cell = rule x commodity x horizon; all counted)
- F1 overnight cue -> MCX day: at 09:00 IST, sign of (a) MCX gap, (b) same-commodity US daily return D-1,
  (c) ES=F D-1 return, (d) DXY D-1 (gold/silver, sign reversed), (e) US10y D-1 change (gold/silver, reversed),
  (f) USDINR D-1 change; each as FOLLOW. Target: MCX open->close. Also the |z|>1 subsets.
- F2 hourly US overnight (MCX close -> 08:30 IST) and gap residual (MCX gap minus US overnight move in INR):
  follow / fade -> MCX open->close.
- F3 minute lead-lag (XAUUSD vs GOLDM; Yahoo 1m for others, holdout-week only, descriptive).
- F4 events: EIA crude (Wed) and EIA gas storage (Thu): fundamental surprise = change minus 5-yr same-week average
  change. Direction rule: draw/low injection -> long. Entry 30 min after release (hourly CL/NG design), exit at
  14:00 ET (~MCX close). Also follow the release-hour move.
- F5 slow macro: 20-day DXY change, 20-day DFII10 change -> gold/silver next 20 days (reversed sign);
  COT managed-money net % of OI, 3-year percentile >90 / <10 -> contrarian 20 days (CL, NG, GC, SI).
- F6 US holidays: MCX range / evening range on US exchange holidays vs normal (descriptive).
- F7 Asian-hours gold/silver drift: long 09:00/09:15 -> 14:00 IST (XAUUSD minute and GC/SI hourly in design).
- F8 FOMC overnight: |MCX gap| after FOMC vs other days (daily, descriptive); holdout real-option straddle.

## Gates (design), all must pass to become a holdout candidate
1. >= 30 trades; 2. futures net Rs > 0 after costs (mini lot: CRUDEOILM 10 bbl, NATGASMINI 250, GOLDTEN 10 g,
   SILVERMIC 1 kg; futures cost = Zerodha charges + 0.03% spread); 3. random-side (sign-flip) p < 0.05;
4. BH q < 0.10 across ALL design cells; 5. >= 55% of calendar years (or months for short samples) positive.
Candidates are then run once in the holdout as 1-lot near-month 1-ITM option buys (real Dhan option minutes, prints
only, spread 0.30%/0.60% crude & gas, 0.40%/0.80% GOLDM/SILVERM after/before 17:00, Zerodha charges) and as mini futures.
Holdout pass: net > 0 for options, random-side p < 0.10, >= 50% green months.

## Amendment 1 (9 Oct 2026, after the design tables, BEFORE any holdout data was touched)
Design result: 19 of 94 cells pass the gates. All F1/F2 passes measure entry at the Dhan DAILY OPEN print (09:00).
A 36-day minute check (Aug-Sep 2025) shows the daily open differs from the 09:00-09:05 minute price by 16-50 bp (std)
and the 09:05 residual vs US fair value is only 11-22 bp median, so the daily-open edge may be a stale-print artefact.
The F3 passes (XAUUSD lead on GOLDM) measure entry at the signal minute's close.
Frozen holdout candidates (minute data, tradable entries only):
- C1 FOLLOW_GAP: side = sign(ln(P_0905 / last price of previous session)), same contract only. Entry: futures at the
  09:05 minute price; option = 1-ITM near month, first printed minute at/after 09:05 (open).
- C2 FOLLOW_US: side = sign(us_on + inr_on), us_on = US future from 18:00 UTC (previous day) to 03:00 UTC (08:30 IST),
  inr_on likewise (Yahoo 1h). Same entry.
- C3 FADE_RES: res = gap_0905 - (us_on + inr_on); side = -sign(res) when |res| > the design (Aug-Sep 2025 minute)
  median |res_0905| of that commodity: CRUDE 12 bp, NATGAS 22 bp, GOLD 11 bp, SILVER 14 bp.
- Exits for C1-C3: X14 = 14:00 IST; XEOD = session end - 10 min; each with (a) time only, (b) option -30% stop on the
  1-min low (futures: none). Options skip expiry day and the day before.
- C4 XAU_LEAD (gold only): catch-up = 5-min XAUUSD log move - 5-min GOLDM move; enter next minute when
  |catch-up| > 23.8 bp (design q99.7) or 15.3 bp (q99), side = sign(catch-up), hold 15 min, one trade at a time.
  Futures (GOLDTEN costs) and GOLDM 1-ITM option (spread by hour, prints only).
Commodities: all four for C1-C3. Holdout pass = net > 0 on options or futures, random-side p < 0.10, >= 50% green months.
- Clarification (before holdout): option entry = first printed minute within 30 min after 09:05 (C1-C3); within 2 min of the signal for C4.

## Amendment 2 (9 Oct 2026, before the holdout was opened)
Dukascopy minute data for XAGUSD / LIGHTCMDUSD / GASCMDUSD is downloading slowly (server 503s). Frozen now, before seeing
any of it: C5 US_LEAD for SILVER, CRUDE, NATGAS = the C4 rule exactly (5-min catch-up, enter next minute, hold 15 min,
one at a time), thresholds = that commodity's own q99.7 and q99 of |catch-up| over the design window 5 Aug - 30 Sep 2025.
Futures (mini costs) and 1-ITM near-month options (prints within 2 min). Run by `lead_cand.py` on whatever holdout days
were downloaded (coverage reported).
