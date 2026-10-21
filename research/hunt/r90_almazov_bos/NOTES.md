# R90 notes and clarifications (dated, each before the numbers it affects)

## N1 (20 Oct 2026, after the code and tests, BEFORE any P&L; only design trade COUNTS were looked at)

Implementation choices that PREREG section 3 leaves implicit, fixed in the code (`structure.py`, `model.py`):

1. **Rare setups are the indicator, not a bug.** A setup needs the HTF swing in the trade's direction to lie BEYOND the
   chart swing. An HTF bar's extreme is one of its chart bars' extremes, so the HTF swing high is very often the same
   price as the chart's active swing high (RR 0, no setup). Setups come mainly from pullbacks: the chart trend flips
   against the HTF and back, and a lower chart swing high forms below an unbroken HTF high (long), or the mirror.
   Design signal counts seen so far (no P&L): NIFTY A1 127, A3 29, A5 14, A15 9, H60 44, H75 11, HD 32, S3 17, RR2 5,
   W30 14, DT 4; GOLDM A1 174, A5 17, H60 44. Many cells will be under the 40-trade gate (G1); the plan is unchanged.
2. **Session run:** an order whose first touch inside the next chart bar falls outside the fill window, or at / after
   the flat minute, is not filled (no trade, not delayed). The chart level is not marked used in that case (the
   structure marks it broken at the bar's close when price went beyond it).
3. **Underlying points (session run, no costs):** fill at the entry level (or the fill minute's open if it opened
   beyond); stop at the level (or the exit minute's open if it gapped through); target at the level; on the fill minute
   itself the close (R89's rule); flat at the open of the flat minute.
4. **As-written run:** from the first bar of the series (warm-up), continuous, positions may be held overnight; a trade
   counts in a window if its fill bar is in that window; a position still open at the window's end is not counted.
5. **DT (daily trend):** the daily structure runs on each panel session's open / high / low / close. On MCX it runs
   across contract rolls without a reset (a reset every month would leave the daily trend unknown most of the time);
   the intraday chart / HTF engines do reset on the first session of a new contract (PREREG 3).
6. **Twins:** a twin enters at the close of its random minute (the option at the next minute's open, R89 twins); the
   after-trigger pool starts at the trade's own fill minute.
