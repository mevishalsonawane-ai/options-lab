# R86b: how the big players trade, read backwards from the index and its stocks

*19 Oct 2026. Research only; nothing in the app changes. Pre-registration: `research/hunt/r86/PREREG_B.md` (commit
155f3f2, pushed before any outcome). Code: `research/hunt/r86/r86b.py`, `r86blib.py`, tests `test_r86b.py`. Results:
`research/hunt/r86/results/b_design.json`. Follows R86 (`HUNT_R86_BIG_PLAYERS.md`).*

Boss, 19 Oct: *"I don't want who are the big players, I want to reverse engineer and find out how do they trade, find
what they could be thinking while buying or selling."*

## The answer for Boss

**From the price and volume path we CAN see how big money trades. But by the time the footprint is visible, the
move it made is already in the price: 0 of 8 "spot it early and follow" rules beat random entries after costs.**

What the footprints show (NIFTY and BANKNIFTY minutes plus the minute trading of 59 big index stocks, 2019-2024):

1. **Big selling is fast; big buying is slow.** On days FIIs sold the most index futures, NIFTY made about 61% of its
   whole move by 10:15 (overnight gap 37%, first hour 24%) and only 6% in the last hour (169 days). On their biggest
   buying days, only 40% was done by 10:15 (gap 36%, first hour just 4%); the rest was spread over the day, with 16% in
   the last hour and a busier close (18.3% of turnover in the last 30 minutes vs 16.1% usually; 179 days). BANKNIFTY
   shows the same: last hour 3% on FII-sell days, 13% on FII-buy days.
   *What they are probably thinking (inference):* selling is risk-cutting, so it is urgent and done at the open;
   buying is building a position, so it is worked patiently through the day (VWAP/TWAP style) and finished at the
   close.
2. **Clients do the mirror image.** On days clients bought the most futures, the profile looks like an FII-sell day
   (front-loaded, the index fell; 166 days). The crowd buys what the institutions sell (OF1 saw this as -0.35
   correlation).
3. **Funds trade at the close on benchmark days.** On month-end sessions the last 30 minutes carry 26.7% of the day's
   turnover (vs 16.1%; 72 days); on monthly expiry days 23.2%. BANKNIFTY month-end: 29.3%. *Inference:* funds are
   valued at the closing price, so they execute into the close to match it.
4. **"Whole-index" orders (basket bursts) exist and follow the day's direction.** A minute where 80% or more of the
   stocks move the same way on 3x the usual turnover happens about 0.7 times a day on NIFTY and 2.6 on BANKNIFTY; 2.4x
   as often on FII-sell days; it agrees with the day's final direction 65% of the time (NIFTY). They come mostly from
   10:45 to 14:30, rarely at the open. *Inference:* index arbitrage, ETF and fund programmes hitting all stocks at once.
5. **But nothing extra follows a burst.** In the 15-60 minutes after a burst the index moves on in its direction by
   3.5-6.6 basis points, the same as from a random minute (3.2-9.6 bps; 953-997 NIFTY bursts). The burst is the
   push, not a warning of one.
6. **"Absorption" at the day's high/low (heavy volume, no movement) is almost never seen** with strict definitions:
   2 NIFTY and 4 BANKNIFTY cases in 3 years. The "big player is absorbing at the top" story is mostly folklore on index
   data.

**For the bot:** use these as *understanding and risk* facts, not entry signals:
- the first hour after a gap on a heavy-selling day carries the most risk;
- the last 30 minutes of month-end and expiry days are the busiest (wider swings, slippage);
- nothing here should open or skip trades by itself.

## Part 1: the footprints (descriptive, design years 2019-2024 only)

Labels come from the NSE participant file of the SAME day (top / bottom 10% of the change in a group's index-futures net
position vs its previous 250 days). They are known only after the close, so this part describes; it never trades.

### Where in the day the move is made (share of the day's close-to-close move)

Robust aggregate: sum over the group's days of (piece x the day's direction) / sum of the day's absolute move.

