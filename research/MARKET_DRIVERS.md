# What moves intraday index candles in India: evidence vs folklore

Research for hunt h18, done in Oct 2026. Our own tests on the data are in `research/HUNT_H18.md`. This page sums up
published evidence and regulator records. Each claim is graded:
- **E**: evidence-backed (peer-reviewed or a regulator's record).
- **P**: plausible but not proven.
- **F**: folklore.

## Short answer

Nobody "decides" the direction of the next candle. The most solid finding in market microstructure is that, over
seconds to minutes, **price moves in the direction of net aggressive order flow**: market orders that eat the best
bid or ask, scaled by how thin the book is. Everything else matters only through that channel:
- institutions working big orders;
- index arbitrage and basket trades;
- option hedgers;
- stop-loss cascades;
- news;
- manipulators.

None of these can be seen in advance from candles alone. The only lasting statistical footprints are small:
- mild intraday momentum;
- a volatility regime that you can partly read from where option open interest sits.

They are far smaller than the cost of buying an option.

## 1. Order flow and order-book imbalance (E)
- Cont, Kukanov & Stoikov (2014, *J. Financial Econometrics*), on 50 US stocks:
  - Over short intervals, price changes are mostly explained by **order-flow imbalance** at the best bid and ask.
  - The relation is linear, with a slope inversely proportional to market depth.
  - Traded volume alone is a noisy predictor.
  - Sources: [arXiv 1011.6402](https://arxiv.org/abs/1011.6402), [RePEc](https://ideas.repec.org/a/oup/jfinec/v12y2013i1p47-88.html).
- What this means for us: the "decider" is the imbalance between aggressive buyers and sellers right now. It explains
  the move at the same moment and forecasts only seconds ahead. It needs tick-level book data (L2/L3), which we do not
  have; minute OHLC with OI is not enough. Retail cannot act on it at a 1-minute horizon after costs.

## 2. Who trades, and who wins (E)
- SEBI's study for FY22-FY24 (Sept 2024):
  - **93% of more than 1 crore individual F&O traders lost money**, in total over Rs 1.8 lakh crore.
  - In FY24 alone, individuals lost over Rs 61,000 crore. Proprietary traders made Rs 33,000 crore gross and FPIs
    Rs 28,000 crore.
  - **97% of FPI profits and 96% of prop profits came from algorithms.**
  - Sources: [Moneylife](https://www.moneylife.in/article/93-percentage-of-individual-traders-lost-rs18-lakh-crore-in-equity-fo-in-past-3-years-sebi/75210.html), [Business Standard](https://www.business-standard.com/markets/capital-market-news/sebi-study-exposes-massive-losses-for-individual-f-o-traders-in-india-124092400948_1.html).
- What this means for us: the other side of the average retail option buyer is a fast, well-capitalised algorithm. Its
  edge is speed, spread capture, volatility selling and cross-market arbitrage, not knowing which way the next candle
  closes.

## 3. FII/DII flows and participant-wise OI (P)
- Daily FII/DII cash flows and NSE's participant-wise OI (FII index-futures long/short ratio) are widely quoted in
  the press, for example [Business Standard](https://www.business-standard.com/amp/markets/news/nifty-bank-nifty-fiis-turn-bullish-f-o-activity-hints-at-gains-ahead-124121000138_1.html).
- We found **no peer-reviewed evidence** that they predict *intraday* direction. They are published after the close,
  so at best they are a daily or multi-day sentiment input.
- Large FII programme trades do move the index on the day, through order flow (section 1). But retail sees them only
  after the fact.

## 4. Option dealers' hedging: GEX, gamma flip, vanna and charm (E for the volatility effect, P/F for direction)
- **Hedgers who are short options make volatility bigger; hedgers who are long options damp it.**
  - Ni, Pearson, Poteshman & White (2021, *Review of Financial Studies*) find a significant negative relation between
    stock volatility and the net *purchased* option position of investors who hedge.
  - Sources: [RePEc](https://ideas.repec.org/a/oup/rfinst/v34y2021i4p1952-1986..html), [paper PDF](https://ou.edu/dam/price/Finance/CFS/paper/pdf/pearsonPoteshmanWhite.pdf).
- **Market intraday momentum.** Baltussen, Da, Lammers & Martens (2021, *J. Financial Economics*), on 60+ futures from
  1974 to 2020:
  - The return over the rest of the day predicts the last 30 minutes, and the effect is linked to short-gamma hedging
    demand.
  - It reverses over the following days.
  - Sources: [EUR](https://pure.eur.nl/en/publications/hedging-demand-and-market-intraday-momentum/), [summary](https://alphaarchitect.com/hot-topic-does-gamma-hedging-actually-affect-stock-prices/).
  - Gao, Han, Li & Zhou (S&P 500 ETF) found the same: the first half-hour predicts the last half-hour ([summary](https://alphaarchitect.com/2014/08/attention-prop-traders-the-first-half-hour-of-trading-predicts-the-last-half-hour/)).
- **Gamma imbalance and illiquidity.** Negative dealer gamma together with illiquidity goes with intraday momentum;
  positive dealer gamma goes with reversal ([Barbon et al., "Gamma fragility"](https://abarbon.com/papers/gamma-fragility)).
- **Caveat for India (P).** "GEX" products (SpotGamma and its clones) must *assume* who holds which side.
  - The US convention, that dealers are long calls and short puts, does not obviously fit NSE. There, retail are net
    option buyers and the sellers are prop desks, FPIs and HNIs. Many of those sellers do not delta-hedge continuously.
  - **Our test** (HUNT_H18, T1) found that when more gamma than usual sits near spot, the next 15-60 minutes are
    *calmer*, not wilder. That held across 5 indices and again in the holdout.
  - So in India the data fits "OI piles up where the market is quiet and pins it" much better than "sellers' hedging
    makes moves bigger".
  - It predicts *how much* the market moves, not *which way*.
- **Vanna and charm flows (F/P for India).** These are dealer re-hedging as IV and time change. The US commentary is
  not tested on NSE, and we found no Indian study.

## 5. Pinning, max pain, and max-OI "support/resistance"
- **Pinning to strikes is real (E).**
  - Ni, Pearson & Poteshman (2005): optionable US stocks close near a strike on expiry far more often than chance.
    The effect is stronger where hedgers' gamma is large.
  - Sources: [cxoadvisory summary](https://www.cxoadvisory.com/equity-options/stock-price-pinning-at-options-expiration), [Avellaneda pinning model](https://math.nyu.edu/~avellane/PowerLaw.pdf).
- **Max pain (F).** An IIM-B study of NSE found that stock pinning exists in India but that "**no max pain phenomenon
  exists**" ([IIMB repository](https://repository.iimb.ac.in/handle/2074/21032?mode=full)). Max pain and "pinning to
  some strike" are different claims ([curvedtrading explainer](https://curvedtrading.com/articles/en/education/max-pain-options-explained/)).
- **Our test (T2).**
  - Before the holdout, expiry closes landed within 10% of a strike step about 23% of the time, against 19% on other
    days. That is mild clustering. It did not repeat in the holdout.
  - There was **no drift toward the max-OI or max-pain strike** in the last 2-3 hours. If anything, the drift went
    slightly away from it: BANKNIFTY max-OI -10 bps, t -2.2.
  - "Max call OI = resistance, max put OI = support" showed nothing.
  - Pinning is in any case a seller's edge, not a buyer's.

## 6. Index arbitrage, basket trades and heavyweights (E for the mechanism)
- Index futures and options are tied to the cash basket by arbitrage. When futures run rich, arbitrageurs sell futures
  and buy the basket, and the reverse when they run cheap. That is the transmission belt in the Jane Street case.
- NIFTY is driven by its top weights: HDFC Bank, ICICI, Reliance, Infosys, ITC, L&T and TCS hold roughly 45-50%.
  BANKNIFTY's top 4 hold about 70%.
- A large buyer in 3-5 heavyweights moves the index point for point. Most "the index reversed at 11:45" days are just
  heavyweight order flow.

## 7. Stop-loss clustering, "stop hunts" and liquidity sweeps
- **Stops cluster just beyond salient levels and cause cascades (E, FX).**
  - Osler (NY Fed Staff Report 150, 2002): stop-loss orders cluster just beyond round numbers and prior highs/lows.
    When they trigger, trends become *unusually rapid*: price *continues*.
  - Take-profit orders cluster *at* round numbers and cause reversals.
  - Sources: [NY Fed SR150](https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr150.pdf), [Osler, price cascades](https://icmaif.soc.uoc.gr/Year/8conf/docs/Osler-Price%20Cascades-03-03.doc).
- **"Smart money sweeps the stops, then reverses" (F).** The ICT/SMC liquidity-grab story has no academic support.
- **Our test (T4)**, 5 indices from 2020 to 2025:
  - After price pokes 0-0.10% beyond a prior-day, opening-range or rolling-60-min high/low and closes back inside, it
    does **not** reverse. It drifts 0.5-1 bp further in the sweep's direction (t ≈ -3 for the 60-min levels).
  - Clean breaks continue by 1-3 bps over 30 minutes (t up to 4.9).
  - This matches Osler's cascades and the momentum literature. It is also why the app's Liquidity 15+5 *trades the
    break* and is profitable, while fading it is not.

## 8. News and events (E)
- Scheduled macro events cause the largest intraday moves: RBI policy, US CPI and FOMC, the Budget, GDP data, and
  index heavyweights' results.
- The direction depends on the surprise, which nobody knows in advance. In the US literature, intraday momentum is
  stronger on such days (Gao et al.). Volatility rises predictably; direction does not.

## 9. Documented manipulation in Indian markets (E: regulator records)
- **Jane Street (SEBI interim order, 3 July 2025).**
  - Alleged "intraday index manipulation" on BANKNIFTY (and NIFTY) expiry days, January 2023 to March 2025.
  - Patch I, 09:15 to about 11:46: aggressive buying of BANKNIFTY constituents and stock futures, often more than 20%
    of market volume in names such as Kotak, SBI and Axis. Orders were placed above the last traded price, which
    lifted the index.
  - At the same time it built much larger *short-delta* index-option positions.
  - Patch II, after 11:49: it sold the cash and futures back, pushing the index down into its options book.
  - Example, 17 Jan 2024: Rs 4,370 crore of BANKNIFTY stocks and futures bought in the morning. Option delta went from
    -Rs 7,311 crore to -Rs 39,426 crore. Rs 5,372 crore sold in Patch II. Profit about Rs 735 crore that day.
  - A second pattern, "extended marking the close", was heavy selling in the last hour of expiry to push down the
    settlement VWAP. Example, 10 Jul 2024: about Rs 2,800 crore sold.
  - It continued after NSE's caution in Feb 2025. SEBI impounded Rs 4,843.57 crore and barred the group, and Jane
    Street deposited the money.
  - Jane Street disputes the findings at SAT. It cites NSE and SEBI surveillance reports that allegedly found no
    manipulation. As of the sources found, no final order had been issued.
  - Sources: [Oxford Business Law Blog](https://blogs.law.ox.ac.uk/oblb/blog-post/2025/07/jane-street-and-expiry-day-trap-unpacking-sebis-crackdown-algorithmic), [Kotak](https://www.kotaksecurities.com/news/market-news/sebi-bars-jane-street), [Moneylife](https://www.moneylife.in/article/jane-street-accused-of-massively-rigging-of-indias-derivatives-market-sebi-orders-ban-escrow-of-rs4843-crore-illegal-gains/77585.html), [TradingHub analysis](https://tradinghub.com/insights/an-analysis-of-sebis-jane-street-order-part-1), [ECGI](https://www.ecgi.global/publications/blog/expiry-day-and-the-governance-of-algorithmic-trading-the-jane-street-episode), [SAT appeal (Moneylife)](https://moneylife.in/article/jane-street-questions-sebis-findings-demands-nsesebi-documents-in-sat-appeal-reports/78222.html), [SAT hearing (Stocktwits)](https://stocktwits.com/news-articles/markets/equity/jane-street-vs-sebi-tribunal-questions-regulator-s-refusal-to-share-probe-papers/chwKTofRdsD).
- **Could retail have seen it?** Not in a usable way.
  - SEBI built the case from *entity-level* trade and position data, which no retail trader has.
  - On the index chart, a manipulated day looks like any morning rally that fades.
  - Our T3 test: on BANKNIFTY expiry days in the SEBI window, the correlation between the morning and afternoon
    returns was **0.01**. After a big morning (more than 0.5%), the afternoon return was **-0.02 bps**, so no
    systematic reversal.
  - The alleged scheme ran on about 18-20 specific days out of more than 100 expiries. A retail rule of "fade every
    expiry morning" would have caught nothing on average.
- **The rules changed after Nov 2024.** SEBI's 1 Oct 2024 circular took effect on 20 Nov 2024:
  - one weekly-expiry index per exchange, so the BANKNIFTY, FINNIFTY and MIDCP weeklies ended;
  - an extra 2% ELM on short options on expiry day;
  - intraday position-limit monitoring from 1 Apr 2025.
  - So the BANKNIFTY weekly-expiry game the order describes no longer exists in that form.
  - Sources: [Zerodha](https://zerodha.com/z-connect/business-updates/sebis-new-rules-for-index-derivatives-heres-whats-changing), [5paisa](https://5paisa.com/news/nse-to-phase-out-weekly-index-derivatives-for-bank-nifty-nifty-midcap-select-and-nifty).
- **Spoofing and layering.** SEBI's Patel Wealth Advisors order (Apr 2025):
  - 621 spoofing instances across 173 scrips, cash and F&O, Jan 2021 to Jan 2025.
  - Large fake orders away from the touch were placed to lure the other side. Small genuine trades were done on the
    opposite side, then the spoof orders were cancelled.
  - Rs 3.22 crore was impounded.
  - Only L2/L3 data would show it.
  - Sources: [Moneylife](https://moneylife.in/article/patel-wealth-advisors-4-directors-barred-from-markets-over-order-spoofing-charges/77003.html), [Business Standard](https://www.business-standard.com/markets/news/sebi-bars-patel-wealth-advisors-4-directors-over-order-spoofing-charges-125042800957_1.html).
- **Pump-and-dump.** SEBI's Sadhna Broadcast order (2025):
  - Five YouTube channels spread fake news ("Adani acquisition", "5G licence").
  - 45% of the volume was trades among connected entities. The price went from about Rs 2.6 to Rs 33 and back.
  - Rs 21.45 crore in penalties; 59 entities barred, including actor Arshad Warsi.
  - This happens in small caps, not in index options.
  - Sources: [Moneylife](https://moneylife.in/article/sadhna-broadcast-stock-manipulation-sebi-slaps-rs2145-crore-penalty-bars-59-including-promoters/77288.html), [Business Today](https://www.businesstoday.in/amp/markets/story/sebi-pump-and-dump-case-arshad-warsi-fined-return-unlawful-gains-478405-2025-05-30).

## What is evidence and what is folklore

| claim | grade | our data (HUNT_H18) |
|---|---|---|
| Order-flow imbalance drives the next seconds and minutes | E | not testable (no order book) |
| Big option-hedger gamma changes realised volatility | E (US) | more gamma near spot than usual → **calmer** next 15-60 min (t -5 to -13, holds in the holdout) |
| GEX sign / "gamma flip" decides trend vs reversion | P/F | inconsistent: no stable effect on direction |
| Intraday momentum (morning predicts afternoon; breaks continue) | E | small but present: +1-3 bps per 30 min after breaks |
| Liquidity sweep then reversal ("stop hunt") | F | **no**: sweeps drift slightly *on*, not back |
| Pinning to strikes on expiry | E (US) / weak in India | mild clustering before the holdout, not in the holdout |
| Max pain / max OI pulls the price | F | **no** drift toward it |
| Jane Street-style morning push then reversal on expiry | E (as an allegation on specific days) | invisible in the aggregate data; nothing to exploit |
| FII/DII data predicts intraday direction | F/P | not tested (daily data, after the close) |
