"""Offline NIFTY development experiment. Never imports a broker or sends orders."""
from __future__ import annotations

import argparse
import hashlib
import html
import inspect
import json
import random
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from sklearn.metrics import balanced_accuracy_score
from torch import nn

HERE = Path(__file__).resolve().parent
HOLIDAYS_2026 = set(pd.to_datetime([
    '2026-01-26', '2026-03-03', '2026-03-26', '2026-03-31', '2026-04-03',
    '2026-04-14', '2026-05-01', '2026-05-28', '2026-06-26', '2026-09-14',
    '2026-10-02', '2026-10-20', '2026-11-10', '2026-11-24', '2026-12-25',
]).date)


def dump(path, obj):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(obj, indent=2, allow_nan=False, default=str) + '\n')
    tmp.replace(path)


def status(out, **values):
    p = out / 'progress.json'
    old = json.loads(p.read_text()) if p.exists() else {}
    old.update(values, updated_at=pd.Timestamp.now(tz='UTC').isoformat())
    dump(p, old)
    with (out / 'events.jsonl').open('a') as f:
        f.write(json.dumps(old) + '\n')
    rows = ''.join('<tr><th>' + html.escape(k) + '</th><td>' + html.escape(str(v)) + '</td></tr>'
                   for k, v in old.items())
    (out / 'dashboard.html').write_text('<!doctype html><meta charset="utf-8">'
        '<meta http-equiv="refresh" content="10"><title>IraAlgo research progress</title>'
        '<style>body{font:16px system-ui;max-width:1000px;margin:40px auto;background:#101827;'
        'color:#eef}td,th{padding:12px;text-align:left;border-bottom:1px solid #456}</style>'
        '<h1>IraAlgo offline research</h1><p>Development evidence. No real orders.</p><table>' + rows + '</table>')


def expiry(day, observed):
    """Observed contracts first; 2026 fallback uses published holidays, never data gaps."""
    day = pd.Timestamp(day).date()
    obs = sorted(d for d in observed if d >= day)
    if obs and day <= max(observed):
        return obs[0], 'observed'
    if day.year != 2026:
        raise ValueError('No verified expiry schedule for this date')
    e = day + pd.Timedelta(days=(1-day.weekday()) % 7)
    while e.weekday() >= 5 or e in HOLIDAYS_2026:
        e -= pd.Timedelta(days=1)
    if e < day:
        return expiry(day + pd.Timedelta(days=1), observed)
    return e, 'published_schedule_reconstructed'


def net_trade(buy, sell, day, exp, stress=1.0):
    if not (np.isfinite([buy, sell]).all() and buy > 0 and sell > 0 and stress >= 1):
        raise ValueError('Invalid fill')
    qty = 75 if pd.Timestamp(exp) < pd.Timestamp('2026-01-01') else 65
    b = buy + stress * (1.5 * (.162 + .00292 * buy) + .0005 * buy)
    s = max(0., sell - stress * (1.5 * (.162 + .00292 * sell) + .0005 * sell))
    bv, sv = b * qty, s * qty
    exchange_rate = .0003503 if str(day)[:10] < '2026-03-01' else .0003553
    stt_rate = .001 if str(day)[:10] < '2026-04-01' else .0015
    exchange = (bv + sv) * exchange_rate
    sebi = (bv + sv) * .000001
    charges = 40 + exchange + sebi + .18 * (40 + exchange + sebi) + bv * .00003 + sv * stt_rate
    return (sv-bv)-charges, (sell-buy)*qty, charges, (sell-buy)*qty-(sv-bv)


