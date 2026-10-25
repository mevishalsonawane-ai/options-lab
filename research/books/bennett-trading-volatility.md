# Trading Volatility: Trading Volatility, Correlation, Term Structure and Skew - Colin Bennett (2014)

Source: bennett-trading-volatility.txt, 317 pages, read in full on 25 Oct 2026 (text layer for the whole book, pages 1-317
including the appendix and index; OCR file covers only the cover and title pages. Pages 6, 10, 12, 164 and 297 are blank
or image-only in both; the charts are images, so only their axis numbers and captions came through - see "Could not read").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
An equity-derivatives desk handbook from a bank strategist (Santander, ex-Merrill / Dresdner / Barclays), mostly European and US indices and stocks.
Chapter 1 covers options for directional investors: strike and expiry choice, overwriting, put underwriting, protection.
Chapters 2-4 cover volatility as an asset: delta hedging, variance / volatility / gamma swaps, why implied sits above realised, VIX and its futures.
Chapters 5-6 cover OTC light exotics, relative value, dispersion / correlation and trading earnings jumps.
Chapter 7 and the appendix cover skew and term structure trading, volatility regimes, historical-volatility estimators and the Greeks.

## Methods, rules and setups

Every method below is the book's, in our words. Our data, for the mapping: NIFTY weekly (Tuesday expiry) and BANKNIFTY
monthly options, 1-minute rolling chain of the nearest expiry within ATM +-10 strikes with IV and OI, India VIX daily (a
30-day variance-style number on NIFTY), index spot / futures bars, MCX futures and options, Delta Exchange crypto
options. Indian index options are European and cash-settled, so the book's early-exercise and dividend material does not
apply to them. We have no India VIX futures or options data, no stock-option chain for the index members, no variance
swaps and no OTC exotics. Costs: about Rs 70 (NIFTY) / Rs 76 (BANKNIFTY) per option round trip; a four-leg structure is
eight orders (about Rs 244 in charges, DEEP_OPTIONS_SELLING.md section 0).

### 1. Pick the option from two views: market direction and volatility (Ch. 1.2, Figure 1)
- Entry: if implied vol looks dear, sell (short put if bullish, short call if bearish); if it looks cheap, buy (long call if
  bullish, long put if bearish). A long-vol position should expire just after a key date; a short-vol position just
  before it.
- Stop: not stated. Target / exit: expiry; the book likes that expiry forces a decision.
- Filters: "cheap" or "dear" is left to the reader (implied vs expected realised).
- Market and timeframe written for: equity and index investors, weeks to a year.
- Signal source: underlying price (the view) plus volatility / options structure (the vol view); maps to NSE index options
  yes (vol view = VIX or ATM IV vs a realised-vol forecast); MCX yes; crypto yes (with a stop and a lock).
- Mechanical? partly (the direction view is discretionary; the vol view can be made mechanical).
- Our research: the vol half was tested by h29 (HUNT_H29.md): buying only when IV was cheap against a HAR realised-vol
  forecast, 16 variants, all lost after costs (best -Rs 30 a day net); even the cheapest fifth of days moved only
  0.62-0.73x what IV priced. Direction from any indicator is a coin flip (R16, F1, B2, R102).

### 2. Strike choice: ITM for "normal" moves, OTM only for "abnormal" ones (Ch. 1.2)
- Entry: for a confident directional view of normal size, buy ITM (highest return on premium); OTM only when expecting an
  exceptional move. For a pure directional bet a forward / future beats any option; the option buys convexity.
- Liquidity: low-strike puts are more liquid than high-strike calls (higher implied, hedging demand).
- Delta is not the probability of finishing ITM (call delta N(d1) > N(d2)); the gap is small except for long-dated,
  high-vol options.
- Stop / target: not stated. Market: 12-month options on indices.
- Signal source: underlying price; maps to NSE yes (strike rule), MCX yes, crypto yes.
- Mechanical? yes as a strike rule.
- Our research: agrees in spirit with the arms that passed gates, which all buy 1-ITM (DEEP_NIFTY_BANKNIFTY.md section 5);
  h29 found the lowest-IV strike on the smile gave +0.21 points of return on premium over 1-ITM, not significant (q 0.24),
  and "buy OTM when IV is cheap" was worse than 1-ITM.

### 3. Stock replacement and put underwriting (Ch. 1.2, 1.4)
- Entry: replace a long with calls of equal delta when implied is cheap (convexity), or with short puts when implied is
  dear. Put underwriting: sell a naked put at the highest level you would happily buy; if you only want the premium, use
  an OTM strike at a support level likely to hold. It earns the vol premium and, with OTM strikes, overpriced skew.
- Stop: none stated (the risk is owning the stock). Exit: expiry.
- Market: single stocks and indices, months.
- Signal source: volatility / options structure plus a support level (underlying price); maps to NSE as a short put or,
  under our rules, a bull put spread on NIFTY weekly / BANKNIFTY monthly; MCX options yes; crypto only with stop and lock.
