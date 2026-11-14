"""One preregistered, offline NIFTY breakout strategy. Never sends orders."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd

from . import pipeline as P


def signals(index):
    """Compute within one session; all inputs exist at the signal bar's close."""
    out=pd.Series(0,index=index.index,dtype=int)
    high=index.high.rolling(20).max().shift(1)
    low=index.low.rolling(20).min().shift(1)
    bn_ema=index.BANKNIFTY.ewm(span=20,adjust=False).mean()
    bn_ret=index.BANKNIFTY.pct_change(15,fill_method=None)
    vix_ret=index.INDIAVIX.pct_change(15,fill_method=None)
    out[(index.close>high)&(index.BANKNIFTY>bn_ema)&(bn_ret>0)&(vix_ret<=.03)]=1
    out[(index.close<low)&(index.BANKNIFTY<bn_ema)&(bn_ret<0)&(vix_ret<=.03)]=2
    minute=index.index.hour*60+index.index.minute
    out[~(((minute+1)%5==0)&(minute+1>=615)&(minute+1<=825))]=0
    # Missing minutes cannot silently shrink the intended lookback.
    contiguous=pd.Series(index.index.asi8,index=index.index).diff().eq(60_000_000_000).rolling(20).sum().eq(20)
    out[~contiguous]=0
    return out


def entry_debit(premium,day,exp,stress):
    qty=75 if str(exp)<'2026-01-01' else 65
    fill=premium+stress*(1.5*(.162+.00292*premium)+.0005*premium)
    turnover=fill*qty
    ex=turnover*.0003553  # Exchange + IPFT, including the pre-March contribution.
    sebi=turnover*.000001
    fees=20+ex+sebi+.18*(20+ex+sebi)+turnover*.00003
    return turnover+fees


def trade_pnl(buy,sell,day,exp,stress=1.):
    """Inclusive exchange/IPFT total remains Rs3553/crore across March 2026."""
    if not np.isfinite([buy,sell,stress]).all() or min(buy,sell)<=0 or stress<1:
        raise ValueError('Invalid fill')
    qty=75 if str(exp)<'2026-01-01' else 65
    b=buy+stress*(1.5*(.162+.00292*buy)+.0005*buy)
    s=max(0.,sell-stress*(1.5*(.162+.00292*sell)+.0005*sell))
    bv,sv=b*qty,s*qty
    exchange=(bv+sv)*.0003553
    sebi=(bv+sv)*.000001
    stt=sv*(.001 if str(day)<'2026-04-01' else .0015)
    fees=40+exchange+sebi+.18*(40+exchange+sebi)+bv*.00003+stt
    gross=(sell-buy)*qty;friction=gross-(sv-bv)
    return (sv-bv)-fees,gross,fees,friction


def exit_fill(bar,entry,stop,elapsed):
    """Conservative OHLC ordering. Trail changes only after a completed bar."""
    op,hi,lo,close,volume=bar
    if volume<=0 or not np.isfinite([op,hi,lo,close]).all() or min(op,hi,lo,close)<=0:
        return None
    if op<=stop: return op,'gap_stop'
    if elapsed>=45: return op,'maximum_hold'
    if elapsed>=20 and op<entry*1.05: return op,'time_stop'
    if op>=entry*1.30: return op,'gap_target'
    if lo<=stop: return stop,'stop_or_profit_lock'
    if hi>=entry*1.30: return entry*1.30,'target'
    return None


