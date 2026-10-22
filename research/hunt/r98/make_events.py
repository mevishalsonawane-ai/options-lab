"""R98d calendar (PREREG_D.md section 1): writes research/hunt/r98/events.csv.

    python3 -I research/hunt/r98/make_events.py

Each row: category, the event's own date and IST time, the Indian session it hits (the same day for RBI / Budget /
election; the next session for the overnight US Fed and CPI releases, which come after the Indian close), and a source.
Sessions come from the lake's NIFTY daily index (index_daily.parquet).
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402

HERE = Path(__file__).resolve().parent

RBI_SRC = {
    "FY2021-22": "https://rbi.org.in/Scripts/BS_PressReleaseDisplay.aspx?prid=51350",
    "FY2022-23": "https://rbi.org.in/Scripts/BS_PressReleaseDisplay.aspx?prid=53486",
    "FY2023-24": "https://rbi.org.in/scripts/BS_PressReleaseDisplay.aspx?prid=55410",
    "FY2024-25": "https://website.rbi.org.in/web/rbi/-/press-releases/meeting-schedule-of-the-monetary-policy-committee-for-2024-2025",
    "FY2025-26": "https://rbi.org.in/Scripts/BS_PressReleaseDisplay.aspx?prid=60062",
    "FY2026-27": "https://www.rbi.org.in/scripts/annualpolicy.aspx",
}
# MPC decision = the last day of each scheduled meeting; changes from the schedules noted with their sources.
RBI = [
    ("2021-08-06", "FY2021-22"), ("2021-10-08", "FY2021-22"), ("2021-12-08", "FY2021-22"),
    ("2022-02-10", "rescheduled from Feb 7-9 (Maharashtra holiday): https://rbi.org.in/Scripts/BS_PressReleaseDisplay.aspx?prid=53228"),
    ("2022-04-08", "FY2022-23"), ("2022-06-08", "FY2022-23"),
    ("2022-08-05", "rescheduled from Aug 2-4 to Aug 3-5 (RBI, 21 Jul 2022; resolution 'today (August 5, 2022)')"),
    ("2022-09-30", "FY2022-23"), ("2022-12-07", "FY2022-23"), ("2023-02-08", "FY2022-23"),
    ("2023-04-06", "FY2023-24"), ("2023-06-08", "FY2023-24"), ("2023-08-10", "FY2023-24"), ("2023-10-06", "FY2023-24"),
    ("2023-12-08", "FY2023-24"), ("2024-02-08", "FY2023-24"),
    ("2024-04-05", "FY2024-25"), ("2024-06-07", "FY2024-25"), ("2024-08-08", "FY2024-25"), ("2024-10-09", "FY2024-25"),
    ("2024-12-06", "FY2024-25"), ("2025-02-07", "FY2024-25"),
    ("2025-04-09", "FY2025-26"), ("2025-06-06", "FY2025-26"),
    ("2025-08-06", "held Aug 4-6 (resolution of 6 Aug 2025, RBI Bulletin Aug 2025: https://www.rbi.org.in/Scripts/BS_ViewBulletin.aspx?Id=23576)"),
    ("2025-10-01", "FY2025-26"), ("2025-12-05", "FY2025-26"), ("2026-02-06", "FY2025-26"),
    ("2026-04-08", "FY2026-27"), ("2026-06-05", "FY2026-27"), ("2026-08-05", "FY2026-27"), ("2026-10-07", "FY2026-27"),
]
# Excluded by the PREREG definition (a scheduled ~10:00 decision): the off-cycle 4 May 2022 hike, announced mid-afternoon.

BUDGET = ["2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01"]
BUDGET_SRC = ("https://www.indiabudget.gov.in/ (Union Budget speeches); 2025-02-01 Saturday and 2026-02-01 Sunday special "
              "sessions: https://www.business-standard.com/budget/news/budget-2026-bse-nse-trading-session-sunday-february-1-time-126011601035_1.html")
ELECTION = [("2024-06-03", "first session after the 1 Jun 2024 exit polls"), ("2024-06-04", "Lok Sabha 2024 result day")]
ELECTION_SRC = "https://results.eci.gov.in/ (counting on 4 June 2024)"

FOMC_SRC = "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"
FOMC = [  # decision day (the second day of each scheduled meeting); the 22 Aug 2025 notation vote is not scheduled
    "2021-07-28", "2021-09-22", "2021-11-03", "2021-12-15",
    "2022-01-26", "2022-03-16", "2022-05-04", "2022-06-15", "2022-07-27", "2022-09-21", "2022-11-02", "2022-12-14",
    "2023-02-01", "2023-03-22", "2023-05-03", "2023-06-14", "2023-07-26", "2023-09-20", "2023-11-01", "2023-12-13",
    "2024-01-31", "2024-03-20", "2024-05-01", "2024-06-12", "2024-07-31", "2024-09-18", "2024-11-07", "2024-12-18",
    "2025-01-29", "2025-03-19", "2025-05-07", "2025-06-18", "2025-07-30", "2025-09-17", "2025-10-29", "2025-12-10",
    "2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16",
]
CPI_SRC = "https://www.bls.gov/bls/news-release/cpi.htm"
USCPI = [
    "2021-08-11", "2021-09-14", "2021-10-13", "2021-11-10", "2021-12-10", "2022-01-12", "2022-02-10", "2022-03-10",
    "2022-04-12", "2022-05-11", "2022-06-10", "2022-07-13", "2022-08-10", "2022-09-13", "2022-10-13", "2022-11-10",
    "2022-12-13", "2023-01-12", "2023-02-14", "2023-03-14", "2023-04-12", "2023-05-10", "2023-06-13", "2023-07-12",
    "2023-08-10", "2023-09-13", "2023-10-12", "2023-11-14", "2023-12-12", "2024-01-11", "2024-02-13", "2024-03-12",
    "2024-04-10", "2024-05-15", "2024-06-12", "2024-07-11", "2024-08-14", "2024-09-11", "2024-10-10", "2024-11-13",
    "2024-12-11", "2025-01-15", "2025-02-12", "2025-03-12", "2025-04-10", "2025-05-13", "2025-06-11", "2025-07-15",
    "2025-08-12", "2025-09-11", "2025-10-24", "2025-12-18", "2026-01-13", "2026-02-13", "2026-03-11", "2026-04-10",
    "2026-05-12", "2026-06-10", "2026-07-14", "2026-08-12", "2026-09-11", "2026-10-14",
]


def sessions() -> list[date]:
    d = pd.read_parquet(R.LAKE / "index_daily.parquet")
    return sorted(pd.to_datetime(d[d.symbol == "NIFTY"].date).dt.date.unique())


def main():
    ss = sessions()
    sset = set(ss)

    def same(dd):
        return dd if dd in sset else None

    def nxt(dd):
        later = [s for s in ss if s > dd]
        return later[0] if later else None

    rows = []
    for d, src in RBI:
        dd = date.fromisoformat(d)
        rows.append(("RBI", d, "10:00", same(dd), RBI_SRC.get(src, src)))
    for d in BUDGET:
        rows.append(("BUDGET", d, "11:00", same(date.fromisoformat(d)), BUDGET_SRC))
    for d, why in ELECTION:
        rows.append(("ELECTION", d, why, same(date.fromisoformat(d)), ELECTION_SRC))
    for d in FOMC:
        rows.append(("FOMC", d, "14:00 ET (23:30 / 00:30 IST)", nxt(date.fromisoformat(d)), FOMC_SRC))
    for d in USCPI:
        rows.append(("USCPI", d, "08:30 ET (18:00 / 19:00 IST)", nxt(date.fromisoformat(d)), CPI_SRC))
    df = pd.DataFrame(rows, columns=["category", "event_date", "event_time", "session", "source"])
    df = df[df.session.notna() & (pd.to_datetime(df.session) >= "2021-08-01") & (pd.to_datetime(df.session) <= ss[-1].isoformat())]
    df.to_csv(HERE / "events.csv", index=False)
    print(df.groupby("category").size().to_dict(), len(df))
    miss = [r for r in rows if r[3] is None and r[0] in ("RBI", "BUDGET", "ELECTION")]
    print("same-day events without a session:", miss)


if __name__ == "__main__":
    main()
