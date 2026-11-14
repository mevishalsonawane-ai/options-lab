import numpy as np
import pandas as pd
import pytest
import torch
from . import data as D,models as M,study as S
from research.confluence65 import engine as E
from research.neural_v2.test_strategy import session
from research.confluence65.test_confluence import index,quotes


def test_horizon_stop_and_fixed_contract_outcome_parity():
    ss=session();ss['book'][(615,25000.,'C')]=(100,118,99,117,100)
    for m in range(616,640):ss['book'][(m,25000.,'C')]=(115,118,112,115,100)
    actual=D.outcome(ss['book'],615,25000.,'C',ss['day'],ss['expiry'])
    assert actual['exit_minute']==635 and actual['exit']==115
    assert D.horizon_exit((115,118,112,115,100),100,110.8,1) is None
    ledger,summary=S.replay([ss],{(ss['day'],615):('C',25000.)},1.)
    assert ledger.iloc[0].net==pytest.approx(actual['net'])
    assert ledger.iloc[0].exit_minute==635
    assert E.exit_fill is not D.horizon_exit
    assert D.horizon_exit((70,140,60,100,1),100,85,1)==(70,'gap_stop')
    assert D.horizon_exit((100,140,80,100,1),100,85,0)==(85,'initial_stop')
    assert D.outcome({},615,25000.,'C',ss['day'],ss['expiry']) is None
    # A different rolling strike cannot fill a missing held contract.
    bad={(615,25000.,'C'):(100,101,99,100,1),(635,25050.,'C'):(200,201,199,200,1)}
    assert D.outcome(bad,615,25000.,'C',ss['day'],ss['expiry']) is None


def test_underlying_prefix_and_train_only_scaler():
    d=index();a=D.underlying(d,None);pd.testing.assert_frame_equal(a.iloc[:60],D.underlying(d.iloc[:60],None))
    rng=np.random.default_rng(42);x=rng.normal(size=(200,5));x[0,0]=np.nan
    z,p=M.scale(x,np.arange(100));x[100:]=100000
    zz,pp=M.scale(x,np.arange(100));np.testing.assert_array_equal(z[:100],zz[:100])
    for k in p:np.testing.assert_array_equal(p[k],pp[k])
    assert np.isfinite(z).all() and z.shape==(200,10)


def test_neural_fit_resume_and_calibration(tmp_path):
    rng=np.random.default_rng(1);x=rng.normal(size=(200,8)).astype(np.float32);y=1000*x[:,0]+rng.normal(size=200)*100
    p=tmp_path/'a.pt';a,b,ck=M.fit_neural(x,y,np.arange(120),np.arange(120,160),p,'data',tmp_path,max_epochs=3)
    aa,bb,cc=M.fit_neural(x,y,np.arange(120),np.arange(120,160),p,'data',tmp_path,max_epochs=3)
    np.testing.assert_array_equal(a,aa);np.testing.assert_array_equal(b,bb)
    with pytest.raises(ValueError):M.fit_neural(x,y,np.arange(120),np.arange(120,160),p,'other',tmp_path,max_epochs=3)
    prob,cal=M.calibrate(a,y,np.arange(100));changed=y.copy();changed[100:]*=-1
    same,_=M.calibrate(a,changed,np.arange(100));np.testing.assert_array_equal(prob,same)
    assert cal['samples']==100 and np.diff(prob[np.argsort(a)]).min()>=0
    with pytest.raises(ValueError):M.calibrate(a,y,np.arange(5))
    for kind in ('linear','trees'):
        prob,net=M.fit(x,y,np.arange(100),np.arange(100,140),np.arange(100,200),kind,tmp_path/kind,'data',tmp_path)
        assert len(prob)==200 and np.isfinite(net).all()
    with pytest.raises(ValueError):M.fit(x,y,np.arange(100),np.arange(100,140),np.arange(100,200),'invalid',tmp_path/'bad','data',tmp_path)


def forecasts():
    return pd.DataFrame(dict(day=['2026-04-02']*2,minute=[615]*2,available_at=['2026-04-02 10:15:00+05:30']*2,
        training_label_cutoff=['2026-03-30 15:30:00+05:30']*2,side=['C','P'],strike=[25000.,25100.],model=['mlp']*2,p_win=[.66,.7],predicted_net=[200.,150.],momentum_side=['P']*2))


