# R45 pre-registration: audit of another AI's "NIFTY premium mean-reversion scalp"

Written 14 Oct 2026, BEFORE any R45 trade or P&L was computed. Committed and pushed on its own.

## Why

Boss (14 Oct) forwarded a claim from another AI working on the same Drive lake: a "NIFTY premium mean-reversion scalp"
is the only strategy that survives 6 years: 3,726 trades 2021-2026, +Rs 649,957, +Rs 174/trade, 53.4% win, PF 1.45,
52/63 green months, max DD Rs 24,809, every year positive (2021 +231/trade, 2022 +195, 2023 +190, 2024 +73, 2025 +120,
2026 +125); BANKNIFTY +Rs 14/trade. R45 audits it: first reproduce it, then fix one suspected problem at a time.

**Signal source (CLAUDE.md rule "signal on the underlying"):** this rule reads its signal from the OPTION'S OWN PRICE
CHART (the ATM call premium). It is NOT the default kind of rule. It is labelled so everywhere, reported separately,
and nothing goes on paper unless Boss switches it on, whatever the result.

## The rule as claimed ("their version", T0)

- Series: 5-minute bars resampled (first/max/min/last) from the lake's 1-minute **rolling ATM call** rows
  (`opt_<UND>_<WEEK|MONTH>_<YEAR>.parquet`, `offset == 0`, `side == "C"`), 09:15-15:29 IST, per session.
- Skip a session with < 25 five-minute bars. Skip a session as "expiry" when any of its last 5 five-minute closes is
  < 1.0.
- Bollinger (20, 2.0) on the 5-minute premium closes, computed within the session: lower = SMA20 - 2 × SD20 (population
  SD, ddof 0; a sample-SD check is reported). Signal at bar i: close[i] < lower[i] and close[i-1] >= lower[i-1].
