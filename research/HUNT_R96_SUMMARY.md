# R96 summary: six rounds hunting an MCX commodity option-BUYING strategy (21 Oct 2026)

Boss, 21 Oct: *"Run until we found something, commodities options buying strategy"*. Six pre-registered rounds, 46 ideas,
one design window (5 Aug 2025 - 14 Jul 2026, four walk-forward folds), real Zerodha MCX charges plus assumed
half-spreads (stressed at 2x), 500 random-session twins per idea, and BH over every idea tried. Research only: nothing
traded and nothing in the app changed.

**Signal source of every rule: the MCX future** (the option book's near future, or the near-month daily continuous
future). Option-chain IV is used as a filter only (V1, R4G, R5B, R5C, R6B). Every trade BUYS options and has:
- a hard stop on the premium (and on the future for single legs);
- a profit lock (the ladder; from round 2 also the edge take-profit at the deepest stored strike).

**The locked holdout (15 Jul - 19 Oct 2026) was never read**, because no idea passed every design gate.

## Verdict

**Nothing found. With this data, MCX option buying has no edge we could prove.**
- 0 of 46 ideas passed.
- About 30 lost money after costs, and most intraday, event and volatility ideas lost even before costs.
- One family survives as an unproven lead: **the crude NR7 breakout** (R3A, and R4G on cheap-IV days). It is positive
  in design, but its books were chosen after seeing earlier rounds, and it has only 14-23 independent signal days.
- It failed its one fresh out-of-sample test: the same rule on COPPER (round 6) lost -Rs 84,978 on 13 trades.

The honest reading: either there is no buyer's edge on MCX options, or it is smaller than our data can show. Our data
is 11 months, ATM+/-3 strikes only, and assumed spreads.

## What each round asked

| round | ideas | question | answer |
|---|---|---|---|
| 1 | 9 | squeeze, NR7, wide-range, pullback, cheap-vol straddle, event straddle / follow, US-session momentum on the four minis | NR7 looked strong (+Rs 1.84 lakh on 58) but its profit came from model-priced deep-ITM exits |
| 2 | 8 | NR7 on real prints only (edge take-profit), 2-ITM, inside day, big energy books, a risk cap, relaxed squeeze, NR7 straddle, quiet-base breakout | real fills killed NR7 on the minis (-Rs 4,067 on 65); only CRUDEOIL held up (+Rs 45,170 on 23) |
| 3 | 12 | NR7 on crude only, NR4, future trail, low-vol regime, weekly NR7, early breaks, US-open hold, EIA momentum, last-half-hour momentum, high-vol ORB, R93's trailing exits | R3A (crude NR7) passed every gate except BH (q 0.135 then) |
| 4 | 7 | crude NR7 variants (US open, 12-month / 1-month trend, 2-ITM, cheap IV), EIA spike fade, 17:00 IST metal momentum | R4G (cheap IV) best yet: +Rs 49,381 on 28, p 0.002; fails trades and the family gate |
| 5 | 6 | NG storage-day call, cheap-IV NR7 on gas / metals, cheap-IV crude straddle, looser EIA fade, pre-event straddle, gold / silver ratio | all six lose; the cheap-IV filter works nowhere else |
| 6 | 4 | NR7, cheap-IV NR7, first-half-hour momentum, 20-day channel breakout, on the untouched COPPER / ZINC books | all four lose; ZINC is untradeable (33 NR7 signals, 0 fills) |

## Every idea tried (design window; BH q over all 46)

