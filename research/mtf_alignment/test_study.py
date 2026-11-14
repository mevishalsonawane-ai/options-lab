import numpy as np
import pandas as pd
import pytest
from . import study as M
from research.neural_v2.test_strategy import session


def bars(n=40):
    ix=pd.date_range('2026-01-02 09:15',periods=n,freq='min',tz='Asia/Kolkata')
    o=np.arange(n)+100.
    return pd.DataFrame(dict(open=o,high=o+2,low=o-1,close=o+1),index=ix)


def test_completion_and_prefix():
    d=bars();s=M.signals(d,'one_plus_five')
    assert not s.iloc[:4].any() and s.iloc[4]==1
    # At09:21 the open09:20 five-minute candle is NOT completed yet.
    d.iloc[5:10]=[200,201,50,60]
    assert M.signals(d,'one_plus_five').iloc[5]==0
    assert M.signals(d,'one_minute').iloc[5]==2
    a=M.signals(d,'one_plus_five');d.iloc[20:]=[500,600,100,200]
    pd.testing.assert_series_equal(a.iloc[:20],M.signals(d,'one_plus_five').iloc[:20])


def test_bearish_agreement_doji_and_missing():
    d=bars();d.loc[:,'open']=d.close+1;d.loc[:,'high']=d.open+1
    assert M.signals(d,'one_plus_five').iloc[4]==0  # five-minute trend still rises
    d.loc[:,'open']=200.;d.loc[:,'close']=100.;d.loc[:,'high']=201.;d.loc[:,'low']=99.
    assert M.signals(d,'one_plus_five').iloc[4]==2
    d.loc[d.index[4],'close']=200.
    assert M.signals(d,'one_plus_five').iloc[4]==0
    d=bars().drop(bars().index[2])
    assert not M.signals(d,'one_plus_five').iloc[:8].any()
    assert M.signals(d,'one_plus_five').iloc[8]==1


def test_invalid_gap_and_session():
    d=bars();d.iloc[1,0]=np.nan
    assert M.signals(d,'one_plus_five').iloc[4]==0
    d=bars().drop(bars().index[6]);assert M.signals(d,'one_minute').iloc[6]==0
    assert M.signals(bars(0),'one_minute').empty
    with pytest.raises(ValueError):M.signals(bars(),'three_frames')
    with pytest.raises(ValueError):M.signals(bars().tz_localize(None),'one_minute')
    with pytest.raises(ValueError):M.signals(pd.concat([bars(),bars()]),'one_minute')
    d=bars();d.index=d.index+pd.Timedelta(seconds=10)
    with pytest.raises(ValueError):M.signals(d,'one_minute')
    d=bars();d.index=d.index+pd.Timedelta(days=1)*np.arange(len(d))
    with pytest.raises(ValueError):M.signals(d,'one_minute')
    d=bars();d.index=d.index-pd.Timedelta(hours=1)
    with pytest.raises(ValueError):M.signals(d,'one_minute')
    d=bars(375);assert not M.signals(d,'one_plus_five').loc['2026-01-02 13:45':].any()


def test_load_callback_restoration(monkeypatch):
    original=M.S.signals
    def fake(lake):
        assert M.S.signals is not original
        return M.S.signals(bars())
    monkeypatch.setattr(M.S,'load',fake)
    assert M.load_arm(None,'one_plus_five').iloc[4]==1
    assert M.S.signals is original
    with pytest.raises(ValueError):M.load_arm(None,'invalid')
    assert M.S.signals is original


def test_reconcile_and_full_run(monkeypatch,tmp_path):
    sessions=[session(day='2026-01-02'),session(day='2026-04-02'),session(day='2026-07-02')]
    monkeypatch.setattr(M,'load_arm',lambda lake,arm:(sessions,dict(start='2026-01-02',end='2026-07-02',sessions=3)))
    result=M.run(None,tmp_path)
    assert len(result['results'])==4 and len(result['window_restarts'])==12
    assert result['exact_three_timeframe']['net'] is None
    assert (tmp_path/'REPORT.md').exists() and len(list(tmp_path.glob('*.csv')))==4
    ledger,s=M.S.replay(sessions);M.reconcile(ledger,s)
    for key in ('net','fees','ending_capital','wins'):
        wrong=dict(s);wrong[key]+=1
        with pytest.raises(ValueError):M.reconcile(ledger,wrong)


def test_adjust_primary():
    results={str(i):dict(p_value=p) for i,p in enumerate([.01,.04,.03,.9])}
    M.adjust_primary(results)
    assert [r['bh_q_four_primary_replays'] for r in results.values()]==pytest.approx([.04,.0533333333,.0533333333,.9])


def test_closed_bar_manual_reference_on_gapped_random_path():
    d=bars(180);rng=np.random.default_rng(19)
    d['open']=100+np.cumsum(rng.normal(size=len(d)))
    d['close']=d.open+rng.choice([-1.,0.,1.],size=len(d))
    d['high']=d[['open','close']].max(axis=1)+1
    d['low']=d[['open','close']].min(axis=1)-1
    d=d.drop(d.index[[1,14,77]])
    actual=M.signals(d,'one_plus_five')
    anchor=d.index[0].normalize()+pd.Timedelta(hours=9,minutes=15)
    for j,(t,row) in enumerate(d.iterrows()):
        decision=t+pd.Timedelta(minutes=1)
        end=anchor+((decision-anchor)//pd.Timedelta(minutes=5))*pd.Timedelta(minutes=5)
        required=pd.date_range(end-pd.Timedelta(minutes=5),periods=5,freq='min')
        expected=0
        if j and t-d.index[j-1]==pd.Timedelta(minutes=1) and set(required)<=set(d.index):
            lo,hi=d.loc[required[0],'open'],d.loc[required[-1],'close']
            minute=np.sign(row.close-row.open);higher=np.sign(hi-lo)
            if minute==higher and minute:expected=1 if minute>0 else 2
        assert actual.loc[t]==expected
