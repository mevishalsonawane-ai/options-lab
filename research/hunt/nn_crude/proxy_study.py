"""NN-CRUDE proxy study (long history).
A) hourly WTI(CL=F) x USDINR, May 2024 -> : next-60-min direction, anchored walk-forward by quarter on dev
   (< 2026-04-01); holdout quarter(s) scored ONCE with --holdout.
B) daily WTI x USDINR since 2000: next-day direction, anchored walk-forward by year 2006-2025, holdout 2026-04+.
Models: MLP (sklearn, 5 seeds), logistic, gradient boosting, random. Output logs/proxy_study.txt"""
import sys, warnings
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *
from model import MLPEns, make, COMMON
from sklearn.metrics import roc_auc_score
warnings.filterwarnings("ignore")
HOLD = "--holdout" in sys.argv
out = open(f"{LOGS}/proxy_study.txt", "a")
def P(*a):
    s = " ".join(str(x) for x in a); print(s, flush=True); out.write(s + "\n"); out.flush()


def wf(X, y, period, test_periods, models, purge):
    res = {m: pd.Series(np.nan, index=y.index) for m in models}
    for tp in test_periods:
        te = period == tp
        t0 = y.index[te].min()
        tr = y.index < (t0 - purge)
        if tr.sum() < 500 or te.sum() == 0:
            continue
        for mname in models:
            if mname == "random":
                res[mname][te] = np.random.default_rng(0).random(te.sum()); continue
            m = MLPEns((32,), 1e-2) if mname == "mlp" else make(mname)
            m.fit(X[tr], y.values[tr])
            res[mname][te] = m.predict_proba(X[te])
    return res


def report(res, y, period, label):
    rows = {}
    for m, s in res.items():
        ok = s.notna()
        per = {str(p): roc_auc_score(y[ok & (period == p)], s[ok & (period == p)]) for p in sorted(period[ok].unique())}
        per["ALL"] = roc_auc_score(y[ok], s[ok])
        per["acc"] = ((s[ok] > 0.5) == y[ok]).mean() if m != "random" else np.nan
        rows[m] = per
    t = pd.DataFrame(rows).T
    P(f"\n== {label}: AUC by period (OOS walk-forward)"); P(t.round(3).to_string())


# A) hourly
Pr = pd.read_parquet(f"{D}/feat_proxy.parquet")
Pr = Pr[Pr.y_ret60.notna() & (Pr.y_ret60 != 0)].copy()
Pr[COMMON] = Pr[COMMON].fillna(0.0)
if not HOLD:
    Pr = Pr[Pr.index < HOLDOUT_START]
y = (Pr.y_ret60 > 0).astype(int)
q = Pr.index.to_period("Q")
tests = [p for p in sorted(q.unique()) if p >= pd.Period("2025Q1")]
if not HOLD:
    tests = [p for p in tests if p.start_time < HOLDOUT_START]
else:
    tests = [p for p in tests if p.start_time >= HOLDOUT_START]
res = wf(Pr[COMMON].values, y, q, tests, ["mlp", "logit", "gbm", "random"], pd.Timedelta(days=1))
report(res, y, pd.Series(q.astype(str), index=y.index), f"A) hourly proxy WTIxINR next 60 min {'HOLDOUT' if HOLD else 'dev'}")

# B) daily since 2000
cl, inr, bz, dx, sp = (yahoo("1d", n) for n in ("CL_F", "INR_X", "BZ_F", "DX_Y_NYB", "_GSPC"))
for x in (cl, inr, bz, dx, sp):
    x.index = x.index.normalize()
df = pd.DataFrame({"cl": cl.close, "inr": inr.close, "bz": bz.close, "dx": dx.close, "sp": sp.close}).sort_index()
df = df[df.cl > 0].ffill().dropna(subset=["cl"])
df["inr"] = df.inr.ffill().bfill()
df = df[~((df.index >= "2020-04-15") & (df.index <= "2020-05-08"))]
lp = np.log(df.cl * df.inr)
r = lp.diff()
vol = r.rolling(20).std().shift(0)
Fd = pd.DataFrame(index=df.index)
for k in (1, 2, 5, 10, 20, 60):
    Fd[f"ret{k}"] = (lp - lp.shift(k)) / (vol * np.sqrt(k))
Fd["vol_ratio"] = np.log(vol / r.rolling(250).std())
Fd["inr1"] = np.log(df.inr).diff(); Fd["dx1"] = np.log(df.dx).diff(); Fd["sp1"] = np.log(df.sp).diff()
Fd["bzcl5"] = np.log(df.bz / df.cl).diff(5)
Fd["hi20"] = (lp - lp.rolling(20).max()) / vol; Fd["lo20"] = (lp - lp.rolling(20).min()) / vol
for k in range(5):
    Fd[f"dow{k}"] = (df.index.dayofweek == k).astype(float)
Fd["y"] = r.shift(-1)
Fd = Fd.replace([np.inf, -np.inf], np.nan).dropna()
Fd = Fd[Fd.y != 0]
if not HOLD:
    Fd = Fd[Fd.index < HOLDOUT_START]
feats = [c for c in Fd.columns if c != "y"]
yd = (Fd.y > 0).astype(int)
yr = pd.Series(Fd.index.year, index=Fd.index)
tests = list(range(2006, 2026)) if not HOLD else [2026]
if HOLD:
    yr = pd.Series(np.where(Fd.index >= HOLDOUT_START, 2026, 0), index=Fd.index)
res = wf(Fd[feats].clip(-10, 10).values, yd, yr, tests, ["mlp", "logit", "gbm", "random"], pd.Timedelta(days=2))
report(res, yd, yr, f"B) daily WTIxINR next-day direction {'HOLDOUT (2026-04..)' if HOLD else 'dev 2006-2025'}")
