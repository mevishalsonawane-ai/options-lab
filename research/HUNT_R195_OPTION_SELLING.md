# R195: option selling, a side study (18 Nov 2026)

Boss chose "no option selling" on 21 Oct 2026. On 18 Nov 2026 Boss asked: "Also start a side research of option
selling", then "Start in new session", and during the session: "You could create a neuronetwork with 5-10 layers for
more insight". **This is research only. Nothing in the app changes; it stays buy-only. Nothing goes to paper or live.**

- **Pre-registration:** `research/hunt/r195_option_selling/PREREG.md` (18 candidates, 5 baselines, the gates, the
  margin model). It was written before any R195 profit or loss existed; `results/PREREG_STAMP.txt` holds its hash
  and time. Amendment 1 (a data-quality check, descriptive) was written after the holdout read and changes no verdict.
- **Code and tables:** `research/hunt/r195_option_selling/` (`r195engine.py` the multi-leg simulator, `r195rules.py`
  the candidates, `r195nn.py` the neural network, `r195study.py` design / the one holdout read / gates / BH,
  `r195quality.py` Amendment 1; 18 tests in `test_r195.py`, including the look-ahead guards and the holdout lock).
  `results/report.md` has every column; `results/final.json` every number.
- **Signal source of every rule:** stated per rule below. Straddles, flies and condors are centred on the index's ATM
  strike (none directional); T1 / T2 / V1 read the index; S2 reads India VIX and S3 / V2 implied-vs-realised volatility,
  as filters only; N1 reads index, VIX and straddle features at 09:20. Stops are on the option premium (T1 also on the
  index). No rule reads an option's own chart for its signal.

## Verdict

**No option-selling idea passed. 0 of 18 candidates, and none is both reliable and affordable at Rs 56k.**

1. **Hedged, defined-risk structures lose money after costs.** These are the hedged straddle, the expiry-day iron
   flies, the trend credit spreads, the calm-day and implied-vs-realised condors, the overnight fly and the neural
   filter: 11 candidates. All 11 lose over the walk-forward period except E2 (the 13:30 expiry fly, +Rs 34k on 231
   trades), and E2 lost after Nov 2024 (-Rs 20k on 59). In the 2026 holdout 10 of the 11 lose; E2 makes +Rs 1.8k on
   45 trades. Four legs cost Rs 195-231 a trade in charges plus slippage. The bought wings cost more than they save.
   R103 found the same thing in October.
2. **The stop-only straddles make money on paper, but they are not reliable or affordable.** The 09:20 short straddle
   with a 25% stop on each leg (S1) was positive over 2021-2025 on all five indices: +Rs 1.5 lakh to +Rs 2.6 lakh, about
   60% wins, 628-1,092 trades. In the 2026 holdout it nearly stopped working:
   - NIFTY +Rs 9.8k on 214 trades;
   - BANKNIFTY -Rs 4.2k;
   - MIDCPNIFTY -Rs 2.8k;
   - SENSEX +Rs 3.9k;
   - FINNIFTY +Rs 1.14 lakh on 144, but partly a stale-print artefact: +Rs 69k on the 134 clean trades (Amendment 1).

   Every S1 breaks Boss's account stop: worst months of -Rs 19k to -Rs 46k, drawdowns of Rs 63k to Rs 1.64 lakh over design + holdout. A naked
   straddle also needs about **Rs 2.0-2.6 lakh of margin per lot**, four to five times the account. The VIX filter (S2)
   was the best NIFTY version: +Rs 1.56 lakh on 421 design trades and +Rs 26k on 152 holdout trades. It still breached
   the stop (worst month -Rs 20.8k in Jan 2026), lost money without its 5 best holdout days, and needs the same margin.
3. **The only trades a Rs 56k account can carry are the single credit spreads (T1 / T2) on days that are not expiry
   days.** Their model margin is about Rs 40-47k. On expiry days the extra 2% ELM pushes them to about Rs 75-80k. All
   three spread rules lose: the first-hour trend spread on NIFTY lost -Rs 58k design / -Rs 32k holdout, and the daily
   trend spread lost -Rs 56k / -Rs 15k. Anything with two short legs needs about Rs 1.3-1.5 lakh at today's lots.
4. **The neural network (N1, 6 hidden layers) found nothing to add.** Fitted walk-forward on the hedged straddle's
   history, it predicted a loss on every 2023 and 2024 day, because the structure did lose. It traded 98 days in 2025
   (-Rs 19.5k) and 37 in the 2026 holdout (-Rs 23.8k, too few trades for the gate). A deeper net cannot make a losing
   structure win. It can only choose days, and the days it chose lost.
