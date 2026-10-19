# R84: Breadth Donchian, the loss of one signal (19 Oct 2026)

19 Oct 2026. Research only: nothing in the app changes (no `android/**`, no `.github/**`). Pre-registered in
`research/hunt/r84/PREREG.md` (commit 5693400, before any run). One variant was added after the first design results
as amendment A1 (commit b5053cb, before it was run), labelled post hoc. Numbers: `research/hunt/r84/results/`.

Boss, 19 Oct: *"Deep analysis and find what happened today with breadth donchian, should not happen again like 9k loss,
so find a best solution for it."*

## The answer

**None of the 8 rules passes. There is no position-count rule that limits a Breadth Donchian signal's loss and keeps its
edge.** Breadth Donchian earns its money on the signals where 4 or 5 indices break together and all keep running. Any
rule that holds fewer of those positions gives up most of the profit, and does no better than dropping the same number of
positions at random.

- Every rule cut the worst day, by 32% to 77%.
- But the drawdown barely moved (max DD down 0-36%; X-skip and X-K2 made it worse).
- The net fell from Rs 2.17 lakh to between Rs 61k and -Rs 1.67 lakh in design.

That includes **the same-direction cap of 2 now in the app** (K2-open below): on Breadth alone it turns design's
Rs 2,16,929 into -Rs 19,377.

**19 Oct was not a freak.** In design (16 months, 936 signals), 36 signals lost Rs 9,700 or more, about 2 a month. The
worst one lost Rs 26,277. On the other side, 73 signals made Rs 9,700 or more. Losing about Rs 10k on one signal is
the normal cost of this arm, not a fault in it.

## What happened on 19 Oct (rebuilt)

The lake ends 14 Oct and the Dhan token is expired, so the options of 19 Oct cannot be priced. The index side was rebuilt
from Yahoo 1-minute index bars, with the lake's minutes before them for warm-up (`results/day19.json`).
- **The signal:** on the 09:25-09:29 bar (closing 09:30), all **5 of 5** indices closed below their 20-bar Donchian low,
  so C1 fired a put on each. NIFTY was skipped (its own expiry day), so 4 puts were bought at 09:30-09:31.
- **The move:** NIFTY's 10-minute move into that close was 0.74% (from the 09:20 open to the 09:30 close), **14.5 times**
  its usual 0.051%. (The app's figure, 0.42% and about 11 times, uses a different 10-minute window. Both say it was a
  shock bar.)
- **After it:** on the next bars (09:30-09:55) no index was below its channel any more: the break reversed at once.
  C1 fired again at 13:20 and 13:25; whether paper took those is not known here (the paper daily loss limit had
  already stopped the book).
- Yahoo's closes matched the lake's within 0.03% for the four NSE indices on 8-14 Oct. SENSEX was off by up to 0.66%, so
  its break size is the least certain number.

