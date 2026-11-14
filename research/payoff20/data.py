"""Feature rows are prospective; future quote absence masks outcomes only."""
import hashlib
import json
from pathlib import Path
import numpy as np
import pandas as pd
from research.confluence65 import features as F
from research.neural_v2 import pipeline as P,strategy as S


def horizon_exit(bar,entry,stop,elapsed,target_pct=.3):
    op,hi,lo,close,volume=bar
    if volume<=0 or not np.isfinite([op,hi,lo,close]).all() or min(op,hi,lo,close)<=0:return None
    stop=entry*.85  # Explicitly ignore the shared engine's profit-lock updates.
    if op<=stop:return op,'gap_stop'
    if elapsed>=20:return op,'twenty_minute_exit'
    if lo<=stop:return stop,'initial_stop'
    return None


def outcome(book,minute,strike,side,day,expiry):
    q=book.get((minute,strike,side))
    if q is None or q[4]<=0 or q[0]<=0 or not np.isfinite(q).all():return None
    entry=q[0]
    for m in range(minute,891):
        q=book.get((m,strike,side))
        if q is None or q[4]<=0:continue
        if m>minute and S.trade_pnl(entry,q[0],day,expiry)[0]<=-3000:fill=(q[0],'standalone_daily_gate')
        elif m>=890:fill=(q[0],'square_off')
        else:fill=horizon_exit(q,entry,entry*.85,m-minute)
        if fill:
            net,gross,fees,friction=S.trade_pnl(entry,fill[0],day,expiry)
            return dict(net=net,gross=gross,fees=fees,friction=friction,exit_minute=m,exit=fill[0],entry=entry,reason=fill[1])
    return None


def underlying(d,previous):
    o={}
    for n in (1,5,15,30):o['return'+str(n)]=d.close.pct_change(n,fill_method=None)
    o['vol15']=d.close.pct_change(fill_method=None).rolling(15).std()
    tr=pd.concat([d.high-d.low,(d.high-d.close.shift()).abs(),(d.low-d.close.shift()).abs()],axis=1).max(axis=1)
    o['atr14']=tr.rolling(14).mean()/d.close
    o['ema_gap']=(d.close.ewm(span=9,adjust=False).mean()-d.close.ewm(span=21,adjust=False).mean())/d.close
    o['range']=(d.high-d.low)/d.close;o['body']=(d.close-d.open)/d.close
    o['from_open']=d.close/d.open.iloc[0]-1
    o['from_session_high']=d.close/d.high.cummax()-1;o['from_session_low']=d.close/d.low.cummin()-1
    for key in ('high','low','close'):o['from_previous_'+key]=d.close/previous[key]-1 if previous else np.nan
    o['vix']=d.INDIAVIX;o['vix_return15']=d.INDIAVIX.pct_change(15,fill_method=None)
    o['time']=(d.index.hour*60+d.index.minute+1-555)/375
    return pd.DataFrame(o,index=d.index)


