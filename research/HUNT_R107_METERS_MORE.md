# R107: can the GEX meter and the danger meter help us trade MORE on the days they flag?

26 Oct 2026. Boss: *"Can we check the GEX meter [and the danger meter: "Both"] and with their help increase the
strategies?"*

- **Pre-registered** in `research/hunt/r107_meters_more/PREREG.md` (commit e924917, pushed before any number).
- **Order of steps:** the design read was committed (f60ccce) before the one final read (`results/FINAL_READ`).
- **Code, tests and every table:** `research/hunt/r107_meters_more/` (`results/report.md`, 19 tests).
- **Entries:** `research/experiments/entries/r107-*.yaml` (11 entries, all rejected).
- **Signal source for every rule: the index.** The arms' own index signals, strikes and exits are unchanged. The
  meters only decide the size (1 or 2 lots) or whether a 3rd same-direction position may open. GEX is option-chain
  context around an index signal.

## The answer: no

**No. None of the 11 tests passed. Nothing changes on paper or live.**

Using either meter to trade bigger, or to allow a 3rd same-direction position, did not beat two simple
comparisons. The first is today's rule. The second is the same extra size (or extra place) on random days that are
flagged equally often.

1. **What the meters tell is "a bigger day", not "a better day".** Both meters flag days when moves are larger.
   - Bigger days bring bigger wins, so on those days the arms often make more per trade.
   - They also bring the arms' worst days.
   - So doubling size there doubles the bad days too. Measured as net per rupee of drawdown or per rupee of worst
     day, that is no better than doubling size on random days.
2. **The meters' edge did not hold over time.**
   - Negative GEX was a worse time to size up in design (Nov 2024 - Mar 2026), especially on R72 and R51.
   - It was a better time in the walk-forward window and the final.
   - The danger meter's flagged days were mostly better for Liquidity, R72 and R51. But flagged days held R72's
     and R51's worst days in design: -Rs 58,613 and -Rs 56,647.
3. **"Always 2 lots" beat every meter rule on net per unit of risk.**
   - The reason is charges, not the market. Brokerage is a flat Rs 20 an order, so a 2-lot trade pays per lot half
     the brokerage of a 1-lot trade.
   - This is a fact about charges, not a tested strategy. It doubles the rupee drawdown, which breaks Boss's
     Rs 40,000 account rule more often. **It is not recommended** without its own test and Boss's choice of risk.
4. **The best single result was luck-sized.**
   - D2 on R72 ("2 lots on danger-flagged days") had a final twin p of 0.044.
   - It failed in design: its net per max drawdown was 1.05 against today's 1.09.
   - With 584 tries counted, a p of 0.044 is expected by chance (BH q = 1.0).

The S3 idea ("switch on ORB only on negative-GEX days") was not run again. It is exactly R28's filter G1, which
already failed: 425 trades, still -Rs 57 a trade, no better than random days. R92 found no time of day when ORB
makes money.

## What each meter really tells (1 lot, n = trades)

### The danger meter (the app's fixed model: gap, VIX, VIX change, event day, expiry day, weekday)

It flags 47 of 337 design sessions (32 careful, 15 paper-only) and 17 of 133 final sessions (15 careful, 2
paper-only).

| arm | design: flagged vs normal, per trade (n) | walk-forward: flagged vs normal | final: flagged vs normal | worst day on flagged / normal days, design |
|---|---|---|---|---|
| Liquidity 15+5 | Rs 690 (112) vs Rs 234 (769) | Rs 904 (94) vs Rs 272 (563) | Rs 286 (58) vs -Rs 18 (299) | -Rs 8,416 / -Rs 6,363 |
| R72 Breadth Donchian | Rs 81 (389) vs Rs 69 (2,682) | Rs 261 (314) vs Rs 132 (1,924) | Rs 567 (99) vs Rs 104 (1,103) | **-Rs 58,613** / -Rs 27,444 |
| R51 Donchian 20 | Rs 34 (585) vs -Rs 2 (3,913) | Rs 170 (452) vs Rs 35 (2,814) | Rs 245 (180) vs Rs 24 (1,601) | **-Rs 56,647** / -Rs 31,959 |
| R52 Open outside value | -Rs 142 (71) vs Rs 474 (635) | -Rs 236 (56) vs Rs 550 (473) | -Rs 527 (17) vs Rs 209 (276) | -Rs 18,625 / -Rs 25,140 |

