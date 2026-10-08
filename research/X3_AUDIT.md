# X3 audit: is there a bug that hides a Rs 5,000/day strategy?

Written 8 Oct 2026 by X3, an independent red-team audit of the research (obuy engine, the h10/h14/h23/h24/h36 Liquidity
chain, H1-H45, the random-entry baseline) and of the app's paper account.
- Code: `research/hunt/x3/`.
- Logs and CSVs: `scratchpad/hunt/x3/`.
- Suggested fixes: `research/hunt/x3/fixes.patch`. It is not applied; no existing research code was changed.

## Verdict (read this first)

**No bug or bias in the research turns a losing strategy into a winner, or hides a Rs 5,000/day method.**

**Where Boss's +5k days come from.** The research reports Rs/day per strategy at 1 lot. Boss's paper account runs
about 8 strategies, about 20 round trips a day, sometimes at 2 lots.
- Boss's +5k days are ordinary noise at that size. His Oct paper days were +5,032, +4,955, -4,551, -3,746 and +3,800.
  That is about **+Rs 1,100/day across the whole book**.
- The app's paper fills are about **Rs 96 a round trip kinder than a real order book**. They charge no bid/ask spread
  and sometimes fill at stale prices. At 20 round trips a day that is about Rs 1,900/day.
- So the same five days would have been roughly **-Rs 800/day with real money**.

**The research matches the paper account when it runs the same rules.**
- I recomputed three weeks of the main plan's trades by hand from the raw Dhan file. Every trade matched to within
  Rs 1.35.
- h22 replayed 1 Oct with the rules the phone actually had and got +Rs 8,045 for Liquidity. That brackets Boss's
  +5,032 for the day.

**The research is a little too harsh on the one good plan.** Plan A is BANKNIFTY Liquidity 15+5 at 1 lot.
- Its cost model double-counts market impact for a single lot.
- Fixed, Plan A is about **Rs 70/day before the locked year and Rs 192/day in it**. The report says Rs 65 and Rs 167.
- **Before any costs at all it makes only Rs 152 and Rs 362 a day.** That is the hard ceiling for 1 lot, and no cost
  fix can lift it.
- Rs 5,000/day still needs about **26-71 BANKNIFTY lots** (the report said 30-77). **The answer stays NO.**

**Every other issue is worth Rs 0-20 a trade.**
- Each one hits a strategy and its random-entry baseline equally.
- The catalog was, if anything, too *generous*: it charged no bid/ask spread.

**No verdict changes.**

## Issues found, with their rupee effect

"Plan A" is h24/h36's BANKNIFTY Liquidity at 1 lot: about 1 trade a day, 750 trades before the holdout and 248 in it.
The baseline is the random-entry trade every study compares against (h25: BANKNIFTY -Rs 142 a trade, NIFTY -Rs 104).

