"""More BTC inputs into crypto/data_more/:

  yahoo_*.csv         daily: spot BTC ETFs (IBIT, FBTC, GBTC), CME bitcoin futures (BTC=F), MSTR, COIN, Nasdaq 100
                      (QQQ), VIX, 13-week T-bill (^IRX), 5-year (^FVX)
  stablecoins.csv     total stablecoin supply (DefiLlama)
  onchain_*.csv       blockchain.com charts: hash rate, transactions, on-chain USD volume, unique addresses,
                      miners' revenue
  premium_index_1h.csv  Binance BTCUSDT perpetual premium index (perp vs index), hourly
  events.csv          FOMC decision days (federalreserve.gov) and US jobs-report days

Only public endpoints, no keys. Run from the repo root: python crypto/fetch_more.py
"""
import datetime as dt
import io
import os
import re
import sys
import zipfile

import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.net import get, get_json  # noqa: E402
from marketlab.yahoo import chart  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data_more")
DAYS = 3 * 365 + 60
NOW = dt.datetime.now(dt.timezone.utc)
START = NOW - dt.timedelta(days=DAYS)


def save(name, df):
    if df is None or len(df) == 0:
        print(f"  {name}: no data")
        return
    df.to_csv(os.path.join(OUT, name))
    print(f"  {name}: {len(df)} rows")


def stablecoins():
    j = get_json("https://stablecoins.llama.fi/stablecoincharts/all") or []
    rows = [(int(x["date"]), float((x.get("totalCirculatingUSD") or {}).get("peggedUSD", 0))) for x in j]
    df = pd.DataFrame(rows, columns=["t", "total_usd"])
    df["time"] = pd.to_datetime(df["t"], unit="s", utc=True)
    return df.set_index("time")[["total_usd"]].loc[lambda d: d.index >= pd.Timestamp(START)]


def onchain(name):
    j = get_json(f"https://api.blockchain.info/charts/{name}?timespan=4years&format=json&sampled=false") or {}
    df = pd.DataFrame(j.get("values", []))
    if df.empty:
        return None
    df["time"] = pd.to_datetime(df["x"], unit="s", utc=True)
    return df.set_index("time")[["y"]].rename(columns={"y": name}).loc[lambda d: d.index >= pd.Timestamp(START)]


def premium_index():
    frames = []
    y, m = START.year, START.month
    while (y, m) <= (NOW.year, NOW.month):
        b = get(f"https://data.binance.vision/data/futures/um/monthly/premiumIndexKlines/BTCUSDT/1h/BTCUSDT-1h-{y}-{m:02d}.zip")
        if b:
            with zipfile.ZipFile(io.BytesIO(b)) as z, z.open(z.namelist()[0]) as f:
                df = pd.read_csv(f, header=None)
            df = df[pd.to_numeric(df[0], errors="coerce").notna()]
            frames.append(pd.DataFrame({"time": pd.to_datetime(df[0].astype("int64"), unit="ms", utc=True),
                                        "premium_close": df[4].astype(float), "premium_high": df[2].astype(float),
                                        "premium_low": df[3].astype(float)}))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    if not frames:
        return None
    return pd.concat(frames).set_index("time").sort_index().loc[lambda d: ~d.index.duplicated()]


def fomc_days():
    """FOMC decision days from the Fed's calendar pages (the second day of each two-day meeting)."""
    days = set()
    months = {m: i + 1 for i, m in enumerate(["January", "February", "March", "April", "May", "June", "July", "August",
                                              "September", "October", "November", "December"])}
    pages = ["https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"]
    pages += [f"https://www.federalreserve.gov/monetarypolicy/fomchistorical{y}.htm" for y in range(START.year, NOW.year + 1)]
    for url in pages:
        try:
            html = (get(url) or b"").decode("utf-8", "ignore")
        except Exception as e:  # noqa: BLE001
            print(f"  fomc {url}: {type(e).__name__}")
            continue
        # calendar page: "<strong>2025 FOMC Meetings</strong>" sections with month + "28-29" style dates
        for ym in re.finditer(r"(\d{4}) FOMC Meetings(.*?)(?=\d{4} FOMC Meetings|$)", html, re.S):
            year, block = int(ym.group(1)), ym.group(2)
            for mm in re.finditer(r"fomc-meeting__month[^>]*>\s*<strong>([A-Za-z/]+)</strong>.*?fomc-meeting__date[^>]*>([\d\-–*]+)", block, re.S):
                mon = mm.group(1).split("/")[-1]
                dd = re.findall(r"\d+", mm.group(2))
                if mon in months and dd:
                    try:
                        days.add(dt.date(year, months[mon], int(dd[-1])))
                    except ValueError:
                        pass
        for mm in re.finditer(r"(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{1,2})(?:-(\d{1,2}))?,\s+(\d{4})\s*(?:Meeting|FOMC)", html):
            try:
                days.add(dt.date(int(mm.group(4)), months[mm.group(1)], int(mm.group(3) or mm.group(2))))
            except ValueError:
                pass
    # the published schedule, in case the page layout changes (decision day = second day of the meeting)
    known = ["2023-02-01", "2023-03-22", "2023-05-03", "2023-06-14", "2023-07-26", "2023-09-20", "2023-11-01", "2023-12-13",
             "2024-01-31", "2024-03-20", "2024-05-01", "2024-06-12", "2024-07-31", "2024-09-18", "2024-11-07", "2024-12-18",
             "2025-01-29", "2025-03-19", "2025-05-07", "2025-06-18", "2025-07-30", "2025-09-17", "2025-10-29", "2025-12-10",
             "2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16", "2026-10-28", "2026-12-09"]
    days |= {dt.date.fromisoformat(x) for x in known}
    return sorted(d for d in days if START.date() <= d <= NOW.date() + dt.timedelta(days=120))


def jobs_days():
    """US employment report: usually the first Friday of the month (moved when that Friday is the 1st and the
    data week ends late, or for holidays; the first Friday rule is right most months)."""
    out = []
    d = START.date().replace(day=1)
    while d <= NOW.date() + dt.timedelta(days=60):
        f = d + dt.timedelta(days=(4 - d.weekday()) % 7)
        out.append(f)
        d = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for sym, name in (("IBIT", "ibit"), ("FBTC", "fbtc"), ("GBTC", "gbtc"), ("BTC=F", "cme_btc"), ("MSTR", "mstr"),
                      ("COIN", "coin"), ("QQQ", "qqq"), ("^VIX", "vix"), ("^IRX", "t_bill_13w"), ("^FVX", "treasury_5y")):
        save(f"yahoo_{name}.csv", chart(sym, DAYS))
    try:
        save("stablecoins.csv", stablecoins())
    except Exception as e:  # noqa: BLE001
        print(f"  stablecoins failed: {type(e).__name__} {e}")
    for n in ("hash-rate", "n-transactions", "estimated-transaction-volume-usd", "n-unique-addresses", "miners-revenue"):
        try:
            save(f"onchain_{n}.csv", onchain(n))
        except Exception as e:  # noqa: BLE001
            print(f"  onchain {n} failed: {type(e).__name__} {e}")
    try:
        save("premium_index_1h.csv", premium_index())
    except Exception as e:  # noqa: BLE001
        print(f"  premium index failed: {type(e).__name__} {e}")
    fomc = fomc_days()
    print(f"  FOMC decision days found: {len(fomc)}")
    ev = pd.DataFrame([(d, "fomc") for d in fomc] + [(d, "jobs") for d in jobs_days()], columns=["date", "event"])
    ev.to_csv(os.path.join(OUT, "events.csv"), index=False)
    print("done")


if __name__ == "__main__":
    main()
