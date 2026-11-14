"""Research-only evidence and dated-tax guards; production modules untouched."""
from datetime import date
from dataclasses import replace
import re
import math
from numbers import Integral

from options_lab import costs


def stt_rate(day,event):
    d=date.fromisoformat(str(day)[:10])
    if not date(2024,10,1)<=d<=date(2026,11,14):raise ValueError('Outside verified schedule coverage')
    if event not in {'sale','exercise'}:raise ValueError('Unknown taxable event')
    if d>=date(2026,4,1):return .0015
    return .001 if event=='sale' else .00125


def dated_buy_to_settle(*,settlement_day,premium,lot_size,lots=1,regime,intrinsic=0.):
    if any(isinstance(v,bool) or not isinstance(v,Integral) or v<=0 for v in [lot_size,lots]):raise ValueError('Invalid whole-lot quantity')
    if not all(isinstance(v,(float,int)) and math.isfinite(v) and v>=0 for v in [premium,intrinsic]):raise ValueError('Invalid premium/intrinsic')
    c=costs.buy_to_settle(premium=premium,lot_size=lot_size,lots=lots,regime=regime,intrinsic=intrinsic)
    return replace(c,stt=max(0.,intrinsic)*lot_size*lots*stt_rate(settlement_day,'exercise'))


def section_numbers(document,heading):
    lines=document.splitlines();start=None;level=None;selected=[]
    for line in lines:
        m=re.match(r'^(#+)\s+(.*)$',line)
        if start is None:
            if m and m.group(2).strip()==heading:
                start=True;level=len(m.group(1))
        else:
            if m and len(m.group(1))<=level:break
            selected.append(line)
    if start is None:raise ValueError('Claimed source section not found')
    return set(re.findall(r'(?<!\w)-?\d+(?:[.,]\d+)*%?', '\n'.join(selected).replace('\u2212','-')))


def evidence_failures(record):
    failures=[]
    for flag in ['fresh_unseen','contract_verified','costs_verified','engine_parity','sample_adequate']:
        if record.get(flag) is not True:failures.append(flag)
    for key,tag,predicate in [('net','net_profit',lambda v:v>0),('stress_net','stress_profit',lambda v:v>0),
                              ('q','multiple_testing',lambda v:0<=v<=.1),('drawdown','risk_budget',lambda v:0<=v<=40000)]:
        value=record.get(key)
        if isinstance(value,bool) or not isinstance(value,(float,int)) or not math.isfinite(value) or not predicate(value):failures.append(tag)
    return failures
