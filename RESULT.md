# R132 result (29 Oct 2026): does R123's network improve Solo (midday)'s picks? Not enough to pass a gate
1. 5 pre-registered ideas, each paired with Solo's plain rule on the same days: EV veto, quiet-hour veto, direction veto, network re-pick, EV+quiet. Walk-forward 2023 - Mar 2026, Apr-Oct 2026 re-read once, BH over the 5. Signal source: the index; a 4-ITM option is bought.
2. Solo trades at most once a day: 245 walk-forward trades (gate 300) and 35 in Apr-Oct 2026 (gate 100), so no idea could pass; the gates were not lowered.
3. Best: the network re-pick (when several indices signal, take the one with the best network EV). Walk-forward +Rs 313 a trade against Solo's +Rs 89 (245 trades, q 0.105); Apr-Oct 2026 -Rs 552 against Solo's -Rs 717 (35 trades). It helped in both periods, but on few days (62 re-picks, then 6).
4. The vetoes hurt Solo in the walk-forward and helped in 2026: not evidence. The direction veto did nothing (as expected).
5. Plain Solo lost on Apr-Oct 2026: 35 trades, 43% won, -Rs 25,094. Nothing goes to paper or a forward test; Solo stays as it is. Details: research/HUNT_R132_SOLO_NETWORK.md.

# R131 result (28 Oct 2026): a better way to call the direction, with every dataset? Intraday no; overnight one thin yes
1. 891 pre-registered tests: 9 horizons x 8 data sets (index/VIX, 59-stock breadth, FII/DII/Pro/Client OI + FPI, futures basis/OI/rollover, option chain, rupee/G-sec/MCX, calendar) x 3 models x 4 coverages, walk-forward 2023 - Mar 2026, holdout Apr-Oct 2026 read once, BH over all.
2. Intraday (09:30/11:00/13:00 to close, next 60 min): still a coin (best AUC 0.52-0.54); nothing reaches 60%; nearest miss 59.4% (645 calls).
3. Overnight: "the index opens above its 14:55 price" on the 10% surest days was right 74-78% (463-528 calls, base 61.9%) and 64-68% on Apr-Oct 2026 (49-79 calls, base 51%); 14 tests pass every gate. Signal source: the index.
4. But it is thin: ~70% of calls are MIDCPNIFTY, moves are ~0.35%, half the calls cannot be priced, and an ATM call's profit (holdout Rs +216 to +742 a trade, 15-32 trades) turns negative if sold at 09:19 instead of 09:15. Not robust; nothing goes to paper.
5. Gaps measured from NSE's official close look 60-79% "right" only because that close is a 30-minute average (a trap, not an edge).
6. The new sources (participants, FPI, basis, chain, rupee/G-sec/MCX) added nothing; breadth helped intraday by under 1 AUC point. Details: research/HUNT_R131_DIRECTION.md.

# R130 result (28 Oct 2026): more S5-like rules from the network? None better than S5
1. 84 rules (7 windows x any/puts/calls x EV Rs 300/600 x fixed/trailing exit) from R123's network, BH-corrected.
2. One passed the walk-forward (13:05-14:00 picks, fixed exits: 311 trades, +Rs 130, q 0.04) but 92% of its trades are 2024, its best 5 days are 109% of the profit, it breaks the month stop, and it made 4 trades in Apr-Oct 2026.
3. In 2026 the network only trades the 09:20-09:30 window (S5's); every other window's picks bunch in 2024.
4. Nothing new goes to paper or a forward test; S5 Trail stays as it is. Details: research/HUNT_R130_NN_SEARCH.md.

# R129 result (28 Oct 2026): the "Gamma-Driven Hero-to-Zero" expiry strategy, as pasted
1. On 917 expiry days (five indices, Aug 2021 - Oct 2026) the stated rules lined up only 2-6 times; every version lost (-Rs 210 to -Rs 900 a trade), none reached 4x.
2. The main blocker: the heaviest OI wall sits at the money and has a 3x call/put imbalance only 14-25% of the time; then VIX, then no breakout.
3. Its claim fails: after the index broke a 3x wall (22 days), it moved -6 points the wall's way to 15:20 (55% right): no blast.
4. As written its trigger reads the option's own chart (not the default; labelled); the index-signal version did no better.
5. A looser version (pre-registered: nearest 2x wall, no VIX floor, no box, Rs 10-40 OTM) trades ~40 times a year (204-228 trades) and loses -Rs 166 to -204 a trade in every version and every year; 17-18% win; 4x on 3-7.5%; worse than random entries.
6. A filter to keep only the winners (pre-registered logistic meta-label on 10 entry-time facts, walk-forward) cannot tell them apart: out-of-sample AUC 0.47-0.51; it kept 20% of winners and 29% of losers; kept trades still lost (-Rs 110 / -Rs 197, 39-41 trades).
7. Nothing passes; nothing goes on paper. Details: research/HUNT_R129_GAMMA_EXPIRY.md.

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
