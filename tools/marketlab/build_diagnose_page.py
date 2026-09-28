"""Build why_no_profit.html (repo root) from crypto/ and forex/ results/diagnose.json and page/diagnose_notes.html.

  python tools/marketlab/build_diagnose_page.py            full page with <!doctype>
  python tools/marketlab/build_diagnose_page.py --frag F   also write a body-only copy to file F
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PAGE = os.path.join(os.path.dirname(__file__), "page")


def section(key, title, eyebrow, options=False):
    opt = """
    <div class="sec-head"><h3>The option leg: the alarm sees big weeks coming, so the straddle was on the wrong side</h3></div>
    <div class="figs alarm-weeks"></div>
    <div class="panel"><h3>Weekly straddle rules compared (P&amp;L as % of BTC price, per week, summed)</h3><div class="opt"></div></div>""" if options else ""
    return f"""
  <section id="{key}">
    <div class="sec-head"><span class="eyebrow">{eyebrow}</span><h2>{title}</h2></div>
    <div class="sec-head"><h3>1 · What each session offered (last 3 months)</h3></div>
    <div class="figs sess-figs"></div>
    <div class="panel"><div class="cv sess-chart" style="height:240px"></div>
      <p class="caveat">Most of each day's range is up-and-down noise: the close usually lands far from the extremes. Catching the range needs you to call both the turn and the direction inside the session.</p></div>
    <div class="sec-head"><h3>2 · Every bar and every multi-bar window: what the network could call</h3>
      <p>At every hourly bar, a network given the last 24 bars (returns, ranges, trend, volatility, hour and day) predicted the direction and the size of the move over the next 1, 2, 4, 8, 12 and 24 bars, walk-forward and out-of-sample. "Needed to pay costs" is the hit rate at which trading the average move just breaks even after costs.</p></div>
    <div class="grid2">
      <div class="panel"><h3>Direction: hit rate against what costs require</h3><div class="cv scan-chart"></div></div>
      <div class="panel"><h3>By horizon</h3><div class="scan"></div></div>
    </div>
    <div class="grid2">
      <div class="panel"><h3>The daily horizon, by period</h3><div class="scan-seg"></div>
        <p class="caveat">Size correlation is between the forecast size and the actual size of the move, where 0 means no skill.</p></div>
      <div class="panel"><h3>Next-bar hit rate by entry hour</h3><div class="cv hour-chart"></div></div>
    </div>
    <div class="sec-head"><h3>3 · Trade autopsy: each trade, how far it went for and against us</h3></div>
    <div class="figs autopsy-figs"></div>
    <div class="panel"><div class="autopsy"></div></div>
    <div class="sec-head"><h3>4 · Fixes, chosen on the development year and then checked on the last 3 months</h3></div>
    <div class="panel"><div class="variants"></div>
      <p class="caveat">Development year: the year before the test window, where the alarms were already out-of-sample. A fix counts only if it helps there, not just in the three months we have already seen.</p></div>
    {opt}
  </section>"""


def main():
    data = {m: json.load(open(os.path.join(ROOT, m, "results", "diagnose.json"))) for m in ("crypto", "forex")}
    notes_p = os.path.join(PAGE, "diagnose_notes.html")
    notes = open(notes_p).read() if os.path.exists(notes_p) else ""
    css = open(os.path.join(PAGE, "report.css")).read()
    js = open(os.path.join(PAGE, "diagnose.js")).read()
    blob = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    sec_c = section("crypto", "Bitcoin", "BTC/USDT", options=True)
    sec_f = section("forex", "Gold", "XAU/USD")
    body = f"""<title>Why No Profit</title>
<meta name="description" content="Bar-by-bar and multi-bar neural-network diagnosis of why the BTC and gold strategies did not make money, with fixes tested out-of-sample.">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap">
<style>
:root {{ --accent-l: #7a3fc4; --accent-d: #b48cff; }}
{css}
.note ul {{ margin: 6px 0 0; padding-left: 18px; color: var(--ink-2); }}
</style>
<div class="wrap">
  <header class="top">
    <span class="eyebrow">Diagnosis · BTC and gold · every bar, every horizon</span>
    <h1>Why the strategies did not make money</h1>
    <p class="sub">The markets move a lot inside every session. This page checks what was on offer, what a neural network can and cannot call at every bar and over multi-bar windows, where each trade gained and lost, and which fixes survive a fair test.</p>
    <nav class="toc" aria-label="Sections"><a href="#answer">The answer</a><a href="#crypto">Bitcoin</a><a href="#forex">Gold</a></nav>
  </header>
  <section id="answer"><div class="sec-head"><h2>The short answer</h2></div><div class="notes">{notes}</div></section>
  {sec_c}
  {sec_f}
  <footer><span>All network scores are walk-forward and out-of-sample. Costs: BTC 0.06% a side, gold 0.015% a side, options 0.03% of the underlying per leg.</span>
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
    open(os.path.join(ROOT, "why_no_profit.html"), "w").write(full)
    print(f"why_no_profit.html {len(full) / 1e6:.2f} MB")
    if "--frag" in sys.argv:
        f = sys.argv[sys.argv.index("--frag") + 1]
        open(f, "w").write(body)


if __name__ == "__main__":
    main()
