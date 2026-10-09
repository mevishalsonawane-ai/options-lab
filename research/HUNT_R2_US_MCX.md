# HUNT R2: does the US market tell us which way MCX will go?

Written 9 Oct 2026 by R2. Fixed frame: option BUYING (plain mini futures where noted), Rs 1,00,000, 1 lot fixed,
Zerodha MCX charges (app `McxCosts.kt`), half-spread paid on entry and on exit (options 0.30%/0.60% crude and gas,
0.40%/0.80% GOLDM/SILVERM, after/before 17:00; futures 0.03%), random-side baseline, BH correction, and the locked holdout
1 Oct 2025 - 6 Oct 2026, opened once (`scratchpad/hunt/r2/holdout.lock`).

- Plan: `research/hunt/r2/PREREG.md`. It has two amendments, both written before the holdout was opened.
- Code: `research/hunt/r2/` (fetch, parse, lib, daily, hourly, events, leadlag, leadlag_trade, minute_open, cand,
  lead_cand, fomc, design_summary, holdout, holdout_summary, robust_live, sens).
- Data and outputs: `scratchpad/hunt/r2/` (about 90 MB): `data/`, `out/*.csv|parquet`, `out/holdout_*.log`.
- Nothing was committed. No Dhan call was made; the old minute data was enough.

## Verdict (plain English)

**Yes, the US market tells you MCX's direction a little. No, not in a way an option buyer can use after costs.
Two narrow futures patterns and one gold option rule survived. They are worth paper-trading, not real money yet.**

1. **MCX is the US price times USD/INR.** Moves line up in the same minute: 1-minute correlation 0.48-0.63 for gold,
   0.7-0.9 for crude, gas and silver.
   - The US lead is at most **1 minute**. XAUUSD leads GOLDM with a lag-1 correlation of 0.11 in design and 0.06 in the
     holdout.
   - The leftover catch-up after a big US move is 3-15 bp. A GOLDTEN round trip costs about 8 bp.
2. **The overnight US move is mostly priced into MCX's 09:00 gap.** The correlation between the gap and the US move
   from the MCX close to 08:30 IST is 0.67-0.82.
   - What the gap misses does help predict the rest of the MCX day. Measured from the Dhan daily open print, the US
     overnight move predicts MCX open-to-close with correlation **0.22-0.41 in design and 0.32-0.35 in the holdout**.
     The hit rate is 53-64%.
   - **Most of that comes from the opening print itself.** That print is 16-50 bp away from the 09:00-09:05 minute
     price. From a tradable 09:05 entry, the effect disappears for crude and gold. It survives only in
     **natural gas (09:05-14:00)** and **silver (09:05 to the close, futures)**.
3. **Option buying eats almost everything.**
   - Holding an option from 09:05 to the close loses Rs 500-2,300 per trade in every commodity. That is the evening
     decay M4 found.
   - Morning-only holds (09:05-14:00) break even at best. The exception is natural gas: +Rs 181 per trade, but it fails
     BH.
   - The one option rule that passed holdout BH is the **GOLDM catch-up to XAUUSD**: +Rs 228 per trade, about
     Rs 123 a day, q 0.095. Its design period had no option trades to check it on.
4. **Scheduled US events give no direction.**
   - EIA crude/gas surprises (stock change vs the 5-year same-week average): hit rate 40-54%.
   - CPI/NFP "follow the first hour": 32-55%.
   - FOMC overnight straddle: 1 winner out of 20 in the holdout, -Rs 57k in total.
   - Slow macro gives no direction either. DXY and 10-year real-yield 20-day trends do not predict gold or silver over
     the next 20 days (hit 39-54%). COT positioning extremes do not predict anything.
5. **Rs 5,000 a day: NO.**
   - The best tradable rule (SILVERMIC follow-US, futures) made about Rs 1,100 a day in a holdout year when silver
     tripled. It is a short-and-long futures rule with a Rs 30k drawdown.
   - Nothing reaches Rs 5,000 a day at 1 lot.

## Effect sizes

