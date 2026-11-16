# R188 pre-registration: can S5 Trail and CISD + network make more money AND win more often?

Written 17 Nov 2026, **before any R188 number was computed** (no parity run, no trade, no lever value). Research only:
nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 17 Nov 2026 (verbatim): *"Cam we make s5,csid more profitable and more winrate"*.

The two arms are PAPER arms by Boss's exceptions (CLAUDE.md, 28 and 29 Oct; both failed their research gates):

- **S5 Trail** (R123 N1-S5 + R128 T2-S5x1): at 09:20 / 09:25 / 09:30 on the five indices, R123's network N1 scores 8
  target / stop cells on both sides; the best cell-side is bought (ATM option, 1 lot) when its expected net is at least
  Rs 300. Exits: the cell's stop (-Rs 1,000 or -Rs 2,000 net); once up Rs 1,000 net (marked at minute closes), out when
  the net falls to 60% of its best; else the square-off.
- **CISD + network** (R133 S3-V2-E3): R131's CISD (ICT's reading) on the index's 5-minute bars, kept only when R123 N1's
  mean EV of the side's 8 cells is above 0 (R132 Amendment A); R131's index stop; once the option's best price (earlier
  minutes' highs) is up Rs 1,000 on the lot, out at the entry + 60% of that gain; else the square-off. One trade at a
  time per index, applied before the filter.

**Signal source.** Both arms signal on the index (S5: the network reads the index, India VIX and breadth; CISD: the
index's 5-minute bars). Option-chain data enter only as network inputs (context) and in one entry filter below (FT3PO:
the bought option's own price and OI, used as context around the index signal, as R144c's FT3 skip). Stops and locks
are on the option premium, except the index stop / index lock, which are on the index.

## 1. Data and the faithful replays (step 1; parity before anything else)

- **R123's network outputs:** R187 could not simulate these arms because R123's predictions were not local. They are
  on Drive: `gdrive:IraAlgo-data-lake/derived/r123/cleaned/` holds R123's own saved walk-forward predictions
  (`wf_rows_preds_N1.parquet`, folds F1-F4: 2023 - 15 Mar 2026), its holdout-fit predictions
  (`holdout_rows_preds_N1.parquet`, 1 Apr - 19 Oct 2026), its trade lists and the holdout weights `net_N1_holdout.pt`.
  Using R123's own saved outputs is more faithful than re-training (bit-identical, no seed drift), so they are used
  as they are; the walk-forward is R123's (models fitted on earlier data only, 10-day embargo). Nothing is re-fitted.
- **New sessions (21 Oct - 13 Nov 2026):** R123 N1 as it scored R123's holdout, rebuilt by R134's `nnlive.py` from its
  saved weights (frozen since 29 Oct), on decision rows built by R134's `build_rows` from the lake plus the 16 Nov gap
  files (`*_gap1113` index and weekly options; India VIX from `fill1113`). Check first: the same pipeline on 1 - 19 Oct
  2026 must reproduce R123's saved holdout predictions (max P(win) difference < 1e-3, EV sign agreement >= 99%), else
  the new month is reported as not possible.
