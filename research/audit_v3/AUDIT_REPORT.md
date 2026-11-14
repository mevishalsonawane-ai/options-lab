# Research and learning audit

14 November 2026. Scope: options-lab research and knowledge sources, plus the
previous neural/strategy experiments. Production, workflows and application
knowledge assets are unchanged. Work is isolated on an experimental branch.

## Coverage and what it means

The scanner catalogued 9,149 research files, including 919 Markdown studies,
2,057 Python sources, 4,635 YAML documents, 927 JSON files and 277 CSV tables.
It parsed all 4,630 experiment entries and all 19 canonical strategy cards.
Read coverage, byte hashes, parsed headers and missing/binary exclusions are in
audit/coverage.json and the compressed inventory. Extensionless holdout markers
are included in the expanded pass. No study is declared independently reproduced
merely because its file was read or parsed.

The strategy matrix covers the 19 cards plus R51, R72, R52, CISD and the latest
confirmed breakout. None of those 24 rows is marked as having a newly verified
win-rate improvement. Non-Indian-index, overnight and multi-leg strategies are
not eligible for a blanket intraday direction filter.

## Confirmed issues and tested corrections

| Issue | Source/evidence | Impact | Correction in this branch |
|---|---|---|---|
| Settlement exercise STT is fixed at 0.125% | options_lab/costs.py:189-220; the protective wing calls it in options_lab/strategy/expiry_put.py:139 | For exercises from April 2026 the applicable rate is 0.15%, so this helper underprices the buyer's exercise tax. This does not prove every study used that helper. | Research-only dated_buy_to_settle and stt_rate in guards.py; date-boundary regression. Rs100 intrinsic x65 units gives Rs9.75 rather than Rs8.125 after the change. Production helper is left unchanged for review. |
| Historical sale taxes and exchange/IPFT can be mixed | static current rates in options_lab/costs.py; the earlier neural helper excluded pre-March IPFT | Current-date constants cannot automatically price every historical session. Missing IPFT was already corrected in the latest strategy replay. | Reuse the corrected strategy cost function; explicit dated exercise/sale guards. Verified schedule coverage is bounded; no extrapolation before October2024 or after the current verified date. |
| Number presence is not contextual evidence | research/knowledge/build_pack.py:607-641 checks numbers against a whole document, while cards also declare sections | The same number can occur in a different row, market, cost regime or period. Matching it does not verify the claim. | Section-scoped numeric guard with a regression showing that a number in section A cannot justify a claim about section B. Full semantic verification still requires source review. |
| Knowledge document ingestion is shallow | research/knowledge/build_pack.py:1080-1082 uses research/*.md | Nested reports such as R163/R169/R170/R171 do not enter as full document facts through this route; experiment summaries can still enter separately. | Recursive read-only research inventory and an expanded strategy matrix. The app pack is not regenerated or silently promoted. |
| Knowledge-card coverage and confidence are insufficient for promotion | 19 cards; current Donchian/CISD families are outside that card list; keyword-based confidence/verdict logic at build_pack.py:273 and 505 | A keyword such as holdout does not prove the period is pristine or an idea passed. A weak card is not a live-trading authorization. | Explicit evidence gate requires verified costs/contracts/parity, adequate sample, fresh data, positive stressed net, risk limits and multiplicity control. It is an audit check, not an order authorizer. |
| Operational permission is not statistical validation | build_pack.py:685-686 maps paper-only to a pass gate, including app-origin owner exceptions whose text says unproven | A downstream learner must not treat a permitted paper arm as a positive-profit label or a fresh research pass. This is a semantic risk, not proof that the app violated its own permissions. | The independent evidence gate requires explicit validation fields; no production permission or owner exception is changed. |
| Direction labels and trade-choice labels were conflated | earlier baseline uses a within-day row shift; neural_v2 predicts CE/PE/no-trade and option payoff | Fifteen rows need not be fifteen minutes when bars are missing; an option-choice classifier does not directly answer underlying direction. | New 5/15/30 elapsed-timestamp targets, explicit availability time, masked missing/tied outcomes, causal sequence tests. |
| Future availability can condition a backtest cohort | neural_v2/pipeline.py requires both complete future option paths before retaining a sample | This is a disclosed retrospective coverage restriction, not an executable signal-time test of path availability. | Direction forecasts are generated for every valid causal feature row regardless of whether a future label exists; masks affect scoring/loss only. The new remove-only gate never reads outcome columns. |
| Calibration, historical reuse and small sample are decisive | R151/R163 show confidence exaggeration; R133/R169 disclose reused final periods | A 60% softmax score is not a demonstrated 60% success rate. A newly chronological split cannot undo previous inspection. | Distinct fit/epoch-validation/calibration/outer windows; monotone calibration; fixed55% threshold; sample/coverage reporting; day-clustered tests and BH across18 specified endpoints. All results remain reused development evidence. |

Authoritative tax reference: https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax
Exchange/IPFT reference: https://nsearchives.nseindia.com/content/circulars/FA73061.pdf

## A genuine test issue, not lost knowledge

The complete knowledge test suite initially failed its always-retained check
for app-s5-trail-paper and app-irathinker-flow-paper. Investigation showed that
the entries were retained with kind=app, as designed. The test incorrectly
counted only kind=experiment. Its invariant now checks both categories with
exp: IDs and explicitly asserts the two app-origin entries remain app items.
No test was disabled and no production pack was changed. Initial sparse-checkout
missing-source errors were resolved by reading the existing study/pages.txt;
those were workspace coverage issues, not repository defects.

## The win-rate flaw in the previous strategy

R105C, R151, R162/R163 and the option-buying guide repeatedly distinguish a
higher win rate from higher profit. The previous confirmed-breakout design's
hard +30% target and early profit lock could cut the rare runners that pay for
breakout losses. Blindly applying those exits to Liquidity or ORB contradicts
the strategy-specific evidence; R19 found locks harmful to Liquidity, while
other families may need different exits.

The new runner-preserving composite improved net P&L from -Rs38,542 to
-Rs14,866 and realized drawdown from Rs38,662 to Rs26,186 on the same available
year, but its win rate fell from44.13% to39.70%. It still lost money; doubled
friction left a loss of about Rs36,757. The no-target-only ablation did not
produce a robust improvement either. These changes are rejected as trading
promotions, not relabelled as successes because one metric improved.

R163 also documents a critical parity distinction: skipped breaks keeping a
side occupied do not produce the same trade list as skips freeing capacity.
Its R72 picker comparison changes from the earlier Rs2.19L reading to roughly
Rs1.70L under the app's walk. The diagnostic gate here removes only existing
baseline trades, preserves their old exits/time slots and never promotes a new
entry. It is still not a full changed-path intrabar equity replay or parity
proof for all historical engines.

## Direction model outcome

Six neural checkpoints were trained: MLP and CNN-GRU attention across three
outer folds, pooled on NIFTY and BANKNIFTY with26 causal features. Predictions
cover three elapsed horizons and are calibrated on earlier independent windows.

For 15 minutes, CNN-GRU scored:

| Market | Scored forecasts | Accuracy | Balanced accuracy | AUC | BH q |
|---|---:|---:|---:|---:|---:|
| NIFTY | 6,808 | 51.06% | 50.48% | 0.505 | 0.467 |
| BANKNIFTY | 6,819 | 50.70% | 49.97% | 0.491 | 0.515 |

No 15-minute GRU forecast crossed the fixed55% confidence gate. The strategy
filter therefore kept zero trades, so its trading win rate is undefined.
Zero exposure is not a proven profitable strategy. A 5-minute NIFTY subset had
53.89% accuracy on only386 forecasts; the complete multiple-testing result did
not establish an edge. Do not market this subset as reliable direction skill.

The model's feature history starts forecasts at10:15. It does not cover the
opening breaks that the existing research identifies as important, nor does it
cover overnight, commodity or crypto strategies. Direction accuracy is not an
option's cost-adjusted win probability. See direction_results/REPORT.md and
improvement_results/REPORT.md for every endpoint and account replay.

## What can responsibly change next

1. Integrate dated charges, exact timestamp labels and explicit evidence-state
   checks only after their original-engine parity tests; these are correctness
   improvements, not promises of a higher win percentage.
2. For each strategy, reproduce its plain rules on one held contract with its
   original time/side/cap semantics. Judge any skip by win rate, net expectancy,
   drawdown, stressed fills and retained sample size together.
3. Preserve runner-dependent payoffs. The repository already shows that tiny
   targets can achieve high win rates and still lose. Avoid a universal exit.
4. Research magnitude/regime models as separate risk/context inputs; stronger
   range predictability does not prove profitable straddles or direction skill.
5. Obtain genuinely new forward sessions and executable quote/order-flow data
   before a new trading verdict. This run establishes no improvement for every
   strategy and no paper/live approval. No orders or production changes.
