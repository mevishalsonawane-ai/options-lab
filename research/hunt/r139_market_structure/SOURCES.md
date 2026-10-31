# R139 market structure: sources and exact rules

Written 31 Oct 2026, before any R139 number was computed. This file contains internet research only. No R139
backtest had been run when it was written.

Boss asked: "Anything regarding equity shift, lower low, lower high, something like that, and create a strategy.
Search on internet regarding these topics." We read "equity shift" as **market structure shift (MSS)**. We also cover
ICT's **equilibrium**, the 50% midpoint of a swing range, used to split premium from discount.

The signal source for every rule below is the **index spot bars** (1, 5, 15 or 60 minutes). We then buy the ATM option
(CLAUDE.md: signal on the underlying, trade the option).

**Honesty note.** Almost everything in the SMC/ICT world (BOS, CHoCH, MSS, OTE, premium/discount) has **no published,
independent, sample-sized test**. Even the vendors that teach it say so (LuxAlgo, TheRightTrader; see below). The
academic evidence covers older and simpler rules: Dow theory, trading-range breaks, support and resistance, and
intraday momentum. Most of that evidence says any edge is small, shrinks out of sample and is often eaten by costs.
Where a number could not be checked against the original source, it is marked "not verified".

The house has tested related ideas before: R130 (sweep → MSS → FVG), R131 (ICT ten) and R133 (CISD). R139 variants
should not simply re-run those.

---

## Common building block: what is a "swing" (pivot)?

Every rule below needs a fixed, look-ahead-safe swing definition. These are the published versions:

