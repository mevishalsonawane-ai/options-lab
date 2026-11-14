# Sources and previous findings used

- [Guo et al., On Calibration of Modern Neural Networks](https://proceedings.mlr.press/v70/guo17a.html), ICML2017. Abstract/documentation reviewed: confidence estimates need calibration; temperature scaling can help on benchmark datasets. Our positive temperature plus intercept is an adaptation, not a guarantee for nonstationary financial data.
- [Geifman and El-Yaniv, Selective Classification](https://arxiv.org/abs/1705.08500), NeurIPS2017, and [SelectiveNet](https://proceedings.mlr.press/v97/geifman19a.html), ICML2019. Abstracts reviewed for the coverage/error tradeoff. This batch uses fixed rejection gates; it does NOT implement SelectiveNet or claim its benchmark error guarantees transfer to correlated trading outcomes.
- [Corsi, HAR realized volatility](https://doi.org/10.1093/jjfinec/nbp001). Reviewed the multi-horizon volatility-model idea during triage. It was NOT implemented as another new profitable straddle strategy; repository R89 already tested buying straddles after a magnitude forecast and found no passing version.
- R163 (`research/hunt/r163_donchian_picker/results/report.md`): trees, neural models and calibrated ensembles with chain/SR inputs failed its Donchian improvement gates. High predicted confidence did not reliably translate to high realized wins. We acknowledge this related prior research rather than present the same concept as a new discovery.
- audit_v3: causal elapsed-time underlying-direction models were near chance. Payoff20 therefore labels a specified costed option payoff, not merely the sign of the index return.
- confluence65: earlier frozen entry/context combinations failed; no threshold from this later test was tuned to make those reported losses disappear.
- R89_SIZE_STRADDLE: predicted magnitude alone did not establish an option-buying edge once already-priced volatility and four-order costs were included. This was reviewed, not rerun in this batch.

Only the registered Payoff20 pipeline was newly executed. Primary papers above
motivate methods and safeguards; none supplies a verified65% Indian intraday
options win rate. Source costs and fixed-contract engine are the previously
checked corrected research implementation. No live quotes were scraped.
