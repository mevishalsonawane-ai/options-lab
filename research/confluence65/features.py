"""Causal underlying levels, observed option context and modeled Greeks."""
import numpy as np
import pandas as pd
from scipy.special import ndtr

FAMILIES=('breakout','sweep','previous_reclaim','range_fade')
CONTEXTS=('plain','chain','greek_proxy','all')


def delta(spot,strike,iv,years,side):
    s,k,v,t=np.broadcast_arrays(np.asarray(spot,float),np.asarray(strike,float),np.asarray(iv,float),np.asarray(years,float))
    good=(s>0)&(k>0)&(v>0)&(v<3)&(t>0)&np.isfinite(s+k+v+t)
    with np.errstate(invalid='ignore',divide='ignore'):
        d1=(np.log(s/k)+(.065+.5*v*v)*t)/(v*np.sqrt(t))
        value=ndtr(d1)-(np.asarray(side)=='P')
    return np.where(good,value,np.nan)


def chain_facts(g,index,expiry):
    """No rolling-offset position: every change/rolling feature uses fixed K/side."""
    g=g.sort_values(['strike','side','ts']).copy()
    keys=['strike','side'];group=g.groupby(keys,observed=True)
    if g.duplicated(['ts',*keys]).any():raise ValueError('Duplicate chain contracts')
    stamps=pd.to_datetime(g.ts)
    good_gap=group.ts.diff().eq(pd.Timedelta(minutes=1))
    contiguous=good_gap.groupby([g.strike,g.side],observed=True).transform(lambda x:x.rolling(20,min_periods=20).sum().eq(20))
    priorvol=group.volume.transform(lambda x:x.shift(1).rolling(20,min_periods=20).median())
    g['rvol']=(g.volume/priorvol.where(priorvol>0)).where(contiguous)
    signed=np.sign(g.close-g.open)*g.volume
    g['signed3']=signed.groupby([g.strike,g.side],observed=True).transform(lambda x:x.rolling(3,min_periods=3).sum())
    g.loc[~good_gap.groupby([g.strike,g.side],observed=True).transform(lambda x:x.rolling(2,min_periods=2).sum().eq(2)),'signed3']=np.nan
    oi=g.set_index(['ts',*keys]).oi
    prior=pd.MultiIndex.from_arrays([stamps-pd.Timedelta(minutes=5),g.strike,g.side],names=['ts',*keys])
    g['doi5']=g.oi.to_numpy()-oi.reindex(prior).to_numpy()
    expiration=pd.Timestamp(str(expiry)+' 15:30',tz='Asia/Kolkata')
    years=(expiration-stamps).dt.total_seconds().to_numpy()/(365*86400)
    g['delta']=delta(g.spot,g.strike,g.iv/100,years,g.side)
    g['iv_fraction']=g.iv/100
    # PCR and OI CHANGE basket use only contracts observed at both exact timestamps.
    spot=index.close.reindex(pd.DatetimeIndex(g.ts)).to_numpy()
    atm=np.floor(spot/50+.5)*50
    near=g[(g.strike.to_numpy()-atm).__abs__()<=250].dropna(subset=['doi5'])
    sums=near.groupby(['ts','side'],observed=True)[['oi','doi5']].sum().unstack('side')
    counts=near.groupby(['ts','side'],observed=True).size().unstack('side')
    result=pd.DataFrame(index=index.index)
    for side in ('C','P'):
        for key in ('oi','doi5'):
            result[f'{side}_{key}']=sums.get((key,side),pd.Series(dtype=float))
        result[f'{side}_count']=counts.get(side,pd.Series(dtype=float))
    result['pcr']=result.P_oi/result.C_oi.where(result.C_oi>0)
    result['doi_balance']=(result.P_doi5-result.C_doi5)/(result.P_oi+result.C_oi).where((result.P_oi+result.C_oi)>0)
    result.loc[(result.C_count<9)|(result.P_count<9),['pcr','doi_balance']]=np.nan
    selected=g[((g.side=='C')&(g.offset==-1))|((g.side=='P')&(g.offset==1))]
    for side in ('C','P'):
        selected_side=selected[selected.side==side].set_index('ts')
        if selected_side.index.has_duplicates:raise ValueError('Ambiguous selected strike')
        for key in ('strike','rvol','signed3','delta','iv_fraction'):
            result[f'{side}_{key}']=selected_side[key]
    return result


