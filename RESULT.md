# R94 result: Boss's advanced commodity ideas on MCX (21 Oct 2026)

**None of the ideas works on MCX after costs.** 0 of 152 pre-registered tests passed design. The holdout was read once
for the 64 tests still in scope, and nothing passed there either. No paper arm, nothing live, nothing sells options.

| idea | verdict |
|---|---|
| 1. Calendar spreads | No. The near/far spread earns about Rs 0 a trade before costs; costs on both legs (Rs 200-1,240 a trade) make every pair lose. Design -Rs 5.31 lakh on 546 trades, holdout -Rs 9.55 lakh on 937. Only 1-5 months of pairs exist (live contracts only). |
| 2. Sector index pairing | The index can't be tested: no point-in-time BULLDEX/METLDEX history, ~0 lots a day, no ENRGDEX future. Proxy pairs: gold/silver ratio lost in both windows; base-metal pairs fail Boss's liquidity rule and lost in the holdout. |
| 3. Option selling | Out of scope (Boss, 21 Oct). Design only, holdout not read: 0 of 88 significant; condors lose to costs, naked strangles have Rs 1.5 lakh gap losses. |
| 4. Bullion seasonals | No. Diwali / Akshaya Tritiya / wedding-month windows don't beat random windows of the same year. 2022-26 profits come from the bull market itself. |
| 5. US overnight -> MCX open | No. The 09:00 gap already prices the night; holdout +Rs 7,467 on 320 trades. Tested with free Dukascopy proxies, not COMEX/NYMEX prints. |

Boss's 2% risk rule at Rs 1 lakh fits only mini crude / mini gas trades; gold and silver need Rs 1.3-8 lakh of capital
per trade.

Files: `research/HUNT_R94_MCX_ADVANCED.md` (full write-up), `research/hunt/r94_mcx_advanced/` (PREREG, NOTES, code, 77
tests, results), `research/experiments/entries/r94-*.yaml` (13), a FINDINGS.md section.
