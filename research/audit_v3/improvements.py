"""Controlled exit ablations and remove-only OOF direction gating."""
import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from research.neural_v2 import strategy as S
from research.neural_v2 import pipeline as P
from .direction import load_forecasts


def alternative_exit(bar,entry,stop,elapsed,mode):
    op,hi,lo,close,volume=bar
    if volume<=0 or not np.isfinite([op,hi,lo,close]).all() or min(op,hi,lo,close)<=0:return None
    if mode not in {'no_target','runner'}:raise ValueError('Unknown ablation')
    if mode=='runner':stop=entry*.85
    if op<=stop:return op,'gap_stop'
    if elapsed>=(60 if mode=='runner' else 45):return op,'maximum_hold'
    if elapsed>=20 and op<entry*1.05:return op,'time_stop'
    if lo<=stop:return stop,'initial_stop' if mode=='runner' else 'stop_or_profit_lock'
    return None


def direction_at(forecasts,stamp,instrument):
    """No future forecast, other-instrument forecast, or overnight carryover."""
    stamp=pd.Timestamp(stamp)
    if stamp.tzinfo is None:raise ValueError('Forecast matching requires timezone-aware decision times')
    f=forecasts[forecasts.instrument==instrument].copy()
    if f.empty:return None
    f['time']=pd.to_datetime(f.available_at,utc=True)
    f=f[(f.time<=stamp)&(f.time>=stamp-pd.Timedelta(minutes=5))]
    if f.empty:return None
    row=f.sort_values('time').iloc[-1]
    if pd.Timestamp(row.training_label_cutoff)>=row.time:raise ValueError('Forecast trained on its own future')
    if row.time.tz_convert('Asia/Kolkata').date()!=stamp.tz_convert('Asia/Kolkata').date():return None
    p=float(row.p_up_15)
    if not np.isfinite(p) or not 0<=p<=1:raise ValueError('Invalid probability')
    return p


def filter_ledger(ledger,forecasts,stress=1.):
    """Remove only. Original exits/time reservations remain; never promote a new entry."""
    kept=[];reasons={};cash=100000.;peak=cash;months={};days={}
    for row in ledger.itertuples(index=False):
        stamp=pd.Timestamp(row.day,tz='Asia/Kolkata')+pd.Timedelta(minutes=row.entry_minute)
        p=direction_at(forecasts,stamp,'NIFTY')
        if p is None:reason='missing_forecast'
        elif not ((row.side=='C' and p>=.55) or (row.side=='P' and p<=.45)):reason='not_confident_or_disagrees'
        else:
            month=row.day[:7];months.setdefault(month,0.);days.setdefault(row.day,0.)
            risk=-S.trade_pnl(row.entry,row.entry*.85,row.day,row.expiry,stress)[0]
            budget=min(2500,3000+days[row.day],13000+months[month],40000-(peak-cash))
            if S.entry_debit(row.entry,row.day,row.expiry,stress)>cash or risk>budget:reason='cash_or_risk'
            else:
                kept.append(row._asdict());cash+=row.net;peak=max(peak,cash);months[month]+=row.net;days[row.day]+=row.net
                continue
        reasons[reason]=reasons.get(reason,0)+1
    return pd.DataFrame(kept,columns=ledger.columns),reasons


def subset_summary(ledger,days):
    s=P.summarize(ledger.assign(stress_net=ledger.net),days);s.pop('stress_net')
    s.update(wins=int((ledger.net>0).sum()),ending_capital=float(100000+ledger.net.sum()),
        average_win=float(ledger.loc[ledger.net>0,'net'].mean()) if (ledger.net>0).any() else None,
        average_loss=float(ledger.loc[ledger.net<0,'net'].mean()) if (ledger.net<0).any() else None)
    return s


def run(lake,forecast_path,out):
    out.mkdir(parents=True,exist_ok=True);sessions,audit=S.load(lake)
    f=load_forecasts(forecast_path) if forecast_path.suffix=='.npz' else pd.read_csv(forecast_path)
    f=f[f.model=='gru']
    if f.empty:raise ValueError('Missing preregistered GRU OOF forecasts')
    start=f.day.min();common=[s for s in sessions if s['day']>=start]
    results={};original=S.exit_fill
    try:
        for mode in ['baseline','no_target','runner']:
            S.exit_fill=original if mode=='baseline' else lambda bar,e,stop,elapsed,m=mode:alternative_exit(bar,e,stop,elapsed,m)
            for name,stress in [('base',1.),('double_friction',2.)]:
                ledger,summary=S.replay(sessions,stress)
                key=mode+'_'+name;results[key]=summary;ledger.to_csv(out/(key+'.csv'),index=False)
    finally:S.exit_fill=original
    common_days=[s['day'] for s in common]
    for name,stress in [('base',1.),('double_friction',2.)]:
        ledger,summary=S.replay(common,stress);results['common_baseline_'+name]=summary
        ledger.to_csv(out/('common_baseline_'+name+'.csv'),index=False)
        kept,reasons=filter_ledger(ledger,f,stress);s=subset_summary(kept,common_days);s['exclusions']=reasons
        results['remove_only_direction_'+name]=s;kept.to_csv(out/('remove_only_direction_'+name+'.csv'),index=False)
    P.dump(out/'results.json',dict(period=audit,forecast_common_start=start,results=results,
        fresh_test=False,all_strategy_win_rate_improvement_proven=False))
    lines=['# Controlled improvements: results','','One predefined exit family and one fixed, remove-only 55% GRU direction gate. No parameter search or production changes.',
        '', '| Replay | Trades | Win rate | Net Rs | Drawdown Rs | Profit factor |', '|---|---:|---:|---:|---:|---:|']
    for key,s in results.items():
        pf='n/a' if s['profit_factor'] is None else f"{s['profit_factor']:.2f}"
        wr='n/a' if s['trades']==0 else f"{100*s['win_rate']:.2f}%"
        lines.append(f"| {key} | {s['trades']} | {wr} | {s['net']:.2f} | {s['max_drawdown']:.2f} | {pf} |")
    lines+=['',f'Full period: {audit["start"]} through {audit["end"]}. Common direction-gate period starts {start}; baseline and filtered account each start once with Rs100000 there.',
        '', 'no_target removes only the +30% target, keeping the existing profit lock and 45-minute limit. runner removes target and profit lock, retains initial -15% stop and 20-minute failure exit, and extends maximum hold to60 minutes.',
        '', 'Full exit scenarios replay their own cash, risk gates and entries. Direction filtering removes only already-executed common-baseline trades and reserves their old time/slots. It does not free capacity for promoted entries. It rechecks realized cash/risk admission; it does not reconstruct a changed intrabar account-equity path, so it is a diagnostic subset replay, not full app-engine parity.',
        '', 'Forecasts are out-of-fold within previously reviewed history. No all-data model is used. A 55% direction probability is not a 55% option-trade win probability. No proof of a shared improvement for all strategies or fresh trading edge.',
        '', 'Use the separate strategy matrix for every knowledge-card family and current missing families. R51/R72, ORB, Liquidity, CISD, overnight, MCX and crypto require their own original-engine parity and inputs before promotion. Higher win rate alone cannot pass.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n')
    return results


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--lake',type=Path,required=True)
    ap.add_argument('--forecasts',type=Path,default=Path(__file__).parent/'direction_results/forecasts.npz')
    ap.add_argument('--out',type=Path,default=Path(__file__).parent/'improvement_results');a=ap.parse_args();run(a.lake,a.forecasts,a.out)
