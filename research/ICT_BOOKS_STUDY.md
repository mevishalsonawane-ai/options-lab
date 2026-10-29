# ICT / SMC books: what they teach, and what our tests got wrong (study before the next strategy)

29 Oct 2026. Research only. Boss sent four sources and asked to *"deep study and add all the knowledge to neuro network,
and check what are we missing or what are we doing wrong, study whole and only then start further process with
creating a strategy"*.

## Sources

| source | status | what it is |
|---|---|---|
| "ALGORITHM THEORY" (Google Drive PDF, 232 pages) | read in full, every chart page | the deepest of the three: liquidity tiers, daily/weekly cycles, strong/weak structure, algo candle, HVI, breaker/rejection blocks, TDI, worked 1-minute trades |
| "Complete Smart Money Concepts Guide", MATI Trader (89 pages) | read in full | a simplified ICT with one complete daily model, a cheat sheet, partial-profit and news rules |
| "Mastering ICT & SMC Trading", Vineesh Rohini (58 pages) | read in full | a beginner booklet: one entry model and a checklist |
| Studocu "ICT institutional SMC trading" | **could not be read** | paywalled; the free preview shows an unrelated document |

The PDFs are not in git (copyright). The page-by-page extracts are in the session notes; this file keeps what matters.

**All three books use forex, gold and US indices (24-hour markets with Asia, London and New York sessions). None of
them uses Indian markets or options.** That matters a lot below.

## 1. The doctrine all three agree on

1. **Liquidity first.** Price moves to take the stops and pending orders resting beyond obvious highs and lows (equal
   highs/lows, trend lines, retail support/resistance, session and previous-period highs/lows). Supply/demand and
   classic patterns are "traps".
2. **The sweep.** Price runs past that liquidity, often fast ("aggressive"), and turns.
   - *Algorithm Theory*: the sweep candle that immediately leaves a fair value gap is the **algo candle**. It makes the
     high or low **strong**. Without the gap the move is weak and will be retraced.
