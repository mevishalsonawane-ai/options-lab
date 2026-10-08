"""h28 calendar flags (see PREREG.md). Run with python -I (raw pages and market data are untrusted):

    OBUY_CACHE=<scratch>/hunt/h28/cache python3 -I research/hunt/h28/cal.py

Reads raw public pages saved under <scratch>/hunt/h28/raw/ (Fed FOMC calendars, ALFRED CPI vintage dates, Wikipedia
Union-budget list, RBI monetary-policy page) and the daily / minute index data. Writes <scratch>/hunt/h28/flags.parquet
(und, day, daily OHLC, 42 boolean flags) and events.csv (every dated event with its source).
"""
from __future__ import annotations

import os
import re
import sys
from datetime import date, timedelta

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))          # research/
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402

SCR = C.SCRATCH
OUT = os.path.join(SCR, "hunt/h28")
RAW = os.path.join(OUT, "raw")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
HOLD = date(2025, 10, 1)
MON = {m: i + 1 for i, m in enumerate(["January", "February", "March", "April", "May", "June", "July", "August",
                                       "September", "October", "November", "December"])}

# ---------------------------------------------------------------------------------------------- recalled lists
DIWALI = ["2006-10-21", "2007-11-09", "2008-10-28", "2009-10-17", "2010-11-05", "2011-10-26", "2012-11-13",
          "2013-11-03", "2014-10-23", "2015-11-11", "2016-10-30", "2017-10-19", "2018-11-07", "2019-10-27",
          "2020-11-14", "2021-11-04", "2022-10-24", "2023-11-12", "2024-11-01", "2025-10-21"]
# RBI MPC decisions 2016-10 .. 2019 (recalled) + off-cycle 2020; 2020-08 .. 2025 = obuy gc_common list; 2026 from the
# RBI monetary-policy page (raw/rbi_annualpolicy.html) for Apr-Oct, Feb from gc_common.
RBI_OLD = ["2016-10-04", "2016-12-07", "2017-02-08", "2017-04-06", "2017-06-07", "2017-08-02", "2017-10-04",
           "2017-12-06", "2018-02-07", "2018-04-05", "2018-06-06", "2018-08-01", "2018-10-05", "2018-12-05",
           "2019-02-07", "2019-04-04", "2019-06-06", "2019-08-07", "2019-10-04", "2019-12-05", "2020-02-06",
           "2020-03-27", "2020-05-22"]
ELECTION_GENERAL = ["2009-05-16", "2014-05-16", "2019-05-23", "2024-06-04"]


def gc_lists():
    from obuy.strategies import gc_common as G
    return G


def parse_fomc():
    out = []
    t = open(os.path.join(RAW, "fomc_cal.html"), encoding="utf-8", errors="replace").read()
    s = re.sub(r"<[^>]+>", " ", t)
    s = re.sub(r"\s+", " ", s)
    parts = re.split(r"(20\d\d) FOMC Meetings", s)
    for i in range(1, len(parts) - 1, 2):
        y = int(parts[i])
        body = parts[i + 1]
        for m, d1, d2 in re.findall(r"(January|February|March|April|May|June|July|August|September|October|November|"
                                    r"December|Jan/Feb|Apr/May|Oct/Nov)\s+(\d{1,2})-(\d{1,2})\*?\s", body):
            mo = m.split("/")[-1]
            mo = {"Feb": "February", "May": "May", "Nov": "November"}.get(mo, mo)
            out.append(date(y, MON[mo], int(d2)))
    for y in range(2006, 2021):
        p = os.path.join(RAW, f"fomc_hist_{y}.html")
        if not os.path.exists(p):
            continue
        t = open(p, encoding="utf-8", errors="replace").read()
        s = re.sub(r"<[^>]+>", " ", t)
        s = re.sub(r"\s+", " ", s)
        for m, dd in re.findall(r"(January|February|March|April|May|June|July|August|September|October|November|"
                                r"December)\s+(\d{1,2}(?:-\d{1,2})?)\s+(?:FOMC )?Meeting", s):
            out.append(date(y, MON[m], int(dd.split("-")[-1])))
    out = sorted(set(d for d in out if date(2006, 1, 1) <= d <= date(2026, 12, 31)))
    return out


