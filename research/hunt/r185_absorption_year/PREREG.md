# R185 pre-registration: absorption and exhaustion as option-buying rules on two years of 1-minute data

*16 Nov 2026. Written and committed (and pushed) BEFORE any R185 signal, trade or rupee was computed. Research only:
nothing in `android/**` changes. Owner: Boss (they/them).*

Boss asked for R184's absorption and exhaustion to be re-tested on a full year, with Dhan's historical minute data, NIFTY
first and then the other indices. R184 (20 phone-recorded days) ended "exhaustion rejected, absorption too few trades".

## 0. What was looked at before this file (no outcome)

- R184's PREREG, code and published results (`research/hunt/r184_absorption_exhaustion/`). Its BH count ended at
  m = 4,512 (`results/decision.json`).
- The data lake's catalogue (`research/data/CATALOG.json`, README): index 1-minute bars (2017-04 to 19 Oct 2026) and rolling
  index options (ATM-10..ATM+10, WEEK, expiry code 1, 1-minute, from 2021-08 to 19 Oct 2026) for NIFTY, BANKNIFTY,
  FINNIFTY, MIDCPNIFTY and SENSEX; futures 1-minute only for the contracts live on a download date (first bar 29 Jul 2026).
  The local copies of the 2024-2026 files match the catalogue's SHA-256 (37 of 37).
- Dhan API probes on 16 Nov 2026, no outcome: expiry code 0 is refused ("expiryCode is required"); code 1 and 2 answer
  (on 4 Nov 2026 09:15 the ATM 23,800 CE closed 175.6 with code 1 and 256.9 with code 2, so code 1 is the nearer expiry;
  the phone check in section 1 confirms which); `fromDate = D, toDate = D + 1` answers both days, a 3-day window answers
  DH-905; **no second-level history**: `/charts/intraday` with interval "1s" returns empty arrays and
  `/charts/rollingoption` refuses every seconds interval (accepts 1, 5, 15, 60 minutes). The expired NIFTY Oct-2026 future
  (security id 48704) returns no minutes; the live Nov-2026 future returns nothing for late 2025. So **there is no index
  futures volume history** for the year: section 2's fallback applies.
- Index `volume` in the lake's index files is 0 on most minutes (no use as an activity measure).
- Nothing else. No signal count, no trade, no price path after any candidate signal was computed before this commit.

**Expected (said now):** R184 and the repo's order-flow studies (R7, R8, R10, R61b, R77, R125, R164, R181, R182) found that
flow explains the same minute but forecasts little. Here the "flow" is estimated from price (BVC), which makes it even
closer to a price pattern. The likely outcome is "no evidence"; a year gives the power R184 lacked to say so firmly.

## 1. Data (outside git)

- **Lake** (`~/.cache/iraalgo/lake/`, the Drive copies, `gdrive:IraAlgo-data-lake`): `index_1m_<IDX>_<YEAR>.parquet`
  (2024-2026) and `opt_<IDX>_WEEK_<YEAR>.parquet` (2024-2026) to 19 Oct 2026.
