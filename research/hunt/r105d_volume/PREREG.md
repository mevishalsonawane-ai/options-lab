# R105D pre-registration: the books' "volume confirms the breakout", on real index-futures minute volume (exploratory)

Written 26 Oct 2026, committed and pushed BEFORE any volume number is computed. Research only.

Boss, 26 Oct: *"Test the volume idea from the books."* The books (Wyckoff, Aziz, Woods, Porwal, Larry Swing) say a
breakout on rising volume is real and one on low volume is suspect.

## 0. What exists and what was already tested

- **The index has no volume.** Index-futures minutes are in the lake only from 29 Jul to 19 Oct 2026 (57 sessions,
  `fut_1m_live_index`). They hold only the contracts that were live when recorded: the Oct contract from 29 Jul
  (BANKNIFTY, MIDCPNIFTY, NIFTY, SENSEX; FINNIFTY from 11 Sep), the Nov contract from 26 Aug, the Dec contract from
  30 Sep.
- **The near-month contract is missing before 30 Sep.** The Aug / Sep contracts were not recorded, so for most of the
  window the volume is a later contract's.
- **Already tested stand-ins, both failed:**
  - R104 N1: an option-volume surge confirming R72 / R51 breaks (design -Rs 3.77 lakh, holdout -Rs 1.04 lakh);
  - R104 F1: daily futures volume.
- **This study is EXPLORATORY.** 57 sessions all lie in the already-read final window (29 Jul - 13 Oct) plus the 4 OOS
  sessions. There is no clean design / holdout split, so nothing can pass a gate. The best possible outcome is "a lead
  to watch on paper".

## 1. Trades

R105's frozen baseline trades (exits P, 1-ITM, 1 lot) of:
- C (R72 Breadth Donchian);
- B (R51 Donchian 20 + Supertrend);

on every session 29 Jul - 19 Oct 2026 that has futures minutes for the trade's index. Final trades come from R105's
final read; 14-19 Oct from R105's OOS read. No trade is re-priced.

## 2. The volume measure (only data before the entry minute)

- **Contract:** for each index and day, the contract with the most volume on the PREVIOUS session in the file (known
  before the day).
- **Breakout bar:** the 5-minute bar that gave the signal, rows er-5 .. er-1 of that day (09:15 = row 0).
- **V-TOD (primary).** The bar's volume / the mean volume of the same 5-minute slot over the previous 10 sessions of
  that contract. Volume is U-shaped through the day, so the slot is the fair reference. Needs >= 5 earlier sessions,
  else unknown.
- **V-20 (secondary).** The bar's volume / the mean of the previous 20 five-minute bars of that contract (across days).
- **Groups:** "high volume" = ratio >= 1.5 (the books' "clearly above average"); "low" = < 1.5; "unknown" kept apart.

## 3. Reported

For each arm and each measure: trades, win rate, net, average net a trade, for high / low / unknown.

Two tests:
- **Permutation p** (10,000 shuffles of the high / low labels among the known trades): is high-volume's average net a
  trade above low-volume's?
- **Halves:** 29 Jul - 31 Aug, 1 Sep - 13 Oct, and 14 - 19 Oct apart.

**A lead to watch on paper only if:**
- high > low in both halves AND in the pooled average;
- permutation p < 0.05 on both arms, on V-TOD.

Even then, nothing changes until about 3 months of the phone's own futures-tick recordings exist.

BH: 4 tests (2 arms x 2 measures); q reported.

## 4. Tests

`tests/test_r105d.py`:
- the contract choice uses only the previous day;
- the bar and both ratios on hand-made minutes (gaps, missing slots, first days unknown);
- the look-ahead guard: changing minutes at or after the entry row never changes the ratio;
- the permutation p and the grouping.