- R17 found the same: the "danger" days are often the GOOD days for breakout buyers, because they are the
  bigger-move days. They also carry the biggest losing days.
- R52 is the opposite. Its 09:20 "open outside value and go" trade does worse on those days, in all three periods.
  The samples are small, though: 71, 56 and 17 trades.
- Caveat: the app's meter was fitted on sessions up to 23 Feb 2026, which overlaps the design window. Only the final
  window is outside its sample.

### The GEX meter (dealer gamma of the trade's own index, read at 09:20 and 10:00)

| arm | design: GEX < 0 vs >= 0, per trade (n) | walk-forward | final | win rate GEX < 0 vs >= 0, final |
|---|---|---|---|---|
| Liquidity 15+5 | Rs 335 (395) vs Rs 257 (486) | Rs 426 (286) vs Rs 313 (371) | Rs 43 (102) vs Rs 27 (255) | 35% vs 38% |
| R72 Breadth Donchian | Rs 30 (1,308) vs Rs 101 (1,763) | Rs 161 (941) vs Rs 142 (1,297) | Rs 179 (474) vs Rs 119 (728) | 46% vs 46% |
| R51 Donchian 20 | -Rs 21 (1,812) vs Rs 18 (2,686) | Rs 75 (1,269) vs Rs 40 (1,997) | Rs 74 (626) vs Rs 31 (1,155) | 44% vs 44% |
| R52 Open outside value | Rs 406 (283) vs Rs 415 (423) | Rs 683 (211) vs Rs 324 (318) | Rs 350 (111) vs Rs 54 (182) | 52% vs 51% |

- **Win rates do not move with GEX.** That matches R28: GEX tells the SIZE of the day's move, not its direction.
- The per-trade gap flips sign between design and the later windows on R72 and R51. That is why sizing up on it
  failed the design gate.
