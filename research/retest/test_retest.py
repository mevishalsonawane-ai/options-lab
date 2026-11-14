import numpy as np
import pandas as pd
import pytest
from . import signals as G,study as S
from research.neural_v2.test_strategy import session
from research.confluence65.test_confluence import index,quotes


def path(n=120,at=45):
    t=pd.date_range('2026-01-02 09:15',periods=n,freq='min',tz='Asia/Kolkata')
    d=pd.DataFrame(dict(open=100.,high=101.,low=99.,close=100.),index=t)
    d.iloc[at]=[100.5,103.,100.5,102.5]
    d.iloc[at+1]=[101.,101.6,100.95,101.5]
    return d


def test_break_retest_separate_and_prefix():
    d=path();im=G.detect(d,None,'range','immediate');re=G.detect(d,None,'range','retest')
    assert im.iloc[45]==1 and re.iloc[45]==0 and re.iloc[46]==1
    for i in (30,45,46,47,80):
        pd.testing.assert_series_equal(re.iloc[:i],G.detect(d.iloc[:i],None,'range','retest'))
    mirrored=d.copy();mirrored.open=200-d.open;mirrored.close=200-d.close;mirrored.high=200-d.low;mirrored.low=200-d.high
    assert G.detect(mirrored,None,'range','retest').iloc[46]==2
    utc=d.tz_convert('UTC');np.testing.assert_array_equal(re,G.detect(utc,None,'range','retest'))


def test_invalid_touch_gap_timeout_and_level_freeze():
    d=path();d.iloc[46,2]=100.
    assert G.detect(d,None,'range','retest').iloc[46]==0
    d=path().drop(path().index[46]);assert not G.detect(d,None,'range','retest').loc['2026-01-02 10:02'].any()
    d=path();d.iloc[46:51]=[102,103,101.5,102.5];d.iloc[51]=[101.,101.6,100.95,101.5]
    assert G.detect(d,None,'range','retest',wait=5).iloc[51]==0
    assert G.detect(d,None,'range','retest',wait=6).iloc[51]==1
    d=path(at=90);d.iloc[30]=[100,105,95,100]
    # Compression ceases after the breakout; the frozen pending event can still retest.
    assert G.detect(d,None,'compression','retest').iloc[91]==1


def test_opening_previous_and_input_errors():
    d=path();assert G.detect(d,None,'opening','retest').iloc[46]==1
    assert G.detect(d,{'high':101.,'low':99.},'previous','retest').iloc[46]==1
    assert not G.detect(d,None,'previous','retest').any()
    assert not G.detect(d.drop(d.index[5]),None,'opening','retest').any()
    for kind,entry,wait,tol in [('bad','retest',5,.1),('range','bad',5,.1),('range','retest',0,.1)]:
        with pytest.raises(ValueError):G.detect(d,None,kind,entry,wait,tol)
    assert G.detect(d.iloc[:0],None,'range','retest').empty
    with pytest.raises(ValueError):G.detect(d.tz_localize(None),None,'range','retest')
    dd=d.copy();dd.index=dd.index+pd.Timedelta(seconds=1)
    with pytest.raises(ValueError):G.detect(dd,None,'range','retest')


def test_higher_timeframe_prefix_complete_and_fresh():
    d=index(180);a=G.higher_trend(d)
    assert G.higher_trend(d.iloc[:0]).empty
    assert a.iloc[:104].eq(0).all() and a.iloc[104]==1
    for n in (120,144,146):pd.testing.assert_series_equal(a.iloc[:n],G.higher_trend(d.iloc[:n]))
    g=d.drop(d.index[144]);b=G.higher_trend(g)
    assert pd.Timestamp('2026-01-02 11:39',tz='Asia/Kolkata') not in b.index
    assert b.loc['2026-01-02 11:40']==0 and b.loc['2026-01-02 11:44']==1
    with pytest.raises(ValueError):G.higher_trend(pd.concat([d,d.iloc[:1]]))


def test_exit_profiles_and_delayed_strike():
    assert S.exit_profile((100,112,80,105,1),100,99,1,'quick')==(85,'initial_stop')
    assert S.exit_profile((100,112,95,105,1),100,99,1,'quick')[0]==pytest.approx(110.)
    assert S.exit_profile((110,120,105,115,1),100,115,20,'runner') is None
    assert S.exit_profile((104,110,100,105,1),100,115,20,'runner')==(104,'time_stop')
    assert S.exit_profile((110,120,105,115,1),100,115,60,'runner')==(110,'maximum_hold')
    with pytest.raises(ValueError):S.exit_profile((100,110,99,105,1),100,85,1,'bad')
    original=session();s=dict(original,picks={(615,'C'):25000.,(616,'C'):25050.},minutes=np.array([615]),eligible_expiry=True,
        raw={('range','retest',5,.1):np.array([1])},masks={('plain',1):np.array([True]),('plain',2):np.array([True])})
    assert S.adapt([s],S.CENTRE,1)[0]['candidates'][616]==('C',25000.)
    before=S.E.exit_fill;d,r,state=S.replay([s],S.CENTRE,1.,diagnostics=False)
    assert S.E.exit_fill is before and len(d)==1 and state['cash']==r['ending_capital']


