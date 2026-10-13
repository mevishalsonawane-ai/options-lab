#!/usr/bin/env python3
"""OF1: daily FPI (FII) cash-market flows from NSDL's public archive, one month per request, Jan 2012 -> today.

    python3 -I research/hunt/of1/fetch_fpi.py        # writes <cache>/of1/raw/fpi/fpi_daily.csv (+ one html per month)

NSDL's "Archive (Trends in FPI/FII Investments)" page (fpi.nsdl.co.in/web/Reports/Archive.aspx) returns, for the month
from its 1st up to the date posted (we post the month's last day), one block per REPORTING date: Equity / Debt / Hybrid, by route (stock exchange, primary market &
others), gross purchases, gross sales and net in Rs crore. We keep Equity via the stock exchange (the secondary cash
market) and the equity sub-total.

Timing (look-ahead): NSDL says the figures are "trades conducted by FPIs on and up to the previous trading day(s)", i.e.
a reporting date's row is published that day about trades of the day before. The analysis therefore treats a row as
known only at the END of its reporting date, never on the trade date.

DII cash flows are published only through NSE's www API, which refuses this machine (HTTP 403, Akamai), so they are
not fetched.
"""
from __future__ import annotations

import calendar
import html as H
import os
import re
import sys
import time
from datetime import date
from pathlib import Path

import requests

CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = CACHE / "of1" / "raw" / "fpi"
URL = "https://www.fpi.nsdl.co.in/web/Reports/Archive.aspx"
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0"}
NUM = r"\(?-?[\d,]+\.\d+\)?"
ROW = re.compile(rf"(\d\d-[A-Z][a-z]{{2}}-\d{{4}}) Equity Stock Exchange ({NUM}) ({NUM}) ({NUM}) ({NUM}).*?"
                 rf"Primary market & others {NUM} {NUM} {NUM} {NUM} Sub-total ({NUM}) ({NUM}) ({NUM})")


def num(s: str) -> float:
    s = s.replace(",", "")
    return -float(s.strip("()")) if s.startswith("(") else float(s)


def text(page: str) -> str:
    t = re.sub(r"<script.*?</script>", " ", page, flags=re.S)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"\s+", " ", H.unescape(t))


def parse(page: str) -> list[dict]:
    """Rows of one month's archive page: reporting date, equity via stock exchange (buy, sell, net), equity sub-total net."""
    out = []
    for m in ROW.finditer(text(page)):
        d = date(*time.strptime(m.group(1), "%d-%b-%Y")[:3])
        out.append({"report_date": d.isoformat(), "eq_exch_buy_cr": num(m.group(2)), "eq_exch_sell_cr": num(m.group(3)),
                    "eq_exch_net_cr": num(m.group(4)), "eq_total_net_cr": num(m.group(8))})
    return out


def month_page(s: requests.Session, y: int, m: int) -> str:
    p = OUT / f"{y}-{m:02d}.html"
    if p.exists() and (y, m) < (date.today().year, date.today().month):
        return p.read_text(encoding="utf-8")
    t = s.get(URL, headers=UA, timeout=60).text
    f = dict(re.findall(r'<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"', t))
    last = min(date(y, m, calendar.monthrange(y, m)[1]), date.today())   # the page lists the month UP TO this date
    f.update({"hdnDate": f"{last:%d-%b-%Y}", "__EVENTTARGET": "btnSubmit1", "__EVENTARGUMENT": ""})
    r = s.post(URL, data=f, headers=UA, timeout=90)
    r.raise_for_status()
    p.write_text(r.text, encoding="utf-8")
    time.sleep(1.0)
    return r.text


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    s = requests.Session()
    rows: dict[str, dict] = {}
    t = date.today()
    for y in range(2012, t.year + 1):
        for m in range(1, 13):
            if (y, m) > (t.year, t.month):
                break
            for a in range(3):
                try:
                    got = parse(month_page(s, y, m))
                    break
                except requests.RequestException:
                    time.sleep(5 * (a + 1))
            else:
                print(f"{y}-{m:02d}: failed", flush=True)
                continue
            for r in got:
                rows[r["report_date"]] = r
            print(f"{y}-{m:02d}: {len(got)} days", flush=True)
    cols = ["report_date", "eq_exch_buy_cr", "eq_exch_sell_cr", "eq_exch_net_cr", "eq_total_net_cr"]
    with open(OUT / "fpi_daily.csv", "w") as fh:
        fh.write(",".join(cols) + "\n")
        for k in sorted(rows):
            fh.write(",".join(str(rows[k][c]) for c in cols) + "\n")
    print(len(rows), "reporting days")
    return 0


if __name__ == "__main__":
    sys.exit(main())
