# R97: gold's "200-300 point" moves, reverse-engineered (21 Oct 2026)

Boss, 21 Oct: *"I can see the gold moves 200-300 easily every single day, still you are not able to find a good
strategy for me, try reverse engineering."* Research only: nothing in `android/**` or `.github/**` changed, nothing
goes to paper or live. Code, tests and every number: `research/hunt/r97_gold_reverse/` (`results/step1.json`,
`step2.json`, `step3.json`, `design.json`, `trades_design.csv`). Pre-registration: `hunt/r97_gold_reverse/PREREG.md`,
committed and pushed (78147fd1) before any rule's P&L was computed.

**Signal source of everything: the MCX gold future** (the option book's near future, minute closes). XAUUSD spot and
SILVER are context only. Every trade is an option BUY with a stop and a profit lock. "Points" = rupees per 10 g of the
future's quote (GOLD and GOLDM are both quoted per 10 g; 1 GOLDM lot = 10 x the quote, 1 GOLD lot = 100 x).

## The answer for Boss

**Nothing passed (R97: NOTHING). 0 of 7 rules made money after costs; the locked holdout was not opened.**

1. **The 200-300 point moves are real, but they are gold's small wiggles, not its big move.** Gold is about Rs 1.4
   lakh per 10 g, so 200 points is only 0.14%. On a normal day (median, 227 GOLDM sessions, 5 Aug 2025 - 14 Jul 2026)
   the future travels **1,786 points** from low to high, and inside that there are about **32 separate swings of 200+
   points** (counting a swing as over when price comes back 200). Every single day had them. So "it moves 200-300
   every day" is true, and it is also true of every random stretch of 10-15 minutes.
2. **What Boss sees on the chart is drawn with hindsight.** The start of a swing is only known after it has started.
   If you wait for the first 100 points to confirm the move and exit when it gives back 200, the average trade on the
   **future itself, before any charges, loses 16 points** (17,181 such moments, 76 a day). Only 22% of the moves that
   pass 100 points go on to 500; the rest turn around and stop you out. That is the gap between "the day's range was
   250" and "a 250-point move you could have entered in real time".
3. **Even with perfect foresight, a GOLDM option buyer keeps little of a 300-point swing.** Taking only swings that DID
   reach 300+ points (hindsight), buying the ATM option 100 points into the move and selling when it gave back 200, the
   **median result was -Rs 84 a lot after costs** (2,081 swings). The option moves about **0.45 x** the future (median
   delta 0.41-0.47), the round trip costs about **Rs 360** a lot (about Rs 95 of charges + the measured bid-ask spread; see Cost check), and about half of the swings could not even be entered
   because the option did not trade within 5 minutes. Only swings of **500+ points** pay a buyer (median +Rs 659 a
   lot, 965 swings) and **1,000+ points** pay well (+Rs 2,872, 186) - but those are 3-12 a day, and nobody knows in
   advance which start is the big one.
4. **When do the big swings start?** Most often 09:00-12:00 and in the US hours 18:00-21:00 IST (about 1.0-1.15
   swings of 500+ per hour then, vs 0.6 at midday). US data days (CPI / NFP, 27 in design) did NOT have bigger days
   (median range 1,681 vs 1,787 on other days). Big swings typically start right after a fast, wide move the other
   way (a V-turn): the 30 minutes before had a 629-point range vs 258 at random minutes.
5. **MCX does not lag international gold enough to trade.** MCX gold and XAUUSD move together in the same minute
   (correlation 0.59); one minute later only 0.07 (GOLDM) / 0.13 (GOLD). When XAUUSD runs ahead over 5 minutes, MCX
   makes up only ~17% of the gap in the next 5 minutes, a few points against ~80 points of option costs.
6. **The seven rules built from these findings all lost** on the design year after real charges (table below). The
   two best before costs (R97A morning new-extreme, gross +Rs 631 on 75 trades; R97E XAUUSD lead, gross +Rs 18,263 on
   355) were wiped out by costs (Rs 37,370 and Rs 1.62 lakh).

Bottom line: gold's daily 200-300 point swings are mostly noise at this scale. A buyer of monthly GOLDM options pays
about 80 points of the future in costs and gets less than half of each move, so a rule has to catch 500-1,000 point
swings and skip the many false starts. None of the seven ways tried here could tell the big start from the false
ones in real time.

## Step 1: what the moves are (design window, minute closes of the future)

GOLDM: 227 full sessions (14 short sessions dropped). GOLD: 176 (63 short or gappy sessions dropped).

| per session | GOLDM median (p25 - p75) | GOLD median |
|---|---|---|
| price of the future | Rs 1,41,700 | Rs 1,34,306 |
| high - low range | **1,786 pts** (1,303 - 2,802); 1.29% | 1,647 pts; 1.23% |
| open -> close | 780 pts | 747 pts |
| share of the range given back by the close (round trip) | 53% | 50% |
| largest one-way swing, retrace < 100 / < 200 / < 300 | 842 / 1,160 / 1,365 pts | 766 / 1,073 / 1,265 |
| that swing as a share of the day's range (retrace < 200) | 63% | 65% |
| days with range >= 1,000 / >= 2,000 / >= 3,000 | 197 / 99 / 44 of 227 | 148 / 62 / 22 of 176 |

Swings per session (GOLDM mean; a swing ends when price comes back X):

| retrace X | swings >= 200 | >= 300 | >= 500 | >= 1,000 | days with a 1,000+ swing |
|---|---|---|---|---|---|
| 100 | 42.6 | 24.8 | 9.5 | 2.1 | 81 of 227 |
| 200 | 32.0 | 22.7 | 11.6 | 3.0 | 143 |
| 300 | 18.6 | 18.4 | 11.2 | 3.6 | 173 |

Longer history (daily high - low of the near-month continuous future; the lake has no gold minutes before Aug 2025):
GOLD median range 448 pts in 2023 (229 days), 639 in 2024 (253), 918 in Jan - Jul 2025 (151), vs 1,647 in the design
year. The "200-300" scale was a big part of a 2023 day; in 2025-26 it is a fraction of one.

**When swings start** (GOLDM, swings >= 500 with retrace < 200; 2,631 swings; starts per hour per session):

| 09:00-12:00 | 12:00-15:00 | 15:00-17:00 | 17:00-18:00 | 18:00-19:30 US open | 19:30-21:00 | 21:00-close |
|---|---|---|---|---|---|---|
| 0.91 | 0.58 | 0.59 | 0.74 | 1.07 | 1.15 | 0.63 |

Up and down equally (50% up). 26 of the 2,631 began within 15 minutes before to 30 minutes after a US CPI / NFP
release (17 CPI, 9 NFP; 27 release days). A typical 500+ swing lasts 18 minutes (median).

**Perfect vs real-time capture** (GOLDM, swings >= 500, retrace < 200; future points, before costs):

| | median | mean |
|---|---|---|
| swing size (hindsight) | 736 | 916 |
| perfect entry after the first 100 pts, perfect exit at the top | 636 | 816 |
| entry after the first 100 pts, exit when it gives back 200 | 265 | 322 |
| same, on swings >= 300 (5,149) | 76 | 146 |
| same, on swings >= 200 (7,273) | -8 | 60 |

**Real-time triggers** (every moment the future is T points off its running extreme; outcome followed until it gives
back X or makes a new extreme past the start; future points, before costs):

| trigger T, trail X | triggers | a day | went on to 500+ | mean capture | win |
|---|---|---|---|---|---|
| 50, 100 | 33,757 | 149 | 8% | -11 | 32% |
| 50, 200 | 33,757 | 149 | 15% | -13 | 27% |
| 100, 200 | 17,181 | 76 | 22% | -16 | 32% |
| 100, 300 | 17,181 | 76 | 28% | -17 | 29% |

On 1-minute closes the trigger fires late: median 128 points past the start for T = 100 (75 for T = 50).
By clock (T 100, X 200), only 09:00-12:00 was positive: +11 pts (3,382); every other bucket -18 to -31.

## Step 2: what came just before (GOLDM, causal features at that minute; look-ahead guard tested)

A. **Swing starts vs random minutes** (2,631 starts of 500+ swings vs 4,540 random minutes, design):

| just before | at a swing start (median) | at a random minute |
|---|---|---|
| range of the last 30 min | 629 pts | 258 |
| range of the last 60 min | 899 pts | 380 |
| the future's move in the last 15 min (in the swing's direction) | -215 pts | 0 |
| distance from the session average price (in the swing's direction) | -197 pts | +7 |
| XAUUSD's move in the last 15 min (in the swing's direction) | -12.4 bp | 0.0 |
| XAUUSD ahead of MCX over 15 min | +1.6 bp | -0.1 |
| SILVER's move in the last 15 min | -14.0 bp | 0.0 |
| option volume, last 5 min vs normal | 1.04 x | 1.01 x |
| within -15/+30 min of CPI / NFP | 1.0% | 0.4% |
| broke yesterday's high / low in the last 15 min | 0.5% | 2.0% |

