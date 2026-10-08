# How the Indian index market moves: who, why, when, how, and what it means for an option buyer

Hunt h41, written 8 Oct 2026, for Boss.

- Code for every number: `research/hunt/h41/`
  - `intraday.py`: volatility by minute, when the high/low is set, first-hour direction, gaps, expiry days, regimes.
  - `volume_moves.py`: volume by time of day, the 15:00 minute, how far the index moves in 15/30/60 minutes.
  - `cas.py`: the closing minutes before and after the Closing Auction Session (3 Aug 2026).
- Logs and tables: `scratchpad/hunt/h41/` (`intraday.log`, `volume_moves.log`, `cas.log`, `*.csv`).
- Source notes and downloaded reports: `scratchpad/hunt/h41/notes.md`, `scratchpad/hunt/h41/raw/` (SEBI July 2025
  study, NSE Market Pulse Sept 2026, the "Animal Spirits on Steroids" paper, parsed to text with `python -I`).
- Data: our index 1-minute candles. NIFTY Aug 2020 to 5 Oct 2026 (1,515 clean days), BANKNIFTY and FINNIFTY from Aug/Oct
  2021, MIDCPNIFTY from Jan 2022, SENSEX from May 2023. Option and stock minute volumes for the volume section.
- No option P&L was computed here. Every "can / cannot" for the buyer points to the study that tested it.

Three periods are compared throughout:

| label | dates | what changed |
|---|---|---|
| P1 | before 20 Nov 2024 | many weekly expiries (NIFTY, BANKNIFTY, FINNIFTY, MIDCP, SENSEX) |
| P2 | 20 Nov 2024 to 3 Jul 2025 | one weekly index per exchange; bigger lots; upfront premium |
| P3 | from 4 Jul 2025 (Jane Street interim order) | plus: NSE expiry moved to Tuesday (1 Sep 2025), intraday position limits (1 Oct 2025), F&O pre-open (8 Dec 2025), STT up (1 Apr 2026), closing auction (3 Aug 2026) |

---

## Verdict (read this first)

1. **Who moves the index:** big money moving through a few heavyweight stocks and index futures. That means foreign
   funds (FPIs), domestic funds (mutual funds fed by Rs 31-32 thousand crore of SIPs a month), index arbitrage desks
   and fast prop/HFT firms. Retail option buyers are the biggest *crowd* in index options, but they are not what moves
   the index. **Evidence.**
2. **Who is on the other side of a retail option buyer:** mostly prop trading firms with co-located algorithms.
   - Prop is 46-49% of index-option premium turnover. Individuals are 39-41%. Foreign investors are about 7%.
   - About half of index-option turnover comes from co-located servers.
   - 91% of individual F&O traders lost money in FY25, Rs 1.06 lakh crore in total.
   - In FY24, 96-97% of prop and FPI profits came from algorithms. **Evidence.**
3. **When it moves:** the first 15 minutes are about **2× as wild** as the average minute of the day, and 09:30-10:00
   about 1.3×. 11:00-13:00 is the quietest stretch, at about 0.85×. Activity picks up again after 14:00.
   - On **3 days in 4**, the day's high or low is already in place by 10:15.
   - But most of that early clustering follows simply from the morning being wilder. It is **not** a sign that someone
     "set the trap" at 9:20.
4. **How it unfolds: the morning does not tell you the afternoon.**
   - After the first hour, the rest of the day continues in the same direction only **49-54%** of the time. That is a
     coin flip, on every index and in every period.
   - Small gaps (under 0.3%) usually get filled, but the distance is tiny. Gaps over 0.6% mostly do **not** fill.
     Big gaps hold: the index closes on the gap's side of yesterday's close 89-97% of the time.
   - Expiry days are a little wilder **in the last hour**, not all day.
5. **What changed after Nov 2024 and after the Jane Street order:**
   - Days got calmer: NIFTY's average range fell from 1.08% to 0.89%. That is explained by lower VIX (16.6 → 13.5). The
     range per unit of VIX barely moved.
   - The opening got relatively more important. The day's extreme is now set in the first 15 minutes on 56-62% of days,
     against 50-54% before.
   - The **15:00 spike** (the start of the old closing-price window) collapsed when the closing auction started on
     3 Aug 2026. Since then, the index print **freezes from 15:15 to 15:27**, while options keep trading until 15:39.
   - The Jane Street pattern (morning push, afternoon dump on expiry) is **not visible** in the averages, either before
     or after the order.
6. **What a buyer with Rs 1 lakh and fixed lots can do:**
   - **Can:** buy only when and where the index actually travels, that is the morning and the breaks of levels. Never
     buy the fade. Keep costs tiny. The one rule that does this and survived honest tests is **Liquidity 15+5**:
     about **Rs 65-167/day net at 1 BANKNIFTY lot**, with 4-5 losing months in 10 (h36).
   - **Cannot:** predict which way the index goes from:
     - the clock;
     - the gap;
     - the first hour;
     - expiry;
     - news, overnight cues, OI, volume, patterns, or the option chart.
     Twelve of our studies tested these. Every one failed after costs.
   - **Rs 5,000/day at Rs 1 lakh: NO.**

