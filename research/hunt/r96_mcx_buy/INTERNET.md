# R96 round 1: internet research on commodity option BUYING (21 Oct 2026)

About 25 web searches (WebSearch / WebFetch), mostly abstracts and secondary summaries; full papers were not read
unless stated. Numbers that could not be verified are marked so. Nothing here is our own evidence; it only chose which
rules to pre-register.

## Headline

- **Option buyers lose on average in commodities.** The variance risk premium is negative for crude and natural gas
  (Trolle & Schwartz 2010, J. Derivatives) and in most of 21 commodity markets (Prokopczuk, Symeonidis & Wese Simen
  2017, J. Banking & Finance; short-variance Sharpe about 0.4). Any buying rule must beat that built-in loss, so an
  edge has to come from **timing or direction**, not from "options are cheap".
- **Event straddles are not supported**: realised vol rises on EIA report days but implied vol tends to *fall*
  (Sharma 2017, UGA thesis); gold and crude IV fall after FOMC statements (Finance Research Letters 2023, vol 55).
- **No published evidence on MCX option buying** (spreads, buyer P&L). SEBI's loss studies cover equity F&O only.

## Approaches found

| approach | source(s) | claimed edge | mechanism | testable with our data |
|---|---|---|---|---|
| EIA crude / gas-storage event straddle | Sharma 2017 (openscholar.uga.edu/record/14104); Linn & Zhu 2004 J. Futures Mkts; Ye & Karali; CME OpenMarkets 2022 | realised vol up, IV down on report day; WTI move 1.2x on EIA days (2022, 22 releases, exchange marketing) | scheduled information; IV crush after | partly: realised move vs straddle cost yes (~60 releases); surprise conditioning **no** (no consensus forecasts) |
| CPI / NFP / FOMC straddle (gold, crude) | FRL 2023 vol 55 ("uncertainty resolution"; author list not confirmed) | IV falls after FOMC | uncertainty resolves | partly: ~14 CPI, ~14 NFP in our window; FOMC 14:00 ET is at / after the MCX close, so untestable intraday |
| pre-event IV run-up (buy days before, sell before release) | Gao, Xing & Zhang 2018 JFQA (equity earnings straddles +3.34%); Horan, Peterson & Mahar 2004 (OPEC) vs OptionMetrics (no rise after 2005) | mixed | under-priced pre-event uncertainty | yes, small samples; monthly MCX options carry little single-event premium |
| volatility squeeze (Bollinger inside Keltner), NR7 / inside day | Crabel 1990 (book); Backtrex WTI squeeze 2016-26 (vendor, 348 trades, no costs); Quantified Strategies (weekly squeeze mostly fails) | anecdotal; no peer-reviewed test | volatility clusters and mean-reverts: quiet then expansion; says nothing about direction | yes: signal on daily futures since 2012; option leg Aug 2025 - Oct 2026 only |
| time-series momentum / Donchian, via long options | Moskowitz, Ooi & Pedersen 2012 JFE; Hurst, Ooi & Pedersen 2017; Fung & Hsieh 2001 RFS (trend following ~ lookback straddles); Kim, Tse & Wald critique | strong before 2009; commodity TSMOM Sharpe ~0.07 in 2010-18 (unverified working paper); no paper tests options instead of futures | under- then over-reaction, hedging pressure | partly: signal yes; options only 14.5 months; ATM+/-3 strikes and the near month only (no rolls) |
| low IV vs forecast realised vol (VRP filter) | Trolle & Schwartz 2010; Prokopczuk et al. 2017; Aka, Gagnon & Power 2025 J. Futures Mkts (details unverified) | VRP negative -> buyers lose on average; no paper shows a filter flips it for long-only | insurance premium | yes: ATM IV in our prints, realised vol from daily OHLC since 2012 |
| intraday momentum (first half-hour -> rest of day) | Gao, Han, Li & Zhou (SPY); Jin, Kearney, Li & Yang 2020 J. Futures Mkts (Chinese commodity futures; strongest on high-volume / volatile sessions); Adelaide working paper on crude (on EIA days the release half-hour predicts the last) | significant in commodity futures (no option tests) | late informed / hedging flow continues the early move | yes: 1-minute futures + same-day options |
| overnight COMEX / NYMEX lead into MCX | Indian price-discovery studies (mixed); R2, R82, R94 here | mixed | off-hours information | already tested here (R2, R82 O1, R94 idea 5): the 09:00 gap prices the night -> **not repeated** |
| big-move continuation / reversal; RSI(2) pullbacks | Hua & Wei 2014 (weak reversal); Mazouz & Wang (efficient); Urquhart & Zhang 2018 (momentum yes, reversal no, 29 commodities); Connors RSI(2) (equity only) | weak | over-reaction (weak) | yes on daily futures; expect a null |
| gold autumn seasonality; NG winter | Baur 2013 (Sep / Nov positive 1980-2010); Potrykus & Augustynowicz 2024 (reversed) | weak / unstable | Indian festive demand, hedging | tested by R94 idea 4 (no) -> **not repeated** |
| gold / silver ratio reversion | practitioner material only | no after-cost evidence | ratio mean reversion | tested by R94 idea 2 (trends, not reverts) -> **not repeated** |
| EIA inventory-surprise trading | Bu 2014; Gay, Simkins & Turac | returns respond to the surprise | information shock | **untestable**: no consensus forecasts |
| opening-range breakout | Lundström (Umeå working papers; crude ORB > 0, volatility-dependent) | positive on long crude history, costs unclear | early-session order flow | tested on MCX by R3 / R22-R23 / R83 E6 (loses after costs) -> **not repeated** |
| MCX specifics | MCX / broker pages | ITM MCX options devolve into futures at expiry; CTT 0.05% of sale premium | | no bid/ask anywhere: costs are an assumed spread, stressed at 2x |

