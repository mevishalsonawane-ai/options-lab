"""h42 question engine, step 1: write every question BEFORE any answer is computed.
Each question is generated from a template and carries a machine spec (table, universe, condition, outcome, test,
and the option-buyer translation: entry minute, side rule, horizon). Output: research/hunt/h42/questions.csv
python3 -I research/hunt/h42/questions.py
"""
from __future__ import annotations

import csv
import os

HERE = os.path.dirname(os.path.abspath(__file__))
U5 = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
SH = {"NIFTY": "NIFTY", "BANKNIFTY": "BANKNIFTY", "FINNIFTY": "FINNIFTY", "MIDCPNIFTY": "MIDCPNIFTY", "SENSEX": "SENSEX"}
HB = {0: "09:20-10:00", 1: "10:00-11:00", 2: "11:00-12:00", 3: "12:00-13:00", 4: "13:00-14:00", 5: "14:00-14:55"}
DOW = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]
CHK = {60: "10:15", 120: "11:15", 165: "12:00", 195: "12:30", 255: "13:30", 285: "14:00", 315: "14:30"}
SPLIT = {"day": "pre-holdout minute era (index start..2025-09-30), one row per day",
         "min": "pre-holdout minute era (..2025-09-30), 5-min grid 09:20-14:55, day-clustered",
         "dly": "pre-holdout daily candles 2006 (MIDCP 2022)..2025-09-30; option check 2020+",
         "con": "constituent era 2024-10-07..2025-09-30 (5-min grid, day-clustered)"}
Q = []


def add(cat, text, table, und, cond, outcome, kind="bin", universe="", ref="comp", entry="", side="none", dircol="",
        horizon="sq", split=None):
    Q.append(dict(category=cat, question=text, table=table, und=und, universe=universe, cond=cond, outcome=outcome,
                  kind=kind, ref=ref, entry=entry, side=side, dircol=dircol, horizon=horizon,
                  split=split or SPLIT["min" if table == "min" else table]))


def tm(col):
    h, m = divmod(555 + col, 60)
    return f"{h:02d}:{m:02d}"


# 1 OPEN: opening move -> rest of day
for u in U5:
    for k in (15, 30):
        for thr in (0.2, 0.4):
            for d, w, op in ((1, "rises", ">"), (-1, "falls", "<")):
                c = f"r{k} {op} {d * thr}"
                add("OPEN", f"If {u} {w} more than {thr}% in the first {k} minutes, how often does it keep going the same way from {tm(k - 1)} to 15:09 (vs other days)?",
                    "day", u, c, f"(rest{k} * {d} > 0)", entry=str(k - 1), side="auto", dircol=f"rest{k}")
    for d, w, op in ((1, "rises", ">"), (-1, "falls", "<")):
        add("OPEN", f"If {u} {w} more than 0.4% in the first 15 minutes, how often does it close (15:29) above the open?",
            "day", u, f"r15 {op} {d * 0.4}", "up", entry="14", side="auto", dircol="rest15")
# 2 GAP
GB = {"gap > 0.5": "gaps up more than 0.5%", "gap > 0.2 and gap <= 0.5": "gaps up 0.2-0.5%",
      "gap < -0.2 and gap >= -0.5": "gaps down 0.2-0.5%", "gap < -0.5": "gaps down more than 0.5%"}
for u in U5:
    for c, w in GB.items():
        add("GAP", f"When {u} {w}, how often is the gap filled (previous close touched) the same day?", "day", u, c, "gapfill",
            universe="gap == gap", entry="0", side="col:gapfill_dir", horizon="sq")
        add("GAP", f"When {u} {w}, how often is the first 15 minutes up?", "day", u, c, "(r15 > 0)", entry="0",
            side="auto", dircol="r15", horizon="15")
        add("GAP", f"When {u} {w}, how often is 09:30-15:09 up (the rest of the day after the first 15 min)?", "day", u, c,
            "(rest15 > 0)", entry="14", side="auto", dircol="rest15")
    for c, w in (("gap > 0.5", "gap-up (>0.5%)"), ("gap < -0.5", "gap-down (<-0.5%)")):
        add("GAP", f"On {u} {w} days, how often is the day's HIGH set in the first 30 minutes?", "day", u, c, "hi30")
        add("GAP", f"On {u} {w} days, how often is the day's LOW set in the first 30 minutes?", "day", u, c, "lo30")
