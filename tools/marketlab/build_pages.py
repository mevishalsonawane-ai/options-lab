"""Build crypto/index.html and forex/index.html from each folder's results/results.json and notes.html.

  python tools/marketlab/build_pages.py            full pages (with <!doctype>) in the folders
  python tools/marketlab/build_pages.py --frag D   also write body-only copies into directory D
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PAGE = os.path.join(os.path.dirname(__file__), "page")

OPTIONS = """
  <section id="options">
    <div class="sec-head"><h2>The options market</h2>
      <p>Deribit BTC options. The history comes from DVOL, Deribit's 30-day implied-volatility index, and from
      option trades sampled in three one-hour windows a day. The open interest and smile are from today's full chain.</p></div>
    <div class="figs" id="opt-figs"></div>
    <div class="grid2">
      <div class="panel"><h3>Implied volatility at the money, by tenor</h3><div class="chart" id="iv-chart"></div><div id="iv-legend"></div></div>
      <div class="panel"><h3>Put skew: 90% put IV minus 110% call IV (vol points)</h3><div class="chart" id="skew-chart"></div>
        <p class="caveat">Above zero means traders pay more for crash protection than for upside.</p></div>
      <div class="panel"><h3>Put/call traded volume ratio (5-day average)</h3><div class="chart" id="pc-chart"></div></div>
      <div class="panel"><h3>Do these signals point to the next week?</h3><div id="quint"></div>
        <p class="caveat">Average BTC move over the next 7 days, split into fifths by the signal's level.</p></div>
    </div>
    <p class="caveat" id="chain-when"></p>
    <div class="grid2">
      <div class="panel"><h3>Term structure</h3><div class="cv" id="term"></div></div>
      <div class="panel"><h3>Smile, expiry nearest 30 days</h3><div class="cv" id="smile"></div></div>
    </div>
    <div class="panel"><h3>Open interest by strike, all expiries</h3><div class="cv" id="oi"></div></div>
    <div class="panel"><h3>Expiries</h3><div id="expiries"></div>
      <p class="caveat">Max pain is the settlement price at which option buyers, in total, would be paid least.</p></div>
  </section>"""

SESSIONS = """<div class="panel"><h3>Trading sessions</h3><div id="sessions"></div></div>"""

MARKETS = {
    "crypto": {
        "TITLE": "Bitcoin Three-Year Study", "ACCENT_L": "#c96a00", "ACCENT_D": "#f2994a",
        "DESCRIPTION": "BTC/USDT over three years: chart study, Deribit options and walk-forward neural network results.",
        "EYEBROW": "BTC / USDT · Deribit options · 3 years",
        "H1": "Bitcoin, three years under the microscope",
        "LEDE": "Daily and hourly BTC/USDT from Binance, with implied volatility, skew, put/call flows and today's option chain from Deribit. The page ends by testing whether a neural network can call the next move.",
        "TOC_EXTRA": '<a href="#options">Options</a>', "OPTIONS": OPTIONS, "SESSIONS": "",
        "NET_EXTRA": ". The fuller model adds option-market inputs (DVOL, implied minus realised vol, skew, put/call, term slope) and daily moves in the S&amp;P 500, dollar and gold",
        "SOURCES": "Sources: Binance (BTCUSDT), Deribit (DVOL, option trades, chain), Yahoo Finance (S&amp;P 500, dollar index, gold).",
    },
    "forex": {
        "TITLE": "Gold Three-Year Study", "ACCENT_L": "#9a7400", "ACCENT_D": "#e3b93f",
        "DESCRIPTION": "XAU/USD over three years: chart study, sessions, macro links and walk-forward neural network results.",
        "EYEBROW": "XAU / USD · spot gold · 3 years",
        "H1": "Gold, three years under the microscope",
        "LEDE": "Hourly spot gold, with daily candles closed at 17:00 New York, studied for trend, volatility, sessions and its links to the dollar, yields, silver and stocks. The page ends by testing whether a neural network can call the next move.",
        "TOC_EXTRA": "", "OPTIONS": "", "SESSIONS": SESSIONS,
        "NET_EXTRA": ". The fuller model adds the daily and 5-day moves in the dollar index, the US 10-year yield, silver, the S&amp;P 500 and bitcoin",
        "SOURCES": "Sources: Dukascopy (XAU/USD), Yahoo Finance (dollar index, US 10-year, silver, S&amp;P 500, gold futures, bitcoin).",
    },
}

METHOD = """
<div class="note"><b>Out-of-sample only</b><p>Each model trains on the history before a block, predicts the block, then retrains with it included. Nothing it is scored on was in its training data, and every input at a bar uses only data up to that bar's close.</p></div>
<div class="note"><b>Costs are charged</b><p>The strategy lines pay a cost every time the position changes: {cost} a side. Long + short takes the network's side when it is more than 52% sure one way; long-or-flat only takes the long side.</p></div>
<div class="note"><b>Compared with simple rules</b><p>A result only counts if it beats a coin, "always bet on the more common direction" and "repeat the last bar", by more than chance allows (p below 0.05).</p></div>
<div class="note"><b>Limits</b><p>Three years is about {days} daily bars, which is small for a neural network. Markets change character over time, and a pattern found in one regime can fade in the next. This is a study, not a trading system.</p></div>"""


def build(market, frag_dir=None):
    d = os.path.join(ROOT, market)
    res_path = os.path.join(d, "results", "results.json")
    if not os.path.exists(res_path):
        print(f"{market}: no results yet")
        return
    data = json.load(open(res_path))
    pat_path = os.path.join(d, "results", "patterns.json")
    if os.path.exists(pat_path):
        data["patterns"] = json.load(open(pat_path))
    notes_path = os.path.join(d, "notes.html")
    notes = open(notes_path).read() if os.path.exists(notes_path) else '<div class="note"><b>Pending</b><p>Notes are written after the run.</p></div>'
    cfg = dict(MARKETS[market])
    cfg["NOTES"] = notes
    pdef = data.get("patterns", {}).get("definition", {})
    cfg["THR_H"] = f"{pdef.get('hourly_sigma', 3):g}&times;"
    cfg["THR_D"] = f"{pdef.get('daily_sigma', 2):g}&times;"
    cfg["METHOD"] = METHOD.format(cost="0.05%" if market == "crypto" else "0.015%",
                                  days=data["study"]["summary"]["bars"])
    cfg["CSS"] = open(os.path.join(PAGE, "report.css")).read()
    cfg["JS"] = open(os.path.join(PAGE, "report.js")).read()
    cfg["DATA"] = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    html = open(os.path.join(PAGE, "template.html")).read()
    for k, v in cfg.items():
        html = html.replace("{{" + k + "}}", v)
    full = ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            + html.replace("<div class=\"wrap\">", "</head>\n<body>\n<div class=\"wrap\">", 1) + "\n</body>\n</html>\n")
    open(os.path.join(d, "index.html"), "w").write(full)
    print(f"{market}/index.html {len(full) / 1e6:.2f} MB")
    if frag_dir:
        os.makedirs(frag_dir, exist_ok=True)
        p = os.path.join(frag_dir, f"{market}.html")
        open(p, "w").write(html)
        print(f"  fragment {p}")


if __name__ == "__main__":
    frag = sys.argv[sys.argv.index("--frag") + 1] if "--frag" in sys.argv else None
    for m in ("crypto", "forex"):
        build(m, frag)
