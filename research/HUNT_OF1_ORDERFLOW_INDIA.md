# OF1: who placed the orders, why, and who made money (Indian index F&O, 2012–2026)

Written 13–14 Oct 2026, after Boss's note (13 Oct): "Reverse-engineer the order flow: what was placed, why it was
placed, and who made good profit." This is research only. No app code changes, and no orders.

- **Pre-registered** in `research/hunt/of1/PREREG.md`, committed (`f969bc2`) before any analysis ran.
  - Amendments 1–3 are dated and give their reasons. They were committed with the design results (`d6183fb`) before
    the holdout was read.
  - The holdout was read **once**, on 14 Oct 2026 at 02:18 IST (`results/HOLDOUT_READ.json`).
  - After that read, India VIX gaps for 2012–2014 were filled from the Dhan lake and the design report was regenerated
    (Amendment 4). Every test and rule output came out byte-identical; only the descriptive VIX-regime tables changed.
- **Code and tables:** `research/hunt/of1/`.
- **Experiment log:** `research/experiments/entries/of1-*.yaml`.

## Verdict for Boss

**Which group has made money in index F&O, and how?**

- **Pro (brokers' own desks) are the steady winners.** They made money in 13 of 15 years (2012–2026, futures plus
  options). Their gain comes from **index options**: about ₹+8,900 crore, ₹+5,900 to +12,900 crore depending on the
  strike-mix assumption.
  - They are net option *writers* on 92% of days.
  - Their index futures lose (about ₹−2,800 crore), which looks like the cost of hedging their written options.
  - They win in trends and ranges, and at high and low VIX alike.
- **FIIs make their money in index futures on trending days.**
  - About ₹+3,200 to +7,100 crore over 2012–2026.
  - On trend days: ₹+7,591 crore over 1,363 days. On range days: ₹−4,356 crore over 2,279 days.
  - The gain is lumpy: 2020 alone was ₹+4,232 crore, and the last 12 months (holdout) ₹+3,298 crore. They lost in
    2015 (₹−1,943 crore) and 2024 (₹−2,417 crore).
  - FIIs are trend-followers: their net futures rise on up days (same-day correlation +0.44).
  - Their options result is not robust: its sign depends on the strike mix.
