# R132: the R131 ICT entries with stop-losses, profit locks, and R123's neural network

29 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss, after R131: *"Can we enhance it and also
try with profit lock and stoploss"*, then *"Also you can use the neuro network created in r123 session"*.

## The answer: 0 of 60 pass. One lead (CISD + the network) is worth a forward test, if Boss wants one

1. **Stop-losses and profit locks do not rescue the ICT entries.** Each of the ten as-stated entries was run with four
   exits: a premium stop of -Rs 1,000 or -Rs 2,000 with a 60% lock after +Rs 1,000; R131's index stop with the same
   lock; and R131's index stop with a move to breakeven at 1R, then a 1R trail.
   - **35 of the 40 lose money in design** (Aug 2021 - 15 Mar 2026).
   - The 5 that make money fail other gates. The best is CISD with the breakeven trail (E4): 2,763 trades, +Rs 43 a
     trade (+Rs 22 at 2x slippage), 3 of 4 folds positive, q 0.04. But its drawdown is -Rs 2.63 lakh and its worst
     month -Rs 1.03 lakh, far past Boss's account stop.
   - The exits change *how* a trade loses (more small stops, or more trades held to 15:20), not *whether* it wins.
     The entries are a coin flip, and no exit makes a coin flip pay.
2. **R123's network is the one thing that helped.** It was used as a filter: take the R131 trade only when the
   network's out-of-sample view, from the 5 minutes before the entry, expects that option to make money (N-EV).
   - In 2023 - Mar 2026 it raised the net per trade for 9 of the 10 entries. For 5 of them (CISD, AMD, IFVG, Turtle
     Soup, Breaker) the kept trades beat random subsets of the same trades (p < 0.05).
   - **CISD + N-EV (S3-V1-NEV) passed every design gate:**
     - 305 trades, 64% won, **+Rs 276 a trade** (+Rs 261 at 2x slippage), +Rs 84k on 1 lot;
     - all 4 folds positive;
     - drawdown -Rs 15.6k and worst month -Rs 11.3k (inside Boss's limits);
     - better than random subsets (p 0.006, BH q 0.04 over 60).
   - The same entries without the filter made Rs 0 a trade (2,450 trades).
   - **But it fails the last check.** On the holdout period it made +Rs 205 a trade, on only **21 trades** (38% won,
     +Rs 4,315), against the 50 needed.
   - Also, 284 of its 305 design trades are in 2023-24. The network says "positive" much less often from 2025 (21
     trades in 2025 - Mar 2026). The good years may be the network's, not the setup's.
3. **The network's direction (N-DIR) did not help.** 2 of 10 are positive in design (Cameron +Rs 14, CISD +Rs 31 a trade), and neither passes.
   This agrees with R123: the network's direction is a coin.

**Verdict.** Nothing passes, so nothing goes on paper or live, and nothing changes in the app (CLAUDE.md: failed ideas
stay notes). The lead is **CISD + the network's EV filter**. The data that could test it fairly is not here yet, because
the lake ends on 19 Oct and the holdout was already used. If Boss wants it, the honest next step is a
pre-registered **forward** read from today: log its signals, decide after a set number of trades, and only then think
about paper.

## The numbers

**Design: net per trade in Rs (trades)**

| entry | E0 R131 exits | E1 -1k stop + lock | E2 -2k stop + lock | E3 index stop + lock | E4 breakeven + trail | N-EV filter* | N-DIR filter* |
|---|---|---|---|---|---|---|---|
| S1 Silver Bullet | -166 (920) | -90 (974) | -183 (967) | -210 (934) | +38 (904) | -261 (94) | -167 (353) |
| S2 Cameron | -44 (1,254) | -133 (1,407) | -120 (1,323) | -45 (1,304) | -121 (1,174) | +50 (149) | +14 (443) |
| S3 CISD | -28 (3,082) | -35 (3,375) | -7 (3,183) | +12 (3,122) | +43 (2,763) | +276 (305) | +31 (1,231) |
| S4 CRT | -116 (412) | -82 (418) | -46 (414) | -75 (410) | +41 (406) | +104 (48) | -108 (160) |
| S5 AMD | -165 (5,739) | -125 (5,854) | -167 (5,047) | -132 (5,561) | -130 (5,098) | -47 (450) | -137 (2,180) |
| S6 IFVG | -81 (14,952) | -104 (13,795) | -118 (11,108) | -78 (16,810) | -92 (17,322) | +38 (1,191) | -69 (5,688) |
| S7 Turtle Soup | -165 (9,568) | -141 (11,294) | -166 (9,499) | -151 (10,260) | -153 (8,088) | +81 (924) | -133 (3,577) |
| S8 Order Block | -97 (5,257) | -108 (5,557) | -131 (5,124) | -99 (5,683) | -92 (5,505) | +30 (441) | -85 (2,017) |
| S9 Breaker | -90 (2,498) | -19 (2,539) | +26 (2,431) | -15 (2,554) | -57 (2,507) | +144 (268) | -11 (1,092) |
| S10 OTE | -161 (4,412) | -94 (4,698) | -141 (4,452) | -98 (4,659) | -117 (4,423) | -83 (336) | -83 (1,902) |

\* the filters are on 2023 - 15 Mar 2026 only (where the network's out-of-sample views exist), with R131's exits.

**Holdout period (Apr - Oct 2026, already seen for these entries): net per trade in Rs (trades)**

| entry | E0 R131 exits | E1 | E2 | E3 | E4 | N-EV | N-DIR |
|---|---|---|---|---|---|---|---|
| S1 Silver Bullet | +36 (122) | -147 (127) | -273 (126) | -105 (122) | -171 (120) | -868 (8) | -23 (61) |
| S2 Cameron | +61 (135) | -83 (157) | -203 (148) | -7 (144) | +591 (131) | +126 (10) | +173 (77) |
| S3 CISD | -234 (432) | +120 (489) | -3 (476) | -2 (455) | +74 (390) | +205 (21) | -215 (245) |
| S4 CRT | -296 (63) | -178 (64) | -311 (63) | -509 (63) | -832 (63) | -1,072 (4) | -1,012 (22) |
| S5 AMD | -126 (815) | -86 (852) | -116 (743) | -92 (800) | +97 (725) | +39 (20) | -126 (412) |
| S6 IFVG | -198 (1,992) | -118 (1,954) | -144 (1,563) | -104 (2,281) | -125 (2,311) | -292 (48) | -234 (954) |
| S7 Turtle Soup | -207 (1,223) | -166 (1,559) | -237 (1,302) | -192 (1,372) | -209 (1,114) | +85 (47) | -191 (601) |
| S8 Order Block | -211 (705) | -223 (773) | -384 (735) | -200 (776) | -174 (739) | -162 (36) | -146 (370) |
| S9 Breaker | -11 (329) | -122 (330) | -290 (317) | -155 (332) | +42 (320) | +768 (5) | -104 (185) |
| S10 OTE | -284 (581) | -130 (609) | -183 (587) | -192 (601) | -112 (585) | +351 (15) | -282 (303) |


## How it was done

- **Pre-registration:** `research/hunt/r132_ict_exits/PREREG.md`, committed before any R132 number. Amendment A (the
  network filter) was also added before any R132 number. The design results were committed before the one read of the
  holdout period.
- **Entries:** R131's ten as-stated V1 entries, unchanged. Signals come from the index spot, the trade buys the ATM
  option, and the R131 entry filters are kept.
- **Exits, wick to wick (Boss's 29 Oct note):**
  - Premium stops and the lock trigger on the option's minute low. The lock's peak uses earlier minutes' highs only.
    A gap fills at the open, with stop slippage and dated charges.
  - Index stops trigger on the index's wicks.
  - If two exits trigger in the same minute, the worse one counts. Everything is out by 15:20.
- **Exit test:** 100 random entries per trade, after the signal, on the same day and side, **with the same exit**.
  This shows whether a gain comes from the entry or only from the exit.
- **Network:** R123's single network (N1) and its walk-forward predictions. Each fold's model was trained on earlier
  data only, and each trade uses the latest view at least 1 minute before its entry.
  - The test: is the kept set better than 2,000 random subsets of the same trades, with the same count in each fold?
- **Gates:**
  - design: ≥ 300 trades; net > 0 at 1x and 2x slippage; ≥ 3 of 4 folds positive; BH q ≤ 0.10 over all 60;
    drawdown above -Rs 40k and no month below -Rs 13k;
  - holdout period: positive on ≥ 50 trades.
- **Code and tests:** `research/hunt/r132_ict_exits/` (`r132.py`, `r132nn.py`, `test_r132.py`, 15 tests). They include
  a look-ahead guard: an exit at minute m is unchanged when every later minute is deleted.
- **Results:** `results/design.json`, `holdout_seen.json`, `nn_design.json`, `nn_holdout_seen.json`. Entries:
  `research/experiments/entries/r132-*.yaml` (60, all rejected).

## Notes

1. **The holdout period was not unseen.** R131 had read it for these entries the same day. R132 read it once more, as
   the pre-registration says, and only as a check.
2. **The network filter is the only change here not chosen in advance by Boss's video.** It came from Boss's request
   to use R123, before any R132 number was computed. It keeps 12% of CISD's trades, so its sample is small.
3. **The 65% rule is not applied (Boss's strategies).** For the record, CISD + N-EV won 64% in design and 38% on the
   21 holdout trades.
