import numpy as np
import pandas as pd
import pytest
from research.neural_v2 import strategy as S
from research.target2000 import execution as E,data as D,models as M,study as T


def test_whole_lot_cost_barriers_and_tick_rounding():
    for day,exp,qty in [('2025-11-03','2025-11-04',75),('2026-05-04','2026-05-05',65)]:
        assert E.quantity(exp)==qty
        for entry in (30.,100.,250.,600.):
            stop,target=E.barriers(entry,day,exp)
            assert np.isclose(stop/.05,round(stop/.05)) and np.isclose(target/.05,round(target/.05))
            assert S.trade_pnl(entry,target,day,exp)[0]>=2000-1e-6
            assert S.trade_pnl(entry,target-.05,day,exp)[0]<2000+1e-6
            assert S.trade_pnl(entry,target,day,exp,2)[0]<2000
            if stop>.05:assert -1504<=S.trade_pnl(entry,stop,day,exp)[0]<=-1500+1e-6


def test_stop_first_gap_and_time_rules():
    assert E.exit_fill((100,140,60,120,1),80,130,0)==(80,'initial_stop')
    assert E.exit_fill((70,140,60,120,1),80,130,0)==(70,'gap_stop')
    assert E.exit_fill((110,140,90,120,1),80,130,30)==(110,'time_exit')
    assert E.exit_fill((100,140,90,120,0),80,130,0) is None


def test_outcome_account_parity_and_stress_barriers():
    book={(600,24000.,'C'):(100,101,99,100,1),(601,24000.,'C'):(100,150,99,135,1)}
    session=dict(day='2026-05-04',expiry='2026-05-05',book=book)
    label=E.outcome(book,600,24000.,'C',session['day'],session['expiry'])
    decisions={(session['day'],600):('C',24000.)}
    d,s,_=E.replay([session],decisions)
    stressed,ss,_=E.replay([session],decisions,2.)
    assert len(d)==1 and d.iloc[0].lots==1 and d.iloc[0].quantity==65
    assert d.iloc[0].net==pytest.approx(label['net'])
    assert d.iloc[0].exit==stressed.iloc[0].exit
    assert d.iloc[0].net>stressed.iloc[0].net
    assert s['target_2000_reaches']==1 and ss['target_2000_reaches']==0
    book.pop((601,24000.,'C'));book[601,24050.,'C']=(100,150,99,135,1)
    assert E.outcome(book,600,24000.,'C',session['day'],session['expiry']) is None
    with pytest.raises(ValueError,match='terminal'):E.replay([session],decisions)


def test_cash_and_daily_cap():
    day='2026-05-04';book={}
    for m in [600,610,620]:book[m,24000.,'C']=(100,150,99,135,1)
    session=dict(day=day,expiry='2026-05-05',book=book)
    d,s,_=E.replay([session],{(day,m):('C',24000.) for m in [600,610,620]})
    assert len(d)==2 and s['rejections']['daily_trade_cap']==1
    session['book']={ (600,24000.,'C'):(2000,2100,1999,2050,1)}
    d,s,_=E.replay([session],{(day,600):('C',24000.)})
    assert d.empty and s['rejections']['insufficient_cash']==1


def test_sequences_are_same_session_side_and_consecutive():
    rows=[dict(day=day,minute=m,side=s,actual_net=np.nan) for day in ['2026-05-04','2026-05-05'] for m in range(600,635,5) for s in ['C','P']]
    meta=pd.DataFrame(rows);ids,end=D.sequence_indices(meta)
    assert len(end)==8
    for seq in ids:
        g=meta.iloc[seq];assert g.day.nunique()==g.side.nunique()==1
        assert (np.diff(g.minute)==5).all()
    meta.actual_net=999999
    aa,bb=D.sequence_indices(meta);np.testing.assert_array_equal(ids,aa)
    broken=meta[~((meta.day=='2026-05-04')&(meta.minute==610))].reset_index(drop=True)
    _,end=D.sequence_indices(broken);assert not (broken.iloc[end].day=='2026-05-04').any()


def test_train_only_scaling_and_neural_checkpoint_resume(tmp_path):
    rng=np.random.default_rng(9);x=rng.normal(size=(120,6,4));x[0,0,0]=np.nan
    tr=np.arange(80);va=np.arange(80,100);y=np.where(x[:,-1,1]>0,2100.,-1500.)
    z,params=M.scale(x,tr);changed=x.copy();changed[80:]=1e6
    zz,pp=M.scale(changed,tr);np.testing.assert_array_equal(z[:80],zz[:80])
    for key in params:np.testing.assert_array_equal(params[key],pp[key])
    for kind in ['dense','hybrid']:
        path=tmp_path/(kind+'.pt')
        a,h=M.train(z,y,tr,va,kind,42,path,'fixed',tmp_path,max_epochs=2)
        b,hh=M.train(z,y,tr,va,kind,42,path,'fixed',tmp_path,max_epochs=2)
        np.testing.assert_array_equal(a,b);assert h['epochs']==hh['epochs']==2
        with pytest.raises(ValueError,match='mismatch'):M.train(z,y,tr,va,kind,42,path,'changed',tmp_path,max_epochs=2)


def test_candidates_ignore_outcomes_and_reject_future_training():
    f=pd.DataFrame(dict(day=['2026-05-04']*2,minute=[615]*2,available_at=['2026-05-04 10:15:00+05:30']*2,
        training_label_cutoff=['2026-03-30 15:30:00+05:30']*2,side=['C','P'],strike=[24000.,24050.],
        p_win=[.7,.8],p_target=[.3,.4],predicted_net=[500.,400.],actual_net=[-1500.,2000.],momentum_side=['P','P']))
    assert T.decisions(f,.65,250)=={('2026-05-04',615):('C',24000.)}
    f.actual_net=[2000.,-1500.]
    assert T.decisions(f,.65,250)=={('2026-05-04',615):('C',24000.)}
    assert T.decisions(f,.65,250,momentum=True)=={('2026-05-04',615):('P',24050.)}
    f.training_label_cutoff='2026-05-04 10:16:00+05:30'
    with pytest.raises(ValueError):T.decisions(f,.65,250)


def test_multiple_completed_bars_prefix_and_gap():
    from research.target2000.bars import confirmations,filter_decisions
    ix=pd.date_range('2026-05-04 09:15',periods=40,freq='min',tz='Asia/Kolkata')
    price=np.arange(40)+100.
    d=pd.DataFrame(dict(open=price,high=price+2,low=price-1,close=price+1),index=ix)
    for minutes in (1,5):
        for n in (2,3):
            a=confirmations(d,minutes,n);cut=d.iloc[:22];b=confirmations(cut,minutes,n)
            available=cut.index[-1]+pd.Timedelta(minutes=1)
            pd.testing.assert_series_equal(a[a.index<=available],b[b.index<=available])
            assert a.iloc[-1]==1
    a=confirmations(d.drop(ix[8]),5,2)
    assert a.loc['2026-05-04 09:25']==0
    assert a.loc['2026-05-04 09:30']==0
    d.loc[ix[20],'close']=d.loc[ix[20],'open']
    assert confirmations(d,1,2).loc[ix[21]]==0
    choices={('2026-05-04',590):('C',24000.),('2026-05-04',595):('P',24000.)}
    kept=filter_decisions(choices,confirmations(d,5,2))
    assert set(kept).issubset(choices) and ('2026-05-04',595) not in kept
