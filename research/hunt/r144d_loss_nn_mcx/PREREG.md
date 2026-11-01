# R144d pre-registration: every losing trade of the network arms and the MCX arms, what makes them lose, and 2-3 skip filters per arm

Written 1 Nov 2026, **before any R144d number**. Research only: no app code changes, nothing trades. One of four parallel
studies (R144a-d) on Boss's question of 1 Nov: *"Find all the lost trades in indian market from our study for current
arms, and find what all makes it lose money ... find patterns for each arm, so it could help us to ignore those
trades."* R144d owns six arms:

| arm id | arm | engine it is rebuilt from |
|---|---|---|
| `S5` | S5 Trail (R128 / R123's morning network pick, as the app runs it: app IV + measured offset) | `r128_missing/s5_app_check.py --cal` (R123 N1 fold networks, R128 x1 trailing exit) |
| `CISD` | CISD + network (R133's S3-V2-E3) | `r133_ict_nn_exits` trades (R131 entries, R132 E3 exit, R132's N-EV filter on R123 N1 out-of-sample predictions) |
| `NGEVE` | NG evening breakout (M3 rule EVE) | `hunt/m3` (`run.py`, `m3lib.py`) and `McxEveRules` |
| `SILM` | SILVERM morning OTM call (M4's frozen Q4) | `hunt/m4` and `McxMorningRules` |
| `TREND` | MCX trend, 12-month (M2's TSM252 on the minis) | `hunt/m2/engine.py` and `McxTrendRules` |
| `USSIL` | US-night silver (R2 rule C2) | `hunt/r2` (`hourly.py`, `cand.py`) and `McxUsSilverRules` |

Each arm is rebuilt with its own engine, unchanged, 1 lot, the engine's own dated charges and slippage. Before any
anatomy, each rebuild is checked against the engine's published numbers for its own published period; the match (or
the gap, and why) is written in the results. **If an arm cannot be rebuilt in a period, the write-up says so** and no
number is invented for it.

## Periods (the same calendar for every arm)

| tag | dates | role |
|---|---|---|
| `U` | 1 Dec 2022 - 19 Nov 2024 | the unseen two years (as R143); test only |
| `D` | 20 Nov 2024 - 31 Mar 2026 | design: the only period used to choose filters; halves D1 (to 31 Mar 2025) and D2 |
| `F` | 1 Apr 2026 - the lake's last day (19 Oct 2026) | final; test only |

- **Network arms (S5, CISD):** only out-of-sample network scores are used: R123's walk-forward fold models
  (F1 2023 ... F4 to 15 Mar 2026) and the holdout model for Apr - Oct 2026. Nothing before 1 Jan 2023 (no
  out-of-sample score), so their `U` starts 1 Jan 2023. 16 Mar - 31 Mar 2026 has no network score (R123's embargo) and
  is empty for them.
- **MCX arms:** every period their data covers. US-night silver needs COMEX silver and USD/INR at 08:30 IST; where
  that is missing it is said.

## What is a loser

Net after charges and slippage below 0, 1 lot. "Losing days" = the sum of the arm's day P&L over days whose P&L is
below 0.

## Features at entry (all read strictly before the entry minute; a look-ahead guard test proves it)

The guard: every feature is computed by a function that is handed only the bars that ended before the entry; a test
re-computes the features after overwriting every bar at or after the entry with garbage and requires identical values.

- **Price action and time** (on the underlying): push into entry (last 3 bars' move in ATR, signed with the trade);
  the day's range so far / 20-day average range; 20-day trend (close vs its 20-day mean, in ATR, signed with the
  trade); gap (open vs previous close, signed with the trade, in ATR); distance to the previous day's high / low and
  today's open (in ATR); minute of day; weekday; trading days to the option expiry (index arms) or to the contract's
  last day / roll (MCX).
- **Volume:** the option's volume in the 15 minutes before entry vs its 20-session average at the same minutes (index
  arms); the future's volume vs its 20-session average at the same minutes (MCX; index futures only where the lake has
  them, from Jul 2026, so not used for the index arms).
- **Liquidity:** the option premium at entry (Rs and % of the underlying); a spread proxy (the median one-minute
  high-low of the option over the last 15 minutes, % of the price; the lake has no quotes); the option's OI; the share
  of the last 15 option minutes with no trade (index arms). MCX: the future's minute range proxy and OI; the option's
  where an arm buys options.
- **OI / options** (index arms): ATM OI change over the session so far; PCR (put OI / call OI, ATM +/- 3); ATM IV and
  its change since 09:20; skew (OTM put IV - OTM call IV).
- **Market context:** India VIX level and its change from the previous close (index arms); the day regime (the
  previous day's range / 20-day average range; the 5-day return in ATR). MCX: COMEX / NYMEX overnight move where the
  lake or the R2 study data hold it, USD/INR move where held, a US-data-day flag (R2's calendar where held), US daylight
  saving flag. Breadth for the index arms: the share of the index constituents above their 09:15 open, where the lake's
  equity minutes cover the day (else missing).
- **Network arms:** the network's EV of the chosen cell and its confidence (P(win) of that cell).
- **Order flow, proxies only (labelled as proxies):** tick-direction volume delta of the option over the 15 minutes
  before entry (volume of up-close minutes minus down-close minutes, over total); the OI-price quadrant of the option
  over that window (price up/down x OI up/down).

A feature with no value for a trade is "missing" and shown as its own bucket; it is never filled with a future value.

## Anatomy (per arm, per period U / D / F)

- trades, loser rate, average loss, money lost by exit type;
- bunching: the share of losers on days where every trade of the arm lost; the longest losing streak;
- the 10 worst trades with their features;
- loser rate and average net by quintile of each feature, quintile cuts fixed on design (D) and reused unchanged in U
  and F.
- **"Steady"** = in every period with >= 30 trades, the bucket's loser rate is above the arm's loser rate in that period
  and its average net is below 0, and the arm has at least two periods with >= 30 trades. Where a period has fewer than
  30 trades, it says so and no steady claim is made from it.

## Skip filters (at most 3 per arm, chosen on D only, then frozen and committed before any U or F number is read)

1. Candidates: "skip the lowest quintile" or "skip the highest quintile" of one feature (design cuts), or skip one level
   of a categorical feature, or "skip if either of the two chosen single filters" (one combination at most).
2. A candidate is eligible only if, in D: it skips >= 10 trades and <= 40% of the trades; the skipped trades' average
   net is below 0; and in both halves D1 and D2 the skipped trades' average net is below 0 (where a half has >= 5
   skipped trades).
3. Among the eligible, rank by the design cut in losing days (sum) per trade skipped, then keep the best 2 that are on
   different features, plus their "either" combination if it is also eligible. Fewer if fewer are eligible. An arm with
   no eligible candidate has no filter.
4. The chosen filters and their cuts are written to `results/filters_frozen.json` and committed before step 5.
5. **Test (R142's lose-less gates), in F and in U separately**, each against the arm as it runs today:
   - the sum of losing days is smaller;
   - the max drawdown, worst day and worst month are no worse;
   - the arm still makes money (net > 0) in that period;
   - **twin test:** 2,000 random skips of the same number of trades in that period; p = share of twins whose losing-days
     sum is at least as small; gate p <= 0.05. The primary p for BH is the F+U pooled twin test (random skips drawn
     within each period, same count per period);
   - BH q <= 0.10 across every filter tested in R144d (all six arms together);
   - a period with fewer than 30 trades is reported but cannot pass; the write-up says "too small".
   - **pass** = every gate in F and in U (where the arm has >= 30 trades there) and q <= 0.10.
6. Win rate before -> after in every period, against Boss's 65% rule.
7. Every raw p-value goes to `results/pvalues.json`.

## Not done

- No arm, filter or switch is built. Passing filters are proposed as switches only (shadow first; paper after Boss's
  approval).
- No re-fitting of any network, cut or exit.
- Signal source: every arm signals on the underlying (index spot; the MCX future; for US-night silver COMEX + USD/INR
  read before the open). Filters use option-chain data only as context.