- Mechanical? partly (the support level is discretionary; a delta rule such as 25-delta is mechanical).
- Our research: no pure put-selling test. R103 tested defined-risk credit structures (iron fly / condor, both sides): 0 of
  24 confirmed (HUNT_R103_OPTIONS_SELLING.md). Support / resistance levels were no better than random lines (R20b). On an
  index there is no delivery, so it is a naked put with cash parked (DEEP_OPTIONS_SELLING.md section 2).

### 4. Call overwriting (buy-write) (Ch. 1.4)
- Entry: hold the index, sell a call against it every month. Best on the book's SX5E back-test: one-month expiry and a
  slightly OTM strike, 103-104% of spot (three-month: 107-108%, about half the gain); many investors use a fixed 25-delta
  call or scale the strike with current vol.
- Stop: none. Exit: expiry, roll monthly.
- Filters: none recommended. "Enhanced" overwriting (only when implied is high, or varying strike) is hard on an index
  because the opportunity cost of not overwriting is too high; simple automated timing is likely arbitraged away.
- Claims: index implied is about 1-2 points above realised on average, more than single stocks, so overwrite the index
  (narrower spreads too). It lags in low-vol bull markets (two of seven BXM periods), it lowers the equity risk premium
  earned, and its lower standard deviation is an illusion: by Sortino the downside equals a plain long.
- Market: S&P500 (BXM) and SX5E, monthly.
- Signal source: volatility / options structure; maps to NSE only as a research benchmark (long NIFTY future + short weekly
  OTM call; the app holds no index longs); MCX possible on futures; crypto not as written (no holding allowed).
- Mechanical? yes.
- Our research: not tested by us.

### 5. 1x2 call spread "booster" after a sharp fall (Ch. 1.4)
- Entry: after a sharp drop, when a limited bounce to earlier levels is expected, overlay a near-zero-cost 1x2 call spread
  (long one ATM call, short two OTM calls) at a short strike at or above the expected bounce level; maturity under three
  months.
- Stop: none; above the upper strike the gain is capped and, without the stock, the extra short call is naked.
- Market: equity holders, under three months.
- Signal source: underlying price (the drop) plus options structure; maps to NSE only as a defined-risk version (add a far
  call: a broken-wing butterfly) because naked shorts are not allowed; MCX same; crypto only with stop and lock.
- Mechanical? partly (the "sharp drop" and bounce target need definitions).
- Our research: not tested as such. Direction after falls is a coin flip; daily momentum reversed in the R102 holdout.

### 6. Protection structures and how to pay for them (Ch. 1.5)
- Structures: put (full protection, dear; usually strikes near 90%), put spread (partial; the sold put should be worth
  about a third of the bought put), collar (zero cost, caps upside, short skew), put spread collar (best when vol is
  high, two options sold per one bought), 1x2 put spread ("pseudo-protection", loses in a crash).
- Choice rule: low vol -> buy puts; low to moderate -> put spread; average vol and bearish -> collar; high vol -> put spread
  collar or 1x2. Maturity follows the feared decline: short-dated for a crash, long for a bear market (DAX since 1960:
  crash 31% over about 1 month, correction 14% over 3 months, bear market 44% over 2.5 years). Rolling 1-month ATM puts
  cost 27.7% a year against 8.0% for one 1-year put (SX5E).
- Calendar collar: fund a 3-month put by selling 1-month calls; the book says it beats in a downturn with lower vol.
- Signal source: volatility / options structure; maps to NSE only as a hedge for a long book (none in the app); MCX same.
- Mechanical? yes as a menu; the vol-level choice needs thresholds.
- Our research: not tested by us (we hold no positions to protect beyond single option trades).

### 7. Other two- and three-leg structures (Ch. 1.6)
- Call spread (about a third cheaper than a call; short strike at a target), risk reversal (short put, long call: sells
  skew), call spread vs put (best when vol is high), ladders (like 1x2 with split short strikes), straddles / strangles
  (pure vol, no direction), butterflies and short calendars (similar profiles; a long calendar is short gamma).
- Signal source: options structure on an underlying view; maps to NSE yes within ATM +-10 (defined-risk versions only);
  MCX yes; crypto only with stop and lock.
- Mechanical? partly. Our research: straddle buying 0 of 18 (R89, HUNT_R89_SIZE_STRADDLE.md, 4,302 design straddles);
  iron fly / condor selling 0 of 24 (R103); butterflies and calendars not tested by us.

### 8. Delta hedging and gamma scalping (Ch. 2.1, 3.5)
- Entry: buy an option (ideally a zero-delta straddle, whose strike is slightly above spot) and hedge its delta with the
  future. Re-hedging buys dips and sells rallies, which locks in the gamma gain. A long-gamma hedger can rest bids and
  offers away from spot and need not cross the spread; a short-gamma hedger must cross it.
