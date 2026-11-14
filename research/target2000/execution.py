"""Fixed-contract, whole-lot rupee barriers and cash/risk account replay."""
import numpy as np
import pandas as pd
from research.neural_v2 import strategy as S, pipeline as P


def quantity(expiry):
    return 75 if str(expiry) < '2026-01-01' else 65


def barriers(entry, day, expiry):
    # Cost is affine in positive sell fill. The low-price floor is handled below.
    base = S.trade_pnl(entry, entry, day, expiry)[0]
    slope = S.trade_pnl(entry, entry+1, day, expiry)[0]-base
    target = np.ceil((entry+(2000-base)/slope)/.05-1e-10)*.05
    stop = max(.05, np.floor((entry+(-1500-base)/slope)/.05+1e-10)*.05)
    if not .0 < stop < entry < target:
        raise ValueError('Invalid rupee barriers')
    return float(stop), float(target)


def valid(bar):
    return bar is not None and np.isfinite(bar).all() and bar[4]>0 and min(bar[:4])>0


def exit_fill(bar, stop, target, elapsed):
    if not valid(bar):return None
    op, hi, lo, close, volume = bar
    if op<=stop:return op,'gap_stop'
    if elapsed>=30:return op,'time_exit'
    if op>=target:return op,'gap_target'
    if lo<=stop:return stop,'initial_stop'
    if hi>=target:return target,'target'
    return None


def outcome(book, minute, strike, side, day, expiry):
    q=book.get((minute,strike,side))
    if not valid(q) or q[0]<=.05:return None
    if S.trade_pnl(q[0],q[0],day,expiry)[0]<=-1500:return None
    entry=q[0];stop,target=barriers(entry,day,expiry)
    for m in range(minute,891):
        q=book.get((m,strike,side))
        if not valid(q):continue
        fill=(q[0],'square_off') if m>=890 else exit_fill(q,stop,target,m-minute)
        if fill:
            net,gross,fees,friction=S.trade_pnl(entry,fill[0],day,expiry)
            return dict(net=net,gross=gross,fees=fees,friction=friction,exit_minute=m,
                        exit=fill[0],entry=entry,reason=fill[1])
    return None


def replay(sessions, decisions, stress=1., state=None):
    state=state or {};cash=float(state.get('cash',100000.));peak=float(state.get('peak',cash))
    mtm_dd=float(state.get('mtm_dd',0.));monthly=dict(state.get('monthly',{}));initial=cash
    if not np.isfinite([cash,peak,mtm_dd]).all() or cash<=0 or peak<cash:raise ValueError('Invalid state')
    rows=[];rejected={};missing=0
    for session in sessions:
        day,exp,book=session['day'],session['expiry'],session['book'];month=day[:7]
        monthly.setdefault(month,0.);day_pnl=0.;count=0;position=None
        for minute in range(555,930):
            if position is None:
                candidate=decisions.get((day,minute))
                if candidate is None:continue
                side,strike=candidate;q=book.get((minute,strike,side))
                if count>=2:reason='daily_trade_cap'
                elif peak-cash>=40000:reason='drawdown_halt'
                elif monthly[month]<=-13000:reason='monthly_halt'
                elif day_pnl<=-3000:reason='daily_halt'
                elif not valid(q) or q[0]<=.05:reason='unfilled_entry'
                elif S.entry_debit(q[0],day,exp,stress)>cash:reason='insufficient_cash'
                elif S.trade_pnl(q[0],q[0],day,exp)[0]<=-1500:reason='roundtrip_cost_exceeds_stop'
                else:
                    entry=q[0];stop,target=barriers(entry,day,exp)
                    risk=-S.trade_pnl(entry,stop,day,exp,stress)[0]
                    budget=min(2500,3000+day_pnl,13000+monthly[month],40000-(peak-cash))
                    if risk>budget:reason='risk_budget'
                    else:
                        position=dict(side=side,strike=strike,entry=entry,entered=minute,stop=stop,target=target)
                        count+=1;reason=None
                if reason:
                    rejected[reason]=rejected.get(reason,0)+1;continue
            q=book.get((minute,position['strike'],position['side']))
            if not valid(q):missing+=1;continue
            atopen=S.trade_pnl(position['entry'],q[0],day,exp,stress)[0]
            if minute>position['entered'] and (peak-(cash+atopen)>=40000 or monthly[month]+atopen<=-13000 or day_pnl+atopen<=-3000):
                fill=(q[0],'account_loss_gate')
            elif minute>=890:fill=(q[0],'square_off')
            else:fill=exit_fill(q,position['stop'],position['target'],minute-position['entered'])
            if fill:
                price,reason=fill;net,gross,fees,friction=S.trade_pnl(position['entry'],price,day,exp,stress)
                cash+=net;day_pnl+=net;monthly[month]+=net;peak=max(peak,cash);mtm_dd=max(mtm_dd,peak-cash)
                rows.append(dict(day=day,expiry=exp,side=position['side'],strike=position['strike'],
                    lots=1,quantity=quantity(exp),entry_minute=position['entered'],exit_minute=minute,
                    entry=position['entry'],exit=price,stop=position['stop'],target=position['target'],
                    reason=reason,net=net,gross=gross,fees=fees,friction=friction,equity=cash))
                position=None
            else:
                liquidation=cash+S.trade_pnl(position['entry'],q[3],day,exp,stress)[0]
                peak=max(peak,liquidation);mtm_dd=max(mtm_dd,peak-liquidation)
        if position is not None:raise ValueError('Unpriceable terminal fixed contract on '+day)
    columns=['day','expiry','side','strike','lots','quantity','entry_minute','exit_minute','entry','exit','stop','target','reason','net','gross','fees','friction','equity']
    ledger=pd.DataFrame(rows,columns=columns);days=[s['day'] for s in sessions]
    summary=P.summarize(ledger.assign(stress_net=ledger.net),days);summary.pop('stress_net')
    summary.update(starting_capital=initial,ending_capital=cash,wins=int((ledger.net>0).sum()),
        target_2000_reaches=int((ledger.net>=2000-1e-6).sum()),gross_profit=float(ledger.gross.sum()),
        fees=float(ledger.fees.sum()),execution_friction=float(ledger.friction.sum()),
        average_net_win=float(ledger.loc[ledger.net>0,'net'].mean()) if (ledger.net>0).any() else None,
        average_net_loss=float(ledger.loc[ledger.net<0,'net'].mean()) if (ledger.net<0).any() else None,
        close_mark_liquidation_drawdown=mtm_dd,rejections=rejected,missing_holding_minutes=missing,
        exit_counts=ledger.reason.value_counts().to_dict(),stress_multiplier=stress,fresh_out_of_sample=False)
    assert np.allclose(ledger.net.to_numpy(float),(ledger.gross-ledger.fees-ledger.friction).to_numpy(float))
    assert np.isclose(cash,initial+ledger.net.sum())
    return ledger,summary,dict(cash=cash,peak=peak,mtm_dd=mtm_dd,monthly=monthly)
