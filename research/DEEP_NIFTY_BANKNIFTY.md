# Deep learn NIFTY and BANKNIFTY (R102, 22 Oct 2026)

Boss (22 Oct 2026): "Deep learn nifty and banknifty." This is the study: what the two indices are, how they move, how
their option chains behave, who trades them, what our own bots have found on them, and a field guide of the most useful
facts, each with its number and sample size. Jarvis reads the same facts from `research/knowledge/index_facts.yaml`.

**Every number here is either from our own lake (Dhan v2 copies on Drive: `index_1m`, `index_daily`,
`options_rolling_1m`, `participants`, the F&O bhavcopy index rows) or a public fact verified online on 22 Oct 2026
(source in brackets).** The lake numbers are "descriptive, not a trading rule" unless the section says it was a
pre-registered test. Periods: the **design period ends 14 Jul 2026**; the **holdout 15 Jul - 19 Oct 2026** was read
once for the predictive part (pre-registration and amendments: `research/hunt/r102_deep/PREREG.md`; code and results:
`research/hunt/r102_deep/`). Where a table says "n", it is days unless stated.

Signal on the underlying (CLAUDE.md): every statistic about "the index" is the cash index, not an option price.

## 0. The short version

* BANKNIFTY is a leveraged NIFTY: daily correlation 0.877 and beta 1.16 over 20 years (n 5,086 days), 0.84 and 1.09
  on 5-minute bars (n 89,575). FINNIFTY is nearly BANKNIFTY (0.974); SENSEX is NIFTY (0.991).
* Both are quieter than they used to be: NIFTY's daily sd was 1.32% over 2006-2026 but 0.74% in 2025; BANKNIFTY 1.73%
  then 0.79%. 2026 to July was busier again (1.05% / 1.36%).
* The day is front-loaded. By 10:30 NIFTY has made a median 61% of its range (BANKNIFTY 65%); the first minute alone
  spans a median 0.21% (NIFTY) / 0.30% (BANKNIFTY); minute-for-minute the first 15 minutes are about 8x the midday rate.
* Gaps of 0.5% or more happen on 20% of NIFTY days and 34% of BANKNIFTY days; a 0.5-1% gap fills by the close less
  than half the time (NIFTY 42%, BANKNIFTY 49-53%).
* The option chain over-prices the ordinary day and under-prices expiry: the 09:20 ATM straddle is exceeded by the
  close-to-09:20 move on only 7% of non-expiry days (NIFTY; BANKNIFTY 6.5%) but on 41% / 38% of expiry days.
* Expiry-day theta is a cliff after 14:30: the rolling ATM straddle is a median 84% of its 09:20 value at 10:30, 61%
  at 13:00, 40% at 14:30, 22% at 15:00 and 13% at 15:15 (NIFTY, n 259 expiries; BANKNIFTY the same shape, n 186).
* Participants: FIIs ran net short index futures through 2025-26 (-61% / -72% of their book) while clients ran net
  long (+30% / +46%). None of the 36 pre-registered participant rules was confirmed on the holdout.
* 70 pre-registered predictive tests, 8 passed the design gates, **1 was confirmed on the holdout, and that one is an
  artefact** (the 1-minute "momentum" of the cash index, +0.08-0.11 bps, is index staleness, worth nothing after
  costs). The two daily-momentum rules that looked best over 20 years (NIFTY +5.8 bps, BANKNIFTY +12.4 bps a day)
  reversed in the holdout quarter (-9.4 and -24.6 bps, n 65). There is no simple sign rule in this data.
* What our bots found on these indices (section 5): out of about 60 arms and studies, five rules passed the full
  gates on the recent market and all are paper-only; everything else is rejected. The one place the data is
  reliably predictable is **size, not direction**: the next 30-60 minutes' range (R16, R41, R88) and the expiry-day
  decay clock above.

## 1. What they are

### 1.1 NIFTY 50

* 50 stocks, float-adjusted market-cap weighted, **no capping**; semi-annual rebalance (cut-offs 31 Jan and 31 Jul,
  changes at the end of March and September) [NSE Indices factsheet 30 Sep 2026, methodology Sep 2026].
* Top 10 on 30 Sep 2026: HDFC Bank 10.38%, ICICI Bank 9.05%, Reliance 7.58%, Bharti Airtel 5.10%, L&T 4.20%, SBI
  3.79%, Axis Bank 3.37%, Infosys 3.35%, Kotak Mahindra Bank 2.93%, M&M 2.52% (sum 52.27%) [factsheet].
* Sector tilt: Financial Services 37.45%, Oil & Gas 9.39%, IT 7.52%, Autos 6.65%, FMCG 5.50%, Telecom 5.10%, Metals
  4.86%, Healthcare 4.74%, Construction 4.20% [factsheet]. The five NIFTY BANK members inside NIFTY 50 (HDFC, ICICI,
  SBI, Axis, Kotak) weigh **29.52%** of it. That overlap is the mechanical reason for the 0.88 daily correlation.

### 1.2 NIFTY BANK (BANKNIFTY)

* **14 banks** since 31 Dec 2025 (Yes Bank and Union Bank added; it was 12). "Periodic capped free float": the top
  three are capped at **19% / 14% / 10%**, non-F&O names at 4.5% each and 10% together. The old 33% single / 62%
  top-three cap went with SEBI's May 2025 derivatives-eligibility rule (at least 14 members, top stock at most 20%,
  top three at most 45%); HDFC and ICICI were cut in four monthly tranches Dec 2025 - Mar 2026 [NSE Indices
  factsheet 30 Sep 2026; methodology Sep 2026; Business Today 3 Nov 2025].
* Weights on 30 Sep 2026: HDFC Bank 18.61%, ICICI Bank 14.18%, Kotak 10.20%, Axis 10.06%, SBI 9.93%, Federal Bank
  6.56%, IndusInd 4.97%, AU Small Finance 4.85%, IDFC First 4.54%, Bank of Baroda 3.59%, then Canara, PNB, Union Bank
  and Yes Bank. **The big five are 62.98%** of the index, so BANKNIFTY still moves on five stocks, and those five are
  also 29.5% of NIFTY 50.
* Beta vs NIFTY 50 printed on the factsheet: 1.15 (one year). Our lake (section 1.4) says 1.16 over 20 years, 0.92 in
  2025 and 1.20 in the first half of 2026.

### 1.3 The neighbours

* **FINNIFTY** (NIFTY Financial Services): 20 names, same 19/14/10 cap; its five largest are BANKNIFTY's five
  largest (about 63% of it), the rest is NBFCs, insurers and the exchanges [factsheet 30 Sep 2026]. Daily correlation
  with BANKNIFTY 0.974, beta 1.006 (n 5,086).
* **MIDCPNIFTY** (NIFTY Midcap Select): 25 liquid midcaps, Financial Services 38.5%, shares Federal Bank, IndusInd
  and AU SFB with BANKNIFTY [factsheet]. Daily correlation with NIFTY 0.82 (n 1,102 days from Jan 2022), with
  BANKNIFTY 0.73.
* **SENSEX**: BSE's 30 stocks; correlation with NIFTY 0.991, beta 0.986 (n 5,086). Treat SENSEX as NIFTY with a
  Thursday expiry.

### 1.4 Correlation and beta from our lake (daily close-to-close returns; descriptive)

| pair | period | n | corr | beta of first on second |
|---|---|---|---|---|
| BANKNIFTY on NIFTY | Jan 2006 - 14 Jul 2026 | 5,086 | 0.877 | 1.157 |
| BANKNIFTY on NIFTY | 2024 | 249 | 0.856 | 1.072 |
| BANKNIFTY on NIFTY | 2025 | 249 | 0.869 | 0.921 |
| BANKNIFTY on NIFTY | Jan - 14 Jul 2026 | 130 | 0.928 | 1.197 |
| BANKNIFTY on FINNIFTY | 2006 - 2026 | 5,086 | 0.974 | 1.006 |
| BANKNIFTY on SENSEX | 2006 - 2026 | 5,086 | 0.885 | 1.162 |
| BANKNIFTY on MIDCPNIFTY | 2022 - 2026 | 1,102 | 0.730 | 0.671 |
| NIFTY on SENSEX | 2006 - 2026 | 5,086 | 0.991 | 0.986 |
| NIFTY on FINNIFTY | 2006 - 2026 | 5,087 | 0.908 | 0.711 |
| NIFTY on MIDCPNIFTY | 2022 - 2026 | 1,102 | 0.820 | 0.615 |
| BANKNIFTY on NIFTY, 5-minute bars | Aug 2021 - 14 Jul 2026 | 89,575 bars | 0.842 | 1.094 |

### 1.5 Contracts, lots, expiries, hours, charges (public, verified 22 Oct 2026)

| item | NIFTY | BANKNIFTY | source |
|---|---|---|---|
| lot now | **65** (from the Jan 2026 expiries; 75 from 20 Nov 2024; 25 from 26 Apr 2024; 50 before) | **30** (Jan 2026; 35 from 25 Apr 2025; 30 from 20 Nov 2024; 15 from 28 Apr 2023; 25 before) | NSE/FAOP/70616 (3 Oct 2025), FAOP67372, FAOP64625, FAOP61415 |
| tick | Rs 0.05 | Rs 0.05 | NSE contract specification |
| strike step | 50 points, 35-1-35 strikes on every expiry | 100 points, 15-1-15 | NSE strike interval file (NSE/FAOP/67775) |
| expiries | weekly **Tuesday** since 2 Sep 2025 (Thursday before); monthly last Tuesday; quarterly and half-yearly long-dated | **monthly only** since 20 Nov 2024 (last weekly 13 Nov 2024); last Tuesday since 30 Sep 2025 (last Thursday Jan - Aug 2025; weekly Wednesday 6 Sep 2023 - Nov 2024, Thursday before) | SEBI circular 1 Oct 2024; NSE FAOP64506, FAOP57540, FAOP68747 |
| settlement | cash, European, final price = the index close of the last day (the last-30-minute VWAP of the constituents; CAS-based 3 Aug - 6 Dec 2026, VWAP again from 7 Dec 2026) | same | NSE spec; SEBI circular 15 Oct 2026 |
| hours | 09:15 - **15:40** since 3 Aug 2026 (15:30 before; 15:45 from 7 Dec 2026, the expiring index contract stops at 15:30 on its expiry day) | same | NSE/CMTR/74466; SEBI 15 Oct 2026 |
| SEBI 2024-25 measures | 2% extra ELM on short options on expiry day (20 Nov 2024); option premium collected upfront from buyers (1 Feb 2025); no calendar-spread margin benefit on expiry day (Feb 2025); intraday position-limit snapshots (1 Apr 2025); index-option limits net Rs 1,500 crore / gross Rs 10,000 crore end of day, net Rs 5,000 crore intraday (1 Oct 2025) | same | SEBI/HO/MRD/TPD-1/P/CIR/2024/132, 2025/79, 2025/122 |
| margin, one lot | long: the premium (Rs 150 x 65 = Rs 9,750); naked short ATM about Rs 1.3-1.7 lakh (estimate) | long Rs 400 x 30 = Rs 12,000; short ATM about Rs 1.5-2.0 lakh (estimate) | broker calculators, not verified for today |
| circuit | market-wide halts on NIFTY or SENSEX at 10% / 15% / 20% of the previous close (10% before 13:00: 45 min halt; 13:00-14:30: 15 min; after 14:30 none; 15% before 13:00: 1 h 45 min; 20%: the day). Options have no fixed band, a 10% operating range that flexes | same | NSE circuit-breaker page (22 Oct 2026 limits: 2,281 / 3,422 / 4,562 points) |