| round | id | idea (signal on the future; trade buys options) | trades | net (1 lot) | twin p | BH q (46) | verdict |
|---|---|---|---|---|---|---|---|
| 1 | S1 | squeeze release, ATM straddle | 6 | Rs 18,814 | 0.068 | 0.498 | lead |
| 1 | S2 | squeeze release, 1-ITM direction | 7 | -Rs 16,521 | 0.563 | 0.841 | dies |
| 1 | N1 | NR7 breakout, 1-ITM | 58 | Rs 1.84 lakh | 0.006 | 0.092 | lead |
| 1 | W1 | wide-range continuation, 1-ITM | 25 | -Rs 2.19 lakh | 1.000 | 1.000 | dies |
| 1 | P1 | pullback in trend, 1-ITM | 35 | Rs 1.08 lakh | 0.164 | 0.606 | lead |
| 1 | V1 | IV below HAR forecast, ATM straddle | 15 | -Rs 46,640 | 0.242 | 0.614 | dies |
| 1 | E1 | event straddle (EIA / storage / CPI / NFP) | 103 | -Rs 61,416 | 0.768 | 0.906 | dies |
| 1 | E2 | event follow, 1-ITM | 103 | -Rs 28,960 | 0.259 | 0.614 | dies |
| 1 | U1 | US-session momentum (big first half-hour), 1-ITM | 236 | -Rs 1.64 lakh | 0.882 | 0.941 | dies |
| 2 | R2A | NR7 breakout, 1-ITM, edge take-profit (real fills) | 65 | -Rs 4,067 | 0.271 | 0.614 | dies |
| 2 | R2B | NR7 breakout, 2-ITM, edge take-profit | 67 | -Rs 7,562 | 0.365 | 0.700 | dies |
| 2 | R2C | Crabel inside-day NR7 breakout, 1-ITM | 13 | -Rs 14,129 | 0.752 | 0.906 | dies |
| 2 | R2D | NR7 breakout on CRUDEOIL / NATURALGAS options | 43 | Rs 30,767 | 0.076 | 0.498 | lead |
| 2 | R2E | NR7 breakout with a Rs 6,500 stop-risk cap | 48 | -Rs 8,599 | 0.758 | 0.906 | dies |
| 2 | R2F | relaxed squeeze release, ATM straddle, 6 books | 15 | Rs 18,279 | 0.002 | 0.046 | lead |
| 2 | R2G | NR7 day -> ATM straddle | 69 | -Rs 59,254 | 0.876 | 0.941 | dies |
| 2 | R2H | quiet-base (NR7 within 5 days) 20-day breakout, 1-ITM | 56 | -Rs 1.08 lakh | 1.000 | 1.000 | dies |
| 3 | R3A | NR7 breakout on crude only (CRUDEOILM + CRUDEOIL) | 46 | Rs 48,875 | 0.014 | 0.161 | lead |
| 3 | R3B | NR4 breakout on crude | 77 | -Rs 886 | 0.226 | 0.614 | dies |
| 3 | R3C | NR7 breakout, future trail instead of the premium ladder (energy) | 43 | Rs 35,048 | 0.066 | 0.498 | lead |
| 3 | R3D | NR7 breakout in a low-volatility regime (energy) | 27 | Rs 16,991 | 0.178 | 0.606 | lead |
| 3 | R3E | weekly NR7 breakout, 5 sessions (energy) | 10 | -Rs 6,173 | 0.535 | 0.841 | dies |
| 3 | R3F | NR7 breakout in the first two hours only (energy) | 23 | Rs 5,353 | 0.309 | 0.619 | lead |
| 3 | R3G | NR7 morning break still holding at the US open (energy) | 32 | Rs 43,065 | 0.190 | 0.606 | lead |
| 3 | R3H | EIA-day 11:00-11:30 ET momentum, 1-ITM (crude) | 90 | -Rs 10,239 | 0.585 | 0.841 | dies |
| 3 | R3I | last-half-hour intraday momentum, 1-ITM (4 minis) | 223 | -Rs 79,100 | 0.639 | 0.890 | dies |
| 3 | R3J | US-open range breakout in a high-volatility regime (crude) | 14 | Rs 7,406 | 0.126 | 0.606 | dies |
| 3 | R3K | NR7 crude, 1-ITM option with R93's 2 ATR stop + 3 ATR chandelier, 10 sessions | 36 | -Rs 45,115 | 0.571 | 0.841 | dies |
| 3 | R3L | NR7 crude, the future itself with R93's 2 ATR stop + 3 ATR chandelier, 10 sessions | 32 | Rs 1.31 lakh | 0.401 | 0.738 | lead |
| 4 | R4A | NR7 crude, morning break still holding -> entry at the US open (R3A x R3G) | 38 | Rs 36,246 | 0.198 | 0.606 | lead |
| 4 | R4B | NR7 crude, only breaks agreeing with the 12-month trend (TSMOM filter) | 15 | Rs 11,534 | 0.178 | 0.606 | lead |
| 4 | R4C | EIA spike fade, 1-ITM against the first 5 minutes (crude) | 2 | -Rs 267 | 0.090 | 0.516 | lead |
| 4 | R4D | MCX 17:00-17:30 IST momentum, 1-ITM, same session (GOLDM, SILVERM) | 214 | -Rs 1.10 lakh | 0.683 | 0.897 | dies |
| 4 | R4E | NR7 crude, 2-ITM strike | 47 | -Rs 901 | 0.293 | 0.614 | dies |
| 4 | R4F | NR7 crude, only breaks agreeing with the 1-month trend | 21 | Rs 2,400 | 0.273 | 0.614 | lead |
| 4 | R4G | NR7 crude, only when the NR7 day's ATM IV is below its 20-session median | 28 | Rs 49,381 | 0.002 | 0.046 | lead |
| 5 | R5A | natural-gas EIA storage day, 1-ITM call from 17:00 IST, same session | 70 | -Rs 43,455 | 0.473 | 0.777 | dies |
| 5 | R5B | NR7 breakout on gas and metals, only on cheap-IV days (R4G's filter) | 36 | -Rs 36,585 | 0.677 | 0.897 | dies |
| 5 | R5C | NR7 crude -> ATM straddle, only on cheap-IV days | 26 | -Rs 28,728 | 0.762 | 0.906 | dies |
| 5 | R5D | EIA spike fade at 0.10 ATR (crude) | 15 | -Rs 368 | 0.162 | 0.606 | dies |
| 5 | R5E | pre-event ATM straddle 17:00 IST -> release - 5 min (EIA crude, EIA storage) | 164 | -Rs 93,324 | 0.283 | 0.614 | dies |
| 5 | R5F | gold / silver ratio breakout -> 1-ITM SILVERM, 5 sessions | 27 | -Rs 47,162 | 0.794 | 0.914 | dies |
| 6 | R6A | NR7 breakout on the untouched COPPER and ZINC option books (R3A's rule) | 13 | -Rs 84,978 | 0.830 | 0.932 | dies |
| 6 | R6B | NR7 breakout on COPPER and ZINC, only on cheap-IV days (R4G's filter) | 7 | -Rs 71,707 | 0.900 | 0.941 | dies |
| 6 | R6C | first-half-hour momentum -> last-hour 1-ITM (COPPER, ZINC) | 48 | -Rs 43,932 | 0.425 | 0.738 | dies |
| 6 | R6D | 20-day Donchian breakout (first day) -> 1-ITM, 5 sessions (COPPER, ZINC) | 21 | -Rs 75,373 | 0.433 | 0.738 | dies |

"lead" in the ledger means net > 0 with >= 2 of 4 positive folds, or twin p < 0.10; it is not a pass. Many leads are
copies of the same crude NR7 days (R3A, R3C, R3D, R3F, R3G, R4A, R4B, R4F, R4G, R2D share most trades), so their
rupee results are not independent. Gate 9 collapses those families into one test each. Trade-level detail per round:
`research/hunt/r96_mcx_buy/results/report_round<N>.md`.

## The best leads, and what would settle them

1. **Crude NR7 on cheap-IV days (R4G).**
   - Design: +Rs 49,381 on 28 trades (14 signal days, both crude books on the same days), 86% win, twin p 0.002.
   - BH q: 0.046 over 46 ideas (0.059 with R97 / R98's MCX ideas). Family gate q 0.177: fails.
   - Weaknesses:
     - its biggest winner (22 Oct 2025) is model-priced;
     - 24 of its 28 trades are R3A's;
     - the filter failed on every other book (R5B, R5C, R6B).
2. **Crude NR7 (R3A).**
   - Design: +Rs 48,875 on 46 trades (23 signal days), 4 of 4 folds, worst month -Rs 9,718, twin p 0.014.
   - BH q 0.161; fails BH and the family gate.
   - Weaknesses:
     - the crude books were picked after round 2;
     - on 2012-25 futures crude was NR7's weakest book (+0.58% vs +0.41%, 329 trades, p 0.26);
     - the one fresh-book test (R6A, COPPER) lost.
3. Weaker, not worth more design-window work:
   - R2F, a relaxed squeeze straddle: p 0.002, but one trade is all of it.
   - R3L, the crude future itself with R93's trail: random days made most of it.

**What data would settle them:**
- **Forward data (pre-registered, PREREG_ROUND5 s7).** R3A and R4G are read once on minute data from 20 Oct 2026,
  when 15 crude NR7 signal days exist (about 7-8 months) or on 30 Jun 2027. Pass: net > 0 at 1x and 2x spreads, twin
  p <= 0.10, no month below -Rs 13,000. A pass allows only a PAPER arm to be proposed to Boss.
- **Wider strikes (ATM+/-5 or more) for MCX in the lake.** Every crude winner hits the deepest stored strike, so the
  right tail an option buyer lives on is never measured. On COPPER the window holds only 3-4 distinct strikes.
- **Recorded bid / ask for MCX options.** Every R96 cost is an assumption: 0.30-0.60% for crude, 1.25-2.50% for zinc.
- **The XAUUSD minute pack on Drive.** Only then can the MCX-vs-COMEX gold lag be tested (R97 / R98 tested a related
  XAUUSD lead on other data and found nothing that passed).
- More history of the options themselves: 11 months gives crude NR7 about 2 signal days a month.

## What six rounds taught (plain)

1. **The option market prices scheduled news.** Buying straddles around EIA, storage, CPI and NFP releases loses
   (E1 -Rs 61,416 on 103; R5E -Rs 93,324 on 164), and so does following the first move after them (E2, R3H, R4C, R5D).
2. **Intraday momentum from the papers does not survive a 1-ITM MCX option.** Every version lost before or after
   costs: U1 (236 trades), R3I (223), R4D (214) and R6C (48).
3. **Cheap-looking volatility is not a buy.** V1, S1, R2G, R5C and R5E all lost: buyers of MCX options pay a premium
   for insurance, as in other markets.
4. **Directional daily signals work a little on futures, but not enough for options.** On 2012-25 futures, NR7 beat
   random days (+0.58% vs +0.32%, 1,436 trades), and so on copper / zinc (+0.22% on 719, weaker). An option buyer pays
   0.6-5% in spreads and theta, which eats an edge of 0.1-0.3% a trade.
5. **Model-priced exits can fake a strategy.** Round 1's +Rs 1.84 lakh became -Rs 4,067 on real prints. Every later
   round flags model exits (gate 8).
6. **Lot size and liquidity rule out many books.**
   - For a 2% stop risk on 1 lot: CRUDEOIL needs about Rs 4.6-6 lakh (R3A / R4G); COPPER (2,500 kg, about Rs 75,000 a
     lot) needs Rs 13-16 lakh (round 6).
   - ZINC barely prints, and the gas books pay 0.45-0.90% half-spreads.
7. **More design-window copies only raise the bar.** After 46 ideas, a new idea needs a twin p near 0.002 to reach
   q <= 0.10. Six rounds on one 11-month window have used up what that window can say.

## Recommendation

- Stop new design-window rounds on MCX option buying. Let the pre-registered forward read-out of crude NR7 run. The
  app's "NR7 crude (record only)" log helps only while Boss's switch is ON.
- Add wider MCX strikes and bid / ask recording to the lake if possible; they are the cheapest way to make a future
  test decisive.
- Nothing goes to paper or live from R96 now.

## Files

Round write-ups: `research/HUNT_R96_ROUND1.md` .. `HUNT_R96_ROUND6.md`. Code, pre-registrations (PREREG_ROUND1-6),
notes N1-N11, internet scans, ledger and results: `research/hunt/r96_mcx_buy/`. Experiment entries:
`research/experiments/entries/r96-*.yaml` (46). Trade-by-trade crude NR7: `hunt/r96_mcx_buy/results/NR7_CRUDE_DETAIL.md`.
