"""Minute-bar candidate construction and fixed-contract option execution."""
import hashlib
import json
from pathlib import Path
import numpy as np
import pandas as pd
import pyarrow.parquet as pq
from research.obuy.costs import Costs

HERE = Path(__file__).resolve().parent
SYMBOLS = ['NIFTY', 'BANKNIFTY', 'SENSEX', 'FINNIFTY', 'MIDCPNIFTY', 'INDIAVIX']
EXITS = ['timed', 'step', 'percent', 'atr', 'adaptive']
START = '2025-11-14'
END = '2026-11-13'
MIN = pd.Timedelta(minutes=1)
FEE = Costs('dated')


def price(raw, buy, stress):
    slip = (0.50 + 0.0005 * raw) * (2 if stress else 1)
    return max(0, round((np.ceil((raw + slip)*20) if buy else np.floor((raw-slip)*20))/20, 2))


def cash(p, buy, qty, day):
    fee = float(FEE.charge(buy, p, qty, (day - pd.Timestamp('1970-01-01').date()).days))
    return qty*p + (fee if buy else -fee)


def outcomes(segment, day, qty, atr):
    quote = segment.open.to_numpy()
    volume = segment.volume.fillna(0).to_numpy()
    valid = np.isfinite(quote) & (quote > 0) & (volume >= qty)
    if not valid[0]:
        return {'filled': False}
    result = {'filled': True}
    for stress in [False, True]:
        entry = price(quote[0], True, stress)
        debit = cash(entry, True, qty, day)
        if not stress:
            result['debit'] = debit
        for mode in EXITS:
            peak = entry
            sell_idx = 20
            for j, mark in enumerate(segment.close.to_numpy()[:20]):
                if not np.isfinite(mark) or volume[j] <= 0:
                    continue
                peak = max(peak, mark)
                peak_profit = (peak-entry)*qty
                step = 500*(np.floor(peak_profit/500)-1) if peak_profit >= 1000 else -500
                levels = {'timed': -np.inf, 'step': entry+step/qty,
                          'percent': entry*.85, 'atr': peak-2*atr,
                          'adaptive': peak-max(.08*entry, 3*atr)}
                if mark <= levels[mode]:
                    sell_idx = j+1
                    break
            ids = np.flatnonzero(valid[sell_idx:])
            ix = sell_idx + ids[0] if len(ids) else None
            credit = cash(price(quote[ix], False, stress), False, qty, day) if ix is not None else 0
            key = mode+('_stress' if stress else '')
            result[key] = credit-debit
            if mode == 'timed' and not stress:
                result['missing_exit'] = ix is None
                result['gross'] = qty*((quote[ix] if ix is not None else 0)-quote[0])
    return result