| question | design | holdout | read |
|---|---|---|---|
| corr(US overnight move, MCX gap 23:30 -> 09:00) | crude 0.82, gas 0.79, gold 0.79, silver 0.73 | 0.67 / 0.80 / 0.78 / 0.77 | the gap prices most of the night |
| corr(US daily return D-1, MCX open->close D), 2012-2025 | -0.01 to +0.08 | +0.04 to +0.09 | nothing |
| corr(US overnight to 08:30 IST, MCX open->close), from the Dhan **daily open** | 0.22 / 0.22 / 0.35 / 0.41 | 0.33 / 0.32 / 0.32 / 0.35 | real at the print; see the next row |
| same, but from the **09:05 minute price**, net Rs per mini-lot trade (C2, to the close) | -246 to +9 (36 days) | crude -160, gas +255, gold +37, **silver +1,189** | survives in silver and gas only |
| corr(gap residual = MCX gap - US-implied gap, MCX open->close) | -0.21 / -0.21 / -0.36 / -0.45 | -0.27 / -0.32 / -0.32 / -0.33 | MCX's open under-reacts, then catches up |
| part of "follow US" that is MCX-specific catch-up (basis change), bp per day | crude 18, gas 54, gold 6, silver 14 | 44 / 45 / 19 / 45 | about half local catch-up, half US momentum |
| 1-min lead, XAUUSD -> GOLDM (lag-1 corr; lag-0 corr) | 0.11 (0.48) | 0.06 (0.63) | 1 minute, small |
| 1-min lead, XAGUSD -> SILVERM (Dukascopy, design) | 0.07 (0.68) | not run | 1 minute, small |
| 1-min lead, Yahoo CL/NG/SI/GC -> MCX, 2-7 Oct 2026 only (lag-1; lag-0) | - | crude 0.13 (0.91), gas 0.09 (0.88), silver 0.03 (0.69) | at most 1 minute. Yahoo GC=F 1-minute bars look shifted by one minute; not used |
| GOLDM move in the 15 min after a 5-min XAUUSD-GOLDM divergence above q99.7 (about 24 bp) | +15 bp (99 events) | +11.6 bp (483 events) | the catch-up is real; costs about 8 bp |
| EIA crude surprise sign -> rest of the US session (hit) | 43% | 40% | no |
| EIA gas-storage surprise sign -> rest of the session (hit) | 54% | 50% | no |
| CPI/NFP first-hour direction -> to the MCX close (gold, silver hit) | 55% / 45% | 32% / 36% | no |
| gold Asian-hours drift, long 09:30-14:30 IST (US hourly) | +3.4 bp/day, t 1.4 | +1.8 bp, t 0.4 | too small; below costs |
| MCX range on US exchange holidays vs normal days (2012-2025, median) | crude 1.6% vs 2.4%, gold 0.55% vs 0.89% | 2.0% vs 3.7% | **US holidays: MCX is 30-45% quieter. Do not buy options then** |
| median \|MCX gap\| the morning after FOMC vs all days (2021-2025) | gold 0.26% vs 0.15%, silver 0.41% vs 0.22% | gold 0.56% vs 0.31% | gaps are bigger, but the straddle is priced for it (lost 19 of 20) |

## Tested rules (every cell counted)

**Design:** 94 cells.
- F1: 40 cells, 2012-Sep 2025 daily.
- F2: 8 cells, May 2024-Sep 2025 with US hourly.
- F3: 18 cells, gold minute lead.
- F4: 8 event cells.
- F5: 12 macro/COT cells.
- F7: 8 Asian-hours cells.

BH ran across all 94. 19 passed the gates (n >= 30, futures net > 0, random p < 0.05, q < 0.10, 55% of years or months
green). All F1/F2 passes used the daily open print. A 36-day minute check showed that print is not tradable. So
amendment 1 froze tradable 09:05 versions (C1-C3) and the minute catch-up rule (C4) before the holdout was opened.

**Holdout:** 76 frozen cells (C1-C4 × commodity × exit × instrument), plus FOMC, plus the macro and event cells for
information. BH ran across the 76.

