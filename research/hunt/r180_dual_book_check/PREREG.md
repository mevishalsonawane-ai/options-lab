# R180 pre-registration: cross-check of an outside "dual-book" build manual on real 1-minute option prices

Written 15 Nov 2026, **before any R180 trade or P&L number was computed**. Committed on `claude/r180-dual-book-check`
before the code's first run on data. Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss received a 4-page PDF, "Options-Lab & IraAlgo: Complete Real-Option-Price Strategy Specification & Build Manual",
that claims two option-buying books ("Book 1 R72-X1 Breakout", "Book 2 R132 S3-V1-E3 Order-Block Pullback") with high
win rates "verified on the 1-minute option chains" of our lake, and asks for them to be added to the repo and the app.
R180 rebuilds both books **exactly as the manual defines them**, on our real 1-minute option prices, and checks whether
its numbers reproduce. Precedent: R173 (14 Nov) checked an earlier set of outside files and found modelled, not real,
option prices.

**Signal source** (CLAUDE.md): both books read their entries from the **index** (5-minute Donchian / order-block
structure on the index spot) and buy the ATM option. Both use one option-chain filter (the ATM straddle against its
09:20 value, context around an index signal, which CLAUDE.md allows). Book 1's stop / lock / time exit is on the option
premium; Book 2's stop is on the index, its lock on the option's rupee P&L. No rule reads its signal from an option chart.

## 0. Read before writing this (no R180 result)

- The manual itself, all 4 pages. Its own tables are internally checked here (arithmetic only, no data):
  - Book 1's per-index rows add up to its total (361 trades, +Rs 78,504; design 264, holdout 97).
  - **Book 2's per-index rows do NOT add up to its total**: 56 + 41 + 165 + 163 = **425** trades vs the stated 271;
    nets +Rs 44,911 vs +Rs 43,924; design 327 vs 206; holdout 98 vs 65.
  - The combined monthly ledger adds up (440 trades, +Rs 81,447; no-stop 458 / +Rs 74,907).
- What the repo already says about the names it borrows:
  - **R72 X1** in the repo (`research/HUNT_R30_ALL_PARAMETERS.md`): X1 = stop -30%, after **+Rs 1,000 keep at least
    +Rs 500**, out after 60 minutes, on R30's grid labels. The manual's X1 is different (+20% premium trigger, keep 50%
    of the peak gain), and R72 trades the **1-ITM** option with exits XB / XL / XI, not ATM with X1.
  - **R132 S3-V1-E3** in the repo (`research/experiments/entries/r132-s3-v1-e3.yaml`) is a **CISD** rule ("close above
    the last green high", R131 index stop, 60% lock after +Rs 1,000), 5 indices, 3,122 design trades, 63% win, average
    net Rs 12, **rejected**. The manual's "S3-V1-E3" is a different rule (fractal swings, displacement, order block,
    afternoon window, straddle < -5%) under the same name.
  - The manual's side claims: "R72 credit spreads -Rs 77,605 on 666 trades" and "Liquidity credit spreads -Rs 39,372
    on 255 trades" **are R173's real-price numbers** (NIFTY + BANKNIFTY only, Nov 2024 - Feb 2026,
    `research/experiments/entries/r173-r72-cs.yaml`, `r173-liq2-cs.yaml`). "R30 C_SPR/P_SPR -Rs 5,78,235 on 2,538 R72
    trades", "-Rs 7,74,987 on 2,878 R52 trades", "unfiltered R72 X1 +Rs 99,515 at 42.9% / -Rs 1,88,325 at 54.0%" appear
    **nowhere** in the repo (grep of origin/main, 15 Nov). R30's SPR label is a **debit** spread, not a credit spread.
  - The manual's `derived/r30/labels_*.parquet`, `derived/r132_ict_exits/`, `derived/r128/`, `options_lab/costs.py`,
    `real_options_dual_book_engine.py`, `OptionsLab_Complete_Build_Blueprint.md` are not in this repo.

## 1. Data, engine pieces, window

- Lake: `~/.cache/iraalgo/lake` (copied from `gdrive:IraAlgo-data-lake`): index_1m, options_rolling_1m WEEK, to
  19 Oct 2026. **No data exists after 19 Oct 2026**, so no fresh out-of-sample stretch can be read (said now).
