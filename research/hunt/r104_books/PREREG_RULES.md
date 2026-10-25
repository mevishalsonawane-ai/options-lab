# R104 rule register (generated from `r104rules.py`; part of PREREG.md)

Mechanics: `research/books/TESTABLE_RULES.md`, rule by rule. Markets: see PREREG.md section 5.

| rule | title | status | family | markets (tests) | R104 note |
|---|---|---|---|---|---|
| `book-wyckoff-01` | Big lots at the offer vs the bid: futures signed volume as a direction lead | lack | F2 | - | index-futures tick prints exist only in the phone's recorder since 12 Oct 2026 (private repo); ~3 months needed |
| `book-wyckoff-02` | Heavy volume packed into a narrow band near a new high, then slipping: short | test | F2 | MCXI | MCX minis have real minute volume (Oct 2025+); the index version lacks volume (index-futures minutes only from 29 Jul 2026, 57 sessions) |
| `book-wyckoff-03` | Buy the light-volume pullback in an up move whose advances carried more volume | lack | F2 | - | index only; needs index-futures minute volume, which exists only from 29 Jul 2026 (57 sessions, too few for design + holdout) |
| `book-wyckoff-04` | Break of a quiet range on a burst of volume | test | F1 | MCXI | CRUDE and NATURALGAS minis with real volume; price-only twin reported beside it (not gated); index version lacks volume |
| `book-wyckoff-05` | Break only when the rest of the market breaks with it | covered | F1 | - | is the R72 Breadth Donchian arm: PASS (final +Rs 1,71,366 on 1,202 trades), on paper |
| `book-wyckoff-06` | Close an index trade when the other indices turn against it | test | F11 | ARM | exit overlay on LIQ and R72 |
| `book-wyckoff-07` | A rally that fails to recover half the drop: sell | test | F2 | IDX | - |
| `book-wyckoff-08` | Stop beyond the last point of resistance, raised under each new reaction low | test | F11 | ARM | swing-low trail overlay on R72, R51, LIQ |
| `book-wyckoff-09` | Fixed-distance trail until costs are covered, then hand over to the arm's exit | test | F11 | ARM | fixed-distance trail to cost cover, overlay on every arm; the crypto clause has no crypto arm to apply to |
| `book-wyckoff-10` | Abandon a trade that does not act right at once | test | F11 | ARM | no-progress exit at 20 minutes, overlay on every arm |
| `book-wyckoff-11` | Do not enter in the direction of a 4-day streak | test | F11 | ARM | 4-day streak skip filter (3-day variant chosen walk-forward) |
| `book-wyckoff-12` | Tighten the stop in the last 15 minutes before square-off | test | F11 | ARM | late-day tightening overlay |
| `book-wyckoff-13` | A sudden burst in a secondary index while the leaders stall: skip new longs | test | F11 | ARM | skip NIFTY/BANKNIFTY longs while a secondary index bursts alone |
| `book-woods-01` | Three-bar pullback in a trend, buy above the next bar in the trend's direction | test | F3 | IDX, MCXI | the book's wider pattern stop (README pick 3) |
| `book-woods-02` | Hikkake: failed breakout of an inside bar within three bars | test | F3 | IDX, MCXI | - |
| `book-woods-03` | Reversal bar in the trend's direction | test | F3 | IDX | - |
| `book-woods-04` | Key reversal day (gap below prior low, close above prior high) | test | F5 | DLY, MCXD | - |
| `book-woods-05` | Exhaustion bar: gap open, close near the far end, gap unfilled, high volume | test | F1 | DLY, MCXD | volume = the near future's daily volume (index futures daily volume 2012+) |
| `book-woods-06` | Pin bar rejected from a prior swing level | test | F3 | IDX | - |
| `book-woods-07` | Two-bar reversal: strong bar then opposite strong bar | test | F3 | IDX | - |
| `book-woods-08` | Three-bar reversal | covered | F3 | - | R71 P7 three-bar reversal (final -Rs 1,67,689 on 855, twin p 0.002: timing real, below option cost); the book's stop/exit are R71's |
| `book-woods-09` | Inside bar breakout, trend side only | test | F3 | IDX | differs from R71 P1 (two-sided) by taking the trend side only |
| `book-woods-10` | Fade the breakout of a doji-like or counter-trend outside bar | test | F3 | IDX | - |
| `book-woods-11` | Outside bar closing at an extreme: trade its breakout | test | F3 | IDX | - |
| `book-woods-12` | NR7 break in the trend's direction only | test | F3 | IDX, DLY, MCXI | differs from R71 P2 (two-sided) by taking the trend side only |
| `book-woods-13` | ID/NR4: inside bar that is also the narrowest of 4 | test | F3 | IDX, DLY | - |
| `book-woods-14` | Engulfing in the trend's direction (continuation) | test | F3 | IDX | - |
| `book-woods-15` | Three white soldiers after a substantial decline | test | F3 | IDX | adds R87-1's missing prior-decline filter and the book's stop |
| `book-woods-16` | Head and shoulders neckline break with measured target | test | F3 | IDX, DLY, MCXI | - |
| `book-woods-17` | Rectangle breakout in the prior trend's direction | test | F3 | IDX | - |
| `book-woods-18` | Ascending / symmetrical triangle breakout with the trend | test | F3 | IDX | - |
| `book-woods-19` | Broken level flips: buy the pullback to old resistance | test | F3 | IDX | - |
| `book-woods-20` | Linear-regression channel: buy pullbacks to the lower line in a rising channel | test | F3 | IDX | - |
| `book-carli-01` | CCI(20) regular divergence, three qualifying rules, trendline-break entry, Fibonacci targets (daily bars) | test | F4 | DLY, MCXD | README pick 4 |
| `book-carli-02` | Same Carli divergence setup on roughly 4-hour bars | test | F4 | IDXF, MCXI | half-session bars, held overnight: index as a futures proxy |
| `book-carli-03` | Do Carli's three divergence filters improve R95's confirmed divergences? | test | F4 | IDX | R95's confirmed divergences re-built on 15/60-minute bars with Carli's three filters |
| `book-carli-04` | Skip a trade in the direction of today's opening gap | test | F11 | ARM | gap-direction skip filter |
| `book-tom-01` | 4-bar fractal momentum entry (David Paul) | test | F10 | IDX, MCXI | - |
| `book-tom-02` | Stochastic hidden divergence in the trend, timed by a 4-bar fractal | test | F10 | IDX, MCXI | - |
| `book-tom-03` | Daily / weekly 3-bar swing (3-bar Donchian) as the trend bias | test | F11 | ARM | daily 3-bar swing bias filter |
| `book-tom-04` | Daily 3-bar breakout traded directly | test | F10 | IDX, MCXI | - |
| `book-tom-05` | Body-engulfing bar on 10-minute index bars in the trend direction | test | F3 | IDX | - |
| `book-tom-06` | Reverse after a failed engulfing trade (5-minute scalp) | test | F3 | IDX | - |
| `book-tom-07` | Fake-out / fake-down of the previous swing, fade back into the range | test | F3 | IDX, MCXI | 15-minute 2-bar fractal swing, not R91's 1-hour swing |
| `book-tom-08` | 1-minute trendline break after a fake-down (Stanley) | test | F3 | IDX | - |
| `book-tom-09` | Trend change on the break of the lower low (and continuation buy above the old high) | test | F3 | IDX | - |
| `book-tom-10` | Buy the 60-minute pullback in a daily uptrend (fade the short-term trend) | test | F10 | IDX, MCXI | - |
| `book-tom-11` | Buy-stop above a dragonfly doji (where the shorts' stops sit) | test | F3 | IDX | - |
| `book-tom-12` | Short on the break of a gravestone doji's low after a rise | test | F3 | IDX | - |
| `book-tom-13` | Spinning top at the end of a pullback, enter on its break with the main trend | test | F3 | IDX | - |
| `book-tom-14` | Hanging man / hammer confirmed by a close beyond support within 3 bars | test | F3 | IDX, DLY | - |
| `book-tom-15` | Red marubozu after a failed push up in a downtrend | test | F3 | IDX | - |
| `book-tom-16` | Equal-size pullback limit buy (harmonic retracement) | test | F3 | IDX, MCXI | - |
| `book-tom-17` | Trade only the first busy hours after the open | covered | F11 | - | R101 B4/C4 'entries by 12:00' on R72/R51 (raised win rate, cut final net -Rs 81,567) and R92 entry-hour study; the book's 12:15 differs by 15 minutes |
| `book-tom-18` | No new entries around high-impact scheduled news | test | F11 | ARM | events.csv (2024+, India and US); the MCX clause has no MCX arm |
| `book-tom-19` | Add one lot at +1R with the stop to entry (scaling in) | covered | F11 | - | R81 X7 pyramid on Liquidity (+Rs 58k design, -Rs 20k final) and H5: the same add-at-+1R mechanism |
| `book-tom-20` | Scale out in thirds (Mark Douglas) | covered | F11 | - | R81 X5 scale-out (half at 1R, -Rs 79k design / -Rs 35k final) and R100: the same partial-profit mechanism |
| `book-ozenbas-01` | Daily close crosses its 65-day moving average | test | F5 | DLY, MCXD | - |
| `book-ozenbas-02` | Enter with a capped limit instead of a market order; never chase | covered | F11 | - | h14 capped limit +0.5%: the only execution policy to pass (+Rs 288/day), already the Liquidity arm's entry |
| `book-ozenbas-03` | Rest the entry at the best bid (maker) instead of crossing the spread | lack | F11 | - | no historical bid/ask for index options (one snapshot, 6 Oct 2026) |
| `book-ozenbas-04` | Fade big moves on no-news days, follow them on scheduled-event days | test | F10 | IDX | event days = events.csv (2024+); earlier days count as no-news days (calendar gap, labelled) |
| `book-ozenbas-05` | Buy when the best bid steps to a new higher level (TraderEx Exercise 4) | test | F10 | IDX | 1-minute spot close as the best-bid proxy (the book's futures bid has no history) |
| `book-ozenbas-06` | Work a multi-lot entry in slices and score it against interval VWAP | lack | F11 | - | no option book-depth history |
| `book-ozenbas-07` | Post a bid and an offer around the mid and capture the spread (TraderEx Exercise 3) | lack | F11 | - | needs tick-by-tick book and queue position |
| `book-atb-01` | Hammer at a prior swing low, bought only after an up candle confirms | test | F3 | IDX, MCXI | - |
| `book-atb-02` | Engulfing candle at the end of a trend | test | F3 | IDX, MCXI | - |
| `book-atb-03` | Morning / evening star three-candle reversal | test | F3 | IDX | - |
| `book-atb-04` | Three white soldiers after a downtrend, confirmed by RSI | test | F3 | IDX, MCXI | - |
| `book-atb-05` | Doji, then trade the direction that is clear three candles later | test | F3 | IDX | - |
| `book-atb-06` | Falling trendline (neckline) break, retest and bullish candle (Strategy 1) | test | F3 | IDX, MCXI | - |
| `book-atb-07` | Symmetrical triangle after a rise: break, retest, bullish candle, buy the next candle (Strategy 2) | test | F3 | IDX, MCXI | - |
| `book-atb-08` | Rising wedge in a downtrend: lower-line break, previous-low break, retest, sell (Strategy 3) | test | F3 | IDX, MCXI | - |
| `book-atb-09` | Triangle in a downtrend: sell the lower-line break at once, stop at the last lower high (Strategy 4) | test | F3 | IDX, MCXI | - |
| `book-atb-10` | Flag (parallel channel against a sharp pole), trade the channel break (Strategy 5, sheet p58) | test | F3 | IDX, MCXI, CRY | - |
| `book-atb-11` | Sideways box after a move: break, retest, confirming candle (Strategy 6) | test | F3 | IDX, MCXI | - |
| `book-atb-12` | Bullish flag: wait for the last swing high to break, buy the first bullish candle after one pullback candle (Strategy 7) | test | F3 | IDX, MCXI | - |
| `book-atb-13` | Double top / bottom neckline break with stop inside and measured target | test | F3 | IDX, MCXI | - |
| `book-atb-14` | Head and shoulders / inverse: neckline break | test | F3 | IDX, MCXI | - |
| `book-atb-15` | Falling wedge buy / rising wedge sell on the boundary break (no retest) | test | F3 | IDX, MCXI, CRY | - |
| `book-atb-16` | Bullish / bearish pennant break | test | F3 | IDX, MCXI | - |
| `book-atb-17` | Bullish / bearish rectangle break with stop at the far side (chart-pattern version, no retest) | test | F3 | IDX, MCXI | - |
| `book-atb-18` | Triple top / bottom: break of the opposite side after three touches | test | F3 | IDX, MCXI | - |
| `book-atb-19` | Cup and handle (and inverse): break of the rim after the handle | test | F3 | IDX, MCXI, CRY | - |
| `book-bennett-01` | Sell defined-risk premium only when implied is dear against forecast realised (volatility risk premium filter) | test | F8 | VOL | version A (intraday iron fly); README pick 2 |
| `book-bennett-02` | NIFTY buy-write: long future, short weekly ~25-delta call | test | F8 | VOL | future = spot + carry proxy; call held to expiry (cash settled) |
| `book-bennett-03` | Put underwriting as a defined-risk bull put spread at 25 delta | test | F8 | VOL | - |
| `book-bennett-04` | Long ATM straddle, gamma-scalped with futures at fixed index bands, only when implied is cheap | test | F8 | VOL | hedge on the spot as a futures proxy |
| `book-bennett-05` | Sell skew (short ATM-5 put, long ATM+5 call, delta hedged) outside jumpy regimes | test | F8 | VOL | hedge on the spot as a futures proxy |
| `book-bennett-06` | Calendar trade when implied moves break the square-root-of-time rule | lack | F8 | - | next-expiry ATM chain is mostly missing from the rolling lake |
| `book-bennett-07` | Sell an overpriced event jump: short the expiry after the event, long the expiry before | lack | F8 | - | needs two expiries' chains on event days |
| `book-bennett-08` | Relative-value vol: NIFTY vs BANKNIFTY ATM IV spread mean reversion (theta-weighted) | lack | F8 | - | matched-maturity IVs need a second expiry; with the nearest only, a 10-session vol position has to roll weekly, which the book's trade is not |
| `book-bennett-09` | After a sharp fall, near-zero-cost 1x2 call spread made defined-risk (broken-wing butterfly) | test | F8 | VOL | rare signal (>= 4% fall in 3 sessions); min-trades gate likely binding |
| `book-bennett-10` | Is skew a risk indicator? Next-day range vs 09:20 skew, controlling for ATM IV | desc | F8 | - | regression only, no trade |
| `book-sadowski-01` | 20/60 SMA crossover with a 100 SMA trend read | test | F5 | DLY, MCXD | daily; differs from OBUY TI-03 (intraday EMA crosses) |
| `book-sadowski-02` | Daily Heikin-Ashi two-candle reversal with stochastic 14,7,3 | test | F5 | DLY, MCXD | - |
| `book-sadowski-03` | Break out of an overlapping correction in the trend direction | test | F10 | IDX | - |
| `book-sadowski-04` | Engulfing candle, enter the next open, stop at its extreme | test | F3 | IDX | - |
| `book-sadowski-05` | Long shadow / hammer candle, enter the next open | test | F3 | IDX | - |
| `book-sadowski-06` | Buy the retest of broken resistance, scaled in three parts | test | F3 | IDX | three limit parts modelled as three sub-lots |
| `book-sadowski-07` | Bollinger 20,2 squeeze, enter on a close across the middle SMA | test | F10 | IDX, MCXI | 60-minute / 15-minute, entry across the middle SMA (R71 P3 used the band close) |
| `book-sadowski-08` | Daily NR4 / NR7 breakout | test | F3 | IDX, MCXI | - |
| `book-sadowski-09` | RSI(2) above 90 buy / below 10 sell, with adds | test | F10 | IDX, DLY | momentum reading on NIFTY/BANKNIFTY not tested before |
| `book-sadowski-10` | Weekly + daily stochastic extreme at a swing level, intraday reversal entry | test | F5 | IDXF | multi-session hold: index as a futures proxy |
| `book-heitkoetter-01` | Trade with an intraday trendline; exit when it breaks | test | F10 | IDX | - |
| `book-heitkoetter-02` | Close crosses a 10-period SMA | covered | F10 | - | H25 price x SMA crosses (15,120 variants, Rs -145 a trade, 1 of 28 walk-forward families positive), exits of this kind included |
| `book-heitkoetter-03` | 14 / 20 moving-average crossover | covered | F10 | - | OBUY_GB TI-03 MA crosses (-Rs 3,69,856 walk-forward on 4,526) and R29 |
| `book-heitkoetter-04` | Turtle 20-bar breakout on 15-minute bars with the book's exits | test | F10 | IDX, MCXI | 15-minute bars with 0.27% / 0.41% / 45-minute exits differ from the 5-minute Donchian arm |
| `book-heitkoetter-05` | MACD 12/26/9 signal-line cross | covered | F10 | - | H25 MACD signal / zero cross: 27,000 variants, 0 of 50 families positive walk-forward |
| `book-heitkoetter-06` | Williams %R 14, sell above 80, buy below 20 | covered | F10 | - | H25 Williams %R as given: no better than random |
| `book-heitkoetter-07` | RSI 14 crosses back through 30 / 70 | covered | F10 | - | OBUY_GB MR-03 RSI 30/70 reversal: -Rs 82,004 walk-forward on 1,272 |
| `book-heitkoetter-08` | Bollinger 21 / 2 band fade | covered | F10 | - | OBUY_GB MR-02 Bollinger fade: -Rs 90,790 walk-forward on 2,136 |
| `book-heitkoetter-09` | Trend channel: enter at the trendline, exit at the channel line | test | F10 | IDX | - |
| `book-heitkoetter-10` | First 15-minute bar direction, previous-day high/low target, then reverse | covered | F10 | - | R1 N11/N12 first-candle rules and the ORB family (R3, R92 0 of 257), PDH/PDL R89 0 of 180 |
| `book-heitkoetter-11` | Three-bar time stop on the Donchian arm | test | F11 | ARM | 15-minute time stop on R51/R72 (R18 V3 was 45 minutes on other arms) |
| `book-heitkoetter-12` | Trail the stop at the previous 60-minute bar's high/low | test | F11 | ARM | - |
| `book-heitkoetter-13` | Take profits in thirds | covered | F11 | - | R81 X5 scale-out failed; thirds are the same partial-profit mechanism |
| `book-heitkoetter-14` | Stop trading for the week once a weekly profit goal per lot is reached | test | F9 | MM | README pick 5 |
| `book-heitkoetter-15` | Only one morning and one afternoon trade a day | test | F11 | ARM | - |
| `book-heitkoetter-16` | Skip holiday-adjacent and pre-major-release days | test | F11 | ARM | events.csv covers the arms' window (2024+); NSE holidays from the trading calendar; US payrolls not in the calendar (dropped, labelled) |
| `book-lswing-01` | FORCESWING pullback scan + Master Plan entry and exits on the index | test | F5 | DLY | Force Index on the near future's daily volume (2012+) |
| `book-lswing-02` | FORCESWING + Master Plan on MCX futures daily bars | test | F5 | MCXD | - |
| `book-lswing-03` | FORCESWING + Master Plan on the R58 stocks, bought monthly options | test | F5 | STK | stock-future proxy (the 10 R58 names); the book's monthly option not priced |
| `book-lswing-04` | Plain 3-day pullback in a 10/20 SMA uptrend (no Force, no ADX) with Master Plan orders | test | F5 | DLY | - |
| `book-lswing-05` | Master Plan exits (half at target, prior-low trail) on our existing daily swing signals | test | F5 | DLY | exit swap on SWING_DEEP's three hosts, judged against the hosts' own exits |
| `book-lswing-06` | Gap delay on a daily breakout trigger (wait 30 min with the gap, 5 min against, re-base on today's high) | test | F5 | IDXF | A vs B on the same setups, 1-minute execution (NIFTY 2017+, BANKNIFTY 2021+) |
| `book-smith-01` | SST six rules on NIFTY/BANKNIFTY daily bars, buy the monthly option | test | F5 | DLY | - |
| `book-smith-02` | SST six rules on 1-hour index bars (intraday-to-few-days mapping) | test | F5 | IDX | intraday variant (15:15 out) bought options |
| `book-smith-03` | Significant-day trailing stop as an exit module on existing multi-day holds | test | F5 | DLY | exit swap on SWING_DEEP's hosts |
| `book-smith-04` | SST six rules on MCX near-month futures, daily | test | F5 | MCXD | - |
| `book-smith-05` | SST six rules on F&O stocks chosen by a walk-forward 'step-ness' screen | lack | F5 | - | no results / AGM calendar for F&O stocks |
| `book-smith-06` | SST six rules on BTC/ETH daily, with the trail as the profit lock | test | F12 | CRY | the step trail is the profit lock; stop always on |
| `book-smith-07` | Skip swing entries whose trigger is hit by a gap | test | F5 | DLY | gap-skip filter on the daily stop-entry hosts |
| `book-atm-01` | TWAP-style sliced exit of multi-lot option positions vs one market order | lack | F11 | - | multi-lot exit fills need book depth |
| `book-atm-02` | NIFTY/BANKNIFTY futures-spot basis snap-back (literal statistical arbitrage) | lack | F12 | - | index-futures minutes only from 29 Jul 2026 (57 sessions) |
| `book-porwal-01` | Inside bar, entry on the close beyond the mother bar, 1x/1.5x/2x targets | test | F6 | IDX, MCXI | thirds as three sub-lots |
| `book-porwal-02` | Doji at an oversold / overbought extreme, traded on the break of its high / low | test | F6 | DLY | - |
| `book-porwal-03` | Engulfing reversal with trend, volume and stochastic filters | test | F6 | DLY, MCXD | - |
| `book-porwal-04` | Falling trendline break confirmed by a hold above the previous lower high | test | F6 | DLY | - |
| `book-porwal-05` | Darvas box break with volume confirmation, stop outside the box | test | F1 | MCXI | with real volume on MCX; the index price-only box is the R51 Donchian arm (covered) and its volume part lacks data |
| `book-porwal-06` | Turbocharged opening: first 15-minute range break with prior-day trend and EMA(14) bias | covered | F6 | - | R23 first-15-minute break (-Rs 71,624 on 1,171) and R92 by day type (0 of 257); the prior-day trend and EMA bias are day-type filters R92 covered |
| `book-porwal-07` | 52-week high / low as a direction filter for intraday index trades | test | F11 | ARM | 52-week filter on R51/R72 |
| `book-porwal-08` | ADX 20-50 + DI alignment + EMA(14) pullback with prior-day trend | test | F6 | IDX | - |
| `book-porwal-09` | Quasimodo: higher high after a downtrend, buy the higher low | test | F6 | IDX, MCXI | - |
| `book-porwal-10` | Bull trap: failed break of the day high reclaimed with a volume spike, go short | lack | F6 | - | index rule needs futures minute volume (57 sessions only); the price part (fade the day-high break) is covered by R86 (6 of 6 lost) and R91 |
| `book-porwal-11` | Bear trap: failed break of the day low / previous-day low, reclaimed on volume, buy above the reversal bar | lack | F6 | - | index rule needs futures minute volume (57 sessions only); the price part (day-low reclaim) is R87's held fact, below option cost |
| `book-porwal-12` | Stop trading for the day after two stop-losses | test | F11 | ARM | - |
| `book-aziz-01` | Opening range breakout on VWAP's side, range narrower than ATR, VWAP stop, next-level target | covered | F7 | - | ORB on VWAP's side with a narrow range: R10 V1 / T6 and R3 / R49 / R92 (0 of 16, 0 of 336, 0 of 257) |
| `book-aziz-02` | VWAP false breakout: sold off below VWAP, reclaimed, lost again in the Late-Morning -> short | test | F7 | MCXI, IDX | MCX with real VWAP; index with a session TWAP proxy (labelled) |
| `book-aziz-03` | VWAP reversal: opening move fails to make a new 5-minute extreme -> trade back to VWAP | test | F7 | IDX | TWAP proxy for VWAP |
| `book-aziz-04` | VWAP moving-average trend: below VWAP and riding the 5-minute 20 EMA after the Late-Morning | test | F7 | IDX, MCXI | index TWAP proxy; MCX real VWAP |
| `book-aziz-05` | ABCD: new high of day, higher pullback that holds, buy the turn near C | test | F7 | IDX | TWAP proxy; the 1-minute volume condition dropped on the index (no volume), labelled |
| `book-aziz-06` | Bull flag: break of the first or second consolidation with a joint 1- and 5-minute new high | test | F7 | MCXI, IDX | MCX with volume; index without the volume condition (labelled) |
| `book-aziz-07` | Fallen Angel analogue: gap-up open spike, flush below VWAP, base, volume breakout | test | F7 | IDX | TWAP proxy; volume test dropped (labelled) |
| `book-aziz-08` | Exit when the 5-minute chart stops making new extremes in the trade's direction | test | F11 | ARM | - |
| `book-aziz-09` | Sell half (or a quarter) at the first level, move the stop to break-even | covered | F11 | - | R81 X5 half at 1R + break-even (-Rs 79k design, -Rs 35k final), R100 G13 |
| `book-aziz-10` | Target the next pre-marked level instead of a fixed premium target | test | F11 | ARM | R18's post-hoc lead, now pre-registered |
| `book-aziz-11` | Skip entries whose reward to the next level is under 2x the risk to the technical stop | test | F11 | ARM | - |
| `book-aziz-12` | Stop for the day after giving back 30% of the first hour's profit | test | F9 | MM | account-level day rule on the combined arms |
| `book-aziz-13` | Per-trade risk 1% (max 2%) of equity; stop for the month at a 6% loss | test | F9 | MM | - |
| `book-douglas-01` | Judge each arm in 20-trade blocks; pause an arm whose block falls outside its own expected range | test | F9 | MM | - |
| `book-douglas-02` | Fixed rupee risk per trade so that 20 straight losses fit the account stop | test | F9 | MM | - |
| `book-douglas-03` | Three lots: one off at a small first profit, one at the next level with stop to entry, last before the next swing | covered | F11 | - | R81 X5 / R100 partial exits failed; the first third here is taken even earlier |
| `book-douglas-04` | Daily uptrend: buy a pullback to 30-minute support; daily downtrend: sell a rally to 30-minute resistance | test | F10 | IDX, MCXI | - |
| `book-douglas-05` | Skip any arm signal whose first target is less than 3x its stop distance | test | F11 | ARM | - |
| `book-fmwilmott-01` | Sell the NIFTY monthly ATM straddle when implied vol is above forecast realised vol, delta-hedge with futures on a band | test | F8 | VOL | NIFTY monthly series; hedge on the spot as a futures proxy |
| `book-fmjavaheri-01` | Skew trade: buy OTM call, sell OTM put, vega-neutral and delta-hedged, on the NIFTY monthly | lack | F8 | - | 10% OTM monthly strikes are outside the lake's ATM +-10; bhavcopy in the lake is a weekly sample, closes only |
| `book-fmjavaheri-02` | Skew trade only when the chain's skew exceeds the skew implied by the index's own history | lack | F8 | - | needs the next-monthly chain and far legs (see javaheri-01) |
| `book-fmjavaheri-03` | Kurtosis trade: sell ATM and far-OTM options, buy near-OTM options, delta-hedged | test | F8 | VOL | intraday; legs beyond ATM +-10 or stale repriced as R103 Amendment 2 |
| `book-fmaqf-01` | Skewness trade S1: sell OTM puts, buy OTM calls when implied skew is richer than historical | lack | F8 | - | implied density needs every strike on the entry day; the lake has ATM +-10 intraday and a weekly bhavcopy sample |
| `book-fmaqf-02` | Kurtosis trade K1: sell ATM and far-OTM, buy near-OTM when implied tails are fatter than historical | lack | F8 | - | as fmaqf-01 |
| `book-fmaqf-03` | Long-memory spread reversal: fade new M-day extremes of a two-asset log spread | test | F12 | DLY, MCXD | NIFTY vs BANKNIFTY and GOLD vs SILVER, spot / back-adjusted futures |
| `book-fmaqf-04` | Pause or size down an arm when a CUSUM chart of its trade results alarms | test | F9 | MM | - |
| `book-fmbayes-01` | Cross-sectional momentum: long the top half, short the bottom half on volatility-scaled 9-month return | test | F12 | MCXD | - |
| `book-fmfabozzi-01` | Filter rule on NIFTY/BANKNIFTY daily closes, buy the option | test | F5 | DLY | futures proxy instead of the bought option |
| `book-fmfabozzi-02` | 200-day moving-average band on index daily closes, buy the option | test | F5 | DLY | futures proxy instead of the bought option |
| `book-fmfabozzi-03` | NIFTY/BANKNIFTY log-spread z-score reversion (pairs trading) | test | F12 | IDX | two bought options, intraday |
| `book-fmlyuu-01` | Index arbitrage when the future strays from cost-of-carry fair value | lack | F12 | - | no minute ETF/basket prices, dividend schedule or futures quotes |
| `book-fmbjork-01` | Short ATM straddle, delta-hedged with the index future (sell implied, pay realised) | test | F8 | VOL | - |
| `book-fmbjork-02` | Long ATM straddle on BANKNIFTY, delta-hedged with the future (gamma scalping) when implied is cheap against forecast realised | test | F8 | VOL | - |
| `book-fmbjork-03` | Put-call parity conversion / reversal on NIFTY options against the future | lack | F12 | - | needs synchronous bid/ask on three legs |
| `book-fmbjork-04` | Constant-fraction (fractional Merton/Kelly) sizing for an arm with a record | test | F9 | MM | - |
| `book-fmbjork-05` | Mean-variance (Merton mutual fund) capital split across the arms with a record | test | F9 | MM | - |
| `book-fmcapinski-01` | Trade put-call parity violations against the index future (conversion / reversal) | lack | F12 | - | needs executable bid/ask; last prints in the lake are often stale (R103) |
| `book-fmcapinski-02` | Buy a butterfly priced below zero (convexity violation across strikes) | lack | F12 | - | needs executable bid/ask |
| `book-fmbinomial-01` | Conversion / reversal when put-call parity breaks by more than costs | lack | F12 | - | needs simultaneous bid/ask |
| `book-fmbinomial-02` | Buy a butterfly that the chain prices at or below zero (convexity break) | lack | F12 | - | needs simultaneous bid/ask |
| `book-fmbinomial-03` | Sell the ATM straddle when implied volatility exceeds forecast realised volatility, delta-hedged with futures | test | F8 | VOL | - |
| `book-fmoptim-01` | Break of a least-width sloped trend channel fitted to the last 20 closes | test | F5 | DLY, MCXD | - |
| `book-fmoptim-02` | Allocate lots across the live-record arms by trailing minimum-variance weights | test | F9 | MM | - |
| `book-fmhughston-01` | Conversion / reversal when call minus put strays from the futures-implied value | lack | F12 | - | needs executable bid/ask |
| `book-fmschmidt-01` | Buy on a turn up after a dip, sell on a turn down after a top | test | F10 | IDX | - |
| `book-fmwhd-01` | Buy the lowest-IV option and sell the highest-IV option of one expiry, delta-hedged, until the IVs converge | test | F8 | VOL | IV of each strike from its own minute price (BS on the spot plus carry) |
| `book-fmwhd-02` | Buy the ATM straddle before a scheduled policy event, sell after it | test | F8 | VOL | RBI / Budget events in events.csv (2024+); too few events to pass min trades |
| `book-fmmusiela-01` | Trade (or flag) chain prices that break model-free no-arbitrage bounds | lack | F12 | - | needs synchronous bid/ask for all legs |
| `book-fmmusiela-02` | Delta-hedge an ATM straddle with smile-adjusted delta vs plain Black-Scholes delta (hedge-error test) | test | F8 | VOL | hedge B vs hedge A on the same short straddles |
| `book-fmmusiela-03` | 09:20 short ATM straddle only when ATM IV is rich against recent realised volatility | test | F8 | VOL | - |
| `book-fmmusiela-04` | MCX GOLD vs COMEX gold x USDINR fair-value gap, mean reversion | lack | F12 | - | no intraday USDINR or dated import-duty history |
| `book-fmchan-01` | Chan's 5-minute Bollinger fade, replicated on index futures with real costs | test | F10 | IDXF | the book trades the futures: spot points with futures costs |
| `book-fmchan-02` | NIFTY vs BANKNIFTY futures pair with a cointegration gate and half-life exit | test | F12 | DLY | - |
| `book-fmchan-03` | GOLD/SILVER spread trade only when cointegrated, exit on the OU half-life | test | F12 | MCXD | R94 idea 2 without the book's cointegration gate and half-life exit |
| `book-fmchan-04` | Natural gas long from late February to mid-April (Chan's exact window) | test | F12 | MCXD | one trade a year: at most 14 trades, min-trades gate binds |
| `book-fmchan-05` | Trailing half-Kelly throttle that can only cut an arm's size | test | F9 | MM | - |
| `book-fmchan-06` | Lot cap from the worst historical day (Chan's worst-loss leverage cap) | test | F9 | MM | - |
| `book-fmchan-07` | Turning point: a 1% day against a fresh 10-day extreme (Chan's GS rule, simplest form) | test | F5 | DLY | - |
| `book-fmchan-08` | ETH vs BTC hourly spread reversion inside Boss's crypto window, with stop and profit lock | test | F12 | CRY | - |
| `book-fmchan-09` | BTC 5-minute Bollinger fade (Chan's cost lesson) with stop and profit lock | test | F12 | CRY | - |
| `book-fmross-01` | Crude next-day continuation after a >1% down day (Ross four-state table) | test | F12 | MCXD | - |
| `book-fmross-02` | Buy the ATM straddle when the state-conditional volatility forecast exceeds ATM IV | test | F8 | VOL | index only (MCX options only from 2025) |
| `book-fmross-03` | Fade deviations from the AR(1) reversion level of the log price | test | F12 | MCXD, DLY | - |
| `book-fmross-04` | Sell defined-risk premium when AR(1) horizon variance is well below the GBM variance in ATM IV | test | F8 | VOL | index only: BANKNIFTY near (monthly) and NIFTY monthly series |
| `book-fmkaratzas-01` | Size an arm's lots on the cushion above the account floor (growth-optimal with a wealth floor) | test | F9 | MM | - |

211 rules; 166 tested in 221 tests; 18 covered; 26 lack data; 1 descriptive.
