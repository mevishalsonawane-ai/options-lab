# R189 pre-registration: Boss's Hull line ("BBI"), 2 closed candles above a rising line, buy the option

Written 16 Nov 2026, **before any R189 signal, trade or P&L number was computed**. Committed and pushed on
`claude/r189-hull-bbi` on its own, before the code's first run on data. Research only: nothing in `android/**` changes.
Owner: Boss (they/them).

**Numbering.** No R189 exists on origin (branches checked 16 Nov: the highest is `claude/r188-s5-cisd`) or in
`research/experiments/entries/` (the highest there is r182). R174 (a 5m / 1m / 10s test from another session) is not in
the repository or on any origin branch. This study is **R189**; every entry id starts `r189-`.

## 0. Boss's question

Boss pasted this TradingView Pine indicator:

```
length = input(55)  // HMA Length
hullma(src, length) =>
    wma1 = ta.wma(src, length)
    wma2 = ta.wma(src, length / 2)
    hull = ta.wma((2 * wma1 - wma2), math.round(math.sqrt(length)))
hma = hullma(close, 55)
trend = hma > hma[1] ? 1 : hma < hma[1] ? -1 : 0   // blue = 1 (rising), red = -1
```

and said: *"Check only if 1min 2 candles and 10sec 2 candles are above blue lines created by pinescript and use this for
both put and call options and check whats the results"*, then *"When both 1min and 10 sec both are up the blue line, only
then buy"*. Boss gave no exit. On 16 Nov Boss added: *"Find the exit by research"*, so the exit is chosen by a
pre-registered rule on the design folds only (section 4), not fixed up front.

**Signal source for every rule: the index spot** (NIFTY 50, NIFTY BANK, NIFTY FIN SERVICE, NIFTY MIDCAP SELECT, SENSEX;
CLAUDE.md default). The Hull line, the candles, the entry and every index-based exit are computed on the index's own
1-minute bars. The option chosen by the strike rule is only what is bought and sold. Premium stops, targets and locks
are set on the option premium (CLAUDE.md allows it). No rule reads an option's own chart.

## 1. The 10-second half: the honest limit, stated first

Boss's rule needs 10-second candles of the index. **None are reachable from this session**, so the 10-second half
cannot be tested and is **not faked from 1-minute bars**. What was searched (16 Nov 2026):

- R174 (the earlier 5m / 1m / 10s study): not on main, not on any of the 305 origin branches, no commit mentions it.
- The repo's `research-data` release: 1-minute data only. The `dhan-data` branch (Dhan's v2 data, fetched 6 Oct 2026):
  1-minute candles and 1-minute rolling options only. `market-data-mtf`: BTC and XAUUSD only. No other branch holds
  sub-minute index data.
- The phone's tick zone (per-second NIFTY / BANKNIFTY futures, ATM +/- 3 options, India VIX, recorded since 12 Oct)
  is uploaded as `live-YYYY-MM-DD.irlive` to the private repo `mevishalsonawane-ai/IraAlgo` (releases `live-2026-10`,
  14 days, and `live-2026-11`, 9 days). Each file is sealed with AES-256-GCM under a key from Boss's backup passphrase
  (format `IRLIVE01`, `research/data/decrypt_live.py`); one file's header was checked and it is sealed. This session
  has no passphrase and **no attempt is made to open it**.

So R189 tests the **1-minute half alone**, labelled everywhere **"1-min only, no 10-sec data"**. Boss's full rule
(1-minute AND 10-second agreeing) is stricter: it can only keep a subset of these entries, at the same or a few seconds'
later moment. The 1-minute result is therefore an upper bound on how often the full rule trades, not a test of it.

**What would be needed for the full rule:** 10-second OHLC of the index spot (or near futures) for each traded day,
09:15-15:30, for at least a year (about 250 sessions; the 1-minute rule's trade count says a few months would give too
few trades to pass the 40-trade gate on calls or puts alone), plus the option's 1-minute (or finer) prices for fills.
The phone's tick zone already records per-second NIFTY and BANKNIFTY futures; with Boss's passphrase,
`research/data/decrypt_live.py` on Boss's own PC could export them (10-second bars are a resample of the per-second
LTP). Today that would be about 23 sessions (13 Oct - 13 Nov 2026): enough to look, not enough to judge.

## 2. Data (reachable, real)