**What each rule would have cost on 19 Oct** (the app's own paper losses of the positions each rule keeps):

| rule | positions kept | 19 Oct |
|---|---|---|
| K0, as now | BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX | **-Rs 9,722** |
| K2-first / K2-open (the app's cap, Breadth alone) | BANKNIFTY, FINNIFTY | -Rs 5,049 |
| K2-strong | SENSEX, FINNIFTY | -Rs 4,374 |
| RB, Rs 5,000 budget | SENSEX, FINNIFTY | -Rs 4,374 |
| K1-BN | BANKNIFTY | -Rs 2,440 |
| K1-strong | SENSEX | -Rs 1,765 |
| X-skip / X-K2 (skip when NIFTY's move > 5 times usual) | none | Rs 0 |

The four losses add to Rs 9,722 (the app showed Rs 9,721, a rounding of paise). For RB, each position's own loss stands in
for its premium x lot x 15%.

So yes: X-skip would have saved all of 19 Oct. **But over the 16 months of design, X-skip kept only 28% of the net.** The
shock bars are usually Breadth's best signals, not its worst (see "Why" below).

## The table (design 20 Nov 2024 - 31 Mar 2026; 1 lot, net after all charges and slippage)

"Acct stop" = months worse than -Rs 13k / drawdowns deeper than Rs 40k. "Qtrs" = walk-forward quarters (Apr 2025 -
Mar 2026) that beat K0 on net AND on worst day. "Twin" = the random-subset twin's mean net and p (500 twins).
q is Benjamini-Hochberg over all 8.

| rule | trades | net (% of K0) | worst day (cut) | worst signal | max DD (cut) | acct stop | green days | net without top 5 | WF net (% of K0) | qtrs | twin mean, p (q) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **K0 as now** | 3,071 | **Rs 2,16,929** | -Rs 58,613 | -Rs 26,277 | Rs 1,99,077 | 5 / 5 | 46% | Rs 1,60,476 | Rs 3,35,883 | - | - | baseline |
| K2-first: 2 per signal, the app's order | 2,314 | Rs 30,932 (14%) | -Rs 26,334 (55%) | -Rs 13,786 | Rs 1,95,227 (2%) | 5 / 3 | 41% | -Rs 24,103 | Rs 1,81,845 (54%) | 1 | Rs 27,294, 0.45 (1.0) | FAIL |
| K2-strong: 2 biggest breaks | 2,332 | -Rs 29,774 | -Rs 27,914 (52%) | -Rs 14,684 | Rs 1,96,285 (1%) | 5 / 2 | 41% | -Rs 83,403 | Rs 1,01,121 (30%) | 0 | Rs 27,294, 0.95 (1.0) | FAIL |
| K1-strong: the biggest break only | 1,443 | -Rs 1,31,636 | -Rs 14,877 (75%) | -Rs 11,511 | Rs 1,71,405 (14%) | 6 / 1 | 41% | -Rs 1,79,390 | -Rs 151 (0%) | 0 | -Rs 84,949, 0.91 (1.0) | FAIL |
| K1-BN: BANKNIFTY, else the biggest | 1,439 | -Rs 45,912 | -Rs 13,259 (77%) | -Rs 5,822 | Rs 1,27,557 (36%) | 7 / 1 | 40% | -Rs 97,756 | Rs 62,295 (19%) | 1 | -Rs 84,949, 0.13 (1.0) | FAIL |
| X-skip: skip if NIFTY > 5x usual | 2,756 | Rs 61,419 (28%) | -Rs 39,585 (32%) | -Rs 25,077 | Rs 2,28,635 (worse) | 5 / 1 | 44% | Rs 6,580 | Rs 2,54,861 (76%) | 1 | Rs 1,64,435, 0.92 (1.0) | FAIL |
| X-K2: X-skip, then 2 biggest | 2,015 | -Rs 1,66,789 | -Rs 17,708 (70%) | -Rs 10,662 | Rs 2,24,434 (worse) | 7 / 1 | 40% | -Rs 2,14,138 | Rs 22,281 (7%) | 0 | -Rs 13,848, 0.998 (1.0) | FAIL |
| RB: Rs 5,000 risk per signal | 2,097 | -Rs 22,399 | -Rs 15,266 (74%) | -Rs 11,511 | Rs 1,80,377 (9%) | 7 / 3 | 43% | -Rs 72,124 | Rs 87,971 (26%) | 1 | Rs 34,062, 0.96 (1.0) | FAIL |
| *K2-open: at most 2 open at once, the app's cap (post hoc)* | 1,755 | -Rs 19,377 | -Rs 22,220 (62%) | -Rs 13,786 | Rs 1,72,230 (13%) | 7 / 1 | 43% | -Rs 74,126 | Rs 1,12,723 (34%) | 0 | -Rs 18,258, 0.52 (1.0) | FAIL |

Every rule fails at least three of the four parts of the pass rule. The best twin p is K1-BN's, 0.13, which is q 0.91 over the 7
pre-registered rules. **The final window (Apr - Oct 2026) was not read**, as pre-registered: it is read only for a rule
that passes design and walk-forward. Each rule's entry is in `research/experiments/entries/r84-*.yaml`.

## Why it fails (design data, `results/diag.json`)

- **The money is in the wide signals.** By how many positions K0 actually took on a signal:
  - 1 position: 214 signals, -Rs 5,747;
  - 3 positions: 175 signals, -Rs 1,15,413;
  - 4 positions: 367 signals, +Rs 1,89,075;
  - 5 positions: 168 signals, +Rs 1,55,881 (55% of them green).
  A cap trims exactly the 4- and 5-position signals, the ones that pay.
- **"The biggest break" is the worst pick.** Ranking each signal's positions by break size in ATR:
  - the biggest break made only Rs 15,190 over 936 trades;
  - the 4th-biggest made Rs 1,09,806 over 535 trades.
  The index that has run furthest past its channel is the one most likely to snap back. That is why K1-strong and
  K2-strong lose more than random picks.
- **Shock bars are good bars, on average.** By NIFTY's 10-minute move at the signal, against its usual:
  - 5-10 times usual: 142 signals, +Rs 1,43,786, 53% green;
  - more than 10 times: 27 signals, +Rs 12,497;
  - 2 times usual or less: 250 signals, **-Rs 1,97,230**.
  19 Oct was a shock bar that reversed. Most shock bars kept going. (The calm-bar result is an observation after the
  fact, not a tested rule. It was not pre-registered, so it is a note only.)
- **A per-signal cap does not cap exposure.** C1 fires on every bar while 4 or more indices stay past their channels. An
  index left out on one bar is free on the next and usually enters 5 minutes later, at a worse price. For example,
  K2-first took 675 such later entries that K0 never took, and they lost Rs 48,286. The app's cap (counting open
  positions, K2-open) blocks that, but loses the wide winners all the same.
- **The drawdown is not a one-signal problem.** K0's Rs 2 lakh design drawdown is a long run of ordinary losing signals.
  Holding fewer positions per signal cuts the worst day, but not that run. That is why no rule reached a 30% smaller
  drawdown except K1-BN (36%), which lost money.
- **The account stop fails with every rule.** Every rule still has 5-7 months worse than -Rs 13k, and 1-5 drawdowns
  deeper than Rs 40k, in design. R75 found the same.

## Recommendation for Boss

1. **No rule from R84 goes to paper.** Nothing passed, so per the rules nothing changes on its account. A loss of about
   Rs 10k on one signal happens to Breadth Donchian about twice a month, and no rule about "how many positions per
   signal" removes it without removing the profit too.
2. **The same-direction cap and Breadth Donchian do not fit together.** On Breadth alone the cap cuts the worst day,
   but it turns the design profit (+Rs 2.17 lakh) into a loss (-Rs 19k). So while the cap is on, Breadth's paper record
   tests a rule that lost money in research, not the R72 rule. The choice is Boss's:
   - **(a) My recommendation, matching what was done for R51 today: make R72 Breadth Donchian record-only on paper.**
     It still logs every signal and every would-be trade, so its uncapped record keeps building for free, and no
     Breadth loss reaches the paper book. This only lowers risk.
   - (b) Let Breadth trade uncapped on paper (exempt it from the cap), knowing that about Rs 10k signal losses are
     normal.
   - Keeping the cap on Breadth as it is now is the one option that gives neither a clean test nor the edge.
3. **Breadth Donchian is not a live candidate in this form.** At 1 lot on five indices it breaks the -Rs 40k / -Rs 13k
   account stop in design with or without these rules. As R75 said, that is a size question (fewer indices or fewer
   lots), and a live one is Boss's alone.
4. **Do not re-tune it again.** Donchian stays frozen (R75). R84 changed only the number of positions held, not the
   signal, and it did not read the final window.

No variant passed, so there is no `research/hunt/r84/APP_SPEC.md`.

## How it was measured

- **Signal source:** the indices' own 5-minute bars (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), read together (R72
  C1, 4 of 5). The rules read the break size in the index's own 5-minute ATR, NIFTY's own 10-minute move, or (RB only)
  the chosen option's last price as a size input. No rule reads an option's chart.
- **Engine:** R75's harness, imported read-only: R64's engine on R51's Amendment A clean stores (rebuilt from the Drive
  lake files of 15 Oct), 1-ITM, exits P (-15%, 30/60/100 ladder + L25, out after 30 minutes, square-off 15:15),
  next-minute fills, R17 liquidity fills, dated charges, 1 lot in force.
  - Per index: one open position a side, at most 2 entries a side a day, never on the index's own expiry day.
  - The only change is the walk: one pass over all five indices in time order, so a rule can choose among one signal's
    positions.
  - With every position chosen, it reproduces R75's C to the rupee: Rs 2,16,929 on 3,071 trades (tested).
