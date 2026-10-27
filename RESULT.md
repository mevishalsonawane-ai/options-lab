# Liquidity paper check (27 Oct 2026): summary for Boss

1. Question: why is Liquidity 15+5's paper record (37 trades, sum -76% of premium) so far below its backtest?
2. Answer: mostly not a bug. The rule itself had a poor October: on the same days the research rule made -1.6% over 65 trades.
3. The paper record was 74 points worse than that. 61 points are deliberate app rules, 8 points are one bug, and about 5 are execution, feed and days that cannot be matched.
4. Biggest single item: on 23 Oct the all-arms paper daily loss limit stopped every bot at 09:49. Minutes later the rule's two best trades of the month came (+45% and +46%).
5. The same-direction cap helped (+34 points: it refused four losers of six). No-twin cost one +29% twin on 23 Oct.
6. Signals, bars and strikes match the research on every checkable day (19-26 Oct, 22 signals). The app took no trade the research did not have.
7. Bug, fixed (paper only): a capped paper limit buy filled on a stale last price plus the spread. On 26 Oct it paid 256.90 when the market traded 237.80-246.65, and the trade lost 16.3% instead of about 9%. Paper limit buys now pay the real ask.
8. The research is too kind on illiquid exits: it books a stale last price when an option stops trading (19 Oct: -5.8% booked, the app's real exit was -16.2%).
9. Not done (Boss's call): a per-arm paper loss limit, a check on the age of the cap's price, the unused fill-window rule, and a research engine fix for stale exits.
10. Full report: research/LIQUIDITY_PAPER_CHECK_2026-10-27.md (per-trade table). Code and tests: research/reports/liqpaper/. Nothing live changed.
