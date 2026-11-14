"""Report the complete frozen family; improvements are not automatic passes."""
import argparse
import json
import zipfile
import pandas as pd
from research.confluence65.robustness import wilson
from pathlib import Path
from .study import name,CENTRE


def read_ledger(out,key):
    path=out/(key+'.csv')
    if path.exists():return pd.read_csv(path)
    with zipfile.ZipFile(out/'ledgers.zip') as archive:
        with archive.open(key+'.csv') as file:return pd.read_csv(file)


def archive_ledgers(out):
    files=sorted(list(out.glob('*__x*.csv'))+list(out.glob('walk_forward_*.csv')))
    if files:
        with zipfile.ZipFile(out/'ledgers.zip','w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
            for path in files:
                info=zipfile.ZipInfo(path.name,date_time=(2026,11,14,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
                archive.writestr(info,path.read_bytes(),compresslevel=9)
    with zipfile.ZipFile(out/'ledgers.zip') as archive:return len(archive.namelist())


def late_comparison(out,full):
    rows={}
    for entry in ('immediate','retest'):
        for stress in (1.,2.):
            key=f'opening__{entry}__trend_cost__quick__w5__a0.10__d0__x{stress}'
            if key not in full:continue
            ledger=read_ledger(out,key);d=ledger[ledger.day>='2026-04-01']
            n=len(d);wins=int((d.net>0).sum())
            rows[entry+'__x'+str(stress)]=dict(trades=n,wins=wins,win_rate=wins/n if n else None,net=float(d.net.sum()),wilson95=wilson(wins,n))
    result=dict(exploratory=True,period_start='2026-04-01',basis='Later subset of the full account, not a new restart or a passing WF strategy',
        selection='This example was chosen after reviewing family results; no independent validation claim',rows=rows)
    (out/'opening_late_diagnostic.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


def write(out):
    r=json.loads((out/'results.json').read_text());full=r['results']
    normal={k:s for k,s in full.items() if k.endswith('__x1.0')}
    passed=[]
    for key,s in normal.items():
        t=full[key[:-len('__x1.0')]+'__x2.0']
        if s['trades']>=100 and s['win_rate']>=.65 and s['net']>0 and t['net']>0 and (s['profit_factor'] or 0)>1 and s['max_drawdown']<40000 and s['worst_month']>-13000 and s['bh_q']<=.1:passed.append(key)
    summary=dict(unique_rules=r['unique_rules'],account_endpoints=r['account_endpoints'],positive_net_endpoints=sum(s['net']>0 for s in full.values()),
        negative_net_endpoints=sum(s['net']<0 for s in full.values()),empty_endpoints=sum(s['trades']==0 for s in full.values()),qualifying_historical_rules=passed,
        demonstrated_65pct_strategy=False,approved_for_trading=False,fresh_holdout=False,production_changed=False,orders_placed=False)
    (out/'decision.json').write_text(json.dumps(summary,indent=2)+'\n')
    audit=r['audit'];lines=['# Retest timing vs immediate breakout','',
        '**No dependable 65%-win profitable strategy established. No paper/live promotion.**',
        f"Period {audit.get('start')}–{audit.get('end')}, {audit['sessions']}available sessions.{r['unique_rules']}unique rules; normal/double-friction plus one fixed latency scenario yield{r['account_endpoints']}endpoints. Each full account startsRs100000 once; chronological diagnostics restart separately.",
        f"Positive/negative/empty account outputs: {summary['positive_net_endpoints']}/{summary['negative_net_endpoints']}/{summary['empty_endpoints']}. Qualifying historical rules after sample/cost/risk/BH gates: {len(passed)}. These are previously inspected historical dates, not a fresh final holdout.",
        '', '## Every primary result','',
        '| Rule | Trades | Win % | Gross Rs | Fees Rs | Friction Rs | Net Rs | 2x net Rs | Realized DD Rs | BH q |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for key,s in normal.items():
        t=full[key[:-len('__x1.0')]+'__x2.0'];wr='n/a' if not s['trades'] else f"{100*s['win_rate']:.2f}"
        lines.append(f"| {key[:-len('__x1.0')]} | {s['trades']} | {wr} | {s['gross_profit']:.2f} | {s['fees']:.2f} | {s['execution_friction']:.2f} | {s['net']:.2f} | {t['net']:.2f} | {s['max_drawdown']:.2f} | {s['bh_q']:.3f} |")
    late=late_comparison(out,full)
    if late['rows']:
        lines+=['','## Exploratory opening-range comparison in the later portion','',
            'This example was selected after reviewing the family. The April–October rows below are subsets of full-year account ledgers, NOT a newly approved strategy or independently selected walk-forward result. Full-year losses remain in the table above. The selector did not qualify this rule before either later window.',
            '', '| Entry | Friction | Later trades | Wins | Win % | Later net Rs |','|---|---:|---:|---:|---:|---:|']
        for key,v in late['rows'].items():
            entry,stress=key.split('__x');wr='n/a' if v['win_rate'] is None else f"{100*v['win_rate']:.2f}"
            lines.append(f"| {entry} | {stress}x | {v['trades']} | {v['wins']} | {wr} | {v['net']:.2f} |")
        lines+=['','A later-period point estimate above65% does not undo an inadequate sample, failed stress result, full-year loss or previous inspection of these dates. No OR-specific parameter/latency robustness is inferred from the separate fixed range-center diagnostics below.']
    lines+=['','The table includes all eight fixed neighbors and the latency arm. Zero-return empty accounts are not positive evidence. Each independent stress account can take different trades after cash/risk gates, so trade counts need not match.',
        '', '## Frozen-center sensitivity','',
        'Center is range20/retest/plain/runner, chosen before any results. Breakout buffer remains0.10ATR; only the subsequent touch tolerance/wait varies. No best-outer-rule parameter selection.',
        '', '| Wait min | Touch ATR | Trades | Win % | Net Rs | Stress net Rs | PF |','|---:|---:|---:|---:|---:|---:|---:|']
    healthy=0
    for wait in (4,5,6):
        for tol in (.08,.1,.12):
            key=name((*CENTRE[:4],wait,tol))+'__d0__x1.0'
            if key not in full:continue
            s=full[key];t=full[key[:-len('__x1.0')]+'__x2.0'];pf='n/a' if s['profit_factor'] is None else f"{s['profit_factor']:.2f}"
            if (wait,tol)!=(5,.1):healthy+=int(s['trades']>=100 and (s['profit_factor'] or 0)>1 and s['net']>0 and t['net']>0)
            lines.append(f"| {wait} | {tol:.2f} | {s['trades']} | {100*s['win_rate']:.2f} | {s['net']:.2f} | {t['net']:.2f} | {pf} |")
    lines +=[f'\nHealthy neighbors: {healthy}/8 requiring>=100trades,PF>1 and positive normal/stress net. This is a stability diagnostic, not a new strategy selector.',
        '', '## Frozen-center latency and Monte Carlo','',
        '| Extra entry delay | Friction | Trades | Win % | Net Rs | Actual DD Rs | Shuffled DD95 Rs | Day-block DD95 Rs |',
        '|---:|---:|---:|---:|---:|---:|---:|---:|']
    for delay in (0,1):
        for stress in (1.,2.):
            key=name(CENTRE)+f'__d{delay}__x{stress}'
            if key not in full:continue
            s=full[key];m=s['monte_carlo'];lines.append(f"| {delay} | {stress:.0f}x | {s['trades']} | {100*s['win_rate']:.2f} | {s['net']:.2f} | {m['actual_drawdown']:.2f} | {m['shuffled_dd95']:.2f} | {m['day_block_dd95']:.2f} |")
    lines+=['','Every endpoint has1000fixed-rupee trade permutations and1000five-day-block resamples in results.json. Shuffling preserves total P&L. Blocks preserve within-day order and include zero-trade sessions; neither reruns signal/cash gates under a shuffled history. Close-mark and realized DD are not tick-level worst drawdown. Sparse profitable samples cannot establish a dependable win probability.',
        '', '## Walk-forward','',
        'Among the48original rules, select using only preceding sessions:>=100trades,>=65%wins,positive normal/stress net,PF>1 and registered risk gates. Rank by stressed net. No qualifier means no trade. Actual cash/peak/monthly state carries between the two later evaluation windows.',
        '', '| Training through | Evaluation start | Qualified | Selected |','|---|---|---:|---|']
    for d in r['walk_forward']['decisions']:lines.append(f"| {d['training_last_day']} | {d['evaluation_start']} | {d['qualified']} | {d['selected']} |")
    for stress,s in r['walk_forward']['accounts'].items():lines.append(f"\nWF {stress}x: {s['trades']}trades, netRs{s['net']:.2f}. Empty no-trade returns are not an edge.")
    lines+=['','## Interpretation and limits','',
        'Immediate and retest strategies can consume different time/side/cash capacity and take different later signals. These are full policy replays, not a guaranteed remove-only subset or matched-trade causal proof. A lower loss and higher historical win rate still fail if net remains negative, execution stress fails, sample is inadequate or the result depends on a fragile parameter.',
        'An opening-range or prior-level breakout is read on the NIFTY index. Retest waits at least one later completed minute. The raw level and ATR tolerance are frozen; pending events expire, invalidate on excessive adverse wick, and clear on missing-minute gaps. No same-bar hindsight confirmation or holding a changing ATM offset.',
        'Trend uses only fresh completed5minute EMA9/21 bars, including earlier complete sessions for warmup. Reward/cost context uses current reported IV/model delta and prior realized volatility; expected movement is not known future movement. This proxy does not identify actual buyers, order-book liquidity or stop orders.',
        'One-strike ITM weekly expiry1–7days away, one lot. Quick:15%stop,+10%target,20minute max. Runner:15%stop,no target or profit lock,20minute failure exit unless+5%,max60minutes.14:50square-off and account-loss gates may act earlier. Stops precede targets when both touch; gaps can overshoot. Costs use dated charges, inclusive exchange/IPFT and assumed spread/slippage.',
        'Limits:100000cash,2500initial risk,3000daily,13000monthly,40000drawdown. Limits can stop trading before data ends. Calendar-window accounts restart only as labeled. All114full p-values use day-block tests and jointBH; that does not undo the unknown prior research search. Win-rate Wilson intervals assume independent trades and are descriptive.',
        'Past history has already been repeatedly studied. New forward data and executable quotes are still needed for a trading verdict. No real orders, paid compute, production/workflow changes, main merge or new neural training. See PREREG.md, README.md, SOURCES.md, audit.json and every ledger for reproduction.']
    archive_ledgers(out)
    lines+=['','[Download every executed ledger, including WF accounts](ledgers.zip). Reproduction creates loose CSVs; the archive preserves them without duplicating all records in git.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n');return summary


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,default=Path(__file__).parent/'results');a=p.parse_args();write(a.out)
