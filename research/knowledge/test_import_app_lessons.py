#!/usr/bin/env python3
"""Tests for import_app_lessons.py and the pack's shared section (13 Oct 2026).

    python research/knowledge/test_import_app_lessons.py        # plain unittest; pytest runs it too

The rule under test: nothing of Boss's money reaches the repo - only rates, counts and effects survive an import - and the
imported lessons reach the pack (facts and shared-knowledge items) so the next APK carries them back to Jarvis and the agents.
"""
from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "experiments"))

import build_pack  # noqa: E402
import import_app_lessons as imp  # noqa: E402
import validate  # noqa: E402

# Distinctive money figures: none may appear anywhere in what is written.
SECRET_FIGURES = ["48213", "48,213", "-7391", "7391", "123456", "98765.4"]


def bundle(day: str = "2026-10-13") -> dict:
    return {
        "schema": 1, "kind": "iraalgo-lessons-bundle", "day": day, "app": "1.0 (test)", "pack_version": "abc123",
        "lessons": [
            {"id": "strat:orb", "topic": "STRATEGY", "kind": "RUPEES", "name": "ORB", "tags": ["orb"], "agents": ["the bot trades"],
             "n": 40, "hits": 22, "sign": 1, "confidence": "strong", "status": "confirmed", "q": 0.04, "first": "2026-09-01",
             "last": "2026-10-13", "rs": 48213.0},
            {"id": "lab:entry:vwap_reclaim", "topic": "LAB", "kind": "RATE", "name": "Ideas using VWAP reclaim", "tags": ["lab", "vwap"],
             "agents": ["the strategy lab"], "n": 36, "hits": 4, "sign": -1, "confidence": "strong", "status": "confirmed",
             "first": "2026-09-20", "last": "2026-10-13", "net": -7391},
            {"id": "x:1", "topic": "ORDERS", "kind": "RATE", "name": "Lost Rs 48,213 on ORB", "n": 5, "hits": 5, "sign": -1,
             "confidence": "weak"},
        ],
        "findings": [{"kind": "DATA_HEALTH", "who": "data health", "count": 12, "days": 9}],
        "lab": {"forge": {"tried": 140, "passed": 3, "rejected": 137, "net": 123456},
                "ideas": [{"id": "F141", "entry": "VWAP_RECLAIM", "filters": ["BUSY_ONLY"], "exit": "FIXED", "underlying": "BANKNIFTY",
                           "stage": "REJECTED", "gate_failed": "random twin", "trades": 52, "win_rate": 0.42, "twin_p": 0.31, "net": -98765.4}],
                "skipped": [{"idea": "plain ORB break", "why": "the research rejected it (ORB lost in both tested years)"}],
                "variants": [{"strategy": "orb", "status": "running", "n": 12, "better": True, "pnl": 5000}]},
        "orders": {"patterns": [{"strategy": "ORB", "venue": "paper", "category": "charges ate the day's gross", "count": 6, "days": 4,
                                 "rupees": 7391}]},
        "votes": [{"family": "orb", "specialist": "traps", "settled": 30, "helped": 12, "rs": 48213}],
        "shadow": [{"rule": "research verdict fails", "arm": "orb", "would_skip": 9, "trades": 9, "saved": 7391}],
        "money_enc": "QUJDREVGR0g=",
        "money": {"lessons": {"strat:orb": 48213.0}, "ideas": {"F141": -98765.4}},
    }


