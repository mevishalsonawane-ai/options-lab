# R184 pre-registration: absorption and exhaustion (order-flow reversals) as option-buying rules

*16 Nov 2026. Written and committed (and pushed) BEFORE any R184 signal, trade or rupee was computed. Research only:
nothing in `android/**` changes. Owner: Boss (they/them).*

Boss, 16 Nov 2026: "Now check absorption and exhaustion technique in options buying."

## 0. What was looked at before this file (no outcome)

- R181 (`research/hunt/r181_orderflow_cases/`) and R182 (`research/hunt/r182_delta_study/`): their PREREG, code and
  reports. Known from them (their published results, not R184 outcomes):
  - R181's guide versions of absorption (one heavy-selling minute with no new low, inside a failed breakdown at the
    prior day's VAL) and exhaustion (5 minutes of shrinking positive delta with rising highs, at VAH) **never fired** on
    these 20 days. R184 tests the two ideas on their own, anywhere in the day, not tied to the prior day's profile.
  - R182: the 1-second file's tick-rule delta tracks the same minute's price change only loosely (rank correlation
    +0.27); the 1-second recorder DROPS 17-35% of futures volume, mostly big prints at an unchanged price (the app's
    "illiquid print" guard) - which is exactly the print an absorption reading would want. The minute recorder (complete
    volume, better delta) stops on 28 Oct, so it cannot cover a design / holdout split. R184 uses the 1-second file and
    says so: **its "absorption" is absorption of the volume the recorder kept.**
  - R182: delta did not forecast the next 15 minutes on any index (q 1.0).
- The data's layout (README, header, a few rows) and one structure check, no outcome: the option roles CE / REC_CE (and
  PE / REC_PE) of the same strike are the SAME token (0 of 148 checked keys on 21 Oct, 5 and 12 Nov had two tokens), so
  R184 merges them per (right, strike). R181's loader kept only one role per strike (a later role's rows replace the
  earlier's); R184 does not reuse that part.
- Nothing else. No signal count, no trade, no price path after any candidate signal was computed before this commit.

**Expected (said now):** order flow in this repo has explained the same minute but forecast little (R7, R8, R10, R61b,
R77, R125, R164, R181, R182). With 13 design days and 7 holdout days the honest outcome is very likely "no evidence
either way". The study is still worth running once, cleanly, so the question has a recorded answer.

## 1. Data (untrusted, read-only, outside git; read with `python -I`)

- 1-second order flow `orderflow-YYYY-MM-DD.csv.gz`, 20 trading days: 15, 16, 19, 21, 22, 23, 26, 27, 28, 29, 30 Oct and
  2, 3, 4, 5, 6, 9, 11, 12, 13 Nov 2026 (no recording 20 Oct, 10 Nov).
- Instruments: **NIFTY and BANKNIFTY only** - the near index future (role FUTURE: the signal), the index (role INDEX:
  the ATM strike) and the recorded near-ATM options (roles CE / PE / REC_CE / REC_PE: the trade). FINNIFTY and MIDCPNIFTY
  have futures flow but no recorded options, so no trade can be priced: not used.
- Session seconds 09:15:00 - 15:29:59; minute bars i = 0..374.
- Per-trade rows stay outside git (`~/.cache/iraalgo/r184/`); the repo gets aggregates only.

### Split (walk-forward, fixed now)
- **Design: the first 13 days, 15 Oct - 3 Nov 2026.** Every variant is run here and the selection is made here.
- **Holdout (locked): the last 7 days, 4 - 13 Nov 2026.** Read ONCE, only for the variant chosen per setup and index,
  after `results/FINAL_READ` (a timestamp) is committed and pushed.
- Disclosed: NIFTY ran up strongly from about 28 Oct; the holdout is mostly a one-way rally (R181). 9 Nov has many
  missing futures minutes (82). Any split this short is at the mercy of a few days' regime.