- **Option bars:** the lake (weekly series; for CISD on a weekly expiry day the monthly series, R131's rule), cleaned by
  `lakeclean` steps 1-3 exactly as R123 / R131 read it. 1 lot at R123's lots, dated Zerodha charges (`Costs("dated")`),
  liquidity-aware fills (`Fills("liq")`), plus 2x slippage reported. The gap files have no monthly series, so a CISD
  setup that would need the monthly contract in the new month is reported as not priced.
- **Parity (must hold before any lever is computed):**
  - S5: R123's S5 trade list rebuilt from the saved predictions equals R123's saved `trades_N1-S5_*` (same trades);
    the R128 T2-S5x1 replay gives R128's published numbers: walk-forward 555 trades, 59% won, +Rs 240 a trade; holdout
    41 trades, 83% won, +Rs 561, worst month -Rs 18.6k (tolerance: the same trade count; average net within Rs 10;
    per-trade nets equal R128's saved `t2.parquet` within Rs 1 on >= 99% of trades).
  - CISD: R131's S3-V2 detector + R132's E3 exit + R132's N-EV filter give R133's published numbers: design 413 kept
    trades, 69% won, +Rs 261 a trade; seen holdout 35 trades (tolerance as above, against R133's saved
    `trades_design.parquet` / `trades_holdout_seen.parquet`).
  - If parity fails for an arm, that arm's levers are not tested; the report says so and why.

## 2. The base (each arm as the app runs it today, `origin/main` e4e4f1d24)

- **S5 base:** R128 T2-S5x1 with the app's square-off: **15:15** (the paper book's MIS square-off; 15:05 on the index's
  expiry day), one open trade per index, at most 5 a day; the pick is the best fillable cell-side at the decision
  (a cell whose window passes the expiry-day square-off is not fillable, as R123). In the app the index is busy until
  the trade's own exit, so the portfolio is rebuilt with each variant's own exits (R128 kept R123's entries, which were
  chosen with the cells' fixed exits; that version is the parity replay and is reported beside the base).
- **CISD base:** R133 S3-V2-E3 with the app's square-off, **15:15**; one trade at a time per index, before the filter
  (a refused setup keeps the index busy until its simulated exit), as the app's `Phantom` does.

## 3. The levers (fixed now; each is one test, applied to the base alone; whichever exit comes first wins)

| id | arm | lever | source |
|---|---|---|---|
| BE10 | both | breakeven at +10%: once a minute's high is 10% above the raw entry price, from the next minute the stop is the premium whose stop sale nets Rs 0 | R162 / V4 |
| L500 | both | step lock: once the best open net (minute highs) reaches +Rs 1,000, the stop locks +Rs 500; +1,500 locks +1,000; +2,000 locks +1,500 ...; from the next minute, only up, sold as a stop | R186 |
| LBE1000 | both | once the best open net reaches +Rs 1,000, the stop moves to breakeven (net Rs 0); from the next minute | R186 |
| IDX | both | once the best open net has reached +Rs 1,000, a 1-minute index close back through the index open of the fill minute (call: below; put: above) sells at the next minute's open (stop fill) | R186 Amendment B |
| LK500 | both | the arm's own 60% lock starts at +Rs 500 instead of +Rs 1,000 (everything else unchanged) | Boss's brief |
| ST1500 | both | S5: the -Rs 2,000 cell stop becomes -Rs 1,500 (the -Rs 1,000 cells unchanged); CISD: a premium stop at -Rs 1,500 on the lot (R132 E1/E2 form) beside the index stop | Boss's brief |
| FT3PO | both | skip when the bought option's price AND OI both fell over the 15 minutes before the decision (R144c's "long unwinding" quadrant, `features.contract_feats`) | R144c C5 |
| VIX313 | both | skip when India VIX is down more than 3.13% today at the decision (last VIX close at or before the decision vs the previous session's last close) | R144c C6 |
| EXP | both | skip on the index's own weekly expiry day | expiry-day skip |
| ADX20 | both | skip when ADX(14) (Wilder) on the index's 5-minute series (previous 3 sessions + today's complete bars before the signal bar) is below 20 | R183 (textbook level) |
| NTHR | CISD | the network threshold (mean EV of the side's 8 cells > T) with T chosen ON THE DESIGN WINDOW ONLY from {-100, -50, +50, +100, +150, +200}: the T with the highest design net among those whose design win rate is above the base's; if none, the T with the highest design net | Boss's brief (c) |
| COMBO | each arm | at most one combined variant, chosen on the design window only: the levers that pass every design gate (section 4) - at most one exit lever (the passing exit lever with the highest design net; LK500 and ST1500 count as exit levers) plus every passing entry filter (and NTHR for CISD). Formed only if that is >= 2 levers; else "not formed" (still counted) | - |

- Entry filters skip only; a skipped setup **still keeps the index busy** until its simulated exit and counts in the
  day's cap (as R151's picker in the app), so a filter can never free a place for a trade the arm would not make.
- A lever that needs data the trade does not have (no OI print in the window, ADX with < 29 bars, no VIX) does not
  skip: the trade is taken as in the base.
- **Tests: S5 10 levers + COMBO = 11; CISD 11 levers + COMBO = 12; 23 in all. BH m = 4,902 (after R187) + 23 = 4,925.**

## 4. Periods and gates

- **Design:** R123's walk-forward folds F1-F4, 2 Jan 2023 - 15 Mar 2026 (out-of-sample network views).
- **Final:** 1 Apr - 19 Oct 2026 (R123's holdout period). **Not fresh:** the base of both arms was read on it by R123,
  R128, R133 and R134's replay. No lever here was read on it for these arms. It is the freshest period with R123's own
  predictions. `results/FINAL_READ` is written before the one read of the levers on it; the code refuses a second read.
- **New month (descriptive only):** 21 Oct - 13 Nov 2026 (18 sessions; 20 Oct was a holiday), never read for these
  arms (R134's forward log stopped at 19 Oct). Too few trades for any gate.
- **Gates, per arm x lever, 1 lot, all must hold:**
  1. design: net >= the base's, win rate > the base's, and the worst month OR the max drawdown smaller (less negative)
     than the base's;
  2. final: the same three;
  3. >= 30 trades in the final (S5's base had 41 and CISD's 35 there: an arm that cannot reach 30 is reported so);
  4. twin test, 500 twins, seed 188, over design + final pooled; p = (1 + #twins with net >= the lever's net) / 501;
     **BH q <= 0.10 with m = 4,925** (the earlier tests taken as p = 1, R187's `bh_q`).
     - random-skip twin (entry filters, NTHR): the lever's trade set differs from the base's by k removed and j added
       trades; a twin removes k random base trades and adds j random trades from the setups the base did not take;
     - random-exit twin (exit levers): each trade whose exit the lever changed is sold instead at the close of a
       uniformly random minute between its entry minute and the base's exit minute (inclusive); other trades keep the
       base's net; COMBO: both.
  - **Said now:** with 500 twins the smallest p is 1/501, so q >= (1/501) x 4,925 / 23 = 0.43 for every test. **No
    lever can pass gate 4**, so no lever can pass the study. What R188 can show is which levers clear gates 1-3 (a lead
    for Boss's choice, as the earlier exceptions were), never a pass.
- **Account stop rule** (-Rs 40,000 drawdown, -Rs 13,000 month), reported for every row in every period.
- Reported for every row and period: trades, win rate, net, average win, average loss, worst month, max drawdown, 2x
  slippage net, and the exits' reasons.

## 5. Honesty

- The arms were chosen by Boss after their numbers were known; the levers come from other studies and Boss's brief,
  not from a search on these arms. The final period was read before for the base. The new month is tiny.
- Every lever is logged in `research/experiments/entries/r188-*.yaml`, whatever it shows.

## 6. Tests (pytest)

Look-ahead guard (a lever's decision at minute t is unchanged when every bar after t is replaced by noise); the exits on
synthetic bars (BE10, L500 levels, LBE1000, IDX, LK500, ST1500, the 15:15 square-off); the filters (FT3PO quadrant,
VIX313, EXP, ADX20) on synthetic data; busy-hold for skipped setups; NTHR selection; COMBO formation; twins; the BH
count (m = 4,925); the FINAL_READ lock; parity tests against R128 / R133 (skipped when the cache is absent).

## 7. Outputs

`research/hunt/r188_s5_cisd/` (code, tests, `results/report.md`, `results/summary.md`, JSON), a FINDINGS.md section,
`research/experiments/entries/r188-*.yaml`, the knowledge pack rebuilt only if `build_pack --check` needs it.
