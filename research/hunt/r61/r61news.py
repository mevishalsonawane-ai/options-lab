"""R61-N: positive / negative news before Solo's signal as take / skip context (NEWS_PREREG.md). Research only.

    python -I research/hunt/r61/r61news.py parse RAW_DIR      # sitemaps (untrusted data) -> <R61_OUT>/news.pkl
    python research/hunt/r57/r57data.py build                  # with R57_OUT=<R61_OUT>: R57's day stores (labels)
    python research/hunt/r61/r61news.py design                 # results/news_design.json (design months only)
    python research/hunt/r61/r61news.py final                  # the ONE final read for N1 / N2 (refuses a second)

Tone and tags are the phone's own reader (android/ira-core/.../News.kt: News.tone, News.tag) ported unchanged, plus the
slug adaptation of NEWS_PREREG section 2. Headline text is data only: nothing here acts on it.
"""
from __future__ import annotations

import bisect
import gzip
import json
import pickle
import re
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))      # python -I does not add the script's folder

import numpy as np  # noqa: E402

import r61cfg as K  # noqa: E402

# ------------------------------------------------------------------------------------------------ the phone's reader
GOOD = {
    "record high": 1.0, "all-time high": 1.0, "rally": 1.0, "rallies": 1.0, "surge": 1.0, "surges": 1.0, "soar": 1.0,
    "soars": 1.0, "jump": 0.8, "jumps": 0.8, "gain": 0.6, "gains": 0.6, "rise": 0.6, "rises": 0.6, "climbs": 0.6,
    "up": 0.3, "higher": 0.5, "recover": 0.6, "recovers": 0.6, "rebound": 0.7, "rebounds": 0.7, "beats": 0.7,
    "upgrade": 0.7, "upgrades": 0.7, "inflows": 0.6, "buying": 0.4, "bullish": 0.8, "optimism": 0.6, "strong": 0.4,
    "boost": 0.6, "boosts": 0.6, "rate cut": 0.7, "eases": 0.4, "easing": 0.4, "outperform": 0.6, "green": 0.3,
}
BAD = {
    "crash": 1.0, "crashes": 1.0, "plunge": 1.0, "plunges": 1.0, "slump": 0.9, "slumps": 0.9, "tumble": 0.9,
    "tumbles": 0.9, "sell-off": 0.9, "selloff": 0.9, "fall": 0.6, "falls": 0.6, "drop": 0.6, "drops": 0.6,
    "decline": 0.6, "declines": 0.6, "down": 0.3, "lower": 0.5, "slips": 0.5, "slide": 0.6, "slides": 0.6, "loss": 0.5,
    "losses": 0.6, "misses": 0.7, "downgrade": 0.7, "downgrades": 0.7, "outflows": 0.6, "selling": 0.4,
    "bearish": 0.8, "fears": 0.6, "fear": 0.5, "weak": 0.5, "war": 0.7, "tension": 0.5, "tensions": 0.5,
    "tariff": 0.5, "tariffs": 0.5, "rate hike": 0.7, "red": 0.3, "volatile": 0.3, "crisis": 0.8, "default": 0.8,
}
NEGATORS = {"not", "no", "never", "fails", "without"}
TAGS = [
    (["bank nifty", "banknifty", "bank stocks", "banks", "bank", "psu bank", "private bank", "rbi", "hdfc bank", "icici",
      "sbi", "kotak", "axis bank"], ["BANKNIFTY"]),
    (["finnifty", "fin nifty", "nbfc", "nbfcs", "insurance", "insurers", "financial services", "bajaj finance"],
     ["FINNIFTY"]),
    (["nifty", "sensex", "stocks", "equities", "equity", "dalal street", "benchmark", "markets", "market", "fii", "fiis",
      "dii", "diis", "midcap", "smallcap"], ["NIFTY", "SENSEX"]),
    (["sensex", "bse"], ["SENSEX"]),
    (["vix", "volatility"], ["VIX"]),
    (["gold", "bullion", "precious metal", "precious metals", "xau", "comex"], ["GOLD"]),
]