| # | issue | where | effect in rupees | verdict change? |
|---|---|---|---|---|
| 1 | **Market impact charged on a 1-lot order, on top of the real half-spread.** κ√(q/v) is about 0.05-0.1% a side at 1 lot. The real snapshot shows 1-7 lots resting at the top of the book, so 1 lot pays the spread and no impact. | `hunt/h14/exe.py:124-125, 137-141, 182-186`; `hunt/h23/build.py:138-144` (κ .02 is the central case in h23/h24/h36) | Plan A **+Rs 24/trade in the holdout** (167 → ~191/day) and **+Rs 5/trade before it** (65 → ~69/day). The κ 0.04 "stress" doubles the error. | No. Still far from 5k/day |
| 2 | **The real half-spread is stacked on top of the app's own ±5 bps fill.** Most studies from h25 on charge open × 1.0005 × (1 + hs), so they pay 5 bps more than a fill at the mid. h23/h24 correctly charge max(hs, fill model). | `hunt/h25/evaluate.py:56`, `h25/filt.py:46`, `h26/post.py:37`, `h28/test.py:46`, `h29/rules.py:42`, `h30/sim.py:85,119`, `h31/test.py:70`, `h32/fwd.py:83`, `h33/test.py:53`, `h34/core.py:91-97`, `h37/evaluate.py:53`, `h40/test.py:44`, `h42/common.py:61-62`, `h43/partD.py:239` | 0.1% of the round-trip premium: **BANKNIFTY ~Rs 17, NIFTY ~Rs 9 a trade** at today's lots, less at historical lots. The baseline improves about the same: BANKNIFTY -142 → ~-128. | No. The best variants and the baseline lose Rs 100-170 a trade |
| 3 | **A stop exit pays 10 bps + 2 ticks, and then h24's spread top-up is computed as if it were a 5 bps / 1 tick exit.** | `hunt/h23/build.py:170-172` | About 5 bps + 1 tick on stop exits only (112 of 998 BANKNIFTY trades): **~Rs 1.5/trade** | No |
| 4 | **The app's charges at today's rates are used for every year.** STT is 0.15%, against 0.05-0.1% in force at the time. This is by design and right for a forward-looking estimate. | `obuy/costs.py:111-113` (mode 'app'); h10 `cap.EXE` | Rates in force at the time would have been **Rs 4-10/trade cheaper** (Plan A: -5.9 before the holdout, -5.4 in it) | No (and today's rates are the correct choice for the future) |
| 5 | **Duplicate-row bug.** When one strike appears at two offsets in the same minute, the loader keeps the first row. In 50-67% of these pairs that row is the stale carried quote (volume 0) and the live print is thrown away. This happens only in the MONTHLY series: BANKNIFTY 2021-24 (14-27k rows a year, ~1% of rows, about 2,200 a year within ATM±2) and NIFTY monthly. The weekly series and BANKNIFTY monthly 2025-26 are clean. | `obuy/data.py:101-106` (fix in `fixes.patch`) | **About Rs 0 for Plan A**: it used the weekly series until Nov 2024 and the clean monthly after. Studies on monthly contracts before 2025 (h11, h13, h29 term structure, h43 ATM-monthly) see about 1% of near-ATM minutes stale. The effect is noise-like and small. | No |
| 6 | **Stale carried quotes are treated as real bars in the unmasked studies.** Rows with volume 0 are always flat copies of the last price. As a share of the 1-ITM entry bars the engine fills at: BANKNIFTY ~0.1-0.8% (7.4% in 2021), NIFTY ~0.3%, **FINNIFTY 38% (monthly) and 12% (weekly); 100% in 2021, 45% in 2022, 33% in 2025 and 54% in 2026**. h23/h24/h36 mask them correctly ("prints only"). | `obuy/data.py:146-153` (Chain keeps volume-0 rows); masked only in `hunt/h23/build.py:59-75` | BANKNIFTY/NIFTY: negligible. **FINNIFTY results in the catalog, h25, h26, h30, h34 and h37 rest partly on prices that did not trade.** The bias has no consistent sign. | No. FINNIFTY was rejected anyway (0.42% real spread) |
| 7 | **The wrong BANKNIFTY lot (35) is used as "today's lot".** Today's lot is 30: from 31 Dec 2025 the data, the exchange and Boss's own fills all show 30. | `hunt/h42/common.py:19`, `hunt/h1/model.py:33` | h42 and h1 BANKNIFTY rupee figures are 1.167x too big, both wins and losses. Their BANKNIFTY losses are overstated by ~14%. | No (nothing positive was found) |
| 8 | **The lot fallback table misses BANKNIFTY 35 → 30 on 31 Dec 2025.** | `obuy/config.py:31` (fix in `fixes.patch`) | **Rs 0**: every study reads the lot from the data (OI moves), and no study uses `lot_mode="official"` | No |
| 9 | **h14's limit entry fills its resting part at the limit L = open × 1.005, even when the market traded lower.** | `hunt/h14/exe.py:236-241`; `hunt/h23/build.py:146-152` | Plan A: **Rs 0** (all 998 BANKNIFTY fills were immediate). MIDCPNIFTY: 53 of 618 trades (flat spread) or 267 (h23 Roll) filled this way, up to 0.5% too dear: **~Rs 5/day (flat) to ~Rs 25/day (Roll)** | No |

**Plan A with all fixes:** about **Rs 70/day before the holdout and Rs 192/day in it** at 1 lot.
- That is +Rs 30-31 a trade over the report, almost all from #1, plus #3.
- Gross before any cost is Rs 152 and Rs 362 a day.
- Rs 5,000/day would need about 26 lots on the holdout's pace and about 71 on the earlier years' pace.

## Checks that came out clean

**1. Charges (`obuy/costs.py`).** Every component matches the app's `SandboxCosts`:
- brokerage Rs 20 a leg;
- STT 0.15% on sells (dated: 0.05% / 0.0625% from Apr 2023 / 0.1% from Oct 2024 / 0.15% from Apr 2026);
- exchange 0.03503% + IPFT 0.0005% (BSE 0.0325%);
- SEBI Rs 10/crore; stamp 0.003% on buys; GST 18% on brokerage + exchange + SEBI.

I recomputed one week of Plan A trades from the raw parquet: 5-6 Jan 2026, 6-10 Jun 2022 and 3-7 Feb 2025.
- The steps were: open of the first print, 5 bps + tick fill, h14 limit logic, impact, real half-spread, charges.
- All 20 trades match `trades24.parquet` within Rs 0.36-1.35 (`x3/a2_handweek.py`).
- Per BANKNIFTY trade in the holdout: gross Rs 364, charges 100, spread 46, fill slippage + impact 50, net 168.

**2. Half-spread double count in the Liquidity chain (h23/h24/h36).** There is none with the fill model:
- h24 charges max(real half-spread, the 5 bps + tick model);
- the passive part of a limit fill pays no spread.

The real stacking is impact on top of the spread (#1). The h24 snapshot is real top of book: BANKNIFTY 1-ITM
bid/ask Rs 2-3.5 wide with 1-7 lots on each side. But it is one moment of one day, and that limit stays.

**3. Fills at the next minute's open.** On a random sample of 16,325 entry bars, the open was compared with the previous
print's close. The mean difference was -0.002% to -0.017%, with a median absolute gap of 0.12-0.24%.
- The open is a fresh, unbiased price.
- No strike switches at the minute boundary, because the series is re-keyed by strike.

**4. One contract all day.** Prints were checked within ATM±3, every 4th day from 2021 to 2026. Option jumps over 20%
in a minute while the index moved under 0.15% occurred in only 0.02-0.03% of minute pairs on non-expiry days. They are
gamma moves on cheap options, not contract switches.
- The "near" series is one expiry per day.
- The expiry flags are correct: 48-53 a year on Thursdays, Tuesdays from Sep 2025, monthly only for BANKNIFTY and
  FINNIFTY after Nov 2024.

**5. Lot sizes by date.** The lot read from OI matches the exchange history:
- NIFTY 75 / 50 / 25 / 75 / 65;
- BANKNIFTY 25 / 15 / 30 / 35 / 30;
- FINNIFTY 40 / 25 / 65 / 60.

Contracts listed before the Nov 2024 revision correctly kept their old lot until they expired.

**6. Missing days, duplicated minutes, out-of-session rows.**
- Trading-day counts are complete (245-249 a year).
- There are no duplicate (minute, offset) rows.
- Out-of-session rows (15:30 prints, a few evening rows in 2021-24) are filtered out. Each day keeps its full 375
  minutes.
- Short index days: 25 for BANKNIFTY in 2021, and 43 + 21 for FINNIFTY in 2021-22. Signals on those days use Dhan's
  spot print.
- Only 2-3 index bars per index have a high below the open or close.

**7. Ties and intrabar exits.** The engine assumes the stop fills first when the stop and the target trade in the same
minute.
- The studies' own counts agree with each other: 0.19% of trades (h25), 0.49% (h26), 0.09-0.95% (h30), and 2% for
  h45's tight ±5% bracket.
- Crediting every tie as a win adds Rs 1-10 a trade.
- Liquidity has no premium target, so this costs Plan A Rs 0.
- There is no "target needs high > limit + half-spread" rule in the code. Targets fill on a touch, and h25 onwards
  then charge the half-spread on that exit, which is roughly fair.

**8. Look-ahead.**
- Perturbation audits (`x3/l1_perturb.py`) scramble everything after 11:45 on a test day and on all later days. All
  1,170 h25 signal sets (61 candles, 9 chart patterns, MAs, 25 indicators, 6 timeframes) and all 206 h37 signal sets
  (Fibonacci, Gann, harmonics, Elliott, Renko, P&F, Market Profile, Wyckoff) stayed identical before the cut. The test
  ran on 3 days each for NIFTY and BANKNIFTY.
- Code review found the daily and bhavcopy features correctly lagged one session: h26/h38 `_lag` and `shift(1)`, h40
  `feat.py:82-85/210/216`, h43 VIX, h44 `daily_tables`.
- h30's fractal pivots wait for the 2 confirming candles.
- h32's `crng[s-4]` is safe because s ≥ 5.
- h42's VIX bug was already fixed.
- Any look-ahead would have made strategies look better, not worse, so it could not hide a winner.

## The app's paper account vs the research model, 8 Oct (`x3/p1_paper8oct.py`)

For each of Boss's 20 fills I took the Dhan minute bar that contains the fill time.
- The research's price is that bar's open with its fill model: 5 bps + 1 tick, or the real half-spread if larger.
- Charges are identical in both, because the app's SandboxCosts is the research's cost model.

| | Rs |
|---|---|
| app paper net (20 round trips) | **+3,773** |
| research model, same contracts, same minutes | **+1,199** |
| difference | +2,573 (+Rs 129 a trade) |
| of which: spread and slippage the paper does not charge | **+1,916 (Rs 96 a trade: BANKNIFTY ~70, NIFTY ~60, MIDCP ~110, FINNIFTY ~175)** |
| of which: exits inside the minute (resting targets / locks filled intrabar) vs the bar open | +1,659 |
| of which: entries later in the minute than the open (the app paid more) | -997 |

- **6 of 40 paper fills lie outside the range Dhan actually traded in that minute.**
  - Exits: FINNIFTY #6 (336.95), #17 (332.40) and #20 (357.25, the "lucky price").
  - Entries: BANKNIFTY #1 at 694.20, 2.95 above the minute's high; #15 just above it; FINNIFTY #20 at the previous
    minute's price.
- These are the stale and LTP fills `Paper.kt:134` produces when the stream drops (lock8 bug #4).
- **So the paper account is too generous, by about Rs 70 (BANKNIFTY) to Rs 175 (FINNIFTY) a round trip.** The research
  is not too harsh, except for #1 above.

**Is the paper Liquidity record consistent with the research? Yes.**
- The paper Liquidity record is 20 trades, -Rs 6,186, 30% won.
- Under the research's Plan A trade distribution (mean +86 / +168 a trade, sd about Rs 1,900-2,400, 34-36% won),
  a 20-trade run this bad or worse happens about 18% of the time.

## What this means for the main verdicts

| verdict | before | after the audit |
|---|---|---|
| Liquidity BN 1 lot (h24/h36) | +Rs 65 / +167 a day (pre / holdout) | **about +Rs 70 / +192 a day.** Gross ceiling 152 / 362 |
| Lots needed for Rs 5,000/day | 30-77 | **26-71** |
| Random-entry baseline per trade (h25) | BANKNIFTY -142, NIFTY -104 | about -128 / -96 (the 5 bps overlap removed). Still a large loss; every comparison against it is unchanged, because strategies get the same fix |
| Catalog (OBUY_FINAL, SCALP17) | no method beats costs | unchanged. It used the app's fills **without** the real spread, so it was already about Rs 50-70 a trade too *kind* |
| h22-h45 "no" verdicts | no | unchanged. The largest correction (#2) is under Rs 20 a trade against losses of Rs 100-260 a trade |
| "Rs 5,000/day with Rs 1 lakh" | NO | **NO** |

## Suggested fixes (not applied)

`research/hunt/x3/fixes.patch` holds four small diffs:
1. `obuy/data.py`: keep the traded row, not the stale one, when a strike appears at two offsets.
2. `obuy/config.py`: add BANKNIFTY 30 from 31 Dec 2025.
3. `hunt/h42/common.py` and `hunt/h1/model.py`: BANKNIFTY lot 30.

Not in the patch, but worth adopting:
- **Impact.** In the h10/h14 impact model (`exe._imp`), charge impact only on the lots beyond the top-of-book size
  (about 1-2 lots for BANKNIFTY), not from the first lot.
- **Spread on top of app fills.** In the studies that stack the half-spread on the app's fills, use max(hs, 5 bps), as
  h23/h24 and h29 `liqfilter.py:35` already do.
- **App paper (android/, untouched).** Add the measured half-spread to paper fills. Refuse a paper fill when the last
  price is older than the current minute. Then the paper account will stop overstating live results by about
  Rs 100 a round trip.

## Files

- `research/hunt/x3/a1_trades24.py`: per-trade cost breakdown of Plan A; impact = net(κ .02) - net(κ .04).
- `research/hunt/x3/a2_handweek.py`: one week of trades recomputed by hand from the raw parquet.
- `research/hunt/x3/d1_session.py`, `d2_examples.py`: out-of-session rows, duplicate rows, volume-0 rows.
- `research/hunt/x3/d3_lots.py`: lots by date against the schedule, day counts, expiry flags.
- `research/hunt/x3/d4_jumps.py`: one-contract continuity.
- `research/hunt/x3/d5_dupkeep.py`: which duplicate row the loader keeps.
- `research/hunt/x3/f1_entrybar.py`: stale entry bars and freshness of the open.
- `research/hunt/x3/l1_perturb.py`: look-ahead perturbation audit of h25/h37 signals.
- `research/hunt/x3/p1_paper8oct.py`: Boss's 8 Oct fills against the research model.
- `research/hunt/x3/fixes.patch`.
- Logs: `scratchpad/hunt/x3/` (`d1.log`, `l1_h37.log`, `l1_h37_nifty.log`, `f1.parquet`, `paper8oct.csv`).
  - The first h37 run showed 5 NIFTY Market Profile differences. They came from my own test harness, which clamped
    the high/low on unperturbed bars. The rerun with that fixed (`l1_h37_nifty.log`) is clean.