Big swings start as V-turns after a wide, fast move the other way, when gold, XAUUSD and silver have all just moved
against the new direction. Breakouts of yesterday's high / low or the opening range are LESS common at swing starts
than at random minutes.

B. **Which triggers went further** (17,181 triggers T 100 / X 200; base: 22% reached 500, mean -16 pts):

| at the trigger minute | triggers | reached 500+ | mean capture (pts, before costs) |
|---|---|---|---|
| 30-min range in the top fifth (>= 762) | 3,443 | 39% | -30 |
| 30-min range in the bottom fifth (< 215) | 3,416 | 14% | -12 |
| new session high (long) / low (short) | 704 | 31% | **+26** |
| crossed yesterday's high / low in the last 15 min | 505 | 25% | +9 |
| at a CPI / NFP release | 137 | 30% | +9 |
| crossed a 1,000 round number in the last 15 min | 3,800 | 32% | -22 |
| XAUUSD ahead of MCX >= 6.2 bp (top fifth) | 3,274 | 30% | -4 |
| 09:00-12:00 | 3,382 | 26% | +11 |

The squeeze idea runs backwards on gold: a busy last half-hour gives BIGGER swings but also more whipsaws, so the
capture is worse. Nothing gives more than ~+26 points before costs; an ATM GOLDM option needs ~80 to break even.

