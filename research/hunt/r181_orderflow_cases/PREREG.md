# R181 pre-registration: the guide's order-flow setups (Cases 1-5 and the Bonus A+) on the phone's recordings

Written 15 Nov 2026, **before any R181 signal, trade or P&L number was computed**, and committed on
`claude/r181-orderflow-cases` before the study code's first run on the recordings. Research only: nothing in
`android/**` changes. Owner: Boss (they/them).

**History of this text (disclosed):** a first draft was written on a first export (11 days of minute order flow,
13-28 Oct). Before anything was run or committed, a second export arrived (20 days of 1-second order flow plus a per-price
footprint, 15 Oct - 13 Nov). The draft was rewritten for it. No signal, trade or P&L had been computed on either export.

## 0. Why, and what was looked at before writing

Boss shared a guide, "Volume Profile & Order Flow Trading Setups (Cases 1-6)" plus a "Bonus Module" (OI walls). R23,
R62, R105F, R177 and R178 could only test its price-level parts (all rejected or unstable): the lake has no order flow.
The phone's recordings now carry tick-derived order flow (Zerodha full-mode quotes, signed by the tick rule: up = buy,
down = sell, unchanged = the previous side; Zerodha gives no aggressor flag, so "delta" is always an estimate).

Looked at before writing (structure and data quality only, no outcome):
- Both exports' READMEs, headers and a few rows; the app code that writes them (`MoveEvents.kt` daily 1-second file and
  footprint, `OrderFlow.kt` tick rule).
- Per day: instruments, row counts, futures minutes covered, minutes with fewer than 20 recorded seconds, seconds after a
  gap of 5 s or more, `stale` rows, index rows, option strikes and tokens, footprint minutes; futures volume is always a
  whole number of lots (NIFTY 65, BANKNIFTY 30).
- **The two exports disagree** on the futures' minute volume and delta for the same minutes (checked on 15 Oct: the
  1-second file's minute volume is lower in most minutes, and its delta differs in about 97% of minutes; the footprint
  equals the 1-second file's buy / sell exactly). Both claim the tick rule; they sample the stream differently.
- The first export's app `absorb` flag fired on 0-3 futures minutes a day. That is a frequency, not an outcome, but it is
  why the main absorption rule below is a delta rule and the app's formula is only a secondary variant. Disclosed.
- Option strikes recorded change through the day (the watch-list re-centres), so the ATM strike is usually, not always,
  recorded. Option tokens of the same strike change on 21 Oct, 28 Oct, 4 Nov and 11 Nov for NIFTY (weekly expiries 19 Oct,
  27 Oct, 3 Nov, and 9 Nov inferred: 10 Nov is not recorded) and on 28 Oct for BANKNIFTY (monthly 27 Oct). The index ran
  from about 22,400 to 25,500 (NIFTY) between 28 Oct and 13 Nov: a strong one-way trend in the sample.
- Boss's own trades are not in either folder and are not used.

**Expected (said now):** earlier order-flow work (R7, R8, R10, OF1, R61b, R77, R125, R164) found order flow explains the
same minute but forecasts little beyond a minute or two, and the guide's price levels already failed (R177). With about
19 signal days, R181 is unlikely to show anything; the honest outcome is probably "no evidence either way".

## 1. Gates: none can pass (said up front)

The repo's gates need at least 40 holdout trades, a design / holdout split, BH q <= 0.10 across all tests, and a clean
account-stop record. With 19 signal days and a few setups a day, **no setup can pass**. The sample is **not** split into
design and holdout (that would pretend). Every rule is fixed here, the study is run **once**, and every run is logged in
`results/results.json` ("runs") and in the report's FINAL READ.

The questions are only:
1. Does the order-flow (OF) condition pick better trades than its price-only twin (the same setup without it)?
2. Do the OF trades beat random entries (same day, same side, same stop / target distances)?
3. Does anything deserve a pre-registered forward test as more days are recorded?

