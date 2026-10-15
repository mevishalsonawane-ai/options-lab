# Market context (TODO I6, I10, I11, I12): pre-registration

Written 13 Oct 2026, BEFORE any test below was run. Only `data.py` had run (it builds per-session index, VIX and
ATM-option minutes; no outcome was looked at beyond row counts). Nothing here was chosen from results.

## Data and periods (all four studies)
- Source: `config.SOURCE` (today the GitHub release `research-data`: BANKNIFTY 13 Feb 2024 - 23 Feb 2026, 498 sessions;
  NIFTY 1 Apr 2024 - 13 Apr 2026, 499 sessions; India VIX). One switch re-runs everything on the Dhan lake later.
- DESIGN: every session before the holdout. HOLDOUT: the last 12 months of each underlying (BANKNIFTY from 23 Feb 2025,
  NIFTY from 13 Apr 2025), read ONCE per idea, by the same script run that reports design (no re-runs with changes; a
  change after the holdout read needs a new entry).
- Costs wherever a trade is simulated: the app's paper fills (+-5 bps, stops -10 bps) plus a modelled half-spread (1 tick,
  2-4 when thin; `research/obuy/costs.py Fills('liq')`) and SandboxCosts charges (`Costs('app')`: Rs 20 brokerage an
  order, STT 0.15% of the sell, exchange 0.03553%, SEBI, stamp, GST 18%). 1 lot, lot size by date.
- Multiple testing: Benjamini-Hochberg within each study's family at q < 0.10, counting every test listed here.

## I6 Event shield (family calendar-events)
Event sessions: the Indian session that first trades after the event (RBI 10:00 and Budget 11:00: that day; US CPI and
FOMC, released after the Indian close: the next session; heavyweight results - HDFC Bank, ICICI Bank for BANKNIFTY,
HDFC Bank, ICICI Bank, Reliance for NIFTY - announced after hours or on Saturdays: the next session). Normal days: no event
of any kind, not an expiry day of the charted index, not the session before an event.
Metrics per session: day range % of previous close, |gap| %, |open-to-close| %, first-hour range % of the open.
- Tests E1-E4 per index (8): for each event kind (RBI, Budget+RBI pooled as "India policy", US CPI+FOMC pooled as "US
  macro", results), is the median day range larger than on normal days? One-sided Mann-Whitney U on DESIGN; the holdout
  confirms a passing one with the same test (p < 0.10, same sign). Budget alone has 4 sessions: described, not tested.
- Also described (no test): |gap|, |open-to-close|, first-hour range, and the session BEFORE each event against normal days.
- Arm check E5 (BANKNIFTY ORB and ORB Fresh as the app runs them, research replay): net P&L per day after costs on event
  sessions vs normal days, and the "pause paper arms on event days" counterfactual (all days minus event days). One-sided
  Welch t on the per-day net difference, BH with E1-E4. A pause can only REMOVE trades: the switch ships OFF and stays
  shadow-first whatever this says.

## I10 Expiry-day playbook (family option-structure)
Expiry sessions with the expiring contract in the data (BANKNIFTY monthly from Feb 2025, NIFTY weekly/monthly).
Described (no test): ATM straddle (strike from 09:20) value at 09:20, 10:00, 11:00, 12:00, 13:00, 14:00, 14:30, 15:00,
15:20 as % of its 09:20 value; the share lost per hour; the last-hour index move (14:30-15:25) vs the same window on
non-expiry days; max pain at 09:20 (from that morning's OI) and the close's distance to it vs the distance to the open
(R16 already found max pain is NOT a forecast; this is a re-check, no claim); pin: the close within half a strike step
of the 09:20 max-OI call or put strike vs any strike.
- Tests X1-X4 (per index, 8): a short ATM straddle sold at 10:00 / 12:00 / 13:30 / 14:30 and bought back at 15:20 (no
  stop), after costs, mean net per expiry > 0 (one-sided t). BH over the 8. Selling options is NOT what the app's arms
  do; a pass is information for the Hero arm and expiry exits only, never a new arm without its own paper run.

## I11 Days like today (family market-facts)
Features known at 09:20: gap %, VIX at 09:20, previous day's range %, expiry day (0/1), event day (0/1). Nearest 10 past
DESIGN-or-earlier sessions (standardised Euclidean, expiry/event as 0/1 x 1 SD), strictly earlier days only.
- Tests A1-A2 per index (4): does the neighbours' mean of (A1) the rest-of-day range from 09:20 and (A2) the rest-of-day
  return from 09:20 forecast better than the mean of ALL earlier sessions? Mean absolute error, one-sided paired t on
  the per-day error difference, walk-forward (every day uses only earlier days; design days then holdout days).
  R16 found analogs worse than pooled history at 10:15; we expect the same. Whatever the result, the app labels it
  "context, not a forecast".

## I12 Level map (family patterns-indicators)
Levels known before the session: previous day high / low / close; classic pivot P, R1, S1, R2, S2; the nearest swing
high above and swing low below the open (5-session 5-minute pivots); the 09:20 max-OI call and put strikes (option OI
from that morning). A level is TESTED on a day when the index comes within 0.05% of it; it HELD when no 5-minute close
after the first touch went beyond it by more than 0.05% for the rest of the session (support from above, resistance
from below, by the side the index came from).
- Tests L1-L10 (per index, 20): for each level type, is the held share higher than for placebo levels (the same level
  moved by a random 0.3-0.7% the same side, 5 placebos a day)? Two-proportion z on DESIGN, holdout confirms. The app
  shows "held N of M times" for every type whatever the test says; the test only says whether the count means more
  than chance.
