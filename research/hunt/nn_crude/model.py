"""NN-CRUDE models: MLP (sklearn, seed-ensemble), logistic regression, gradient boosting; anchored monthly
walk-forward on the DEV period with purge+embargo. Never reads rows >= HOLDOUT_START (evaluate.py does that once).
Usage: python3 model.py            -> dev walk-forward, hyper-parameter choice, OOS scores, logs
Outputs: data/oos_dev.parquet, models/choice.json, logs/model_dev.txt"""
import sys, json, time, warnings
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *
from sklearn.neural_network import MLPClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import roc_auc_score, brier_score_loss
warnings.filterwarnings("ignore")

SPEC = json.load(open(f"{MODELS}/feature_spec.json"))
G = SPEC["groups"]
FULL = G["seq"] + G["mcx"] + G["opt"] + G["common"]
COMMON = G["common"]
HOR = SPEC["horizons"]
MLP_GRID = [dict(alpha=a, hidden=h) for a in (1e-3, 1e-2, 1e-1) for h in ((64, 32), (32,))]
SEEDS = (0, 1, 2, 3, 4)
out = open(f"{LOGS}/model_dev.txt", "a")
def P(*a):
    s = " ".join(str(x) for x in a); print(s, flush=True); out.write(s + "\n"); out.flush()


def load(holdout=False):
    F = pd.read_parquet(f"{D}/feat_mcx.parquet")
    if not holdout:
        F = F[F.index < HOLDOUT_START]
    F[FULL] = F[FULL].fillna(0.0)          # long look-backs are NaN early in the session (no overnight look-back)
    return F


class MLPEns:
    def __init__(self, hidden=(64, 32), alpha=1e-2, seeds=SEEDS, pre=None):
        self.hidden, self.alpha, self.seeds, self.pre = hidden, alpha, seeds, pre

    def fit(self, X, y):
        self.sc = StandardScaler().fit(X)
        Xs = np.clip(self.sc.transform(X), -5, 5)
        self.ms = []
        for s in self.seeds:
            if self.pre is not None:            # warm start from a proxy-pretrained net (same inputs)
                import copy
                m = copy.deepcopy(self.pre[s % len(self.pre)])
                m.set_params(warm_start=True, max_iter=60, early_stopping=False)
                m.best_loss_ = np.inf      # pretrained with early stopping -> best_loss_ is None
            else:
                m = MLPClassifier(hidden_layer_sizes=self.hidden, alpha=self.alpha, learning_rate_init=1e-3,
                                  batch_size=256, max_iter=200, early_stopping=True, validation_fraction=0.15,
                                  n_iter_no_change=8, random_state=s)
            m.fit(Xs, y)
            self.ms.append(m)
        return self

    def predict_proba(self, X):
        Xs = np.clip(self.sc.transform(X), -5, 5)
        return np.mean([m.predict_proba(Xs)[:, 1] for m in self.ms], axis=0)


def make(kind, cfg=None, pre=None):
    if kind == "mlp":
        return MLPEns(cfg["hidden"], cfg["alpha"])
    if kind == "mlp_pre":
        return MLPEns(pre=pre)
    if kind == "logit":
        class L:
            def fit(s, X, y):
                s.sc = StandardScaler().fit(X); s.m = LogisticRegression(C=0.1, max_iter=500).fit(np.clip(s.sc.transform(X), -5, 5), y); return s
            def predict_proba(s, X):
                return s.m.predict_proba(np.clip(s.sc.transform(X), -5, 5))[:, 1]
        return L()
    if kind == "gbm":
        class Gb:
            def fit(s, X, y):
                s.m = HistGradientBoostingClassifier(max_depth=3, max_iter=200, learning_rate=0.05, l2_regularization=1.0,
                                                     random_state=0).fit(X, y); return s
            def predict_proba(s, X):
                return s.m.predict_proba(X)[:, 1]
        return Gb()


def pretrain_proxy(hidden=(32,), alpha=1e-2, end=HOLDOUT_START):
    """Train an MLP per seed on the hourly WTI x USDINR proxy (COMMON features, 60-min target), dev period only."""
    Pr = pd.read_parquet(f"{D}/feat_proxy.parquet")
    Pr = Pr[(Pr.index < end) & Pr.y_ret60.notna() & (Pr.y_ret60 != 0)].copy()
    Pr[COMMON] = Pr[COMMON].fillna(0.0)
    X, y = Pr[COMMON].values, (Pr.y_ret60 > 0).astype(int).values
    sc = StandardScaler().fit(X)
    nets = []
    for s in SEEDS:
        m = MLPClassifier(hidden_layer_sizes=hidden, alpha=alpha, batch_size=256, max_iter=200, early_stopping=True,
                          validation_fraction=0.15, n_iter_no_change=8, random_state=s)
        m.fit(np.clip(sc.transform(X), -5, 5), y)
        nets.append(m)
    return nets, sc, Pr


