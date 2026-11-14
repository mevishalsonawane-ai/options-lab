"""Frozen entry policy; small exit grid, independent accounts and chronological checks."""
import argparse
import json
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

from research.retest import study as T
from research.confluence65 import engine as E, robustness as R
from research.neural_v2 import pipeline as P

ENTRY = ('opening', 'retest', 'trend_cost', 'quick', 5, .1)
CENTER = (.15, .10, 20)
POLICIES = [(s, t, 20) for s in (.10, .15, .20) for t in (.08, .10, .12)] + [(.15, .10, h) for h in (10, 30)]


def name(policy, delay=0):
    stop, target, hold = policy
    return f's{stop:.2f}_t{target:.2f}_h{hold}_d{delay}'


def exit_fill(bar, entry, elapsed, policy):
    stop_pct, target_pct, hold = policy
    if not (0 < stop_pct < 1 and 0 < target_pct < 2 and hold > 0):
        raise ValueError('Invalid exit geometry')
    op, hi, lo, close, volume = bar
    if not np.isfinite(bar).all() or volume <= 0 or min(op, hi, lo, close) <= 0:
        return None
    stop, target = entry * (1-stop_pct), entry * (1+target_pct)
    if op <= stop:
        return op, 'gap_stop'
    if elapsed >= hold:
        return op, 'time_exit'
    if op >= target:
        return op, 'gap_target'
    if lo <= stop:
        return stop, 'initial_stop'
    if hi >= target:
        return target, 'target'
    return None


def replay(sessions, policy, stress, delay=0, state=None, diagnostics=False):
    original = E.exit_fill
    try:
        E.exit_fill = lambda bar, entry, stop, elapsed, target: exit_fill(bar, entry, elapsed, policy)
        ledger, summary = E.replay(T.adapt(sessions, ENTRY, delay), stress,
                                   stop_pct=policy[0], target_pct=policy[1], state=state)
    finally:
        E.exit_fill = original
    state = summary.pop('_state')
    if diagnostics:
        days = [s['day'] for s in sessions]
        summary['wilson95'] = R.wilson(summary['wins'], summary['trades'])
        summary['block_p_value'] = R.block_pvalue(ledger, days)
        summary['monte_carlo'] = R.monte_carlo(ledger, days)
    return ledger, summary, state


def attribution(ledger):
    groups = {}
    for column in ('reason', 'side'):
        groups[column] = {str(k): dict(trades=len(g), wins=int((g.net > 0).sum()),
            gross=float(g.gross.sum()), fees=float(g.fees.sum()), friction=float(g.friction.sum()),
            net=float(g.net.sum())) for k, g in ledger.groupby(column)}
    groups['positive_gross_but_nonpositive_net'] = int(((ledger.gross > 0) & (ledger.net <= 0)).sum())
    groups['mean_cost_per_trade'] = float((ledger.fees + ledger.friction).mean()) if len(ledger) else None
    return groups


def matched_entries(original, wider):
    keys = ['day', 'entry_minute', 'side', 'strike', 'expiry']
    joined = original.merge(wider, on=keys, how='outer', suffixes=('_original', '_wider'), indicator=True,
                            validate='one_to_one')
    return dict(exploratory=True,
        basis='Post-result matched-entry diagnostic; risk eligibility and subsequent account paths may differ',
        groups={str(k):dict(entries=len(g), original_net=float(g.net_original.sum()),
            wider_stop_net=float(g.net_wider.sum())) for k, g in joined.groupby('_merge', observed=True)},
        net_difference=float(wider.net.sum()-original.net.sum()))


def verify(ledger, summary):
    assert len(ledger) == summary['trades']
    assert int((ledger.net > 0).sum()) == summary['wins']
    assert np.isclose(ledger.net.sum(), summary['net'])
    assert np.allclose(ledger.net.to_numpy(float),
                       (ledger.gross-ledger.fees-ledger.friction).to_numpy(float))
    assert np.isclose(summary['ending_capital'], summary['starting_capital']+ledger.net.sum())
    assert (ledger.exit_minute >= ledger.entry_minute).all()
    assert (ledger.expiry > ledger.day).all()