**Forward-test bar (fixed now):** a case is "worth a forward test" only if, with at least 8 OF trades, its mean net per
trade at 2x slippage is above zero AND above its price-only twin's mean AND both its p values (T1, T2) are <= 0.20.
Otherwise "no evidence" (or "too few trades" under 8). Nothing goes to paper or live from R181 in any case.

## 2. Data (outside git, read-only, untrusted)

- **Main sample: the 1-second export** (`orderflow-YYYY-MM-DD.csv.gz`, `orderflow-footprint-YYYY-MM-DD.csv.gz`), 20 days
  15, 16, 19, 21, 22, 23, 26, 27, 28, 29, 30 Oct and 2, 3, 4, 5, 6, 9, 11, 12, 13 Nov 2026. 15 Oct is a profile day only;
  **signal days: the other 19**, NIFTY and BANKNIFTY.
- First export (`market.jsonl.gz`, `orderflow_1m.jsonl.gz`, 13-28 Oct) is used only for (a) the option chain OI of the
  A+ rule (no chain exists after 28 Oct, so **A+ runs on 16-28 Oct only**) and (b) the source cross-check (section 7).
  13 and 14 Oct exist only there and are not used (one source for the main sample).
- Session seconds 09:15:00-15:29:59; minute bars i = 0..374 (09:15..15:29). The events folder and the shadow log are not
  used.

### Futures minute bars (from the 1-second rows, role FUTURE)
- O = the first `last` of the minute, C = the last `last`, H / L = the max / min of `last`, `high`, `low`; volume = sum of
  `vol`; buy / sell = sums of `buy_vol` / `sell_vol`; delta D = buy - sell; rows = recorded seconds. No row in a minute:
  a NaN bar.
- A bar with fewer than 20 recorded seconds is "thin": every single-bar OF check on it is False (its delta still adds to
  the CVD).
- CVD = R181's own cumulative sum of D from 09:15.
- `ref` at bar i = mean |D| of today's non-thin bars i-30..i-1; needs at least 10, else every delta-size check at i is
  False. "Big" = |D| >= 1.5 x ref.
- Spot at minute i = the index's last `last` (role INDEX) at or before the minute's end, at most 120 s old.
- Basis at minute i = futures C - spot.

### Footprint (per minute, per traded price, futures buy / sell volume)
- Used for the prior-day profile, Case 3 and the secondary absorption variant. Minutes absent from the footprint have no
  footprint (Case 3 / the variant are False there).

## 3. Levels (the prior recorded day's futures volume profile)

- From the PREVIOUS recorded day's footprint (09:15-15:29): each traded price's buy + sell volume into bins of strike
  step / 5 (NIFTY 10 points, BANKNIFTY 20).
- POC = the bin with the most volume (tie: nearest the session mid-price). Value area: from the POC add the larger of the
  next bin above / below (tie: above) until >= 70% of volume. VAH = top of the highest VA bin, VAL = bottom of the lowest.
- PDH / PDL = the prior day's futures high / low (minute bars).
- Fewer than 300 footprint minutes on the prior day: no levels and no signals for that index that day.
- **Contract shift:** levels move by (today's first basis - the prior day's last basis). On a normal day a few points; on
  28 Oct (the OCT -> NOV futures roll) it carries the levels onto the new contract.

## 4. The setups (signal on the index futures' price and order flow; trade the ATM option)

`step` = 50 (NIFTY) / 100 (BANKNIFTY); `buf` = step / 10 (5 / 10 points); `tol` = step / 5 (10 / 20 points). Signal bars
r in 10..329 (09:25-14:44). A setup's **signal bar r** is the bar at whose close every condition is known; the option is
bought at the start of minute r + 1. Long = buy the ATM CE, short = buy the ATM PE.

Each setup first finds **price episodes** (= the price-only twin); the OF condition is then read on each. Episodes in one
case / index / day are found in time order, a new search starting after the previous signal bar; at most 3 a case, index
and day.

### Case 1: failed breakdown + absorption at VAL (long)
- Price: break bar b with C[b-1] >= VAL and C[b] < VAL. Reclaim bar r = the first in b+1..b+15 with C[r] > VAL. No
  reclaim within 15 bars: accepted, no episode; search on from b+16.
