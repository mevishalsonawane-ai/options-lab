"""Build strategy_v2_report.html (repo root) from crypto/ and forex/ results/suite.json.

  python tools/marketlab/build_v2_page.py            full page with <!doctype>
  python tools/marketlab/build_v2_page.py --frag F   also write a body-only copy to file F
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PAGE = os.path.join(os.path.dirname(__file__), "page")


def section(key, title, eyebrow, extra):
    return f"""
  <section id="{key}">
    <div class="sec-head"><span class="eyebrow">{eyebrow}</span><h2>{title}</h2></div>
    <div class="panel"><h3>The $100 account</h3><div class="pf"></div></div>
    <div class="panel"><h3>Account value, last 3 months</h3><div class="chart" style="height:300px"><div class="eq" style="height:100%"></div></div><div class="eq-leg"></div></div>
    <div class="panel"><h3>The parts, each chosen on its record before the window</h3><div class="parts"></div>
      <p class="caveat">Each cell shows the return, then "S" for Sharpe and the worst fall.</p></div>
    <div class="sec-head"><h3>Every variant tested</h3></div>
    <div class="panel"><h3>Trend with volatility targeting</h3><div class="fam-trend"></div></div>
    <div class="panel"><h3>Weekly straddles</h3><div class="fam-strad"></div></div>
    {extra}
    <div class="panel"><h3>Straddle weeks in the last 3 months (chosen rule)</h3><div class="weeks"></div></div>
    <div class="panel"><h3>Neural network with the extra data</h3><div class="nn"></div><p class="caveat feat"></p></div>
    <div class="panel"><h3>Daily report, last 30 days</h3><div class="daily"></div></div>
  </section>"""


def main():
    data = {m: json.load(open(os.path.join(ROOT, m, "results", "suite.json"))) for m in ("crypto", "forex")}
    notes = open(os.path.join(PAGE, "v2_notes.html")).read()
    css = open(os.path.join(PAGE, "report.css")).read()
    js = open(os.path.join(PAGE, "v2.js")).read()
    blob = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    b, g = data["crypto"]["portfolio"], data["forex"]["portfolio"]
    sec_c = section("crypto", "Bitcoin: trend, straddle switch, funding carry", "BTC/USDT · $100",
                    '<div class="panel"><h3>Funding carry</h3><div class="fam-carry"></div></div>')
    sec_f = section("forex", "Gold: trend, GVZ straddles, original rules", "XAU/USD · $100",
                    '<div class="panel"><h3>Original rules</h3><div class="fam-orig"></div></div>')
    body = f"""<title>Strategy V2 Report</title>
<meta name="description" content="Research-based BTC and gold strategies with extra data, chosen before the test window and run with $100 each over the last 3 months.">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap">
<style>
:root {{ --accent-l: #0f7a6c; --accent-d: #3fd1bd; }}
{css}
.note ul {{ margin: 6px 0 0; padding-left: 18px; color: var(--ink-2); }}
.headline {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 300px), 1fr)); gap: 16px; }}
.headline .big {{ font: 600 28px/1.1 var(--mono); }}
</style>
<div class="wrap">
  <header class="top">
    <span class="eyebrow">Strategy v2 · research-based · $100 per market · last 3 months</span>
    <h1>Better strategies, chosen before the test</h1>
    <p class="sub">The strategies traders use with the best evidence (trend with volatility targeting, filtered option selling and buying, funding carry), rebuilt with a much larger data set: every hour of Deribit option trades, funding, open interest, positioning, GVZ and macro data. Each part was picked on its record before the last 3 months and then run on them unchanged.</p>
    <nav class="toc" aria-label="Sections"><a href="#verdict">Verdict</a><a href="#crypto">Bitcoin</a><a href="#forex">Gold</a></nav>
  </header>
  <section id="verdict">
    <div class="headline">
      <div class="panel"><h3>Bitcoin, $100 → stacked</h3><div class="big pos">${b['stacked']['account']['end']:.2f}</div>
        <p class="caveat">Balanced ${b['balanced']['account']['end']:.2f} · old strategy $93.64 · holding ${b['stacked']['account']['hold_end']:.2f}</p></div>
      <div class="panel"><h3>Gold, $100 → stacked</h3><div class="big pos">${g['stacked']['account']['end']:.2f}</div>
        <p class="caveat">Balanced ${g['balanced']['account']['end']:.2f} · old strategy $101.64 · holding ${g['stacked']['account']['hold_end']:.2f}</p></div>
      <div class="panel"><h3>All 3 years, stacked</h3><div class="big">{b['stacked']['full']['total_return'] * 100:+.0f}% / {g['stacked']['full']['total_return'] * 100:+.0f}%</div>
        <p class="caveat">BTC / gold. Worst falls {b['stacked']['full']['max_drawdown'] * 100:.0f}% / {g['stacked']['full']['max_drawdown'] * 100:.0f}%, against −53% / −27% for holding.</p></div>
    </div>
    <div class="notes">{notes}</div>
  </section>
  {sec_c}
  {sec_f}
  <footer><span>Research notes: docs/market_strategies_research.md. Options are priced with Black-Scholes at the traded ATM implied volatility (BTC) or GVZ (gold), plus slippage and fees.</span>
  <span>Research only, not advice.</span></footer>
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
    open(os.path.join(ROOT, "strategy_v2_report.html"), "w").write(full)
    print(f"strategy_v2_report.html {len(full) / 1e6:.2f} MB")
    if "--frag" in sys.argv:
        open(sys.argv[sys.argv.index("--frag") + 1], "w").write(body)


if __name__ == "__main__":
    main()
