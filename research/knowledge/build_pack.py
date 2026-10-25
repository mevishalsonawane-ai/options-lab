#!/usr/bin/env python3
"""Builds Jarvis's knowledge pack (ARCHITECTURE.md, L5 Level 4) from the repo's research.

    python research/knowledge/build_pack.py            # writes the pack and prints a summary
    python research/knowledge/build_pack.py --check    # fails if the committed pack is out of date

Sources (all in the repo, nothing fetched):
  * research/FINDINGS.md - every bullet, split at sentences when long (the master list; highest weight)
  * research/*.md (HUNT_*, R*, X*, and every study doc) - prose and list items; verdict-like sections weigh more and
    their tables are read row by row
  * research/hunt/h42/answers.csv - the 1,296 plain-language answers about how the indices move
  * android/app/src/main/assets/study/pages.txt - the app's study pages
  * research/knowledge/strategies.yaml - per-strategy cards (what it does, verdict, numbers with period and costs); every
    number in a card must appear in its cited source, else the build fails
  * research/models/*.md - model cards (none yet)
  * research/experiments/entries/*.yaml - the experiment log: every idea tried, failures included (13 Oct 2026)
  * research/app_lessons/*.json - the phone's own lessons sent back to research (import_app_lessons.py; rates, counts and
    effects only, never Boss's money)
  * research/knowledge/open_questions.yaml and baselines.yaml - the questions not settled yet and the execution baselines
    (every number checked against its source, like strategy cards)
  * research/knowledge/index_facts.yaml - R102's field guide (22 Oct 2026): how NIFTY and BANKNIFTY behave, one fact per
    entry with its sample size, period and instrument, so Jarvis can answer "how does BankNifty behave at 09:20?"
    (every number checked against its source, like strategy cards)
  * research/knowledge/book_facts.yaml - what the books Boss gave teach (25 Oct 2026, research/books/): short cards in our own
    words, each the BOOK's claim and said as such ("<author>'s book says (the book's claim, not our tested result): ..."),
    with our own test's result beside it where our research tested the idea. Fact index only (kind book_fact, weight 0.8, low
    confidence), never the shared section: a book's claim must not crowd out or outrank what our research tested.

The pack also carries a "shared" section (13 Oct 2026, Boss: "all the agents should learn from whatever findings we have"):
compact, tagged items for ira-core's SharedKnowledge - each with its topics, strategy (arm), instrument, time of day,
regime, execution flag, source kind, date, sample size, effect and gate (pass / fail / open) - plus the execution baselines.
Every agent on the phone reads it (context, explanation and ranking only; never a trade). It is capped at SHARED_CAP items
(25 Oct 2026): the findings, cards, curated open questions and index facts always, then the experiment log ranked - every
passed or open entry, one item per idea family of the rejected ones (a sweep's cells share a hypothesis; the same rule on two
markets is two), newest and arm-tied first - so a 1,700-entry log does not crowd the phone's memory; the fact index keeps
every entry for Jarvis. The build fails if the section still passes the cap (nothing is cut silently).

Weights (the retrieval's prior, 25 Oct 2026): FINDINGS and strategy cards 3.0; index facts 2.5; a doc's verdict / answer /
honest-count sections 2.2, its other priority sections (caveats, limits, lessons) 2.0; study pages, the h42 answers and the
experiment entries 2.0; the phone's lessons 1.5; plain doc prose 1.0; a proposed experiment (never run, kind proposed) 1.0;
book cards 0.8. ira-core's search also ranks the two claim kinds (book_fact, proposed) below every tested research hit when
asked whether something works.

Each fact: id, kind, title, text (plain English with its numbers), tags, instruments, source doc + section, date,
verdict (positive / weak / negative / context, a keyword heuristic except for strategy cards and h42), confidence
(high / medium / low) and personal (mentions Boss's own fills, trades or capital: never said on a locked phone).

Output: android/app/src/main/assets/knowledge/pack.bin (deterministic: same sources -> same bytes) and a small
pack_meta.json beside it. The app's retrieval (ira-core Knowledge) reads only this file.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import re
import subprocess
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
OUT_DIR = ROOT / "android/app/src/main/assets/knowledge"
OUT = OUT_DIR / "pack.bin"   # gzip JSON; a neutral name so the APK packer never treats it as a .gz
META = OUT_DIR / "pack_meta.json"
SCHEMA = 1
MAX_CHARS = 420           # one fact's text; longer passages are split at sentences
MAX_TABLE_ROWS = 40
MAX_BYTES = 4_000_000     # the pack ships in the APK: a few MB at most

# Docs that are design notes or plans, not findings.
SKIP_DOCS = {"research/data/ARCHITECTURE.md"}
# Of the priority sections, the ones that ARE the doc's answer (its verdict, its honest count, what it means for Boss) weigh a
# little more (2.2) than its caveats, limits and lessons (2.0) - 25 Oct 2026: with 180 studies in the pack, a study's verdict
# line must still beat another study's caveat on the same words. One word list, so the two patterns cannot drift apart.
VERDICT_WORDS = r"verdict|answer|conclusion|summary|what to do|recommend|headline|bottom line|for boss|what this means|honest|key finding|decision"
VERDICT_SECTION = re.compile(VERDICT_WORDS, re.I)
PRIORITY_SECTION = re.compile(VERDICT_WORDS + r"|result|limits|caveat|lesson|takeaway|plain|findings", re.I)
SKIP_SECTION = re.compile(r"^(files|sources|code|how to (re)?run|reproduce|scripts?|data files|references)\b", re.I)

INSTRUMENTS = [
    ("BANKNIFTY", r"\bbank ?nifty\b|\bnifty ?bank\b|\bBN\b"),
    ("FINNIFTY", r"\bfin ?nifty\b|\bFIN\b"),
    ("MIDCPNIFTY", r"\bmidcp(nifty)?\b|\bmidcap ?nifty\b"),
    ("NIFTY", r"(?<![a-z])(?<!bank )(?<!bank)(?<!fin )(?<!fin)(?<!midcp)nifty(?! ?bank)(?!nxt)\b"),
    ("SENSEX", r"\bsensex\b"),
    ("BANKEX", r"\bbankex\b"),
    ("INDIAVIX", r"\b(india )?vix\b"),
    ("GOLD", r"\bxauusd\b|\bgold(m)?\b"),
    ("SILVER", r"\bsilver(m|mic)?\b"),
    ("CRUDE", r"\bcrude ?(oil)?m?\b"),                     # CRUDE, CRUDEOIL and the mini CRUDEOILM (25 Oct 2026)
    ("NATURALGAS", r"\bnatural ?gas\b|\bnatgas(mini)?\b"),  # NATURALGAS and the mini NATGASMINI
    ("CRYPTO", r"\bcrypto\b|\bbitcoin\b|\bbtc\b|\beth(ereum)?\b"),
]
INST_RX = [(n, re.compile(p, re.I)) for n, p in INSTRUMENTS]
# The instrument names the pack may carry (ira-core's KnowledgeTest knows exactly these). An experiment entry names the
# contracts it ran on (GOLDM, CRUDEOILM, NATGASMINI, "GOLD, SILVER (daily continuous)"); canon_instruments() maps each to
# these, so the phone's readers see one name per market (25 Oct 2026).
KNOWN_INSTRUMENTS = {n for n, _ in INSTRUMENTS} | {"F&O stocks", "NIFTYNXT50"}
# Entry spellings of the two names that are not markets of INSTRUMENTS ("F&O stocks (top 50)", "Nifty Next 50"); read by
# canon_instruments() only, never in prose (every doc that says "stocks" is not about the stocks' options).
ENTRY_INST_RX = [("F&O stocks", re.compile(r"\bstocks?\b|\bf&o\b", re.I)), ("NIFTYNXT50", re.compile(r"nifty ?(nxt|next) ?50", re.I))]
# The entry instrument names canon_instruments() could not read (COPPER, "the same 6 books"): main() prints them as a note, so
# a new market is noticed rather than silently dropped.
UNKNOWN_INSTRUMENTS: set[str] = set()
# The MCX markets: an entry run only on these belongs to the app's MCX bots (the "mcx" arm), whatever its rule is called.
MCX_INSTRUMENTS = {"GOLD", "SILVER", "CRUDE", "NATURALGAS"}

TAGS = {
    "orb": r"\borb\b|opening range|range fade",
    "liquidity": r"liquidity",
    "expiry": r"expir",
    "gap": r"\bgap(s|ped|-up|-down| up| down)?\b",
    "vix": r"\bvix\b",
    "iv": r"\bimplied vol|\biv\b|\bskew\b",
    "straddle": r"straddle|strangle",
    "option-selling": r"\bsell(ing)? (the )?(options?|straddle|strangle|premium|puts?|calls?)|credit spread|iron (fly|condor)|short straddle|non-?directional selling",
    "option-buying": r"option buy|buying (the )?(atm |itm |otm )?(options?|calls?|puts?|ce|pe)|\bbuy(s|ing)? (the )?(atm|itm|otm|1-itm)",
    "candles": r"candle|candlestick",
    "volume": r"\bvolume\b",
    "oi": r"\boi\b|open interest|\bpcr\b|put.call ratio",
    "gex": r"\bgex\b|gamma",
    "swing": r"\bswing\b|multi-?day|overnight hold|daily chart",
    "overnight": r"overnight|15:20|next (day|session)",
    "scalp": r"scalp",
    "trend": r"\btrend",
    "supertrend": r"supertrend",
    "moving-average": r"\bema\b|\bsma\b|moving average|\bhma\b",
    "vwap": r"\bvwap\b",
    "rsi": r"\brsi\b",
    "ml": r"\bmodel\b|machine learning|neural|\bml\b|walk-forward|xgboost|lightgbm|meta-label",
    "costs": r"\bcosts?\b|charges|spread|slippage|brokerage",
    "stop": r"\bstop(s|ped)?\b|stop-loss|stoploss",
    "target": r"\btarget",
    "profit-lock": r"profit.lock|breakeven|break-even ladder|trail",
    "majority-rule": r"majority rule|\bmr ?14\b",
    "gann-fib": r"\bgann\b|fibonacci|\bfib\b|harmonic|elliott",
    "order-flow": r"order.?flow|delta|absorption|depth|\btape\b",
    "reentry": r"re-?entr|churn",
    "filter": r"\bfilter",
    "holdout": r"holdout|out of sample|locked (last )?year|pre-?regist",
    "news": r"\bnews\b|headline|\brbi\b|\bfed\b|budget|election",
    "fii": r"\bfii|\bdii\b|flows",
    "breadth": r"breadth|advance.decline|constituent",
    "theta": r"theta|time decay|\bdecay",
    "reversal": r"revers|mean.revert|fade",
    "momentum": r"momentum|follow-through|continuation",
    "time-of-day": r"\b(09|10|11|12|13|14|15):\d\d\b|first (15|30|hour)|midday|lunch|last hour",
    "day-of-week": r"monday|tuesday|wednesday|thursday|friday|weekday|day of week",
    "trade-manager": r"trade manager|specialists?",
    # "non-directional" (a straddle, a condor) is not about direction: without the lookbehind an open MCX condor question
    # carried the "direction" topic and the study agent treated index direction as unsettled (25 Oct 2026).
    "direction": r"(?<!non-)(?<!non )\bdirection(al)?\b|up or down",
    "traps": r"\btraps?\b|trap guard|stop hunt",
    # R86 (19 Oct 2026): footprints of the big players (FII/FPI positioning, CFTC managed money, dealers, stop runs)
    "big-players": r"big players?|\binstitution(al|s)?\b|\bfpis?\b|managed money|commitments of traders|\bcot\b|\bdealers?\b|market.makers?|stop.?runs?|participant.(wise|oi|data)",
    "sizing": r"\blots?\b|position siz|kelly|capital|drawdown",
    "pine": r"\bpine\b",
    "pattern": r"pattern|inside day|nr4|nr7|\bcpr\b",
    "mcx": r"\bmcx\b|commodit",
    "crypto": r"\bcrypto\b|\bbitcoin\b|\bbtc(usdt)?\b|\beth(usdt|ereum)?\b|\bvda\b|\bfunding (rate|carry)|\bperp(etual)?s?\b",
    "random-baseline": r"random|coin.flip|luck",
    "multiple-testing": r"benjamini|\bbh\b|\bq ?[<=]|multiple.testing|bonferroni|holm",
}
TAG_RX = {t: re.compile(p, re.I) for t, p in TAGS.items()}

WEAK_RX = re.compile(r"candidate|not proven|unproven|paper only|on paper|not confirmed|weak|small sample|too few|"
                     r"not significant|shadow|decaying|mixed|one year|treat as unproven|lead", re.I)
NEG_RX = re.compile(r"\blos(e|es|t|ing|s)\b|no edge|\bnoise\b|no signal|\bfail(s|ed)?\b|nothing|no reliable|worse|"
                    r"negative|coin flip|not worth|not added|\bNO\b|does not (help|pay|work|predict)|did not|hurts?|"
                    r"equal to random|break-?even to losing", re.I)
POS_RX = re.compile(r"positive in both|confirmed|passed|holds? up|beats? random|profitable|\bedge\b|survive|"
                    r"replicat|significant after|both halves positive|better in both", re.I)

PERSONAL_RX = re.compile(r"\bBoss'?s\b[^.]{0,60}\b(fills?|paper (days?|trades?|tests?)|trades?|p&l|pnl|account|logs?|orders?|"
                         r"capital|money|days?|figures|numbers)\b|\bBoss (bought|sold|took|made|lost|traded)\b|\b(his|my) (fills|trades|p&l|account)\b", re.I)
PERSONAL_DOCS = {"research/DAY_2026_09_29.md", "research/AGENTS_MISSED_2026-10-12.md", "research/HUNT_H22.md",
                 "research/X1_TODAY_AND_FORWARD.md"}
# Sections that replay or quote Boss's own days ("2026-09-30 (Boss: (12, -6014))", "Boss's paper days against the plan").
PERSONAL_SECTION = re.compile(r"Boss: \(|Boss's (paper|good|own|\+)|Boss's fills|app against replay|trades backfired", re.I)

MONTHS = {m: i for i, m in enumerate(["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], 1)}


# ---- small helpers --------------------------------------------------------------------------------------------------

def rel(p: Path) -> str:
    return p.resolve().relative_to(ROOT).as_posix()


def slug(s: str, n: int = 40) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
    return s[:n].strip("-") or "x"


def clean_md(s: str) -> str:
    s = re.sub(r"</?[a-zA-Z][a-zA-Z0-9]*(\s[^<>]{0,80})?/?>", " ", s)  # HTML tags only, never "p<0.05 ... ->"
    s = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", s)
    s = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", s)
    s = s.replace("**", "").replace("__", "").replace("`", "")
    s = re.sub(r"(?<!\w)\*(?!\s)([^*]+)(?<!\s)\*(?!\w)", r"\1", s)
    s = s.replace("₹", "Rs ").replace("→", "->").replace("≈", "~").replace("–", "-").replace("—", " - ").replace("−", "-")
    s = s.replace("×", "x").replace("≥", ">=").replace("≤", "<=").replace(" ", " ")
    s = re.sub(r"\s+", " ", s).strip()
    return s


SENT_SPLIT = re.compile(r"(?<=[.;!?])\s+(?=[A-Z(\"'])")


def chunks(text: str, limit: int = MAX_CHARS) -> list[str]:
    if len(text) <= limit:
        return [text]
    out, cur = [], ""
    for s in SENT_SPLIT.split(text):
        if len(s) > limit:  # one very long sentence: cut at clause boundaries, else hard
            parts = re.split(r"(?<=[,:])\s+", s)
        else:
            parts = [s]
        for p in parts:
            while len(p) > limit:
                cut = p.rfind(" ", 0, limit)
                cut = cut if cut > limit // 2 else limit
                if cur:
                    out.append(cur); cur = ""
                out.append(p[:cut].strip()); p = p[cut:].strip()
            if cur and len(cur) + 1 + len(p) > limit:
                out.append(cur); cur = p
            else:
                cur = (cur + " " + p).strip()
    if cur:
        out.append(cur)
    merged: list[str] = []
    for c in (c for c in out if c):
        if merged and (len(c) < 40 or len(merged[-1]) < 40):  # a scrap joins its neighbour
            merged[-1] = merged[-1] + " " + c
        else:
            merged.append(c)
    return merged


def instruments(text: str) -> list[str]:
    return [n for n, rx in INST_RX if rx.search(text)]


def canon_instruments(raw: list) -> list[str]:
    """An entry's instrument names as the pack's names, in order, each once: a known name stays ("BANKNIFTY", "F&O stocks"),
    a contract or a free-text list is read for the markets it names ("GOLDM" -> GOLD, "CRUDEOIL, NATURALGAS" -> CRUDE,
    NATURALGAS), and a name of no pack market ("COPPER", "the same 6 books") is dropped - the fact's text still says it."""
    out: list[str] = []
    for r in raw:
        if not isinstance(r, str):
            continue
        names = [r] if r in KNOWN_INSTRUMENTS else instruments(r) + [n for n, rx in ENTRY_INST_RX if rx.search(r)]
        if not names:
            UNKNOWN_INSTRUMENTS.add(r)
        for n in names:
            if n not in out:
                out.append(n)
    return out


