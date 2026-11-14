"""Completed multi-bar confirmation extension, registered separately."""
import argparse
import json
import zipfile
import io
from pathlib import Path
import numpy as np
import pandas as pd
from research.neural_v2 import strategy as S,pipeline as P
from research.audit_v3.direction import load_forecasts
from research.confluence65 import robustness as R
from . import execution as E,study as T


def confirmations(index,minutes,n):
    if minutes not in (1,5) or n not in (2,3):raise ValueError('Unsupported candle confirmation')
    pieces=[]
    for day,d in index.groupby(index.index.date):
        d=d.sort_index()
        if d.index.has_duplicates:raise ValueError('Duplicate bar')
        if minutes==1:
            bars=d[['open','high','low','close']].copy();bars.index=bars.index+pd.Timedelta(minutes=1)
            complete=pd.Series(True,index=bars.index)
        else:
            resampled=d.resample('5min',closed='left',label='right',origin='start_day')
            bars=resampled.agg(dict(open='first',high='max',low='min',close='last'))
            # Unique timestamps plus count5 alone is insufficient if source wasn't minute aligned.
            count=resampled.close.count();complete=count.eq(5)
        finite=pd.Series(np.isfinite(bars.to_numpy(float)).all(axis=1),index=bars.index)
        complete &= finite & bars[['open','high','low','close']].gt(0).all(axis=1)
        cont=pd.Series(bars.index.asi8,index=bars.index).diff().eq(minutes*60_000_000_000)
        eligible=complete.rolling(n).sum().eq(n)&cont.rolling(n-1).sum().eq(n-1)
        up=bars.close.gt(bars.open).rolling(n).sum().eq(n)&bars.close.diff().gt(0).rolling(n-1).sum().eq(n-1)
        down=bars.close.lt(bars.open).rolling(n).sum().eq(n)&bars.close.diff().lt(0).rolling(n-1).sum().eq(n-1)
        pieces.append(pd.Series(np.where(eligible&up,1,np.where(eligible&down,2,0)),index=bars.index))
    return pd.concat(pieces)


def filter_decisions(decisions,mask):
    return {(day,minute):(side,strike) for (day,minute),(side,strike) in decisions.items()
        if mask.get(pd.Timestamp(day,tz='Asia/Kolkata')+pd.Timedelta(minutes=minute),0)==(1 if side=='C' else 2)}


