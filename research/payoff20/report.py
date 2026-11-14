"""Render payoff learning outcomes without promoting small winning subsets."""
import argparse
import json
from pathlib import Path
from research.audit_v3.direction import load_forecasts
from .study import hindsight


def write(out):
    result=json.loads((out/'results.json').read_text());metrics=json.loads((out/'candidate_metrics.json').read_text())
    forecast=load_forecasts(out/'forecasts.npz');oracle=hindsight(forecast[forecast.model=='linear'])
    (out/'hindsight_outer_diagnostics.json').write_text(json.dumps(oracle,indent=2)+'\n')
    full=result['results'];normal={k:v for k,v in full.items() if k.endswith('__x1.0')}
    empirical=[]
    for key,v in normal.items():
        stress=full[key[:-len('__x1.0')]+'__x2.0']
        if v['trades']>=100 and v['win_rate']>=.65 and v['net']>0 and stress['net']>0 and (v['profit_factor'] or 0)>1 and v['max_drawdown']<40000 and v['worst_month']>-13000 and v['bh_q']<=.1:empirical.append(key)
    decision=dict(status='historical_candidate_only' if empirical else 'completed_no_qualifying_strategy',qualifying_historical_policies=empirical,
        demonstrated_65pct_strategy=False,fresh_holdout=False,production_changed=False,orders_placed=False,
        models=3,outer_folds=2,policies=30,account_endpoints=60)
    (out/'decision.json').write_text(json.dumps(decision,indent=2)+'\n')
    audit=result['audit'];start=forecast.day.min();end=forecast.day.max()
    lines=['# Cost-aware 20-minute option payoff research','',
        '**No dependable 65%-win profitable strategy established. No paper/live promotion.**',
        f"Training source: {audit['start']}–{audit['end']}, {audit['sessions']}sessions, {audit['candidate_rows']}potential action rows, {audit['observed_labels']}observed labels and {audit['missing_labels']}masked outcomes. {len(audit['features'])}causal features. Outer predictions: {start}–{end} on eligible non-expiry decision days.",
        'Three systems (logistic/ridge, gradient trees, two-head neural MLP) fit on earlier data, with separate epoch selection and probability calibration. Saved outer folds never use all-data weights. Full normal and stress accounts each startRs100000 once and continue across the two outer windows.',
        f'Qualifying historical policies after sample/cost/risk/BH gates: {len(empirical)}. All dates were previously inspected in repository studies; chronological separation does not restore pristine data.',
        '', '## Candidate payoff prediction','',
        'These are hypothetical standalone candidate outcomes, NOT the win rates of executed, account-gated trades.',
        '', '| Model | Scored actions | Actual positive-net share | AUC | Brier | Net MAE Rs |','|---|---:|---:|---:|---:|---:|']
    for kind,s in metrics.items():lines.append(f"| {kind} | {s['samples']} | {100*s['observed_win_rate']:.2f}% | {s['auc']:.3f} | {s['brier']:.4f} | {s['net_mae']:.2f} |")
    lines+=['','## Actual executed accounts and fixed threshold sensitivity','',
        'p is the calibrated normal-friction probability; net is the predicted net-rupee floor. Each model has the entire predeclared3x3grid, with no threshold selected after reading outer results. Zero trades have undefined win rate.',
        '', '| Policy | Trades | Net wins | Win % | Net Rs | Double-friction net Rs | Realized DD Rs | Wilson95% win interval | Block BH q |',
        '|---|---:|---:|---:|---:|---:|---:|---|---:|']
    for key,s in normal.items():
        t=full[key[:-len('__x1.0')]+'__x2.0'];wr='n/a' if not s['trades'] else f"{100*s['win_rate']:.2f}"
        lo,hi=s['wilson95'];interval='n/a' if lo is None else f'{100*lo:.1f}–{100*hi:.1f}%'
        lines.append(f"| {key[:-len('__x1.0')]} | {s['trades']} | {s['wins']} | {wr} | {s['net']:.2f} | {t['net']:.2f} | {s['max_drawdown']:.2f} | {interval} | {s['bh_q']:.3f} |")
    lines+=['','Complete calibration bins, predictions, all60executed ledgers, chronological restart diagnostics, MonteCarlo and costs are saved. A few profitable trades or a selected100% reading do not meet the100-trade requirement or demonstrate65% future success.',
        '', '## Hindsight diagnostics: explicitly non-tradable','',
        f"On {oracle['paired_observed_decisions']}outer decision times with both outcomes observable, at least one option had positive gross P&L at {100*oracle['either_option_gross_positive_share']:.2f}% of times, versus {100*oracle['actual_best_option_positive_share']:.2f}% after costs. Perfect endpoint direction selected a profitable stopped option at {100*oracle['perfect_endpoint_direction_win_rate']:.2f}% of times.",
        'Both calculations look into the future. They are diagnostics of the opportunity pool, not executable signals or a demonstrated strategy. They ignore cash/day caps and restrict to paired observed outcomes. A selective strategy can exceed an always-on fraction, so this is not a universal upper bound. The learned models must identify profitable cases BEFORE entry; the AUC/calibration tables show whether they actually can.',
        '', '## Monte Carlo and chronology','',
        'Every account has1000fixed-rupee trade permutations and1000five-consecutive-session block resamples, including zero-trade sessions; metrics are in results.json. Shuffling leaves total P&L unchanged and does not replay counterfactual cash admission. Empty ledgers stay zero and prove no edge. Single-trade bootstrap intervals are especially uninformative. Daily block net p-values are jointly BH adjusted across60endpoints, not the entire prior research program.',
        'The first outer model learns from pre-April sessions; the second learns from pre-July sessions. Within each preceding block,70%fit/15%validation/15%calibration by whole dates. Label availability is bounded by the last earlier session15:30. Full trading equity does not reset at July; the separately reported calendar-window diagnostics do reset and must not be summed.',
        '', '## Execution, targets and limitations','',
        'Signal/feature clock is on the underlying. Option chain snapshots are context. At each five-minute decision09:45–13:45, evaluate both one-strike-ITM actions; enter at the next-minute option open with modeled friction. Keep the same strike and scheduled expiry. Exit at an initial15% premium stop or20minutes; no target or profit lock. Missing held quotes delay fills. Account loss gates and14:50square-off can close earlier; normal-label win probabilities are not exact account-conditional probabilities.',
        'Labels use historical counterfactual trade paths. Missing outcomes are masked only in loss/calibration/scoring; every valid causal feature row still receives predictions, and execution may reject it for absent quotes, cash or risk. Features use train-only median imputation and missing indicators, train-only scaling, clipped z-scores. Entry-open prices and future returns never enter the feature matrix.',
        'The two MLP heads learn BCE(net>0)+0.25Huber(net/1000), with a64/32/16network; early stopping is on preceding validation data. Probabilities receive positive-temperature/intercept calibration on a different earlier window. Regression estimates are not guaranteed profits. Models are fit to normal friction and replayed unchanged under double friction.',
        'Costs use the corrected shared research helper: brokerage, dated taxes, exchange/IPFT, GST, SEBI and stamp plus assumed spread/slippage. The cost model and zero-latency open fill remain assumptions; these are minute OHLC simulations, not recorded executable bid/ask or intrabar worst drawdown. Budgets: one lot, max2trades/day,2500initial risk,3000daily,13000monthly,40000drawdown.',
        'Reported IV and modeled Black-Scholes delta are not buyer/seller delta. Signed volume remains a proxy. No futures-volume or footprint feature is added to paper over its limited history; no order book or spoofing detector is claimed. New forward data and executable quotes are still needed. No paid resources, real trades, production changes or universal internet/math search claim.']
    lines+=['','## Nonempty-account sequence diagnostics','',
        '| Policy | Trades | Actual DD Rs | Shuffled DD95 Rs | Day-block DD95 Rs | Day-block net95% interval Rs |',
        '|---|---:|---:|---:|---:|---|']
    for key,s in normal.items():
        if not s['trades']:continue
        m=s['monte_carlo'];lo,hi=m['day_block_net_95_interval']
        lines.append(f"| {key[:-len('__x1.0')]} | {s['trades']} | {m['actual_drawdown']:.2f} | {m['shuffled_dd95']:.2f} | {m['day_block_dd95']:.2f} | {lo:.2f} to {hi:.2f} |")
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n');return decision


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,default=Path(__file__).parent/'results');a=p.parse_args();write(a.out)
