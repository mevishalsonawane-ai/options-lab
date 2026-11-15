# R180: does the outside "dual-book" build manual reproduce on our real option prices?

15 Nov 2026. Research only: nothing in the app changes. Pre-registration: `../PREREG.md` (committed before any number). Signal source: the index (5-minute bars of the index spot) for both books; one option-chain filter (ATM straddle vs 09:20); Book 1's exits on the option premium, Book 2's stop on the index and lock on option P&L.

## For Boss, in short

- **The manual's numbers do not reproduce.** Built exactly as it is written, on our real 1-minute option prices with real dated charges and slippage (1 lot, 4 Nov 2024 - 19 Oct 2026):
  - **Book 1** (R72-X1 Breakout): manual 361 / 67.9% / Rs 78,504; **ours 123 / 51.2% / Rs 23,280** (design 93 / 54.8% / Rs 19,655, holdout 30 / 40.0% / Rs 3,625).
  - **Book 2** (Order-Block Pullback): manual 271 / 59.8% / Rs 43,924; **ours 863 / 33.5% / -Rs 71,838** (design 633 / 32.4% / -Rs 43,448, holdout 230 / 36.5% / -Rs 28,390).
  - **Combined** one-position account with the -Rs 11k month pause: manual 440 / 63.9% / Rs 81,447; **ours 874 / 37.0% / -Rs 22,934**, max drawdown -Rs 57,354.
- **No win rate near the manual's.** Book 1 wins 51.2% (manual 67.9%), Book 2 33.5% (manual 59.8%), combined 37.0% (manual 63.9%).
- **Book 1 makes a little money, but not because of its timing**; Book 2 loses steadily. **Random entries do about as well (Book 2) or better (Book 1).** Re-pricing every trade with a random entry minute (same day, index, side and exits, 500 times): Book 1 p 0.90 (random average Rs 59,723), Book 2 p 0.47 (-Rs 74,148), combined p 0.69. The entry rules add nothing over random timing.
- **Where the manual's Book 1 trade count probably comes from.** Its "initial breakout" rule uses an undefined number. Read our way (pre-registered), Book 1 takes only 123 trades. Dropping that rule (a post-hoc check, not a test) gives 350 / 50.9% / Rs 59,503: close to the manual's 361 trades (design 257 vs 264, holdout 93 vs 97), but still a 50.9% win rate, not 67.9%, and random entries beat it (p 1.00, random average Rs 189,439).
- **The manual is not internally consistent.** Book 2's per-index rows add up to 425 trades (+Rs 44,911), not the stated 271 (+Rs 43,924); design 327 vs 206, holdout 98 vs 65. It names files and folders this repo has never had (`derived/r30/labels_*.parquet`, `derived/r132_ict_exits/`, `options_lab/costs.py`, `real_options_dual_book_engine.py`, a Blueprint .md).
- **It borrows our study names for different rules.** The repo's X1 (R30) is -30% / keep +Rs 500 after +Rs 1,000 / 60 min, and R72 buys the 1-ITM option; the manual's X1 is +20% trigger / keep 50% of the peak gain on ATM. The repo's R132 S3-V1-E3 is a CISD rule (3,122 design trades, 63% win, avg Rs 12, rejected), not an order-block pullback.
- **Its side claims:** "R72 credit spreads -Rs 77,605 on 666 trades" and "-Rs 39,372 on 255" are R173's real-price results (NIFTY + BANKNIFTY only, Nov 2024 - Feb 2026), quoted out of context. "R30 C_SPR/P_SPR -Rs 5,78,235 on 2,538 R72 trades", "-Rs 7,74,987 on 2,878 R52 trades" and "unfiltered R72 X1 54.0% win, -Rs 1,88,325 / +Rs 99,515 at 42.9%" appear nowhere in the repo; R30's SPR is a debit spread, not a credit spread.
- **Gates: all three fail** (Book 1: twin p < 0.05, BH q <= 0.10, >= 40 holdout trades; Book 2: design > 0, holdout > 0, 2x slippage > 0, max DD better than -Rs 40k, no month worse than -Rs 13k, twin p < 0.05, BH q <= 0.10; combined: design > 0, holdout > 0, 2x slippage > 0, max DD better than -Rs 40k, twin p < 0.05, BH q <= 0.10).
- **Verdict: do not build it.** Nothing goes to paper or live. The Apr - Oct 2026 "holdout" was read by many earlier studies (not clean), and the lake ends 19 Oct 2026, so no fresh days after the manual's window exist yet.