- **The `dhan-data` branch of options-lab** (Dhan v2 data APIs, fetched 6 Oct 2026; `README.md` there): read with
  `research/obuy/data.py` (the obuy lab's loader, unchanged): rolling ATM-10..ATM+10 1-minute option bars (open, high,
  low, close, volume, OI) of the **nearest** expiry ("near": the weekly series where Dhan has it, else the monthly), and
  the index 1-minute candles (`candles/minute/IDX_I`). Copied out of git into the session scratchpad with `git archive`
  (never committed), read only with `python -I`.
- Indices: **NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX** (the five R51 trades; options exist for all five through
  6 Oct 2026: NIFTY and SENSEX weekly, BANKNIFTY, FINNIFTY and MIDCPNIFTY monthly after Nov 2024).
- This source has **no gap** in 2026 (the 24 Feb - 5 Sep 2026 hole in `docs/design.md` belongs to the lake's own live
  collection, not to Dhan's history): 128 sessions from 1 Apr to 6 Oct 2026 for each index (counted, no P&L read).
- Index bars from 2 Sep 2024 warm the Hull line; trades only from 20 Nov 2024 (NSE's weekly-expiry change; R51's window).
- Lots: the lot in force on the trade date (`research/hunt/r51/r51cfg.py` `lot`), at **1, 2 and 3 lots**.
- Expiry days: detected from the chain (obuy `expiry_from_chain`: the cheapest straddle at the last common minute is
  under 0.75 x the strike step). They are traded with the 15:05 square-off (CLAUDE.md; R51 `SQ_ROW_EXPIRY`).

## 3. The rules (fixed now)

### The Hull line (Boss's script, exactly)

- `ta.wma(x, n)` = sum over i = 0..n-1 of (n - i) x x[t - i], divided by n(n + 1)/2; **na** until n values exist, and na
  whenever a value in its window is na (Pine's rule).
- `length / 2` with `length = input(55)`: `input(55)` is an int input, and Pine v5 needs a simple int for `ta.wma`'s
  length; 55 / 2 as integer division is **27** (and if Pine produced 27.5 it would truncate to 27): **27 either way**.
  `math.round(math.sqrt(55))` = round(7.416) = **7**.
- **Boss's line (PRIMARY, "BOSS")**: `hull = WMA(2 x WMA(close, 55) - WMA(close, 27), 7)`.
- **Standard Hull MA (variant "STD")**: `hull = WMA(2 x WMA(close, 27) - WMA(close, 55), 7)`.
- The difference in one line: **the standard Hull doubles the fast (27) average and subtracts the slow (55) one to cut
  lag; Boss's script does the reverse (doubles the slow one), so its line is smoother but lags MORE than a plain
  55-bar WMA, not less.**
- Colour: **blue** at bar t when hull[t] > hull[t-1] (rising), **red** when hull[t] < hull[t-1], neither when equal.
- The line runs on the **continuous series of existing 1-minute index bars across days** (as on a TradingView 1-minute
  chart): a missing minute is skipped, not filled. Rows are 09:15 + r, r = 0..374.

### Entry (1-min only, no 10-sec data)

At the close of 1-minute candle t of the day (both t and t-1 present that day):

- **CALL condition** (primary "CLOSE" reading): close[t] > hull[t] **and** close[t-1] > hull[t-1], **and** the line is
  blue at both t and t-1.
- **PUT condition (mirror)**: close[t] < hull[t] and close[t-1] < hull[t-1], and the line is red at both t and t-1.
- **Variant "LOW" (the whole candle)**: call needs low > hull on both candles (put: high < hull), same colour rule.
- **Fresh signal**: an entry needs the condition true at t and false at t-1 (the condition at t-1 uses candles t-1 and
  t-2). A condition that stays true never re-enters; after an exit the next entry needs a new false -> true change.
- **One position at a time per index** (calls and puts share it). A fresh signal while a position is open is ignored
  (and is not saved for later).
- **Times**: the order is placed for minute t + 1 and filled at the option's open there, or at the first traded minute
  up to 2 minutes later (R51's fill rule; else no fill, counted, and the index stays free). Order minutes 09:20 - 15:00
  only (decision candles t = 4 .. 344): no entry before 09:20 (the brief) and none after 15:00 (the arms' usual last
  entry: CisdNet / R131 `LAST_ENTRY` 15:00; DonchianArm allows 15:10 but squares off at 15:15, so 15:00 is used).
- **Strike**: ATM = the index close of candle t rounded to the step (NIFTY / FINNIFTY 50, BANKNIFTY / SENSEX 100,
  MIDCPNIFTY 25), of the nearest expiry. **Variant "ITM"**: 1 in the money (call ATM - step, put ATM + step). A strike not
  in the stored chain that minute: no fill (counted).

### Exit candidates (Boss: "Find the exit by research")

Every candidate keeps **the repo's usual -15% premium stop** (a resting stop at 0.85 x the option's raw fill, on the
option's minute low; a gap through it fills at the minute's open; sold as a stop sale) and **the square-off** (15:10,
15:05 on an expiry day; R51 `SQ_ROW` / `SQ_ROW_EXPIRY`). Whichever exit comes first wins; a minute that hits the stop and
a target counts as the stop. Index-based exits read the index's closed 1-minute candles from the fill minute on and sell
at the next minute's open.

| id | exit (on top of the -15% stop and the square-off) |
|---|---|
| X01 FLIP1 | the 1-min line turns against the trade (red for a call, blue for a put) |
| X02 CROSS1 | a 1-min candle closes back across the line (below for a call, above for a put) |
| X03 FLIPX1 | X01 or X02, whichever first (the brief's original primary) |
| X04 STOP15 | nothing else: the -15% stop alone |
| X05 T10 | a limit target at +10% of the raw fill (filled at its price) |
| X06 T20 | target +20% |
| X07 T30 | target +30% |
| X08 ISTOP | an index stop at the signal candle's low (call) / high (put); sold as a stop sale |
| X09 ISTOP15R | X08 + an index target at entry + 1.5R (R = index entry at the fill minute's open - the stop; put mirrored) |
| X10 ISTOP2R | X08 + an index target at entry + 2R |
| X11 LOCK50 | a step lock: from a best gain of +20%, keep half of it, in 10% steps (+20% best -> +10% kept, +30% -> +15%, ... to +500% -> +250%); the lock rests as a stop |
| X12 LOCK60 | from +10%, keep 60%, in 10% steps (+10% -> +6%, +20% -> +12%, ... to +500% -> +300%) |
| X13 TIME5 | sell at the close of the 5th minute held (market) |
| X14 TIME10 | the 10th minute |
| X15 TIME15 | the 15th minute |
| X16 TIME30 | the 30th minute |
| X17 FLIP10S | the 10-sec line turns against: **not testable (no 10-sec data)**, not run, not counted |
| X18 CROSS10S | a 10-sec candle closes back across: **not testable**, not run, not counted |

Details: X08-X10: if R <= 0 (the index already opened beyond the stop) the stop fires on the first minute; no target
then. A best gain is read from minutes already completed (the lock moves at the next minute; R51's `simulate` steps).
The locks, targets, -15% stop, time stops and fills are R51's `simulate` (research/hunt/r51/engine.py) unchanged; the
index-based exits are given to it as a forced exit minute.

### Rule sets (19 run)

- **E-BOSS x X01..X16**: the primary entry (Boss's line, CLOSE reading, ATM) with each of the 16 testable exits.
- With **the exit research picks** (section 4), three entry variants: **STD** (the standard Hull line, for entry and
  any line-based exit), **LOW** (the whole candle) and **ITM** (1 in the money).

### Costs (R51's engine, unchanged)

R17 fills (buy x1.0005 plus a half-spread of 1 / 2 / 4 ticks for >= 20 / some / no lots of position size traded in the
5 minutes before; market sales x0.9995 minus the half-spread; stop sales x0.999 minus twice the half-spread; limit
targets at their price) and Zerodha charges `Costs("dated")` (the rates in force on each date) on the whole quantity.

## 4. Periods, the exit choice and the one holdout read

- **Full period**: 20 Nov 2024 - 6 Oct 2026 (Dhan's last session).
- **Design**: 20 Nov 2024 - 31 Mar 2026, in four folds: (1) 20 Nov 2024 - 31 Mar 2025, (2) 1 Apr - 31 Jul 2025,
  (3) 1 Aug - 30 Nov 2025, (4) 1 Dec 2025 - 31 Mar 2026.
- **Final (locked holdout, read ONCE) = the last 6 months**: 1 Apr - 6 Oct 2026. `results/FINAL_READ` is written before
  the read; the code refuses a second read. Honesty: these months were read by other studies for other rules (R51, R151,
  R176, ...); this rule was never run on them.
- **Exit selection rule (fixed now, design only)**: on the headline cell (E-BOSS, all five indices, calls + puts,
  1 lot), each candidate's four design fold nets f1..f4. Among the candidates with **all four folds > 0**, pick the
  **highest median fold net**; if none has all four > 0, pick the **highest design net**. Ties: the lower X number.
  "The next two candidates" are the 2nd and 3rd by the same ordering.
- **Selection walk-forward (gate 6 for the picked exit)**: for fold k = 2, 3, 4, pick an exit by the same rule using
  folds 1..k-1 only and take that exit's fold-k net; the walk-forward net is their sum. For every other rule set (a fixed
  rule, nothing chosen) gate 6 is its design net.
- The single final read computes every rule set. **Only the picked exit (and its three entry variants) is the
  verdict**; every other candidate's holdout is reported as secondary, not gated (its gate result is still computed and
  still counted in BH).

## 5. Tests and cells

Each rule set is judged in **54 cells**: index {NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, all five} x side
{calls, puts, both} x lots {1, 2, 3}: **19 x 54 = 1,026 tests**, all in the BH. The headline is the picked exit, E-BOSS,
all five indices, calls + puts.

## 6. Twins (random entries, the p-value)

Per (index, side) unit and rule set, 2,000 twins, seed 189: the same final-period days and the same number of entries
per day; entry candles drawn uniformly without replacement from that day's allowed candles (t = 4 .. 344), the same
side, strike rule and exit candidate (the line-based and index exits read from the drawn candle on; the index stop from
the drawn candle), the same lots. Overlap between twin trades is not prevented (R44 / R177). A cell's twin total is the
sum of its units' twins with the same draw number. **Empirical p** = (1 + twins with net >= the rule's) / 2,001,
one-sided. The smallest empirical p (0.0005) can never pass BH at this m, so the **BH p** is the normal tail of
z = (rule net - twin mean) / twin sd; both are reported (R177's rule).

## 7. Gates (all must hold, per cell and lots; R176 / R177's)

1. Final net > 0.
2. Empirical twin p < 0.05.
3. BH q <= 0.10 across **m = 4,686 + 1,026 = 5,712** (4,686 = the entry files in `research/experiments/entries/` at the
   branch base, origin/main e4e4f1d24; each earlier test taken as p = 1). q within R189 alone is also shown.
4. At least 40 final trades.
5. Final net without its top 5 trades > 0.
6. Design walk-forward net > 0 (section 4).
7. The account stop rule at the tested lots, in design and in the final: max drawdown (daily net) <= Rs 40,000 and
   worst calendar month >= -Rs 13,000.

## 8. What was already tested near this (cited, not re-run)

| study | rule | result |
|---|---|---|
| H25 | 15,120 HMA variants (crosses, slopes; 5-60 minute index bars) | 0 of 28 HMA families passed |
| R52 H25-3 | SENSEX 60-minute HMA 13/34 cross, fade, re-tested on Nov 2024+ | final 49 trades, +Rs 3,266, q 0.63: rejected |
| R44 / R51 / R177-EMA | moving-average crossovers on 5- and 15-minute index bars | lost after costs in every test |
| R172 / R176 | tight rupee stops and early locks on R51 / R72 | hurt |

Expected (said now, before any number): 1-minute moving-average entries on index options have lost money after costs in
every earlier test here; a 2-candle confirmation on a lagging line usually enters late in a move. The most likely outcome
is that every exit loses after charges and slippage, and that quick exits (time stops, small targets) lose least because
they pay the spread on smaller moves. This is stated so the reader can weigh the result; it changes no rule.

## 9. What is reported

For every rule set x index x side x lots, in the full period, the design (and its 4 folds) and the last 6 months:
trades, win % (net > 0), net after charges and slippage, average win / average loss, worst day, max drawdown (daily
net), worst calendar month, net without the top 5, exit mix, no-fill count. Gate verdicts, p and q. Which exit research
picked and why; the picked exit's holdout vs the next two candidates. `results/summary.md` (plain English for Boss,
outcome first), `results/report.md` (full tables), `results/decision.json`, one experiment entry per rule-set group.

## 10. Tests (pytest, in this folder) and the look-ahead guard

Unit tests with edge cases: Pine's WMA (na warm-up, na inside the window), Boss's and the standard Hull line, the 27 / 7
lengths, colours with equal values; the entry conditions (CLOSE and LOW readings, both colours), the fresh-signal rule,
missing minutes, the 09:20 / 15:00 window, one position per index and the busy rule; the strike rule (ATM / ITM, a
missing strike); every exit candidate's forced minute (flip, cross, index stop with R <= 0, R targets, a stop and target
in one minute, no index minute); fills with no trade in the slack; expiry-day square-off; empty data; the exit selection
rule and its walk-forward (all folds > 0, none qualifying, ties); month / drawdown / worst-day helpers; BH with m; the
gates; the twin sampler (same days and counts, seed reproducible). **Look-ahead guard**: on synthetic days, replacing
every index minute after a decision candle with noise leaves that candle's decision (line, colour, condition, strike)
unchanged, and replacing every minute after a trade's exit leaves the exit minute and price unchanged.
