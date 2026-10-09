"""R3: histdata.com XAUUSD 1-minute (ASCII, bar times New York local incl. DST) yearly zips.
Zips are untrusted: saved in scratchpad/hunt/r3/hd/, only numeric CSV rows are parsed. -> raw/xauH_<Y>.parquet (ts UTC)"""
import re, sys, io, zipfile, time, urllib.request, urllib.parse, http.cookiejar
from pathlib import Path
import pandas as pd
R = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r3")
cj = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
UA = {"User-Agent": "Mozilla/5.0"}
for y in sys.argv[1:]:
    out = R / "raw" / f"xauH_{y}.parquet"
    if out.exists():
        continue
    ref = f"https://www.histdata.com/download-free-forex-historical-data/?/ascii/1-minute-bar-quotes/xauusd/{y}"
    html = op.open(urllib.request.Request(ref, headers=UA), timeout=60).read().decode("latin-1")
    tk = re.search(r'id="tk" value="([^"]+)"', html)
    if not tk:
        print(y, "no token", flush=True); continue
    data = urllib.parse.urlencode({"tk": tk.group(1), "date": y, "datemonth": y, "platform": "ASCII", "timeframe": "M1", "fxpair": "XAUUSD"}).encode()
    b = op.open(urllib.request.Request("https://www.histdata.com/get.php", data=data, headers={**UA, "Referer": ref}), timeout=120).read()
    (R / "hd" / f"xau_{y}.zip").write_bytes(b)
    z = zipfile.ZipFile(io.BytesIO(b))
    name = [n for n in z.namelist() if n.lower().endswith(".csv")][0]
    df = pd.read_csv(z.open(name), sep=";", header=None, names=["t", "open", "high", "low", "close", "v"], dtype={"t": str})
    df = df[df.t.str.fullmatch(r"\d{8} \d{6}")]
    # bar times are New York local time WITH US DST (checked against Dukascopy UTC in Oct-Dec 2023: corr 1.0)
    df["ts"] = (pd.to_datetime(df.t, format="%Y%m%d %H%M%S").dt.tz_localize("America/New_York", ambiguous="NaT", nonexistent="NaT")
                .dt.tz_convert("UTC").dt.tz_localize(None))
    df = df.dropna(subset=["ts"])
    df = df[["ts", "open", "high", "low", "close"]]
    for c in ["open", "high", "low", "close"]:
        df[c] = pd.to_numeric(df[c], errors="coerce").astype("float32")
    df.dropna().to_parquet(out, compression="zstd")
    print(y, len(df), df.ts.min(), df.ts.max(), flush=True)
    time.sleep(3)