## What round 1 takes from this

1. Options price the move on average (VRP), so rules must pick moments where the realised move should beat the implied
   one: after volatility compression, when IV is below a realised-vol forecast, and on large-volume sessions.
2. Event straddles are a known-weak idea, but they are cheap to test and our own MB1 found event hours 1.2-1.7x larger;
   they go in as one idea (and the IV crush is measured).
3. Intraday momentum is the best-documented directional effect in commodity futures; it goes in, filtered to big
   first half-hours (Jin et al.: strongest on volatile sessions).
4. Multi-day directional holds make the option spread small next to the move: squeeze, NR7, wide-range continuation and
   pullback-in-trend go in, with the signal checked on 2012 - Jul 2025 daily futures as well.
5. Not repeated (already tested here): COMEX overnight lead, seasonality, gold/silver ratio, ORB, calendar spreads,
   option selling (out of scope).

## Round 3 scan (21 Oct 2026, before the round-3 pre-registration)

Effect sizes below are from abstracts or summaries where marked; none were verified on full texts.

| idea | source | used |
|---|---|---|
| EIA-day intraday momentum: on EIA days the 11:00-11:30 ET half-hour predicts the last half-hour | Wen, Indriawan, Lien & Xu, Energy Journal 44(5) 2023, https://ideas.repec.org/a/sae/enejou/v44y2023i5p149-172.html | R3H |
| market intraday momentum (prior close -> last 30 min predicts the last 30 min; dealer gamma hedging) across 60 futures 1974-2020 | Baltussen, Da, Lammers & Martens, JFE 142 (2021), https://www3.nd.edu/~zda/intramom.pdf | R3I |
| opening-range breakout on crude futures earns > 0; returns differ strongly by volatility state | Holmberg, Loennbark & Lundstroem, FRL 10(1) 2013, https://ideas.repec.org/p/hhs/umnees/0845.html; Lundstroem WP 861, https://swopec.hhs.se/umnees/abs/umnees0861.htm | R3J |
| EIA spike reverts within ~25 minutes / over-reaction partly reversed in hours | Li & Geman, J. Energy Markets 2018, https://ideas.repec.org/a/rsk/journ2/5728636.html; Rousse & Sevi, Energy Journal 40(2) | round 4 (conflicts with R3H) |
| night-session first half-hour momentum in Chinese metal futures; "night effect" momentum and reversal | Bouri, Ma, Xu & Zhou (AUT working paper); Ma et al., Global Finance Journal 64 (2025), https://ideas.repec.org/a/eee/glofin/v64y2025ics1044028325000110.html | round 4 |
| time-series momentum (12-month sign) in commodities | Moskowitz, Ooi & Pedersen, JFE 2012 | round 4 (as a filter only) |
| gold rises in Asian hours, falls in US hours | CBS master's thesis; FXStreet commentary (Jul 2026), descriptive only | round 4 (futures check first) |
| dropped: pre-FOMC drift in gold (Lucca & Moench find it in stocks only), drift after NFP / CPI in gold (retail write-ups), API Tuesday (no study), storage direction (vendor test: none) | - | no |

