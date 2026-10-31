# R142: R141's leads tested. One exit passes on R52; the bad-break filters look good but are not proven

31 Oct 2026. Research only. **Nothing in the app or any arm has changed** (Boss: *"dont push update strategies without
taking my approval"*). Pre-registration: `research/hunt/r142_bad_break_noprogress/PREREG.md`, pushed before any
number.

**What was tested (22 tests):**
- **"Bad-break" filters** on R72, R51 and Liquidity: skip breaks after an earlier fake poke, with a weak push or a
  quiet signal bar, or mid-morning.
- **A "no progress" exit** on R72, R51 and R52: sell if the option has not risen X% within N minutes.

**The gates (R140's, about losing less):** in design, the walk-forward year and the final months (Apr - 13 Oct 2026):
- the losing days add up to less;
- the drawdown, worst day and worst month are no worse;
- the arm still makes money;
- the loss cut beats random skipping or random exit times, and passes BH.

## The answer

**1. One rule passes every gate: NP5-52, a 5-minute "no progress" exit on R52 Open outside value.**
- **The rule:** R52 buys at 09:20. If the option has not risen 5% by 09:25, sell it then. Otherwise R52 runs as today.
- **Final months, against today's R52:**
  - losing days: -Rs 1.33 lakh instead of -Rs 2.23 lakh (**41% less**);
  - drawdown: Rs 31,482 instead of Rs 47,194;
  - worst day: -Rs 7,752 instead of -Rs 16,554;
  - worst month: **-Rs 7,897 instead of -Rs 17,113**, inside Boss's -Rs 13k monthly stop;
  - average loss per losing trade: Rs 995 instead of Rs 1,893 (about half).
- **It held in design and the walk-forward year too.**
- Random sell times did this well 0 times in 2,000 (p 0.0001, q 0.0008).
- **The cost is profit, and the win rate goes down, not up:**
  - final profit Rs 37,510 instead of Rs 48,612;
  - design profit Rs 1.10 lakh instead of Rs 2.91 lakh;
  - wins 42% instead of 52-56%.
  - Selling early turns some trades that would have recovered into small losses. It **does** cut the big losses,
    which is what Boss asked for.
- **Caveat:** the final months were not fresh. R141 looked at them to find "trades that never worked", though the 5
  minutes and 5% were fixed before any R142 number. So this earns a **forward log on new days first**.

**2. The bad-break filters cut losses a lot and raise the win rate, but they do not beat random skipping.**
- The best was **BBX-51**: on R51 Donchian 20 + Supertrend, skip a break if any of earlier fake poke, weak push or
  quiet signal bar. In the final months:
  - profit: **Rs 2.73 lakh instead of Rs 1.35 lakh**;
  - losing days: -Rs 2.25 lakh instead of -Rs 3.96 lakh;
  - drawdown: Rs 24,487 instead of Rs 81,683;
  - no losing month (+Rs 2,644 worst, against -Rs 54,587);
  - wins: 50% instead of 45%;
  - 760 trades instead of 1,670.
  - It also held in design and the walk-forward year.
- **But it skips more than half the trades, and random skipping of that many also cuts a lot of loss.** It beat random
  only at p 0.12 (gate 0.05).
- And R141 used these same months to find these patterns. **So this is a strong lead, not a result.** The same is true
  on R72 (BBX-72: profit Rs 2.02 lakh vs Rs 1.71 lakh, losing days -Rs 2.00 lakh vs -Rs 3.90 lakh, p 0.60).
- **On Liquidity** the filters did not help (p 0.37-0.94).

**3. The "no progress" exit hurts the Donchian arms (R72, R51).** Their slow starters include the big winners: final
profit fell, and on R51 with 5 minutes the design profit went to zero. Mid-morning skipping on R51 (T11-51) also
failed.

## "How could we make these losing trades win?" (Boss)

Straight answer:
- **A losing trade cannot be turned into a winning trade after the fact.** About half of every arm's trades lose.
- The arms make money only because their winners are bigger.
- **The win rate does not go much above 50%** with any rule tried, so none of these reaches Boss's 65%.

There are only **three levers**, and R139-R142 tested all of them:
1. **Do not take the likely losers (skip filters):**
   - this raises the win rate a few points (R51 45% to 50%, R72 46% to 50% with BBX) and cuts losses, by trading less;
   - R140's wide-day filter on R72 passed;
   - the bad-break filters look good but are not yet proven better than random skipping.
2. **Get out of losers early (exits):**
   - on R52 a 5-minute no-progress exit halves the average loss and passed every gate;
   - on R72 and R51 it hurts.
   - This **lowers** the win rate (more small losses), but the losses are smaller.
3. **Keep profit from turning into a loss (locks):**
   - R105 tested 20 on R72 / R51: none passed;
   - R139's 60% lock reached a 68% win rate but lost money.

**The honest way to a higher win rate is lever 1 with the R140 / R142 filters**, then confirming them on new days.

## Every test (1 lot; "losing days" = the sum of all losing days)

| test | arm | rule | final: trades, win, net, losing days, drawdown, worst month | design: net, losing days | walk-forward: net, losing days | p | result |
|---|---|---|---|---|---|---|---|
| *today* | R72 | as it runs now | 1,202, 46%, Rs 171,366, Rs -389,644, Rs 61,884, Rs -40,908 | Rs 216,929, Rs -1,154,058 | Rs 335,882, Rs -708,002 | | |
| BB1-72 | R72 | skip after an earlier fake poke today | 673, 47%, Rs 180,553, Rs -264,655, Rs 52,328, Rs -16,954 | Rs 310,952, Rs -711,322 | Rs 285,804, Rs -480,297 | 0.691 | fails: twin p 0.691 >= 0.05; q 1 > 0.1 |
| BB2-72 | R72 | skip a weak push into the entry (lowest 2 fifths) | 948, 48%, Rs 196,476, Rs -295,437, Rs 39,889, Rs -29,123 | Rs 382,229, Rs -846,061 | Rs 355,527, Rs -563,454 | 0.409 | fails: final: worst day worse; twin p 0.409 >= 0.05; q 1 > 0.1 |
| BB3-72 | R72 | skip a quiet signal bar (lowest fifth of range / ATR) | 1,077, 46%, Rs 216,884, Rs -362,331, Rs 84,379, Rs -33,457 | Rs 206,822, Rs -1,037,820 | Rs 245,927, Rs -660,882 | 0.942 | fails: wf: drawdown worse; wf: worst day worse; final: drawdown worse; final: worst day worse; twin p 0.942 >= 0.05; q 1 > 0.1 |
| BBX-72 | R72 | skip if any of BB1-BB3 | 536, 50%, Rs 201,665, Rs -199,554, Rs 45,201, Rs -12,324 | Rs 291,959, Rs -564,394 | Rs 227,689, Rs -397,031 | 0.601 | fails: twin p 0.601 >= 0.05; q 1 > 0.1 |
| NP5-72 | R72 | sell if the option is not up 5% within 5 min | 1,247, 36%, Rs -56,360, Rs -302,654, Rs 89,395, Rs -38,707 | Rs 77,665, Rs -815,441 | Rs 165,841, Rs -486,404 | 0.781 | fails: final: drawdown worse; final: the arm no longer makes money; twin p 0.781 >= 0.05; q 1 > 0.1 |
| NP10-72 | R72 | sell if the option is not up 5% within 10 min | 1,225, 39%, Rs 38,242, Rs -329,991, Rs 60,693, Rs -26,720 | Rs 179,329, Rs -923,325 | Rs 274,612, Rs -544,440 | 0.982 | fails: design: worst month worse; twin p 0.982 >= 0.05; q 1 > 0.1 |
| NP15-72 | R72 | sell if the option is not up 10% within 15 min | 1,223, 42%, Rs 57,543, Rs -376,745, Rs 108,352, Rs -46,491 | Rs 140,992, Rs -967,061 | Rs 255,734, Rs -582,138 | 1.000 | fails: design: drawdown worse; final: drawdown worse; final: worst day worse; final: worst month worse; twin p 1 >= 0.05; q 1 > 0.1 |
| *today* | R51 | as it runs now | 1,670, 45%, Rs 134,743, Rs -395,932, Rs 81,683, Rs -54,587 | Rs 50,425, Rs -1,346,619 | Rs 213,748, Rs -851,416 | | |
| BB1-51 | R51 | skip after an earlier fake poke today | 976, 47%, Rs 215,279, Rs -284,249, Rs 52,155, Rs -32,600 | Rs 290,428, Rs -963,129 | Rs 277,133, Rs -656,863 | 0.137 | fails: twin p 0.137 >= 0.05; q 0.668 > 0.1 |
| BB2-51 | R51 | skip a weak push into the entry (lowest 2 fifths) | 1,390, 45%, Rs 193,120, Rs -320,536, Rs 39,756, Rs -11,135 | Rs 233,142, Rs -1,099,675 | Rs 280,763, Rs -697,679 | 0.152 | fails: twin p 0.152 >= 0.05; q 0.668 > 0.1 |
| BB3-51 | R51 | skip a quiet signal bar (lowest fifth of range / ATR) | 1,545, 46%, Rs 185,836, Rs -377,759, Rs 73,151, Rs -53,757 | Rs 92,597, Rs -1,259,785 | Rs 218,276, Rs -786,289 | 0.731 | fails: wf: worst day worse; wf: worst month worse; twin p 0.731 >= 0.05; q 1 > 0.1 |
| BBX-51 | R51 | skip if any of BB1-BB3 | 760, 50%, Rs 273,070, Rs -225,448, Rs 24,487, Rs 2,644 | Rs 292,214, Rs -802,901 | Rs 271,138, Rs -542,276 | 0.120 | fails: twin p 0.12 >= 0.05; q 0.668 > 0.1 |
| T11-51 | R51 | skip entries 10:30-12:00 | 1,545, 46%, Rs 180,581, Rs -371,601, Rs 60,541, Rs -19,957 | Rs 121,877, Rs -1,237,379 | Rs 248,702, Rs -807,790 | 0.434 | fails: design: worst month worse; wf: drawdown worse; wf: worst month worse; final: worst day worse; twin p 0.434 >= 0.05; q 1 > 0.1 |
| NP5-51 | R51 | sell if the option is not up 5% within 5 min | 1,723, 38%, Rs 3,820, Rs -301,525, Rs 55,620, Rs -17,801 | Rs -18, Rs -991,587 | Rs 102,238, Rs -609,554 | 0.679 | fails: design: the arm no longer makes money; twin p 0.679 >= 0.05; q 1 > 0.1 |
| NP10-51 | R51 | sell if the option is not up 5% within 10 min | 1,700, 40%, Rs 30,986, Rs -347,353, Rs 75,298, Rs -26,283 | Rs 15,981, Rs -1,161,585 | Rs 143,484, Rs -720,086 | 0.996 | fails: wf: worst month worse; twin p 0.996 >= 0.05; q 1 > 0.1 |
| NP15-51 | R51 | sell if the option is not up 10% within 15 min | 1,698, 42%, Rs 35,692, Rs -375,955, Rs 73,951, Rs -24,255 | Rs 101,402, Rs -1,168,542 | Rs 261,137, Rs -686,056 | 1.000 | fails: design: drawdown worse; twin p 1 >= 0.05; q 1 > 0.1 |
| *today* | LIQ | as it runs now | 357, 37%, Rs 11,155, Rs -144,353, Rs 61,062, Rs -37,821 | Rs 257,249, Rs -289,743 | Rs 238,064, Rs -187,995 | | |
| BB1-L | LIQ | skip after an earlier fake poke today | 204, 40%, Rs 28,766, Rs -102,891, Rs 48,885, Rs -29,330 | Rs 233,246, Rs -153,332 | Rs 189,269, Rs -110,927 | 0.849 | fails: final: worst day worse; twin p 0.849 >= 0.05; q 1 > 0.1 |
| BB2-L | LIQ | skip a weak push into the entry (lowest 2 fifths) | 212, 41%, Rs 34,730, Rs -108,213, Rs 42,982, Rs -23,753 | Rs 218,271, Rs -202,979 | Rs 204,756, Rs -130,245 | 0.848 | fails: twin p 0.848 >= 0.05; q 1 > 0.1 |
| BB3-L | LIQ | skip a quiet signal bar (lowest fifth of range / ATR) | 290, 39%, Rs 22,870, Rs -121,361, Rs 47,021, Rs -30,008 | Rs 239,519, Rs -258,171 | Rs 207,073, Rs -171,504 | 0.372 | fails: twin p 0.372 >= 0.05; q 1 > 0.1 |
| BBX-L | LIQ | skip if any of BB1-BB3 | 142, 43%, Rs 44,445, Rs -83,357, Rs 35,661, Rs -20,359 | Rs 191,198, Rs -133,304 | Rs 156,097, Rs -90,711 | 0.938 | fails: twin p 0.938 >= 0.05; q 1 > 0.1 |
| *today* | R52 | as it runs now | 293, 52%, Rs 48,612, Rs -223,320, Rs 47,194, Rs -17,113 | Rs 290,573, Rs -626,170 | Rs 247,057, Rs -427,746 | | |
| **NP5-52** | R52 | sell if the option is not up 5% within 5 min | 293, 42%, Rs 37,510, Rs -132,769, Rs 31,482, Rs -7,897 | Rs 109,749, Rs -442,924 | Rs 71,746, Rs -306,142 | 0.000 | **passes** |
| NP10-52 | R52 | sell if the option is not up 5% within 10 min | 293, 44%, Rs 72,545, Rs -140,172, Rs 25,739, Rs -6,752 | Rs 120,101, Rs -528,474 | Rs 98,725, Rs -368,831 | 0.000 | fails: design: drawdown worse; design: worst month worse; wf: drawdown worse; wf: worst month worse |
| NP15-52 | R52 | sell if the option is not up 10% within 15 min | 293, 46%, Rs 44,404, Rs -157,800, Rs 30,285, Rs -9,287 | Rs 133,317, Rs -503,992 | Rs 113,489, Rs -355,928 | 0.191 | fails: design: drawdown worse; design: worst day worse; design: worst month worse; wf: drawdown worse; wf: worst day worse; wf: worst month worse; twin p 0.191 >= 0.05; q 0.702 > 0.1 |

## What is proposed (not built: each needs Boss's approval)

1. **Forward log first, then Boss decides:**
   - NP5-52 (the 09:25 no-progress exit on R52);
   - BBX-51 (bad-break skip on R51);
   - R140's FB-R72-A10 (wide-day skip on R72).

   Log each in shadow beside its arm on new sessions: what it would have done, with no orders and no change to the
   arm.
2. **Only after Boss approves:** a labelled paper switch per rule, logged in the Autopilot timeline, one-tap undo, the
   arm's plain-rules record kept separable.
3. **Live:** these only skip trades or exit earlier, so they lower risk. They would still start in shadow, behind a
   switch that is off by default.

## Method

- **Engine:** R115's engine reproduced all four arms' published trades and net in design and final. The re-priced
  exits on R52 were checked to match R52's own prices before use.
- **Data:** the Drive lake, copied 31 Oct; R51's clean stores. 1 lot; R17 fills; dated Zerodha charges.
- **Tests:** `test_r142.py`.
- **Results:** `results/r142.json`.
- **Entries:** `research/experiments/entries/r142-*.yaml` (1 paper-only candidate, 21 rejected).