- Hedge timing: less often when the market trends, more often when it chops, and just before a key announcement so the jump
  is captured. Hedging every 5% vs every 10% move changes the result path by path.
- Exit: expiry or when the vol view is done.
- Claims: hedging four times as often halves the noise (sd of P&L about sigma x vega x sqrt(pi / 4N)); with discrete
  hedging a cheap option can lose and a dear one can win. Compute deltas from the volatility you expect, not the implied:
  a SX5E Dec08 straddle bought at 22% implied in April 2008 realised 42% yet lost money when hedged with implied-vol
  deltas (the two deltas differed by up to 24 points, 60% vs 84%).
- Signal source: volatility / options structure; maps to NSE partly: one NIFTY future lot is 65, the same as the option
  lot, so a one-lot straddle can only be hedged in whole-lot steps (DEEP_OPTIONS_SELLING.md section 3 says the same for
  sellers); MCX options are on futures, same granularity issue; crypto futures are finer-grained but need a stop and lock.
- Mechanical? yes.
- Our research: not tested by us with a futures hedge. R1 listed delta-neutral / gamma scalping as "not testable for a
  buy-only index trader" (HUNT_R1_INTERNET.md). Unhedged straddles lose (R89 0 of 18; h29; FINDINGS LONG_VOL).

### 9. Rules of thumb for pricing and P&L (Ch. 2.1-2.2)
- Annualised vol is about 16 x the typical daily % move (sqrt 252; weekly x 7, monthly x 3.5). ATM premium in % of spot
  is about 0.4 x vol x sqrt(time). OTM prices from ATM using a 50% delta.
- Delta-hedged P&L grows with the square of the move (a 2% day earns four times a 1% day), so variance, not volatility,
  is the right measure; but daily carry depends on realised minus implied (buying 20% that realises 21% earns the same
  carry as 40% that realises 41%).
