#!/usr/bin/env python3
"""Tests for build_pack.py's ranking and tagging rules (25 Oct 2026).

    python -m pytest research/knowledge/test_build_pack.py -q

What is under test: the pack's instrument names (an entry's GOLDM is the pack's GOLD), the experiment log's topics (the idea's
words, not the method's), the MCX bots' arm, the shared section's cap and order (one item per idea family, the curated open
questions before the experiments), the h42 answers' titles and the R102 contract facts' confidence. The whole pack is built
once at the end and read the way ira-core's SharedKnowledge reads it.
"""
from __future__ import annotations

import sys
import textwrap
from pathlib import Path

import pytest
import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build_pack as bp  # noqa: E402


# ---- instrument names ------------------------------------------------------------------------------------------------

def test_an_entry_names_its_contracts_and_the_pack_names_its_markets():
    assert bp.canon_instruments(["GOLDM", "SILVERM", "CRUDEOILM", "NATGASMINI"]) == ["GOLD", "SILVER", "CRUDE", "NATURALGAS"]
    assert bp.canon_instruments(["GOLD, SILVER (daily continuous; GOLDM / SILVERM lot)"]) == ["GOLD", "SILVER"]
    assert bp.canon_instruments(["CRUDEOIL/CRUDEOILM", "NATURALGAS/NATGASMINI", "XAUUSD"]) == ["CRUDE", "NATURALGAS", "GOLD"]


def test_known_names_stay_unknown_ones_are_dropped_and_each_comes_once():
    assert bp.canon_instruments(["BANKNIFTY", "F&O stocks", "NIFTYNXT50", "COPPER", "the same 6 books", 7, None]) == \
        ["BANKNIFTY", "F&O stocks", "NIFTYNXT50"]
    assert bp.canon_instruments(["GOLD", "GOLDM", "GOLD/GOLDM"]) == ["GOLD"]
    assert bp.canon_instruments([]) == []
    assert set(bp.canon_instruments(["GOLDM", "SILVER", "CRUDEOILM", "NATGASMINI", "NIFTY"])) <= bp.KNOWN_INSTRUMENTS


def test_the_mini_contracts_are_read_in_prose_too():
    assert bp.instruments("CRUDEOILM and NATGASMINI books") == ["CRUDE", "NATURALGAS"]
    assert bp.instruments("crude oil at the NYMEX open") == ["CRUDE"]
    assert bp.instruments("natural gas, natgas") == ["NATURALGAS"]


# ---- topics ----------------------------------------------------------------------------------------------------------

def test_non_directional_is_not_about_direction():
    assert "direction" not in bp.tags("MCX iron condor, a non-directional selling structure")
    assert "direction" not in bp.tags("non directional premium selling")
    assert "direction" in bp.tags("the brain forecasts the direction over the next hour")
    assert "direction" in bp.tags("up or down in the first 15 minutes")


# ---- the experiment log's rank and families --------------------------------------------------------------------------

def entry(id_: str, verdict: str = "rejected", date: str = "2026-10-20", hypothesis: str = "it pays", doc: str = "research/X.md",
          family: str = "orb", instruments: list | None = None) -> dict:
    return {"id": id_, "title": id_, "verdict": verdict, "date": date, "hypothesis": hypothesis, "doc": doc, "family": family,
            "instruments": instruments or ["NIFTY"]}


def item(id_: str, arm: str | None = None, date: str = "2026-10-20", n: int | None = None) -> dict:
    it = {"id": f"exp:{id_}", "d": date}
    if arm:
        it["a"] = arm
    if n:
        it["n"] = n
    return it


def test_rank_passed_or_open_then_arm_then_newest_then_largest_then_id():
    passed = bp.exp_rank(entry("a", "paper-only"), item("a"))
    open_ = bp.exp_rank(entry("b", "inconclusive", date="2020-01-01"), item("b", date="2020-01-01"))
    armed = bp.exp_rank(entry("c"), item("c", arm="orb", date="2026-01-01"))
    newest = bp.exp_rank(entry("d"), item("d", date="2026-10-21"))
    bigger = bp.exp_rank(entry("e"), item("e", date="2026-10-20", n=500))
    smaller = bp.exp_rank(entry("f"), item("f", date="2026-10-20", n=50))
    same_but_later_id = bp.exp_rank(entry("g"), item("g", date="2026-10-20", n=50))
    undated = bp.exp_rank(entry("h"), item("h", date=""))
    assert sorted([undated, same_but_later_id, smaller, bigger, newest, armed, open_, passed]) == \
        [passed, open_, armed, newest, bigger, smaller, same_but_later_id, undated]


