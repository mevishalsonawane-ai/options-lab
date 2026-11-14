# Strategy improvement and eligibility matrix

No row asserts an improvement that has not been replayed.

| Family | Existing card status | Research-informed action | New improvement verified |
|---|---|---|---|
| Liquidity 15+5 (BANKNIFTY) | weak | Preserve rare runners; retest exits separately. Avoid transferring Donchian profit locks blindly. | No |
| ORB (opening range breakout) | negative | Re-entry caps and plain-rule parity first; generic filters failed R169/R170; no new edge established. | No |
| ORB Fresh | negative | Re-entry caps and plain-rule parity first; generic filters failed R169/R170; no new edge established. | No |
| ORB Sweep | negative | Re-entry caps and plain-rule parity first; generic filters failed R169/R170; no new edge established. | No |
| Range Fade | negative | Re-entry caps and plain-rule parity first; generic filters failed R169/R170; no new edge established. | No |
| Big-candle pullback (Solo) | weak | Preserve rare runners; retest exits separately. Avoid transferring Donchian profit locks blindly. | No |
| Expiry Hero (zero to hero) | negative | Validate cheap-premium costs and exercise tax; tiny targets can inflate win rate while losing. | No |
| FT3 near-ATM morning (forward test) | weak | Reproduce original ledger first; evaluate remove-only calibrated gate only on matching instrument/horizon. | No |
| Night (R3) | weak | Separate overnight gap/expiry risk; this intraday direction model is not eligible. | No |
| VIX divergence | negative | Reproduce original ledger first; evaluate remove-only calibrated gate only on matching instrument/horizon. | No |
| ATM short straddle (selling) | weak | Use magnitude/IV and both-leg liquidity, not binary direction alone; reprice stale wings. | No |
| Buying the straddle / strangle | negative | Use magnitude/IV and both-leg liquidity, not binary direction alone; reprice stale wings. | No |
| Supertrend + EMA (owner's Pine) | negative | Reproduce original ledger first; evaluate remove-only calibrated gate only on matching instrument/horizon. | No |
| Majority Rule 14 (weekly NIFTY) | negative | Reproduce original ledger first; evaluate remove-only calibrated gate only on matching instrument/horizon. | No |
| MCX bots | negative | Validate actual futures/options expiries and data holes; separate model needed. | No |
| Crypto expected range (BTC, ETH) | context | Separate crypto features, costs and horizon; Indian-index model is ineligible. | No |
| Crypto direction (BTC, ETH) | negative | Separate crypto features, costs and horizon; Indian-index model is ineligible. | No |
| Crypto in Boss's window (00:00-09:00 IST) | context | Separate crypto features, costs and horizon; Indian-index model is ineligible. | No |
| ntfy alerts (external messages) | context | Reproduce original ledger first; evaluate remove-only calibrated gate only on matching instrument/horizon. | No |
| R51 Donchian | missing_from_strategy_cards | R151/R163 calibration and occupied-side parity; do not repeat failed 60% target search. | No |
| R72 Breadth Donchian | missing_from_strategy_cards | Keep breadth/entry semantics; compare net and drawdown, not only selected win rate. | No |
| R52 Open outside value | missing_from_strategy_cards | Existing high-win target result needs current costs and genuinely fresh replication. | No |
| CISD neural-filtered entry | missing_from_strategy_cards | Design win rate is not holdout win rate; only 35 later trades in cited variant. | No |
| Latest confirmed breakout | missing_from_strategy_cards | Explicitly test runner-preserving exits and calibrated remove-only direction gate. | No |