| NIFTY | days | gap | 09:15-10:15 | 10:15-11:15 | 11:15-12:15 | 12:15-13:15 | 13:15-14:15 | 14:15-15:30 |
|---|---|---|---|---|---|---|---|---|
| all days | 1,479 | 0.32 | 0.14 | 0.09 | 0.09 | 0.08 | 0.11 | 0.17 |
| FII big buy | 179 | 0.36 | 0.04 | 0.15 | 0.11 | 0.10 | 0.09 | 0.16 |
| FII big sell | 169 | 0.37 | 0.24 | 0.13 | 0.08 | 0.07 | 0.06 | 0.06 |
| Client big buy | 166 | 0.33 | 0.22 | 0.13 | 0.07 | 0.09 | 0.06 | 0.10 |
| Client big sell | 168 | 0.35 | 0.06 | 0.14 | 0.11 | 0.08 | 0.08 | 0.17 |
| monthly expiry | 72 | 0.39 | 0.14 | 0.08 | 0.08 | 0.08 | 0.01 | 0.22 |
| month-end | 72 | 0.18 | 0.09 | 0.18 | 0.17 | 0.07 | 0.07 | 0.25 |

| BANKNIFTY | days | gap | 09:15-10:15 | 10:15-11:15 | 11:15-12:15 | 12:15-13:15 | 13:15-14:15 | 14:15-15:30 |
|---|---|---|---|---|---|---|---|---|
| all days | 814 | 0.29 | 0.19 | 0.11 | 0.07 | 0.09 | 0.11 | 0.15 |
| FII big buy | 107 | 0.42 | 0.17 | 0.07 | 0.06 | 0.10 | 0.06 | 0.13 |
| FII big sell | 98 | 0.34 | 0.20 | 0.16 | 0.09 | 0.12 | 0.09 | 0.03 |

On FII big-buy days NIFTY closed up 66% of the time (179 days); on big-sell days it closed down 76% (169). The same-day
link is strong; OF1 and R86 showed it does not carry to the next day.

### Turnover, steadiness, bursts (mean, 90% bootstrap interval)

| NIFTY | days | last-30-min turnover share | first-30-min share | steadiness ER | bursts a day | bursts agreeing with the day |
|---|---|---|---|---|---|---|
| all days | 1,480 | 16.1% (15.9-16.4) | 16.6% | 0.062 | 0.67 | 65% (361 days with one) |
| FII big buy | 179 | 18.3% (17.1-19.5) | 15.9% | 0.071 | 1.13 | 62% |
| FII big sell | 169 | 16.6% | 16.7% | 0.068 | 1.63 (0.86-2.64) | 73% |
| monthly expiry | 72 | 23.2% (21.3-25.5) | 14.1% | 0.071 | 0.71 | 55% |
| month-end | 72 | 26.7% (24.2-29.5) | 14.1% | 0.068 | 0.49 | 55% |

BANKNIFTY (bank stocks' turnover): last 30 minutes 17.2% on all days (815), 19.6% on FII-buy days (107), 29.3% at
month-end (40), 24.2% on expiry days (40); bursts 2.6 a day (5.4 on FII-sell days, 98).

- The day's turnover is U-shaped: 09:15-09:44 carries 16.5% of NIFTY's turnover, each midday half hour about 5.5%, and
  14:45-15:29 about 21% (12.3% + 8.6%).
- Within a day, a minute's size of move and its turnover correlate 0.38: big moves happen on big volume (aggressive
  orders), not quietly.
- Steadiness (ER) is a little higher on FII days (0.068-0.071 vs 0.062): their days drift more one way.

### What follows a basket burst (design years)

| | n | move in the burst's direction after 15 / 30 / 60 min | random minute, same day, same direction |
|---|---|---|---|
| NIFTY | 997 / 997 / 953 | +3.5 / +5.2 / +6.6 bps | +3.8 / +3.2 / +9.6 bps |
| BANKNIFTY | 2,124 / 2,124 / 2,030 | +1.1 / +1.7 / +3.9 bps | +0.3 / +1.9 / +3.8 bps |

Average absolute moves in the same windows: 20-55 bps. The burst's direction tells nothing beyond the day's own drift.

## Part 2: can the bot spot it early? (8 pre-registered tests)

