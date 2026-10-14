# R29 pre-registration: multi-EMA (ribbon) patterns on the Dhan lake

Part A was written 14 Oct 2026, BEFORE any R29 outcome was computed (no forward move, no option P&L, no lift of any
pattern in any period). Only the lake's file list, schemas, date spans and the earlier studies' documents had been read.
Part A is committed and pushed on its own. Part B (the at most 10 rules) is added after the mining step (design data
only, Steps 1-2) and BEFORE any rule is run on a walk-forward year or the holdout. Part B may only fill in what Part A
leaves open. After the Part B commit, nothing here changes; a change needs a new entry, a new pre-registration and a
fresh holdout.

Boss's request (14 Oct 2026): "Try to use multiple EMAs at once, and check the patterns." Research only: no app change.
The sister session F1 (branch `claude/f1-india-factory`) screens single indicators (incl. EMA 9/20/50/200 crosses);
R29 looks only at what a single-indicator screen cannot see: the shape of many EMAs at once.

## What is already known (so R29 is not a re-run)

- **H25** (`research/HUNT_H25.md`) traded 195 single signals as option-buying triggers, incl. EMA crosses 5/13, 9/21,
  13/34, 20/50, 50/200, price × EMA 9/20/50/200, EMA slope turns, pullback to EMA 20/50 and "EMA ribbons" (one signal
  family, 2,160 variants). Nothing survived: after costs the average trade lost Rs 146, the ribbon family made
  Rs +1.3 a trade over random entries, and 0 of 4 ribbon families passed. Its locked holdout was 1 Oct 2025 – 6 Oct 2026.
- So a single ribbon signal is not expected to work. R29 tests whether **combinations** (ribbon state on one timeframe +
  a trigger on another, compression then expansion, pullback inside a stacked trend, multi-timeframe agreement) carry
  an edge after costs that the single signals did not. The expected answer is "no"; the study is built so that a "yes"
  would have to survive a holdout nothing has selected on.

## Coordination with F1 (checked 14 Oct 2026, 06:10 UTC)

`git fetch origin claude/f1-india-factory` → "couldn't find remote ref": F1 had pushed neither its feature code nor a
holdout choice when Part A was written. So R29 chooses its own holdout (below) by listing every period earlier studies
read. F1 may reuse it. **BH family:** R29's tests (Part A: 140 mining candidates; Part B: at most 55 confirmatory
tests) are one family with F1's. F1's count is not known yet; the report states R29's own family size, F1's count if
it has been published by then, and for every R29 result the largest combined family size at which it would still pass
BH at q = 0.10 (assuming, in R29's favour, that every F1 p-value is larger than R29's).

## Data

- **Lake** (Drive `IraAlgo-data-lake`, fetched with rclone remote `gdrive`):
  - `index_1m/index_1m_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX,INDIAVIX}_<YEAR>`: index minutes. NIFTY from 2020,
    BANKNIFTY / FINNIFTY / SENSEX from 4 Aug 2021, MIDCPNIFTY from 31 Jan 2022; all to 12 Oct 2026.
  - `options_rolling_1m/opt_<UND>_WEEK_<YEAR>`: Dhan "rollingoption", the nearest expiry, ATM-10 .. ATM+10 strikes chosen
    at each minute. Options from 2 Aug 2021 (NIFTY), 4 Aug 2021 (BANKNIFTY, FINNIFTY), 31 Jan 2022 (MIDCPNIFTY),
    15 May 2023 (SENSEX). After the weeklies of BANKNIFTY / FINNIFTY / MIDCPNIFTY ended (Nov 2024) the series is the
    nearest monthly: R29 buys whatever the nearest expiry is, as the app would.