- Entry at the OPEN of bar e = i + 1. No entry at or after 15:15. Max 2 trades a session; no new trade while one is
  open (a signal is taken only on a bar after the previous trade's exit bar).
- Exits, checked on bars e, e+1, e+2 in order, each bar: stop first (low <= 0.9 × entry → filled at 0.9 × entry); then
  the peak gain is updated with the bar's HIGH, and if the peak gain has reached a lock level L of 25 / 50 / 75 / 100%
  and the low is <= entry × (1 + L) (L = the highest level reached), exit filled at entry × (1 + L). If none fired,
  exit at the CLOSE of bar e+3 (this is their code; bar e+3's high/low are not checked). Square-off: a trade still open
  on the 15:15 bar exits at that bar's open.
- P&L = (exit - entry) × 75 - Rs 95 a trade.

## Reproduction (step 0)

- Run T0 on NIFTY WEEK, NIFTY MONTH and both together (each series simulated on its own, trades pooled), and on
  BANKNIFTY WEEK and MONTH. Say which reproduces 3,726 trades / +Rs 174 / 53.4% / PF 1.45 and the per-year numbers.
- Also tried, as reproduction aids only (not fixes): a Bollinger band computed across sessions (not reset each day),
  and sample SD. Whichever matches best is called T0 from then on; if none matches within 10% on trades and Rs 30 on
  net/trade, the closest is used and the gap is reported.

## The fixes (each a pre-registered, measured check; effect reported in Rs/trade vs T0)

- **P1 rolling-ATM artifact.** Measure in T0 how often the strike in the 1-minute rows changes between the entry minute
  and the exit minute. Then:
  - **P1a fixed contract:** the signal stays on the rolling series, but each trade is priced on ONE contract: the
    strike that was ATM at the entry bar's first minute (same expiry; its 1-minute bars rebuilt from every offset of the
    lake rows, resampled to 5 minutes). Entry = that contract's open in the entry bar; exits on that contract's bars.
  - **P1b live-note version:** the 09:15 ATM strike is held all session; signals AND trades are on that one contract.
  - Missing 5-minute bars of the fixed contract: no check on a missing bar; a time exit or square-off uses the last
    traded price at or before that bar; a trade with no entry bar is not taken.
- **P2 gap fills and intrabar order.** Stop or lock on a bar that OPENS beyond the level fills at the open (the worse of
  the two). Lock levels are those reached by the peak up to the PREVIOUS bar (a bar's own high cannot arm a lock that the
  same bar's low then fills); the bar's high is added after the checks.
- **P3 hold length.** P3a: the stated 15 minutes (checks on e..e+2, time exit at the close of e+2). P3b: their 20 minutes
  with bar e+3 also checked.
- **P4 trades a day.** Report trades/session of each series. If 3,726 needs WEEK + MONTH together, the max-2-a-day rule
  is broken there (up to 4); the fixed version uses one series (WEEK), max 2 a session.
- **P5 costs and slippage.** The research cost model (`research/obuy/costs.py`, `Costs("dated")`: the charges in force on
  the trade date) instead of Rs 95, with slippage 0% / 0.5% / 1% of the price on EACH side (buy × (1 + s), sell × (1 - s),
  also on stop/lock fills). Lots: their 75 throughout (main), and the historical NIFTY lot
  (`research/obuy/config.LOT_SCHEDULE`) reported separately.
- **P6 expiry filter.** Real expiry days from the lake (R25 time-value method, `research/brain/expiry_infer`, run on the
  same series) replace "last 5 closes < 1.0". Expiry-day trades are reported separately; the fixed version skips them.
- **P7 look-ahead.** A prefix test: for a sample of 200 sessions, every signal and entry computed on the session cut at
  bar t equals the one computed on the full session, for every t. Expected: no look-ahead.

Variants run (all on design data first): T0; T0 + each of P1a, P1b, P2, P3a, P3b, P5 (0.5% and 1%), P6 one at a time;
**F-a** = all fixes with P1a (WEEK, P2, P3a, P5 at 0.5%, P6, lot 75) and **F-b** = all fixes with P1b; F-a and F-b at 1%
slippage and with historical lots; BANKNIFTY T0 and F-a. The full list is fixed in `r45cfg.VARIANTS`.

## Data split, walk-forward, holdout

- Data: Drive lake `gdrive:IraAlgo-data-lake/options_rolling_1m`, NIFTY and BANKNIFTY WEEK and MONTH, Aug 2021 - 13 Oct 2026.
- The rule has NO fitted parameter (everything is fixed by the claim), so walk-forward = each calendar year reported as
  its own out-of-sample block; nothing is tuned on any year.
- **Design:** sessions up to 13 Apr 2026. **Embargo:** 14-26 Apr 2026 (unused). **Locked holdout:** 27 Apr 2026
  onwards (the Data Brain's holdout, `research/brain/holdout.py`), read ONCE by `r45study.py holdout`, which refuses to
  run if `results/holdout.json` exists. Their full-span totals (which include 2026) are given only after that read; the
  reproduction is judged on 2021-2025 per-year numbers, which lie in design.

## Statistics and gates

- Random-entry twins: for each variant, 300 twins with the same sessions and the same number of trades per session,
  entries at random eligible 5-minute bars (uniform among bars that could be entered, respecting no-overlap and the
  15:15 cut), the same exits and costs. p = (1 + #twins with net/trade >= the rule's) / (1 + 300).
- Benjamini-Hochberg over all R45 design variants (q reported). The experiment family count is also reported.
- Per variant: trades, win rate, net/trade, net, PF, green months / months, max drawdown, worst month, per year, p, q.
- **A version "survives" only if ALL hold** (only F-a and F-b, the tradable versions, can survive):
  G1 design net/trade > 0 after real charges and 0.5% slippage a side; G2 positive in at least 4 of the 5 design years
  2022-2026 (2021 is partial); G3 BH q < 0.05 against the random twins; G4 holdout net/trade > 0 with the same costs;
  G5 still net/trade > 0 at 1% slippage on design.
- Even a survivor is an option-chart signal: it is reported, and only Boss can switch it on for paper.

Sample sizes are given with every number. Every variant run, failures included, is logged in
`research/experiments/entries/r45-*.yaml`.