def test_a_sweeps_cells_share_one_family_but_verdicts_arms_docs_and_markets_do_not():
    a = bp.exp_family(entry("r51-1", hypothesis="fade a gap of at least 0.3% / 0.6%"), item("r51-1"))
    b = bp.exp_family(entry("r51-2", hypothesis="Fade a gap of at least 0.5% / 1.0%."), item("r51-2"))
    assert a == b
    # The same rule on another market is another result; the contracts' spellings do not matter (GOLDM is GOLD).
    assert bp.exp_family(entry("of1-r2", hypothesis="fade a gap of at least 0.3%", instruments=["BANKNIFTY"]), item("of1-r2")) != a
    assert bp.exp_family(entry("g1", hypothesis="x", instruments=["GOLDM"]), item("g1")) == \
        bp.exp_family(entry("g2", hypothesis="x", instruments=["GOLD"]), item("g2"))
    assert bp.exp_family(entry("r51-3", "adopted", hypothesis="fade a gap of at least 0.3%"), item("r51-3")) != a
    assert bp.exp_family(entry("r51-4", hypothesis="fade a gap of at least 0.3%"), item("r51-4", arm="orb")) != a
    assert bp.exp_family(entry("r51-5", hypothesis="fade a gap of at least 0.3%", doc="research/Y.md"), item("r51-5")) != a


def test_selection_keeps_one_per_family_the_passed_and_open_first_within_the_room_in_entry_order():
    cands = [
        (entry("r51-01", hypothesis="rule pays at 90th pct"), item("r51-01", n=10)),
        (entry("r51-02", hypothesis="rule pays at 95th pct"), item("r51-02", n=900)),   # the family's best (largest n)
        (entry("r51-03", hypothesis="rule pays at 99th pct"), item("r51-03", n=20)),
        (entry("r14", "adopted", hypothesis="cap re-entries", doc="research/R14.md"), item("r14", arm="orb")),
        (entry("r86", "inconclusive", hypothesis="not run", doc="research/R86.md"), item("r86")),
        (entry("r83", hypothesis="orb on gas", doc="research/R83.md"), item("r83", arm="mcx", date="2026-10-19", n=540)),
        (entry("r92", hypothesis="orb by hour", doc="research/R92.md"), item("r92", arm="orb", date="2026-10-21", n=100)),
    ]
    cands += [  # two paper-only cells of one family: both kept (a passed or open entry is never folded into another)
        (entry("r66-l25", "paper-only", hypothesis="solo2 lock at 25", doc="research/R66.md"), item("r66-l25", arm="solo")),
        (entry("r66-l30", "paper-only", hypothesis="solo2 lock at 30", doc="research/R66.md"), item("r66-l30", arm="solo")),
    ]
    entries, items = [e for e, _ in cands], [it for _, it in cands]
    all_room = bp.select_experiments(entries, items, 100)
    assert [it["id"] for it in all_room] == ["exp:r51-02", "exp:r14", "exp:r86", "exp:r83", "exp:r92", "exp:r66-l25", "exp:r66-l30"]
    # Short of room: the passed and open ones, then the arm-tied newest.
    assert [it["id"] for it in bp.select_experiments(entries, items, 5)] == ["exp:r14", "exp:r86", "exp:r92", "exp:r66-l25", "exp:r66-l30"]
    # No room at all: the passed and open ones are still kept (build() then fails loudly on the cap, never cuts them).
    assert [it["id"] for it in bp.select_experiments(entries, items, 0)] == ["exp:r14", "exp:r86", "exp:r66-l25", "exp:r66-l30"]
    assert bp.select_experiments([], [], 5) == []


# ---- the entries as facts and shared items ---------------------------------------------------------------------------

def write_entry(d: Path, **e):
    (d / f"{e['id']}.yaml").write_text(yaml.safe_dump(e), encoding="utf-8")