def fixed_path(book, minute, strike, side, horizon=15):
    """Next-open execution; stop/target observed at minute opens, not optimistic wick fills."""
    try:
        opens = np.asarray([book[(minute+1+j, float(strike), side)][0] for j in range(horizon+1)])
        volumes = np.asarray([book[(minute+1+j, float(strike), side)][1] for j in range(horizon+1)])
    except KeyError:
        return None
    if not (np.isfinite(opens).all() and (opens > 0).all() and (volumes > 0).all()):
        return None
    stop = np.flatnonzero((opens[1:] <= opens[0]*.85) | (opens[1:] >= opens[0]*1.30))
    j = int(stop[0]+1) if len(stop) else horizon
    return float(opens[0]), float(opens[j]), minute+1+j


def underlying_features(d):
    out = {}
    for s in d:
        for k in (1, 5, 15, 30):
            out[s + f'_ret{k}'] = d[s].pct_change(k, fill_method=None)
        out[s+'_vol15'] = d[s].pct_change(fill_method=None).rolling(15).std()
    out['time'] = (d.index.hour*60 + d.index.minute - 555)/375
    return pd.DataFrame(out, index=d.index)


def build(lake, out):
    paths = sorted(lake.glob('*.parquet'))
    if len(paths) < 8:
        raise ValueError('Eight required input files are missing')
    fingerprints = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    frames, audit = [], {'files': fingerprints, 'rejections': {}, 'expiry_sources': {}}
    for s in ('NIFTY', 'BANKNIFTY', 'INDIAVIX'):
        d = pd.concat([pd.read_parquet(p) for p in sorted(lake.glob(f'index_1m_{s}_*.parquet'))])
        if d.ts.duplicated().any():
            raise ValueError('Duplicate index timestamps: '+s)
        if d.ts.dt.tz is None:
            raise ValueError('Naive timestamps')
        d.ts = d.ts.dt.tz_convert('Asia/Kolkata')
        if not (np.isfinite(d.close).all() and (d.close > 0).all()):
            raise ValueError('Invalid index close')
        frames.append(d.set_index('ts').close.rename(s))
    ix = pd.concat(frames, axis=1, join='inner').sort_index().dropna()
    end = ix.index.max().normalize()
    start = end - pd.DateOffset(years=1) + pd.Timedelta(days=1)
    ix = ix.loc[start: end+pd.Timedelta(days=1)]
    audit.update(start=str(start), end=str(end), index_rows=len(ix), sessions=len(ix.groupby(ix.index.date)))
    opts = pd.concat([pd.read_parquet(p) for p in sorted(lake.glob('opt_NIFTY_WEEK_*.parquet'))])
    opts.ts = opts.ts.dt.tz_convert('Asia/Kolkata')
    opts = opts[(opts.ts >= start) & (opts.ts < end+pd.Timedelta(days=1))].copy()
    opts['side'] = opts.side.astype(str)
    audit['option_rows'] = len(opts)
    # Different rolling offsets may repeat a contract; identical duplicates are safe to collapse.
    keys = ['ts', 'strike', 'side']
    quote_cols = ['open', 'close', 'volume', 'oi', 'iv']
    duplicated = opts[opts.duplicated(keys, keep=False)]
    if len(duplicated) and (duplicated.groupby(keys)[quote_cols].nunique().max(axis=1) > 1).any():
        raise ValueError('Conflicting contract quotes')
    audit['identical_contract_duplicates'] = int(opts.duplicated(keys).sum())
    opts = opts.drop_duplicates(keys)
    ex = pd.read_csv(HERE.parent/'brain/calendar_data/expiries.csv')
    observed = set(pd.to_datetime(ex.loc[ex.und == 'NIFTY', 'date']).dt.date)
    samples, labels, regimes, pnls, stresses, metadata = [], [], [], [], [], []
    names = None
    option_days = {day: g for day, g in opts.groupby(opts.ts.dt.date)}
    for day, d in ix.groupby(ix.index.date):
        o = option_days.get(day, opts.iloc[:0]).copy()
        if o.empty:
            audit['rejections']['session_without_options'] = audit['rejections'].get('session_without_options', 0)+1
            continue
        exp, source = expiry(day, observed)
        audit['expiry_sources'][source] = audit['expiry_sources'].get(source, 0)+1
        # Only this day's nearest weekly expiry exists in this file; never join across dates.
        o['minute'] = o.ts.dt.hour*60+o.ts.dt.minute
        book = {(int(r.minute), float(r.strike), r.side): (r.open, r.volume) for r in o.itertuples()}
        atm = o[o.offset == 0].set_index(['ts', 'side'])
        f = underlying_features(d)
        for side in ('C', 'P'):
            if side not in atm.index.get_level_values('side'):
                continue
            a = atm.xs(side, level='side')
            f[side+'_logoi'] = np.log1p(a.oi.clip(lower=0))
            f[side+'_logvolume'] = np.log1p(a.volume.clip(lower=0))
            f[side+'_iv'] = a.iv.where(a.iv > 0)
            # Missing IV is explicitly flagged; no future-value filling.
            f[side+'_iv_missing'] = f[side+'_iv'].isna().astype(float)
            f[side+'_iv'] = f[side+'_iv'].fillna(0)
        f['dte'] = (exp-day).days/7
        if len(f.columns) != 25:
            continue
        names = list(f.columns)
        for j in range(60, len(d)-16, 5):
            t = d.index[j]
            m = t.hour*60+t.minute
            if m+16 >= 905:
                continue
            segment = d.index[j-29:j+17]
            if (np.diff(segment.asi8) != 60_000_000_000).any():
                audit['rejections']['index_gap'] = audit['rejections'].get('index_gap', 0)+1
                continue
            x = f.iloc[j-29:j+1].to_numpy(dtype=np.float32)
            if not np.isfinite(x).all():
                continue
            pairs, outcomes, stress_out = [], [], []
            for side in ('C', 'P'):
                try:
                    strike = float(atm.loc[(t, side), 'strike'])
                except KeyError:
                    break
                p = fixed_path(book, m, strike, side)
                if p is None:
                    break
                buy, sell, exit_minute = p
                pairs.append((side, strike, buy, sell, exit_minute))
                outcomes.append(net_trade(buy, sell, day, exp)[0])
                stress_out.append(net_trade(buy, sell, day, exp, 2)[0])
            if len(pairs) != 2:
                audit['rejections']['incomplete_fixed_contract_path'] = audit['rejections'].get('incomplete_fixed_contract_path', 0)+1
                continue
            target = int(np.argmax(outcomes))+1 if max(outcomes) > 100 else 0
            regime = 1 if d.NIFTY.iloc[j]/d.NIFTY.iloc[j-30]-1 > .001 else 2 if d.NIFTY.iloc[j]/d.NIFTY.iloc[j-30]-1 < -.001 else 0
            samples.append(x); labels.append(target); regimes.append(regime)
            pnls.append(outcomes); stresses.append(stress_out)
            metadata.append(dict(ts=str(t), day=str(day), expiry=str(exp), expiry_source=source,
                ce_strike=pairs[0][1], pe_strike=pairs[1][1], ce_exit=pairs[0][4], pe_exit=pairs[1][4],
                momentum=float(d.NIFTY.iloc[j]/d.NIFTY.iloc[j-15]-1)))
    if not samples:
        raise ValueError('No valid fixed-contract training samples')
    audit.update(samples=len(samples), features=names, iv_note='zero IV retained as missing, Greeks not fabricated')
    dump(out/'data_audit.json', audit)
    return np.stack(samples), np.array(labels), np.array(regimes), np.array(pnls), np.array(stresses), pd.DataFrame(metadata), audit


