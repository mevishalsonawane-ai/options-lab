"""R12 the improvement loop - DESIGN PERIOD ONLY (trades before 2025-10-01; holdout rows are never replayed here).

    python3 -I research/hunt/r12/loop.py            -> <scratch>/hunt/r12/loop_log.csv, loop_rounds.csv, D_<arm>.npz,
                                                       singles.csv (twins + BH), whiteRC.json, final_design.json

Round 1  each specialist alone (exit specialists and lock specialists), full declared grids.
Round 2  exit committees: pairs (OR = quorum 1, AND = quorum 2) and triples (2 of 3) of each fold's top-6 robust singles.
Round 3  best exit rules x best locks (each fold's top 3 x top 3).
Round 4  extensions (arms with a target: ORB family on the premium, Liquidity on its index target) - M0 = the current
         manager's extension without its live-only flow condition, quorum variants - with and without round-3's best.
Round 5+ refinement: one-step finer neighbours of every fold's pick (each member's parameters) and weight-2 variants;
         repeated until two consecutive rounds improve the design walk-forward net by under 2%, at most 8 rounds.
Walk-forward: folds 2023, 2024, 2025 (Jan-Sep); each fold picks on all earlier design years only.
Pick rule (loop B, the one frozen): EXCESS over the random twin > 0 on the training years AND in both halves of them
(excess = improvement over the original exits minus what as many random early exits would have gained on the same
trades), and for a single specialist at least one neighbouring grid value also > 0; best training excess among those;
none -> original exits. (Loop A, archived in <scratch>/hunt/r12/loopA, used the raw improvement and an |improvement|
stop rule; it ran 8 rounds and was rejected because its picks did not beat random exits - see PREREG.md.)
Every configuration evaluated (all rounds) is logged and enters White's reality check.
"""
from __future__ import annotations

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import lib12 as L  # noqa: E402
import specs as SP  # noqa: E402

OUT = L.OUT
FOLDS = [2023, 2024, 2025]
B_TWIN = 1000
EXT_ARMS = {"orb": "premium", "orb_fresh": "premium", "orb_sweep": "premium", "range_fade": "premium", "liq_bn": "index"}