Signal source: the index's own minutes and its constituents' minute prices and turnover. Trades on the index future
with real costs (Rs 40, dated STT, exchange, SEBI, stamp, GST, 0.02% slippage a side, 0.04% on stops; carry overnight),
each with a stop. Design folds 2022, 2023, 2024; 500 random twins each; one BH family of 8.

| test | footprint | trades | net (Rs) | without top 5 | random twins | twin p | BH q |
|---|---|---|---|---|---|---|---|
| T1-NIFTY | steady busy morning drift, follow 11:30-15:15 | 82 | +3,403 | -47,505 | -37,908 | 0.110 | 0.319 |
| T1-BANKNIFTY | same | 94 | -61,409 | -1,10,924 | -47,713 | 0.615 | 0.994 |
| T2-NIFTY | basket burst, follow 30 min | 201 | -21,558 | -59,478 | -57,071 | 0.120 | 0.319 |
| T2-BANKNIFTY | same | 772 | -4,65,154 | -5,12,253 | -3,36,831 | 0.990 | 0.994 |
| T3-NIFTY | absorption at the day's extreme, fade | 2 | +465 | 0 | -1,492 | 0.062 | 0.319 |
| T3-BANKNIFTY | same | 4 | -3,233 | 0 | -2,977 | 0.655 | 0.994 |
| T4-NIFTY | extreme heavy last hour, follow overnight to 09:30 | 113 | -1,14,080 | -1,70,534 | -68,116 | 0.804 | 0.994 |
| T4-BANKNIFTY | same | 87 | -1,80,810 | -2,30,184 | -47,241 | 0.994 | 0.994 |

- **0 of 8 pass.** The smallest q is 0.319. The final window (1 Jan 2025 - 12 Oct 2026) was not read
  (`r86b.py final` refuses). No APP_SPEC.
- T1-NIFTY (following a steady, busy morning) is the closest: about break-even (+Rs 41 a trade on 82) and better
  than random days, but all of it is its top 5 trades. Consistent with point 1: a patient buyer's drift is real but
  most of it is already done by 11:30.
- T2: following a burst only pays the spread; BANKNIFTY's 772 bursts lost Rs 4.65 lakh.
- T3: too rare to judge (2 and 4 cases).
- T4: "smart money at the close" did not carry overnight; it lost in every fold year on both indices.

## Part C: what this means for the bot

| item | label |
|---|---|
| Do not add "follow the basket burst", "fade absorption", "follow the closing push overnight" or "join the steady morning drift" as entries | proven as negatives here (0 of 8, design) |
| The open after a gap on a heavy-selling day carries most of the day's move; a bought option's stop is most at risk then | descriptive fact (169 FII-sell days), not a rule |
| Month-end and expiry last 30 minutes are the busiest of the month (23-29% of turnover): expect wider swings and slippage | descriptive fact (72 / 40 days) |
| Same-day FII flow and the index move together (66-76%), but nothing here predicts the next day | consistent with OF1 and R86 |

Knowledge items (they reach the pack from FINDINGS.md and `r86b-*.yaml`; tag `big-players`):
- "Big selling is fast, big buying is slow: on FIIs' heaviest index-futures selling days NIFTY made about 61% of its move
  by 10:15; on their heaviest buying days 40%, with more of it late and at the close (R86b, 2019-2024, 169 / 179 days)."
- "Month-end sessions put 27% of NIFTY's turnover in the last 30 minutes (16% on other days): funds trade at the
  closing price (R86b, 72 days)."
- "Minutes when 80% of the index's stocks move together on 3x volume are followed by no extra move (R86b, 997 NIFTY
  bursts)."

## Honest limits

- Turnover comes from today's 59 constituents (survivorship); index futures minutes exist only from Jul 2026, so
  futures volume could not be used.
- The participant label is the change in ALL index futures (not NIFTY alone) and is end-of-day: it shows what the
  day looked like, not when during the day FIIs traded.
- "What they are thinking" lines are inferences that fit the footprints and the literature in `BIG_PLAYERS_GUIDE.md`
  (Almgren-Chriss execution, benchmark trading at the close); they are not proven intent.
- T3's strict definitions produced almost no cases; a looser definition was not tested (it would be a new test).