3. **The structure shift.** After the sweep, price breaks the structure the other way (MSS / CHoCH / "momentum
   shift").
   - *Algorithm Theory*: only the break of a **strong** swing counts. A break of a **weak** swing is a fake (a trap).
   - Mati: the break must be a **candle close** beyond the swing's **wick**.
4. **The entry on the pullback.** Enter where the move started, on the first pullback: the order block, the FVG, the
   "money transfer" higher low, the HVI or the breaker. Longs should be in **discount** (below 50% of the leg), shorts
   in **premium**.
5. **Stop beyond the swept extreme. Target the opposite liquidity.**
   - Targets are the external range high/low, the other side of the Asian range, PDH/PDL, PWH/PWL, or higher-timeframe
     pools.
   - The books show reward-to-risk of 2 to 22, minimum 1.5-3.
6. **Context decides everything.**
   - *Higher-timeframe bias*: daily/weekly trend or price vs the 200 SMA (Mati); the HTF liquidity target (Algorithm
     Theory).
   - *Session timing*: Asia builds the range, Frankfurt/London fakes and sweeps one side, New York traps then
     completes the day.
   - *Weekly timing*: Monday/Tuesday manipulation, Friday distribution.
   - *News*: Mati skips CPI/NFP/PPI/FOMC days; Algorithm Theory trades around them.
7. **Liquidity has sizes.** Major liquidity is yearly, monthly, weekly and daily highs/lows plus HTF swings. Medium is
   1-hour swings. Minor is 1-30 minute swings. Big moves come only from major and medium liquidity. Minor liquidity
   is "about 20 pips a day", for scalps and confirmation only.
8. **Structure must be real.** A valid higher low or lower high must retrace past 50% of the leg (about 75% in the
   charts). A shallow pullback low is "just liquidity" and will be taken.
9. **Management.**
   - Move the stop to breakeven when price breaks the next structure.
   - Mati: take 50% off at each target; risk 1-2% a trade; a 3% daily loss limit.

Concepts unique to *Algorithm Theory*:
- **HVI**: the body junction inside a displacement leg, used as a zone.
- **Rejection block**: from the wick's extreme to the nearest body edge.
- **90-minute cycle** from NY midnight.
- **20/40/60/90-day targets** after a major sweep, with a reversal about 20 days later.
- **"Ping pong"**: 1-minute trades between the Asian high, mid and low.
- **TDI**: RSI(50) with bands of length 50. Entry when RSI crosses the middle band and comes back to touch it while
  price retests the zone. The divergence it uses is hidden divergence.

## 2. What our tests already did right

- Every level, sweep, gap and zone was read on **wicks**, as the books draw them (Mati and Algorithm Theory both).
- **Signal on the underlying**, as the books do.
- Mati's BOS is a **close beyond the wick**, which is what R130/R131 used.
- The FVG is the books' three-candle wick-to-wick gap.
- Pre-registered tests with random twins and walk-forward, not chart-picking. The books never show a losing streak
  or a statistic; their claims ("90% of days", "80% ranges", "75-80% win rates") are untested marketing.

## 3. What we are missing, or doing wrong (the important part)

1. **We traded minor liquidity.** This is probably the biggest mistake.
   - R130/R131 swept the most recent 5-minute pivot (3 bars each side) and targeted the nearest untaken 5-minute
     swing.
   - The books call that **minor liquidity**: good for a scalp of "about 20 pips", and for confirmation, not for the
     trade.
   - Their big moves come from **daily, weekly and monthly** highs and lows and **1-hour** swings. We tested PDH/PDL
     only (Cameron, Turtle Soup V2, CRT), and never weekly or monthly levels, or a confluence of two levels.
2. **We treated every pivot break as a structure shift.** The books separate strong from weak.
   - A **strong** swing is one that swept liquidity and broke structure. Only its break counts.
   - The break of a **weak** swing is the trap they fade.
   - A valid higher low must retrace **past 50%** of the leg.
   - None of R130-R133 modelled strong/weak, so many of our "CHoCH" entries were, by the books, fake breaks.
3. **We did not require displacement.** The books' sweep must be aggressive and leave an FVG (the algo candle).
   Except R130's FVG step, our sweeps had no such test, so we took weak sweeps the books would skip.
4. **No higher-timeframe bias.** Mati's model trades only with the daily/weekly bias (trend or the 200 SMA).
   Algorithm Theory trades toward the HTF liquidity target. We had only Cameron's 5-day close filter.
5. **No premium/discount filter.** The books buy only in discount and sell only in premium. We used it only in OTE.
6. **Targets too close for options.** The books target **external, higher-timeframe liquidity** at 2-22R, with 1-10
   pip stops on spot FX.
   - We targeted the nearest minor swing or 2R, and we **buy an ATM option**. That costs Rs 100-200 a lot in charges,
     spread and decay.
   - So a small, coin-flip index move is a sure loss for us. This is why R131's index-points result (about 0 R) became
     a money loss every time.
7. **The session model does not port to NSE as written.**
   - Their timing edge is Asia's range, swept at the Frankfurt/London open, then the New York trap. NSE has one
     session (09:15-15:30) and no Asian session.
   - Our Silver Bullet "Indian window" was one guess.
   - Closer analogues to test (none tested yet):
     - the **previous afternoon** and the **overnight gap** as the "accumulation";
     - the **opening range** (09:15-09:45) as Asia's range, swept in the next hour (the "London" role);
     - the **US-open / 13:30-14:30** move as the "NY" role.
8. **No news filter.** Mati skips high-impact days. India's are RBI policy, the Budget and US CPI/FOMC (their
   overnight effect). We never filtered them.
