"""Build strategy_report.html (repo root) from crypto/ and forex/ results/strategy.json and strategy_notes.html.

  python tools/marketlab/build_strategy_page.py            full page with <!doctype>
  python tools/marketlab/build_strategy_page.py --frag F   also write a body-only copy to file F
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PAGE = os.path.join(os.path.dirname(__file__), "page")


def section(key, title, eyebrow, extra=""):
    return f"""
  <section id="{key}">
    <div class="sec-head"><span class="eyebrow">{eyebrow}</span><h2>{title}</h2></div>
    <div class="figs"></div>
    <div class="panel"><h3>Account value, $100 start</h3><div class="chart" style="height:320px"><div class="eq" style="height:100%"></div></div><div class="eq-leg"></div></div>
    <div class="grid2">
      <div class="panel"><h3>Month by month</h3><div class="months"></div></div>
      <div class="panel"><h3>What each rule made</h3><div class="parts"></div>
        <p class="caveat thr"></p><p class="caveat capnote"></p></div>
    </div>
    <details class="panel"><summary><h3 style="display:inline">The rules</h3></summary><pre class="rules"></pre></details>
    <div class="panel"><h3>Every trade in the 3 months</h3><div class="trades"></div>{extra}</div>
    <div class="panel"><h3>Daily report, last 30 days</h3><div class="daily"></div>
      <p class="caveat">Days are UTC calendar days. Alarm columns show the highest big-rise and big-drop probability the networks gave during the day.</p></div>
  </section>"""


def main():
    data = {}
    for m in ("crypto", "forex"):
        data[m] = json.load(open(os.path.join(ROOT, m, "results", "strategy.json")))
    notes_p = os.path.join(ROOT, "tools", "marketlab", "page", "strategy_notes.html")
    notes = open(notes_p).read() if os.path.exists(notes_p) else ""
    b, g = data["crypto"]["summary"], data["forex"]["summary"]
    start = b["start"][:10]
    end = max(b["end"], g["end"])[:10]
    css = open(os.path.join(PAGE, "report.css")).read()
    js = open(os.path.join(PAGE, "strategy.js")).read()
    blob = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    sk = '<h3 style="margin-top:14px">Fridays the straddle was not sold</h3><div class="skipped"></div>'
    sec_c = section("crypto", "Bitcoin: breakout long, risk short, weekly straddle sale", "BTC/USDT · $100", sk)
    sec_f = section("forex", "Gold: blow-off fade and buy the big drop", "XAU/USD · $100")
    body = f"""<title>Study Strategy Report</title>
<meta name="description" content="Strategies built from the BTC and gold studies, tested with $100 each over the last 3 months.">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap">
<style>
:root {{ --accent-l: #2f5fd0; --accent-d: #7aa2ff; }}
{css}
pre.rules {{ white-space: pre-wrap; font: 12.5px/1.6 var(--mono); color: var(--ink-2); margin: 10px 0 0; }}
details summary {{ cursor: pointer; }}
.daily-t td {{ vertical-align: top; }}
.daily-t td:nth-last-child(-n+4) {{ text-align: left; white-space: normal; min-width: 150px; }}
.note-cell {{ color: var(--ink-2); }}
.headline {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr)); gap: 16px; }}
.headline .panel .big {{ font: 600 30px/1.1 var(--mono); }}
</style>
<div class="wrap">
  <header class="top">
    <span class="eyebrow">Strategy test · $100 per market · {start} → {end}</span>
    <h1>Trading the study: three months with $100</h1>
    <p class="sub">Two rule sets built from the BTC and gold studies, tested walk-forward on the last three months. The rules and alarm thresholds were fixed from data before the window. Every trade pays costs, and nothing was tuned on the results shown here.</p>
    <nav class="toc" aria-label="Sections"><a href="#verdict">Verdict</a><a href="#crypto">Bitcoin</a><a href="#forex">Gold</a></nav>
  </header>
  <section id="verdict">
    <div class="headline">
      <div class="panel"><h3>Bitcoin account</h3><div class="big {'pos' if b['pnl_usd'] > 0 else 'neg'}">${b['end_capital']:.2f}</div>
        <p class="caveat">from $100 ({b['total_return'] * 100:+.2f}%). $100 simply held in BTC: ${b['benchmark_end_usd']:.2f}.</p></div>
      <div class="panel"><h3>Gold account</h3><div class="big {'pos' if g['pnl_usd'] > 0 else 'neg'}">${g['end_capital']:.2f}</div>
        <p class="caveat">from $100 ({g['total_return'] * 100:+.2f}%). $100 simply held in gold: ${g['benchmark_end_usd']:.2f}.</p></div>
      <div class="panel"><h3>Both together</h3><div class="big {'pos' if b['pnl_usd'] + g['pnl_usd'] > 0 else 'neg'}">${b['end_capital'] + g['end_capital']:.2f}</div>
        <p class="caveat">from $200. Simply holding both: ${b['benchmark_end_usd'] + g['benchmark_end_usd']:.2f}.</p></div>
    </div>
    <div class="notes">{notes}</div>
  </section>
  {sec_c}
  {sec_f}
  <footer><span>Straddle values are modelled with Black-Scholes at the short-dated ATM implied volatility seen the day before each sale; real fills would also pay the bid/ask spread.</span>
  <span>Research only, not advice. Past three months say little about the next three.</span></footer>
</div>
<script type="application/json" id="data">{blob}</script>
<script src="https://cdn.jsdelivr.net/npm/lightweight-charts@4.2.0/dist/lightweight-charts.standalone.production.js"></script>
<script>
{js}
</script>
"""
    full = ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            + body.replace('<div class="wrap">', '</head>\n<body>\n<div class="wrap">', 1) + "\n</body>\n</html>\n")
    out = os.path.join(ROOT, "strategy_report.html")
    open(out, "w").write(full)
    print(f"strategy_report.html {len(full) / 1e6:.2f} MB")
    if "--frag" in sys.argv:
        f = sys.argv[sys.argv.index("--frag") + 1]
        os.makedirs(os.path.dirname(f), exist_ok=True)
        open(f, "w").write(body)


if __name__ == "__main__":
    main()