| rule (frozen) | instrument | design | holdout trades | holdout win% | holdout net/trade | holdout Rs/day | holdout max DD | green months | random p | BH q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **C2 follow US overnight, SILVER, 09:05 -> close** | SILVERMIC futures | daily-open +Rs 303/tr (t 5.3); 09:05 minute check -Rs 246/tr (31 d) | 166 | 56% | **+1,189** | **+1,115** | 29.6k | 10/13 | 0.000 | 0.019 | **pass** (futures; long +1,690, short +776) |
| C2 same | SILVERM 1-ITM option | - | 41 | 49% | -608 | -141 | 45.4k | 4/9 | 0.07 | 0.20 | fail |
| C1 follow MCX gap, SILVER, 09:05 -> close | SILVERMIC futures | daily-open +Rs 51/tr | 177 | 51% | +545 | +545 | 32.0k | 8/13 | 0.03 | 0.16 | fails BH |
| **C1 follow gap, NATGAS, 09:05 -> 14:00** | NATGASMINI futures | daily-open +Rs 196/tr (t 8.0, 14/14 yrs); minute -Rs 120 | 233 | 50% | **+346** | **+344** | 10.4k | 8/13 | 0.000 | 0.019 | **pass, but Jan 2026 = Rs 65k of Rs 81k** |
| C1 same | NATURALGAS 1-ITM option | - | 208 | 51% | +181 | +161 | 22.7k | 8/13 | 0.019 | 0.13 | fails BH (watch) |
| **C2 follow US, NATGAS, 09:05 -> 14:00** | NATGASMINI futures | daily-open +Rs 227/tr | 216 | 51% | +248 | +229 | 9.0k | 9/13 | 0.009 | 0.095 | pass, but Jan 2026 > 100% of the total |
| C2 same | NATURALGAS option | - | 192 | 52% | +51 | +41 | 29.2k | 8/13 | 0.08 | 0.20 | fail |
| C3 fade gap residual, NATGAS, 09:05 -> 14:00 | NATURALGAS option | daily-open +Rs 541/tr | 116 | 59% | +262 | +130 | 28.3k | 8/13 | 0.016 | 0.13 | fails BH (watch) |
| **C3 fade residual, GOLD, 09:05 -> 14:00** | GOLDTEN futures | daily-open +Rs 127/tr | 123 | 57% | +126 | +65 | 8.8k | 8/13 | 0.003 | 0.049 | pass; longs only (+392 vs -22); dies with a 10-min entry delay |
| C3 fade residual, CRUDE, 09:05 -> close | CRUDEOILM futures | daily-open +Rs 17/tr | 141 | 51% | +274 | +164 | 11.7k | 9/13 | 0.054 | 0.17 | fails BH |
| **C4 XAUUSD lead, GOLDM, 15 min, q99.7** | GOLDM 1-ITM option | futures +Rs 33/tr (31 tr); option: no prints in design | 128 | 44% | **+228** | **+123** | 13.0k | 5/8 | 0.008 | 0.095 | **pass (only option pass)**; longs +719, shorts -263 |
| C4 same | GOLDTEN futures | +Rs 33/tr, p 0.014 | 333 | 41% | +28 | +39 | 10.0k | 7/12 | 0.001 | 0.024 | pass, thin |
| C4 q99 (looser threshold) | futures / option | -Rs 11/tr | 987 / 454 | 37% / 41% | -58 / -186 | -241 / -354 | 58k / 96k | 2/12 / 3/11 | 0.00 / 0.08 | | fail: the catch-up is smaller than costs |
| C1/C2 crude and gold, any exit | futures and options | | 187-236 | 35-53% | -2,165 to +37 | | | | 0.11-0.86 | | fail |
| all C1-C3 option cells held to the close | options | | | | -62 to -2,284 | | up to 4.2 lakh | | | | fail (evening decay) |
| FOMC overnight ATM straddle (22:45 -> 09:30) | options | 2 events, both lost | 20 | 5% | -2,854 | | | | | | fail |
| EIA crude surprise follow (US hourly, to the close) | CRUDEOILM | -Rs 73/tr (68) | 53 | 40% | -151 | | | | 0.64 | | fail |
| EIA gas storage surprise follow | NATGASMINI | -Rs 147/tr (69) | 50 | 50% | +65 | | | | 0.24 | | fail |
| CPI/NFP first-hour follow, gold / silver | minis | -99 / -55 (31) | 22 | 32% / 36% | -290 / -482 | | | | 0.77 / 0.69 | | fail |
| DXY / DFII10 20-day trend, reversed -> gold, silver 20 days | minis | -81 to -232 | 13 | 38-54% | -2,084 to -18,496 | | | | 0.75-0.91 | | fail |
| COT managed-money extremes (contrarian) / follow | minis | -96 to +1,133 (37-50 tr, p > 0.10) | 0-13 | | | | | | | | fail |
| ES=F overnight risk-off -> gold / crude day | minis | -48 / -41 per tr | 260 | 49-50% | +54 / -339 | | | | 0.11 / 0.96 | | fail |
| Asian-hours long gold / silver, 09:30-14:30 | minis | -59 / -11 | 247 | 54% / 53% | -96 / -161 | | | | 0.35 / 0.47 | | fail |

