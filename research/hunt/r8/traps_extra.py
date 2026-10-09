"""R8 extra: (a) does persistence make a wall more 'real'? among walls alive at s seconds, how they end;
(b) wall size vs reference; (c) end-of-session (last 5 min to 23:30 IST) spread/depth vs earlier;
(d) trade-to-receive latency tails; (e) flicker by chunk size relative to median level."""
import json
from pathlib import Path
import numpy as np, pandas as pd
O = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r8")
R7 = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r7")
log = open(O / "traps_extra.log", "w")
def say(*a):
    s = " ".join(map(str, a)); print(s); log.write(s + "\n")
W = pd.read_csv(O / "walls.csv"); W = W[W.end != "open"]
W["grp"] = np.where(W.kind == "FUT", "FUT", np.where(W.name.str.startswith("CRUDE"), "CRUDE-OPT", "GAS-OPT"))
say("== (a) walls alive at s seconds: how they end (pulled / traded / out of view), and remaining life")
for K in (2, 3, 5):
    for grp, x in W[W.K == K].groupby("grp"):
        out = []
        for s in (0, 1, 3, 5, 10):
            y = x[x.life >= s]
            if len(y) < 5: continue
            out.append(f"{s}s n={len(y)} P{(y.end=='pulled').mean():.0%}/T{(y.end=='traded').mean():.0%}/O{(y.end=='outofview').mean():.0%}")
        say(f" K={K} {grp:9s} " + " | ".join(out))
say("\n== (b) wall peak / ref (trailing median level size) quantiles, K=2")
for grp, x in W[W.K == 2].groupby("grp"):
    r = x.peak / x.ref
    say(f" {grp:9s} ref median {x.ref.median():.1f} lots; peak/ref p50 {r.median():.1f} p90 {r.quantile(.9):.1f} p99 {r.quantile(.99):.1f} max {r.max():.1f}")
F = pd.read_csv(O / "flicker_chunks.csv")
say("\n== (e) cancel within 3 s by chunk size bucket (pooled, futures vs options)")
for k, x in F.groupby(F.kind == "FUT"):
    q = pd.qcut(x["size"].rank(method="first"), 5, labels=False)
    say(f" {'FUT' if k else 'OPT'}: " + ", ".join(f"q{i+1}(size {x['size'][q==i].min()}-{x['size'][q==i].max()}) {(((x.end=='cancel')&(x.life<=3))[q==i]).mean():.0%}" for i in range(5)))
raw = pd.read_parquet(R7 / "ticks.parquet"); meta = json.load(open(R7 / "instruments.json"))
cut = pd.Timestamp("2026-10-09 23:25").value / 1e9 - 19800
say("\n== (c) last 5 min before close vs earlier: median spread (bp of mid), 5-level depth, TBQ+TSQ; (d) latency tails")
for sid, g in raw.groupby("sid"):
    nm = meta[sid]["sym"] + "-" + meta[sid]["kind"] + str(meta[sid].get("strike", ""))
    g = g[(g.bp0 > 0) & (g.ap0 > g.bp0)]
    g = g[g.vol >= g.vol.cummax()]
    sp = (g.ap0 - g.bp0) / ((g.ap0 + g.bp0) / 2) * 1e4
    dep = g[[f"bq{k}" for k in range(5)] + [f"aq{k}" for k in range(5)]].sum(axis=1)
    late = g.ts >= cut
    tr = g[g.vol.diff() > 0]; lat = tr.ts - (tr.ltt - 19800)
    say(f" {nm:22s} spread {sp[~late].median():6.1f} -> {sp[late].median():6.1f} bp; depth5 {dep[~late].median():6.0f} -> {dep[late].median():6.0f}; "
        f"tbq+tsq {(g.tbq+g.tsq)[~late].median():7.0f} -> {(g.tbq+g.tsq)[late].median():7.0f} | trade age at receipt p50 {lat.median():.2f}s p95 {lat.quantile(.95):.2f}s p99 {lat.quantile(.99):.2f}s max {lat.max():.1f}s")
