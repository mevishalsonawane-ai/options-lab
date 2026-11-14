"""Research copy of shared account replay with explicit stop/target knobs.
Default parity is unit-tested; production strategy.py is unchanged.
"""
import numpy as np
import pandas as pd
from research.neural_v2 import pipeline as P
from research.neural_v2.strategy import entry_debit,trade_pnl

def exit_fill(bar,entry,stop,elapsed,target_pct=.30):
    """Conservative OHLC ordering. Trail changes only after a completed bar."""
    op,hi,lo,close,volume=bar
    if volume<=0 or not np.isfinite([op,hi,lo,close]).all() or min(op,hi,lo,close)<=0:
        return None
    if op<=stop: return op,'gap_stop'
    if elapsed>=45: return op,'maximum_hold'
    if elapsed>=20 and op<entry*1.05: return op,'time_stop'
    if op>=entry*(1+target_pct): return op,'gap_target'
    if lo<=stop: return stop,'stop_or_profit_lock'
    if hi>=entry*(1+target_pct): return entry*(1+target_pct),'target'
    return None


def replay(sessions,stress=1.,stop_pct=.15,target_pct=.30,state=None):
    if not np.isfinite([stop_pct,target_pct]).all() or not 0<stop_pct<1 or not 0<target_pct<2:
        raise ValueError("Invalid stop/target")
    state=state or {}
    cash=float(state.get('cash',100000.));peak=float(state.get('peak',cash));mtm_dd=float(state.get('mtm_dd',0.))
    monthly=dict(state.get('monthly',{}));initial_cash=cash
    if not np.isfinite([cash,peak,mtm_dd]).all() or cash<=0 or peak<cash:raise ValueError('Invalid carried account')
    trades=[];rejected={};missing=0
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
                    fill=exit_fill(q,position['entry'],position['stop'],minute-position['entered'],target_pct)
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
                    risk=-trade_pnl(entry,entry*(1-stop_pct),day,exp,stress)[0]
                    budget=min(2500,3000+day_pnl,13000+monthly[month],40000-(peak-cash))
                    if entry_debit(entry,day,exp,stress)>cash: key='insufficient_cash'
                    elif risk>budget: key='risk_budget'
                    else:
                        position=dict(side=side,strike=strike,entry=entry,entered=minute,stop=entry*(1-stop_pct),highest=entry)
                        count+=1
                        # Entry-bar OHLC exits must also be observed; process this bar immediately.
                        fill=exit_fill(q,entry,position['stop'],0,target_pct)
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
    summary.update(starting_capital=initial_cash,ending_capital=cash,return_pct=(cash/initial_cash-1)*100,
        wins=int((ledger.net>0).sum()),losses=int((ledger.net<0).sum()),gross_profit=float(ledger.gross.sum()),
        fees=float(ledger.fees.sum()),execution_friction=float(ledger.friction.sum()),
        close_mark_liquidation_drawdown=mtm_dd,rejections=rejected,missing_holding_minutes=missing,
        fresh_out_of_sample=False,stress_multiplier=stress,
        average_net_win=float(ledger.loc[ledger.net>0,'net'].mean()) if (ledger.net>0).any() else None,
        average_net_loss=float(ledger.loc[ledger.net<0,'net'].mean()) if (ledger.net<0).any() else None,
        last_trade_day=str(ledger.day.max()) if len(ledger) else None,
        exit_counts=ledger.reason.value_counts().to_dict())
    summary['_state']=dict(cash=cash,peak=peak,mtm_dd=mtm_dd,monthly=monthly)
    return ledger,summary
