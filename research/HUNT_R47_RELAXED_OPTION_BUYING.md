# R47: index-option BUYING with relaxed design rules (15 Oct 2026)

**Verdict: nothing passes.** Boss allowed relaxing what kinds of buying strategies we try. R47 searched 387,392 rule
variants (29,002 rule combinations x 14 regime filters) on four indices, on design data from Aug 2021 to Oct 2025.
A fixed walk-forward rule picked 10 candidates. On the locked holdout (10 Nov 2025 - 13 Oct 2026, read once) 6 of the 10
made money, but **none beat random entries after correcting for 10 tries** (best q = 0.50; the gate is 0.10), and 4
lost money. **There is no paper proposal.** The closest was the BANKNIFTY "high PCR -> buy ITM calls" rule (C4): +Rs 865 a
trade on 91 holdout trades, p = 0.0499, but q = 0.50, and its design profit had already faded to zero in 2025.

Pre-registered in `hunt/r47/PREREG.md` (commit 2f27c32d, pushed before any P&L), and three written amendments (see "Data and
code problems found"):
- Amendment 1, a stray-print guard, came before the candidates were frozen.
- Amendments 2 and 3 came after the one holdout read: a lake data defect and a look-ahead bug. Neither changed the
  verdict.

Candidates were frozen in commit 4f012404 before the holdout read. Code, tests and results are in `hunt/r47/`. Experiment entries: `experiments/entries/r47-*.yaml` (19
families + 10 candidates).

## What was relaxed (Boss, 15 Oct), and what was not

Relaxed (design of the strategies; each labelled):

1. **Signal source.** Besides the index (label **U**, the CLAUDE.md default), option-chain signals (**C**: PCR, OI shift,
   ATM IV) and the option's own price chart (**O**, *non-default: option-chart signal*). Calendar / event timing is **E**,
   the tree model is **M**.
2. **Holding period.** Intraday; overnight (exit next session 09:16); 1, 3 and 5 sessions (exit 15:15). Gaps are priced
   on the real next-session rows of the held contract.
3. **Instruments, all bought.** Near expiry ITM3, ITM1, ATM, OTM2, OTM5; month expiry ATM and ITM2 (NIFTY, SENSEX);
   debit spreads (buy ATM / sell OTM3; buy ITM2 / sell OTM2); straddles and OTM2 strangles. Next-month expiry is not in
   the lake, so it was not tested.
4. **Trade count.** No 2-a-day cap (one position at a time per rule). Size by a Rs 20,000 premium budget (1-20 lots,
   never more than 10% of the lots the contract traded in the 5 minutes before entry).
5. **Search breadth.** Every combination, with no BH during exploration; a gradient-boosted tree model (M1) as one family.
6. **Regime / calendar filters.** Previous VIX close (< 14, 14-20, > 20), weekday, sessions to expiry (0, 1, 2+), the last 3
   sessions of a month, RBI / budget days +-1. FII flows and global cues are not in the lake.

Kept (measurement): every leg priced on **one contract from entry to exit** (never the rolling ATM series), R17 "liq"
fills, the dated charges on every order of every leg, entry at the next minute's open, stops filled at the worse of the
level and the open (overnight gaps included), stop before target in the same minute, no look-ahead (prefix tests for
every family), a locked holdout read once, 500 random-entry twins per candidate and BH across the candidates.

Underlyings: NIFTY, BANKNIFTY, FINNIFTY, SENSEX. MIDCPNIFTY was left out because its expiry dates cannot be recovered
from the lake.

## Data and code problems found (one before the freeze, two after the holdout read)

The first two fixes can only lower results. All three are written in the PREREG as amendments.

**1. Stray prints in thin contracts.** The first design run's top FINNIFTY candidate made +107% on a day the index
fell. Its 18200 call printed a 09:15 open of 500 and a 09:16 open of 151 while it traded near 60. Fix: a fill never takes
a price more than 20% away from the median of the contract's surrounding closes if that price would help us. A high that
far above the median cannot hit a target or arm a lock. The search and the selection were run again
(`results/*_v0_unamended.json` keep the first run).

**2. Rows of ANOTHER underlying inside the lake's option files.** This was found after the holdout read, while checking a
design number that looked impossible: +Rs 4,807 a trade for FINNIFTY trees, from contracts with strike 12,550 when
FINNIFTY was at 25,700. Some rows carry a `spot` and `strike` of a different index:

| file(s) | rows whose spot is > 3% off the index | days | spot / index |
|---|---|---|---|
| opt_FINNIFTY_WEEK 2025 | 117,928 of 3.7 M | 65 | 0.49 (MIDCPNIFTY level) |
| opt_FINNIFTY_WEEK 2021-24, 2026 | 25,054 | 124 | 0.5 or 2.1 |
| opt_BANKNIFTY_MONTH 2022-24 | 76,558 | 212 | 0.44 (NIFTY level) |
| opt_SENSEX_MONTH 2023-25 | 69,769 | 63 | 0.75 (BANKEX level) |
| opt_SENSEX_WEEK 2023-25 | 3,597 | 48 | 0.75 |
| opt_MIDCPNIFTY_* 2022-24 | about 93,000 | 200+ | about 2 (FINNIFTY level) |
| opt_NIFTY_MONTH 2024, 2026; opt_BANKNIFTY_WEEK 2022, 2025 | 1,042 | 49 | about 0.5 |

NIFTY WEEK is clean. Fix: an option row whose own `spot` is more than 3% off the index's close at that minute is dropped
(`r47data.foreign_rows`). **The design search and the selection were then run again on clean data. They chose exactly
the same 10 candidates, with the same design numbers** (`results/candidates_v2.json`). Re-pricing those same frozen
candidates on the cleaned holdout rows (`results/holdout_amended.json`) gives the same holdout trades for all ten.
Only C3's random twins changed (p 0.40 -> 0.48). So the one holdout read stands, and nothing was read twice to choose
anything. **Every other lake study that reads FINNIFTY, BANKNIFTY MONTH, SENSEX MONTH or MIDCPNIFTY option rows should
add this filter** (see FINDINGS.md).

**3. A look-ahead bug found in the self-review (after the holdout read).** BANKNIFTY, FINNIFTY and SENSEX index minutes
start on 4 Aug 2021, so their first session had index 0. There, "the previous session" (`dclose[di - 1]`) wrapped round
to the last design session. Only signals on 4-6 Aug 2021 could read it (gap, daily mean reversion, previous-day
high/low, VIX and tree families), and no candidate's rule reads it. Fixed: signals start at session 3 at the earliest,
and a test checks this. The design search and selection were rerun once more (`results/design_v3.json`,
`results/candidates_v3.json`, the numbers used in the tables here). Nine of the 10 candidates are identical. The 10th
slot changes:
- BANKNIFTY big-bar (C9) drops out. Starting 3 sessions later shifts its 20-bar warm-up, and that flips its fragile
  walk-forward pick. U7 never read the wrapped value.
- A NIFTY option-chart rule would come in at rank 10: O1 premium ORB (15 min), target +100%, last-3-sessions-of-month
  filter. It has 140 design trades, +Rs 1,777 a trade, and walk-forward 115 trades, +Rs 292 a trade, t 0.47 (the
  weakest of the ten).

It was NOT sent to the holdout: the holdout has been read once, and a second read would no longer be a clean test. It
would at best be a non-default (option-chart) rule, and it can only be tested on data after 13 Oct 2026.

## The exploration (design, Aug 2021 - Oct 2025)

Per signal family: the variants run, how many had 100+ trades, how many of those were net positive, and the median net
per trade. The "best" column is the single best variant out of thousands, so it is heavily selection-biased; only the
walk-forward below says anything.

| family | source | variants | 100+ trades | net positive | median Rs/trade | best single variant (100+ trades) |
|---|---|---|---|---|---|---|
| U1 ORB breakout | U | 1,920 | 1,920 | 76 (4%) | -353 | FINNIFTY OTM2, to 15:15: 771 trades, +709, 3/4 blocks green |
| U2 gap follow/fade | U | 3,840 | 2,120 | 560 (26%) | -200 | SENSEX gap fade OTM5: 207, +3,012, 3/3 |
| U3 late-day move, held overnight / days | U | 2,048 | 1,677 | 587 (35%) | -1,081 | NIFTY OTM2, 5 sessions: 199, +4,631, 4/4 |
| U4 Donchian breakout, held | U | 1,536 | 1,229 | 641 (52%) | **+38** | SENSEX OTM5, 5 sessions: 118, +6,192, 3/3 |
| U5 daily mean reversion, held | U | 1,024 | 1,008 | 0 | -2,446 | none positive |
| U6 EMA cross | U | 2,560 | 2,560 | 40 (2%) | -420 | NIFTY ATM: 1,302, +287, 3/4 |
| U7 big 5-minute bar | U | 2,560 | 2,560 | 306 (12%) | -332 | FINNIFTY OTM2: 316, +1,811, 3/4 |
| U8 previous-day high/low | U | 1,280 | 1,280 | 270 (21%) | -296 | SENSEX ATM: 485, +956, 3/3 |
| U9 VIX spike -> calls | U | 1,024 | 0 | - | - | too few trades |
| U10 trend day at 13:00/14:00 | U | 2,560 | 2,200 | 603 (27%) | -233 | FINNIFTY spread: 181, +1,197, 2/4 |
| C1 PCR extreme | C | 2,560 | 2,560 | 708 (28%) | -317 | SENSEX spread: 150, +1,871, 1/2 |
| C2 cheap IV -> straddle | C | 720 | 434 | 183 (42%) | -34 | NIFTY OTM2 strangle, 3 sessions: 104, +1,059, 3/4 |
| C3 OI shift | C | 1,280 | 1,280 | 244 (19%) | -249 | SENSEX ATM: 149, +1,135, 1/2 |
| O1 premium ORB | O (non-default) | 160 | 160 | 35 (22%) | -177 | SENSEX: 493, +406, 2/3 |
| O2 premium momentum | O (non-default) | 160 | 160 | 44 (28%) | -148 | SENSEX: 350, +834, 2/3 |
| E1 RBI / budget straddle | E | 70 | 0 | - | - | under 100 events (exploration only) |
| E2 expiry-day straddle | E | 180 | 144 | 6 (4%) | -1,816 | FINNIFTY strangle: 177, +605, 3/4 |
| E3 expiry-day "hero" | E | 2,240 | 700 | 178 (25%) | -451 | NIFTY spread: 121, +1,600, 3/4 |
| M1 gradient-boosted trees | M | 1,280 | 1,280 | 89 (7%) | -419 | BANKNIFTY ATM: 598, +471, 1/3 |

By source, the share of 100+-trade variants that were net positive was: underlying 19% (3,083 of 16,554), option chain
27% (1,135 of 4,274), option chart 25% (79 of 320), events 22% (184 of 844), trees 7% (89 of 1,280). Most rules lose
after costs, as in every earlier study. Only the multi-day breakout family (U4) has a positive median.

## Walk-forward and the candidates

A **family** is (signal family, index, instrument kind): 132 families. For each test year (2023, 2024, Jan-Oct 2025),
the family's best variant on the earlier years was traded in that year. A family qualified with 100+ out-of-sample trades,
a positive out-of-sample net and 2 of the 3 years green. **18 families qualified.** The strongest were overnight
late-day momentum (U3: BANKNIFTY t 3.2, FINNIFTY spreads t 2.5) and NIFTY 10-day breakout spreads (U4: t 2.7). Each
qualifying family's candidate was its best design variant with 100+ trades, net positive without its 5 best trades and
green in all but one block. The top 10 by walk-forward t (one per family and index) went to the holdout:

| id | rule (signal source) | instrument, exit | filter | design trades, Rs/trade | walk-forward OOS trades, Rs/trade |
|---|---|---|---|---|---|
| C1 | BANKNIFTY moved > 0.5% from the open by 15:19 -> follow (U) | ITM3, target +100%, sell next 09:16 | not expiry eve/day | 311, +1,200 | 130, +1,316 |
| C2 | NIFTY 15:19 close beyond the 10-session high/low (U) | spread ITM2/OTM2, 1 session | VIX < 14 | 163, +2,207 | 163, +2,004 |
| C3 | FINNIFTY late-day move > 0.5% -> follow (U) | spread ITM2/OTM2, target +100%, next 09:16 | VIX < 14 | 156, +1,245 | 109, +1,432 |
| C4 | BANKNIFTY PCR at 10:15 in its top/bottom fifth -> follow (C) | ITM3, stop 30%, lock +60%->+30%, 15:15 | - | 440, +1,093 | 229, +651 |
| C5 | SENSEX 60-minute ORB (U) | spread ITM2/OTM2, stop 30%, lock | expiry day | 103, +2,741 | 120, +677 |
| C6 | NIFTY OI shift 09:20-11:00 -> follow (C) | spread ITM2/OTM2, stop 30%, lock | expiry day | 110, +2,232 | 159, +1,078 |
| C7 | NIFTY PCR at 11:30 extreme -> follow (C) | ITM3, stop 25%, target +100%, 15:15 | - | 429, +843 | 221, +389 |
| C8 | SENSEX 5-min EMA 9/21 cross with VWAP (U) | OTM2, 60 minutes | Wednesday | 187, +839 | 166, +266 |
| C9 | BANKNIFTY big 5-minute bar -> follow (U) | ITM3, stop 40%, 15:15 | Monday | 188, +1,226 | 151, +476 |
| C10 | SENSEX 09:15 ATM option breaks its own 15-min high (**O, non-default**) | that option, stop 25%, target +100% | Friday | 100, +2,009 | 215, +278 |

Warning signs were already visible in design: C2, C4, C7 and C8 made almost nothing in Jan-Oct 2025 (+1,037, +887,
+17,621 and +1,076 rupees in all over 40-94 trades each).

## The holdout (10 Nov 2025 - 13 Oct 2026, read once)

Twins: 500 per candidate. Each candidate trade was re-entered on a random holdout session (expiry days for expiry-day
rules) at a random minute of the rule's window, with the same direction, instrument, exits and sizing. p = the share of
twins that made at least as much. q = Benjamini-Hochberg across the 10.

| id | trades | net Rs | Rs/trade | win | twins Rs/trade | p | q | gates failed |
|---|---|---|---|---|---|---|---|---|
| C1 BANKNIFTY late-day overnight | 90 | -92,814 | -1,031 | 44% | -1,101 | 0.50 | 0.56 | net, p, q, top-5, month |
| C2 NIFTY breakout spread | 43 | +32,045 | +745 | 44% | +298 | 0.39 | 0.56 | p, q, top-5, month |
| C3 FINNIFTY late-day spread | 49 | -265,497 | -5,418 | 29% | -5,569 | 0.48 | 0.56 | net, p, q, top-5, month |
| **C4 BANKNIFTY PCR follow** | 91 | **+78,750** | **+865** | 48% | -287 | **0.0499** | 0.50 | **q only** |
| C5 SENSEX ORB spread, expiry day | 47 | -31,675 | -674 | 32% | -304 | 0.65 | 0.65 | net, p, q, top-5, month |
| C6 NIFTY OI shift spread, expiry day | 24 | +430 | +18 | 50% | -93 | 0.46 | 0.56 | trades, p, q, top-5, month |
| C7 NIFTY PCR follow | 102 | +74,805 | +733 | 45% | +312 | 0.21 | 0.56 | p, q, top-5 |
| C8 SENSEX EMA, Wednesdays | 67 | -28,448 | -425 | 42% | -270 | 0.51 | 0.56 | net, p, q, top-5, month |
| C9 BANKNIFTY big bar, Mondays | 41 | +11,113 | +271 | 49% | -290 | 0.27 | 0.56 | p, q, top-5, month |
| C10 SENSEX premium ORB, Fridays (O) | 41 | +13,191 | +322 | 39% | +68 | 0.39 | 0.56 | p, q, top-5, month |

Gates: holdout net > 0; 40+ trades; p < 0.05; q <= 0.10; net still positive without the 5 best trades; net still
positive without the best month. **No candidate passes.** Apart from C4, every candidate that made money on the holdout
made less than its own 5 best trades.

Notes:
- **C4 came closest.** The rule: at 10:15, when BANKNIFTY's put/call OI ratio (ATM+-10, near expiry) is in its top fifth
  of the previous 60 sessions, buy the ITM3 call (bottom fifth: the ITM3 put); stop -30%, lock +30% once +60% is
  reached, exit 15:15. It made money on the holdout and beat 95% of its twins. With 10 candidates tried, a p of 0.05 is
  what one of them would show by luck about 40% of the time (q 0.50). Its design profit also faded from +1,766 a trade
  in 2022 to +9 in 2025. It is a note only, not a proposal. If Boss wants it watched, it would need a fresh forward
  test (it could only be a paper candidate after a new pre-registered holdout).
- C3's large holdout loss is partly from our conservative valuation. On 15 of its 49 trades a leg of the thin FINNIFTY
  monthly spread had no lake row at the next 09:15-09:17, and it was valued at the worst bound. Without those 15 it still
  lost Rs 1,313 a trade.
- Overnight late-day momentum (U3) was the strongest design idea (+1,200 to +1,400 a trade, every design year green),
  and it lost on the holdout for BANKNIFTY (-1,031, 90 trades) and FINNIFTY.

## What the data did show

- Relaxing the design did not create an edge. Under honest pricing, buying index options loses after costs for almost
  every rule family, whatever the signal source, holding period, strike, spread or filter. In 16 of the 17 families with
  enough trades the median variant (100+ trades) lost Rs 34-2,446 a trade on a Rs 20,000 budget; the exception is U4
  (+38). U9 and E1 had too few trades.
- The families that came closest on design were:
  - multi-day breakouts held with spreads (U4, median slightly positive);
  - overnight continuation of a strong last hour (U3);
  - chain-PCR "follow" rules on BANKNIFTY and NIFTY (C1).
  The last of these is the only one that also made money on the holdout (C4 +865 and C7 +733 a trade, about 95 trades
  each). It did not pass the multiple-testing gate.
- Option-chart signals (O, non-default) were no better than index signals (25% of variants positive vs 19%). The one
  sent (C10) made +322 a trade on 41 trades, p 0.39.
- Buying straddles on expiry days (E2) is the clearest loser: 6 of 144 variants positive, median -1,816 a trade.
- The tree model (M1) was worse than simple rules (7% of variants positive).
- **Data:** the lake's option files mix in rows of other underlyings (table above) and carry stray prints in thin
  contracts. Both can create large fake profits.

## Reproduce

```bash
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m ~/.cache/iraalgo/lake --include "opt_{NIFTY,BANKNIFTY,FINNIFTY,SENSEX}_*"
rclone copy gdrive:IraAlgo-data-lake/index_1m ~/.cache/iraalgo/lake
rclone copy gdrive:IraAlgo-data-lake/index_daily ~/.cache/iraalgo/lake
python3 research/hunt/r47/r47data.py build              # design stores (no row after 31 Oct 2025)
python3 research/hunt/r47/r47search.py                  # 387,392 variants -> <cache>/r47/search_*.parquet
python3 research/hunt/r47/r47select.py --tag v3         # walk-forward + candidates (results/design_v3.json)
python3 research/hunt/r47/r47holdout.py --recheck       # the frozen candidates on cleaned holdout rows
python -m pytest research/hunt/r47 -q                   # 74 tests
```

`r47holdout.py` without `--recheck` refuses to run again (`results/holdout.json` exists: the one read).
