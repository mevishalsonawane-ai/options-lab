import numpy as np
import pandas as pd
import pytest

from .strategy import signals,entry_debit,exit_fill,load,replay,trade_pnl


def session(day='2026-01-02',entry=100.,n=1):
    book={(m,25000.,'C'):(entry,entry,entry,entry,100) for m in range(615,930)}
    picks={615+5*j:('C',25000.) for j in range(n)}
    for j in range(n):
        m=616+5*j;book[(m,25000.,'C')]=(entry,entry*1.4,entry*.9,entry*1.3,100)
    return dict(day=day,expiry='2026-01-06',book=book,candidates=picks)


def test_signal_prefix_and_session_timing():
    ix=pd.date_range('2026-01-02 09:15',periods=120,freq='min',tz='Asia/Kolkata')
    v=np.arange(120)+1000.
    d=pd.DataFrame(dict(close=v,high=v+.1,low=v-.1,BANKNIFTY=v,INDIAVIX=20.),index=ix)
    a=signals(d)
    assert a.loc['2026-01-02 10:14']==1
    assert a.loc['2026-01-02 10:13']==0
    d.iloc[80:,0]=99999
    pd.testing.assert_series_equal(a.iloc[:80],signals(d).iloc[:80])
    d.loc[ix[59],'INDIAVIX']=25
    assert signals(d).iloc[59]==0


def test_conservative_ohlc_stop_and_gap():
    assert exit_fill((100,140,80,120,100),100,85,1)==(85,'stop_or_profit_lock')
    assert exit_fill((70,140,60,100,100),100,85,1)==(70,'gap_stop')
    assert exit_fill((100,101,99,100,0),100,85,1) is None
    assert exit_fill((103,110,100,105,100),100,85,20)==(103,'time_stop')
    assert exit_fill((110,120,100,110,100),100,85,45)==(110,'maximum_hold')


def test_inclusive_ipft_and_stt_change():
    a=trade_pnl(100,110,'2026-02-28','2026-03-03')
    b=trade_pnl(100,110,'2026-03-01','2026-03-03')
    assert a==b
    assert trade_pnl(100,110,'2026-04-01','2026-04-07')[0]<b[0]
    assert a[0]==pytest.approx(a[1]-a[2]-a[3])
    with pytest.raises(ValueError):trade_pnl(0,10,'2026-01-01','2026-01-06')


def test_profit_reconciles_and_daily_cap():
    trades,r=replay([session(n=3)])
    assert len(trades)==2 and r['wins']==2
    assert r['rejections']['daily_trade_cap']==1
    assert r['net']==pytest.approx(r['gross_profit']-r['fees']-r['execution_friction'])
    assert r['ending_capital']==pytest.approx(100000+r['net'])
    assert entry_debit(100,'2026-01-02','2026-01-06',2)>entry_debit(100,'2026-01-02','2026-01-06',1)


def test_holding_contract_never_rolls_and_missing_terminal_aborts():
    s=session();del s['book'][(616,25000.,'C')]
    s['book'][(616,25050.,'C')]=(200,250,150,200,100)
    s['book'][(617,25000.,'C')]=(135,140,130,135,100)
    trades,r=replay([s])
    assert trades.iloc[0].strike==25000 and trades.iloc[0].exit==135
    assert r['missing_holding_minutes']==1
    s['book']={(615,25000.,'C'):(100,100,100,100,100)}
    with pytest.raises(ValueError,match='Unpriceable terminal'):replay([s])


def test_profit_lock_uses_prior_bar_only():
    s=session();s['book'][(615,25000.,'C')]=(100,118,100,117,100)
    s['book'][(616,25000.,'C')]=(112,125,110,120,100)
    t,_=replay([s]);assert t.iloc[0].exit==pytest.approx(110.8)


def test_cash_and_stress_risk_budget():
    t,r=replay([session(entry=2000)])
    assert len(t)==0 and r['rejections']['insufficient_cash']==1
    t,r=replay([session(entry=225)])
    assert len(t)==1
    t,r=replay([session(entry=225)],2)
    assert len(t)==0 and r['rejections']['risk_budget']==1


def test_monthly_budget_and_unfilled_entry(tmp_path):
    sessions=[]
    for j in range(2,20):
        s=session(day=f'2026-01-{j:02d}',n=2)
        for m in (616,621):s['book'][(m,25000.,'C')]=(100,101,80,85,100)
        sessions.append(s)
    t,r=replay(sessions)
    assert r['net']>=-13000 and len(t)<36 and r['close_mark_liquidation_drawdown']>=0
    s=session();s['book'][(615,25000.,'C')]=(100,100,100,100,0)
    t,r=replay([s]);assert len(t)==0 and r['rejections']['unfilled_entry']==1
    with pytest.raises(ValueError,match='Missing index'):load(tmp_path)