- **Fractal or DeMark pivot of size N** (Oxford Capital Strategies, "Dow Theory – Trend",
  https://oxfordstrat.com/trading-strategies/dow-theory-trend/). A swing high is a bar whose high is above the highs
  of the N bars on each side. A swing low is the mirror. They test N = 1 to 40.
  - **Look-ahead trap:** a pivot of size N is only *known* N bars after it happens. Code it as "confirmed at bar t+N".
- **Zig-zag of X% or k×ATR** (thinkorswim ZigZagHighLow docs,
  https://toslc.thinkorswim.com/center/reference/Tech-Indicators/studies-library/V-Z/ZigZagHighLow). A new swing is
  confirmed once price reverses by X% plus a multiple of ATR from the running extreme. It is confirmed at the bar where
  the reversal amount is reached, so it is also look-ahead-safe if coded that way.
- **Strict versus internal swings** (LuxAlgo CHoCH page, https://www.luxalgo.com/library/concept/change-of-character/).
  The "strict" swing is the higher low that produced the trend's last extreme. "Internal" swings are any smaller pivots.
  LuxAlgo says this is a speed-versus-reliability trade-off: "pick one convention and keep it fixed". No numbers are
  given.

**Pre-registration advice:** fix N (for example N = 2 and N = 3 on 5-minute bars, or an ATR zig-zag) **before** looking
at results, and count every N tried in the Benjamini–Hochberg (BH) family.

---

## 1. Dow theory: higher highs and higher lows define the trend

**Rule (classical).** An uptrend is a series of higher highs (HH) and higher lows (HL). A downtrend is lower highs (LH)
and lower lows (LL). The trend stays in force until a definite reversal, meaning at least one lower high plus one lower
low, or the mirror.

**Sources and evidence**

- **Brown, Goetzmann & Kumar (1998)**, "The Dow Theory: William Peter Hamilton's Track Record Reconsidered", *Journal of
  Finance* 53(4), 1311–1333. Abstract: https://econpapers.repec.org/RePEc:fth:nystfi:98-013 ; summary:
  https://www.cxoadvisory.com/?p=582
  - Claims: Hamilton's 1902–1929 timing calls give "high Sharpe ratios and positive alphas". This reverses Cowles (1934),
    who did not adjust for risk.
  - Method: a neural net was fitted to Hamilton's calls and then run out of sample.
  - The secondary summary adds that Dow theory has some predictive power after 1929, but trading frictions prevent large
    excess returns, especially since the 1970s. Not verified against the full text.
  - Rigour: peer-reviewed, risk-adjusted, daily DJIA. It is **not** a mechanical HH/HL pivot rule; it replicates one
    editor's judgement.
- **Oxford Capital Strategies, "Dow Theory – Trend"** (https://oxfordstrat.com/trading-strategies/dow-theory-trend/).
  - Mechanical rule: DeMark pivots of size N.
    - Long: buy stop one tick above the lowest supply-pivot high **during a bear trend**. This is a counter-trend reading
      of the first break of a lower high.
    - Short: the mirror.
    - Time exit: n days. Stop: 6 × ATR(20).
  - Test: 42 futures markets, 1980–2013, $0 and $100 round-turn costs.
  - Results appear only in charts (not readable as text). The author's own grade is **"C"** on an A–D scale.
- **Quantified Strategies, "Dow Theory strategy"** (https://www.quantifiedstrategies.com/dow-theory-strategy/) tests
  DIA/IYT confirmation rules on daily bars. The page was behind a bot check, so the numbers are **not verified**.

**Codeable version (D1, trend state).** On 5- or 15-minute index spot bars with confirmed pivots of size N:

- trend = UP if the last two confirmed swing highs are rising AND the last two confirmed swing lows are rising;
- trend = DOWN for the mirror;
- otherwise RANGE.

Use the state as a filter: only CE in UP, only PE in DOWN. It is not an entry by itself.

---

## 2. Break of structure (BOS): continuation

**Rule.** In an uptrend, a BOS is a **close** above the most recent confirmed swing high while the last higher low still
holds. A bearish BOS is the mirror. A wick through the level that closes back inside does **not** count.

**Sources**

- TheRightTrader, "Market structure" (https://therighttrader.com/learn/price-action/market-structure). Closes only.
  States plainly: "There is no reliable published success rate for market structure and break of structure."
- LuxAlgo CHoCH/BOS page (https://www.luxalgo.com/library/concept/change-of-character/). A body close beyond the swing,
  "ideally with displacement", is "the usual filter against noise and stop runs". No numbers are given.
- TradingView user back-tests, for example RTY 4-hour BOS with 13 trades
  (https://pl.tradingview.com/chart/RTY1%21/uHQ94yQa-RTY-Russell-4H-Backtest-Break-of-market-structure). Others report
  46% over 26 trades and 67% over 24 trades. These samples are far too small to mean anything, and the authors chose
  them themselves. **Not evidence.**
- **Closest academic relative: the trading-range break (TRB) of Brock, Lakonishok & LeBaron (1992)**, *Journal of
  Finance* 47(5), https://ideas.repec.org/a/bla/jfinan/v47y1992i5p1731-64.html. Rule: buy when price breaks above a
  local maximum (resistance), sell below a local minimum. DJIA daily, 1897–1986.
  - Buy-signal returns were above sell-signal returns, and bootstrap tests (AR(1), GARCH-M, EGARCH) could not explain
    the difference.
  - **But** Sullivan, Timmermann & White (1999, *Journal of Finance* 54(5), working paper
    https://www.fmg.ac.uk/sites/default/files/2020-11/dp303.pdf) re-tested this with a data-snooping correction. Out of
    sample, 1987–1996, "the probability that the best-performing trading rule did not outperform the benchmark … is
    nearly 12 percent … scant evidence that technical trading rules were of any economic value". On **S&P 500 futures
    since 1984 they find no evidence** of value. (Quoted from the paper's introduction.)
  - Bajgrowicz & Scaillet (2012), *Journal of Financial Economics* 106(3), 473–491
    (https://econpapers.repec.org/RePEc:eee:jfinec:v:106:y:2012:i:3:p:473-491) found two things. First, an investor
    could never have picked the future best rules in advance (persistence tests). Second, even in sample, the
    performance is "completely offset by low transaction costs".
- **Why breaks might follow through: Osler (2003)**, "Currency Orders and Exchange Rate Dynamics", *Journal of Finance*
  58(5) (staff report https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr125.pdf). Based on about
  9,700 real stop-loss and take-profit orders at one bank (FX, Sep 1999 – Apr 2000):
  - take-profit orders cluster *at* round numbers, so trends tend to reverse there;
  - stop-loss orders cluster *just beyond* round numbers, so trends **speed up after the level is crossed**.
  This is the order-flow reason a close beyond a swing could continue. It is FX, not NSE.

**Codeable version (B1, BOS continuation).**

- Conditions: trend state UP (D1). Bar t **closes** above the last confirmed swing high H.
- Entry: buy the ATM CE at the next bar's open.
- Stop: index stop at the last confirmed swing low (the HL), or a fixed premium stop.
- Exit: a target of 1R or 2R on the index, or the time exit. Square off by 15:05 on expiry day and by 15:20 otherwise.
- Bearish: the mirror, buying the PE.
- Variant B1w: "wick break" (high > H) to compare against the close rule. Pre-register that this variant is the
  noisier one.

---

## 3. Change of character (CHoCH) and market structure shift (MSS): reversal

**Rule.** CHoCH is the **first close against the trend**. In an uptrend, it is a close below the most recent meaningful
higher low; in a downtrend, a close above the most recent lower high.

- LuxAlgo treats MSS as an alias of CHoCH. Some traders reserve "MSS" for a stronger version: a counter-trend break
  **with displacement right after a liquidity sweep** of the trend's extreme.
- LuxAlgo: "a lone CHoCH is failure-prone … a warning rather than an entry".

**ICT "2022 model"** (free 2022 YouTube mentorship by Michael J. Huddleston; summary at LuxAlgo,
https://www.luxalgo.com/library/concept/model-2022/):

1. Mark liquidity on 15-minute to 1-hour charts: prior-day high/low, session high/low, equal highs/lows.
2. A **raid**: price trades through such a level (an old low, for a long).
3. On 1–5-minute charts, an energetic leg **closes through the most recent opposing swing** (the MSS) and leaves a fair
   value gap (FVG). FVG = a three-candle gap: bar1.high < bar3.low for bullish (ProRealCode ICT indicator,
   https://www.prorealcode.com/prorealtime-indicators/ict-concepts-indicator/).
4. Entry on the retrace into the FVG, or into the order block or the 62–79% OTE band.
5. Stop beyond the raided extreme. Target: the opposite liquidity pool.
6. Time windows: London/NY killzones. These are **filters, not part of the setup**.

"Structure breaks without a prior raid are treated as continuation noise."

**Evidence.** LuxAlgo itself says: "no published, independently audited track record exists"; mechanized public
backtests report "mixed results depending on definitions"; "highly discretionary". The only futures example found is a
TradingView note that NQ 3-minute MSS "seems profitable" after adding a 200-SMA filter for shorts
(https://www.tradingview.com/chart/NQ1!/MmuzRSI8-ICT-market-structure-shift-then-buy-sell-strategy-on-3-min/). It is
anecdotal, with no sample size. **Rated: untested marketing.**

House note: R130 already ran sweep → MSS → FVG, and R133 ran CISD. R139's MSS variants should differ, for example the
plain MSS without the FVG step, or MSS plus the pullback entry from section 5.

**Codeable versions**

- **C1, plain CHoCH.** Trend state UP (D1). Bar t **closes** below the last confirmed swing low L (the protected higher
  low). Buy the ATM PE on the next bar. Index stop at the last swing high (the final HH). Target 1R or 2R, or the time
  exit.
- **C2, MSS with a sweep and displacement.**
  - Sweep: within the last K bars (for example K = 12 on 5-minute bars), price traded above the prior day's high or a
    confirmed swing high, then turned.
  - Trigger: a bar closes below the most recent confirmed swing low.
  - Displacement: that bar's body ≥ 1.0 × ATR(14) (pre-register the factor).
  - Entry and stop: buy the PE next bar; stop at the sweep extreme.

---

## 4. The 1-2-3 reversal: Sperandeo (Trader Vic) and Joe Ross

**Sperandeo's 1-2-3 trend change** (*Trader Vic: Methods of a Wall Street Master*, 1991). Secondary sources: Bulkowski
https://www.thepatternsite.com/123tc.html ; RoboForex
https://blog.roboforex.com/blog/2020/02/11/trading-like-sperandeo-1-2-3-reversal-and-2b-pattern

For the end of an uptrend (Bulkowski's close-based wording):

1. Price **closes below the up-trendline**, drawn from the lowest low C to the highest high A.
2. Price re-tests high A and fails to make a clear new high. It may poke slightly above: that is the "2B".
3. Price **closes below the low B** between A and point 2. The trend has changed.

Bulkowski's statistics (daily stocks, no period or market stated):

- after downtrend 1-2-3s, **74 of 101 (73%)** rose at least 20% from low A;
- after uptrend 1-2-3s, only **29 of 67 (43%)** fell at least 20%.

No entry, stop, target or costs are given, so this is not a trading test. One web source titles it "predicts a change
in trend 80% of the time" (https://mql5.com/en/blogs/post/119876). **Not verified; treat as marketing.**

**Sperandeo's 2B** (same book). A new high is made, there is a pullback, then a retest makes a marginal new high and
fails back below the old high.

- Quoted in secondary sources: for intermediate 2Bs, the reversal "will usually break within three to five days"
  (https://stockjock2.blogspot.com/2007/08/sperandeo-analysis.html).
- Stop above the new high. Target: the swing low before the high.
- No numbers.

**Joe Ross, *Law of Charts*** (free e-book https://tradingeducators.com/free-ebook-the-law-of-charts-tloc ; PDF mirror
https://c.mql5.com/3/128/Law_of_Charts.pdf):

- **1-2-3 low**: point 1 is the low, point 2 is the rally high, and point 3 is a higher pullback low that does not break
  point 1.
- Entry on the break of point 2.
- **Ross hook** = "the first failure to make a new extreme after the breakout of a 1-2-3, a ledge or a trading range".
- Ross preferred his "Trader's Trick" entry: entering ahead of the break of point 2 or the hook. He warns that the
  break is often a stop-run.
- Ross says the break of a subsequent hook "establishes" the trend.
- **No win rate is published** (TheRightTrader, https://therighttrader.com/learn/trading-strategies/ross-hook).

**Codeable version (R1, 1-2-3 top, bearish; mirror for bullish).** With confirmed pivots:

- swing high P1 = the highest high of the session or leg;
- swing low P2 after P1;
- swing high P3 after P2 with P3 < P1 (a lower high).

Trigger: a bar **closes** below P2. Buy the ATM PE next bar. Index stop just above P3. Target = P1 − P2 projected down
from P2 (a measured move), or 2R.

- Ross hook add-on: after the trigger, the first bar that fails to make a new low marks the hook. Re-enter or add on a
  close below the hook low.

---

## 5. Pullback entry: sell the first lower high in a new downtrend (and the mirror)

**Rule.** After a CHoCH or 1-2-3 has created the first lower low, wait for the bounce. Its top is the first lower high.
Enter when the bounce fails.

- This is the same geometry as point 3 of the Ross 1-2-3, and as Al Brooks's "L2" (second-entry short).
- Brooks's L2, per LuxAlgo's bar-counting summary (https://www.luxalgo.com/library/concept/bar-counting-h1-h2/): in a
  bear leg, count the bounce bars. The second bar that breaks the prior bar's low is the L2. Sell stop one tick below
  the L2 signal bar. Stop above that bar or above the bounce high.
- Why the second entry: the first attempt to resume often traps traders.
- NexusFi notes that in strong trends the second leg is often shorter
  (https://nexusfi.com/a/strategies/two-legged-pullback-trading).
- **No published numbers found.**

**Codeable version (P1, first lower high after a lower low).**

- Setup: trend flips to DOWN by C1, so a confirmed lower low exists. Wait for the next confirmed swing high LH with
  LH < the prior swing high.
- Trigger: a bar closes below the low of the bar that made LH, or below the last bar's low (the L2 style).
- Entry: buy the ATM PE next bar. Index stop just above LH.
- Target: the previous lower low (1st target), then 2R or the time exit.
- Optional filter (from section 7): the LH must sit in the **premium** half (≥ 50%) of the leg from the last swing high
  to the new lower low.
- Bullish: the mirror (the first higher low after a higher high), buying the CE.

---

## 6. Swing failure pattern (SFP) and Turtle Soup: fade the false break

**Turtle Soup** (Linda Raschke & Laurence Connors, *Street Smarts*, 1995/96). Summaries:
https://www.luxalgo.com/library/concept/turtle-soup/ ;
https://technical.traders.com/tradersonline/display.asp?art=2414 ; https://www.mql5.com/it/articles/2717

- Long setup:
  - today makes a **new 20-day low**;
  - the previous 20-day low was made **at least 4 sessions earlier**.
- Entry: a buy stop a few ticks (some say 5–10) above the *previous* 20-day low, valid today only.
- Stop: just below today's low.
- The book's exit wording is not verified. Exits differ by source: trail, or the opposite side of the range.
- **Plus One**: the prior low is at least 3 days old, today *closes* at or below it, and the buy stop at the old low goes
  in tomorrow.
- Shorts: the mirror at 20-day highs.

**SFP** (crypto/forex usage; LuxAlgo https://www.luxalgo.com/library/concept/swing-failure-pattern/ and TradingView SFP
scripts, for example https://www.tradingview.com/script/aBEgxFEw-Swing-Failure-Pattern-SFP-Advanced-Improved/)

- Bearish SFP: the wick goes above a prior confirmed swing high and the bar **closes back below** it. Bullish SFP is the
  mirror.
- Strict versions need a same-bar close. Loose versions allow the next bar.
- Some scripts add filters: wick ≥ 0.3 × ATR beyond the level, close ≥ 0.25 × ATR back inside, or wick ≥ 55% of the bar
  range. LuxAlgo says there is "no honest fixed number".
- Entry on the close of the SFP bar. Stop beyond the wick tip plus a buffer.

**Evidence**

- Oxford Capital Strategies tested Turtle Soup (channel 10–100, time exit 1–40 days, 6 × ATR stop) on 42 futures,
  1980–2011, with $0 and $100 costs (https://oxfordstrat.com/?p=5614). Numbers appear only in charts. The author's
  grade appears to be **"D"**.
- LuxAlgo: no statistics; "regime-dependent", "loses when the breakout is real".
- A 55–65% win-rate claim seen on a blog has **no data and is not verified**.
- No rigorous SFP test was found.
- Osler (2000, below) gives indirect support: published support/resistance levels do stop intraday moves more often
  than random levels.

**Codeable version (S1, intraday SFP or turtle soup on the index).**

- Level: the prior day's high/low, or the highest high / lowest low of the last 20 bars, provided that extreme was made
  ≥ 4 bars ago (the intraday analogue of Turtle Soup).
- Trigger (bearish): high > level AND close < level on the same bar.
- Entry: buy the ATM PE next bar. Index stop at the wick high plus 0.1 × ATR.
- Target: 1R / 2R, or the opposite side of the 20-bar range.
- Bullish: the mirror.
- Variant S1+1: allow the close back inside on the next bar.

---

## 7. Premium / discount, equilibrium (50%) and OTE (62–79%)

**Rule** (ICT; LuxAlgo https://www.luxalgo.com/library/concept/premium-and-discount/ and
https://www.luxalgo.com/library/concept/optimal-trade-entry/):

- Dealing range = the most recent meaningful leg, from swing low to swing high (one that swept a level or broke
  structure).
- Equilibrium = (high + low) / 2.
- Below 50% = **discount** (look for longs only if the leg is bullish). Above 50% = **premium** (look for shorts only if
  the leg is bearish).
- **OTE** = the 62%–79% retracement of the leg, with 70.5% as the "sweet spot". Some use 61.8% and 78.6%. Every OTE
  long is a discount buy, but not every discount buy is OTE.
- LuxAlgo calls premium/discount "context, not a signal". First target = equilibrium, then the prior high.
- **Evidence: none.** LuxAlgo gives no backtest, win rate or sample size. The 50% level is traced to W. D. Gann.
- Related academic note: Osler (2000) mentions the widely used "50 percent rule" and the 38.2%/61.8% retracements, but
  did **not** test them as trading rules.

**Codeable version (E1, filter, not an entry).** For a long signal from B1, P1 (mirror) or R1 (mirror):

- take the last confirmed bullish leg, from swing low SL to swing high SH;
- require the entry price ≤ SL + 0.5 × (SH − SL) (discount);
- OTE version: SL + 0.21 × (SH − SL) ≤ entry ≤ SL + 0.38 × (SH − SL), i.e. a 62–79% retracement.

Shorts are the mirror. Report each signal with and without the filter (this costs one BH slot per filter).

---

## 8. Academic and quantitative evidence (what is actually measured)

| Study | Market and data | Finding | Rigour |
|---|---|---|---|
| Brock, Lakonishok & LeBaron 1992, *J. Finance* 47(5) https://ideas.repec.org/a/bla/jfinan/v47y1992i5p1731-64.html | DJIA daily 1897–1986 | Moving-average and trading-range-break rules: buy-signal returns above sell-signal returns, which bootstrap null models cannot explain | Peer-reviewed, but in sample and without costs |
| Sullivan, Timmermann & White 1999, *J. Finance* 54(5) https://www.fmg.ac.uk/sites/default/files/2020-11/dp303.pdf | DJIA 1897–1996; S&P 500 futures 1984–96 | Best rule's edge survives the snooping test in sample. Out of sample 1987–96: p ≈ 0.12, "scant evidence". S&P futures: "no evidence" | High (Reality Check bootstrap) |
| Bajgrowicz & Scaillet 2012, *JFE* 106(3) https://econpapers.repec.org/RePEc:eee:jfinec:v:106:y:2012:i:3:p:473-491 | DJIA daily from 1897 | Best rules cannot be picked in advance; low costs wipe out the edge even in sample | High (false-discovery-rate control) |
| Park & Irwin 2007, *J. Econ. Surveys* 21(4) https://econpapers.repec.org/RePEc:bla:jecsur:v:21:y:2007:i:4:p:786-826 | Survey of 95 modern studies | 56 positive, 20 negative, 19 mixed. Profits mostly before the early 1990s. Warns of snooping, after-the-fact rule choice and costs. (Counts from the peeref listing; not verified against the full text) | Survey |
| Lo, Mamaysky & Wang 2000, *J. Finance* 55(4) https://www.nber.org/papers/w7613 | US stocks daily 1962–96 | Kernel-regression pattern detection (head-and-shoulders, double tops etc.): patterns carry "incremental information", but statistical anomaly is not the same as trading profit | High statistically; no trading test |
| Brown, Goetzmann & Kumar 1998, *J. Finance* 53(4) https://econpapers.repec.org/RePEc:fth:nystfi:98-013 | DJIA 1902–29 | Hamilton's Dow-theory calls: positive alpha and high Sharpe (risk-adjusted) | Peer-reviewed; one man's calls |
| Osler 2000, FRBNY EPR 6(2) https://www.newyorkfed.org/medialibrary/media/research/epr/00v06n2/0007osle.html | FX, 1-minute quotes, 6 firms' published support/resistance levels, Jan 1996 – Mar 1998 | Rates "bounced" (did not cross within 15 minutes) at published levels 60.8% of the time vs 56.2% at 10,000 sets of random levels. Significant at 5% for 13 of 16 firm-currency pairs | High; FX, not equities. Edge is about 4.6 percentage points |
| Osler 2003, *J. Finance* 58(5) https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr125.pdf | About 9,700 FX stop and take-profit orders, 1999–2000 | Take-profit orders cluster at round numbers (reversals); stop orders cluster just beyond them (acceleration after the break) | High; explains mechanism only, no strategy |
| Gao, Han, Li & Zhou 2018, *JFE* 129(2) https://econpapers.repec.org/RePEc:eee:jfinec:v:129:y:2018:i:2:p:394-414 | SPY 1993–2013, plus 10 other ETFs, FTSE and EuroStoxx futures | First half-hour return predicts the last half-hour return. Predictive R² 1.6%, rising to 2.6% with the 12th half-hour. Timing rule earns 6.3% a year gross vs −0.5% (secondary figure, gross of costs) | High; intraday index |
| Zarattini, Aziz & Barbon 2024, SFI WP 24-97 https://ideas.repec.org/p/chf/rpseri/rp2497.html | SPY 2007 – early 2024, intraday | "Noise area" breakout (14-day average intraday move bands): +1,985% total, 19.6% a year net of costs, Sharpe 1.33. A replication on TradingView found lower results | Working paper; costs included; independent replication weaker |
| Holmberg, Lönnbark & Lundström 2013, *Finance Research Letters* 10(1) https://ideas.repec.org/a/eee/finlet/v10y2013i1p27-33.html | Crude oil futures, intraday | Opening-range breakout returns significantly above zero and above a fair game (bootstrap). Exact numbers not verified | Peer-reviewed, short paper |
| Muruganandan 2020, Colombo Business Journal https://cbj.sljol.info/articles/56 | BSE Sensex 2000–2018 | RSI rule: no positive return even before costs. MACD sell signals did better in bear markets but weak after risk | India, daily; not swing rules |
| Mok, Lam & Li 2000, *Rev. Quant. Fin. Acc.* (https://scholars.hkbu.edu.hk/en/publications/using-daily-highlow-time-to-test-for-intraday-random-walk-in-two-) | Hang Seng and S&P futures | Timing of the daily high and low: random walk not rejected for S&P; Hang Seng extremes cluster in the first 15 minutes | Timing only, not a strategy |

**India gap.** No peer-reviewed test of swing-structure, BOS or range-break rules on NIFTY or BANKNIFTY intraday was
found. An Asian Journal of Management paper (https://indianjournals.com/article/ajm-8-3-082) reports that only the first
20 minutes of the Nifty 50 session showed significant returns after rolling settlement. Abstract only; not verified.

**Practitioner backtests.**

- A public ATR zig-zag test on S&P hourly data found that trading every confirmed leg loses −0.2 to −0.5 points a leg
  **at zero cost** (informal GitHub fork, https://forge.mql5.io/aron3/Warrior_EA/src/branch/main/research/pivot_expectancy.py).
  Weak source, but a useful warning: raw swing-following is not an edge by itself.
- Quantified Strategies pages were behind a bot check, so no numbers were taken from them.
- No Robot Wealth or QuantConnect study of BOS or swing-structure rules with an adequate sample was found.

**Bottom line.**

- Simple breakout rules once had an edge, but in rigorous tests it shrinks out of sample and dies after costs (STW 1999,
  BS 2012).
- The most robust intraday finding is **index momentum later in the day** (Gao et al. 2018; Zarattini et al. 2024).
- Support and resistance levels do slow price slightly more than random levels (Osler 2000: 60.8% vs 56.2%), and breaks
  beyond them can speed up because stop orders sit there (Osler 2003).
- These give an honest prior: BOS continuation and SFP fades are both plausible but small effects. Expect most R139
  variants to fail after option costs.

---

## 9. Summary table

| Rule | Source | Claimed | Evidence quality | Codeable rule (signal on index spot; buy ATM option) |
|---|---|---|---|---|
| D1 HH/HL trend state | Dow/Hamilton; Brown–Goetzmann–Kumar 1998; Oxfordstrat | Positive alpha 1902–29 (risk-adjusted); Oxfordstrat grade C | Medium (old, daily) | UP if the last 2 confirmed swing highs and lows are both rising; DOWN the mirror; use as a filter |
| B1 BOS continuation (close) | TheRightTrader; LuxAlgo; BLL 1992 range break | No published win rate; range break worked in sample, faded out of sample | Low for SMC; Medium–Low academic | Trend UP and a close above the last confirmed swing high → CE next bar; stop at the last HL; 1R/2R or time exit |
| B1w BOS wick break | Same | None | None | high > swing high (comparison only) |
| C1 CHoCH | LuxAlgo; TheRightTrader | "Failure-prone" (LuxAlgo) | None | Trend UP and a close below the protected HL → PE; stop at the last HH |
| C2 MSS + sweep + displacement | ICT 2022 model (LuxAlgo summary) | No audited record; "mixed" | Untested marketing | A sweep of the prior-day high or swing high within K bars, then a close below the last swing low with body ≥ 1 × ATR(14) → PE; stop at the sweep high |
| R1 1-2-3 top/bottom (+ Ross hook) | Sperandeo 1991; Ross *Law of Charts*; Bulkowski | 73% of 101 / 43% of 67 reach a 20% move (daily stocks); "80%" claim not verified | Low (no costs, no trading test) | P1 high, P2 low, P3 < P1; a close below P2 → PE; stop above P3; target the measured move or 2R |
| 2B (one-bar failed new high) | Sperandeo | Reversal within 3–5 days (intermediate) | Anecdotal | New high above the prior swing high, then a close back below it within 3 bars → PE |
| P1 first LH after LL (L2) | Ross 1-2-3 point 3; Brooks L2 | None | Anecdotal | After C1, the first confirmed LH < the prior SH; a close below the LH bar's low → PE; stop above the LH; target the prior LL then 2R |
| S1 SFP / Turtle Soup | Raschke–Connors 1995; SFP scripts | 55–65% claim not verified; Oxfordstrat grade D | Low | Level = prior-day high/low or a 20-bar extreme made ≥ 4 bars ago; wick beyond and close back inside → fade; stop at the wick plus 0.1 ATR |
| E1 premium/discount, OTE | ICT; LuxAlgo | "Context, not a signal"; no numbers | None | Long only if entry ≤ the leg's 50% level; OTE = 62–79% retracement band |
| Support/resistance bounce (background) | Osler 2000 | 60.8% vs 56.2% bounce (FX, 1-minute) | High (FX) | Supports S1-style fades at prominent levels |
| Break acceleration (background) | Osler 2003 | Stops cluster beyond levels | High (mechanism) | Supports B1 (close beyond the level) |
| Late-day momentum (background) | Gao et al. 2018; Zarattini et al. 2024 | R² 1.6–2.6%; Sharpe 1.33 net | High (US) | Possible time filter: structure signals that agree with the day's direction after 13:00 IST |
