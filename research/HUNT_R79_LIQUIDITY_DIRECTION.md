# R79: should Liquidity wait for Supertrend, 10 candles, or the other indices?

18 Oct 2026. Research only (no `android/**`). Pre-registered in `research/hunt/r79/PREREG.md` (fe4ad3a, pushed before
any code ran), with Amendment 1 (1093dff, before any number). Design results were committed (8db2dcc) before the final
period was read once. Code: `research/hunt/r79/`.

Boss, 18 Oct: *"For liquidity strategy should we use Supertrend or wait for 10 candles for getting accurate direction?
Or like Breadth Donchian, should we check all the other indices like BANKNIFTY, SENSEX and NIFTY at once to find the
best direction?"*

## Answer

**No. None of the three ideas makes Liquidity BANKNIFTY better, so it should keep its plain rules.** Every filter made
less money than the plain rules in the walk-forward year (Apr - Dec 2025, 173 plain trades). None passed the
pre-registered gate.

- **Supertrend** (5-minute) almost never disagrees with a Liquidity break. It skipped 9 of 267 design trades, and those
  9 were net winners (-Rs 3,799). On 15-minute bars it skipped 48, and lost Rs 26,877.
- **Waiting 10 candles** is the worst. Liquidity makes its money on the first move after the break. After a 10-minute
  wait much of that move is gone. The wait does skip many losers: the 172 skipped trades lost Rs 53,220 under the plain
  rules. But the 95 trades it still takes earn Rs 1,15,281 less, because they enter late. Design: Rs 15,268 against
  Rs 79,808. The 50-minute wait (W10b) leaves only 50 trades and lost money in walk-forward.
- **The other indices** (NIFTY, FINNIFTY, SENSEX trending the same way) skipped 33 trades that were net winners
  (Rs 8,522). It was ahead in the short final period (+Rs 1,719, 43 trades), but behind in walk-forward (-Rs 10,021). It
  also did no better than skipping the same number of trades at random (p 0.20). Requiring all three (X2) was worse in
  the final period.

This matches the priors: `LIQUIDITY_WAIT5.md` found a 5-candle wait loses, and FINDINGS says breadth does not call the
next 5-30 minutes.

**Limits:** this is BANKNIFTY only, on the repo's `research-data` release (20 Nov 2024 - 23 Feb 2026). The Drive lake
R76 used (to 13 Oct 2026, three indices) could not be reached from this session. The final period is short (48 plain
trades over 30 days) and was already seen by R51 / R63 / R70 / R76, so it is weak evidence. The walk-forward losses are
the stronger evidence.

## Results (BANKNIFTY Liquidity 15+5, 1 lot, net after all charges and slippage)

| rule | design 20 Nov 24 - 31 Dec 25 | win | avg win / loss | max DD | worst day | without top 5 | walk-forward Apr - Dec 25 | final 1 Jan - 23 Feb 26 | final win |
|---|---|---|---|---|---|---|---|---|---|
| **B0 plain rules** | **Rs 79,808 (267)** | 43% | Rs 1,854 / -Rs 878 | Rs 10,552 | -Rs 5,194 | Rs 22,067 | **Rs 32,567 (173)** | Rs 16,214 (48) | 33% |
| S1 Supertrend 5-min | Rs 76,010 (258) | 43% | Rs 1,868 / -Rs 894 | Rs 11,343 | -Rs 4,944 | Rs 18,268 | Rs 28,514 (167) | Rs 16,288 (46) | 33% |
| S2 Supertrend 15-min | Rs 52,931 (219) | 42% | Rs 1,758 / -Rs 878 | Rs 17,048 | -Rs 5,194 | -Rs 580 | Rs 15,236 (142) | Rs 16,289 (41) | 32% |
| W10a wait 10 x 1-min | Rs 15,268 (96) | 43% | Rs 1,708 / -Rs 996 | Rs 19,561 | -Rs 4,167 | -Rs 21,536 | Rs 16,481 (66) | Rs 13,988 (16) | 62% |
| W10b wait 10 x 5-min | Rs 19,378 (50) | 42% | Rs 2,038 / -Rs 807 | Rs 9,371 | -Rs 3,616 | -Rs 13,020 | -Rs 7,273 (39) | -Rs 2,147 (7) | 43% |
| X1 2 of 3 indices agree | Rs 71,286 (234) | 44% | Rs 1,843 / -Rs 926 | Rs 12,020 | -Rs 4,944 | Rs 13,772 | Rs 22,546 (154) | Rs 17,934 (43) | 35% |
| X2 all 3 agree | Rs 76,082 (217) | 46% | Rs 1,906 / -Rs 954 | Rs 12,518 | -Rs 4,944 | Rs 18,568 | Rs 27,376 (142) | Rs 12,762 (39) | 31% |

Trades in brackets. Design covers 160 days with trades; final covers 30. Final max DD / worst day: B0 Rs 8,277 /
-Rs 1,985; S1 Rs 8,285 / -Rs 1,985; S2 Rs 7,792 / -Rs 1,985; W10a Rs 1,105 / -Rs 691; W10b Rs 2,267 / -Rs 1,630; X1
Rs 6,821 / -Rs 1,985; X2 Rs 8,002 / -Rs 1,985. Final without top 5: B0 -Rs 10,587, X1 -Rs 8,867. The plain rules'
final profit, like every rule's here, rests on a few big winners.

