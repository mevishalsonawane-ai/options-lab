"""h40: NSDL FPI 'Daily Trends in FPI Investments' archive (public ASP.NET form, one month per request).
Keeps the Equity / Stock Exchange row per reporting date (gross buy, gross sell, net, Rs crore).
NOTE: an NSDL 'reporting date' R covers FPI trades done on the previous trading day(s) (custodian reports).
    python3 -I fetch_nsdl.py
"""
import datetime as dt, os, re, subprocess, sys, time, urllib.parse
S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h40"
UA = "options-lab-research/1.0 (personal academic backtest; polite)"
URL = "https://www.fpi.nsdl.co.in/web/Reports/Archive.aspx"
tmp = f"{S}/raw/nsdl_tmp.html"
out = f"{S}/data/nsdl_fpi_equity.csv"
done = set()
if os.path.exists(out):
    done = {l.split(",")[0] for l in open(out)}
fo = open(out, "a")
if not done:
    fo.write("month,rep_date,gross_buy,gross_sell,net\n")
num = lambda s: -float(s.strip("()").replace(",", "")) if s.startswith("(") else float(s.replace(",", ""))
m = dt.date(2019, 12, 1)
while m <= dt.date(2026, 10, 1):
    nm = (m.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
    last = min(nm - dt.timedelta(days=1), dt.date(2026, 10, 7))
    key = f"{m:%Y-%m}"
    if key in done:
        m = nm; continue
    subprocess.run(["curl", "-sS", "-m", "60", "-A", UA, "-o", tmp, URL])
    t = open(tmp, errors="replace").read()
    f = {}
    for n in ["__VIEWSTATE", "__VIEWSTATEGENERATOR", "__EVENTVALIDATION"]:
        z = re.search(r'name="%s" id="%s" value="([^"]*)"' % (n, n), t)
        f[n] = z.group(1) if z else ""
    d = f"{last:%d-%b-%Y}"
    f.update({"__EVENTTARGET": "btnSubmit1", "__EVENTARGUMENT": "", "hdnDate": d, "txtDate": d,
              "HdnValexceldata": "", "hdnFlag": ""})
    time.sleep(3)
    open(tmp + ".post", "w").write(urllib.parse.urlencode(f))
    subprocess.run(["curl", "-sS", "-m", "90", "-A", UA, "-o", tmp, "--data", "@" + tmp + ".post", URL])
    t = open(tmp, errors="replace").read()
    txt = re.sub(r"\s+", " ", re.sub("<[^>]+>", " ", t))
    rows = re.findall(r"(\d{2}-\w{3}-\d{4}) Equity Stock Exchange ([\d.,()]+) ([\d.,()]+) ([\d.,()]+)", txt)
    for r in rows:
        fo.write(f"{key},{dt.datetime.strptime(r[0], '%d-%b-%Y').date()},{num(r[1])},{num(r[2])},{num(r[3])}\n")
    fo.flush()
    print(key, len(rows), flush=True)
    time.sleep(3)
    m = nm
os.remove(tmp)
print("done")