# 3 TIMING
for u in U5:
    for m in (60, 120, 195, 285):
        add("TIMING", f"If at {tm(m)} {u}'s day high so far was made in the first 15 minutes, how often does it stay the day's high to the close?",
            "day", u, f"hiearly{m} == 1", f"holdhi{m}", entry=str(m), side="auto", dircol=f"rest_{m}")
        add("TIMING", f"If at {tm(m)} {u}'s day low so far was made in the first 15 minutes, how often does it stay the day's low to the close?",
            "day", u, f"loearly{m} == 1", f"holdlo{m}", entry=str(m), side="auto", dircol=f"rest_{m}")
    for j, dn in enumerate(DOW):
        add("TIMING", f"On {dn}s, how often is {u}'s day high set in the first 30 minutes (vs other weekdays)?", "day", u,
            f"dow == {j}", "hi30")
# 4 TOD
for u in U5:
    for j, w in HB.items():
        add("TOD", f"Are {u}'s 15-minute moves bigger when they start at {w} than at other times?", "min", u, f"hb == {j}",
            "abs(f15)", kind="cont", entry="s", side="both", horizon="15")
        add("TOD", f"Is {u} more often up over the next 15 minutes when starting at {w}?", "min", u, f"hb == {j}", "(f15 > 0)",
            entry="s", side="auto", dircol="f15", horizon="15")
        add("TOD", f"On EXPIRY days, are {u}'s 60-minute moves bigger starting at {w} than at other hours of expiry days?", "min",
            u, f"hb == {j}", "abs(f60)", kind="cont", universe="exp == 1", entry="s", side="both", horizon="60")
# 5 DOW
for u in U5:
    for j, dn in enumerate(DOW):
        add("DOW", f"Is {u} more often up (close > open) on {dn}s?", "day", u, f"dow == {j}", "up", entry="0", side="auto",
            dircol="rest0")
        add("DOW", f"Is {u}'s day range bigger on {dn}s?", "day", u, f"dow == {j}", "rng", kind="cont", entry="0", side="both")
for u in ("NIFTY", "BANKNIFTY"):
    for j, dn in enumerate(DOW):
        add("DOW", f"Since 2006, is {u} more often up (close > open) on {dn}s?", "dly", u, f"dow == {j}", "up", entry="0",
            side="auto", dircol="oc")
# 6 EXPIRY proximity
DT = {"dte == 0": "on expiry day", "dte == 1": "1 day before expiry", "dte == 2": "2 days before expiry",
      "dte >= 3 and dte <= 4": "3-4 days before expiry", "dte >= 5": "5+ days before expiry"}
for u in U5:
    for c, w in DT.items():
        add("EXPIRY", f"Is {u}'s day range bigger {w}?", "day", u, c, "rng", kind="cont", entry="0", side="both")
        add("EXPIRY", f"Does {u}'s ATM straddle lose more of its value 09:20->15:09 {w}?", "day", u, c, "straddecay",
            kind="cont", entry="5", side="both")
# 7 PRIORDAY (daily 2006+)
PD = {"pret > 1": "the previous day rose more than 1%", "pret < -1": "the previous day fell more than 1%",
      "nr7 == 1": "the previous day was the narrowest of 7 (NR7)", "inside == 1": "the previous day was an inside day",
      "prng_rel > 1.5": "the previous day's range was 1.5x its 20-day average", "pstreak >= 3": "3+ up days in a row",
      "pstreak <= -3": "3+ down days in a row"}