## Against the plain rules and the gate

| rule | design vs B0 (p) | walk-forward vs B0 | final vs B0 (p) | BH q (6 tests) | random-skip p | verdict |
|---|---|---|---|---|---|---|
| S1 | -Rs 3,799 (0.85) | -Rs 4,053 | +Rs 74 (0.39) | 0.84 | 0.56 | FAIL |
| S2 | -Rs 26,877 (0.96) | -Rs 17,331 | +Rs 75 (0.47) | 0.84 | 0.40 | FAIL |
| W10a | -Rs 64,540 (0.99) | -Rs 16,086 | -Rs 2,226 (0.56) | 0.84 | 0.10 | FAIL |
| W10b | -Rs 60,431 (0.98) | -Rs 39,840 | -Rs 18,362 (0.90) | 0.90 | 0.82 | FAIL |
| X1 | -Rs 8,522 (0.77) | -Rs 10,021 | +Rs 1,719 (0.015) | 0.09 | 0.20 | FAIL |
| X2 | -Rs 3,726 (0.60) | -Rs 5,191 | -Rs 3,452 (0.76) | 0.90 | 0.61 | FAIL |

p is a one-sided day bootstrap (2,000) of "the filter beats B0", so p near 1 means it did not. BH is over the 6 tests,
on the final-period p. The random-skip p compares the filter's final net with 500 sets of B0 minus as many random
trades. **PASS needed:** beat B0 in walk-forward AND final, q < 0.10 AND random-skip p < 0.10. No rule passed. X1 met
the q gate only, and lost in walk-forward.

What the filters skipped in design (B0 net on the skipped trades; a positive number means they skipped winners):
S1 9 trades, +Rs 3,799 (5 losers, 4 winners). S2 48, +Rs 26,877. X1 33, +Rs 8,522. X2 50, +Rs 3,726. W10a 172,
-Rs 53,220 (118 losers avoided), but the 95 trades it kept made Rs 1,15,281 less than entering at once. W10b 217,
-Rs 24,024, and its 50 kept trades made Rs 84,455 less.

## Decision

Liquidity keeps its plain rules on paper and for the 26 Oct live decision. Nothing is built. No filter is "safe to
add": each made less than B0 in walk-forward.

## For Jarvis

Boss asked (18 Oct) whether Liquidity should wait for Supertrend, wait 10 candles, or check the other indices first
(R79). Answer: no. On BANKNIFTY, 20 Nov 2024 - 23 Feb 2026, every version made less than the plain rules in the
walk-forward year (173 trades). Supertrend 5-minute barely changes anything, and on 15-minute bars it skips winners.
Waiting 10 candles cuts profit most, because Liquidity's money is in the first move after the break. Checking NIFTY,
FINNIFTY and SENSEX skips winners, and was no better than random skips. Keep Liquidity as it is.

## Signal source and method

- **Signal (every rule):** the BANKNIFTY index. Liquidity's level breaks (swing + pool zones) on 15- and 5-minute index
  bars (`hunt/r51/fam_liq.py`, unchanged). The 1-ITM option is bought. **Filters:** S1 / S2 read BANKNIFTY's own
  5- / 15-minute index bars. W10a / W10b read BANKNIFTY's next 10 one- / five-minute index candles. X1 / X2 read the
  5-minute index bars of NIFTY, FINNIFTY and SENSEX. No rule reads an option chart. **Exits:** Liquidity's own (-15%
  premium stop, 20-minute time stop unless +5%, the index exits, 15:10).
- Supertrend(10, 3) is R73's function (`r73rules.supertrend_dir`), read on the last bar fully closed before the entry
  minute. Waits enter at the open after the 10th candle, at the 1-ITM strike of that moment. They are dropped past
  14:00, or if Liquidity's index exit came during the wait. A skipped signal frees the book for a later one (each rule
  re-runs fam_liq's own one-position-per-book walk).
- No look-ahead: tests in `test_r79.py` rewrite every minute from the entry row on and show no decision changes. The
  final period was opened once (`results/FINAL_READ`).
- Data (Amendment 1): `research-data` release, `banknifty_prev_year_wide` + `banknifty_year_wide` (index + the nearest
  expiry after the day, strikes within 1,000 points of 09:20), built into an R51-format store by `r79data.py`, 311
  option days. Index minutes for NIFTY / FINNIFTY / SENSEX from the same release (no MIDCPNIFTY there). Costs: dated
  charges, R17 liquidity fills, next-minute fills, stops on the minute low with gap fills at the worse open, 1 lot.
- B0 sanity check: about 20 trades a month, as R76's BANKNIFTY book (336 trades over Nov 2024 - Mar 2026 on the lake).
  This store has no lake cleaning, and 30 Dec 2025 (an expiry day, skipped anyway) has no data.
- Not tested here: FINNIFTY and MIDCPNIFTY books, and Apr - Oct 2026. The full plan in the PREREG can run with
  `R79_SOURCE=lake` once the Drive lake is reachable.

Files: `research/hunt/r79/` (PREREG.md, r79rules.py, r79data.py, r79study.py, r79entries.py, test_r79.py, results/),
`research/experiments/entries/r79-*.yaml` (7 entries).