5. **BH:** with m = 4,807 log entries, a test would need p of about 0.00002 to reach q <= 0.10. The best design p was
   0.005 (S1-FINNIFTY), so every q is 1.00. Even without BH, no candidate passes the other gates.

**Plain English for Boss:** selling options here wins often (55-69% of trades on the straddles) but earns small amounts.
The bought hedges and the charges eat the edge, and the unhedged version needs four times your capital and has months
that would hit your -Rs 13k stop. Nothing found is safe to trade with Rs 56k. The app's buy-only choice stands on this
evidence.

## One thing the stops do well (the tail)

A 25% stop on each leg caps the disaster days that sink a naked seller. 1 lot, today's lot:

| day | NIFTY naked (B0) | NIFTY S1 | BANKNIFTY naked | BANKNIFTY S1 | FINNIFTY naked | FINNIFTY S1 |
|---|---|---|---|---|---|---|
| 4 Jun 2024 (election results) | -Rs 28,048 | +Rs 5,386 | -Rs 58,420 | -Rs 1,478 | -Rs 64,135 | -Rs 4,896 |
| 5 Jun 2024 | -Rs 19,544 | +Rs 12,853 | -Rs 38,695 | -Rs 7,766 | -Rs 26,368 | -Rs 11,965 |
| 7 Apr 2025 (gap -5.0% NIFTY) | -Rs 11,350 | -Rs 7,919 | -Rs 11,555 | -Rs 13,194 | no trade | no trade |
| 1 Feb 2026 (Budget, Sunday session) | -Rs 7,052 | -Rs 11,853 | -Rs 2,090 | +Rs 8,127 | -Rs 3,806 | +Rs 7,383 |

The stop does not remove the risk: single days still lost up to -Rs 18.6k (BANKNIFTY, 16 Mar 2026; MIDCPNIFTY and FINNIFTY about -Rs 18k). Those
losses are
the whipsaw days, where both legs are stopped one after the other. The intraday rules carry no overnight gap. The one
overnight rule (P1, the hedged fly) lost on 80% of holdout nights (124 trades) before any gap mattered: the morning
reprice and four legs of costs ate the overnight decay (gross -Rs 43 a trade, charges Rs 227).

## Every candidate (1 lot at today's lot, after slippage and dated charges)

WF = walk-forward 2 Aug 2021 - 31 Dec 2025; HO = holdout 1 Jan - 13 Nov 2026 (read once). Cells: trades / win / net /
max drawdown / worst month / worst day. Margin = the model's 95th percentile at 1 lot in the holdout; RoM = holdout
return on mean margin, a year.

