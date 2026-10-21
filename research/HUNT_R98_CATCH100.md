# R98: catching gold's 100-point move "in any condition" (21 Oct 2026)

Boss, 21 Oct: *"I want you to catch the 100+ rs movement in any condition, do all the deep analysis you want, study
the candles, study the market ... think out of the box."* Research only; nothing goes to paper or live. Code, tests
and numbers: `research/hunt/r98_catch100/` (`results/scan.json`, `design.json`, `trades_design.csv`, `NOTES.md`).
Pre-registration `hunt/r98_catch100/PREREG.md`, committed and pushed (b532522c) before any rule was simulated.

**Signal source of every rule: the MCX gold future** (GOLDM near future, minute closes); XAUUSD only as a filter on
the future's move. "100 rupees" = 100 points of the quote (Rs per 10 g) = Rs 1,000 a GOLDM lot.

## The answer for Boss

**R98: NOTHING. 0 of 6 rules pass; the locked holdout was not opened.** This time the reason is precise:

1. **A 100-point move happens all the time, in both directions.** From any moment, gold moves 100+ points within the
   next hour 63% of the time, but in a coin-flip direction: +100 before -100 46.8% of the time, -100 first 46.8%
   (73,432 moments, 239 sessions, both sides). Gross: 0.0 points.
2. **I found conditions that really tilt the coin.** 34 of 254 candle / market / clock conditions passed a
   multiple-testing check (day-clustered, BH q <= 0.10). Two families:
   - gold **snaps back** after a stretch (far from the session average, fast 15-min move, red candles in a row);
   - **MCX follows XAUUSD**: when international gold has run >= 10 bp ahead over 15 minutes and MCX has not moved
     yet, MCX tends to follow. The rule built on this (R98B, after 12:00) beat random entries with **p = 0.01**
     (612 trades). That is real.
3. **But the tilt is worth ~Rs 120-170 a trade, and Zerodha's charges on one GOLDM futures round trip are ~Rs 290**
   (CTT 0.01% of the sale + exchange fees on a ~Rs 14 lakh lot), before any spread. Even pricing every stop and target
   exactly at its level with zero spread (the most generous case), every rule loses (NOTES N2). With real minute
   data the stops slip: a -100 stop was seen, on average, at -186, because gold often moves 50-100+ points inside a
   single minute.
4. **Options are worse for this:** the option keeps under half of a 100-point future move and costs about the same.
   Every one of the 254 option buckets lost (-25 to -60 points net); R98E (option on the best signal) lost -Rs 2.47 lakh on 548.
5. **The machine-learning model** (gradient boosting on every candle, indicator, XAUUSD, silver and clock feature,
   trained only on earlier months) did not beat random entries out of sample (p 0.90, 382 trades).

So: the 100-point move is there "in any condition", and some conditions tilt it, but at the 100-point size the
government and exchange charges alone eat about 30% of the target, and the best tilt found is about 14%. A 100-point
scalp on MCX gold cannot pay, by arithmetic, unless the edge were 2-3x larger than anything found in 254 conditions.

## Step A: base rates (design, GOLDM, a 5-minute grid of entry moments 09:15-22:30, both sides)

| bracket on the future (target +100) | hit target | hit stop | neither | gross | net after ~43 pts |
|---|---|---|---|---|---|
| stop 50, 60 min | 36.7% | 60.6% | 2.7% | -0.6 | -42.4 |
| stop 100, 60 min | 46.8% | 46.8% | 6.4% | 0.0 | -41.9 |
| stop 150, 120 min | 56.3% | 39.6% | 4.1% | +1.3 | -40.6 |
| option ATM, +100 / -50 premium, 60 min (37,117 fills; 50% of moments had a print within 5 min) | 27.4% | 63.6% | | | -42.6 pts (-Rs 426 a lot) |

