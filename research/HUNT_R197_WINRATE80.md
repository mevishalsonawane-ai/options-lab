# R197: can option selling reach an 80% win rate and still make money? (side study, 18 Nov 2026)

Boss: "Do whatever you can try to get me 80% winrate, and survay says, 60-70% people win in option selling, so i want to
get 80% with more precision. Do all the combination/profility, math, and all the study we have done until now, use all
dont miss anything". **Research only. The app stays buy-only.**

- **Pre-registration:** `research/hunt/r197_winrate80/PREREG.md` (hash-stamped before any number). It fixed a 45-test
  grid, a design-only selection rule, 11 extension tests and the gates (BH m = 4,877).
- **Code:** `r197engine.py` (spread with a target), R196's pair loop with a target (off by default; R196 unchanged),
  `r197study.py`, `r197frontier.py`, `r197report.py`; 5 tests in `test_r197.py`.
- **Tables:** `results/report.md` (the grid), `results/frontier.md` (every selling rule we have tested, 106 rules).

## Verdict

**No option-selling rule we have ever tested reaches 80% wins, and none that comes near it makes money.**

- **The grid (45 tests on NIFTY, 2021-2025):** every combination of strike distance (ATM, 2 or 4 steps out), stop per
  leg (25%, 50%, 100%) and profit target (none, 20%, 35%, 50% of the credit) on the 09:20 straddle / strangle, and 9 put
  credit spreads (2, 4 or 6 steps out; no target, 30%, 50%).
  - The highest win rate was **71.7%** (Wilson 95%: 68.6%-74.7%, 860 trades): a put spread 4 steps out with a 30%
    target. It **lost -Rs 93,059**, and 65.7% / -Rs 29,676 in 2026.
  - No straddle or strangle setting got above 63.5%.
  - The pre-registered selection therefore found **no pick**, so there were no extensions to test.
- **All studies together** (R103, R195, R196, R197, 106 rules): 0 at or above 80%.
  - The only rows near 70% are R103's BANKNIFTY flies. They were a stale-price artefact: repriced, they won 42-47% and
    lost -Rs 2.9 to -Rs 3.3 lakh.
  - The real money-makers sit at 60-62% wins (the 09:20 straddle with a stop per leg, best version +Rs 2.31 lakh over
    2021-2025). They still break the account stop and need about Rs 2-2.6 lakh of margin.

## The math, and why 80% does not come

At win rate W the rule makes money only if the average loss b is under W / (1 - W) times the average win a. At 80%,
b must be under 4a.

| rule (design 2021-2025) | win | avg win a | avg loss b | b / a | win rate needed to break even |
|---|---|---|---|---|---|
| put spread 4 out, 30% target (highest win rate) | 71.7% | Rs 381 | Rs 1,351 | 3.5 | 78.0% |
| put spread 2 out, 30% target | 70.8% | Rs 649 | Rs 1,805 | 2.8 | 73.6% |
| straddle, 50% stop per leg, no target (the most money: +Rs 2,30,873) | 61.4% | Rs 1,754 | Rs 2,238 | 1.28 | 56.1% |
| straddle, 25% stop per leg, no target (R195's S1) | 60.2% | Rs 2,019 | Rs 2,596 | 1.29 | 56.3% |
| straddle, 25% stop, 20% target | 62.6% | Rs 1,696 | Rs 2,608 | 1.54 | 60.6% |

- **Taking profit early pushes the win rate up a little, but the losses stay the same size.** On the put spread a
  30% target lifted the win rate from 60% to 71%. The average win shrank to Rs 381-649, while a losing day still cost
  Rs 1,350-1,800. The rule needed 74-78% wins to break even and got 71%.
- **Intraday, a weekly straddle decays only a few percent on a normal day.** Most days end near flat. A 20% target is
  hit on only about 6 days in 10, and the charges come out of every trade, so the win rate stays around 60-63%.
- **"60-70% of sellers win" is per trade, and that matches what we measured: 55-72%.** It does not mean 60-70% of
  sellers make money. SEBI's FY25 study found about 91% of individual F&O traders lost money over the year (net losses
  about Rs 1.06 lakh crore), and FY22-FY24's study found 93%. A seller can win most trades and still lose overall,
  which is what the losing 72% put spread did.

## What would honestly give a higher win rate

Only things that make each win smaller or each loss rarer-but-bigger: far-OTM strikes held to expiry without a stop
(naked; excluded by our safety rules and the account stop), or a hedge so far out it hardly protects. Both move the
risk into rare big days. R195's baselines showed what those days cost: -Rs 28k (NIFTY) to -Rs 64k (FINNIFTY) on
4 Jun 2024, at one lot. **We do not recommend chasing 80% this way.** Buying-side arms are judged by money and
drawdown, and on those the selling rules lost (R195-R197).

## Caveats

R195's caveats apply: modelled slippage (no bid / ask), a margin model, today's lot for every year, and stale monthly
prints. 2026 is a second look (R195 read it). The 11 extension tests were pre-registered but not run (there was no pick);
they still count in BH's m. The forward window (from 16 Nov 2026) stays unread; nothing earned a forward test.
