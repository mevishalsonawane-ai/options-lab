# R92: is there a time of day when the ORB family makes money?

Written 21 Oct 2026 for Boss ("Find if there is any specific time where the ORB family makes money."). Research only:
no app code changed.

- Pre-registration `research/hunt/r92_orb_time/PREREG.md` (257 cells, the gates, the holdout) was committed and pushed
  in **63f275d** before any P&L. The code and tests followed in **d690a79**, also before any P&L.
- The design results and note N1 were frozen in **51cc0cb** before the one holdout read (21 Oct 2026 03:59 UTC,
  `results/HOLDOUT_READ`).
- Code: `research/hunt/r92_orb_time/` (`r92cfg.py`, `r92data.py`, `r92engine.py`, `r92study.py`, `r92report.py`,
  `r92entries.py`, tests `test_r92.py`). Dated notes: `NOTES.md`. Every cell's numbers: `results/report.md` (with
  `design.json` and `holdout.json`).
- It reuses R3's line-by-line rebuild of the four arms (`research/hunt/r3orb`) and R49's dated charges and lots read
  from the data (`research/hunt/r49`). On the app rules it matches R49's engine trade for trade (tested).

## Verdict for Boss (read this first)

1. **No. There is no time, and no time plus day type, at which the ORB family makes money after costs.**
   - 0 of 257 pre-registered cells passed.
   - Only 4 cells were above zero on the design data (Aug 2021 - Mar 2026, 5 indices, 4,884 index-sessions). All four
     were small, and the best money p of all 257 cells is 0.13, so after BH every q is 1.00.
   - The holdout (1 Apr - 19 Oct 2026, read once) changes nothing.
   - **ORB, ORB Fresh, ORB Sweep and Range Fade stay paused. No time filter goes on paper. There is no APP_SPEC.**
2. **Every hour loses, for every arm.** Signal on the index, ATM option, 1 lot, 1 entry a side a day, after real
   charges and slippage. The table shows the ANY mode: the arm only enters in that hour, which is what an app switch
   would do.

   | entry hour | ORB | ORB Fresh | ORB Sweep | Range Fade |
   |---|---|---|---|---|
   | 10:00-10:59 | Rs -73 a trade (2,587 trades) | -84 (2,579) | -188 (2,261) | -139 (2,088; from 10:35) |
   | 11:00-11:59 | -53 (2,993) | -83 (1,860) | -128 (1,911) | -154 (2,627) |
   | 12:00-12:59 | -52 (3,122) | -89 (1,537) | -200 (1,584) | -132 (2,195) |
   | 13:00-13:59 | -103 (3,270) | -97 (1,391) | -172 (1,475) | -129 (2,040) |
   | 14:00-14:30 | -59 (3,196) EXT | -47 (993) EXT | -115 (1,037) | -117 (1,473) EXT |

   EXT means the app's window stops at 13:55, so this hour is outside it and was tested with the window stretched to
   14:30. The 30- and 15-minute slots tell the same story. All 125 ANY-mode slot cells lose on design (the full
   tables are in `results/report.md`).
3. **R3's 12:00-12:59 ORB lead was not a time effect. It was the recent months on two indices.**
   - On the three indices R3 never split by hour (FINNIFTY, MIDCPNIFTY, SENSEX), the same rule lost:
     - design: Rs -15,327 on 260 trades (Rs -59 a trade, p 0.83);
     - holdout: Rs -3,248 on 66 trades.
   - On BANKNIFTY + NIFTY themselves, the 12 h trades lost on the design years too: Rs -26 a trade (276 trades). Then
     they made +Rs 12,391 on 35 trades in Apr - Oct 2026. That is where R3's +Rs 28 a trade came from (note N2).