## 2. Bars and order-flow series (signal source: the INDEX FUTURE's own price and order flow)

Every rule below reads only the near index future (NIFTY / BANKNIFTY), which is the underlying (CLAUDE.md: signal on the
underlying, trade the option). Option prices are used only to price the trade; the premium stop and breakeven lock act on
the option's premium (allowed by CLAUDE.md).

- Futures minute bar i (R181's `bars_from_seconds`): O = first `last`, C = last `last`, H / L = max / min of `last`,
  `high`, `low` in the minute; delta D = sum(`buy_vol` - `sell_vol`) (the app's tick-rule signing); rows = recorded
  seconds. No row: a NaN bar, D = 0.
- **Thin** bar: fewer than 20 recorded seconds, or no close. Every condition that names a bar requires it NOT thin.
- `ref1(i)` = mean |D| of the day's non-thin bars 0..i-1 (expanding, the day's own); needs at least 20 such bars, else
  NaN (no signal).
- `S_w(j)` = D[j-w+1] + ... + D[j] when all w bars are non-thin, else undefined. `ref_w(i)` = mean |S_w(j)| over the
  defined windows ending at j = w-1..i-1 (the day's own, expanding); needs at least 20, else NaN.
- **Band** = beta x the reference price (beta in basis points; at NIFTY 24,000 3 bp = 7.2 points, 6 bp = 14.4 points;
  BANKNIFTY 56,000: 16.8 / 33.6 points). It scales the "small band" with the index level.
- Signal bars r in 25..329 (09:40 - 14:44). A signal at bar r uses bars 0..r only; the option is bought at the start of
  minute r + 1 (second t0 + 60 (r + 1)).

## 3. The two setups (exact)

### ABSORPTION (window w, heavy k, band beta, confirm)
**Selling absorbed -> buy a CALL** at bar r (with p = r - w, the bar before the window):
1. Bars r-w+1..r all non-thin; C[p] finite.
2. Heavy aggressive selling: S_w(r) <= -k x ref_w(r).
3. Price held: min(L[r-w+1..r]) >= C[p] - beta x C[p] (it never traded more than the band below where the window began).
4. Confirm "none": signal at r. Confirm "turn": signal at r' = r + 1 only if bar r + 1 is non-thin and C[r+1] > C[r]
   (the first up close after the absorption); otherwise no signal.
5. Index stop level = min(L over the window and the confirm bar) - beta x C[p].

**Buying absorbed -> buy a PUT**: the mirror (S_w(r) >= +k x ref_w(r); max(H[r-w+1..r]) <= C[p] + band; "turn" =
C[r+1] < C[r]; index stop = max H + band).

Grid: w in {3, 5} minutes; k in {2, 3}; beta in {3, 6} bp; confirm in {none, turn}.

### EXHAUSTION (lookback X, spike k, band beta, confirm)
**Selling exhaustion at a low -> buy a CALL.** A climax bar c:
1. Bar c non-thin, its low a new X-minute low: L[c] < min(L[c-X..c-1]) with at least X/2 of those bars present and
   c >= X.
2. A spike of selling: D[c] < 0 and |D[c]| >= k x ref1(c).
Then the signal bar r is the FIRST bar in c+1..c+3 such that, over the bars c+1..r:
3. all non-thin;
4. selling dried up: every D[j] > D[c] / 2 (less than half the climax's selling);
5. price stalled: min(L[c+1..r]) >= L[c] - beta x C[c] (no new low beyond the band);
6. delta flipped: D[r] > 0;
7. confirm "flip": nothing more. Confirm "flip+close": also C[r] > C[c].
If at some bar in c+1..c+3 condition 3, 4 or 5 fails, that climax is dead (no signal from it).
8. Index stop level = min(L[c..r]) - beta x C[c].

**Buying exhaustion at a high -> buy a PUT**: the mirror (new X-minute high, D[c] > 0 spike, every later D[j] < D[c] / 2,
max(H[c+1..r]) <= H[c] + band, D[r] < 0, "flip+close" C[r] < C[c]; index stop = max H + band).

Grid: X in {15, 30} minutes; k in {2, 3}; beta in {3, 6} bp; confirm in {flip, flip+close}.

### Taking signals
- Per variant, index and day, signals are taken in time order (calls and puts together): a signal is taken only if its
  entry second is at or after the previous taken trade's exit second (one trade at a time), at most 4 trades a day.
- A signal is counted as a **signal**; it becomes a **trade** if it is priced (section 4). Unpriced signals are counted
  by reason (no spot, no strike, no price) and do not block later signals.

## 4. The option trade and the exits (fixed)

- Strike: ATM = the index's last `last` at or before the end of minute r (at most 120 s old) rounded half-up to the step
  (NIFTY 50, BANKNIFTY 100); the recorded option (CE for a call, PE for a put; nearest expiry, what the app records)
  at that strike if it has a row in the 10 minutes up to the entry, else the recorded strike nearest the ATM within 2
  steps (tie: in the money); none: unpriced ("no strike"). 1 lot: NIFTY 65, BANKNIFTY 30.
- Entry: the option's `last` at the entry second (the latest row at most 30 s old, else the first row within 10 s
  after; none: unpriced "no price"). Fill = the `last` plus R181's liquidity slippage (`Fills("liq")`, research/obuy/
  costs.py) by the option's traded lots in the 5 minutes before.
- Exits, each checked from the second after the entry; the first wins (a stop wins a tie):
  1. **Premium stop -15%:** the option's `last` <= 0.85 x fill.
  2. **Breakeven lock:** once the option's `last` >= 1.10 x fill, the stop moves up to breakeven = fill + the round
     trip's charges per unit (it never moves down); exit when `last` <= breakeven after that second.
  3. **Index stop:** the future's second `low` (call) / `high` (put), `last` folded in, crosses the setup's index stop
     level (section 3).
  4. **Time stop T** after the entry, T in {15, 30} minutes (part of the grid).
  5. Day end: 15:15:00 (15:05:00 when the bought option expires that day: NIFTY 19 Oct, 27 Oct, 3 Nov, 9 Nov; BANKNIFTY
     27 Oct; R181's expiry list).
- Exit price: the option's `last` at the exit second (a stop triggered by the option's own print sells at that print; a
  fast market can sell below the stop); index stop and time: the option price at that second (as the entry rule). Sell
  slippage `Fills("liq")` (stops: the stop slippage).
- Charges: `Costs("dated")` (STT 0.15% on option sells from 1 Apr 2026, NSE transaction charge, SEBI, stamp, GST, Rs 20
  a side). Net = (sell fill - buy fill) x lot - both sides' charges. Win = net > 0.
- Sensitivities (reported, no test): 2x slippage; buying at the recorded `ask` and selling at the `bid`.

**Variants: 16 signal settings x 2 time stops = 32 per setup**, the same for both indices. 2 setups x 2 indices x 32 =
**128 design variants**.

## 5. Selection on the design days (fixed now)

Per setup and index: among the 32 variants with **at least 12 design trades**, choose the one with the highest design
net (1x slippage, rupees summed); a tie goes to more trades, then the first in the grid order (w / X, k, beta, confirm,
T, smallest first). If no variant has 12 trades, choose the one with the most design trades (tie: higher net). The
choice and every variant's design numbers go to `results/selection.json` and are committed before the holdout read.
Descriptive on the design days (biased by the selection, no test claimed): the chosen variant against its random twins.

## 6. The holdout read and the tests (fixed now)

For each setup x index (4), the chosen variant on the 7 holdout days:

- **Random-entry twins (T1, the primary test):** a twin set has, for each rule trade, one entry on the same index and
  side (CE / PE), at the same signal minute r, on a day drawn at random from the same period's other days, with the same
  exits (the index stop at the same distance in points from that day's C[r]; premium stop, breakeven, T, day end). A
  draw that cannot be priced is redrawn (up to 50 tries, then left out). 1,000 twin sets; p1 = (1 + sets whose mean net
  >= the rule's mean net) / 1,001 (one-sided: the rule beats chance). So the twins match the count, the time-of-day
  distribution, the side mix and the exits.
- **Do nothing (T2, secondary):** is the mean net above 0? Day-block bootstrap of the holdout's per-day net sums (10,000
  resamples of days); p2 = share of resampled means <= 0 (one-sided). Days with no trade count as 0.
- Fewer than 3 holdout trades: p = 1. Seed 20261116.
- **Multiple testing:** tests = 4 x 2 = 8; the 128 design variants count as ideas tried. **BH m = 4,376 (after R183) +
  128 + 8 = 4,512.** q = p x m / rank (rank among R184's 8 tests, the others taken as larger), step-up, capped at 1;
  also the q within R184's 8.
- Report per setup x index for design (all 32 variants, and the choice) and holdout (the choice): signals, trades, win
  rate, net after charges (1x, 2x, bid / ask), mean, average win and loss, worst day, trades by side and exit reason,
  the twins' mean and 10th / 90th percentiles; and the pooled per-setup holdout (descriptive).

### Bars (fixed now)
- **"Passes the gates"** (could go to paper by the repo's rules): >= 40 holdout trades, holdout mean net > 0 at 2x
  slippage, T1 q (m = 4,512) <= 0.10 and T2 q <= 0.10, the design choice's mean net > 0, and the holdout's worst day
  above -Rs 13,000. Said now: with 7 holdout days this is very unlikely to be reachable.
- **"Worth a forward test"** (a shadow note only, re-run as recordings grow): >= 10 holdout trades, holdout mean net at
  2x slippage > 0, above the twins' mean, p1 <= 0.20, and the design choice's mean net > 0.
- Otherwise **"no evidence"** (or **"too few trades"** under 10 holdout trades).
- Nothing in the app changes in any case; any arm would need a new Boss decision.

### Power (fixed formula now)
The smallest mean edge per trade the holdout could detect with 80% power at one-sided 5%: 2.49 x sd / sqrt(n) (sd =
the holdout trades' standard deviation of net, n = trades), and at the BH level for rank 1 (alpha = 0.10 / 4,512: z =
3.85) (3.85 + 0.84) x sd / sqrt(n). Reported next to each mean.

## 7. Descriptive (no test)

- **Overlap with the arms:** the paper entries of R51 Donchian 20, R72 Breadth Donchian and Liquidity 15+5 from the
  phone's diagnostics (paper orders only, R182's loader and trade rebuild; 19 Oct - 13 Nov) on NIFTY / BANKNIFTY: the
  share of R184's trades (chosen variants, both periods) with an arm entry on the same index and the same right (CE / PE)
  within 15 minutes, and with the opposite right (the arm going the other way).
- Data quality per day: missing / thin futures minutes, option coverage, unpriced signals by reason.
- By side (call / put) and by exit reason.

## 8. Look-ahead guard and run log

- Tests (`test_r184.py`) check: every signal at bar r is unchanged when all bars after r (or after r + 1 for "turn")
  are replaced by random values; `ref1` / `ref_w` read only earlier bars; exits never read a second before the entry.
- `r184.py --stage design` runs the design days only (it refuses holdout days). `--stage holdout` refuses to run unless
  `results/FINAL_READ` exists and `results/selection.json` exists; it writes `results/holdout.json` with this file's
  SHA-256 and the selection's SHA-256. The holdout stage is run once; a rerun after a bug fix is disclosed in the report
  with what changed.
- Any change to this plan after this commit is an amendment, dated, with its reason, and says whether any number had
  been seen.
