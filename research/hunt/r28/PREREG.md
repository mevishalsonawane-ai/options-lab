# R28 pre-registration: dealer gamma exposure (GEX) on Indian indices, as a filter on the existing arms

Part A written 14 Oct 2026, BEFORE any GEX value of any period was computed and BEFORE any filter outcome. Only the lake's
file list, its schema, one day of raw rows (to read the columns) and the OI unit check below had been read. Part A is
committed and pushed on its own. Nothing below changes after that commit. A change needs a new entry, a new
pre-registration and a fresh holdout. Research only: nothing in the app changes.

Boss (14 Oct 2026): "Use gamma exposure too."

## Prior evidence, stated before the test

- **R9 (9 Oct 2026, `HUNT_R9_VOLUME_GAMMA.md`) already tested GEX** from the NSE bhavcopy (prior evening, 3 expiries,
  ±8% strikes) under 4 sign conventions, plus the zero-gamma flip:
  - 40 predictive tests: only "size" effects passed (range, realised vol), and the main BANKNIFTY one REVERSED in the
    holdout;
  - 28 filters on the arms (ORB family, Range Fade, Liquidity): 0 passed. The Liquidity BANKNIFTY filter flipped sign
    between design and holdout.
- R28 differs from R9 in three ways: (1) GEX is INTRADAY and point-in-time (each minute, from that minute's option prices
  and OI), not the prior evening; (2) it is tested on the arms rebuilt on the 6-year lake, including Hero and Night,
  which R9 did not test; (3) the joined BH family includes R9's 68 gamma tests.
- **Expectation, written now:** most filters will do nothing. In India the option sellers are mostly prop desks, FIIs
  and HNIs and the buyers mostly retail, so the US sign convention ("dealers long calls, short puts") has no agreed
  meaning here (R9 saw the sign flip between periods). Any effect is more likely on the SIZE of the move than on its
  direction or trend-vs-chop.

## Data (the Dhan lake on Drive, `gdrive:IraAlgo-data-lake`, copied 14 Oct 2026)

- `options_rolling_1m/opt_<UND>_WEEK_<YEAR>` (the nearest expiry) and `opt_<UND>_MONTH_<YEAR>` (the nearest monthly) for
  NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX. At each minute: the 21 strikes ATM-10 .. ATM+10, calls and puts, with
  OHLC, volume, **OI (in units, not contracts: checked, the NIFTY total does not jump 3x at the 28 Nov 2024 lot change
  25 -> 75)**, the vendor's IV and the spot.
- `index_1m` for the five indices and INDIAVIX.
- Span: Aug 2021 (NIFTY, BANKNIFTY, FINNIFTY) / 2022-23 (MIDCPNIFTY, SENSEX) to 12 Oct 2026.
- Expiry dates: the files have no expiry column. Found from the data (R25's rule, `r25data.expiry_days`: the ATM time
  value at 15:15 below 0.6% of spot and more than doubling the next session), separately for the WEEK and the MONTH
  series. The expiry time is 15:30 IST on that day.
- Raw data and the GEX tables stay out of git (cache `~/.cache/iraalgo/r28`, Drive `derived/r28_gex/`).

## Step 1: the GEX feature (definitions fixed here)

At each minute t (the bar that starts at 09:15 + t; known at its close), for one index and one expiry series:

- **Price used:** each contract's last traded close at or before t, from earlier minutes of the same session only,
  at most 30 minutes old (a staler contract is left out). Spot: the index close at t.
- **IV:** Black-Scholes on spot (`research/brain/bs.py`), r = 6.5% a year, q = 0 (the brain's `config.py`), T = calendar
  minutes from the END of minute t to 15:30 IST on the expiry day / (365 x 1440). That includes the intraday time left
  on expiry day. Rows with no IV (price outside the no-arbitrage band, below Rs 0.10, or vega below one tick) use the
  IV of the nearest strike of the same side with an IV at t; with none, the row is left out.
- **Gamma** per unit per index point (`bs.greeks`), from that IV.
- **GEX of one contract, Rs per 1% move of the index** = gamma x OI (units) x spot^2 x 0.01. No lot multiplier: the
  lake's OI is already in units.
- **Sign conventions:**
  - **S1 (primary, the common US one):** dealers are long calls and short puts. GEX_S1 = sum over calls - sum over puts.
  - **S2 (customer net buying, from the OI change):** for each contract, from the session's first minute, add the OI
    change of each minute signed by that minute's price change (OI up and price up = customers bought new longs; OI up
    and price down = customers wrote new shorts; price flat = 0). Customers' net long units N_k(t) = that running sum.
    Dealers take the other side: GEX_S2 = -sum_k gamma_k x N_k(t) x spot^2 x 0.01, calls and puts alike. It sees only
    today's flow (the stock of OI from earlier days has no known owner), so it is 0 at the open by construction.
  - **S3 (writers short everything, the "Indian" view):** GEX_S3 = -(sum over calls + sum over puts) = minus the gross.
    It is always <= 0; only its size varies.
