# R195 pre-registration: defined-risk option SELLING on the five index option books (side study, 18 Nov 2026)

Written 18 Nov 2026, **before any R195 profit or loss was computed**. Committed and pushed on
`claude/r195-option-selling` before the study code first runs on option prices. Owner: Boss (they/them).

Boss chose "no option selling" on 21 Oct 2026. On 18 Nov 2026 Boss asked: "Also start a side research of option
selling", then "Start in new session". **This is research only. Nothing in `android/**` or `.github/**` changes, the app
stays buy-only, and nothing goes to paper or live.** A pass would be a note for Boss to read, nothing more. Boss also
asked (18 Nov, during this session): "You could create a neuronetwork with 5-10 layers for more insight". One neural
candidate (N1, section 4) is in the list, pre-registered here, and judged by the same gates.

R195 was free when this was written: R194 (support / resistance) runs on `claude/r194-sr-study`; no `r195` branch,
folder or log entry existed. Nothing under `research/hunt/r194*` is touched.

## 1. What is already known, and what is new here

* **R103** (22 Oct 2026) tested 24 hedged 09:21 structures (iron fly, wide iron fly, iron condor x stop / stop+lock+target,
  NIFTY and BANKNIFTY, expiry and other days). 0 of 24 passed. Its six design passers were an artefact: far wings that
  left the lake's ATM +-10 window stopped printing and were sold at stale prices. Repriced, all lost. R103 also found
  that a "50% of credit" stop on an iron fly was beyond the fly's own maximum loss, so it almost never fired; the naked
  09:20 short straddle held to 15:10 made money on both indices but with worst days near -Rs 29k on one lot.
* **New in R195:** (a) the stale-leg fix is part of the rule from the start (section 3), not an amendment; (b) per-leg
  premium stops on the straddle (the common Indian "09:20 straddle, 25% SL per leg"), never tested here; (c) stops set
  INSIDE each hedged structure's maximum loss; (d) trend credit spreads with an index signal and an index stop; (e) calm
  day / implied-vs-realised filters; (f) one overnight structure, to measure gap risk directly; (g) all five indices
  (FINNIFTY, MIDCPNIFTY, SENSEX for the first time in a selling test); (h) a margin model with the hedge benefit and an
  explicit "possible at Rs 56k?" answer; (i) the post-Nov-2024 regime weighted in the verdict.

## 2. Data, periods, holdout

* Drive lake `gdrive:IraAlgo-data-lake`, copied with rclone on 18 Nov 2026 to `~/.cache/iraalgo/r195/raw` (never in
  git): `index_1m` for the five indices and INDIAVIX (2017/2021 - 19 Oct 2026, plus the `_fill1020-1113` and `_gap1113`
  parts to 13 Nov 2026) and `options_rolling_1m` WEEK files (nearest expiry, ATM +-10 strikes, 1-minute OHLC, volume,
  OI, IV) for the five indices, Aug 2021 (MIDCPNIFTY Jan 2022, SENSEX May 2023) - 19 Oct 2026, plus the `_gap1113`
  parts to 13 Nov 2026. For BANKNIFTY, FINNIFTY and MIDCPNIFTY the WEEK file is the monthly contract after Nov 2024
  (R51's note). Read with pandas under `python3 -I`, never from inside the data folder.
* Held contracts are rebuilt per (side, strike) with **R51's store builder, Amendment A cleaning** (`r195data.py`), so
  every leg is priced on the strike traded. Expiry day = R51's flag (the nearest expiry settles that day). The expiry
  DATE used for Black-Scholes (section 3) is the next flagged day on or after the trade day.
* **Walk-forward (design) period: 2 Aug 2021 - 31 Dec 2025.** No rule except N1 has a fitted parameter; every filter
  reads only trailing data (the previous 20 or 250 sessions, causal), so every design trade is out-of-sample for its
  own filter. Results are reported per calendar year (2021 part, 2022, 2023, 2024, 2025) and for the **post-regime part
  20 Nov 2024 - 31 Dec 2025** separately. N1 is retrained each year (section 4).
* **Holdout: 1 Jan 2026 - 13 Nov 2026, read ONCE** by `r195study.py holdout`, which writes `results/HOLDOUT_READ` and
  refuses a second read. Honest disclosure: R103 read 15 Jul - 19 Oct 2026 for six BANKNIFTY non-expiry 09:21 hedged
  structures (all lost when repriced). That is the only selling result read inside this holdout; no R195 rule is one of
  those six. Many buying studies (R151-R193) read 2026 for the arms; none priced a sale.
* The Nov 2024 SEBI changes (one weekly expiry per exchange, bigger lots, +2% expiry-day ELM) split the data into two
  regimes. The holdout is wholly in the new regime; the gate (section 6) also needs the design's post-regime part > 0.

## 3. Common mechanics (all candidates)

* **Minute grid** 09:15 - 15:29. A decision reads minute closes up to and including the decision minute; orders fill at
  the NEXT minute's open (R103 `fill_px`: that minute's open, else the next open within 5 minutes). Square-off 15:10,
  and **15:05 on the leg's own expiry day** (the app's expiry square-off). No rule holds to settlement, so **no exercise
  and no STT on exercise** arise; every leg is closed by an order.
