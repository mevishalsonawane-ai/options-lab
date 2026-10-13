# OF1 pre-registration: Indian order flow forensics (who, why, who profited)

*Written 13 Oct 2026, before any analysis was run. At this point the data was still downloading. The only data looked at
were single files opened to learn their layout: the participant OI and volume files for 5 and 8 Jul 2024, the bhavcopy
rows for 4–9 Jul 2024, the index-close file for 9 Oct 2026, and NSDL's FPI page for Jan and Mar 2012. No series, return or
correlation has been computed. Nothing below may be changed after the results are seen. Any change gets a dated
"Amendment" section at the end, with its reason, and the BH count then includes it.*

## 0. What can and cannot be known (said up front)

NSE's data is **anonymous**. It reports positions and volumes by participant **type** only:

- **Client**: everyone who is not one of the other three. That is mostly retail, but it also includes HNIs, corporates
  and many non-institutional funds.
- **DII**: domestic institutions (mutual funds, insurers, banks).
- **FII**: foreign portfolio investors.
- **Pro**: brokers trading their own money (proprietary desks, including the large HFT/market-making desks).

No person, firm, order or reason can be identified. "Why" is only ever an **inference from behaviour**, for example
"positions that grow with the index and shrink after falls look like hedging". It is never a known intent.

## 1. Data (public, fetched by `fetch.py` / `fetch_fpi.py`)