- Signal source: none (pricing arithmetic); maps everywhere. Mechanical? yes. Our research: used implicitly (R16's
  "VIX-implied" range, R36's move ratio = realised / implied variance).

### 10. Pinning near expiry (Ch. 2.1)
- Claim: big long-gamma hedging can pin a stock to a strike into expiry (Swisscom convertible 2004-05, AXA Jun05); the
  stock snaps back after expiry. Needs hedging size large against traded volume; a major index is hard to pin; pins
  happen in calm markets.
- Signal source: option chain as context (OI at strikes); maps to NSE index options weakly; MCX not stated.
- Mechanical? partly. Our research: agrees that indices barely pin. EXPIRY_PLAYBOOK.md: the close landed within half a
  strike step of the 09:20 largest-OI strike on 11% of NIFTY expiries (53) and 9% of BANKNIFTY ones (11); max pain is
  not a magnet (R16; R102: close a median 0.60% / 0.68% from max pain). R36 found that, holding IV fixed, more GEX goes
  with a slightly smaller afternoon move (-0.11), "big gamma pins a little".

### 11. Variance, volatility and gamma swaps (Ch. 2.3, A.4, A.5)
- Mechanics: a variance swap pays realised variance minus strike, is hedged by a static strip of options weighted 1/K^2
  (long skew, about 60% puts), trades near the 30-delta put, and is quoted in vol points; variance notional = vega / (2 x
  strike). Vol swap <= gamma swap <= variance swap; the variance swap is usually one or two vol points above the vol swap
  (vol swaps are short vol of vol). Max loss of a short var swap is unlimited in practice; capped versions (2.5x strike)
  embed an option on variance. Variance vega decays linearly with time.
- Market impact claim: dealers delta-hedge variance at the close; when clients buy variance the dealers' hedge damps moves,
  when clients sell variance it exaggerates them. Short-dated implieds are "pinned" to realised because funds arbitrage
  the gap. After 2008 removal of the implied-vol cap lifted var swaps from about 2 to 7 points above ATM forward vol (later
  3-4).
- Signal source: volatility / options structure; maps to NSE no (no variance swaps); a log-contract replica would need the
  whole strike range, we have ATM +-10. MCX no; crypto no.
- Mechanical? yes in principle. Our research: not tested by us.

### 12. Options on variance and vol of vol (Ch. 2.4)
- Options on variance have positive skew and an inverted term structure (vol mean reverts in about eight months); selling
  a straddle on variance with the lower breakeven at the perceived vol floor is a popular trade.
- Maps to NSE no. Not tested by us.

### 13. Implied above realised: the volatility risk premium (Ch. 3.1, 1.4)
- Claim: implied vol (and more so variance) is on average above the vol later realised, by about 1-2 points for the
  index, because of demand for protection, the lottery-ticket premium on OTM options, the gap-risk premium of near-dated
  gamma and structured products lifting index implieds. Part of the premium is fair pay for equity risk: short vol is
  positively correlated with equities, so a short-vol book is implicitly long the market.
- Trade: sell near-dated vol (overwriting, short 1-3 month variance). Far-dated vol is the most overpriced, but twelve
  1-month sales a year earn more than one 1-year sale (with more risk if several go wrong).
- Warnings: the trade is crowded and published; it should be less profitable than before; do not trust a back-test as a
  forecast.
- Signal source: volatility / options structure; maps to NSE yes (NIFTY weekly ATM premium vs forecast realised; India
  VIX vs forward realised), MCX yes, crypto yes (daily options).
- Mechanical? yes once the "dear" threshold is set.
- Our research: the premium exists on NIFTY. R102 (DEEP_NIFTY_BANKNIFTY.md section 2.2): India VIX over-states NIFTY's
  next 21 days on 81% of days (median ratio 1.28, n 4,507 days), but under-states BANKNIFTY's on 60% (ratio 0.93) because
  BANKNIFTY realises about 1.3x NIFTY: VIX is a NIFTY number, so the "free" premium is not the same on both. h29: the move to
  15:10 was 0.62-0.73x what ATM IV priced (part of that is overnight risk inside the IV). MCX_OPTIONS.md: realised moves
  were 0.73-0.93 of what MCX options priced over 60 minutes. RC2: crypto daily options are dear in 08:30-17:30 IST (moves
  0.67-0.90x implied) and cheap at the US open (1.12-1.46x). But harvesting it failed on our data: R103 0 of 24
  defined-risk tests confirmed; the naked 09:20 short straddle held to 15:10 made +Rs 64k on NIFTY (1,272 days, 62% win)
  and +Rs 1.51 lakh on BANKNIFTY (1,234 days, 65%) with worst days of -Rs 28,876 and -Rs 29,257 on one lot, beyond Boss's
  stops. Bennett's own caveat (part of the premium is crash insurance, i.e. equity risk) is exactly R103's tail table.

### 14. Long volatility is a poor hedge (Ch. 3.2)
- Claim: long vol has negative average returns; variance swaps correlate about -70% with equities, but their overpricing
  makes them worse hedges than simply cutting the position. Rolling one-month vStoxx futures returned -87% from launch to
  2014 while the SX5E returned -4%. Best hedge horizon is 9-12 months (realised vol mean reverts in about 8 months).
- Signal source: volatility / options structure; maps to NSE as "do not buy vol as a hedge"; MCX and crypto same.
- Mechanical? yes. Our research: long-vol trades lose: R89 (0 of 18 straddle versions, 4,302 design straddles), FINDINGS
  LONG_VOL and R1 (realised 0.69x implied for event straddles), R36 (buying Rs 15-30 wings on 264 expiries: -Rs 1,03,781).

### 15. Structured-product "vicious circle" and vanna / volga (Ch. 3.3-3.4)
- Claim: banks short skew and short vol of vol from structured products must buy vol when markets fall, so implieds
  overshoot in crashes and undershoot in recoveries; variable-annuity hedging lifts long-dated vol and skew (and Dodd-Frank
  pushed far-dated skew up in mid-2010). Vanna (dVega/dSpot) is the size of a skew position; volga (dVega/dVol) of a vol-of-
  vol position, highest for 10-15 delta options.
- Signal source: volatility / options structure (flow); maps to NSE as context only (Indian flow is retail buyers and prop
  writers, R102: clients hold 58% of index-option longs, prop desks 23-24% of shorts). Mechanical? no. Not tested by us.

### 16. Forward volatility and forward-starting products (Ch. 4.1)
- Additive variance rule: forward vol from T1 to T2 = sqrt((s2^2 T2 - s1^2 T1) / (T2 - T1)). Forward-start options and
  forward variance (long var to T2, short T1/T2 of var to T1) give vega without gamma or theta, for views like "quiet now,
  busy later". The constant-smile rule is the best of three pricing conventions. Upward term structure makes long forward
  vol bleed as it is re-marked lower.
- Signal source: volatility / options structure; maps to NSE only as a calculation (forward vol between two listed
  expiries) - no forward-start options. Mechanical? yes. Our research: not tested by us.

### 17. Volatility indices and their futures, options and ETNs (Ch. 4.2-4.5)
- VIX-style indices are variance-based; they sit 0.2-0.7 points below a variance swap of the same maturity (wings chopped,
  discrete strikes) and up to about 0.8 below from linear interpolation; an index that rolls expiries without
  interpolation jumps by about 2 points at the roll. VIX settlement on the open can be pushed about 1 point by bids on
  deep puts.
- VIX futures: delta to spot VIX about 90% for the front month and 55% for six months (R^2 0.97 vs 0.70) because vol mean
  reverts; near zero delta for six-month futures when VIX is above 40. They are overpriced (vol of vol underpriced): short
  VIX futures / long forward variance. Constant-maturity ETNs roll into contango and lose; the book suggests buying the
  front or fourth VIX future (most ETN selling pressure), shorting VIX ETNs, or short 1m VIX / long 1m vStoxx.
- Options on vol futures: positive skew, steeply inverted term structure, implied below the realised of the vol index.
- Signal source: volatility / options structure; maps to NSE no (India VIX itself is not tradable and we have no India VIX
  derivatives data); India VIX is usable as a filter / sizing input. MCX no. Crypto no (no vol index in our data).
- Mechanical? yes where listed. Our research: India VIX is the range dial, not a direction dial (R16, R102: median NIFTY
  range 0.75% with VIX 9-15 vs 1.72% above 20, n 1,509 days each); VIX divergence as a direction rule was rejected (R74:
  design -Rs 25,287, final -Rs 23,860).

### 18. Light exotics: barriers, worst-of / best-of, outperformance, look-back, contingent premium, composite / quanto (Ch. 5)
- Rules of thumb: a knock-out put costs about 15-25% of the matching put spread; knock-in + knock-out = vanilla; worst-of
  calls are long correlation; outperformance options are short correlation and priced by Margrabe's formula; contingent
  premium = vanilla premium / probability of touching the strike.
- Maps to NSE no (OTC only). Mechanical? not applicable. Not tested by us.

### 19. Relative value (pairs) with cash, options or outperformance options (Ch. 6.1)
- Entry: long the cheap / short the rich of two correlated assets (dual listing, share class, cross-holding, takeover, same
  sector); weight legs by beta, estimated as the stock's vol / the market's vol; vol-weighting ATM options is roughly zero
  cost. Rotate a winning cash leg into a call to protect profit.
- Stop: not stated. Market: stocks, weeks to months.
- Signal source: underlying price (the spread); maps to NSE partly (NIFTY vs BANKNIFTY, beta 1.157), MCX (GOLD/SILVER),
  crypto (BTC/ETH, stop and lock needed).
- Mechanical? partly. Our research: R94 GOLD/SILVER ratio reversion lost in both windows (W60 design -Rs 6.3 lakh, n 68);
  relative value across indices and heavyweight-option leads: no tradable lead (h39, h6; HUNT_R1_INTERNET.md row 40).

### 20. Relative value volatility trading (Ch. 6.2)
- Entry: trade two implieds that normally keep a stable spread: when A's implied sits at the top of its range and B's at the
  bottom, short A vol, long B vol - it is the spread, not the level, that matters. Weight two indices (same type) by
  theta (proportional vol moves); weight an index against a stock by vega. Short trades profit from mean reversion;
  longer ones from carry (realised minus implied).
- Claim: when markets fall, the implied of a diverse, high-skew index (S&P500, FTSE) comes under pressure relative to a
  concentrated one (DAX), and the reverse when markets rise.
- Signal source: volatility / options structure; maps to NSE partly: NIFTY weekly vs BANKNIFTY monthly ATM IV (different
  expiries, so compare at matched time to expiry or use forward vol); MCX GOLD vs SILVER implieds; crypto BTC vs ETH.
- Mechanical? yes. Our research: not tested by us.

### 21. Correlation and dispersion trading (Ch. 6.3)
- Index vol is capped by the weighted average member vol; implied correlation is about index variance / weighted average
  member variance (about 2 points too high). Structured products leave banks short correlation, so implied correlation
  is rich: a long dispersion trade sells index vol and buys member vol. Theta-weighting gives near-pure correlation
  exposure; vega-weighting (the book's favourite) adds a long-vol hedge; gamma-weighting is not recommended. Dispersion is
  short vol of vol, so its implied correlation runs about 10 points above correlation swaps, which run about 5 points
  above realised. Prefer OTM strangles to straddles for a flatter vega.
- Signal source: volatility / options structure; maps to NSE in principle (BANKNIFTY is 14 members, the big five banks
  62.98%) but we have no member stock-option data; MCX no; crypto no.
- Mechanical? yes. Our research: not tested by us (data lacking).

### 22. Trading earnings and event jumps (Ch. 6.4, A.3)
- Entry: back out the event's implied jump from the expiries before and after it with additive variance (diffusive vol from
  the pre-event expiry, or from forward vol after it); compare with the expected move. To trade only the jump, buy the
  expiry after and sell the expiry before (or the reverse to sell a dear jump). For a stock, subtract the index term
  structure first. Post-event vol is typically about three quarters of pre-event vol.
- Stop / exit: after the event. Market: single stocks, nearest expiries.
- Signal source: volatility / options structure around a calendar event; maps to NSE yes for Budget, RBI and election
  days when a weekly expiry falls on each side (NIFTY); BANKNIFTY monthly rarely brackets an event; MCX for US data
  releases; crypto for US events.
- Mechanical? yes. Our research: event straddles lose (R1 rows 22-24: realised 0.69x implied); Budget day doubles the
  range (2.13% NIFTY, 2.62% BANKNIFTY, n 6) and its straddle is priced 3-5x (R98d, cited in DEEP_OPTIONS_SELLING.md); an
  implied-jump calendar was not tested by us.

### 23. Skew and term structure, and the square-root-of-time rule (Ch. 7.1-7.3, A.6)
- Claims: term structure is normally upward sloping and inverts when markets fall; near-dated implieds move more; realised
  vol takes about 8 months to mean revert after a spike. Implieds move roughly by 1/sqrt(T) (empirical power about 0.44):
  a 3-month implied moves about twice as much as a 1-year one. Normalise term structure by sqrt(T2 T1)/(sqrt T2 - sqrt T1)
  and skew by multiplying with sqrt(T) to compare maturities. Skew and term structure are correlated (both rise when
  near-dated ATM implied falls, because low-strike and long-dated implieds are sticky); diverse indices have more skew
  (correlation goes to 100% at low strikes).
- Calendar rule: if a vol rise came with a power above 0.5 (near-dated rose too much), go long the calendar (sell near, buy
  far); below 0.5, short it; mirror for falls. A 1x1 calendar keeps its value when vols move by the sqrt-time rule.
- Skew is not a risk indicator: it often rises when ATM implied falls.
- Signal source: volatility / options structure; maps to NSE partly (NIFTY weekly vs next weekly / monthly; our lake keeps
  only the nearest expiry, and h29 built monthly-minus-weekly IV only while both were listed); MCX nearest vs next month;
  crypto daily vs weekly.
- Mechanical? yes. Our research: h29: term structure (monthly minus weekly ATM IV) did not sort the realised move at all;
  MCX_OPTIONS.md: term structure (crude) predicts nothing. R102: nearest-expiry ATM IV sits 0.3 points above VIX on a normal
  NIFTY day and 7.7 points above on expiry day (16.2 for BANKNIFTY's monthly), and six days out the weekly is 1.4 points
  below VIX - a front-end event spike, not the book's smooth upward slope. Calendar trades not tested by us.

### 24. Measuring skew (Ch. 7.4)
- Use a fixed-strike difference (90%-100% or 90%-110%) and do not divide by ATM vol (the strike width and the vol scale
  cancel); delta skew ((25d put - 25d call) / 50d) is "purest" but has R^2 93% with strike skew. The CBOE SKEW index is the
  third moment. Vega, vanna and volga measure exposure to the 2nd, 3rd and 4th moments; kurtosis (smile) decays with time,
  skew with sqrt(time).
- Signal source: option chain; maps to NSE yes within ATM +-10 (NIFTY 90% strikes are outside +-10 on a weekly, so use
  ATM-5 vs ATM, as R102 does). Our research: R102 skew fact: ATM-5 put IV above ATM+5 call IV by 2.56 points on NIFTY (91% of
  1,222 days) and 2.11 on BANKNIFTY (90%); IV1 and h29: skew shifts carry no direction (h29 IC about 0).

### 25. Skew trading and the four volatility regimes (Ch. 7.5, A.1, A.9)
- Regimes: sticky delta (ATM vol constant; long-run or calm trending markets), sticky strike (fixed-strike vols constant),
  sticky local vol (ATM moves twice the skew; prices skew fairly), jumpy vol (panic, days to weeks). Long skew (long OTM
  put, short OTM call, delta hedged) pays "skew theta" and only profits in the jumpy regime; it loses in sticky delta and
  sticky strike. Markets usually sit between sticky strike and sticky local vol, so skew is usually overpriced and selling
  it pays outside panics. Skew profit is proportional to (realised skew - 1), realised skew = surface move / skew. To pick
  strikes compare vanna weighted by sqrt(time) with skew theta. Local vol skew is twice Black-Scholes skew.
- Example rule: if 1-year 90-100 skew is 25bp per 1% and spot falls 1%, surfaces must rise more than 25bp for long skew to
  win.
- Signal source: volatility / options structure; maps to NSE yes in principle (sell ATM-5 put / buy ATM+5 call, hedge with
  the future) but the one-lot hedge granularity problem of method 8 applies; MCX yes; crypto yes with stop and lock.
- Mechanical? yes. Our research: not tested by us (R102 measured the skew level only).

### 26. Measuring historical volatility (A.2)
- Use zero drift; compare like with like (1-month implied vs 21-day realised) but do not look back more than about 8
  months; exclude one-off jumps; at least 5 samples, ideally 20 or more. Close-to-close is best for large samples and for
  anything settled on closes; Yang-Zhang (up to 14x as efficient) is best for small samples; Parkinson underestimates
  because it ignores overnight jumps. About a sixth of equity vol happens overnight. Intraday vol is U-shaped (open and
  close), so sub-daily estimates are noisy. Weekly vol above daily vol means trending (positive autocorrelation); long
  daily variance / short weekly variance pays occasionally in panics.
- Signal source: underlying price (an estimator); maps everywhere. Our research: agrees: R102 found Parkinson well below
  close-to-close every year because the overnight gap is a big part of daily variance; the day is front-loaded (61% / 65%
  of the range by 10:30).

### 27. Pricing near-dated options after a crash (A.6)
- Claim: very short-dated options should be priced by premium, not IV (a small price change is a big IV change). After a
  sudden fall a bounce is more likely, so a one-week ATM call should not be offered for less than the size of the fall
  (a 5% drop -> at least about 5%).
- Signal source: underlying price (the fall) for an option-pricing floor; maps to NSE as a seller's filter. Mechanical?
  yes. Our research: not tested by us; R102 notes IV on expiry minutes is unreliable (ATM IV prints 6.3 / 14.4 at 15:15).

### 28. Capital structure arbitrage (A.12)
- Merton model, jump diffusion, put vs CDS, no-default implied vol: long credit / short equity on BBB-BB names; puts floored
  by CDS and capped by ratio put spreads. Maps to NSE no (single names and CDS). Not tested by us.

### 29. Reference material (A.7-A.11)
- Black-Scholes formulae, the Greeks (delta, gamma, theta pays for gamma, vega, rho, volga, vanna), shadow Greeks
  (volatility slide theta, anchor delta), share borrowing and short rebate, the Sortino ratio for skewed returns. Not
  trading rules; the Sortino point is useful for judging option-selling equity curves.

## Performance claims
- Implied vol is on average 1-2 points above realised, more for indices: no data table given, a stated average.
- BXM call overwriting beats the S&P500 total-return index over 1988-2012 except in two low-vol bull periods: a chart only,
  no numbers or costs; the book warns that comparing BXM with the price index flatters it.
- SX5E one-month overwriting is best at a 103-104% strike, three-month at 107-108% with about half the gain: chart only;
  the author says the best strike depends on the back-test period.
- Rolling ATM puts on SX5E cost 27.7% a year (1-month), 19.6% (2-month), 16.0% (3-month), 11.3% (6-month) and 8.0% (1-year):
  a table, no period stated.
- 3-month put funded by 1-month calls outperforms SX5E with lower vol (2000-2014): chart only.
- Rolling 1-month vStoxx futures -87% since launch while SX5E -4%: one chart.
- The 1m vStoxx / 1m VIX futures ratio "offers an attractive performance": chart only.
- Var swaps 1-2 points above vol swaps; vol indices 0.2-0.7 below var swaps; VIX futures about 1 point (not 2) below forward
  variance and 5-6 points (not 1-2) above ATMf in 2012; correlation swaps about 5 points above realised: desk rules of
  thumb, no sample sizes.
- Dec08 SX5E straddle (22% implied, 42% realised) lost when hedged with implied deltas: one worked example.
- No win rates, no costs and no sample sizes anywhere; the author himself cautions against trusting back-tests of vol
  selling as a forecast.

## Risk, sizing and psychology
- Vol selling is "selling insurance": steady carry, rare large losses; short vol behaves like long equity. Near-dated
  selling earns most per year but risks several losses in one period.
- Sizing for vol trades is by vega (vega-, theta- or gamma-weighting of pair and dispersion legs); variance notional =
  vega / (2 x strike); the max loss of a short variance swap rises with the square of realised vol, which is why the single-
  stock variance market died in 2008.
- Protection must be paid for by premium, by giving up upside or by extra loss in a crash: pick one consciously.
- Use Sortino, not standard deviation, for skewed (option) returns; overwriting does not reduce downside risk.
- Expiry choice enforces discipline and stops "inertia". Be wary of systems that show sticky-strike surfaces (an illusion
  of stability) and of trader re-marks (anchor delta).
- No account-level stop, no position-size rule for a retail account.

## How it relates to our research
- Agree, measured: the volatility risk premium on NIFTY (R102: VIX above forward 21-day realised on 81% of days, median
  1.28, n 4,507; FINDINGS "Deep learn NIFTY and BANKNIFTY"); the event / long-vol buyer pays it (R1, R89 0 of 18, R36);
  the puts-dearer skew (R102: +2.56 / +2.11 IV points, 91% / 90% of days); indices barely pin and max pain is not a magnet
  (EXPIRY_PLAYBOOK.md, R16, R102); overnight jumps make close-to-close above high-low vol (R102); implied vol is the range
  dial (R16, R102); intraday vol is U-shaped / front-loaded (R102 section 2.3).
- A nuance the book explains for us: India VIX is a NIFTY 30-day number. On BANKNIFTY it under-states realised vol on 60% of
  days (ratio 0.93) - in Bennett's terms, compare each index's own implied with its own realised (and BANKNIFTY's monthly
  ATM IV, not VIX). The 81% figure is a VIX-vs-close-to-close comparison, so part of the gap is the overnight gap that an
  intraday trader never collects (h29 notes IV "carries overnight risk").
- Contradicts nothing we measured, but the step from "premium exists" to "selling pays" failed for us: R103 0 of 24 defined-
  risk tests confirmed (costs of eight orders and wing prices decide it; FINDINGS "Defined-risk option selling"); the naked
  09:20 straddle made money only with worst days near -Rs 29k a lot, beyond the -Rs 40k / -Rs 13k stops. Bennett's own
  answer to that tail is delta hedging and size by vega, which a one-lot account cannot do with a 65-unit future.
- GEX (R28, R36, R37): Bennett's mechanism (dealer hedging flows damp or amplify moves, variance-swap close hedging) is the
  theory behind GEX. Our data agree only on size: negative GEX at 10:00 preceded a bigger range (BANKNIFTY 0.87% vs 0.75%,
  R28), and R36 found GEX mostly re-measures low IV (correlation -0.69). No GEX trade passed (0 of 10 R28 filters, 0 of 4
  R36, 0 of 4 R37).
- IV1 / h29: Bennett treats skew and term structure as things to trade, not direction signals; our studies agree they carry
  no direction (IV1 option-only models lost on all 5 indices; h29 skew IC about 0).
- R89 straddle sizing: Bennett's carry rule (pay off only if realised beats implied) is why R89 lost: when a big candle is
  forecast, options already price it ("gross before costs is about zero").
- Expiry studies: Bennett's "theta pays for gamma" is our expiry decay clock (84% / 61% / 40% / 13% of the 09:20 straddle at
  10:30 / 13:00 / 14:30 / 15:15, n 259) and the 13.5% a minute last-hour gamma; his "price near-dated options by premium,
  not IV" matches R102's warning not to read IV in the last minutes.

## What is new to us
- Delta-hedged (gamma-scalped) long or short straddles with a futures hedge, hedged by bands rather than by time, and
  hedged with expected-vol deltas. Never tested; limited by the 65-unit future vs a 65-unit option lot for one lot.
- A volatility-risk-premium filter on the index's own implied vs its own forecast realised (Yang-Zhang or HAR, excluding the
  overnight part for intraday), used to choose WHEN to run a defined-risk sell, instead of selling every day (R103 sold
  every day).