- **First to the door:** approximated by the app's own order of one bar's signals (NIFTY, BANKNIFTY, FINNIFTY,
  MIDCPNIFTY, SENSEX; `BreadthDonchianArm.signals`). R84 models Breadth alone. The app's real cap counts every arm's
  automatic positions, so in the app Breadth can get fewer than 2.
- **Twins:** 500 per rule (seed 84). Each keeps the same number of positions per signal, chosen at random. For X-skip,
  it skips the same number of signals at random.
- **Tests:** `research/hunt/r84/test_r84.py`, 18 tests:
  - the walker equals per-index trade-taking on 200 random days;
  - every rule on hand-made signals, and the open-position cap;
  - look-ahead guards for the break size, the 10-minute move and the usual move;
  - the twins keep the same count;
  - the pass rule and the 19 Oct cost table;
  - K0 reproducing R75 on the real stores.
- **Checks run:**
  - `research/experiments/validate` passes, and `features catalog --check` is up to date.
  - `pytest research/brain/tests`: 134 pass and 5 fail. They are the same 5 that R82 found failing in this sandbox on a
    clean branch, caused by library versions (pandas 3 prints floats differently). R84 does not touch `research/brain`.

## Honest limits

- **19 Oct is half rebuilt.** The index side comes from Yahoo (SENSEX's Yahoo prints are rough), and the option side is
  the app's own paper record. RB's premium risk on 19 Oct is approximated by each position's loss.
- **One post-hoc rule.** K2-open was added after the first design results, labelled and counted in BH. It failed anyway.
- **Design only.** R84 decided on design and walk-forward, as pre-registered. Because nothing passed, the final window
  was not read. That keeps it as clean as it still is after R72, R73 and R75.
- **Fixed settings.** k = 5 and the Rs 5,000 budget were fixed before the run. Other settings were not tried; trying them
  would be the signal re-tuning R75 ruled out.

## To re-run

```
rclone copy gdrive:IraAlgo-data-lake/index_1m $IRAALGO_LAKE --include "index_1m_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX,INDIAVIX}_202[456].parquet"
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m $IRAALGO_LAKE --include "opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX}_WEEK_202[456].parquet"
R51_CLEAN=1 python research/hunt/r51/r51data.py build
python research/hunt/r84/r84study.py design     # K0 check, 8 rules, twins, BH -> results/design.json
python research/hunt/r84/r84diag.py             # why (design only) -> results/diag.json
python research/hunt/r84/r84day19.py fetch       # Yahoo 1-minute index bars (last 8 days) -> /dev/shm/yahoo1m.json
python research/hunt/r84/r84study.py day19
python research/hunt/r84/r84entries.py
```
