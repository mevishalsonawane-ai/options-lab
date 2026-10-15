# R49 pre-registration: the cancelled ORB family on the recent market only (20 Nov 2024 - 13 Oct 2026)

Written 15 Oct 2026, BEFORE any trade outcome of any R49 period was computed. Only the lake's file list, the session,
expiry-day and lot counts (`r49data.py summary`, no prices of any trade) and the earlier reports were read. This file is
committed and pushed on its own before any P&L. After that commit nothing here changes; a change needs a new entry and
a new pre-registration.

Boss (15 Oct 2026): "add the Nov 2024 onwards view. Don't test any data before that, and now find a hero-zero, ORB, and
all the strategies which we have cancelled". R49 is the ORB part: ORB, ORB Fresh, ORB Sweep and Range Fade were paused
on 13 Oct 2026; R3 (`research/HUNT_R3_ORB_FAMILY.md`) found every version loses on 2021-2026. R49 asks: on the market
since NSE's Nov 2024 expiry change only, does any version make money? Research only: R49 changes no app code. Bringing
anything back is Boss's decision after the report.

## A1. Data and window

- Lake (Drive `IraAlgo-data-lake`, fetched 15 Oct 2026): `options_rolling_1m/opt_<UND>_WEEK_<2024|2025|2026>` and
  `index_1m/index_1m_<UND|INDIAVIX>_<2024|2025|2026>`. For BANKNIFTY / FINNIFTY / MIDCPNIFTY the WEEK and MONTH files
  hold the same (monthly) contract in this window; R49 reads WEEK only, never both. WEEK = the nearest expiry on or
  after the day, which is the ORB family's own contract rule (`OrbRules.expiryOnOrAfter`).
- Window: sessions from **20 Nov 2024** (NSE's weekly-expiry change; 20 Nov 2024 itself was a market holiday, so the
  first session is 21 Nov 2024) to the lake's end (**13 Oct 2026** for NIFTY, **12 Oct 2026** for the others). Nothing
  before 20 Nov 2024 is traded or reported. The 14 sessions before it only feed ATR14 and the previous close / VIX.
- Sessions (`r49data.py summary`, 15 Oct 2026):

  | index | sessions | design (21 Nov 2024 - 31 Mar 2026) | final test (1 Apr - 13 Oct 2026) | expiry days | lots seen |
  |---|---|---|---|---|---|
  | NIFTY | 469 | 336 | 133 | 100 | 25, 75, 65 |
  | BANKNIFTY | 468 | 336 | 132 | 23 | 15, 30, 35, 30 |
  | FINNIFTY | 468 | 336 | 132 | 23 | 25, 65, 60 |
  | MIDCPNIFTY | 468 | 336 | 132 | 23 | 50, 120, 140, 120 |
  | SENSEX | 468 | 336 | 132 | 97 | 10, 20 |

- Records are R3's (`r3data.Sess`, built by `r49data.py` with R3's builder), with two changes:
  - **Lot = the day's contract lot read from the data**: the largest known lot of the index that divides at least 90%
    of the day's traded volumes. Contracts listed before NSE's Nov 2024 change kept their old lot until they expired
    (BANKNIFTY 15 until the Jan 2025 monthly; NIFTY 25 on the Jan 2025 monthly), and the Dec 2025 change (BANKNIFTY back
    to 30, MIDCPNIFTY 140 -> 120, NIFTY 65, FINNIFTY 60) is in the data. The calendar table `r49cfg.LOTS` is the
    fallback (used on 2 FINNIFTY sessions).
  - **Expiry days**: R3's rule (15:15 ATM time value under 0.6% of spot and the next session's more than twice it), OR
    the session's own 15:15 ATM time value under 0.15% of spot. The second catches FINNIFTY 30 Mar and 25 Aug 2026 (the
    next session had no 15:15 prices) and the lake's last NIFTY session (13 Oct 2026, a Tuesday expiry). No non-expiry
    day of the window is under 0.15% (lowest 0.166%).

## A2. Split (committed here, before any P&L)

- **DESIGN**: 21 Nov 2024 - 31 Mar 2026 (336 sessions per index). Walk-forward blocks are calendar quarters:
  2024Q4 (21 Nov - 31 Dec, short), 2025Q1, Q2, Q3, Q4, 2026Q1 (6 blocks).
- **FINAL TEST**: 1 Apr - 13 Oct 2026 (132-133 sessions per index), read ONCE by `study.py final`, which writes
  `results/FINAL_READ` and refuses a second read. Until then `r49study.sessions()` serves design sessions only.
- **Honesty about the final test**: it is NOT pristine. Earlier studies already looked at parts of 2025-26 with ORB-type
  rules: R3 ran these exact four arms (and its 16 variants) on every index to 12 Oct 2026; R12 / R14 / H19 / H20 ran
  them on BANKNIFTY to 6 Oct 2026; R24 read 27 Apr - 12 Oct 2026 on NIFTY and BANKNIFTY; H43 ran a generic ORB grid on
  all five indices. What is new here is only the restriction to the post-Nov-2024 market, the per-index view and the
  re-tune chosen on this window's design part. A pass would still need live paper from 15 Oct 2026, the only truly
  unseen data.

