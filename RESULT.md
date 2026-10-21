R99: LEADS

Boss asked to catch the 100+ rupee move. After gold (R97, R98: nothing), R99 tested 100 premium points on index
options (design 21 Nov 2024 - 31 Mar 2026; R51's engine with real prints and charges). 0 of 3 pass; the final period
was not opened.

- On NIFTY, costs are small (about 1% of a +100 move) but buying the ATM option at a random moment for +100 loses
  Rs 161 a trade (42,334 moments): +100 comes 9.2% of the time, the -40 stop 29%. No index condition (0 of 218) and no
  machine-learning model (R99A: -Rs 43,085, 238 trades) fixes that.
- On Boss's Solo 2.0 trades (reproduced exactly: 166 trades, +Rs 55,910), a fixed +100 / -40 exit made it worse
  (-Rs 12,284). Letting the winners run (no target, lock +25 after +50, trail 40) made +Rs 89,242, i.e. +Rs 33,333
  more than Solo's own exits - the LEAD. Not proven: all of the gain came in Jan-Mar 2026, it was worse in Jul-Dec
  2025, bootstrap p 0.245. Suggested next step (needs Boss's go-ahead): a shadow log next to Solo's real exits.
- Cost check (Boss's question): MCX charge rates are lower than NSE's, but GOLDM options' measured bid-ask spread
  (~1% of premium) is ~10x NIFTY's; even with charges only and no spread every gold rule stays negative.

Write-ups: research/HUNT_R99_NIFTY100.md, research/HUNT_R98_CATCH100.md, research/HUNT_R97_GOLD_REVERSE.md.
Nothing goes to paper or live.

---

R98: NOTHING

Boss asked to catch gold's 100+ point (Rs 100 per 10 g) move in any condition. R98 studied 73,432 moments on the MCX
gold future (design year 5 Aug 2025 - 14 Jul 2026), 254 candle / indicator / market / clock conditions, a
walk-forward machine-learning model, and 6 pre-registered rules on the GOLDM future and option. 0 of 6 pass; the
locked holdout (15 Jul - 19 Oct 2026) was not opened.

- A 100-point move comes in most hours, but in a coin-flip direction (+100 first 46.8%, -100 first 46.8%).
- Some conditions really tilt it: gold snaps back after a stretch, and MCX follows XAUUSD when international gold
  runs ahead (the afternoon version beat random entries, p = 0.01, 612 trades).
- The tilt is worth about Rs 120-170 a GOLDM lot per trade; Zerodha's statutory charges on one GOLDM futures round
  trip are about Rs 290 (CTT and exchange fees on a ~Rs 14 lakh lot), before spread and stop slippage. Every rule
  lost even if every stop and target were filled exactly at its level with zero spread.
- Options are worse here: they keep under half of a 100-point move and cost about the same.

Earlier the same day, R97 (gold's 200-300 point moves, 7 rules): also NOTHING.
Write-ups: research/HUNT_R98_CATCH100.md, research/HUNT_R97_GOLD_REVERSE.md. Nothing goes to paper or live.