class Ctx:
    def __init__(self, name, part="design"):
        t = time.time()
        self.a = L.Arm(name)
        self.F = L.trade_features(self.a)
        L.attach(self.a, self.F)
        self.d = np.asarray(self.a.design() if part == "design" else self.a.holdout())
        self.di = np.nonzero(self.d)[0]
        self.year = self.a.day[self.di].year.values
        self.days = np.array(sorted(set(self.a.day[self.di])))
        self.base = L.replay(self.a, None, self.di)
        self.votes, self.locks = {}, {}
        self.ext = SP.EXT(self.a, self.F)
        self.netx = None
        nx = self.market_exit_net()
        c0 = self.a.c0[self.di]
        xc = self.base.xcol.values
        cols = np.arange(L.W)[None, :]
        life = (cols >= c0[:, None]) & (cols < xc[:, None])
        m = np.where(life, nx, np.nan)
        with np.errstate(all="ignore"):
            mi = np.nanmean(m, axis=1) - self.base.net.values
        self.mtwin = np.nan_to_num(mi)          # expected gain of ONE random early exit of trade i (twin control)
        print(f"[{name}] design trades {len(self.di)}, base net {self.base.net.sum():,.0f} ({time.time() - t:.0f}s)", flush=True)

    def vote(self, fam, p):
        k = SP.key(fam, p)
        if k not in self.votes:
            self.votes[k] = SP.FN[fam](self.a, self.F, **p)
        return self.votes[k]

    def lock(self, fam, p):
        k = SP.key(fam, p)
        if k not in self.locks:
            self.locks[k] = SP.FN[fam](self.a, self.F, **p).astype(np.float32)
        return self.locks[k]

    def manager(self, cfg):
        exits, q, locks, ext, eq = cfg
        ex = lk = xt = None
        if exits:
            s = np.zeros((self.a.n, L.W), np.float32)
            for fam, p, w in exits:
                s += w * self.vote(fam, dict(p))
            ex = s >= q - 1e-9
        if locks:
            lk = np.full((self.a.n, L.W), L.NEG)
            for fam, p in locks:
                lk = np.maximum(lk, self.lock(fam, dict(p)))
        mode = None
        if ext and self.a.name in EXT_ARMS:
            s = np.zeros((self.a.n, L.W), np.float32)
            for nm in ext:
                s += self.ext[nm]
            xt = s >= eq - 1e-9
            mode = EXT_ARMS[self.a.name]
        return L.Manager(ex, lk, xt, mode, ckey(cfg))

    def evaluate(self, cfg):
        tr = L.replay(self.a, self.manager(cfg), self.di)
        dl = tr.net.values - self.base.net.values
        acted = (tr.why.values >= 9) | (tr.n_ext.values > 0) | (np.abs(dl) > 0.5)
        return tr, dl, acted

    def market_exit_net(self):
        """net[i, j] of exiting design trade i at the next printed open after close j (the random twins' exits)."""
        if self.netx is None:
            a = self.a
            idx = self.di
            O, Cl = a.O[idx], a.Cl[idx]
            valid = ~np.isnan(Cl)
            cols = np.arange(L.W)[None, :]
            nv = np.where(valid, cols, L.W)
            nv = np.minimum.accumulate(nv[:, ::-1], axis=1)[:, ::-1]
            nxt = np.concatenate([nv[:, 1:], np.full((len(idx), 1), L.W)], axis=1)
            rows = np.arange(len(idx))[:, None]
            raw = np.where(nxt < L.W, O[rows, np.minimum(nxt, L.W - 1)], np.nan)
            q = a.qty[idx, None]
            e1 = a.e1[idx, None]
            if a.kind == "hero":
                x = np.maximum(np.nan_to_num(raw, nan=0.0) - L.TICK, 0.0)
            else:
                x = L.FILLS.sell(np.nan_to_num(raw, nan=0.0), None)
            chg = L.COSTS.charge(True, np.broadcast_to(e1, x.shape), np.broadcast_to(q, x.shape)) + \
                L.COSTS.charge(False, x, np.broadcast_to(q, x.shape))
            net = (x - e1) * q - chg - a.hs[idx, None] * (e1 + x) * q
            self.netx = np.where(np.isfinite(raw), net, np.nan)
        return self.netx

    def twin_p(self, dl, acted, rng):
        """random twins: the same number of random early exits on random trades at random minutes of their lives."""
        k = int(acted.sum())
        if k == 0:
            return 1.0, 0.0
        nx = self.market_exit_net()
        c0 = self.a.c0[self.di]
        xc = self.base.xcol.values
        life = np.maximum(xc - c0, 1)
        n = len(self.di)
        obs = dl.sum()
        sims = np.empty(B_TWIN)
        bn = self.base.net.values
        for b in range(B_TWIN):
            ii = rng.choice(n, size=k, replace=False)
            jj = c0[ii] + (rng.random(k) * life[ii]).astype(int)
            v = nx[ii, np.minimum(jj, L.W - 1)]
            v = np.where(np.isnan(v), bn[ii], v)
            sims[b] = (v - bn[ii]).sum()
        return float((1 + (sims >= obs).sum()) / (B_TWIN + 1)), float(sims.mean())


def ckey(cfg):
    exits, q, locks, ext, eq = cfg
    parts = []
    if exits:
        parts.append("EXIT[" + "+".join(("2*" if w == 2 else "") + SP.key(f, dict(p)) for f, p, w in exits) + f"]q{q:g}")
    if locks:
        parts.append("LOCK[" + "+".join(SP.key(f, dict(p)) for f, p in locks) + "]")
    if ext:
        parts.append("EXT[" + "&".join(ext) + f"]q{eq:g}")
    return "|".join(parts) or "ORIGINAL"


