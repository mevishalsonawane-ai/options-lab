# R146 notes (dated; each amendment comes before the numbers it affects)

## A1, 5 Nov 2026: data facts found while writing the code, before any target or result was computed

- **MIDCPNIFTY has no daily history on Yahoo.** `NIFTY_MID_SELECT.NS` returns only today's quote (one timestamp), and
  `^NSEMDCP50` is a different index (NIFTY Midcap 50). MIDCPNIFTY is therefore dropped from part D.
  - D has 4 indices x 5 ideas = 20 ideas.
  - The prediction family is 20 + 16 = **36 ideas**, not 41. No other change.
- **BANKNIFTY's contract before Nov 2024.** The release holds only the nearest expiry after each day. Until BANKNIFTY's
  weeklies ended (Nov 2024) that is the WEEKLY, whereas Night trades BANKNIFTY's monthly. So BANKNIFTY design nights
  before 20 Nov 2024 price a weekly 1-ITM option, and Night's expiry rule then removes the night before each weekly
  expiry. After that the nearest is the monthly, as in the app.
- **Breadth is a proxy.** Night's R3 reads 214 F&O stocks. The release's bank-stock file starts in Feb 2025, so
  R146's R3 proxy reads the cross-index sign breadth (m_brd: NIFTY, BANKNIFTY, FINNIFTY, SENSEX), as pre-registered.
  It is **not** trade-for-trade the app's Night. R27's lake rebuild (Rs 301 a trade 2023 to Sep 2025, Rs 245 a trade
  in Oct 2025 to Oct 2026) is quoted alongside for scale.
- **A night needs the same contract on both days.** The next session must hold the same expiry (always true when
  Night's expiry rule passes). Otherwise the night is dropped as `next_day_other_series` and counted.

## A2, 5 Nov 2026: the fill guard was too strict (found in the build counts, before any target or result was read)

- **What the build showed.** The first build counted only why nights drop. With PREREG's guard ("open more than 20%
  from its own close"), 101 of 467 NIFTY nights and 71 of 496 BANKNIFTY nights were dropped at an EXIT bar.
- **Why that is wrong.** The 09:15 bar of an option on a gap morning legitimately opens far from where it closes a
  minute later. The guard was dropping the big-gap nights, the very nights R146 is about.
- **The amended rule.** A fill price is a stray print only if it is more than 20% from its own bar's close AND more
  than 20% from the next bar's open (within 3 minutes). A real gap persists into the next bar, so it passes.
  - Entry and exit use the same rule.
  - Tested in `tests/test_r146.py`.
- **What was and was not seen.** No gap, P&L or prediction number had been computed or looked at when this was
  changed; only the drop-reason counts in `results/build.json`.

## A3, 5 Nov 2026: the 09:15 option open is often a stale print (data check, before any result)

- **What still dropped after A2.** 130 of the 135 failing exit bars were the 09:15 bar. Its open equals its high and
  sits 20-50% above everything that trades a minute later. Example: open 149.9, close 119.4, next opens 119.4 / 123.6 /
  120.3.
- **The fix.**
  - When the 09:15 open fails the guard, the X15 comparison exit uses that bar's close, labelled `X15_close`.
  - The S30 stop's gap-through check uses that same guarded 09:15 price.
  - No night is dropped for X15 any more.
- **Unaffected.** Night's real exit, X16 (09:16 open), failed the guard on 3 of these nights.
- **The lesson for X15.** Any study pricing a 09:15 option OPEN from minute data overstates it on these mornings. X15
  is reported as a comparison only, and it is not a gate.
