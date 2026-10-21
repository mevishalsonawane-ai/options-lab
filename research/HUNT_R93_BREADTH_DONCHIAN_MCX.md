# R93: does Breadth Donchian work on commodities? (21 Oct 2026)

Boss, 21 Oct: *"Just check if Breadth Donchian works on commodities or not."*

Research only: nothing in `android/**` or `.github/**` changed. Pre-registered in
`research/hunt/r93_breadth_donchian_mcx/PREREG.md` (commit 4e5ba0f, before any statistic); code and tests frozen in
9b35f84 before any result; implementation notes N1-N4 in `hunt/r93_breadth_donchian_mcx/NOTES.md` (each made before the
result it could affect). Every number: `hunt/r93_breadth_donchian_mcx/results/` (`design.json`, `holdout.json`,
`report.md`; the holdout was read once, `HOLDOUT_READ` 21 Oct 09:59 UTC). Experiment entries `r93-*.yaml` (5).

**Signal source of every rule: the MCX future itself** (the near future under each option book; daily: the near-month
daily, back-adjusted for rolls). I-C also reads the international driver's own price (XAUUSD, XAGUSD, WTI, Henry Hub,
COMEX copper). Gold, silver, crude and gas **buy the 1-ITM option** (GOLDM, SILVERM, CRUDEOILM, NATGASMINI, real
prints); copper, zinc and aluminium **trade the future**, because their options barely trade (1-ITM printed in 0-12% of
minutes) or do not exist. The daily rules trade the future (no option history before Aug 2025).

## The answer

**No. Neither Breadth Donchian nor plain Donchian works on MCX commodities. 0 of 5 variants pass; nothing goes to
paper, so there is no APP_SPEC.**

