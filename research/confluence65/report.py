"""Render every primary result, Monte Carlo and the frozen neighborhood."""
import argparse
import json
from pathlib import Path
import numpy as np
import pandas as pd


def write_report(result,out):
    out.mkdir(parents=True,exist_ok=True)
    full=result['full'];leader=result['design_leader'];base=full[leader+'__x1.0'];stress=full[leader+'__x2.0']
    normal={k:v for k,v in full.items() if k.endswith('__x1.0')}
    large=[(k,v) for k,v in normal.items() if v['trades']>=100]
    best=max(large,key=lambda kv:kv[1]['win_rate']) if large else None
    point65=[k for k,v in large if v['win_rate']>=.65]
    lines=['# Search for a 65%-win, cost-profitable NIFTY option strategy','',
        '**No validated 65% profitable strategy was established. No paper/live promotion.**',
        f"Period {result['audit'].get('start')}–{result['audit'].get('end')}; {result['audit']['sessions']} sessions;100000 rupees starting capital per full account.",
        f"Tested {result['primary_rule_count']} unique rule/settings combinations, each at normal and doubled execution friction. Support/resistance, breakouts, sweep/reclaim, mean reversion, option volume/OI, modeled delta/IV, signed-volume proxy and observed futures volume/VWAP/profile are explicitly separated.",
        f"Normal-friction arms with at least100trades and point winrate>=65%: {len(point65)}. The highest sufficiently sampled win rate was "+(f"{100*best[1]['win_rate']:.2f}% on {best[1]['trades']} trades, net Rs{best[1]['net']:.2f} ({best[0]})." if best else 'not available.'),
        '', '## All primary replay results','',
        '| Rule | Trades | Net win % | Net Rs | 2x friction net Rs | PF | Realized DD Rs | Wilson95% win interval | Block BH q |',
        '|---|---:|---:|---:|---:|---:|---:|---|---:|']
    for k,v in normal.items():
        twin=full[k[:-len('__x1.0')]+'__x2.0'];ci=v['wilson95']
        interval='n/a' if ci[0] is None else f'{100*ci[0]:.1f}–{100*ci[1]:.1f}%'
        pf='n/a' if v['profit_factor'] is None else f"{v['profit_factor']:.2f}"
        wr='n/a' if not v['trades'] else f"{100*v['win_rate']:.2f}"
        lines.append(f"| {k[:-len('__x1.0')]} | {v['trades']} | {wr} | {v['net']:.2f} | {twin['net']:.2f} | {pf} | {v['max_drawdown']:.2f} | {interval} | {v['bh_q']:.3f} |")
    lines+=['','## Frozen development leader','',
        f"Descriptive leader: `{leader}`. Selected on first-window stressed net among rules with>=30 normal trades; it was not a passing strategy and was frozen before later-window replay. Full normal: {base['trades']} trades, {100*base['win_rate']:.2f}% wins, Rs{base['net']:.2f} net; stress Rs{stress['net']:.2f}.",
        '', '| Window (separate diagnostic account) | Trades | Win % | Normal net Rs | Stress net Rs |','|---|---:|---:|---:|---:|']
    for window in ('design','q2','q3'):
        s=result['chronological'][leader+'__x1.0'][window];t=result['chronological'][leader+'__x2.0'][window]
        lines.append(f"| {window} | {s['trades']} | {100*s['win_rate']:.2f} | {s['net']:.2f} | {t['net']:.2f} |")
    lines+=['','## Walk-forward selection','',
        'The selector sees only preceding dates; test accounts carry cash/peak/monthly risk across folds. A rule requires>=100 preceding trades,>=65% wins, positive normal/stress net, PF>1 and the registered risk gates. No qualifying rule means no trade.',
        '', '| Prior data through | Evaluation begins | Qualified rules | Locked choice |','|---|---|---:|---|']
    for d in result['walk_forward']['decisions']:
        lines.append(f"| {d['training_end']} | {d['evaluation_start']} | {d['qualified']} | {d['selected']} |")
    lines+=[f"\nWalk-forward normal net: Rs{result['walk_forward']['normal_net']:.2f}. A no-trade outcome is not evidence of a profitable edge. Dates were reused by previous studies, so this is chronological development evidence, not pristine out-of-sample proof.",
        '', '## Parameter sensitivity','',
        'Only the design-selected family/context/target is varied. All nine cells are shown; the center was already in the32 primary rules, so eight extra rules were counted. No later-window parameter optimization.',
        '', '| Lookback | Stop % | Trades | Win % | Normal net Rs | Stress net Rs | PF |','|---:|---:|---:|---:|---:|---:|---:|']
    healthy=0;neighbours=0
    for k,v in result['sensitivity'].items():
        if v['stress']!=1.:continue
        t=result['sensitivity'][k[:-len('__x1.0')]+'__x2.0']
        if (v['lookback'],v['stop'])!=(20,.15):
            neighbours+=1;healthy+=int(v['trades']>=100 and (v['profit_factor'] or 0)>1 and v['net']>0 and t['net']>0)
        pf='n/a' if v['profit_factor'] is None else f"{v['profit_factor']:.2f}"
        lines.append(f"| {v['lookback']} | {100*v['stop']:.0f} | {v['trades']} | {100*v['win_rate']:.2f} | {v['net']:.2f} | {t['net']:.2f} | {pf} |")
    lines+=[f'\nHealthy neighboring cells: {healthy}/{neighbours}, requiring>=100trades, PF>1 and positive normal AND stressed net. This is stricter than a PF-only green heatmap.',
        '', '## Monte Carlo: fixed development leader','',
        '| Friction | Actual realized DD Rs | Shuffled DD95 Rs | Shuffled DD99 Rs | Five-day-block DD95 Rs | Five-day-block net95% interval Rs |',
        '|---|---:|---:|---:|---:|---|']
    for key,v in result['leader_monte_carlo'].items():
        lo,hi=v['day_block_net_95_interval']
        lines.append(f"| {key}x | {v['actual_drawdown']:.2f} | {v['shuffled_dd95']:.2f} | {v['shuffled_dd99']:.2f} | {v['day_block_dd95']:.2f} | {lo:.2f} to {hi:.2f} |")
    lines+=['','1000 shuffles retain every fixed-rupee trade P&L, so total profit is unchanged. They assess order risk, not whether the strategy has an edge. The1000 five-day block resamples retain within-day order and zero-trade days. Neither resimulation reruns cash/risk gates under reordered signals, models intratrade worst drawdown or justifies position sizing on its own.',
        '', '## What was measured and what was not','',
        f"Observed futures sessions: {result['audit'].get('futures_sessions')}. Far-month coverage limits that gate; zero or tiny samples cannot pass. Approximate profile assigns each futures minute's volume to its typical-price25-point bin. It is not a footprint or true traded volume-at-price.",
        'Option OI/volume are observed, but OI has no unique buyer/seller directional interpretation. Both sides of an open contract exist. PCR/OI-change filters are hypotheses, not verified smart-money labels. Changes compare the SAME strike and side at exact five-minute timestamps. Reported IV uses its percent scale; invalid/zero IV fails Greek gates. Black-Scholes delta uses reported IV, scheduled expiry and6.5% rate; it is a model exposure, not aggressor delta.',
        'Signed minute volume uses the option candle body sign and is explicitly a PROXY. Resting liquidity, spoofing, actual stop executions, market depth and true buyer/seller delta were not tested. Phone recordings remain encrypted. A sweep detector proves a price-level crossing/reclaim, not a liquidity raid.',
        'Orders are simulated at next-minute option opens, fixed strike/expiry throughout. OHLC fills use conservative stop-first ordering; a touched order is not guaranteed executable. Brokerage, taxes and modeled spread/slippage are included. Square-off14:50; initial stop15%, targets10%/30%, max45minutes and existing profit lock/time stop. One lot, max2trades/day and cash/daily/monthly/drawdown limits. Sensitivity stops12%/18% change both exits and admission risk consistently.',
        'All80 full-account p-values are day-block based and BH adjusted. This does not repair selection across the previously inspected research program. Win-rate Wilson intervals assume independent trades; day clustering can make that optimistic. A dependable65% claim needs new data and a lower confidence bound at least65%, not just a selected historical point estimate.',
        '', '## Why65% wins can still lose','',
        'With average gross win W, average gross loss L and average cost C, expectancy=pW−(1−p)L−C; break-even p=(L+C)/(W+L). A65% win rate on Rs100 wins and Rs300 losses loses Rs4000 per100trades before costs. Accuracy, win rate and account return are different quantities.',
        '', 'See ../PREREG.md, ../SOURCES.md, audit.json, design_selection.json, results.json and saved leader/WF ledgers. No paid resources, production changes, real trades or claim of exhaustive internet coverage.']
    lines+=['','## Diagnostic figures','','![Parameter sensitivity](sensitivity.png)','','![Sequence-risk simulation](sequence_risk.png)']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n')
    return dict(qualified_point65=len(point65),healthy_neighbours=healthy,total_neighbours=neighbours,
        highest_sampled_win_rule=best[0] if best else None)


