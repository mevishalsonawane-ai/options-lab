# R94 idea 5 notes (dated, each before the numbers it affects)

## N5-1 (21 Oct 2026, after the code and tests, BEFORE any P&L or correlation)

Implementation choices PREREG 3.5 leaves open, fixed in `usgap.py` and tested:
1. **Dukascopy scaling.** Prices are integer points: XAUUSD, XAGUSD, LIGHTCMDUSD / 1,000; GASCMDUSD / 10,000 (at
   1,000 gas read USD 28.7 on 15 Sep 2026, 10x the Henry Hub level; MCX NATURALGAS ~Rs 300 = ~USD 3.4). Checked on
   price LEVELS only (15 Sep 2026: gold 4,297, silver 63.2, WTI 102.5, gas 2.87).
2. **"Previous 40 sessions" for sd** = the previous 40 USABLE sessions of the book (sessions with MCX minutes at 09:05
   and 09:06 and a US price; today excluded). No signal until 40 exist, so the first trades come about 40 sessions
   after 5 Aug 2025 (early October 2025). The design's first fold (Aug-Oct 2025) is therefore short.
3. **US price at a time** = the close of the last Dukascopy minute starting at or before it, at most 10 minutes
   earlier. The reference for u is the US price at the previous session's last MCX minute (its start time).
4. **Time exit**: the 14:00 minute close, or the last MCX minute before 14:00 if 14:00 has no row. The stop is checked
   on minute closes after 09:06 up to and including 14:00, and fills at that minute's close (close-only series).
5. **ATR14**: the daily continuous of the signal book's own symbol (GOLDM, SILVERM, CRUDEOIL, NATURALGAS), rows strictly
   before the trade day, roll rows (open interest > 3x the previous row) counted as high - low. A day without ATR14
   (fewer than 14 earlier rows) is not traded (never happens in this window).
6. **Descriptive gap correlation** uses the US move to the 08:59 minute (u_open) against the 09:00 MCX gap; the
   catch-up correlation uses e against ln(14:00 close / 09:05 close), usable sessions only.
7. **Design run never loads a row on or after 1 Apr 2026** (MCX minutes, Dukascopy minutes and daily rows are cut
   before any computation); the holdout path loads everything and calls `open_holdout` first.
8. Twins: per trade, the same day / entry / exit / stop rule with the opposite side is simulated once; each of the
   1,000 twin sets picks a side per trade at random (p = share of twin totals >= the rule's net).

## N5-2 (21 Oct 2026, AFTER the design run; data-quality checks prompted by the numbers, nothing changed)

1. **The 09:05 / 09:06 MCX prices are live, not stale.** Design sessions: the 09:05 and 09:06 closes equal the previous
   session's close on 0-0.6% of days (GOLDM 151, SILVERM 132, CRUDEOIL 156, NATURALGAS 156 days), and the share of
   unchanged minutes 09:01-09:06 (GOLDM 7%, SILVERM 6%, crude 25%, gas 22%) is BELOW the 10:00-12:00 share (12%, 8%,
   33%, 26%). So crude's high corr(e, 09:05 -> 14:00) = 0.57 is not a stale-price artefact. Caveat: e and the
   09:05 -> 14:00 move share the 09:05 minute close, so minute noise there adds some positive correlation by
   construction; the traded entry is the NEXT minute (09:06), which does not share it.
2. **Gas proxy is weak.** corr(US move to 08:59, MCX 09:00 gap) is 0.19 for GASCMDUSD -> NATURALGAS (151 days) against
   0.71-0.87 for the other three. Dukascopy's Henry Hub CFD may follow a different contract month or trade thinly
   overnight; idea-5 gas results lean on a poor proxy.
3. **Stops fill at minute closes** (close-only series), so a stop can fill well beyond its level in a crash minute
   (GOLDM 29 Jan 2026: stop 2,077 points away, filled 4,005 points away). That is kept: it is what a close-only
   check pays.
