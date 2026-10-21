# R81 pre-registration: Liquidity 15+5 EXITS and TRADE MANAGEMENT, and which indices run it

Written 18 Oct 2026, **before any R81 result was computed**. Research only (no `android/**`, no `.github/**`). Any app
change is Boss's decision, made elsewhere.

Boss, 18 Oct: *"You have 8 hours, use multiple agents/sessions to enhance the liquidity strategies ... use all the
existing knowledge and, if you need, the internet."* R80 does entry filters and R79 direction filters; **R81 changes
only what happens after the fill** (plus a re-entry and an add, which are trade management on an existing trade).

## 0. What is already known (do not re-test)

From `research/experiments/entries/` and FINDINGS.md: premium profit locks and ladders (LIQUIDITY_LOCK, R19, R64, R70
L25, R76 T25-T50 / L35), premium give-back trails (LIQUIDITY_TRAIL, R19b), a fixed premium target (+40,
LIQUIDITY_TARGET40), point-based 30/60 exits (LIQUIDITY_EXITS_3060), premium stop widths 10-25% **without** an index
stop (LIQUIDITY_STOP, 2024-26), trend-turn exits on the index (R63 T1-T4: EMA20, Supertrend, VWAP, Donchian), "no stop,
no target, hold to a level" (R52 LIQ-19: no time stop either), later entry cut-off (R52 LIQ-22), five R18 overlays
(VWAP band, next S/R, expected range). All failed. The lesson every time: **the edge is a few runners that reach the next
level; anything that sells early cuts them.** So R81 tests things that are *different in kind*: stops and trails on the
**index** scaled by volatility, an index "no progress" time stop, giving runners *more* room (no premium stop, later
square-off, overnight), adding to runners, a re-entry, and scaling out.