def parse_uscpi():
    t = open(os.path.join(RAW, "alfred_dl.html"), encoding="utf-8", errors="replace").read()
    ds = sorted(set(date.fromisoformat(x) for x in re.findall(r"(20[0-2]\d-\d\d-\d\d)", t)))
    ds = [d for d in ds if d.weekday() < 5 and d.year >= 2006]
    by = {}
    for d in ds:
        by.setdefault((d.year, d.month), []).append(d)
    out = []
    for k, v in by.items():
        mid = [d for d in v if 10 <= d.day <= 18]
        out.append(max(mid) if mid else max(v))
    return sorted(out)


def parse_budget():
    t = open(os.path.join(RAW, "wiki_budget.html"), encoding="utf-8", errors="replace").read()
    s = re.sub(r"<[^>]+>", " ", t)
    s = re.sub(r"\s+", " ", s)
    i = s.find("List of Union Budgets", s.find("List of Union Budgets") + 30)
    s = s[i:s.find("Union Budget History", i)]
    out = []
    for dd, m, y in re.findall(r"(\d{1,2}) (January|February|March|April|May|June|July|August|September|October|"
                               r"November|December) (\d{4})", s):
        d = date(int(y), MON[m], int(dd))
        if d.year >= 2006:
            out.append(d)
    # Wikipedia lists the July 2024 budget on 22 July; the speech (and market reaction) was 23 July 2024.
    out = [date(2024, 7, 23) if d == date(2024, 7, 22) else d for d in out]
    return sorted(set(out))


def parse_rbi_2026():
    t = open(os.path.join(RAW, "rbi_annualpolicy.html"), encoding="utf-8", errors="replace").read()
    s = re.sub(r"<[^>]+>", " ", t)
    s = re.sub(r"\s+", " ", s)
    out = []
    for m, dd, y in re.findall(r"Governor.s Statement: (\w+) (\d{1,2}), (20\d\d)", s):
        out.append(date(int(y), MON[m], int(dd)))
    return sorted(set(out))


# ---------------------------------------------------------------------------------------------- sessions
def daily(und, budget_days):
    df = pd.read_parquet(os.path.join(C.DATA, "candles/daily/IDX_I", f"{und}.parquet"))
    df["day"] = df.ts.dt.tz_localize(None).dt.date if df.ts.dt.tz is not None else df.ts.dt.date
    df = df.drop_duplicates("day").sort_values("day")
    wk = np.array([d.weekday() >= 5 for d in df.day])
    keep = ~wk | df.day.isin(budget_days).values
    keep &= ~df.day.isin([date.fromisoformat(x) for x in DIWALI]).values
    df = df[keep & (df.open > 0)]
    return df[["day", "open", "high", "low", "close"]].reset_index(drop=True)


def last_thursday(y, m):
    d = date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)
    while d.weekday() != 3:
        d -= timedelta(days=1)
    return d


def on_or_before(d, sess, sset):
    while d not in sset and d >= sess[0]:
        d -= timedelta(days=1)
    return d if d in sset else None


def next_after(d, sess_arr):
    i = np.searchsorted(sess_arr, np.datetime64(d), side="right")
    return pd.Timestamp(sess_arr[i]).date() if i < len(sess_arr) else None


