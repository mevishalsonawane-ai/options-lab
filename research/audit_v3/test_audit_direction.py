from pathlib import Path
import numpy as np
import pandas as pd
import pytest
import torch

from . import direction as D
from .guards import stt_rate,dated_buy_to_settle,section_numbers,evidence_failures


def test_exact_elapsed_labels_do_not_shift_across_gap():
    ix=pd.date_range('2026-01-02 10:00',periods=50,freq='min',tz='Asia/Kolkata')
    close=pd.Series(np.arange(50)+100.,index=ix).drop(ix[5])
    y=D.elapsed_targets(close)
    assert np.isnan(y[0,0])  # 10:05 missing; five rows later would incorrectly use 10:06.
    assert y[0,1]==1 and y[0,2]==1
    flat=D.elapsed_targets(pd.Series(100.,index=ix))
    assert np.isnan(flat).all()


def test_feature_prefix_causality():
    ix=pd.date_range('2026-01-02 09:15',periods=120,freq='min',tz='Asia/Kolkata')
    a=np.arange(120)+1000.
    day=pd.DataFrame(dict(NIFTY=a,BANKNIFTY=a*2,INDIAVIX=20.,NIFTY_high=a+1,NIFTY_low=a-1,BANKNIFTY_high=a*2+1,BANKNIFTY_low=a*2-1),index=ix)
    previous=dict(close=999.,high=1010.,low=990.)
    before=D.features(day,previous,'NIFTY')
    day.loc[ix[80]:,'NIFTY']=99999
    pd.testing.assert_frame_equal(before.iloc[:80],D.features(day,previous,'NIFTY').iloc[:80])


@pytest.mark.parametrize('kind',['mlp','gru'])
def test_masked_neural_fit_and_resume(tmp_path,kind):
    torch.set_num_threads(1)
    x=np.random.default_rng(1).normal(size=(30,30,26)).astype('float32')
    y=np.random.default_rng(2).integers(0,2,size=(30,3)).astype(float);y[:5,1]=np.nan
    tr=np.arange(20);va=np.arange(20,30);p=tmp_path/f'{kind}.pt'
    a,ck=D.fit(x,y,tr,va,kind,p,'data',tmp_path,1,max_epochs=3)
    b,_=D.fit(x,y,tr,va,kind,p,'data',tmp_path,1,max_epochs=3)
    np.testing.assert_array_equal(a,b)
    with pytest.raises(ValueError):D.fit(x,y,tr,va,kind,p,'other',tmp_path,1,max_epochs=3)
    loss=D.masked_loss(torch.zeros((2,3)),torch.tensor([[1.,np.nan,0.],[0.,1.,np.nan]]))
    assert float(loss)==pytest.approx(np.log(2))
    with pytest.raises(ValueError):D.masked_loss(torch.zeros((2,3)),torch.full((2,3),float('nan')))


def test_calibration_is_monotone_and_uses_only_calibration_targets():
    rng=np.random.default_rng(3);z=rng.normal(size=(100,3));y=rng.integers(0,2,size=(100,3)).astype(float)
    a,params=D.calibrate(z,y,np.arange(60));y[60:]=1-y[60:];b,_=D.calibrate(z,y,np.arange(60))
    np.testing.assert_array_equal(a,b)
    for k in range(3):assert np.diff(a[np.argsort(z[:,k]),k]).min()>=0
    result=D.metrics(y[:,0],a[:,0],np.zeros(100),np.repeat(['2026-01-01','2026-01-02'],50),np.full(100,.5))
    assert 0<=result['auc']<=1 and 0<=result['confident_coverage']<=1
    assert result['baseline_log_loss']==pytest.approx(np.log(2))


def test_forecast_archive_preserves_values_without_pickle(tmp_path):
    frame=pd.DataFrame(dict(instrument=['NIFTY','BANKNIFTY'],p_up_15=[.5000000123,.6],actual=[np.nan,1.]))
    D.save_forecasts(frame,tmp_path/'p.npz')
    pd.testing.assert_frame_equal(frame,D.load_forecasts(tmp_path/'p.npz'))