## Round 4 scan (21 Oct 2026, before the round-4 pre-registration)

About 8 web searches; abstracts and summaries only.

| idea | source | used |
|---|---|---|
| short-lookback time-series momentum: 1-month lookback / 1-month hold best in Chinese commodity futures | J. Futures Markets 39(12) 2019, https://ideas.repec.org/a/wly/jfutmk/v39y2019i12p1515-1528.html | R4F (filter) |
| commodity factor momentum strongest at the 1-month horizon, US and UK 1985-2022 | J. Futures Markets 45(11) 2025, https://ideas.repec.org/a/wly/jfutmk/v45y2025i11p1934-1969.html | R4F (support) |
| after night trading began, the night session's first half-hour (not the day's) predicts later returns in gold, silver, copper, aluminium futures | Bouri, Ma, Xu & Zhou, AUT working paper, https://acfr.aut.ac.nz/__data/assets/pdf_file/0008/686816/4a-Z-Ivy-Zhou.pdf ; Ma et al., GFJ 64 (2025) | R4D |
| crude variance risk premium is negative and time-varying; it predicts futures returns | Chevallier & Sevi (FEEM WP); Da Fonseca & Xu (USO, AUT); Trolle & Schwartz 2010 | R4G (cheap-IV filter) |
| dealer-gamma regime pages (negative gamma favours breakouts) | vendor pages (optionsanalysissuite, SpotGamma), no track record | no (no MCX dealer data) |
| MCX NR7 / inside-bar option backtests | none found (only tip pages) | - |

## Round 5 scan (21 Oct 2026, before the round-5 pre-registration)

About 12 web searches; abstracts and summaries only (two full texts could not be fetched: 404 / DNS failure).

| idea | source | used |
|---|---|---|
| natural-gas futures earn > 50% of the annual return on EIA storage days, half before the release; "a simple strategy earns after costs" | Prokopczuk, Wese Simen & Wichmann, Energy Journal 42(2) 2021, https://journals.sagepub.com/doi/10.5547/01956574.42.2.mpro ; https://livrepository.liverpool.ac.uk/3083246/ | R5A |
| crude IV drifts up into OPEC meetings and falls after; OptionMetrics (57 meetings since 2005): pre-meeting returns positive, IV falls slightly during meetings | Horan, Peterson & Mahar, Energy Journal 25(3) 2004, https://ideas.repec.org/a/aen/journl/2004v25-03-a06.html ; https://optionmetrics.com/blog/crude-oil-and-trading-opec/ | R5E (the run-up idea); OPEC itself not tested (monthly Sunday meetings, ~11 in design) |
| equity straddles bought before earnings and sold before them earn (pre-event IV run-up) | Gao, Xing & Zhang, JFQA 2018 | R5E |
| OPEC+ eight-country meeting dates (monthly, Sundays: 4 Jan, 1 Feb, 1 Mar, 5 Apr 2026 ...) | https://opec.org/pr-detail/1619593-1-march-2026.html ; https://www.opec.org/pr-detail/589-1-february-2026.html | sample-size check only |
| crude and gas IV rise toward expiry and fall Friday -> Monday (1999-2006) | Univ. of Oklahoma dissertation (Ederington, adviser), https://shareok.org/handle/11244/319355 | no (MCX monthly options; weekly expiries don't exist on MCX) |
| gold: overnight returns positive, day (US hours) negative | Blose, Gondhalekar & Kort 2018, https://ideas.repec.org/a/spr/jecfin/v42y2018i3d10.1007_s12197-017-9403-0.html | no (a few bp a day, under GOLDM spreads; R97-R99 territory) |
| gold / silver ratio catch-up | practitioner pages only (WisdomTree, TradingView) | R5F used R94's "ratio trends" finding instead |
| MCX vs NYMEX minute lead-lag | nothing quantitative found (broker pages only) | not testable (no XAUUSD / COMEX minutes on Drive) |
| API Tuesday -> EIA Wednesday | news anecdotes only | no |