- Stores: R51's Amendment A clean stores (`hunt/r51/r51data.py`, code unchanged), built in R180's own folder from
  **1 Nov 2024** (the manual's start; the lake's first November session is 4 Nov) to 19 Oct 2026. Held contracts: a
  trade is priced on the strike bought, never on the rolling ATM series. WEEK series = the nearest expiry; after
  20 Nov 2024 BANKNIFTY / FINNIFTY / MIDCPNIFTY have only monthly expiries, so their WEEK file is the monthly contract
  (R51's note); before 20 Nov 2024 their weeklies still existed and are what the WEEK file holds. Expiry days: R51's
  straddle test.
- Lots on each date: `r51cfg.LOTS` (NSE revisions incl. Dec 2025). Charges: `Costs('dated')` (the rates in force on the
  trade date) on both orders. Fills: R17 "liq" (`Fills('liq')` as R51's engine): buy +5 bps + 1/2/4 ticks by the
  5-minute volume before; market sell -5 bps - the half-spread; a stop sale -10 bps - 2x the half-spread. 2x slippage:
  R51's SLIP2. A gap through a premium stop fills at the minute's open (the worse price).
- Window and split (the manual's): design 1 Nov 2024 - 31 Mar 2026; holdout 1 Apr - 19 Oct 2026.
  **Honest note:** Apr - Oct 2026 has been read by R51 - R176 for many ideas and the manual's author also saw it; it is
  NOT a clean holdout. With the manual's rules fixed by someone else and not tuned here, the whole window is the test;
  the split is reported because the manual reports it.

## 2. The rules, exactly as written, and every choice where the manual is ambiguous (fixed now)

Common measure: `straddle_vs_0920(m) = (S(m) - S(09:20)) / S(09:20)`, S(m) = close of the ATM call + close of the ATM
put at minute m, ATM = the lake's offset-0 strike of that minute (the same rows R30's `chain_features` reads), raw
rupees (the manual's formula; R30 divides by spot, noted as a difference). S(09:20) = the 09:20 minute's close (row 5).
Causal: S(m) uses only minute m's close and the 09:20 close; m >= 5. NaN when either leg is missing (no trade).

**Book 1 "R72-X1 Breakout"** (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX):
1. 5-minute index bars as R72 builds them (`fam_r44.bars5`), one continuous series per index. chi/clo = high / low of
   the **prior 20** bars; mid = their average; ATR14 = EWM(span 14, adjust=False) of the true range (manual Step 1.2;
   R72 itself uses Wilder's ATR). up = C > chi, dn = C < clo, at bar index >= 60 of the series.
2. A bar with >= 4 of the 5 indices up (or down) on the same (day, bar) is a breadth bar; each index that broke is a
   candidate. Signal minute m_lbl = the bar's last minute; entry minute em = m_lbl + 1; 15 <= em <= 330.
3. Per-index "initial breakout" state machine (manual: "em >= free_idx_m, free_idx_m = xm + 1, xm = the first 1-min bar
   touching mid - s x idx_stop_ext, or 355"). `idx_stop_ext` is never defined: **choice: 0** (touching the breakout
   bar's channel middle). Every breadth candidate of an index (taken or not) locks that index until xm + 1, where xm is
   the first minute after m_lbl whose low <= mid (up) / high >= mid (down), else row 355; the lock ends at the day's
   end too. A candidate on a locked index is not "initial" and is dropped. (So a weak first break followed by a strong
   second one is NOT taken: "initial breakout bar only".)
4. Accept iff |C - edge| / ATR14 >= 0.50 (edge = chi up, clo down), straddle_vs_0920(m_lbl) of THAT index > 0, and the
   ATM option's price x qty <= Rs 56,000.
5. Book 1 holds one position at a time (manual: "1 open lot across account (exit+1)"): a new entry needs em >= the last
   exit minute + 1 (same day). Ties on one bar: the highest straddle_vs_0920 (manual's tie-break).
6. Option: ATM at em (offset-0 strike of minute em), call for up, put for down. **Entry price: the manual's
   `opt_close[em]` + slippage** (the close of the minute after the signal bar; the R17 buy fill applied to that close).
7. Exit X1 on each minute m = em + 1 .. min(em + 60, 365) (15:05 = 350 on expiry days): (a) low <= 0.70 x entry -> stop
   at 0.70 x entry (gap: the open if lower), stop-sale fill; (b) if the PRIOR peak >= 1.20 x entry and low <= entry +
   0.50 x (peak - entry) -> lock at that price (gap: the open if lower), stop-sale fill; (c) peak = max(peak, high);
   else out at the close of min(em + 60, 365), market-sell fill. `entry` here = the filled price (the manual uses the
   slipped price).

**Book 2 "R132 S3-V1-E3 Order-Block Pullback"** (NIFTY, BANKNIFTY, FINNIFTY, SENSEX):
1. Same 5-minute bars. Fractal swing at bar b-2 with k = 2: H[b-2] strictly greater than H of b-4, b-3, b-1, b (swing
   high; swing low mirror), known at the close of bar b. Continuous series.
2. Displacement bar b: |C-O| > 1.5 x median(|C-O|) of the **20 previous bars** (choice: the bar itself excluded),
   body in the direction (C > O up), and C closes beyond the last swing high (up) confirmed by bar b. **Choice: each
   swing is broken once** (the first close beyond it; a later close beyond the same swing is not a new break).
3. Order block: the last opposite-colour candle (C < O for up) among bars b-1 .. b-5 (same day or not); zone [L, H].
   None found -> no setup. Valid for 12 bars after b: 1-minute triggers from b's last minute + 1 up to the last minute
   of bar b + 12.
4. 1-minute trigger at minute t, 180 <= t <= 330, inside the validity: the minute's range touches the zone
   (low <= H and high >= L), closes in the direction (close > open for up), and has not closed beyond the far edge
   (close >= L for up; mirror). **Choice: one trade per order block** (its first trigger). straddle_vs_0920 < -0.05,
   read at the last completed 5-minute mark at or before t (manual: `straddle_vs_0920(m_5m)`).
5. ref = the index 1-minute close at t; stop_pts = |ref - far edge| + 0.10 x ATR14 (5-min, the last completed bar,
   EWM span 14). far edge = L for up, H for down.
6. Book 2 holds one position at a time; ties: the lowest straddle_vs_0920 (manual).
7. Option: ATM at t. **Entry: the manual's `opt_close[t]`** + the R17 buy fill.
8. Exit E3 on each minute m = t + 1 .. 364 (350 on expiry days): (a) the index low <= ref - stop_pts (up) / high >=
   ref + stop_pts (down) -> out at the option's close of m (manual), stop-sale fill; (b) peak_rs = max(peak_rs,
   (close - entry) x qty); if peak_rs >= Rs 1,000 and cur_rs <= 0.60 x peak_rs -> out at the close of m, market-sell
   fill; (c) else out at the close of row 364. entry = the raw option close for the rupee lock (as the manual).

**Combined account** (the manual's "Combined 1-Pos Acct"): one position across both books (a signal while any position
is open is skipped; a new entry needs the open position's exit minute + 1); if both books signal at the same entry
minute, Book 1 first (choice; the manual is silent). -Rs 11k monthly pause: once the month's closed net <= -Rs 11,000,
no new entry until the next month. Also reported without the pause (the manual's "No Mo Stop").

Sensitivity (counted in BH, not gated alone): every book and the combined account with the **repo's standard fill**
instead of the manual's close fills: entry at the OPEN of the minute after the signal (R51's next-minute fill), Book 2's
index stop and lock sold at the next minute's open.

## 3. What is measured

Per book and combined, 1 lot: trades, win rate (net > 0), net at 1x and 2x slippage, average win / loss, worst month,
max drawdown (closed-trade equity), per index, design vs holdout, monthly, exit reasons; each next to the manual's figure.

## 4. Random-entry twins

500 twins (seed 180) per book: each real trade is re-priced with the same day, index and side and an entry minute drawn
uniformly in the book's window (Book 1: 15 - 330; Book 2: 180 - 330), the same strike rule and the same exits (Book 2's
index stop: the same stop points from the twin's own entry close). Positions are not re-coordinated. p = share of twins
with total net >= the real total. The combined account's twin = the sum of the two books' twins in the same draw order,
restricted to the trades the account took.

## 5. Hypotheses and gates

H1: Book 1 earns 361 trades / 67.9% / +Rs 78,504 (design 264 / 68.6% / +Rs 53,688; holdout 97 / 66.0% / +Rs 24,816).
H2: Book 2 earns 271 / 59.8% / +Rs 43,924 (holdout 65 / 61.5% / +Rs 14,589).
H3: the combined account earns 440 / 63.9% / +Rs 81,447.

**Reproduces** = trade count within +-25% of the claim, win rate within 5 points, and net of the same sign and within
+-50% of the claim (generous on purpose: small definition choices move counts).

**Passes the repo's gates** (all needed, per book and combined): net > 0 in design AND holdout; net > 0 at 2x
slippage; account stop rule never hit (max drawdown better than -Rs 40,000 and no month worse than -Rs 13,000);
random-entry twin p < 0.05 and BH q <= 0.10 with m = 4,044 earlier tests + R180's 7 (3 gated + 4 sensitivity) = 4,051;
>= 40 holdout trades.

Even a pass would not change paper or live: an outside rule that failed nothing here would still be proposed to Boss
for paper only, under the normal research gates. Every test gets an entry in `research/experiments/entries/`.

## 6. Look-ahead guard (pytest)

`test_r180.py`: straddle_vs_0920 at minute m is unchanged when every minute after m is deleted or perturbed; Book 1
and Book 2 signals up to minute t are unchanged when the index bars and option prices after t are perturbed; exits
never read a minute before the entry; plus unit tests of the state machine, swings, displacement, order block, exits
(stop / lock / time / gap / expiry square-off), the one-position coordinator and the monthly pause.