def test_an_entry_gets_the_ideas_topics_its_studys_doc_the_mcx_arm_and_the_packs_names(tmp_path, monkeypatch):
    monkeypatch.setattr(bp, "EXPERIMENTS", tmp_path)
    write_entry(tmp_path, id="r83-e6-ng", title="R83: US-open ORB on natural gas with trend agreement", family="mcx-crypto",
                date="2026-10-18", doc="research/FINDINGS.md", verdict="rejected", instruments=["NATURALGAS", "NATGASMINI"],
                hypothesis="The rule makes money after real costs on the holdout with BH q <= 0.1.",
                results={"summary": "540 trades, net Rs -74,598."}, holdout_read=True)
    write_entry(tmp_path, id="r14", title="ORB re-entry churn: cap or let it run", family="orb", date="2026-10-10",
                doc="research/no-such-doc.md", verdict="adopted", instruments=["BANKNIFTY"],
                hypothesis="Capping re-entries cuts ORB's loss.", results={"summary": "Cap 1 a side: loss cut."}, holdout_read=True)
    write_entry(tmp_path, id="rc1-a1", title="CoinSwitch costs for the range rule", family="mcx-crypto", date="2026-10-12", doc="research/FINDINGS.md",
                verdict="inconclusive", instruments=["CRYPTO"], hypothesis="The venue's fees leave the range rule's edge intact.", results={"summary": "design only"})
    write_entry(tmp_path, id="r82-gold-spike", title="R82: a silver spike at the MCX open", family="mcx-crypto", date="2026-10-18",
                doc="research/FINDINGS.md", verdict="rejected", instruments=["GOLDM", "SILVERM", "COPPER"],
                hypothesis="The first bar's spike reverses by at least 50 trades a month.", results={"summary": "212 trades, net Rs -3."})
    pack, shared, entries = bp.Pack(), [], []
    assert bp.add_experiments(pack, shared, entries) == 4
    assert [e["id"] for e in entries] == ["r14", "r82-gold-spike", "r83-e6-ng", "rc1-a1"]
    assert [it["id"] for it in shared] == ["exp:r14", "exp:r82-gold-spike", "exp:r83-e6-ng", "exp:rc1-a1"]
    facts = {f["id"]: f for f in pack.facts}
    items = {it["id"]: it for it in shared}
    gas, r14, crypto, spike = facts["exp.r83-e6-ng"], facts["exp.r14"], facts["exp.rc1-a1"], facts["exp.r82-gold-spike"]
    # The pack's names, the study's doc as the source, the idea's topics (no costs / holdout / multiple-testing from the method).
    assert gas["inst"] == ["NATURALGAS"] and gas["src"] == "research/FINDINGS.md" and gas["w"] == 2.0 and gas["conf"] == "high"
    assert {"orb", "trend", "mcx"} <= set(gas["tags"]) and not {"costs", "holdout", "multiple-testing"} & set(gas["tags"])
    # The arm its words name (ORB), the one market, the failed gate, and the sample from the results (540 trades).
    assert items["exp:r83-e6-ng"]["a"] == "orb" and items["exp:r83-e6-ng"]["i"] == "NATURALGAS" and items["exp:r83-e6-ng"]["g"] == "fail"
    assert items["exp:r83-e6-ng"]["n"] == 540
    # An MCX-only entry naming no strategy is the MCX bots'; COPPER is no pack market (kept in the text, noted by the build);
    # a "50 trades" bar in the hypothesis is not its sample size.
    assert spike["inst"] == ["GOLD", "SILVER"] and items["exp:r82-gold-spike"]["a"] == "mcx" and "i" not in items["exp:r82-gold-spike"]
    assert items["exp:r82-gold-spike"]["n"] == 212 and "COPPER" in bp.UNKNOWN_INSTRUMENTS
    # A doc that is not in the repo and an entry file outside it (this temp dir): the file's name is cited, never a machine path.
    assert r14["src"] == "r14.yaml" and items["exp:r14"]["a"] == "orb" and items["exp:r14"]["g"] == "pass" and "n" not in items["exp:r14"]
    assert "reentry" in r14["tags"] and "costs" not in r14["tags"]
    # The title's own "costs" is the idea's (an entry about costs keeps the topic); a crypto-only entry is about crypto whatever
    # its title says, and nobody's arm: its shared item carries the crypto topic alone (the fact keeps every tag for Jarvis).
    assert {"costs", "crypto", "mcx"} <= set(crypto["tags"]) and crypto["inst"] == ["CRYPTO"]
    assert items["exp:rc1-a1"]["t"] == ["crypto"] and items["exp:rc1-a1"]["i"] == "CRYPTO" and items["exp:rc1-a1"].get("a") in (None, "")
    assert items["exp:rc1-a1"]["g"] == "open" and crypto["verdict"] == "weak"


# ---- the h42 answers and the index facts -----------------------------------------------------------------------------

def test_h42_answers_are_titled_by_index_and_category_and_weigh_like_a_verdict_section():
    pack = bp.Pack()
    bp.add_h42(pack)
    facts = {f["id"]: f for f in pack.facts}
    assert len(facts) == 1296
    q76 = facts["h42.q0076"]
    assert q76["title"] == "How BANKNIFTY behaves: gaps (h42)" and q76["w"] == bp.H42_WEIGHT == 2.0
    assert q76["inst"] == ["BANKNIFTY"] and "gap" in q76["tags"]
    assert all(f["title"].startswith("How ") and f["title"].endswith("(h42)") for f in facts.values())
    assert all(cat in bp.H42_CAT_WORDS for f in facts.values() for cat in [f["sec"].split("(")[-1].rstrip(")")])


