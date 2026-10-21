# R56 pre-registration: Boss's own manual NIFTY ORB plan (15-minute range, +Rs 1,000 target, range stop)

Written 15 Oct 2026, BEFORE any trade outcome was computed. Only the lake's file list and the session, expiry-day and
lot counts (`r56data.py summary`, no option prices of any trade) were read. This file is committed and pushed on its
own before any P&L. After that commit nothing here changes; a change needs a new note below, dated, saying it came
after the results.

Boss (15 Oct 2026) will trade this plan by hand and asked for its backtest. It is ONE rule, fixed by Boss, not chosen
from data, so there is no search and no Benjamini-Hochberg step. Research only: R56 changes no app code.

## A1. Data and window

- Lake (Drive `IraAlgo-data-lake`, fetched 15 Oct 2026): `options_rolling_1m/opt_NIFTY_WEEK_2024|2025|2026` (the
  nearest expiry on or after the day, ATM-10 .. ATM+10 strikes each minute) and `index_1m/index_1m_NIFTY_2024|2025|2026`.
- Window: **20 Nov 2024** (NSE's change; that day was a holiday, the first session is 21 Nov 2024) to the lake's last
  session, **14 Oct 2026**. Nothing before 20 Nov 2024 is read for trading or reported.
- `r56data.py summary` (15 Oct 2026, no prices): 471 sessions (design 337, recent 134); 470 have the 09:29 bar (the
  missing one is the 21 Oct 2025 Muhurat session, no trade). 100 expiry days (design 72, recent 28; weekdays Tue 55,
  Thu 39, Mon 4, Wed 2: Thursday expiries to Aug 2025, Tuesday after, holiday shifts). Lot read from the day's traded
  volumes on all 471 days: 25 (21 Nov - 26 Dec 2024 and 24 - 30 Jan 2025, contracts listed before NSE's change kept the
  old lot), 75 (27 Dec 2024 - 30 Dec 2025), 65 (from 31 Dec 2025). The repo's calendar table
  (`research/obuy/config.py LOT_SCHEDULE`) differs on 25 of those days; the data's lot is used (R49's rule).