- Stop = (lowest low of b..r) - buf. Target = POC.
- OF (primary, "delta absorption"): some non-thin bar j in b..r with L[j] < VAL, D[j] < 0 and big, and C[j] >= (H[j] +
  L[j]) / 2 (heavy net selling, yet the bar closes in its upper half); AND r non-thin with D[r] > 0.
- OF variant ("app absorb formula", from the footprint, the app's README rule): some bar j in b..r with L[j] < VAL whose
  volume is >= 2.0 x the mean of today's earlier bars (at least 10), with >= 60% of its footprint volume at one price, and
  H[j] - L[j] <= max(0.1, 0.0002 x C[j]).

### Case 2: failed breakout + exhaustion at VAH (short)
Mirror: C[b-1] <= VAH, C[b] > VAH; reclaim C[r] < VAH within 15 bars; stop = (highest high of b..r) + buf; target = POC.
OF: a non-thin bar j with H[j] > VAH, D[j] > 0 and big, C[j] <= (H[j] + L[j]) / 2; AND r non-thin with D[r] < 0.
Variant: the app formula on a bar with H[j] > VAH.

### Case 3: stacked imbalances across a value-area edge, then a retest (long above VAH; mirror short below VAL)
- Footprint rows: prices grouped into rows of step / 25 (NIFTY 2 points, BANKNIFTY 4).
- Price: breakout bar b with C[b-1] <= VAH and C[b] > VAH. Retest bar r = the first in b+1..b+30 with L[r] <= VAH + tol
  and C[r] > VAH; the episode dies if a close <= VAH - tol comes first.
- Stop = min(lowest low of b..r, VAH - tol) - buf. Target = E + 2 x (E - stop) (the guide gives no level; 2:1 fixed).
- OF: bar b's footprint has >= 3 consecutive rows with a buy imbalance, row k's buy >= 3 x row (k-1)'s sell (the
  diagonal: the buy one row up against the sell one row down), with row k's buy >= 2 lots (a zero sell counts as
  imbalanced), and the stacked rows' price span overlaps [VAH - tol, H[b]].
- Mirror (short, below VAL): C[b-1] >= VAL, C[b] < VAL; retest H[r] >= VAL - tol and C[r] < VAL within 30, dies on a close
  >= VAL + tol; stop = max(highest high, VAL + tol) + buf; target = E - 2 x (stop - E); sell imbalances: row k's sell >= 3
  x row (k+1)'s buy, >= 3 rows, span overlapping [L[b], VAL + tol]. Case 3 = both sides together.

### Case 4: double bottom at POC with a CVD higher low (long), and its mirror (short)
- Low 1 at bar j1: |L[j1] - POC| <= tol, L[j1] <= min L[j1-3..j1-1], price came from above: max H[j1-15..j1-1] >= POC +
  2 x tol.
- Low 2 at j2 in j1+5..j1+60: |L[j2] - POC| <= tol, |L[j2] - L[j1]| <= tol, L[j2] <= min L[j2-3..j2-1]; a real bounce
  between, max H[j1+1..j2-1] >= max(L[j1], L[j2]) + 2 x tol; no undercut, min L[j1+1..j2-1] >= min(L[j1], L[j2]) - tol.
- Signal r in j2+1..j2+10: the first with C[r] > H[j2] and min L[j2+1..r] >= L[j2].
- Stop = min(L[j1], L[j2]) - buf. Target = today's session high over bars 0..r.
- OF: CVD[j2] > CVD[j1].
- Mirror (short): highs at POC approached from below, CVD[j2] < CVD[j1], signal close below L[j2], target = session low.
- Pairing: the earliest j1, then the earliest valid j2 for it. Case 4 = both sides together.