for u in U5:
    for c, w in PD.items():
        add("PRIORDAY", f"After {w}, is {u} more often up (close > open) today?", "dly", u, c, "up", entry="0", side="auto",
            dircol="oc")
        add("PRIORDAY", f"After {w}, is {u}'s day range bigger today?", "dly", u, c, "rng", kind="cont", entry="0", side="both")
# 8 FIRSTHOUR
for u in U5:
    for c, w in (("rg60_rel < 0.6", "narrow (<0.6x its 20-day average)"), ("rg60_rel > 1.5", "wide (>1.5x average)")):
        add("FIRSTHOUR", f"When {u}'s first-hour range is {w}, is the move 10:15->15:09 bigger?", "day", u, c, "abs(rest_60)",
            kind="cont", entry="60", side="both")
        add("FIRSTHOUR", f"When {u}'s first-hour range is {w}, how often is it a trend day (close-open >= 70% of range)?", "day",
            u, c, "trend")
    for nm, w, sd in (("bu", "breaks above its first-hour high (first close after 10:15)", "C"),
                      ("bd", "breaks below its first-hour low (first close after 10:15)", "P")):
        add("FIRSTHOUR", f"When {u} {w}, how often does it close (15:09) beyond that level?", "day", u, f"{nm}min >= 0",
            f"{nm}_close", ref="const:0.5", entry=f"{nm}min", side=sd)
        add("FIRSTHOUR", f"When {u} {w}, how far does it run in the next 30 minutes (in the break direction, %)?", "day", u,
            f"{nm}min >= 0", f"{nm}_f30", kind="cont", ref="const:0", entry=f"{nm}min", side=sd, horizon="30")
        add("FIRSTHOUR", f"When {u} {w} BEFORE 11:15, does it follow through to 15:09 more than a later break?", "day", u,
            f"{nm}min >= 0 and {nm}min < 120", f"{nm}_rest", kind="cont", universe=f"{nm}min >= 0", entry=f"{nm}min", side=sd)
# 9 TREND
for u in U5:
    for m in (120, 195):
        for thr in (0.5, 1.0):
            add("TREND", f"If at {tm(m)} {u} is up more than {thr}% and near its high (top 20% of the range), how often does it rise further to 15:09?",
                "day", u, f"id{m} > {thr} and pos{m} > 0.8", f"(rest_{m} > 0)", entry=str(m), side="auto", dircol=f"rest_{m}")
            add("TREND", f"If at {tm(m)} {u} is down more than {thr}% and near its low (bottom 20%), how often does it fall further to 15:09?",
                "day", u, f"id{m} < -{thr} and pos{m} < 0.2", f"(rest_{m} < 0)", entry=str(m), side="auto", dircol=f"rest_{m}")
# 10 STREAK
WIN = {"s < 105": "09:20-11:00", "s >= 105 and s < 225": "11:00-13:00", "s >= 225": "13:00-14:55"}
for u in U5:
    for uni, w in WIN.items():
        for c, t in (("st5 >= 3", "3+ green"), ("st5 <= -3", "3+ red"), ("st5 >= 5", "5+ green"), ("st5 <= -5", "5+ red")):
            add("STREAK", f"After {t} 5-minute candles in a row at {w}, is {u} up over the next 15 minutes more often?", "min", u,
                c, "(f15 > 0)", universe=uni, entry="s", side="auto", dircol="f15", horizon="15")
    for c, t in (("st15 >= 3", "3+ green"), ("st15 <= -3", "3+ red")):
        add("STREAK", f"After {t} 15-minute candles in a row, is {u} up over the next 30 minutes more often?", "min", u, c,
            "(f30 > 0)", entry="s", side="auto", dircol="f30", horizon="30")
# 11 MOMREV
for u in U5:
    for k in (5, 15, 30, 60):
        for h in (15, 60):
            for c, w in ((f"z{k} > 1.5", "a big rise (>1.5 sd)"), (f"z{k} < -1.5", "a big fall (<-1.5 sd)")):
                add("MOMREV", f"After {w} of {u} over the last {k} minutes, is the next {h} minutes up more often (momentum vs reversal)?",
                    "min", u, c, f"(f{h} > 0)", entry="s", side="auto", dircol=f"f{h}", horizon=str(h))
