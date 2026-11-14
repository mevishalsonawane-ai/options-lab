"""Frozen broken levels, later clean retests, completed higher-timeframe context."""
import numpy as np
import pandas as pd

KINDS=('range','opening','previous','compression')
CONTEXTS=('plain','trend','trend_cost')


def higher_trend(frame):
    if frame.index.tz is None or frame.index.has_duplicates or not frame.index.is_monotonic_increasing:
        raise ValueError('Ordered timezone-aware unique candles required')
    if frame.empty:return pd.Series(0,index=frame.index,dtype=np.int8)
    if frame.index.second.any() or frame.index.microsecond.any() or frame.index.nanosecond.any():raise ValueError('Minute candles required')
    d=frame.tz_convert('Asia/Kolkata')
    bars=d.close.resample('5min',closed='left',label='right').agg(['last','count'])
    complete=bars[bars['count']==5]['last']
    fast=complete.ewm(span=9,adjust=False,min_periods=21).mean();slow=complete.ewm(span=21,adjust=False,min_periods=21).mean()
    direction=pd.Series(np.where((fast>slow)&(complete>fast),1,np.where((fast<slow)&(complete<fast),2,0)),index=complete.index)
    decision=d.index+pd.Timedelta(minutes=1)
    aligned=direction.reindex(decision,method='ffill').fillna(0)
    last=pd.Series(complete.index,index=complete.index).reindex(decision,method='ffill')
    age=decision.asi8-pd.DatetimeIndex(last).asi8
    fresh=(age>=0)&(age<300_000_000_000)&last.notna().to_numpy()
    same=np.array([not pd.isna(t) and t.date()==s.date() for t,s in zip(last,decision)])
    return pd.Series(np.where(fresh&same,aligned.to_numpy(),0),index=frame.index,dtype=np.int8)


def levels(d,previous,kind):
    if kind not in KINDS:raise ValueError('Unknown level')
    hi=d.high.rolling(20).max().shift(1);lo=d.low.rolling(20).min().shift(1)
    need=20;event=pd.Series(True,index=d.index)
    if kind=='opening':
        start=d.index[0].normalize()+pd.Timedelta(hours=9,minutes=15)
        required=pd.date_range(start,periods=15,freq='min')
        opening=d.reindex(required)
        valid=opening[['open','high','low','close']].notna().all().all()
        hi=pd.Series(opening.high.max() if valid else np.nan,index=d.index).where(d.index>=start+pd.Timedelta(minutes=15))
        lo=pd.Series(opening.low.min() if valid else np.nan,index=d.index).where(d.index>=start+pd.Timedelta(minutes=15));need=14
        event=pd.Series(d.index>=start+pd.Timedelta(minutes=15),index=d.index)
    elif kind=='previous':
        hi=pd.Series(previous['high'] if previous else np.nan,index=d.index)
        lo=pd.Series(previous['low'] if previous else np.nan,index=d.index);need=14
    elif kind=='compression':
        wide=d.high.rolling(60).max().shift(1)-d.low.rolling(60).min().shift(1)
        event=((hi-lo)/wide.where(wide>0))<=.5;need=60
    contiguous=pd.Series(d.index.asi8,index=d.index).diff().eq(60_000_000_000).rolling(need).sum().eq(need)
    return hi,lo,event.fillna(False)&contiguous


def detect(d,previous,kind,entry,wait=5,tolerance=.1):
    if kind not in KINDS or entry not in ('immediate','retest') or wait<1 or not 0<tolerance<1:raise ValueError('Invalid retest rule')
    if not isinstance(d.index,pd.DatetimeIndex) or d.index.tz is None or d.index.has_duplicates or not d.index.is_monotonic_increasing:
        raise ValueError('Ordered timezone-aware unique candles required')
    original_index=d.index
    d=d.tz_convert('Asia/Kolkata')
    if d.index.second.any() or d.index.microsecond.any() or d.index.nanosecond.any():raise ValueError('Minute candles required')
    out=pd.Series(0,index=original_index,dtype=np.int8)
    if d.empty:return out
    if len(set(d.index.date))!=1:raise ValueError('One session required')
    high,low,event=levels(d,previous,kind)
    tr=pd.concat([d.high-d.low,(d.high-d.close.shift()).abs(),(d.low-d.close.shift()).abs()],axis=1).max(axis=1)
    atr=tr.rolling(14).mean().to_numpy();all_sides=[]
    op,hi,lo,close=d[['open','high','low','close']].to_numpy(float).T
    finite=np.isfinite(np.stack([op,hi,lo,close],axis=1)).all(1)
    stamps=d.index.asi8;minutes=d.index.hour*60+d.index.minute+1;events=event.to_numpy()
    for side,sign,limit in [(1,1,high),(2,-1,low)]:
        sig=np.zeros(len(d),np.int8);pending=None;armed=True
        limits=limit.to_numpy()
        for i in range(len(d)):
            stamp=stamps[i];minute=minutes[i]
            if i and stamp-stamps[i-1]!=60_000_000_000:pending=None;armed=True
            if not 585<=minute<=825 or not finite[i]:pending=None;continue
            if pending is not None:
                when,level,tol=pending;age=(stamp-when)/60_000_000_000
                extreme=lo[i] if side==1 else hi[i]
                if age>wait or sign*(extreme-level)<-tol:pending=None
                elif age>=1 and abs(extreme-level)<=tol and sign*(close[i]-level)>0 and sign*(close[i]-op[i])>0:
                    sig[i]=side;pending=None;continue
            level=limits[i]
            if not np.isfinite(level):continue
            if pending is None and sign*(close[i]-level)<=0:armed=True
            if pending is not None or not armed or not events[i] or not np.isfinite(atr[i]) or atr[i]<=0:continue
            tol=float(atr[i]*tolerance)
            if sign*(close[i]-level)>.10*atr[i]:
                armed=False
                if entry=='immediate':sig[i]=side
                else:pending=(stamp,float(level),tol)
        all_sides.append(sig)
    a,b=all_sides;out.iloc[:]=np.where((a>0)&(b>0),0,np.maximum(a,b))
    return out
