import copy
import numpy as np
import pandas as pd
import pytest

from . import features as F,engine as E,robustness as R,study as S
from research.neural_v2 import strategy as original
from research.neural_v2.test_strategy import session


def index(n=100,day='2026-01-02'):
    ix=pd.date_range(day+' 09:15',periods=n,freq='min',tz='Asia/Kolkata');v=25000+np.arange(n)*.5
    return pd.DataFrame(dict(open=v,high=v+2,low=v-2,close=v+1,INDIAVIX=15.,volume=100.),index=ix)


def quotes(d):
    rows=[]
    for j,t in enumerate(d.index):
        for k in range(24750,25401,50):
            for side in ('C','P'):
                atm=np.floor(d.close.loc[t]/50+.5)*50;offset=int((k-atm)/50)
                rows.append(dict(ts=t,strike=float(k),side=side,offset=offset,open=100.,high=102.,low=99.,close=101.,volume=100+j,oi=1000+j*(2 if side=='P' else 1),spot=d.close.loc[t],iv=15.))
    return pd.DataFrame(rows)


def test_default_engine_parity_and_knobs():
    ss=[session(),session(day='2026-01-05',n=2)]
    a,x=original.replay(ss);b,y=E.replay(ss);y.pop('_state')
    pd.testing.assert_frame_equal(a,b);assert x==y
    s=session();s['book'][(615,25000.,'C')]=(100.,112.,86.,110.,100)
    t,_=E.replay([s],stop_pct=.12,target_pct=.10);assert t.iloc[0].exit==88.
    t,_=E.replay([s],stop_pct=.18,target_pct=.10);assert t.iloc[0].exit==pytest.approx(110.)
    assert E.exit_fill((100,140,80,100,1),100,85,1)==(85,'stop_or_profit_lock')
    for stop,target in [(0,.3),(.15,0),(float('nan'),.3)]:
        with pytest.raises(ValueError):E.replay(ss,stop_pct=stop,target_pct=target)


def test_carried_cash_risk_state_matches_whole_replay():
    ss=[session(),session(day='2026-04-02',n=2)]
    all_t,all_s=E.replay(ss)
    first,a=E.replay(ss[:1]);second,b=E.replay(ss[1:],state=a['_state'])
    pd.testing.assert_frame_equal(all_t,pd.concat([first,second],ignore_index=True))
    assert all_s['_state']==b['_state'] and b['starting_capital']==a['ending_capital']
    with pytest.raises(ValueError):E.replay(ss,state={'cash':-1})


def test_delta_values_and_unavailable():
    c=F.delta(100,100,.2,.1,'C');p=F.delta(100,100,.2,.1,'P')
    assert 0<c<1 and -1<p<0 and c-p==pytest.approx(1)
    for iv,t in [(0,.1),(.2,0),(.2,-.1)]:assert np.isnan(F.delta(100,100,iv,t,'C'))


def test_chain_fixed_strike_elapsed_and_prefix():
    d=index();g=quotes(d);a=F.chain_facts(g,d,'2026-01-06')
    prefix=F.chain_facts(g[g.ts<=d.index[59]],d.iloc[:60],'2026-01-06')
    pd.testing.assert_frame_equal(prefix,a.iloc[:60])
    assert a.iloc[:5].pcr.isna().all() and a.iloc[30].pcr>1
    assert a.iloc[30].C_signed3>0 and a.iloc[30].C_rvol>1
    missing=g[~((g.ts==d.index[25])&(g.strike==25000)&(g.side=='C'))]
    b=F.chain_facts(missing,d,'2026-01-06')
    # Missing exact five-minute comparison removes that K from the OI-change basket.
    assert b.iloc[30].C_count==a.iloc[30].C_count-1
    with pytest.raises(ValueError):F.chain_facts(pd.concat([g,g.iloc[:1]]),d,'2026-01-06')
    g.loc[g.iv.index,'iv']=0
    assert F.chain_facts(g,d,'2026-01-06').C_delta.isna().all()


