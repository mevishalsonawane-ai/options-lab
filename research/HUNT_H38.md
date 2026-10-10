# HUNT h38: index FUTURES — price + OI build-up, volume, basis (option buying only)

Files:
- Code: `research/hunt/h38/`
  - `PREREG.md`: the plan, written before any P&L, with one amendment.
  - `probe.py`: the Dhan probes.
  - `fetch_bhav.py`: the NSE daily bhavcopy fetcher.
  - `build.py`: the data panels.
  - `feats.py`: the triggers.
  - `run.py`: runs the Lab.
  - `post.py`: BH, SPA and survivor checks.
  - `liqfilter.py`: the features as Liquidity filters.
  - `diag.py`: data checks.
- Data: `scratchpad/hunt/h38/data/` (18 MB, zstd parquet / npz).
- Logs: `scratchpad/hunt/h38/*.log`, `*.csv`; Lab runs in `scratchpad/hunt/h38/cache/runs/`.

No secrets are in the code or the logs. Every printed Dhan string goes through a redactor. Only option BUYING was
tested, at 1 lot, 1-ITM, nearest expiry, with entry at the next minute's open. Fills are the app's, plus the measured
real half-spread from h24. Charges are `Costs('app')`.

## Verdict

**NO. Futures OI build-up, futures volume, futures basis and futures-vs-option OI disagreement do not give an
option-buying rule that makes money after costs. Rs 5,000/day: NO.**

1. **Minute futures OI history before 2026-07-29 cannot be fetched from Dhan, or from any free source I found.** This
   is now proven, not assumed (see "Data" below).
   - The 1-minute futures-OI tests could only run on 43 trading days (2026-08-05 to 2026-10-06), all inside the locked
     holdout.
   - I ran them once, with every setting fixed in advance. 1,080 variants.
   - None passes BH (lowest q 0.54). The union White RC p is 0.56.
   - The futures build-up (FBU) triggers average +Rs 2/day GROSS. That is zero.
2. **On 2020 to Sep 2025 I tested what history allows. 1,800 variants. Nothing survives.**
   - The daily futures build-up, the daily volume spike, the daily futures-vs-option OI disagreement and the daily basis
     change all come from 6.8 years of NSE bhavcopies that I fetched.
   - The intraday futures-premium change uses a synthetic future built from the option minutes.
   - Union White RC p = 1.00, Hansen SPA p = 0.997. The lowest BH q is 0.100, and the bar is < 0.10.
   - The 5 best families, run once in the holdout for information: **3 of 5 lost money net.**
3. **As a filter on Liquidity 15+5, one rule passed the pre-registered adoption bar, then failed the holdout.**
   - The rule: "skip the trade when the 30-minute futures-premium change strongly agrees with it".
   - Before the holdout: +Rs 58/day, BH q 0.008.
   - In the holdout: **−Rs 35/day**, p 0.61.
   - Not adopted.
4. **Liquidity 15+5 is unchanged.** Holdout: Rs 328/day net, 1 lot per index, all 5 indices.
   - None of the futures features improves it in the holdout.
   - In the futures-minute window none improves it either. The best was "skip if the 15-minute basis change opposes the
     trade": +Rs 76/day on 17 skipped trades, p 0.44.

## Data: what could and could not be fetched

### Dhan v2 probes

- The first probe at 04:51 UTC got HTTP 401 / DH-901. The old token had expired 2026-10-07 04:38 UTC.
- The coordinator then supplied a fresh token. All the later probes used it.
- Log: `scratchpad/hunt/h38/probe*.log`. Every probe was a single small request, about 1 per second.

| Request | Result |
|---|---|
| `/charts/intraday` FUTIDX, live NIFTY 2026-10 future (id 48704), 1-minute, `oi:true` | **Works**: 374 bars with OI and volume |
| Same, but with an **expired** contract id. NIFTY 2026-09 = 68407 and BANKNIFTY 2026-09 = 68390, ids taken from the NSE bhavcopy `FinInstrmId`, which equals Dhan's NSE_FNO securityId | HTTP 200, **0 bars** |
| Expired 2025-01 NIFTY future (id 35006). NSE reuses these ids later | 0 bars |
| Live id, asked for dates before the contract was listed (2026-06-01) | 0 bars. There is no continuous intraday series |
| Intraday with `expiryCode` 1, by contract id or by underlying id 13 | 0 bars |
| `/charts/rollingoption` with `instrument: FUTIDX` | empty |
| `/charts/historical` (**daily**) with a live future id and `expiryCode 0`, 2020 | **Works**: a continuous near-month DAILY series with OI back to 2020. It matches the bhavcopy exactly (median close difference 0.00, OI ratio 1.000) |
| `/charts/historical` with an expired id | DH-905 (bad input) |

