"""Rebuild real prospective inputs and compare every saved outer prediction."""
import argparse
import json
import tempfile
from pathlib import Path
import numpy as np
from research.audit_v3.direction import load_forecasts
from research.neural_v2 import pipeline as P
from . import data as D
from .inference import predict


def verify(lake,results):
    with tempfile.TemporaryDirectory(prefix='target2000-verification-') as scratch:
        x,meta,_,audit=D.build(lake,Path(scratch))
    old=json.loads((results/'data_audit.json').read_text())
    assert audit['fingerprint']==old['fingerprint']
    saved=load_forecasts(results/'forecasts.npz');rows=[]
    for fold,(start,end) in enumerate([('2026-04-01','2026-06-30'),('2026-07-01','2026-12-31')],1):
        ids=np.flatnonzero((meta.day>=start)&(meta.day<=end))
        for kind in ('linear','dense','hybrid'):
            actual=predict(x[ids],kind,fold,results/'models')
            reference=saved[(saved.fold==fold)&(saved.model==kind)]
            assert len(reference)==len(ids)
            assert reference.available_at.tolist()==meta.iloc[ids].available_at.tolist()
            differences={}
            for column,value in zip(('p_target','p_win','predicted_net'),actual):
                other=reference[column].to_numpy(float)
                np.testing.assert_allclose(value,other,rtol=1e-5,atol=1e-4)
                differences[column]=float(np.max(np.abs(value-other)))
            rows.append(dict(fold=fold,model=kind,rows=len(ids),max_absolute_error=differences))
    P.dump(results/'inference_verification.json',dict(passed=True,real_input_rebuild=True,checks=rows))
    print('Every saved outer prediction reproduced from published model files',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--lake',type=Path,required=True)
    p.add_argument('--results',type=Path,default=Path(__file__).parent/'results');a=p.parse_args();verify(a.lake,a.results)
