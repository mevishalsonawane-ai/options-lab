from itertools import groupby
import numpy as np
import pandas as pd

CAPITAL = 100000


def select(meta, scores, threshold):
    frame = meta.assign(score=scores)
    chosen = frame.loc[frame.groupby(['day','clock']).score.idxmax()]
    return chosen[chosen.score>=threshold].sort_values(['day','clock'])


def measure(chosen, mode, sessions):
    cash = CAPITAL
    trades = []
    for row in chosen.to_dict('records'):
        if row['filled'] and row['debit'] <= cash:
            cash += row[mode]
            trades.append(row)
    d = pd.DataFrame(trades, columns=chosen.columns)
    v = d[mode].to_numpy(float)
    eq = np.r_[0,v.cumsum()]
    dd = float((np.maximum.accumulate(eq)-eq).max())
    dates = pd.Index(sessions)
    daily = d.groupby('day')[mode].sum().reindex(dates,fill_value=0).astype(float)
    counts = d.groupby('day')[mode].count().reindex(dates,fill_value=0).to_numpy()
    wins = d[mode].gt(0).groupby(d.day).sum().reindex(dates,fill_value=0).to_numpy(float)
    rng = np.random.default_rng(24)
    ids = rng.integers(len(dates),size=(3000,len(dates)))
    ns = counts[ids].sum(axis=1)
    valid = ns>0
    def interval(series):
        return np.quantile(series[valid]/ns[valid],[.025,.975]).tolist() if valid.any() else None
    def ratio(a,b):
        return float(a/b) if b else None
    pos, neg = v[v>0],v[v<0]
    # Returns use preceding session's equity; idle sessions are included.
    returns = daily/(CAPITAL+daily.cumsum().shift(fill_value=0))
    def cuts(key):
        return {str(k):{'trades':len(g),'net':round(float(g[mode].sum()),2)} for k,g in d.groupby(key)}
    perm_dd = []
    for _ in range(500):
        s = np.r_[0,rng.permutation(v).cumsum()]
        perm_dd.append((np.maximum.accumulate(s)-s).max())
    return dict(trades=len(v),wins=len(pos),losses=len(neg),flat=int(sum(v==0)),win_rate=ratio(len(pos),len(v)),
                net=float(v.sum()),gross_winners_after_cost=float(pos.sum()),gross_losers_after_cost=float(neg.sum()),
                avg_net=ratio(v.sum(),len(v)),average_win=ratio(pos.sum(),len(pos)),average_loss=ratio(neg.sum(),len(neg)),
                profit_factor=ratio(pos.sum(),-neg.sum()),max_drawdown=dd,recovery=ratio(v.sum(),dd),
                max_consecutive_losses=max((len(list(g)) for k,g in groupby(v<0) if k), default=0),
                sharpe=ratio(returns.mean()*np.sqrt(252),returns.std()),sortino=ratio(returns.mean()*np.sqrt(252),np.sqrt(np.mean(np.minimum(0,returns)**2))),
                return_on_capital=float(v.sum()/CAPITAL),max_entry_debit=float(d.debit.max()) if len(d) else 0,
                account=CAPITAL,monthly=cuts(d.day.str[:7]),side=cuts('side'),expiry=cuts(d.dte.eq(0)),regime=cuts('regime'),
                session_bootstrap_avg_ci=interval(daily.to_numpy()[ids].sum(axis=1)),session_bootstrap_win_ci=interval(wins[ids].sum(axis=1)),
                shuffled_drawdown_p95=float(np.quantile(perm_dd,.95)),unfilled=int((~chosen.filled).sum()),
                gross_before_cost=float(d.gross.sum()) if mode.startswith('timed') else None)