# 12 VIX
VB = {"vixp < 13": "below 13", "vixp >= 13 and vixp < 16": "13-16", "vixp >= 16 and vixp < 20": "16-20", "vixp >= 20": "20 or more"}
for u in U5:
    for c, w in VB.items():
        add("VIX", f"When India VIX closed {w} yesterday, is {u}'s day range bigger?", "day", u, c, "rng", kind="cont", entry="0",
            side="both")
        add("VIX", f"When India VIX closed {w} yesterday, is {u} more often up (close > open)?", "day", u, c, "up", entry="0",
            side="auto", dircol="rest0")
    for c, w in (("vixchp > 5", "rose more than 5%"), ("vixchp < -5", "fell more than 5%")):
        add("VIX", f"When India VIX {w} yesterday, is {u} more often up today?", "day", u, c, "up", entry="0", side="auto",
            dircol="rest0")
    for c, w in (("vix1015 > 3", "is up more than 3% at 10:15"), ("vix1015 < -3", "is down more than 3% at 10:15")):
        add("VIX", f"When India VIX {w}, is {u} more often up 10:15->15:09?", "day", u, c, "(rest_60 > 0)", universe="vix1015 == vix1015",
            entry="60", side="auto", dircol="rest_60")
    for c, w in (("vixday > 5", "is up more than 5% intraday"), ("vixday < -3", "is down more than 3% intraday")):
        add("VIX", f"When India VIX {w}, does {u} fall more over the next 60 minutes (mean move, bps)?", "min", u, c, "f60",
            kind="cont", universe="vixday == vixday", entry="s", side="auto", dircol="f60", horizon="60")
    for c, w in (("z_vix15 > 1.5", "jumps (>1.5 sd in 15 min)"), ("z_vix15 < -1.5", "drops (<-1.5 sd in 15 min)")):
        add("VIX", f"When India VIX {w}, is {u} up over the next 15 minutes more often?", "min", u, c, "(f15 > 0)",
            universe="z_vix15 == z_vix15", entry="s", side="auto", dircol="f15", horizon="15")
# 13 PIN
for u in U5:
    for m in (165, 255, 315):
        add("PIN", f"On expiry days, does {u} move toward the max-OI strike from {tm(m)} to 15:09 more often than on other days?",
            "day", u, "exp == 1", f"pin{m}", universe=f"pinside{m} == pinside{m}", entry=str(m), side=f"col:pinside{m}")
        add("PIN", f"On expiry days, how far does {u} move toward the max-OI strike from {tm(m)} to 15:09 (%, vs other days)?",
            "day", u, "exp == 1", f"pinmv{m}", kind="cont", universe=f"pinside{m} == pinside{m}", entry=str(m), side=f"col:pinside{m}")
# 14 OPTPREM
for u in U5:
    for c, w in (("abs(p15) < 5 and cA15 < -5", "the ATM CE premium fell 5%+ in 15 min while spot was flat (<0.05%)"),
                 ("abs(p15) < 5 and pA15 < -5", "the ATM PE premium fell 5%+ in 15 min while spot was flat"),
                 ("abs(p15) < 5 and cA15 > 5", "the ATM CE premium rose 5%+ in 15 min while spot was flat"),
                 ("abs(p15) < 5 and pA15 > 5", "the ATM PE premium rose 5%+ in 15 min while spot was flat")):
        for h in (15, 30):
            add("OPTPREM", f"If {w}, is {u} up over the next {h} minutes more often?", "min", u, c, f"(f{h} > 0)", entry="s",
                side="auto", dircol=f"f{h}", horizon=str(h))
    for c, w in (("z_strad15 > 1.5", "the ATM straddle jumped (>1.5 sd in 15 min)"), ("z_strad15 < -1.5", "the ATM straddle dropped (<-1.5 sd)")):
        add("OPTPREM", f"If {w}, is {u}'s next 30-minute move bigger?", "min", u, c, "abs(f30)", kind="cont", entry="s",
            side="both", horizon="30")
    add("OPTPREM", f"If {u}'s ATM straddle fell 3%+ in 15 min with flat spot, is the next-60-min range bigger?", "min", u,
        "strad15 < -3 and abs(p15) < 5", "frng60", kind="cont", entry="s", side="both", horizon="60")
    for c, w in (("z_pcrat15 > 1.5", "the PE got more expensive relative to the CE (>1.5 sd in 15 min)"),
                 ("z_pcrat15 < -1.5", "the CE got more expensive relative to the PE (>1.5 sd)")):
        for h in (15, 30):
            add("OPTPREM", f"If {w}, is {u} up over the next {h} minutes more often?", "min", u, c, f"(f{h} > 0)", entry="s",
                side="auto", dircol=f"f{h}", horizon=str(h))
