# RESULT: R92, is there a time when the ORB family makes money? (21 Oct 2026)

**No.** 0 of 257 pre-registered time and day cells pass. ORB, ORB Fresh, ORB Sweep and Range Fade stay paused, and no
time filter goes to paper (so there is no APP_SPEC).

- **Every hour loses, for every arm.** Design (Aug 2021 - Mar 2026, 5 indices), Rs a trade after real costs:
  - ORB: -73 / -53 / -52 / -103 / -59 for the 10 to 14 h entries;
  - ORB Fresh: -47 to -97;
  - ORB Sweep: -115 to -200;
  - Range Fade: -117 to -154.
- **The other slices lose too:** half-hour and quarter-hour slots, range length 15-75 minutes, exit at 13:30 / 14:30 /
  15:10, weekday, expiry and pre-expiry days, start or end of month. The best money p is 0.13; every BH q is 1.00.
- **R3's 12:00 hint was the last six months on BANKNIFTY / NIFTY** (35 trades, +Rs 12,391). It lost on FINNIFTY,
  MIDCPNIFTY and SENSEX (Rs -59 a trade, 260 trades) and on the older years.
- **Least bad:** the day's first ORB signal at 13:30-13:59. Design +Rs 10,176 on 114 trades, holdout +Rs 5,475 on 19.
  It is not an edge: q 1.00, twin p 0.09, mostly FINNIFTY, and negative without its 5 best trades.
- **Walk-forward** (best cell so far, traded next year): Rs -27,595 on 186 trades.
- **Worst month:** app ORB on all 5 indices, Rs -38,150 (Feb 2025).

Details: `research/HUNT_R92_ORB_TIME.md`; code and tables: `research/hunt/r92_orb_time/`. Research only, no app code
changed.
