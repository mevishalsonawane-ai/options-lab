# Live Zerodha checklist (manual, about 10 minutes)

Automated tests run the app's live-trading code against a fake Kite server. Only this
checklist touches your real Zerodha account. Do it once after installing a new build,
during market hours (09:15–15:30 IST), with the phone on the network your static IP /
relay covers.

Cost: nothing if the steps are followed. The only order uses a limit price far away from
the market, so it cannot fill, and it is cancelled in step 6.

## Before you start
- [ ] More → Zerodha: **Log in to Zerodha** for today.
- [ ] The badge at the top shows **LIVE** (tap it and confirm if it shows PAPER).
- [ ] More → Bot → Bot settings: kill switch **off**, order limits set.

## 1. Self-test (read-only)
- [ ] More → Zerodha → **Live self-test** → *Run the self-test*.
- [ ] All checks show ✓. "Static IP for orders" must pass; otherwise orders would be rejected.

## 2. Place an order that cannot fill
- [ ] Trade → pick a far out-of-the-money NIFTY option on the next expiry (premium under ₹5).
- [ ] BUY **1 lot**, LIMIT, at the lowest price allowed (₹0.05), product NRML.
- [ ] Review screen shows the right symbol, side, quantity and price. Hold to send, then enter the PIN.
- [ ] The order shows **OPEN** in the app's order book and in the Kite app.

## 3. Modify it
- [ ] Change the limit to ₹0.10. Both the app and Kite show the new price, and it is still OPEN.

## 4. GTT
- [ ] On the same option, create a GTT (stop-loss/target) from the app.
- [ ] It appears in Kite → GTT. Delete it from the app, and it disappears in Kite.

## 5. Protection (stop/target)
Only if you already hold a position you are happy to protect. Otherwise skip this step.
- [ ] Add a stop and target far from the market, and check both orders appear in Kite.
- [ ] Remove the protection, and check both orders are CANCELLED in Kite.

## 6. Cancel
- [ ] Cancel the order from step 2. It shows **CANCELLED** in the app and in Kite.
- [ ] Positions show nothing new and funds are unchanged.

## 7. Safety gates (nothing should be sent)
- [ ] Switch the badge to **PAPER** and try a live order: the app refuses.
- [ ] Turn the kill switch **on** and try a new BUY: refused. A square-off of an existing position is still allowed.
- [ ] Enter a wrong PIN at the send step: nothing is sent.

## If anything fails
Note the step, the message shown and the time, and take a screenshot only if screenshots
are allowed in this build. Check Kite's order book for anything left open and cancel it
there.