- NSE participant-wise OI and volume (contracts), daily, from 2 Jan 2012 (the first file in NSE's archive) to 13 Oct
  2026.
- The NSE F&O bhavcopy (old layout up to 5 Jul 2024, UDiFF after), cut to index futures and options: settle and close
  prices, OI in units and volume in contracts.
- NSE's all-index closing file: NIFTY 50, NIFTY BANK and India VIX OHLC.
- NSDL's FPI daily cash-market flows (equity, stock-exchange route).

DII cash flows are not reachable (NSE's www API refuses this machine), so they are reported as missing.

**Splits:**

- Design: 2 Jan 2012 – 12 Oct 2025.
- Locked holdout: 13 Oct 2025 – 13 Oct 2026, the last 12 months, as in ARCHITECTURE.md L6. It is read once, only for what
  survives the design gates, in one run (`analyze.py holdout`). The run refuses a second read: it writes
  `results/HOLDOUT_READ.json`.

## 2. (A) WHO: positioning by type

For each group *g* and day *t* (all in contracts, from the participant OI file):

- index futures net = long − short;
- long share = long / (long + short);
- index calls net = long − short, and index puts net = long − short;
- options directional stance = calls net − puts net (a crude "net long delta" count that ignores strike and delta);
- put writing = put short − put long;
- the same for stock futures and options, for description only.

These are described against NIFTY and BANKNIFTY over the next 1, 5 and 20 trading days, close to close, with
Newey–West (HAC) standard errors, lag = horizon. All of this is descriptive.

## 3. (C) WHO PROFITED: the mark-to-market method (fixed now)

**Index futures.**

- Pool MTM per contract for day *t+1*:
  m(t+1) = Σ_c OIqty_c(t) · (settle_c(t+1) − settle_c(t)) / TotalIndexFutContracts(t).
  - The sum runs over every index futures contract *c* in the bhavcopy on day *t*.
  - TotalIndexFutContracts(t) is the participant file's TOTAL long.
  - A contract that expires on *t+1* uses its final settlement price, which is its bhavcopy settle that day.
- Group P&L(t+1) = net_g(t) · m(t+1). Across the four groups this sums to zero (a check).
- **Second estimate**, adding the day's position change: ΔN_g(t+1) · (settle(t+1) − typical price(t+1)) per contract.
  The typical price is the pool's OI-weighted (H+L+C)/3.

**Index options.** The same, separately for calls and puts:

- Pool MTM per contract = Σ OIqty(t) · Δsettle / Total index call (put) contracts(t).
- Group options P&L = calls net · call pool MTM + puts net · put pool MTM.
- Three strike mixes, so we can see whether the conclusion depends on the unknown strike mix:
  - (a) every strike, weighted by OI (primary);
  - (b) near the money only: |K/S − 1| ≤ 2%, where S is the underlying close at *t*;
  - (c) far from the money: > 2%.

  For (b) and (c), the per-contract MTM is the OI-weighted average change of the strikes in that bucket. It assumes the
  group's book is entirely in that bucket.

**Limits, stated in every table:**

- Each group is assumed to hold the market's mix of underlyings, expiries and (options) strikes. The data cannot say
  otherwise.
- P&L inside the day (trades opened and closed the same day) is invisible.
- Gross of costs: groups' charges are not known.
- Options P&L is the least reliable estimate. A conclusion is called robust only if its sign is the same under mixes (a),
  (b) and (c).

**Regimes**, all point-in-time (only data up to *t*):

- **Trend or range.** Trend when |NIFTY 20-day return| > the 20-day realised daily volatility · √20 (computed on days up
  to *t*); otherwise range.
- **High or low VIX.** India VIX close at *t* above or below its trailing 250-day median.
- **Expiry week.** The calendar week that contains NIFTY's monthly expiry, the last monthly-expiry contract in the
  bhavcopy.
- **Year.** The calendar year.

## 4. (B) WHY: behaviour, inferred

These are descriptive patterns, each labelled as an inference:

- Does a group's net index futures position move **with** the index (trend-following) or **against** it (contrarian or
  hedging)? Measured as the correlation of ΔN_g(t) with the index return on the same day *t* and on the previous day,
  t−1.
- Option behaviour: is the group a net option buyer or writer, and in calls or puts?
- Does its put long rise when stock futures long rises (hedging)?

## 5. Predictive tests (family `flow-oi`, BH q = 0.10)

**Signals, known after the close of day *t*.** The participant files are published in the evening, so a trade can only
happen on *t+1*.

For each of FII, DII, Pro and Client:

- S1: z of Δ(index futures net), where z = (x − mean) / std over the previous 60 trading days, ending at *t*.
- S2: z of Δ(long share).
- S3: z of Δ(options directional stance).
- S4: z of Δ(put writing).

Plus:

- S5: z of the FPI cash net (stock-exchange route), using rows whose **reporting date** ≤ *t*;
- S6: z of FII Δ(index futures net) + FPI cash net z (combined "foreign money").

That makes 16 + 2 = 18 signals. Targets:

- primary, tradable: NIFTY and BANKNIFTY open(t+1) → close(t+1), in %;
- secondary: close(t) → close(t+1), descriptive only (it includes the overnight gap, which cannot be traded on this
  information).

**Statistic.** The slope of the target on the signal (OLS) with HAC standard errors (lag 1), a two-sided p-value, and
also the Spearman correlation. **Primary family: 18 signals × 2 indices = 36 tests, BH at q = 0.10 on the design
period.**

**Walk-forward.**

- The sign used for trading is the sign of the design slope estimated on an expanding window up to the end of year Y−1,
  applied to year Y, for Y = 2014..2025.
- Reported: the walk-forward hit rate (share of days where sign · signal agrees with the return's sign, |z| ≥ 1 only) and
  the mean return per signal day.

**Holdout pass.** A test passes the holdout if, on the holdout:

- its slope has the design sign; and
- its one-sided p < 0.05.

Only design BH survivors are read.

The 5- and 20-day horizons are reported with HAC (lag = horizon), BH-adjusted as a separate descriptive family. They make
no trading claim.

## 6. Option-chain build-up and walls/magnets (family `flow-oi`)

**Build-up**, per contract per day, from the bhavcopy (Δ settle = price change, Δ OI from the OI series):

| Δ price | Δ OI | label |
|---|---|---|
| > 0 | > 0 | long build-up |
| < 0 | > 0 | short build-up |
| > 0 | < 0 | short covering |
| < 0 | < 0 | long unwinding |
| any | = 0, or Δprice = 0 | neutral |

**Who made money at expiry.**

- For every OI **addition** (ΔOI > 0) on day *t*, the new long's expiry P&L per unit is final settlement − settle(t). The
  writer's is the negative of that.
- Weighted by the ΔOI units, then summed by label (long build-up vs short build-up). This shows which side, buyers or
  writers, the added positions paid.
- Split by index (NIFTY and BANKNIFTY, nearest expiry only), year, moneyness bucket (ITM, ATM ±1%, OTM 1–3%, OTM > 3%),
  and days to expiry.
- This is a held-to-expiry approximation, not the traders' actual exits.

**Walls and magnets** (nearest expiry, NIFTY and BANKNIFTY). At the close of the day *k* trading days before expiry,
for k = 1 and 3:

- **Call wall**: the call strike with the most OI at or above spot. **Put wall**: the put strike with the most OI at or
  below spot.
- **Wall test.** Breach = the final settlement goes beyond the wall.
  - Twin baseline: for each expiry *i*, p_i = the share of OTHER expiries in the same period whose k-day move was larger
    than wall *i*'s distance (in %).
  - Observed breaches are compared with Σ p_i. The p-value is from 10,000 Poisson-binomial simulations, one-sided
    ("fewer breaches than distance alone predicts" = a wall).
- **Magnet test.** The max total-OI strike (CE + PE) at k days, K*.
  - Statistic: |final − K*| − |final − K_mirror|, where K_mirror = 2·spot(k) − K*, rounded to the strike step: a twin at
    the same distance on the other side.
  - Also compared with 1,000 random strikes per expiry drawn uniformly at the same |distance| ± 1 step, either side.
  - The p-value is a one-sided sign-flip (Wilcoxon) test that the max-OI strike is closer.
  - If spot is exactly at K* (distance 0), the expiry is dropped from the magnet test and counted.
- **Family:** {call wall, put wall, magnet} × {NIFTY, BANKNIFTY} × {k = 1, 3} = 12 tests, BH q = 0.10 on the design
  period. Survivors are read once on the holdout, with the same pass rule as §5.

## 7. Footprints in the app's recordings (descriptive only)

**Detector** (`footprints.py`), on the tick zone's per-second rows (`ticks.jsonl.gz`):

- **Large aggressive print**: a second whose volume rise is ≥ the day's earlier-seconds 99th percentile (needs ≥ 200
  earlier seconds), signed by the tick rule.
- **Absorption**: TickZone.absorbed's rule, on minutes.
- **Sweep**: in one second, the price moves through ≥ 3 of the previous second's 5 book levels on one side, with volume.
- **Iceberg-like refill**: the best level's price is unchanged while traded volume at that price exceeds the displayed
  quantity at least 2×, and the displayed quantity is restored at least 3 times in 60 seconds.

**Outcome.** The futures' move 1, 5 and 15 minutes later, in points, signed by the footprint's side.

With only a few days this is description, not a claim. If no recording is readable, the detector ships with synthetic
tests only.

## 8. Trading rules (at most 4)

**R0, always run** (it answers "does following FII help?"):

- Signal: FII S1 (z of Δ index futures net) at the close of *t*. Trade on t+1 only if |z| ≥ 1.
- Side: the walk-forward sign from §5, re-estimated each year on data before that year.
- Instrument: the app's option-buying arm. Buy the NIFTY ATM call (sign · z > 0) or put, nearest weekly expiry, skipping
  expiry day.
- Entry: the option's day-*t+1* OPEN, plus slippage of 5 bps + 2 ticks.
- Stop: −30% of the premium.
- Lock: once the premium reaches +40%, lock +15% (a step lock).
- Exit: otherwise the day's close.
- **Daily-bar approximation**, pessimistic: if the day's low reaches the stop, the stop fills, even if the high came
  first. Only if the stop is never touched and the high reaches the trigger is the exit max(lock, close). Gaps through the
  stop fill at the open.
- Charges: the app's dated model (research/obuy/costs.py Costs('dated')), one lot, the lot size in force on the date.
- Twin: the same days with a random side, 1,000 draws.

**R1–R3, only for design BH survivors** of §5/§6 (strongest q first, at most 3):

- The same arm on the survivor's index (NIFTY or BANKNIFTY), side from the survivor's sign.
- R1–R3 use the same stop/lock as R0. No variants are tuned.

**Gates (all must pass):**

- design walk-forward net P&L > 0 after costs;
- twin p < 0.05;
- BH q < 0.10 across R0–R3;
- the holdout, read once, net P&L > 0 with the design sign.

If nothing survives §5/§6, only R0 is run, and the write-up says plainly that nothing passed.

## 9. Look-ahead guard

The tests check two things:

- Every signal at *t* is unchanged when every row after *t* is deleted or perturbed.
- Every target starts strictly after the signal's information time:
  - participant data: the evening of *t*;
  - FPI: the end of its reporting date.

## 10. What gets logged

One experiment entry per test family:

- `of1-participants-pnl`: not-a-test;
- `of1-signals`: 36 tests;
- `of1-buildup`: not-a-test;
- `of1-walls`: 12 tests;
- `of1-footprints`: not-a-test;
- `of1-r0`, and one per R1–R3 run.

Each entry is filed whatever its result.