class Network(nn.Module):
    def __init__(self, features, kind):
        super().__init__()
        self.kind = kind
        if kind == 'mlp':
            self.encoder = nn.Sequential(nn.Linear(features,64),nn.ReLU(),nn.Dropout(.2),
                nn.Linear(64,32),nn.ReLU(),nn.Dropout(.2),nn.Linear(32,16),nn.ReLU())
            width = 16
        else:
            self.conv = nn.Conv1d(features,32,3)
            self.rnn = (nn.LSTM if kind == 'lstm' else nn.GRU)(32,32,batch_first=True)
            self.dropout = nn.Dropout(.2)
            self.attention = nn.Linear(32,1)
            width = 32
        self.actions = nn.Linear(width,3)
        self.premium = nn.Linear(width,2)
        self.regime = nn.Linear(width,3)

    def forward(self, x):
        if self.kind == 'mlp':
            z = self.encoder(x[:,-1])
        else:
            z = torch.relu(self.conv(torch.nn.functional.pad(x.transpose(1,2),(2,0)))).transpose(1,2)
            z, _ = self.rnn(z)
            z = (z*torch.softmax(self.attention(z),dim=1)).sum(1) if self.kind == 'gru' else z[:,-1]
            z = self.dropout(z)
        return self.actions(z), self.premium(z), self.regime(z)