def build():
    G = gc_lists()
    budget = parse_budget()
    fomc = parse_fomc()
    uscpi = parse_uscpi()
    rbi26 = parse_rbi_2026()
    rbi = sorted(set([date.fromisoformat(x) for x in RBI_OLD + G._RBI + ["2022-05-04"] if not x.startswith("2026")]
                     + [date.fromisoformat(x) for x in G._RBI if x.startswith("2026-02")] + rbi26))
    elect = sorted(set([date.fromisoformat(x) for x in ELECTION_GENERAL]
                       + [date.fromisoformat(d) for d, a in G._ELECTION if a == "pre"]))
    res = {k: [date.fromisoformat(x) for x in v] for k, v in G._RESULTS.items()}
    ev = ([(d, "budget", "wikipedia") for d in budget] + [(d, "rbi", "recall/gc/rbi.org.in") for d in rbi]
          + [(d, "election", "recall/gc") for d in elect] + [(d, "fomc", "federalreserve.gov") for d in fomc]
          + [(d, "uscpi", "alfred.stlouisfed.org") for d in uscpi]
          + [(d, f"results_{k}", "gc recall") for k, v in res.items() for d in v])
    evdf = pd.DataFrame(ev, columns=["date", "kind", "source"]).sort_values(["date", "kind"])
    os.makedirs(OUT, exist_ok=True)
    evdf.to_csv(os.path.join(OUT, "events.csv"), index=False)

    nifty = daily("NIFTY", budget)
    sess_all = list(nifty.day)
    sset_all = set(sess_all)
    sarr_all = np.array(sess_all, dtype="datetime64[D]")
    # exchange holidays: weekdays between first and last session with no session
    d0, d1 = sess_all[0], sess_all[-1]
    hol = set()
    d = d0
    while d <= d1:
        if d.weekday() < 5 and d not in sset_all:
            hol.add(d)
        d += timedelta(days=1)

    from obuy.data import Index
    rows = []
    for und in UNDS:
        df = daily(und, budget)
        try:
            ix = Index(und)
            era = {d: bool(ix.d[d]["exp"]) for d in ix.days}
        except Exception as e:  # noqa: BLE001
            print("no minute index for", und, e)
            era = {}
        era0 = min(era) if era else date(2100, 1, 1)
        sess = list(df.day)
        sset = set(sess)
        sarr = np.array(sess, dtype="datetime64[D]")
        n = len(sess)
        F = {}
        dt = pd.to_datetime(pd.Series(sess))
        for i, nm in enumerate(["mon", "tue", "wed", "thu", "fri"]):
            F[f"dow_{nm}"] = (dt.dt.weekday == i).values
        for m in range(1, 13):
            F[f"moy_{m:02d}"] = (dt.dt.month == m).values
        ym = dt.dt.year * 100 + dt.dt.month
        rk = ym.groupby(ym).cumcount().values
        rk_rev = ym[::-1].groupby(ym[::-1]).cumcount()[::-1].values
        F["tom_last2"] = rk_rev < 2
        F["tom_first3"] = rk < 3
        F["dom_01_10"] = (dt.dt.day <= 10).values
        nxt_wd = [s + timedelta(days=1) for s in sess]
        F["pre_hol"] = np.array([nd in hol for nd in nxt_wd])
        prv_wd = [s - timedelta(days=1) for s in sess]
        F["post_hol"] = np.array([pd_ in hol for pd_ in prv_wd])
        # expiries
        exp = np.zeros(n, bool)
        for i, s in enumerate(sess):
            if s >= era0:
                exp[i] = era.get(s, False)
        mexp = set()
        for y in range(sess[0].year, sess[-1].year + 1):
            for m in range(1, 13):
                lt = on_or_before(last_thursday(y, m), sess, sset)
                if lt is not None and lt < era0:
                    mexp.add(lt)
        weekly0 = {"NIFTY": date(2019, 2, 11), "BANKNIFTY": date(2016, 5, 27)}.get(und)
        for i, s in enumerate(sess):
            if s >= era0:
                continue
            if s in mexp:
                exp[i] = True
            elif weekly0 and s >= weekly0:
                # weekly Thursday (holiday -> previous session)
                th = s + timedelta(days=(3 - s.weekday()) % 7)
                if on_or_before(th, sess, sset) == s:
                    exp[i] = True
        # option-era monthly expiry = last expiry session of the calendar month
        ymv = ym.values
        for key in np.unique(ymv[np.array(sess) >= era0]) if era else []:
            idx = np.nonzero((ymv == key) & exp)[0]
            if len(idx):
                mexp.add(sess[idx[-1]])
        is_m = np.array([s in mexp for s in sess])
        F["exp_day"] = exp
        F["post_exp"] = np.r_[False, exp[:-1]]
        F["post_mexp"] = np.r_[False, is_m[:-1]]
        roll = np.zeros(n, bool)
        wk = np.zeros(n, bool)
        for j in np.nonzero(is_m)[0]:
            roll[max(j - 3, 0):j] = True
            iso = sess[j].isocalendar()[:2]
            k = j
            while k >= 0 and sess[k].isocalendar()[:2] == iso:
                wk[k] = True
                k -= 1
        F["exp_week_m"] = wk
        F["roll3"] = roll
        # rebalancing
        last_sess = {}
        for i, s in enumerate(sess):
            last_sess[(s.year, s.month)] = s
        F["msci"] = np.array([s.month in (2, 5, 8, 11) and last_sess[(s.year, s.month)] == s for s in sess])
        F["nse_rebal"] = np.array([s.month in (3, 9) and last_sess[(s.year, s.month)] == s for s in sess])
        ft = set()
        for y in range(sess[0].year, sess[-1].year + 1):
            for m in (3, 6, 9, 12):
                d = date(y, m, 1)
                d += timedelta(days=(4 - d.weekday()) % 7 + 14)
                x = on_or_before(d, sess, sset)
                if x:
                    ft.add(x)
        F["ftse"] = np.array([s in ft for s in sess])
        # macro
        F["rbi"] = np.array([s in set(rbi) for s in sess])
        F["pre_rbi"] = np.r_[F["rbi"][1:], False]
        F["budget"] = np.array([s in set(budget) for s in sess])
        el = set()
        for d in elect:
            x = d if d in sset else next_after(d, sarr)
            if x:
                el.add(x)
        F["election"] = np.array([s in el for s in sess])

        def nxt(dates, first=None):
            st = set()
            for d in dates:
                if first and d < first:
                    continue
                x = next_after(d, sarr)
                if x:
                    st.add(x)
            return np.array([s in st for s in sess])
        F["fomc_next"] = nxt(fomc)
        F["uscpi_next"] = nxt(uscpi)
        incpi = []
        for y in range(2012, 2027):
            for m in range(1, 13):
                d = date(y, m, 12)
                while d.weekday() >= 5 or d in hol:
                    d += timedelta(days=1)
                incpi.append(d)
        F["incpi_next"] = nxt(incpi)
        gdp = []
        for y in range(2006, 2027):
            for m in (2, 5, 8, 11):
                d = date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)
                while d.weekday() >= 5:
                    d -= timedelta(days=1)
                gdp.append(d)
        F["gdp_next"] = nxt(gdp)
        F["res_next"] = nxt([d for v in res.values() for d in v])
        F["res_bank_next"] = nxt(res["HDFCBANK"])
        F["res_it_next"] = nxt(res["INFY"] + res["TCS"])
        dw = np.zeros(n, bool)
        for x in DIWALI:
            d = date.fromisoformat(x)
            j = int(np.searchsorted(sarr, np.datetime64(d)))
            dw[max(j - 3, 0):min(j + 3, n)] = True
        F["diwali_wk"] = dw
        out = df.copy()
        out.insert(0, "und", und)
        for k, v in F.items():
            out[k] = np.asarray(v, bool)
        out["opt_era"] = out.day >= era0
        rows.append(out)
    fl = pd.concat(rows, ignore_index=True)
    fl.to_parquet(os.path.join(OUT, "flags.parquet"))
    fcols = [c for c in fl.columns if c not in ("und", "day", "open", "high", "low", "close", "opt_era")]
    print(len(fcols), "flags")
    pre = fl[fl.day < HOLD]
    cnt = pre.groupby("und")[fcols].sum().T
    cnt_opt = pre[pre.opt_era].groupby("und")[fcols].sum().T
    print(pd.concat([cnt, cnt_opt.add_suffix("_opt")], axis=1).to_string())
    print("events:", evdf.groupby("kind").date.agg(["count", "min", "max"]).to_string())
    print("rbi 2026 from page:", rbi26)
    return fl


FLAGS = None

if __name__ == "__main__":
    build()