1. **On 5-minute bars (the app's Breadth Donchian, moved to MCX), a Donchian break on a commodity has no edge even
   before costs.** In the holdout, the option trades earned between -Rs 82 and +Rs 86 a trade before costs (by book and
   variant), against Rs 100-520 of costs a trade. Every variant lost in every month (7 of 7 holdout months below the -Rs 13k account
   stop) and did no better than random entries on the same days and sides.
2. **Breadth helps a little on MCX, but far too little.** Breaks that the rest of the group joins are a bit better than
   lonely breaks (holdout gross a trade higher in 7 of 7 commodities), so the idea behind R72 is not wrong. But on
   MCX the improvement is tens of rupees a trade, and an MCX option round trip costs hundreds. On the indices,
   confirmed breaks made +Rs 183 a trade and unconfirmed lost -Rs 54 (R72); on MCX **both lose** (holdout
   -Rs 805 vs -Rs 914 a trade).
3. **Daily Donchian made money on futures, but only because metals went up.** Plain daily Donchian made +Rs 2.75 lakh
   in design (2012-2022, 1,103 trades) and +Rs 7.26 lakh in the holdout (2023 - Oct 2026, 367 trades). Random entries on
   the same sides in the same years made as much (+Rs 7.45 lakh; twin p 0.51), and without its 5 best trades it lost
   -Rs 5.76 lakh. That is the 2023-26 silver and copper rally, not the breakout. Daily breadth lost in both periods.
4. **The sample is long enough to know.** Intraday holdout: 1,957-3,365 trades per variant (142 sessions); even the
   optimistic end of each book's estimate (the 95% upper bound of its gross a trade) is below its cost a trade, in every
   book. The closest is gold with breadth (upper bound Rs 265 against Rs 364 of cost). Daily: 14 years. What is short
   is the intraday history (14.5 months), so a regime change could still matter, but nothing here is close to passing.

## The results (1 lot, net after real MCX charges and slippage)

Locked holdout: intraday 1 Apr - 19 Oct 2026 (142 sessions), daily 1 Jan 2023 - 16 Oct 2026. Read once.

| variant (signal on the MCX future) | design trades / net | walk-forward | holdout trades | holdout gross (before costs) | holdout net | win | worst month (below -13k) | max DD | twin avg | twin p (BH q) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **I-A** plain Donchian 20, 5-minute | 3,443 / -Rs 31.09 lakh | -Rs 23.13 lakh | 3,365 | +Rs 1.54 lakh | **-Rs 30.06 lakh** | 24% | -Rs 4.85 lakh (7 of 7) | Rs 30.06 lakh | -Rs 31.38 lakh | 0.148 (0.394) | fail |
| **I-B** complex breadth, 5-minute (R72's rule on MCX) | 1,597 / -Rs 16.52 lakh | -Rs 11.92 lakh | 1,957 | +Rs 1.53 lakh | **-Rs 17.93 lakh** | 28% | -Rs 3.41 lakh (7 of 7) | Rs 17.93 lakh | -Rs 18.33 lakh | 0.349 (0.582) | fail |
| **I-C** cross-market confirmation, 5-minute | 2,542 / -Rs 20.20 lakh | -Rs 13.26 lakh | 2,485 | +Rs 1.97 lakh | **-Rs 22.57 lakh** | 27% | -Rs 4.35 lakh (7 of 7) | Rs 22.57 lakh | -Rs 23.86 lakh | 0.158 (0.394) | fail |
| **D-A** plain Donchian 20, daily (futures) | 1,103 / +Rs 2.75 lakh | +Rs 1.51 lakh | 367 | +Rs 10.33 lakh | **+Rs 7.26 lakh** | 49% | -Rs 1.87 lakh (22 of 45) | Rs 6.78 lakh | +Rs 7.45 lakh | 0.509 (0.636) | fail: no better than random; -Rs 5.76 lakh without top 5 |
| **D-B** complex breadth, daily (futures) | 373 / -Rs 39,447 | -Rs 1.13 lakh | 133 | -Rs 2.23 lakh | **-Rs 3.68 lakh** | 45% | -Rs 8.07 lakh (14 of 30) | Rs 11.75 lakh | +Rs 2.53 lakh | 0.856 (0.856) | fail |

Pass rule (all must hold, PREREG s7): holdout net > 0, >= 40 trades, twin p < 0.05 and BH q <= 0.10 (5 variants),
design net > 0 and walk-forward > 0, holdout net > 0 without the top 5 trades and without the best commodity, and > 0
at twice the spread. Intraday variants fail all of them; D-A fails the twin, BH and top-5 gates.

The big rupee numbers of I-A to I-C come mostly from the copper future (2,500 kg; -Rs 20.29 lakh of I-A's holdout).
**The option arms on their own** (what an MCX Breadth Donchian paper arm would trade) also lose:

| holdout, options only | gold + silver (GOLDM, SILVERM) | crude + gas (CRUDEOILM, NATGASMINI) |
|---|---|---|
| I-A plain | 1,024 trades, -Rs 4.82 lakh (7 of 7 months below -13k) | 1,065 trades, -Rs 1.10 lakh (6 of 7) |
| I-B breadth | 653 trades, -Rs 2.33 lakh (7 of 7) | 594 trades, -Rs 57,064 (0 of 7) |
| I-C cross-market | 962 trades, -Rs 4.57 lakh (7 of 7) | 1,048 trades, -Rs 1.03 lakh (5 of 7) |

### Per commodity, holdout, I-B (the R72 rule on MCX)

| commodity (traded) | trades | win | avg win / loss | gross | net | worst month (below -13k) |
|---|---|---|---|---|---|---|
| GOLD (GOLDM option) | 337 | 38% | Rs 1,312 / -Rs 1,240 | +Rs 28,901 | -Rs 93,801 | -Rs 32,346 (4 of 7) |
| SILVER (SILVERM option) | 316 | 34% | Rs 1,609 / -Rs 1,520 | +Rs 6,039 | -Rs 1,39,298 | -Rs 39,367 (4 of 7) |
| CRUDE (CRUDEOILM option) | 295 | 34% | Rs 192 / -Rs 225 | +Rs 5,036 | -Rs 25,056 | -Rs 5,481 (0 of 7) |
| NATGAS (NATGASMINI option) | 299 | 20% | Rs 120 / -Rs 165 | -Rs 483 | -Rs 32,009 | -Rs 5,752 (0 of 7) |
| COPPER (future) | 356 | 21% | Rs 6,067 / -Rs 6,251 | +Rs 1,13,375 | -Rs 13,26,165 | -Rs 2,56,396 (7 of 7) |
| ZINC (ZINCMINI future) | 271 | 20% | Rs 637 / -Rs 844 | -Rs 9,050 | -Rs 1,47,190 | -Rs 31,242 (5 of 7) |
| ALUMINIUM (ALUMINI future, from 24 Jul) | 83 | 24% | Rs 590 / -Rs 649 | +Rs 9,050 | -Rs 29,087 | -Rs 15,955 (1 of 3) |

Every commodity, every intraday variant: net negative in the holdout (and in design). Full tables per variant,
commodity and group, design and holdout: `results/report.md`.

## Does breadth help on MCX as it did on the indices?

The plain Donchian trades (I-A), split by whether the rest of the group broke the same way on the same bar:

| | design: trades, net a trade | holdout: trades, net a trade |
|---|---|---|
| **indices (R72 final, for comparison)** | | confirmed 752, **+Rs 183**; unconfirmed 1,029, **-Rs 54** |
| MCX, confirmed by the group (I-B) | 559, -Rs 502 | 640, -Rs 805 |
| MCX, not confirmed | 2,884, -Rs 981 | 2,725, -Rs 914 |
| MCX, confirmed by the international driver (I-C) | 1,660, -Rs 605 | 1,512, -Rs 769 |
| MCX, not confirmed by the driver | 1,783, -Rs 1,180 | 1,853, -Rs 995 |

Gross a trade (before costs), holdout, confirmed vs not: aluminium +Rs 189 / -Rs 24, copper +Rs 819 / +Rs 193, crude
+Rs 20 / +Rs 14, gold +Rs 103 / +Rs 38, gas +Rs 8 / -Rs 6, silver +Rs 24 / -Rs 89, zinc +Rs 135 / -Rs 88. In design
it was mixed (better on copper, silver, crude; worse on gold and zinc; equal on gas). So breadth tilts the odds a
little, as on the indices, but the whole 5-minute move a commodity break gives is about the size of the spread.
Why the indices differ: NSE index options are liquid at the strike and cheap to trade next to an index's
5-minute move; the MCX books are thin (GOLDM and SILVERM 1-ITM print in about a
quarter of minutes), a round trip costs 0.6-1.2% of the premium in
spread alone (GOLDM's 1-ITM premium is about Rs 2,850 a unit, SILVERM's Rs 7,700), and a 30-minute commodity move after
a break is small (the futures' gross is about zero).

Daily (D-A trades split by D-B): confirmed +Rs 1,194 a trade (202) vs +Rs 38 (901) in design; +Rs 7,584 (81) vs
+Rs 389 (286) in the holdout. Better when confirmed for copper, silver and zinc in both periods, worse for gold, crude
and gas in both. But the tradeable form (D-B) lost in both periods, the samples are 5-43 trades per commodity, and
this split was looked at after the fact. A lead at most, not a finding (see "What next").

## How it was measured

- **Data** (Drive lake, never committed): the ten MCX option books' near-future minutes and ATM-3..+3 option minutes
  (5 Aug 2025 - 19 Oct 2026, 309 sessions; R53 `Market`, R42 bad-print filter); ALUMINIUM's continuous future
  (24 Jul - 19 Oct 2026, 56 sessions; it has no option book); the near-month daily series 2012 - 16 Oct 2026, rolls found
  from open-interest jumps (causal, N2) and back-adjusted for the channel; Dukascopy's free 1-minute drivers (fetched
  21 Oct, 0 failed days; clock alignment checked: 1-minute correlation with the MCX future peaks at lag 0, 0.66-0.87,
  copper 0.29).
- **The rule, as R72 / the app, moved to MCX hours (d):** 5-minute close-only bars from 09:00; Donchian 20 (close beyond
  the previous 20 bars' high / low), never across a contract change; groups metals = GOLD, SILVER, COPPER, ZINC,
  ALUMINIUM (minis with their parent, once), energy = CRUDE, NATGAS; breadth = at least 3 of 5 metals / 2 of 2 energy
  on the same bar (a missing commodity counts as not breaking, as in the app). 1-ITM strike, filled at the first print
  within 3 minutes after the bar; fills 09:10 to the close - 20 minutes, square-off at the close - 15 (23:15 / 23:40 by
  US time); never on the option's expiry day; one position a side, 2 entries a side a day per commodity; R72's exits XL
  (stop -15% on the premium, +5% once +25%, the 30/60/100 ladder, out after 30 minutes). Futures: stop when a minute
  closes back through the channel's mid, out after 30 minutes. Daily: next open, stop 2 x ATR14, chandelier lock
  3 x ATR14, out after 10 sessions or before a roll.
- **Costs:** Zerodha MCX option / futures charges plus a half-spread on each side (options 0.30% from 17:00 / 0.60%
  before, NATGASMINI 0.45% / 0.90%; futures 0.03-0.06%, metals 0.05%), and every gate again at twice the spread.
  The 0.05% futures spread for copper, zinc and aluminium is generous (a few ticks is nearer 0.01%), but it does not
  change the answer: with exchange charges only and no spread at all (post-hoc check), copper still lost -Rs 1.62 lakh,
  zinc -Rs 86,116 and aluminium -Rs 19,993 on I-A's holdout trades.
- **Twins:** 500 sets; each trade replaced by a random 5-minute bar on the same day, commodity and side whose entry
  fills (daily: a random session of the same year), same exits (N1, N3). BH across the 5 variants.
- **Walk-forward:** intraday Nov-Dec 2025, Jan-Feb 2026, Mar 2026; daily 2019-2022 by year. Nothing was tuned; the
  selection test (trade the best variant so far in the next fold) picked I-B every time (-Rs 11.92 lakh) and, daily,
  D-A, D-B, D-B, D-A (+Rs 36,825).
- **Tests:** `research/hunt/r93_breadth_donchian_mcx/tests/` (38): bars, channel and contract changes, breadth with a
  missing member, the driver check, daily rolls and stops, R72's XL engine on an R53 Market (stop, gap, L25, ladder,
  breakeven, time, square-off, expiry skip), futures exits, costs, caps, twins, BH, gates, the one-time lock, and the
  look-ahead guard (every row after a cut deleted or perturbed, intraday and daily; a leaky canary is caught).

## Honest limits

- Intraday history is short (14.5 months; aluminium 3 months, in the holdout only). The holdout overlaps windows used
  by R44 (5m Donchian on CRUDEOIL options), R53, R83, R85 and MB1 (MCX), and the daily holdout overlaps R82 / R83's
  daily Donchian work; none of them ran the breadth or cross-market rules.
- Close-only 5-minute bars (from minute closes) are a little narrower than the app's true bars, so breaks are a little
  more frequent here. 20-24% of gold and silver option exits used a stale last print (thin books); crude and gas 1-2%.
- Zinc's option book shows no April 2026 expiry (N4): one contract change may not have blanked zinc's channel.
- The daily study is futures only, 10-day holds and big lots (copper 2,500 kg: average win Rs 64,661 in the holdout),
  which is not Boss's "enter, take profit or stop, exit" intraday style.

## What this means for Boss

1. **Do not add an MCX Breadth Donchian arm**, on paper or live. Keep Breadth Donchian on the indices only.
2. The breadth idea is sound (confirmed breaks are a little better on commodities too), but MCX option costs eat
   anything a 5-minute commodity break gives. This matches every MCX study so far (M3, M4, R53, R83, R85, MB1):
   **on MCX, costs decide**.
3. If wanted later, one pre-registered follow-up on unseen data (after 19 Oct 2026): "the first daily break of a
   metals-wide move" (D-A trades confirmed by D-B: +Rs 7,584 a trade on 81 holdout trades, looked at after the fact),
   on silver and copper futures, sized for the account stop. It is a note, not a result.

## Files

`research/hunt/r93_breadth_donchian_mcx/`: `PREREG.md`, `NOTES.md`, `cfg.py` (settings), `rules.py` (signals),
`engine.py` (trade engine and costs), `study.py` (build, design, holdout), `report.py`, `entries.py`, `fetch_proxy.py`
(Dukascopy drivers), `tests/`, `results/` (`design.json`, `holdout.json`, `report.md`, `HOLDOUT_READ`).
Commands:
`IRAALGO_LAKE=~/lake python -m research.hunt.r93_breadth_donchian_mcx.study build|design|holdout` (holdout once), then
`... .report` and `... .entries`.