- Negative GEX is common in these two years. It shows at 10:00 on 28-46% of design sessions per index, and on
  27-52% of final sessions (R28's 6-year figure was 18-29%).

## Before / after (final window 1 Apr - 13 Oct 2026, read once; 1 lot = today)

"Changed" = trades sized up to 2 lots (or new positions for G3 / D3). The ratio is net / max drawdown. "Always" = every
trade at 2 lots (A3 = the cap at 3 every day). Twins = the same rule on random days, 10,000 times.

| test | rule | changed | today's net | new net | net / max DD: new / today / always / twins | design: new / today / always | final twin p | verdict |
|---|---|---|---|---|---|---|---|---|
| G2-LIQ | 2 lots when GEX < 0 | 102 of 357 | Rs 11,155 | Rs 20,221 | 0.24 / 0.18 / 0.33 / 0.25 | 8.51 / 7.91 / 8.81 | 0.52 | fail |
| G2-R72 | 2 lots when GEX < 0 | 474 of 1,202 | Rs 1,71,366 | Rs 2,78,366 | 3.60 / 2.77 / 3.59 / 2.96 | 1.04 / 1.09 / 1.54 | 0.21 | fail |
| G2-R51 | 2 lots when GEX < 0 | 626 of 1,781 | Rs 81,845 | Rs 1,56,943 | 1.20 / 0.89 / 1.49 / 1.17 | 0.16 / 0.04 / 0.55 | 0.47 | fail |
| G2-R52 | 2 lots when GEX < 0 | 111 of 293 | Rs 48,612 | Rs 92,562 | 1.36 / 1.03 / 1.21 / 1.04 | 2.58 / 3.51 / 3.80 | 0.23 | fail |
| D2-LIQ | 2 lots on flagged days | 58 of 357 | Rs 11,155 | Rs 30,362 | 0.50 / 0.18 / 0.33 / 0.18 | 8.32 / 7.91 / 8.81 | 0.17 | fail |
| D2-R72 | 2 lots on flagged days | 99 of 1,202 | Rs 1,71,366 | Rs 2,32,082 | 4.57 / 2.77 / 3.59 / 2.71 | 1.05 / 1.09 / 1.54 | 0.044 | fail |
| D2-R51 | 2 lots on flagged days | 180 of 1,781 | Rs 81,845 | Rs 1,34,343 | 1.40 / 0.89 / 1.49 / 0.99 | 0.20 / 0.04 / 0.55 | 0.18 | fail |
| D2-R52 | 2 lots on flagged days | 17 of 293 | Rs 48,612 | Rs 40,428 | 0.72 / 1.03 / 1.21 / 0.99 | 3.39 / 3.51 / 3.80 | 0.71 | fail |
| G3 | 3rd same-direction place when GEX < 0 (paper book) | 354 new | Rs 59,426 | Rs 76,571 | 0.82 / 0.69 / 2.26 / 1.45 | -0.92 / -0.95 / -0.92 | 0.90 | fail |
| D1 | 2 lots on the meter's normal days (paper book) | 1,487 of 1,694 | Rs 59,426 | Rs 1,84,062 | 1.54 / 0.69 / 1.58 / 1.46 | -0.93 / -0.95 / -0.93 | 0.41 | fail |
| D3 | 3rd same-direction place on flagged days (paper book) | 59 new | Rs 59,426 | Rs 78,683 | 0.93 / 0.69 / 2.26 / 0.83 | -0.95 / -0.95 / -0.92 | 0.35 | fail |

- **More size always makes more rupees when an arm is profitable.** That is not the test. The test is whether the
  meter picks the RIGHT days for the extra size, better than random days and better than simply always trading more.
  It did not, in all three periods together.
- The walk-forward window and the design-window tables, with worst days and drawdowns, are in `results/report.md`.

## The account rule (max drawdown Rs 40,000, a month at -Rs 13,000), at the paper size

**Today's rules already break it on their own, at 1 lot.** No variant fixes that. Most variants make it worse.

- **Each arm alone, final window, today's rule (7 months):**
  - Liquidity: max drawdown Rs 61,062; 1 month at or below -Rs 13,000.
  - R72: Rs 61,884; 2 months.
  - R51: Rs 91,626; 1 month.
  - R52: Rs 47,194; 1 month.
- **The 2-lot variants raise the drawdown.** For example, G2-R72 reaches Rs 77,217 and G2-R51 Rs 1,31,002.
- **Exceptions, smaller in the final only:**
  - D2-R72: Rs 50,745 against Rs 61,884 (but -Rs 1,16,525 worst day in design, against -Rs 58,613);
  - D2-LIQ: Rs 60,515 against Rs 61,062;
  - D3: Rs 84,376 against Rs 85,742.
- **Months at or below -Rs 13,000 in the final:** the variants have 0-3 of 7, against today's 1-2. G2-LIQ is worst
  at 3, against 1.
- **The paper book (all four arms with the same-direction cap of 2):**
  - final: max drawdown Rs 85,742; 2 of 7 months at or below -Rs 13,000;
  - design: Rs 4,22,620; 10 of 17 months.
  - D1 (2 lots on normal days) raises the final drawdown to Rs 1,19,677.

## What can go on paper

**Nothing from R107.** No rule passed the gates, so no meter rule may act on paper, and nothing goes live. The app keeps
showing both meters as information only. The danger meter's "half size on danger days" switch stays off. The data
says that switch would cut size on the better days for Liquidity, R72 and R51.

## A lead for the next study (not a test, not gated)

To test S2 and D3, R107 had to model the paper book with the app's same-direction cap: at most 2 open positions
betting the same way, across all arms, first come first served. In that model, **the cap itself cost far more than
any meter could add:**

| | design: four arms alone | design: book with the cap | final: four arms alone | final: book with the cap |
|---|---|---|---|---|
| net (trades) | Rs 7,74,750 (9,156) | **-Rs 4,02,702** (4,303) | Rs 3,12,978 (3,633) | Rs 59,426 (1,694) |
| R52 trades kept | 706 | 119 | 293 | 33 |
| Liquidity trades kept | 881 | 190 | 357 | 83 |
| R51: net of the trades the cap refused | - | +Rs 3,67,874 (2,453) | - | +Rs 1,46,806 (964) |

**Why:**
- R72 and R51 fire on the same 09:20 bars as R52 and take the two places first.
- Donchian wins most when many indices break together, which is exactly when the cap is full.
- A refused R51 signal leaves its book free, so it takes a later and weaker signal instead: 823 such trades in
  design, -Rs 1,73,539.

**This depends on choices the app may make differently:**
- who goes first on a shared bar (the model goes R72, R51, Liquidity, R52);
- R72's reserved place (22 Oct), which is not modelled;
- whether an arm retries a refused bar.

So it is a lead, not a finding. **Suggested next step:**
- an app session checks, from the paper record since 19 Oct, which arms the cap refused and what those trades would
  have made (the app keeps them as would-be entries);
- then a pre-registered study tests cap variants. Examples: the cap per arm, R52 and Liquidity outside the cap,
  or the cap only when the combined stop exceeds a rupee amount.

The cap is a risk-lowering rule. Loosening it on paper needs a passed test, and on live it stays as it is.

## How it was tested

- **Engine:** R106 / R101's engine and candidates. That means R51 clean stores, R64's held-contract simulator, R17
  fills, dated Zerodha charges and next-minute fills. Today's rule (B1) reproduced R101 to the rupee on all four arms:
  - Liquidity: 881 trades / Rs 2,57,249 in design, 357 / Rs 11,155 in the final;
  - R72: 3,071 / Rs 2,16,929 and 1,202 / Rs 1,71,366;
  - R51: 4,498 / Rs 9,998 and 1,781 / Rs 81,845;
  - R52: Rs 2,90,573 and Rs 48,612.
- **2 lots:** every trade was priced again by the engine as one 2-lot order: the fill's half-spread for that size,
  and that order's charges. In practice no exit moved to another minute.
- **GEX:** R28's per-minute table, S1 near-expiry net GEX of the trade's own index. Entries from 10:00 use the 10:00
  reading; entries 09:21-09:59 use the 09:20 reading. Each reading is built only from earlier minutes (tested).
- **Danger meter:** a line-for-line copy of the app's `DangerMeter`, checked against the app's source and its own
  test cases. Its inputs:
  - BANKNIFTY's gap;
  - VIX's previous close and change;
  - event days (RBI, Budget, the app's rebalance days);
  - NIFTY / BANKNIFTY expiry;
  - the weekday.
- **Paper book:** each arm's own taking rule plus the app's `SameDirectionCap`.
- **Gates** (all needed, in design AND the walk-forward window AND the final):
  - net > 0;
  - net / max drawdown and net / |worst day| above today's rule, above "always more", and above the median of
    10,000 random-day twins;
  - BH q <= 0.10 on the final twin p, counted against 584 tests (R107's 11 plus R17 3, R28 10, R28b 378, R36 4,
    R37 4, R105 A-F 138 and R106 36, all taken as p = 1);
  - at least 30 final trades changed.
  - **0 of 11 pass.** Every test failed 6-19 of the checks, and each failed at least one design check. The best BH q
    was 1.0; within R107 alone the best q was 0.49.

## Honest limits

- **The final window is not fresh.** R51-R106 have all read it. Every test failed in design first anyway.
- **The danger meter's fit overlaps the design window.** It was fitted on the ORB-family arms to 23 Feb 2026, so only
  its final read is out of sample: 17 flagged days, which is small.
- **The GEX table ends on 12 Oct,** so 13 Oct's trades count as unflagged (1 session).
- **Small data and counting notes:**
  - INDIA VIX repeats the previous close exactly on 9 of 694 lake sessions (more than chance), so a few days may read
    "VIX flat" instead of up or down.
  - The run's count of new positions for G3 / D3 ignored Liquidity's book, so it is short by one in places: D3 final
    is 60, not 59; design and walk-forward G3 / D3 are +1. Fixed in the code afterwards. No gate depends on it.
- **The per-arm tests (G2, D2) use each arm's own record,** without the paper account's rules. The book tests (G3,
  D1, D3) model the same-direction cap only (no account stop, weekly stop or 2% cap).
- **Not tested, with the reasons in PREREG section 3:**
  - Night R3: overnight, separate data; R28 G10 showed GEX picks the wrong days for it;
  - Solo 2.0: its final picks do not reproduce;
  - MCX arms: another lake;
  - the "mirror" rule: none of the four arms is mean-reverting.
- **Checks run:** `research/brain/tests` 183 passed (Python 3.11 with the pinned versions); `features catalog --check`
  is up to date; R107's own tests, 19 passed.
