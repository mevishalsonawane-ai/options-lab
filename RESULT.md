# R106 for Boss: small fixed profits ("5 trades x Rs 1,000") — answer: no

1. **No.** I tested 9 small-profit rules on each of the 4 paper arms (36 tests): sell at +Rs 500, +Rs 1,000 or +Rs 1,500 per trade, with the arm's own stop or a 1:1 stop, and with re-entries or a stop for the day at 5 trades / +Rs 5,000. None passed. On every arm they made less than today's rules, and most lost money.
2. Final period (1 Apr - 13 Oct 2026), four arms together, 1 lot: today's rules made **+Rs 3,12,978**. "+Rs 1,000 / -Rs 1,000" lost **Rs 3,43,492**. "+Rs 1,000 with the arm's own stop" lost **Rs 2,52,385**.
3. Why: the arms earn their money from a few big trades. A fixed target cuts exactly those trades and leaves the losses as they are. Win rates rise (R72: 46% to 67% at +Rs 500), but the win rate needed to break even rises faster (73%).
4. Break-even after exact charges (Rs 68-108 a round trip): with a 1:1 stop you need 50%; the arms win 36-52%. With the -15% stop you need 60-89%, depending on the index and target.
5. "5 trades x Rs 1,000", all arms, final period (133 sessions): median day **-Rs 651**, **13%** of days reach Rs 5,000, worst day -Rs 8,185, net **-Rs 14,673**. Today's rules: median day Rs 41, worst day -Rs 69,940, net +Rs 3,12,978. Calmer days, but a slow loss.
6. After 13 Oct (14-19 Oct, 4 sessions): that plan made -Rs 14,232 and today's rules -Rs 13,092. Too short to judge either way.
7. **Nothing goes on paper or live.** If Boss wants smaller, steadier days, trading fewer lots or using the account's day and week limits does that without cutting the arms' edge.
8. It was done honestly: pre-registered first (8e8eb6e), design read committed before the one final read, today's rules reproduced R101 to the rupee, real charges and slippage, random-exit twins, and BH across 549 tests (R106's 36 plus 513 earlier). The best q was 0.27.
9. Solo 2.0: its design picks reproduce, and every rule made less than its Rs 55,910 there. Its final picks no longer reproduce R57 exactly (one pick differs; likely a revised lake file), so, as pre-registered, its final was not run.
10. Files: write-up `research/HUNT_R106_SMALL_TARGETS.md`; code, 25 tests and every table in `research/hunt/r106_small_targets/`; 36 experiment-log entries. The knowledge pack (in `android/`, out of scope here) was already stale on main and needs the coordinator's rebuild.