def words(s: str) -> list:
    return [w for w in re.split(r"\s+", re.sub(r"[^a-z0-9\- ]", " ", s.lower())) if w]


def _lists(slug: bool):
    g, b = dict(GOOD), dict(BAD)
    if slug:            # NEWS_PREREG section 2: slugs carry no in-word hyphens, so hyphenated entries also match spaced
        for d in (g, b):
            for k, v in list(d.items()):
                if "-" in k:
                    d[k.replace("-", " ")] = v
    return g, b


def tone(title: str, slug: bool = False) -> float:
    """News.tone (non-gold): phrases first (longest first, good before bad at equal length as listed), negation one or
    two words back, score / 2 clipped to -1..+1."""
    used = " " + " ".join(words(title)) + " "
    g, b = _lists(slug)
    allp = sorted([(k, v, 1) for k, v in g.items()] + [(k, v, -1) for k, v in b.items()], key=lambda x: -len(x[0]))
    score = 0.0
    for phrase, weight, sign in allp:
        idx = used.find(f" {phrase} ")
        while idx >= 0:
            before = used[:idx].strip().split(" ")[-2:]
            neg = any(w in NEGATORS for w in before)
            score += weight * sign * (-1 if neg else 1)
            used = used[:idx] + " " + "#" * len(phrase) + " " + used[idx + len(phrase) + 2:]
            idx = used.find(f" {phrase} ")
    return max(-1.0, min(1.0, score / 2.0))


def tag(title: str) -> list:
    text = " " + " ".join(words(title)) + " "
    out = []
    for keys, ms in TAGS:
        if any(f" {k} " in text for k in keys):
            for m in ms:
                if m not in out:
                    out.append(m)
    return out


def counts_for(und: str, tags: list) -> bool:
    """NEWS_PREREG section 2: a headline counts for an index when tagged with it; NIFTY-tagged (market-wide) news counts
    for all four (MIDCPNIFTY has no tag of its own)."""
    return "NIFTY" in tags or und in tags


# ------------------------------------------------------------------------------------------------ sitemaps
ROW = re.compile(r"<url>\s*<loc>([^<]+)</loc>\s*<lastmod>([^<]+)</lastmod>")
SLUG = re.compile(r"/([^/]+?)(?:-article)?-\d+\.html$")


def slug_title(url: str) -> str | None:
    m = SLUG.search(url)
    return m.group(1).replace("-", " ") if m else None


def parse_sitemap(xml: str) -> list:
    """[(ts naive IST, title)] for /news/business/ URLs."""
    out = []
    for loc, lm in ROW.findall(xml):
        if "/news/business/" not in loc:
            continue
        t = slug_title(loc)
        if not t:
            continue
        try:
            ts = datetime.fromisoformat(lm.strip())
        except ValueError:
            continue
        if ts.tzinfo is not None:
            ts = (ts - ts.utcoffset() + timedelta(hours=5, minutes=30)).replace(tzinfo=None)
        out.append((ts, t))
    return out


def parse(raw: Path) -> dict:
    rows = {}
    for p in sorted(raw.glob("mc_*.xml.gz")):
        for ts, t in parse_sitemap(gzip.decompress(p.read_bytes()).decode("utf-8", "replace")):
            rows[(t, ts)] = None                          # the same story listed twice at the same time counts once
    heads = sorted((ts, t, tone(t, slug=True), tag(t)) for t, ts in rows)
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / "news.pkl", "wb") as f:
        pickle.dump(heads, f, protocol=pickle.HIGHEST_PROTOCOL)
    tagged = sum(1 for h in heads if h[3])
    return dict(headlines=len(heads), tagged=tagged, first=str(heads[0][0]) if heads else None,
                last=str(heads[-1][0]) if heads else None)


