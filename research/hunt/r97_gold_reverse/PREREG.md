# R97 pre-registration: gold option buying, reverse-engineered from the moves (21 Oct 2026)

Boss, 21 Oct: *"I can see the gold moves 200-300 easily every single day, still you are not able to find a good
strategy for me, try reverse engineering."* Research only: nothing in `android/**` or `.github/**` changes; nothing
goes to paper or live.

Written and committed **before any P&L statistic of any rule below was computed.** What was looked at first (all on
the DESIGN window only, all descriptive, in `results/step1.json`, `step2.json`, `step3.json`):
step 1 (the moves on the future), step 2 (what came before them, triggers split by outcome, on the future before any
costs), step 3 (the option P&L on hindsight-picked swings only). The rules below were chosen BECAUSE of those numbers,
so the design test is not fully independent of the choice; that is why the twins, BH and the untouched holdout
decide, not the design net. The holdout (15 Jul - 19 Oct 2026) has not been opened by any step.

## 1. Periods (R96's, unchanged)

- Design 5 Aug 2025 - 14 Jul 2026; walk-forward folds F1 5 Aug - 31 Oct 2025, F2 1 Nov 2025 - 31 Jan 2026,
  F3 1 Feb - 30 Apr 2026, F4 1 May - 14 Jul 2026 (rules fixed in advance; nothing is tuned per fold).
- **LOCKED HOLDOUT 15 Jul - 19 Oct 2026**, read once by `study.py holdout <rule>`, only for a rule that passes every
  design gate (s6); the script writes a lock file and refuses a second read.

## 2. Book, signal source, strike

- **Book: GOLDM** near-month options (x10), the only gold book whose stop risk fits Boss's account rules: a GOLD
  (x100) ATM option cost a median Rs 2.2 lakh a lot in step 3, so one 15% premium stop alone (~Rs 33k) is close to
  the -Rs 40k drawdown stop. GOLD is reported descriptively only.
- **Signal source of every rule: the MCX gold future** (GOLDM's near future: the per-minute median of the option rows'
  `spot`, close-only, R53 `Market`). R97E also reads **XAUUSD spot** (Dukascopy BID minutes) as context for the
  future's own move. No rule reads an option's chart. Stops and locks on the premium are allowed (CLAUDE.md).
- Strike: **ATM** (the stored strike nearest the future at the signal minute), call for long, put for short.
- Fill: the OPEN of the contract's first printed minute within **5 minutes** after the signal minute, else no trade.
  No entry on the option expiry day; out by the square-off (session close - 15 min) the same day; entries only
  09:15 - 22:30 IST.

## 3. The seven rules (each one test in the BH family)

"Trigger T" = the future's minute close is T points off its running extreme in the other direction (a zig-zag of
threshold T read in real time: `moves.trigger_points`). Its "pivot" is that extreme. Every filter reads only the
session up to the trigger minute (`features.day_features`, look-ahead guard tested).

| id | from which finding | signal on the future | max a day | underlying stop | underlying trail |
|---|---|---|---|---|---|
| R97A | step 2: triggers at a new session extreme (+26 pts before costs, 704) and morning triggers (+11, 3,382) | trigger 100 in 09:15-12:00 that is also a new session high (long) / low (short) | 1 | pivot | 300 |
| R97B | step 2: new session extreme | trigger 100 at a new session high / low, 09:15-22:30 | 2 | pivot | 300 |
| R97C | step 2: previous-day high / low break (+9, 505) | trigger 100 that crossed yesterday's high (long) / low (short) in the last 15 min | 1 | pivot | 300 |
| R97D | step 1-2: the morning is the only clock bucket with positive pre-cost capture | the first trigger 100 of 09:15-11:30, either side | 1 | pivot | 300 |
| R97E | step 2: MCX follows XAUUSD with a small 1-minute lag (corr 0.07-0.13) | XAUUSD's 5-min move beats the future's by >= 12 bp (about the top 5% of minutes) and XAUUSD moved >= 15 bp: buy in XAUUSD's direction | 3 | 150 pts | none; out after 15 min |
| R97F | step 3: only swings >= 500-1000 pay an option buyer | trigger 300 (the move is already 300 off its extreme) at a new session high / low | 1 | pivot | 300 |
| R97G | step 2: big swings START after a wide, fast counter-move (30-min range 629 vs 258 at random; 15-min move -215) | 30-min range >= 600 and the future moved >= 200 against the trade in the last 15 min, then trigger 100 the other way | 1 | pivot | 300 |

One position at a time; a signal while a position is open is skipped. R96's ideas are not repeated: E1/E2 (event
straddle / follow), U1 / R3I (US-session and last-half-hour momentum), the NR7 family and squeezes are R96's.

## 4. Exits common to all rules (a stop and a profit lock on every trade)

Minute by minute from the fill, in this order (`engine.walk`):
1. **Hard premium stop -15%** of the fill, and the **profit lock** on the premium (GOLDM option points; x10 = rupees):
   peak +60 -> lock breakeven (fill + round-trip costs), +120 -> lock +60, beyond +120 a trail at peak - 80. Checked on
   the minute's low; out at the level, or at the open if the minute opened below it.
2. **Underlying stop** (the pivot, or 150 points for R97E) and **underlying trail** (the future back 300 points from
   its best close since the fill), on the future's minute close; out at the option's exit price that minute.
3. Time: R97E after 15 minutes; all rules at the square-off.
Exit price: the printed close, else the next print's open within 10 minutes, else the Black-76 mark when the strike
has left the stored ATM+/-3 window (2x half-spread; "model exit", counted), else the last print (stale, counted).

## 5. Costs and size

Zerodha MCX option charges (R53 `mcx_opt_charges`) + R96's half-spread on each fill: 0.60% of the premium before
17:00 IST, 0.30% from 17:00; every gate also at 2x the half-spreads. 1 lot a trade. Stop risk per trade and the
capital at which it is 2% are reported.

## 6. Gates (R96 round 2's, unchanged)

On design: >= 30 trades; net > 0 at 1x and at 2x half-spreads; **random-entry twins** p < 0.05 (each trade re-run on a
random other design session of the same calendar month at the same clock minute, same side, strike rule and exits,
its stop distance kept; 500 sets); **BH q <= 0.10** over the family = R96's 27 ideas (rounds 1-3, ledger snapshot
`results/r96_ledger_snapshot.json`, c02f77b4) + these 7 = 34; >= 3 of 4 folds positive; no month worse than
-Rs 13,000; max drawdown better than -Rs 40,000; net without the 3 best trades > 0; model-priced exits <= 10% of
trades and the real-exit trades' net > 0.
Holdout (only after a full pass): >= 10 trades, net > 0, twin p <= 0.10, no month below -Rs 13k, drawdown > -Rs 40k.

## 7. What is reported whatever happens

Every rule's trades, net, gross, costs, win rate, folds, worst month, drawdown, twin p and BH q in
`results/design.json` and the write-up `research/HUNT_R97_GOLD_REVERSE.md`; each rule logged as
`research/experiments/entries/r97-*.yaml`, failures included.