def E(fam, p, w=1):
    return (fam, tuple(sorted(p.items())), w)


def single_exit(fam, p):
    return ((E(fam, p),), 1, (), None, 0)


def single_lock(fam, p):
    return ((), 0, ((fam, tuple(sorted(p.items()))),), None, 0)


def combine(c1, c2):
    """exit part of c1 / c2 (they are merged with OR unless one has none), locks unioned, ext of either."""
    ex = c1[0] or c2[0]
    q = c1[1] if c1[0] else c2[1]
    if c1[0] and c2[0] and c1[0] != c2[0]:
        return None
    locks = tuple(sorted(set(c1[2]) | set(c2[2])))
    ext = c1[3] or c2[3]
    eq = c1[4] if c1[3] else c2[4]
    return (ex, q, locks, ext, eq)


class Book:
    """All evaluated configurations of one arm (the log; White RC input)."""

    def __init__(self, ctx):
        self.ctx = ctx
        self.cfg, self.dl, self.acted, self.round, self.single = {}, {}, {}, {}, {}
        self.daily = {}

    def run(self, cfg, rnd, single=None):
        k = ckey(cfg)
        if k in self.cfg:
            return k
        tr, dl, acted = self.ctx.evaluate(cfg)
        self.cfg[k], self.dl[k], self.acted[k], self.round[k] = cfg, dl, acted, rnd
        self.single[k] = single
        s = pd.Series(dl, index=self.ctx.a.day[self.ctx.di]).groupby(level=0).sum()
        self.daily[k] = s.reindex(self.ctx.days, fill_value=0.0).values
        return k

    def train_score(self, k, Y):
        y = self.ctx.year
        tr = y < Y
        if tr.sum() < 20:
            return None
        # excess over the random twin: the gain minus what as many random early exits would gain on these trades
        exc = self.dl[k][tr] - self.acted[k][tr] * self.ctx.mtwin[tr].mean()
        h = len(exc) // 2
        return exc.sum(), exc[:h].sum(), exc[h:].sum()

    def ok(self, k, Y):
        s = self.train_score(k, Y)
        if s is None or not (s[0] > 0 and s[1] > 0 and s[2] > 0):
            return False
        sg = self.single[k]
        if sg is not None:
            fam, p, grid = sg
            nb = [n for n in SP.neighbours(fam, p, grid)]
            good = False
            for g in nb:
                kk = ckey(single_exit(fam, g) if fam in SP.EXIT_GRID else single_lock(fam, g))
                if kk in self.cfg and self.train_score(kk, Y)[0] > 0:
                    good = True
            return good
        return True

    def pick(self, Y, among=None):
        best, bk = 0.0, None
        for k in (among or self.cfg):
            if not self.ok(k, Y):
                continue
            s = self.train_score(k, Y)[0]
            if s > best:
                best, bk = s, k
        return bk

    def ranked(self, Y, kind=None, top=6):
        out = []
        for k, c in self.cfg.items():
            if kind == "exit" and not (c[0] and not c[2] and not c[3]):
                continue
            if kind == "lock" and not (c[2] and not c[0] and not c[3]):
                continue
            if not self.ok(k, Y):
                continue
            out.append((self.train_score(k, Y)[0], k))
        return [k for _, k in sorted(out, reverse=True)[:top]]

    def wf(self):
        """walk-forward improvement, per fold: (pick, test delta)."""
        y = self.ctx.year
        res = {}
        for Y in FOLDS:
            k = self.pick(Y)
            te = y == Y
            res[Y] = (k, float(self.dl[k][te].sum()) if k else 0.0)
        return res


BOUNDS = {"T": (10, 45), "m": (0.03, 0.10), "a": (0.06, 0.12), "tau": (780, 870), "g": (0.0, 0.05), "z": (0.0, 1.0),
          "p": (1, 5), "z0": (1.0, 2.5), "x": (0.01, 0.03), "r": (0.001, 0.0025), "k": (2.0, 6.0), "b": (0.05, 0.20),
          "step": (0.01, 0.04), "keep": (0.5, 0.65)}