def load(lake):
    data={};fingerprints={}
    for sym in ['NIFTY','BANKNIFTY','INDIAVIX']:
        paths=sorted(lake.glob(f'index_1m_{sym}_*.parquet'))
        if not paths: raise ValueError('Missing index '+sym)
        frames=[]
        for p in paths:
            fingerprints[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
            frames.append(pd.read_parquet(p))
        d=pd.concat(frames).sort_values('ts')
        if d.ts.duplicated().any(): raise ValueError('Duplicate index timestamps')
        if d.ts.dt.tz is None: raise ValueError('Naive timestamps')
        d.ts=d.ts.dt.tz_convert('Asia/Kolkata');data[sym]=d.set_index('ts')
    ix=data['NIFTY'][['open','high','low','close']].join(data['BANKNIFTY'].close.rename('BANKNIFTY'),how='inner').join(data['INDIAVIX'].close.rename('INDIAVIX'),how='inner').dropna()
    end=ix.index.max().normalize();start=end-pd.DateOffset(years=1)+pd.Timedelta(days=1)
    ix=ix.loc[start:end+pd.Timedelta(days=1)]
    opts=[]
    for p in sorted(lake.glob('opt_NIFTY_WEEK_*.parquet')):
        fingerprints[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
        d=pd.read_parquet(p);d.ts=d.ts.dt.tz_convert('Asia/Kolkata')
        opts.append(d[(d.ts>=start)&(d.ts<end+pd.Timedelta(days=1))])
    if not opts: raise ValueError('Missing option files')
    o=pd.concat(opts);o['side']=o.side.astype(str)
    if o.duplicated(['ts','strike','side']).any(): raise ValueError('Duplicate contract quotes')
    ex=pd.read_csv(P.HERE.parent/'brain/calendar_data/expiries.csv')
    observed=set(pd.to_datetime(ex.loc[ex.und=='NIFTY','date']).dt.date)
    option_days={day:g for day,g in o.groupby(o.ts.dt.date)}
    sessions=[]
    for day,d in ix.groupby(ix.index.date):
        if day not in option_days: raise ValueError('Missing option session '+str(day))
        g=option_days[day].copy();g['minute']=g.ts.dt.hour*60+g.ts.dt.minute
        book={(int(r.minute),float(r.strike),r.side):(r.open,r.high,r.low,r.close,r.volume) for r in g.itertuples()}
        picks={(int(r.minute),r.side):float(r.strike) for r in g[((g.side=='C')&(g.offset==-1))|((g.side=='P')&(g.offset==1))].itertuples()}
        exp,source=P.expiry(day,observed)
        sig=signals(d)
        candidates={int(t.hour*60+t.minute+1):('C' if a==1 else 'P',picks.get((t.hour*60+t.minute,'C' if a==1 else 'P')))
                    for t,a in sig.items() if a and 1<=(exp-day).days<=7}
        sessions.append(dict(day=str(day),expiry=str(exp),expiry_source=source,book=book,candidates=candidates))
    return sessions,dict(start=str(start.date()),end=str(end.date()),sessions=len(sessions),files=fingerprints)


def replay(sessions,stress=1.):
    cash=100000.;peak=100000.;mtm_dd=0.;monthly={};trades=[];rejected={};missing=0
    for session in sessions:
        day=session['day'];month=day[:7];exp=session['expiry'];book=session['book']
        day_pnl=0.;count=0;position=None;monthly.setdefault(month,0.)
        for minute in range(555,930):
            if position is not None:
                q=book.get((minute,position['strike'],position['side']))
                if q is None or q[4]<=0:
                    missing+=1;continue
                op,hi,lo,close,vol=q
                pnl_at_open=trade_pnl(position['entry'],op,day,exp,stress)[0]
                reason=None
                if peak-(cash+pnl_at_open)>=40000 or monthly[month]+pnl_at_open<=-13000 or day_pnl+pnl_at_open<=-3000:
                    fill=(op,'account_loss_gate')
                elif minute>=890:
                    fill=(op,'square_off')
                else:
                    fill=exit_fill(q,position['entry'],position['stop'],minute-position['entered'])
                if fill:
                    price,reason=fill
                    net,gross,fees,friction=trade_pnl(position['entry'],price,day,exp,stress)
                    cash+=net;day_pnl+=net;monthly[month]+=net
                    peak=max(peak,cash);mtm_dd=max(mtm_dd,peak-cash)
                    trades.append(dict(day=day,entry_minute=position['entered'],exit_minute=minute,
                        side=position['side'],strike=position['strike'],expiry=exp,entry=position['entry'],exit=price,
                        reason=reason,net=net,gross=gross,fees=fees,friction=friction,equity=cash))
                    position=None
                    continue  # No same-minute re-entry following an intrabar exit.
                liquidation=cash+trade_pnl(position['entry'],close,day,exp,stress)[0]
                peak=max(peak,liquidation);mtm_dd=max(mtm_dd,peak-liquidation)
                position['highest']=max(position['highest'],hi)
                if position['highest']>=position['entry']*1.15:
                    position['stop']=max(position['stop'],position['entry']+.6*(position['highest']-position['entry']))
                continue
            if minute not in session['candidates']: continue
            side,strike=session['candidates'][minute]
            if count>=2: key='daily_trade_cap'
            elif peak-cash>=40000: key='drawdown_halt'
            elif monthly[month]<=-13000: key='monthly_halt'
            elif day_pnl<=-3000: key='daily_halt'
            elif strike is None: key='missing_entry_strike'
            else:
                q=book.get((minute,strike,side))
                if q is None or q[4]<=0 or q[0]<=0: key='unfilled_entry'
                else:
                    entry=q[0]
                    risk=-trade_pnl(entry,entry*.85,day,exp,stress)[0]
                    budget=min(2500,3000+day_pnl,13000+monthly[month],40000-(peak-cash))
                    if entry_debit(entry,day,exp,stress)>cash: key='insufficient_cash'
                    elif risk>budget: key='risk_budget'
                    else:
                        position=dict(side=side,strike=strike,entry=entry,entered=minute,stop=entry*.85,highest=entry)
                        count+=1
                        # Entry-bar OHLC exits must also be observed; process this bar immediately.
                        fill=exit_fill(q,entry,position['stop'],0)
                        if fill:
                            price,reason=fill;net,gross,fees,friction=trade_pnl(entry,price,day,exp,stress)
                            cash+=net;day_pnl+=net;monthly[month]+=net;peak=max(peak,cash);mtm_dd=max(mtm_dd,peak-cash)
                            trades.append(dict(day=day,entry_minute=minute,exit_minute=minute,side=side,strike=strike,expiry=exp,
                                entry=entry,exit=price,reason=reason,net=net,gross=gross,fees=fees,friction=friction,equity=cash))
                            position=None
                        else:
                            liquidation=cash+trade_pnl(entry,q[3],day,exp,stress)[0]
                            peak=max(peak,liquidation);mtm_dd=max(mtm_dd,peak-liquidation)
                            position['highest']=max(entry,q[1])
                            if position['highest']>=entry*1.15:
                                position['stop']=max(position['stop'],entry+.6*(position['highest']-entry))
                        continue
            rejected[key]=rejected.get(key,0)+1
        if position is not None:
            raise ValueError('Unpriceable terminal fixed contract on '+day)
    columns=['day','entry_minute','exit_minute','side','strike','expiry','entry','exit','reason','net','gross','fees','friction','equity']
    ledger=pd.DataFrame(trades,columns=columns)
    days=[s['day'] for s in sessions]
    adapted=ledger.assign(stress_net=ledger.net)
    summary=P.summarize(adapted,days)
    summary.pop('stress_net')  # Each scenario is independently replayed below.
    summary.update(starting_capital=100000,ending_capital=cash,return_pct=(cash/100000-1)*100,
        wins=int((ledger.net>0).sum()),losses=int((ledger.net<0).sum()),gross_profit=float(ledger.gross.sum()),
        fees=float(ledger.fees.sum()),execution_friction=float(ledger.friction.sum()),
        close_mark_liquidation_drawdown=mtm_dd,rejections=rejected,missing_holding_minutes=missing,
        fresh_out_of_sample=False,stress_multiplier=stress,
        average_net_win=float(ledger.loc[ledger.net>0,'net'].mean()) if (ledger.net>0).any() else None,
        average_net_loss=float(ledger.loc[ledger.net<0,'net'].mean()) if (ledger.net<0).any() else None,
        last_trade_day=str(ledger.day.max()) if len(ledger) else None,
        exit_counts=ledger.reason.value_counts().to_dict())
    return ledger,summary


def run(lake,out):
    out.mkdir(parents=True,exist_ok=True);sessions,audit=load(lake)
    P.dump(out/'audit.json',audit)
    results={};monthly=[]
    for name,stress in [('base',1.),('double_friction',2.)]:
        ledger,summary=replay(sessions,stress);results[name]=summary
        ledger.to_csv(out/f'{name}_trades.csv',index=False)
        month=ledger.groupby(ledger.day.str[:7]).net.agg(['count','sum']).reindex(sorted({s['day'][:7] for s in sessions}),fill_value=0)
        for m,row in month.iterrows(): monthly.append(dict(scenario=name,month=m,trades=int(row['count']),net=float(row['sum'])))
    P.dump(out/'results.json',results);P.dump(out/'monthly.json',monthly)
    base=results['base'];stress=results['double_friction']
    lines=['# NIFTY confirmed breakout backtest','',
        f"Period: {audit['start']} through {audit['end']}, {audit['sessions']} available sessions. Previously reviewed historical data; not a fresh test.",
        '', '| Scenario | Trades | Net win rate | Gross Rs | Fees Rs | Spread/slippage Rs | Net Rs | Ending capital Rs | Realized DD Rs | Close-mark DD Rs |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for name,s in results.items():
        lines.append(f"| {name} | {s['trades']} | {s['win_rate']*100:.2f}% | {s['gross_profit']:.2f} | {s['fees']:.2f} | {s['execution_friction']:.2f} | {s['net']:.2f} | {s['ending_capital']:.2f} | {s['max_drawdown']:.2f} | {s['close_mark_liquidation_drawdown']:.2f} |")
    lines+=['','## Rules','', 'NIFTY breaks the preceding 20-minute high/low, BANKNIFTY confirms its direction, India VIX has not risen over 3% in 15 minutes. Entry on five-minute boundaries from 10:15–13:45. Buy one lot one-strike-ITM CE/PE, nearest scheduled weekly expiry 1–7 days away; skip expiry day.',
        '', 'Stop -15%, target +30%, protect 60% of the prior-bar peak gain after +15%. At 20 minutes exit unless at least +5%; maximum hold 45 minutes, square-off by 14:50. Maximum two trades/day and one position; cash and risk-budget checks apply.',
        '', 'Rs100000 initial capital; initial estimated risk at most Rs2500/trade, Rs3000 daily loss budget, Rs13000 monthly budget and Rs40000 drawdown budget. Gaps can overshoot limits. All entries, fills and exclusions are in the ledgers and results.json.',
        '', 'Neural models were deliberately excluded: the prior study did not establish an edge, and the final all-data weights cannot create an unseen historical test. The rule is informed by prior breakout/cost/risk findings, not by pretending those weights predicted their training data.',
        '', '## Monthly net P&L','', '| Scenario | Month | Trades | Net Rs |','|---|---|---:|---:|']
    for row in monthly: lines.append(f"| {row['scenario']} | {row['month']} | {row['trades']} | {row['net']:.2f} |")
    lines+=['','## Assessment','',
        'Retrospective result only. No paper/live approval: the rules were designed after earlier studies inspected this history. One candidate was registered before this run, without parameter searching. Prior research reuse remains selection bias.',
        '', 'OHLC fills assume stop/target availability with modeled friction. A minute touching both uses stop first; trail updates begin next bar. Spread law is an assumption, not a recorded bid/ask. Missing/nontraded holding minutes delay executable fills and are counted. A terminal unpriceable position aborts the run. Close-mark drawdown uses simulated liquidation at bar close; it is not tick-level worst drawdown.',
        '', 'Fees: brokerage Rs20/order, dated STT, exchange/IPFT, SEBI, GST and buy stamp duty. Cost rates use continuous approximations without contract-note rounding. Cost sources: https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax and https://nsearchives.nseindia.com/content/circulars/FA73061.pdf . Expiry is reconstructed from repository-observed dates or published schedules, not explicit expiry fields in the lake.',
        '', 'The stress scenario independently replays cash, risk gates and entries with doubled spread/slippage; it can take different trades. No real orders or production changes.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n')
    return results


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--lake',type=Path,required=True);ap.add_argument('--out',type=Path,default=P.HERE/'strategy_results')
    a=ap.parse_args();print(json.dumps(run(a.lake,a.out),indent=2))
