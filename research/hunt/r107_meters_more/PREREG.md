# R107 pre-registration: use the GEX meter and the danger meter to trade MORE on the days they flag

Written 26 Oct 2026, **before any R107 number was computed** (this file is committed and pushed on
`claude/r107-meters-more-trades` ahead of the code's first run). Research only; nothing in `android/**` changes.

Boss, 26 Oct: *"Can we check the GEX meter [and the danger meter: "Both"] and with their help increase the strategies?"*

What is already known (not re-tested here):
- R17 (danger meter I4): the flagged ("careful" / "paper-only") days lost LESS than normal days on 122 holdout days; the
  meter failed as a skip / half-size rule. The app shows it as advice, with an off-by-default "half size" switch.
- R28 (dealer GEX): negative GEX at 09:20 / 10:00 predicts a WIDER rest-of-day range on all 5 indices, not the
  direction. 0 of 10 GEX skip filters helped any arm. R36 / R37: 0 of 52 gamma rules on expiry.

The NEW question: instead of skipping, trade **more** (bigger size or one more position) on the days a meter flags.

Signal source for every rule: **the index** (CLAUDE.md default). The arms' entry signals, strikes and exits do not
change. The meters only decide the SIZE (1 or 2 lots) or whether a 3rd same-direction position may open; GEX is
option-chain context around an index signal, which CLAUDE.md allows.

## 1. Arms, engine, periods (as R101 / R106)

- Engine, stores, fills, charges and taking rules exactly as R106 / R101 (`research/hunt/r106_small_targets/`,
  `research/hunt/r101_arms_enhance/`): R51 Amendment A clean stores (`R51_CLEAN=1`), R64's held-contract simulator,
  R17 fills (the half-spread depends on the order size against the contract's 5-minute volume), dated Zerodha charges,
  next-minute fills. The arms (B1 = today's paper rule, 1 lot) must reproduce R101's published trades and net first
  (asserted in code):
  - Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5): 881 / Rs 2,57,249 design; 357 / Rs 11,155 final
  - R72 Breadth Donchian: 3,071 / Rs 2,16,929; 1,202 / Rs 1,71,366
  - R51 Donchian 20: 4,498 / Rs 9,998; 1,781 / Rs 81,845
  - R52 Open outside value: Rs 2,90,573; Rs 48,612
- **2 lots** = the same trade priced again by the engine with twice the quantity (so the fill's half-spread and the
  per-order charges are those of a 2-lot order), never simply 2 x the 1-lot net. Which trades are taken stays as at
  1 lot.
- Periods: design 20 Nov 2024 - 31 Mar 2026; walk-forward window Apr 2025 - Mar 2026 (four quarters; nothing is fitted
  in R107, so it is reported as its own period); **final 1 Apr - 13 Oct 2026, read ONCE** (`results/FINAL_READ`),
  already read by R51-R106 (weak evidence). The post window (14-19 Oct) is not used: the GEX table ends 12 Oct.

## 2. The two meters (fixed now; nothing is fitted in R107)

**GEX meter** (R28's core, `research/brain/gex.py`; the per-minute table `gdrive:IraAlgo-data-lake/derived/r28_gex/`):
S1 net dealer GEX of the near expiry, `near_net_cr`, of the trade's OWN index. "Negative GEX" = `near_net_cr < 0`.
- A reading is the value on the bar that closes at 09:20 (grid row 4) or at 10:00 (row 44); each is computed from
  that session's earlier rows only.
- The reading a trade uses: the 10:00 one when its entry row is >= 45 (10:00 onwards), else the 09:20 one (entry rows
  5-44). R52 always enters at 09:20 (row 5), so it always uses the 09:20 reading. No reading (a missing day, or
  13 Oct, after the table ends) = not flagged.

**Danger meter**: the APP's fixed model, copied exactly from `PreTradeChecks.kt` `DangerMeter` (19 buckets, score =
sum of the buckets' shrunk deviations; paper-only at <= -753.9, careful at <= -372.5, else normal; gap cuts 0.11 /
0.301 %, VIX cuts 10.68 / 11.98, VIX move +-5%). Inputs known by 09:16:
- gap: BANKNIFTY's first-minute open against its previous session's last close, in %;
- VIX: INDIA VIX's previous session close; VIX change: that close against the close of the session before it;
- event day: the RBI policy and Union Budget dates (`research/brain/calendar_data/events.csv`; 2026 from the app's
  bundled list in `EventCalendar.kt`) plus the app's rule-made days (NSE rebalance: last trading day of Mar and Sep;
  MSCI rebalance: last trading day of Feb, May, Aug and Nov). The app's "expiry moved by a holiday" rule is not
  modelled (such a day is an expiry day anyway);
- expiry day: NIFTY or BANKNIFTY expires that day (the R51 stores' data-derived expiry);
- weekday.
"Flagged" = careful or paper-only. Caveat stated now: the app's model was fitted (on the ORB-family arms' summed net,
not these arms) on sessions to 23 Feb 2026, which overlaps the design window; only the final window is out of its
sample.

## 3. The tests (11, fixed now)

Per arm (the arm's own record, 1 lot, no account cap, as R101 / R106), on LIQ, R72, R51 and R52:

| id | rule |
|---|---|
| G2-LIQ, G2-R72, G2-R51, G2-R52 | **2 lots** when the trade's index shows negative GEX at its reading; 1 lot otherwise |
| D2-LIQ, D2-R72, D2-R51, D2-R52 | **2 lots** on the danger meter's flagged days (careful / paper-only); 1 lot otherwise (R17 found flagged days better) |

The paper book (all four arms together, today's same-direction cap of 2, 1 lot):

| id | rule |
|---|---|
| G3 | a **3rd same-direction position** may open when the NEW entry's index shows negative GEX at its reading (else the cap stays 2) |
| D1 | **2 lots** for every arm on the danger meter's NORMAL days; 1 lot on flagged days |
| D3 | a **3rd same-direction position** on the meter's flagged days (else 2) |

The paper book's walk: each arm's own taking rule (Liquidity one a book; Donchian / R72 one open a side and 2 fresh
fills a side a day; R52 one a day) plus the app's `SameDirectionCap`: at most N open automatic index-option positions
betting the same way (call = up, put = down) across all arms. A position is open from its fill row to its exit row
(inclusive). A refused entry takes nothing: the arm's book stays free and it is not counted as a fill. Same-minute ties
go in the order R72, R51, LIQ, R52, then NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, then the book. R72's
"reserved place" (22 Oct) is not modelled.

Not tested, with the reason:
- **S3 "switch on ORB / ORB Fresh only on negative-GEX days at 10:00"**: this is exactly R28's filter G1 ("ORB: keep
  only S1 < 0 at 10:00"), already tested on the 6-year lake: 425 trades, still -Rs 57 a trade in design, no better
  than random days (twin p 0.94), still losing in its holdout. R92 found no time at which ORB makes money. A second
  test of the same rule on overlapping days would only add a multiple-testing try.
- **The mirror "2 lots on positive-GEX days for mean-reverting arms"**: none of the four arms is mean-reverting (R52 is
  "open outside value AND GO", a continuation trade).
- **Night R3**: it holds overnight and needs R25 / R27's separate data; R28 G10 already showed GEX days are the wrong
  days for it. **Solo 2.0**: its final picks do not reproduce (R106). **MCX arms**: another lake.

## 4. Baselines and twins

- **B1**: today's rule (1 lot; the book with cap 2). **A2**: always 2 lots (every trade re-priced at 2 lots). For the
  slot tests (G3, D3) the "always" baseline is **A3**: the cap is 3 every day.
- **Random-day twin** (10,000 sets, seed 107, drawn within the period):
  - G2 / G3: the GEX readings are shuffled across the period's days WITHIN each index (both readings of a day move
    together), so each index keeps its own number of flagged days;
  - D1 / D2 / D3: the meter's day levels are shuffled across the period's days.
  The twin applies the same extra size / slot to its shuffled days.

## 5. What is reported (every test, B1, A2 / A3, every period)

Trades, trades sized up (or extra positions), net, net per session, worst day, max drawdown (daily, closed P&L),
**net / max drawdown** and **net / |worst day|**, the twins' median of both, the twin p, the worst calendar month and
the number of months below -Rs 13,000, and whether max drawdown passes Rs 40,000 (the account rule, at the paper size
of 1 lot = B1, and for every variant). For each meter, what it really tells: each arm's net per trade and per day on
flagged vs unflagged days (descriptive).

## 6. Gates (all must hold; never relaxed)

1. Net > 0 in design, in the walk-forward window and in the final.
2. In EACH of design, walk-forward and final: net / max drawdown AND net / |worst day| above B1's and above A2's (A3's
   for the slot tests).
3. In EACH of design, walk-forward and final: both ratios above the random-day twins' median.
4. Final twin p (share of twins whose net / max drawdown is >= the variant's; p = (1 + count) / 10,001) passes
   **Benjamini-Hochberg q <= 0.10** counted against R107's 11 tests plus 573 earlier tests taken as p = 1 (none
   passed): R17 3, R28 10, R28b 378, R36 4, R37 4, R105 20, R105B 40, R105C 60, R105D 4, R105E 6, R105F 8,
   R106 36 = 584 in all. q within R107 alone is also shown.
5. At least 30 final trades sized up (or 30 extra positions for G3 / D3).

The account rule (max drawdown Rs 40,000; a month at -Rs 13,000) is reported for every variant and baseline, and a
variant that passes 1-5 but breaks the account rule more often than B1 is flagged as such.

## 7. What a pass means (CLAUDE.md)

A passing rule may act on PAPER for that arm (or the paper book) straight away: a labelled switch, logged in the
Autopilot change timeline, undoable in one tap, shown to Boss, with the arm's plain-rules record kept rebuildable. It
is applied later by an app session, not here. Nothing goes live from R107. A failed rule stays a note in
`research/HUNT_R107_METERS_MORE.md` and `research/experiments/entries/r107-*.yaml`.