- Call overwriting / buy-write on NIFTY as a benchmark (weekly ~25-delta call against a long future), and put-underwriting
  at 25 delta as a spread.
- Skew trading by regime: measure "realised skew" (ATM IV change per unit of skew as spot moves) to tell sticky strike from
  sticky local vol from jumpy vol, and sell skew (risk reversals) outside jumpy regimes.
- Calendar / term-structure trades by the sqrt-time rule and the implied event jump (Budget, RBI, election) from the
  expiries either side - needs the next-expiry chain, which the rolling lake mostly lacks.
- Relative-value vol between NIFTY and BANKNIFTY ATM IV (theta-weighted), and BANKNIFTY dispersion against its 14 members
  (needs stock-option data).
- Variance as the unit: judge option P&L by realised variance vs implied variance (R36's move ratio already does this).

## Could not read
- Pages 6, 10, 12, 164 and 297: blank or image-only pages with no text in either the text layer or the OCR file.
- Chart images throughout (about 160 figures): only axis labels, legends and captions survived; the curves themselves were
  not readable. Figure 21 (the option-structure matrix by market and vol view, page 33) came through as labels only, but
  section 1.6's text covers the same structures.
- Some formulae lost their layout (square roots, sums, superscripts); they were reconstructed from context (e.g. the
  forward-vol and hedging-noise formulas) and are standard.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-bennett-01`: tested (R104): VOL: 537 trades, win 42%, net -Rs 144,620 design, holdout -Rs 38,545 (fail)
- `book-bennett-02`: tested (R104): VOL: 168 trades, win 55%, net -Rs 160,226 design, holdout -Rs 63,992 (fail)
- `book-bennett-03`: tested (R104): VOL: 222 trades, win 55%, net -Rs 28,552 design, holdout -Rs 6,907 (fail)
- `book-bennett-04`: tested (R104): VOL: 557 trades, win 27%, net -Rs 279,847 design, holdout -Rs 35,441 (fail)
- `book-bennett-05`: tested (R104): VOL: 511 trades, win 49%, net -Rs 180,635 design, holdout -Rs 15,112 (fail)
- `book-bennett-06`: not testable (R104): next-expiry ATM chain is mostly missing from the rolling lake
- `book-bennett-07`: not testable (R104): needs two expiries' chains on event days
- `book-bennett-08`: not testable (R104): matched-maturity IVs need a second expiry; with the nearest only, a 10-session vol position has to roll weekly, which the book's trade is not
- `book-bennett-09`: tested (R104): VOL: 23 trades, win 43%, net -Rs 29,762 design, holdout Rs 18,668 (fail)
- `book-bennett-10`: descriptive only (R104)
