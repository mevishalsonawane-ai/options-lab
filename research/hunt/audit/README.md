# Exit audit of R25, R26 and R34-R37 (14 Oct 2026)

Boss: "check all the studies you have done, and check if your entry / exit / trailing profit lock / profit lock / stop
are correct for all the study you have done".

## What was checked

1. **Hand-worked paths** (`test_exit_audit.py`, 15 tests). Every engine runs on option paths built by hand, and its
   fills, quantities, lock levels, exit rows and P&L are asserted against numbers worked out in the test:
   - **Fills:** a buy fills at the next minute's open + 5 bps + a half-spread of 1 / 2 / 4 ticks by the option's
     5-minute volume; a sell is the mirror. A Rs 20 buy fills at Rs 20.06 when liquid, Rs 20.11 when thin, Rs 20.21
     when untraded.
   - **Charges** match by hand: Rs 20 brokerage, 0.15% STT on the sell, 0.03553% exchange, SEBI, 0.003% stamp on the
     buy, 18% GST. Buying 75 at Rs 20 costs Rs 24.28; selling 75 at Rs 40 costs Rs 29.36.
   - **Entry:** the next minute's open, capped at 1.2 × the signal price + 0.10, worked for 3 minutes, never chased.
     A gap above the cap gives no trade.
   - **R26 engine** (R26 and R34-R37), exits:
     - E1 / E1b: everything sold at the 15:05 / 15:20 bar's open.
     - E2: the 4× limit fills only when the high trades a tick through it and the close or next open confirms it, so a
       freak print fills nothing.
     - E3, Boss's style:
       - half sold at the 2× limit;
       - the lock steps to (m − 1)× once the high reaches m×;
       - a minute close at or below the lock sells the rest at the NEXT minute's open;
       - the lock raised in a bar never sells in that same bar;
       - with 1 lot the 2× sale is the whole position.
     - Worthless at the exit: no sell order, and the loss is the premium plus the buy charges.
   - **R25 engine:**
     - app: a −60% stop on a minute close, sold at the next open; half at 5×, the rest at 20×;
     - ladder: half at 3×, then once 5× is reached the lock is 2.5×;
     - trail: armed at +50%, then giving back half of the best close.
   - **R37 T2 trail:** breakeven once 2× is reached, then 50% of each doubling level (8× reached → lock 4×); sold at
     the next open.
   - **R35 / R37 size rule:** a trade whose single lot costs more than Rs 2,000 is skipped.
   - **R34 KS stop:** an index close back beyond the other side of the 15-minute range sells at the next open.
2. **Lock sensitivity** (`lock_sensitivity.py` → `lock_sensitivity.json`).
   - Every study's lock-based trades were re-simulated twice:
     - as published, which must reproduce the stored nets;
     - with the locks raised only by **confirmed** prices: a bar's high counts only up to max(its close, the next
       open).
   - The published engines raise locks on the bar's raw traded high (the R37 review pointed this out). A single freak
     print can therefore arm a lock.
3. **Independent code reviews** of R26 and R34-R37 (one per study). They checked entry rows, exits, locks, sizing and look-ahead
   against each pre-registration.

## Results

- **All 15 hand-worked tests pass**, and every study's trades reproduce their published nets to the rupee.
- **No entry, exit, lock or stop was found doing something other than what its pre-registration says.**
- **Lock sensitivity: no verdict changes.** Confirmed-price locks change 1-20 exits per rule and move the nets a little,
  in both directions:

  | study / rule | trades | published net Rs | confirmed-lock net Rs | exits changed |
  |---|---|---|---|---|
  | R26-A6 (2× + step lock) | 15 | +10,383 | +8,627 | 2 |
  | R34-M2 (ML ranker, E3) | 141 | −72,304 | −70,374 | 18 |
  | R35-S1 (Dual-Supertrend) | 114 | −76,771 | −80,716 | 11 |
  | R35-S3 | 163 | −1,16,661 | −1,25,771 | 20 |
  | R35-S4 | 35 | −31,637 | −33,157 | 1 |
  | R35-S5 | 70 | −56,251 | −47,796 | 9 |
  | R36-X1 (GEX pair, E3) | 66 | −32,226 | −31,929 | — |
  | R36-X2 | 98 | −42,909 | −44,415 | — |
  | R36-B0 (every day) | 264 | −1,03,781 | −1,09,681 | — |
  | R37-G1 (gamma flip, T2) | 42 | −40,003 | −41,059 | 3 |
  | R37-G2 | 34 | −34,878 | −36,272 | 2 |
  | R37-G4 | 65 | −37,924 | −40,056 | 4 |

  R26-A6 was +Rs 10,383 and is +Rs 8,627 with confirmed locks. It still fails its gates: 15 trades, and negative
  without its best day.

## Known, disclosed limits (not bugs)

- **The 4× / 2× / 3× / 5× targets** fill at their limit only when the next open or the close confirms them. That is
  conservative: a real resting order could fill on a spike that reverses at once.
- **Inside one minute** the order of high and low is unknown. The entry is at the open; a lock sells only on a minute
  close, at the next open.
- **With 1 lot, "sell half at 2×" sells everything** (whole lots only). Many Rs 15-30 NIFTY trades at Rs 2,000 are
  1-2 lots.
- **R26 sizes 1 lot over budget** when one lot costs more than Rs 2,000, as the R26 spec says, and reports the real
  risk. R35 and R37 skip such trades, as their specs say.
- **R25's own lake days are not rebuilt here.** R25's engine is covered by the hand-worked tests above and by its own
  28 tests. Its ladder uses the same raw-high arming; the R26 and R34-R37 sensitivity suggests it would not change R25's
  verdicts either.

## Files

- `test_exit_audit.py`: `python3 -m pytest research/hunt/audit -q` (it imports R25's
  synthetic-day helpers).
- `lock_sensitivity.py`, which writes `lock_sensitivity.json`.
- **The `arm` option** (`"high"`, the default and how every study ran; or `"confirmed"`) is in
  `research/hunt/r26/r26lib.py` (`simulate`) and `research/hunt/r37/r37flip.py` (`simulate`).