## 1. Manual vs ours (1 lot; trades / win % / net at 1x slippage)

| | manual, all | ours, all | manual 2x | ours 2x | manual design | ours design | manual holdout | ours holdout | reproduces? |
|---|---|---|---|---|---|---|---|---|---|
| Book 1 R72-X1 Breakout | 361 / 67.9% / Rs 78,504 | **123 / 51.2% / Rs 23,280** | Rs 75,470 | Rs 20,305 | 264 / 68.6% / Rs 53,688 | 93 / 54.8% / Rs 19,655 | 97 / 66.0% / Rs 24,816 | 30 / 40.0% / Rs 3,625 | no (trades, win, net) |
| Book 2 S3-V1-E3 Order-Block Pullback | 271 / 59.8% / Rs 43,924 | **863 / 33.5% / -Rs 71,838** | Rs 38,587 | -Rs 86,442 | 206 / 59.2% / Rs 29,336 | 633 / 32.4% / -Rs 43,448 | 65 / 61.5% / Rs 14,589 | 230 / 36.5% / -Rs 28,390 | no (trades, win, net) |
| Combined 1-position, -Rs 11k month pause | 440 / 63.9% / Rs 81,447 | **874 / 37.0% / -Rs 22,934** | Rs 74,730 | -Rs 38,663 | 326 / 64.7% / Rs 61,887 | 659 / 36.4% / -Rs 1,584 | 114 / 61.4% / Rs 19,561 | 215 / 38.6% / -Rs 21,350 | no (trades, win, net) |
| Combined 1-position, no month pause | 458 / 62.7% / Rs 74,907 | **972 / 35.7% / -Rs 35,994** | Rs 67,939 | -Rs 53,392 | 344 / 63.1% / Rs 55,347 | 718 / 35.2% / -Rs 17,724 | 114 / 61.4% / Rs 19,561 | 254 / 37.0% / -Rs 18,270 | no (trades, win, net) |

Reproduces = trades within +-25%, win rate within 5 points, net of the same sign within +-50% (PREREG section 5).

## 2. Gates (PREREG section 5)