for u in ("BANKNIFTY", "SENSEX"):
    for c, w, sd in (("abs(p15) < 10 and cApt15 <= -20", "the ATM CE premium fell 20+ points in 15 min while spot was flat (<0.1%)", "f30"),
                     ("abs(p15) < 10 and pApt15 <= -20", "the ATM PE premium fell 20+ points in 15 min while spot was flat", "f30")):
        add("OPTPREM", f"If {w}, is {u} up over the next 30 minutes more often?", "min", u, c, "(f30 > 0)", entry="s",
            side="auto", dircol=sd, horizon="30")
# 15 THETA
for u in U5:
    for j, w in HB.items():
        add("THETA", f"How much does {u}'s ATM straddle change over the next 60 minutes starting at {w} (%, vs other hours)?",
            "min", u, f"hb == {j}", "fstrad60", kind="cont", entry="s", side="both", horizon="60")
    for c, w in DT.items():
        add("THETA", f"How much does {u}'s ATM straddle change over the next 60 minutes {w} (%, vs other days)?", "min", u, c,
            "fstrad60", kind="cont", entry="s", side="both", horizon="60")
# 16 JUMPS (option outcomes: +20 before -15 within 15 min = +1, -15 first = -1)
TB = {"s <= 40": "09:20-09:55", "s >= 45 and s < 225": "10:00-12:55", "s >= 225": "13:00-14:55"}
for u in U5:
    for c, w in TB.items():
        add("JUMPS", f"Is a 1-ITM {u} CE bought at {w} more likely to jump +20 before -15 (net of drops) than at other times?",
            "min", u, c, "(jC - dC)", kind="cont", universe="okC == 1", entry="s", side="C", horizon="15")
        add("JUMPS", f"Is a 1-ITM {u} PE bought at {w} more likely to jump +20 before -15 (net of drops) than at other times?",
            "min", u, c, "(jP - dP)", kind="cont", universe="okP == 1", entry="s", side="P", horizon="15")
    add("JUMPS", f"What share of {u} CE +20-point jumps start in the first 30 minutes (09:20-09:45), vs the rest of the day's rate?",
        "min", u, "s <= 30", "jC", universe="okC == 1", entry="s", side="C", horizon="15")
    add("JUMPS", f"What share of {u} PE +20-point jumps start in the first 30 minutes (09:20-09:45), vs the rest of the day's rate?",
        "min", u, "s <= 30", "jP", universe="okP == 1", entry="s", side="P", horizon="15")
    add("JUMPS", f"On expiry days, is a 1-ITM {u} CE more likely to jump +20 before -15 (net of drops)?", "min", u, "exp == 1",
        "(jC - dC)", kind="cont", universe="okC == 1", entry="s", side="C", horizon="15")
    add("JUMPS", f"On expiry days, is a 1-ITM {u} PE more likely to jump +20 before -15 (net of drops)?", "min", u, "exp == 1",
        "(jP - dP)", kind="cont", universe="okP == 1", entry="s", side="P", horizon="15")
    add("JUMPS", f"When VIX is 18+, is a 1-ITM {u} CE more likely to jump +20 before -15 (net)?", "min", u, "vixp >= 18",
        "(jC - dC)", kind="cont", universe="okC == 1", entry="s", side="C", horizon="15")
    add("JUMPS", f"When VIX is 18+, is a 1-ITM {u} PE more likely to jump +20 before -15 (net)?", "min", u, "vixp >= 18",
        "(jP - dP)", kind="cont", universe="okP == 1", entry="s", side="P", horizon="15")
    add("JUMPS", f"After the 1-ITM {u} CE already rose 20+ points off its 15-min low, does it jump +20 again before -15 (net)?",
        "min", u, "ricC15 >= 20", "(jC - dC)", kind="cont", universe="okC == 1", entry="s", side="C", horizon="15")
    add("JUMPS", f"After the 1-ITM {u} PE already rose 20+ points off its 15-min low, does it jump +20 again before -15 (net)?",
        "min", u, "ricP15 >= 20", "(jP - dP)", kind="cont", universe="okP == 1", entry="s", side="P", horizon="15")
