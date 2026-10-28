# R128 result (28 Oct 2026): what we were missing, fixed and tested (option buying only)
1. Three reviews (internet research, the repo's book/PDF studies, a code audit) agreed: we never compared our move forecast with the option's PRICE, and our fixed exits capped the big wins a bought option needs.
2. Options here price about twice the intraday move that actually comes, so even zero-cost straddle buyers lose to time decay (-Rs 87 an hour); costs double it (net -Rs 260, 4,194 straddles).
3. Timing straddles by forecast-vs-price does not help: every tenth of the ranking lost (-Rs 115 to -214 a trade, walk-forward).
4. Exits that keep the big wins DO help R123's morning put picks: hold-to-close made S5 +Rs 373 a trade in design (555 trades, was +Rs 172) and +Rs 1,797 on unseen Apr-Oct 2026 (41 trades).
5. But it is fragile: the best 5 days give more than all the profit, most design trades are in 2024, and on 1 lot it breaches the account stop.
6. Late-day momentum (published abroad) loses in India with ITM options (-Rs 104 a trade).
7. No bug turned profits into losses; R127's twin slip is fixed (CPR still loses).
8. Nothing passes every gate.
9. Boss chose (28 Oct) to run S5 with the trailing exit on PAPER anyway, as a labelled exception (CLAUDE.md): app arm "S5 Trail", never live. The app's own version (its IV, offset to Dhan's) backtests weaker: 540 trades, +Rs 161 a trade walk-forward; 33 trades, 79% won, +Rs 509 on Apr-Oct 2026.
10. Details: research/HUNT_R128_MISSING.md; code + tests in research/hunt/r128_missing/.
