"""Gold lab pack, step 7: a small self-contained HTML overview for Boss (15 Oct 2026). Inline SVG, a few lines of
inline JS for hover tips, no external scripts or fonts. Charts: mid price over the year (daily closes), average
spread by IST hour, volatility by IST hour and by session, tick volume by IST hour."""
from __future__ import annotations

import html

import numpy as np
import pandas as pd

SESS = ["asia", "london", "overlap", "ny", "off"]


def summary_stats(df):
    """Numbers behind the charts, from the clean frame."""
    ist_h = (df.ist_minute // 60).astype(int)
    absret_bp = (np.log(df.close / df.close.shift(1)).abs() * 1e4).where(df.gap_min <= 1)
    rng = df.high - df.low
    by_h = pd.DataFrame({"h": ist_h, "spread": df.spread, "absret_bp": absret_bp, "range": rng, "vol": df.volume})
    g = by_h.groupby("h")
    day = df.groupby("trading_day").close.last()
    s = pd.DataFrame({"s": df.session.astype(str), "absret_bp": absret_bp, "spread": df.spread, "range": rng})
    gs = s.groupby("s")
    return {
        "rows": int(len(df)), "first": str(df.ts_utc.iloc[0]), "last": str(df.ts_utc.iloc[-1]),
        "days": int(df.trading_day.nunique()),
        "median_spread": float(df.spread.median()),
        "bad_ticks": int(df.flag_bad_tick.sum()),
        "daily_close": [(str(d.date()), float(v)) for d, v in day.items()],
        "hours": [int(h) for h in g.spread.mean().index],
        "spread_by_hour": [float(x) for x in g.spread.mean()],
        "spread_p90_by_hour": [float(x) for x in g.spread.quantile(0.9)],
        "absret_by_hour": [float(x) for x in g.absret_bp.mean()],
        "range_by_hour": [float(x) for x in g.range.mean()],
        "volume_by_hour": [float(x) for x in g.vol.mean()],
        "minutes_by_hour": [int(x) for x in g.size()],
        "sessions": [k for k in SESS if k in gs.groups],
        "absret_by_session": [float(gs.absret_bp.mean().get(k, np.nan)) for k in SESS if k in gs.groups],
        "spread_by_session": [float(gs.spread.mean().get(k, np.nan)) for k in SESS if k in gs.groups],
        "minutes_by_session": [int(gs.size().get(k, 0)) for k in SESS if k in gs.groups],
    }


def _bars(labels, values, fmt, title, unit, w=640, h=220):
    pad_l, pad_b, pad_t = 48, 28, 12
    vmax = max(v for v in values if np.isfinite(v)) or 1.0
    bw = (w - pad_l - 8) / len(values)
    out = [f'<svg viewBox="0 0 {w} {h}" role="img" aria-label="{html.escape(title)}">']
    for k in range(5):
        y = pad_t + (h - pad_t - pad_b) * (1 - k / 4)
        out.append(f'<line class="grid" x1="{pad_l}" x2="{w - 8}" y1="{y:.1f}" y2="{y:.1f}"/>'
                   f'<text class="ax" x="{pad_l - 6}" y="{y + 4:.1f}" text-anchor="end">{fmt(vmax * k / 4)}</text>')
    for i, (lab, v) in enumerate(zip(labels, values)):
        if not np.isfinite(v):
            continue
        bh = (h - pad_t - pad_b) * v / vmax
        x = pad_l + i * bw + 1
        y = h - pad_b - bh
        tip = html.escape(f"{lab}: {fmt(v)} {unit}")
        out.append(f'<rect class="bar" x="{x:.1f}" y="{y:.1f}" width="{max(bw - 2, 1):.1f}" height="{bh:.1f}" '
                   f'rx="2" data-tip="{tip}"/>')
        if len(labels) <= 6 or i % 3 == 0:
            out.append(f'<text class="ax" x="{x + bw / 2 - 1:.1f}" y="{h - 10}" text-anchor="middle">'
                       f'{html.escape(str(lab))}</text>')
    out.append("</svg>")
    return "".join(out)


def _line(points, w=640, h=240):
    pad_l, pad_b, pad_t = 56, 28, 12
    ys = [p[1] for p in points]
    lo, hi = min(ys), max(ys)
    span = (hi - lo) or 1.0
    X = lambda i: pad_l + (w - pad_l - 8) * i / max(len(points) - 1, 1)  # noqa: E731
    Y = lambda v: pad_t + (h - pad_t - pad_b) * (1 - (v - lo) / span)  # noqa: E731
    out = [f'<svg viewBox="0 0 {w} {h}" role="img" aria-label="XAUUSD mid close by trading day">']
    for k in range(5):
        v = lo + span * k / 4
        out.append(f'<line class="grid" x1="{pad_l}" x2="{w - 8}" y1="{Y(v):.1f}" y2="{Y(v):.1f}"/>'
                   f'<text class="ax" x="{pad_l - 6}" y="{Y(v) + 4:.1f}" text-anchor="end">{v:,.0f}</text>')
    path = " ".join(f"{X(i):.1f},{Y(v):.1f}" for i, (_, v) in enumerate(points))
    out.append(f'<polyline class="line" points="{path}"/>')
    step = max(len(points) // 6, 1)
    for i in range(0, len(points), step):
        out.append(f'<text class="ax" x="{X(i):.1f}" y="{h - 10}" text-anchor="middle">{points[i][0][:7]}</text>')
    seg = (w - pad_l - 8) / max(len(points) - 1, 1)
    for i, (d, v) in enumerate(points):  # invisible hover strips, one per day
        out.append(f'<rect class="hit" x="{X(i) - seg / 2:.1f}" y="{pad_t}" width="{seg:.1f}" '
                   f'height="{h - pad_t - pad_b}" data-tip="{d}: {v:,.2f} USD"/>')
    out.append("</svg>")
    return "".join(out)


def _table(head, rows):
    th = "".join(f"<th>{html.escape(h)}</th>" for h in head)
    tr = "".join("<tr>" + "".join(f"<td>{html.escape(str(c))}</td>" for c in r) + "</tr>" for r in rows)
    return f"<details><summary>Table</summary><table><tr>{th}</tr>{tr}</table></details>"


CSS = """
:root{--bg:#fcfcfb;--ink:#0b0b0b;--ink2:#52514e;--grid:#e4e3df;--s1:#2a78d6;--card:#ffffff}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#1a1a19;--ink:#f0efec;--ink2:#c3c2b7;
--grid:#3a3936;--s1:#3987e5;--card:#222220}}
:root[data-theme="dark"]{--bg:#1a1a19;--ink:#f0efec;--ink2:#c3c2b7;--grid:#3a3936;--s1:#3987e5;--card:#222220}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
main{max-width:720px;margin:0 auto;padding:24px 16px 48px}
h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:28px 0 4px}p.note{color:var(--ink2);margin:0 0 8px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:8px;margin:16px 0}
.tile{background:var(--card);border:1px solid var(--grid);border-radius:8px;padding:10px 12px}
.tile b{display:block;font-size:20px}.tile span{color:var(--ink2);font-size:13px}
svg{width:100%;height:auto;display:block}.grid{stroke:var(--grid);stroke-width:1}
.ax{fill:var(--ink2);font-size:11px}.bar{fill:var(--s1)}.bar:hover{opacity:.75}
.line{fill:none;stroke:var(--s1);stroke-width:2}.hit{fill:transparent}
#tip{position:fixed;pointer-events:none;background:var(--ink);color:var(--bg);padding:4px 8px;border-radius:4px;
font-size:12px;display:none}
details{margin:4px 0 0;color:var(--ink2);font-size:13px}table{border-collapse:collapse;margin-top:6px}
td,th{padding:2px 10px 2px 0;text-align:right}
"""

JS = """
const t=document.getElementById('tip');document.addEventListener('mousemove',e=>{const d=e.target.dataset&&
e.target.dataset.tip;if(!d){t.style.display='none';return}t.textContent=d;t.style.display='block';
t.style.left=Math.min(e.clientX+12,innerWidth-200)+'px';t.style.top=(e.clientY+12)+'px'});
"""


def render(st, extra_notes=()):
    hours = [f"{h:02d}" for h in st["hours"]]
    notes = "".join(f"<p class='note'>{html.escape(n)}</p>" for n in extra_notes)
    body = [
        "<h1>XAUUSD lab pack: a year of gold minutes</h1>",
        f"<p class='note'>Dukascopy XAUUSD 1-minute bid and ask, {st['first'][:16]} to {st['last'][:16]} UTC. "
        "Mid = (bid + ask) / 2; spread = ask - bid at each minute's close. Research data, not trading advice.</p>",
        notes,
        "<div class='tiles'>",
        f"<div class='tile'><b>{st['rows']:,}</b><span>minutes with trades</span></div>",
        f"<div class='tile'><b>{st['days']}</b><span>trading days</span></div>",
        f"<div class='tile'><b>{st['median_spread']:.2f}</b><span>median spread, USD/oz</span></div>",
        f"<div class='tile'><b>{st['bad_ticks']:,}</b><span>minutes flagged as bad ticks</span></div>",
        "</div>",
        "<h2>Price over the year</h2><p class='note'>Mid close at the end of each trading day (17:00 New York).</p>",
        _line(st["daily_close"]),
        _table(["day", "close"], [(d, f"{v:,.2f}") for d, v in st["daily_close"][-10:]]),
        "<h2>Average spread by hour (IST)</h2><p class='note'>Mean ask - bid in USD an ounce, by the IST hour of "
        "the minute. The spread is the cost of every round trip before broker fees.</p>",
        _bars(hours, st["spread_by_hour"], lambda v: f"{v:.2f}", "spread by IST hour", "USD"),
        _table(["IST hour", "mean spread", "90th pct", "minutes"],
               [(h, f"{a:.3f}", f"{b:.3f}", m) for h, a, b, m in zip(hours, st["spread_by_hour"],
                                                                      st["spread_p90_by_hour"],
                                                                      st["minutes_by_hour"])]),
        "<h2>Volatility by hour (IST)</h2><p class='note'>Mean absolute 1-minute mid return in basis points "
        "(consecutive minutes only).</p>",
        _bars(hours, st["absret_by_hour"], lambda v: f"{v:.2f}", "volatility by IST hour", "bp"),
        _table(["IST hour", "mean |ret| bp", "mean 1m range USD"],
               [(h, f"{a:.2f}", f"{b:.2f}") for h, a, b in zip(hours, st["absret_by_hour"], st["range_by_hour"])]),
        "<h2>Volatility by session</h2><p class='note'>Sessions by local clocks (London 08:00-16:30, New York "
        "08:00-17:00); overlap = both open; off = 17:00 New York to 00:00 UTC.</p>",
        _bars(st["sessions"], st["absret_by_session"], lambda v: f"{v:.2f}", "volatility by session", "bp"),
        _table(["session", "mean |ret| bp", "mean spread", "minutes"],
               [(s, f"{a:.2f}", f"{b:.3f}", m) for s, a, b, m in zip(st["sessions"], st["absret_by_session"],
                                                                      st["spread_by_session"],
                                                                      st["minutes_by_session"])]),
        "<h2>Tick volume by hour (IST)</h2><p class='note'>Mean Dukascopy tick volume per minute (bid + ask). "
        "One broker's activity, not exchange volume.</p>",
        _bars(hours, st["volume_by_hour"], lambda v: f"{v:,.0f}", "volume by IST hour", "per minute"),
    ]
    return ("<!doctype html><html lang='en'><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'><title>Gold Lab Overview</title>"
            f"<style>{CSS}</style></head><body><main>{''.join(body)}</main><div id='tip'></div>"
            f"<script>{JS}</script></body></html>")