# 17 ROUND numbers
RN = {"NIFTY": 100, "BANKNIFTY": 500, "FINNIFTY": 200, "MIDCPNIFTY": 100, "SENSEX": 500}
for u in U5:
    R = RN[u]
    for c, w in (("xrndu == 1", f"crosses ABOVE a round {R} level"), ("xrndd == 1", f"crosses BELOW a round {R} level"),
                 ("nrndb == 1", f"rises to within 0.1% below a round {R} level"), ("nrnda == 1", f"falls to within 0.1% above a round {R} level")):
        for h in (15, 30):
            add("ROUND", f"When {u} {w} (last 5 min), is it up over the next {h} minutes more often?", "min", u, c, f"(f{h} > 0)",
                entry="s", side="auto", dircol=f"f{h}", horizon=str(h))
# 18 LEVELS
for u in U5:
    for c, w in (("xpdh == 1", "crosses above the previous day's high"), ("xpdl == 1", "crosses below the previous day's low"),
                 ("xopu == 1", "crosses back above the day's open"), ("xopd == 1", "crosses back below the day's open"),
                 ("nh5 == 1 and s >= 165", "makes a new day high after 12:00"), ("nl5 == 1 and s >= 165", "makes a new day low after 12:00")):
        add("LEVELS", f"When {u} {w} (last 5 min), is it up over the next 15 minutes more often?", "min", u, c, "(f15 > 0)",
            entry="s", side="auto", dircol="f15", horizon="15")
    for nm, w, sd in (("pu", "first breaks above the previous day's high (opened below it)", "C"),
                      ("pd", "first breaks below the previous day's low (opened above it)", "P")):
        add("LEVELS", f"When {u} {w}, how often does it close (15:09) beyond it?", "day", u, f"{nm}min >= 0", f"{nm}_close",
            ref="const:0.5", entry=f"{nm}min", side=sd)
        add("LEVELS", f"When {u} {w}, how far does it run in the next 30 minutes (%)?", "day", u, f"{nm}min >= 0", f"{nm}_f30",
            kind="cont", ref="const:0", entry=f"{nm}min", side=sd, horizon="30")
# 19 CROSS
PAIRS = [("BANKNIFTY", "FINNIFTY"), ("FINNIFTY", "BANKNIFTY"), ("BANKNIFTY", "NIFTY"), ("NIFTY", "BANKNIFTY"),
         ("SENSEX", "NIFTY"), ("NIFTY", "SENSEX"), ("MIDCPNIFTY", "NIFTY"), ("FINNIFTY", "NIFTY")]