Web ideas (no evidence of an edge, sources only for the idea): ATR / chandelier trailing stops on the underlying
(Chuck LeBeau's chandelier exit, e.g. https://school.stockcharts.com/doku.php?id=technical_indicators:chandelier_exit);
"stop beyond the liquidity sweep candle" in ICT / smart-money material (e.g.
https://www.tradingview.com/scripts/liquiditysweep/); scale-out at 1R then breakeven on the runner (Van Tharp R-multiples);
time stops for breakout trades (e.g. https://www.investopedia.com/terms/t/time-stop.asp).

## 1. Arm and trades (exactly as R70 / R76)

Liquidity 15+5, `hunt/r51/fam_liq.py make(und, books)` on R51's Amendment A clean stores (R51_CLEAN=1, the lake on
Drive, options to 13 Oct 2026): BANKNIFTY 15+5 (index stop 30 pts), FINNIFTY 30+5 (15 pts, parked), MIDCPNIFTY 15+5
(8 pts); 1-ITM; one position per book; expiry days skipped. B0 must reproduce R76 exactly (Rs 2,57,249 on 881 design
trades, Rs 11,155 on 357 final), else the study stops.

**Per-index re-check (also in this study):** the same rules on NIFTY 15+5 (index stop 15 pts) and SENSEX 15+5 (50 pts),
the values in `hunt/h4/comps.py IDX_STOP_EXT` / `BOOKS_EXT`, unchanged.

- **Signal source:** the index (level breaks on 15/30/5-minute index bars). Unchanged. The option (1-ITM) is bought.
- **Own exits (B0):** -15% premium stop; 20-minute time stop unless +5%; index exits (index stop beyond the broken
  level, next-level target, failed break, new level; sold at the next minute's open); square-off 15:10 (15:05 expiry).

## 2. Variants (B0 + 11 tests). All parameters fixed here; nothing is tuned.

Definitions used below:
- `I0` = the index open of the option's fill minute (R76). `TG` = the trade's own next-level target. `level` = the broken
  pool edge (`idx_stop + sgn * istop`).
- `ATR` = mean true range of the last 14 **5-minute index bars** (09:15 grid, earlier sessions included) that are
  complete at the signal minute (bar end <= the entry row). Fixed for the trade. No later bar is read.
- An index rule "fires" on a minute's 1-minute index bar and sells at the **next minute's open** (as B0's index exits).
  Premium locks act from the next minute (R51 / R64 / R76 engine).

| id | rule | exit source | type |
|---|---|---|---|
| **B0** | Liquidity's own exits | premium + index | |
| **X1 NOPS** | no -15% premium stop; everything else B0 (the index stop beyond the level is the stop) | index | later exit |
| **X2 SWEEPSTOP** | the index stop moves to the far side of the **breaking bar** (its low for a call, high for a put) instead of `level -/+ istop`; never tighter than B0's (the further of the two) | index | later exit |
| **X3 ATRTRAIL** | chandelier on the index: once the index (1-min high / low) has gone >= 1.0 ATR past `I0`, sell when a 1-min close is more than 1.5 ATR back from the best 1-min high (call) / low (put) since the fill | index | earlier exit |
| **X4 NOPROG** | index time stop: at the close of the 10th minute from the fill (fill row + 9), sell if the index close has not moved >= 0.25 ATR past `I0` in the trade's direction | index | earlier exit |
| **X5 SCALE** | 2 lots: lot A sold at +15% premium (1R, R = the 15% stop); lot B is B0 plus a breakeven stop (the raw fill) once +15% has traded. Reported per lot: (A + B) / 2 | premium | both |
| **X6 REENTRY** | after a B0 trade leaves on its premium stop or its index stop, one re-entry if a 5-min index bar (09:15 grid) completing within 30 minutes of that exit, and by 14:00, closes back beyond `level` with room >= 1 index stop to `TG`; bought at the bar end on the 1-ITM strike at that minute, same own exits (same `level`, index stop, `TG`; failed break = a 5-min close back through `level`; the new-level exit is not modelled). Skipped if the same book's next B0 trade starts before the re-entry would have been sold | index signal | added trades |
| **X7 PYRAMID** | add 1 lot (same contract) at the next minute's open once the index has covered 50% of the way from `I0` to `TG` and the option's close that minute is above the fill; the add has its own -15% stop and is otherwise sold with the base trade (the base's exit row) | index trigger | added lots |
| **X8 EOD1430** | square-off at 14:30 instead of 15:10 | time | earlier exit |
| **X9 EOD1520** | square-off at 15:20 instead of 15:10 (15:05 on expiry days, unchanged) | time | later exit |
| **X10 OVERNIGHT** | a trade still open at the 15:10 square-off whose option close at 15:09 is >= +20% over the fill and whose index target has not been touched is held (NRML) to the next session, with a GTT stop at the fill price; sold at the 09:16 open, or at the GTT stop (gap: at the 09:15 open) if the 09:15 minute trades at or below it. Only if the same contract has bars the next session; otherwise B0 | premium + time | later exit |
| **X11 BESTRUCT** | breakeven only after a structure confirmation: once a completed 5-min index bar closes >= 0.5 ATR beyond `level` in the trade's direction **and** the option's close that minute is >= +3% over the fill, the stop rises to +1% over the raw fill (about the charges) | index trigger, premium stop | earlier exit |

Not tested (named so they are not lost): a delta- or IV-aware strike roll (the stores keep one held contract a trade,
not the chain's greeks; rolling would need a second contract's fill per roll and a rule that has never been specified),
pyramiding by premium gain, more than one re-entry, crypto (not touched).

Signal source for every rule: the index. Exits are on the index (X1-X4, X6-X7 triggers, X11 trigger), on time
(X8-X10) or on the premium (X5, the X10 GTT, the X11 stop level). No rule reads an option chart for its signal.

**Risk note (CLAUDE.md):** X1, X2, X5 (lot B), X6, X7, X9, X10 can raise risk (wider or later exits, more trades or
lots, overnight). Even if one passes, it can only ever be a **paper** variant on the arms Boss chooses, logged and
labelled; it never moves to live by itself, and Liquidity BANKNIFTY's plain-rules record (the 26 Oct live candidate) is
never mixed with it.

## 3. Measurement, periods, gates

R64 / R76 engine (held contract, liquidity slippage, stops on the minute low with gap fills at the worse open, locks from
the next minute, `Costs('dated')`, 1 lot of the day's size). Every B0 trade is priced again under each variant from the
same entry row (only the exit changes), except X6 / X7, which add trades or lots, and X5, which is the mean of two lots.

- **Periods (as R72-R78):** design 20 Nov 2024 - 31 Mar 2026; walk-forward = its last four quarters (Apr 2025 - Mar
  2026; nothing is fitted, so the walk-forward is the out-of-sample slice of design); **final 1 Apr - 13 Oct 2026, read
  once** (`results/FINAL_READ`, a second read refuses). The final period was already read by R51, R63, R70, R76 and
  others for this arm, so it is **weak evidence**; said in the write-up.
- **Families:** the pooled arm (BANKNIFTY + FINNIFTY + MIDCPNIFTY, as R76) gives 11 tests; each variant on each of the
  five indices alone gives 55 more. **BH is applied across all 66** (the pooled tests too).
- **Twin p (final period, 500 sets, seed 81):**
  - earlier-exit variants (X3, X4, X8, X11): R65 / R76 random-exit twins (as many random early exits as the variant
    changed trades, at a uniform minute after the fill up to the plain exit);
  - X6 / X7: random-timing twins (the same number of re-entries / adds, on randomly chosen B0 trades that qualify, at a
    uniform minute: re-entries between the B0 exit and 14:00 in the same direction with the same exits; adds between the
    fill and the base exit);
  - X1, X2, X5, X9, X10 (later exits / scale): a day-level sign-flip twin of the paired difference (each day's
    variant - B0 difference given a random sign), because there is no "random version" of removing a stop.
- **Top-5 check:** the variant's gain over B0 with its 5 best per-trade gains (variant - B0) removed must still be > 0,
  in design and in final.
- **Pass (all of):** net > B0's in walk-forward AND in final (and not below B0 in design); twin p < 0.10; BH q < 0.10
  (across the 66); top-5 check passed. A pooled pass recommends the rule for the arm; an index cell pass recommends it
  for that index only. Anything else is "not recommended"; it is "harmless" if it never made less than B0 in design,
  walk-forward or final.
- **Per-index verdict (which indices run the arm):** B0 per index, design / walk-forward / final, with top-5 removed,
  worst day, max drawdown and a random-entry twin p (R51 `common.twin_p`, final period). Reported, not BH-gated as an
  exit test; "run it" needs design, walk-forward and final net > 0 and ex-top-5 > 0 in design and final.
- Also reported: trades, win rate, net, per trade, worst day, max drawdown; changed trades, Rs saved / given up; examples.

## 4. Look-ahead guard

Every rule reads only index / option bars up to its decision minute: ATR from 5-min bars complete by the signal; the
trail, the no-progress check, the structure confirmation and the pyramid trigger from 1-minute bars at or before the
minute they act on (acting from the next minute's open); X10 decides at 15:09's close. pytest tests in
`test_r81.py` prove each rule's output does not change when bars after its decision minute are altered.

## 5. Limits fixed now

An earlier exit that would have let a book take a later signal, or a later exit that would have blocked one, is not
modelled (as R70 / R76), except X6 which skips a re-entry that would overlap the book's next B0 trade. The changed-trade
count bounds this. Liquidity BANKNIFTY's plain record stays separate: nothing here changes the app.
