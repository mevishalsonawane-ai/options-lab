from datetime import date
import json

import numpy as np
import pandas as pd
import pytest
import torch
from . import pipeline

from .pipeline import (Network, bh, build, dump, expiry, fit, fixed_path,
                       net_trade, objective, simulate, status, summarize, underlying_features)


def test_expiry_observed_and_holiday():
    assert expiry(date(2026,4,10),{date(2026,4,13)}) == (date(2026,4,13),'observed')
    assert expiry(date(2026,10,19),set())[0] == date(2026,10,19)
    assert expiry(date(2026,10,21),set())[0] == date(2026,10,27)
    with pytest.raises(ValueError): expiry(date(2027,1,1),set())


def test_costs_date_lot_and_stress():
    a=net_trade(100,110,'2026-03-31','2026-04-07')
    b=net_trade(100,110,'2026-04-01','2026-04-07')
    assert b[0]<a[0] and b[2]>a[2]
    assert a[1]==650 and a[3]>0
    assert a[0] == pytest.approx(a[1]-a[2]-a[3])
    assert net_trade(100,110,'2026-04-01','2026-04-07',2)[0]<b[0]
    assert net_trade(100,110,'2025-12-01','2025-12-02')[1]==750
    assert net_trade(100,110,'2026-03-01','2026-03-03')[2]>net_trade(100,110,'2026-02-28','2026-03-03')[2]
    for b,s in [(0,1),(1,-1),(float('nan'),10)]:
        with pytest.raises(ValueError): net_trade(b,s,'2026-01-01','2026-01-06')


def test_fixed_strike_no_rolling_substitution():
    book={(601+j,25000.,'C'):(100.,100) for j in range(16)}
    assert fixed_path(book,600,25000,'C')==(100.,100.,616)
    book[(605,25000.,'C')]=(80.,100)
    assert fixed_path(book,600,25000,'C')==(100.,80.,605)
    del book[(604,25000.,'C')]
    book[(604,25050.,'C')]=(200.,100)
    assert fixed_path(book,600,25000,'C') is None
    assert fixed_path({},600,25000,'C') is None


def test_stale_price_and_target():
    book={(601+j,25000.,'P'):(100.,100) for j in range(16)}
    book[(602,25000.,'P')]=(135.,100)
    assert fixed_path(book,600,25000,'P')[1:]==(135.,602)
    book[(602,25000.,'P')]=(135.,0)
    assert fixed_path(book,600,25000,'P') is None


def test_features_are_prefix_causal():
    ix=pd.date_range('2026-01-01 09:15',periods=100,freq='min',tz='Asia/Kolkata')
    d=pd.DataFrame({'NIFTY':np.arange(100)+100.},index=ix)
    a=underlying_features(d);d.iloc[80:]=99999
    b=underlying_features(d)
    pd.testing.assert_frame_equal(a.iloc[:80],b.iloc[:80])


@pytest.mark.parametrize('kind',['mlp','lstm','gru'])
def test_network_and_loss(kind):
    torch.manual_seed(42);m=Network(25,kind)
    out=m(torch.randn(4,30,25))
    assert [x.shape for x in out]==[torch.Size([4,3]),torch.Size([4,2]),torch.Size([4,3])]
    loss=objective(out,torch.zeros(4,dtype=torch.long),torch.zeros(4,2),torch.zeros(4,dtype=torch.long))
    assert torch.isfinite(loss)
    loss.backward()


def test_overlap_and_statistics():
    m=pd.DataFrame([dict(ts=f'2026-01-01 10:{v:02d}:00+05:30',day='2026-01-01',expiry='2026-01-06',
        ce_strike=25000,pe_strike=25000,ce_exit=620,pe_exit=620,momentum=.002) for v in (0,5,25)])
    p=np.array([[-100,100],[-100,100],[50,100]])
    t=simulate(m,p,p,None,baseline='momentum')
    assert len(t)==2
    stats=summarize(t,['2026-01-01','2026-01-02'])
    assert stats['net']==-50 and stats['max_drawdown']==100 and stats['trades']==2
    zero=summarize(t.iloc[:0],['2026-01-01','2026-01-02'])
    assert zero['p_value']==1 and zero['max_drawdown']==0
    pro=np.tile([.9,.05,.05],(3,1))
    assert len(simulate(m,p,p,pro,np.zeros((3,2))))==0
    assert len(simulate(m,p,p,None,baseline='contrarian'))==2