BH over m = 4,051 (4,044 earlier tests + R180's 3 gated + 4 sensitivity runs).

| run | trades | win | net | 2x | avg win / loss | worst month | max DD | twin p (random mean) | BH q | holdout n | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Book 1 R72-X1 Breakout | 123 | 51.2% | Rs 23,280 | Rs 20,305 | Rs 2,195 / -Rs 1,917 | -Rs 7,463 (2025-01) | -Rs 16,657 | 0.896 (Rs 59,723) | 1.00 | 30 | fail: twin p < 0.05, BH q <= 0.10, >= 40 holdout trades |
| Book 2 S3-V1-E3 Order-Block Pullback | 863 | 33.5% | -Rs 71,838 | -Rs 86,442 | Rs 1,136 / -Rs 697 | -Rs 20,079 (2025-12) | -Rs 94,733 | 0.469 (-Rs 74,148) | 1.00 | 230 | fail: design > 0, holdout > 0, 2x slippage > 0, max DD better than -Rs 40k, no month worse than -Rs 13k, twin p < 0.05, BH q <= 0.10 |
| Combined 1-position, -Rs 11k month pause | 874 | 37.0% | -Rs 22,934 | -Rs 38,663 | Rs 1,348 / -Rs 832 | -Rs 11,554 (2026-07) | -Rs 57,354 | 0.689 (-Rs 4,518) | 1.00 | 215 | fail: design > 0, holdout > 0, 2x slippage > 0, max DD better than -Rs 40k, twin p < 0.05, BH q <= 0.10 |
| Book 1, repo fill (next-minute open) | 123 | 52.0% | Rs 40,356 | Rs 36,171 | Rs 2,363 / -Rs 1,879 | -Rs 5,045 (2026-09) | -Rs 17,815 | 0.782 (Rs 63,942) | 1.00 | 30 | fail: twin p < 0.05, BH q <= 0.10, >= 40 holdout trades |
| Book 2, repo fill | 868 | 32.8% | -Rs 89,973 | -Rs 104,617 | Rs 1,143 / -Rs 713 | -Rs 21,022 (2025-12) | -Rs 107,341 | 0.571 (-Rs 84,081) | 1.00 | 230 | fail: design > 0, holdout > 0, 2x slippage > 0, max DD better than -Rs 40k, no month worse than -Rs 13k, twin p < 0.05, BH q <= 0.10 |
| Combined (pause), repo fill | 877 | 36.6% | -Rs 20,713 | -Rs 37,657 | Rs 1,390 / -Rs 840 | -Rs 11,548 (2025-12) | -Rs 54,640 | 0.607 (-Rs 11,455) | 1.00 | 202 | fail: holdout > 0, 2x slippage > 0, max DD better than -Rs 40k, twin p < 0.05, BH q <= 0.10 |
| Combined 1-position, no month pause | 972 | 35.7% | -Rs 35,994 | -Rs 53,392 | Rs 1,334 / -Rs 798 | -Rs 20,816 (2025-12) | -Rs 69,779 | 0.723 (-Rs 14,036) | 1.00 | 254 | fail: design > 0, holdout > 0, 2x slippage > 0, max DD better than -Rs 40k, no month worse than -Rs 13k, twin p < 0.05, BH q <= 0.10 |

Design / holdout of the sensitivity runs (repo fill = buy at the next minute's open, Book 2's stop / lock sold at the next open):

- Book 1, repo fill (next-minute open): design 93 / 55.9% / Rs 37,709, holdout 30 / 40.0% / Rs 2,647.
- Book 2, repo fill: design 638 / 32.1% / -Rs 54,161, holdout 230 / 34.8% / -Rs 35,812.
- Combined (pause), repo fill: design 675 / 36.4% / Rs 2,741, holdout 202 / 37.1% / -Rs 23,454.
- Combined 1-position, no month pause: design 718 / 35.2% / -Rs 17,724, holdout 254 / 37.0% / -Rs 18,270.

## 3. Per index (manual vs ours, the books on their own)

| book | index | manual all | ours all | manual 2x | ours 2x | manual design | ours design | manual holdout | ours holdout |
|---|---|---|---|---|---|---|---|---|---|
| B1 | NIFTY | 73 / 63.0% / Rs 16,186 | 27 / 51.9% / -Rs 5,581 | Rs 15,512 | -Rs 6,093 | 56 / 64.3% / Rs 13,137 | 24 / 58.3% / -Rs 962 | 17 / 58.8% / Rs 3,049 | 3 / 0.0% / -Rs 4,620 |
| B1 | BANKNIFTY | 75 / 72.0% / Rs 14,930 | 20 / 50.0% / Rs 10,738 | Rs 14,600 | Rs 10,220 | 49 / 79.6% / Rs 20,444 | 8 / 75.0% / Rs 8,882 | 26 / 57.7% / -Rs 5,514 | 12 / 33.3% / Rs 1,855 |
| B1 | FINNIFTY | 64 / 65.6% / Rs 10,602 | 19 / 47.4% / Rs 6,915 | Rs 9,927 | Rs 6,376 | 50 / 62.0% / Rs 3,570 | 17 / 41.2% / Rs 2,619 | 14 / 78.6% / Rs 7,032 | 2 / 100.0% / Rs 4,296 |
| B1 | MIDCPNIFTY | 63 / 82.5% / Rs 36,258 | 24 / 54.2% / Rs 19,286 | Rs 35,120 | Rs 18,253 | 44 / 84.1% / Rs 17,902 | 18 / 55.6% / Rs 13,199 | 19 / 78.9% / Rs 18,356 | 6 / 50.0% / Rs 6,086 |
| B1 | SENSEX | 86 / 59.3% / Rs 528 | 33 / 51.5% / -Rs 8,078 | Rs 311 | -Rs 8,451 | 65 / 58.5% / -Rs 1,365 | 26 / 53.8% / -Rs 4,084 | 21 / 61.9% / Rs 1,893 | 7 / 42.9% / -Rs 3,994 |
| B2 | NIFTY | 165 / 57.6% / Rs 12,539 | 366 / 35.5% / Rs 4,435 | Rs 9,028 | -Rs 1,782 | 130 / 55.4% / Rs 7,362 | 265 / 34.0% / Rs 9,173 | 35 / 65.7% / Rs 5,177 | 101 / 39.6% / -Rs 4,738 |
| B2 | BANKNIFTY | 56 / 64.3% / Rs 5,408 | 137 / 32.1% / -Rs 19,538 | Rs 3,844 | -Rs 22,588 | 36 / 61.1% / -Rs 3,438 | 102 / 32.4% / -Rs 9,116 | 20 / 70.0% / Rs 8,846 | 35 / 31.4% / -Rs 10,421 |
| B2 | FINNIFTY | 41 / 53.7% / Rs 309 | 94 / 33.0% / -Rs 22,434 | -Rs 738 | -Rs 25,100 | 34 / 58.8% / Rs 4,817 | 73 / 31.5% / -Rs 18,362 | 7 / 28.6% / -Rs 4,509 | 21 / 38.1% / -Rs 4,071 |
| B2 | SENSEX | 163 / 55.8% / Rs 26,655 | 266 / 31.6% / -Rs 34,302 | Rs 24,384 | -Rs 36,972 | 127 / 55.1% / Rs 13,294 | 193 / 30.6% / -Rs 25,143 | 36 / 58.3% / Rs 13,360 | 73 / 34.2% / -Rs 9,159 |

Manual's Book 2 per-index rows add to 425 trades, not its stated total of 271.

## 4. Combined account by month (-Rs 11k pause): manual vs ours

| month | manual trades / win / net | ours trades | ours win | ours net 1x | ours net 2x |
|---|---|---|---|---|---|
| 2024-11 | 17 / 58.8% / Rs 5,350 | 37 | 43.2% | Rs 12,253 | Rs 11,899 |
| 2024-12 | 23 / 73.9% / Rs 9,010 | 45 | 42.2% | -Rs 1,312 | -Rs 2,231 |
| 2025-01 | 22 / 63.6% / Rs 1,588 | 45 | 42.2% | -Rs 3,228 | -Rs 4,109 |
| 2025-02 | 15 / 60.0% / Rs 860 | 30 | 33.3% | -Rs 11,221 | -Rs 11,781 |
| 2025-03 | 14 / 85.7% / Rs 11,332 | 37 | 37.8% | -Rs 2,155 | -Rs 2,725 |
| 2025-04 | 16 / 56.2% / Rs 477 | 31 | 38.7% | Rs 1,254 | Rs 714 |
| 2025-05 | 20 / 70.0% / -Rs 2,181 | 37 | 40.5% | Rs 18,843 | Rs 18,098 |
| 2025-06 | 20 / 45.0% / -Rs 4,300 | 37 | 27.0% | -Rs 3,075 | -Rs 3,801 |
| 2025-07 | 17 / 58.8% / Rs 4,344 | 43 | 25.6% | -Rs 5,019 | -Rs 5,638 |
| 2025-08 | 25 / 48.0% / -Rs 2,869 | 41 | 36.6% | -Rs 3,784 | -Rs 4,376 |
| 2025-09 | 26 / 73.1% / Rs 11,888 | 44 | 25.0% | -Rs 11,145 | -Rs 11,752 |
| 2025-10 | 19 / 78.9% / Rs 10,656 | 42 | 28.6% | -Rs 6,419 | -Rs 7,153 |
| 2025-11 | 21 / 71.4% / Rs 6,016 | 51 | 39.2% | -Rs 1,351 | -Rs 2,337 |
| 2025-12 | 8 / 25.0% / -Rs 11,461 | 20 | 30.0% | -Rs 11,215 | -Rs 11,582 |
| 2026-01 | 20 / 65.0% / Rs 5,360 | 34 | 50.0% | Rs 8,787 | Rs 8,213 |
| 2026-02 | 27 / 63.0% / Rs 127 | 55 | 36.4% | -Rs 242 | -Rs 1,334 |
| 2026-03 | 16 / 87.5% / Rs 15,690 | 30 | 43.3% | Rs 17,447 | Rs 16,521 |
| 2026-04 | 16 / 81.2% / Rs 10,794 | 39 | 56.4% | Rs 8,305 | Rs 7,555 |
| 2026-05 | 23 / 65.2% / Rs 1,496 | 39 | 43.6% | Rs 7,438 | Rs 6,727 |
| 2026-06 | 20 / 65.0% / -Rs 2,921 | 25 | 24.0% | -Rs 11,303 | -Rs 11,789 |
| 2026-07 | 21 / 52.4% / Rs 3,693 | 32 | 31.2% | -Rs 11,554 | -Rs 12,077 |
| 2026-08 | 13 / 69.2% / Rs 7,416 | 21 | 19.0% | -Rs 11,229 | -Rs 11,580 |
| 2026-09 | 14 / 42.9% / Rs 3,101 | 37 | 37.8% | -Rs 1,997 | -Rs 2,649 |
| 2026-10 | 7 / 42.9% / -Rs 4,018 | 22 | 45.5% | -Rs 1,010 | -Rs 1,476 |

The manual shows 24 months, every one but 6 positive, worst -Rs 11,461 (Dec 2025, after the pause). Ours: worst -Rs 11,554 (2026-07), max drawdown -Rs 57,354; the combined account took 112 Book 1 and 762 Book 2 trades.

## 5. Funnels and exits

- Book 1: 10,667 index breaks on a >= 4-of-5 bar; 6,922 not the initial break (index still locked); 1,794 outside 09:30 - 14:45; 899 weaker than 0.5 ATR; 827 straddle not above 09:20; **225** accepted; none over the Rs 56k cap; one position at a time leaves 123.
- Book 2: 10,272 order blocks; 5,780 alive inside 12:15 - 14:45; **1,553** triggered with the straddle below -5%; one position leaves 863.
- Exits, Book 1 R72-X1 Breakout: stop 19, lock 34, time 70.
- Exits, Book 2 S3-V1-E3 Order-Block Pullback: stop 564, time 73, lock 221, missing 5.
- Exits, Combined 1-position, -Rs 11k month pause: stop 506, time 124, lock 241, missing 3.
- Every accepted signal, no one-position rule (Book 1 R72-X1 Breakout): 225 / 51.1% / -Rs 18,132 (design 174 / 52.3% / -Rs 18,898, holdout 51 / 47.1% / Rs 766), max DD -Rs 51,988.
- Every accepted signal, no one-position rule (Book 2 S3-V1-E3 Order-Block Pullback): 1553 / 31.0% / -Rs 194,315 (design 1157 / 30.0% / -Rs 143,753, holdout 396 / 34.1% / -Rs 50,562), max DD -Rs 217,870.

## 6. Post-hoc diagnostic (written after the run; not a test, not gated)

Book 1 without the per-index "initial breakout" state machine (its `idx_stop_ext` is never defined), manual fills, one position: **350 / 50.9% / Rs 59,503**, 2x Rs 53,356, design 257 / 51.0% / Rs 12,727, holdout 93 / 50.5% / Rs 46,777, worst month -Rs 15,621, max DD -Rs 29,644. Random-entry twin p 1.000 (random mean Rs 189,439), BH q 1.00 (m = 4,052). Every accepted signal without the one-position rule: 1169 / 48.1% / -Rs 41,102. The trade count now matches the manual's (361 / 264 / 97) closely; the win rate and the design net do not.

## 7. Every ambiguity in the manual and the choice made (fixed in PREREG.md before any number)

- straddle_vs_0920 uses the raw rupee straddle (the manual's formula); R30's version divides by spot first. ATM = the lake's offset-0 strike of that minute; 09:20 = the 09:20 minute's close.
- Book 1 Donchian: the prior 20 bars of the continuous multi-day 5-minute series (R72's); "bar index >= 60" = of that series. ATR14 = EWM span 14 (manual), not R72's Wilder ATR.
- Book 1 "initial-breakout state machine": `idx_stop_ext` undefined -> 0; every >= 4-of-5 break of an index (taken or not) locks that index until its 1-minute price touches the breakout bar's channel middle (or 15:10), same day. A later, stronger break while locked is dropped. Section 6 shows the count without it.
- Book 1 entry: the manual's `opt_close[em] + slip` (close of the minute after the signal bar) with the R17 buy fill; levels (-30%, +20%, 50% of the peak gain) on the filled price, as the manual's `entry_px`; the lock reads the PRIOR minute's peak; time exit at the close of min(em + 60, 15:20).
- Book 1 ties on one bar: the highest straddle_vs_0920 (manual). The Rs 56k cap: filled price x lot size (never binding at 1 lot).
- Expiry days: square-off 15:05 for both books (CLAUDE.md; the manual's own guard table says 15:05 on expiry days).
- Book 2 swings: continuous series; each swing is broken once (the first close beyond it); the displacement bar must close in its own direction and be that first break; median body of the 20 bars BEFORE it.
- Book 2 order block: the last opposite-colour candle among the 5 bars before the displacement; valid for the 60 minutes after the displacement bar closes, same day; one trade per order block (the first minute meeting every condition).
- Book 2 trigger: the 1-minute range touches the zone, closes in the direction, not beyond the far edge; the straddle read at the last completed 5-minute mark; entry at the trigger minute's close (manual's `opt_close[entry]`).
- Book 2 stop: the index 1-minute low (high) beyond ref -/+ stop_pts, sold at that minute's option close (manual), as a stop sale; ATR = the last completed 5-minute bar's EWM-14 ATR. Lock: rupee P&L on closes from the raw entry close.
- One position per book (manual: "1 open lot across account"); a new entry from the exit minute + 1. Combined: one position across both books, Book 1 first at the same minute; the -Rs 11k pause counts closed trades of the month.
- Sensitivity (the repo's standard fill): buy at the next minute's open; Book 2's stop / lock sold at the next open.

## 8. Data, costs, honesty

- R51's Amendment A clean stores (code unchanged), built for R180 from 1 Nov 2024 to 19 Oct 2026 (the lake's first November session is 4 Nov; 1 Nov 2024 was the Diwali special session, not in the lake). Held contracts: a trade is priced on the strike bought. Lots by date (`r51cfg.LOTS`): NIFTY 25 -> 75 (28 Nov 2024) -> 65 (31 Dec 2025), and so on. After 20 Nov 2024 BANKNIFTY / FINNIFTY / MIDCPNIFTY have monthly expiries only, so their "ATM" option is the monthly contract (R173 measured a median of 14 days to expiry on BANKNIFTY); NIFTY and SENSEX trade the weekly.
- Costs('dated') on both orders (the rates in force on the date; the manual's Rs 40 brokerage is the same two Rs 20 orders); R17 liquidity fills (buy +5 bps + 1-4 ticks by volume, stop sale -10 bps - 2x the half-spread); 2x slippage = R51's SLIP2. A premium stop gapped through fills at the minute's open.
- The holdout (Apr - Oct 2026) was read by R51 - R176 before; it is not clean. No data exists after 19 Oct 2026.
- Runs: see `FINAL_READ` (first run, then any re-run with its reason). The diagnostic in section 6 was added after the first run and is labelled as such.
