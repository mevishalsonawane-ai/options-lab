# R99: catching the 100-point premium move on index options (21 Oct 2026)

Boss, 21 Oct: catch the 100+ rupee move (after R97 / R98 on gold, "Continue now"). Research only; nothing goes to paper
or live. Code, tests and numbers: `research/hunt/r99_nifty100/` (`results/scan.json`, `results/design.json`).
Pre-registration `hunt/r99_nifty100/PREREG.md`, committed and pushed (543fc366) before any rule was priced.

**Signal source: the index** (NIFTY; for R99B / C the four indices Solo trades). Trade: the ATM weekly option.
"100 rupees" = 100 points of the option premium (x the lot: Rs 7,500 on a 75-unit NIFTY lot, Rs 6,500 on 65).
Prices: R51's held-contract engine unchanged (real prints, lakeclean, R17 fills, dated charges and lots).

## The answer for Boss

**R99: LEADS. 0 of 3 pass; the final period was not opened.** One lead worth following:

1. **On NIFTY, cost is not the problem; direction and decay are.** A round trip costs about Rs 60-75 of charges a
   lot (about 1% of a +100 move; on gold it was about 30%). But buying the ATM option at a random moment for +100
   (stop -40) loses **Rs 161 a trade** (42,334 moments, 336 sessions): +100 comes only 9.2% of the time, the stop 29%,
   and 62% of trades just decay into the square-off.
2. **No single index condition fixes that** (0 of 218 conditions significant; the best +Rs 115 a trade, p 0.54), and
   a machine-learning model on all of them lost too (R99A: 238 trades, -Rs 43,085).
3. **Boss's own winner, Solo 2.0, already has the entry edge** (reproduced exactly: 166 design trades, +Rs 55,910).
   On those same trades:
   - forcing a fixed **+100 target / -40 stop** made it worse: **-Rs 12,284** (R99B; -Rs 68,194 vs Solo's exits);
   - **letting winners run** (Solo's stop, no target, lock +25 once +50, trail 40 points) made it **+Rs 89,242**,
     i.e. **+Rs 33,333 more than Solo's own exits** (R99C). That is the lead - but it is not proven: the whole gain
     came in Jan-Mar 2026 (+Rs 55,179); in Jul-Sep 2025 it was Rs 20,927 worse and Oct-Dec Rs 919 worse; bootstrap
     p 0.245. It may be a trending-quarter effect.

So: "catch 100 in any condition" does not work as an entry rule on NIFTY either; the edge must come from the entry
(Solo's), and the open question is only how long to hold Solo's winners. R99C is a note, not a change: it failed the
gates, so by the paper rule (CLAUDE.md) it stays a note.

## Step A-B: NIFTY base rates and the scan (design 21 Nov 2024 - 31 Mar 2026)

| | |
|---|---|
| NIFTY day range | median 211 points (p25 153, p75 275) |
| ATM weekly premium | median 117 (p25 74, p75 153); expiry days 43 |
| ATM call reaches +25 / +50 / +100 later the same day (from 09:30-13:30) | 51% / 30% / 12.6% (1,337) |
| charges, 120 -> 220 on 75 units / 120 -> 70 | Rs 75 / Rs 59 |
| buy ATM, +100 target / -40 stop / square-off, every 5 min 09:20-14:30, calls and puts | target 9.2%, stop 29.3%, square-off 61.5%; -Rs 161 a trade (42,334); calls -Rs 274, puts -Rs 48 |
| same with +50 / -25 | target 21.2%, stop 48.1%; -Rs 175 a trade |
| expiry days vs others (+100 trade) | -Rs 250 vs -Rs 137 a trade |

Scan: 218 one-feature buckets (index candles and indicators, stretch from the session average, opening-range and
previous-day breaks, VIX level and change, sessions to expiry, premium as % of the index, clock), pooled net per
trade with a day-clustered p, BH: **none positive at q <= 0.10**. Top: gap of 64+ points with the trade +Rs 115
(8,568, p 0.54); 10-35 points above the session average +Rs 87 (p 0.46). (A first version averaged each day's mean and
overstated tilts, e.g. +Rs 510 for opening-range breaks whose pooled net is -Rs 167; it was replaced before any rule
was chosen.)

## Step C: the three pre-registered ideas (design walk-forward)

| idea | trades | net | vs Solo's own exits | p (BH q over 3) | quarters | verdict |
|---|---|---|---|---|---|---|
| R99A NIFTY model, +100 / -40 trade | 238 | **-Rs 43,085** | - | twin 0.571 (0.856) | -20,149 / -23,302 / -6,272 / +6,638 | dies |
| R99B Solo entries, +100 / -40 exit | 166 | **-Rs 12,284** | -Rs 68,194 | 0.915 (0.915) | diff: - / -32,181 / -38,361 / +2,349 | dies |
| R99C Solo entries, let it run | 166 | **+Rs 89,242** | **+Rs 33,333** | 0.245 (0.735) | diff: - / -20,927 / -919 / +55,179 | lead (fails p, BH, quarters) |
| reference: Solo 2.0 A2, its own exits (R57) | 166 | +Rs 55,910 | - | - | +17,994 / +26,061 / +11,855 | (R57) |

R99C detail: win 59%, Rs 538 a trade, worst month -Rs 12,196, max drawdown -Rs 33,899, at 2x fills +Rs 81,201;
exits 64 lock, 62 square-off, 40 stop. Solo's own exits are mostly the time stop (150 of 166). A2 trades are mostly
MIDCPNIFTY (90), FINNIFTY (46) and BANKNIFTY (27); only 3 are NIFTY.

**The final period (1 Apr - 13 Oct 2026) was not read**: nothing passed. It would not be fresh anyway (other index
studies have read it).

## What would test the lead properly

R99C needs its own pre-registration on data it has not seen: the cleanest is the forward period from 14 Oct 2026, on
paper as a SHADOW log next to Solo's real exits (logged only, nothing changed), for at least 3 months or 60 trades,
then the same paired test. That needs Boss's go-ahead; it is not switched on.

## Ideas logged

`research/experiments/entries/r99-scan.yaml` (measurement) and `r99-a`, `r99-b`, `r99-c` (rejected; r99-c noted as a
lead).