class ImportRuleTest(unittest.TestCase):
    def test_summary_keeps_only_rates_counts_and_effects(self):
        s = imp.summarize(bundle())
        text = json.dumps(s)
        self.assertEqual([], imp.money_free(s))
        for f in SECRET_FIGURES:
            self.assertNotIn(f, text, f"money figure {f} leaked")
        for k in ('"money"', '"money_enc"', '"rs"', '"net"', '"pnl"', '"rupees"', '"saved"'):
            self.assertNotIn(k, text)
        orb = next(l for l in s["lessons"] if l["id"] == "strat:orb")
        self.assertEqual((40, 22), (orb["n"], orb["hits"]))
        self.assertAlmostEqual(0.55, orb["rate"])
        self.assertAlmostEqual(0.05, orb["effect"])
        # A lesson whose words quote rupees is dropped whole.
        self.assertFalse(any("Lost" in l["name"] for l in s["lessons"]))

    def test_written_files_hold_no_money_and_entries_validate(self):
        with tempfile.TemporaryDirectory() as d:
            out, entries = Path(d) / "app_lessons", Path(d) / "entries"
            written = imp.write(imp.summarize(bundle()), out=out, entries=entries)
            self.assertTrue((out / "2026-10-13.json").is_file())
            self.assertTrue((out / "2026-10-13.md").is_file())
            for p in written:
                text = p.read_text(encoding="utf-8")
                for f in SECRET_FIGURES:
                    self.assertNotIn(f, text, f"{p.name} leaked {f}")
                self.assertNotRegex(text, r"(?i)\brs\.?\s*\d|₹")
            ys = sorted(entries.glob("*.yaml"))
            self.assertEqual(["app-lab-lab-entry-vwap-reclaim.yaml"], [p.name for p in ys])
            # The entry is a valid experiment-log entry (its doc path is checked against the repo, so point it at one).
            import yaml
            e = yaml.safe_load(ys[0].read_text(encoding="utf-8"))
            self.assertEqual("rejected", e["verdict"])
            self.assertEqual(36, e["variants_tried"])
            e["doc"] = "research/FINDINGS.md"
            p = Path(d) / f"{e['id']}.yaml"
            p.write_text(yaml.safe_dump(e, sort_keys=False), encoding="utf-8")
            self.assertEqual([], validate.check(p, set()))

    def test_bare_rupee_amounts_without_rs_are_never_written(self):
        # 13 Oct review: an amount written without "Rs" or "₹" in a name or a "why" is money too.
        b = bundle()
        b["lessons"] += [
            {"id": "x:2", "topic": "STRATEGY", "kind": "RATE", "name": "ORB lost 48,213 this month", "n": 40, "hits": 20, "sign": -1,
             "confidence": "strong"},
            {"id": "x:3", "topic": "STRATEGY", "kind": "RATE", "name": "Night made 12000 on expiry", "n": 40, "hits": 20, "sign": 1,
             "confidence": "strong"},
            {"id": "x:4", "topic": "STRATEGY", "kind": "RATE", "name": "Solo down 7,391", "n": 40, "hits": 20, "sign": 1,
             "confidence": "building"},
            {"id": "x:5", "topic": "STRATEGY", "kind": "RATE", "name": "Hero: a 48k loss", "n": 40, "hits": 20, "sign": 1,
             "confidence": "building"},
            {"id": "x:6", "topic": "STRATEGY", "kind": "RATE", "name": "Pine lost 98765.4", "n": 40, "hits": 20, "sign": 1,
             "confidence": "building"},
            {"id": "x:7", "topic": "STRATEGY", "kind": "RATE", "name": "Range fade, 1,23,456 rupees", "n": 40, "hits": 20, "sign": 1,
             "confidence": "building"},
        ]
        b["lab"]["skipped"] += [{"idea": "plain ORB break", "why": "it lost 48213 in the forge"},
                                {"idea": "VWAP reclaim", "why": "the variant lost 7,391 net"}]
        s = imp.summarize(b)
        self.assertEqual([], imp.money_free(s))
        names = [l["name"] for l in s["lessons"]]
        self.assertFalse(any(n.startswith(("ORB lost", "Night made", "Solo down", "Hero:", "Pine lost", "Range fade")) for n in names), names)
        with tempfile.TemporaryDirectory() as d:
            for p in imp.write(s, out=Path(d) / "a", entries=Path(d) / "e"):
                text = p.read_text(encoding="utf-8")
                for f in SECRET_FIGURES + ["12000", "1,23,456", "48k"]:
                    self.assertNotIn(f, text, f"{p.name} leaked {f}")
        # Never too eager: percentages, counts and dates in plain words survive.
        for ok in ("a gap down of 0.5% or more", "the gap filled", "Ideas using VWAP reclaim", "Big moves 09:15-10:00", "2026-10-13",
                   "won more often than not", "orb_fresh", "f15up"):
            self.assertEqual(ok, imp.clean_text(ok), ok)
        for bad in ("lost 48,213", "made 12000", "down 5,000", "a 48k day", "net -7391", "₹500", "Rs. 12", "500 rupees"):
            self.assertEqual("", imp.clean_text(bad), bad)

    def test_refuses_a_summary_with_money(self):
        s = imp.summarize(bundle())
        s["lessons"][0]["rs"] = 1.0
        with tempfile.TemporaryDirectory() as d, self.assertRaises(ValueError):
            imp.write(s, out=Path(d), entries=Path(d))

    def test_reads_every_day_from_the_export_zip(self):
        with tempfile.TemporaryDirectory() as d:
            z = Path(d) / "export.zip"
            with zipfile.ZipFile(z, "w") as zf:
                zf.writestr("README.txt", "x")
                zf.writestr("2026-10-12/lessons_bundle.json", json.dumps(bundle("2026-10-12")))
                zf.writestr("2026-10-13/lessons_bundle.json", json.dumps(bundle("2026-10-13")))
                zf.writestr("2026-10-13/market.jsonl.gz", b"")
            self.assertEqual(["2026-10-12", "2026-10-13"], [b["day"] for b in imp.read_bundles(z)])

    def test_rejects_what_is_not_a_bundle(self):
        with self.assertRaises(ValueError):
            imp.summarize({"kind": "something else", "schema": 1, "day": "2026-10-13"})
        with self.assertRaises(ValueError):
            imp.summarize({**bundle(), "day": "../../etc"})