def write_report(out, result):
    rows = result['accounts']
    lines = ['# Opening-range retest: exit economics', '',
        'Research only. Previously inspected historical data; no independent final holdout.',
        '11 exit policies plus one latency arm, each at normal and doubled modeled friction.',
        'Every account starts with Rs100,000; changing stop size changes risk eligibility.', '',
        '| Stop | Target | Hold min | Delay min | Trades | Win % | Gross Rs | Costs Rs | Net Rs | Stress net Rs | PF | BH q |',
        '|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for policy, delay in [(p, 0) for p in POLICIES]+[(CENTER, 1)]:
        a, b = [rows[name(policy, delay)+f'_x{x}'] for x in (1., 2.)]
        lines.append(f'| {policy[0]:.0%} | {policy[1]:.0%} | {policy[2]} | {delay} | {a["trades"]} | {a["win_rate"]*100:.2f} | {a["gross_profit"]:.2f} | {a["fees"]+a["execution_friction"]:.2f} | {a["net"]:.2f} | {b["net"]:.2f} | {a["profit_factor"] or 0:.2f} | {a["bh_q"]:.3f} |')
    lead = rows[name((.20,.10,20))+'_x1.0']
    lead_stress = rows[name((.20,.10,20))+'_x2.0']
    groups = result['matched_entries']['groups']
    both = groups.get('both', dict(entries=0, original_net=0., wider_stop_net=0.))
    lines += ['', '## Interpretation', '',
        f'The 20%stop/10%target policy wins {lead["wins"]} of {lead["trades"]} trades ({lead["win_rate"]:.2%}), net Rs{lead["net"]:.2f}; doubled-friction net Rs{lead_stress["net"]:.2f}. Its Wilson95%win interval is {lead["wilson95"]}, assuming independent trades. This is exploratory history, not evidence that the future win rate is at least65%.',
        f'Profitable doubled-friction endpoints: {sum(s["net"]>0 for k,s in rows.items() if k.endswith("_x2.0"))} of12. Read all target neighbors above rather than interpreting only the best point.',
        f'The20%stop also changes risk-budget admission. A post-result comparison against the15%stop/10%target center has {both["entries"]} matching entries: their net changes by Rs{both["wider_stop_net"]-both["original_net"]:.2f}, compared with the whole-account change of Rs{result["matched_entries"]["net_difference"]:.2f}. Differing executed trades contribute to that gap; it cannot be attributed solely to allowing the same positions more room. Full decomposition is below.',
        'The latency arm tests the original15%center only, not the retrospectively interesting20%stop policy. The profitable boundary of this small grid is not a demonstrated robustness plateau.', '',
        '## Chronological selection', '',
        'The strict selector uses only preceding dates and retains the original 100-trade and 65%-win thresholds. Historical dates were previously reviewed in other research.', '',
        '```json', json.dumps(result['walk_forward']['decisions'], indent=2), '```', '',
        '## Cost and exit diagnosis for the original center', '',
        '```json', json.dumps(result['center_attribution'], indent=2), '```', '',
        'Post-result matched-entry diagnostic:', '```json', json.dumps(result['matched_entries'], indent=2), '```', '',
        '## Validation and limitations', '',
        f'Baseline numeric ledger parity: {result["baseline_parity"]}.',
        f'Profitable normal/stress center-grid neighbors: {result["healthy_neighbors"]}/8.',
        f'Qualifying historical policies: {result["qualifying_historical"]}.',
        'All ledgers reconcile net = gross - fees - friction and ending capital.',
        'results.json includes each policy in separate April-June and July-October accounts, Wilson intervals, 1,000 permutations and 1,000 five-day block resamples. These separate accounts do not sum to a continuous portfolio.',
        'Monte Carlo resamples fixed rupee closed-trade P&L; it does not replay reordered cash gates or establish an edge. BH adjustment covers this batch only, not the entire research history.',
        'Modeled OHLC fills are not executable quotes. Missing holding bars and expiry inference limitations of the shared loader still apply. No true 10-second, depth or aggressor-volume information is introduced.',
        'No model or strategy is approved for trading. Prospective unseen data is required even if historical thresholds pass.', '',
        '## Reproduce', '',
        '```bash', 'python -m research.exit_economics.study --lake /absolute/path/to/lake',
        'python -m pytest research/exit_economics research/retest research/neural_v2/test_strategy.py -q', '```', '',
        'Data hashes are in audit.json. The input loader and account engine are unchanged. See ../PREREG.md and ../SOURCES.md.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n')


def run(lake, out):
    out.mkdir(parents=True, exist_ok=True)
    P.status(out, stage='ingestion', errors=[])
    sessions, audit = T.load(lake, out)
    accounts, folds, payloads = {}, {}, {}
    center_ledger = None
    for i, (policy, delay) in enumerate([(p, 0) for p in POLICIES]+[(CENTER, 1)]):
        for stress in (1., 2.):
            key = name(policy, delay)+f'_x{stress}'
            ledger, summary, _ = replay(sessions, policy, stress, delay, diagnostics=True)
            verify(ledger, summary)
            accounts[key] = summary
            payloads[key+'.csv'] = ledger.to_csv(index=False).encode()
            if policy == CENTER and delay == 0 and stress == 1.:
                center_ledger = ledger
            folds[key] = {}
            for start, end in [('2026-04-01','2026-06-30'), ('2026-07-01','2026-12-31')]:
                sub = [s for s in sessions if start <= s['day'] <= end]
                d, a, _ = replay(sub, policy, stress, delay)
                verify(d, a)
                folds[key][start] = a
                payloads[f'{key}_{start}.csv'] = d.to_csv(index=False).encode()
        P.status(out, stage='replaying', completed=i+1, total=12)
        print('replayed', i+1, '/12', name(policy, delay), flush=True)
    for s, q in zip(accounts.values(), P.bh([s['block_p_value'] for s in accounts.values()])):
        s['bh_q'] = float(q)
    original, _, _ = T.replay(sessions, ENTRY, 1., diagnostics=False)
    # Different reason labels for the same time deadline are intentional.
    pd.testing.assert_frame_equal(original.drop(columns='reason'), center_ledger.drop(columns='reason'))
    states, pieces, decisions, training = {1.:None, 2.:None}, {1.:[], 2.:[]}, [], {}
    for start, end in [('2026-04-01','2026-06-30'), ('2026-07-01','2026-12-31')]:
        train = [s for s in sessions if s['day'] < start]
        test = [s for s in sessions if start <= s['day'] <= end]
        scores = {name(p): tuple(replay(train, p, x)[1] for x in (1., 2.)) for p in POLICIES}
        chosen = R.select(scores)
        training[start] = scores
        decisions.append(dict(evaluation_start=start, training_last_day=train[-1]['day'],
                              selected=chosen or 'NO_TRADE', qualified=sum(R.qualifies(*v) for v in scores.values())))
        for stress in (1., 2.):
            if chosen:
                policy = next(p for p in POLICIES if name(p) == chosen)
                d, s, states[stress] = replay(test, policy, stress, state=states[stress])
            else:
                adapted = T.adapt(test, ENTRY)
                for session in adapted:
                    session['candidates'] = {}
                d, s = E.replay(adapted, stress, state=states[stress])
                states[stress] = s.pop('_state')
            verify(d, s)
            pieces[stress].append(d)
    wf_accounts = {}
    wf_days = [s['day'] for s in sessions if s['day'] >= '2026-04-01']
    for stress in (1., 2.):
        d = pd.concat(pieces[stress], ignore_index=True)
        payloads[f'walk_forward_x{stress}.csv'] = d.to_csv(index=False).encode()
        wf_accounts[str(stress)] = P.summarize(d.assign(stress_net=d.net), wf_days)
    qualified = [name(p) for p in POLICIES if R.qualifies(*(accounts[name(p)+f'_x{x}'] for x in (1.,2.)))]
    healthy = sum(all(accounts[name(p)+f'_x{x}']['net'] > 0 for x in (1.,2.)) for p in POLICIES[:9] if p != CENTER)
    from io import BytesIO
    wider = pd.read_csv(BytesIO(payloads[name((.20,.10,20))+'_x1.0.csv']))
    matched = matched_entries(center_ledger, wider)
    P.dump(out/'matched_entries.json', matched)
    result = dict(audit=audit, accounts=accounts, chronological=folds, training=training,
        walk_forward=dict(decisions=decisions, accounts=wf_accounts), baseline_parity=True,
        center_attribution=attribution(center_ledger), healthy_neighbors=healthy,
        qualifying_historical=qualified, matched_entries=matched, approved_for_trading=False, fresh_holdout=False)
    with zipfile.ZipFile(out/'ledgers.zip', 'w', zipfile.ZIP_DEFLATED) as z:
        for filename, data in sorted(payloads.items()):
            info = zipfile.ZipInfo(filename, (2026,11,15,0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    with zipfile.ZipFile(out/'ledgers.zip') as z:
        assert len(z.namelist()) == 74
        for filename, data in payloads.items():
            assert z.read(filename) == data
    P.dump(out/'results.json', result)
    write_report(out, result)
    P.status(out, stage='completed', approved_for_trading=False)
    print('Completed:', len(accounts), 'endpoints; qualified:', qualified, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--lake', type=Path, required=True)
    parser.add_argument('--out', type=Path, default=Path(__file__).parent/'results')
    args = parser.parse_args()
    run(args.lake, args.out)
