# h42 PRE-REGISTRATION

Run 2, written 2026-10-08 by `make_prereg.py` from `answers_raw.csv` (1,296 questions answered on data before 2025-10-01),
before the holdout was re-run. Run 1 was voided: post-holdout diagnostics found a look-ahead bug (15-minute changes at
09:20/09:25 read the END of the day through a negative array index). The bug was fixed, a perturbation audit
(`audit.py`) now passes, and the whole chain (tables -> answers -> candidates -> this file -> holdout) was re-run with
the SAME pre-written questions and the SAME candidate rule. Run 1 files are kept in scratchpad/hunt/h42/run1_buggy/.

## Candidate definitions (unchanged from run 1)

- **Strict:** question BH q < 0.05 AND best option exit net > 0 AND that option test BH q < 0.05 over all 44,536 question x exit option tests. **Result: 0 strict candidates** (lowest option q = 1.00).
- **Loose (run in the holdout):** question BH q < 0.05 AND same-sign effect in both halves of the pre-holdout period AND best-of-38-exit net > 0 per trade after app charges + h24 real spread AND raw one-sided p < 0.05 AND better than a same-index / same-minute / same-side random entry. **6 rules.** Their exit was picked as the best of 38 on pre-holdout data, so their pre-holdout numbers are optimistic by construction.

## Rules (1-ITM nearest expiry, bought at the open after the decision bar; one position at a time per rule; 1 lot at today's lot)

| id | index | condition | option | pre-registered exit | pre trades | pre Rs/day net | gross | per trade net | p vs random (pre) |
|---|---|---|---|---|---|---|---|---|---|
| Q0357 | BANKNIFTY | Does BANKNIFTY's ATM straddle lose more of its value 09:20->15:09 on expiry day? [`day: dte == 0`; entry `5`] | CE+PE | liq | 176 | 172 | 206 | 1002 | 0.003 |
| Q0108 | MIDCPNIFTY | When MIDCPNIFTY gaps down more than 0.5%, how often is the gap filled (previous close touched) the same day? [`day: gap < -0.5`; universe `gap == gap`; entry `0`] | col:gapfill_dir | time | 35 | 163 | 176 | 2728 | 0.008 |
| Q0176 | MIDCPNIFTY | If at 14:00 MIDCPNIFTY's day high so far was made in the first 15 minutes, how often does it stay the day's high to the close? [`day: hiearly285 == 1`; entry `285`] | PE | T25S20H30 | 192 | 69 | 121 | 211 | 0.002 |
| Q0177 | MIDCPNIFTY | If at 14:00 MIDCPNIFTY's day low so far was made in the first 15 minutes, how often does it stay the day's low to the close? [`day: loearly285 == 1`; entry `285`] | CE | time | 193 | 108 | 162 | 328 | 0.111 |
| Q0378 | MIDCPNIFTY | Is MIDCPNIFTY's day range bigger 1 day before expiry? [`day: dte == 1`; entry `0`] | CE+PE | T30S10H15 | 82 | 53 | 87 | 376 | 0.007 |
| Q0498 | MIDCPNIFTY | When MIDCPNIFTY's first-hour range is wide (>1.5x average), is the move 10:15->15:09 bigger? [`day: rg60_rel > 1.5`; entry `60`] | CE+PE | liq | 81 | 175 | 223 | 1265 | 0.001 |

Exit codes: TxSyHz = +x premium-point target on the option HIGH, -y stop on the option LOW (stop first if both in one
minute), time stop z minutes; liq = Liquidity-arm exit (-15% resting stop, out after 20 min unless the premium is >= +5%,
square-off 15:10); time = the question's own horizon (15:10 for whole-day questions). Gap-throughs fill at the bar open.
Fills: app +-5 bps (stops 10 bps) plus the h24 real half-spread on both sides (NIFTY/BN 0.16%, MIDCP 0.21%, FIN 0.42%,
SENSEX 0.20%); app charges (STT 0.15%). Lots: NIFTY 65, BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20.

## Holdout protocol (`python3 -I research/hunt/h42/holdout.py hold`)

- Period 2025-10-01 .. latest. Same code, thresholds, side and exit. No re-selection.
- All 38 exits reported for information; the headline is the pre-registered exit.
- Random baseline: each holdout trade vs a random holdout day of the same index, same minute, same side and exit (B = 2000).
- **PASS** only if holdout net Rs/day > 0 at the pre-registered exit AND p vs random < 0.05/6 (Bonferroni).

Note added after the holdout run (2026-10-08): FINNIFTY's daily candles for 2006-2011 are flat placeholders (O=H=L=C).
They were dropped from the daily table and all answers were recomputed on pre-holdout data. The candidate set is
unchanged (the same 6 rules), so the holdout result below stands. Questions significant after BH: 336 -> 327.