def build(lake, out):
    files = sorted([*lake.glob('index_1m_*.parquet'), *lake.glob('opt_NIFTY_WEEK_*.parquet')])
    audit = {'inputs': {}, 'excluded_windows': 0, 'ambiguous_contract_minutes': 0}
    for f in files:
        audit['inputs'][f.name] = dict(rows=pq.ParquetFile(f).metadata.num_rows,
                                         sha256=hashlib.sha256(f.read_bytes()).hexdigest())
    contexts = []
    for symbol in SYMBOLS:
        paths = sorted(lake.glob(f'index_1m_{symbol}_*.parquet'))
        frame = pd.concat([pd.read_parquet(f).set_index('ts') for f in paths]).sort_index().loc[START:END]
        if frame.index.has_duplicates or frame.index.tz is None:
            raise ValueError(f'Invalid timestamp keys for {symbol}')
        close = frame.close
        contexts.append(close.rename(symbol))
        if symbol == 'NIFTY':
            spot = frame
    context = pd.concat(contexts, axis=1).sort_index()
    dates = json.loads((HERE.parent/'hunt/r50/results/expiry_days.json').read_text())['NIFTY']
    expiry = pd.DatetimeIndex(sorted(set(dates+['2026-10-19','2026-10-27','2026-11-03','2026-11-09','2026-11-17'])))
    x, meta = [], []
    feature_names = SYMBOLS+['signed_move5','signed_move15','range','orb_position','option_return','premium_range',
                              'volume_log','oi_change','iv','distance','signed_oi_balance','premium','dte','clock','call']
    for f in sorted(lake.glob('opt_NIFTY_WEEK_*.parquet')):
        chain = pd.read_parquet(f)
        chain = chain[chain.ts.between(pd.Timestamp(START,tz='Asia/Kolkata'),pd.Timestamp(END,tz='Asia/Kolkata')+pd.Timedelta(days=1))]
        for day, book in chain.groupby(chain.ts.dt.date, sort=True):
            date = pd.Timestamp(day)
            expiry_date = expiry[expiry.searchsorted(date)]
            dte = (expiry_date-date).days
            if not 0 <= dte <= 7:
                raise ValueError(f'Missing weekly expiry on {day}')
            qty = 75 if expiry_date <= pd.Timestamp('2025-12-30') else 65
            duplicate = book.duplicated(['ts','strike','side'], keep=False)
            audit['ambiguous_contract_minutes'] += int(duplicate.sum())
            book = book.loc[~duplicate].copy()
            book['signed_oi'] = book.oi * np.where(book.side=='C',1,-1)
            balance = book.groupby('ts').signed_oi.sum()/book.groupby('ts').oi.sum().replace(0,np.nan)
            contracts = {k:g.set_index('ts').sort_index() for k,g in book.groupby(['strike','side'])}
            base = date.tz_localize('Asia/Kolkata')+pd.Timedelta(hours=9,minutes=15)
            times = pd.date_range(base, periods=375, freq='min')
            ctx = context.reindex(times)
            s = spot.reindex(times)
            ret = ctx.pct_change(fill_method=None)*100
            bound = s.iloc[:15]
            if bound.close.count()!=15:
                continue
            spread = max(bound.high.max()-bound.low.min(), .05)
            for m in range(30,331,30):
                signal = times[m-1]
                ref = s.close.iloc[m-1]
                if not np.isfinite(ref):
                    continue
                strike = 50*np.floor(ref/50+.5)
                for side, direction in [('C',1),('P',-1)]:
                    c = contracts.get((strike,side))
                    if c is None:
                        audit['excluded_windows'] += 1
                        continue
                    # Only past timestamps determine eligibility and features.
                    hist = c.reindex(times[m-21:m])
                    seq = ret.iloc[m-20:m].copy()
                    underlying = s.close
                    seq['signed_move5'] = direction*underlying.pct_change(5,fill_method=None).reindex(seq.index)*100
                    seq['signed_move15'] = direction*underlying.pct_change(15,fill_method=None).reindex(seq.index)*100
                    seq['range'] = (s.high-s.low)/s.close*100
                    # Opening range becomes known only after the first 15 bars.
                    seq['orb_position'] = direction*((s.close-bound.low.min())/spread-.5)
                    seq.loc[seq.index < times[14], 'orb_position'] = 0
                    seq['option_return'] = hist.close.pct_change(fill_method=None)*100
                    seq['premium_range'] = (hist.high-hist.low)/hist.close*100
                    seq['volume_log'] = np.log1p(hist.volume.clip(lower=0)/qty)
                    seq['oi_change'] = hist.oi.pct_change(fill_method=None)*100
                    seq['iv'] = hist.iv
                    seq['distance'] = direction*(s.close-strike)/50
                    seq['signed_oi_balance'] = balance*direction
                    seq['premium'] = hist.close
                    seq['dte'], seq['clock'], seq['call'] = dte, m/375, int(side=='C')
                    if hist.volume.iloc[-5:].sum()<qty or not np.isfinite(seq.to_numpy()).all() or hist.close.iloc[-1]<=0:
                        audit['excluded_windows'] += 1
                        continue
                    prev = hist.close.shift()
                    atr = pd.concat([hist.high-hist.low,(hist.high-prev).abs(),(hist.low-prev).abs()],axis=1).max(axis=1).tail(14).mean()
                    path = c.reindex(times[m:m+25])
                    row = outcomes(path, day, qty, max(.05,atr))
                    row.update(day=str(day),clock=m,side=side,strike=strike,expiry=str(expiry_date.date()),
                               lot=qty,dte=dte,momentum=direction*(ref/underlying.iloc[m-6]-1),
                               regime='rising' if ref>=bound.open.iloc[0] else 'falling')
                    x.append(seq.to_numpy(np.float32))
                    meta.append(row)
    order = pd.DataFrame(meta).sort_values(['day','clock','side']).index.to_numpy()
    df = pd.DataFrame(meta).iloc[order].reset_index(drop=True)
    audit.update(candidates=len(df), unfilled=int((~df.filled).sum()), missing_timed_exit=int(df.missing_exit.eq(True).sum()),
                 features=feature_names, sessions=sorted(set(spot.index.strftime('%Y-%m-%d'))))
    out.mkdir(parents=True,exist_ok=True)
    (out/'audit.json').write_text(json.dumps(audit,indent=2))
    df.to_parquet(out/'candidates.parquet')
    np.save(out/'history.npy',np.stack(x)[order])
