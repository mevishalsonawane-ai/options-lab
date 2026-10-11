# R13 — "Majority Rule" (MR 14, NIFTY weekly): is it profitable?

*Research only. No app code changed. Scripts: `research/hunt/r13/{data,study,boss}.py`. Raw outputs (all tables, every cut): `scratchpad/hunt/r13/study_out.md` and `boss_out.md`.*

**Definition used.** MR(14) = 100 × (number of the last 14 weekly closes that are above the previous week's close) / 14. It moves in steps of 7.14, so "touches 28.5" means **only 4 of the last 14 weeks closed up**. The alternative TradingView-style definition, with a weekly candle counted as up when close > open, was also tested ("co" below). Equal closes never happen on weekly NIFTY (0 ties in 1,084 weeks), so how ties are counted does not matter.

**Data.** NIFTY 50, BANKNIFTY and India VIX daily bars from the local Dhan candles, **2006-01-02 to 2026-10-05**, topped up to **2026-10-09** from Yahoo (^NSEI, ^NSEBANK, ^INDIAVIX). Cross-check: on 4,673 overlapping days the Dhan and Yahoo NIFTY closes match exactly (0 days off by more than 0.5%). VIX data starts 2008-03-03. Weekly bars run Monday–Friday, using the last trading day's close (1,084 weeks). Caveat: before 2011, the index's daily open is close to the previous close, so the colour of a weekly candle in those years ≈ the week's direction.

---

## 1. Verdict (Boss's exact rule first)

**Boss's rule:** *"If MR(14) touches 28.57 and the NEXT weekly candle is green, NIFTY bounces more than 1,000 points."*

**Short answer: not proven, and not a money-maker on its own.**

- **How often it happens.** MR(14) has touched 28.57 only **13 times in 20 years**: 2008 ×4, 2012, 2013, 2019, 2020 ×3, 2022, 2025, and **now (week ending 2026-10-01)**. Because of the clusters, that is about **8 independent episodes**. No p-value below can be strong with so few.
- **Two readings of "next candle green".**
  - *Strict* (the very next week must be green, otherwise the setup is void): 9 signals, 4 void.
  - *Wait* (take the first green week, which came within 1–2 weeks every time): 12 signals.
  - The strict reading throws away the three best bounces (Nov-2008, Mar-2020, May-2020), because in those cases the market fell one more week before turning.
- **">1,000 points": partly true, but it is mostly about how high NIFTY is now, not about the signal.**
  - *Wait reading, entry at the green week's close:* NIFTY rose ≥1,000 points from entry at some point within 13 weeks in **50%** of signals, against **27%** for random green weeks (bootstrap **p = 0.07**). Within 26 weeks: 67% vs 48% (p = 0.17).
  - Since 2015 it happened 6 out of 6 times within 13 weeks, against 38% at random (p = 0.003). But 1,000 points is ~4–5% at today's levels and was 20–35% in 2008, so the points test gets easier every year.
  - *Strict reading:* 44% vs 27% within 13 weeks (p = 0.20). Not significant.
  - **Does it rise 1,000 before falling 500?** Wait reading **50%** vs **36%** random (p = 0.23). Strict reading **33%** vs 36% (p = 0.68). In %, "+4% before −2%" happened 42% vs 39% (p = 0.53). **This is how NIFTY normally moves, not something special.** The bounce does usually come, but often only after a dip of more than 500 points first (Mar-2008, Apr-2020, May-2022, Aug-2025 all hit −500 first).
  - **Fair % view:** the median best rise within 13 weeks is +10.8% vs +6.9% for random green weeks (p = 0.10), and within 26 weeks +17.2% vs +11.4% (p = 0.006). So bounces after a touch are bigger than normal in % terms, but the "−500 first" problem remains.
- **Waiting for the green candle makes the trade worse, not better.** The green week itself carries much of the bounce (+8% in Mar-2008, +5% in May-2013).
  - *Plain touch* (buy next week's open, hold 4 weeks): average **+5.8%**, 9/9 winners.
  - *Boss rule* (buy after the green week, hold 4 weeks): average **+1.1% to +3.0%**.
- **Is it profitable as a trading system?**
  - **Per trade, yes, on a long hold.** Plain touch, hold 13 weeks or until MR > 50: 8 trades, 8 wins, average +10.3%, **+₹4.2 L** on 1 lot (65) of futures since 2008. Boss rule (wait reading), same exit: 8 trades, 88% winners, **+₹2.8 L**.
  - **As a use of capital, no.** It is in the market only ~7% of the time. ₹1 lakh in an ETF grows to ₹1.6–2.1 L (CAGR **2.3–3.8%**) against buy-and-hold NIFTY at **9.6%** CAGR over the same period.
  - **With Boss's +1,000 target / −500 stop on futures, it is about break-even:** 8 trades, 50% winners, +₹39 k in total over 18 years.
- **Option buying (Boss's actual style), Black-Scholes approximation using India VIX: roughly break-even to losing.**
  - Boss rule (wait), 35-day ATM call held 4 weeks: 9 trades, 44% winners, **−₹18 k** in total.
  - 63-day call held 8 weeks: 38% winners, **−₹29 k**.
  - Plain touch, 35-day call held 4 weeks: +₹56 k over 9 trades, but almost all of it came before 2015. Since 2020: 4 trades, about +₹13 k.
  - The reason: VIX is high right after a sell-off, so the calls are expensive, and the bounce comes too slowly for 1-month options.
- **Overbought mirror (MR ≥ 71.43):** 41 touches. **No reversal and no continuation edge** (4-week return +1.5% vs +0.9% baseline; p = 0.78 for a reversal).
- **Confidence: low to moderate.** The plain "deep oversold → higher in 1–2 months" effect is fairly consistent, with excess returns of +5 points at 4 and 8 weeks and p ≈ 0.001–0.01 before the clustering adjustment. **The "+1,000 points" claim and the green-candle confirmation do not add anything measurable.**

**Live status (as of 2026-10-09).**
- MR(14) touched 28.57 in the week ending 2026-10-01, with NIFTY at 22,422.
- The next week (5–9 Oct) closed **red by 12 points** (open 22,532.4 → close 22,520.4, checked on both Dhan and Yahoo). It is almost a doji.
- Under the *strict* reading the setup is void. Under the *wait* reading it is still armed, and the first green weekly close triggers it.
- On the close>open definition, MR is at 14.29, matching its deepest reading on record (April 2013).

---

## 2. Boss's exact rule — the details

### 2a. Every setup (MR cc touch → first green week). Entry E1 = the green week's Friday close. MFE = best rise from entry, in points.

| touch week | green week | wait | strict? | entry | best rise 4w | 8w | 13w | 26w | 26w % | +1000 before −500? | +4% before −2%? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2008-03-19 | 2008-03-28 | 1 | yes | 4942 | +269 | +357 | +357 | +357 | +7.2% | stop | stop |
| 2008-04-04 | 2008-04-11 | 1 | yes | 4778 | +521 | +521 | +521 | +521 | +10.9% | stop | target |
| 2008-11-14 | 2008-11-28 | 2 | **void** | 2755 | +355 | +392 | +392 | +1938 | +70.3% | target | stop |
| 2008-12-05 | 2008-12-12 | 1 | yes | 2921 | +226 | +226 | +226 | +1772 | +60.7% | target | target |
| 2012-05-18 | 2012-05-25 | 1 | yes | 4920 | +270 | +428 | +528 | +979 | +19.9% | neither (26w) | stop |
| 2013-04-12 | 2013-04-18 | 1 | yes | 5783 | +446 | +446 | +446 | +469 | +8.1% | stop | target |
| 2019-09-06 | 2019-09-13 | 1 | yes | 11076 | +619 | +958 | +1192 | +1355 | +12.2% | target | stop |
| 2020-03-27 | 2020-04-09 | 2 | **void** | 9112 | +777 | +1217 | +1782 | +2914 | +32.0% | target | stop |
| 2020-04-24 | 2020-04-30 | 1 | yes | 9860 | +72 | +693 | +1482 | +2166 | +22.0% | stop (day 1) | stop |
| 2020-05-15 | 2020-05-29 | 2 | **void** | 9580 | +973 | +1660 | +2106 | +3566 | +37.2% | target | target |
| 2022-05-13 | 2022-05-20 | 1 | yes | 16266 | +528 | +528 | +1726 | +2348 | +14.4% | stop | stop |
| 2025-08-08 | 2025-08-14 | 1 | yes | 24631 | +522 | +818 | +1615 | +1742 | +7.1% | target | target |
| 2026-10-01 | — | — | void / waiting | | | | | | | | |

Barrier tests use daily highs and lows, with a 26-week cap. If the target and the stop were both hit on the same day, it is counted as a stop.

### 2b. Signals vs random green weeks (baseline = every green week in the same period, same entry; 10,000-rep bootstrap)

| test (entry E1, full history) | Wait reading (n=12) | Strict (n=9) | random green weeks (n=578) | p wait / strict |
|---|---|---|---|---|
| median best rise within 4w, pts | +484 | +446 | +269 | 0.18 / 0.43 |
| median best rise within 13w, pts | +860 | +528 | +596 | 0.09 / 0.27 |
| median best rise within 26w, pts | +1757 | +1355 | +945 | 0.06 / 0.38 |
| median best rise within 13w, % | +10.8% | +10.6% | +6.9% | 0.10 / 0.29 |
| median best rise within 26w, % | +17.2% | +12.2% | +11.4% | **0.006** / 0.18 |
| reached +1000 pts within 4w / 8w | 0% / 17% | 0% / 0% | 6% / 17% | — |
| reached +1000 pts within 13w | 50% | 44% | 27% | 0.07 / 0.20 |
| reached +1000 pts within 26w | 67% | 56% | 48% | 0.17 / 0.46 |
| **+1000 before −500** | **50%** | **33%** | **36%** | 0.23 / 0.68 |
| +1000 before −1000 | 50% | 33% | 43% | 0.42 / 0.82 |
| **+4% before −2%** (fair across years) | **42%** | **44%** | **39%** | 0.53 / 0.48 |
| +4% before −4% | 67% | 67% | 56% | 0.34 / 0.39 |
| median weeks to +1000 (when hit) | 9.0 | 9.4 | 11.4 | |

**Sub-periods (wait reading, E1):**
- **2015+ (n=6):** reached +1000 pts within 13w 100% vs 38% (p = 0.003); +1000 before −500 67% vs 42% (p = 0.21); +4% before −2% 33% vs 38%.
- **2020+ (n=5):** 13w 100% vs 62% (p = 0.09); +1000 before −500 60% vs 43% (p = 0.38).

**Entry choice:** buying at the next week's open (E2) instead of the green week's close (E1) gives the same picture.

**On the close>open MR definition ("co"; 15 wait-reading signals):** +1000 before −500 was 60% vs 36% (p = 0.046), and +4% before −2% was 60% vs 39% (p = 0.08). That is a little better than on our definition, but it uses a different set of signal weeks, and a result that appears under one indicator definition and not the other is not robust.

### 2c. Trades on the exact rule (cc MR, entry at the next week's open after the green week, full history 2006–2026)

Columns: futures = 1 lot of 65; ETF = ₹1 lakh compounding; buy-and-hold CAGR over the same period = **9.6%**.

| reading | exit | trades | win% | avg % | futures total ₹ | worst ₹ | ETF end ₹ (CAGR) | 35d ATM call: win / avg / total ₹ | 63d ATM call: win / avg / total ₹ |
|---|---|---|---|---|---|---|---|---|---|
| wait | hold 4w | 9 | 89 | +2.7% | +45,426 | −70,141 | 1,24,353 (1.1%) | 44% / +20% / −17,791 | 44% / +14% / −16,821 |
| wait | hold 8w | 8 | 50 | +2.9% | +95,985 | −25,846 | 1,22,597 (1.0%) | — | 38% / −10% / −29,081 |
| wait | hold 13w | 8 | 75 | +4.8% | +2,49,101 | −57,776 | 1,38,817 (1.6%) | — | — |
| wait | 13w or MR>50 | 8 | 88 | +6.4% | +2,78,083 | −2,805 | 1,60,705 (2.3%) | — | 75% / +73% / +50,918 (n=4) |
| wait | **+1000 / −500 pts, 13w cap** | 8 | 50 | +2.3% | **+38,569** | −36,833 | 1,16,481 (0.7%) | 0% / −75% / −46,660 (n=2) | 50% / +36% / +11,495 (n=4) |
| wait | +4% / −2%, 13w cap | 12 | 33 | 0.0% | −60,721 | −36,676 | 97,608 (−0.1%) | 33% / −3% / −30,848 | 33% / −4% / −35,782 |
| strict | hold 4w | 8 | 62 | +1.4% | −10,174 | −70,141 | 1,09,523 (0.4%) | 38% / +8% / −35,886 | 38% / +7% / −32,590 |
| strict | 13w or MR>50 | 8 | 88 | +4.8% | +2,38,424 | −16,416 | 1,42,549 (1.7%) | — | 75% / +73% / +50,918 (n=4) |
| strict | **+1000 / −500 pts, 13w cap** | 8 | 38 | −0.6% | **−69,313** | −36,833 | 92,322 (−0.4%) | 0% / −70% / −63,026 (n=3) | 25% / +19% / −17,299 (n=4) |

Notes on the option columns:
- An option is only priced when the hold fits inside its life. "—" means the trade was held too long for that option, and the "n=" in a cell is how many trades fitted.
- Entering at the green week's close (E1) changes totals by only a few percent.
- Since 2015 there are 4–6 trades per row. Sub-period tables are in `boss_out.md`.

Trade list for Boss's rule taken literally (wait reading, buy at the next open, +1000 target / −500 stop, 13-week cap):

| green week | entry | exit | why | entry | exit | pts | ₹ 1 lot fut |
|---|---|---|---|---|---|---|---|
| 2008-03-28 | 2008-03-31 | 2008-06-09 | stop | 4942 | 4442 | −500 | −36,818 |
| 2008-11-28 | 2008-12-01 | 2009-02-27 | time | 2755 | 2764 | +8 | −2,805 |
| 2012-05-25 | 2012-05-28 | 2012-08-24 | time | 4932 | 5387 | +455 | +24,104 |
| 2013-04-18 | 2013-04-22 | 2013-07-19 | time | 5790 | 6029 | +239 | +9,382 |
| 2019-09-13 | 2019-09-16 | 2019-11-06 | target | 10995 | 11995 | +1000 | +58,477 |
| 2020-04-09 | 2020-04-13 | 2020-06-03 | target | 9104 | 10108 | +1004 | +59,818 |
| 2022-05-20 | 2022-05-23 | 2022-06-13 | stop | 16291 | 15791 | −500 | −36,833 |
| 2025-08-14 | 2025-08-18 | 2025-08-29 | stop | 24938 | 24438 | −500 | −36,757 |

The 2008-12 and 2020-04-30 / 2020-05-29 signals were skipped because a trade was already open.

---

## 3. Plain touch event study (Boss's original framing)

**Method.** Signal = a new weekly close with MR(14) ≤ 28.57 (above it the previous week). Enter at the next week's open; the return runs to the close N weeks later. Baseline = all weeks in the same period. The p-value is from 10,000 bootstrap draws of the same number of random weeks.

### Oversold, new touches, cc

| period | hold | n | mean | median | hit% | baseline mean | baseline hit% | p(mean > base) | avg / worst MAE |
|---|---|---|---|---|---|---|---|---|---|
| 2006+ | 1w | 13 | +2.05% | +1.27% | 69 | +0.15% | 54 | 0.007 | −1.9% / −11.0% |
| 2006+ | 2w | 12 | +3.92% | +3.14% | 75 | +0.38% | 56 | 0.001 | −2.2% / −11.0% |
| 2006+ | 4w | 12 | **+6.22%** | +6.73% | 92 | +0.86% | 61 | **0.001** | −2.5% / −11.0% |
| 2006+ | 8w | 12 | **+7.40%** | +6.85% | 100 | +1.82% | 64 | **0.011** | −3.0% / −11.0% |
| 2006+ | 13w | 12 | +7.83% | +9.05% | 75 | +3.00% | 64 | 0.058 | −5.1% / −16.9% |
| 2006+ | 26w | 12 | +18.38% | +14.92% | 83 | +6.18% | 69 | 0.006 | −6.9% / −19.8% |
| 2015+ | 4w | 6 | +3.60% | +2.21% | 83 | +0.66% | 60 | 0.034 | −2.6% / −4.9% |
| 2015+ | 13w | 6 | +15.34% | +16.29% | 100 | +2.44% | 64 | <0.001 | −3.2% / −4.9% |
| 2020+ | 4w | 5 | +3.89% | +2.25% | 80 | +0.78% | 59 | 0.057 | −2.7% / −4.9% |
| 2020+ | 13w | 5 | +16.61% | +20.89% | 100 | +3.23% | 66 | <0.001 | −3.4% / −4.9% |

**All weeks with MR ≤ 28.57** (21 weeks, including repeats): 4-week return +7.57% vs +0.86% (p < 0.001), with 95% of them positive.

**The 2015+ and 2020+ figures at 13 weeks** are driven by the April–May 2020 COVID recovery, which accounts for 3 of the 5–6 signals.

### Every signal (cc, from the next week's open)

| signal week | NIFTY close | entry | 1w | 2w | 4w | 8w | 13w | 26w | MAE 13w |
|---|---|---|---|---|---|---|---|---|---|
| 2008-03-19 | 4574 | 4577 | +8.0% | +1.5% | +8.3% | +12.7% | −5.0% | −7.2% | −5.3% |
| 2008-04-04 | 4647 | 4631 | +3.2% | +7.1% | +12.9% | +5.2% | −13.3% | −17.6% | −16.9% |
| 2008-11-14 | 2810 | 2813 | −4.3% | −2.1% | +3.8% | +2.1% | +4.8% | +30.5% | −11.0% |
| 2008-12-05 | 2714 | 2715 | +7.6% | +13.4% | +12.2% | +5.9% | −3.5% | +69.0% | −6.5% |
| 2012-05-18 | 4891 | 4888 | +0.7% | −1.0% | +5.1% | +6.9% | +9.8% | +14.0% | −2.4% |
| 2013-04-12 | 5529 | 5508 | +5.0% | +6.6% | +10.6% | +6.8% | +9.1% | +10.7% | −0.1% |
| 2019-09-06 | 10946 | 10937 | +1.3% | +3.1% | +2.2% | +8.7% | +9.0% | +0.5% | −2.4% |
| 2020-03-27 | 8660 | 8386 | −3.6% | +8.7% | +9.2% | +7.8% | +23.8% | +31.8% | −3.9% |
| 2020-04-24 | 9154 | 9260 | +6.5% | −0.1% | −2.4% | +10.6% | +20.9% | +28.8% | −4.9% |
| 2020-05-15 | 9137 | 9158 | −1.3% | +4.6% | +8.9% | +17.6% | +22.1% | +38.9% | −3.8% |
| 2022-05-13 | 15782 | 15845 | +2.7% | +3.2% | +2.3% | +2.4% | +11.7% | +15.8% | −4.2% |
| 2025-08-08 | 24363 | 24372 | +1.1% | +2.0% | +1.5% | +2.1% | +4.6% | +5.4% | −0.1% |
| 2026-10-01 | 22422 | 22532 | −0.1% | open | | | | | |

Note that **2011 and 2016 are not on the list**: MR(14) never fell to 28.57 in those sell-offs.

### Close > open definition ("co"), 16 touches

- 4-week return +5.24% vs +0.86% (p = 0.002), 93% positive.
- 13 weeks: only +4.41% vs +3.00% (p = 0.29).
- 26 weeks: +11.8% vs +6.2% (p = 0.09).
- **Same conclusion:** a 1–2 month bounce, weaker beyond that.

### Overbought mirror (MR ≥ 71.43), 41 new touches (cc)

| hold | mean | baseline | p (reversal: mean < base) | p (continuation: mean > base) |
|---|---|---|---|---|
| 1w | −0.06% | +0.15% | 0.29 | 0.71 |
| 4w | +1.53% | +0.86% | 0.78 | 0.22 |
| 13w | +4.84% | +3.00% | 0.87 | 0.13 |
| 26w | +5.56% | +6.18% | 0.42 | 0.58 |

There is no usable signal either way: shorting at 71.43 would have lost money. The co definition (28 touches) gives the same result. The full per-signal list is in `study_out.md`.

---

## 4. Trading the plain touch (buy at the next week's open after the touch week)

**Cost model.**
- **Futures:** 1 lot = **65** (the current NIFTY lot since 2025-12-31 per `docs/lot-history.md`; it was 75 during 2025, so multiply ₹ by 1.15 for a 75 lot). The same lot is applied to all history so years can be compared.
- **Futures charges:** brokerage ₹40 per round trip plus GST, exchange fee, stamp duty, and STT on the sell side by era (0.017% / 0.01% / 0.02%). Plus 1 point of slippage each side, a carry drag of 5.5% a year on notional (a long futures position gives up the premium), and an extra round trip plus slippage for every monthly rollover.
- **Margin:** about ₹1.2–1.5 L for 1 lot, **which is more than Boss's ₹1 L capital**. One lot is ~₹14.6 L notional at 22,500, so this is about 15× leverage.
- **ETF:** ₹1 L compounding, all-in while in a trade; 0.05% spread per side plus ₹20 per order plus stamp, exchange fee and STT. Idle cash earns 0%.

| period | exit | trades | win% | avg % | futures total ₹ | futures max DD ₹ | 1 lot held all period ₹ | ETF end ₹ | ETF CAGR | B&H CAGR (maxDD) | time in market |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2006+ | hold 4w | 9 | 100 | +5.8% | +2,27,586 | 0 | +4,41,108 | 1,62,706 | 2.4% | 9.6% (−59%) | 3% |
| 2006+ | hold 8w or MR>50 | 8 | 100 | +6.6% | +2,16,902 | 0 | +4,41,108 | 1,63,936 | 2.4% | 9.6% | 6% |
| 2006+ | hold 13w | 8 | 88 | +8.5% | +3,67,628 | −19,818 | +4,41,108 | 1,85,642 | 3.1% | 9.6% | 9% |
| 2006+ | hold 13w or MR>50 | 8 | 100 | +10.3% | +4,21,681 | 0 | +4,41,108 | 2,13,795 | 3.8% | 9.6% | 7% |
| 2015+ | hold 13w or MR>50 | 4 | 100 | +11.9% | +3,30,585 | 0 | +2,92,704 | 1,54,767 | 3.8% | 8.9% (−35%) | 7% |
| 2020+ | hold 13w or MR>50 | 3 | 100 | +12.9% | +2,73,417 | 0 | +2,01,416 | 1,42,112 | 5.3% | 9.4% | 10% |

**The co definition** gives similar results: 10–11 trades, 70–100% winners, futures +₹2.2–3.8 L, ETF CAGR 2.3–4.1%.

**In plain words:** almost every trade won, but there are only 8–9 trades in 20 years. One lot held long the whole time made about as much (+₹4.4 L) without any rule. As a way to deploy ₹1 lakh, it badly trails just holding NIFTY, unless the idle 93% of the time is put to work elsewhere.

### Option-buying approximation (plain touch)

**This is an APPROXIMATION.** Black-Scholes, with IV = India VIX on the previous close at entry and India VIX at exit, no skew, a 1% spread each side, STT 0.1% on the sell, and ₹47 brokerage. Strike = ATM rounded to 50; lot 65. Signals before March 2008 are excluded (no VIX).

| period | option / exit | trades | win% | avg return on premium | total ₹ | avg premium ₹ |
|---|---|---|---|---|---|---|
| 2008+ | 35-day call, hold 4w | 9 | 67 | +55% (median +15%) | +56,091 | 22,543 |
| 2008+ | 63-day call, hold 4w | 9 | 56 | +38% | +50,464 | 30,943 |
| 2008+ | 63-day call, hold 8w or MR>50 | 8 | 50 | +45% | +23,392 | 29,736 |
| 2015+ | 35-day call, hold 4w | 5 | 60 | +11% | +15,354 | 30,800 |
| 2015+ | 63-day call, hold 8w | 4 | 25 | +18% (median −34%) | −12,799 | 42,788 |
| 2020+ | 35-day call, hold 4w | 4 | 50 | +10% | +13,033 | 34,554 |
| 2020+ | 63-day call, hold 8w | 3 | 0 | −35% | −52,013 | 49,669 |

**Why the calls struggle:**
- After a sell-off VIX is high (it was 70 in Nov-2008 and Mar-2020), so the premium is expensive.
- IV collapses as the market bounces, which cuts the call's value even when the market rises.
- The bounce often takes more than 4 weeks to arrive.

Example: the March-2020 signal was a strong bounce, yet its call *lost* 7%.

**On the co definition** the approximation looks better: 35-day call, +₹1.0 L over 10 trades. That depends heavily on the 2019 signals, which appear only under that definition.

---

## 5. Robustness: is 14 / 28.57 special? (Not used to pick anything.)

**Method.** For each length and each threshold step: 13-week excess return over baseline (oversold, new touches, cc, full history, p = bootstrap).

| L | threshold (k up-weeks of L) | n | 4w excess | 13w excess | p13 |
|---|---|---|---|---|---|
| 10 | 2 (20%) | 7 | +1.9 | +0.8 | 0.40 |
| 10 | 3 (30%) | 29 | +0.6 | +2.9 | 0.07 |
| 10 | 4 (40%) | 52 | −0.6 | +2.4 | 0.06 |
| 12 | 3 (25%) | 9 | +6.9 | +5.4 | 0.06 |
| 12 | 4 (33%) | 25 | −0.3 | −1.1 | 0.71 |
| 12 | 5 (42%) | 44 | −0.4 | +3.4 | 0.02 |
| **14** | **4 (28.6%)** | **12** | **+5.4** | **+4.8** | **0.06** |
| 14 | 5 (35.7%) | 32 | +0.8 | −0.1 | 0.52 |
| 14 | 6 (42.9%) | 45 | −0.7 | +3.4 | 0.02 |
| 16 | 5 (31%) | 7 | +8.2 | +4.4 | 0.13 |
| 16 | 6 (37.5%) | 27 | +0.5 | −1.1 | 0.71 |
| 20 | 7 (35%) | 12 | −1.5 | +2.9 | 0.17 |
| 20 | 8 (40%) | 23 | +1.3 | −0.7 | 0.61 |

**What this shows.**
- **The surface is not smooth.** Only the most extreme reachable step at each length (L12 k3, L14 k4, L16 k5; n = 7–12 each) shows a large 4-week bounce.
- One step less extreme (L14 k5, 32 signals), the edge disappears (+0.8 / −0.1).
- L20 shows nothing.
- So 28.57 is not a magic number. It is simply "the deepest reading MR(14) normally reaches", which marks a deep, prolonged sell-off. The edge is the well-known "buy after a deep sell-off" effect, and it rests on about 8 episodes.
- **Overbought:** no cell for any length or threshold shows a reliable reversal.

**Daily chart.**
- MR(14) on daily bars (≤4 of the last 14 days up; 94 touches): forward returns are **below** baseline at every horizon (20-day −0.7 points, p(mean > base) = 0.88).
- The co version gives +0.4% at 5 days in 2011+ (p = 0.02), which disappears from 2015 on.
- **The weekly effect does not carry over to the daily chart.**

**40-week MA trend filter.**
- 11 of the 12 weekly signals came with NIFTY *below* its 40-week MA. Only one, Aug-2025, was a "dip in an uptrend": it gained +1.5% in 4 weeks and +4.6% in 13 weeks.
- A "trade only above the 40w MA" filter therefore leaves almost no trades.
- Signals below the MA behave like the full set: +6.7% at 4 weeks, p = 0.001.

---

## 6. As a direction filter for the intraday bots

**Measure:** share of up-days (daily close > open), 2011 onward.

| index | days counted | n days | up-day % | baseline | avg open→close | p |
|---|---|---|---|---|---|---|
| NIFTY | week right after a new touch | 40 | 57.5% | 46.6% | +0.02% | 0.17 |
| NIFTY | 4 weeks after a new touch | 147 | 49.0% | 46.6% | +0.04% | 0.56 |
| NIFTY | any week after a week with MR ≤ 28.57 | 62 | 61.3% | 46.6% | +0.28% | 0.02 |
| BANKNIFTY | week right after a new touch | 40 | 55.0% | 47.9% | −0.11% | 0.37 |
| BANKNIFTY | 4 weeks after a new touch | 147 | 46.3% | 47.9% | −0.03% | 0.70 |
| BANKNIFTY | any week after a week with MR ≤ 28.57 | 62 | 61.3% | 47.9% | +0.31% | 0.03 |

**Not worth wiring in.**
- The flag is active on only ~4–8 days a year on average, and on zero days in most years.
- The one "significant" row is 62 days from about 8 clustered episodes.
- Over the 4 weeks after a touch there is no tilt at all.

At most it is a "don't be aggressively short this week" note for a human. It is not a bot input.

---

## 7. Caveats and look-ahead checks

- **Few signals.** There are 12 completed touches (13 with the live one), about 8 independent episodes (2008 ×4 and 2020 ×3 are clustered).
  - The bootstrap treats each signal as independent and its forward windows overlap, so **the p-values are optimistic**. Read p = 0.01 as roughly 0.05.
  - Several readings and horizons were tested. Boss's exact rule does not clear the bar on any of the "+1,000 before −500" style tests.
- **Regime.** Every touch except Aug-2025 came in a bear phase, below the 40-week MA. The 2015+/2020+ results lean heavily on the 2020 COVID V-shape. The 2008 signals show that a touch can come early in a crash: Apr-2008 was still −17% at its worst 13 weeks later.
- **The points claim depends on the index level.** +1,000 points was ~35% in 2008 and ~4% in 2026. Use the % rows to compare across years.
- **Look-ahead checks.**
  - MR uses only completed weekly closes.
  - Entries are at the next week's open (or at the Friday close of the green week for E1, which assumes you buy in the last minutes knowing the candle is green).
  - The MR > 50 exit is taken at the next week's open.
  - Barrier tests count a same-day target-and-stop as a stop.
  - Baselines are drawn from the same period.
  - The robustness grid was run *after* fixing Boss's 14 / 28.57 rule and was not used to choose anything.
- **Data.** The daily index open before 2011 ≈ the previous close, which affects candle colour in the 2008 signals. VIX starts in 2008. The option prices are a model, not real quotes; real ATM calls after a crash also carry skew and wider spreads.
- **Lot.** The brief said 75; the repo's lot history shows **65 since 2025-12-31**. ₹ figures use 65 (×1.15 for 75). Historical lots were different, which is why one lot is applied to all years.
- **TradingView's built-in indicator** may still differ, for example in how its first bar or weekly session is handled. Both up-week definitions were tested, and the conclusions are the same: a 1–2 month bounce after the plain touch, no special "+1,000" property, no overbought signal.