- Clean-up (R47's measurement fixes, which can only lower results): option rows whose own spot is more than 3% off the
  NIFTY close of that minute are dropped (0 rows in this window); stray-print guard on fills and the target check
  (below). `research/data/spikes.py` is NOT on main on 15 Oct 2026, so no Data Brain spike flag is applied (noted in
  the report).

## A2. Split (fixed here)

- **Whole span**: 21 Nov 2024 - 14 Oct 2026.
- **Design part**: 21 Nov 2024 - 31 Mar 2026. **Recent part**: 1 Apr 2026 - 14 Oct 2026.
- Nothing is tuned on either part (the rule is Boss's), so the split is a stability check, not a holdout. The recent
  part is not pristine: R49 already ran ORB-type rules on NIFTY to 13 Oct 2026.

## A3. The rule (signal source: the NIFTY INDEX, the CLAUDE.md default; the option is only the instrument)

Grid: row t = the 1-minute bar that starts at 09:15 + t.
- **Opening range**: high and low of the 1-minute NIFTY index bars 09:15 .. 09:29 (15 bars). Needs the 09:29 bar,
  else no trade that day.
- **Signal**: scanning 1-minute index bars from 09:30 to 14:59 (a close known by 15:00), the FIRST bar whose close is
  above the range high -> buy a CALL; below the range low -> buy a PUT. Only the first signal counts: one trade a day,
  no re-entry. No signal by the 14:59 bar -> no trade. A gap that opens the day beyond yesterday is inside today's
  range by construction; a bar that already closes beyond the range at 09:30 is a signal at 09:30.
- **Contract**: ATM = the strike (step 50) nearest the index close of the signal bar (a tie rounds up, the app's
  half-up rule); the nearest expiry on or after the day (on an expiry day, the contract expiring that day, as the app's
  `expiryOnOrAfter`). 1 lot of that day's contract lot.
- **Entry**: buy at the open of the next minute of THAT contract (the first print within 3 minutes, else no trade,
  counted as "no fill"), plus slippage.
- **Held contract**: from entry to exit the trade is priced on that one (strike, side, expiry). Never the rolling ATM.
- **Stop (range stop)**: an index 1-minute close back beyond the OTHER side of the range (call: close below the range
  low; put: close above the range high). Sell at the next minute's open of the contract.
- **Target**: target level = entry price + Rs 1,000 / lot, rounded UP to the 0.05 tick (gross of charges). When the
  contract's minute high reaches it (stray-print-confirmed), sell at the next minute's open. The report gives both the
  gross P&L at exit and the net after charges.
- **Square-off**: the open of the 15:15 minute; 15:05 on an expiry day. A signal after the square-off is not taken.
- Same minute: if the stop and the target both trigger in one minute, it counts as the stop (worse fill).
- A held strike that leaves the stored ATM +-10 band: its exit uses the last stored close before ("stale", counted).

## A4. Costs and fills

- Charges in force on the date (`research/obuy/costs.py Costs("dated")`): brokerage Rs 20 an order; STT on option
  sells 0.1% to 31 Mar 2026 and 0.15% from 1 Apr 2026; NSE 0.03503% + IPFT 0.0005%; SEBI Rs 10 a crore; stamp 0.003% of
  buys; GST 18%.
- Fills: R49's (R17 "liq"): +-5 bps plus a half-spread of 1 / 2 / 4 ticks by the contract's traded lots in the 5
  minutes before (>= 20 / some / none). Range-stop exits use the stop slippage (10 bps, ticks doubled) because they are
  urgent market orders; target and square-off exits use the normal slippage.
- Stray-print guard (R47 amendment 1): a fill at an open more than 20% (and 2 ticks) away from the median of the
  contract's closes on the minutes before, at and after takes the WORSE of the two; a high more than 20% above that
  median cannot trigger the target.

## A5. Statistics

- **Random-entry twins** (1,000, seed 56): the same trade days, the same side (call / put) on each day; the entry
  minute drawn uniformly from 09:30 .. 14:59 (the rule's own signal window, so the twin can enter before or after the
  real break); the ATM at that minute; the same exits (range stop on the index, +Rs 1,000 target, square-off, same
  fills and charges). A draw that cannot fill is re-drawn up to 5 times. p = (1 + twins with net >= the rule's net) /
  1,001, one-sided, for the whole span and for each part (the same draws summed per part).
- No BH (one pre-specified rule).

## A6. Metrics (whole span, design part, recent part)

Trades (and sessions with no signal / no fill), win rate (net > 0), share of exits by target / range stop /
square-off / stale, average win and average loss (net), net total, net per trade, a per-month table (trades, net,
win rate) and months green, worst day, worst 5-session run (sum of daily net over any 5 consecutive sessions, no-trade
days count 0), max drawdown (cumulative net), longest losing streak (consecutive losing trades), expiry days vs other
days, calls vs puts. Loss size in rupees: the share of losing trades worse than -Rs 1,000 (net), and the loss
distribution (median, 75th, 90th percentile, worst; counts in buckets 0-500, 500-1,000, 1,000-1,500, 1,500-2,000,
2,000-3,000, over 3,000). Target trades: average gross at exit and average net.

## A7. Context only (labelled, NOT decisions)

- **C1 no target**: the same rule without the +Rs 1,000 target (hold to the range stop or square-off).
- **C2 Rs 1,000 hard stop**: the range stop replaced by a resting stop at entry - Rs 1,000 / lot (tick-floored), filled
  at the worse of that level and the minute's open with stop slippage; target and square-off unchanged.
- **C3 limit target**: the target filled at the target price itself (a resting limit sell), only when the minute's
  confirmed high trades at least one tick through it.
- R49's closest rule on NIFTY (`r49-g-orb-m15-k1`: 15-minute range, but 5-minute closes, ATM of 09:20, a 20-point
  premium stop / target, 1 entry a side, out 15:10) is quoted from R49's results for comparison, not re-run.

## A8. Tests (`test_r56.py`)

Range and signal on synthetic days; a no-signal day; an expiry day (15:05 square-off); a gap past the range (signal at
09:30); a missing 09:29 bar / holiday (no trade); a missing option print (re-tried, else no fill); held-contract
pricing; the target level per lot; the stop; dated charges; the look-ahead guard (the signal and the entry fill are
unchanged when every minute after the entry is replaced by noise); the twin draws stay in the window.

## Deliverables

`research/HUNT_R56_BOSS_ORB_PLAN.md` (plain English, signal source stated), `research/experiments/entries/r56-boss-orb-plan.yaml`,
`results/summary.json`, `results/report.md`, `results/trades.csv` (one row per trade, derived numbers only).