def fine_values(name, v):
    """one finer step either side, kept inside the declared round-1 grid range (no extrapolation)."""
    if isinstance(v, str):
        return [v]
    lo, hi = BOUNDS.get(name, (v, v))
    if name in ("T", "p", "tau"):
        st = {"T": 5, "p": 1, "tau": 15}[name]
        c = {v - st, v, v + st}
    else:
        c = {round(v * 0.8, 4), v, round(v * 1.2, 4)}
    return sorted(x for x in c if lo - 1e-9 <= x <= hi + 1e-9 or x == v)


def refine(cfg):
    """one-step finer neighbours of every member parameter."""
    exits, q, locks, ext, eq = cfg
    out = []
    for i, (fam, p, w) in enumerate(exits):
        pd_ = dict(p)
        for nm, v in pd_.items():
            for nv in fine_values(nm, v):
                if nv == v:
                    continue
                p2 = dict(pd_, **{nm: nv})
                ex2 = tuple(exits[:i]) + (E(fam, p2, w),) + tuple(exits[i + 1:])
                out.append((ex2, q, locks, ext, eq))
    for i, (fam, p) in enumerate(locks):
        pd_ = dict(p)
        for nm, v in pd_.items():
            for nv in fine_values(nm, v):
                if nv == v:
                    continue
                p2 = dict(pd_, **{nm: nv})
                lk2 = tuple(locks[:i]) + ((fam, tuple(sorted(p2.items()))),) + tuple(locks[i + 1:])
                out.append((exits, q, lk2, ext, eq))
    if len(exits) >= 2:
        for i in range(len(exits)):
            ex2 = tuple((f, p, 2 if j == i else 1) for j, (f, p, w) in enumerate(exits))
            out.append((ex2, 2, locks, ext, eq))
    return out


EXT_SETS = [(("xVW", "xMP", "xOI", "xVX"), 4), (("xVW", "xMP", "xOI", "xVX"), 3), (("xVW", "xMP", "xOI", "xVX"), 2),
            (("xVW", "xMO"), 2), (("xVW", "xDP"), 2), (("xMP", "xOI"), 2), (("xVW",), 1)]