C. **Does MCX lag XAUUSD?** Correlation of MCX 1-minute returns with XAUUSD 1-minute returns L minutes earlier
(195,712 design minutes, GOLDM): L = 0: **0.59**; L = 1: 0.07; L = 2: 0.02; L >= 3: ~0. GOLD: 0.53 / 0.13 / 0.01. When
XAUUSD outruns MCX over 5 minutes, MCX's next 5 minutes follow by 0.17 x the gap (192,704); in the top 5% of gaps the
next 5 minutes go the gap's way only 49% of the time, +4 bp on average. USD/INR could not be studied (not in the lake,
not on Dukascopy). Caveat: the MCX series is the median `spot` of the option rows, which can be a few seconds stale;
the small 1-minute lag may partly be that.

## Step 3: what the options actually did (hindsight swings, real prints, design)

Swings that DID reach S points (retrace < 200); bought 100 points into the move at the first print within 5 minutes,
sold when the future gave back 200 (or at the square-off). Costs: Zerodha MCX charges + half-spread 0.60% before
17:00 / 0.30% after. Exits whose strike had left the stored ATM +/- 3 window are model-priced (2x spread) and counted.
**This is a ceiling: the failed starts are left out.**

GOLDM (1 lot = 10 x):

| strike | swings >= | swings | premium paid | future move fill -> exit | option gross | costs | **net (median / mean)** | win | delta | model exits |
|---|---|---|---|---|---|---|---|---|---|---|
| ATM | 300 | 2,081 | Rs 29,500 | Rs 530 | Rs 275 | Rs 359 | **-Rs 84 / +Rs 152** | 46% | 0.41 | 6% |
| ATM | 500 | 965 | Rs 30,300 | Rs 2,390 | Rs 1,035 | Rs 390 | **+Rs 659 / +Rs 887** | 70% | 0.47 | 14% |
| ATM | 1,000 | 186 | Rs 35,620 | Rs 6,960 | Rs 3,405 | Rs 509 | **+Rs 2,872 / +Rs 2,411** | 74% | 0.51 | 34% |
| 1-ITM | 500 | 656 | Rs 30,180 | Rs 2,625 | Rs 1,230 | Rs 365 | +Rs 843 / +Rs 776 | 74% | 0.51 | 27% |
| 1-OTM | 300 | 1,721 | Rs 26,725 | Rs 840 | Rs 390 | Rs 308 | +Rs 64 / +Rs 390 | 53% | 0.42 | 4% |
| 1-OTM | 500 | 847 | Rs 27,710 | Rs 2,490 | Rs 1,235 | Rs 340 | +Rs 906 / +Rs 1,174 | 79% | 0.48 | 7% |

