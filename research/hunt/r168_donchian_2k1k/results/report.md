# R168: R51 / R72 with a +₹2,000 target and a −₹1,000 stop (13 Nov 2026)

## For Boss, in short

**It does not work: the win rate falls and the profit goes, at every lot size.** Nothing changes in the app.

**"The year" = Apr 2025 – Mar 2026** (12 months, nothing fitted). ₹ after all charges, for the whole position:

| arm | lots | today's exits: trades / win / profit | +₹2k / −₹1k: trades / win / profit |
|---|---|---|---|
| R72 | 1 | 2,238 / 49.0% / **₹3,35,882** | 2,338 / 39.9% / **₹5,721** |
| R72 | 2 | 2,238 / 49.5% / ₹7,75,437 | 2,442 / 32.5% / −₹2,60,620 |
| R72 | 3 | 2,238 / 49.7% / ₹12,13,421 | 2,486 / 31.3% / −₹2,97,054 |
| R51 | 1 | 3,044 / 45.9% / **₹2,13,748** | 3,128 / 38.2% / **−₹98,786** |
| R51 | 2 | 3,044 / 46.3% / ₹5,68,032 | 3,233 / 32.4% / −₹3,10,165 |
| R51 | 3 | 3,044 / 46.4% / ₹9,20,100 | 3,267 / 31.1% / −₹3,60,301 |

Apr – Oct 2026 (a second look, read by many earlier studies) says the same:
- R72, 1 lot: 37.8% won, −₹41,283 (today 45.8%, +₹1,71,366; 1,245 vs 1,202 trades);
- R51, 1 lot: 36.9% won, −₹1,02,354 (today 44.9%, +₹1,34,743; 1,720 vs 1,670 trades).

On the trades the app actually takes (R151's picker), R72 at 1 lot keeps only ₹10,077 vs ₹1,69,674.

**Why:**
- **The ₹1,000 stop is hit about twice as often as the ₹2,000 target.** R72, 1 lot, the year: 1,125 stop-outs vs 532
  targets. These options often dip 10% before running, and ₹1,000 on one NIFTY lot is only about a 10% move.
- **At 2-3 lots it gets much worse**, because ₹1,000 for the whole position is only ₹333-500 a lot: about a 3-5% dip,
  normal noise. R72 at 3 lots: 1,700 stop-outs vs 762 targets.
- **The ₹2,000 cap sells the big winners** that pay for these arms: today's average win is about ₹1,670-1,720 at 1 lot,
  but the best trades make far more.
- A 2 : 1 trade needs only about 40% wins to break even. These rules won 31-40%.

What it does do: a smaller worst day at 1 lot (R72 −₹13,992 vs −₹25,837 in the year). That is not worth the lost profit.

## Method (honest notes)

- **Pre-registered** in `PREREG.md` before any number was computed.
- **Engine:** R152's: R106 / R64 simulator, R17 slippage, dated Zerodha charges on the whole quantity. Both levels are
  solved from each trade's own real fill. The nearer of the −₹1,000 stop and the arm's −15% acts. The ladder, 30-minute
  time stop and 15:15 stay.
- **B0 parity is exact:**
  - R72: design 3,071 trades / ₹2,16,929; final 1,202 / ₹1,71,366;
  - R51: design 4,208 / ₹50,425; final 1,670 / ₹1,34,743.
- **Signal source:** the index (the arms' Donchian entries, unchanged). The target and stop are on the premium.
- **Gates (R162 / R167's):** all 6 fail.
  - Win rate and net fell in both periods.
  - Twin p = 1.0 for every test: random exits at the same trades did better on net and win rate.
  - BH q = 1.0 at m = 2,674.
- **Raw p-values:** `results/raw_pvalues.csv`. Every number: `results/design.json`, `final.json`, `picker.json`.

## Calendar 2025, for reference (1 lot)

- R72: 2,370 trades, 37.5% won, −₹90,884 (today 2,247, 46.9%, +₹63,279).
- R51: 3,200 trades, 36.6% won, −₹1,87,925 (today 3,104, 44.6%, −₹17,536).