* **Entry needs real prints.** Every leg must have a close in the 5 minutes up to the decision minute and a real open
  within 5 minutes of the fill minute; otherwise the day is skipped (counted). Strikes are at most 8 steps from the ATM
  at entry. Steps: NIFTY 50, BANKNIFTY 100, FINNIFTY 50, MIDCPNIFTY 25, SENSEX 100.
* **Marks and the stale-leg fix (R103's lesson, part of the rule).** A leg's mark at minute t is its close at t; if it did
  not trade at t, its last close if that is at most 5 minutes old; otherwise **Black-Scholes** at the implied volatility
  of its last print (inverted from that close, the index at that minute and the time to 15:30 on the expiry day; rate
  6.5%), with the index at t and the time left, floored at intrinsic value and one tick. A leg with no IV (last print at
  or below intrinsic + 0.05) is marked at max(intrinsic, last close). An exit fill with no real open within 5 minutes is
  filled at that model mark with thin-contract slippage (4 ticks + 5 bps). This is R103 `r103stale.reprice`, applied
  to every mark and fill, not only to exits.
* **Fills and costs.** Slippage per order: R103's 'liq' model (5 bps plus 1 / 2 / 4 ticks by the contract's traded
  volume in the 5 minutes before, in lots), doubled ticks on stop exits. Charges per order: `obuy.costs.Costs('dated')`
  (Rs 20 brokerage, STT on the SELL side at the dated rate: 0.05% to Mar 2023, 0.0625% to Sep 2024, 0.1% to Mar 2026,
  **0.15% from 1 Apr 2026**; exchange, SEBI, stamp on the buy, GST), SENSEX with BSE's exchange rate (`bse=True`).
* **Size: one lot at TODAY's lot for every year** (NIFTY 65, BANKNIFTY 30, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20),
  so every rupee, drawdown and margin is what one lot would mean for Boss's account now. A stated choice: older years
  traded smaller lots; this makes their rupees comparable, not historical.
