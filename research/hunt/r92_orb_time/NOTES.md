# R92 notes (dated, each before the numbers it affects)

## N1 (21 Oct 2026, after the design run, BEFORE the holdout read)

1. **Design result: 0 of 257 cells pass.** Only 4 cells have a design net above zero (ORB FIRST 13:30-13:59 +Rs 10,176
   on 114 trades; ORB FIRST 14:00-14:30 EXT +Rs 5,591 on 119, twice: see 2; ORB 14:00-14:30 EXT on expiry days +Rs 147 on
   568). None has q below 1.00. The holdout is still read once, as pre-registered, for the time map.
2. **Cells identical by construction (pre-registered as listed, kept in the m = 257 BH family; this makes BH slightly
   conservative, it changes no verdict):** the hour 14:00-14:30 and the 30-minute slot 14:00-14:30 are the same slot
   (ANY and FIRST, every arm); Range Fade's hour 10 and its 10:30-10:59 slot are the same (Fade has no entry before
   10:35). Their twin p's differ only by the random draws.
3. **The twins for late slots carry hindsight.** A twin keeps the real trade's side and draws a random OTHER minute of
   the window, which for a 12:00-14:30 ORB trade is usually EARLIER, i.e. before the break whose direction it already
   knows. So the ORB twins of late slots make money (12 h twin mean +Rs 61,502; 13 h +Rs 2,06,892) while the real ORB
   loses: twin p near 1 there means "entering after the break is worse than a same-direction entry before it", which R3
   already saw. It is not a tradable rule. The money gates (G1, G2) decide; the twin p is a secondary check.
4. **Reproduction of R3's hour row (not a test; R3 already read these data):** app ORB trades by entry hour on BANKNIFTY +
   NIFTY, 1 Aug 2021 - 31 Mar 2026, dated charges, lots from the data: 10 h Rs -112 a trade (1,295), 11 h -77 (459),
   **12 h -26 (276)**, 13 h -100 (203). R3 (to 12 Oct 2026, today's charges, the calendar lots): -121 / -84 / **+28
   (311)** / -66. So R3's 12 h lead is NOT positive on the design years. For R3's +Rs 8.7k on 311 to hold, its 35 trades
   of 1 Apr - 12 Oct 2026 would have to make about +Rs 15.8k, which the holdout read will show (descriptive line,
   BANKNIFTY + NIFTY, ATTR 12 h). Prediction written here before the read: R3's lead came mostly from Apr 2026 (R49:
   ORB made +Rs 23,944 in April 2026 alone).
