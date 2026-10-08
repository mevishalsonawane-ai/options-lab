"""Apply the pre-registered rule grid to the simulated straddles, size with Rs 1 lakh, tax both ways, compare with
random entries, BH across variants, walk-forward by quarter, then the holdout once ("partly seen").
Usage: python3 -I rules.py SCRATCH"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import itertools, json
import numpy as np, pandas as pd

SCR = sys.argv[1]
CURS = ["BTC", "ETH"]
LOT = {"BTC": 0.001, "ETH": 0.01}
FX = 96.78  # USD/INR 8 Oct 2026, same as research/NN_CRYPTO.md
CAP0 = 100000.0
FRAC = 0.05
TAX = 0.312
EVAL0 = pd.Timestamp("2022-01-01", tz="UTC")
HOLD = pd.Timestamp("2026-04-01", tz="UTC")
HZH = {"1": 1, "4": 4, "24": 24, "X": 24}
EXITS = ["time", "+30/-30", "+30/-50", "+60/-30", "+60/-50"]
SIGS = ["ALWAYS", "CLOCK13", "BIG_HAR", "BIG_NN", "CHEAP_HAR0", "CHEAP_HAR20", "CHEAP_NN0", "BIG+CHEAP_HAR",
        "BIG+CHEAP_NN", "EVENT", "EVENT+CHEAP", "IST", "IST+CHEAP", "FUND+CHEAP"]
RNG = np.random.default_rng(11)
NRAND = 2000


def load():
    D = {}
    for cur in CURS:
        f = pd.read_parquet(f"{SCR}/data/{cur}_feat.parquet")
        fc = pd.read_parquet(f"{SCR}/data/{cur}_fc.parquet").reindex(f.index)
        sim = pd.read_parquet(f"{SCR}/data/{cur}_sim.parquet")
        D[cur] = (f, fc, sim)
    return D


def signals(f, fc, info, hz):
    h = HZH[hz]
    imp = info.iv0 * np.sqrt(h / 8760.0)
    fh, fn = fc[f"fh{h}"], fc[f"fn{h}"]
    big_h = fh > fc[f"fh{h}_p70"]; big_n = fn > fc[f"fn{h}_p70"]
    ch0 = fh >= imp; ch20 = fh >= 1.2 * imp; cn0 = fn >= imp
    ev = f[f"ev{h}"] == 1
    ist = f[f"ist{h}"] == 1
    fund = f.fund8.abs() > f.fund_p95
    s = {"ALWAYS": pd.Series(True, index=f.index), "CLOCK13": pd.Series(f.index.hour == 13, index=f.index),
         "BIG_HAR": big_h, "BIG_NN": big_n, "CHEAP_HAR0": ch0, "CHEAP_HAR20": ch20, "CHEAP_NN0": cn0,
         "BIG+CHEAP_HAR": big_h & ch0, "BIG+CHEAP_NN": big_n & cn0, "EVENT": ev, "EVENT+CHEAP": ev & ch0,
         "IST": ist, "IST+CHEAP": ist & ch0, "FUND+CHEAP": fund & ch0}
    ok = fh.notna() & fn.notna() & info.iv0.notna()
    return {k: (v.fillna(False) & ok) for k, v in s.items()}, ok, imp


def take(H, kexit, mask):
    """greedy non-overlapping trades (one open position per asset)."""
    idx = np.flatnonzero(mask)
    if len(idx) == 0:
        return idx
    Hs = H[idx]
    ends = H + kexit * np.timedelta64(5, "m")
    out = []
    i = 0
    while i < len(idx):
        j = idx[i]
        out.append(j)
        i = np.searchsorted(Hs, ends[j], side="left")
    return np.array(out)


def fy_of(t):
    return t.year - (t.month < 4)


def money(tr, mode="pre"):
    """Size and book trades in time order. mode 'pre': no tax; 'A': strict VDA, 31.2% of each winning trade paid at
    exit, sizing on after-tax capital; 'B': business income, 31.2% of each FY's net profit (losses set off and
    carried forward), paid at FY end (the last, partial FY is taxed at the end of the run)."""
    tr = tr.sort_values("H").reset_index(drop=True)
    n = len(tr)
    pnl = np.zeros(n); lots = np.zeros(n); tax = np.zeros(n)
    endv = tr.end.values; Hv = tr.H.values
    order_end = np.argsort(endv, kind="stable")
    ptr = 0
    cap = CAP0
    fy_pnl = {}; carry = 0.0; taxed_fy = set(); taxB = []
    unit_all = (tr.cost * tr.S * tr.cur.map(LOT) * FX).values
    netv = tr.net.values
    endts = pd.to_datetime(endv).tz_localize("UTC")
    for i in range(n + 1):
        t_now = Hv[i] if i < n else np.datetime64("2100-01-01")
        while ptr < n and endv[order_end[ptr]] <= t_now:
            k = order_end[ptr]
            cap += pnl[k] - (tax[k] if mode == "A" else 0.0)
            y = fy_of(endts[k]); fy_pnl[y] = fy_pnl.get(y, 0.0) + pnl[k]
            ptr += 1
        if mode == "B":
            cur_fy = fy_of(pd.Timestamp(t_now).tz_localize("UTC")) if i < n else 10 ** 6
            for y in sorted(fy_pnl):
                if y < cur_fy and y not in taxed_fy:
                    p = fy_pnl[y] - carry
                    tx = TAX * p if p > 0 else 0.0
                    carry = 0.0 if p > 0 else -p
                    cap -= tx; taxed_fy.add(y); taxB.append((y, tx))
        if i == n:
            break
        lots[i] = np.floor(max(cap, 0) * FRAC / unit_all[i]) if unit_all[i] > 0 else 0
        pnl[i] = lots[i] * unit_all[i] * netv[i]
        tax[i] = TAX * max(pnl[i], 0)
    tr["lots"] = lots; tr["pnl"] = pnl; tr["tax"] = tax if mode == "A" else 0.0
    tr.attrs["taxB"] = sum(x for _, x in taxB) if mode == "B" else 0.0
    tr.attrs["final_cap"] = cap
    return tr


def curve(tr, t0, t1, mode):
    tr = tr.sort_values("end")
    flow = tr.pnl - (tr.tax if mode == "A" else 0.0)
    eq = CAP0 + flow.cumsum()
    dd = float((eq.cummax() - eq).max())
    months = pd.period_range(t0.tz_convert(None).to_period("M"), (t1 - pd.Timedelta(seconds=1)).tz_convert(None).to_period("M"), freq="M")
    mp = flow.groupby(tr.end.dt.tz_convert(None).dt.to_period("M")).sum().reindex(months, fill_value=0)
    return dd, float((mp > 0).mean()), len(mp)


def stats(trs, t0, t1, label):
    """trs: dict mode -> booked trades (same trades, different sizing)."""
    days = (t1 - t0).days
    tr = trs["pre"]
    if len(tr) == 0:
        return dict(rule=label, trades=0)
    A, B = trs["A"], trs["B"]
    netA = float(A.pnl.sum() - A.tax.sum()); netB = float(B.pnl.sum() - B.attrs["taxB"])
    dd, green, nm = curve(tr, t0, t1, "pre")
    ddA, greenA, _ = curve(A, t0, t1, "A")
    gross_rs = (tr.lots * tr.cost * tr.S * tr.cur.map(LOT) * FX * tr.gross).sum()
    return dict(rule=label, trades=len(tr), hit=float((tr.net > 0).mean()), gross_pct=float(tr.gross.mean() * 100),
                net_pct=float(tr.net.mean() * 100), gross_rs=float(gross_rs), net_rs=float(tr.pnl.sum()),
                taxA_rs=netA, taxB_rs=netB, rs_day_net=float(tr.pnl.sum() / days), rs_day_A=netA / days,
                rs_day_B=netB / days, maxdd_rs=dd, maxdd_A=ddA, green_months=green, green_months_A=greenA, months=nm,
                ruin=bool(min(CAP0 + tr.sort_values("end").pnl.cumsum().min(), CAP0) < 25000),
                tds_rs=float(0.01 * (tr.lots * tr.S * tr.cur.map(LOT) * FX * tr.cost * (1 + tr.net)).sum()))


def book(tr):
    return {m: money(tr.copy(), m) for m in ("pre", "A", "B")}


def main():
    D = load()
    pools = {}   # (cur, hz, ex) -> DataFrame of all eligible hours with net/gross/etc (delta scenario + others)
    sigs = {}
    gap_rows = []
    for cur in CURS:
        f, fc, sim = D[cur]
        for hz in HZH:
            info = sim[(sim.hz == hz) & (sim.sc == "info")].set_index("H").reindex(f.index)
            S, ok, imp = signals(f, fc, info, hz)
            sigs[(cur, hz)] = S
            g = pd.DataFrame(dict(H=f.index, cur=cur, hz=hz, iv0=info.iv0.values, src=info.why.values, imp=imp.values,
                                  rv=f[f"rv{HZH[hz]}f"].values, mid0=info.cost.values, end_mid=info.end_mid.values,
                                  T0=info.T0.values, fh=fc[f"fh{HZH[hz]}"].values, fn=fc[f"fn{HZH[hz]}"].values,
                                  ok=ok.values, hod=f.index.hour))
            gap_rows.append(g)
            for sc in ["delta", "deribit", "delta2x"]:
                for ex in range(5):
                    s = sim[(sim.hz == hz) & (sim.sc == sc) & (sim.ex == ex)].set_index("H").reindex(f.index)
                    pools[(cur, hz, ex, sc)] = pd.DataFrame(dict(H=f.index, cur=cur, S=f.S.values, net=s.net.values,
                                                                 gross=s.gross.values, cost=s.cost.values,
                                                                 kexit=s.kexit.values, why=s.why.values))
    gap = pd.concat(gap_rows)
    gap.to_parquet(f"{SCR}/data/gap.parquet")

    def trades_for(sig, hz, ex, sc, t0, t1):
        out = []
        for cur in CURS:
            p = pools[(cur, hz, ex, sc)]
            m = sigs[(cur, hz)][sig].values & p.net.notna().values & (p.H >= t0).values & (p.H < t1).values
            j = take(p.H.values, np.nan_to_num(p.kexit.values, nan=1).astype(int), m)
            t = p.iloc[j].copy()
            t["end"] = t.H + pd.to_timedelta(t.kexit * 5, unit="m")
            # gross P&L in Rs is measured on the premium paid (same lots), gross ratio applied to mid-based cost
            t["gross_on_cost"] = t.gross
            out.append(t)
        return pd.concat(out)

    def eligible(hz, ex, sc, t0, t1, cur):
        p = pools[(cur, hz, ex, sc)]
        m = sigs[(cur, hz)]["ALWAYS"].values & p.net.notna().values & (p.H >= t0).values & (p.H < t1).values
        return p.net.values[m], p.H.dt.hour.values[m]

    variants = list(itertools.product(SIGS, HZH.keys(), range(5)))
    res = []; TR = {}
    for sig, hz, ex in variants:
        lab = f"{sig}|{hz}|{EXITS[ex]}"
        for per, t0, t1 in [("pre", EVAL0, HOLD), ("hold", HOLD, pd.Timestamp("2026-10-09", tz="UTC"))]:
            tr = trades_for(sig, hz, ex, "delta", t0, t1)
            trs = book(tr) if len(tr) else {"pre": tr}
            tr = trs["pre"]
            TR[(lab, per)] = tr
            st = stats(trs, t0, min(t1, pd.Timestamp.now(tz="UTC").floor("D")), lab)
            st.update(period=per, sig=sig, hz=hz, ex=EXITS[ex])
            if len(tr):
                # simple, path-free money view: a fixed Rs 5,000 of premium per straddle, no compounding
                st["fixed5k_net_rs"] = float(5000 * tr.net.sum())
                st["fixed5k_gross_rs"] = float(5000 * tr.gross.sum())
                st["fixed5k_taxA_rs"] = float(5000 * (tr.net - TAX * tr.net.clip(lower=0)).sum())
            if len(tr) >= 5:
                # random baseline: same number of trades per asset at random eligible hours
                rmeans = np.zeros(NRAND); hmeans = np.zeros(NRAND)
                for cur in CURS:
                    tc = tr[tr.cur == cur]
                    k = len(tc)
                    if k == 0:
                        continue
                    pool, phod = eligible(hz, ex, "delta", t0, t1, cur)
                    draws = pool[RNG.integers(0, len(pool), size=(NRAND, k))]
                    rmeans += draws.sum(1)
                    # post-hoc extra: random hours with the SAME hour-of-day mix as the rule's trades
                    for hd, kh in tc.H.dt.hour.value_counts().items():
                        ph = pool[phod == hd]
                        hmeans += ph[RNG.integers(0, len(ph), size=(NRAND, kh))].sum(1)
                rmeans /= len(tr); hmeans /= len(tr)
                st["randhod_mean_pct"] = float(hmeans.mean() * 100)
                st["p_rand_hod"] = float((np.sum(hmeans >= tr.net.mean()) + 1) / (NRAND + 1))
                st["rand_mean_pct"] = float(rmeans.mean() * 100)
                st["p_rand"] = float((np.sum(rmeans >= tr.net.mean()) + 1) / (NRAND + 1))
                bs = tr.net.values[RNG.integers(0, len(tr), size=(NRAND, len(tr)))].mean(1)
                st["p_pos"] = float((np.sum(bs <= 0) + 1) / (NRAND + 1))
                for sc in ["deribit", "delta2x"]:
                    t2 = trades_for(sig, hz, ex, sc, t0, t1)
                    st[f"net_pct_{sc}"] = float(t2.net.mean() * 100)
            res.append(st)
        print(lab, flush=True)
    R = pd.DataFrame(res)
    allt = pd.concat([t.assign(rule=k[0], per=k[1])[["rule", "per", "H", "end", "cur", "net", "gross", "lots", "pnl", "why"]]
                      for k, t in TR.items() if len(t)])
    allt.to_parquet(f"{SCR}/data/all_trades.parquet")
    # BH over the 280 pre-holdout random-p values
    pre = R[(R.period == "pre") & R.p_rand.notna()].copy()
    m = len(pre)
    o = np.argsort(pre.p_rand.values)
    q = np.empty(m); prev = 1.0
    for rank in range(m - 1, -1, -1):
        i = o[rank]
        prev = min(prev, pre.p_rand.values[i] * m / (rank + 1)); q[i] = prev
    pre["q_bh"] = q
    R = R.merge(pre[["rule", "q_bh"]], on="rule", how="left")
    R.loc[R.period == "hold", "q_bh"] = np.nan
    R.to_csv(f"{SCR}/logs/rules_all.csv", index=False)

    # walk-forward by quarter: choose on all evaluated pre-holdout data before the quarter
    wf = []
    qs = pd.date_range("2023-01-01", "2026-04-01", freq="QS", tz="UTC")
    labs = [f"{s}|{h}|{EXITS[e]}" for s, h, e in variants]
    for qi in range(len(qs) - 1):
        q0, q1 = qs[qi], qs[qi + 1]
        best, bv = None, 0.0
        for lab in labs:
            tr = TR[(lab, "pre")]
            if len(tr) == 0:
                continue
            past = tr[tr.end < q0]
            if len(past) >= 40 and past.net.mean() > bv:
                best, bv = lab, past.net.mean()
        if best is None:
            wf.append(dict(q=str(q0.date()), pick="stand aside", trades=0)); continue
        tr = TR[(best, "pre")]
        cur_q = tr[(tr.H >= q0) & (tr.H < q1)]
        wf.append(dict(q=str(q0.date()), pick=best, train_net_pct=bv * 100, trades=len(cur_q),
                       net_pct=float(cur_q.net.mean() * 100) if len(cur_q) else np.nan, _tr=cur_q))
    wtr = [w.pop("_tr") for w in wf if "_tr" in w]
    W = pd.DataFrame(wf)
    if wtr:
        allw = pd.concat(wtr)[["H", "cur", "S", "net", "gross", "cost", "kexit", "why", "end", "gross_on_cost"]]
        wst = stats(book(allw), qs[0], HOLD, "WALK-FORWARD (quarterly re-pick)")
    else:
        wst = dict(rule="WALK-FORWARD", trades=0)
    W.to_csv(f"{SCR}/logs/walkforward.csv", index=False)
    # final rule: best on all pre-holdout data (>= 40 trades), run once on holdout
    P = R[(R.period == "pre") & (R.trades >= 40)].sort_values("net_pct", ascending=False)
    final = P.iloc[0].rule
    hold = R[(R.period == "hold") & (R.rule == final)].iloc[0].to_dict()
    json.dump(dict(final=final, pre=P.iloc[0].to_dict(), hold=hold, wf=wst), open(f"{SCR}/logs/final.json", "w"), indent=1, default=str)
    print("FINAL", final); print(json.dumps(hold, default=str, indent=1)); print("WF", wst)
    # keep trade lists of the final rule
    TR[(final, "pre")].to_csv(f"{SCR}/logs/final_trades_pre.csv", index=False)
    TR[(final, "hold")].to_csv(f"{SCR}/logs/final_trades_hold.csv", index=False)


if __name__ == "__main__":
    main()