def test_bh_and_status(tmp_path):
    assert np.all(bh([.01,.04,1])>=np.array([.01,.04,1]))
    dump(tmp_path/'x.json',{'ok':1});assert json.loads((tmp_path/'x.json').read_text())=={'ok':1}
    status(tmp_path,stage='training',errors=['<error>'])
    assert '&lt;error&gt;' in (tmp_path/'dashboard.html').read_text()
    assert json.loads((tmp_path/'progress.json').read_text())['stage']=='training'
    with pytest.raises(ValueError): build(tmp_path,tmp_path)


def test_fit_resume_and_mismatch(tmp_path):
    torch.set_num_threads(1)
    x=np.random.default_rng(1).normal(size=(16,30,25)).astype('float32')
    y=np.zeros(16,dtype='int64');r=y.copy();p=np.zeros((16,2))
    path=tmp_path/'last.pt';tr=np.arange(10);va=np.arange(10,16)
    a=fit(x,y,r,p,tr,va,'mlp',.001,path,'fingerprint',tmp_path,1)
    b=fit(x,y,r,p,tr,va,'mlp',.001,path,'fingerprint',tmp_path,1)
    np.testing.assert_allclose(a[0],b[0])
    with pytest.raises(ValueError): fit(x,y,r,p,tr,va,'mlp',.001,path,'changed',tmp_path,1)
    assert len(a[3])==6


def test_interrupted_epoch_resume_exact(tmp_path,monkeypatch):
    torch.set_num_threads(1)
    x=np.random.default_rng(1).normal(size=(16,30,25)).astype('float32')
    y=np.zeros(16,dtype='int64');p=np.zeros((16,2));tr=np.arange(10);va=np.arange(10,16)
    real_status=pipeline.status
    def crash(out,**values):
        if values.get('epochs_completed')==3:
            raise RuntimeError('simulated interruption after checkpoint')
        return real_status(out,**values)
    monkeypatch.setattr(pipeline,'status',crash)
    with pytest.raises(RuntimeError):
        fit(x,y,y,p,tr,va,'mlp',.001,tmp_path/'resume.pt','data',tmp_path,1)
    monkeypatch.setattr(pipeline,'status',real_status)
    resumed=fit(x,y,y,p,tr,va,'mlp',.001,tmp_path/'resume.pt','data',tmp_path,1)
    fresh=fit(x,y,y,p,tr,va,'mlp',.001,tmp_path/'fresh.pt','data',tmp_path,1)
    np.testing.assert_array_equal(resumed[0],fresh[0])
    assert resumed[3]==fresh[3]


def test_build_contract_coverage_and_causality(tmp_path):
    ix=pd.date_range('2026-01-02 09:15',periods=120,freq='min',tz='Asia/Kolkata')
    for sym in ('NIFTY','BANKNIFTY','INDIAVIX'):
        price=np.arange(120)*.01+1000
        d=pd.DataFrame(dict(ts=ix,open=price,high=price+1,low=price-1,close=price,volume=100))
        d.to_parquet(tmp_path/f'index_1m_{sym}_2026.parquet')
        d.iloc[:0].to_parquet(tmp_path/f'index_1m_{sym}_2025.parquet')
    o=pd.DataFrame([dict(ts=t,offset=0,side=s,open=10.,high=11.,low=9.,close=10.,iv=20.,
        volume=100,strike=1000.,oi=100,spot=1000.) for t in ix for s in ('C','P')])
    o.to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet');o.iloc[:0].to_parquet(tmp_path/'opt_NIFTY_WEEK_2025.parquet')
    before=build(tmp_path,tmp_path)
    assert len(before[0])>0 and before[0].shape[1:]==(30,25)
    # Future context cannot change features at an earlier signal.
    o.loc[o.ts>=ix[80],'oi']=999
    o=o[~((o.ts==ix[80])&(o.side=='C'))]
    o.to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet')
    after=build(tmp_path,tmp_path)
    np.testing.assert_array_equal(before[0][0],after[0][0])
    assert len(after[0])<len(before[0])
    duplicate=o.iloc[[0]].copy();duplicate['open']=99
    pd.concat([o,duplicate]).to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet')
    with pytest.raises(ValueError,match='Conflicting contract'):
        build(tmp_path,tmp_path)
