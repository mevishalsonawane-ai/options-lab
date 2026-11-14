"""Uncertainty and sequence diagnostics; these cannot turn reused data fresh."""
import numpy as np
import pandas as pd


def wilson(wins,n):
    if not 0<=wins<=n:raise ValueError('Invalid win counts')
    if not n:return [None,None]
    z=1.959963984540054;p=wins/n;den=1+z*z/n
    center=(p+z*z/(2*n))/den;half=z*np.sqrt(p*(1-p)/n+z*z/(4*n*n))/den
    return [float(max(0,center-half)),float(min(1,center+half))]


def block_indices(n,rng,length=5):
    if n<1 or length<1:raise ValueError('Nonempty positive block length required')
    length=min(length,n);starts=rng.integers(0,n-length+1,size=int(np.ceil(n/length)))
    return np.concatenate([np.arange(s,s+length) for s in starts])[:n]


def block_pvalue(ledger,days,reps=1000):
    daily=ledger.groupby('day').net.sum().reindex(days,fill_value=0).to_numpy(float) if len(ledger) else np.zeros(len(days))
    if not len(daily):return 1.
    rng=np.random.default_rng(42);centered=daily-daily.mean()
    values=np.array([centered[block_indices(len(daily),rng)].mean() for _ in range(reps)])
    return float((1+(values>=daily.mean()).sum())/(reps+1))


def drawdown(values):
    x=np.r_[0,np.cumsum(np.asarray(values,float))]
    if not np.isfinite(x).all():raise ValueError('Nonfinite P&L')
    return float((np.maximum.accumulate(x)-x).max())


def monte_carlo(ledger,days,reps=1000):
    if reps<1:raise ValueError('Positive repetitions required')
    amounts=ledger.net.to_numpy(float);rng=np.random.default_rng(42)
    permutation=np.array([drawdown(rng.permutation(amounts)) for _ in range(reps)])
    # Preserve each day's whole closed-trade order inside resampled consecutive-day blocks.
    grouped={str(k):g.net.to_numpy(float) for k,g in ledger.groupby('day')}
    blocks=[grouped.get(str(d),np.array([],float)) for d in days]
    nets=[];dds=[]
    if blocks:
        for _ in range(reps):
            v=np.concatenate([blocks[i] for i in block_indices(len(blocks),rng)])
            nets.append(float(v.sum()));dds.append(drawdown(v))
    else:nets=[0.]*reps;dds=[0.]*reps
    return dict(repetitions=reps,actual_net=float(amounts.sum()),actual_drawdown=drawdown(amounts),
        shuffled_dd95=float(np.percentile(permutation,95)),shuffled_dd99=float(np.percentile(permutation,99)),
        shuffled_ending_net_constant=float(amounts.sum()),
        day_block_net_95_interval=np.percentile(nets,[2.5,97.5]).tolist(),day_block_dd95=float(np.percentile(dds,95)),
        note='Fixed-rupee closed-trade sequence diagnostics; no margin/cash gates replayed. Day-block bootstrap uses five consecutive sessions including zero-trade days. Permutations preserve total P&L and do not establish an edge.')


def qualifies(normal,stress):
    return bool(normal['trades']>=100 and normal['win_rate']>=.65 and normal['net']>0 and stress['net']>0
        and (normal['profit_factor'] or 0)>1 and normal['max_drawdown']<40000 and normal['worst_month']>-13000)


def select(scores):
    qualified=[k for k,(normal,stress) in scores.items() if qualifies(normal,stress)]
    return max(sorted(qualified),key=lambda k:scores[k][1]['net']) if qualified else None
