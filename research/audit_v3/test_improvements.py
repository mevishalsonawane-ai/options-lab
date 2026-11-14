import numpy as np
import pandas as pd
import pytest
from .improvements import alternative_exit,direction_at,filter_ledger,subset_summary
from research.neural_v2.test_strategy import session
from research.neural_v2 import strategy as S


def test_runner_does_not_cut_at_target_or_profit_lock():
    bar=(120,150,110,140,100)
    assert alternative_exit(bar,100,115,1,'runner') is None
    assert alternative_exit(bar,100,115,1,'no_target')==(115,'stop_or_profit_lock')
    assert alternative_exit((90,100,80,90,100),100,120,2,'runner')==(85,'initial_stop')
    assert alternative_exit((120,120,120,120,100),100,85,60,'runner')==(120,'maximum_hold')
    assert alternative_exit((100,100,100,100,0),100,85,1,'runner') is None
    with pytest.raises(ValueError):alternative_exit(bar,100,115,1,'unknown')


def forecasts(p=.6,cutoff='2026-01-01 15:15:00+05:30'):
    return pd.DataFrame([dict(instrument='NIFTY',available_at='2026-01-02 10:15:00+05:30',
        training_label_cutoff=cutoff,p_up_15=p)])


def test_forecast_availability_and_provenance():
    f=forecasts()
    assert direction_at(f,'2026-01-02 10:14:00+05:30','NIFTY') is None
    assert direction_at(f,'2026-01-02 10:15:00+05:30','BANKNIFTY') is None
    assert direction_at(f,'2026-01-02 10:21:00+05:30','NIFTY') is None
    assert direction_at(f,'2026-01-02 10:15:00+05:30','NIFTY')==.6
    with pytest.raises(ValueError):direction_at(forecasts(cutoff='2026-01-02 10:16:00+05:30'),'2026-01-02 10:15:00+05:30','NIFTY')


def test_remove_only_keeps_original_exit_and_slots():
    ledger,_=S.replay([session(n=2)])
    kept,reasons=filter_ledger(ledger,forecasts(.6))
    assert len(kept)==2 and list(kept.exit_minute)==list(ledger.exit_minute)
    assert kept.net.sum()==pytest.approx(ledger.net.sum())
    zero,reasons=filter_ledger(ledger,forecasts(.5))
    assert len(zero)==0 and sum(reasons.values())==2
    s=subset_summary(zero,['2026-01-02','2026-01-03'])
    assert s['net']==0 and s['trades']==0