def test_interrupted_direction_resume(tmp_path,monkeypatch):
    torch.set_num_threads(1);rng=np.random.default_rng(42)
    x=rng.normal(size=(30,30,26)).astype('float32');y=rng.integers(0,2,size=(30,3)).astype(float)
    tr=np.arange(20);va=np.arange(20,30);original=D.P.status
    def interrupt(out,**values):
        if values.get('epochs_completed')==2:raise RuntimeError('checkpoint saved, interrupt')
        return original(out,**values)
    monkeypatch.setattr(D.P,'status',interrupt)
    with pytest.raises(RuntimeError):D.fit(x,y,tr,va,'mlp',tmp_path/'resume.pt','data',tmp_path,1,max_epochs=4)
    monkeypatch.setattr(D.P,'status',original)
    a,ck=D.fit(x,y,tr,va,'mlp',tmp_path/'resume.pt','data',tmp_path,1,max_epochs=4)
    b,other=D.fit(x,y,tr,va,'mlp',tmp_path/'fresh.pt','data',tmp_path,1,max_epochs=4)
    np.testing.assert_array_equal(a,b);assert ck['history']==other['history']


def test_cost_regression_and_evidence_guards():
    assert stt_rate('2026-03-31','exercise')==.00125
    assert stt_rate('2026-04-01','exercise')==.0015
    before=dated_buy_to_settle(settlement_day='2026-03-31',premium=100,lot_size=65,regime='quoted',intrinsic=100)
    after=dated_buy_to_settle(settlement_day='2026-04-01',premium=100,lot_size=65,regime='quoted',intrinsic=100)
    assert after.stt-before.stt==pytest.approx(1.625)
    assert after.stt==pytest.approx(9.75)
    with pytest.raises(ValueError):dated_buy_to_settle(settlement_day='2026-04-01',premium=100,lot_size=-1,regime='quoted')
    with pytest.raises(ValueError):stt_rate('2021-01-01','sale')
    with pytest.raises(ValueError):stt_rate('2027-01-01','sale')
    with pytest.raises(ValueError):stt_rate('2026-01-01','other')
    doc='# A\n70% wins\n# B\n30% wins\n'
    assert '70%' not in section_numbers(doc,'B')
    with pytest.raises(ValueError):section_numbers(doc,'missing')
    assert 'fresh_unseen' in evidence_failures(dict(net=10000,stress_net=5000,q=.01,drawdown=1000))
    assert 'net_profit' in evidence_failures(dict(net=float('nan')))
    assert '-70%' in section_numbers('# Loss\n\u221270% return','Loss')


def test_small_end_to_end_dataset(tmp_path):
    for sym in ('NIFTY','BANKNIFTY','INDIAVIX'):
        parts=[]
        for day in ['2025-12-30','2025-12-31','2026-01-01','2026-01-02','2026-01-03']:
            ix=pd.date_range(day+' 09:15',periods=200,freq='min',tz='Asia/Kolkata')
            v=np.arange(200)+1000.
            parts.append(pd.DataFrame(dict(ts=ix,close=v,high=v+1,low=v-1)))
        pd.concat(parts).to_parquet(tmp_path/f'index_1m_{sym}_2026.parquet')
    x,y,m,a=D.build(tmp_path,tmp_path)
    assert len(x)>0 and x.shape[1]==30 and y.shape[1]==3
    assert m.available_at.is_monotonic_increasing
    assert set(m.instrument)=={'NIFTY','BANKNIFTY'}
    assert x.shape[-1]==len(a['features'])


def test_corpus_inventory_counts_and_compressed_provenance(tmp_path,monkeypatch):
    import gzip,json,yaml
    from . import corpus
    cards=tmp_path/'research/knowledge/strategies.yaml';cards.parent.mkdir(parents=True)
    cards.write_text(yaml.safe_dump([dict(id='orb',name='ORB',verdict='negative',facts=[])]))
    entry=tmp_path/'research/experiments/entries/x.yaml';entry.parent.mkdir(parents=True)
    entry.write_text('id: x\nverdict: rejected\nfamily: ml\nvariants_tried: 7\n')
    code=tmp_path/'research/test.py';code.write_text('print(1)\n')
    paths=[str(p.relative_to(tmp_path)) for p in [cards,entry,code]]+['research/model.pt']
    monkeypatch.setattr(corpus.subprocess,'check_output',lambda *a,**kw:'\n'.join(paths))
    r=corpus.inspect_corpus(tmp_path,tmp_path/'out')
    assert r['tracked_research_files']==4 and r['text_files_read']==3
    assert r['experiment_entries']==1 and r['sum_logged_variant_counts_not_unique']==7
    inventory=json.loads(gzip.decompress((tmp_path/'out/inventory.json.gz').read_bytes()))
    assert len(inventory)==4 and inventory[-1]['read'] is False
