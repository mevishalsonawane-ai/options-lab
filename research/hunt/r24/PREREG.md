# R24 pre-registration: the what-if table, back-tracking big winners and losers, and rules built from the patterns

Written 13 Oct 2026 (IST evening; Boss's request is dated 14 Oct), BEFORE any what-if outcome was computed and before
any pattern was mined. This file is committed on its own before the first run; the commit hash is quoted in the
write-up (`research/HUNT_R24_WHATIF_PATTERNS.md`). Nothing below may change after that commit. A change needs a new
entry, a new pre-registration and a fresh holdout.

Boss (14 Oct): "Add calculation data to the brain for every call/put option: what could have happened, the profit or
loss, if we had bought it. Find all the amounts and back-track them, find all the patterns, find the most common
pattern, and create a strategy based on it."

Research only. Nothing here changes the app. A rule that passes is described for a paper-only arm; it is not built.

## Data and periods

- Source: the Dhan lake on Google Drive (`gdrive:IraAlgo-data-lake`, uploaded 13 Oct 2026): `index_1m_{NIFTY,BANKNIFTY,
  INDIAVIX}_<YEAR>`, `opt_{NIFTY,BANKNIFTY}_WEEK_<YEAR>` (rolling ATM−10..ATM+10, nearest expiry; BANKNIFTY's "WEEK"
  series follows the monthly after NSE dropped its weeklies in Nov 2024), read through the brain's lake adapter and
  cube (`research/brain`, cube v2). The option minutes start Aug–Oct 2021, so the study runs from the first session
  with an option chain to 12 Oct 2026.
- The `MONTH` option series (nearest monthly) is not on Drive yet. The code takes it with no change once it is (one
  command, see the write-up). Until then "monthly" exists only where the WEEK series is the monthly (BANKNIFTY from
  Nov 2024).
- Periods (the lake notes, R18 PREREG amendment 1): **design = sessions up to 2026-04-13**; embargo 2026-04-14 ..
  2026-04-26, used by nobody; **holdout = 2026-04-27 onwards, both indices, read ONCE** (a flag file refuses a second
  read), and only if it has at least 100 sessions per index.
- Note for honesty: the design period contains the release sessions (Feb 2024 – Apr 2026) that R16–R23 examined.
  Nothing from those studies is used to pick any setting here.

## Step 1: the what-if table

For each session, index (NIFTY, BANKNIFTY), side (CE, PE), strike offset o = −3..+3 from the ATM of the decision
minute (ATM = the index close rounded to the strike step, 50 / 100), and entry minute 09:20 .. 14:45 (the bar the
order fills in; the decision is on the previous bar's close, so the ATM, the features and the strike are known first):

- Contract = that strike and side of the nearest expiry (the expiring contract on its expiry day). Its own minute path
  is followed by strike, whatever offset it drifts to (the chain covers ATM±10).
- Fill = the first traded minute's open in the entry minute .. +2 (a 3-minute LIMIT window, never later), at the ASK
  (below). No traded open in those 3 minutes: no fill, the row is kept with `filled = False` and no outcomes.
- Only traded bars (volume > 0, cube flag `ok`) count as prices. Untraded minutes are never filled in.
- Lot = the NSE lot in force that day. All rupee figures are per lot.

Costs (the hunt studies' model, `research/obuy/costs.py`, plus the spread by moneyness):

- Buy at `adverse_bps(open, +5 bps)` + a half-spread HS; sell at `adverse_bps(price, −5 bps)` − HS (a resting stop:
  −10 bps).
- HS = max(h × price, ticks × Rs 0.05). h by moneyness (in ITM steps for that side; h24's real snapshot gave
  0.05–0.24% from ITM2 to OTM2): ITM 3/2/1 and ATM 0.16%, OTM1 0.20%, OTM2 0.25%, OTM3 0.35%. ticks by the contract's
  volume in the 5 minutes before (the `liq` model): ≥ 20 lots 1, 1–20 lots 2, none 4; a resting stop's fill uses
  twice the ticks.
- Charges: the app's model at today's rates (`Costs("app")`: Rs 20 an order, STT 0.15% of the sell, exchange, SEBI,
  stamp, GST), on the buy and on every sell.
- Stress: twice HS.

Outcomes per row (net = after both fills and all charges):

- `net_rs_<x>`, `net_pct_<x>` for fixed exits: x = 15, 30, 60 minutes after the fill (sold at the open of that minute;
  the first traded open from it on, never after 15:20), 1505 (the 15:05 open) and 1520 (the 15:20 open). net_pct =
  net Rs / (fill × lot) × 100.
- Best and worst price from the fill minute to 15:19 (traded highs and lows): `mfe_pct`, `mae_pct` (vs the fill),
  `mfe_rs`, `mae_rs` (per lot, before exit costs), `t_best` (minutes from the fill to the first minute at the best
  high), `t_worst`.
- The app's real exit families (`android/engine` arm rules), each net of costs, with its exit minute and reason:
  - `orb`: resting stop at fill − 40 points (none when the fill is ≤ 40, as `OrbRules.stopTrigger`), target +40 on a
    minute's high sold at the next open, the profit-lock ladder resting (+10 → breakeven after charges, +20 → +10,
    +30 → +20; a rung counts from the next minute), out at 15:10, or 15:05 when the contract expires that day;
  - `sweep`: as `orb` with −40 / +80 and rungs +20 / +40 / +60;
  - `liq`: Liquidity 15+5's own exits without its index-level target (that target belongs to its signal): resting stop
    at −15% of the fill, the time stop (the first minute from 20 minutes after the fill: a close below +5% sells at the
    next open), out at 15:10 / 15:05.
- Stored as Parquet per index and year with a catalog (rows, days, sha256, columns), on Drive
  (`gdrive:IraAlgo-data-lake/derived/r24_whatif/`), never in git.
- Brain labels: a compact per-minute summary (ITM1 / ATM / OTM1, CE and PE: net % at +30 and 15:05, MFE %, MAE %,
  `orb` net Rs; plus the best net % at 15:05 among the 14 options and the share of them that won) as `y_wi_*` LABEL
  columns keyed by the decision minute. They are written apart from the features and meet them only through
  `research/brain/split.join_forward`. A test proves they never enter a feature frame.

## Step 2: back-tracking (design period only)

Rows: every design row on a 5-minute entry grid (09:20, 09:25, .. 14:45; 66 a day), all 14 options, both indices.

- BIG WINNER: net % at the 15:05 exit ≥ +50%. BIG LOSER: net % at the 15:05 exit ≤ −50%. (A fixed exit, so no hindsight
  in the label's rule.)
- Second view (reported, not used to pick rules): top and bottom decile of `orb` net Rs per lot, per index.

Point-in-time features at the decision minute (the brain's feature store, look-ahead-guarded): side-ALIGNED ones are
multiplied by +1 for a call and −1 for a put ("with the option" = up for a call, down for a put).

| item | brain feature(s) | bins (design terciles per index unless stated) |
|---|---|---|
| trend since the open, aligned | `ret_day` × s | against / flat / with |
| last 15 minutes, aligned | `ret_15m` × s | terciles |
| ORB 15-minute position, aligned | `or15_state` × s | beyond the far edge / inside / beyond the near edge |
| VWAP-proxy distance, aligned | `dist_tpavg_pct` × s (the lake's index has no volume; futures VWAP exists only for live contracts) | terciles |
| place in the day's range, aligned | `pos_in_day_range` (call) / 1 − it (put) | terciles |
| room to the next level ahead | `lvl_above_dist_pct` (call) / `lvl_below_dist_pct` (put) | terciles |
| distance to the level behind | the other one | terciles |
| levels near | `lvl_near_count` | 0 / 1 / 2+ |
| ATM IV | `atm_iv` | terciles |
| IV change, 15 min | `atm_iv_chg_15m` | terciles |
| IV rank | `iv_rank_252` | terciles |
| own-side OI build-up today | `oi_build_ce_pct` (call) / `oi_build_pe_pct` (put) | terciles |
| other-side OI build-up today | the other one | terciles |
| put-call OI ratio | `pcr_oi` | terciles |
| VIX | `vix` | terciles |
| VIX change vs yesterday | `vix_chg_day_pct` | terciles |
| gap, aligned | `gap_pct` × s | terciles |
| realised vol, 30 min | `rv_30m` | terciles |
| trend strength (day-type proxy) | `adx14` | terciles |
| range used (day-type proxy) | `day_range_pct` / `em_vix_1d_pct` | terciles |
| straddle since 09:20 | `straddle_vs_0920` | terciles |
| time of day | `minute_of_day` | 09:20–09:59 / 10:00–11:29 / 11:30–12:59 / 13:00–14:45 |
| days to expiry | `dte_days` | 0 / 1–2 / 3–6 / 7+ |
| option | side; moneyness ITM3–2 / ITM1 / ATM / OTM1 / OTM2–3; index | as stated |

Order-flow summaries are not in the lake (they exist only in the phone's recordings since Oct 2026), and the brain's
day-type is not on this branch (sibling session): both are left out and said so. A feature that is missing on a row
puts that row in no bin of that item.

Mining (design only):

- Itemsets of 1–3 items from different rows of the table. For each: support (share of rows, distinct days), the big-
  winner rate and LIFT (rate / base rate), the big-loser rate and its lift, and the mean net at 15:05 and under `orb`
  (Rs per lot). Kept only with ≥ 0.5% of rows and ≥ 100 distinct days.
- Reported: the top 20 by winner lift, by loser lift, and by mean net (`orb`); and the "most common among big winners"
  (by share of winners covered) next to the same itemsets' share of ALL rows, to show whether "common among winners"
  is just "common".
- A decision tree (depth 4, leaves ≥ 2% of rows, balanced classes) for big winner vs the rest; its leaves with lift and
  net. Descriptive only.

## Step 3: rules (at most 8), twins, walk-forward, one holdout read

Turning a pattern into a rule: the pattern must fix a side (a side item or at least one aligned item; otherwise it is
not tradable and skipped). The rule buys, per index, at the FIRST decision minute of the day (entry 09:20–14:45) at
which the pattern holds, 1 lot of: the pattern's moneyness if it has one (the bucket's nearer-the-money strike:
ITM3–2 → ITM2, OTM2–3 → OTM2), else ATM; one trade per index per day; fills, costs and exits from the what-if table.
Exit family = the one of `orb`, `sweep`, `liq` with the highest design net for that rule (3 tries per rule, all
counted).

Selection (design only, mechanical): eligible = itemsets with winner lift ≥ 1.3, ≥ 100 distinct days, that fix a side
and whose rule (as above) has design net > 0 under its chosen exit. Ranked by lift × support (the brief's order),
highest first, skipping one whose design trade days overlap ≥ 70% with a rule already chosen (same index and side), until
8 rules or none left.

Walk-forward (design): 4 consecutive blocks of design sessions. For k = 2, 3, 4: bins re-cut, patterns mined and rules
selected on blocks before k only, then scored on block k. Reported per block. The final rules are re-selected once on
the whole design period and only those are read on the holdout.

Controls:

- Random-entry twins: 1,000 sets; each trade re-entered at a uniformly random entry minute 09:20–14:45 the same day,
  same side, the same strike rule (offset from the ATM of that minute), the same exit family. p = share of twin sets
  with holdout net ≥ the rule's.
- Random-pattern twins: 200 itemsets drawn at random among those of the same size (number of items) whose design
  support is within ±25% of the rule's and which fix a side, turned into rules the same way (exit chosen on design the
  same way). p = share with holdout net ≥ the rule's.

Gates (a rule passes only if ALL hold):

1. Design net > 0 in at least 3 of the 4 design blocks.
2. Holdout (read once): at least 30 trades and net > 0.
3. Random-entry twin p, **Benjamini–Hochberg q < 0.05** across the R24 rules together with the holdout p-values of
   the R20–R23 rules (their entries).
4. Random-pattern twin p < 0.05.
5. Holdout net > 0 at twice the half-spread.
6. Holdout max drawdown smaller than Rs 40,000 per lot (Boss's account stop).

Reported per rule, design and holdout: trades a day, win rate, average win and loss, Rs a day per lot, % green days,
worst day, max drawdown, big winners cut short (the option reached +50% net of the fill before 15:05 but the rule's exit
kept under half of that move), and the walk-forward blocks.

A passing rule is described for a PAPER-only arm (logged, labelled, undoable in one tap, never carried to live by
itself). Nothing is built in this study.