# ------------------------------------------------------------------------------------------------ point-in-time windows
class Windows:
    """Per index: sorted headline times with cumulative tone, for O(log n) window means."""

    def __init__(self, heads: list):
        self.t, self.c = {}, {}
        for u in K.UNDS:
            sel = [(h[0], h[2]) for h in heads if counts_for(u, h[3])]
            self.t[u] = [x[0] for x in sel]
            self.c[u] = np.concatenate([[0.0], np.cumsum([x[1] for x in sel])])

    def window(self, und: str, lo: datetime, hi: datetime) -> tuple:
        """(n, mean tone) of headlines with lo < ts < hi."""
        t = self.t[und]
        i, j = bisect.bisect_right(t, lo), bisect.bisect_left(t, hi)
        n = max(j - i, 0)
        return n, (float(self.c[und][j] - self.c[und][i]) / n if n else 0.0)


def prev_close(sessions: list, d: date) -> datetime:
    i = bisect.bisect_left(sessions, d)
    p = sessions[i - 1] if i > 0 else d - timedelta(days=1)
    return datetime.combine(p, datetime.min.time()) + timedelta(hours=15, minutes=30)


def features(W: Windows, und: str, d: date, er: int, side: str, sessions: list) -> dict:
    """news_tone (signed by side) and news_count for a candidate entering at row er (09:15 + er minutes)."""
    hi = datetime.combine(d, datetime.min.time()) + timedelta(minutes=K.K57.OPEN_MIN + er)
    n, m = W.window(und, prev_close(sessions, d), hi)
    s = 1.0 if side == "C" else -1.0
    return dict(news_tone=m * s, news_count=float(np.log1p(n)))


NEWS = ["news_tone", "news_count"]
VARIANTS = {"V1": [], "N1": ["news_tone", "news_count"], "N2": ["news_tone"]}
FLAG = K.RESULTS / "NEWS_FINAL_READ"


# ------------------------------------------------------------------------------------------------ study
def _rows(name: str, lo: date, hi: date) -> list:
    import r57data
    import r57setups as S57
    import r57study as R57
    path = K.OUT / f"{name}.pkl"
    if path.exists():
        with open(path, "rb") as f:
            return pickle.load(f)
    for u in K.UNDS:
        r57data.load(u)
    rows = R57.a_rows(S57.candidates(lo, hi, setups=("S1", "S2", "S3", "S4")))
    with open(path, "wb") as f:
        pickle.dump(rows, f, protocol=pickle.HIGHEST_PROTOCOL)
    return rows


def add_news(rows: list) -> list:
    import r57data
    with open(K.OUT / "news.pkl", "rb") as f:
        W = Windows(pickle.load(f))
    sess = {u: sorted(r57data.load(u)) for u in K.UNDS}
    for r in rows:
        r["f"].update(features(W, r["und"], r["day"], r["er"], r["side"], sess[r["und"]]))
    return rows


def preopen_corr() -> dict:
    """Descriptive (design days only): pre-open tone (previous close .. 09:15) vs the index's open-to-close return."""
    import r57data
    with open(K.OUT / "news.pkl", "rb") as f:
        W = Windows(pickle.load(f))
    out = {}
    for u in K.UNDS:
        days = r57data.load(u)
        ss = sorted(days)
        xs, ys = [], []
        for d in ss:
            if not (K.START <= d <= K.DESIGN_END):
                continue
            a = days[d].idx
            o, c = a[0][np.isfinite(a[0])], a[3][np.isfinite(a[3])]
            if not len(o) or not len(c):
                continue
            n, m = W.window(u, prev_close(ss, d), datetime.combine(d, datetime.min.time()) + timedelta(hours=9, minutes=15))
            if n:
                xs.append(m)
                ys.append((c[-1] / o[0] - 1) * 100)
        out[u] = dict(days=len(xs), corr=round(float(np.corrcoef(xs, ys)[0, 1]), 3) if len(xs) > 2 else None)
    return out