The official docs (dhanhq.co/docs/v2, fetched 2026-10-08) say the same thing:
- **Intraday** is "for all active instruments", 5 years back, and at most 90 days per call.
- **Expired-contract data** exists only for OPTIONS (`rollingoption`, ATM±10).
- **Full Market Depth, 20-level and 200-level**: a **live websocket only** (`wss://depth-api-feed.dhan.co/twentydepth`,
  `wss://full-depth-api.dhan.co/twohundreddepth`). It covers only NSE equity and derivatives, with 50 instruments per
  connection.
  - There is **no historical depth** endpoint.
  - So depth or spread history has to be RECORDED live from now on. It cannot be downloaded.

**Conclusion:**
- Dhan cannot give 1-minute OI for any expired index future.
- The most it can ever give is each live contract from its listing date, so about 3 months for the far month.
- Our minute futures data is the 2026-10 contract (2026-07-29 to 2026-10-06), plus the 2026-11 and 2026-12 contracts.
  The 2026-09 near-month contract was never saved while it was live, so it is gone.

### What I fetched or built

| Dataset | Source | Coverage | Size |
|---|---|---|---|
| Daily index futures, every contract: OHLC, settle, contracts, OI, OI change | NSE F&O bhavcopy, public archive (old format to 2024-07-05, UDiFF after), 1,658 sessions, 0 failures | NIFTY and BANKNIFTY from 2020-01; FINNIFTY from 2021-01; MIDCPNIFTY from 2022-01; to 2026-10-07 | 17 MB |
| Daily index-option OI by expiry and side, all strikes | same bhavcopies | same | (in the above) |
| 1-minute futures with OI and volume | the existing Dhan files, compacted | NIFTY, BANKNIFTY, MIDCPNIFTY, SENSEX: 2026-07-29/31 to 2026-10-06; FINNIFTY 11 days; BANKEX 8 days | 1 MB |
| Synthetic futures premium, 1-minute: median over ATM±2 strikes of K + C − P, using fresh CE/PE prints only | built from the existing Dhan option minutes | 2020-08 (NIFTY) to 2026-10 | 0.5 MB |

- SENSEX and BANKEX futures were not fetched as daily history. The BSE bhavcopy shows about 1,600 SENSEX futures
  contracts and about 800 lots of OI a day, and about 10 BANKEX contracts. That is too thin to carry an OI signal.
- **The synthetic premium is only a partial proxy.**
  - Its 15-minute changes correlate 0.42 (NIFTY), 0.44 (BANKNIFTY), 0.34 (MIDCP) and 0.22 (SENSEX) with the real
    futures basis over the futures-minute window.
  - It follows the nearest option expiry, not the monthly future.

## Results

### Part A — testable history (choice window 2020 to 2025-09; 1,800 variants)

| Trigger | Variants | Net > 0 | Mean net Rs/day | Best Rs/day (in-sample) | Mean gross Rs/day |
|---|---|---|---|---|---|
| BASIS, intraday futures premium change (synthetic), conventional sign | 360 | 0% | −267 | −59 | −109 |
| BASIS, reversed | 360 | 9% | −142 | +110 | +12 |
| DBU, previous day's futures build-up | 144 | 18% | −22 | +48 | −6 |
| DBU, reversed | 144 | 11% | −23 | +48 | −8 |
| DVOL, futures volume spike with direction | 144 | 19% | −25 | +24 | −11 |
| DVOL, reversed | 144 | 11% | −26 | +14 | −12 |
| DDIS, futures OI vs option OI (agree / disagree) | 216 | 6% | −30 | +27 | −9 |
| DBAS, daily basis change | 144 | 22% | −17 | +87 | −2 |
| DBAS, reversed | 144 | 22% | −23 | +18 | −8 |

- Union White RC p = 1.00 and SPA p = 0.997. The lowest BH q is 0.100.
- Only 1 family passed the walk-forward gates: reversed BASIS on MIDCPNIFTY, WF +Rs 45.7k.
  - Its picked variant has BH q 0.15, so it is **not** a survivor.
- Every exit loses on average (−84 to −105 Rs/day). The premium-point exits make money in only 2-14% of variants.
- Every stop and target is checked on the option's 1-minute HIGH/LOW, with the stop first on a tie (as in h26).

**Holdout, run once, information only** (the best family by walk-forward per trigger type; 2025-10-01 to 2026-10-06,
249 days, 1 lot):

