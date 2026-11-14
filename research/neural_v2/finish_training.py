"""Convergence selection followed by all-data refitting, for research only."""
from __future__ import annotations

import argparse
import hashlib
import inspect
import json
from pathlib import Path

import numpy as np
import pandas as pd
import torch

from . import pipeline as P


def save_checkpoint(path, checkpoint):
    tmp = path.with_suffix('.tmp')
    torch.save(checkpoint, tmp)
    tmp.replace(path)


def fit_epoch(model, optimizer, x, y, pnl, regime, indices):
    model.train()
    total = 0.
    for batch in torch.tensor(indices)[torch.randperm(len(indices))].split(256):
        optimizer.zero_grad()
        loss = P.objective(model(x[batch]), y[batch], pnl[batch], regime[batch])
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.)
        optimizer.step()
        total += float(loss.detach()) * len(batch)
    return total / len(indices)


def train_complete(x, y, regime, pnl, dates, features, kind, path, fingerprint,
                   out, max_epochs=50, patience=6, min_delta=.0001):
    if len(x) == 0 or x.ndim != 3 or x.shape[1:] != (30, len(features)) or not np.isfinite(x).all():
        raise ValueError('Training requires finite 30-minute feature sequences')
    if not (len(x) == len(y) == len(regime) == len(pnl) == len(dates)):
        raise ValueError('Training arrays differ in length')
    if kind not in ('mlp', 'lstm', 'gru') or max_epochs < 1 or patience < 1 or min_delta < 0:
        raise ValueError('Invalid training configuration')
    days = np.array(sorted(set(dates)))
    if len(days) < 5 or list(dates) != sorted(dates):
        raise ValueError('Need at least five chronological session dates')
    boundary = days[int(len(days)*.8)]
    train = np.flatnonzero(np.asarray(dates) < boundary)
    val = np.flatnonzero(np.asarray(dates) >= boundary)
    source = ''.join(inspect.getsource(f) for f in
        (P.build, P.expiry, P.net_trade, P.fixed_path, P.underlying_features,
         P.Network, P.objective, fit_epoch, train_complete))
    config = dict(kind=kind, features=list(features), sequence_length=30, seed=42,
        learning_rate=.001, weight_decay=.001, max_epochs=max_epochs,
        patience=patience, min_delta=min_delta, code_sha256=hashlib.sha256(source.encode()).hexdigest())
    torch.manual_seed(42)
    model = P.Network(len(features), kind)
    optimizer = torch.optim.AdamW(model.parameters(), lr=.001, weight_decay=.001)
    if path.exists():
        ck = torch.load(path, map_location='cpu', weights_only=False)
        if ck['fingerprint'] != fingerprint or ck['config'] != config:
            raise ValueError('Checkpoint data or configuration mismatch')
        model.load_state_dict(ck['model']); optimizer.load_state_dict(ck['optimizer'])
        torch.set_rng_state(ck['rng'])
    else:
        ck = dict(config=config, fingerprint=fingerprint, phase='selection', epoch=0,
            mean=x[train].mean(axis=(0,1)), scale=x[train].std(axis=(0,1)).clip(.000001),
            best_loss=None, significant_best=None, best_epoch=0, stale=0,
            selection_history=[], refit_history=[], features=list(features),
            train_rows=len(train), validation_rows=len(val), all_rows=len(x),
            train_end=str(np.asarray(dates)[train[-1]]), validation_start=str(boundary),
            data_start=str(days[0]), data_end=str(days[-1]))
    yy = torch.tensor(y, dtype=torch.long)
    rr = torch.tensor(regime, dtype=torch.long)
    pp = torch.tensor(pnl/1000, dtype=torch.float32)
    z = torch.tensor(np.clip((x-ck['mean'])/ck['scale'], -10, 10), dtype=torch.float32)
    if ck['phase'] == 'selection':
        for epoch in range(ck['epoch'], max_epochs):
            if ck['stale'] >= patience:
                break
            train_loss = fit_epoch(model, optimizer, z, yy, pp, rr, train)
            model.eval()
            with torch.no_grad():
                val_loss = float(P.objective(model(z[val]), yy[val], pp[val], rr[val]))
            if not np.isfinite([train_loss, val_loss]).all():
                raise ValueError('Nonfinite training or validation loss')
            ck['epoch'] = epoch+1
            ck['selection_history'].append(dict(epoch=epoch+1, training_loss=train_loss, validation_loss=val_loss))
            if ck['best_loss'] is None or val_loss < ck['best_loss']:
                ck['best_loss'] = val_loss; ck['best_epoch'] = epoch+1
                ck['selection_best_model'] = {k: v.detach().clone() for k,v in model.state_dict().items()}
            if ck['significant_best'] is None or val_loss < ck['significant_best']-min_delta:
                ck['significant_best'] = val_loss; ck['stale'] = 0
            else:
                ck['stale'] += 1
            ck.update(model=model.state_dict(), optimizer=optimizer.state_dict(), rng=torch.get_rng_state())
            save_checkpoint(path, ck)
            P.status(out, stage='duration_selection', architecture=kind, epochs_completed=epoch+1,
                validation_loss=val_loss, training_loss=train_loss, last_successful_checkpoint=path.name)
            print(f'{kind} selection epoch={epoch+1} train={train_loss:.6f} validation={val_loss:.6f}', flush=True)
        ck['stop_reason'] = 'early_stopping' if ck['stale'] >= patience else 'maximum_epochs'
        ck['selection_epochs_completed'] = ck['epoch']
        # Fresh initialization: all-data fitting uses a newly fitted all-data scaler.
        torch.manual_seed(42)
        model = P.Network(len(features), kind)
        optimizer = torch.optim.AdamW(model.parameters(), lr=.001, weight_decay=.001)
        ck.update(phase='refit', epoch=0, mean=x.mean(axis=(0,1)), scale=x.std(axis=(0,1)).clip(.000001),
                  model=model.state_dict(), optimizer=optimizer.state_dict(), rng=torch.get_rng_state())
        save_checkpoint(path, ck)
        z = torch.tensor(np.clip((x-ck['mean'])/ck['scale'], -10, 10), dtype=torch.float32)
    if ck['phase'] == 'refit':
        for epoch in range(ck['epoch'], ck['best_epoch']):
            train_loss = fit_epoch(model, optimizer, z, yy, pp, rr, np.arange(len(x)))
            if not np.isfinite(train_loss):
                raise ValueError('Nonfinite refit loss')
            ck['epoch'] = epoch+1
            ck['refit_history'].append(dict(epoch=epoch+1, training_loss=train_loss))
            ck.update(model=model.state_dict(), optimizer=optimizer.state_dict(), rng=torch.get_rng_state())
            save_checkpoint(path, ck)
            P.status(out, stage='all_data_refit', architecture=kind, epochs_completed=epoch+1,
                selected_epochs=ck['best_epoch'], training_loss=train_loss,
                validation_loss='not applicable: all-data fitting', last_successful_checkpoint=path.name)
            print(f'{kind} all-data epoch={epoch+1}/{ck["best_epoch"]} loss={train_loss:.6f}', flush=True)
        ck.update(phase='completed', training_completed=True, approved_for_trading=False,
            completed_at=pd.Timestamp.now(tz='UTC').isoformat())
        save_checkpoint(path, ck)
    return ck