def test_loader_and_cost_features(tmp_path):
    with pytest.raises(ValueError):S.load(tmp_path,tmp_path/'out')
    d=index(375).reset_index(names='ts')
    for sym in ('NIFTY','INDIAVIX'):d.to_parquet(tmp_path/f'index_1m_{sym}_2026.parquet')
    g=quotes(d.set_index('ts'));g.to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet')
    ss,audit=S.load(tmp_path,tmp_path/'out');assert len(ss)==1 and audit['sessions']==1
    assert len(ss[0]['raw'])==16 and len(ss[0]['masks'])==6
    pd.concat([g,g.iloc[:1]]).to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet')
    with pytest.raises(ValueError):S.load(tmp_path,tmp_path/'out')


def fake_sessions():
    out=[]
    for day in ['2026-01-02','2026-01-05','2026-04-02','2026-04-03','2026-07-02','2026-07-03']:
        s=session(day=day);s.pop('candidates');s.update(picks={(615,'C'):25000.},minutes=np.array([615]),eligible_expiry=True,
            raw={(kind,entry,w,t):np.array([1]) for kind in G.KINDS for entry in ('immediate','retest') for w in (4,5,6) for t in (.08,.1,.12)},
            masks={(c,side):np.array([True]) for c in G.CONTEXTS for side in (1,2)})
        out.append(s)
    return out


def test_complete_run_and_wf_account_handoff(monkeypatch,tmp_path):
    ss=fake_sessions();monkeypatch.setattr(G,'KINDS',('range',));monkeypatch.setattr(G,'CONTEXTS',('plain',))
    monkeypatch.setattr(S,'load',lambda lake,out:(ss,{'sessions':6}))
    result=S.run(None,tmp_path)
    assert result['unique_rules']==12 and result['account_endpoints']==26
    assert len(list(tmp_path.glob('*__x*.csv')))==26
    assert all(d['selected']=='NO_TRADE' for d in result['walk_forward']['decisions'])
    assert all('bh_q' in x for x in result['results'].values())
    # Exercise a chosen policy's routing/state, without weakening production eligibility.
    monkeypatch.setattr(S.R,'select',lambda scores:next(iter(scores)))
    chosen=S.walk_forward(ss,[('range','immediate','plain','quick',5,.1)],tmp_path)
    assert chosen['accounts']['1.0']['trades']==4
    assert chosen['accounts']['1.0']['net']>0


def test_report_rejects_tiny_sample_and_zero_exposure(monkeypatch,tmp_path):
    from .report import write
    ss=fake_sessions();monkeypatch.setattr(G,'KINDS',('range',));monkeypatch.setattr(G,'CONTEXTS',('plain',))
    monkeypatch.setattr(S,'load',lambda lake,out:(ss,{'sessions':6}))
    S.run(None,tmp_path);summary=write(tmp_path)
    assert not summary['qualifying_historical_rules'] and not summary['demonstrated_65pct_strategy']
    text=(tmp_path/'REPORT.md').read_text();assert 'Frozen-center sensitivity' in text and 'not a fresh final holdout' in text


def test_opening_level_itself_not_available_before_close():
    d=path();full=G.levels(d,None,'opening')
    assert full[0].iloc[:15].isna().all() and full[0].iloc[15]==101
    for n in (10,15,20,50):
        part=G.levels(d.iloc[:n],None,'opening')
        for a,b in zip(full,part):pd.testing.assert_series_equal(a.iloc[:n],b)
    bad=d.copy();bad.index=bad.index+pd.Timedelta(seconds=1)
    with pytest.raises(ValueError):G.higher_trend(bad)


def test_late_diagnostic_explicit_subset_not_new_strategy(tmp_path):
    from .report import late_comparison
    keys={}
    for entry in ('immediate','retest'):
        for stress in (1.,2.):
            key=f'opening__{entry}__trend_cost__quick__w5__a0.10__d0__x{stress}';keys[key]={}
            pd.DataFrame(dict(day=['2026-01-02','2026-04-02','2026-07-02'],net=[-1000,100,-50])).to_csv(tmp_path/(key+'.csv'),index=False)
    x=late_comparison(tmp_path,keys)
    assert x['exploratory'] and len(x['rows'])==4
    assert x['rows']['retest__x1.0']['trades']==2 and x['rows']['retest__x1.0']['net']==50
    assert x['rows']['retest__x1.0']['win_rate']==.5


def test_archive_exact_bytes_and_reader_fallback(tmp_path):
    import zipfile
    from .report import archive_ledgers,read_ledger
    p=tmp_path/'example__x1.0.csv';original=b'day,net\n2026-04-02,123.45\n';p.write_bytes(original)
    assert archive_ledgers(tmp_path)==1
    with zipfile.ZipFile(tmp_path/'ledgers.zip') as z:assert z.read(p.name)==original
    p.unlink();assert read_ledger(tmp_path,'example__x1.0').net.iloc[0]==123.45
    assert archive_ledgers(tmp_path)==1