Notes on the table:
- Rs/day counts all MCX sessions with data, not just trading days.
- Every option row is real Dhan option minutes, printed minutes only. Expiry day and the day before are skipped.
- The futures entries use the Dhan per-minute futures price (`spot`). A spot-check against real futures OHLC on the
  days it could be matched (Jun-Oct 2026, entering one minute later at the 09:06 open) gave the same net to within
  Rs 1-130 a trade, with 0-6 bp slippage (`out/robust_live.csv`). The spot price is not stale enough to fake these
  results.
- Sensitivity (post-hoc, chose nothing; `out/sens_holdout.csv`): SILVER C2 stays positive for entries from 09:05 to
  10:00 and exits from 17:00 to the close. Most of its profit comes after 17:00. NATGAS C1/C2 stays positive for every
  entry and exit tried. GOLD C3 turns negative with a 10-minute entry delay.
- **C5 (the same catch-up rule for silver, crude and gas)** was frozen in amendment 2.
  - Silver design window (5 Aug-30 Sep 2025, full Dukascopy XAGUSD) is done. XAGUSD leads SILVERM by 1 minute
    (lag-1 correlation 0.07; lag-0 0.68). After a q99.7 divergence (about 32 bp), SILVERM catches up 7 bp in 15 minutes,
    against an 8.5 bp SILVERMIC round trip. The frozen rule made +Rs 15 a trade on 25 trades (p 0.09) in design. It is
    weaker than gold.
  - The silver holdout and crude/gas did not run. Dukascopy kept returning HTTP 503 (shared with another agent), so no
    holdout day downloaded. C5 is still open. Expect it to be costs-bound, like gold futures.

## Why the patterns that survived might be real, and why they might not

- **Silver follow-US (C2):**
  - **Why it may be real:** the profit has two parts. US silver itself kept moving in its overnight direction during
    2024-2026 (+27 to +36 bp a day). And MCX silver's local premium caught up during the day (+14 to +45 bp a day).
  - **Why it may not last:** the holdout is one extraordinary year. Silver tripled. Indian premiums swung hard in the
    Oct 2025 shortage. The import duty went from 6% to 15% on 13 May 2026. The 36-day minute design check was negative.
  - Treat it as a regime trade.
- **Natural gas morning follow:** January 2026 (the cold snap) made 80-100% of the profit. In the other 12 months it is
  about flat.
- **GOLDM XAUUSD catch-up (C4):**
  - **Why it may be real:** a real microstructure lag. GOLDM is thinner than world gold and catches up within minutes.
  - **Why it may not last:** the option version was never tested in design (no prints). Profits came from calls in a
    rising gold market (puts lost). There are 125-128 trades in 8 months.
  - It needs a **real-time** XAUUSD feed. Yahoo's GC=F is delayed and its 1-minute bars look shifted.

## Paper-arm candidates (exact rules)

1. **ARM "US-night silver" (SILVERMIC futures, paper only).**
   - **Signal (09:05 IST):** s = ln(COMEX SI front month at 08:30 IST / the same at the previous MCX close,
     23:30 IST in US summer or 23:55 in US winter) + ln(USD/INR, same window). Any 10-minute-delayed quote is fine,
     because both prices are taken before entry.
   - **Entry:** at 09:05, buy 1 SILVERMIC if s > 0, sell 1 if s < 0. Skip the day if the near-month contract changed
     overnight.
   - **Exit:** 10 minutes before the MCX close (23:20 in summer, 23:45 in winter). No stop was tested. For risk, put a
     disaster stop at 3x the 20-day average absolute day move, and report it separately.
   - **Holdout:** 166 trades, +Rs 1,189 net per trade, 56% wins, max DD Rs 29.6k, 10 of 13 months green.
   - **Risk:** the short side is a naked short future. SILVERMIC margin is Rs 29-55k.
2. **ARM "XAU lead" (GOLDM 1-ITM option buy, paper only).**
   - **Needs:** a real-time XAUUSD 1-minute feed.
   - **Signal:** every minute, catch-up = ln(XAU_t / XAU_t-5) - ln(GOLDM_t / GOLDM_t-5). Trigger when |catch-up| > 24 bp.
   - **Entry:** buy the GOLDM 1-ITM call (catch-up > 0) or put (< 0) at the next minute, but only if it prints within
     2 minutes.
   - **Exit:** after 15 minutes. One position at a time. Skip expiry day.
   - **Holdout:** 128 trades, +Rs 228 per trade, about Rs 123 a day, max DD Rs 13k.
