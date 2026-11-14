# Additional user-requested consecutive-bar test,15November2026 India

Registered after the neural target2000 results and before this extension runs.
The user asks for multiple bars in the same direction and whole-lot accounting.
No retraining or selecting new thresholds from outer performance.

Four confirmation variants:2or3consecutive completed candles, on1minute or5minute
underlying NIFTY bars. Bullish means every close>open AND each successive close
is higher; bearish is the inverse. Dojis/mixed/gapped/nonfinite bars fail.
5minute aggregates require all5actual source minutes; a decision sees only bars
whose right-edge completion time<=decision time. No partial5minute candles.
No carry across sessions. All decisions remain original eligible5minute times.

For each confirmation variant compare4policies: standalone same-direction bar
entry; and linear,dense,hybrid UNGATED highest-expected-net direction with this
confirmation as a remove-only filter. Determine neural direction BEFORE filtering,
so model/side is not silently reselected to match bars. All original gated neural
policies had no trades; lowering their confidence thresholds after outcomes is
not called validation. Ungated comparisons are explicitly diagnostic.

16policies x normal/double-friction =32additional endpoints. Same ONE complete
lot,Rs2000normal-net target,Rs1500planned stop,30minute limit,100000capital and
all original cash/risk rules. Fixedstrike held to exit. No rescaling prior P&L.
Reproduce original ungated ledger with new session loader as a parity check.
Score1000permutations/1000five-day blocks. BH within32extension endpoints AND
joint94with original62. Separate result/report/ledgers; all trials retained.
No new untouched holdout; no automatic paper/live promotion. Future performance
and Rs2000profit on every trade cannot be assured by candle alignment.