- **Features** are the Data Brain L4 ribbon features (`research/brain/features/ribbon.py`, family "indicators"),
  computed with the same code on the whole index history of each underlying (EMAs run across sessions like a chart),
  checked by the look-ahead guard (`research/brain/tests/test_ribbon.py`). A pattern is used only after 60 sessions of
  index history (warm-up).
  - Ribbon RIB = EMA 5, 8, 13, 21, 34, 55, 89, 200; common set CLS = EMA 9, 21, 50, 200; bars of 1, 3, 5, 15 minutes,
    session-anchored, used from the row of their last minute.
- **Expiry days** are found from the data (R25's rule: ATM time value at 15:15 < 0.6% of spot and the next session's
  more than twice it). They set the square-off time only.
- **Gaps:** a minute without an index bar is skipped, never filled. An option minute without a price keeps the last
  price for marking (no exit is triggered on a missing bar); the count of trades with a missing exit-time price is
  reported. A held strike is followed by its strike number, so it can leave the ATM±10 window (≥ 500 NIFTY points): such
  a trade is closed at its last stored price and counted.

## Splits and the locked holdout

| part | period (all five indices) | used for |
|---|---|---|
| discovery | 2021 (from each index's first option day) – 31 Dec 2022 | Step 1 pattern mining |
| selection | 1 Jan – 31 Dec 2024 | Step 2 ranking by out-of-sample lift / expectancy; survivors |
| walk-forward | 1 Jan 2025 – 12 Oct 2026, by year (2025, 2026) | Step 3, read only after the Part B commit |
| **holdout (locked)** | **1 Jan – 31 Dec 2023** | Step 4, read ONCE, after the walk-forward |

SENSEX has no options before 15 May 2023, so it has no discovery days: its patterns are mined on the other four and it
is tested on selection, walk-forward and the holdout (May – Dec 2023) only.

**Why 2023.** Every period earlier studies read as a locked holdout (inventory of 14 Oct 2026, from
`research/experiments/entries/*.yaml`, `research/HUNT_*.md` and the R2x pre-registrations):

| study (examples) | locked holdout read |
|---|---|
| H25 (all MA/ribbon single signals), H34, R12, OBUY gates, about 50 entries | 1 Oct 2025 – 6 Oct 2026 |
| R16, R17, R18, R19C, R21 | NIFTY 13 Oct 2025 – , BANKNIFTY 25 Aug 2025 – (to Oct 2026 / Jan-Apr 2026) |
| R19C | 1 Apr 2025 – |
| R20 family, R22, R23 (ORB / ranges) | 27 Apr / 13 Apr – 12 Oct 2026; 2025-03-03 – 2025-08-22; 2024-08-01 – 2025-02-14 |
| R24 what-if patterns | 27 Apr – 12 Oct 2026 |
| R25 hero | expiry days only, 5 Aug 2021 – 29 Dec 2022 |
| R26-R28, F1 | not pushed when Part A was written |

- **No locked holdout has ever been 2023.** It is the only full calendar year of the lake that is not one.
- Honest caveats: 2023 has been DESIGN data for other studies. H25 ran its single MA / ribbon signals on it (pre-holdout
  2021 – Sep 2025), OBUY's walk-forward printed results for test year 2023, and R24's design mining covered it. So the
  market of 2023 is not unseen by the project, but **no multi-EMA combination (R29's subject) has ever been run on it**,
  and R29 reads none of its outcomes before the one holdout command.
- 2023 lies between the discovery and selection years. Patterns are not fitted to a trend in time (no parameter is
  estimated from dates), so a middle block is a fair test; the walk-forward years after it show whether anything
  decays over time.
- The holdout read is one command (`study.py holdout`). It writes `results/holdout.json` and a flag file and refuses a
  second read. Every other step asks the loader for its own part only (`r29data.load(und, part)`); asking for the
  holdout outside `study.py holdout` raises.

## Trade simulation (every pattern, rule, twin and baseline)

- **What is bought:** the nearest-expiry ATM option (primary) or 1-ITM (secondary; CE one strike below spot's ATM, PE one
  above), in the pattern's trade direction (CE for up, PE for down), **1 lot** (the lot in force that day: NIFTY 50 →
  25 → 75 → 65; BANKNIFTY 25 → 15 → 30 → 35; FINNIFTY 40 → 25 → 65 → 60; MIDCPNIFTY 75 → 120; SENSEX 10 → 20).
- **Signal time:** a pattern fires on the close of its bar (the row of the bar's last minute, t). The order fills at the
  option's open of minute t + 1. Signals whose bar closes 09:30 – 14:45 only.
- **Fills** (`research/obuy/costs.py` `Fills("liq")`): buy = open + 5 bps + a half-spread of 1 tick (≥ 20 lots traded
  in the 5 minutes before), 2 ticks (some) or 4 ticks (none); market sells the mirror; stop sells 10 bps and 2× the
  ticks. Stress: 2× the half-spread, reported for every rule.
- **Charges:** `Costs("app")` (today's rates: Rs 20 an order, STT 0.15% of the sell, exchange NSE 0.03553% / BSE 0.0325%,
  SEBI, stamp 0.003% of the buy, GST 18%) in every period, so periods differ only in the market.
- **Exits** (all checked minute by minute on the option's own 1-minute bars):
  - **X1 "lock" (primary, Boss's):** stop −30% of the entry price, resting. Once the open profit at a minute's close is
    ≥ +Rs 1,000 for the lot, the stop rises to (best close − Rs 500 / lot qty) and only rises. Time stop 60 minutes.
  - X2 "trail": stop −25%; once a close is ≥ +20%, a trailing stop 15% under the best close. Time stop 90 minutes.
  - X3 "fixed": stop −20%, target +40% (filled at the target only once the high trades a tick through it), time stop
    30 minutes.
  - X4 "time": out after 30 minutes; catastrophic stop −40%.
  - Square-off at 15:20 (15:05 on expiry days) on every exit. A stop fills at the stop price, or at the bar's open if the
    bar opens through it. If a minute's low reaches the stop and its high reaches the target, the stop is taken.
  - Close-based exits (lock, trail, time) sell at the next minute's open.
- **One trade at a time** per (rule or pattern, index); at most 3 entries a day; a new signal is taken only after the
  previous trade has closed.
- **Random-entry twins:** for every trade, a twin enters at a random minute (uniform over 09:30 – 14:45 bar closes of the
  same day and index) in the same direction with the same option choice and the same exit. 1,000 twin sets (seed 29). p
  (one-sided) = share of twin sets whose mean net per trade ≥ the rule's.
- **Big winners cut short:** a closed trade whose option, within the 120 minutes after the exit (before square-off),
  traded ≥ (exit price + 50% of the entry price). Reported as a count and a share.

## Step 1: pattern mining (discovery 2021-22 only)

Per index and pattern event, the labels are:
- the index move from the signal close over 15 / 30 / 60 minutes, signed in the trade direction, in bps and in ATRs of
  the signal's bar size;
- MFE / MAE of the index over 60 minutes (bps);
- the option's net P&L per lot under X1-X4, ATM and 1-ITM; and the twin-adjusted net (the trade minus the mean of its
  20 random-entry twins; the confirmatory steps use 1,000 twin sets).

**The candidates (fixed here: 140).** 35 pattern-timeframe events, each split by the sign of the event (bullish /
bearish) and traded two ways (follow = in the event's direction, fade = against it): 35 × 2 × 2 = 140.

| # | pattern (event on the bar close) | bar sizes |
|---|---|---|
| P1 | RIB full stack onset: the ribbon becomes fully stacked (from not) | 1, 3, 5, 15 |
| P2 | CLS full stack onset (EMA 9/21/50/200) | 1, 3, 5, 15 |
| P3 | twist: full order flip (`rib<tf>_twist`) | 1, 3, 5, 15 |
| P4 | pullback to the 21/34 band and close back (`rib<tf>_pullback`) | 1, 3, 5, 15 |
| P5 | compression then expansion (`rib<tf>_sqz_break`) | 1, 3, 5, 15 |
| P6 | ribbon breakout: the close leaves the ribbon (`rib<tf>_pos` turns ±1 from 0 or the other side) | 1, 3, 5, 15 |
| P7 | fan-out: fully stacked and the width change turns positive (from ≤ 0) | 1, 3, 5, 15 |
| P8 | MTF alignment onset: `rib_mtf_align` becomes ±3 | 1 |
| P9 | 5-min pullback with the 15-min ribbon fully stacked the same way | 5 |
| P10 | 1-min pullback with the 5- and 15-min ribbons fully stacked the same way | 1 |
| P11 | 5-min compression-expansion with the 15-min order ≥ 5/7 the same way | 5 |
| P12 | lower-timeframe twist back in the direction of a fully stacked 15-min ribbon | 1, 3, 5 |

Sign: +1 events are bullish (CE on follow, PE on fade), −1 bearish. Events are pooled over the four indices with
discovery days (each index's own twins), and also shown per index.

## Step 2: selection (2024 only) and survivors

- For every candidate, on discovery and on selection separately: trades, LIFT (mean signed 30-minute index move minus
  the same for its twins, in bps), EXPECTANCY (mean ATM X1 net per lot) and the twin-adjusted expectancy.
- p (selection) = one-sided, from a day-clustered t-test of the twin-adjusted ATM X1 net (per-day means), H1: > 0.
- **A survivor** has twin-adjusted expectancy > 0 in discovery AND in selection, ≥ 100 selection trades, and
  BH q ≤ 0.10 across the 140 selection p-values.
- Ranking: by selection twin-adjusted expectancy (never by frequency). Both directions are always shown; candidates
  that fail are listed as noise.

## Step 3-4: rules, walk-forward, holdout

- Part B registers at most 10 rules, each a survivor (or a conjunction of two survivors / a survivor plus a ribbon
  filter read off Steps 1-2), with its index list, exit (X1 primary) and option (ATM primary).
  - If fewer than 10 survivors exist, Part B also registers Boss's three named examples as written in the request
    (15-min fully stacked + 5-min pullback; compression-then-fan-out breakout; multi-timeframe alignment entry), marked
    "named example, not mined", so the holdout answers his question directly.
- Plus the **baseline B0** (the plain single-EMA cross: EMA 9 × EMA 21 on 5-minute bars, follow) and the random twins.
- Walk-forward: every rule, fixed, run on 2025 and 2026 separately (nothing re-fitted); then the holdout once.
- **Confirmatory family:** (rules + B0) × 5 indices ≤ 55 tests on the holdout, p vs the twins (ATM, X1), BH at q = 0.10.
- **A rule passes on an index** if, in the holdout, net P&L > 0, p < 0.05 and BH q ≤ 0.10, AND its walk-forward net is
  > 0 in both 2025 and 2026, AND it stays > 0 at 2× the half-spread. A passing rule may become a PAPER candidate behind
  a switch that is off by default; never live.

## Reporting (every rule, every index, every period)

Trades / day, win rate, average win and loss, P&L / day, % green days, worst day, max drawdown (daily), big winners cut
short, the 4 exits and 1-ITM side by side, discovery / selection / 2025 / 2026 / holdout, p vs twins, BH q, the sample
sizes (trades and days) with every number.

## Part B (14 Oct 2026, after Steps 1-2 on discovery + selection only; before any walk-forward or holdout read)

What Steps 1-2 found (`results/mining.json`, `results/mining.csv`; 93,650 discovery and 134,455 selection trades over the
140 candidates, ATM, X1):

- **No candidate made money after costs in both discovery and selection** except one with 2 and 9 trades (P12_5 bear
  fade), which is noise by any count. A random minute's trade lost Rs 169 (discovery) and Rs 99 (selection) per lot.
- **45 candidates passed Part A's survivor gate. All 45 are "fade" candidates, and every one of them LOSES money on its
  own** (selection mean Rs -10 to -203 a trade). They pass because their random twins lose even more.
- **Twin caveat** (seen in Step 2; it changes no gate): a twin keeps the trade's day AND direction. A ribbon event's sign
  says which way the day has been going, so a twin of a FOLLOW trade is a random entry in the day's own direction
  (selection twins +Rs 41 on average), and a twin of a FADE trade is a random entry against it (-Rs 241). The twin test
  therefore flatters fades and is harsh on follows. Part A's pass gate also needs net P&L > 0 on its own, so a fade that
  merely loses less than its twins cannot pass. From here on the report also shows, as a description only, a random
  minute with a random direction on the same day (`rand_any_per_trade`).
- Boss's three named ideas were mined as candidates and failed on selection (2024), both signs, ATM X1:
  15-min stacked + 5-min pullback (P9_5 follow) Rs -62 / -209 a trade; compression then fan-out (P5_5, P5_15, P11_5
  follow) Rs -104 to -209 except P5_15 bear +123 on 146 trades (q = 0.92, and -297 in discovery); multi-timeframe
  alignment (P8_1 follow) Rs -72 / -78. They stay notes (CLAUDE.md: ideas that failed the gates stay notes only).

**The rules (mechanical, as Part A fixes): the 10 best survivors by selection twin-adjusted expectancy**, each on all
five indices, ATM, exit X1, no filters, one trade at a time, at most 3 a day:

| rule | event | traded | discovery n / adj / net | selection n / adj / net | selection q |
|---|---|---|---|---|---|
| R1 | P3_3 twist, bearish (3-min) | fade (buy CE) | 162 / +173 / -174 | 244 / +227 / -85 | 0.009 |
| R2 | P3_5 twist, bullish (5-min) | fade (buy PE) | 104 / +155 / -226 | 157 / +218 / -10 | 0.050 |
| R3 | P5_15 compression-expansion, bullish (15-min) | fade | 111 / +104 / -229 | 182 / +213 / -37 | 0.015 |
| R4 | P2_15 CLS 9/21/50/200 stack onset, bearish (15-min) | fade | 166 / +194 / -111 | 241 / +194 / -69 | 0.056 |
| R5 | P5_3 compression-expansion, bullish (3-min) | fade | 457 / +151 / -141 | 603 / +192 / -11 | 0.0002 |
| R6 | P9_5 5-min pullback in a 15-min stack, bearish | fade | 206 / +151 / -159 | 338 / +159 / -29 | 0.010 |
| R7 | P3_3 twist, bullish (3-min) | fade | 160 / +238 / -143 | 232 / +156 / -127 | 0.050 |
| R8 | P10_1 1-min pullback in 5+15-min stacks, bearish | fade | 388 / +174 / -233 | 529 / +154 / -128 | 0.002 |
| R9 | P2_5 CLS stack onset, bearish (5-min) | fade | 486 / +189 / -56 | 661 / +154 / -111 | 0.063 |
| R10 | P1_5 RIB stack onset, bullish (5-min) | fade | 805 / +130 / -111 | 1,267 / +150 / -68 | 3e-6 |

(n trades pooled over the indices with options in that part; adj = mean net minus the mean of its 20 twins; net = mean
ATM X1 net per lot, Rs.)

Plus **B0** (EMA 9 × EMA 21 on 5-minute bars, follow, ATM, X1) and the random twins. Confirmatory family on the
holdout: 11 × 5 indices = **55 tests**; R29's whole family = 140 mining + 55 = **195**, to be added to F1's count.
**Expected result, written down before the read:** every rule loses money on its own in the walk-forward years and in
the holdout, so none passes Part A's gate.

Run order: `study.py design` (any time), `study.py wf` (2025, 2026), then `study.py holdout` once.