Liquidity: about half of the 300+ swings had **no ATM print within 5 minutes** of the trigger (2,144 skipped for
that reason vs 2,081 traded, GOLDM ATM). ATM IV barely changed during a swing (median 0.0 points). Holds were short
(median 21-35 minutes), so theta is small; the loss is delta (< 0.5) plus costs.

GOLD (1 lot = 100 x): the same picture ten times larger (ATM, swings >= 500: 244, median net +Rs 8,749 on a median
premium of Rs 2.2 lakh), but one 15% premium stop is ~Rs 33k, too big for Boss's -Rs 40k drawdown rule; the rules
used GOLDM only.

## Step 4: the seven pre-registered rules (GOLDM, design 5 Aug 2025 - 14 Jul 2026)

All buy the ATM GOLDM option at the first print within 5 minutes, hard premium stop -15%, profit lock (+60 option
points -> breakeven, +120 -> +60, then peak - 80), underlying stop at the move's start, underlying trail 300 points
(R97E: stop 150, out after 15 minutes), out by the square-off. 1 lot; net after Zerodha MCX charges and half-spreads.
BH over 34 ideas (R96's 27 + these 7).

| rule (signal on the future) | trades | gross | costs | **net** | win | folds + | worst month | twin p (BH q) | verdict |
|---|---|---|---|---|---|---|---|---|---|
| R97A morning trigger at a new session extreme | 75 | +Rs 631 | Rs 37,370 | **-Rs 36,739** | 20% | 1 of 4 | -Rs 13,078 | 0.196 (0.543) | dies |
| R97B all-day trigger at a new session extreme | 208 | -Rs 21,594 | Rs 86,666 | **-Rs 1.08 lakh** | 21% | 0 of 4 | -Rs 28,760 | 0.164 (0.543) | dies |
| R97C trigger + yesterday's high / low break | 87 | -Rs 17,703 | Rs 34,326 | **-Rs 52,029** | 15% | 0 of 4 | -Rs 18,746 | 0.415 (0.706) | dies |
| R97D first morning trigger | 189 | -Rs 88,005 | Rs 86,572 | **-Rs 1.75 lakh** | 12% | 0 of 4 | -Rs 40,163 | 0.914 (0.971) | dies |
| R97E XAUUSD lead, MCX catch-up | 355 | +Rs 18,263 | Rs 1.62 lakh | **-Rs 1.43 lakh** | 19% | 1 of 4 | -Rs 41,011 | 0.240 (0.543) | dies |
| R97F join a 300-pt move at a new extreme | 122 | -Rs 19,851 | Rs 52,315 | **-Rs 72,166** | 24% | 0 of 4 | -Rs 17,046 | 0.571 (0.829) | dies |
| R97G stretch reversal (the swing-start profile) | 137 | -Rs 47,589 | Rs 60,415 | **-Rs 1.08 lakh** | 15% | 0 of 4 | -Rs 23,306 | 0.731 (0.901) | dies |

Median stop risk per trade Rs 4,300 - 4,800 (2% of an account of about Rs 2.2 - 2.4 lakh). Most exits were the profit
lock or the underlying stop; the 300-point trail was rarely reached (5-22 trades per rule). Full numbers with
2x spreads, drawdown, without-top-3, folds and exit reasons: `results/design.json`. Random-entry twins lost about as
much (R97A twins -Rs 48,685 on average), i.e. the triggers were no better than entering the same way at random times.

**The locked holdout (15 Jul - 19 Oct 2026) was NOT read**: no rule passed the design gates. (The Dukascopy tail for
1-19 Oct missed 5 days; it would only have mattered for a holdout read of R97E.)

## Cost check (added 21 Oct, Boss: "India commodities cost less than NIFTY")

Boss is right on the **rates**: CTT on MCX futures is 0.01% of the sale vs STT 0.05% on NSE index futures, and 0.05%
vs 0.15% on option premiums (zerodha.com/charges, read 21 Oct). Our model already used those MCX rates. What made the
GOLDM option trades expensive is not the tax: it is the **bid-ask spread** of thinly traded GOLDM options, which the
tests charged as a half-spread on each fill (0.60% of the premium before 17:00, 0.30% after).

- **Charges alone** on one GOLDM option round trip: about **Rs 92-102** a lot. On a GOLDM futures lot (~Rs 14 lakh
  of gold) about **Rs 285-295** (the 0.01% CTT on that size is most of it).
- **The spread, measured** from real prints (Roll's estimator on consecutive printed minutes of the same contract,
  design): near-ATM GOLDM options **~1.0% full spread before 17:00 and ~1.1% after** (20-24 points on a ~Rs 2,100
  premium; 80,525 / 91,876 minute pairs). So the 0.60% half-spread before 17:00 was about right and the 0.30% after
  17:00 was, if anything, too kind. NIFTY weekly options trade 1-2 ticks wide (~0.1%), which is why NIFTY's all-in
  cost is much lower even though its tax rate is higher.
- **Re-priced with charges only and NO spread** (an optimistic bound: a market order really pays the spread):

| rule | trades | gross | charges only | net, no spread | net as tested |
|---|---|---|---|---|---|
| R97A | 75 | Rs 631 | -Rs 7,288 (Rs 97 a trade) | -Rs 6,656 | -Rs 36,739 |
| R97B | 208 | -Rs 21,594 | -Rs 19,776 (Rs 95 a trade) | -Rs 41,370 | -Rs 108,260 |
| R97C | 87 | -Rs 17,703 | -Rs 8,008 (Rs 92 a trade) | -Rs 25,711 | -Rs 52,029 |
| R97D | 189 | -Rs 88,005 | -Rs 17,486 (Rs 93 a trade) | -Rs 105,491 | -Rs 174,578 |
| R97E | 355 | Rs 18,263 | -Rs 36,194 (Rs 102 a trade) | -Rs 17,930 | -Rs 143,245 |
| R97F | 122 | -Rs 19,851 | -Rs 11,384 (Rs 93 a trade) | -Rs 31,235 | -Rs 72,166 |
| R97G | 137 | -Rs 47,589 | -Rs 13,604 (Rs 99 a trade) | -Rs 61,193 | -Rs 108,003 |
- **Hindsight ceiling (step 3)** with charges only: GOLDM ATM on swings that DID reach 300+ points, median **+Rs 179** a
  lot (2,081; win 57%), 1-OTM +Rs 303 (1,721) - instead of -Rs 84 / +Rs 64 with the measured spread. Swings of 500+:
  +Rs 936 (965).

Conclusion unchanged: every rule is negative even with zero spread, because its GROSS (before any cost) is about zero
or negative - the triggers cannot tell the big swings from the false starts. Numbers: `hunt/r98_catch100/results/
cost_check.json` (`python -m research.hunt.r98_catch100.costcheck`).

## What was learned

1. "Gold moves 200-300 every day" is true, but at Rs 1.4 lakh per 10 g that is 0.14%: 30-40 such swings happen every
   session. The day's range (median 1,786) is made of many of them, and half of it is given back by the close.
2. A real-time trigger cannot tell a big swing's start from a false start. Waiting for confirmation costs the first
   ~130 points; the trailing exit gives back the last 200-300. On the future itself, before costs, that loses.
3. GOLDM monthly options turn a future move into less than half of it (delta 0.41-0.51), cost ~Rs 360 a round trip,
   and often do not trade for minutes. A buyer needs 500-1,000 point swings, which are 3-12 a day and look the same
   as the false starts at the trigger.
4. MCX gold is not a slow copy of XAUUSD: the follow-through is one minute and a few points.

## Ideas logged

`research/experiments/entries/r97-moves.yaml` (the measurement, not a test) and `r97-a` ... `r97-g` (the seven rules,
all rejected). R96's 27 ideas are counted in the BH family; R96 round 3 (NR7 / crude / energy) was not repeated.

## Data and checks

- Drive lake `gdrive:IraAlgo-data-lake` copied 21 Oct with rclone (installed in this session; the environment lacked
  it): `options_rolling_1m` (GOLD / GOLDM / SILVER MONTH books to 19 Oct 2026), `fut_daily_continuous`,
  `fut_1m_mcx_continuous` (only from May 2026, so not used), `xauusd_1m` (only the README and the US release calendar
  are on Drive). XAUUSD minutes: the options-lab `research-data` release asset `xauusd_m1_bid.csv.gz` (checksum matches
  the release digest) + Dukascopy for 28 Sep - 20 Oct.
- Tests: `research/hunt/r97_gold_reverse/tests/test_r97.py` (swings, triggers, the look-ahead guard on every feature,
  the rules' signals on cut sessions, the engine's stops / lock / trail, fills and expiry). No raw data in git.
