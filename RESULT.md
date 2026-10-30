# Every arm live, OFF = shadow (30 Oct), for Boss

| Arms | Before | Now |
|---|---|---|
| R51 Donchian, R72, R52, Solo 2.0, FT3, VIX, Night, Flow, ntfy, MCX arms, Hero ₹1,000 | some paper only / record only, ntfy & Flow live default off | ON = paper + live (in Live); OFF = shadow |
| ORB, Liquidity, Sweep, Range Fade, Pine scripts, re-armed shadow variants | Sweep/Range Fade refused live; no "AI trades go live" check | live when armed in Live with the PIN; door now asks "AI trades go live" too |
| S5 Trail, CISD ("failed gates, Boss's exception") | paper only | ON = paper + live, with a stop resting at Zerodha |
| Jarvis's own trades | paper | paper (safety rule, unchanged) |

1. **Monday, once you go Live** (PIN + "AI trades go live"): each arm that is ON trades through its own "Trade live" switch and every gate. **1 lot per live entry** (ntfy keeps its own lots), stop resting at Zerodha (SL intraday, GTT carried). Live shadow now starts OFF; an arm you put in live shadow yourself stays there; Solo midday stays in shadow. R51 is switched back ON.
2. **OFF means shadow**: an off arm never buys (no paper, no live) but keeps deciding; each would-be trade is priced at the ask with charges, closed by its own exits, shown as "OFF · SHADOW". Switching off never needs the PIN; every switch is in the change timeline.
3. **Hero family hidden** ("Hide zero hero family arms"): Hero ₹1,000 ticket joins Hero (expiry) under Strategies → Hidden arms (off, runs in shadow, one tap restores). FT3 is not hidden; tell me if you meant it too.
4. **Gates kept**: Live + PIN, AI trades go live, each arm's switch, kill switch, day lock, split day stops, account guard, loss limit, order gates, paper-only period, static IP. Kill switch and day stops block new entries, never exits.
5. **Risk rules today**: account stop (−₹40k / −₹13k) LIVE for mirrored arms and ORB, SHADOW for Pine/strategies; same-direction cap, 2% cap, weekly stop, opposite bets SHADOW; one-index claims not on mirrored arms; ExitCoordinator and PositionsLag shadow. Unchanged (your call).
6. **Review fixes**: S5 had no resting stop; a crash between fill and save could lose a position; a lost-reply entry was dropped; MCX evening live trades had no timed look; a Jarvis voice order could go live in a race; FT3 went live at 2 lots and Hero was always refused.
7. **Tap before 09:15 Monday**: max trades a day is 10 and counts every order (entry, stop, exit), about 3 live trades, raise it if you want all arms; the paper-only period must end 1 Nov or earlier; check each arm's switch. Optional: ORB "Exchange stops" is off; MCX trend's safety GTT is 30% away.
8. **Shadow never slows a real position**: off arms decide in their own background job after the stops; the MCX off arms decide only while every MCX arm is off and flat. **Not changed, for you to decide**: ORB/Pine hold a lock across network reads (an exit can wait seconds); a shadow decision and a real one share one "decided" mark, so switching an arm on mid-window skips a signal the shadow took.