- **Outputs**, per index per minute (and read at the fixed clock times 09:20, 10:00, 12:00, 13:45, 15:15 = the value of
  the minute whose bar CLOSES at that time):
  - `gex_net_cr` (S1, Rs crore per 1% move), `gex_s2_cr`, `gex_gross_cr` (= -S3);
  - `gex_ratio` = S1 / gross, in [-1, 1] (scale-free);
  - `gex_norm` = S1 / the median of |S1| at the same clock time over the previous 20 sessions of that index (NaN with
    fewer than 10 of them);
  - **zero-gamma flip:** S1 recomputed with spot moved to S' = spot x (0.95 .. 1.05, step 0.05%), each contract's IV and
    T fixed; the flip is the crossing of zero nearest to spot (linear interpolation between grid points); NaN when
    there is no crossing on the grid. `gex_flip_dist_pct` = (flip / spot - 1) x 100; `spot_above_flip` (1/0);
  - **gamma walls:** the strike with the largest positive per-strike S1 (call side dominant) and the strike with the
    most negative one: `gex_wall_pos_dist_pct`, `gex_wall_neg_dist_pct` (strike vs spot, %), and the absolute
    biggest-gamma strike `gex_wall_abs_strike` (largest gross);
  - **near vs all:** the above for the nearest expiry (WEEK series) = "near"; and "all" = WEEK + MONTH when the MONTH
    series is a different contract from the WEEK series that day (by expiry date), else WEEK only.
    `gex_near_share` = |near S1| / (|near S1| + |month S1|).
  - **coverage gap:** only ATM±10 is stored, so far-OTM gamma is missing. `gex_edge_share` = gross GEX at offsets ±9 and
    ±10 / total gross, and `oi_edge_share` = OI at ±9, ±10 / total OI. Reported by index and by days to expiry.
- **In the Data Brain (L4):** the nearest-expiry outputs become registered features in the options family
  (`research/brain/features/gex_f.py`), computed from the cube's chain with the same core function
  (`research/brain/gex.py`), checked by the brain's look-ahead test and the catalogue check. The R28 study computes the
  same core on the lake's WEEK + MONTH files directly (the cube holds only the nearest expiry).
- **Look-ahead guard:** a test recomputes GEX at minute t with every row after t deleted and with every row after t set
  to absurd values; the values at t must be identical.

## Periods: design, embargo, locked holdouts (one per family), and why

Inventory of what earlier studies read (14 Oct 2026):

| family | locked holdouts already read | other reads |
|---|---|---|
| ORB, ORB Fresh/Sweep, Range Fade | release: BANKNIFTY >= 25 Aug 2025, NIFTY >= 13 Oct 2025 (R16-R22); R9: 1 Oct 2025 - 6 Oct 2026 (incl. its 28 GEX filters); lake >= 27 Apr 2026 (R24) | R23/R24 lake design 2021 - Apr 2026; h19 replays 2020-26 |
| Liquidity 15+5 | release holdouts (R19, R19b, R19c); R9: 1 Oct 2025 - 6 Oct 2026 | h24 replays (R9 design 2021-25) |
| Hero / expiry buying | R25: Aug 2021 - Dec 2022; H34, R12, R21, R24: Apr 2025 - Oct 2026 | R25 design 2023 - Oct 2026; OBUY walk-forward test years 2022-26 |
| Night R3 | R27: 1 Oct 2025 - 12 Oct 2026; X2's own holdout (release era) | R27 2021-22 descriptive, 2023-25 walk-forward |

No block of the lake is unread for any arm's PLAIN rules. What no study has read anywhere is an INTRADAY GEX filter on
these arms. The blocks below are those that were never a locked holdout for that family:

- **Family A (ORB, Range Fade, Liquidity 15+5, Night R3): holdout = the lake's start .. 31 Dec 2022.** Embargo
  1-15 Jan 2023. Design = 16 Jan 2023 .. 12 Oct 2026. Walk-forward = the design years 2023, 2024, 2025, 2026.
  Caveat: R9's design (bhavcopy GEX filters on the h19/h24 replays) covered 2021-22; R27 read 2021-22 descriptively.
- **Family H (Hero H0, lottery L3): holdout = 1 Jan 2024 .. 31 Mar 2025.** Embargo 18-31 Dec 2023 and 1-14 Apr 2025.
  Design = the lake's start .. 17 Dec 2023, and 15 Apr 2025 .. 12 Oct 2026. Walk-forward folds = 2021-22, 2023,
  2025-26. Caveat: R25's design and OBUY's walk-forward printed the plain rules' results on this block; never a
  locked holdout for the hero family.
- **Descriptive step (Step 2):** only days in BOTH designs: 16 Jan .. 17 Dec 2023 and 15 Apr 2025 .. 12 Oct 2026.
- Each holdout is read ONCE by `study.py holdout` (a flag file and `results/holdout.json`; a second read is refused).

## Step 2: describe (design days of Step 2 only; no rule is chosen from it)

Per index (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), regime = S1 at 10:00 (also 09:20 and 12:00; S2 and S3
reported as sensitivity), by sign and by `gex_norm` tercile:
1. rest-of-day range (10:00 - 15:29 high - low, % of the 10:00 close), realised volatility (1-minute), trend ratio
   (|15:29 close - 10:00 close| / that range);
