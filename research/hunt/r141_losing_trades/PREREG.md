# R141 pre-registration: every losing trade of each arm, and the patterns in them

Written 31 Oct 2026, before any R141 number. Research only, **descriptive**: no rule is adopted. Anything it suggests
would need its own pre-registered test with R140's lose-less gates.

Boss, 31 Oct: *"Can we check and every losing trade and find a pattern in them for each arm"*.

## Arms and data

- **The four paper index arms**, rebuilt with R115's engine, which reproduces their published trades to the rupee:
  - R72 Breadth Donchian;
  - R51 Donchian 20 + Supertrend;
  - R52 Open outside value;
  - Liquidity 15+5.
- **The other app arms are not covered here, and the write-up says so:**
  - ORB, Night (R3), Solo, S5 Trail, S3-V2-E3 and the MCX / gold arms each need their own engine and data;
  - their losing trades are a follow-up.
- **Periods:**
  - design: 20 Nov 2024 - 31 Mar 2026, split into H1 (to 31 Mar 2025) and H2 (1 Apr 2025 - 31 Mar 2026);
  - final: 1 Apr - 13 Oct 2026. Earlier studies (R140) already read it, so here it is only a check.
- **A losing trade** is net < 0 after charges, 1 lot.

## What is measured for every losing trade (and winners, for contrast)

1. **How it lost:** the exit reason (stop, time rule, square-off, lock, Liquidity's rule exit), minutes held, loss
   size (below Rs 500, Rs 500-1,500, Rs 1,500-3,000, above Rs 3,000).
2. **Did it ever work?** Use the trade's peak premium gain before the exit (`peak`, as a share of the entry
   premium):
   - **never worked:** peak below 5%;
   - **gave it back:** peak at or above 15%, still lost;
   - **in between.**
3. **When and where:** index, side, weekday, expiry day, entry time (09:15-09:30, 09:30-10:30, 10:30-12:00,
   12:00-13:30, after 13:30).
4. **Clustering:**
   - losers per day;
   - the share of losing trades that fall on days when every trade of the arm lost;
   - the longest losing streak.
5. **Market context at entry** (R140 Amendment A's 17 features: push into the entry, day's range so far, stretch from
   the open, beyond yesterday's extreme, entry minute, last bar's close location and range, box width, 15-min trend,
   yesterday's range, earlier fake pokes, gap, realised volatility, VIX change, breadth, VIX regime, weekday). For
   each, the loser rate and average net by fifth (or category).
6. **Combined patterns:** a depth-2 decision tree (leaves >= 100 trades) per arm, losers vs winners, on the design
   features. Its leaves' loser rates are then shown in H1, H2 and the final.

## When a pattern "holds" (fixed now)

A slice is reported as a **steady losing pattern** only if, in **H1, H2 and the final separately**:
- its loser rate is above the arm's loser rate in that period;
- its average net is below 0;
- with >= 30 trades in each.
Everything else is reported as **not steady**. No p-values are claimed: this is a search, so a steady pattern is a
lead for a pre-registered test, not a result.

## Outputs

- `r141.py`, `test_r141.py`, `results/losers.json`.
- `research/HUNT_R141_LOSING_TRADES.md`, written in plain English for Boss: per arm, how it loses and the steady
  patterns.
- One descriptive entry per arm: `research/experiments/entries/r141-*.yaml`.