def plots(result,out):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import matplotlib.colors as colors
    grid=np.full((3,3),np.nan)
    for v in result['sensitivity'].values():
        if v['stress']==1. and v['profit_factor'] is not None:
            grid[(16,20,24).index(v['lookback']),(.12,.15,.18).index(v['stop'])]=v['profit_factor']
    fig,ax=plt.subplots(figsize=(6,4));im=ax.imshow(grid,cmap='RdYlGn',norm=colors.TwoSlopeNorm(vmin=0,vcenter=1,vmax=max(2,np.nanmax(grid) if np.isfinite(grid).any() else 2)))
    for i in range(3):
        for j in range(3):ax.text(j,i,'n/a' if not np.isfinite(grid[i,j]) else f'{grid[i,j]:.2f}',ha='center',va='center')
    ax.set(xticks=range(3),xticklabels=['12%','15%','18%'],yticks=range(3),yticklabels=[16,20,24],xlabel='Initial premium stop',ylabel='Level lookback (minutes)',title='Frozen design leader: profit factor\nHistorical development; no 65% edge established')
    fig.colorbar(im,ax=ax,label='Profit factor');fig.tight_layout();fig.savefig(out/'sensitivity.png',dpi=160);plt.close(fig)
    d=pd.read_csv(out/'leader_1.0.csv');v=d.net.to_numpy(float);rng=np.random.default_rng(42)
    paths=np.array([np.r_[0,np.cumsum(rng.permutation(v))] for _ in range(1000)])
    dd=(np.maximum.accumulate(paths,axis=1)-paths).max(axis=1);q=np.percentile(paths,[5,50,95],axis=0)
    fig,axes=plt.subplots(1,2,figsize=(10,4));ax=axes[0];ax.fill_between(range(len(q[0])),q[0],q[2],color='#7aa6c2',alpha=.4,label='5–95% shuffled paths');ax.plot(q[1],color='#47788f',label='Shuffle median');ax.plot(np.r_[0,np.cumsum(v)],color='#bb403b',label='Observed sequence');ax.axhline(0,color='grey',lw=.6);ax.set(xlabel='Closed trade count',ylabel='Cumulative net P&L (Rs)',title='1000 fixed-rupee permutations');ax.legend(fontsize=7)
    ax=axes[1];ax.hist(dd,bins=30,color='#47788f');ax.axvline(np.percentile(dd,95),color='#bb403b',label='95th percentile');ax.set(xlabel='Realized maximum drawdown (Rs)',ylabel='Permutations',title='Sequence risk; fixed ending P&L');ax.legend(fontsize=8)
    fig.tight_layout();fig.savefig(out/'sequence_risk.png',dpi=160);plt.close(fig)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,default=Path(__file__).parent/'results');a=p.parse_args()
    result=json.loads((a.out/'results.json').read_text());write_report(result,a.out);plots(result,a.out)