### Case 5: stop-run trap below the prior-day low (long), and its mirror above the prior-day high (short)
- Sweep bar b: L[b] < PDL - buf with C[b-1] > PDL. Reclaim r = the first in b..b+5 with C[r] > PDL (r = b allowed).
- Stop = (lowest low of b..r) - buf. Target = the nearest of VAL, POC, VAH above C[r] (none: no episode).
- OF ("trapped sellers"): sum of D over b..r <= -1.0 x ref[r], no thin bar in b..r.
- Mirror: H[b] > PDH + buf with C[b-1] < PDH; C[r] < PDH within 5; sum of D >= +1.0 x ref; target = the nearest of VAH,
  POC, VAL below; short. Case 5 = both sides together.

### Bonus A+: value-area edge + OI wall + order flow (16-28 Oct only)
- Long: a Case 1 price episode where the PE with the biggest OI in the first export's chain at minute r (ATM +/- 10
  strikes; the M row of that minute, else the latest within 5 minutes before) is within one step of VAL in spot terms:
  |K - (VAL - basis[r])| <= step. Plus Case 1's OF.
- Short: a Case 2 episode with the CE with the biggest OI within one step of VAH - basis[r]. Plus Case 2's OF.
- Price-only twin: the episodes with the OI condition, no OF. Variant: the app absorb formula in place of the delta rule.
- "Biggest" is within the recorded ATM +/- 10 window only.

### Common filters (price rules, applied to the twin too)
- **2:1:** with E = C[r], skip unless |target - E| >= 2 x |E - stop| with the target on the right side (Case 3: by
  construction).
- No spot at minute r (no ATM strike), or no recorded option price (below): dropped and counted.

### Not testable here
- **Case 6 (naked / virgin POC):** needs many prior sessions' POCs to see which were never revisited; 20 days give too few
  candidate levels to test.

## 5. Option leg, exits and costs

- Strike: ATM = spot at minute r rounded half-up to the step; the index's nearest expiry (what the app records).
  If that strike / right has no 1-second row in the 10 minutes up to the entry second, use the recorded strike nearest
  the ATM within 2 steps (tie: the in-the-money one); none: dropped and counted. Off-ATM trades are counted.