| Rule | Trades | Gross Rs/day | Net Rs/day | Net at 1.5× spread | Max DD | Losing months | p vs random |
|---|---|---|---|---|---|---|---|
| BASIS rev MIDCPNIFTY (since-open premium z, arm exits) | 147 | +73 | **−66** | −102 | −41,077 | 9/12 | 0.39 |
| DBAS rev FINNIFTY (basis z1, ladder) | 69 | +140 | **+63** | +38 | −17,904 | 5/12 | 0.20 |
| DBU FINNIFTY (all four classes, arm exits) | 30 | −70 | **−102** | −112 | −31,209 | 7/10 | 0.84 |
| DDIS FINNIFTY (disagree, follow futures, −15%/+30%) | 44 | +27 | **−22** | −38 | −38,933 | 6/12 | 0.50 |
| DVOL MIDCPNIFTY (k2, −10/+30 points) | 37 | +58 | **+31** | +25 | −13,622 | 7/12 | 0.12 |

- **Best positive, DBAS rev FINNIFTY at +63/day:**
  - Rs 5,000/day would need about 79 lots, which is about Rs 16.8 lakh of premium.
  - Rs 1 lakh buys 4 lots by premium. That is about Rs 250/day if the holdout repeated, with a drawdown of about −72k.
  - P(losing month) = 46%.
  - Not significant, and FINNIFTY's 0.42% spread is the thinnest book.

### Part B — 1-minute futures OI (holdout window only, 43 days, 1,080 variants, information only)

| Trigger | Variants | Trades / variant | Net > 0 | Mean net Rs/day | Mean gross Rs/day | Lowest p vs random |
|---|---|---|---|---|---|---|
| FBU: futures price + OI build-up over 5/15/30 min and since the open (long build-up, short covering, short build-up, long unwinding) | 576 | 18 | 28% | −49 | +2 | 0.002 |
| FVOL: 5-minute futures volume ≥ 3× / 5× normal, with direction | 144 | 79 | 8% | −426 | −179 | 0.009 |
| FVOL, reversed | 144 | 77 | 19% | −316 | −72 | 0.001 |
| FBAS: real futures basis change over 15 min | 72 | 81 | 1% | −409 | −152 | 0.027 |
| FBAS, reversed | 72 | 81 | 6% | −322 | −71 | 0.035 |
| FDIS: futures build-up agrees with the option OI (put − call) change | 72 | 29 | 13% | −117 | −35 | 0.18 |

- The lowest BH q is 0.54, the union RC p is 0.56, and the SPA p is 0.03.
- The best variants are MIDCPNIFTY futures build-up with tight point exits: about +Rs 540/day net on 25 trades.
  - That is selection on 43 days (q 0.68). It cannot be confirmed, because this data does not exist before July 2026.
  - It is a candidate to **log live**, not to trade.

### Part C — filters on Liquidity 15+5

- Before the holdout: Liquidity made +Rs 81/day net (gross 326) on 2,786 trades.
- 16 filters were tested.
- One was adopted by the rule: skip when the 30-minute synthetic premium change agrees with the trade (z ≥ 1).
  - It skipped 99 of 536 holdout trades.
  - Holdout change: **−Rs 35/day** (p 0.61). It **failed**.
- Daily build-up, volume-spike and OI-disagreement filters all lost money as "skip if opposes" (−47 to −70/day before
  the holdout).
  - So Liquidity trades that go against yesterday's futures positioning are, if anything, the better ones.

## Rs/day and size (Boss's questions)

- **Nothing here earns its place.** No rule survived, so there is no honest Rs/day at size.
- For scale, the most positive holdout rule (DBAS rev FINNIFTY):
  - +Rs 63/day net at 1 lot (+140 gross);
  - 79 lots for Rs 5,000/day;
  - 4 lots at Rs 1 lakh fixed, which is about Rs 250/day.
  - It is not significant.
- The best plan is still the h10/h13/h14 Liquidity plan.

## Honest counts

- Part A: 1,800 variants.
- Part B: 1,080 variants (one look).
- Part C: 16 + 5 filters.
- 2,901 tests in all.
- Amendment 1 changed Part B's normaliser from expanding to rolling 10-day, before any P&L. Only event counts had been
  seen.

## Recommendation

1. **Futures-OI as a minute signal can only be studied going forward.**
   - Save every live index future's 1-minute OHLCV + OI daily while the contract trades, starting now. The fetcher
     already has a `futures` kind; run it at least every ~80 days, before contracts expire.
   - The same applies to 20-level depth: record it live from the websocket if spreads matter.
2. **Do not add any futures-based trigger or filter to the app.** At most, log Part B's FBU MIDCPNIFTY signal on paper.