for f_, l_ in PAIRS:
    for c, w in ((f"xz5_{l_} > 1.5 and abs(z5) < 0.5", f"{l_} jumped (>1.5 sd in 5 min) while {f_} stayed flat"),
                 (f"xz5_{l_} < -1.5 and abs(z5) < 0.5", f"{l_} dropped (<-1.5 sd in 5 min) while {f_} stayed flat")):
        for h in (5, 15):
            add("CROSS", f"If {w}, is {f_} up over the next {h} minutes more often (does {l_} lead)?", "min", f_, c, f"(f{h} > 0)",
                entry="s", side="auto", dircol=f"f{h}", horizon=str(max(h, 15)))
    for c, w in ((f"x30_{l_} > 15 and p30 < -15", f"{l_} rose >0.15% but {f_} fell >0.15% over 30 min"),
                 (f"x30_{l_} < -15 and p30 > 15", f"{l_} fell >0.15% but {f_} rose >0.15% over 30 min")):
        add("CROSS", f"If {w}, is {f_} up over the next 30 minutes more often (does it catch up)?", "min", f_, c, "(f30 > 0)",
            entry="s", side="auto", dircol="f30", horizon="30")
# 20 CONSTIT (2024-10+)
CU = "kd_HDFCBANK == kd_HDFCBANK"
for s_ in ("HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK"):
    for c, w in ((f"kz5_{s_} > 1.5 and abs(z5) < 0.5", f"{s_} jumped (>1.5 sd in 5 min) while BANKNIFTY was flat"),
                 (f"kz5_{s_} < -1.5 and abs(z5) < 0.5", f"{s_} dropped (<-1.5 sd) while BANKNIFTY was flat")):
        for h in (5, 15):
            add("CONSTIT", f"If {w}, is BANKNIFTY up over the next {h} minutes more often?", "min", "BANKNIFTY", c, f"(f{h} > 0)",
                universe=CU, entry="s", side="auto", dircol=f"f{h}", horizon="15", split=SPLIT["con"])
for u in ("BANKNIFTY", "FINNIFTY"):
    for c, w in (("z_bank5 > 1.5 and abs(z5) < 0.5", "the 5 big banks together jumped (>1.5 sd in 5 min)"),
                 ("z_bank5 < -1.5 and abs(z5) < 0.5", "the 5 big banks together dropped")):
        for h in (5, 15):
            add("CONSTIT", f"If {w} while {u} was flat, is {u} up over the next {h} minutes more often?", "min", u, c, f"(f{h} > 0)",
                universe=CU, entry="s", side="auto", dircol=f"f{h}", horizon="15", split=SPLIT["con"])
for s_ in ("RELIANCE", "HDFCBANK", "ICICIBANK", "INFY", "TCS", "BHARTIARTL", "LT", "ITC"):
    for c, w in ((f"kz5_{s_} > 1.5", f"{s_} jumped (>1.5 sd in 5 min)"), (f"kz5_{s_} < -1.5", f"{s_} dropped (<-1.5 sd in 5 min)")):
        add("CONSTIT", f"If {w}, is NIFTY up over the next 15 minutes more often?", "min", "NIFTY", c, "(f15 > 0)", universe=CU,
            entry="s", side="auto", dircol="f15", horizon="15", split=SPLIT["con"])
for u in ("NIFTY", "BANKNIFTY", "SENSEX"):
    for c, w in (("breadth1015 > 0.8", "more than 80% of the top-20 NIFTY stocks are above their open at 10:15"),
                 ("breadth1015 < 0.2", "fewer than 20% of the top-20 NIFTY stocks are above their open at 10:15")):
        add("CONSTIT", f"If {w}, is {u} up 10:15->15:09 more often?", "day", u, c, "(rest_60 > 0)",
            universe="breadth1015 == breadth1015", entry="60", side="auto", dircol="rest_60", split=SPLIT["con"])
for u in ("NIFTY", "SENSEX"):
    for c, w in (("breadth > 0.7 and iday < 0", "most top-20 stocks are above their open but the index is below its open"),
                 ("breadth < 0.3 and iday > 0", "most top-20 stocks are below their open but the index is above its open")):
        add("CONSTIT", f"If {w}, is {u} up over the next 30 minutes more often?", "min", u, c, "(f30 > 0)", universe="breadth == breadth",
            entry="s", side="auto", dircol="f30", horizon="30", split=SPLIT["con"])
