# R96 implementation notes (dated; each written before the result it could affect)

- **N1 (21 Oct, before any rule was run): strike choice.** PREREG s3 says "1-ITM (call ATM-1, put ATM+1)". On the
  thin metal books (GOLDM, SILVERM) the lake's offset -> strike map is forward-filled within the day and can repeat a
  strike across offsets (checked on one minute: GOLDM offsets -1, 0, +1 all 160000). So the engine picks strikes from
  the SET of strikes stored at that minute (both sides, all offsets): ATM = the stored strike nearest the future,
  1-ITM = the next stored strike below (call) / above (put). Same intent, no offset reading.
- **N2 (21 Oct, before any rule was run): the 2x-spread gate re-costs the same trades.** Exits are simulated once (at
  1x costs, which set the breakeven lock); the 2x number re-prices each trade's fills with twice the half-spreads.
- **N3 (21 Oct, before any rule was run): straddle breakeven** = combined fill + one round trip of costs on the
  combined premium + one extra Rs 40 brokerage (two legs), per unit.
- **N4 (21 Oct, round 2, before any round-2 rule was run): the Dhan token in this environment is expired** (HTTP 401
  DH-901 on one test request for CRUDEOILM ATM+8), so ATM+/-10 strikes could not be fetched; round 2 uses the edge
  take-profit instead (PREREG_ROUND2 s2). When the edge minute has no print, the position is sold at the OPEN of the
  contract's next printed minute (the first real price available).
- **N5 (21 Oct, round 2, before any run): edge strikes come from the held side's stored strikes** (as the round-1
  window check), and the edge check runs after the stop / lock and underlying-stop checks of the same minute.