(These labels use minute closes; see NOTES N2: close-based labels overstate both sides' moves.)

## Step B: what tilts the coin (254 one-feature buckets; future bracket +100 / -100 / 60 min, gross points)

Day-clustered means (each session counts once), BH q over all 254:

| condition (in the trade's direction) | moments | gross | q |
|---|---|---|---|
| price >= 294 below the session average (fade the stretch) | 14,687 | +15.2 | 0.001 |
| XAUUSD ahead of MCX by >= 5 bp over 15 min | 14,228 | +12.6 | <0.001 |
| 2+ 15-min candles against the trade | 7,198 | +12.4 | 0.096 |
| bearish 5-min engulfing (for a long; fade it) | 2,907 | +9.8 | 0.068 |
| 5-min close >= 130 below its EMA20 | 14,682 | +9.1 | 0.008 |
| 15-min move >= 8.4 bp against | 14,638 | +8.6 | 0.001 |
| 15-min RSI low (for a long) | 14,671 | +7.2 | 0.076 |
| beyond yesterday's high / low (with the trade) | 19,302 | -13.7 | 0.069 |

Clock anchors (Shanghai Gold Exchange auction 11:45 IST, LBMA fixes, COMEX open, US data time) and silver gave no
bucket with q <= 0.10 beyond those above. Every option bucket was negative.

The best two-feature cell: XAUUSD ahead >= 10 bp and MCX's last three 5-min candles not yet moving that way:
3,197 moments, positive in all 4 folds, long and short, strongest after 12:00, and alive with one minute of extra
delay. On minute closes it read +42.5 points; priced at the exact levels it is **+13.6 points** (target first 55.4%
vs stop first 41.8%).

## Step C: the six pre-registered rules (design 5 Aug 2025 - 14 Jul 2026; 1 lot; BH over 40 ideas)

Future rules: fill at the next minute's close; stop -100 (at the close), target +100 (limit), profit lock +70 ->
+20, out after 60 min or at the square-off; max 4 a day. Costs: Zerodha MCX futures charges + R53's spread.

| rule | trades | gross | costs | **net** | win | twin p (BH q) | gross a trade if filled exactly at the levels (no spread) vs charges |
|---|---|---|---|---|---|---|---|
| R98A XAUUSD ahead >= 10 bp, MCX not moved (future) | 701 | -Rs 1.67 lakh | Rs 3.00 lakh | **-Rs 4.67 lakh** | 50% | 0.385 (0.670) | Rs 128 vs Rs 288 |
| R98B R98A after 12:00 (future) | 612 | -Rs 55,540 | Rs 2.63 lakh | **-Rs 3.18 lakh** | 50% | **0.010** (0.133) | Rs 168 vs Rs 289 |
| R98C walk-forward ML model (future; F2-F4 only) | 382 | -Rs 1.14 lakh | Rs 1.64 lakh | **-Rs 2.78 lakh** | 50% | 0.896 (0.962) | Rs 136 vs Rs 290 |
| R98D stretched >= 600 + XAUUSD ahead (future) | 542 | -Rs 1.82 lakh | Rs 2.36 lakh | **-Rs 4.19 lakh** | 51% | 0.671 (0.894) | Rs 123 vs Rs 296 |
| R98E R98A's signal, ATM option | 548 | +Rs 11,640 | Rs 2.59 lakh | **-Rs 2.47 lakh** | 31% | 0.351 (0.664) | (option) |
| R98F candle fade (future) | 913 | -Rs 1.71 lakh | Rs 3.85 lakh | **-Rs 5.56 lakh** | 44% | 0.198 (0.565) | Rs 56 vs Rs 282 |

Every rule lost in every fold it traded and broke the -Rs 13k month limit. Exit mix for R98A: 350 targets (+Rs 1,000 each),
270 stops (average -Rs 1,860: the slippage), 68 locks, 13 time exits. Full table: `results/design.json`.

**The locked holdout (15 Jul - 19 Oct 2026) was NOT read**: nothing passed.

## What this means

- The pieces Boss sees are real: gold gives 100 points many times every session, and MCX does follow international
  gold a little (R98B beat random timing, p 0.01).
- At a 100-point target, costs are the wall: ~29 points of statutory charges (no broker can remove CTT or exchange
  fees) + spread + stop slippage, against an edge of ~10-17 points.
- What could change the arithmetic (not tested here, each needs its own pre-registration): tick or second data for
  honest stop fills (the lake has only minute closes for the near GOLDM future); much larger targets where a ~30-point
  cost is a small share (but the tilts found are short catch-ups that do not scale); or a market whose costs are a
  smaller share of a typical move.

## Ideas logged

`research/experiments/entries/r98-scan.yaml` (the measurement, not a test) and `r98-a` ... `r98-f` (rejected).
Implementation notes: `hunt/r98_catch100/NOTES.md` (N1 a report-shape fix before any result; N2 the level re-pricing
diagnosis after the results, not a gate).