def folds(F, h):
    months = sorted(F.index.to_period("M").unique())
    for k in range(2, len(months)):
        te = months[k]
        t0 = te.start_time
        tr_mask = F.index < (t0 - pd.Timedelta(days=1))                          # embargo 1 day
        tr_mask &= (F.index + pd.Timedelta(minutes=h)) < (t0 - pd.Timedelta(days=1))  # purge label overlap
        te_mask = F.index.to_period("M") == te
        yield str(te), tr_mask, te_mask


def run_dev():
    F = load()
    P(f"\n===== dev walk-forward {time.strftime('%Y-%m-%d %H:%M')}  rows {len(F)}  {F.index.min()} .. {F.index.max()}")
    oos = []
    choice = {}
    # proxy pretraining (COMMON features); the pretrained nets are fit on proxy data before each test month
    # (proxy rows < test month start - 1 day), to keep the walk-forward honest.
    hors = [int(x) for x in os.environ["NN_H"].split(",")] if os.environ.get("NN_H") else HOR
    for h in hors:
        y_all = F[f"y_ret{h}"]
        ok = y_all.notna() & (y_all != 0)
        Fh = F[ok]
        yd = (Fh[f"y_ret{h}"] > 0).astype(int)
        absr = Fh[f"y_ret{h}"].abs()
        res = {}
        variants = [("mlp", c) for c in MLP_GRID] + [("logit", None), ("gbm", None), ("mlp_common", None), ("mlp_pre", None)]
        for kind, cfg in variants:
            tag = kind if cfg is None else f"mlp_a{cfg['alpha']}_h{'x'.join(map(str, cfg['hidden']))}"
            cf = f"{D}/oos_cache/{h}_{tag}.parquet"
            if os.path.exists(cf):
                c = pd.read_parquet(cf)
                res[tag] = roc_auc_score((c.y > 0).astype(int), c.score)
                oos.append(c); P(f"h={h:>2} {tag:<22} AUC {res[tag]:.4f} (cached)"); continue
            feats = COMMON if kind in ("mlp_common", "mlp_pre") else FULL
            scores = pd.Series(np.nan, index=Fh.index); sz = pd.Series(np.nan, index=Fh.index)
            t0 = time.time()
            for te, trm, tem in folds(Fh, h):
                Xtr, Xte = Fh.loc[trm, feats].values, Fh.loc[tem, feats].values
                if kind == "mlp_pre":
                    nets, psc, _ = pretrain_proxy(end=pd.Period(te).start_time - pd.Timedelta(days=1))
                    m = MLPEns(pre=nets).fit(Xtr, yd[trm].values)
                elif kind == "mlp_common":
                    m = MLPEns((32,), 1e-2).fit(Xtr, yd[trm].values)
                else:
                    m = make(kind, cfg).fit(Xtr, yd[trm].values)
                scores[tem] = m.predict_proba(Xte)
                # size target: |ret| above the training median (same model family)
                med = absr[trm].median()
                ms = make("logit").fit(Xtr, (absr[trm] > med).astype(int).values) if kind != "mlp" else \
                     MLPEns(cfg["hidden"], cfg["alpha"], seeds=(0,)).fit(Xtr, (absr[trm] > med).astype(int).values)
                sz[tem] = ms.predict_proba(Xte)
            msk = scores.notna()
            auc = roc_auc_score(yd[msk], scores[msk])
            med_all = absr[msk].median()
            auc_sz = roc_auc_score((absr[msk] > med_all).astype(int), sz[msk])
            per_m = {str(p): round(roc_auc_score(yd[msk][scores[msk].index.to_period('M') == p], scores[msk][scores[msk].index.to_period('M') == p]), 3)
                     for p in sorted(scores[msk].index.to_period("M").unique())}
            acc = ((scores[msk] > 0.5).astype(int) == yd[msk]).mean()
            brier = brier_score_loss(yd[msk], scores[msk])
            res[tag] = auc
            P(f"h={h:>2} {tag:<22} AUC {auc:.4f} acc {acc:.4f} brier {brier:.4f} (flat-0.5 brier 0.25) | size-AUC {auc_sz:.3f} | per month {per_m} | {time.time()-t0:.0f}s")
            c = pd.DataFrame({"h": h, "model": tag, "score": scores[msk], "size": sz[msk], "y": Fh.loc[msk, f"y_ret{h}"]})
            os.makedirs(f"{D}/oos_cache", exist_ok=True); c.to_parquet(cf)
            oos.append(c)
        mlps = {k: v for k, v in res.items() if k.startswith("mlp_a")}
        best = max(mlps, key=mlps.get)
        choice[str(h)] = {"best_mlp": best, "dev_auc": round(mlps[best], 4), "all": {k: round(v, 4) for k, v in res.items()}}
        P(f"h={h}: chosen MLP config {best} (dev WF AUC {mlps[best]:.4f}); base rate up {yd.mean():.3f}")
    O = pd.concat(oos)
    O.to_parquet(f"{D}/oos_dev.parquet", compression="zstd")
    json.dump(choice, open(f"{MODELS}/choice.json", "w"), indent=1)
    P("variants tried per horizon:", len(MLP_GRID) + 4, "x", len(HOR), "horizons =", (len(MLP_GRID) + 4) * len(HOR))


if __name__ == "__main__":
    run_dev()