def report(out,result):
    lines=['# Two/three-bar whole-lot confirmation extension','',
        'User-requested extension, registered after neural results. All history reused; no independent final holdout.',
        'Bullish: every close above open and successive closes rising. Bearish: inverse. Only completed1minute or5minute candles;2or3consecutive candles, no missing components/session carry.',
        'One WHOLE lot per trade; premium is quoted per unit. Cash debit and profit multiply the per-unit premium by the date-appropriate contract quantity. Same Rs2000normal-net target,Rs1500planned stop,30minute horizon and account controls.',
        'Neural directions are chosen before confirmation; confirmation only removes candidates. These use the preregistered ungated diagnostic directions because original confidence-gated neural models found no eligible trades. Standalone bar policies use no model.', '',
        '| Policy | Trades | Win % | Rs2000 reaches | Average winner Rs | Average ALL trades Rs | Net Rs | Stress net Rs | Joint BH q |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for key,s in result['accounts'].items():
        if not key.endswith('_x1.0'):continue
        stress=result['accounts'][key.replace('_x1.0','_x2.0')]
        win=f'{s["win_rate"]:.2%}' if s['trades'] else 'undefined'
        avg=f'{s["average_net_win"]:.2f}' if s['average_net_win'] is not None else 'undefined'
        lines.append(f'| {key[:-5]} | {s["trades"]} | {win} | {s["target_2000_reaches"]} | {avg} | {s["expectancy"]:.2f} | {s["net"]:.2f} | {stress["net"]:.2f} | {s["joint_bh_q"]:.3f} |')
    lines += ['',f'Historically qualifying policies: {result["qualifying_historical"]}.',
        'No strategy approved for trading. Joint BH includes all94original+extension endpoints; five-day block tests do not erase prior research reuse.1000trade shuffles and1000day-block resamples per endpoint do not replay reordered account controls.',
        'All normal/doubled accounts replay independently; changing confirmation can change later positions and risk admission, so ledgers need not be subsets. Candidate filtering itself is remove-only for neural policies.',
        'Original linear ungated ledger parity verified. Each held position retains its entry strike and expiry. One complete lot is recorded explicitly in every ledger.',
        'See ../BAR_CONFIRMATION_PREREG.md and ../results/REPORT.md for original model evidence. Results are saved in results.json and32full trade ledgers in ledgers.zip.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n')


def run(lake,base,out):
    out.mkdir(parents=True,exist_ok=True);P.status(out,stage='loading',errors=[])
    f=load_forecasts(base/'forecasts.npz');original=json.loads((base/'results.json').read_text())
    sessions,audit=S.load(lake);sessions=[s for s in sessions if s['day']>=f.day.min()]
    index=pd.concat([pd.read_parquet(p) for p in sorted(lake.glob('index_1m_NIFTY_*.parquet'))]).sort_values('ts')
    index.ts=index.ts.dt.tz_convert('Asia/Kolkata');index=index.set_index('ts')[['open','high','low','close']]
    if index.index.has_duplicates or (index.index.second!=0).any() or (index.index.microsecond!=0).any():
        raise ValueError('Invalid minute bars')
    choices={kind:T.decisions(f[f.model==kind],0,0,ungated=True) for kind in ('linear','dense','hybrid')}
    reproduced,_,_=E.replay(sessions,choices['linear'])
    with zipfile.ZipFile(base/'ledgers.zip') as z:
        saved=pd.read_csv(z.open('linear_ungated_x1.0.csv'))
    pd.testing.assert_frame_equal(reproduced,saved,check_dtype=False)
    accounts={};payloads={};done=0
    for minutes in (1,5):
        for n in (2,3):
            mask=confirmations(index,minutes,n)
            standalone={}
            for row in f[f.model=='linear'].itertuples():
                if mask.get(pd.Timestamp(row.available_at),0)==(1 if row.side=='C' else 2) and np.isfinite(row.strike):
                    standalone[row.day,int(row.minute)]=(row.side,float(row.strike))
            policies={'bars_only':standalone,**{k:filter_decisions(v,mask) for k,v in choices.items()}}
            for kind,decisions in policies.items():
                name=f'{kind}_{minutes}m_{n}bars'
                for stress in (1.,2.):
                    ledger,s,_=E.replay(sessions,decisions,stress);key=name+f'_x{stress}'
                    days=[v['day'] for v in sessions]
                    s['wilson95']=R.wilson(s['wins'],s['trades']);s['block_p_value']=R.block_pvalue(ledger,days)
                    s['monte_carlo']=R.monte_carlo(ledger,days);accounts[key]=s;payloads[key+'.csv']=ledger.to_csv(index=False).encode()
                done+=1;P.status(out,stage='replaying',completed=done,total=16);print('bars replayed',done,'/16',name,flush=True)
    for s,q in zip(accounts.values(),P.bh([s['block_p_value'] for s in accounts.values()])):s['batch_bh_q']=float(q)
    combined=list(original['accounts'].values())+list(accounts.values())
    joint=P.bh([s['block_p_value'] for s in combined]);original_joint={}
    for key,q in zip(original['accounts'],joint[:62]):original_joint[key]=float(q)
    for s,q in zip(accounts.values(),joint[62:]):s['joint_bh_q']=float(q)
    qualified=[key[:-5] for key,s in accounts.items() if key.endswith('_x1.0') and R.qualifies(s,accounts[key.replace('_x1.0','_x2.0')])]
    result=dict(audit=audit,accounts=accounts,original_joint_bh=original_joint,qualifying_historical=qualified,
                original_ledger_parity=True,fresh_holdout=False,approved_for_trading=False)
    with zipfile.ZipFile(out/'ledgers.zip','w',zipfile.ZIP_DEFLATED) as z:
        for name,data in sorted(payloads.items()):
            info=zipfile.ZipInfo(name,(2026,11,15,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
    with zipfile.ZipFile(out/'ledgers.zip') as z:
        assert len(z.namelist())==32
        for name,data in payloads.items():assert z.read(name)==data
    P.dump(out/'results.json',result);report(out,result);P.status(out,stage='completed',approved_for_trading=False)
    print('BARS COMPLETE; qualified:',qualified,flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--lake',type=Path,required=True)
    p.add_argument('--base',type=Path,default=Path(__file__).parent/'results')
    p.add_argument('--out',type=Path,default=Path(__file__).parent/'bar_results')
    a=p.parse_args();run(a.lake,a.base,a.out)