def objective(output, y, p, r):
    a, ev, regime = output
    return nn.functional.cross_entropy(a,y)+.2*nn.functional.smooth_l1_loss(ev,p)+.1*nn.functional.cross_entropy(regime,r)


def fit(x, y, r, pnl, train, val, kind, lr, path, fingerprint, out, fold):
    random.seed(42); np.random.seed(42); torch.manual_seed(42)
    code = ''.join(inspect.getsource(f) for f in (expiry,net_trade,fixed_path,underlying_features,build,Network,objective,fit))
    config = dict(kind=kind,lr=lr,fold=fold,features=x.shape[-1],epochs=6,seed=42,
                  code_fingerprint=hashlib.sha256(code.encode()).hexdigest())
    mean = x[train].mean(axis=(0,1)); scale = x[train].std(axis=(0,1)).clip(.000001)
    z = torch.tensor(np.clip((x-mean)/scale,-10,10),dtype=torch.float32)
    yy = torch.tensor(y,dtype=torch.long); rr = torch.tensor(r,dtype=torch.long)
    pp = torch.tensor(pnl/1000,dtype=torch.float32)
    model = Network(x.shape[-1],kind)
    optimizer = torch.optim.AdamW(model.parameters(),lr=lr,weight_decay=.001)
    best = float('inf'); best_state = None; begin = 0; history = []
    if path.exists():
        ck = torch.load(path,weights_only=False,map_location='cpu')
        if ck['fingerprint'] != fingerprint or ck['config'] != config:
            raise ValueError('Checkpoint configuration/data mismatch')
        model.load_state_dict(ck['model']);optimizer.load_state_dict(ck['optimizer'])
        torch.set_rng_state(ck['rng']);begin=ck['epoch'];best=ck['best_loss'];best_state=ck['best_model'];history=ck['history']
    for ep in range(begin,6):
        model.train()
        order = torch.tensor(train)[torch.randperm(len(train))]
        for ids in order.split(256):
            optimizer.zero_grad();loss=objective(model(z[ids]),yy[ids],pp[ids],rr[ids]);loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(),1);optimizer.step()
        model.eval()
        with torch.no_grad():
            vl = float(objective(model(z[val]),yy[val],pp[val],rr[val]))
        history.append(dict(epoch=ep+1,validation_loss=vl))
        if vl < best:
            best=vl;best_state={k:v.detach().clone() for k,v in model.state_dict().items()}
        ck=dict(config=config,fingerprint=fingerprint,model=model.state_dict(),optimizer=optimizer.state_dict(),
            rng=torch.get_rng_state(),epoch=ep+1,best_loss=best,best_model=best_state,history=history,
            mean=mean,scale=scale,train_rows=len(train),validation_rows=len(val),
            train_last=int(train[-1]),validation_first=int(val[0]))
        tmp=path.with_suffix('.tmp');torch.save(ck,tmp);tmp.replace(path)
        status(out,stage='training',fold=fold,architecture=kind,learning_rate=lr,epochs_completed=ep+1,
            validation_loss=vl,last_successful_checkpoint=path.name)
        print(f'fold={fold} {kind} lr={lr} epoch={ep+1} validation_loss={vl:.6f}',flush=True)
    model.load_state_dict(best_state);model.eval()
    with torch.no_grad():
        probabilities, ev, _ = model(z)
    return torch.softmax(probabilities,dim=1).numpy(),ev.numpy()*1000,best,history