4. **The least-bad time is "the day's first ORB signal comes between 13:30 and 13:59".**
   - Design: +Rs 10,176 on 114 trades (Rs +89 a trade, 45% winners).
   - Holdout: +Rs 5,475 on 19 trades.
   - It is not an edge:
     - p 0.13 and q 1.00;
     - twin p 0.09 (fails the 0.05 gate);
     - FINNIFTY alone made +Rs 13,340, and only 2 of 5 indices are positive;
     - without its 5 best trades it is Rs -2,359;
     - it lost in 2024 and in Jan-Mar 2026;
     - it trades about once every two to three months per index, only on days that broke out late;
     - the walk-forward that picked it lost (point 6).
5. **The other dimensions do not help either** (Rs a trade, design):
   - **Opening-range length:** 15 / 30 / 50 (the app's) / 60 / 75 minutes.
     - ORB: -71 / -68 / -68 / -75 / -86.
     - ORB Sweep: -100 to -172.
     - Range Fade: -122 to -147.
   - **Exit time:** out at 13:30 / 14:30 / 15:10.
     - ORB: -70 / -72 / -68.
     - ORB Sweep: -165 / -173 / -172.
     - Range Fade: -138 / -145 / -147.
     - Holding shorter or longer changes almost nothing.
   - **Weekday:** ORB loses Rs 49-94 a trade every day of the week (least bad Thursday -49 and Wednesday -51). ORB Sweep
     loses -120 to -226; Range Fade -95 to -184.
   - **Days to expiry:**
     - ORB: expiry day -79, 1 day before -70, 2 days -47, 3+ days -71.
     - ORB Sweep and Range Fade lose on every type.
   - **Month position:** first 3 / middle / last 3 sessions all lose for every arm.
   - **Hour x expiry day / not** (30 cells): all lose, except ORB 14:00-14:30 (EXT) on expiry days. That cell is
     +Rs 147 on 568 trades, which is zero, and it lost Rs 613 in the holdout.
6. **Walk-forward.** In each test year we took the best of all 257 cells over the earlier years and traded it in the
   next year. It lost in 3 of 4 test years:
   - 2023: ORB Fresh 11:45-11:59, Rs -20,454 (122 trades);
   - 2024: ORB first-signal 13:30-13:59, Rs -2,154 (24);
   - 2025: the same cell, +Rs 207 (22);
   - 2026 Jan-Mar: the same cell, Rs -5,194 (18).

   Out of sample total: **Rs -27,595 on 186 trades**.
7. **Worst month (the account stop is -Rs 13k a month).** The app arms, all 5 indices together, 1 lot each:
   - ORB: worst month Rs -38,150 (Feb 2025); 13 of 56 design months green.
   - ORB Sweep: Rs -44,197 (Mar 2025); 6 of 56 green.
   - Range Fade: Rs -43,409 (Jul 2025); 2 of 56 green.
   - On one index alone, the worst ORB month is Rs -19,135 (FINNIFTY, Feb 2022).
   - 8 of the 20 single-hour cells also break -Rs 13k on some index in some month.
8. **Why no time works: the arm's own structure, not the clock.**
   - 70% of ORB trades leave on the profit-lock ladder. On those exits the index has moved +7.8 points the trade's way
     on average (NIFTY), but only 36% of them net a profit after the spread and charges. They average Rs +62.
   - A stop costs about Rs 1,114 (NIFTY) and a target pays about Rs 961, while stops come about twice as often as
     targets (227 vs 120 on NIFTY).
   - On the underlying, the ORB is pointed the right way on 64-72% of trades (+3 to +16 index points a trade by index,
     no costs). The option still loses Rs 33-110 a trade.
   - That is true in every hour: points per trade are +5 to +9 in every ORB slot, so the clock does not change it.
9. **What changes in the app: nothing.** The four arms stay paused with their code and settings kept. No time filter is
   proposed, on paper or live.

Plain answer: we cut the ORB family into 257 time and day slices that were fixed in advance: every hour and half hour
and quarter hour, five opening-range lengths, every weekday, expiry and pre-expiry days, start and end of month, three
exit times, and hour by expiry. We tested them on five indices and four and a half years of real option prices. Not
one slice makes money after costs in a way that survives the checks, and the one that looked best on paper (a late
first break after 13:30) is a few lucky FINNIFTY trades. R3's "12 o'clock" hint came from the last six months on
BANKNIFTY and NIFTY only. The ORB family stays off.

## Signal source

Every rule reads its entry and exit signals from the **index itself**: 1-minute NIFTY / BANKNIFTY / FINNIFTY /
MIDCPNIFTY / SENSEX spot, with 5-minute bars built from it. That is the CLAUDE.md default. The option is only the
instrument: the ATM strike of the 09:20 bar's close, the nearest expiry, one held contract from entry to exit. Stops,
targets and the profit-lock ladder are on the option premium. No VIX, no option-chain data, no option chart.

## Data, costs and the holdout

- **Lake:** Drive `gdrive:IraAlgo-data-lake`, fetched 21 Oct 2026.
  - `options_rolling_1m/opt_<UND>_WEEK_2021-2026`: real 1-minute option prints, ATM+-10 of the nearest expiry on or
    after the day, which is the ORB family's own contract rule.
  - `index_1m`: the index bars.
  - Both run to 19 Oct 2026.
- **Records:** R3's session builder, with R49's two fixes:
  - the lot is the day's contract lot, read from the traded volumes (this covered 97% of sessions; the calendar table
    covers the rest);
  - the extra 15:15 rule for spotting expiry days.