# 21 SEASON (daily 2006+)
for u in U5:
    for c, w in (("tdm <= 3", "the first 3 trading days of a month"), ("tdme <= 3", "the last 3 trading days of a month"),
                 ("dow == 0 and pret > 1", "a Monday after a >1% up Friday"), ("dow == 0 and pret < -1", "a Monday after a >1% down Friday"),
                 ("pret5 > 3", "a week (5 days) up more than 3%"), ("pret5 < -3", "a week down more than 3%")):
        add("SEASON", f"On {w}, is {u} more often up (close > open)?", "dly", u, c, "up", entry="0", side="auto", dircol="oc")
# 22 OIPCR
for u in U5:
    for c, w in (("z_dpcr15 > 1.5", "near-ATM put OI grew much faster than call OI (>1.5 sd, 15 min)"),
                 ("z_dpcr15 < -1.5", "near-ATM call OI grew much faster than put OI (<-1.5 sd)")):
        for h in (15, 30):
            add("OIPCR", f"If {w}, is {u} up over the next {h} minutes more often?", "min", u, c, f"(f{h} > 0)", entry="s",
                side="auto", dircol=f"f{h}", horizon=str(h))
    for c, w in (("pcr > 1.3", "the near-ATM put/call OI ratio is above 1.3"), ("pcr < 0.7", "the near-ATM put/call OI ratio is below 0.7")):
        add("OIPCR", f"If at 10:15 {w}, is {u} up 10:15->15:09 more often?", "min", u, c, "(fsq > 0)", universe="s == 60", entry="s",
            side="auto", dircol="fsq")
# 23 VOLUME
for u in U5:
    for c, w in (("vshare > 0.65", "calls took >65% of near-ATM option volume in the last 5 min"),
                 ("vshare < 0.35", "puts took >65% of near-ATM option volume in the last 5 min")):
        add("VOLUME", f"If {w}, is {u} up over the next 15 minutes more often?", "min", u, c, "(f15 > 0)", entry="s", side="auto",
            dircol="f15", horizon="15")
    add("VOLUME", f"If {u}'s near-ATM option volume surged (>3x the prior 30-min pace), is the next 15-minute move bigger?", "min",
        u, "vsurge > 3", "abs(f15)", kind="cont", entry="s", side="both", horizon="15")
    for c, w in (("vsurge > 3 and p5 > 0", "with the index rising"), ("vsurge > 3 and p5 < 0", "with the index falling")):
        add("VOLUME", f"If {u}'s option volume surged (>3x) {w} over the last 5 min, is the next 15 minutes up more often?", "min", u,
            c, "(f15 > 0)", entry="s", side="auto", dircol="f15", horizon="15")


def how(q):
    t = {"day": "day table", "min": "5-min grid table", "dly": "daily-candle table"}[q["table"]]
    r = ("condition vs complement, difference in mean" if q["ref"] == "comp" else f"condition mean vs {q['ref'][6:]}")
    uni = f"; universe: {q['universe']}" if q["universe"] else ""
    tr = "no option trade (descriptive)" if q["side"] == "none" else (
        f"option: buy 1-ITM nearest-expiry {q['side']} at the open after decision minute [{q['entry']}], horizon {q['horizon']}")
    return f"{t}: {q['und']}{uni}; condition: {q['cond']}; outcome: {q['outcome']} ({q['kind']}); test: {r} (day-clustered SE); {tr}"


if __name__ == "__main__":
    out = os.path.join(HERE, "questions.csv")
    cols = ["id", "category", "question", "how_measured", "data_split", "table", "und", "universe", "cond", "outcome", "kind",
            "ref", "entry", "side", "dircol", "horizon"]
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for i, q in enumerate(Q, 1):
            row = dict(q)
            row["id"] = f"Q{i:04d}"
            row["how_measured"] = how(q)
            row["data_split"] = row.pop("split")
            w.writerow({k: row[k] for k in cols})
    from collections import Counter
    print(len(Q), Counter(q["category"] for q in Q))
