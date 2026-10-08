# HUNT h27: does overnight / outside information predict the Indian index day?

Code: `research/hunt/h27/` (`PREREG.md`, `fetch.sh`, `fetch_poi.py`, `build.py`, `part_a.py`, `sim.py`, `analyse.py`,
`detail.py`, `info.py`). Logs and tables: `scratchpad/hunt/h27/` (`part_a_pre.csv`, `part_a_hold.csv`,
`variants_pre.csv`, `variants_hold.csv`, `info_pre.csv`, `*.log`). Raw downloads: `scratchpad/hunt/h27/raw/`.

Option BUYING only. 1 lot. Rs 1 lakh. Holdout locked from 2025-10-01 and run once.

## Verdict

**NO.** Outside information known by 09:15 does not predict the rest of the Indian index day well enough to beat
option costs.

1. **The opening gap already prices the news.** US, ADR and Asian cues line up strongly with the 09:15 gap
   (correlation about 0.5-0.65). After 09:20 almost nothing is left to trade.
2. **Before the holdout there was a small effect, but it was the reverse of "follow the cue".** A strong overseas
   cue made the gap overshoot, and the index gave some of it back by 15:10. The correlation was about -0.10.
   - "Follow the cue" (buy CE after a good US/Asia night) lost money: -Rs 143/day net on average.
3. **No option variant passed the pre-registered gates.**
   - 234 variants were tried.
   - Best random-side BH q = 0.10. The gate needed q < 0.05.
   - Hansen SPA p = 0.76. The gate needed p < 0.10.
4. **The best variant collapsed in the holdout.** It was: fade the Nasdaq-100 cue on BANKNIFTY, hold to 15:10.
   - Pre-holdout: +Rs 135/day net.
   - Holdout: **-Rs 218/day net** (gross -Rs 117/day). That is no better than a coin flip (random-side p = 0.45).
5. **The index-level pattern did not replicate in the holdout either.** 0 of 240 tests passed BH. The sign of the
   "fade" correlation turned positive.
6. **Rs 5,000/day is out of reach.** Even the pre-holdout best would need about 37 BANKNIFTY lots. Rs 1 lakh buys 3-5
   lots.

## Data: what the network allowed

| source | status | used for |
|---|---|---|
| Yahoo chart API | works only with a cookie+crumb session (a plain request gets 429) | daily 2015+: S&P 500, Nasdaq-100, Dow, ES/NQ, Nikkei, Hang Seng, Kospi, crude, Brent, USD/INR, DXY, US 10y, gold, HDB/IBN/INFY/WIT ADRs, INDA, US VIX, India VIX; 1h bars for 730 days only |
| NSE archives (participant-wise OI) | works (slow, some timeouts) | FII index-futures net long, 2020-04 onward |
| NSE FII/DII cash API | latest day only | not usable (no history) |
| NSDL FPI archive | ASP.NET form | not used |
| stooq | connection reset | blocked |
| FRED | timeout | blocked (Yahoo used for yields and FX) |
| investing.com | 403 | blocked |
| api.nasdaq.com | works | not needed |
| GIFT NIFTY | Dhan daily bars (2017+); session times undocumented | exploratory only |
| SGX Nifty | discontinued in 2023 | none |

Timing (no look-ahead):
- US-session data comes from the last US bar dated before the Indian day. That bar closes by 02:30 IST at the latest.
- Asian markets: only their **open** on day D is used (Nikkei and Kospi open at 05:30 IST, Hang Seng at 07:00 IST).
  Their close comes after 09:15 IST, so it is never used.
- India VIX and FII OI are from D-1.
- The alignment check confirms the timing: the cues correlate with the gap as expected.

## Part A: index-level screen (pre-holdout 2020-08 .. 2025-09)

Setup:
- 15 cues (14 single cues plus a composite of S&P 500 + ADRs + Asia opens, no fitted weights).
- 5 indices, and moves from the 09:20 close to 10:15, 11:15 and 15:10.
- One extra test per index and horizon: the gap residual, meaning the part of the gap the composite does not
  explain.
- In total, 240 tests with BH correction.

| | pre-holdout | holdout (run once) |
|---|---|---|
| tests passing BH q<0.05 | 10 / 240 | **0 / 240** |
| tests with raw p<0.05 | 47 | 7 (about what chance gives) |
| corr(composite cue, BANKNIFTY 09:20→15:10) | **-0.118** | +0.047 |
| corr(composite cue, NIFTY 09:20→15:10) | -0.068 | +0.036 |
| corr(gap residual, BANKNIFTY 09:20→15:10) | **+0.147** | +0.005 |
| corr(composite cue, the 09:15 gap), NIFTY | 0.65 | 0.66 |

- Before the holdout, all 10 BH survivors sat at the 11:15/15:10 horizons:
  - Strong overseas news made the gap overshoot, and it faded.
  - The part of the gap the news did not explain carried on.
- Neither effect held in the holdout.
- The fade was not steady even before the holdout. For BANKNIFTY by year: -0.14, -0.13, -0.03, -0.15, -0.11. For
  NIFTY 2023 it was +0.02.

Crude, USD/INR, DXY, US 10y, gold, India VIX change and FII OI change: none passed BH in either period.

Exploratory checks (not pre-registered; 45 tests with their own BH): the ES/NQ futures move from the US close to
08:30 IST, and the GIFT NIFTY "surprise" (the actual gap minus the gap GIFT implied). **None passed** (best q = 0.26).
The ES/NQ data covers only about 1 pre-holdout year.