def futures_facts(frame):
    """Observed single-contract futures volume; OHLC profile is an approximation."""
    if frame.index.has_duplicates or not frame.index.is_monotonic_increasing:raise ValueError('Invalid futures times')
    d=frame.copy();volume=d.volume.where(d.volume>0)
    prior=volume.shift(1).rolling(20,min_periods=20).median()
    contiguous=pd.Series(d.index.asi8,index=d.index).diff().eq(60_000_000_000).rolling(20).sum().eq(20)
    d['f_rvol']=(volume/prior).where(contiguous)
    typical=(d.high+d.low+d.close)/3
    d['f_vwap']=(typical*volume.fillna(0)).cumsum()/volume.fillna(0).cumsum().replace(0,np.nan)
    profile={};poc=[]
    for price,vol in zip(typical,volume):
        if np.isfinite(price) and np.isfinite(vol):
            bucket=float(np.floor(price/25)*25+12.5);profile[bucket]=profile.get(bucket,0.)+float(vol)
        poc.append(max(profile,key=profile.get) if profile else np.nan)
    d['f_poc']=poc
    return d[['close','f_rvol','f_vwap','f_poc']].rename(columns={'close':'f_close'})


def raw_signals(d,previous,lookback=20):
    if lookback<2:raise ValueError('Lookback too short')
    high=d.high.rolling(lookback).max().shift(1);low=d.low.rolling(lookback).min().shift(1)
    mean=d.close.rolling(lookback).mean().shift(1);sd=d.close.rolling(lookback).std().shift(1)
    pairs={'breakout':(d.close>high,d.close<low),
           'sweep':((d.low<low)&(d.close>low)&(d.close>d.open),(d.high>high)&(d.close<high)&(d.close<d.open)),
           'range_fade':((d.close<mean-2*sd)&(d.close>d.open),(d.close>mean+2*sd)&(d.close<d.open))}
    if previous:
        pairs['previous_reclaim']=((d.low<previous['low'])&(d.close>previous['low'])&(d.close>d.open),
             (d.high>previous['high'])&(d.close<previous['high'])&(d.close<d.open))
    else:pairs['previous_reclaim']=(pd.Series(False,index=d.index),pd.Series(False,index=d.index))
    contiguous=pd.Series(d.index.asi8,index=d.index).diff().eq(60_000_000_000).rolling(lookback).sum().eq(lookback)
    m=d.index.hour*60+d.index.minute+1;eligible=contiguous&(m>=585)&(m<=825)
    return pd.DataFrame({name:np.where(eligible&up&~down,1,np.where(eligible&down&~up,2,0)) for name,(up,down) in pairs.items()},index=d.index)


def gate(d,chain,future,raw,context):
    if context not in CONTEXTS:raise ValueError('Unknown context')
    if context=='plain':return raw.copy()
    keep=pd.Series(False,index=d.index)
    vix=d.INDIAVIX.pct_change(15,fill_method=None)
    # Enforce elapsed15-minute context rather than silently using15 gapped rows.
    continuous=pd.Series(d.index.asi8,index=d.index).diff().eq(60_000_000_000).rolling(15).sum().eq(15)
    for side,number,sign in [('C',1,1),('P',2,-1)]:
        chain_ok=(chain[f'{side}_rvol']>=1.5)&((chain.pcr-1)*sign>=0)&(chain.doi_balance*sign>0)
        greek_ok=chain[f'{side}_delta'].abs().between(.5,.8)&chain[f'{side}_iv_fraction'].between(.00001,.25)&(chain[f'{side}_signed3']>0)&(vix<=.03)&continuous
        fut_ok=(future.f_rvol>=1.5)&((future.f_close-future.f_vwap)*sign>0)&((future.f_close-future.f_poc)*sign>0)
        wanted=chain_ok if context=='chain' else greek_ok if context=='greek_proxy' else chain_ok&greek_ok&fut_ok
        keep|=(raw==number)&wanted.fillna(False)
    return raw.where(keep,0)