* **Margin model (stated, not a broker quote).** Margin = exposure + SPAN part:
  - exposure: 2% of each short leg's notional (strike x lot), plus **2% more on a short leg on its own expiry day from
    20 Nov 2024** (SEBI 1 Oct 2024);
  - SPAN part of a hedged structure: the widest wing in points x lot (a vertical spread cannot lose more than its width;
    an iron condor / fly only on one side). Calibration: Zerodha Varsity's NIFTY condor (9,973, lot 75, 200-point wings,
    two short legs of Rs 7.48 lakh): model 2% x 14.96 lakh + 200 x 75 = Rs 44,920 vs Varsity's Rs 44,303 (+1.4%);
  - naked shorts (S1-S3, the baselines): SPAN 8% of one leg's notional (charged once for a straddle pair) plus the
    exposure on each leg. For a NIFTY straddle at 23,000 x 65 that is about Rs 1.8 lakh (DEEP_OPTIONS_SELLING section
    4: Rs 1.5-1.7 lakh for the pair, Rs 1.64 lakh for one naked leg). Any naked short is far above Rs 56k either way.
  - **"Possible at Rs 56k"** = the model margin is at most Rs 56,000 on at least 95% of the candidate's holdout trades.
    Reported, not a gate of the edge itself; the final answer needs both.
  - **Return on margin** = net / mean margin per trade, per year of trading days in the period.

## 4. The candidates (18, fixed now) and the baselines (5)

Common names: K0 = the ATM strike at the decision minute (offset 0 of the rolling series; the index rounded to the step
if missing); S = C(K0) + P(K0) closes at the decision minute (points). "Sell straddle" = sell C(K0) and P(K0).
"Max loss" of a hedged structure = widest wing x lot - credit (rupees, before costs).

**Every candidate has a defined maximum loss: a bought hedge, or a stop on every short leg (intraday only, so there is
no overnight gap; a stop can still fill beyond its level in a fast minute, which the fills price).**