def simulate(meta, pnl, stress, probabilities, ev=None, baseline=None):
    rows=[]; busy={}
    for i,row in meta.iterrows():
        minute=pd.Timestamp(row.ts).hour*60+pd.Timestamp(row.ts).minute
        if minute <= busy.get(row.day,-1):
            continue
        if baseline == 'momentum':
            action=1 if row.momentum > .001 else 2 if row.momentum < -.001 else 0
        elif baseline == 'contrarian':
            action=2 if row.momentum > .001 else 1 if row.momentum < -.001 else 0
        else:
            action=int(probabilities[i].argmax())
            if action and (probabilities[i,action] < .5 or ev[i,action-1] <= 100):
                action=0
        if not action:
            continue
        k=action-1
        busy[row.day]=int(row.ce_exit if k==0 else row.pe_exit)
        rows.append(dict(ts=row.ts,day=row.day,side='C' if k==0 else 'P',expiry=row.expiry,
            strike=float(row.ce_strike if k==0 else row.pe_strike),exit_minute=busy[row.day],
            net=float(pnl[i,k]),stress_net=float(stress[i,k])))
    return pd.DataFrame(rows,columns=['ts','day','side','expiry','strike','exit_minute','net','stress_net'])


def summarize(trades, days):
    daily=trades.groupby('day').net.sum().reindex(days,fill_value=0) if len(trades) else pd.Series(0.,index=days)
    v=daily.to_numpy(float);cum=np.r_[0,np.cumsum(trades.net.to_numpy(float))]
    dd=float((np.maximum.accumulate(cum)-cum).max())
    loss=float(-trades.loc[trades.net<0,'net'].sum());win=float(trades.loc[trades.net>0,'net'].sum())
    rng=np.random.default_rng(42);centered=v-v.mean()
    # One-sided null bootstrap clustered by session. Not a correction for historical reuse.
    p=float((1+(rng.choice(centered,(2000,len(v)),replace=True).mean(1)>=v.mean()).sum())/2001) if len(trades) else 1.
    month=daily.groupby(pd.to_datetime(daily.index).to_period('M')).sum()
    return dict(trades=len(trades),sessions=len(days),net=float(v.sum()),expectancy=float(trades.net.mean()) if len(trades) else 0.,
        stress_net=float(trades.stress_net.sum()),win_rate=float((trades.net>0).mean()) if len(trades) else 0.,
        profit_factor=win/loss if loss else None,max_drawdown=dd,worst_month=float(month.min()),
        daily_sharpe=float(v.mean()/v.std(ddof=1)*np.sqrt(252)) if v.std(ddof=1)>0 else None,
        fixed_capital=100000,p_value=p,drawdown_basis='realized closed-trade equity; not intratrade mark-to-market')


def bh(p):
    p=np.asarray(p);order=np.argsort(p);q=np.empty(len(p))
    q[order]=np.minimum.accumulate((p[order]*len(p)/np.arange(1,len(p)+1))[::-1])[::-1].clip(0,1)
    return q