**Charges on one lot, round trip, discount broker (Zerodha rates on 22 Oct 2026):** brokerage Rs 20 an order, STT
**0.15%** of the sell-side premium (since 1 Apr 2026; 0.1% from 1 Oct 2024), NSE transaction charge 0.03553% of
premium, SEBI Rs 10 a crore, stamp 0.003% on the buy, GST 18% on brokerage + exchange + SEBI.

| example | premium x lot | brokerage | exchange | STT | GST and rest | total | in premium points |
|---|---|---|---|---|---|---|---|
| NIFTY ATM Rs 150 x 65 | Rs 9,750 a side | 40.00 | 6.93 | 14.62 | 8.76 | **Rs 70.32** | 1.08 |
| BANKNIFTY ATM Rs 400 x 30 | Rs 12,000 a side | 40.00 | 8.53 | 18.00 | 9.12 | **Rs 75.65** | 2.52 |

R16's "ATM round trip about Rs 98 NIFTY / Rs 133 BANKNIFTY" (FINDINGS.md) adds the half-spread of h24 (0.16% of
premium each way) on the old lots; with today's lots the charges alone are Rs 70-76, the spread Rs 30-40 more.
**Note for the repo:** `research/obuy/config.py` LOT_SCHEDULE still carries BANKNIFTY 35 after Jun 2025 and has no
Jan 2026 row; the lot in force since the 27 Jan 2026 monthly is 30 (NSE/FAOP/70616).

### 1.6 Our expiry calendar, from the data

The bhavcopy rows give the nearest expiry of every trading day, so the Thursday -> Wednesday -> Tuesday changes need
no table: NIFTY had 302 expiry days from Jan 2021 to 13 Oct 2026 (66 monthly), BANKNIFTY 225 (62 monthly; after
13 Nov 2024 only the monthly ones). The weekday counts match the circulars (NIFTY 2025: 17 Tuesdays, 33 Thursdays;
BANKNIFTY 2024: 41 Wednesdays).

## 2. How they move (descriptive; design period unless stated)

### 2.1 Daily returns, 2006 - 14 Jul 2026 (close to close, %)

| | NIFTY | BANKNIFTY |
|---|---|---|
| n days | 5,087 | 5,086 |
| mean / median | +0.051 / +0.074 | +0.065 / +0.077 |
| sd (annualised) | 1.316 (20.9%) | 1.734 (27.5%) |
| 1st / 99th percentile | -3.77 / +3.60 | -4.82 / +4.86 |
| worst / best day | -12.98 (23 Mar 2020) / +17.74 (18 May 2009) | -16.73 (23 Mar 2020) / +18.82 |
| skew / kurtosis | -0.04 / 14.1 | +0.07 / 8.5 |
| up days | 53.7% | 52.8% |
| days with a move of 1% or more / 2% or more | 29.8% / 9.2% | 41.1% / 17.4% |
| open-to-close mean | **-0.035** | **-0.039** |

The drift lives overnight: the close-to-close mean is positive and the open-to-close mean is negative on both
indices over 20 years (n 5,087). A day-trader who is flat overnight has been fighting the drift.

By year (sd of the daily return, %, with the year's worst and best day):

| year | NIFTY sd | ann. vol | up | worst | best | BANKNIFTY sd | ann. vol | up | worst | best |
|---|---|---|---|---|---|---|---|---|---|---|
| 2019 | 0.869 | 13.8% | 53% | -2.14 | +5.32 (20 Sep) | 1.260 | 20.0% | 52% | -2.77 | +8.31 |
| 2020 | 1.967 | 31.2% | 59% | -12.98 | +8.76 | 2.712 | 43.1% | 55% | -16.73 | +10.51 |
| 2021 | 0.986 | 15.7% | 56% | -3.76 | +4.74 (1 Feb) | 1.451 | 23.0% | 50% | -5.10 | +8.26 |
| 2022 | 1.092 | 17.3% | 50% | -4.78 (24 Feb) | +3.03 | 1.332 | 21.2% | 54% | -5.79 | +4.00 |
| 2023 | 0.618 | 9.8% | 57% | -1.61 | +2.07 | 0.802 | 12.7% | 55% | -3.13 | +3.61 |
| 2024 | 0.879 | 14.0% | 55% | -5.93 (4 Jun) | +3.36 (5 Jun) | 1.100 | 17.5% | 53% | -7.95 | +4.53 |
| 2025 | 0.744 | 11.8% | 50% | -3.24 (7 Apr) | +3.82 (12 May) | 0.789 | 12.5% | 54% | -3.19 | +3.34 |
| 2026 to 14 Jul | 1.051 | 16.7% | 52% | -3.26 (19 Mar) | +3.78 (8 Apr) | 1.356 | 21.5% | 52% | -3.82 | +5.67 |

Each year n = 245-252 days (130 for 2026). 2023 and 2025 were the quietest years in the sample; 2026 to July was
busier than any year since 2022.

The holdout quarter (15 Jul - 19 Oct 2026, n 66 days, read after the predictive tests): NIFTY -4.94% over the
quarter, sd 0.72%, up on 40% of days, median day range 0.70%; BANKNIFTY -3.93%, sd 0.75%, up 42%, median range
0.86%; India VIX averaged 12.4 (10.4 - 15.3). A quiet, drifting-down quarter.

### 2.2 Realised vs implied volatility

India VIX is a 30-day NIFTY number, so for BANKNIFTY it is only a reference. Compared with the realised volatility
of the NEXT 21 days (annualised close-to-close sd), n 4,507 days from Nov 2007:

| | NIFTY | BANKNIFTY |
|---|---|---|
| median VIX / forward realised | **1.28** | 0.93 |
| days VIX above forward realised | **81%** | 40% |
| correlation VIX vs forward realised | 0.75 | 0.77 |

VIX over-states NIFTY's coming month on four days in five (the volatility risk premium that short-option sellers
collect, and that long-option buyers pay). For BANKNIFTY the same number under-states realised vol on 60% of days,
because BANKNIFTY's realised vol is about 1.3x NIFTY's.

By year (close-to-close realised, Parkinson high-low realised, VIX mean, VIX / forward realised median):

| year | NIFTY RV cc | RV Parkinson | VIX mean (min-max) | VIX / fwd RV | BANKNIFTY RV cc | RV Parkinson | VIX / fwd RV |
|---|---|---|---|---|---|---|---|
| 2021 | 15.7 | 11.3 | 18.0 (11.7-28.1) | 1.26 | 23.0 | 17.3 | 0.87 |
| 2022 | 17.3 | 11.5 | 19.3 (12.9-32.0) | 1.19 | 21.2 | 15.3 | 1.01 |
| 2023 | 9.8 | 7.7 | 12.5 (10.1-17.7) | 1.29 | 12.7 | 10.5 | 1.01 |
| 2024 | 14.0 | 9.7 | 14.7 (10.2-26.8) | 1.12 | 17.5 | 12.5 | 0.97 |
| 2025 | 11.8 | 8.4 | 13.4 (9.2-22.8) | 1.35 | 12.5 | 9.7 | 1.32 |
| 2026 to 14 Jul | 16.7 | 10.7 | 16.2 (9.2-27.9) | 1.01 | 21.5 | 13.4 | 0.89 |

The Parkinson (high-low) measure runs well below the close-to-close measure on both indices every year: the
overnight gap is a big part of daily variance (section 2.4).

**VIX regimes** (terciles of VIX over 2007-2026, fixed cuts 15.05 and 19.97, n 1,509 days each):

| regime | VIX | NIFTY RV cc | NIFTY median day range | NIFTY median abs return | BANKNIFTY RV cc | BANKNIFTY median range | BANKNIFTY median abs return |
|---|---|---|---|---|---|---|---|
| low | 9.2 - 15.0 | 10.0% | **0.75%** | 0.41% | 13.1% | **1.01%** | 0.48% |
| mid | 15.1 - 20.0 | 14.6% | 1.02% | 0.56% | 20.8% | 1.42% | 0.75% |
| high | 20.0 - 85.1 | 29.3% | **1.72%** | 0.94% | 39.1% | **2.37%** | 1.31% |

A high-VIX day has about 2.3x the range of a low-VIX day on both indices. This is the one filter that has
always worked for sizing a stop or a target; direction it never gave (h42 #12, R16).

### 2.3 The day's shape: when the range is made (1-minute lake; NIFTY Apr 2017 - 14 Jul 2026, n 2,288 full days;
BANKNIFTY Sep 2021 - 14 Jul 2026, n 1,185)

Median day range (high-low over the 09:15 open): NIFTY **0.907%** (mean 1.10, p5 0.44, p95 2.20), BANKNIFTY
**1.131%** (mean 1.29, p5 0.53, p95 2.62).

Share of the day's final range already made by the clock (median over days, and the share of days whose range was
already complete):

