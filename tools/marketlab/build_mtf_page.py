"""Build multi_timeframe_report.html (repo root) from results/mtf.json, suite.json and suite_mtf.json.

  python tools/marketlab/build_mtf_page.py            full page with <!doctype>
  python tools/marketlab/build_mtf_page.py --frag F   also write a body-only copy to file F
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PAGE = os.path.join(os.path.dirname(__file__), "page")


def load(m, name):
    p = os.path.join(ROOT, m, "results", name)
    return json.load(open(p)) if os.path.exists(p) else None


def section(key, title, eyebrow):
    return f"""
  <section id="{key}">
    <div class="sec-head"><span class="eyebrow">{eyebrow}</span><h2>{title}</h2></div>
    <div class="panel"><h3>The data: every timeframe built from the same 1-minute bars</h3><div class="bars"></div></div>
    <div class="panel"><h3>One network over all timeframes against the hourly-only network</h3><div class="cmp"></div>
      <p class="caveat">Walk-forward and out-of-sample: "dev" is the development year and "3m" is the last 3 months. Green marks a clear gain for all timeframes. "Trading it" follows the direction call on non-overlapping bars after costs.</p></div>
    <div class="panel"><h3>Which timeframes the network relies on</h3><div class="cv imp" style="height:300px"></div>
      <p class="caveat">Each timeframe's block of inputs is shuffled in turn on the development year; the bigger the drop in skill, the more the network depends on that timeframe.</p></div>
    <div class="panel"><h3>The $100 strategies with the new alarms</h3><div class="strat"></div></div>
    <div class="panel"><h3>Straddle rules: hourly alarms against all-timeframe alarms</h3><div class="strad"></div></div>
  </section>"""


def main():
    data = {m: {"mtf": load(m, "mtf.json"), "suite": load(m, "suite.json"), "suite_mtf": load(m, "suite_mtf.json")} for m in ("crypto", "forex")}
    notes = open(os.path.join(PAGE, "mtf_notes.html")).read() if os.path.exists(os.path.join(PAGE, "mtf_notes.html")) else ""
    css = open(os.path.join(PAGE, "report.css")).read()
    js = open(os.path.join(PAGE, "mtf.js")).read()
    blob = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    body = f"""<title>Multi-Timeframe Study</title>
<meta name="description" content="BTC and gold from 1-minute to 24-hour bars, joined by date and time into one neural network, against the hourly-only network.">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap">
<style>
:root {{ --accent-l: #b3470f; --accent-d: #ff8a4c; }}
{css}
.note ul {{ margin: 6px 0 0; padding-left: 18px; color: var(--ink-2); }}
</style>
<div class="wrap">
  <header class="top">
    <span class="eyebrow">1m · 5m · 15m · 30m · 1h · 3h · 6h · 12h · 24h · one network</span>
    <h1>Every timeframe in one network</h1>
    <p class="sub">Three years of 1-minute bars for BTC and gold, rebuilt into eight more timeframes and joined by date and time. At each hour the network sees only the bars that have closed by then, from the last minute up to the last day. It was compared with the hourly-only network, opened up to see which timeframes matter, and its alarms were fed back into the $100 strategies.</p>
    <nav class="toc" aria-label="Sections"><a href="#answer">Findings</a><a href="#crypto">Bitcoin</a><a href="#forex">Gold</a></nav>
  </header>
  <section id="answer"><div class="sec-head"><h2>What changed</h2></div><div class="notes">{notes}</div></section>
  {section("crypto", "Bitcoin", "BTC/USDT")}
  {section("forex", "Gold", "XAU/USD")}
  <footer><span>How the timeframes are joined: each timeframe's features are stamped with the bar's close time and attached to every hourly decision at or after it (an as-of join), so no decision sees an unfinished bar.</span>
  <span>Research only, not advice.</span></footer>
</div>
<script type="application/json" id="data">{blob}</script>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.js"></script>
<script>
{js}
</script>
"""
    full = ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            + body.replace('<div class="wrap">', '</head>\n<body>\n<div class="wrap">', 1) + "\n</body>\n</html>\n")
    open(os.path.join(ROOT, "multi_timeframe_report.html"), "w").write(full)
    print(f"multi_timeframe_report.html {len(full) / 1e6:.2f} MB")
    if "--frag" in sys.argv:
        open(sys.argv[sys.argv.index("--frag") + 1], "w").write(body)


if __name__ == "__main__":
    main()
