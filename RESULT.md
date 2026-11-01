# R144c result (1 Nov 2026): losing trades of FT3, Expiry Hero, Hero Rs 1,000 ticket, Night (R3), VIX divergence, Flow
1. No skip filter passed: 12 tested (3 each on FT3, Hero ticket, Night, VIX divergence), chosen on design only, 0 passed R142's lose-less gates.
2. Expiry Hero (48 trades, 2021-26) and Flow (62 trades, Dec 2022 - Oct 2026) are too small to test: described only.
3. FT3: 81-91% of tickets lose; 72 of 478 runners (5x+) made +Rs 25.2 lakh, the other 406 lost Rs 16.5 lakh; design lost Rs 1.66 lakh.
4. Hero ticket: 78-85% lose; the 25% trail sells nearly all; both tickets lost on 67% of 406 two-ticket days.
5. Night: 67-79% of losing nights opened against the trade next morning (unseeable at 15:20; no US data in the lake).
6. VIX divergence fades (47% -> 36% -> 32% won); NIFTY's 20-min / -15% exits lost Rs 98k, its 15:10 holds made Rs 1.77 lakh.
7. Flow: its 20% premium stop closed 31 of 62 trades, all losers; the Time Machine's odds sit near 50%.
8. Part B: 435 skip rules, none beat random skipping. Part C before/after (hindsight): Hero ticket off saves Rs 40.6k; FT3 skip monthly expiries + long unwinding Rs 6.24 -> 8.40 lakh; Night skip VIX down >3.13% Rs 1.77 -> 2.24 lakh; VIX NIFTY to 15:10 and wider Flow stops HURT.
9. No arm is near Boss's 65% win rule; no filter moved any win rate by more than ~4 points. Nothing in the app changes. Write-up: research/HUNT_R144C_LOSS_EXPIRY_OTHER.md; code and results: research/hunt/r144c_loss_expiry_other/; entries r144c-*.yaml.