| id | rule (signal source) | WF | HO | margin, RoM | verdict: failed gates |
|---|---|---|---|---|---|
| S1-NIFTY | 09:20 straddle, 25% stop per leg (none) | 1,092 / 60% / +Rs 1,97,065 / Rs 35,628 / -Rs 12,318 / -Rs 9,192 | 214 / 58% / +Rs 9,798 / Rs 55,787 / -Rs 29,396 / -Rs 11,853 | Rs 2.55 lakh, +6% | fail: 4, 6, 7 |
| S1-BANKNIFTY | same | 1,060 / 59% / +Rs 1,53,836 / Rs 77,177 / -Rs 30,313 / -Rs 14,813 | 214 / 64% / -Rs 4,249 / Rs 1,07,869 / -Rs 45,322 / -Rs 18,606 | Rs 2.21 lakh, -2% | fail: 1, 2, 4, 5, 6, 7 |
| S1-FINNIFTY | same | 884 / 60% / +Rs 2,36,414 / Rs 71,030 / -Rs 45,745 / -Rs 16,532 | 144 / 69% / +Rs 1,13,559 (clean 134: +Rs 69,411) / Rs 36,418 / -Rs 12,117 / -Rs 18,112 | Rs 2.35 lakh, +68% | fail: 6, 7 |
| S1-MIDCPNIFTY | same | 628 / 61% / +Rs 2,59,400 / Rs 42,570 / -Rs 23,856 / -Rs 18,245 | 209 / 61% / -Rs 2,819 / Rs 80,961 / -Rs 29,061 / -Rs 17,468 | Rs 2.17 lakh, -2% | fail: 2, 4, 6, 7 |
| S1-SENSEX | same | 638 / 60% / +Rs 1,54,627 / Rs 42,366 / -Rs 16,418 / -Rs 17,917 | 214 / 61% / +Rs 3,915 / Rs 33,681 / -Rs 19,250 / -Rs 11,604 | Rs 2.53 lakh, +2% | fail: 4, 5, 6, 7 |
| S2-NIFTY | S1 when VIX >= its 250-day median (VIX filter) | 421 / 62% / +Rs 1,56,174 / Rs 27,629 / -Rs 14,734 / -Rs 8,788 | 152 / 60% / +Rs 26,091 / Rs 36,761 / -Rs 20,849 / -Rs 11,853 | Rs 2.52 lakh, +16% | fail: 4, 6, 7 |
| S3-NIFTY | S1 when IV >= 1.2 x RV20 (IV-RV filter) | 809 / 60% / +Rs 1,78,207 / Rs 36,100 / -Rs 12,318 / -Rs 9,192 | 112 / 56% / -Rs 22,206 / Rs 49,095 / -Rs 37,242 / -Rs 11,853 | Rs 2.63 lakh, -12% | fail: 2, 4, 5, 6, 7 |
| S4-NIFTY | straddle + wings 6 steps, 30% stop per side (none) | 1,092 / 57% / -Rs 1,27,088 / Rs 1,31,843 / -Rs 17,666 / -Rs 8,113 | 214 / 55% / -Rs 35,846 / Rs 61,155 / -Rs 13,767 / -Rs 8,086 | Rs 1.47 lakh, -44% | fail: all but 3 |
| E1-NIFTY | expiry 09:20 iron fly, 1.5x premium stop (none) | 231 / 45% / -Rs 36,317 / Rs 59,068 / -Rs 12,805 / -Rs 6,036 | 45 / 47% / -Rs 21,812 / Rs 29,255 / -Rs 7,783 / -Rs 5,039 | Rs 1.47 lakh, -19% | fail: all but 3 |
| E2-NIFTY | expiry 13:30 iron fly (none) | 231 / 55% / +Rs 34,337 / Rs 26,826 / -Rs 8,111 / -Rs 4,789 | 45 / 58% / +Rs 1,799 / Rs 8,994 / -Rs 5,459 / -Rs 4,613 | Rs 1.47 lakh, +2% | fail: 1 (post-Nov-2024 -Rs 20,294), 4, 5, 6 |
| E3-SENSEX | SENSEX expiry 09:20 iron fly (none) | 134 / 41% / -Rs 9,459 / Rs 24,320 / -Rs 7,992 / -Rs 4,848 | 45 / 42% / -Rs 4,845 / Rs 11,645 / -Rs 4,905 / -Rs 4,715 | Rs 1.42 lakh, -4% | fail: 1, 2, 4, 5, 6, 8 |
| T1-NIFTY | first-hour move >= 0.25%: credit spread with it, index stop (index) | 485 / 53% / -Rs 58,181 / Rs 98,169 / -Rs 11,135 / -Rs 5,957 | 109 / 44% / -Rs 31,799 / Rs 39,770 / -Rs 12,302 / -Rs 5,841 | Rs 78k (78% of trades <= Rs 56k), -72% | fail: all but 3 |
| T1-BANKNIFTY | same (index) | 578 / 48% / -Rs 68,943 / Rs 74,624 / -Rs 15,011 / -Rs 4,914 | 120 / 38% / -Rs 16,584 / Rs 22,387 / -Rs 6,653 / -Rs 4,430 | Rs 74k (94%), -40% | fail: all but 3 |
| T2-NIFTY | credit spread with the 20-day trend (index) | 1,092 / 60% / -Rs 56,091 / Rs 81,785 / -Rs 19,829 / -Rs 7,322 | 214 / 57% / -Rs 14,679 / Rs 46,018 / -Rs 10,579 / -Rs 6,058 | Rs 77k (79%), -33% | fail: all but 3 |
| V1-NIFTY | iron condor on calm days (index) | 251 / 50% / -Rs 66,581 / Rs 73,439 / -Rs 10,029 / -Rs 5,289 | 43 / 42% / -Rs 10,268 / Rs 13,338 / -Rs 5,730 / -Rs 3,769 | Rs 1.37 lakh, -16% | fail: all but 3 |
| V2-NIFTY | iron condor when IV >= 1.2 x RV20 (IV-RV) | 809 / 50% / -Rs 1,70,191 / Rs 1,78,476 / -Rs 14,235 / -Rs 5,289 | 112 / 47% / -Rs 27,792 / Rs 35,550 / -Rs 11,674 / -Rs 3,913 | Rs 1.41 lakh, -34% | fail: all but 3 |
| P1-NIFTY | overnight iron fly 15:16 -> 09:21 (none) | 629 / 26% / -Rs 1,69,715 / Rs 1,70,417 / -Rs 7,512 / -Rs 3,999 | 124 / 20% / -Rs 39,802 / Rs 49,115 / -Rs 7,431 / -Rs 2,323 | Rs 80k, -61% | fail: all but 3 |
| N1-NIFTY | S4 when a 6-layer MLP predicts a profit (index, VIX, straddle features) | 98 (2025 only) / 59% / -Rs 19,534 / Rs 31,987 / -Rs 11,921 / -Rs 8,113 | 37 / 49% / -Rs 23,818 / Rs 32,524 / -Rs 10,570 / -Rs 6,435 | Rs 1.50 lakh, -28% | fail: all eight |