def tags(text: str) -> list[str]:
    return sorted(t for t, rx in TAG_RX.items() if rx.search(text))


def verdict_of(text: str) -> str:
    if WEAK_RX.search(text):
        return "weak"
    neg, pos = len(NEG_RX.findall(text)), len(POS_RX.findall(text))
    if neg > pos:
        return "negative"
    if pos > neg:
        return "positive"
    return "context"


def parse_date(s: str) -> str | None:
    m = re.search(r"\b(20\d\d)-(\d\d)-(\d\d)\b", s)
    if m:
        return f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
    m = re.search(r"\b(\d{1,2}) (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* (20\d\d)\b", s, re.I)
    if m:
        return date(int(m.group(3)), MONTHS[m.group(2).lower()], int(m.group(1))).isoformat()
    return None


def git_date(path: Path, first: bool = True) -> str | None:
    try:
        out = subprocess.run(["git", "log", "--follow", "--format=%as", "--", rel(path)], cwd=ROOT,
                             capture_output=True, text=True, check=True).stdout.split()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None
    if not out:
        return None
    return out[-1] if first else out[0]


def blame_dates(path: Path) -> list[str | None]:
    """The commit date of each line (1-based index - 1), or None where git cannot say (uncommitted)."""
    try:
        out = subprocess.run(["git", "blame", "--line-porcelain", "--", rel(path)], cwd=ROOT, capture_output=True,
                             text=True, check=True).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return []
    dates, cur = [], None
    for line in out.splitlines():
        if line.startswith("author-time "):
            cur = datetime.fromtimestamp(int(line.split()[1]), timezone.utc).date().isoformat()
        elif line.startswith("\t"):
            dates.append(cur)
    return dates


