# R93 implementation notes (each made before the result it could affect)

- **N1 (21 Oct, before any result): twins are drawn from entries that fill.** PREREG s7 says a twin is a random
  in-window bar of the same family, day and side. The GOLDM and SILVERM 1-ITM options print in only about a quarter of
  minutes, so many random bars cannot fill; the rule's own trades are all filled trades. Counting an unfilled twin as
  Rs 0 (R64's convention on liquid NSE books) would shrink the twins toward zero and bias the test. So each twin is
  drawn from the bars of that family, day and side whose entry fills; a day and side with no fillable bar adds 0.
  Same for daily twins (a session of the same year whose trade can be simulated).
- **N2 (21 Oct, before any result): daily roll rows are causal.** The data check in PREREG s1 kept the largest
  open-interest jump in any 15-day window; that uses later rows. The study marks a roll row when the jump is >= 1.5x and
  no roll was marked in the previous 15 calendar days (greedy, forward), decided at that row's close. Counts per
  family (2012 - Oct 2026): GOLD 89, SILVER 77, COPPER 122, ZINC 163, ALUMINIUM 161, CRUDEOIL 175, NATURALGAS 175
  (about 6, 5, 8-12, 11, 11, 12, 12 a year, matching the exchange calendar). Exiting at the close before a roll row
  uses the public expiry calendar, not the open interest of the next day.
- **N3 (21 Oct, before any result): futures twins' stop.** A futures trade's stop is the signal bar's channel mid. A
  random twin bar is not a break, so its own mid can sit on the wrong side of its entry; the twin's stop is instead
  half the channel width from the twin bar's close (the distance a fresh break has to its mid).
- **N4: zinc's option book shows no expiry jump in April 2026** (13 detected expiries vs 14 on the others), so one zinc
  contract change in April may not blank the channel for its first 20 bars. One day of zinc signals; noted, not fixed.
