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