- 1 lot: NIFTY 65, BANKNIFTY 30 (every recorded futures volume is a whole number of these lots; `Ic.kt` and R164 agree;
  note `research/obuy/config.py` still lists BANKNIFTY 35 from Jun 2025 and misses NSE's later cut to 30).
- Option price at second s = `last` of the latest row of that contract at or before s if at most 120 s old, else the
  first row within 60 s after s; else none (entry: dropped; exit: the next priced second).
- Entry second = the start of minute r + 1; buy at the option price there.
- Exits, checked on every futures second from the entry second (its `last`, `high`, `low`):
  1. Stop: long when the low <= stop, short when the high >= stop. Sell at the option price at that second, as a stop.
  2. Target: long when the high >= target, short when the low <= target. Sell at the option price at that second.
  3. Time: 15:15:00 (15:05:00 when the bought option expires that day; expiries in section 0), sell there.
  A second that hits both is a stop.
- Fills: `Fills("liq")` (research/obuy/costs.py: +-5 bps market, -10 bps stops, plus 1-4 ticks by the option's traded lots
  in the 5 minutes before; stops 2x ticks), with the contract's own recorded volume.
- Charges: `Costs("dated")` on the trade date (STT 0.15% on option sells from 1 Apr 2026, NSE 0.03503% + IPFT, SEBI,
  stamp, GST, Rs 20 a side).
- 2x slippage: each side's slippage (fill - raw price) doubled, charges recomputed. Win = net > 0 at 1x.
- Sensitivity (reported, no test): buying at the recorded `ask` and selling at the `bid` of those seconds.

## 6. Controls and tests (all fixed now)

For each case (C1, C2, C3, C4, C5, A+), the OF version vs:
- **(a) Price-only twin:** all price episodes (same 2:1 filter). Reported: count, win %, net at 1x and 2x, average win and
  loss; also the OF-fail subset.
  - **T1 (selection twin):** p = (1 + number of 10,000 random subsets of the price-only trades, of the OF count, whose mean
    net (1x) is >= the OF mean) / 10,001.
- **(b) Random-entry twins:** for each OF trade, 200 entries on the same index, day and side at a random signal bar in
  10..329, with the same stop and target distances (points from that bar's close), the same option rules, exits and costs.
  - **T2:** p = (1 + number of the 200 twin means >= the OF mean) / 201.
- Secondary T1 for the app-absorb-formula variant on C1, C2 and A+.
- Fewer than 3 OF trades: p = 1 (counted all the same). Seed 20261115.
- **Tests: 6 x 2 + 3 = 15.** BH **m = 4,051 (after R180) + 15 = 4,066**. q = p x m / rank (rank among R181's tests; the
  other 4,051 taken as larger, which is conservative), capped at 1; also the q within R181's 15.
- Also reported: every case by index and by day, so one day's move cannot hide.

## 7. Data checks and the source cross-check (reported, no test)

- Missing futures minutes, thin minutes, gap seconds, stale rows, footprint coverage, index coverage, watch-list
  re-centring (signal-time ATM not recorded), days missing.
- **Source cross-check (16-28 Oct):** the OF conditions recomputed with the first export's minute deltas (`orderflow_1m`)
  in place of the 1-second file's: the share of episodes where the two agree, and the OF result with the other source.

## 8. Run log

Every run of `r181.py` on the recordings appends to `results/results.json` ("runs": time, PREREG sha256). Only the first
full run counts; a rerun after a bug fix is disclosed in the report's FINAL READ with what changed.

## Amendment 1 (15 Nov 2026, before any run): the guide's own definitions from its Bonus Module

Boss sent the full PDF. Where the text above left a definition to R181, the guide's own wording now replaces it. Written
and committed before the study code's first run on the recordings; no signal, trade or P&L had been computed.

- **Absorption (guide Pattern 1), used in Case 1 and the A+ long:** heavy aggressive selling, yet price makes no new low.
  The bar condition becomes: a non-thin bar j in b..r with L[j] < VAL, D[j] < 0 and big (|D| >= 1.5 x ref, R181's
  threshold), and **L[j] >= L[j-1]** (its low not below the previous bar's low; previous bar present). This replaces
  "C[j] >= (H[j] + L[j]) / 2". The reclaim condition D[r] > 0 stays.
- **Exhaustion (guide Pattern 2), used in Case 2 and the A+ short:** price keeps pushing higher while positive delta
  shrinks on every candle (the guide's example: +1,295, +904, +622, +162, +12). The bar condition becomes: **5
  consecutive non-thin bars j-4..j, each with D > 0, strictly falling D and strictly rising highs**, with j in b..r and
  H[j] > VAH (no size threshold). This replaces "D[j] > 0 and big, C[j] <= mid". The reclaim condition D[r] < 0 stays.
- **A+ targets:** T1 = the prior day's POC, T2 = VAH (long) / VAL (short). With one lot (it cannot be split), R181's
  translation: the 2:1 check uses T1; when the futures touch T1 the index stop moves to the entry level E (the signal
  close) and the trade runs on to T2, that stop, or the time exit. The A+ price-only twin and random twins use the same
  two-stage exit.
- "Price magnets" (single prints, imbalances, naked POCs) are context only in the guide; nothing is added for them.
- No other change: same cases, twins, tests (15) and BH m = 4,066.

## Amendment 2 (15 Nov 2026, AFTER the one run; post hoc, descriptive only, no new test)

The run's level table showed the pre-registered contract shift (today's first-minute basis minus the prior day's
last-minute basis) moving the levels by 100-160 BANKNIFTY points on ordinary days. The recorded basis (futures minus the
index row) is noisy at the open and the close (for example 15 Oct: about 136 at midday, 260 at 15:27, 70 at 15:29), so the
rule mostly added noise. The primary result stays as pre-registered. One sensitivity is added and labelled post hoc:
- **No shift on the same contract** (the true day-on-day basis change is a few points); on the roll day (28 Oct) shift by
  (the median basis of today's bars 0-9, before the first signal bar) minus (the prior day's median basis over bars
  45-344, 10:00-15:00).
- Reported as counts and rupees only, with no p value and no BH entry; it cannot change any verdict to "worth a forward
  test" on its own.
