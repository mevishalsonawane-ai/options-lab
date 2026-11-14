"""Test observed minute-candle ablations; never fabricate ten-second candles."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from research.neural_v2 import strategy as S
from research.neural_v2 import pipeline as P

ARMS=('one_minute','one_plus_five')


def signals(frame,arm):
    if arm not in ARMS:raise ValueError('Unsupported arm; genuine 10-second data required for all three')
    if not isinstance(frame.index,pd.DatetimeIndex) or frame.index.tz is None:
        raise ValueError('Timezone-aware candle timestamps required')
    if frame.index.has_duplicates or not frame.index.is_monotonic_increasing:
        raise ValueError('Duplicate or unordered candles')
    out=pd.Series(0,index=frame.index,dtype=int)
    if frame.empty:return out
    if len(set(frame.index.date))!=1:raise ValueError('Pass exactly one session')
    if frame.index.second.any() or frame.index.microsecond.any():raise ValueError('Minute bars required')
    local=frame.index.tz_convert('Asia/Kolkata')
    if ((local.hour*60+local.minute<555)|(local.hour*60+local.minute>=930)).any():
        raise ValueError('Outside regular underlying session')
    d=frame[['open','high','low','close']].copy();d.index=local
    finite=np.isfinite(d).all(axis=1)&d.gt(0).all(axis=1)
    valid=finite&(d.high>=d[['open','close','low']].max(axis=1))&(d.low<=d[['open','close','high']].min(axis=1))
    direction=np.sign(d.close-d.open).where(valid,0)
    # A missing prior minute cannot turn a discontinuous feed into a confirmation.
    continuous=pd.Series(local.asi8,index=local).diff().eq(60_000_000_000)
    decision=local+pd.Timedelta(minutes=1)
    if arm=='one_plus_five':
        anchor=local[0].normalize()+pd.Timedelta(hours=9,minutes=15)
        rule=dict(rule='5min',origin=anchor,closed='left',label='right')
        bars=d.where(valid).resample(**rule).agg({'open':'first','close':'last'})
        count=valid.astype(int).resample(**rule).sum()
        higher=np.sign(bars.close-bars.open).where(count==5,0)
        # Labels are candle CLOSE times: 09:15..09:19 appears at 09:20 only.
        aligned=higher.reindex(decision,method='ffill').fillna(0).to_numpy()
        direction=direction.where(direction.to_numpy()==aligned,0)
    minute=decision.hour*60+decision.minute
    side=np.where(direction>0,1,np.where(direction<0,2,0))
    out.iloc[:]=np.where(continuous&(minute>=560)&(minute<=825),side,0)
    return out


def load_arm(lake,arm):
    # Existing audited contract loader and independent account replay are reused.
    # Only the process-local research signal callback changes and is restored.
    original=S.signals
    try:
        S.signals=lambda frame:signals(frame,arm)
        return S.load(lake)
    finally:S.signals=original


def reconcile(ledger,summary):
    if not np.isclose(ledger.net.sum(),summary['net'],rtol=0,atol=1e-6):raise ValueError('Ledger net mismatch')
    if not np.isclose(summary['net'],summary['gross_profit']-summary['fees']-summary['execution_friction'],rtol=0,atol=1e-6):
        raise ValueError('Cost reconciliation failed')
    if not np.isclose(summary['ending_capital'],100000+summary['net'],rtol=0,atol=1e-6):raise ValueError('Cash mismatch')
    if summary['wins']!=int((ledger.net>0).sum()):raise ValueError('Net win mismatch')


def adjust_primary(results):
    """Conservative BH across two signals times two friction replays."""
    for r,q in zip(results.values(),P.bh([r['p_value'] for r in results.values()])):
        r['bh_q_four_primary_replays']=float(q)


def run(lake,out):
    out.mkdir(parents=True,exist_ok=True)
    results={};audits={};monthly=[];window_results={}
    windows=(('first','2025-01-01','2026-03-31'),('middle','2026-04-01','2026-06-30'),('last','2026-07-01','2027-01-01'))
    for arm in ARMS:
        sessions,audit=load_arm(lake,arm);audits[arm]=audit
        audit['signal_candidates']=sum(len(s['candidates']) for s in sessions)
        for label,stress in [('base',1.),('double_friction',2.)]:
            key=arm+'_'+label;ledger,summary=S.replay(sessions,stress);reconcile(ledger,summary)
            results[key]=summary;ledger.to_csv(out/(key+'.csv'),index=False)
            for month in sorted({s['day'][:7] for s in sessions}):
                g=ledger[ledger.day.str[:7]==month]
                monthly.append(dict(arm=key,month=month,trades=len(g),wins=int((g.net>0).sum()),net=float(g.net.sum())))
            for name,start,end in windows:
                subset=[s for s in sessions if start<=s['day']<=end]
                if not subset:continue
                wledger,ws=S.replay(subset,stress);reconcile(wledger,ws)
                window_results[key+'_'+name]=ws
        print(arm+' completed',flush=True)
    adjust_primary(results)
    payload=dict(audit=audits,results=results,window_restarts=window_results,monthly=monthly,
        exact_three_timeframe=dict(status='blocked',win_rate=None,net=None,
            reason='No readable genuine sub-minute underlying/option history in current environment. Minute OHLC cannot reconstruct ten-second bars.'),
        fresh_out_of_sample=False,approved_for_trading=False)
    P.dump(out/'results.json',payload)
    lines=['# Completed-candle timeframe alignment test','',
        'Signal source: NIFTY underlying. Bullish=close>open, bearish=close<open; doji/missing=no signal. Real fixed-strike option contracts with modeled fills/costs.',
        f"Period: {audit['start']} through {audit['end']}; {audit['sessions']} available sessions. Initial capital Rs100000 per independently replayed arm.",
        '', '**Exact 5-minute + 1-minute + 10-second strategy: BLOCKED. No win rate or profit result. The following are partial ablations only.**',
        '', '| Arm | Trades | Net wins | Net win rate | Gross Rs | Fees Rs | Friction Rs | Net Rs | Ending capital Rs | Realized DD Rs | Close-mark DD Rs | PF |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for k,r in results.items():
        wr='n/a' if not r['trades'] else f"{100*r['win_rate']:.2f}%"
        pf='n/a' if r['profit_factor'] is None else f"{r['profit_factor']:.3f}"
        lines.append(f"| {k} | {r['trades']} | {r['wins']} | {wr} | {r['gross_profit']:.2f} | {r['fees']:.2f} | {r['execution_friction']:.2f} | {r['net']:.2f} | {r['ending_capital']:.2f} | {r['max_drawdown']:.2f} | {r['close_mark_liquidation_drawdown']:.2f} | {pf} |")
    lines+=['','## Chronological diagnostic restarts','',
        'Each window starts afresh with Rs100000; these are not a concatenated portfolio or unseen holdout. They show whether the full-account halt conceals later outcomes.',
        '', '| Arm/window | Trades | Win rate | Net Rs | Realized DD Rs |','|---|---:|---:|---:|---:|']
    for k,r in window_results.items():
        wr='n/a' if not r['trades'] else f"{100*r['win_rate']:.2f}%"
        lines.append(f"| {k} | {r['trades']} | {wr} | {r['net']:.2f} | {r['max_drawdown']:.2f} |")
    lines+=['','## Rules and limits','',
        'Five-minute bars anchor at09:15 IST and require all five valid observed minutes. At09:20 the latest completed five-minute candle is09:15–09:19; the latest completed minute is09:19. No in-progress higher candle or next-minute close is used. Entry uses the option open at the decision timestamp (a modeled fill, not a latency-aware executable quote).',
        'Both ablations allow decisions09:20–13:45, maximum two trades/day and one position. First aligned eligible signal while flat can trade; later alignments after an exit may re-enter. One lot, one-strike ITM, scheduled weekly expiry1–7 days away; skip expiry day. Existing fixed-contract loader resolves rolling offsets at entry only.',
        'Same exits: initial -15% premium stop,+30% target,60% profit lock after+15% from completed prior bars;20-minute failure exit unless+5%, maximum45min, square-off14:50. Stop-first if both stop and target touched. Same Rs2500 initial risk, Rs3000 daily, Rs13000 monthly and Rs40000 drawdown budgets; gaps can overshoot. The account can stop trading before data ends.',
        'Brokerage, dated STT, GST, SEBI, exchange/IPFT, buy stamp and assumed spread/slippage are included. Double-friction scenarios fully replay risk/cash and can take different trades. Missing held quotes delay fills and are counted. Quote spreads, tick-level stop ordering and latency were not observed.',
        'All dates were previously inspected in repository studies. BH adjusts the four primary signal/friction replay p-values in results.json; it does not correct the whole previously reused research program or make diagnostic restarts fresh. There is no pristine final holdout, no neural model, no parameter search, no inferential claim of a new edge, no live/paper promotion and no actual orders. Win rate refers to net-positive option trades, not candle prediction accuracy.',
        'The unavailable ten-second arm must not inherit these partial results. Sealed phone recordings require local decryption before their feed, coverage and contract quotes can be evaluated. No private recordings or catalogue metadata are published here.',
        '', 'Monthly trade counts and net values, rejection reasons and last trade dates are in results.json; four complete executed ledgers are alongside this report. See PREREG.md for the registered protocol.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n')
    return payload


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--lake',type=Path,required=True)
    p.add_argument('--out',type=Path,default=Path(__file__).parent/'results');a=p.parse_args();run(a.lake,a.out)
