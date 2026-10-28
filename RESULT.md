# RESULT: IraThinker's flow, as a paper arm in IraAlgo (28 Oct 2026)

1. New paper arm "IraThinker's flow": IraThinker's Scanner → IraScore → Time Machine → Risk Gate, ported rule for rule (engine `FlowRules.kt`).
2. A plan that passes all four stages becomes a paper order: the ATM option of the nearest expiry, 1 lot. No alert, no notification.
3. Index options only: NIFTY, BANKNIFTY, FINNIFTY (the indices IraThinker itself trades). IraThinker's VIX rule lets only breakdowns through, so it buys puts.
4. Zerodha data only: the indices' and India VIX's minutes are built from Zerodha's stream in memory. No Yahoo, Upstox or web prices.
5. Exits: a 20% premium stop resting in the paper book, IraThinker's index stop and 2R target, out by 15:15. The index's own expiry day is skipped.
6. Every guard applies: kill switch, day lock, account guard, paper limits, same-direction cap, 3 a day, one per index. A gap in the minutes means no decision.
7. On by default on paper, as you asked. This is logged in the Autopilot timeline and undone in one tap. Its own Trade live switch starts off.
8. It needs about two recorded Zerodha sessions before its Time Machine can replay, so it takes nothing on its first day.
9. Flow page: Settings → Automation → IraThinker's flow. It animates only while open, and stays still under battery saver or at 30% or less. Also a row on the Strategies card.
10. Not proven: R110 found no IraThinker strategy that passed. It is labelled that way everywhere and logged as research entry r120.