| index | sessions | first | design | holdout | of which 13-19 Oct | expiry days |
|---|---|---|---|---|---|---|
| NIFTY | 1,289 | 2021-08-02 | 1,152 | 137 | 5 | 274 |
| BANKNIFTY | 1,260 | 2021-09-03 | 1,123 | 137 | 5 | 191 |
| FINNIFTY | 1,259 | 2021-09-03 | 1,122 | 137 | 5 | 191 |
| MIDCPNIFTY | 911 | 2022-02-21 | 774 | 137 | 5 | 106 |
| SENSEX | 850 | 2023-05-15 | 713 | 137 | 5 | 181 |

- **The app rules** (R3's rebuild of `OrbRules` / `SweepRules` / `RangeFadeRules` / `ProfitLock`):
  - range 09:15-10:04;
  - ORB / Fresh enter 10:10-13:55, Sweep 10:10-14:30, Fade 10:35-13:55 (entry-minute clock: the signal bar's close);
  - fill at the next minute's open, refused at or below P;
  - stop -P, target +P (Sweep +2P), and the ladder. P = BANKNIFTY 40 (the app's), NIFTY 20, FINNIFTY 20, MIDCPNIFTY
    10, SENSEX 60;
  - out at 15:10, or 15:05 on an expiry day;
  - **1 entry a side a day for every arm** (R14) and at most 2 a day.
- **Costs:**
  - the charges in force on each date (`Costs("dated")`: STT 0.1% to 31 Mar 2026, 0.15% after; BSE rates for SENSEX);
  - R17's "liq" fills (+-5 bps plus 1-4 ticks of half-spread by traded lots; stops 10 bps and double ticks);
  - 1 lot of the day's contract.
- **Holdout: 1 Apr - 19 Oct 2026, all five indices, read once.** This was stated before the read: it is not pristine.
  - R49 read Apr - 13 Oct 2026 for these arms, but only as whole-day totals, never split by time.
  - R3's hour row already contained the BANKNIFTY / NIFTY trades of those months.
  - Only 13-19 Oct 2026 (5 sessions per index) was seen by no ORB study. It is reported on its own, never gated.
  - R3's 12 h lead (H12) was tested on the indices R3's hour row never used. Since nothing passed design, the holdout's
    staleness changes no answer.

## The time map (design Aug 2021 - Mar 2026 | holdout Apr - Oct 2026)

Rs per 1 lot after costs, all five indices pooled.
- **win** = share of trades that made money.
- **pts** = the index's move in the trade's direction from entry to exit (no costs).
- **twin p** = how often 500 random-entry twins did at least as well. A twin re-enters each trade at a random OTHER
  minute of the arm's window: same session, same side, same exits.
- **q** = Benjamini-Hochberg over all 257 cells.

### By hour (ANY mode)

| arm | hour | design trades | win | net Rs | Rs / trade | pts | q | twin p | holdout trades | net Rs | Rs / trade |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | 10 | 2,587 | 38% | -1,90,023 | -73 | +6.4 | 1.00 | 0.078 | 346 | -23,518 | -68 |
| ORB | 11 | 2,993 | 39% | -1,59,354 | -53 | +7.6 | 1.00 | 0.936 | 404 | -5,385 | -13 |
| ORB | 12 | 3,122 | 38% | -1,62,708 | -52 | +7.4 | 1.00 | 1.000 | 455 | -60,921 | -134 |
| ORB | 13 | 3,270 | 38% | -3,36,604 | -103 | +7.0 | 1.00 | 1.000 | 495 | -30,990 | -63 |
| ORB | 14 EXT | 3,196 | 40% | -1,89,512 | -59 | +8.0 | 1.00 | 1.000 | 470 | -76,343 | -162 |
| ORB Fresh | 10 | 2,579 | 38% | -2,16,043 | -84 | +6.4 | 1.00 | 0.142 | 346 | -23,518 | -68 |
| ORB Fresh | 11 | 1,860 | 37% | -1,54,892 | -83 | +6.2 | 1.00 | 0.976 | 277 | -18,726 | -68 |
| ORB Fresh | 12 | 1,537 | 39% | -1,36,603 | -89 | +6.6 | 1.00 | 1.000 | 246 | -6,768 | -28 |
| ORB Fresh | 13 | 1,391 | 37% | -1,35,439 | -97 | +5.7 | 1.00 | 1.000 | 208 | +18,752 | +90 |
| ORB Fresh | 14 EXT | 993 | 38% | -46,650 | -47 | +8.4 | 1.00 | 0.956 | 117 | -31,657 | -271 |
| ORB Sweep | 10 | 2,261 | 27% | -4,25,129 | -188 | +11.2 | 1.00 | 0.998 | 368 | -30,863 | -84 |
| ORB Sweep | 11 | 1,911 | 28% | -2,44,071 | -128 | +9.7 | 1.00 | 0.002 | 297 | -34,925 | -118 |
| ORB Sweep | 12 | 1,584 | 27% | -3,16,356 | -200 | +6.9 | 1.00 | 0.012 | 251 | -12,200 | -49 |
| ORB Sweep | 13 | 1,475 | 26% | -2,53,608 | -172 | +6.9 | 1.00 | 0.002 | 227 | -40,627 | -179 |
| ORB Sweep | 14 | 1,037 | 30% | -1,19,206 | -115 | +6.0 | 1.00 | 0.002 | 150 | +11,915 | +79 |
| Range Fade | 10 (10:35+) | 2,088 | 32% | -2,89,491 | -139 | +6.9 | 1.00 | 0.908 | 370 | -23,723 | -64 |
| Range Fade | 11 | 2,627 | 30% | -4,05,768 | -154 | +4.7 | 1.00 | 0.914 | 423 | -46,959 | -111 |
| Range Fade | 12 | 2,195 | 33% | -2,89,929 | -132 | +5.5 | 1.00 | 0.002 | 363 | -46,902 | -129 |
| Range Fade | 13 | 2,040 | 31% | -2,62,779 | -129 | +6.2 | 1.00 | 0.002 | 314 | -78,125 | -249 |
| Range Fade | 14 EXT | 1,473 | 31% | -1,71,809 | -117 | +4.7 | 1.00 | 0.002 | 213 | -10,921 | -51 |

How to read the twins:
- Sweep and Fade "beat" their twins (p 0.002) in the later hours. That only means a random entry at another minute, on
  the same side, loses even more. The twin means are Rs -2.5 to -3.9 lakh. Losing less than random is not making money.
- For late ORB slots the twins make money (e.g. 13 h twin mean +Rs 2.07 lakh). That twin is not tradable: it keeps the
  real trade's direction and usually enters EARLIER, before the break it already knows about (note N1.3; R3 saw the
  same). It says the ORB's direction is often right and its timing (after the break) is what loses.

### First signal only (the day's first ORB trade, kept only if it falls in the slot)

| first ORB signal | design trades | net Rs | Rs / trade | holdout trades | net Rs |
|---|---|---|---|---|---|
| 10:00-10:59 | 2,562 | -1,89,399 | -74 | 345 | -24,688 |
| 11:00-11:59 | 816 | -38,624 | -47 | 109 | -12,881 |
| 12:00-12:59 | 392 | -17,121 | -44 | 80 | +5,699 |
| 13:00-13:59 | 243 | -10,879 | -45 | 44 | +14,583 |
| of which 13:30-13:59 | 114 | **+10,176** | +89 | 19 | +5,475 |
| 14:00-14:30 (EXT) | 119 | +5,591 | +47 | 23 | -5,796 |

The later the day's first break, the smaller the loss. But those days are rare, and the numbers turn on a handful of
trades. ORB Sweep and Range Fade first-signal cells lose in every slot (Rs -68 to -261 a trade).

### The 30-minute view for ORB (ANY mode, design Rs a trade; holdout in brackets)

- 10:00-10:29: -87 (-158)
- 10:30-10:59: -58 (-139)
- 11:00-11:29: -54 (-66)
- 11:30-11:59: -62 (-40)
- 12:00-12:29: -34 (-179)
- 12:30-12:59: -56 (+1)
- 13:00-13:29: -103 (-100)
- 13:30-13:59: -53 (-29)
- 14:00-14:30 EXT: -59 (-162)

The design numbers rest on 1,651-3,196 trades a slot. The 15-minute slots are in `results/report.md`: all 70 lose on
design, for every arm.

## R3's 12:00-12:59 lead (H12), tested on data R3's hour row did not use

| scope | trades | net Rs | Rs / trade | money p |
|---|---|---|---|---|
| FINNIFTY + MIDCPNIFTY + SENSEX, design (the pre-registered test) | 260 | -15,327 | -59 | 0.83 |
| the same, holdout Apr - Oct 2026 (+ BANKNIFTY / NIFTY 13-19 Oct) | 67 | -4,191 | -63 | |
| reproduction: BANKNIFTY + NIFTY, design (R3 had read these) | 276 | -7,081 | -26 | |
| BANKNIFTY + NIFTY, 1 Apr - 12 Oct 2026 (R3 had read these) | 35 | +12,391 | +354 | |

R3 used the BANKNIFTY + NIFTY rows to 12 Oct 2026 and found +Rs 28 a trade on 311 trades. The 35 trades of
Apr - Oct 2026 carry all of it. **H12 fails** on fresh indices and on the older years of its own indices.

## Walk-forward

- **The years gate (G4):** no cell is positive in 3 of the 4 full years 2022-2025 and on 3 of 5 indices together.
- **Nested selection:** at each step, the best of the 257 cells so far is traded in the next year (point 6 above). It
  lost Rs 27,595 on 186 trades out of sample.

## Holdout (1 Apr - 19 Oct 2026, read once)

- No cell passed design, so nothing could pass the holdout.
- 54 of 257 cells are above zero in the holdout, and every one of them lost in design except ORB first-signal
  13:30-13:59 (+Rs 5,475 on 19 trades).
- The app ORB itself made +Rs 3,084 on 654 holdout trades. +Rs 12,897 of that came from 18 trades in 13-19 Oct, so
  Apr - 12 Oct lost Rs 9,813 (R49 found Rs -9,813 for Apr - 13 Oct).
- App ORB Sweep and Range Fade lost Rs 94,666 (660 trades) and Rs 97,300 (721).
- These are the usual "positive in the last six months after years of losses" cells that R49 already warned about.

## Least-bad times (information only, not a recommendation)

1. **First ORB signal 13:30-13:59.** Design +Rs 89 a trade (114 trades), holdout +Rs 288 (19). It fails q, the twin,
   years, indices and the top-5 test.
2. **ORB's 11:00-12:59 (ANY):** about Rs -52 a trade. This is the smallest loss of any slot with thousands of trades.
3. **ORB Wednesday / Thursday, and 2 days before expiry:** Rs -47 to -51 a trade on design. The holdout is above zero
   (+122 / +17 / +85 a trade on 137 / 141 / 63 trades).
4. **ORB Sweep and Range Fade:** no time is close to zero. Their least-bad hour is 14:00-14:30, at Rs -115 / -117 a
   trade.

None of these makes money. They are where the ORB family loses least, not where it wins.

## Multiple testing

- BH at q 0.10 over m = 257, on the money p.
- The smallest money p is 0.13, so every q is 1.00. The twin q is reported too (`results/report.md`).
- Some cells are the same slot by construction (the 14 h hour and the 14:00-14:30 half hour; Fade's 10 h and its
  10:30-10:59 slot). They were pre-registered as listed and kept in m. This only makes BH more conservative (NOTES N1.2).

## Limits (stated, not fixed)

- The holdout is not pristine (see above). The truly unseen data is 13-19 Oct 2026 only (25 index-sessions).
- Each arm is simulated alone on each index (1 lot). Not modelled:
  - Liquidity's priority on BANKNIFTY;
  - one position per index across arms;
  - FlowGate / the R12 manager.
- Stop and target points for indices other than BANKNIFTY are R3's price-level scaling, not app settings (the app
  traded BANKNIFTY only).
- Fills are modelled (the lake has no bid / ask).
- FINNIFTY 2021-22 has sparse and partly mislabelled prints (R3's sensitivity check showed the answer does not depend
  on them). The least-bad cell's design profit is mostly FINNIFTY.
- Expiry days come from the data. One known miss: SENSEX 25 Sep 2025.

## Tests and checks

- `python -m pytest research/hunt/r92_orb_time/test_r92.py -q`: 53 tests. They cover:
  - the clock (entry minute = the signal bar's close, matching R3's hour row) and the slots;
  - the 257 cells and their counts;
  - windows (the app's, R49's range lengths, the exit cut);
  - the ANY / FIRST / APP / ATTR modes, the 1-a-side cap, 2 a day, cooldown and refusals;
  - day types (days to expiry, month position, the incomplete last month);
  - earlier square-offs and expiry;
  - parity with R49's `simulate` and `run_session` on the app rules;
  - twins (outside the slot, never the own bar, reproducible);
  - money p, BH, the gates, the nested walk-forward;
  - the holdout lock and the refusal of a second read;
  - **the look-ahead guard**: every minute after each decision is replaced by noise, and the signals for every range
    length, the entry prices and the stop exits do not change.
- `python -m pytest research/brain/tests -q` (183 passed), `python -m research.brain.features catalog --check` (up to
  date) and `python -m research.experiments.validate`. All were run with the pinned `research/brain/requirements.txt`
  (Python 3.11, scikit-learn 1.5.2).
- Experiment log: 20 entries `research/experiments/entries/r92-*.yaml` (one per family x arm x mode; 257 cells; all
  rejected).
- Reproduce:

  ```
  rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m <lake> --include "opt_*_WEEK_*"
  rclone copy gdrive:IraAlgo-data-lake/index_1m <lake>
  IRAALGO_LAKE=<lake> python3 research/hunt/r92_orb_time/r92data.py build
  python3 research/hunt/r92_orb_time/r92study.py tables design
  python3 research/hunt/r92_orb_time/r92study.py design
  python3 research/hunt/r92_orb_time/r92report.py
  ```

  The holdout read is spent: `r92study.py holdout` refuses to run again.