def run(lake, out):
    out.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2)
    status(out,stage='ingestion',errors=[],backtesting_progress='not_started')
    x,y,r,pnl,stress,meta,audit=build(lake,out)
    status(out,ingestion_progress='complete',valid_samples=len(x),input_sessions=audit['sessions'])
    fingerprint=hashlib.sha256(json.dumps(audit,sort_keys=True).encode()).hexdigest()
    days=np.array(sorted(meta.day.unique()));edges=[int(len(days)*z) for z in (.5,.65,.8,1)]
    results={};folds=[];preds={};evs={};outer=[]
    for fold,(a,b) in enumerate(zip(edges[:-1],edges[1:]),1):
        cut=int(a*.8)
        tr=np.flatnonzero(meta.day < days[cut]);va=np.flatnonzero((meta.day >= days[cut]) & (meta.day < days[a]))
        te=np.flatnonzero(meta.day.isin(days[a:b]));outer.extend(te)
        folds.append(dict(fold=fold,train=len(tr),validation=len(va),evaluation=len(te),
            train_end=days[cut-1],validation_start=days[cut],evaluation_start=days[a],evaluation_end=days[b-1]))
        chosen=[];chosen_ev=[]
        for kind in ('mlp','lstm','gru'):
            variants=[]
            for lr in (.001,.0003):
                name=f'{kind}_{lr}'
                path=out/f'fold{fold}_{name}.pt'
                pro,ev,loss,history=fit(x,y,r,pnl,tr,va,kind,lr,path,fingerprint,out,fold)
                preds.setdefault(name,[]).append(pro[te]);evs.setdefault(name,[]).append(ev[te])
                variants.append((loss,pro[te],ev[te],lr))
            chosen_variant=min(variants,key=lambda t:t[0]);chosen.append(chosen_variant[1]);chosen_ev.append(chosen_variant[2])
            preds.setdefault(kind+'_inner_selected',[]).append(chosen_variant[1]);evs.setdefault(kind+'_inner_selected',[]).append(chosen_variant[2])
            folds[-1][kind+'_selected_lr']=chosen_variant[3]
        preds.setdefault('ensemble',[]).append(np.mean(chosen,axis=0));evs.setdefault('ensemble',[]).append(np.mean(chosen_ev,axis=0))
    outer=np.array(outer);m=meta.iloc[outer].reset_index(drop=True);p=pnl[outer];s=stress[outer]
    all_days=sorted(m.day.unique())
    status(out,stage='backtesting',backtesting_progress='running')
    for name,parts in preds.items():
        pro=np.concatenate(parts);ev=np.concatenate(evs[name]);t=simulate(m,p,s,pro,ev)
        t.to_csv(out/(name+'_trades.csv'),index=False)
        results[name]=summarize(t,all_days)
        results[name]['balanced_accuracy']=float(balanced_accuracy_score(y[outer],pro.argmax(1)))
        results[name]['raw_class_distribution']=np.bincount(pro.argmax(1),minlength=3).tolist()
        results[name]['max_directional_confidence']=float(pro[:,1:].max())
        results[name]['max_predicted_net']=float(ev.max())
        results[name]['fold_performance']=[summarize(t[t.day.between(f['evaluation_start'],f['evaluation_end'])],
            [d for d in all_days if f['evaluation_start']<=d<=f['evaluation_end']]) for f in folds]
    for baseline in ('momentum','contrarian'):
        t=simulate(m,p,s,None,baseline=baseline);t.to_csv(out/(baseline+'_trades.csv'),index=False)
        results[baseline]=summarize(t,all_days)
        results[baseline]['fold_performance']=[summarize(t[t.day.between(f['evaluation_start'],f['evaluation_end'])],
            [d for d in all_days if f['evaluation_start']<=d<=f['evaluation_end']]) for f in folds]
    earlier_path=HERE/'initial_attempt_results.json'
    earlier=json.loads(earlier_path.read_text())['models'] if earlier_path.exists() else {}
    family=[v['p_value'] for v in results.values()]+[v['p_value'] for v in earlier.values()]
    qs=bh(family)[:len(results)]
    for v,q in zip(results.values(),qs):
        v['bh_q']=float(q);v['paper_trading_suitable']=False
    dump(out/'results.json',dict(status='completed_development_only',fresh_final_test=False,
        final_verdict='REJECT: no untouched final test; no production or paper deployment authorized by these results',
        fingerprint=fingerprint,folds=folds,models=results,multiple_testing_family_size=len(family)))
    lines=['# Neural V2 cost-adjusted development report','','All results are retrospective development evidence. No untouched final test exists.',
        '',f"Input window: {audit['start']} through {audit['end']}. Valid samples: {len(x):,}; evaluated samples: {len(outer):,}.",
        f'Outer evaluation: {len(all_days)} sessions; fixed capital Rs100000. No-trade baseline net and drawdown are zero.',
        '', '| Model | Trades | Net Rs | Stress net Rs | Expectancy Rs | Max DD Rs | Win % | PF | Sharpe | BH q |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for name,v in results.items():
        pf='n/a' if v['profit_factor'] is None else f"{v['profit_factor']:.2f}"
        sh='n/a' if v['daily_sharpe'] is None else f"{v['daily_sharpe']:.2f}"
        lines.append(f"| {name} | {v['trades']} | {v['net']:.0f} | {v['stress_net']:.0f} | {v['expectancy']:.1f} | {v['max_drawdown']:.0f} | {100*v['win_rate']:.1f} | {pf} | {sh} | {v['bh_q']:.3f} |")
    lines+=['','## Decision','','No model is approved for paper or live trading by this study. Historical reuse, reconstructed expiry metadata, assumed spreads and incomplete market coverage prevent a credible fresh edge claim.',
        '', 'Entry gates are fixed by the preregistration. Zero trades means abstention and insufficient evidence, not a profitable strategy. Drawdown is measured on realized closed-trade equity, not intratrade mark-to-market. Per-fold results and prediction diagnostics are in results.json.',
        '', 'The experiment covers NIFTY only, with BANKNIFTY and India VIX context. Futures, equities, participant and commodity ingestion, verified Greeks, strike/expiry optimization, independent final testing and persistent hosted execution remain incomplete.',
        '', 'Zero IV is flagged as missing. No fabricated Greeks. Fixed ATM strike at signal time; next-open execution and minute-open risk exits. Stops can overshoot between observations. Full 16-minute contract paths are required before inclusion, creating a retrospective coverage restriction; rejected opportunities are not assumed executable.',
        '', 'No fresh test was consumed. R51/R72 were reviewed as historical references; momentum and contrarian baselines here are not parity reproductions of those arms. Bootstrap and BH do not remove historical selection bias. Stress columns reprice the same filled paths, not a second stop simulation.',
        '', 'Costs and schedule sources are in HANDOVER.md. Brokerage/taxes are continuous approximations without contract-note aggregation rounding. Checkpoints retain optimizer/RNG/scaler/configuration for resume and are not live trading artifacts.']
    lines+=['', 'Baseline ledgers are unconstrained signal diagnostics: account cash, monthly loss and drawdown stop gates are not applied, so losses may exceed the fixed reference capital. They are not executable account simulations. Neural candidates made no trades; full account risk integration remains incomplete.']
    lines+=['',f'BH includes {len(family)} reported candidates across the initial and corrected cost runs. The first attempt was retained for transparency; the correction was not a search for better performance. Prior repository-wide research reuse remains an additional bias.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n')
    status(out,stage='completed_development_only',backtesting_progress='complete',
        model_performance=results['ensemble'],blockers=['untouched final test unavailable','expanded market ingestion deferred'],
        paper_trading_suitable=False)
    return results


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--lake',type=Path,required=True)
    ap.add_argument('--out',type=Path,default=HERE/'results');args=ap.parse_args()
    try:
        run(args.lake,args.out)
    except Exception as exc:
        args.out.mkdir(parents=True,exist_ok=True)
        status(args.out,stage='failed',errors=[f'{type(exc).__name__}: {exc}'])
        raise


if __name__ == '__main__':
    main()