| id | index | signal source | rule |
|---|---|---|---|
| **S1-NIFTY** | NIFTY | none directional; K0 from the index at 09:20 | 09:20 decision, sell straddle at 09:21. Each short leg has its own stop: buy it back when its mark >= 1.25 x its entry fill (25% per-leg stop, on the premium). The other leg runs on with its own stop. Square-off. Every trading day. |
| **S1-BANKNIFTY / S1-FINNIFTY / S1-MIDCPNIFTY / S1-SENSEX** | each | same | S1 on each index (4 tests). |
| **S2-NIFTY** | NIFTY | as S1; filter India VIX | S1-NIFTY only when India VIX's 09:20 close >= the median of its 09:20 closes over the previous 250 sessions (at least 120 known). |
| **S3-NIFTY** | NIFTY | as S1; filter implied vs realised vol | S1-NIFTY only when IV >= 1.2 x RV20. IV = S / (0.7979 x index x sqrt(T)), T = calendar years from 09:20 to 15:30 on the expiry day; RV20 = the standard deviation of the index's daily close-to-close log returns over the previous 20 sessions x sqrt(252). |
| **S4-NIFTY** | NIFTY | none directional | Hedged straddle: S1-NIFTY plus bought wings C(K0 + 6 steps), P(K0 - 6 steps). Each SIDE (short leg + its wing) has a 30% stop on the short leg's premium: when the short call's mark >= 1.30 x its fill, close the short call and its wing; same for puts. Square-off. |
| **E1-NIFTY** | NIFTY | none directional | NIFTY expiry days only. 09:20 iron fly: sell straddle, buy C(K0 + 4 steps), P(K0 - 4 steps). Premium-multiple stop: close all four legs when the two shorts' marks summed >= 1.5 x their summed entry fills. Square-off 15:05. |
| **E2-NIFTY** | NIFTY | none directional | E1 entered on the 13:30 close (fill 13:31). |
| **E3-SENSEX** | SENSEX | none directional | E1 on SENSEX expiry days (4 steps = 400 points). |
| **T1-NIFTY** | NIFTY | the index: first-hour return | Decision on the 10:15 close; r = index(10:15) / index(09:15 open) - 1. r >= +0.25%: bull put spread, sell P(K0 - 1 step), buy P(K0 - 5 steps). r <= -0.25%: bear call spread, sell C(K0 + 1), buy C(K0 + 5). Else no trade. Exits: the index closes back through its 09:15 open (index stop); or the spread's loss >= 50% of its max loss; else square-off. |
| **T1-BANKNIFTY** | BANKNIFTY | same | T1 on BANKNIFTY. |
| **T2-NIFTY** | NIFTY | the index: daily trend | 09:20 decision. Yesterday's close above the 20-session average of closes (sessions up to yesterday): bull put spread as T1; below: bear call spread. Exit: loss >= 50% of max loss, else square-off. |
| **V1-NIFTY** | NIFTY | the index: calm-day filter | 09:20 iron condor on calm days: yesterday's range (high - low) <= 0.8 x the average range of the 20 sessions before it, AND the 09:15 open within 0.3% of yesterday's close. Shorts at K0 +- a steps, a = round(0.5 x S / step) clipped to 1..4; wings 3 steps beyond each short. Exit: loss >= 50% of max loss, else square-off. |
| **V2-NIFTY** | NIFTY | IV vs RV filter (S3's) | The V1 condor (same strikes and exits) on days with IV >= 1.2 x RV20 instead of the calm filter. |
| **P1-NIFTY** | NIFTY | none directional | Overnight hedged fly: on a NIFTY day that is not an expiry day and whose next session is not an expiry day, decision on the 15:15 close: sell straddle, buy C(K0 + 4), P(K0 - 4); exit at the next session's 09:21 open (decision 09:20). No intraday stop (the wings define the loss). It carries the overnight gap: the direct gap test. |
| **N1-NIFTY** | NIFTY | index, VIX and implied-vol features at 09:20 | S4-NIFTY taken only on days a neural network predicts a positive net. Network: sklearn `MLPRegressor`, **6 hidden layers of 32 ReLU units**, adam, L2 alpha 1e-3, early stopping (10% validation, 20 rounds), max 500 epochs, seed 195, standardised inputs. Target: S4-NIFTY's net per trade (rupees / 1,000). Features (all known at 09:20): India VIX at 09:20, its change over 5 sessions, IV / RV20, RV5 / RV20, the 09:15 gap vs yesterday's close, yesterday's range / its 20-session average, index return 09:15-09:20, yesterday's close / 20-session average - 1, sessions to expiry, weekday, S / index. Walk-forward: for test year Y in 2023, 2024, 2025 the net is fitted on every S4 day before 1 Jan Y (2021-22 are training only, so N1's design period is 2023-2025); for the holdout it is refitted once on all design days and frozen. |

**Baselines (never candidates; logged, counted in BH's m):** **B0-<index>** for each of the five indices: the naked
09:20 short straddle (sell at 09:21) held to the square-off with no stop and no hedge. It is the "naked, no stop"
benchmark the brief asks every candidate to beat.

## 5. What is reported (per candidate, walk-forward and holdout, and post-regime)

Trades, win %, net, average win / loss, max drawdown (closed-trade equity), worst calendar month, worst single day, net
without its 5 best trades, the mean and 95th-percentile margin at 1 lot, return on margin (a year), months <= -Rs 13,000
and whether the running drawdown ever passed Rs 40,000 (with dates), net at 2x slippage, the stop / exit reasons, the
skipped days and why, the legs marked by Black-Scholes (stale) and their share. Per year inside the design.

**Tail days:** each candidate's net (or "no trade") on the Union Budget days (1 Feb 2022, 1 Feb 2023, 1 Feb 2024,
23 Jul 2024, 1 Feb 2025, 1 Feb 2026 when the market traded), the election result day 4 Jun 2024 and 5 Jun 2024, and
the five largest 09:15 opening gaps (|open / previous close - 1|) of its index in the data.

## 6. The gate (fixed now; never loosened after seeing a number)

A candidate PASSES only if ALL hold:

1. **Walk-forward:** net > 0 over the design period AND net > 0 over its post-regime part (20 Nov 2024 - 31 Dec 2025).
2. **Holdout:** net > 0 (read once).
3. **Trades:** at least 40 in the design period and at least 40 in the holdout.
4. **Not a few days:** net without its 5 best trades > 0, in the design period and in the holdout separately.
5. **Twin p < 0.05** on the design period (one-sided, empirical, 1,000 twins, seed 195):
   - all-days rules (S1, S4, E1, E2, E3, P1, B0): **timing twin**, the same structure and exits entered on the same days
     at a random decision minute on a 5-minute grid (S1, S4, E1, B0: 09:20 - 13:00; E2: 09:20 - 14:30; P1: 13:00 -
     15:15 the same day, the same next-morning exit);
   - filters (S2, S3 on S1-NIFTY's days; V1, V2 on the unfiltered 09:20 condor's days; N1 on S4-NIFTY's days):
     **random-day twin**, the same number of trades drawn at random from the unfiltered rule's design trades;
   - directional (T1, T2): **random-side twin**, the same days and times with the side (put spread / call spread) by a
     coin flip.
6. **BH q <= 0.10** with m = 4,784 (the entry files at this branch's base, origin/main 4398bd6c) + 23 = **4,807**;
   earlier entries count as p = 1. Each R195 test's p = the larger of (a) the day-bootstrap p that the design mean
   net is <= 0 (10,000 resamples, seed 195) and (b) its twin's normal-tail p (z = (net - twin mean) / twin sd), because
   1,000 twins cannot give an empirical p below 1/1,001. Baselines enter with their bootstrap p.
7. **Account stop at 1 lot:** over design + holdout, worst calendar month >= -Rs 13,000 AND max drawdown <= Rs 40,000.
   A breach fails the candidate and is written next to its verdict.
8. **Costs robustness:** design net > 0 with twice the slippage ticks.

A pass = "**reliable on the data**". The answer to Boss ("reliable AND affordable at Rs 56k") also needs section 3's
"possible at Rs 56k". The baselines are reported against the same gates for comparison only.

**Verdict weighting.** The holdout is entirely post-regime and gate 1 needs the post-regime design part. A candidate
that passes only because of the old regime cannot pass.

## 7. Process

* Code: `r195cfg.py`, `r195data.py` (stores), `r195engine.py` (multi-leg simulator with the stale-leg marks, stops,
  margin), `r195rules.py` (the candidates), `r195nn.py` (N1), `r195study.py` (design, twins, the one holdout read,
  gates, BH), `r195report.py`, `r195entries.py`; tests in `test_r195.py` (leg marks and the BS fallback, stops, fills,
  charges, margin calibration, every rule's legs, the look-ahead guard: nothing after a decision minute or after the
  decision day changes a decision or a filter; the holdout lock; BH; twins).
* Background runs are limited to 30 minutes: the design run is split by candidate and written incrementally to
  `~/.cache/iraalgo/r195/` (trade caches, never in git); `results/` holds only small JSON and markdown.
* Log: one `research/experiments/entries/r195-*.yaml` per test (18 candidates + 5 baselines), failures included,
  family `option-structure` (N1 `ml`).
* Any change after this commit is a dated amendment below, saying whether any R195 number existed when it was written.

## Amendment 1 (18 Nov 2026, written AFTER the holdout read; descriptive only, no verdict changes)

**What was found.** S1-FINNIFTY's holdout (+Rs 1.14 lakh on 144 trades) leaned on a few days whose ATM call printed off
the market at 09:20: on 27 Mar 2026 the ATM call showed 635.95 with no volume in 09:16-09:21 while the ATM put was
296.6, one session before expiry. The rule "sold" that print. The pre-registered rule needed a fresh print on every
leg; a stale repeated print with no volume passes that check.

**The check (`r195quality.py`, results/quality.json).** A trade is flagged when a SOLD leg traded nothing in the 5
minutes up to its entry fill, or put-call parity fails at the decision minute at K0 by more than 25% of the straddle.
Each test's net is shown with and without flagged trades. This can only lower a result. It cannot make a failed
candidate pass, and no verdict depends on it: every candidate fails the pre-registered gates as they stand (BH and
the account stop for S1-FINNIFTY, the only candidate whose flagged trades matter).
