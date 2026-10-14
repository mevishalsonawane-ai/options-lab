"""RC2 money, tax and statistics: small-account sizing, daily loss cap, the two Indian tax readings, BH, random twins,
walk-forward."""
import numpy as np
import pandas as pd

from . import config as C

IST = pd.Timedelta(hours=5, minutes=30)


def ist_day(ts):
    return (pd.DatetimeIndex(ts) + IST).floor("D").tz_localize(None)


def money(tr, account, risk=C.RISK_PER_TRADE, cap=C.DAILY_LOSS_CAP):
    """Size each trade so the premium paid (with the entry fee) is at most risk x account (whole lots, none if one lot
    is dearer), and stop the rule for the IST day once its realised loss reaches cap x account. Fixed account (no
    compounding), so results add up across years. Returns the trades with lots and rupee P&L."""
    if tr.empty:
        return tr.assign(lots=[], inr=[], sale_inr=[], day=[])
    tr = tr.sort_values("T").copy()
    tr["day"] = ist_day(tr["T"])
    budget = risk * account
    lots, inr, sale = [], [], []
    day_pnl = {}
    for r in tr.itertuples():
        n = int(budget // (r.cost_lot * C.USDINR))
        if day_pnl.get(r.day, 0.0) <= -cap * account or n < 1:
            lots.append(0); inr.append(0.0); sale.append(0.0)
            continue
        p = n * r.pnl_lot * C.USDINR
        day_pnl[r.day] = day_pnl.get(r.day, 0.0) + p
        lots.append(n); inr.append(p); sale.append(n * r.sell * C.LOT[r.coin] * C.USDINR)
    tr["lots"], tr["inr"], tr["sale_inr"] = lots, inr, sale
    return tr


def fin_year(ts):
    t = pd.DatetimeIndex(ts) + IST
    return np.where(t.month >= 4, t.year, t.year - 1)


def taxes(m):
    """Rupees after tax under the two readings. VDA: 31.2% of every winning trade, losses never set off.
    Business: 31.2% of each financial year's net profit, losses set off and carried forward. TDS: 1% of sale value
    (a prepayment, not a cost, if it applies)."""
    t = m[m.lots > 0]
    if t.empty:
        return dict(net=0.0, vda=0.0, business=0.0, tds=0.0)
    net = t.inr.sum()
    vda = net - C.TAX * t.inr.clip(lower=0).sum()
    carry, tax = 0.0, 0.0
    for fy, g in t.groupby(fin_year(t["T"])):
        p = g.inr.sum() + carry
        if p > 0:
            tax += C.TAX * p
            carry = 0.0
        else:
            carry = p
    return dict(net=float(net), vda=float(vda), business=float(net - tax), tds=float(C.TDS * t.sale_inr.sum()))


def summary(m, start, end):
    """Per-rule report numbers on money() output over [start, end)."""
    t = m[m.lots > 0]
    days = max((end - start).total_seconds() / 86400, 1)
    out = dict(trades=int(len(t)), trades_per_day=len(t) / days)
    if t.empty:
        return out
    w, l = t[t.inr > 0], t[t.inr <= 0]
    daily = t.groupby("day").inr.sum()
    cum = t.inr.cumsum()
    lock = t[t.reason == "lock"]
    out.update(
        win_rate=len(w) / len(t), avg_win_inr=w.inr.mean() if len(w) else 0.0, avg_loss_inr=l.inr.mean() if len(l) else 0.0,
        avg_win_ret=w.ret.mean() if len(w) else 0.0, avg_loss_ret=l.ret.mean() if len(l) else 0.0,
        mean_ret=t.ret.mean(), median_ret=t.ret.median(), net_inr=t.inr.sum(), inr_per_day=t.inr.sum() / days,
        green_days=(daily > 0).mean(), trade_days=len(daily), worst_day=daily.min(), best_day=daily.max(),
        max_dd=float((cum.cummax().clip(lower=0) - cum).max()),
        lock_exits=len(lock), lock_cut_2x=int((lock.post_max >= 2 * lock.level / lock.mid).sum()),
        stops=int((t.reason == "stop").sum()), times=int((t.reason == "time").sum()),
        top3_share=t.inr.nlargest(3).sum() / t.inr.sum() if t.inr.sum() > 0 else np.nan,
        mean_lots=t.lots.mean())
    out.update(taxes(m))
    return out


def bh(p):
    """Benjamini-Hochberg q-values."""
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank, i in reversed(list(enumerate(o, start=1))):
        prev = min(prev, p[i] * n / rank)
        q[i] = prev
    return q


def p_twin(actual_mean, pool_ret, n, draws=2000, seed=0):
    """Share of random draws (n trades from the pool, without replacement) whose mean return is >= the rule's."""
    pool_ret = np.asarray(pool_ret, float)
    if n == 0 or len(pool_ret) < n:
        return np.nan
    rng = np.random.default_rng(seed)
    means = np.array([pool_ret[rng.choice(len(pool_ret), n, replace=False)].mean() for _ in range(draws)])
    return float((np.sum(means >= actual_mean) + 1) / (draws + 1))


def p_direction(ret_same, ret_flip, draws=2000, seed=0):
    """Same entry times, a coin flip for call or put: share of draws whose mean is >= the rule's."""
    a, b = np.asarray(ret_same, float), np.asarray(ret_flip, float)
    if len(a) == 0:
        return np.nan
    rng = np.random.default_rng(seed)
    act = a.mean()
    hits = 0
    for _ in range(draws):
        pick = rng.random(len(a)) < 0.5
        hits += np.where(pick, a, b).mean() >= act
    return float((hits + 1) / (draws + 1))
