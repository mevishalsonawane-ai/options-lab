# R118 pre-registration: an atlas of every option on every expiry day (descriptive only)

Written 27 Oct 2026, **before any R118 number was computed** (pushed alone on `claude/r117-zero-hero-lock`, the session's
branch). Boss, 27 Oct: *"Deep analysis and deep drive on each and every option on expiry date for all the data we have,
look into each and every single parameters, find a pattern, dont directly write strategy, but analysis all the basic."*

**Descriptive only.** No entry, no exit, no P&L, no strategy. Nothing in `android/**` changes. Nothing here is a tested
trading idea, so it is not counted in Benjamini-Hochberg; a later idea built on a pattern from here must be
pre-registered and tested on its own, and must count R118's cells as looks already taken.

**Signal source:** none (no rules). The atlas reads both the underlying (index minutes) and the options' own prints, and
says which in every table.

## Data

- R98's expiry-day records (`research/hunt/r98/r98data.py`, lake copied 27 Oct, to 19 Oct 2026): NIFTY 273, BANKNIFTY
  195, FINNIFTY 167, MIDCPNIFTY 100, SENSEX 176 = 911 expiry days; every stored strike (ATM±10 at each minute), calls and
  puts, 1-minute open / high / low / close / volume / OI / vendor IV; index and India VIX minutes. R25's same-minute
  bad-print clean is kept.
- For the "expiry vs normal day" comparison: the lake's index minutes on every session of the same years.
- 27 Oct 2026 is not in the lake.

## What is measured (fixed now)

Times are the bar that STARTS at that minute. "em" = the ATM straddle (call + put at the strike nearest spot), the
market's own priced move for the rest of the day. Distance is measured in strikes, in % of spot and in em.

A. **Census and data quality:** strikes and option-minutes per day; share of minutes with no print by distance and time;
   the bad-print clean; the vendor IV column's coverage; put-call parity at the ATM strike (synthetic forward vs spot).
B. **Time-value decay:** the ATM straddle in % of spot through the day, and as a share of its 09:30 value; OTM premium
   through the day by distance; ITM time value.
C. **Priced vs realised move:** the straddle at each mark against the index's later move (close-to-15:29 and the
   best one-way move), by time, index, year.
D. **Implied volatility:** ATM IV through the day; the smile (IV / ATM IV) by distance in em, calls vs puts.
E. **Volume and OI:** volume by minute, by distance and by premium bucket; where the most-traded strike sits; OI by
   distance, the call wall and put wall, OI change through the day; PCR (OI and volume).
F. **Pinning:** the index's 15:29 close against the nearest strike, the call / put walls and max pain (from the stored
   ATM±10 chain only) at 09:30 and 12:00, against a placebo.
G. **The index on expiry vs normal days:** range, time of the day's high / low, last-hour move, trend vs reversal.
H. **Option paths:** from each 15-minute mark 09:30-15:15, by premium bucket x distance x time: the share reaching
   2x / 5x / 10x later, the share ending under Rs 0.10, the time of the best close; the empirical delta and leverage
   (option % move per index % move) by distance and time.
I. **Calls vs puts:** price at the same distance; up vs down moves.
J. **Conditions:** index, weekday, weekly vs monthly, year, previous-close VIX bucket, opening gap bucket, for the main
   measures above.

## What counts as a "pattern" (fixed now)

- Every number carries its n (days, and options or option-marks).
- A pattern is reported as **stable** only if it has the same direction in both halves, 2021-23 and 2024-26, with
  at least 30 days in each half. Otherwise it is "seen once" or "noise".
- With this many cells, some will look striking by chance. The write-up says so, and lists the stable ones first.
- Lookups are point-in-time where it matters: anything "at mark t" uses data up to t only; anything after t is labelled
  as an outcome. A test checks this.