NUM_RX = re.compile(r"\d[\d,]*(?:\.\d+)?")


def numbers(s: str) -> list[str]:
    return [n.replace(",", "").rstrip(".") for n in NUM_RX.findall(s)]


# ---- markdown reading ----------------------------------------------------------------------------------------------

class Block:
    def __init__(self, kind: str, text: str, section: str, line: int, rows: list[list[str]] | None = None, end: int = 0):
        self.kind, self.text, self.section, self.line, self.rows, self.end = kind, text, section, line, rows, end or line


def md_blocks(text: str) -> tuple[str, list[Block]]:
    """(title, blocks): paragraphs, list items (continuations joined) and tables, each with its section path."""
    title, heads, blocks = "", [], []
    lines = text.splitlines()
    i, in_code = 0, False
    cur: list[str] = []
    cur_kind, cur_line = None, 0

    def section() -> str:
        return " > ".join(h for _, h in heads)

    def flush(end: int):
        nonlocal cur, cur_kind
        if cur:
            blocks.append(Block(cur_kind or "para", clean_md(" ".join(cur)), section(), cur_line, end=end))
        cur, cur_kind = [], None

    while i < len(lines):
        raw = lines[i]
        s = raw.strip()
        if s.startswith("```"):
            flush(i); in_code = not in_code; i += 1; continue
        if in_code:
            i += 1; continue
        m = re.match(r"^(#{1,6})\s+(.*)$", s)
        if m:
            flush(i)
            lvl, h = len(m.group(1)), clean_md(m.group(2))
            if lvl == 1 and not title:
                title = h
            else:
                heads = [x for x in heads if x[0] < lvl] + [(lvl, h)]
            i += 1; continue
        if s.startswith("|"):
            flush(i)
            start, rows = i, []
            while i < len(lines) and lines[i].strip().startswith("|"):
                cells = [clean_md(c) for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{2,}:?|", c.replace(" ", "")) for c in cells):
                    rows.append(cells)
                i += 1
            blocks.append(Block("table", "", section(), start + 1, rows=rows, end=i))
            continue
        if not s:
            flush(i); i += 1; continue
        item = re.match(r"^(\s*)([-*+]|\d+[.)])\s+(.*)$", raw)
        if item:
            indent = len(item.group(1))
            # A nested item joins its parent when the parent ends with ":" (a lead-in); else it is its own item.
            if indent >= 2 and cur and cur_kind == "item" and cur[-1].rstrip().endswith(":"):
                cur.append(item.group(3)); i += 1; continue
            flush(i)
            cur, cur_kind, cur_line = [item.group(3)], "item", i + 1
            i += 1; continue
        if s.startswith(">"):
            s = s.lstrip("> ").strip()
        if not cur:
            cur_kind, cur_line = "para", i + 1
        cur.append(s)
        i += 1
    flush(len(lines))
    return title, blocks


def table_rows(rows: list[list[str]]) -> list[str]:
    if len(rows) < 2:
        return []
    head = rows[0]
    out = []
    for r in rows[1:MAX_TABLE_ROWS + 1]:
        if not any(c.strip() for c in r):
            continue
        label = r[0].strip()
        parts = [f"{h.strip()} {c.strip()}".strip() for h, c in zip(head[1:], r[1:]) if c.strip()]
        line = (label + ": " if label else "") + "; ".join(parts)
        out.append(clean_md(line))
    return out


def worth(text: str) -> bool:
    if len(text) < 40:
        return False
    # A lead-in to a list or table ("Walk-forward 2019-2026, BANKNIFTY:") says nothing on its own.
    if text.rstrip().endswith(":") and len(text) < 200:
        return False
    if re.search(r"https?://|www\.", text):
        return False
    if len(re.findall(r"[a-zA-Z]{3,}", text)) < 6:
        return False
    # File lists: mostly paths.
    if len(re.findall(r"[\w/]+\.(py|csv|parquet|json|md|txt|pine)\b", text)) >= 2 and len(text) < 160:
        return False
    return True


# ---- builders -------------------------------------------------------------------------------------------------------

class Pack:
    def __init__(self):
        self.facts: list[dict] = []
        self.ids: set[str] = set()

    def add(self, *, id_: str, kind: str, title: str, text: str, src: str, sec: str, date_: str | None,
            insts: list[str], tags_: list[str], verdict: str, conf: str, weight: float, personal: bool,
            extra: dict | None = None):
        base, n = id_, 2
        while id_ in self.ids:
            id_ = f"{base}-{n}"; n += 1
        self.ids.add(id_)
        f = {"id": id_, "kind": kind, "title": title, "text": text, "tags": tags_, "inst": insts, "src": src,
             "sec": sec, "date": date_ or "", "verdict": verdict, "conf": conf, "w": round(weight, 2),
             "personal": personal}
        if extra:
            f.update(extra)
        self.facts.append(f)


def doc_default_instruments(title: str, blocks: list[Block], fallback: list[str]) -> list[str]:
    head = title + " " + " ".join(b.text for b in blocks[:3])
    return instruments(head) or fallback


def add_markdown(pack: Pack, path: Path, *, findings: bool = False):
    src = rel(path)
    text = path.read_text(encoding="utf-8")
    title, blocks = md_blocks(text)
    title = title or path.stem.replace("_", " ")
    written = parse_date(" ".join(text.splitlines()[:6])) or git_date(path, first=True)
    line_dates = blame_dates(path) if findings else []
    doc_insts = doc_default_instruments(title, blocks, ["BANKNIFTY"] if findings else [])
    prefix = "findings" if findings else slug(path.stem, 30)
    doc_personal = src in PERSONAL_DOCS
    holdout_doc = bool(re.search(r"holdout|pre-?regist|locked", text, re.I))
    claims_doc = bool(re.search(r"internet|catalog|claimed", path.stem + " " + title, re.I))
    counter: dict[str, int] = {}
    for b in blocks:
        sec = b.section
        top = sec.split(" > ")[0] if sec else ""
        if any(SKIP_SECTION.search(part) for part in sec.split(" > ") if part):
            continue
        priority = bool(PRIORITY_SECTION.search(sec)) or findings
        if b.kind == "table":
            if not priority:
                continue
            texts = [c for t in table_rows(b.rows or []) if worth(t) for c in chunks(t)]
        else:
            texts = chunks(b.text) if worth(b.text) else []
        if not texts:
            continue
        # The section heading's own date ("(R13, 11 Oct 2026)"), else the lines' last commit (FINDINGS), else the doc's.
        d = parse_date(sec) if findings else None
        if not d and line_dates:
            ds = [x for x in line_dates[b.line - 1:b.end] if x]
            d = max(ds) if ds else None
        d = d or written
        label = None
        if findings and b.kind == "item":
            m = re.match(r"^([A-Z0-9][A-Z0-9 +&,/.'-]{3,70}?)\s*[(:]", b.text)
            if m and sum(ch.isupper() for ch in m.group(1)) >= 3:
                label = m.group(1).strip().title()
        if label:
            sec = f"{sec} > {label}" if sec else label
            top = label
        sec_key = slug(top or "intro", 24)
        for t in texts:
            counter[sec_key] = counter.get(sec_key, 0) + 1
            ctx = f"{title} {sec} {t}"
            insts = instruments(t) or doc_default_instruments(title, blocks, doc_insts)
            if findings:
                weight = 3.0
                conf = "high" if re.search(r"both years|holdout|pre-registered|confirmed", t, re.I) else "medium"
            elif priority:
                weight, conf = (2.2 if VERDICT_SECTION.search(sec) else 2.0), ("high" if holdout_doc else "medium")
            else:
                weight, conf = 1.0, "medium"
            if claims_doc and re.search(r"claim|published|blog|youtube|forum|says", t, re.I):
                conf = "low"
            pack.add(id_=f"{prefix}.{sec_key}.{counter[sec_key]}", kind="finding" if findings else "doc",
                     title=title, text=t, src=src, sec=sec or "(top)", date_=d, insts=insts, tags_=tags(ctx),
                     verdict=verdict_of(t), conf=conf, weight=weight,
                     personal=doc_personal or bool(PERSONAL_SECTION.search(sec) or PERSONAL_RX.search(t)))


H42_CAT_TAGS = {
    "TOD": ["time-of-day"], "GAP": ["gap"], "MOMREV": ["momentum", "reversal"], "VIX": ["vix"], "OPTPREM": ["option-buying", "theta"],
    "PRIORDAY": ["pattern"], "STREAK": ["candles", "momentum"], "JUMPS": ["momentum"], "TIMING": ["time-of-day"],
    "DOW": ["day-of-week"], "THETA": ["theta", "straddle"], "CONSTIT": ["breadth"], "OPEN": ["time-of-day"],
    "EXPIRY": ["expiry"], "FIRSTHOUR": ["time-of-day"], "LEVELS": ["pattern"], "CROSS": ["breadth"], "TREND": ["trend"],
    "ROUND": ["pattern"], "PIN": ["expiry", "oi"], "SEASON": ["day-of-week"], "OIPCR": ["oi"], "VOLUME": ["volume"],
}
H42_UND = {"BN": "BANKNIFTY", "FIN": "FINNIFTY", "MIDCP": "MIDCPNIFTY"}
# Each h42 category in plain words, for the answers' titles ("How BANKNIFTY behaves: gaps (h42)"): the retrieval boosts a fact
# whose title names what the question asks, so a "how does BankNifty behave after a gap down" finds the answers about
# BANKNIFTY gaps before a study's gap rule (25 Oct 2026). The title is read by the retrieval only; Jarvis says the text.
H42_CAT_WORDS = {
    "TOD": "time of day", "GAP": "gaps", "MOMREV": "momentum and reversal", "VIX": "VIX", "OPTPREM": "option premium",
    "PRIORDAY": "the prior day", "STREAK": "streaks", "JUMPS": "jumps", "TIMING": "timing", "DOW": "day of week", "THETA": "theta",
    "CONSTIT": "constituents", "OPEN": "the open", "EXPIRY": "expiry", "FIRSTHOUR": "the first hour", "LEVELS": "levels",
    "CROSS": "crosses", "TREND": "trend", "ROUND": "round numbers", "PIN": "pinning", "SEASON": "season", "OIPCR": "OI and PCR",
    "VOLUME": "volume",
}
# The h42 answers weigh like a study's verdict section: 1,296 tested behaviour answers with a BH q-value each, the first source
# for "how does X behave" (25 Oct 2026; they were 1.2 when nothing else in the pack described the indices' behaviour).
H42_WEIGHT = 2.0


def add_h42(pack: Pack):
    path = RESEARCH / "hunt/h42/answers.csv"
    if not path.is_file():
        return
    src = rel(path)
    with path.open(encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    for r in rows:
        q = clean_md(r["question"])
        ans = clean_md(r["answer"])
        behaviour = re.sub(r"\s*\[95% CI [^\]]*\]", "", ans.split(". Option")[0]).rstrip(".")
        # Keep the plain read and the trade verdict; drop the long exit list.
        trade = ""
        m = re.search(r"->\s*(.+)$", ans)
        if m:
            trade = " As an option buy: " + m.group(1).strip().rstrip(".") + "."
        text = f"{q} Answer: {behaviour}.{trade}"
        try:
            qv = float(r.get("q") or 1)
        except ValueError:
            qv = 1.0
        stable = str(r.get("stable")).lower() == "true"
        conf = "high" if qv < 0.05 and stable else ("medium" if qv < 0.2 else "low")
        und = H42_UND.get(r["und"], r["und"])
        cand = str(r.get("candidate_strict")).lower() == "true"
        cat = r["category"]
        pack.add(id_=f"h42.{r['id'].lower()}", kind="answer",
                 title=f"How {und or 'the index'} behaves: {H42_CAT_WORDS.get(cat, cat.lower())} (h42)",
                 text=text, src=src, sec=f"{r['id']} ({cat})", date_="2026-10-08",
                 insts=[und] if und else instruments(q), tags_=sorted(set(tags(q) + H42_CAT_TAGS.get(cat, []))),
                 verdict="weak" if cand else "context", conf=conf, weight=H42_WEIGHT, personal=False,
                 extra={"q": round(qv, 4)})


def add_study_pages(pack: Pack):
    path = ROOT / "android/app/src/main/assets/study/pages.txt"
    if not path.is_file():
        return
    src = rel(path)
    d = git_date(path, first=False)
    head, paras = None, []

    def flush():
        if not head:
            return
        pid, title, group, kws = (head + ["", "", "", ""])[:4]
        body = "\n".join(paras)
        for n, para in enumerate([p for p in re.split(r"\n\s*\n", body) if p.strip()], 1):
            t = clean_md(para.replace("\n", " "))
            for k, c in enumerate(chunks(t), 1):
                ctx = f"{title} {kws} {c}"
                pack.add(id_=f"study.{slug(pid.strip(), 24)}.{n}" + (f".{k}" if k > 1 else ""), kind="study",
                         title=title.strip(), text=c, src=src, sec=f"{title.strip()} ({group.strip()})", date_=d,
                         insts=instruments(c) or instruments(ctx), tags_=tags(ctx), verdict=verdict_of(c), conf="medium",
                         weight=2.0, personal=bool(PERSONAL_RX.search(c)))

    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            continue
        if line.startswith("@@"):
            flush(); head = [x.strip() for x in line[2:].split("|")]; paras = []; continue
        if head is not None:
            paras.append(line)
    flush()


def source_numbers(src: str, cache: dict) -> set[str]:
    if src not in cache:
        p = ROOT / src
        cache[src] = set(numbers(clean_md(p.read_text(encoding="utf-8")))) if p.is_file() else set()
    return cache[src]


def add_strategies(pack: Pack) -> list[str]:
    path = RESEARCH / "knowledge/strategies.yaml"
    cards = yaml.safe_load(path.read_text(encoding="utf-8")) or []
    errors, cache = [], {}
    for c in cards:
        sid, name = c["id"], c["name"]
        insts = c.get("instruments", [])
        vtag = c.get("verdict", "context")
        base_tags = sorted(set(c.get("tags", []) + tags(name + " " + c.get("what", ""))))
        what = clean_md(c.get("what", ""))
        said = {"positive": "it held up", "weak": "a small edge at best, paper only",
                "negative": "it does not pay after costs"}.get(vtag, "context only")
        # Dated by the newest of its sources (not by this file's own commit, so a rebuild after committing gives the same pack).
        src_dates = [git_date(ROOT / f["src"], first=False) for f in c.get("facts", []) if (ROOT / f["src"]).is_file()]
        pack.add(id_=f"strategy.{sid}.what", kind="strategy", title=name, text=f"{name}: {what} Verdict: {said}.", src=rel(path),
                 sec=name, date_=max([d for d in src_dates if d], default=None), insts=insts, tags_=base_tags, verdict=vtag, conf="high",
                 weight=3.0, personal=False)
        for n, f in enumerate(c.get("facts", []), 1):
            src = f["src"]
            missing = [x for x in numbers(f["text"]) if x not in source_numbers(src, cache)]
            if not (ROOT / src).is_file():
                errors.append(f"{sid}: source {src} not found")
            elif missing:
                errors.append(f"{sid} fact {n}: numbers {missing} not in {src}")
            text = clean_md(f["text"]) + f" (Period: {f['period']}. Costs: {f['costs']}.)"
            pack.add(id_=f"strategy.{sid}.{n}", kind="strategy", title=name, text=text, src=src, sec=f["sec"],
                     date_=git_date(ROOT / src, first=False) if (ROOT / src).is_file() else None, insts=insts,
                     tags_=sorted(set(base_tags + tags(f["text"]))), verdict=vtag, conf="high", weight=3.0,
                     personal=bool(PERSONAL_RX.search(f["text"])),
                     extra={"period": f["period"], "costs": f["costs"], "strategy": sid})
    return errors


def add_model_cards(pack: Pack) -> int:
    d = RESEARCH / "models"
    n = 0
    for p in sorted(d.glob("*.md")) if d.is_dir() else []:
        before = len(pack.facts)
        add_markdown(pack, p)
        for f in pack.facts[before:]:
            f["kind"] = "model"
        n += 1
    return n


# ---- the shared knowledge (13 Oct 2026) ------------------------------------------------------------------------------

EXPERIMENTS = RESEARCH / "experiments/entries"
APP_LESSONS = RESEARCH / "app_lessons"

# A research strategy card -> the app's strategy (the trade manager's family keys, TradeManager.FAMILIES).
STRATEGY_ARMS = {
    "liquidity-15-5": "liquidity", "orb": "orb", "orb-fresh": "orb_fresh", "orb-sweep": "orb_sweep", "range-fade": "range_fade",
    "big-candle-pullback": "solo", "expiry-hero": "hero", "night-r3": "night", "vix-divergence": "vixdiv",
    "supertrend-ema": "pine", "mcx-bots": "mcx",
}
# The app's strategy a text names (the first that matches; ORB Fresh / Sweep before ORB).
ARM_RX = [(a, re.compile(p, re.I)) for a, p in [
    ("orb_fresh", r"\borb fresh\b"), ("orb_sweep", r"\borb sweep\b"), ("range_fade", r"\brange fade\b"),
    ("liquidity", r"\bliquidity\b"), ("orb", r"\borb\b|opening range break"), ("hero", r"expiry hero|zero to hero"),
    ("night", r"\bnight \(?r3\)?"), ("vixdiv", r"vix divergence"), ("solo", r"\bsolo\b|big-candle pullback"),
    ("pine", r"\bpine\b|supertrend"), ("mcx", r"\bmcx\b"),
]]
TOD_RX = [("morning", re.compile(r"\bmorning|before 10:30|first (15|30|hour)|\bthe open\b|09:\d\d", re.I)),
          ("midday", re.compile(r"\bmidday|lunch|1[12]:\d\d", re.I)),
          ("afternoon", re.compile(r"afternoon|last hour|1[45]:\d\d|after 13:30", re.I))]
REGIME_RX = [("busy", re.compile(r"\bbusy\b|high[- ]vix|volatile", re.I)), ("quiet", re.compile(r"\bquiet\b|low[- ]vix|calm", re.I)),
             ("trending", re.compile(r"\btrend(ing)? day", re.I)), ("sideways", re.compile(r"sideways|range day", re.I))]
EXEC_RX = re.compile(r"spread|slippage|charges|\bcosts?\b|fill(s|ed)? at|round trip|brokerage", re.I)
N_RX = re.compile(r"\b(\d[\d,]*) (trades|days|sessions|touches|nights|orders|signals|episodes|questions|fills)\b", re.I)
VERDICT_GATE = {"positive": "pass", "negative": "fail", "weak": "none", "context": "none", "open": "open"}
EXP_VERDICT = {  # experiment-log verdict -> (pack verdict, gate)
    "adopted": ("positive", "pass"), "paper-only": ("weak", "pass"), "shadow": ("open", "open"), "rejected": ("negative", "fail"),
    "inconclusive": ("open", "open"), "superseded": ("context", "none"), "not-a-test": ("context", "none"),
    "proposed": ("context", "none"),
}
# A proposed entry (25 Oct 2026: the books' testable rules) is an idea written down and never run: it goes in the fact index,
# so Jarvis can say what is queued, but never in the shared section, where its fresh date would rank it above real results.
EXP_UNRUN = {"proposed"}
# Words of the method, not of the idea: a hypothesis saying "makes money after real costs, read once on the holdout, BH q <= 0.1"
# is not about costs, the holdout or multiple testing. These topics come from an entry's title or family only.
EXP_METHOD_TAGS = {"costs", "holdout", "multiple-testing", "random-baseline", "ml"}
EXP_FAMILY_TOPIC = {
    "liquidity": "liquidity", "orb": "orb", "ml": "ml", "patterns-indicators": "pattern", "flow-oi": "order-flow",
    "overnight": "overnight", "calendar-events": "expiry", "sizing-execution": "costs", "option-structure": "straddle",
    "outside-strategies": "pine", "trade-management": "trade-manager", "daily-filters": "filter", "market-facts": "direction",
    "mcx-crypto": "mcx", "stocks": "stocks", "diagnosis": "diagnosis",
}


def arm_of(text: str) -> str | None:
    for a, rx in ARM_RX:
        if rx.search(text):
            return a
    return None


def first_of(rxs, text: str) -> str | None:
    for name, rx in rxs:
        if rx.search(text):
            return name
    return None


def sample_size(text: str) -> int | None:
    m = N_RX.search(text)
    return int(m.group(1).replace(",", "")) if m else None


def shared_item(*, id_: str, topics: list[str], text: str, src: str, source: str, date_: str | None, verdict: str,
                gate: str | None = None, arm: str | None = None, inst: str | None = None, n: int | None = None,
                effect: float | None = None, exec_: bool | None = None, sec: str = "") -> dict:
    """One item of the shared section (short keys: the phone keeps a few hundred in memory)."""
    if "crypto" in topics and set(instruments(text)) <= {"CRYPTO"}:
        # Crypto research must not steer the phone's NSE/MCX agents: an open crypto question tagged "direction" made the
        # study agent treat Indian direction as unsettled (13 Oct 2026). Crypto-only items carry only the crypto topic
        # here; the fact index keeps their full tags, so Jarvis still finds them.
        topics, inst = ["crypto"], "CRYPTO"
        arm = arm or ""
    it = {"id": id_, "t": sorted(set(topics))[:8], "x": text[:MAX_CHARS], "s": src, "k": source, "d": date_ or "",
          "v": verdict, "g": gate or VERDICT_GATE.get(verdict, "none")}
    a = arm if arm is not None else arm_of(text)
    if a:
        it["a"] = a
    if inst:
        it["i"] = inst
    tod, reg = first_of(TOD_RX, text), first_of(REGIME_RX, text)
    if tod:
        it["tod"] = tod
    if reg:
        it["r"] = reg
    if exec_ if exec_ is not None else bool(EXEC_RX.search(text)):
        it["e"] = True
    nn = n if n is not None else sample_size(text)
    if nn:
        it["n"] = nn
    if effect is not None:
        it["f"] = round(effect, 4)
    if sec:
        it["sec"] = sec
    return it


def checked(cards: list[dict], path: Path, cache: dict, label: str) -> list[str]:
    """Every number in each card's text must appear in its cited source (like strategy cards)."""
    errors = []
    for c in cards:
        src = c.get("src", "")
        if not (ROOT / src).is_file():
            errors.append(f"{label} {c.get('id', c.get('inst', '?'))}: source {src} not found")
            continue
        missing = [x for x in numbers(c.get("text", "")) if x not in source_numbers(src, cache)]
        if missing:
            errors.append(f"{label} {c.get('id', c.get('inst', '?'))}: numbers {missing} not in {src}")
    return errors


# The shared section's size (25 Oct 2026). Every item is kept in the phone's memory and ira-core's SharedKnowledgeTest allows
# 2,000; the experiment log passed that on its own (1,678 entries, most of them cells of one parameter sweep), so the
# experiments are ranked and only the best fill the room left after the findings, cards, open questions and index facts.
SHARED_CAP = 1_800
# Entries every agent must see whatever the room: the ideas that passed (adopted, paper-only) and the ones still open
# (shadow, inconclusive) - the study asks the open ones first, the arms cite the passed ones.
EXP_ALWAYS = {"adopted", "paper-only", "shadow", "inconclusive"}


def exp_rank(e: dict, item: dict) -> tuple:
    """An experiment's place in the shared section, best first: passed or open, then tied to an app strategy, then the
    newest, then the largest sample, then its id (deterministic)."""
    day = re.sub(r"\D", "", str(item.get("d", "")))  # "2026-10-20" -> 20261020; an undated entry sorts last
    return (0 if str(e.get("verdict")) in EXP_ALWAYS else 1, 0 if item.get("a") else 1, -int(day) if day.isdigit() else 0,
            -(item.get("n") or 0), str(e.get("id")))


def exp_family(e: dict, item: dict) -> tuple:
    """The idea family an entry belongs to: its study doc, family, verdict, app strategy, markets and hypothesis with the numbers
    taken out. One sweep's 70 cells share one hypothesis and differ in thresholds (the shared section keeps the best one of
    them, the fact index keeps every cell for Jarvis); the same rule on NIFTY and on BANKNIFTY are two results, so the
    markets are part of the key. The cells' titles are not: three selling structures of one R103 hypothesis are one family here."""
    hyp = re.sub(r"[^a-z ]+", " ", str(e.get("hypothesis", "")).lower())
    hyp = re.sub(r"\s+", " ", hyp).strip()[:160]
    return (str(e.get("doc", "")), str(e.get("family", "")), str(e.get("verdict", "")), item.get("a") or "",
            tuple(canon_instruments(e.get("instruments") or [])), hyp)


def select_experiments(entries: list[dict], items: list[dict], room: int) -> list[dict]:
    """The shared items of the experiment log ([items], one per entry of [entries], same order) that fit in [room]: every
    passed or open entry, one item per idea family of the rest (the best by exp_rank), then by rank until the room is full.
    The items come back in their entries' order."""
    best: dict[tuple, tuple[tuple, dict]] = {}
    always: list[dict] = []
    for e, it in zip(entries, items):
        if str(e.get("verdict")) in EXP_ALWAYS:
            always.append(it)  # kept whatever the room (build() fails loudly if the section then passes its cap)
            continue
        key, rank = exp_family(e, it), exp_rank(e, it)
        if key not in best or rank < best[key][0]:
            best[key] = (rank, it)
    rest = sorted(best.values(), key=lambda x: x[0])[:max(room - len(always), 0)]
    ids = {it["id"] for it in always} | {it["id"] for _, it in rest}
    return [it for it in items if it["id"] in ids]


def add_experiments(pack: Pack, shared: list[dict], entries: list[dict] | None = None) -> int:
    """The experiment log: one fact and one shared item per entry (every idea tried, failures included). The shared items are
    candidates: build() keeps the ones select_experiments() ranks into the room; [entries], when given, collects each item's
    entry (same order) for that ranking."""
    n = 0
    for p in sorted(EXPERIMENTS.glob("*.yaml")) if EXPERIMENTS.is_dir() else []:
        e = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        if not isinstance(e, dict) or "id" not in e:
            continue
        verdict, gate = EXP_VERDICT.get(str(e.get("verdict")), ("context", "none"))
        res = (e.get("results") or {}).get("summary", "")
        title = clean_md(str(e.get("title", e["id"])))
        text = clean_md(f"{e.get('title', '')}: {e.get('hypothesis', '')} Result: {res} Verdict: {e.get('verdict', '?')}.")
        if "Result:" not in text[:MAX_CHARS * 2]:
            # A long hypothesis pushed the result past the cut, so the entry was said with no result at all (the R104 book
            # rules, 25 Oct 2026): then the result and verdict go first and the hypothesis after.
            text = clean_md(f"{e.get('title', '')}: Result: {res} Verdict: {e.get('verdict', '?')}. "
                            f"Hypothesis: {e.get('hypothesis', '')}")
        insts = canon_instruments(e.get("instruments") or [])
        fam = str(e.get("family", ""))
        # Topics from what the idea IS (its title, and its hypothesis less the method's words), not from its result summary:
        # every summary and most hypotheses say "pays after costs", "holdout", "BH q", so tagging them gave every entry the
        # costs, holdout and multiple-testing topics, and the order review cited an MCX natural-gas sweep, then an ORB
        # time-of-day sweep, for an ORB charges flaw instead of R14's re-entry churn (25 Oct 2026).
        topics = sorted(set(tags(title) + [t for t in tags(clean_md(str(e.get("hypothesis", "")))) if t not in EXP_METHOD_TAGS] +
                            [EXP_FAMILY_TOPIC.get(fam, fam)] +
                            # A crypto-only entry is about crypto whatever its title says ("CoinSwitch costs"): shared_item() then
                            # keeps it off the NSE / MCX agents' topics.
                            (["crypto"] if insts and set(insts) <= {"CRYPTO"} else [])))
        # Cited by the study it belongs to (its doc), so Jarvis says "HUNT_R14_REENTRY_CHURN.md" rather than "r14.yaml" and the
        # retrieval's two-per-doc rule counts a study's entries with its prose.
        doc = str(e.get("doc") or "")
        src = doc if doc and (ROOT / doc).is_file() else (rel(p) if p.resolve().is_relative_to(ROOT) else p.name)
        app = str(e["id"]).startswith("app-")
        # The strategy the entry's words name ("Liquidity 15+5 on GOLDM" is the Liquidity arm's result, on gold); an MCX-only
        # entry naming none is the MCX bots'; a crypto-only one is nobody's (shared_item); else its family's default.
        arm = arm_of(text) or ("mcx" if insts and set(insts) <= MCX_INSTRUMENTS else None) or (fam if fam in ("liquidity", "orb") else None)
        unrun = str(e.get("verdict")) in EXP_UNRUN
        if unrun:
            # Its own kind, so ira-core's search never lets it lead a "does X work" answer, and said as not run first: the
            # spoken text is cut at MAX_CHARS * 2, which on a long hypothesis fell before "Result: Not run" (25 Oct 2026).
            title = f"Proposed, not run yet: {title}"
            text = clean_md(f"Not run yet (a proposed test, no result): {e.get('title', '')}. {e.get('hypothesis', '')}")
        pack.add(id_=f"exp.{slug(str(e['id']), 40)}", kind="proposed" if unrun else "experiment", title=title, text=text[:MAX_CHARS * 2],
                 src=src, sec=f"experiment {e['id']} ({fam})", date_=str(e.get("date", "")) or None, insts=insts, tags_=topics,
                 verdict="weak" if verdict == "open" else verdict,
                 conf="low" if unrun else "high" if e.get("holdout_read") else "medium", weight=1.0 if unrun else 2.0,
                 personal=False, extra={"exp": str(e["id"]), "family": fam, "tries": e.get("variants_tried")})
        if unrun:
            continue
        n += 1
        # The sample size is what was tested (the results' "540 trades"), never a bar set beforehand ("at least 100 trades").
        n_ = e.get("variants_tried") if app else sample_size(clean_md(str(res)))
        shared.append(shared_item(id_=f"exp:{e['id']}", topics=topics, text=text, src=src,
                                  source="app" if app else "experiment", date_=str(e.get("date", "")), verdict=verdict, gate=gate,
                                  arm=arm, inst=insts[0] if len(insts) == 1 else None, n=n_ or 0, sec=fam))
        if entries is not None:
            entries.append(e)
    return n


def add_app_lessons(pack: Pack, shared: list[dict]) -> int:
    """The phone's lessons imported by import_app_lessons.py (rates, counts and effects only): facts and shared items."""
    n = 0
    for p in sorted(APP_LESSONS.glob("*.json")) if APP_LESSONS.is_dir() else []:
        s = json.loads(p.read_text(encoding="utf-8"))
        if s.get("kind") != "iraalgo-app-lessons":
            continue
        day = s.get("day", "")
        src = f"research/app_lessons/{p.name}"
        for l in s.get("lessons", []):
            if l.get("confidence") not in ("strong", "building") or not l.get("n"):
                continue
            word = "held" if l.get("sign", 0) > 0 else "did not hold"
            text = f"On the phone ({l.get('topic', '')}): {l['name']} {word} - {l.get('hits', 0)} of {l['n']} ({l['confidence']}, {l.get('first', '')} to {l.get('last', '')})."
            topics = sorted(set([t for t in l.get("tags", []) if t] + tags(l["name"])))
            verdict = ("positive" if l.get("sign", 0) > 0 else "negative") if l["confidence"] == "strong" else "weak"
            pack.add(id_=f"app.{day}.{slug(l.get('id', l['name']), 40)}", kind="app_lesson", title="The phone's own lessons", text=text,
                     src=src, sec=f"app lessons {day}", date_=day, insts=instruments(l["name"]), tags_=topics, verdict=verdict,
                     conf="high" if l["confidence"] == "strong" else "medium", weight=1.5, personal=False)
            shared.append(shared_item(id_=f"app:{l.get('id', slug(l['name']))}", topics=topics, text=text, src=src, source="app",
                                      date_=day, verdict=verdict, gate="pass" if verdict == "positive" else ("fail" if verdict == "negative" else "none"),
                                      n=int(l["n"]), effect=l.get("effect"), inst=(instruments(l["name"]) or [None])[0]))
            n += 1
    return n


def add_open_questions(pack: Pack, shared: list[dict]) -> list[str]:
    path = RESEARCH / "knowledge/open_questions.yaml"
    cards = yaml.safe_load(path.read_text(encoding="utf-8")) or []
    errors = checked(cards, path, {}, "open question")
    # Dated by the file's own header ("13 Oct 2026"), not its commit: a rebuild after committing gives the same pack.
    written = parse_date(" ".join(path.read_text(encoding="utf-8").splitlines()[:3]))
    for c in cards:
        text = clean_md(c["text"])
        topics = sorted(set(c.get("topics", []) + tags(text)))
        pack.add(id_=f"open.{slug(c['id'], 40)}", kind="open", title="Open questions (not settled yet)", text=text, src=c["src"],
                 sec=c.get("sec", ""), date_=written, insts=[c["inst"]] if c.get("inst") else instruments(text),
                 tags_=topics, verdict="weak", conf="medium", weight=2.0, personal=False)
        shared.append(shared_item(id_=f"open:{c['id']}", topics=topics, text=text, src=c["src"], source="open",
                                  date_=written, verdict="open", gate="open", arm=c.get("arm"),
                                  inst=c.get("inst"), exec_=c.get("exec"), sec=c.get("sec", "")))
    return errors


INDEX_FACT_KINDS = ("descriptive", "rule", "contract")
# One title per kind of index fact, naming what the facts answer (the retrieval boosts a fact whose title holds the question's
# words): the behaviour facts, the sign rules R102 tested, and the contract facts (lot size, expiry day, hours, charges).
INDEX_FACT_TITLES = {
    "descriptive": "How NIFTY and BANKNIFTY behave (R102 field guide)",
    "rule": "NIFTY and BANKNIFTY sign rules tested (R102 field guide)",
    "contract": "NIFTY and BANKNIFTY contract facts: lot size, expiry day, strikes, hours, charges (R102 field guide)",
}


def add_index_facts(pack: Pack, shared: list[dict]) -> list[str]:
    """R102's facts file (22 Oct 2026): every entry needs id, inst (NIFTY / BANKNIFTY / BOTH), kind (descriptive / rule /
    contract), n (sample size > 0), period, text, src and sec; `topics`, `tod`, `verdict` (rules only) are optional.
    Numbers in the text are checked against the cited source. Returns the errors (an empty list when the file is absent)."""
    path = RESEARCH / "knowledge/index_facts.yaml"
    if not path.is_file():
        return []
    cards = yaml.safe_load(path.read_text(encoding="utf-8")) or []
    errors = checked(cards, path, {}, "index fact")
    written = parse_date(" ".join(path.read_text(encoding="utf-8").splitlines()[:3]))
    seen: set[str] = set()
    for c in cards:
        cid = str(c.get("id", ""))
        if not cid or cid in seen:
            errors.append(f"index fact {cid or '?'}: missing or duplicate id")
            continue
        seen.add(cid)
        if c.get("inst") not in ("NIFTY", "BANKNIFTY", "BOTH"):
            errors.append(f"index fact {cid}: inst must be NIFTY, BANKNIFTY or BOTH")
        if c.get("kind") not in INDEX_FACT_KINDS:
            errors.append(f"index fact {cid}: kind must be one of {INDEX_FACT_KINDS}")
        n = c.get("n")
        if not isinstance(n, int) or isinstance(n, bool) or n <= 0:
            errors.append(f"index fact {cid}: n must be a positive integer (the sample size)")
            continue
        if not c.get("period") or not c.get("sec"):
            errors.append(f"index fact {cid}: period and sec are required")
        text = clean_md(f"{c.get('text', '')} (n = {n:,}; {c.get('period', '')}.)")
        topics = sorted(set(c.get("topics", []) + tags(text)))
        insts = ["NIFTY", "BANKNIFTY"] if c.get("inst") == "BOTH" else [c.get("inst")]
        # Plus the markets the text itself names (a fact about VIX and the indices' range is found when Boss asks about VIX).
        insts += [i for i in instruments(text) if i not in insts]
        if c.get("kind") == "rule":
            verdict = c.get("verdict", "weak")
            if verdict not in ("positive", "weak", "negative"):
                errors.append(f"index fact {cid}: a rule needs verdict positive / weak / negative")
        else:
            verdict = "context"
        # A contract fact (lot size, expiry day, hours) is verified against the exchange, not sampled: high whatever its n.
        conf = "high" if n >= 100 or c.get("kind") == "contract" else "medium"
        pack.add(id_=f"index.{slug(cid, 40)}", kind="index_fact", title=INDEX_FACT_TITLES.get(c.get("kind"), INDEX_FACT_TITLES["descriptive"]), text=text,
                 src=c["src"], sec=c.get("sec", ""), date_=written, insts=insts, tags_=topics, verdict=verdict, conf=conf,
                 weight=2.5, personal=False, extra={"n": n, "fact_kind": c.get("kind"), "period": c.get("period", "")})
        shared.append(shared_item(id_=f"index:{cid}", topics=topics, text=text, src=c["src"], source="research", date_=written,
                                  verdict=verdict, gate="none" if verdict == "context" else None,
                                  inst=c.get("inst") if c.get("inst") != "BOTH" else None, n=n, sec=c.get("sec", "")))
        if c.get("tod"):
            shared[-1]["tod"] = c["tod"]
    return errors


BOOK_VERDICTS = ("negative", "weak", "positive")


def add_book_facts(pack: Pack) -> list[str]:
    """The books' cards (25 Oct 2026, research/knowledge/book_facts.yaml): `books` maps a short name to its author, title and
    year; each card needs id book.<short>.<n>, book, title, chapter, text and tags (from TAGS); `inst` is optional. Every
    number in the text must be in the book's write-up research/books/<short>.md. A card whose idea our research tested sets
    verdict to that test's result (negative / weak / positive) with tested_src (the study) and tested_text (one sentence,
    its numbers checked against the study); every other card is context. Fact index only, never the shared section (a
    book's claim must not crowd out or outrank a tested result): kind book_fact, weight 0.8, low confidence, and the text
    says it is the book's claim. Returns the errors (an empty list when the file is absent)."""
    path = RESEARCH / "knowledge/book_facts.yaml"
    if not path.is_file():
        return []
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    books, cards = data.get("books") or {}, data.get("cards") or []
    errors: list[str] = []
    cache: dict = {}
    written = parse_date(" ".join(path.read_text(encoding="utf-8").splitlines()[:3]))
    seen: set[str] = set()
    for c in cards:
        cid, short = str(c.get("id", "")), str(c.get("book", ""))
        if not re.fullmatch(r"book\.[a-z0-9-]+\.\d+", cid) or cid in seen:
            errors.append(f"book fact {cid or '?'}: id must be book.<short>.<n> and unique")
            continue
        seen.add(cid)
        meta = books.get(short)
        if not meta or cid.split(".")[1] != short:
            errors.append(f"book fact {cid}: book {short!r} not in books, or not the id's book")
            continue
        src = f"research/books/{short}.md"
        if not c.get("title") or not c.get("chapter") or not c.get("text"):
            errors.append(f"book fact {cid}: title, chapter and text are required")
            continue
        bad = [t for t in c.get("tags") or [] if t not in TAGS]
        if bad:
            errors.append(f"book fact {cid}: unknown tags {bad}")
        errors += checked([{"id": cid, "src": src, "text": f"{c['title']} {c['text']}"}], path, cache, "book fact")
        verdict = c.get("verdict", "context")
        tested = ""
        if verdict != "context":
            if verdict not in BOOK_VERDICTS or not c.get("tested_src") or not c.get("tested_text"):
                errors.append(f"book fact {cid}: a tested card needs verdict {BOOK_VERDICTS}, tested_src and tested_text")
                continue
            errors += checked([{"id": cid, "src": c["tested_src"], "text": c["tested_text"]}], path, cache, "book fact (tested)")
            tested = f" Our research tested it: {c['tested_text']}"
        author = meta.get("author", short)
        text = clean_md(f"{author}'s book says (the book's claim, not our tested result): {c['text']}{tested}")
        insts = canon_instruments(c.get("inst") or [])
        insts += [i for i in instruments(c["text"]) if i not in insts]
        pack.add(id_=cid, kind="book_fact", title=f"{author}, {meta.get('title', short)}: {c['title']}", text=text, src=src,
                 sec=str(c["chapter"]), date_=written, insts=insts, tags_=sorted(set((c.get("tags") or []) + tags(c["text"]))),
                 verdict=verdict, conf="low", weight=0.8, personal=False,
                 extra={"book": short, "tested_src": c["tested_src"]} if tested else {"book": short})
    return errors


def baselines() -> tuple[dict, list[str]]:
    path = RESEARCH / "knowledge/baselines.yaml"
    b = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    cache: dict = {}
    spreads = b.get("spreads", [])
    trips = b.get("round_trips", [])
    mult = b.get("signal_multiplier") or {}
    crypto = b.get("crypto_round_trips", [])
    errors = checked(spreads, path, cache, "baseline") + checked(trips, path, cache, "round trip") + checked([mult], path, cache, "multiplier")
    errors += checked(crypto, path, cache, "crypto round trip")
    for x in spreads:
        if str(x["half_spread_pct"]) not in source_numbers(x["src"], cache):
            errors.append(f"baseline {x['inst']}: {x['half_spread_pct']} not in {x['src']}")
    d = git_date(ROOT / "research/HUNT_H24.md", first=False) or ""
    out = {
        "spreads": [{"i": x["inst"], "hs": x["half_spread_pct"], "n": x.get("n", 1), "s": x["src"], "sec": x["sec"], "x": clean_md(x["text"]), "d": d}
                    for x in spreads],
        "signal_x": mult.get("value", 1), "signal_text": clean_md(mult.get("text", "")), "signal_src": mult.get("src", ""),
        "round_trips": [{"i": x["inst"], "rs": x["rs"], "s": x["src"], "x": clean_md(x["text"])} for x in trips],
        # crypto costs are in bps of notional, so they get their own key (round_trips is read as rupees) - 13 Oct 2026
        "crypto_round_trips": [{"i": x["inst"], "venue": x["venue"], "bps": x["bps"], "s": x["src"], "x": clean_md(x["text"])}
                               for x in crypto],
    }
    return out, errors


def shared_from_facts(facts: list[dict]) -> list[dict]:
    """The findings' and strategy cards' facts as shared items (the verdict-bearing ones only: context stays in the index)."""
    out = []
    for f in facts:
        if f["kind"] not in ("finding", "strategy") or f["verdict"] == "context" or f.get("personal"):
            continue
        arm = STRATEGY_ARMS.get(f.get("strategy", "")) if f["kind"] == "strategy" else None
        if f["kind"] == "strategy" and not arm:
            arm = STRATEGY_ARMS.get(f["id"].split(".")[1], None)
        out.append(shared_item(id_=f["id"], topics=f["tags"], text=f["text"], src=f["src"], source="research", date_=f["date"],
                               verdict=f["verdict"], arm=arm, inst=f["inst"][0] if len(f["inst"]) == 1 else None, sec=f["sec"]))
    return out


def research_docs() -> list[Path]:
    docs = sorted(p for p in RESEARCH.glob("*.md") if p.name != "FINDINGS.md")
    return [p for p in docs if rel(p) not in SKIP_DOCS]


def build() -> tuple[dict, list[str]]:
    pack = Pack()
    add_markdown(pack, RESEARCH / "FINDINGS.md", findings=True)
    add_strategies_errors = add_strategies(pack)
    add_study_pages(pack)
    for p in research_docs():
        add_markdown(pack, p)
    add_h42(pack)
    model_cards = add_model_cards(pack)
    # The shared section, in the order the phone's readers walk it (25 Oct 2026): the findings and cards with a verdict, the
    # curated open questions (the first open item on a topic is the one an agent cites: "S7: order flow is still unproven",
    # never an experiment that happened to be filed first), the index facts, the experiment log, the phone's own lessons.
    shared: list[dict] = []
    exp_items: list[dict] = []
    exp_entries: list[dict] = []
    experiments = add_experiments(pack, exp_items, exp_entries)
    app_items: list[dict] = []
    app_lessons = add_app_lessons(pack, app_items)
    add_strategies_errors += add_open_questions(pack, shared)
    add_strategies_errors += add_index_facts(pack, shared)
    add_strategies_errors += add_book_facts(pack)
    base, base_errors = baselines()
    add_strategies_errors += base_errors
    # Exact duplicates (the same sentence copied into several docs): the first (highest-weight source) is kept.
    seen, facts = set(), []
    for f in pack.facts:
        key = re.sub(r"\W+", " ", f["text"].lower()).strip()
        if key in seen:
            continue
        seen.add(key); facts.append(f)
    shared = shared_from_facts(facts) + shared
    shared += select_experiments(exp_entries, exp_items, SHARED_CAP - len(shared) - len(app_items)) + app_items
    seen_ids, items = set(), []
    for it in shared:
        if it["id"] in seen_ids:
            continue
        seen_ids.add(it["id"]); items.append(it)
    if len(items) > SHARED_CAP:  # the passed and open entries and the phone's lessons are never cut: say so instead
        add_strategies_errors.append(f"shared section: {len(items)} items pass SHARED_CAP {SHARED_CAP} (every passed / open entry and "
                                     f"app lesson is kept; raise the cap on purpose or settle some open entries)")
    shared_section = {"items": items, "baselines": base}
    body = json.dumps([facts, shared_section], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    version = hashlib.sha256(body.encode()).hexdigest()[:12]
    dates = [f["date"] for f in facts if f["date"]]
    kinds: dict[str, int] = {}
    for f in facts:
        kinds[f["kind"]] = kinds.get(f["kind"], 0) + 1
    out = {
        "schema": SCHEMA,
        "version": version,
        "built_from": max(dates) if dates else "",
        "counts": kinds,
        "model_cards": model_cards,
        "experiments": experiments,
        "app_lessons": app_lessons,
        "shared_items": len(items),
        "sources": sorted({f["src"] for f in facts}),
        "facts": facts,
        "shared": shared_section,
    }
    return out, add_strategies_errors


def encode(pack: dict) -> bytes:
    raw = json.dumps(pack, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0, filename="") as gz:
        gz.write(raw)
    return buf.getvalue()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="fail when the committed pack differs from a fresh build")
    args = ap.parse_args()
    pack, errors = build()
    if errors:
        print("cards with numbers not found in their sources:\n  " + "\n  ".join(errors), file=sys.stderr)
        return 1
    if UNKNOWN_INSTRUMENTS:
        print("note: entry instrument names of no pack market (kept in the text only): " + ", ".join(sorted(UNKNOWN_INSTRUMENTS)), file=sys.stderr)
    data = encode(pack)
    if len(data) > MAX_BYTES:
        print(f"pack too big: {len(data)} bytes", file=sys.stderr)
        return 1
    meta = {k: pack[k] for k in ("schema", "version", "built_from", "counts", "model_cards", "experiments", "app_lessons", "shared_items")}
    meta["facts"] = len(pack["facts"])
    meta["bytes"] = len(data)
    meta_text = json.dumps(meta, indent=2, sort_keys=True) + "\n"
    if args.check:
        same = OUT.is_file() and OUT.read_bytes() == data
        print("pack up to date" if same else "pack out of date: run research/knowledge/build_pack.py")
        return 0 if same else 1
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    META.write_text(meta_text, encoding="utf-8")
    print(f"{len(pack['facts'])} facts, {len(data):,} bytes gzip, version {pack['version']}, kinds {pack['counts']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