def build(lake,out):
    indices={};hashes={}
    for sym in ('NIFTY','INDIAVIX'):
        paths=sorted(lake.glob(f'index_1m_{sym}_*.parquet'))
        if not paths:raise ValueError('Missing '+sym)
        for p in paths:hashes[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
        d=pd.concat([pd.read_parquet(p) for p in paths]).sort_values('ts')
        if d.ts.dt.tz is None or d.ts.duplicated().any():raise ValueError('Invalid index timestamps')
        d.ts=d.ts.dt.tz_convert('Asia/Kolkata');indices[sym]=d.set_index('ts')
    index=indices['NIFTY'][['open','high','low','close']].join(indices['INDIAVIX'].close.rename('INDIAVIX'),how='inner').dropna()
    end=index.index.max().normalize();start=end-pd.DateOffset(years=1)+pd.Timedelta(days=1)
    paths=sorted(lake.glob('opt_NIFTY_WEEK_*.parquet'))
    if not paths:raise ValueError('Missing options')
    options=[]
    for p in paths:
        hashes[p.name]=hashlib.sha256(p.read_bytes()).hexdigest();g=pd.read_parquet(p);g.ts=g.ts.dt.tz_convert('Asia/Kolkata')
        options.append(g[(g.ts>=start)&(g.ts<end+pd.Timedelta(days=1))])
    opts=pd.concat(options);opts['side']=opts.side.astype(str)
    if opts.duplicated(['ts','strike','side']).any():raise ValueError('Duplicate contracts')
    groups={day:g for day,g in opts.groupby(opts.ts.dt.date)}
    ex=pd.read_csv(P.HERE.parent/'brain/calendar_data/expiries.csv');observed=set(pd.to_datetime(ex.loc[ex.und=='NIFTY','date']).dt.date)
    rows=[];sessions=[];previous=None
    for day,d in index.groupby(index.index.date):
        if pd.Timestamp(day).tz_localize('Asia/Kolkata')<start:
            previous=dict(high=float(d.high.max()),low=float(d.low.min()),close=float(d.close.iloc[-1])) if len(d)>=300 else None
            continue
        if day not in groups:raise ValueError('Missing option session '+str(day))
        expiry,source=P.expiry(day,observed);g=groups[day].copy();g['minute']=g.ts.dt.hour*60+g.ts.dt.minute
        book={(int(r.minute),float(r.strike),r.side):(r.open,r.high,r.low,r.close,r.volume) for r in g.itertuples()}
        chain=F.chain_facts(g,d,expiry);u=underlying(d,previous)
        selected=g[((g.side=='C')&(g.offset==-1))|((g.side=='P')&(g.offset==1))].set_index(['ts','side'])
        continuous=pd.Series(d.index.asi8,index=d.index).diff().eq(60_000_000_000).rolling(29).sum().eq(29)
        for j,(stamp,bar) in enumerate(d.iterrows()):
            minute=stamp.hour*60+stamp.minute+1
            if minute%5 or not 585<=minute<=825 or not continuous.iloc[j] or not 1<=(expiry-day).days<=7:continue
            future=d.close.get(stamp+pd.Timedelta(minutes=20),np.nan)
            for side,sign in [('C',1.),('P',-1.)]:
                cf=chain.loc[stamp];snapshot=selected.loc[(stamp,side)] if (stamp,side) in selected.index else None
                strike=float(cf.get(side+'_strike',np.nan));premium=float(snapshot.close) if snapshot is not None else np.nan
                feature=u.loc[stamp].to_dict();feature.update(side_sign=sign,premium=premium,
                    iv=float(cf.get(side+'_iv_fraction',np.nan)),delta_abs=abs(float(cf.get(side+'_delta',np.nan))),
                    relative_volume=float(cf.get(side+'_rvol',np.nan)),signed_volume_proxy=float(cf.get(side+'_signed3',np.nan)),
                    pcr=float(cf.pcr),oi_change_balance=float(cf.doi_balance),moneyness=strike/bar.close-1,
                    dte=((pd.Timestamp(str(expiry)+' 15:30',tz='Asia/Kolkata')-stamp).total_seconds()/86400))
                if np.isfinite(premium) and premium>0:
                    cost=-S.trade_pnl(premium,premium,str(day),str(expiry))[0]
                    feature['estimated_cost_fraction']=cost/(premium*(75 if str(expiry)<'2026-01-01' else 65))
                    feature['expected_move_over_cost']=float(cf.get(side+'_delta',np.nan))*sign*bar.close*float(u.loc[stamp,'vol15'])*np.sqrt(20)*(75 if str(expiry)<'2026-01-01' else 65)/cost
                else:feature['estimated_cost_fraction']=np.nan;feature['expected_move_over_cost']=np.nan
                v=feature['signed_volume_proxy'];feature['signed_volume_proxy']=np.sign(v)*np.log1p(abs(v))
                actual=outcome(book,minute,strike,side,str(day),str(expiry)) if np.isfinite(strike) else None
                row=dict(day=str(day),available_at=str(stamp+pd.Timedelta(minutes=1)),minute=minute,side=side,strike=strike,
                    expiry=str(expiry),actual_net=actual['net'] if actual else np.nan,actual_gross=actual['gross'] if actual else np.nan,
                    actual_fees=actual['fees'] if actual else np.nan,actual_friction=actual['friction'] if actual else np.nan,
                    actual_exit=actual['exit_minute'] if actual else np.nan,index_up=(float(future>bar.close) if np.isfinite(future) and future!=bar.close else np.nan),
                    momentum_side='C' if u.loc[stamp,'return15']>0 else 'P' if u.loc[stamp,'return15']<0 else '')
                rows.append((feature,row))
        sessions.append(dict(day=str(day),expiry=str(expiry),expiry_source=source,book=book,candidates={}))
        previous=dict(high=float(d.high.max()),low=float(d.low.min()),close=float(d.close.iloc[-1])) if len(d)>=300 else None
        if len(sessions)%50==0:print('data',len(sessions),'sessions',len(rows),'candidate actions',flush=True)
    if not rows:raise ValueError('No feature rows')
    x=pd.DataFrame([a for a,b in rows]).replace([np.inf,-np.inf],np.nan);meta=pd.DataFrame([b for a,b in rows])
    fingerprint=hashlib.sha256(json.dumps(hashes,sort_keys=True).encode()+np.nan_to_num(x.to_numpy(),nan=-999999).tobytes()+pd.util.hash_pandas_object(meta,index=False).to_numpy().tobytes()).hexdigest()
    audit=dict(start=str(start.date()),end=str(end.date()),sessions=len(sessions),candidate_rows=len(meta),observed_labels=int(meta.actual_net.notna().sum()),
        missing_labels=int(meta.actual_net.isna().sum()),features=list(x.columns),files=hashes,fingerprint=fingerprint,
        label='One fixed contract, next-open entry,15% stop,20minute exit; missing outcomes mask labels only',fresh_holdout=False)
    P.dump(out/'data_audit.json',audit);return x,meta,sessions,audit