def design():
    import r61feat as F
    import r61study as ST
    import r57study as R57
    cal = [d for d in ST.calendar() if d <= K.DESIGN_END]
    rows = add_news(_rows("news_design_rows", K.START, K.DESIGN_END))
    out = dict(rows=len(rows), share_with_news=round(float(np.mean([r["f"]["news_count"] > 0 for r in rows])), 3),
               variants={}, preopen_corr=preopen_corr())
    for v, extra in VARIANTS.items():
        r = ST.run("logit", F.A2 + extra, rows, K.WF_MONTHS, cal)
        out["variants"][v] = dict(inputs=extra, wf=R57.stats(r["trades"]), auc_wf=r["auc"],
                                  auc_random=ST.auc(r["y"], ST.random_split(r["X"], r["y"], "logit")))
        print(v, json.dumps(out["variants"][v], default=str)[:300], flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "news_design.json").write_text(json.dumps(out, indent=1, default=str))


def final():
    import r57setups as S57
    import r57study as R57
    import r61feat as F
    import r61study as ST
    if FLAG.exists():
        raise SystemExit("R61-N: the final test was already read (results/NEWS_FINAL_READ). One read only.")
    dres = json.loads((K.RESULTS / "news_design.json").read_text())
    FLAG.write_text(f"news final test read on {date.today()}\n")
    S57.open_final()
    cal = ST.calendar()
    rows = add_news(_rows("news_design_rows", K.START, K.DESIGN_END) + _rows("news_final_rows", K.FINAL_START, K.END))
    lo, hi = K.FINAL_START, K.END
    sessions = sorted(d for d in cal if lo <= d <= hi)
    out, trades = dict(variants={}), {}
    for v, extra in VARIANTS.items():
        r = ST.run("logit", F.A2 + extra, rows, K.FINAL_MONTHS, cal)
        tr = trades[v] = r["trades"]
        p, tm = ST.twin_p(tr)
        out["variants"][v] = dict(inputs=extra, test=R57.stats(tr), auc_test=r["auc"], p=p, twin_mean=tm,
                                  ex_midcp=ST.ex_index(tr))
    ids = ["N1", "N2"]
    qs = R57.bh([out["variants"][i]["p"] or 1.0 for i in ids])
    for i, q in zip(ids, qs):
        r = out["variants"][i]
        f = r["test"]
        r["q"] = round(float(q), 4)
        fails = [m for c, m in ((f["net"] <= 0, "final net <= 0"), (r["p"] is None or r["p"] >= K.GATE_P, "p >= 0.05"),
                                (r["q"] > K.GATE_Q, "q > 0.10"), (f["trades"] < K.GATE_MIN_TRADES, "fewer than 40"),
                                (f["trades"] and f.get("ex_top5", 0) <= 0, "top-5 >= net"),
                                (dres["variants"][i]["wf"]["net"] <= 0, "design walk-forward <= 0")) if c]
        r["verdict"] = "PASS" if not fails else "FAIL: " + "; ".join(fails)
        b = out["variants"]["V1"]
        chk = dict(net=f["net"] > b["test"]["net"], auc=(r["auc_test"] or 0) > (b["auc_test"] or 0),
                   boot_p=ST.paired_boot(trades[i], trades["V1"], sessions), ex_midcp=r["ex_midcp"] > b["ex_midcp"])
        chk["boot"] = chk["boot_p"] is not None and chk["boot_p"] < K.REPLACE_BOOT_P
        r["beats_V1"] = chk
        r["helps_solo"] = r["verdict"] == "PASS" and chk["net"] and chk["auc"] and chk["boot"] and chk["ex_midcp"]
        print(i, json.dumps(r, default=str)[:400], flush=True)
    (K.RESULTS / "news_final.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "parse":
        print(parse(Path(sys.argv[2])))
    elif cmd == "design":
        design()
    elif cmd == "final":
        final()
    else:
        print(__doc__)
