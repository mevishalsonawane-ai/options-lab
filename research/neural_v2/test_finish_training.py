import numpy as np
import pytest
import torch

from . import finish_training as F


@pytest.fixture
def data():
    torch.set_num_threads(1)
    rng=np.random.default_rng(7)
    x=rng.normal(size=(40,30,25)).astype('float32')
    y=np.zeros(40,dtype='int64');pnl=np.zeros((40,2))
    dates=np.repeat([f'2026-01-{d:02d}' for d in range(1,11)],4)
    features=[f'f{i}' for i in range(25)]
    return x,y,pnl,dates,features


def train(data,kind,path,out,**kw):
    x,y,pnl,dates,features=data
    return F.train_complete(x,y,y,pnl,dates,features,kind,path,'data',out,**kw)


@pytest.mark.parametrize('kind',['mlp','lstm','gru'])
def test_all_data_refit_and_completed_resume(data,tmp_path,kind):
    ck=train(data,kind,tmp_path/f'{kind}.pt',tmp_path,max_epochs=3)
    assert ck['phase']=='completed' and ck['training_completed']
    assert ck['all_rows']==40 and ck['train_rows']==32 and ck['validation_rows']==8
    assert ck['train_end']<ck['validation_start']
    assert len(ck['refit_history'])==ck['best_epoch']
    assert ck['best_loss']==min(v['validation_loss'] for v in ck['selection_history'])
    np.testing.assert_array_equal(ck['mean'],data[0].mean(axis=(0,1)))
    again=train(data,kind,tmp_path/f'{kind}.pt',tmp_path,max_epochs=3)
    assert again['completed_at']==ck['completed_at']
    with pytest.raises(ValueError,match='mismatch'):
        train(data,kind,tmp_path/f'{kind}.pt',tmp_path,max_epochs=4)


@pytest.mark.parametrize('stage',['duration_selection','all_data_refit'])
def test_interrupted_resume_matches_uninterrupted(data,tmp_path,monkeypatch,stage):
    original=F.P.status
    def interrupt(out,**values):
        if values.get('stage')==stage and values.get('epochs_completed')==1:
            raise RuntimeError('simulated failure after atomic checkpoint')
        return original(out,**values)
    monkeypatch.setattr(F.P,'status',interrupt)
    with pytest.raises(RuntimeError):
        train(data,'mlp',tmp_path/'resume.pt',tmp_path,max_epochs=3)
    monkeypatch.setattr(F.P,'status',original)
    resumed=train(data,'mlp',tmp_path/'resume.pt',tmp_path,max_epochs=3)
    fresh=train(data,'mlp',tmp_path/'fresh.pt',tmp_path,max_epochs=3)
    assert resumed['selection_history']==fresh['selection_history']
    assert resumed['refit_history']==fresh['refit_history']
    for name in fresh['model']:
        assert torch.equal(resumed['model'][name],fresh['model'][name])


def test_early_stop_and_input_rejection(data,tmp_path):
    ck=train(data,'mlp',tmp_path/'stop.pt',tmp_path,max_epochs=8,patience=1,min_delta=1e6)
    assert ck['selection_epochs_completed']==2 and ck['stop_reason']=='early_stopping'
    x,y,pnl,dates,names=data
    with pytest.raises(ValueError):
        F.train_complete(x[:0],y[:0],y[:0],pnl[:0],dates[:0],names,'mlp',tmp_path/'empty.pt','data',tmp_path)
    with pytest.raises(ValueError): train(data,'invalid',tmp_path/'bad.pt',tmp_path)
    with pytest.raises(ValueError):
        F.train_complete(x,y,y,pnl,dates[::-1],names,'mlp',tmp_path/'bad.pt','data',tmp_path)


def test_ensemble_schema_and_finite_inference(data,tmp_path):
    models=[train(data,k,tmp_path/f'{k}.pt',tmp_path,max_epochs=2) for k in ['mlp','lstm','gru']]
    result=F.predict(models,data[0][:3],data[4])
    np.testing.assert_allclose(np.array(result['probabilities']).sum(1),1,atol=1e-6)
    assert set(result['actions']).issubset({0,1,2})
    for inputs,names in [(data[0][:3],data[4][::-1]),(data[0][:3,:20],data[4]),(data[0][:0],data[4])]:
        with pytest.raises(ValueError): F.predict(models,inputs,names)
    with pytest.raises(ValueError): F.predict(models[:1],data[0][:3],data[4])
    mixed=[dict(m) for m in models];mixed[0]['fingerprint']='different data'
    with pytest.raises(ValueError): F.predict(mixed,data[0][:3],data[4])
    invalid=data[0][:1].copy();invalid[0,0,0]=np.nan
    with pytest.raises(ValueError): F.predict(models,invalid,data[4])