def test_futures_profile_prefix_and_gaps():
    d=index();a=F.futures_facts(d);pd.testing.assert_frame_equal(a.iloc[:60],F.futures_facts(d.iloc[:60]))
    assert a.iloc[30].f_rvol==1 and a.iloc[30].f_vwap>25000
    assert (a.f_poc.dropna()%25==12.5).all()
    gap=F.futures_facts(d.drop(d.index[25]));assert pd.isna(gap.loc[d.index[30],'f_rvol'])
    with pytest.raises(ValueError):F.futures_facts(pd.concat([d,d.iloc[:1]]))


def test_raw_levels_causal_session_time_and_gap():
    d=index();a=F.raw_signals(d,None)
    pd.testing.assert_frame_equal(a.iloc[:60],F.raw_signals(d.iloc[:60],None))
    assert not a.iloc[:29].to_numpy().any()
    d=index();d.loc[d.index[40],['open','high','low','close']]=[25020,25050,25000,25045]
    assert F.raw_signals(d,None).loc[d.index[40],'breakout']==1
    d.loc[d.index[40],['open','high','low','close']]=[25020,25022,24970,25021]
    assert F.raw_signals(d,None).loc[d.index[40],'sweep']==1
    assert F.raw_signals(d,{'low':25000,'high':26000}).loc[d.index[40],'previous_reclaim']==1
    gap=F.raw_signals(d.drop(d.index[35]),None)
    assert not gap.loc[d.index[40]].any()
    with pytest.raises(ValueError):F.raw_signals(d,None,1)


def test_gates_missing_proxy_and_context():
    d=index();c=F.chain_facts(quotes(d),d,'2026-01-06');f=F.futures_facts(d)
    raw=pd.Series(1,index=d.index);assert F.gate(d,c,f,raw,'plain').eq(1).all()
    c['C_rvol']=2;c['C_delta']=.6;c['C_signed3']=1
    assert F.gate(d,c,f,raw,'chain').iloc[40]==1
    assert F.gate(d,c,f,raw,'greek_proxy').iloc[40]==1
    assert F.gate(d,c,f,raw,'all').iloc[40]==0  # no futures volume spike
    c['C_delta']=np.nan;assert not F.gate(d,c,f,raw,'greek_proxy').any()
    with pytest.raises(ValueError):F.gate(d,c,f,raw,'invalid')


def test_uncertainty_sequence_and_selection():
    assert R.wilson(0,0)==[None,None]
    assert R.wilson(65,100)[0]<.65 and R.wilson(90,100)[0]>.65
    with pytest.raises(ValueError):R.wilson(2,1)
    assert R.drawdown([10,-5,-8,20])==13
    with pytest.raises(ValueError):R.drawdown([np.nan])
    ix=R.block_indices(20,np.random.default_rng(42));assert len(ix)==20 and ((ix>=0)&(ix<20)).all()
    for i in range(0,20,5):assert (np.diff(ix[i:i+5])==1).all()
    with pytest.raises(ValueError):R.block_indices(0,np.random.default_rng(42))
    ledger=pd.DataFrame({'day':['2026-01-01','2026-01-01','2026-01-03'],'net':[10.,-20.,30.]});days=['2026-01-01','2026-01-02','2026-01-03']
    a=R.monte_carlo(ledger,days,100);b=R.monte_carlo(ledger,days,100)
    assert a==b and a['shuffled_ending_net_constant']==20 and a['shuffled_dd95']>=20
    assert 0<R.block_pvalue(ledger,days,100)<=1
    assert R.monte_carlo(ledger.iloc[:0],[],5)['actual_net']==0
    with pytest.raises(ValueError):R.monte_carlo(ledger,days,0)
    passed=dict(trades=100,win_rate=.7,net=1000.,profit_factor=1.2,max_drawdown=2000,worst_month=-100)
    failed=dict(passed,net=-100)
    assert R.select({'bad':(failed,failed)}) is None
    assert R.select({'good':(passed,passed),'bad':(failed,failed)})=='good'


def fake_sessions():
    ss=[]
    for day in ['2026-01-02','2026-01-05','2026-04-02','2026-04-03','2026-07-02','2026-07-03']:
        s=session(day=day);s.pop('candidates');s.update(picks={(615,'C'):25000.},minutes=np.array([615]),eligible_expiry=True,
            raw={(lb,f):np.array([1],np.int8) for lb in (16,20,24) for f in F.FAMILIES},
            masks={(c,side):np.array([True]) for c in F.CONTEXTS for side in (1,2)})
        ss.append(s)
    return ss


