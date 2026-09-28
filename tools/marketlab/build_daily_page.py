import datetime as dt
import html
import json
import os
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
OUT = os.path.join(ROOT, "last_month_daily.html")
SHORT = {"Trend": "Trend", "Straddle": "Straddle", "Carry": "Carry", "Original": "Fade / dip-buy"}

def money(v, sign=True):
    s = f"{abs(v):,.2f}"
    if not sign: return "$" + s
    return ("+" if v > 0.004 else "−" if v < -0.004 else "") + "$" + s

def cls(v): return "pos" if v > 0.004 else "neg" if v < -0.004 else "zero"

def chart(days):
    w, h, pad_l, pad_b, pad_t = 720, 220, 44, 26, 12
    vals = [d["pnl"] for d in days]
    top = max(1, max(vals)); bot = min(-1, min(vals))
    import math
    step = 2 if top - bot <= 12 else 5
    top = math.ceil(top / step) * step; bot = math.floor(bot / step) * step
    ih = h - pad_b - pad_t
    y = lambda v: pad_t + (top - v) / (top - bot) * ih
    n = len(days); bw = (w - pad_l - 8) / n
    parts = []
    t = bot
    while t <= top + 1e-9:
        parts.append(f'<line x1="{pad_l}" x2="{w-4}" y1="{y(t):.1f}" y2="{y(t):.1f}" class="{"axis0" if t==0 else "grid"}"/>'
                     f'<text x="{pad_l-6}" y="{y(t)+4:.1f}" text-anchor="end" class="tick">{"+" if t>0 else "−" if t<0 else ""}${abs(t):g}</text>')
        t += step
    for i, d in enumerate(days):
        x = pad_l + i * bw + bw * 0.15
        v = d["pnl"]; y0, y1 = sorted((y(0), y(v)))
        parts.append(f'<rect x="{x:.1f}" y="{y0:.1f}" width="{bw*0.7:.1f}" height="{max(1, y1-y0):.1f}" class="b {cls(v)}"><title>{d["date"]}: {money(v)}</title></rect>')
        if i % max(1, n // 8) == 0 or i == n - 1:
            lab = dt.date.fromisoformat(d["date"]).strftime("%-d %b")
            parts.append(f'<text x="{x+bw*0.35:.1f}" y="{h-8}" text-anchor="middle" class="tick">{lab}</text>')
    return f'<div class="chartbox"><svg viewBox="0 0 {w} {h}" role="img" aria-label="Daily profit and loss">{"".join(parts)}</svg></div>'

def section(key, title, market_name, note):
    s = json.load(open(f"{ROOT}/{key}/results/suite.json"))
    acc = s["portfolio"]["stacked"]["account"]
    days = acc["days"]
    parts = list(days[0]["parts"])
    names = [SHORT.get(p.split(":")[0], p) for p in parts]
    start, end = days[0]["start"], days[-1]["end"]
    tot = end - start
    up = sum(d["pnl"] > 0.004 for d in days); dn = sum(d["pnl"] < -0.004 for d in days)
    best = max(days, key=lambda d: d["pnl"]); worst = min(days, key=lambda d: d["pnl"])
    part_tot = {n: sum(d["parts"][p] for d in days) for n, p in zip(names, parts)}
    mk = 1.0
    for d in days: mk *= 1 + d["market_ret"]
    fmt_d = lambda d: dt.date.fromisoformat(d).strftime("%a %-d %b")
    rows = []
    for d in days:
        bits = "".join(f'<td class="{cls(d["parts"][p])}">{money(d["parts"][p]) if abs(d["parts"][p])>0.004 else "—"}</td>' for p in parts)
        rows.append(f'<tr><td>{fmt_d(d["date"])}</td><td>{money(d["start"],False)}</td>{bits}'
                    f'<td class="{cls(d["pnl"])} strong">{money(d["pnl"]) if abs(d["pnl"])>0.004 else "$0.00"}</td>'
                    f'<td class="{cls(d["pnl"])}">{100*d["pnl"]/d["start"]:+.2f}%</td><td>{money(d["end"],False)}</td>'
                    f'<td class="{cls(d["market_ret"])} muted">{100*d["market_ret"]:+.2f}%</td></tr>')
    foot = "".join(f'<td class="{cls(v)}">{money(v)}</td>' for v in part_tot.values())
    return f'''
<section id="{key}">
  <div class="sec-head"><h2>{title}</h2><p>{note}</p></div>
  <div class="figs">
    <div class="fig"><span class="k">Start → end</span><span class="v">{money(start,False)} → {money(end,False)}</span></div>
    <div class="fig"><span class="k">Month P&amp;L</span><span class="v {cls(tot)}">{money(tot)} <small>({100*tot/start:+.1f}%)</small></span></div>
    <div class="fig"><span class="k">Up / down days</span><span class="v">{up} / {dn}</span><span class="n">{len(days)-up-dn} flat</span></div>
    <div class="fig"><span class="k">Best day</span><span class="v pos">{money(best["pnl"])}</span><span class="n">{fmt_d(best["date"])}</span></div>
    <div class="fig"><span class="k">Worst day</span><span class="v neg">{money(worst["pnl"])}</span><span class="n">{fmt_d(worst["date"])}</span></div>
    <div class="fig"><span class="k">{market_name} itself</span><span class="v {cls(mk-1)}">{100*(mk-1):+.1f}%</span><span class="n">same days</span></div>
  </div>
  {chart(days)}
  <div class="tbl"><table>
    <thead><tr><th>Day</th><th>Start</th>{"".join(f"<th>{html.escape(n)}</th>" for n in names)}<th>Day P&amp;L</th><th>%</th><th>End</th><th>{market_name}</th></tr></thead>
    <tbody>{"".join(rows)}</tbody>
    <tfoot><tr><td>Month</td><td>{money(start,False)}</td>{foot}<td class="{cls(tot)} strong">{money(tot)}</td><td class="{cls(tot)}">{100*tot/start:+.2f}%</td><td>{money(end,False)}</td><td class="{cls(mk-1)} muted">{100*(mk-1):+.1f}%</td></tr></tfoot>
  </table></div>
  <p class="caveat">Parts: {"; ".join(html.escape(p) for p in parts)}. Each part trades the full account (stacked). Account started at $100 on {fmt_d(s["window"][0][:10])}.</p>
</section>'''

css = open(f"{ROOT}/tools/marketlab/page/report.css").read()
page = f'''<title>Last Month Day by Day</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600&display=swap">
<style>
:root {{ --accent-l:#2f5bd3; --accent-d:#8aa8ff; --accent-soft-l:#e7edfb; --accent-soft-d:#1c2540; --zero:#9aa1ad; }}
{css}
.fig .v small {{ font-size: 13px; font-weight: 500; }}
.chartbox {{ background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px; overflow-x: auto; }}
.chartbox svg {{ width: 100%; min-width: 520px; height: auto; display: block; }}
.grid {{ stroke: var(--line-2); }} .axis0 {{ stroke: var(--ink-3); }}
.tick {{ fill: var(--ink-3); font: 11px var(--mono); }}
rect.b.pos {{ fill: var(--up); }} rect.b.neg {{ fill: var(--down); }} rect.b.zero {{ fill: var(--line); }}
td.zero {{ color: var(--ink-3); }} td.strong {{ font-weight: 600; }} td.muted.pos, td.muted.neg {{ opacity: .75; }}
tfoot td {{ border-top: 2px solid var(--line); font-weight: 600; }}
td {{ font-family: var(--mono); font-size: 12.5px; }} td:first-child {{ font-family: var(--sans); font-size: 13px; }}
</style>
<div class="wrap">
<header class="top">
  <span class="eyebrow">$100 accounts · strategy v2 · last month</span>
  <h1>Last month, day by day</h1>
  <p class="sub">Profit and loss for every day of the last month in both $100 accounts, split by strategy part. These are the stacked portfolios from the strategy v2 report (they ended the 3 months at $122.70 for BTC and $102.15 for gold). The table shows each day's starting balance, what each part made, the day's result, and how the market itself moved.</p>
  <nav class="toc"><a href="#crypto">BTC</a><a href="#forex">Gold</a><a href="#notes">What stands out</a></nav>
</header>
{section("crypto", "BTC: 28 Aug – 26 Sep (30 days)", "BTC", "BTC trades every day, including weekends. The straddle part books each week's result on the day the straddle closes, so its money shows up in single large days.")}
{section("forex", "Gold: 27 Aug – 25 Sep (22 trading days)", "Gold", "Gold trades Monday to Friday; weekend days are not listed. The straddle filter (GVZ rich and alarm quiet) never passed this month, so that part stayed flat.")}
<section id="notes"><div class="sec-head"><h2>What stands out</h2></div><div class="notes">
<div class="note"><b>BTC lost $11.46 this month, and two straddle weeks did most of it</b><p>The account fell from $134.16 to $122.70 while BTC itself rose 5.2%. The straddle part lost $9.67 in total. It came from two weekly straddles that closed at a loss: $5.49 on Fri 28 Aug and $4.18 on Fri 18 Sep, a day BTC jumped 5.9%. Trend following lost $1.86. It gave back money in the choppy first half of September, then made $7.66 on 21 Sep when BTC rose 6.7% and lost $2.66 two days later. Funding carry was off for almost the whole month and added $0.07.</p></div>
<div class="note"><b>Gold ended the month almost exactly where it started, while gold fell 6.7%</b><p>$102.00 to $102.15. The fade/dip-buy part made $3.85, mostly from being short into two sharp drops ($3.36 on 28 Aug and $1.41 on 1 Sep). The trend part was long all month and lost $3.70 as gold slid. The two parts cancelled out.</p></div>
<div class="note"><b>How to read this</b><p>This is a backtest on past prices, not live trading: the trades were simulated with estimated costs and slippage. A single month is too short to judge a strategy. The 3-month and multi-year numbers are in the strategy v2 report.</p></div>
</div></section>
<footer><span>Source: crypto/results/suite.json and forex/results/suite.json (portfolio → stacked → account → days).</span></footer>
</div>'''
open(OUT, "w").write(page)
print(OUT)