3. **Watch-list, not an arm:** NATURALGAS 1-ITM option in the direction of the 09:05 MCX gap, 09:05 -> 14:00. Holdout
   +Rs 181 per trade, q 0.13; it depends on January 2026.

Before any of these goes live: log real bid/ask at 09:05 for SILVERMIC, NATURALGAS and GOLDM options, and paper-trade
for 2-3 months.

## Data and timing notes

- **US data:**
  - Yahoo chart API (cookie+crumb): daily from 2000; 1-hour from 17 May 2024; 5-minute for 60 days; 1-minute for
    7 days.
  - FRED: DFII10, DGS10, DTWEXBGS, DEXINUS, WTI, Henry Hub.
  - EIA weekly crude stocks (WCESTUS1) and Lower-48 gas storage.
  - CFTC disaggregated COT, 2012-2026.
  - Dukascopy XAUUSD 1-minute (Oct 2023-Sep 2026, already local). XAGUSD was partial.
- **MCX data:**
  - Dhan continuous daily futures, 2012-2026.
  - Dhan minute futures price and options from 5 Aug 2025 (M3, STRAD-CRUDE caches).
  - Real futures OHLC for Jun-Oct 2026 (M3 `fut_live`).
- **No look-ahead:**
  - Every US value used at 09:00/09:05 IST comes from bars that closed by 08:30 IST (03:00 UTC). "Previous US day"
    means bars dated before the MCX date.
  - Gap-residual thresholds were frozen from the design window.
  - US DST is handled per date. The EIA release moves to Thursday after a Monday-Wednesday US holiday (detected from
    missing CL=F days).
- **Event dates:**
  - FOMC 2021-2026 is from federalreserve.gov.
  - CPI/NFP 2026 is from the BLS schedule pages. 2024-25 is from the standard calendar, including the shutdown-delayed
    Oct-Dec 2025 releases.
  - Consensus forecasts were not available, so "surprise" means the change versus the 5-year same-week average.

## Sources

- Elder, Miao and Ramchander (2012), "Impact of macroeconomic news on metal futures", *Journal of Banking & Finance*.
  Metals react within minutes, NFP has the biggest effect, and good news is bad for gold and silver.
  [Mountain Scholar](https://mountainscholar.org/handle/10217/206884).
- Gold price discovery: US futures lead in incorporating information intraday; MCX showed little price-discovery role
  after 2013 ([UTS repository, JFM paper](https://opus.lib.uts.edu.au/bitstream/10453/41414/4/GoldILS%20JFutMkt%20Forthcoming.pdf);
  [publishingindia MCX gold study](https://publishingindia.com/storage/PDFBrochures/587.pdf)). MCX and NYMEX energy
  prices are cointegrated, with world markets leading India ([serialsjournals](https://serialsjournals.com/abstract/15217_27.pdf)).
- Gold rises in Asian hours and falls in US hours: [CBS thesis](https://research.cbs.dk/en/studentProjects/gold-price-dynamics-around-the-clock/);
  the WGC H1 2026 split as reported by [FXStreet](https://www.fxstreet.com/analysis/a-strange-dichotomy-gold-up-on-the-year-in-asian-markets-down-big-in-the-west-202607131959).
  On our data it is too small to trade.
- Gold vs real yields broke down after 2022 because of central-bank buying ([Janus Henderson](https://www.janushenderson.com/en-us/advisor/article/chart-to-watch-whats-behind-the-divergence-between-gold-and-real-treasury-yields/)).
  Consistent with our F5 "no".
- COT contrarian signals are weak and not significant in energy ([Aalto thesis](https://aaltodoc.aalto.fi/items/b84b07d6-3bd2-4040-bbcf-052b938b1f74)).
- EIA inventory reactions and pre-release drift: Halova, Kurov & Kucher (2014, *JFM*); Kurov et al. (2019, *JFQA*). See
  MCX_GUIDE.md section 3.
- Schedules: [FOMC calendar](https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm),
  [BLS CPI](https://www.bls.gov/schedule/news_release/cpi.htm), [BLS Employment Situation](https://www.bls.gov/schedule/news_release/empsit.htm).
- Earlier lab work: MCX_GUIDE, MCX_TREND, MCX_INTRADAY, MCX_OPTIONS, NN_CRUDE, STRAD_CRUDE, HUNT_H27.