| by | NIFTY median share | NIFTY range complete | BANKNIFTY median share | BANKNIFTY range complete |
|---|---|---|---|---|
| 09:30 | 40% | 0.3% | 43% | 0.5% |
| 10:30 | **61%** | 5% | **65%** | 7% |
| 13:00 | 82% | 20% | 87% | 27% |
| 14:30 | 97% | 44% | 100% | 53% |

Range inside each window (median, % of the open), the window's share of the whole day's range, where the day's high
and low fall, and the range per minute:

| window | NIFTY range | share of day | day high here | day low here | bp per minute | BANKNIFTY range | share of day | high here | low here | bp per minute |
|---|---|---|---|---|---|---|---|---|---|---|
| 09:15-09:30 | 0.348% | 40% | 28.7% | 23.9% | **2.32** | 0.463% | 43% | 28.9% | 27.9% | **3.09** |
| 09:30-10:30 | 0.385% | 43% | 14.3% | 16.9% | 0.64 | 0.520% | 46% | 14.9% | 17.2% | 0.87 |
| 10:30-13:00 | 0.436% | 49% | 14.6% | 17.6% | 0.29 | 0.567% | 52% | 17.3% | 17.6% | 0.38 |
| 13:00-14:30 | 0.379% | 43% | 13.1% | 14.5% | 0.42 | 0.445% | 41% | 14.4% | 14.7% | 0.50 |
| 14:30-15:30 | 0.357% | 40% | 29.4% | 27.1% | 0.59 | 0.422% | 37% | 24.5% | 22.6% | 0.70 |

Read it as: the first 15 minutes and the last hour each hold the day's high or low about as often as the whole
middle of the day; minute for minute the open is 8x the midday rate on both indices. The 09:15 bar alone spans a
median 0.213% on NIFTY (p95 0.52%) and 0.295% on BANKNIFTY (p95 0.63%); the first five minutes 0.271% / 0.377%.
The open (first five minutes) is the day's high or low on 41% of NIFTY days and 46% of BANKNIFTY days. The high comes
before the low on 52% / 50% of days (no trend-day bias either way). The close sits in the top or bottom tenth of the
day's range on 26% / 24% of days.

By year, the median day range and the median first-15-minute range: NIFTY 2023 0.72% / 0.28%, 2024 0.86% / 0.30%,
2025 0.81% / 0.33%, 2026 1.03% / 0.42%; BANKNIFTY 2023 0.99% / 0.38%, 2024 1.12% / 0.45%, 2025 0.95% / 0.44%,
2026 1.26% / 0.56%. The first 15 minutes have taken a growing share: 47% (NIFTY) and 55% (BANKNIFTY) of the day's
range in the holdout quarter (n 67 days), against 40% and 43% over the design period.

Weekday makes no difference to the shape: NIFTY's median range is 0.89-0.94% on every weekday (n 451-459 each),
BANKNIFTY's 1.04-1.17% (n 233-238). Expiry days are not wider: NIFTY 0.92% on 289 expiry days vs 0.91% on 1,999
others; BANKNIFTY 1.17% (185) vs 1.12% (1,000). BANKNIFTY's expiry last hour is a little wider (0.467% vs 0.403%).

### 2.4 Gaps (daily lake 2006 - 14 Jul 2026, gap = open / previous close - 1)

| | NIFTY (n 5,086) | BANKNIFTY (n 5,085) |
|---|---|---|
| gap sd / median | 0.545% / +0.05% | 0.873% / +0.12% |
| days with a gap of at least 0.25% / 0.5% / 1% / 2% | 43% / **20%** / 5.2% / 1.2% | 61% / **34%** / 13.2% / 3.5% |
| gap up filled by the close (any size, low touches previous close) | 68.9% (n 3,267) | 68.4% (n 3,121) |
| gap down filled by the close | 67.7% (n 1,775) | 66.8% (n 1,958) |

Fill rate by size (the day trades back to the previous close), and how the day closed:

| gap size | NIFTY up: n / filled / closed below prev close | NIFTY down: n / filled / closed above | BANKNIFTY up | BANKNIFTY down |
|---|---|---|---|---|
| 0-0.25% | 1,773 / 83% / 45% | 1,073 / 81% / 42% | 1,169 / 87% / 46% | 819 / 85% / 44% |
| 0.25-0.5% | 863 / 61% / 31% | 336 / 61% / 26% | 903 / 69% / 37% | 447 / 69% / 34% |
| 0.5-1% | 484 / **42%** / 19% | 248 / 41% / 23% | 687 / 53% / 26% | 384 / 49% / 27% |
| 1-2% | 120 / 28% / 14% | 86 / 24% / 12% | 276 / 37% / 20% | 218 / 39% / 22% |
| 2% and more | 27 / 19% / 4% | 32 / 13% / 6% | 86 / 34% / 21% | 90 / 29% / 19% |

A 2%-plus gap closes on the gap's own side of the previous close 94-96% of the time on NIFTY: big gaps are
information, not noise. On the minute lake (gaps of at least 0.25%, NIFTY 2017-2026 n 1,245, BANKNIFTY 2021-2026
n 694): 48% / 52% fill at some point in the day; of those that fill, the median fill comes 52 / 32 minutes after the
open; 12% / 19% are filled by 09:30, 27% / 32% by 10:30, 38% / 45% by 13:00. MARKET_HOW.md §3d and h42 #2-#3 found
the same shape on their windows; trading the fill lost money there (Rs 106-363 a trade) and the pre-registered fade
rules below did not pass either.

### 2.5 Trend or mean reversion, by horizon (lag-1 autocorrelation of non-overlapping returns; descriptive)

| horizon | NIFTY n | NIFTY lag-1 autocorr | "follows previous sign" | BANKNIFTY n | lag-1 autocorr | follows |
|---|---|---|---|---|---|---|
| 1 minute | 853,718 | +0.027 | 50.1% | 451,625 | +0.007 | 49.5% |
| 5 minutes | 167,082 | +0.006 | 48.4% | 88,643 | +0.009 | 48.3% |
| 15 minutes | 52,640 | +0.031 | 48.6% | 27,929 | 0.000 | 48.2% |
| 1 hour | 9,154 | +0.055 | 50.6% | 4,869 | +0.030 | 49.3% |
| 1 day (2006-) | 5,086 | +0.028 | 53.2% | 5,085 | +0.091 | 53.1% |
| 1 week | 1,070 | +0.021 | 53.3% | 1,070 | -0.032 | 51.4% |