## A3. The rules (signal source: the INDEX itself for every rule, the CLAUDE.md default)

Every rule computes its entry and exit signals on the 1-minute index spot (5- or 15-minute bars built from it), and
then buys the option chosen by the strike rule. VIX (R3's filters) is context. Stops, targets and locks are on the
option premium. No rule reads an option's own price chart.

Shared (R3 PREREG A2, the app's `OrbRules` / `SweepRules` / `RangeFadeRules` / `ProfitLock` / `OrbArms`):
- 5-minute bars labelled by their start; opening range = bars 09:15 .. 10:00 (1-minute rows 09:15 .. 10:04), and only
  when the 10:00 bar exists.
- Strike = ATM of the 09:20 bar's close (half-up, index step: NIFTY / FINNIFTY 50, BANKNIFTY / SENSEX 100, MIDCPNIFTY
  25), fixed all day; the nearest expiry on or after the day (today's contract on an expiry day).
- **One held contract**: that (strike, side) of that expiry from entry to exit. Never the rolling ATM series (R45).
- Entry = 1 lot at the next minute's open after the signal bar closes (a print within 3 minutes, else no trade);
  refused when that premium is at or below the stop points.
- Stop = resting SL-M at fill - P (tick-floored), filled at min(level, the minute's open) (a gap fills at the worse
  price). P: BANKNIFTY 40 (the app's `OrbRules.STOP_POINTS`), NIFTY 20, FINNIFTY 20, MIDCPNIFTY 10, SENSEX 60 (R3's
  price-level scaling). Target +P (Sweep +2P), checked on the minute's high, sold at the next minute's open.
- Profit-lock ladder: at +25% of the target lock breakeven-after-charges, +50% -> +25%, +75% -> +50%; the peak is the
  best 1-minute high before the current minute.
- Out at 15:10 (the 15:10 minute's open), 15:05 on an expiry day. Cooldown: the next decision bar starts after the
  exit's 5-minute bar. One position per arm per index.

### Group O: the old rules exactly as cancelled (4 rules + 1 check)

- `r49-orb`: ORB; decision bars 10:05 .. 13:55; close > high -> CE, close < low -> PE; at most 1 entry a side a day
  (the paper cap since 13 Oct).
- `r49-orb-fresh`: ORB, and the previous bar must not already be beyond the same edge; same cap.
- `r49-orb-sweep`: decision bars 10:05 .. 14:25; high > range high and close < range high -> PE; low < range low and
  close > range low -> CE; at most 2 entries a day; target +2P.
- `r49-range-fade`: decision bars 10:30 .. 13:55; w = high - low of the range; high >= range high - 0.1w and close <
  range high -> PE; low <= range low + 0.1w and close > range low -> CE; at most 2 a day.
- `r49-orb-uncapped`: ORB without the 1-a-side cap (the app's ORB before 13 Oct). A check, counted in BH like the rest.

### Group R3: R3's 16 enhanced versions, exactly as R3 froze them (16 rules)

`r49-r3-<arm>-F|E|S|C` for the four arms, with R3's Part B filters and cut values UNCHANGED (they were chosen on
2021-2024; nothing is re-fitted):
- F: skip ORB / ORB Fresh entries with VIX change 09:15 -> signal in [-0.8638%, 1.0372%); Sweep entries with VIX
  change < -0.9926%; Range Fade entries on expiry days.
- E: no fixed target, the arm's ladder stays (on +P, Sweep +2P); step lock +Rs 1,000 a lot -> keep +Rs 500; give-back
  trail from +Rs 1,000 a lot, Rs 500 a lot below the peak; time stop at 45 minutes if the peak never reached +25% of P
  (Sweep 2P).
- S: 1 strike in the money (CE ATM - step, PE ATM + step) and at most 1 entry a side a day.
- C: F + E + S.

### Group R23: the three ORB finalists R23 froze for the lake and never read (3 rules)

From `research/HUNT_R23_ORB_RANGE.md` ("If a finalist passes"), cut values unchanged (chosen on the 2024-26 release):
- `r49-r23-r22-f2`: range = 1-minute bars 09:15 .. 09:59 (three 15-minute candles; the 10:00 bar is not in it).
  Signal: the first 15-minute candle (starting 10:00, 10:15, .., 13:45) that closes above the high (CE) or below the
  low (PE); only the first signal of each side per day counts. Filter at that close (both must hold, else that side is
  not traded that day): f1 `side x (close - mean of the 1-minute closes from 09:15) / ATR14 > 0.21982`; f2
  `side x (close - broken edge) / ATR14 > 0.03538`. Trade: the ORB arm's contract, entry and exits (above).
- `r49-r23-r22-f1`: the same with f1 only.
- `r49-r23-orb-f2`: today's ORB range (09:15 .. 10:04) and 5-minute closes 10:05 .. 13:55, with f1 and f2, first
  signal per side.
- R23's trades used the next week's contract on expiry days; R49 uses the app's rule (today's contract, 15:05 out).

### Group G: the small re-tuned grid, chosen on DESIGN only (32 rules)

For each arm X in {ORB, ORB Fresh, ORB Sweep, Range Fade}, every combination of:
- opening-range length M in {15, 30, 50} minutes (50 = the app: 09:15 .. 10:04; 15: 09:15 .. 09:29; 30: 09:15 ..
  09:44), the range's last 5-minute bar must exist. The first decision bar is the bar right after the range (ORB / Fresh
  / Sweep), or 5 bars later for Range Fade (the app's 10:30 = range end + 25 minutes). The last decision bar is the
  arm's own (13:55, Sweep 14:25).
- stop / target scale k in {0.5, 1.0, 1.5}: stop k x P, target k x P (Sweep 2k x P), ladder on that target, and the
  entry refusal at k x P.

(M = 50, k = 1.0) is the old rule (Group O), so the grid adds 8 new rules per arm, `r49-g-<arm>-m<M>-k<k>`. Everything
else is the arm's old rule (cap, strike, ladder, square-off). The R3 enhancements are not crossed with the grid.

**Re-tune selection (design only):** for each arm and each scope (pooled, and each index), the "re-tuned pick" is the
(M, k) of its 9 (8 grid + old) with the best design net; ties go to the old rule. The nested walk-forward of that
selection: for each design quarter from 2025Q1 on, pick the (M, k) with the best net over the quarters before it, trade
it in that quarter; the walk-forward net is the sum over 2025Q1 .. 2026Q1.

**Total: 5 + 16 + 3 + 32 = 56 rules**, each on 6 scopes (pooled over the five indices, and each index alone).

## A4. Costs and fills (every rule, every period)

- Charges in force on the trade date: `research/obuy/costs.py Costs("dated")`: brokerage Rs 20 an order; STT on option
  sells 0.1% (1 Oct 2024 - 31 Mar 2026) and 0.15% from 1 Apr 2026 (Budget 2026); NSE exchange 0.03503% + IPFT 0.0005%,
  BSE 0.0325% (SENSEX); SEBI Rs 10 a crore; stamp 0.003% of buys; GST 18%.
- Fills: R17's "liq" (as R3): +-5 bps plus a half-spread of 1 / 2 / 4 ticks by the contract's traded lots in the 5
  minutes before (>= 20 / some / none); stops 10 bps and double ticks.
- 1 lot of the day's contract (A1). Rupees are per 1 lot.
- A held strike that leaves the stored ATM+-10 band: its exit uses the last stored price before ("stale", counted).

## A5. Final-test statistics

- **Random-entry twins** (R3 A9, 500 per rule, seed 49 + the rule's position): same sessions, same number of entries
  per session and the same sides; each entry at a random decision bar of the rule's own window (uniform), with the
  rule's strike, stop, target and exits; a refused draw is re-drawn up to 5 times. Twin p = (1 + twins with net >= the
  rule's net) / 501. The per-index p uses the same twin draws summed per index.
- One-sided t-test on the mean net per index-session (descriptive only).
- **Benjamini-Hochberg** over every rule x scope tested in R49: m = 56 x 6 = **336** twin p-values, q <= 0.10.

## A6. Gates (a rule passes on a scope only if ALL hold)

1. Final-test net > 0 after costs.
2. Twin p < 0.05.
3. BH q <= 0.10 (m = 336).
4. At least 40 final-test trades (else "too few trades to judge", not a pass).
5. The top 5 final-test trades make less than 100% of the final-test net (net without them still > 0).
6. Design walk-forward net > 0: for rules with no parameter chosen in this window (Groups O, R3, R23) the design net
   (which is out of sample for them); for Group G both the rule's own design net and its arm's walk-forward selection
   net (A3) on that scope.

All 56 rules are read on the final test in the one read (no further selection), so m is fixed in advance.

## A7. Reports

- Per rule and scope: trades, win rate, net, net per day, average win / loss, worst day, max drawdown, p, q, pass /
  fail; design per quarter; final test per month.
- Per strategy, a table: OLD verdict (R3, full data 2021-2026) vs NOW (Nov 2024+ design) vs FINAL TEST.
- `research/HUNT_R49_ORB_RECENT.md` starts with the verdict for Boss and ends with which arms / versions would come
  back on PAPER under the Nov-2024+ view (exact settings) and which stay off.
- Experiment entries `research/experiments/entries/r49-*.yaml` (every rule), a FINDINGS.md section.
- Tests (`test_r49.py`): the window and split guards, lot and expiry detection, the grid windows, the R23 signals, dated
  charges, and the look-ahead guard (every signal and entry unchanged when every minute after the decision is noise).

## If a rule passes (paper only; the parent session builds it, only if Boss agrees)

Paper only, on the index (or indices) where it passed, logged in the Autopilot change timeline, labelled "R49",
undoable in one tap, never live by itself. Its exact settings will be in the report.