def predict(checkpoints, sequences, feature_names):
    """Offline inference only. Enforce feature identity, order, length and finiteness."""
    x = np.asarray(sequences, dtype=np.float32)
    if x.ndim != 3 or x.shape[1:] != (30, len(feature_names)) or len(x) == 0 or not np.isfinite(x).all():
        raise ValueError('Invalid inference sequences')
    probabilities, payoffs = [], []
    if len(checkpoints) != 3 or {c['config']['kind'] for c in checkpoints} != {'mlp','lstm','gru'}:
        raise ValueError('Ensemble requires all three architectures')
    if len({c['fingerprint'] for c in checkpoints}) != 1:
        raise ValueError('Ensemble checkpoints use different datasets')
    for ck in checkpoints:
        if ck['phase'] != 'completed' or list(feature_names) != ck['features']:
            raise ValueError('Incomplete model or feature-schema mismatch')
        model = P.Network(len(feature_names), ck['config']['kind'])
        model.load_state_dict(ck['model']); model.eval()
        z = torch.tensor(np.clip((x-ck['mean'])/ck['scale'], -10, 10), dtype=torch.float32)
        with torch.no_grad():
            pro, ev, _ = model(z)
        probabilities.append(torch.softmax(pro,dim=1).numpy())
        payoffs.append(ev.numpy()*1000)
    pro = np.mean(probabilities,axis=0); ev = np.mean(payoffs,axis=0)
    if not (np.isfinite(pro).all() and np.isfinite(ev).all()):
        raise ValueError('Invalid model output')
    actions = pro.argmax(1)
    for i,a in enumerate(actions):
        if a and (pro[i,a] < .5 or ev[i,a-1] <= 100):
            actions[i] = 0
    return dict(probabilities=pro.tolist(), predicted_net_rupees=ev.tolist(), actions=actions.tolist())


