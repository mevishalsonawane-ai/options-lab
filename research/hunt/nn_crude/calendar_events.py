"""NN-CRUDE event calendar with IST timestamps.
EIA Weekly Petroleum Status Report (WPSR): Wednesday 10:30 ET; official holiday exceptions 2025-26 from
eia.gov/petroleum/supply/weekly/schedule.php (fetched 2026-10-08); before 2025 the rule 'US federal holiday on
Mon-Wed -> Thursday 11:00 ET' (EIA's usual pattern; approximate). US CPI 08:30 ET and FOMC 14:00 ET dates from
h28 (alfred / federalreserve.gov). OPEC/OPEC+ ministerial meeting dates are typed from memory (APPROXIMATE,
descriptive use only, never a model input)."""
import datetime as dt, sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd
from zoneinfo import ZoneInfo
from pandas.tseries.holiday import USFederalHolidayCalendar

ET, IST = ZoneInfo("America/New_York"), ZoneInfo("Asia/Kolkata")
OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/nn_crude/data"
H28 = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h28/events.csv"
EIA_EXC = {  # normal Wednesday of that week -> (alternate date, ET time)   [official 2025-26]
    "2025-01-01": ("2025-01-02", "11:00"), "2025-01-22": ("2025-01-23", "12:00"), "2025-02-19": ("2025-02-20", "12:00"),
    "2025-05-28": ("2025-05-29", "12:00"), "2025-09-03": ("2025-09-04", "12:00"), "2025-10-15": ("2025-10-16", "12:00"),
    "2025-11-12": ("2025-11-13", "12:00"), "2025-12-24": ("2025-12-29", "17:00"), "2026-01-21": ("2026-01-22", "12:00"),
    "2026-02-18": ("2026-02-19", "12:00"), "2026-05-27": ("2026-05-28", "12:00"), "2026-09-09": ("2026-09-10", "12:00"),
    "2026-10-14": ("2026-10-15", "12:00"), "2026-11-11": ("2026-11-12", "12:00")}
OPEC = """2014-11-27 2016-09-28 2016-11-30 2016-12-10 2017-05-25 2017-11-30 2018-06-22 2018-12-07 2019-07-02 2019-12-06
2020-03-06 2020-04-12 2020-06-06 2020-12-03 2021-01-05 2021-03-04 2021-04-01 2021-07-18 2021-09-01 2021-10-04 2021-11-04
2021-12-02 2022-01-04 2022-02-02 2022-03-02 2022-03-31 2022-05-05 2022-06-02 2022-06-30 2022-08-03 2022-09-05 2022-10-05
2022-12-04 2023-04-02 2023-06-04 2023-11-30 2024-06-02 2024-12-05 2025-04-03 2025-05-31 2025-07-05 2025-08-03 2025-09-07
2025-10-05 2025-11-02 2025-11-30""".split()


def ist(d, hm, tz=ET):
    h, m = map(int, hm.split(":"))
    return dt.datetime(d.year, d.month, d.day, h, m, tzinfo=tz).astimezone(IST).replace(tzinfo=None)


def build():
    rows = []
    hol = set(USFederalHolidayCalendar().holidays("2009-12-01", "2027-12-31").date)
    for wed in pd.date_range("2010-01-06", "2026-12-30", freq="W-WED").date:
        k = wed.isoformat()
        if k in EIA_EXC:
            d, t = EIA_EXC[k]; d = dt.date.fromisoformat(d); src = "eia-official"
        elif wed.year < 2025 and any((wed - dt.timedelta(days=i)) in hol for i in range(0, 3)):
            d, t, src = wed + dt.timedelta(days=1), "11:00", "rule-holiday"
        else:
            d, t, src = wed, "10:30", "rule"
        rows.append(dict(kind="eia", ts_ist=ist(d, t), src=src))
    h = pd.read_csv(H28)
    for _, r in h.iterrows():
        d = dt.date.fromisoformat(r["date"])
        if r["kind"] == "uscpi": rows.append(dict(kind="uscpi", ts_ist=ist(d, "08:30"), src=r["source"]))
        if r["kind"] == "fomc": rows.append(dict(kind="fomc", ts_ist=ist(d, "14:00"), src=r["source"]))
    for s in OPEC:
        rows.append(dict(kind="opec_approx", ts_ist=dt.datetime.fromisoformat(s), src="memory-approx (date only)"))
    ev = pd.DataFrame(rows).sort_values("ts_ist").reset_index(drop=True)
    ev.to_parquet(f"{OUT}/events.parquet", compression="zstd")
    return ev


if __name__ == "__main__":
    ev = build()
    print(ev.groupby("kind").ts_ist.agg(["count", "min", "max"]))
    e = ev[ev.kind == "eia"]
    print(e.ts_ist.dt.strftime("%H:%M").value_counts().head(8))
