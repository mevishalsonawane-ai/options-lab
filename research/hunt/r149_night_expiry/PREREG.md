# R149 pre-registration: Night on expiry-related nights: is there a case?

Written 5 Nov 2026, BEFORE any R149 number was computed. Boss (5 Nov): "Check on expiry date, is there any case?"

Research only.

## The question

Night (R3) never trades on two kinds of night (`NightRules.expiry`):
- **(a)** an expiry day of the index (`entry_on_expiry_day`);
- **(b)** the night before its contract expires (`contract_expires_next_session`).

On NIFTY these are about 2 of every 5 nights (R147: 272 + 272 of 1,287). R149 asks whether Night's signal on those
nights can be traded profitably, and how.

## Variants (Night's own side on those nights; everything else as R147)

| id | nights | contract | exit |
|---|---|---|---|
| E1 | (b) the night before expiry | the EXPIRING contract (1-ITM, Night's nearest series), held into expiry morning | sold at the 09:16 open on the expiry day |
| E2 | (b) the night before expiry | the index's MONTHLY contract (1-ITM), when it expires after the next session | sold at 09:16 |
| E3 | (a) the expiry day itself | the MONTHLY contract (1-ITM), when it is not the one expiring that day and expires after the next session | sold at 09:16 |

- **Indices:** NIFTY, BANKNIFTY, FINNIFTY (Night's exact rule); SENSEX (no-OI rule), as R147 / R148.
- **Monthly expiry days** come from the MONTH files by R27's time-value rule.
- **Each variant is tested per index** (where it has trades) and pooled. The pooled test is the gate.
- **Not testable:** next month's contract on a MONTHLY expiry day. The lake holds only the expiring month on that day.
  This is most of BANKNIFTY / FINNIFTY's expiry days since Nov 2024.

## Data, costs, periods

- **Data:** the Drive lake (R147's copy). The option MONTH files of NIFTY, FINNIFTY and SENSEX are added. Same
  foreign-row filter, fill guard and stale-09:15 rule.
- **Costs:** app fills and charges, h28 half-spreads, and 1.5x stress.
- **Periods:**
  - DESIGN 2022-24 (yearly folds);
  - CHECK 1 Jan 2025 to 16 Oct 2026.
  - These expiry nights were never priced by R147 / R148, but the CHECK period is R147's holdout, so it is a second
    look at those dates (disclosed).
  - The real test is forward.

## Gates (as R147's new-index gate)

**DESIGN, pooled over the indices:**
- net per trade > 0 at 1.5x spread;
- positive in at least 2 of 3 years;
- random-side twin p <= 0.05;
- BH q <= 0.10 over E1-E3;
- at least 60 trades.

**CHECK:** net > 0 at 1.5x spread, twin p <= 0.05, at least 30 trades.

Also reported: per index, worst month, max drawdown, worst trade, and the share of trades losing more than 30% of
premium. Expiry-morning options can go to almost zero.

## Verdict rules

- A passer is a PAPER candidate for Night. Live stays in shadow first, since it adds new live trades.
- Everything else stays a note.
- Experiment entries are `r149-*.yaml`.
- Pytest tests cover the contract choice on expiry nights.