- **DIIs** gain a little in futures (₹+1,100 to +1,600 crore), mostly in range markets and at high VIX. They are
  contrarian: they buy after falls (correlation with the previous day's move −0.22).
- **Clients (mostly retail) lose.**
  - Index options: ₹−7,600 to −11,600 crore. Index futures: ₹−2,000 to −5,000 crore.
  - They made money in only 7 of 15 years.
  - Most of their options loss came at **low VIX**: ₹−9,661 crore over 1,949 days.
  - This end-of-day measure misses their intraday losses and their costs. SEBI's own study counts both and finds far
    bigger losses for individuals (see "Who profited", below).

**Does following any group, or any footprint, help after costs?** **No.**

- 15 of 36 "follow the group" signals passed the multiple-testing gate on 2012–2025. A typical one: when FIIs add
  index-futures longs, NIFTY tends to rise the next day from open to close; when Clients add longs, it tends to fall.
- **On the locked last 12 months, 0 of 15 held: 13 of the 15 even flipped sign** (246 days).
- The walk-forward had already shown the edge fading: the FII signal was right on only 39% of signal days in 2024 (44
  days) and 33% in 2025 (33 days).
- None of the four "follow the group" option-buying rules passed even the design gates. On the holdout all four lost:
  ₹−32,212 on 39 trades for "follow FII futures", ₹−1,04,848 on 57 trades for the worst.
- The effect is about 0.05% of the index per standard deviation of the signal. That is about 12 NIFTY points on a
  strong day, which an ATM option's theta and costs eat.

**What does the option chain's build-up tell you?** Mostly who was paid at expiry, not where the index goes.

- OI added on days the premium **fell** ("short build-up", the writers' side) paid the writers. Its new longs lost
  about 23% of the premium by expiry: NIFTY ₹47,133 crore over 1,38,164 strike-days; BANKNIFTY ₹34,907 crore over
  1,62,579.
- Far-OTM (more than 3% away) options added on falling-premium days won for the buyer only 1–2% of the time (6% for
  BANKNIFTY ones added on rising-premium days).
- OI added on days the premium **rose** ("long build-up") roughly broke even (NIFTY −5.6%, BANKNIFTY +4.7% of
  premium).
- The **heaviest-OI strikes are neither walls nor magnets.** Over 433 NIFTY and 506 BANKNIFTY expiries, settlement broke
  the max-OI call/put strikes as often as distance alone predicts. The max-OI strike ended closer to settlement than a
  same-distance random strike 41.8% / 43.2% of the time, about what no magnet at all gives (~44% in simulation, because
  some random twins land on the max-OI strike itself). 0 of 12 tests passed.

**What can never be known from this data:**

- **who** any trader is, and their reason;
- intraday round trips, and P&L inside the day;
- each group's real strike and expiry mix;
- each group's costs;
- whether a "Client" is a retail person or an HNI or a corporate;
- DII cash flows (NSE's API refused this machine).

The app's own order-flow recordings could not be studied: none has been uploaded yet.

## What the data can and cannot say (up front)

NSE's data is anonymous. It reports positions by participant **type** only. Every "who" below is a type, and every
"why" is an inference from behaviour, never a known intent.

| type | who it is (NSE's definition) |
|---|---|
| Client | everyone not in the other three: mostly retail individuals, but also HNIs, corporates and non-institutional funds |
| DII | domestic institutions: mutual funds, insurers, banks |
| FII | foreign portfolio investors |
| Pro | trading members' (brokers') own money: proprietary desks, including the big HFT and market-making desks |

## Data obtained (exactly)

| source | what | obtained |
|---|---|---|
| NSE `fao_participant_oi_DDMMYYYY.csv` | participant-wise OI, contracts | 3,644 days, 2 Jan 2012 – 13 Oct 2026. 12 damaged files left out (listed in `results/REPORT_design.md`), so **3,632 usable** |
| NSE `fao_participant_vol_DDMMYYYY.csv` | participant-wise volume | 3,644 days |
| NSE F&O bhavcopy (old layout to 5 Jul 2024, UDiFF after) | every index future/option: close, settle, OI, volume | 3,644 days. Cut to index rows: 3,643 day-to-day MTM steps |
| NSE `ind_close_all` | NIFTY, BANKNIFTY, India VIX OHLC | 3,600 days. Gaps (incl. VIX 2012–2014) filled from the Dhan lake's `index_daily` |
| NSE `fii_stats` | FII derivatives statistics | 1,432 days (late 2020 →). Stopped early to save time; not used |
| NSDL FPI archive | daily FPI cash-equity flows, by reporting date | 3,560 days, 2 Jan 2012 – 13 Oct 2026 |
| DII cash flows | | **not obtained**: only on NSE's www API, which returned HTTP 403 to this machine |
| The app's recordings (IraAlgo) | per-second futures/option flow, 5-level book | **none**: the repo is readable, but it has 0 releases and an empty catalogue (it was created 13 Oct). Uploads are also sealed with Boss's passphrase, which this session does not have |

- NSE's archive host (`nsearchives.nseindia.com`) answered this cloud machine with a browser user agent and paced
  requests. Only the www site and its API blocked it.
- All files are on Google Drive, `gdrive:IraAlgo-data-lake/participants/`. Each file is listed in `MANIFEST.txt`
  (sha256), with a `README.txt`. None of them is in git.

## (A) WHO: positioning by type

Design period: 3,385 days, 2012–2025.

| | Client | DII | FII | Pro |
|---|---|---|---|---|
| median index-futures long share | 0.49 | 0.47 | 0.57 | 0.40 |
| median index calls, net (thousand contracts) | −81 | +31 | +90 | −80 |
| median index puts, net (thousand contracts) | −234 | +41 | +259 | −85 |
| days net option WRITER (calls + puts net < 0) | 96.6% | 0.0% | 0.8% | 91.7% |

Share of index-option volume, 2025: Pro 60.3%, Client 32.7%, FII 7.0%, DII ≈ 0%. In 2012 it was Pro 49.1%, Client 34.6%,
FII 16.2%. Share of index-futures volume, 2025: Client 47.8%, Pro 32.1%, FII 15.9%, DII 4.1%. Tables by year are in
`results/REPORT_design.md`.

Read plainly:

- In overnight OI, **FIIs hold long puts and long calls** (hedges, and long volatility).
- **Clients and Pros are the net writers.**
- DIIs hold a few long puts (hedges) and almost no written options.

## (B) WHY: behaviour, inferred (design, 3,385 days)

| correlation (Spearman) | Client | DII | FII | Pro |
|---|---|---|---|---|
| Δ net index futures vs NIFTY's move the same day | −0.35 | −0.26 | **+0.44** | −0.15 |
| Δ net index futures vs NIFTY's move the day before | −0.05 | **−0.22** | +0.08 | +0.01 |
| Δ stock-futures long vs Δ index-put long (hedging) | +0.07 | +0.11 | +0.18 | +0.11 |

- **FII: trend-following.** They add longs on up days and cut them on down days. Whether they push the move or follow
  it inside the day cannot be told from end-of-day data. Their put buying rises with their stock-futures longs
  (+0.18), which looks like portfolio hedging.
- **DII: contrarian, slow.** They add longs the day *after* a fall (−0.22). That looks like value buying or
  rebalancing.
- **Client: contrarian on the day.** They are the other side of the FII move (−0.35): they buy falls, sell rises and
  write puts.
- **Pro: market-making.** They are net option writers with modest futures moves against the market, which looks like
  delta-hedging the options they wrote.

These are inferences. The same numbers could come from other motives.

## (C) WHO PROFITED

### Method (fixed in PREREG §3)

The participant file gives each group's open contracts by product, not by underlying, expiry or strike. So a group's
day P&L is its net contracts (end of day *t*) times the **market pool's** average mark-to-market per contract on day
*t+1*:

- Index futures pool MTM per contract = Σ OIqty(t) · Δ settle / NSE's total index-futures contracts(t).
  - The bhavcopy OI is in units, so this is rupees without knowing the lot.
  - Units per contract came out at 23–72 (1st–99th percentile, 3,643 steps), matching the lot sizes of each
    period (median 25 in 2015, 70 in 2018, 24 in 2024, 68 in 2025), with no jump on expiry days.
- The four groups' nets sum to zero, so their P&Ls sum to zero. Checked: the largest daily residual is ₹4,820
  (futures) and ₹19,458 (options), from NSE's ±1-contract rounding; ₹0 in crore. This is close to an identity, so it
  confirms the bookkeeping, not the method.
- A second futures estimate adds the day's position change, valued between settle and the day's typical price.
- Index options use the same pool, separately for calls and puts. Three strike mixes are used because the real mix is
  unknown: (a) every strike, weighted by OI; (b) within 2% of the money; (c) beyond 2%. A sign is called **robust**
  only if (a), (b) and (c) agree.
  - Options are marked to the **close**. NSE's option "settle" is a theoretical price for untraded strikes and is
    sometimes 0.00 (Amendment 3).
  - Expiring options are valued at intrinsic value. The underlying's final level is NSE's printed value from about
    2020, and the official index close before that, when NSE printed 0. On all 623 later-era expiries the two agree
    exactly.

**Limits:**

- The P&L is gross of costs.
- It covers end-of-day positions only, so intraday round trips are invisible.
- Each group is assumed to hold the market's mix of underlyings, expiries and strikes.

This understates retail losses badly. SEBI's study of individual F&O traders (Sep 2024, as published) counts intraday
trades and costs. It reports aggregate losses of over ₹1.8 lakh crore over FY22–FY24, with 93% of individuals losing.
Our end-of-day, index-only, gross measure for the whole Client type over 15 years is ₹−9,600 to −16,600 crore.

### Totals, 2012–2026

3,642 sessions, ₹ crore, gross.

| estimate | Client | DII | FII | Pro |
|---|---|---|---|---|
| index futures (end-of-day positions) | −2,009 | +1,571 | +3,235 | −2,797 |
| index futures + the day's position change | −4,973 | +1,147 | +7,062 | −3,236 |
| index options (a) all strikes | −10,453 | +254 | +1,279 | +8,921 |
| index options (b) near the money | −11,647 | −558 | −720 | +12,925 |
| index options (c) far from the money | −7,617 | +682 | +990 | +5,946 |
| **robust sign: futures / options** | **− / −** | **+ / mixed** | **+ / mixed** | **− / +** |

### By year (index futures + options (a), ₹ crore)

| year | Client | DII | FII | Pro |
|---|---|---|---|---|
| 2012 | +212 | +198 | −662 | +252 |
| 2013 | +100 | +36 | −283 | +147 |
| 2014 | −1,090 | +603 | +328 | +159 |
| 2015 | +1,918 | −165 | −2,006 | +252 |
| 2016 | −255 | −112 | +251 | +116 |
| 2017 | −1,230 | +485 | +987 | −242 |
| 2018 | −353 | +565 | −574 | +362 |
| 2019 | +1,143 | +125 | −1,388 | +121 |
| 2020 | −5,598 | −854 | +6,438 | +14 |
| 2021 | +46 | −724 | +306 | +372 |
| 2022 | −286 | +317 | −1,007 | +976 |
| 2023 | −986 | +622 | −889 | +1,253 |
| 2024 | +797 | −333 | −2,362 | +1,898 |
| 2025 | +109 | +1,464 | −2,129 | +557 |
| 2026 (to 13 Oct) | −6,990 | −402 | +7,504 | −113 |
| **years with a profit** | 7 / 15 | 9 / 15 | 6 / 15 | **13 / 15** |

### By regime

The regime is labelled point-in-time, at the day before each P&L day.

| regime (days) | Client fut | Client opt | DII fut | DII opt | FII fut | FII opt | Pro fut | Pro opt |
|---|---|---|---|---|---|---|---|---|
| trend (1,363) | −4,683 | −6,470 | −1,492 | +452 | **+7,591** | +2,166 | −1,416 | +3,853 |
| range (2,279) | +2,674 | −3,983 | **+3,064** | −198 | −4,356 | −887 | −1,382 | +5,068 |
| VIX above its 250-day median (1,693) | −103 | −793 | +1,640 | −868 | −699 | −1,679 | −838 | +3,339 |
| VIX below (1,949) | −1,907 | **−9,661** | −68 | +1,121 | +3,934 | +2,958 | −1,959 | **+5,581** |
| monthly-expiry week (836) | −604 | −730 | −556 | −76 | +1,877 | −1,593 | −716 | +2,399 |
| other weeks (2,806) | −1,405 | −9,723 | +2,127 | +330 | +1,358 | +2,872 | −2,081 | +6,522 |

"opt" = options mix (a). Trend = |NIFTY 20-day move| > its 20-day volatility × √20. All tables, including mixes (b) and
(c), are in `results/SUMMARY_full.md`.

**The last 12 months (holdout, 13 Oct 2025 – 13 Oct 2026, descriptive):**

- Index futures: FII +3,298, Client −2,327, DII −542, Pro −429 (₹ crore).
- Options (a): FII +2,827, Client −3,342, Pro +291, DII +224.

## Does following a group predict the next day?

Signals are known after day *t*'s close; NSE publishes the files in the evening. Each is a z-score of the day's change
over the previous 60 days, for each group:

- S1: Δ net index futures;
- S2: Δ long share;
- S3: Δ options stance (calls net − puts net);
- S4: Δ put writing.

Two more come from foreign money: S5 is the FPI cash net (only a row reported on *t* counts at *t*), and S6 is S1_FII
plus S5. That is 18 signals × NIFTY and BANKNIFTY = **36 tests**. The target is the next day's **open → close** move,
the part a trader can still catch.

| | design 2012–2025 (3,322 days) | holdout, 13 Oct 2025 – 13 Oct 2026 (246 days) |
|---|---|---|
| survive BH (q < 0.10) | **15 of 36**: FII S1–S4 positive, Client S1–S4 negative, S6 on BANKNIFTY | **0 of 15 pass**: 13 of 15 slopes flipped sign; best one-sided p 0.13 (S1_FII → NIFTY) |
| size | slope 0.03–0.08% of the index per 1 σ of the signal; Spearman 0.04–0.08 | |
| FPI cash flow alone (S5) | p 0.66 (BANKNIFTY), 0.97 (NIFTY): nothing | |

- **Walk-forward** (the sign learned on years before each year, |z| ≥ 1 days only). FII S1 → NIFTY:
  - right on 68% of signal days in 2014 (44 days) and 69% in 2020 (64 days);
  - but 39% in 2024 (44 days) and 33% in 2025 (33 days).
  - The edge was fading before the holdout.
- **Longer horizons** (descriptive, close to close, HAC errors):
  - 6 of 36 have q < 0.10 at 5 days, mostly "Clients turning bullish in options → weaker NIFTY";
  - 0 of 36 at 20 days;
  - positioning *levels* (long-share percentiles in quintiles) show no monotone pattern at 1, 5 or 20 days (HAC t
    between −1.1 and +1.4).
- These are known patterns in Indian market commentary. They were real on 2012–2023 data, but too small to trade with
  bought options, and they have stopped working.

## Option chain: build-up, and walls/magnets

The **build-up** labels use NIFTY and BANKNIFTY's nearest expiry, every strike, every day (3,644 sessions). The label
comes from that day's premium change and OI change:

| | NIFTY | BANKNIFTY |
|---|---|---|
| long build-up / short build-up / short covering / long unwinding strike-days (design) | 45,277 / 121,625 / 72,474 / 124,465 | 47,489 / 144,346 / 59,827 / 109,300 |

**Who was paid at expiry, 2012–2026.** Each day's OI additions are held to expiry, so this is a hindsight measure of
who was paid, not of how traders actually exited:

| added on a day labelled… | NIFTY: new longs won / P&L % of premium / to writers | BANKNIFTY: the same |
|---|---|---|
| short build-up (premium fell, OI rose) | 13.6% / −23.4% / ₹47,133 crore | 13.8% / −22.8% / ₹34,907 crore |
| long build-up (premium rose, OI rose) | 26.7% / −5.6% / ₹3,207 crore | 26.9% / +4.7% / ₹−2,365 crore |
| short build-up, OTM > 3% | 0.9% / −63.4% | 2.2% / −50.4% |
| long build-up, ATM | 33.9% / +5.5% | 33.5% / +5.8% |

Plainly:

- Positions opened on falling-premium days are written options that, on average, decay to the writers.
- Buyers who pile in as the premium rises roughly break even at ATM.
- Far-OTM buying almost never pays at expiry.

This is the writers' edge Pro collects. Its tail risk shows in years like 2020.

**Walls and magnets.** At the close 1 and 3 sessions before each expiry:

- **Call wall**: the max-OI call at or above spot. **Put wall**: the max-OI put at or below spot.
- **Magnet**: K*, the max total-OI strike.
- **Random twins**: a breach rate expected from the distance alone (other expiries' moves), and same-distance random
  strikes.

| design (NIFTY 433 / BANKNIFTY 506 expiries at k = 1) | result |
|---|---|
| call wall, NIFTY k = 1 | 47 breaches vs 49.9 expected from distance alone (p 0.35) |
| put wall, NIFTY k = 1 | 56 vs 51.3 (p 0.80) |
| put wall, BANKNIFTY k = 1 | 51 vs 57.8 (p 0.17) |
| magnet: K* closer to settlement than a same-distance random strike | NIFTY 41.8% (392 expiries), BANKNIFTY 43.2% (481); with no magnet at all this is ~44% (ties with K* count as not closer), so this is the null; the sign-flip test vs the mirror strike: p 0.92 / 0.62 |
| all 12 tests | best BH q 0.83: **no wall, no pin** |

The holdout wall/magnet tests were computed for the record only (53 NIFTY and 12 BANKNIFTY expiries). With no design
survivor, nothing could pass. Nothing there either (best q 0.34).

## The app's own order-flow recordings (footprints)

The IraAlgo data repository is readable from this session, but it holds **0 releases and an empty catalogue** (created
13 Oct). The phone's uploads are also sealed with Boss's backup passphrase. So **no recorded day was analysed**, and
nothing is claimed.

`research/hunt/of1/footprints.py` is ready for when days accumulate. It reads `ticks.jsonl.gz`, as `decrypt_live.py`
extracts it, and finds:

- large aggressive prints (above the day's earlier 99th percentile, as TickZone);
- absorption (TickZone's rule);
- sweeps through 3 or more book levels;
- iceberg-like refills.

For each it measures the future's move 1, 5 and 15 minutes later, in points, signed by the footprint's side:

```
python research/data/decrypt_live.py live-2026-10-*.irlive --extract days/
python3 -I research/hunt/of1/footprints.py days/2026-10-* --out research/hunt/of1/results/footprints.csv
```

It is tested on synthetic ticks only. With a few days it is description, not a trading claim.

## Trading rules ("follow the group"), PREREG §8

Each rule uses the app's option-buying arm:

- ATM call or put of the nearest expiry, one lot, bought at the next day's open, with 5 bps + 2 ticks slippage;
- stop −30%; step lock (+40% reached → +15% locked); otherwise out at the close;
- expiry days skipped;
- charges at the rates in force on each date (`research/obuy/costs.py`);
- daily bars with a pessimistic order (the stop is assumed hit first);
- walk-forward side.

| rule | design 2014–2025 | twin p / BH q | holdout |
|---|---|---|---|
| R0 follow FII futures (S1) → NIFTY | 526 trades, 201 wins, ₹−94,447 | 0.73 / 0.73 | 39 trades, 11 wins, **₹−32,212** |
| R1 against Client long share (S2) → NIFTY | 575, 236, ₹+30,460 | 0.105 / 0.21 | 47, 7, **₹−1,00,666** |
| R2 against Client long share (S2) → BANKNIFTY | 567, 225, ₹+240 | 0.21 / 0.28 | 57, 20, **₹−1,04,848** |
| R3 follow FII options stance (S3) → NIFTY | 608, 241, ₹+22,359 | 0.076 / 0.21 | 63, 19, **₹−52,562** |
| (R3 superseded by Amendment 2: S1_FII → BANKNIFTY) | 511, 186, ₹+22,549 | 0.36 | not run |

**No rule passes.** None cleared the design gates: twin p < 0.05 and BH q < 0.10. All four lost on the holdout. Nothing
goes to the app, not even to shadow.

## Limits

- **Anonymous data.** Types, not people. "Client" mixes retail, HNIs and corporates; "Pro" mixes HFT market makers and
  ordinary prop desks.
- **The MTM method** assumes each group holds the market's mix of underlyings, expiries and strikes; options are
  checked under three mixes. It is gross of costs and covers end-of-day positions only. The options figures are the
  least reliable; only robust signs are claimed.
- **12 participant files were damaged** and left out (positions carried one day for the MTM only).
- **Two MTM steps lost OI with no next-day price**: 0.03% and 0.09% of the pool, around Diwali-session dates.
- **The rules' rupee sizes** use the repo's lot schedule (`research/obuy/config.py`), which has NIFTY at 75 before
  July 2021. NSE's real lot was smaller in 2014–2015 (25–50), so those early trades are overstated in rupees. Signs and
  win counts are unaffected.
- **One R0 design trade** (23 Apr 2014) bought an option on its holiday-moved expiry day: the expiry-day skip compares
  the listed expiry, not the effective one. 1 of 1,052 R0 design rows (rule + twin); none in R1–R3 or the holdout. Not
  rerun (the holdout is spent); it cannot change a verdict.
- **The rules use daily bars**, not minute paths. The pessimistic ordering penalises them. A minute-level test would need
  the Dhan lake, but with signals this weak and a holdout this negative it would not change the verdict.
- **The pinned research environment** (Python 3.11, pandas 2.2.3, `research/brain/requirements.txt`) ran the final
  build, analysis and every check. Under this container's default Python 3.13 with pandas 3, the OF1 tests pass but 4
  existing brain tests fail on string dtypes (unrelated to OF1).

## Reproduce

```
python3 -I research/hunt/of1/fetch.py --from 2012-01-01          # NSE public files -> <cache>/of1/raw (paced)
python3 -I research/hunt/of1/fetch_fpi.py                        # NSDL FPI daily flows
python3 -I research/hunt/of1/build.py                            # pools, nearest-expiry chains, finals
python3 -I research/hunt/of1/analyze.py design                   # design-period tests and tables
python3 -I research/hunt/of1/analyze.py holdout                  # refuses: already read (results/HOLDOUT_READ.json)
python3 -I research/hunt/of1/analyze.py summary                  # 2012-2026 who-profited tables
python3 -m pytest research/hunt/of1/test_of1.py -q               # 30 tests: P&L maths, build-up, footprints, look-ahead
python3 -I research/hunt/of1/publish_gdrive.py                   # copy to gdrive:IraAlgo-data-lake/participants
```

Instead of fetching, copy the files from Drive and unzip them into the layout the loaders read (`<cache>` is
`$IRAALGO_CACHE`, default `~/.cache/iraalgo`):

```
rclone copy gdrive:IraAlgo-data-lake/participants /tmp/of1-drive
for k in oi vol; do mkdir -p <cache>/of1/raw/participants/$k && for z in /tmp/of1-drive/$k/*.zip; do unzip -oq "$z" -d <cache>/of1/raw/participants/$k; done; done
mkdir -p <cache>/of1/raw/indices && for z in /tmp/of1-drive/indices/*.zip; do unzip -oq "$z" -d <cache>/of1/raw/indices; done
mkdir -p <cache>/of1/raw/fpi && cp /tmp/of1-drive/fpi/fpi_daily.csv <cache>/of1/raw/fpi/
```

The index-only bhavcopy is on Drive as one parquet a year (`bhav_index/`). `build.py` reads the per-day files that
`fetch.py` writes, so either re-fetch those, or split the yearly files by `date` into `<cache>/of1/raw/bhav/YYYY-MM-DD.parquet`.