## Part B: option trades (pre-holdout)

Setup:
- Contract: 1-ITM, nearest expiry, 1 lot (the lot size in force on each date).
- Entry at the 09:21 open (R2 enters at 09:31).
- Exits fixed in advance:
  - E1: Liquidity exits (-15% stop, 20-minute time stop unless up 5%).
  - E2: -15% stop / +30% target.
  - E3: ladder, with a +40% target.
  - E4 / E5 / E6: -30% stop, then out at 10:15 / 11:15 / 15:10.
- GROSS = raw prices with no charges.
- NET = app charges at the dated STT + the real half-spread (BN/NIFTY 0.16%, MIDCP 0.21%, FIN 0.42%, SENSEX 0.20%
  assumed), paid at entry and exit, + 5 bps on stop fills.
- STRESS = 1.5 × the spread.
- Random baseline: the same days and minutes with a coin-flip side and identical exits. Both sides were simulated
  every day, so the comparison is exact.

Average over the 5 indices × 6 exits:

| rule | gross Rs/day | net Rs/day | stress Rs/day | random side net Rs/day | hit |
|---|---|---|---|---|---|
| R1 follow the cue | -97 | -143 | -151 | -86 | 31% |
| R2 follow the cue, confirmed at 09:30 | -48 | -68 | -72 | -40 | 29% |
| R3 gap-go when cue agrees | -42 | -73 | -78 | -46 | 32% |
| R4 gap-fill when cue disagrees | -46 | -58 | -61 | -25 | 25% |
| R5 fade the gap residual | -40 | -58 | -62 | -24 | 27% |
| R6 fade the cue (added after Part A) | +37 | -15 | -25 | -92 | 34% |
| R7 follow the gap residual (added after Part A) | +29 | +10 | +7 | -24 | 36% |

Top variants. Rs/day is counted over all trading days of that index. None was promoted.

| variant | trades | hit | gross/day | net/day | stress/day | random p | BH q | years + | max DD | lots for 5k/day |
|---|---|---|---|---|---|---|---|---|---|---|
| fade NDX, BANKNIFTY, hold to 15:10 | 549 | 36% | 190 | **135** | 127 | 0.000 | 0.10 | 4/5 | -48.5k | 37 |
| follow residual, FINNIFTY, 15:10 | 146 | 42% | 158 | 133 | 126 | 0.004 | 0.10 | 3/4 | -22.1k | 38 |
| follow residual, BANKNIFTY, 15:10 | 148 | 43% | 125 | 111 | 108 | 0.002 | 0.10 | 3/4 | -33.7k | 45 |
| fade INDA residual, MIDCP, 15/30 | 323 | 41% | 180 | 106 | 91 | 0.012 | 0.16 | 2/3 | -54.7k | 47 |
| follow residual, FINNIFTY, 11:15 | 146 | 48% | 126 | 101 | 94 | 0.002 | 0.10 | 4/4 | -13.2k | 50 |

Gates, all 234 variants:
- 38 had net > 0.
- 32 had a raw random-side p < 0.05.
- 0 had BH q < 0.05.
- SPA p = 0.76 and White RC p = 0.41.

So nothing beats chance once the number of tries is counted.

## Locked holdout (2025-10-01 .. 2026-10-05, run once)

The protocol ran the single best pre-holdout variant, for information (nothing was promoted).

| | pre-holdout | holdout |
|---|---|---|
| rule | BANKNIFTY: buy PE after a Nasdaq-100 up night (z >= 0.5), CE after a down night; 1-ITM, -30% stop, out 15:10 | same |
| trades | 549 / 992 days | 149 / 248 days |
| hit rate | 36% | 43% |
| gross Rs/day | +190 | **-117** |
| net Rs/day (real spread) | +135 | **-218** |
| stress Rs/day (1.5× spread) | +127 | -240 |
| random-side net Rs/day | -111 | -243 |
| random-side p | 0.000 | 0.45 |
| max drawdown, 1 lot | -48.5k | -92.7k |
| worst month | -26.5k | -49.5k |
| losing months | 19 / 49 | 7 / 13 |
| bootstrap P(losing month) | 0.45 | 0.60 |
| median premium per lot | Rs 7.8k | Rs 22.6k (3 lots fit in Rs 1 lakh) |

At Rs 1 lakh, a -92.7k drawdown on 1 lot would have nearly wiped out the account.

## Honest variant count

- Part A: 240 tests (pre-registered), plus 45 exploratory.
- Part B: 234 option variants: 5 pre-registered rules and 2 added rules, × 5 indices × 6 exits, plus 4 single-cue
  rules × 6 exits.
- Holdout runs: 1 option variant and 1 index-level replication.
- The added rules R6/R7 and the 4 single-cue rules came from the Part A index-level results, before any option P&L
  was computed. Amendment 1 in `PREREG.md` records this.

## Plain answer for the Boss

- Overseas markets tell you where India will **open**, not where it goes after 09:20.
- The 09:15 price already includes the US close, the ADRs, the Asian opens and GIFT.
- What looked like a "fade the overseas news" edge in 2021-2025 was small (+Rs 135/day per lot at best). It did not
  survive the 2025-26 holdout (-Rs 218/day).
- **Rs 5,000/day from morning cues: NO.**
