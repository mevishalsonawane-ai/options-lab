"""Walk-forward (by quarter, expanding) size forecasts: HAR (OLS) and a small MLP. No P&L.
Holdout (2026-04-01+) uses the models fit on data before 2026-04-01 (frozen).
Usage: python3 -I models.py SCRATCH"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import warnings
import numpy as np, pandas as pd
from scipy.stats import spearmanr
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler

warnings.filterwarnings("ignore")
SCR = sys.argv[1]
HS = (1, 4, 24)
HOLD = pd.Timestamp("2026-04-01", tz="UTC")
EPS = 1e-5


def X_har(f):
    X = pd.DataFrame(index=f.index)
    for c in ["rv1p", "rv4p", "rv24p", "rv168p"]:
        X[c] = np.log(f[c] + EPS)
    X = pd.concat([X, pd.get_dummies(f.hod, prefix="h", drop_first=True).astype(float)], axis=1)
    return X


def X_nn(f, h):
    X = pd.DataFrame(index=f.index)
    for c in ["rv1p", "rv4p", "rv24p", "rv168p"]:
        X[c] = np.log(f[c] + EPS)
    X["dvol"] = np.log(f.dvol.ffill().bfill())
    X["fundz"] = f.fundz.fillna(0).clip(-5, 10)
    X["ev"] = f[f"ev{h}"]; X["ist"] = f[f"ist{h}"]
    X["hs"] = np.sin(2 * np.pi * f.hod / 24); X["hc"] = np.cos(2 * np.pi * f.hod / 24)
    X["wk"] = (f.dow >= 5).astype(float)
    X = pd.concat([X, pd.get_dummies(f.hod, prefix="h", drop_first=True).astype(float)], axis=1)
    return X


def fit_ols(X, y):
    A = np.c_[np.ones(len(X)), X]
    b, *_ = np.linalg.lstsq(A, y, rcond=None)
    return lambda Z: np.c_[np.ones(len(Z)), Z] @ b


def run(cur):
    f = pd.read_parquet(f"{SCR}/data/{cur}_feat.parquet")
    f = f[f.index >= "2021-04-01"]
    out = pd.DataFrame(index=f.index)
    quarters = pd.date_range("2022-01-01", "2026-04-01", freq="QS", tz="UTC")
    for h in HS:
        y = np.log(f[f"rv{h}f"] + EPS)
        Xh = X_har(f).values; Xn = X_nn(f, h).values
        ph = np.full(len(f), np.nan); pn = np.full(len(f), np.nan)
        for qi, q in enumerate(quarters):
            qend = quarters[qi + 1] if qi + 1 < len(quarters) else f.index[-1] + pd.Timedelta(hours=1)
            tr = (f.index + pd.Timedelta(hours=h) <= q) & y.notna().values
            te = (f.index >= q) & (f.index < qend)
            m = fit_ols(Xh[tr], y.values[tr])
            sc_h = np.mean(np.exp(y.values[tr])) / np.mean(np.exp(m(Xh[tr])))
            ph[te] = np.exp(m(Xh[te])) * sc_h
            ss = StandardScaler().fit(Xn[tr])
            nn = MLPRegressor(hidden_layer_sizes=(32, 16), early_stopping=True, random_state=7, max_iter=200, alpha=1e-3,
                              learning_rate_init=1e-3, batch_size=512)
            nn.fit(ss.transform(Xn[tr]), y.values[tr])
            sc_n = np.mean(np.exp(y.values[tr])) / np.mean(np.exp(nn.predict(ss.transform(Xn[tr]))))
            pn[te] = np.exp(nn.predict(ss.transform(Xn[te]))) * sc_n
            print(cur, h, q.date(), "train", int(tr.sum()), "test", int(te.sum()), flush=True)
        out[f"fh{h}"] = ph; out[f"fn{h}"] = pn
    # BIG thresholds: 70th pct of same-model forecasts, same hour-of-day, previous 30 days
    for c in [x for x in out.columns]:
        thr = out.groupby(f.hod)[c].transform(lambda s: s.rolling(30, min_periods=20).quantile(0.7).shift(1))
        out[c + "_p70"] = thr
    out.to_parquet(f"{SCR}/data/{cur}_fc.parquet")
    rep = []
    for h in HS:
        for per, msk in [("2022-01..2026-03", (out.index < HOLD)), ("holdout(not used)", out.index >= HOLD)]:
            if per.startswith("holdout"):
                continue
            yy = f[f"rv{h}f"]
            for mdl in ["fh", "fn"]:
                ok = msk & out[f"{mdl}{h}"].notna().values & yy.notna().values
                rep.append(dict(cur=cur, h=h, model={"fh": "HAR", "fn": "NN"}[mdl], period=per,
                                spearman=spearmanr(out[f"{mdl}{h}"][ok], yy[ok])[0],
                                spearman_ex_hod=spearmanr((out[f"{mdl}{h}"] / out[f"{mdl}{h}"].groupby(f.hod).transform("median"))[ok],
                                                          (yy / yy.groupby(f.hod).transform("median"))[ok])[0],
                                ratio_mean=float((out[f"{mdl}{h}"][ok]).mean() / yy[ok].mean())))
    return pd.DataFrame(rep)


if __name__ == "__main__":
    r = pd.concat([run(c) for c in sys.argv[2:] or ["BTC", "ETH"]])
    print(r.to_string())
    r.to_csv(f"{SCR}/logs/model_skill_{'_'.join(sys.argv[2:])}.csv", index=False)