def test_adapt_evaluate_and_run(monkeypatch,tmp_path):
    ss=fake_sessions();rule=('breakout','plain',.3,20,.15)
    assert S.rule_name(rule)=='breakout__plain__t0.30__l20__s0.15'
    assert S.adapt(ss,rule)[0]['candidates'][615]==('C',25000.)
    ss[0]['eligible_expiry']=False;assert not S.adapt(ss,rule)[0]['candidates']
    t,s=S.evaluate(ss,rule,1.);assert s['trades']==5 and len(s['wilson95'])==2
    monkeypatch.setattr(F,'FAMILIES',('breakout',));monkeypatch.setattr(F,'CONTEXTS',('plain',))
    monkeypatch.setattr(S,'build',lambda lake,out:(ss,{'sessions':6}))
    result=S.run(None,tmp_path)
    assert result['primary_rule_count']==10 and len(result['full'])==20
    assert all(x['selected']=='NO_TRADE' for x in result['walk_forward']['decisions'])
    assert not result['demonstrated_65pct_strategy'] and (tmp_path/'results.json').exists()
    assert all('bh_q' in x for x in result['full'].values())


def test_build_data_contracts_and_missing_inputs(tmp_path):
    with pytest.raises(ValueError,match='Missing index'):S.build(tmp_path,tmp_path/'out')
    d=index(375).reset_index(names='ts')
    for sym in ('NIFTY','BANKNIFTY','INDIAVIX'):d.to_parquet(tmp_path/f'index_1m_{sym}_2026.parquet')
    with pytest.raises(ValueError,match='Missing options'):S.build(tmp_path,tmp_path/'out')
    g=quotes(d.set_index('ts'));g.to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet')
    with pytest.raises(ValueError,match='Missing observed futures'):S.build(tmp_path,tmp_path/'out')
    f=d.copy();f['symbol']='NIFTY';f['expiry']='2026-01-27';f.to_parquet(tmp_path/'fut_1m_live_index.parquet')
    sessions,audit=S.build(tmp_path,tmp_path/'out')
    assert len(sessions)==1 and audit['futures_sessions']==1
    assert sessions[0]['eligible_expiry'] and sessions[0]['picks']
    assert len(sessions[0]['raw'])==12 and len(sessions[0]['masks'])==8
    pd.concat([g,g.iloc[:1]]).to_parquet(tmp_path/'opt_NIFTY_WEEK_2026.parquet')
    with pytest.raises(ValueError,match='Duplicate quotes'):S.build(tmp_path,tmp_path/'out')


def test_report_and_plot_preserve_failed_verdict(monkeypatch,tmp_path):
    from .report import write_report,plots
    ss=fake_sessions();monkeypatch.setattr(F,'FAMILIES',('breakout',));monkeypatch.setattr(F,'CONTEXTS',('plain',))
    monkeypatch.setattr(S,'build',lambda lake,out:(ss,{'sessions':6}))
    result=S.run(None,tmp_path);summary=write_report(result,tmp_path);plots(result,tmp_path)
    assert summary['qualified_point65']==0
    assert 'No validated 65%' in (tmp_path/'REPORT.md').read_text()
    assert (tmp_path/'sensitivity.png').stat().st_size>1000 and (tmp_path/'sequence_risk.png').stat().st_size>1000


def test_walk_forward_locked_rule_carries_state(monkeypatch,tmp_path):
    ss=fake_sessions();rule=('breakout','plain',.3,20,.15)
    # Force a chosen rule only to exercise the execution router/state handoff.
    # Eligibility logic is separately tested; production gates are unchanged.
    monkeypatch.setattr(R,'select',lambda scores:next(iter(scores)))
    result=S.walk_forward(ss,[rule],tmp_path)
    first=result['fold_results']['2026-04-01_1.0'];second=result['fold_results']['2026-07-01_1.0']
    assert first['ending_capital']==second['starting_capital']
    assert result['decisions'][0]['training_end']<'2026-04-01'
    assert result['decisions'][1]['training_end']<'2026-07-01'
    ledger=pd.read_csv(tmp_path/'walk_forward_1.0.csv')
    assert len(ledger)==4 and result['normal_net']==pytest.approx(ledger.net.sum())