9. **Cycles never tested:** the weekly cycle (Monday/Tuesday sets the week's high or low), the 3-5 day direction
   change, the 20/40/60-day HTF target, the 90-minute cycle. They are cheap to check as facts before any rule.
10. **What the network was given.**
    - R125 added support/resistance, simple sweeps (yesterday's high/low, 5-day extreme), candle shapes, VWAP and
      RSI(14) to R123's network. It found **no help** for direction.
    - It did **not** get the books' central ideas:
      - strong vs weak swings;
      - valid structure (the 50% rule);
      - weekly and monthly liquidity;
      - sweep + displacement (the algo candle);
      - open FVGs and their fill state;
      - premium/discount in the current leg and vs the day's open;
      - the 200-SMA daily bias;
      - news days;
      - the books' TDI.
    - The R123 network filter is also the only thing that has helped so far (R132-R133). So the books' concepts are
      worth giving to it.

## 3b. Step 1 done: R135 measured the claims on NSE (`research/HUNT_R135_ICT_FACTS.md`)

The books' core claim fails here. Sweeps of PDH/PDL, last month's high/low and the opening range are followed by
**more continuation than normal**, not a reversal. Three claims do hold:
- the day's high or low usually comes in the first 75 minutes;
- strong-swing breaks continue more than weak ones;
- FVGs get filled.

Two claims are backwards or absent:
- the 50% pullback rule is backwards (shallow pullbacks hold more);
- "fill then reverse" and the Monday/Tuesday week show nothing.

So the strategy step should test **continuation**, not the books' reversal.

## 3c. Step 2 done: R136 put the concepts into the network (`research/HUNT_R136_ICT_NETWORK.md`)

The network with the books' 35 inputs:
- forecast direction no better (still a coin flip);
- forecast its trade cells **worse**;
- picked **worse** trades through its EV filter: CISD 229 kept, -Rs 4k, against the original's 428 kept, +Rs 94.5k
  (q 0.04).

R123's original network stays.

## 3d. Step 3 done: R137, the strategy (`research/HUNT_R137_STRONG_BREAK.md`)

The rule: continuation after a strong-swing break. In design it made Rs 156-176 a trade (about 795 trades, every
fold positive, q 0.01), and the weak-swing control lost. But:
- it broke the account limit;
- it **lost in the holdout** (130 trades, -Rs 373 to -Rs 461 each).

With R123's network filter it made Rs 1,000+ a trade, on only 83 design trades and 9 holdout trades. **0 of 4 pass.**
R123's network filter remains the one lead, and that is what the R134 forward test is for.

## 4. The plan (step by step, each pre-registered before any number)

1. **R135: facts first.** Measure the books' claims on NSE minutes (2021-2026), with sample sizes:
   - Does a sweep of PDH/PDL, PWH/PWL or PMH/PML reverse more often than chance, and by how many points? How does the
     same sweep compare with and without displacement, and strong vs weak?
   - Does the day's high or low form in the first hour (the "London" role)? Is the opening range swept and reversed?
   - Monday/Tuesday as the week's high/low; the 3-5 day turn; the 20-day reversal after a major sweep.
   - Does a break of a strong swing continue more than a break of a weak swing?
   - This tells us which ideas carry real information on Indian indices.
2. **R136: give the knowledge to the network.**
   - Add the books' concepts as inputs to R123's network: liquidity tiers and their distances, strong/weak state,
     valid-structure trend, algo-candle and FVG state, premium/discount, HTF bias, day of week, opening-range state,
     news day, TDI.
   - Retrain it the same walk-forward way, and compare with R123 on the same rows: direction, EV, and the CISD filter.
     BH over the comparisons.
3. **R137: the strategy, only after 1 and 2.** One model, built from the books' complete chain and adapted to NSE
   using what R135 shows:
   - HTF bias;
   - a sweep of **major or medium** liquidity with displacement;
   - a strong-swing shift;
   - a pullback entry in discount/premium;
   - stop beyond the sweep;
   - target the external HTF liquidity, so the index move is big enough to pay for the option;
   - optionally the network filter.
   - Tested with twins, walk-forward and BH. The only unseen data left is the forward period from 20 Oct 2026, the
     same limit as R134.

Nothing here changes the app, and nothing trades. The 65% rule does not apply (Boss's strategies).
