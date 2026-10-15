# I11 Days like today: the morning brief's nearest past days

*13 Oct 2026. Pre-registration: `research/context/PREREG.md` (I11). Code: `research/context/days_like_today.py`; numbers:
`research/context/results/days_like_today.json`. Data: the `research-data` release (BANKNIFTY 457, NIFTY 432 sessions
scored walk-forward after a 40-session start). Re-runs on the lake with one switch. Context, not a forecast.*

## Verdict (read this first)

**The 10 nearest past days do not say which way today goes.** By five things known at 09:20 - the gap, India VIX, the
previous day's range, expiry day, event day - the nearest 10 earlier sessions forecast the rest-of-day return WORSE than
simply the average of all earlier sessions, in all four checks (skill -11% and -7% BANKNIFTY design / holdout, -6% and
-5% NIFTY). R16 found the same with a richer 10:15 match.

On the size of the rest of the day the result is mixed: no skill on BANKNIFTY design (-1%), +5% on NIFTY design (p = 0.03,
BH q = 0.12: not a pass), and +16-21% in both holdouts. The holdout gain comes mostly from VIX: the holdout year was
quieter than the design year, and the neighbours (matched on VIX) follow that while the all-days average does not. R16
already showed VIX itself is the forecast of the day's size; this adds nothing to it. **Verdict: rejected as a forecast**
(no design pass). Shown as context only.

| | target | design n / skill / p | holdout n / skill / p |
|---|---|---|---|
| BANKNIFTY | rest-of-day range from 09:20 | 213 / -1.1% / 0.67 | 244 / +20.5% / <0.001 |
| BANKNIFTY | rest-of-day return from 09:20 | 213 / -10.8% / 1.00 | 244 / -6.6% / 0.99 |
| NIFTY | rest-of-day range | 214 / +5.4% / 0.03 (q 0.12) | 218 / +15.5% / <0.001 |
| NIFTY | rest-of-day return | 214 / -5.5% / 0.99 | 218 / -4.6% / 0.96 |

Skill = 1 - (mean absolute error of the neighbours' mean / of the all-days mean); one-sided paired t; BH over the 4.

## What the app shows

In the morning brief, after 09:20: "Days like today (context, not a forecast): 10 past sessions with a similar gap, VIX,
previous-day range, expiry and event. After 09:20 they moved a median ±x% (from a to b); n closed up. ORB on those days:
Rs ... (k days with a trade)." The past days come from the phone's own candles and, for older history, from the small
table `research/context/results/days_<UND>.csv` shipped in the app (one row a session: the five features, what happened
next, and the research replay's ORB / ORB Fresh net after costs for BANKNIFTY). Every line carries its sample size.