2. ORB breaks (range 09:15-10:00, first 5-minute close beyond it by 13:55): the share that RUN (move >= 0.5 x ATR14
   beyond the edge before 15:10) vs FAIL (a 5-minute close back inside within 30 minutes);
3. expiry days: the 15:29 close's distance to the largest-gross-gamma strike at 13:45 vs to the ATM strike at 13:45
   (pinning);
4. spot crossing the flip level: |move| over the next 15 and 30 minutes after a cross vs the same clock times on
   non-cross minutes (per index, matched by hour);
5. expiry afternoons (NIFTY, BANKNIFTY weeklies, SENSEX): days with a 10x "hero blast" (some option at Rs 1-10 at 13:45
   reaching 10x its 13:45 close before 15:05) by the sign of S1 at 13:45;
6. Night: the next morning's |gap| (09:15 open vs 15:29 close, %) by the sign and tercile of S1 at 15:15.
Controls: each comparison also within VIX terciles (previous close) and by days to expiry (0, 1-2, 3+). Mann-Whitney
(two-sided) or a two-sample proportion test; BH at q 0.10 within Step 2. Descriptive only.

## Step 3: the 10 pre-registered filters (fixed here; no threshold is fitted)

All filters are SKIP rules on an existing arm, rebuilt on the lake with its own rules, fills and charges; the arm's
unfiltered trades are the baseline. The GEX is the arm's own index, nearest expiry, read at the stated time (never later
than the decision).

| id | arm | keep the trade only when |
|---|---|---|
| G1 | ORB (today's: R20 V0 via R22, BANKNIFTY + NIFTY) | S1 at 10:00 < 0 |
| G2 | ORB | NOT (gex_norm at 10:00 > +1): skip strongly positive days |
| G3 | ORB | S2 (customer net buying) at 10:00 < 0 |
| G4 | ORB | spot below the flip at 10:00 (the negative-gamma side) |
| G5 | Range Fade (R17 signals, R22 exits per index) | S1 at 10:00 > 0 |
| G6 | Range Fade | NOT (gex_norm at 10:00 < -1): skip strongly negative days |
| G7 | Liquidity 15+5 BANKNIFTY (R19b engine walk, lake data) | NOT (gex_norm > +1) at the signal minute |
| G8 | Hero H0 (R25's port of the app's Hero, NIFTY) | S1 < 0 at 13:45 (at the signal minute when it comes before 13:45) |
| G9 | Lottery basket L3 (R25: CE + PE near Rs 3 at 14:30, hold to 15:05; NIFTY, BANKNIFTY weeklies, SENSEX) | S1 at 13:45 < 0 |
| G10 | Night R3 (R27's rebuild of NightRules; NIFTY + BANKNIFTY) | S1 at 15:15 < 0 |

- Arms' rules, fills and charges are those studies' code, unchanged (R17 Fills 'liq' + app charges; R19b engine charges;
  R25 Costs 'app' + Fills 'liq'; R27 X2 costs + the 0.16% real half-spread). ORB and Range Fade trade the nearest
  expiry AFTER the day, so they take no trade on the index's expiry days in the lake (the lake has only the expiring
  contract then).
- When the GEX value is missing (no chain), the trade is KEPT (the filter cannot act) and the count is reported.
- **Random-filter twins:** 2,000 twins per filter; each skips the same number of trade-days, chosen at random among the
  arm's trade-days in that period. p = (1 + #twins with P&L/day >= the filter's) / 2,001.

## Gates (a filter passes only if ALL hold)

1. Design: kept P&L/day > the baseline's P&L/day (same sessions), and twin p < 0.05.
2. BH q < 0.10 on the design twin p, in the JOINED family: R28's 10 + R9's 40 gamma prediction tests + R9's 28 gamma
   filters (p-values from HUNT_R9; any not printed there entered as 1.0, which can only raise q) + the arm families'
   recorded holdout p-values in this repo (R22 holdout.json table, R25 holdout.json, R27 holdout.json where present).
   The q within R28 alone is reported too.
3. Walk-forward: kept beats the baseline in at least 3 of the 4 design years (family A) / 2 of 3 folds (family H).
4. At least 100 kept trades in design (family H: 30, it trades about once a week).
5. Holdout (read once): kept P&L/day > baseline P&L/day, and twin p < 0.10.
6. At 2x the spread (where the arm's code has the switch) the design difference stays > 0.
A passed filter may go to the PAPER arm only if the filtered arm's holdout P&L/day is also > 0 (a filter that makes a
losing arm lose less is a note, not a paper change). Never live.

## Reporting (each filter vs its arm's baseline; design, each walk-forward year, holdout)

Trades, win rate, average win / loss, P&L per session, % green trade-days, worst day, max drawdown, big winners cut
(skipped trades in the baseline's top 5% by net), kept / skipped counts, missing-GEX count, twin p, BH q (R28-only and
joined), sample sizes. Experiment entries `research/experiments/entries/r28-*.yaml` for the feature, the descriptive
step and each of the 10 filters, failures included.