def complete(lake, out):
    out.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2)
    P.status(out, stage='ingestion', errors=[], approved_for_trading=False)
    x,y,r,pnl,stress,meta,audit = P.build(lake,out)
    fingerprint = hashlib.sha256(json.dumps(audit,sort_keys=True).encode()).hexdigest()
    checkpoints, summaries = [], []
    for kind in ('mlp','lstm','gru'):
        path = out/f'{kind}_final.pt'
        ck = train_complete(x,y,r,pnl,meta.day.to_numpy(),audit['features'],kind,path,fingerprint,out)
        checkpoints.append(ck)
        summaries.append(dict(architecture=kind,checkpoint=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            duration_selection_epochs=ck['selection_epochs_completed'],selected_refit_epochs=ck['best_epoch'],
            minimum_validation_loss=ck['best_loss'],stop_reason=ck['stop_reason'],all_data_rows=ck['all_rows'],
            training_start=ck['data_start'],training_end=ck['data_end'],
            training_end_before_selection_validation=ck['train_end'] < ck['validation_start']))
    inference = predict(checkpoints,x[-3:],audit['features'])
    P.dump(out/'inference_smoke_test.json',dict(note='In-sample finite-output/schema test, not performance validation',**inference))
    report = dict(training_completed=True,stage='all_data_training_completed',samples=len(x),
        sessions=int(meta.day.nunique()),features=audit['features'],sequence_length=30,
        fingerprint=fingerprint,models=summaries,untouched_final_test_available=False,
        approved_for_paper_trading=False,approved_for_live_trading=False,
        note='Training completion does not establish profitability. Original development results remain unchanged.')
    P.dump(out/'TRAINING_SUMMARY.json',report)
    lines=['# Final NIFTY research training completed','',
        f'All three architectures were refitted on all {len(x):,} valid samples across {meta.day.nunique()} sessions.',
        'The data window is 20 October 2025 through 19 October 2026. Each sequence contains 30 minutes and 25 features.',
        '', '| Architecture | Duration-selection epochs | Selected all-data epochs | Best reused validation loss | Stop reason |',
        '|---|---:|---:|---:|---|']
    for s in summaries:
        lines.append(f'| {s["architecture"]} | {s["duration_selection_epochs"]} | {s["selected_refit_epochs"]} | {s["minimum_validation_loss"]:.6f} | {s["stop_reason"]} |')
    lines += ['', 'Equal-weight ensemble inference passed the finite-output and exact feature-schema checks.',
        'Each checkpoint includes optimizer/RNG state, scaler, configuration, code/data fingerprints and both training histories.',
        '', 'This is completion of NIFTY research fitting, not completion of the entire multi-market trading project.',
        'The monitored validation period has already been seen in historical research. The final refits consume all available samples and have no remaining historical holdout.',
        'Do not backtest these final refits on their training data and call it out-of-sample performance. Earlier walk-forward checkpoints and reports are preserved separately.',
        'No profitable edge, paper-trading approval or live-trading approval is established. No real orders were placed.',
        '', 'Full-market expansion, verified Greeks, account risk integration and fresh prospective profitability testing remain separate stages.']
    (out/'TRAINING_REPORT.md').write_text('\n'.join(lines)+'\n')
    P.status(out,stage='all_data_training_completed',training_completed=True,samples=len(x),
        models_completed=3,approved_for_trading=False,blockers=['fresh prospective validation unavailable'])
    return report


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--lake',type=Path,required=True)
    ap.add_argument('--out',type=Path,default=P.HERE/'final_training');args=ap.parse_args()
    try:
        complete(args.lake,args.out)
    except Exception as exc:
        args.out.mkdir(parents=True,exist_ok=True)
        P.status(args.out,stage='failed',errors=[f'{type(exc).__name__}: {exc}'])
        raise


if __name__ == '__main__':
    main()