Gates: 1 walk-forward > 0 and post-Nov-2024 part > 0; 2 holdout > 0; 3 40+ trades in each; 4 > 0 without the 5 best
trades in each; 5 twin p < 0.05; 6 BH q <= 0.10 (m = 4,807); 7 worst month >= -Rs 13k and drawdown <= Rs 40k; 8 > 0
at 2x slippage. Twin p, bootstrap p and per-year numbers are in `results/report.md`.

**Baselines (naked 09:20 straddle, no stop; never candidates):** NIFTY +Rs 70k WF / -Rs 39.7k HO; BANKNIFTY +Rs 1.98
lakh / -Rs 10k; FINNIFTY +Rs 66k / +Rs 63.5k; MIDCPNIFTY +Rs 3.67 lakh / -Rs 9.8k; SENSEX +Rs 1.32 lakh / -Rs 69.7k.
Worst single days -Rs 28k (NIFTY) to -Rs 64k (FINNIFTY), all on 4 Jun 2024. Every baseline breaks the account stop
(7-10 months at or below -Rs 13k).

## Margin and Rs 56k (the model, PREREG section 3)

Margin = 2% exposure on each short leg's notional (+2% on its expiry day since 20 Nov 2024) + the widest wing x lot
for a hedged book, or 8% SPAN once for naked shorts. Checked against Zerodha Varsity's worked NIFTY condor (model
Rs 44,920 vs Rs 44,303). At today's lots (NIFTY 65, BANKNIFTY 30, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20) and 2026
index levels:

| structure | model margin, 1 lot | at Rs 56k? |
|---|---|---|
| naked straddle (S1-S3, B0) | Rs 2.0-2.6 lakh | no |
| straddle with wings / iron fly / condor (S4, E, V, P1, N1) | Rs 1.3-1.5 lakh (about Rs 80k without the expiry ELM for P1) | no |
| one credit spread, 4 steps wide (T1, T2) | about Rs 40-47k; Rs 75-80k on its expiry day | non-expiry days only |

The broker's calculator decides on the day; these are planning numbers.

## Data coverage and caveats

- **Data:** Drive lake copied 18 Nov 2026. Held-contract stores (R51's builder, Amendment A cleaning), option days:
  NIFTY 1,308, BANKNIFTY 1,305, FINNIFTY 1,297, MIDCPNIFTY 938 (from 31 Jan 2022), SENSEX 869 (from 15 May 2023),
  all to 13 Nov 2026.
  - Rules skip a day when a leg has no fresh print at entry. That skipped FINNIFTY often (884 of about 1,100 design
    days traded; 144 of 214 in the holdout) and MIDCPNIFTY in 2022 (its first trades are in 2023).
  - BANKNIFTY, FINNIFTY and MIDCPNIFTY trade their monthly contract after Nov 2024.
- **Stale legs:** a leg not traded for more than 5 minutes was marked with Black-Scholes at its last print's implied
  volatility, as part of the rule (R103's lesson). It touched 0.5-7% of trades on most rules, and 13% on the hedged
  straddle (176 of 1,306).
- **Stale entry prints (Amendment 1):** a sold leg with no volume, or failing put-call parity, at entry. This flagged
  10 of FINNIFTY's 144 holdout trades (+Rs 44k of its +Rs 1.14 lakh) and few elsewhere. `results/quality.json` has
  every test. It changes no verdict.
- **Fills:** the lake has no bid / ask. Slippage is R103's model (5 bps plus 1-4 ticks by traded volume, doubled on
  stops), and every result was also run at 2x ticks.
- **Costs:** charges are dated, including STT at 0.15% on sales from 1 Apr 2026. No rule holds to settlement, so
  exercise STT never arises.
- **Lot size:** one lot at today's lot for every year, so older years' rupees are scaled to today's lot sizes, not
  what was traded then.
- **Holdout:** 1 Jan - 13 Nov 2026, wholly in the post-Nov-2024 regime. R103 had read 15 Jul - 19 Oct 2026 for six
  BANKNIFTY 09:21 hedged structures; no R195 rule is one of them. The pre-registration was written and hash-stamped
  before any R195 number. The session could not commit it to git at that moment (a permission check), so the stamp
  file is the record of the order.
- **N1** counts sessions to expiry from the exchange calendar (known in advance), not from prices.