---

## 1. WHO moves the Indian index market, and WHY

Grades:
- **E**: evidence (a regulator's record, exchange data, a peer-reviewed or serious working paper, or our own data);
- **P**: plausible, with a mechanism but no clean proof;
- **F**: folklore.

### 1a. The players (who trades what)

| who | share of NSE index-option premium turnover (FY26 → FY27 to Aug) | share of cash market (FY27 to Aug) | what they do | grade |
|---|---|---|---|---|
| Prop traders (own-money desks, HFT, market makers) | 49.3% → 45.9% | 33.4% | make markets, arbitrage, sell option premium, mostly algorithmic and co-located | E |
| Individuals (retail, HUF, NRI) | 39.0% → 41.1% | 33.2% | mostly short-term directional option buying, day trades, near expiry | E |
| Foreign investors (FPIs) | 7.3% → 6.7% | 13.1% | big cash and index-futures positions; 31% of equity-futures turnover | E |
| DIIs (mutual funds, insurers) | 0.1-0.2% | 13.1% | buy stocks with SIP money; barely trade index options | E |
| Corporates / others | about 6% | about 7% | hedging, treasury | E |

Sources:
- [NSE Market Pulse, Sept 2026](https://nsearchives.nseindia.com//web/mediaattachment/2026-09/Market_Pulse_September_2026_Final.pdf):
  Tables 89 and 95 for participation, Table 108 for channels.
- Our parsed copy is `scratchpad/hunt/h41/raw/mp_sep2026.txt`.

**Machines run the order flow (E):**
- **Co-location** (servers inside the exchange) carries:
  - about **50-52% of index-option premium** turnover;
  - **58-60% of equity-derivatives notional**;
  - **43% of cash-market** turnover (FY27 to date).
- Retail's mobile apps carry about 31% of index-option premium.
- Source: NSE Market Pulse Sept 2026.

### 1b. Who is on the other side of retail option buyers (E)

- **SEBI FY25 study (July 2025):**
  - **91% of individual F&O traders lost money.**
  - Net loss: **Rs 1,05,603 crore**, about Rs 1.1 lakh per trader, across 96 lakh traders.
  - Losses fell in Q4 FY25, after the Nov 2024 curbs. They were still above Q1.
  - [SEBI PDF](https://www.sebi.gov.in/sebi_data/attachdocs/jul-2025/1751900271726.pdf).
- **SEBI FY22-24 study (Sept 2024):**
  - In FY24, prop traders made Rs 33,000 crore and FPIs Rs 28,000 crore gross.
  - **96-97% of those profits came from algorithms.**
  - [summary](https://www.outlookmoney.com/invest/93-of-individual-traders-suffered-losses-in-fo-in-last-3-years-sebi-study).
- **Agarwal, Ghosh, Prabhala & Zhao, "Animal Spirits on Steroids" (CFR WP 25-09, Aug 2025):**
  - Data: every NSE option trade from 2007 to 2021, with investor IDs.
  - Retail *dominate* index options. Day trading was **90%** of retail volume by the end of the sample.
  - **78.7%** of retail index-option positions are naked directional bets.
  - Retail are **42% of expiry-day volume**.
  - Institutions are more often **net sellers** of options. The authors checked that retail losses equal institutions'
    profits: it is a zero-sum game.
  - When lots got bigger, retail moved to cheaper, further-OTM options.
  - [paper](https://www.cfr-cologne.de/download/workingpaper/cfr-25-09.pdf).
- **Plain meaning:**
  - The other side of your CE or PE is usually a fast prop algorithm.
  - It earns the spread and sells you volatility that is priced above what the index later delivers. Our h29 found the
    index moved only **0.62-0.73×** of what implied volatility priced.
  - It does not need to know the direction. It needs you to pay a little too much, many times.

### 1c. What actually pushes the index

| driver | how it moves the index | grade | what our data says |
|---|---|---|---|
| **Net aggressive order flow** in heavyweights and index futures | price goes where market orders push it; impact depends on book depth (Cont, Kukanov & Stoikov) | E | not testable without order-book data (MARKET_DRIVERS §1) |
| **FPI flows** | big sell/buy programmes in heavyweights. FPIs sold about Rs 2 lakh crore in CY2025 | E (that they move prices over days) / P (intraday timing) | h27: FII index-futures OI change did **not** predict the next day (0 of 240 tests passed) |
| **DII / mutual-fund flows (SIPs)** | about Rs 31-32 thousand crore a month of SIPs; DIIs bought a record ~Rs 6 lakh crore in 2025, absorbing FPI selling | E (size) / P (they damp falls) | no intraday signal; h28 found no month-start or SIP-date edge |
| **Index arbitrage / basket trades** | futures or options rich vs cash → arbitrageurs sell one and buy the other. This is the belt that carries option-market pressure into the index | E (mechanism) | h29: the option-implied forward **leads** the index by 5-30 min (IC +5-13%), but the option has already moved, so a buyer can't use it |
| **Closing flows** (passive funds, MF rebalancing, index rejigs, the closing price) | executed near the close; on MSCI days the closing auction was ~22% of cash turnover | E (our data) | heavyweight cash value: **20% of the day's value trades in the last 30 min** (3.6× the midday rate); the 15:00 spike (section 3e) |
| **Option dealers' hedging (gamma)** | short-gamma hedgers chase moves; long-gamma hedgers damp them | E (US) / P (India) | h18: more gamma near spot → **calmer** next 15-60 min, on all 5 indices. Says *how much*, not *which way* |
| **Scheduled news** (RBI, Budget, election, CPI/FOMC, results of heavyweights) | the surprise moves the price at a known hour | E | h28: Budget days 2.0-2.4× range (11:00-14:00); RBI days 1.6× in 10:00-11:00. Direction unknown |
| **Unscheduled news** | headline → order flow | E | h33: the price usually moves **before** the headline; no follow-through over 15-30 min |
| **Overnight world cues** (US, Asia, crude, USD/INR) | priced into the 09:15 gap | E | h27: cues correlate 0.5-0.65 with the gap; nothing left after 09:20 |
| **Stop-loss cascades** | stops beyond prior highs/lows make breaks run further | E (FX, Osler) | h18: breaks continue +2-3 bps/30 min; "sweep then reverse" does **not** happen |
| **Manipulation** (Jane Street, spoofing) | big cash/futures pushes against a larger options book | E (regulator allegation; Jane Street disputes it; no final order found as of Feb 2026) | h18: invisible in aggregate data; morning-afternoon correlation on BANKNIFTY expiries was 0.01 |
| **Retail option buying itself** | through dealers' hedging, in theory | P | h18 points the other way: big OI near spot pins the market |

### 1d. Folklore check

| claim | grade | our answer |
|---|---|---|
| "Max pain / max OI pulls the price on expiry" | F | no drift toward it (h18) |
| "Operators sweep stops, then reverse" | F | sweeps drift slightly further, they don't reverse (h18) |
| "FII data tells tomorrow's direction" | F/P | no (h27) |
| "Gaps always fill" | F (half true) | only small gaps fill; gaps above 0.6% fill 5-41% of the time (section 3d) |
| "The first hour decides the day" | F | the rest of the day follows the first hour 49-54% of the time (section 3c) |
| "3 pm is the operators' hour" | P (mechanism, not direction) | the 15:00 spike was the start of the closing-price window, and it vanished with the closing auction (section 3e). No direction |
| "Expiry days are crazy all day" | F | expiry range is about the same as normal days; only the last hour is livelier (section 3f) |
| "Morning push, afternoon dump on expiry" (Jane Street) | E as an allegation on ~18 days | not visible in the averages before or after the order (section 3f, h18) |

---

## 2. WHY the day has a shape (the mechanics)

- **The open (09:15-10:00) is wild because overnight news is priced all at once.**
  - The pre-open auction sets the cash open.
  - Index futures got their own pre-open from 8 Dec 2025.
  - In the first minutes, arbitrage, hedgers and stale limit orders all reprice together.
  - Academic work on NSE finds the same U-shape in volume, volatility and trade counts:
    - [Sampath & Gopalaswamy 2020](https://ideas.repec.org/a/sae/emffin/v19y2020i3p271-295.html);
    - NIFTY futures 2011-18, [Singh & Gangwar 2018](https://mpra.ub.uni-muenchen.de/89689/). **E.**
- **Midday (11:00-14:00) is quiet.** News is digested and Europe is not yet open. Large orders are worked slowly,
  with VWAP/TWAP algorithms, to hide them. **E** for the lull (our data). **P** for the reasons.
- **The close (14:00-15:30) wakes up because many funds must trade "at the close".**
  - Until Aug 2026, NSE's closing price was the volume-weighted average of 15:00-15:30.
  - Since 3 Aug 2026 it is set by a closing auction in about 220 F&O stocks
    ([report](https://www.outlookbusiness.com/markets/sebi-closing-auction-session-new-stock-market-timings-from-august-3)).
  - Passive funds, MF rebalancing and index rejigs all aim at that price. **E.**
- **Expiry days:** options lose all their time value by the close. Sellers with big books manage risk into the last
  hour, and retail piles into cheap 0DTE options (42% of expiry-day volume in the paper above). Our data shows extra
  movement in the last hour, not all day.

---

## 3. WHEN moves happen, with our numbers

### 3a. Volatility and volume through the day (the U-shape)

Mean absolute 1-minute move, as a ratio to the day's average minute (all days):

| index | 09:15-09:30 | 09:30-10:00 | 10-11 | 11-12 | 12-13 | 13-14 | 14-15 | 15:00-15:30 | avg minute |
|---|---|---|---|---|---|---|---|---|---|
| NIFTY | **2.02** | 1.30 | 1.00 | 0.84 | 0.84 | 0.91 | 1.01 | 1.00 | 2.3 bps |
| BANKNIFTY | **2.06** | 1.29 | 1.00 | 0.85 | 0.85 | 0.89 | 0.99 | 1.02 | 3.0 bps |
| FINNIFTY | **2.09** | 1.32 | 1.00 | 0.86 | 0.84 | 0.88 | 0.98 | 1.03 | 2.8 bps |
| MIDCPNIFTY | **2.43** | 1.53 | 1.09 | 0.86 | 0.80 | 0.81 | 0.87 | 0.90 | 2.9 bps |
| SENSEX | **2.06** | 1.30 | 1.00 | 0.86 | 0.84 | 0.86 | 0.98 | 1.10 | 2.1 bps |

- The very first minute (09:15) is 14-19 bps on average, about 6× a normal minute.
- Source: `intraday.log` A1-A2.

Share of the day's volume in each window:

| volume source | 09:15-09:30 | 09:30-10:00 | 10-11 | 11-12 | 12-13 | 13-14 | 14-15 | 15:00-15:30 |
|---|---|---|---|---|---|---|---|---|
| NIFTY weekly options, ATM±10, 2020-26 | 8.1% | 10.3% | 14.7% | 12.2% | 12.4% | 14.0% | **17.6%** | 10.6% |
| BANKNIFTY monthly options | 9.6% | 11.3% | 15.7% | 12.1% | 11.7% | 12.4% | 15.4% | 11.8% |
| 10 NIFTY heavyweights, cash value, Oct 2024-26 | 9.0% | 7.9% | 12.2% | 11.3% | 11.3% | 12.0% | 15.9% | **20.3%** |

- Per minute, option volume runs at 2.6× the midday rate in the first 15 minutes and 1.7× in the last 30.
- Heavyweight cash trades 3.2× the midday rate in the first 15 minutes and **3.6× in the last 30**.
- **The close is where the big cash money trades. The open is where the price jumps.**
- Source: `volume_moves.log` V1-V2.

### 3b. When the day's high and low are set

% of days on which the day's high (H) or low (L) is set in each window, actual 1-minute highs and lows:

| index | H by 09:30 | L by 09:30 | high or low in first 15 min | high or low in first 60 min | high or low in last 30 min | both extremes between 10:15 and 15:00 |
|---|---|---|---|---|---|---|
| NIFTY | 27% | 24% | 51% | **76%** | 38% | 12% |
| BANKNIFTY | 29% | 28% | 57% | **79%** | 34% | 11% |
| FINNIFTY | 30% | 29% | 58% | **80%** | 35% | 9% |
| MIDCPNIFTY | 30% | 32% | 61% | **83%** | 38% | 8% |
| SENSEX | 30% | 26% | 56% | **78%** | 37% | 11% |

**Is that special? Compare with random walks** (close-to-close path, `intraday.log` C2):

| NIFTY | extreme in first 15 min | extreme in first 60 min | both extremes mid-session |
|---|---|---|---|
| real data | 47.6% | 73.9% | 12.6% |
| random walk, minutes shuffled within the day (flat volatility) | 28.3% | 52.1% | 27.9% |
| random walk keeping the real U-shaped volatility | 47.0% | 70.2% | 15.9% |

- Almost all of the "high/low comes early" effect is explained by the morning simply being wilder.
- What is left is a small 3-5 point excess. The other indices are the same: BANKNIFTY 76.8 vs 72.8, SENSEX 75.9 vs
  71.5.
- **There is no hidden "9:20 trap". The open is just where the most movement is.**

**By day type** (trend day: open-to-close covers 60% or more of the range; range day: 25% or less):

| NIFTY | share of days | an extreme in the first hour | an extreme in the last 30 min | both extremes mid-session |
|---|---|---|---|---|
| trend days | 37% | **88%** | **54%** | 3% |
| middle days | 40% | 70% | 34% | 13% |
| range days | 23% | 67% | 19% | **23%** |

- A trend day typically starts at one end at the open and finishes at the other end at the close.
- You know which kind of day it was only at the end.
- The other indices are the same (C3).

**By period** (first-15-minute extreme / last-30-minute extreme):

| index | P1 pre-Nov 24 | P2 Nov 24-Jul 25 | P3 post-JS |
|---|---|---|---|
| NIFTY | 50% / 40% | 55% / 27% | 56% / 34% |
| BANKNIFTY | 54% / 35% | 58% / 27% | 62% / 35% |
| SENSEX | 50% / 44% | 57% / 23% | 62% / 35% |

- Since Nov 2024 the open sets the extreme more often.
- The close set it less often in P2.
- By year, NIFTY's first-15-minute extreme went 46-53% (2020-24) → 57% (2025-26) (C5).

### 3c. Does the first hour's direction hold?

Open → 10:14 close (first hour), then 10:14 → 15:29 (rest of day). All days:

| index | close on the same side of the open as the first hour | rest of day **continues** the first hour | average continuation | t |
|---|---|---|---|---|
| NIFTY | 68% | **51.5%** | +1.6 bps | 1.0 |
| BANKNIFTY | 68% | **49.4%** | -2.3 bps | -1.1 |
| FINNIFTY | 71% | **51.4%** | +0.6 bps | 0.3 |
| MIDCPNIFTY | 74% | **53.5%** | +2.5 bps | 1.0 |
| SENSEX | 70% | **51.3%** | -0.9 bps | -0.5 |

- The 68-74% "same side" figure is mechanical: the first hour is already part of the day. It is not a prediction.
- The honest number is the rest of the day, and it is a coin flip.
- The same holds for:
  - first 15 or 30 minutes, or up to 11:45 (D1);
  - only the biggest quarter of first hours (50-55%);
  - each period. P3 is 51-56% with |t| < 1.1.
- **US "intraday momentum" does not carry over to Indian indices on our data.**
  - The first half hour, including the gap, vs the last half hour: correlation -0.08 to 0.00 on all five indices
    (Gao et al. find positive in the US).
  - Rest of day vs last 30 minutes (Baltussen et al.): NIFTY +1.0 bps (t 1.9). The others are about zero.
  - In P2 every index showed a mild *reversal* (corr -0.13 to -0.19), on only 154 days. Source: D2.

### 3d. Gaps: fill or go?

Gap = 09:15 open vs the previous 15:29 close. "Fill" = the index touches the previous close later that day.

| NIFTY gap size | share of days | fill same day | fill by 10:15 | median minutes to fill | open → close in the gap's direction | closes on the gap's side of yesterday's close |
|---|---|---|---|---|---|---|
| < 0.1% | 20% | 93% | 90% | 0 | -3.7 bps | 53% |
| 0.1-0.3% | 33% | 78% | 61% | 7 | -3.6 bps | 62% |
| 0.3-0.6% | 28% | 55% | 30% | 48 | -5.4 bps | 72% |
| 0.6-1.0% | 13% | 32% | 9% | 122 | -5.2 bps | 85% |
| > 1.0% | 7% | **10%** | 0% | 238 | +1.0 bps | **95%** |

BANKNIFTY fill rates by the same sizes: 91 / 88 / 59 / 39 / 20%. The other indices are similar (E1).

- **Small gaps fill, but they are small.** A 0.2% NIFTY gap is about 50 points. The fill is a few points of premium,
  not a trade.
- **Big gaps hold.** After a gap above 0.6%, the index usually stays on the gap side all day.
- From the open, the index gives back a little of the gap: negative in 20 of 25 index×size cells, typically 4-15 bps.
  A few cells reach t ≈ -3. That is about a tenth of a day's range, and the size and sign vary by period (E2). It is far
  too small for an option buyer.
- h27 already showed that the gap prices the overnight news, and that fading or following it lost money in the holdout.

### 3e. The closing minutes, and what the closing auction changed

Mean |1-minute move| in bps, 2026 (`cas.log`):

| index | period | 14:59 | **15:00** | 15:01 | 15:15-15:27 | 15:28 | 15:29 | midday |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 2026 before 3 Aug | 2.2 | **8.3** | 3.8 | 1.9 | 2.0 | 2.1 | 2.1 |
| NIFTY | from 3 Aug (closing auction) | 1.3 | 2.1 | 2.2 | **0.02 (frozen)** | **7.0** | **8.5** | 1.3 |
| BANKNIFTY | before | 2.6 | **7.3** | 4.6 | 2.4 | 2.4 | 3.5 | 2.6 |
| BANKNIFTY | from 3 Aug | 1.7 | 2.6 | 2.6 | **0.03** | **6.7** | **11.3** | 1.8 |

- **The 15:00 spike was real in every year from 2020 to 2026.** It ran 3-4× a normal minute, and heavyweight cash
  volume jumped at the same minute.
- It lined up exactly with the start of the old 15:00-15:30 closing-price window. When that rule was replaced by an
  auction, the spike shrank to about 1.6×.
- That is a natural experiment, so we grade the mechanism **E**. The direction of the 15:00 move was never knowable.
- **Since 3 Aug 2026, the index print stands still from 15:15 to 15:27**, while the auction collects orders. Then it
  jumps at 15:28-15:29.
- Options keep trading to 15:39. 8.4% of NIFTY weekly CE volume trades in 15:15-15:29, and 1.9% after 15:30.
- **For a buyer:** after 15:15 you trade options while the index you watch is frozen. Exit rules based on index
  candles stop working in that window. The app's 15:10 square-off sits just before it, which is fine.

### 3f. Expiry day vs a normal day

| index | day | n | range | last-hour \|move\| | last hour's share of the range | \|1-min move\| 15:00-15:30 |
|---|---|---|---|---|---|---|
| NIFTY | expiry | 319 | 1.03% | 21.5 bps | 0.42 | 2.4 bps |
| NIFTY | normal | 1,196 | 1.04% | 19.9 bps | 0.40 | 2.3 bps |
| BANKNIFTY | expiry | 190 | 1.37% | **28.3 bps** | 0.43 | **3.7 bps** |
| BANKNIFTY | normal | 1,054 | 1.27% | 23.7 bps | 0.40 | 2.9 bps |
| FINNIFTY | expiry | 180 | 1.27% | **29.0 bps** | 0.46 | 3.2 bps |
| FINNIFTY | normal | 995 | 1.22% | 21.4 bps | 0.39 | 2.8 bps |
| SENSEX | expiry | 177 | 0.98% | 17.8 bps | 0.45 | **3.2 bps** |
| SENSEX | normal | 661 | 0.92% | 16.5 bps | 0.39 | 2.1 bps |

- **Expiry days are not wilder overall.** The NIFTY range is the same, and BANKNIFTY/FINNIFTY/SENSEX are 4-8% wider.
- **The last hour is livelier**: the last-hour move is 8% bigger on NIFTY and SENSEX, 19% on BANKNIFTY and 36% on
  FINNIFTY.
- The afternoon after a big morning (more than 0.5%) on expiry reverses 54-55% of the time on NIFTY and BANKNIFTY
  (n 78 and 64), and 39% on SENSEX.
  - That is within chance, and it flips by period (F1).
  - Post-order samples are tiny: 3-16 big-morning expiry days per index.
  - **No tradable "expiry trap" pattern**, before or after the Jane Street order. This agrees with h18.

### 3g. How the market changed (regimes)

| NIFTY | P1 pre-Nov 24 | P2 Nov 24-Jul 25 | P3 post-JS |
|---|---|---|---|
| days | 1,061 | 154 | 300 |
| India VIX (avg) | 16.6 | 15.3 | 13.5 |
| average day range | 1.08% | 1.06% | **0.89%** |
| range ÷ VIX-implied daily move | 1.03 | 1.12 | 1.05 |
| first hour's share of the day's range | 0.58 | 0.65 | 0.61 |
| first 15 min vs midday (per minute) | 2.37× | 2.51× | 2.47× |
| last 30 min vs midday | 1.22× | 0.98× | 1.18× |
| trend days | 36% | 38% | 39% |

BANKNIFTY moved the same way: range 1.38% → 1.23% → 1.10%, range/VIX 1.39 → 1.29 → 1.28 (G1).

- **Calmer, mostly because volatility (VIX) is lower.** Relative to VIX, ranges barely changed.
- The share of the day's move made in the opening hour went **up** a little. Trend days rose slightly.
- The last half hour was unusually dull in P2 (Nov 2024 to Jul 2025), then recovered.
- None of this hands a buyer a direction.

---

## 4. HOW moves unfold (the shape a buyer actually faces)

How far the index typically travels (median and 75th percentile of the **absolute** move), and what that is worth to
1 lot of a 1-ITM option (delta about 0.6) **if you guessed the side right**. Lots: NIFTY 65, BANKNIFTY 30, SENSEX 20.
Source: `volume_moves.log` H.

| index, start, hold | median move | 75th pct | median Rs for 1 lot | hit rate needed to beat ~Rs 124 cost* |
|---|---|---|---|---|
| NIFTY 09:20, 30 min | 13.9 bps (27 pts) | 25.5 bps | Rs 1,040 | about 56% |
| NIFTY 12:00, 30 min | 7.7 bps (15 pts) | 14.4 bps | Rs 590 | about **61%** |
| NIFTY 14:00, 30 min | 9.1 bps (18 pts) | 17.5 bps | Rs 680 | about 59% |
| BANKNIFTY 09:20, 30 min | 17.9 bps (84 pts) | 31.2 bps | Rs 1,510 | about 54% |
| BANKNIFTY 12:00, 30 min | 9.5 bps (44 pts) | 18.1 bps | Rs 800 | about 58% |
| SENSEX 12:00, 30 min | 7.0 bps (53 pts) | 12.9 bps | Rs 630 | about 60% |

\* Simple symmetric bet: win or lose the median move. Rs 124 is the average loss per random 1-lot trade after charges,
spread and decay (OBUY_FINAL). Theta during the hold is ignored, which makes the numbers *too kind*.

What this means:
- **The index moves enough to pay a buyer only when the buyer is right about the side, and the side is a coin flip**
  (sections 3c and 3d, and twelve studies below).
- **The morning gives the most movement per rupee of cost**, so the hit rate needed is lowest there (54-56%).
- **Midday needs about 60% accuracy just to break even.** Nobody we tested gets near that.
- A price move unfolds as: an opening burst, a fade into the 11:00-14:00 lull, and a late pick-up into the close.
- Trend days run open-to-close. Range days leave both extremes in the middle. You cannot tell them apart early.
- Breaks of recent highs and lows tend to continue a little (+2-3 bps in 30 min, h18). That is the only footprint that
  survived honest testing.

---

## 5. HOW an option BUYER with Rs 1 lakh and fixed lots can and cannot make money

### 5a. What the evidence says you CAN do

| point | why (mechanism) | tested in | result |
|---|---|---|---|
| Trade **breaks**, never fades or "sweep reversals" | stop cascades make breaks run on (Osler); our sweeps drift on | h18 T4; Liquidity studies; h36 | breaks continue +2-3 bps/30 min. **Liquidity 15+5 is the only rule positive in every test year**: Plan A (BANKNIFTY, 1 lot) Rs 65/day net before the holdout, **Rs 167/day** in the holdout (Rs 152 / 362 gross) |
| Be in the market when it **moves**: the morning, and event hours | U-shape: per rupee of cost, the open gives about 2× the movement of midday | this study (sections 3a and 4); h28 for events | lowers the hit rate you need from about 60% to about 55%. It still needs a side |
| Keep costs tiny: limit orders, liquid strikes, 1-ITM nearest monthly, no chasing | costs are about Rs 100-200 per round trip, the whole "edge" of most rules | h24 (real spreads 0.16% BANKNIFTY/NIFTY, 0.42% FINNIFTY); h14 limit entry | the limit entry (+0.5%, 3 min) is part of the best plan; FINNIFTY's spread turns gross winners into net losers (h26) |
| Let winners run with the strategy's own exits | profit comes from a few far-running break days | h34; h36 | point targets (+15 to +30) **destroyed** Liquidity's profit (+Rs 42k → -Rs 57k in the holdout). The best ~10 days a year make 2-2.5× the year's net |
| Read gamma concentration as a *calm* warning | big OI near spot → calmer next 15-60 min | h18 T1 | real (t -5 to -13), but as a filter it did not lift P&L. Use it only as context |
| Size small: 1 lot | ruin risk climbs fast with size and with weak indices | h36, h23 | Plan A: 1% chance of Rs 1 lakh falling to 50k in a year; adding MIDCP raises that to 28% |

### 5b. What you CANNOT do (and which study proved it)

| idea | study | result |
|---|---|---|
| Pick the side from the time of day, the first hour or the gap | this study (3b-3d); h27 | rest of day follows the first hour 49-54%; gap direction carries no edge after 09:20 |
| Candle patterns, chart patterns, indicators (195 signals, 210,600 variants) | h25 | SPA/RC p = 1.00; 28 of 31 gate-passers lost in the holdout |
| Volume and OI (PCR, OI build-up, volume spikes) | h26 | 2,268 variants, none survives; BANKNIFTY VSPIKE +Rs 102/day in the holdout but weak; FINNIFTY spread eats it |
| Overnight / world cues | h27 | priced into the gap; the best variant went from +135 to **-218 Rs/day** in the holdout |
| Calendar and scheduled events (RBI, Budget, FOMC, expiry, month ends) | h28 | 8,820 variants, SPA p = 1.00; more movement on event days, but no side |
| "Cheap" options, IV skew, synthetic-forward signals | h29 | all 24 directional variants lose; the forward "leads" the index only after the option moved |
| The option's own chart (premium breakouts, VWAP, EMAs) | h30 | the average trade is -Rs 16 even **before** costs; worse than random for 11 of 14 families |
| Overnight option buying (BTST) | h31 | the direction signal is real, the money is not (walk-forward -Rs 44,738; every family lost in 2026) |
| Spotting a 20-point jump early | h32 | +20 before -15 happens 38-40% of the time vs a 43% break-even; all 90 forward variants lose |
| Trading news or price shocks | h33 | the headline arrives after the move; walk-forward -Rs 1.02 lakh |
| Premium-point targets and stops | h34 | random entries lose with all 128 exits; points make Liquidity worse |
| Expiry-day "hero-zero" and straddles | OBUY_FINAL; h28; EXPIRY_SCALP | hero-zero lost Rs 15 lakh; the 09:20 straddle lost Rs 2.9 lakh a year; BANKNIFTY expiry puts flipped from winner to loser |
| Fading the "Jane Street" expiry morning | h18 T3; this study 3f | morning-afternoon correlation about 0; nothing to fade |
| Max pain, max-OI support/resistance, stop-hunt reversals | h18 | no effect, or the opposite sign |

### 5c. The honest bottom line for Rs 1 lakh

- **The market's "when" is real and stable:**
  - a wild open;
  - a quiet midday;
  - a busy close, now with a frozen 15:15-15:27 index print;
  - a livelier last hour on expiry.
- **The market's "which way" is not readable from anything a retail screen shows.**
  - The firms that profit (prop and FPI algos) earn from spread, volatility selling and arbitrage. They do not forecast
    candles.
- **A buyer can only:**
  - be in the market when it moves;
  - trade breaks;
  - pay as little as possible;
  - let the rare big day pay for the many small losses.
  That is exactly Liquidity 15+5 at 1 BANKNIFTY lot: **about Rs 65-167/day net (Rs 1,400-3,500 a month), with 4-5
  losing months in 10** (h36).
- **Rs 5,000/day at Rs 1 lakh and fixed lots: NO.** It would need 30-77 BANKNIFTY lots, about Rs 22-63 lakh (h36).

---

## 6. Sources

Regulators and exchanges:
- SEBI, *Comparative study of growth in EDS vis-à-vis cash market after recent measures* (7 Jul 2025):
  [PDF](https://www.sebi.gov.in/sebi_data/attachdocs/jul-2025/1751900271726.pdf)
- SEBI FY22-24 F&O P&L study (23 Sep 2024): [summary](https://www.outlookmoney.com/invest/93-of-individual-traders-suffered-losses-in-fo-in-last-3-years-sebi-study)
- NSE Market Pulse, Sept 2026: [PDF](https://nsearchives.nseindia.com//web/mediaattachment/2026-09/Market_Pulse_September_2026_Final.pdf)
- SEBI Jane Street interim order, 3 Jul 2025:
  - [Oxford Business Law Blog](https://blogs.law.ox.ac.uk/oblb/blog-post/2025/07/jane-street-and-expiry-day-trap-unpacking-sebis-crackdown-algorithmic)
  - [Legal500 summary](https://www.legal500.com/developments/thought-leadership/sebi-update-interim-order-against-jane-street-group-for-alleged-index-manipulation/)
  - [SAT hearing adjourned, Feb 2026](https://www.businesstoday.in/amp/markets/story/jane-street-vs-sebi-sat-adjourns-hearing-in-market-manipulation-case-517925-2026-02-25)
- Rule changes:
  - weekly expiry rationalisation (Nov 2024): [Zerodha](https://zerodha.com/z-connect/business-updates/sebis-new-rules-for-index-derivatives-heres-whats-changing)
  - NSE Tuesday expiry (Sep 2025): [ICICI Direct](https://www.icicidirect.com/research/equity/finace/sebi-clears-expiry-day-clash-between-nse-and-bse)
  - intraday position limits (Oct 2025): [Taxguru](https://taxguru.in/sebi/sebi-tightens-intraday-position-limits-derivatives.html)
  - F&O pre-open (Dec 2025): [Tradejini](https://www.tradejini.com/blogs/nse-to-launch-preopen-session-for-futures-contracts-from-december-8-2025)
  - STT hike (Apr 2026): [ICICI Direct](https://www.icicidirect.com/ilearn/futures-and-options/articles/stt-changes-in-budget-2026-what-f-o-traders-should-know)
  - closing auction (Aug 2026): [Outlook Business](https://www.outlookbusiness.com/markets/sebi-closing-auction-session-new-stock-market-timings-from-august-3), [first day](https://hdfcsky.com/news/nse-sees-rs-1276-cr-turnover-in-closing-auction-session-icici-bank-hdfc-bank-among-top-traded-stocks)
- RBI Financial Stability Report on derivatives risk (coverage): [Deccan Herald](https://deccanherald.com/business/markets/rbi-warns-of-risks-from-rapid-rise-in-derivative-trading-volumes-3083255)

Flows:
- DIIs record ~Rs 6 lakh crore in 2025, FPIs sold ~Rs 2 lakh crore:
  - [Business Standard](https://www.business-standard.com/markets/news/rs-6-trillion-counting-diis-pump-record-money-in-indian-stocks-in-2025-125101500254_1.html)
  - [Angel One](https://oga-prod.angelone.in/news/market-updates/dii-flows-cross-rs-6-trillion-in-2025-in-india-stocks-offsetting-fpi-outflows-of-rs-2-trillion)
- SIP flows:
  - [IANS, Dec 2025 record Rs 31,002 cr](https://ianslive.in/sip-inflows-at-new-record-high-of-rs-31002-crore-in-dec-amfi-data--20260109114816)
  - [Cafemutual, July 2026](https://cafemutual.com/news/industry/38488-amfi-monthly-sip-inflows-almost-touch-rs-32000-crore-equity-inflows-decline-by-15-in-july-2026)
- Index rejig and closing flows: [Swastika](https://www.swastika.co.in/blog/wipro-share-price-and-nifty-50-reconstitution-bse-flows-free-float-shifts-and-re)

Academic:
- Agarwal, Ghosh, Prabhala & Zhao (2025), *Animal Spirits on Steroids: Evidence from Retail Options Trading in India*: [CFR WP 25-09](https://www.cfr-cologne.de/download/workingpaper/cfr-25-09.pdf)
- Sampath & Gopalaswamy (2020), *Intraday variability and trading volume: evidence from NSE*: [RePEc](https://ideas.repec.org/a/sae/emffin/v19y2020i3p271-295.html)
- Singh & Gangwar (2018), *Intraday volatility of Nifty futures*: [MPRA 89689](https://mpra.ub.uni-muenchen.de/89689/)
- Gao, Han, Li & Zhou, first half-hour predicts last half-hour (US): [summary](https://alphaarchitect.com/2014/08/attention-prop-traders-the-first-half-hour-of-trading-predicts-the-last-half-hour/)
- Baltussen, Da, Lammers & Martens (2021), hedging demand and intraday momentum: [EUR](https://pure.eur.nl/en/publications/hedging-demand-and-market-intraday-momentum/)
- The rest of the order-flow, gamma, pinning, stop-cascade and manipulation literature, with links, is in
  `research/MARKET_DRIVERS.md`.

Not found despite searching:
- a BIS or IMF paper specifically on India's option boom;
- an India-specific intraday-momentum paper.
Our own D2 test fills the second gap: there is no momentum on Indian indices.

## 7. Caveats

- Index minutes only. There is no order book, so the order-flow claims rest on the literature and on SEBI's
  entity-level case files, not on our data.
- P2 has only 154 days. Post-order expiry samples for BANKNIFTY and FINNIFTY are 15-16 days (monthly expiries only).
  Treat P2/P3 expiry rows as indicative.
- "Expiry day" means that index's own weekly or monthly expiry, as flagged in the data. NSE moved from Thursday to
  Tuesday on 1 Sep 2025.
- The section 4 hit-rate figures are a simple illustration, not a backtest. Theta makes the real bar higher.
- The heavyweight cash volume covers only Oct 2024 to Oct 2026.