def main():
    arms = sys.argv[1:] or L.ARMS
    rng = np.random.default_rng(12)
    ctxs = {a: Ctx(a) for a in arms}
    books = {a: Book(c) for a, c in ctxs.items()}
    rounds = []
    t0 = time.time()

    def total_wf():
        tot, per = 0.0, {}
        for a, b in books.items():
            w = b.wf()
            y = b.ctx.year
            base = sum(float(b.ctx.base.net.values[y == Y].sum()) for Y in FOLDS)
            d = sum(v[1] for v in w.values())
            per[a] = dict(base=base, delta=d, picks={Y: v[0] for Y, v in w.items()}, fold_delta={Y: v[1] for Y, v in w.items()})
            tot += base + d
        return tot, per

    def log_round(r, note):
        tot, per = total_wf()
        prev = rounds[-1]["wf_net"] if rounds else None
        imp = None if prev is None else (tot - prev) / abs(prev)
        rounds.append(dict(round=r, note=note, wf_net=tot, improvement=imp, n_configs=sum(len(b.cfg) for b in books.values()),
                           per={a: dict(delta=v["delta"], fold_delta=v["fold_delta"], picks=v["picks"]) for a, v in per.items()}))
        print(f"== round {r} ({note}): WF net {tot:,.0f}  improvement {imp if imp is None else f'{imp:+.2%}'}  "
              f"configs {rounds[-1]['n_configs']}  ({time.time() - t0:.0f}s)", flush=True)
        for a, v in per.items():
            print(f"   {a:11s} WF base {v['base']:>10,.0f}  delta {v['delta']:>9,.0f}  {v['fold_delta']}  picks {v['picks']}", flush=True)
        return imp

    # ---------------- round 1: singles
    for a, b in books.items():
        for fam, grid in SP.EXIT_GRID.items():
            for p in grid:
                b.run(single_exit(fam, p), 1, (fam, p, grid))
        for fam, grid in SP.LOCK_GRID.items():
            for p in grid:
                b.run(single_lock(fam, p), 1, (fam, p, grid))
        print(f"[{a}] round 1 done: {len(b.cfg)} configs ({time.time() - t0:.0f}s)", flush=True)
    imps = [log_round(1, "each specialist alone")]

    # ---------------- round 2: exit committees
    for a, b in books.items():
        cand = set()
        for Y in FOLDS + [9999]:
            top = b.ranked(Y, "exit", 6)
            mem = [b.cfg[k][0][0] for k in top]
            for i in range(len(mem)):
                for j in range(i + 1, len(mem)):
                    if mem[i][0] == mem[j][0]:
                        continue
                    cand.add(((mem[i], mem[j]), 1))
                    cand.add(((mem[i], mem[j]), 2))
                    for l in range(j + 1, len(mem)):
                        if mem[l][0] in (mem[i][0], mem[j][0]):
                            continue
                        cand.add(((mem[i], mem[j], mem[l]), 2))
        for ex, q in sorted(cand, key=str):
            b.run((tuple(sorted(ex)), q, (), None, 0), 2)
    imps.append(log_round(2, "exit committees (pairs OR/AND, triples 2-of-3)"))

    # ---------------- round 3: exits x locks
    for a, b in books.items():
        cand = []
        for Y in FOLDS + [9999]:
            ex = [k for k in b.ranked(Y, None, 12) if b.cfg[k][0]][:3]
            lk = b.ranked(Y, "lock", 3)
            for e1 in ex:
                for l1 in lk:
                    c = combine(b.cfg[e1], b.cfg[l1])
                    if c:
                        cand.append(c)
            for l1 in lk:
                for l2 in lk:
                    if l1 < l2:
                        c = combine(b.cfg[l1], b.cfg[l2])
                        if c:
                            cand.append(c)
        for c in cand:
            b.run(c, 3)
    imps.append(log_round(3, "exit rules x locks"))

    # ---------------- round 4: extensions
    for a, b in books.items():
        if a not in EXT_ARMS:
            continue
        bases = [((), 0, (), None, 0)]
        for Y in FOLDS + [9999]:
            k = b.pick(Y) if Y != 9999 else None
            for kk in ([k] if k else []) + b.ranked(Y, None, 2):
                bases.append(b.cfg[kk])
        for base in bases:
            for xs, xq in EXT_SETS:
                b.run((base[0], base[1], base[2], xs, xq), 4)
    imps.append(log_round(4, "target extensions with the ratcheting lock"))

    # ---------------- rounds 5..8: refinement until two rounds < 2%
    r = 5
    while r <= 8:
        if len(imps) >= 2 and imps[-1] is not None and imps[-2] is not None and imps[-1] < 0.02 and imps[-2] < 0.02:
            break
        for a, b in books.items():
            picks = {b.pick(Y) for Y in FOLDS + [9999]} - {None}
            picks |= set(b.ranked(9999, None, 3))
            for k in picks:
                for c in refine(b.cfg[k]):
                    b.run(c, r)
        imps.append(log_round(r, "refinement (finer neighbours, weight-2 members)"))
        r += 1

    # ---------------- outputs: log, singles with twins + BH, White RC, the final (full-design) pick
    rows = []
    for a, b in books.items():
        for k, c in b.cfg.items():
            dl = b.dl[k]
            y = b.ctx.year
            rows.append(dict(arm=a, round=b.round[k], config=k, delta=dl.sum(), acted=int(b.acted[k].sum()),
                             excess=float(dl.sum() - b.acted[k].sum() * b.ctx.mtwin.mean()),
                             **{f"d{Y}": dl[y == Y].sum() for Y in sorted(set(y))}))
    log = pd.DataFrame(rows)
    log.to_csv(os.path.join(OUT, "loop_log.csv"), index=False)
    pd.DataFrame([dict(round=x["round"], note=x["note"], wf_net=x["wf_net"], improvement=x["improvement"],
                       n_configs=x["n_configs"]) for x in rounds]).to_csv(os.path.join(OUT, "loop_rounds.csv"), index=False)
    with open(os.path.join(OUT, "loop_rounds.json"), "w") as f:
        json.dump(rounds, f, indent=1, default=str)

    # singles: twin p for every single-specialist config (BH across all of them, all arms)
    srows = []
    for a, b in books.items():
        for k, c in b.cfg.items():
            if b.round[k] != 1:
                continue
            p, mu = b.ctx.twin_p(b.dl[k], b.acted[k], rng)
            fam = (c[0][0][0] if c[0] else c[2][0][0])
            srows.append(dict(arm=a, spec=fam, family=SP.FAMILY[fam], config=k, delta=b.dl[k].sum(), acted=int(b.acted[k].sum()),
                              twin_mean=mu, p_twin=p))
        print(f"[{a}] twins done ({time.time() - t0:.0f}s)", flush=True)
    sg = pd.DataFrame(srows)
    sg["q_bh"] = L.bh(sg.p_twin.values)
    sg.to_csv(os.path.join(OUT, "singles.csv"), index=False)

    # White RC per arm (all configs, all rounds) and global
    wrc = {}
    allD = []
    for a, b in books.items():
        D = np.stack([b.daily[k] for k in b.cfg], axis=1)
        za = os.path.join(OUT, "loopA", f"D_{a}.npz")
        if os.path.exists(za):
            A = np.load(za)
            extra = [i for i, kk in enumerate(A["keys"]) if kk not in b.cfg]
            if extra and len(A["days"]) == D.shape[0]:
                D = np.concatenate([D, A["D"][:, extra].astype(np.float64)], axis=1)
        np.savez_compressed(os.path.join(OUT, f"D_{a}.npz"), D=D.astype(np.float32), days=b.ctx.days.astype("datetime64[D]").astype(np.int64),
                            keys=np.array(list(b.cfg)))
        mu, p = L.white_rc(D)
        wrc[a] = dict(configs=D.shape[1], days=D.shape[0], best_mean_day=mu, p=p)
        allD.append(pd.DataFrame(D, index=b.ctx.days))
        print(f"[{a}] White RC p={p:.3f} over {D.shape[1]} configs", flush=True)
    G = pd.concat(allD, axis=1).fillna(0.0).values
    mu, p = L.white_rc(G)
    wrc["ALL"] = dict(configs=G.shape[1], days=G.shape[0], best_mean_day=mu, p=p)
    with open(os.path.join(OUT, "whiteRC.json"), "w") as f:
        json.dump(wrc, f, indent=1)

    # the final pick per arm on the WHOLE design period (frozen into PREREG) + its twin p + WF
    final = {}
    for a, b in books.items():
        k = b.pick(9999)
        w = b.wf()
        if k:
            fin_exc = b.train_score(k, 9999)
        res = dict(config=k or "ORIGINAL", wf_delta=sum(v[1] for v in w.values()), wf_folds={Y: v[1] for Y, v in w.items()},
                   wf_picks={Y: v[0] for Y, v in w.items()}, design_base=float(b.ctx.base.net.sum()))
        if k:
            p, mu = b.ctx.twin_p(b.dl[k], b.acted[k], rng)
            res.update(design_excess=float(fin_exc[0]), design_delta=float(b.dl[k].sum()), acted=int(b.acted[k].sum()), p_twin=p, twin_mean=mu,
                       cfg=json.loads(json.dumps(b.cfg[k], default=list)))
        final[a] = res
    with open(os.path.join(OUT, "final_design.json"), "w") as f:
        json.dump(final, f, indent=1, default=str)
    print(json.dumps(final, indent=1, default=str), flush=True)


if __name__ == "__main__":
    main()