def test_index_facts_are_titled_by_kind_name_the_markets_their_text_names_and_a_contract_fact_is_high(tmp_path, monkeypatch):
    research = tmp_path / "research"
    (research / "knowledge").mkdir(parents=True)
    (research / "knowledge/index_facts.yaml").write_text(textwrap.dedent("""
        # 22 Oct 2026
        - id: vix-dial
          inst: BOTH
          kind: descriptive
          n: 5
          period: "two decades"
          text: "VIX is the range dial, not a direction dial, for NIFTY and BANKNIFTY."
          src: research/FINDINGS.md
          sec: "VIX"
        - id: lots
          inst: NIFTY
          kind: contract
          n: 1
          period: "verified"
          text: "Lot sizes: NIFTY one lot, BANKNIFTY another."
          src: research/FINDINGS.md
          sec: "Contracts"
        - id: momentum
          inst: BANKNIFTY
          kind: rule
          verdict: negative
          n: 500
          period: "two decades"
          text: "Following yesterday's sign made nothing on BANKNIFTY."
          src: research/FINDINGS.md
          sec: "Momentum"
    """), encoding="utf-8")
    monkeypatch.setattr(bp, "RESEARCH", research)
    pack, shared = bp.Pack(), []
    assert bp.add_index_facts(pack, shared) == []
    facts = {f["id"]: f for f in pack.facts}
    vix, lots, mom = facts["index.vix-dial"], facts["index.lots"], facts["index.momentum"]
    assert vix["inst"] == ["NIFTY", "BANKNIFTY", "INDIAVIX"] and vix["title"] == bp.INDEX_FACT_TITLES["descriptive"] and vix["conf"] == "medium"
    assert lots["inst"] == ["NIFTY", "BANKNIFTY"] and lots["title"] == bp.INDEX_FACT_TITLES["contract"] and lots["conf"] == "high"
    assert mom["title"] == bp.INDEX_FACT_TITLES["rule"] and mom["conf"] == "high" and mom["verdict"] == "negative"
    assert [it["id"] for it in shared] == ["index:vix-dial", "index:lots", "index:momentum"]


# ---- the whole pack, read as the phone reads it ----------------------------------------------------------------------

@pytest.fixture(scope="module")
def built():
    pack, errors = bp.build()
    assert errors == [], errors
    return pack


def test_the_shared_section_is_capped_ordered_and_keeps_what_the_agents_cite(built):
    items = built["shared"]["items"]
    kinds = [it["k"] for it in items]
    assert 100 <= len(items) <= bp.SHARED_CAP <= 2_000
    # The order the phone's readers walk: research, the curated open questions, the index facts, then the experiments.
    first = {k: kinds.index(k) for k in ("research", "open", "experiment")}
    assert first["research"] < first["open"] < first["experiment"]
    assert max(i for i, k in enumerate(kinds) if k == "open") < first["experiment"]
    by_id = {it["id"]: it for it in items}
    assert by_id["exp:r14"]["g"] == "pass" and by_id["open:s7-order-flow"]["g"] == "open"
    # Every passed or open entry is in; the fact index keeps every entry (the rejected cells of a sweep are one item here).
    exp_facts = {f["exp"]: f for f in built["facts"] if f["kind"] == "experiment"}
    kept = {it["id"].split(":", 1)[1] for it in items if it["k"] == "experiment"}
    assert kept <= set(exp_facts)
    always = {e["id"] for e in (yaml.safe_load(p.read_text(encoding="utf-8")) for p in bp.EXPERIMENTS.glob("*.yaml"))
              if e.get("verdict") in bp.EXP_ALWAYS}
    assert always and always <= kept
    assert len(exp_facts) == built["experiments"] > len(kept)
    # The first open item on order flow is the S7 question, so the flow verdict cites it (SharedKnowledge.flowVerdict).
    flow = [it for it in items if ("order-flow" in it["t"] or "traps" in it["t"]) and it["k"] != "app" and it["g"] == "open"]
    assert flow and flow[0]["id"] == "open:s7-order-flow"
    # No open item claims the "direction" topic, so the study keeps skipping what the research settled (f15up / f15down).
    assert not [it for it in items if it["g"] == "open" and it["k"] != "app" and "direction" in it["t"]]


def test_every_fact_carries_a_known_instrument_name(built):
    for f in built["facts"]:
        assert set(f["inst"]) <= bp.KNOWN_INSTRUMENTS, (f["id"], f["inst"])
        assert 20 <= len(f["text"]) <= 1_200, f["id"]


def test_the_order_review_cites_r14_for_an_orb_charges_flaw(built):
    """SharedKnowledge.flawCite("chg", "ORB"): the best decisive item on reentry / costs tied to the ORB arm, ranked by sample size."""
    items = built["shared"]["items"]
    pool = [it for it in items if ({"reentry", "costs"} & set(it["t"])) and it["k"] in ("research", "experiment") and it["g"] != "none"
            and it.get("a") == "orb"]
    best = sorted(pool, key=lambda it: (-(it.get("n") or 0), it["id"]))[0]
    assert best["id"] == "exp:r14", [it["id"] for it in pool[:5]]