class PackSharedTest(unittest.TestCase):
    def test_imported_lessons_reach_the_pack_as_facts_and_shared_items(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            imp.write(imp.summarize(bundle()), out=out, entries=out / "e", experiments=False)
            old = build_pack.APP_LESSONS
            build_pack.APP_LESSONS = out
            try:
                pack, shared = build_pack.Pack(), []
                n = build_pack.add_app_lessons(pack, shared)
            finally:
                build_pack.APP_LESSONS = old
        self.assertEqual(2, n)   # strong / building only, money-quoting lesson dropped at import
        self.assertTrue(all(f["kind"] == "app_lesson" for f in pack.facts))
        orb = next(i for i in shared if i["id"] == "app:strat:orb")
        self.assertEqual("app", orb["k"])
        self.assertEqual(40, orb["n"])
        self.assertEqual("orb", orb.get("a"))
        self.assertEqual([], imp.money_free(shared))

    def test_experiment_log_items_carry_verdict_and_gate(self):
        pack, shared = build_pack.Pack(), []
        n = build_pack.add_experiments(pack, shared)
        self.assertGreater(n, 50)
        r14 = next(i for i in shared if i["id"] == "exp:r14")
        self.assertEqual(("positive", "pass"), (r14["v"], r14["g"]))
        self.assertIn("orb", r14["t"])
        r10 = next(i for i in shared if i["id"] == "exp:r10")
        self.assertEqual("fail", r10["g"])

    def test_open_questions_and_baselines_check_their_numbers(self):
        pack, shared = build_pack.Pack(), []
        self.assertEqual([], build_pack.add_open_questions(pack, shared))
        s7 = next(i for i in shared if i["id"] == "open:s7-order-flow")
        self.assertEqual(("open", "open"), (s7["v"], s7["g"]))
        base, errors = build_pack.baselines()
        self.assertEqual([], errors)
        self.assertEqual(0.16, next(x for x in base["spreads"] if x["i"] == "BANKNIFTY")["hs"])
        self.assertEqual(2, base["signal_x"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