def test_causal_candidate_gate_no_outcome_and_tie():
    f=forecasts();assert S.candidates(f,'mlp')=={('2026-04-02',615):('C',25000.)}
    f['actual_net']=[-99999,99999];assert S.candidates(f,'mlp')=={('2026-04-02',615):('C',25000.)}
    assert S.candidates(f,'mlp',.7,0)=={('2026-04-02',615):('P',25100.)}
    assert not S.candidates(f,'mlp',.8,0)
    assert S.candidates(f,'baseline_momentum')=={('2026-04-02',615):('P',25100.)}
    f.loc[:,'predicted_net']=100;assert S.candidates(f,'mlp')[('2026-04-02',615)][0]=='C'
    f['training_label_cutoff']='2026-04-02 10:16:00+05:30'
    with pytest.raises(ValueError):S.candidates(f,'mlp')


def test_metrics_and_explicit_hindsight():
    f=forecasts();f['actual_net']=[100.,-100.];f['actual_gross']=[200.,-50.];f['index_up']=[0.,0.]
    a=S.metrics(f);assert a['samples']==2 and a['auc']==0
    oracle=S.hindsight(f);assert oracle['nontradable'] and oracle['actual_best_option_positive_share']==1
    assert oracle['perfect_endpoint_direction_win_rate']==0


def test_build_preserves_missing_future_feature_rows(tmp_path):
    with pytest.raises(ValueError):D.build(tmp_path,tmp_path/'out')
    d=index(100).reset_index(names='ts')
    for sym in ('NIFTY','INDIAVIX'):d.to_parquet(tmp_path/f'index_1m_{sym}_2026.parquet')
    g=quotes(d.set_index('ts'));g.to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet')
    x,meta,ss,audit=D.build(tmp_path,tmp_path/'out');assert len(x)==len(meta)>0
    # Removing all option prints AFTER the initial decision must not remove that forecast.
    g=g[g.ts<pd.Timestamp('2026-01-02 09:45',tz='Asia/Kolkata')]
    g.to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet')
    xx,mm,_,_=D.build(tmp_path,tmp_path/'out')
    pd.testing.assert_series_equal(x.iloc[0],xx.iloc[0]);assert len(xx)==len(x) and mm.actual_net.isna().all()


def fake_data():
    days=list(pd.bdate_range('2026-01-02','2026-03-30').strftime('%Y-%m-%d'))+['2026-04-02','2026-07-02']
    rows=[];sessions=[]
    for day in days:
        ss=session(day=day);sessions.append(ss)
        for side,net,k in [('C',100.,25000.),('P',-100.,25100.)]:
            rows.append(dict(day=day,available_at=day+' 10:15:00+05:30',minute=615,side=side,strike=k,expiry='2026-07-07',actual_net=net,actual_gross=net+50,actual_fees=25.,actual_friction=25.,actual_exit=635,index_up=1.,momentum_side='C'))
    meta=pd.DataFrame(rows);x=pd.DataFrame(np.random.default_rng(42).normal(size=(len(meta),4)))
    return x,meta,sessions,dict(fingerprint='fake',start=days[0],end=days[-1],sessions=len(sessions),candidate_rows=len(meta))


def test_forecast_outer_dates_and_complete_replay(monkeypatch,tmp_path):
    x,meta,sessions,audit=fake_data();calls=[]
    def fake_fit(x,y,tr,va,ca,kind,path,fingerprint,out):
        assert meta.day.iloc[tr].max()<meta.day.iloc[va].min()
        assert meta.day.iloc[va].max()<meta.day.iloc[ca].min()
        calls.append(kind)
        return np.full(len(y),.7),np.full(len(y),200.)
    monkeypatch.setattr(M,'fit',fake_fit);monkeypatch.setattr(D,'build',lambda lake,out:(x,meta,sessions,audit))
    result=S.run(None,tmp_path)
    assert len(calls)==6 and len(result['results'])==60
    assert not result['approved_for_trading'] and (tmp_path/'forecasts.npz').exists()
    assert len(list(tmp_path.glob('*__x*.csv')))==60
    assert all('bh_q' in r for r in result['results'].values())
    f=forecasts();f['available_at']='2026-04-02 10:16:00+05:30'
    with pytest.raises(ValueError):S.candidates(f,'mlp')
    with pytest.raises(ValueError):S.candidates(f,'baseline_invalid')


def test_report_preserves_sample_and_hindsight_limits(monkeypatch,tmp_path):
    from .report import write
    x,meta,sessions,audit=fake_data();audit.update(observed_labels=len(meta),missing_labels=0,features=list(x.columns))
    monkeypatch.setattr(M,'fit',lambda x,y,tr,va,ca,kind,path,fingerprint,out:(np.full(len(y),.7),np.full(len(y),200.)))
    monkeypatch.setattr(D,'build',lambda lake,out:(x,meta,sessions,audit))
    S.run(None,tmp_path);decision=write(tmp_path)
    assert not decision['qualifying_historical_policies'] and not decision['demonstrated_65pct_strategy']
    text=(tmp_path/'REPORT.md').read_text();assert 'explicitly non-tradable' in text and 'undefined win rate' in text