Intraday bars are inside the session only (no bar spans the open). At 5 and 15 minutes both indices lean very
slightly to reversal (the next bar has the previous bar's sign 48% of the time, not 50%); at one hour and one day
they lean slightly to continuation. The sizes are tiny; the pre-registered version of this table (section 4.2) shows
what survives a proper test: nothing tradeable. The first 30 minutes' direction (open to 09:44 close) continues to
the close on 53.0% of NIFTY days (n 2,286) and 50.4% of BANKNIFTY days (n 1,185); the first hour 52.5% / 49.9%.
MARKET_HOW.md found 49-54% on all five indices; "72% green days after a green first half hour" (FINDINGS) is the
mechanical part the first half hour itself contributes.

### 2.6 Calendar: weekday, expiry, events (daily returns, %; descriptive)

Weekday, 2006 - 14 Jul 2026 (n about 1,000-1,019 per weekday per index):

| weekday | NIFTY mean | up | median range | BANKNIFTY mean | up | median range |
|---|---|---|---|---|---|---|
| Mon | +0.006 | 55.7% | 1.16% | +0.022 | 54.8% | 1.56% |
| Tue | +0.072 | 53.5% | 1.11% | +0.074 | 51.8% | 1.52% |
| Wed | +0.095 | 54.2% | 1.08% | +0.091 | 51.3% | 1.51% |
| Thu | +0.019 | 52.8% | 1.14% | +0.047 | 53.8% | 1.52% |
| Fri | +0.063 | 51.9% | 1.14% | +0.089 | 52.3% | 1.61% |

Since 2021 (n 270-276 each): Tuesday +0.114 (NIFTY) / +0.148 (BANKNIFTY) and Friday -0.024 / -0.069 look
different, but the pre-registered weekday tests (section 4.2) give every weekday a BH q of 0.43 or worse. h42 found
the same ("0 of 60 weekday questions survive"). Day of week is not a rule.

Expiry days since Jan 2021 (NIFTY 289 expiry days, BANKNIFTY 222, against 1,081 / 1,148 other days):

| | NIFTY expiry | NIFTY other | BANKNIFTY expiry | BANKNIFTY other |
|---|---|---|---|---|
| mean return | -0.015% | +0.059% | -0.079% | +0.076% |
| up days | 49.5% | 54.6% | 46.4% | 54.4% |
| median range | 0.93% | 0.90% | 1.23% | 1.18% |
| median abs return | 0.51% | 0.50% | 0.55% | 0.57% |

The expiry-day return looks lower (BANKNIFTY t -1.0; n 222), but the pre-registered test gives q 0.80 / 1.00; and
expiry days are not wider. Monthly expiries alone: NIFTY -0.116% (n 63), BANKNIFTY -0.094% (n 59); the day before an
expiry +0.062% / +0.060%. All inside noise.

Event sessions (R98's list, the NSE session that first trades on the news; 2021-2026):

| event | NIFTY n | mean return | up | median range | normal days' range | BANKNIFTY mean | up | median range | normal range |
|---|---|---|---|---|---|---|---|---|---|
| RBI policy day | 30 | **+0.36%** (t 2.0) | 53% | 0.86% | 0.88% | **+0.50%** (t 2.1) | 67% | 1.26% | 1.14% |
| FOMC (next session) | 39 | -0.02% | 51% | 0.93% | 0.89% | -0.08% | 51% | 1.21% | 1.14% |
| US CPI (next session) | 58 | +0.10% | 59% | 0.94% | 0.88% | +0.07% | 59% | 1.10% | 1.14% |
| Union Budget | 6 | -0.20% | 17% | **2.13%** | 0.86% | -0.28% | 33% | **2.62%** | 1.08% |
| election result 2024 (3 and 4 Jun) | 2 | -1.34% | | 4.69% | | -1.94% | | 5.56% | |

RBI days have been up days on average (30 of them, +0.36% / +0.50%), but 30 is a small sample and the ranges are
normal; EVENT_SHIELD.md found BANKNIFTY's range 1.48x on RBI-plus-Budget days (15 sessions). The pre-registered "US
event session" test (FOMC + CPI together, n 96) found nothing (q 1.00). Budget day is the one event that reliably
doubles the range (n 6). Results season (15th of Jan / Apr / Jul / Oct to the 15th of the next month, an approximate
window): NIFTY median range 1.15% in season vs 1.11% out (n 1,764 / 3,323); BANKNIFTY 1.60% vs 1.51%. A small
widening, nothing to trade.

## 3. Options structure (the rolling ATM +-10 books, Aug 2021 - 14 Jul 2026; the bhavcopy chain for OI)

The option lake is Dhan's rolling series: at each minute the contract that is ATM+k for the nearest expiry (so the
strike follows spot, and BANKNIFTY's "nearest expiry" is the monthly after 13 Nov 2024). "ATM straddle" below is the
rolling ATM call plus put, i.e. the time value at the money at that minute, not one fixed contract held all day.

### 3.1 What the 09:20 ATM straddle prices, and how often the day beats it

| | NIFTY | BANKNIFTY |
|---|---|---|
| n days with a 09:20 straddle | 1,222 | 1,190 |
| straddle at 09:20, % of spot: median (p25-p75) | **0.96%** (0.69-1.28) | **1.49%** (1.01-2.21) |
| by year: 2022 / 2023 / 2024 / 2025 / 2026 | 1.23 / 0.79 / 0.99 / 0.88 / 1.08 | 1.58 / 1.02 / 1.34 / **2.15** / **2.78** |
| by days to expiry: 0 / 1 / 2 / 3 / 6 | 0.50 / 0.79 / 1.00 / 1.16 / 1.37 | 0.68 / 1.10 / 1.36 / 1.75 / 1.72 |
| close-to-09:20 move exceeds the straddle: all days | 14.2% | 11.4% |
| ... non-expiry days | **7.1%** (n 961) | **6.5%** (n 1,000) |
| ... expiry days | **40.5%** (n 259) | **37.8%** (n 185) |
| the day's furthest excursion from 09:20 exceeds the straddle: non-expiry / expiry | 13.7% / 68.3% | 11.3% / 69.2% |
| median move / straddle: non-expiry / expiry | 0.36 / 0.83 | 0.25 / 0.77 |

Since BANKNIFTY lost its weeklies the 09:20 straddle is a monthly-expiry straddle most days, so its price doubled
(2.15% of spot in 2025, 2.78% in 2026) and the close beat it on only 2.4% of 2025 days and 2.3% of 2026 days. The
NIFTY weekly straddle stayed near 1% of spot and is beaten about one day in seven. On expiry day both indices beat
the straddle about 40% of the time at the close and about 69% at the day's extreme: the expiry-day straddle is
cheap relative to the day's range, which is the whole hero-zero story (R98) and also why it loses: the move has to
be caught, and 97% of cheap options still end below cost (R98 §5). This matches FINDINGS ("the index moved only 0.69x
what the straddle priced") and R89's straddle tests (0 of 18 pass).

### 3.2 Theta by hour: the rolling ATM straddle as a share of its 09:20 value (median over days)

| time | NIFTY expiry (n 259) | NIFTY 1 day before (241) | NIFTY 2-4 days before (441) | NIFTY other (963) | BANKNIFTY expiry (186) | BANKNIFTY 1 day before (174) | BANKNIFTY other (1,004) |
|---|---|---|---|---|---|---|---|
| 09:15 | 1.045 | 1.015 | 1.006 | 1.009 | 1.047 | 1.015 | 1.005 |
| 09:30 | 0.963 | 0.991 | 0.990 | 0.991 | 0.961 | 0.983 | 0.992 |
| 09:45 | 0.931 | 0.976 | 0.983 | 0.984 | 0.932 | 0.971 | 0.987 |
| 10:30 | **0.844** | 0.947 | 0.966 | 0.965 | **0.848** | 0.942 | 0.974 |
| 12:00 | 0.702 | 0.906 | 0.937 | 0.937 | 0.727 | 0.912 | 0.957 |
| 13:00 | **0.608** | 0.881 | 0.923 | 0.924 | **0.609** | 0.880 | 0.948 |
| 13:30 | 0.557 | 0.867 | 0.913 | 0.915 | 0.554 | 0.868 | 0.941 |
| 14:30 | **0.395** | 0.831 | 0.897 | 0.896 | **0.379** | 0.830 | 0.925 |
| 15:00 | 0.221 | 0.808 | 0.883 | 0.884 | 0.231 | 0.804 | 0.921 |
| 15:15 | **0.128** | 0.794 | 0.874 | 0.876 | **0.114** | 0.794 | 0.914 |
| 15:29 | 0.164 | 0.780 | 0.861 | 0.864 | 0.150 | 0.776 | 0.903 |

Expiry day loses about 16% of the 09:20 straddle by 10:30, 39% by 13:00, 60% by 14:30 and 87% by 15:15 on both
indices (the 15:29 print is above 15:15 because the rolling ATM carries intrinsic value in the last minutes). A
non-expiry day loses 3.5% by 10:30 and 12-14% by the close (NIFTY) / 10% (BANKNIFTY); the day before expiry 22%.
EXPIRY_PLAYBOOK.md and h42 #9-#10 measured the same clock on fixed strikes (82-88% lost by 15:09 on expiry, 11-13%
on other days). The first-15-minute "IV crush" is real but small on a normal day: the straddle is 0.991 of its 09:20
value at 09:30, 1.009 at 09:15.

### 3.3 The first 15 minutes and the last hour, in premium

Rolling ATM straddle change (median; share of days it fell), NIFTY / BANKNIFTY:

| window | non-expiry NIFTY | non-expiry BANKNIFTY | expiry NIFTY | expiry BANKNIFTY |
|---|---|---|---|---|
| 09:15 -> 09:20 | -0.87% (63% down) | -0.53% (59%) | -4.35% (72%) | -4.46% (77%) |
| 09:15 -> 09:30 | **-1.83%** (69%) | -1.21% (66%) | **-8.04%** (79%) | -7.72% (81%) |
| 14:30 -> 15:29 | **-3.57%** (77%) | -2.26% (76%) | **-59.3%** (89%) | -61.6% (85%) |
| 15:00 -> 15:29 | -2.21% (71%) | -1.63% (75%) | -29.6% (67%) | -35.0% (65%) |
| n days | 962 | 999-1,027 | 259 | 185-192 |

A single ATM option moves a median 3.3% (NIFTY) / 2.6% (BANKNIFTY) per minute in the first 15 minutes (n 36,652 /
35,740 contract-minutes), 2.3% / 1.7% per minute in the last hour of a normal day, and **13.5% / 15.9% per minute in
the last hour of expiry day** (n 30,562 / 22,656). The first 15 minutes are where a buyer pays the most for
movement; the last hour of expiry is where an option is a lottery ticket.

### 3.4 Skew, ATM IV and the (crude) term structure, at 09:20

| | NIFTY | BANKNIFTY |
|---|---|---|
| n days | 1,222 | 1,190 |
| ATM IV (Dhan's %), median (p25-p75) | 16.1 (12.7-20.9) | 19.1 (14.6-26.2) |
| ATM IV median on non-expiry / expiry days | 15.0 / 22.6 | 17.4 / 31.9 |
| put ATM-5 IV minus call ATM+5 IV, median (points) | **+2.56** | **+2.11** |
| days the put side is dearer | 91% | 90% |
| ATM IV minus India VIX, median: non-expiry / expiry | +0.3 / +7.7 | +2.9 / +16.2 |
| ... by days to expiry 1 / 2 / 3 / 6 | +2.2 / +1.1 / +0.8 / -1.4 | +7.0 / +6.0 / +5.7 / +2.1 |

Puts five strikes out are dearer than calls five strikes out on nine days in ten: the chain always prices a crash
tail more than a melt-up. The nearest-expiry ATM IV sits above the 30-day VIX by 0.3 points on a normal NIFTY day
and by 7.7 points on expiry day (16 points for BANKNIFTY's monthly expiry): the term structure is flat to slightly
inverted at the front and spikes on the last day, which is the event-vol the decay clock in 3.2 then burns. Six days
out the NIFTY weekly is 1.4 points BELOW VIX (the calm week-ahead discount). At 15:15 on expiry day the ATM IV print
is 6.3 (NIFTY) / 14.4 (BANKNIFTY): the model breaks down on the last minutes, do not read IV there.

### 3.5 PCR, max pain, OI walls (end-of-day bhavcopy, nearest expiry, Jan 2021 - 14 Jul 2026, n 1,363 days each)

| | NIFTY | BANKNIFTY |
|---|---|---|
| PCR of OI, median (p5-p95) | **0.86** (0.52-1.34) | **0.86** (0.54-1.31) |
| PCR by year 2022 / 2024 / 2026 (median) | 0.81 / 0.87 / 0.90 | 0.87 / 0.85 / 0.90 |
| max pain minus spot, median (p5-p95) | -0.04% (-0.59 to +0.69) | -0.01% (-0.90 to +1.56) |
| call wall (largest call OI) above spot, median | +1.48% | +1.42% |
| put wall below spot, median | -1.14% | -0.88% |
| spot between the two walls | 92% of days | 77% |
| expiry close vs the previous day's max pain: median distance | **0.60%** (n 289) | **0.68%** (n 222) |
| expiry close within one strike step of the previous day's max pain | 17.6% | 21.2% |
| next-day return after PCR in the top decile (>= 1.24 / 1.19): mean, n | +0.22%, 137 | +0.29%, 137 |
| ... bottom decile (<= 0.58 / 0.60) | +0.04%, 137 | -0.04%, 137 |
| ... middle | +0.02%, 1,088 | +0.03%, 1,088 |

Max pain is not a magnet: the expiry close lands a median 0.60% / 0.68% from it, within one strike only 18-21% of
the time; R16 and EXPIRY_PLAYBOOK found "max pain is worse than no change" as a forecast. The high-PCR decile's
next-day mean (+0.22% / +0.29%, n 137) is the one hint in this table and it is descriptive; h42 and R47 tested PCR
extremes as trades and they did not survive BH (R47's "PCR extreme at 10:15 -> ITM3" was q 0.50). BANKNIFTY's PCR
distribution narrowed after the weeklies went (2025 sd 0.17 vs 0.27 in 2022): a monthly chain carries more hedging
OI, so PCR says even less.

## 4. Participants (NSE participant-wise OI, 2 Jan 2012 - 14 Jul 2026, n 3,577 days; NSDL FPI cash flows)

### 4.1 Who holds what (descriptive)

Net position = (long - short) / (long + short) of the group's own index-futures book; the option measure is
(call long + put short - call short - put long) over the group's four index-option legs.

| measure | 2022 | 2023 | 2024 | 2025 | 2026 to 14 Jul | all 2012-2026 median |
|---|---|---|---|---|---|---|
| FII net index futures | -0.14 | -0.19 | -0.06 | **-0.61** | **-0.72** | +0.12 |
| FII net index futures, contracts (mean) | -21,583 | -34,164 | -983 | -140,111 | -204,132 | +23,285 |
| Client net index futures | +0.16 | +0.08 | +0.08 | **+0.30** | **+0.46** | -0.00 |
| Pro net index futures | -0.08 | -0.08 | -0.10 | -0.01 | +0.06 | -0.19 |
| DII net index futures | -0.40 | +0.07 | -0.18 | +0.31 | +0.51 | -0.02 |
| FII net index options | -0.07 | -0.05 | -0.03 | -0.09 | -0.22 | -0.12 |
| FII share of index-futures OI | 22% | 24% | 28% | 29% | 36% | 33% |
| Client share of index-futures OI | 56% | 52% | 47% | 42% | 38% | 47% |
| Client share of index-option longs | 65% | 64% | 61% | 58% | 58% | 53% |
| Pro share of index-option shorts | 24% | 22% | 22% | 24% | 23% | 24% |
| FII share of index-option shorts | 8% | 11% | 13% | 12% | 15% | 14% |
| FPI cash, net Rs crore for the year (share of days net buying) | -146,048 (39%) | +127,760 (57%) | -121,210 (44%) | -240,193 (36%) | -288,081 (34%) | |

Through 2025 and the first half of 2026 FIIs carried their most short index-futures book in the 14 years of the
file (-61% and -72% of their book, -1.4 to -2.0 lakh contracts) while clients carried their longest (+30%, +46%).
Clients own 58% of index-option longs; prop desks and FIIs are the writers. FPIs sold Rs 2.4 lakh crore of cash
equity in 2025 and Rs 2.9 lakh crore in the first half of 2026, buying on only a third of days. OF1 / R86 described
the same picture ("FII trend-follow, Client takes the other side, Client and Pro are the net option writers").

### 4.2 Does any of it predict? The 70 pre-registered tests (B1-B4), design then the one holdout read

Each row is a sign rule with no fitted parameter, effect in bps of the index per trade (positive = the rule's side
was right), 95% CI, t, hit rate, years with the same sign, 1,000 random twins, BH q over all 70, the cost of one ATM
option round trip in index bps (R16's Rs 98 / Rs 133 through delta 0.5 and today's lots at the design-end close), the
design verdict (q <= 0.10 and the sign in at least 70% of years), then the holdout (15 Jul - 19 Oct 2026). B2 signals
are read from the participant file of day t, usable from the open of t+1; return = close t to close t+1 (or t+5).

| test | n | effect bps | 95% CI | t | hit | years same sign | twin p | BH q | cost bps | design | holdout n | holdout effect (95% CI) | holdout p | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| B1-NIFTY-1m | 853,718 | 0.11 | 0.10, 0.12 | 26.45 | 50.0% | 10/10 | 0.000 | 0.000 | 1.25 | PASS | 24,991 | 0.08 (0.06, 0.11) | 0.000 | **confirmed, an artefact** |
| B1-NIFTY-5m | 167,082 | -0.00 | -0.04, 0.04 | -0.11 | 48.3% | 5/10 | 0.911 | 0.974 | 1.25 | fail | 4,891 | 0.03 (-0.11, 0.18) | 0.686 | - |
| B1-NIFTY-15m | 52,640 | 0.04 | -0.08, 0.16 | 0.63 | 48.5% | 5/10 | 0.549 | 0.932 | 1.25 | fail | 1,541 | -0.12 (-0.59, 0.35) | 0.603 | - |
| B1-NIFTY-1h | 9,154 | 0.88 | 0.28, 1.48 | 2.89 | 50.5% | 10/10 | 0.005 | 0.044 | 1.25 | PASS | 268 | 0.42 (-1.71, 2.54) | 0.691 | not confirmed |
| B1-NIFTY-1d | 5,086 | 5.77 | 2.16, 9.39 | 3.13 | 53.2% | 18/21 | 0.003 | 0.030 | 1.25 | PASS | 65 | -9.44 (-27.09, 8.22) | 0.433 | **reversed** |
| B1-NIFTY-1w | 1,070 | 3.73 | -12.63, 20.09 | 0.45 | 53.3% | 12/21 | 0.674 | 0.974 | 1.25 | fail | 13 | too few |  | - |
| B1-BANKNIFTY-1m | 451,625 | 0.04 | 0.03, 0.06 | 6.71 | 49.5% | 4/6 | 0.000 | 0.000 | 1.54 | fail | 24,991 | 0.06 (0.03, 0.10) | 0.002 | - |
| B1-BANKNIFTY-5m | 88,643 | -0.04 | -0.10, 0.02 | -1.32 | 48.3% | 4/6 | 0.158 | 0.526 | 1.54 | fail | 4,891 | 0.07 (-0.11, 0.25) | 0.475 | - |
| B1-BANKNIFTY-15m | 27,929 | -0.01 | -0.20, 0.17 | -0.11 | 48.2% | 3/6 | 0.913 | 0.974 | 1.54 | fail | 1,541 | -0.04 (-0.61, 0.53) | 0.855 | - |
| B1-BANKNIFTY-1h | 4,869 | 0.73 | -0.16, 1.63 | 1.61 | 49.3% | 4/6 | 0.104 | 0.428 | 1.54 | fail | 268 | -0.17 (-2.67, 2.34) | 0.867 | - |
| B1-BANKNIFTY-1d | 5,085 | 12.39 | 7.63, 17.15 | 5.11 | 53.1% | 21/21 | 0.000 | 0.000 | 1.54 | PASS | 65 | -24.59 (-41.90, -7.28) | 0.070 | **reversed** |
| B1-BANKNIFTY-1w | 1,070 | -2.29 | -25.76, 21.18 | -0.19 | 51.4% | 10/21 | 0.894 | 0.974 | 1.54 | fail | 13 | too few |  | - |
| B2-NIFTY-FII-fut-level-r1 | 3,456 | 2.21 | -1.15, 5.57 | 1.29 | 51.3% | 9/15 | 0.230 | 0.578 | 1.25 | fail | 63 | 6.63 (-10.45, 23.71) | 0.534 | - |
| B2-NIFTY-FII-fut-level-r5 | 3,452 | 3.76 | -3.79, 11.30 | 0.98 | 49.9% | 7/15 | 0.690 | 0.974 | 1.25 | fail | 62 | 13.48 (-26.44, 53.40) | 0.694 | - |
| B2-NIFTY-FII-fut-change-r1 | 3,575 | 4.48 | 1.17, 7.80 | 2.65 | 52.6% | 10/15 | 0.008 | 0.062 | 1.25 | fail | 63 | -8.83 (-25.85, 8.19) | 0.360 | - |
| B2-NIFTY-FII-fut-change-r5 | 3,571 | 0.84 | -6.60, 8.27 | 0.22 | 50.8% | 7/15 | 0.843 | 0.974 | 1.25 | fail | 62 | 1.23 (-38.83, 41.29) | 0.975 | - |
| B2-NIFTY-Client-fut-level-r1 | 3,456 | 1.06 | -2.31, 4.42 | 0.62 | 49.5% | 11/15 | 0.559 | 0.932 | 1.25 | fail | 63 | -9.66 (-26.65, 7.33) | 1.000 | - |
| B2-NIFTY-Client-fut-level-r5 | 3,452 | 7.14 | -0.40, 14.68 | 1.86 | 52.3% | 12/15 | 0.408 | 0.752 | 1.25 | fail | 62 | -43.55 (-82.09, -5.01) | 1.000 | - |
| B2-NIFTY-Client-fut-change-r1 | 3,575 | -5.15 | -8.47, -1.84 | -3.05 | 47.6% | 11/15 | 0.000 | 0.000 | 1.25 | PASS | 63 | 3.58 (-13.55, 20.72) | 0.656 | **reversed** |
| B2-NIFTY-Client-fut-change-r5 | 3,571 | -0.09 | -7.52, 7.35 | -0.02 | 48.8% | 6/15 | 0.990 | 0.990 | 1.25 | fail | 62 | -15.58 (-55.45, 24.29) | 0.593 | - |
| B2-NIFTY-Pro-fut-level-r1 | 3,456 | -2.10 | -5.46, 1.26 | -1.22 | 48.6% | 10/15 | 0.240 | 0.579 | 1.25 | fail | 63 | -10.35 (-27.32, 6.62) | 0.156 | - |
| B2-NIFTY-Pro-fut-level-r5 | 3,452 | -3.48 | -11.02, 4.07 | -0.90 | 49.9% | 10/15 | 0.649 | 0.974 | 1.25 | fail | 62 | -28.48 (-67.90, 10.93) | 0.566 | - |
| B2-NIFTY-Pro-fut-change-r1 | 3,575 | -2.01 | -5.33, 1.31 | -1.19 | 48.2% | 11/15 | 0.231 | 0.578 | 1.25 | fail | 63 | -12.24 (-29.13, 4.65) | 0.116 | - |
| B2-NIFTY-Pro-fut-change-r5 | 3,571 | -7.28 | -14.71, 0.15 | -1.92 | 49.2% | 13/15 | 0.044 | 0.220 | 1.25 | fail | 62 | -14.48 (-54.37, 25.42) | 0.454 | - |
| B2-NIFTY-FII-opt-level-r1 | 3,456 | 4.80 | 1.44, 8.16 | 2.80 | 53.2% | 11/15 | 0.011 | 0.070 | 1.25 | PASS | 63 | 7.52 (-9.54, 24.58) | 0.737 | not confirmed |
| B2-NIFTY-FII-opt-level-r5 | 3,452 | 10.29 | 2.75, 17.83 | 2.68 | 52.4% | 8/15 | 0.137 | 0.479 | 1.25 | fail | 62 | 35.98 (-3.05, 75.01) | 0.765 | - |
| B2-NIFTY-FII-opt-change-r1 | 3,574 | 4.66 | 1.34, 7.97 | 2.75 | 53.2% | 10/15 | 0.003 | 0.030 | 1.25 | fail | 63 | -0.09 (-17.25, 17.07) | 0.985 | - |
| B2-NIFTY-FII-opt-change-r5 | 3,570 | 7.36 | -0.08, 14.79 | 1.94 | 52.2% | 13/15 | 0.067 | 0.313 | 1.25 | fail | 62 | -13.88 (-53.79, 26.02) | 0.621 | - |
| B2-NIFTY-FPI-cash-sign-r1 | 3,497 | 0.35 | -3.02, 3.71 | 0.20 | 51.2% | 6/15 | 0.844 | 0.974 | 1.25 | fail | 62 | 4.29 (-13.05, 21.63) | 0.639 | - |
| B2-NIFTY-FPI-cash-sign-r5 | 3,493 | -7.71 | -15.22, -0.20 | -2.01 | 48.8% | 10/15 | 0.212 | 0.571 | 1.25 | fail | 61 | -10.64 (-51.08, 29.81) | 0.575 | - |
| B2-BANKNIFTY-FII-fut-level-r1 | 3,456 | 3.72 | -0.94, 8.38 | 1.57 | 49.6% | 11/15 | 0.135 | 0.479 | 1.54 | fail | 63 | -1.38 (-20.00, 17.24) | 0.915 | - |
| B2-BANKNIFTY-FII-fut-level-r5 | 3,452 | 8.84 | -1.85, 19.53 | 1.62 | 50.1% | 10/15 | 0.473 | 0.828 | 1.54 | fail | 62 | 0.20 (-34.44, 34.83) | 1.000 | - |
| B2-BANKNIFTY-FII-fut-change-r1 | 3,575 | 6.02 | 1.40, 10.63 | 2.56 | 52.0% | 11/15 | 0.012 | 0.070 | 1.54 | PASS | 63 | -14.10 (-32.39, 4.19) | 0.082 | **reversed** |
| B2-BANKNIFTY-FII-fut-change-r5 | 3,571 | 1.39 | -9.22, 11.99 | 0.26 | 50.0% | 7/15 | 0.810 | 0.974 | 1.54 | fail | 62 | -5.22 (-39.83, 29.39) | 0.771 | - |
| B2-BANKNIFTY-Client-fut-level-r1 | 3,456 | 1.04 | -3.62, 5.70 | 0.44 | 50.9% | 10/15 | 0.715 | 0.974 | 1.54 | fail | 63 | -6.58 (-25.13, 11.97) | 1.000 | - |
| B2-BANKNIFTY-Client-fut-level-r5 | 3,452 | 5.86 | -4.84, 16.55 | 1.07 | 51.5% | 10/15 | 0.660 | 0.974 | 1.54 | fail | 62 | -32.29 (-65.96, 1.38) | 1.000 | - |
| B2-BANKNIFTY-Client-fut-change-r1 | 3,575 | -8.20 | -12.81, -3.59 | -3.49 | 48.0% | 13/15 | 0.000 | 0.000 | 1.54 | PASS | 63 | 2.67 (-15.94, 21.29) | 0.659 | **reversed** |
| B2-BANKNIFTY-Client-fut-change-r5 | 3,571 | 0.33 | -10.28, 10.94 | 0.06 | 49.1% | 10/15 | 0.949 | 0.974 | 1.54 | fail | 62 | -6.71 (-41.30, 27.88) | 0.745 | - |
| B2-BANKNIFTY-Pro-fut-level-r1 | 3,456 | -0.65 | -5.31, 4.01 | -0.27 | 50.2% | 9/15 | 0.779 | 0.974 | 1.54 | fail | 63 | -9.74 (-28.20, 8.72) | 0.185 | - |
| B2-BANKNIFTY-Pro-fut-level-r5 | 3,452 | 1.61 | -9.09, 12.30 | 0.29 | 49.9% | 6/15 | 0.875 | 0.974 | 1.54 | fail | 62 | -33.30 (-66.91, 0.31) | 0.192 | - |
| B2-BANKNIFTY-Pro-fut-change-r1 | 3,575 | -2.28 | -6.90, 2.33 | -0.97 | 48.5% | 10/15 | 0.322 | 0.663 | 1.54 | fail | 63 | -10.60 (-29.04, 7.83) | 0.202 | - |
| B2-BANKNIFTY-Pro-fut-change-r5 | 3,571 | -6.19 | -16.80, 4.42 | -1.14 | 49.8% | 11/15 | 0.260 | 0.607 | 1.54 | fail | 62 | -10.33 (-44.87, 24.20) | 0.459 | - |
| B2-BANKNIFTY-FII-opt-level-r1 | 3,456 | 5.62 | 0.96, 10.28 | 2.37 | 51.1% | 9/15 | 0.016 | 0.086 | 1.54 | fail | 63 | 2.22 (-16.40, 20.83) | 0.918 | - |
| B2-BANKNIFTY-FII-opt-level-r5 | 3,452 | 13.38 | 2.69, 24.07 | 2.45 | 51.6% | 7/15 | 0.168 | 0.526 | 1.54 | fail | 62 | 26.10 (-7.90, 60.11) | 0.723 | - |
| B2-BANKNIFTY-FII-opt-change-r1 | 3,574 | 5.91 | 1.29, 10.52 | 2.51 | 52.1% | 10/15 | 0.009 | 0.063 | 1.54 | fail | 63 | -10.06 (-28.51, 8.40) | 0.258 | - |
| B2-BANKNIFTY-FII-opt-change-r5 | 3,570 | 9.55 | -1.06, 20.16 | 1.76 | 51.9% | 13/15 | 0.113 | 0.439 | 1.54 | fail | 62 | -12.96 (-47.44, 21.52) | 0.550 | - |
| B2-BANKNIFTY-FPI-cash-sign-r1 | 3,497 | 0.21 | -4.46, 4.89 | 0.09 | 49.3% | 7/15 | 0.932 | 0.974 | 1.54 | fail | 62 | 11.77 (-6.86, 30.41) | 0.149 | - |
| B2-BANKNIFTY-FPI-cash-sign-r5 | 3,493 | -2.19 | -12.93, 8.55 | -0.40 | 49.3% | 8/15 | 0.780 | 0.974 | 1.54 | fail | 61 | 2.08 (-32.95, 37.12) | 0.890 | - |
| B3-NIFTY-Mon | 1,014 | 0.56 | -9.15, 10.28 | 0.11 | 55.7% | 13/21 | 0.960 | 0.974 | 1.25 | fail | 13 | too few |  | - |
| B3-NIFTY-Tue | 1,019 | 7.18 | -0.21, 14.58 | 1.90 | 53.5% | 13/21 | 0.281 | 0.626 | 1.25 | fail | 13 | too few |  | - |
| B3-NIFTY-Wed | 1,019 | 9.49 | 2.14, 16.84 | 2.53 | 54.2% | 19/21 | 0.101 | 0.428 | 1.25 | fail | 13 | too few |  | - |
| B3-NIFTY-Thu | 1,011 | 1.85 | -5.67, 9.38 | 0.48 | 52.8% | 12/21 | 0.856 | 0.974 | 1.25 | fail | 14 | too few |  | - |
| B3-NIFTY-Fri | 1,001 | 6.26 | -2.10, 14.62 | 1.47 | 51.9% | 14/21 | 0.365 | 0.710 | 1.25 | fail | 13 | too few |  | - |
| B3-NIFTY-expiry | 289 | -1.52 | -11.19, 8.14 | -0.31 | 49.5% | 3/6 | 0.858 | 0.974 | 1.25 | fail | 13 | too few |  | - |
| B3-NIFTY-us-event | 96 | 4.83 | -13.88, 23.53 | 0.51 | 55.2% | 3/5 | 0.603 | 0.974 | 1.25 | fail | 5 | too few |  | - |
| B3-BANKNIFTY-Mon | 1,014 | 2.15 | -10.50, 14.80 | 0.33 | 54.8% | 12/21 | 0.844 | 0.974 | 1.54 | fail | 13 | too few |  | - |
| B3-BANKNIFTY-Tue | 1,019 | 7.38 | -2.72, 17.49 | 1.43 | 51.8% | 13/21 | 0.401 | 0.752 | 1.54 | fail | 13 | too few |  | - |
| B3-BANKNIFTY-Wed | 1,019 | 9.09 | -0.74, 18.92 | 1.81 | 51.3% | 15/21 | 0.286 | 0.626 | 1.54 | fail | 13 | too few |  | - |
| B3-BANKNIFTY-Thu | 1,010 | 4.73 | -5.17, 14.63 | 0.94 | 53.8% | 13/21 | 0.648 | 0.974 | 1.54 | fail | 14 | too few |  | - |
| B3-BANKNIFTY-Fri | 1,001 | 8.94 | -1.79, 19.68 | 1.63 | 52.3% | 15/21 | 0.313 | 0.663 | 1.54 | fail | 13 | too few |  | - |
| B3-BANKNIFTY-expiry | 222 | -7.91 | -22.75, 6.93 | -1.04 | 46.4% | 2/5 | 0.357 | 0.710 | 1.54 | fail | 3 | too few |  | - |
| B3-BANKNIFTY-us-event | 96 | 1.34 | -19.93, 22.61 | 0.12 | 56.2% | 2/5 | 0.898 | 0.974 | 1.54 | fail | 5 | too few |  | - |
| B4-NIFTY-gapup0.3 | 1,262 | 6.29 | 1.08, 11.51 | 2.36 | 52.2% | 14/18 | 0.173 | 0.526 | 1.25 | fail | 10 | too few |  | - |
| B4-NIFTY-gapdown0.3 | 601 | 1.27 | -8.30, 10.84 | 0.26 | 49.8% | 9/16 | 0.846 | 0.974 | 1.25 | fail | 11 | too few |  | - |
| B4-NIFTY-gapup0.6 | 457 | 8.30 | -1.47, 18.07 | 1.67 | 49.9% | 11/17 | 0.182 | 0.531 | 1.25 | fail | 5 | too few |  | - |
| B4-NIFTY-gapdown0.6 | 286 | 9.83 | -5.94, 25.59 | 1.22 | 53.1% | 9/13 | 0.206 | 0.571 | 1.25 | fail | 4 | too few |  | - |
| B4-BANKNIFTY-gapup0.3 | 1,716 | 2.73 | -4.82, 10.28 | 0.71 | 51.7% | 12/21 | 0.657 | 0.974 | 1.54 | fail | 11 | too few |  | - |
| B4-BANKNIFTY-gapdown0.3 | 1,029 | -4.94 | -16.27, 6.39 | -0.85 | 48.8% | 16/21 | 0.430 | 0.772 | 1.54 | fail | 13 | too few |  | - |
| B4-BANKNIFTY-gapup0.6 | 839 | -0.57 | -13.27, 12.14 | -0.09 | 49.9% | 8/21 | 0.921 | 0.974 | 1.54 | fail | 3 | too few |  | - |
| B4-BANKNIFTY-gapdown0.6 | 585 | -1.19 | -18.70, 16.33 | -0.13 | 49.4% | 11/20 | 0.878 | 0.974 | 1.54 | fail | 6 | too few |  | - |

What the table says:

* **B1, trend vs reversal.** The 1-minute rule (+0.11 bps NIFTY, +0.04 bps BANKNIFTY, t 26 and 6.7 on 850k /
  450k pairs) is the only thing confirmed on the holdout, and it is an artefact: the cash index is a weighted sum of
  50 or 14 stocks that update at different moments, so consecutive index minutes share information (the Epps /
  stale-price effect). It is 0.1 bps against a 1.25-1.54 bps cost; the futures would not show it. Five and fifteen
  minutes: nothing (slight reversal, CI through zero). One hour: NIFTY +0.88 bps passed design (q 0.044, all 10
  years positive) but is below cost and was +0.42 (CI -1.7 to +2.5) on the holdout. **One day: the twenty-year
  momentum of +5.8 bps (NIFTY) and +12.4 bps (BANKNIFTY), positive in 18 and 21 of 21 years, reversed in the holdout
  quarter (-9.4 and -24.6 bps, n 65).** The yearly table in `pred_design.json` shows it had already faded: BANKNIFTY
  +28 bps a day in 2006-2008, +11-12 in 2012-2016, +2 to +6 in 2018-2026. It is a fact about the past, not a rule.
* **B2, participants.** Four rules passed design: clients adding index-futures longs predicts a DOWN day (NIFTY -5.2
  bps, BANKNIFTY -8.2 bps, q 0.000, sign right in 11 and 13 of 15 years), FIIs adding futures longs predicts an up
  day on BANKNIFTY (+6.0 bps, q 0.07), FII option positioning above its trailing median predicts an up day on NIFTY
  (+4.8 bps, q 0.07). On the holdout (n 63) every one of them has a CI through zero, and the FII-futures one has the
  wrong sign (-14 bps). Levels (vs the trailing 250-day median), the five-day horizon and the FPI cash sign never
  passed. R86's P1-P3 (FII levels and five-day shifts) failed the same way. The honest reading: the participant file
  says who is positioned, not what comes next; the one-day "client change" effect is the strongest thing in the
  file and it is worth about Rs 25-45 a day per lot before the holdout said no.
* **B3, calendar.** No weekday, the expiry day and the US-event session all fail (best q 0.43, NIFTY Wednesday).
* **B4, gap fade.** No rule passes; the best, fading a NIFTY gap up of 0.3% or more (+6.3 bps, n 1,262), has twin
  p 0.17 and q 0.53. Gap days behave as section 2.4 shows, and that is not a trade.

**Verdict of part B: 70 tests, 8 passed design, 1 confirmed, and the confirmed one is index staleness. There is no
simple sign rule on NIFTY or BANKNIFTY at any horizon from one minute to one week, from the participant file, from
the weekday or from the gap.** This agrees with everything the repo found before (R16 direction AUC 0.51-0.54, B2
0.52, R87 "no shape predicts direction", h42 "0 strict candidates").

## 5. What our bots have found on these two indices

The repo has tested about sixty arms and studies on NIFTY and BANKNIFTY since Aug 2021. The table keeps one row per
family; the numbers are quoted from the cited docs (FINDINGS.md is the master list; R74 is the map).

| arm or study (doc) | index | verdict | sample and period | key number |
|---|---|---|---|---|
| Liquidity 15+5 (FINDINGS "Green candles", HUNT_FINAL) | BANKNIFTY | **adopted, paper now; "weak"** | 1,651 trades Aug 2021 - Oct 2026 (R74); locked year read once | Rs 65/day before and Rs 167/day in the locked year; 40-50% of months lose; drawdowns Rs 32-40k |
| Liquidity on NIFTY / SENSEX (FINDINGS LIQUIDITY_INDICES, R81) | NIFTY | rejected | two years | NIFTY loses both years (-Rs 44k / -22k); "Do not add NIFTY or SENSEX" |
| Liquidity entries, exits, locks, filters (R15, R18, R19, R19b, R70, R76, R80, R81, R100) | BANKNIFTY | all rejected, plain rules kept | R80 336 / 131 trades; R100 1,238 trades | R15: 0 of 5 daily filters (best q 0.50); R100: give-back locks 0 of 7 |
| ORB, ORB Fresh, ORB Sweep, Range Fade (R3, R14, R49, R92) | BANKNIFTY, 5 indices | **rejected; hidden 15 Oct** | R3 2,489 ORB trades over 5 years; R92 4,884 sessions | ORB -Rs 2.23 lakh over 5 years; every entry hour and every weekday loses (R92) |
| Solo / big-candle pullback (FINDINGS COMBINED, ADVANCED) | BANKNIFTY, NIFTY | paper, "weak" | 285 + 299 trades | index +0.20 R, t 2.35; ATM options +Rs 7.5k a year (t 0.27) |
| Solo 2.0 meta-labeller A2 (R57) | 4 indices | passed; paper only | 128 final trades | +Rs 99,172 (twin p 0.002); 72% of it MIDCPNIFTY |
| Donchian 20 re-tune (R51), + Supertrend T7 (R73) | 5 indices | passed (1 of 125); paper, "thin" | 1,787 final trades | +Rs 55,439; lost on NIFTY in both periods; about zero at 2x spread |
| Breadth Donchian R72 C1 (R72, R75, R84) | 5 indices together | passed; paper only | 3,071 design / 1,202 final trades | final Rs 1,71,366 (q 0.032); "not a live candidate at 1 lot x 5 indices" |
| OOV open-outside-value (R52) | 5 indices | passed (1 of 188); paper | 294 final trades | +Rs 48,772; top 5 trades = 82% of net |
| Pine Supertrend+EMA50, Matrix (FINDINGS, R52) | BANKNIFTY | rejected | 357 trades a year; 38 Matrix versions | 15-min arm -Rs 22,709 (t -1.35); Matrix -Rs 1.09 lakh a year |
| Night R3 (R27, R28, R51) | NIFTY, BANKNIFTY | paper-only, "weak"; hidden 15 Oct | 869 trades Aug 2021 - Oct 2026 | +Rs 168/day in the locked year, not significant |
| VIX divergence (R1, R74 V1) | NIFTY, BANKNIFTY | rejected; hidden 15 Oct | 253 design / 65 final | R74: design -Rs 25,287, final -Rs 23,860 |
| Expiry hero / zero-to-hero / FT3 (R21, R25, R43, R50, R54, R55, R98) | NIFTY, all 5 | rejected; Boss's Rs 1,000 ticket on paper by choice | R98 911 expiries, 394,956 tickets | "0 of 198 hero rules pass"; tickets end below cost 97% of the time |
| Straddle buying, size straddle (FINDINGS LONG_VOL, R89) | both | rejected | 66 versions; 4,302 design straddles | -Rs 0.5 to -1.5 lakh a year; R89 0 of 18 |
| Short ATM straddle (FINDINGS NONDIRECTIONAL) | BANKNIFTY | "weak", not app-eligible (selling) | two years | intraday 50%-credit stop +Rs 60k / +29k a year |
| PDHL break (R89), SMC sweep (R91), CCI (R95), Nifty100 (R99) | 5 indices | rejected | 2,925 / 135 / 4,072 / 238 design trades | R89 0 of 180; R91 0 of 63; R95 0 of 288; R99 0 of 3 |
| Boss's sweep strategies (R43, R44), relaxed buying (R47), high win-rate (R48), what-if (R24) | 4-5 indices | rejected | R44 64,209 trades; R47 387,392 configs; R24 11.7M option-minutes | blind ATM buying loses Rs 97-231 a lot (R24) |
| Factory, ribbons, all parameters, nets, all-at-once (F1, R29, R30, R41, B2) | 4-5 indices | rejected | F1 11,672 tests; R30 249,414 moments | direction AUC 0.51-0.52 everywhere |
| GEX (R28, R36), OI wall blast (R26) | 5 indices | rejected as filters; size fact kept | 519-597 days per index; 341 expiries | negative GEX at 10:00 -> range 0.87% vs 0.75% (BANKNIFTY) |
| Predictability (R16) | both | **range 30/60 min predictable**; direction not | 498 / 499 sessions, holdout 122 / 120 | holdout error 15-20% below the time-of-day average; direction AUC 0.509-0.536 |
| Participants OF1, R86 family | both | rejected | OF1 3,632 days; R86b 1,479 / 814 days | OF1 15 passed BH 2012-2025, 0 of 15 held; R86 0 of 23 |
| Candle anatomy (R87), what moves the candle (R88), move anatomy (R11) | both | descriptive; no direction | 77,000 5-min candles per index | 92% of shape cells between 45% and 55% up; size predictable (225 of 305 hold) |
| Question engine h42, market how h41, expiry playbook, event shield | 5 indices | descriptive | 1,296 questions; 1,515 days | 327 true facts; 0 strict trade candidates |
| **R102 (this study)** | both | descriptive + 70 tests | 5,087 days; 2.3k / 1.2k minute days; 1.2k option days; 3,577 participant days | 1 of 70 confirmed, an artefact |

**Where an edge might exist, from all of it.** Direction is a coin on both indices at every horizon our data
covers; forty studies and this one agree. What is predictable is **size**: the next 30-60 minutes' range (R16,
R41, R88), the VIX regime (section 2.2), the day's front-loading (2.3) and the expiry decay clock (3.2). The arms
that passed the gates (Donchian 20, OOV, Solo 2.0, Breadth Donchian, the A4 ladder) all buy a 1-ITM option on a
mechanical break and live on a handful of big days; none is a live candidate and all are thin. If there is money in
these two indices for a one-lot account it is in (a) paying less (lots are now 65 / 30, charges Rs 70-76 a round
trip), (b) trading when the range is big (VIX, first hour, budget day) and sitting out when it is not, (c) selling
the expiry-day decay with defined risk, which the repo lists as untested (open_questions `defined-risk-selling`),
and (d) nothing that depends on calling the direction.

## 6. Field guide for Boss: the 20 most useful facts (each with its number and sample size)

1. **BANKNIFTY is NIFTY times 1.16.** Daily correlation 0.877, beta 1.157 over 2006-2026 (n 5,086 days); 0.842
   and 1.094 on 5-minute bars (n 89,575). The big five banks are 63% of BANKNIFTY and 29.5% of NIFTY 50.
2. **A normal NIFTY day moves 0.74-1.05% (sd), a BANKNIFTY day 0.79-1.36%.** 2025 was the quietest year since 2023
   (sd 0.744% / 0.789%, n 249); the first half of 2026 was busier (1.051% / 1.356%, n 130). Over 20 years (n 5,087)
   NIFTY's sd is 1.316% and BANKNIFTY's 1.734%.
3. **The drift is overnight.** NIFTY's close-to-close mean is +0.051% a day and its open-to-close mean is -0.035%
   (n 5,087); BANKNIFTY +0.065% / -0.039% (n 5,086). An intraday-only trader does not get the drift.
4. **The median day range is 0.91% on NIFTY and 1.13% on BANKNIFTY** (n 2,288 and 1,185 full minute days). By 10:30
   the day has made 61% / 65% of it; by 13:00 82% / 87%; the range is already complete at 14:30 on 44% / 53% of days.
5. **The first minute spans 0.21% (NIFTY) / 0.30% (BANKNIFTY), the first five 0.27% / 0.38%** (median). Minute for
   minute, 09:15-09:30 is 2.3 / 3.1 bp and 10:30-13:00 is 0.29 / 0.38 bp: 8x.
6. **The open is the day's high or low on 41% of NIFTY days and 46% of BANKNIFTY days** (first five minutes). The
   first 15 minutes hold the day's high 29% of the time and the last hour 29% (NIFTY; BANKNIFTY 29% / 25%).
7. **Gaps of 0.5% or more: 20% of NIFTY days, 34% of BANKNIFTY days** (n 5,086 / 5,085). A 0.5-1% gap fills by the
   close 42% (NIFTY up, n 484) / 41% (down, n 248) of the time; BANKNIFTY 53% / 49%. A 2%-plus NIFTY gap closes on
   its own side of the previous close 94-96% of the time (n 27 / 32).
8. **When a gap fills it fills early:** of filled gaps of at least 0.25%, the median fill is 52 minutes after the
   open on NIFTY (n 1,245 gaps) and 32 minutes on BANKNIFTY (n 694); by 10:30, 27% / 32% of all such gaps are filled.
9. **Direction is a coin at every horizon.** The next bar carries the previous bar's sign 48.4% (5 min), 48.6%
   (15 min), 50.6% (1 h) of the time on NIFTY (n 167,082 / 52,640 / 9,154) and 48.3% / 48.2% / 49.3% on BANKNIFTY. The
   first 30 minutes' direction holds to the close 53.0% (NIFTY, n 2,286) / 50.4% (BANKNIFTY, n 1,185) of the time.
10. **The twenty-year daily momentum (+5.8 bps NIFTY, +12.4 bps BANKNIFTY per day, n 5,086) is gone:** it was +2 to
    +6 bps in 2018-2026 and -9.4 / -24.6 bps in the Jul-Oct 2026 holdout (n 65).
11. **Weekday means nothing.** NIFTY's median range is 0.89-0.94% on every weekday (n 451-459 each), the mean return
    +0.006% to +0.095% (n about 1,000 each), and no weekday survives BH (best q 0.43).
12. **Expiry day is not a bigger day**: NIFTY range 0.92% on 289 expiry days vs 0.91% on 1,999 others; BANKNIFTY
    1.17% (185) vs 1.12% (1,000). Its mean return is a little lower (BANKNIFTY -0.079% on 222 expiry days vs +0.076%)
    but not significantly (q 0.80).
13. **Budget day doubles the range (2.13% NIFTY, 2.62% BANKNIFTY, n 6). RBI days have been up days (+0.36% / +0.50%,
    n 30) with a normal range. FOMC (n 39) and US CPI (n 58) sessions look like any other day.**
14. **VIX is the range dial, not a direction dial.** Low VIX (9-15): NIFTY median range 0.75%, BANKNIFTY 1.01%; high
    VIX (above 20): 1.72% / 2.37% (n 1,509 days each). VIX over-states NIFTY's next month on 81% of days (median
    ratio 1.28, n 4,507) and under-states BANKNIFTY's on 60% (0.93).
15. **The 09:20 ATM straddle costs a median 0.96% of spot on NIFTY (n 1,222) and 1.49% on BANKNIFTY (n 1,190; 2.15%
    in 2025 and 2.78% in 2026 since it became a monthly).** The close-to-09:20 move beats it on 7.1% of NIFTY
    non-expiry days (n 961) and 6.5% of BANKNIFTY's (n 1,000), but on 40.5% / 37.8% of expiry days (n 259 / 185).
16. **The expiry-day decay clock:** the ATM straddle is 84% of its 09:20 value at 10:30, 70% at 12:00, 61% at 13:00,
    40% at 14:30, 22% at 15:00 and 13% at 15:15 (NIFTY, n 259; BANKNIFTY 85 / 73 / 61 / 38 / 23 / 11%, n 186). A
    normal day: 96.5% at 10:30 and 86% at 15:29 (NIFTY).
17. **Premium in the first 15 minutes and the last hour:** the straddle falls 09:15 -> 09:30 on 69% of normal NIFTY
    days (median -1.8%; expiry -8.0%) and 14:30 -> 15:29 on 77% (median -3.6%; expiry -59%). One ATM option moves a
    median 3.3% a minute in the first 15 minutes and 13.5% a minute in expiry's last hour (n 36,652 / 30,562).
18. **The chain always prices the crash:** the ATM-5 put is dearer than the ATM+5 call by 2.56 IV points on NIFTY
    (91% of days, n 1,222) and 2.11 on BANKNIFTY (90%). PCR of OI is 0.86 on both (p5-p95 0.52-1.34, n 1,363 days).
19. **Max pain is not a magnet:** the expiry close lands a median 0.60% (NIFTY, n 289) / 0.68% (BANKNIFTY, n 222)
    from the previous day's max pain, within one strike step only 17.6% / 21.2% of the time.
20. **Who is positioned (2025 - Jul 2026): FIIs net short index futures by 61-72% of their book, clients net long by
    30-46%, clients hold 58% of index-option longs, prop desks write 23-24% of the shorts (n 3,577 days). None of 36
    participant rules was confirmed on the holdout.**

## 7. Jarvis: the facts file

`research/knowledge/index_facts.yaml` holds these facts (and a few more from sections 1-4) as machine-readable
cards: `id`, `inst` (NIFTY / BANKNIFTY / BOTH), `kind` (descriptive / rule / contract), `n` (the sample size, required),
`period`, `topics`, `tod`, `text`, `src` and `sec`. `research/knowledge/build_pack.py` reads it (`add_index_facts`),
checks every number in a card against this study, and puts each card in the pack as a `index_fact` and in the shared
section for every agent (source `research`, gate `none` for descriptive cards). So "how does BankNifty behave at
09:20?" is answered from facts 4-6 and 17 with their n. The pack itself (`android/app/src/main/assets/knowledge/
pack.bin`) is rebuilt in an app session (`python research/knowledge/build_pack.py`); this research-only branch does
not touch `android/**`.

## 8. Method, limits, honesty

* **Data.** Dhan v2 lake on Drive (index_1m: NIFTY 2017 ->, BANKNIFTY 2021 -> (full days from 21 Sep 2021);
  index_daily 2006 ->; opt_*_WEEK 2021 ->; participants 2012 ->; bhavcopy index rows 2021 ->). Minute days with fewer
  than 360 bars are dropped from the intraday tables (short days, feed gaps). Option rows with a non-positive close are
  dropped. The lake's minute bars end at 15:29 (the cash index), although options now trade to 15:40.
* **Descriptive tables are on the design period (to 14 Jul 2026)**; the full-lake run (`desc_all.json`, to 19 Oct
  2026) was written after the holdout read and moves no headline number by more than its rounding (NIFTY median day
  range 0.900% on 2,355 days; 09:20 straddle 0.959% on 1,289 days).
* **Predictive part.** Pre-registered and committed before any statistic (PREREG.md); two amendments after the first
  design run, both disclosed there (the B4 test count and side split; the twin method, whose first version could
  hand a twin the sign of its own bar). 1,000 twins, BH over all 70, walk-forward as year-by-year sign consistency
  (the rules have no fitted parameter), the holdout read once by `r102pred.py holdout`, which wrote
  `results/HOLDOUT_READ` and refuses a second read. The holdout is one quiet quarter (n 65 days for daily rules):
  it can reject (it did) but it could not have confirmed a daily rule with much confidence either; a yearly re-read
  with a fresh pre-registration is the right next step for B2's client-change rule if anyone still cares.
* **Costs.** The cost line in the table is one ATM option round trip in index bps (R16's Rs 98 / Rs 133 through delta
  0.5 and today's lots). It is a floor: it ignores the signal-time spread (open question `signal-time-spread`) and
  theta on an overnight hold.
* **Not claimed.** No new arm, no parameter search, no option-chart signal, nothing for paper or live. The lot table
  in `research/obuy/config.py` should get its Jan 2026 row (65 / 30 / 60 / 120) in a later research change.
* **Tests.** `research/hunt/r102_deep/tests/test_r102.py`: loaders on a synthetic lake, the expiry calendar, the
  holdout lock (a second read is refused), bars that never span the open, the look-ahead guard (every participant
  signal at day t is unchanged when later days are removed; forward returns never cross the design end), BH, the 70
  test count, the facts file's schema and its numbers against this document, and the pack loader's rejections.