- **Gap fetch** (`r185fetch.py`): 20 Oct - 13 Nov 2026 from Dhan v2 in the lake's format (index 1-minute via
  `/charts/intraday`; options WEEK, expiry code 1, ATM-10..ATM+10, CALL and PUT via `/charts/rollingoption`, one trading
  day per request). Raw files stay in `~/.cache/iraalgo/dhan/` and go to `gdrive:IraAlgo-data-lake/dhan/<IDX>/` (and as
  `*_gap1113.parquet` into the lake's `options_rolling_1m/` and `index_1m/` folders); the repo gets an aggregate manifest
  (`results/data_manifest_<IDX>.json`: days, rows, gaps).
- **Validation (before the design run, no outcome):** Dhan's 1-minute option closes against the phone's 1-second
  recordings (15 Oct - 13 Nov 2026, NIFTY and BANKNIFTY near-ATM options, read with `python -I`, untrusted, outside git):
  for each recorded (strike, right, minute), the recording's last `last` in the minute vs Dhan's close; reported: pairs,
  median absolute difference, share within 1% and within Rs 1, by day. It also checks that expiry code 1 is the expiry
  the phone records (the nearest).
- **Days:** trading days with at least 300 index minutes (drops special sessions such as Muhurat and half days; listed).
- **Periods (fixed now, the repo's usual split):**
  - **Selection: 20 Nov 2024 - 19 Oct 2025**, in two halves **H1 20 Nov 2024 - 30 Apr 2025** and **H2 1 May - 19 Oct
    2025**. Every variant is run here and the choice is made here.
  - **Final (locked): 20 Oct 2025 - 19 Oct 2026**, read ONCE for the chosen variant per setup and index, after
    `results/FINAL_READ` (a timestamp) is committed and pushed. Gate periods inside it (as R176 / R183):
    early 20 Oct 2025 - 31 Mar 2026, final 1 Apr - 19 Oct 2026, last six months 20 Apr - 19 Oct 2026, the year.
  - **Recent: 20 Oct - 13 Nov 2026**, descriptive only, read with the final (never used to choose).
- **Indices and order (fixed now):** NIFTY, then BANKNIFTY, then FINNIFTY, MIDCPNIFTY, SENSEX (BSE). Each index follows
  these same frozen rules; nothing is re-tuned after NIFTY's final read. An index whose data cannot be had is reported as
  "not run" and its tests still count in BH (section 6). For BANKNIFTY, FINNIFTY and MIDCPNIFTY Dhan's WEEK series
  follows the nearest available expiry, which is the monthly since Nov 2024 (the lake's README): those trades are in the
  nearest monthly option, said so in the report.
- Strike step: the lake's own spacing (the day's most common strike gap between ATM and ATM+1). Lot: the repo's lot
  schedule by date (`research/obuy/config.py LOT_SCHEDULE`; NIFTY 75, 65 from 31 Dec 2025).

## 2. Bars and the estimated order flow (signal source)

**Signal source: the INDEX's own 1-minute price (the underlying), with the near-ATM options' combined traded volume as
the activity measure - labelled "option-volume context" (OVC).** No index future has minute volume for the year (section
0), so the brief's fallback is used: the direction of each minute's flow comes from the index price only; the option
volume only sizes it (CLAUDE.md: option-chain data as context around an underlying signal). No rule reads an option's own
price chart for its signal. Option prices only price the trade; the premium stop and breakeven lock act on the premium.

- Index minute bar i = 0..374 (09:15 - 15:29): O, H, L, C from the index file; no row: a NaN bar.
- Activity V[i] = the summed `volume` of the 10 rolling series ATM-2..ATM+2, CALL and PUT, at minute i (missing rows
  count 0).
- **Bulk volume classification (BVC):** dP[i] = C[i] - C[i-1]; sigma[i] = the standard deviation (ddof 1) of the day's
  dP over bars 1..i-1 (at least 10 finite values, else NaN); buy share b[i] = Phi(dP[i] / sigma[i]) (Phi = the standard
  normal CDF); **D[i] = V[i] x (2 b[i] - 1)** (estimated buy minus sell volume). sigma[i] reads bars < i only.
- **Thin** bar: no index close, V[i] = 0, or D[i] undefined (no dP or no sigma). Every condition that names a bar
  requires it not thin. (R184's "fewer than 20 recorded seconds" has no meaning at 1 minute.)
- Then R184's references and setups apply **unchanged** (R184 PREREG sections 2-3, its code `r184lib.absorption` /
  `r184lib.exhaustion` reused as they are): `ref1`, `S_w`, `ref_w` (the day's own earlier bars, at least 20), band = beta
  bp x the reference price, signal bars r in 25..329 (09:40 - 14:44), the ABSORPTION and EXHAUSTION rules with their
  index stops, the same grids:
  - ABSORPTION: w in {3, 5}; k in {2, 3}; beta in {3, 6} bp; confirm in {none, turn};
  - EXHAUSTION: X in {15, 30}; k in {2, 3}; beta in {3, 6} bp; confirm in {flip, flip+close};
  - each x time stop T in {15, 30} minutes: **32 variants per setup, 64 per index**.
- Said now, a limitation: BVC infers the side of the volume from the price change, so it cannot see "heavy selling
  that does not move the price", the core of absorption. Under BVC, absorption reads as "heavy volume on down-closing
  minutes while the low stays within the band". The study tests these definitions as written; it cannot test true
  absorption, and the report says so.
- Taking signals (R184 unchanged): per variant, index and day in time order, one trade at a time (a signal is taken only
  at or after the previous trade's exit minute), at most 4 a day; unpriced signals are counted by reason.

## 3. The option trade at 1 minute (fixed)

- Strike: ATM = the index close of bar r rounded half-up to the step; the option CE (call) / PE (put) at that strike, from
  the rolling series (any offset whose `strike` equals it at that minute). 1 lot (schedule by date).
- Entry: the option's OPEN of minute r + 1 (the first minute after the signal's close); if that minute has no bar, the
  open of the first bar in r + 2..r + 3; none: unpriced ("no price"). Fill = the open plus `Fills("liq")` slippage by the
  contract's traded lots in minutes r - 4..r (research/obuy/costs.py, as R181 / R184).
- Exits, checked minute by minute from the entry minute j = e (its bar after the open) on; the contract's bar at minute j
  (a minute with no bar is skipped for the option checks):
  1. **Premium stop -15%:** the option's low <= 0.85 x fill -> exit at min(open, 0.85 x fill).
  2. **Breakeven lock:** once a minute's high >= 1.10 x fill, from the NEXT minute the stop is breakeven = fill + the
     round trip's charges per unit (never moves down); exit when a later minute's low <= breakeven, at min(open, be).
  3. **Index stop:** the index's low (call) / high (put) of minute j crosses the setup's stop level -> exit at the
     option's close of minute j.
  4. **Time stop T:** the option's close of minute r + T (held T minutes from the entry open).
  5. **Day end:** the close of minute 15:14 (15:04 on the option's expiry day).
  - When two exits fall in the same minute, the one with the lower exit price is taken (conservative: the order inside
    a minute is unknown).
  - An exit price at a minute with no bar: the last close in the 5 minutes before, else the first open in the 5 minutes
    after; none: the trade is dropped as "no exit price" (counted).
- Expiry day (from the data, the repo's rule r25 / r147): the ATM time value at 15:15 < 0.6% of spot and the next
  session's > 2x it. The last day of the data counts as not an expiry day.
- Sell slippage `Fills("liq")` (stops - premium, breakeven, index - at the stop slippage). Charges `Costs("dated")`
  (STT on option sells 0.0625% to 31 Mar 2026, 0.1% from 1 Apr 2026, exchange charge (BSE's for SENSEX), SEBI, stamp,
  GST, Rs 20 a side). Net = (sell fill - buy fill) x lot - both sides' charges. Win = net > 0. Sensitivity: 2x slippage.
  (No historical bid / ask exists, so R184's bid / ask sensitivity is not computable.)

## 4. Random-entry twins (fixed)

For a rule trade (day d, bar r, side, index-stop distance dist = |C[r] - stop|, T): its twin pool is the same trade on up
to 60 other days of the same period (the same selection half, or the final year), drawn at random (seed per index), with
that day's C[r] -/+ dist as the index stop and the same exits; unpriced draws are left out. So twins match the count, the
time of day, the side mix and the exits; they differ only in the day.

## 5. Selection on 20 Nov 2024 - 19 Oct 2025 (fixed now)

Per setup and index, a variant is **eligible** when, in **each** half (H1 and H2): at least 30 trades, net > 0 (1x
slippage, rupees summed), and its mean net a trade > its twins' mean (the mean of all priced twin trades of that half).
Choose the eligible variant with the highest selection net; tie: more trades, then the first in grid order (w / X, k,
beta, confirm, T, smallest first). **No eligible variant:** the variant with the highest selection net among those with
at least 30 trades in each half (none: the most trades) is carried to the final read as a fallback, and its verdict can be
no better than "no evidence (nothing passed the selection)". Every variant's numbers (both halves, twins' means) and the
choice go to `results/selection_<IDX>.json`, committed before the final read.

## 6. The final read and the tests (fixed now)

For each setup x index, the chosen variant on 20 Oct 2025 - 19 Oct 2026, read once:
- **T1 twins (primary):** 1,000 twin sets, each drawing one twin per rule trade from its pool; p1 = (1 + sets whose mean
  net >= the rule's mean net) / 1,001 (one-sided).
- **T2 do nothing:** day-block bootstrap of the year's per-day net sums (days with no trade = 0), 10,000 resamples;
  p2 = share of resampled means <= 0.
- Fewer than 3 trades: p = 1.
- **BH:** tests = 2 setups x 5 indices x 2 = 20; the 64 variants per index x 5 indices = 320 count as ideas tried.
  **m = 4,512 (after R184) + 320 + 20 = 4,852**, fixed now for all five indices (an index not run keeps its count).
  q = p x m / rank among R185's tests (the others taken as larger), step-up, capped at 1; also q within R185's 20.
  NIFTY's q is computed at its read with m = 4,852 and the rank among the tests read so far; at the end all 20 are
  ranked together and the final q reported (a later index can only change q through the rank).
- **Reported per setup x index** (selection: every variant, both halves; final: the choice): signals, trades, win rate,
  net (1x, 2x), mean, average win / loss, worst day, worst month, max drawdown, trades by side and exit reason, the
  twins' mean and 10th / 90th percentiles, p, q; by gate period; the recent weeks (descriptive).
- **Power:** smallest mean edge a trade detectable with 80% power: one-sided 5% 2.49 x sd / sqrt(n), and at BH rank 1
  (alpha = 0.10 / 4,852) (z + 0.84) x sd / sqrt(n).

### Bars (fixed now)
- **"Passes the gates"** (could go to paper by the repo's rules), all of:
  1. it passed the selection (section 5);
  2. net > 0 in early, final, last six months and the year (1x), and the year's net > 0 at 2x slippage;
  3. its mean net a trade above the twins' mean in early, final and the year;
  4. T1 q <= 0.10 and T2 q <= 0.10 (m = 4,852);
  5. at least 40 trades in the final period (1 Apr - 19 Oct 2026);
  6. the account stop rule in every gate period: max drawdown no worse than -Rs 40,000 and worst month no worse than
     -Rs 13,000 (1 lot).
- **"Worth a forward test"** (a shadow note only): it passed the selection, >= 40 trades in the year, the year's mean at
  2x slippage > 0, above the twins' mean, p1 <= 0.20.
- **"Too few trades"**: under 20 trades in the year. Otherwise **"no evidence"**.
- Nothing in the app changes in any case; any arm would need a new Boss decision.

## 7. Look-ahead guard, run log, order of work

- Tests (`test_r185.py`, synthetic, no network, no token): BVC's sigma[i] and D[i] read bars <= i only (the bars after i
  replaced by noise change nothing up to i); every signal at bar r is unchanged when bars after r (r + 1 for "turn") are
  replaced by noise; exits never read a minute before the entry; twins use only their own day; the fetcher's request
  bodies, one-day trimming, cache resume and error handling with a fake `post`.
- `r185.py --stage select --und <IDX>` runs the selection window only (refuses final-year days). `--stage final` refuses
  unless `results/FINAL_READ_<IDX>` and `results/selection_<IDX>.json` exist; it writes `results/final_<IDX>.json` with
  this file's SHA-256 and the selection's. Each index's final is run once; a rerun after a bug fix is disclosed.
- Order: NIFTY (fetch -> validate -> select -> FINAL_READ -> final -> write-up, pushed), then BANKNIFTY, FINNIFTY,
  MIDCPNIFTY, SENSEX the same way.
- Any change to this plan after this commit is an amendment, dated, with its reason, and says whether any number had
  been seen.

## Amendment A (16 Nov 2026, after NIFTY's final read; before any BANKNIFTY, FINNIFTY, MIDCPNIFTY or SENSEX signal, trade or rupee)

**Change:** section 1's lot ("the repo's lot schedule by date") is replaced by the **true lot of the contract traded, by
trading day** (`r185lib.TRUE_LOTS`), for every index. **Why:** the repo's shared schedule (`research/obuy/config.py`)
differs from what the exchanges printed (the phone's own BANKNIFTY paper fills in Oct - Nov 2026 were 30 a lot; the
schedule says 35). **Sources:** NSE's F&O bhavcopies in the lake (`nse_bhavcopy/`, 144 weekly files 11 Jul 2024 - 7 Oct
2026; `NewBrdLotQty` of the nearest index-option expiry in the latest file on or before the day; `r185lots.py` ->
`results/lot_sizes.json`); for SENSEX (BSE, not in NSE's file) the lake's option volumes and OI (every contract's daily
units a multiple of 10 to 3 Jan 2025 and on 22 - 28 Jan 2025, of 20 otherwise); Dhan's instrument master for the live
contracts agrees (NIFTY 65, BANKNIFTY 30, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20). The shared schedule is NOT changed in
this branch.

**What had been seen:** NIFTY's selection and final read. NIFTY's final year (20 Oct 2025 - 19 Oct 2026) used the right
lots (75, then 65 from 31 Dec 2025: the schedule and the true lots agree there), so **NIFTY's final read and verdicts
stand unchanged**. NIFTY's selection window had 28 Nov - 26 Dec 2024 and 24 - 30 Jan 2025 at 75 instead of 25; the
NIFTY selection is recomputed with the true lots and reported next to the original (`results/selection_NIFTY_truelots.json`,
descriptive): if the choice would have differed, that is disclosed, and the final read is not repeated. No rule,
grid, period, test or bar changes. BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX use the true lots from the start.
