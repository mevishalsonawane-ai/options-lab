"""Experiment entries for MB1 (research/experiments/entries/mb1-*.yaml), written from the committed result files.

    python -m research.brain.mcx.entries      # (re)write the entries; run before and after the holdout read
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

from . import config

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
OUT = HERE.parents[2] / "research" / "experiments" / "entries"
DOC, PREREG = "research/BRAIN_MCX.md", "research/brain/mcx/PREREG.md"
DATA = ("Drive lake options_rolling_1m (6 MCX monthly option books, 5 Aug 2025 - 13 Oct 2026: near-future close + ATM "
        "IV/OI/volume) + fut_1m_live_mcx and fut_daily_continuous fetched from Dhan 19-20 Oct 2026 (to 16 Oct); MCX "
        "cube v1, 56 features (research/brain/mcx/FEATURES.md); raw data on Drive, not in git")
PERIOD = {"design": f"5 Aug 2025 - {config.DESIGN_END} (226 sessions; walk-forward tests on blocks 3-6, 21 Nov 2025 - "
                    "23 Jun 2026, about 150 sessions)",
          "holdout": f"{config.HOLDOUT_START} - 16 Oct 2026 (77 sessions; embargo 24-30 Jun)"}
SYMS = list(config.OPTION_SYMBOLS)


def _f(x, k=3):
    return "n/a" if x is None else f"{x:.{k}f}"


def entries(dm: dict, l4: dict, hm: dict | None) -> list[dict]:
    S, V, Q = dm["specialists"], dm["verdicts"], dm["bh"]["q"]
    read = hm is not None
    out = []

    def hold(s, what):
        if not read or s not in hm["specialists"]:
            return ""
        h = hm["specialists"][s]
        if what == "A":
            return f"; holdout skill {_f(h['size']['skill'])} ({h['size']['rows']} rows)"
        if what == "C":
            return f"; holdout AUC {_f(h['C'].get('auc'))} ({h['C'].get('rows')} rows)"
        c = h["committee"]
        return f"; holdout hit {_f(c.get('hit_rate'))} on {c.get('acted')} acted (p {_f(c.get('p'))})"

    def base(id_, title, family, hyp, feats, model, variants, summary, verdict, notes, holdout_read, costs="none (forecasts, no trades)"):
        return dict(id=id_, title=title, family=family, date="2026-10-20", doc=DOC, hypothesis=hyp, data_version=DATA,
                    period=PERIOD, instruments=SYMS, features=feats, model_or_rule=model,
                    settings="research/brain/mcx/PREREG.md; results/design_metrics.json, holdout_metrics.json",
                    prereg=PREREG, variants_tried=variants, results=dict(summary=summary, costs=costs),
                    holdout_read=holdout_read, verdict=verdict, notes=notes)

    a = "; ".join(f"{s} skill {_f(S[s]['size']['skill'])} vs {S[s]['size']['best_baseline']} (q {_f(Q.get('L1A:' + s))}, "
                  f"{S[s]['size']['rows']} rows / {S[s]['size']['sessions']} sessions, {V['L1A:' + s]}){hold(s, 'A')}"
                  for s in SYMS)
    npass = sum(V[f"L1A:{s}"] == "pass" for s in SYMS)
    out.append(base("mb1-l1-range", "MCX brain L1-A: size of the next 60 minutes (log close range), 6 MCX futures",
                    "ml", "Point-in-time features forecast the next hour's close range better than the best of the time-of-day, last-hour and ATM-IV baselines (skill >= 5%, BH q <= 0.05).",
                    ["all 56 MCX L4 features"], "HistGradientBoosting (primary) and ridge per symbol, walk-forward 4 folds",
                    3 * 6, f"Design: {npass} of 6 pass. {a}.",
                    "adopted" if read else "inconclusive",
                    "Information only (a size forecast for stops/targets and context); nothing trades. Same finding as the India brain B1."
                    + (" Holdout (read once): skill >= 5% with p <= 0.05 on " + ", ".join(
                        s for s in SYMS if hm["specialists"][s]["size"]["skill"] >= 0.05
                        and hm["specialists"][s]["size"]["p"] <= 0.05) + "; not on " + ", ".join(
                        s for s in SYMS if not (hm["specialists"][s]["size"]["skill"] >= 0.05
                                                and hm["specialists"][s]["size"]["p"] <= 0.05)) + "." if read else ""),
                    read))
    b = "; ".join(f"{s} AUC {_f(S[s]['B'].get('auc'))} ({S[s]['B'].get('rows')} rows, {V['L1B:' + s]})" for s in SYMS)
    out.append(base("mb1-l1-persistence", "MCX brain L1-B: does the last hour's move continue over the next hour?", "ml",
                    "The sign of the next-60-minute return agrees with the last 60 minutes more or less often than the base rate, predictably (AUC >= 0.55, log-loss below the base rate).",
                    ["all 56 MCX L4 features"], "HistGradientBoosting classifier (primary) and logistic, per symbol",
                    12, f"Design: 0 of 6 pass. {b}. Persistence is about 47-51% everywhere (no momentum, no reversal).",
                    "rejected", "Holdout not read for this test (PREREG section 8).", False))
    c = "; ".join(f"{s} AUC {_f(S[s]['C'].get('auc'))} ({S[s]['C'].get('rows')} rows, {V['L1C:' + s]}){hold(s, 'C')}" for s in SYMS)
    out.append(base("mb1-l1-direction", "MCX brain L1-C: up or down over the next hour", "ml",
                    "The brain forecasts the sign of the next-60-minute return better than the base rate (AUC >= 0.55, log-loss below the base rate).",
                    ["all 56 MCX L4 features"], "HistGradientBoosting classifier (primary) and logistic, per symbol",
                    12, f"Design: 0 of 6 pass. {c}.", "rejected", "Direction is not predictable, as in R16 and B1.", read))
    D = dm["session"]
    d = "; ".join(f"{s} size skill {_f(D[s]['size']['skill'])} ({D[s]['size']['n_days']} days, {V['L1Di:' + s]}), "
                  f"continue rate {_f(D[s]['continue']['rate'])} AUC {_f(D[s]['continue']['auc'])} ({V['L1Dii:' + s]})" for s in SYMS)
    out.append(base("mb1-l1-session", "MCX brain L1-D: the US cash open (size of the next 150 minutes; does it continue the day?)",
                    "ml", "At the US open the brain forecasts the next 150 minutes' range better than the previous-20-day mean, and whether the US session continues the day's move.",
                    ["all 56 MCX L4 features at the bar ending at 09:30 New York"], "HistGradientBoosting and ridge/logistic per symbol, walk-forward",
                    24, f"Design: 0 of 12 pass. {d}.", "rejected", "One row a day (73-149 test days a symbol): small samples.", False))
    E = dm["events"]
    e = "; ".join(f"{g} x{_f(r['multiplier'], 2)} ({r['n_events']} releases, q {_f(Q.get('L1Ei:' + g))}, {V['L1Ei:' + g]}), "
                  f"follow {_f(r['follow_rate'], 2)} of {r['n_follow']} ({V['L1Eii:' + g]})" for g, r in E.items())
    ne = sum(V[f"L1Ei:{g}"] == "pass" for g in E)
    out.append(base("mb1-l1-events", "MCX brain L1-E: EIA crude, EIA gas storage, US CPI/NFP hours (bigger? follow or fade?)",
                    "calendar-events", "The hour after a scheduled US release is bigger than the same clock hour on other days, and its direction follows the hour before.",
                    ["release calendar (R83 EIA/storage, R82 CPI/NFP)", "the 60 minutes before"], "descriptive tests (day bootstrap, binomial)",
                    16, f"Design: bigger on {ne} of 8 groups, follow/fade on 0 of 8. {e}.",
                    "adopted" if ne else "rejected",
                    "A size fact for risk (event hours are 1.1-1.7x bigger), not a direction edge; R83 already found EIA/storage fade and follow lose after costs.",
                    False))
    l2 = "; ".join(f"{s} size gain {_f(S[s]['size']['enc_gain'], 4)} (PCA {_f(S[s]['size']['pca_gain'], 4)}), direction AUC gain "
                   f"{_f(S[s]['C'].get('enc_gain'), 3)}" for s in SYMS)
    recon = "; ".join(f"{f['test'][0]}: autoencoder MSE {_f(f['encoder_recon_mse'])} vs PCA {_f(f['pca_recon_mse'])} on {f['encoder_rows']} windows" for f in dm["folds"])
    out.append(base("mb1-l2-encoder", "MCX brain L2: 120-minute sequence autoencoder (16-number market state) added to L1",
                    "ml", "The self-supervised 16-number market state improves the size (MAE >= 1%) or direction (AUC +0.01) specialists.",
                    ["120-minute window: 24 x (5-min return / vol, position, IV z, IV mask, clock sin/cos)"],
                    "MLP autoencoder 144-64-16-64-144 per fold, pooled symbols; PCA-16 baseline", 12,
                    f"Design: 0 of 12 pass. {l2}. {recon}.", "rejected",
                    "The state adds nothing the 56 features do not already carry; kept as a stored representation (tensors) and for the market-state cards.", False))
    l3 = "; ".join(f"{s} acted {S[s]['committee']['acted']} hit {_f(S[s]['committee']['hit_rate'])} (q {_f(Q.get('L3C:' + s))}, {V['L3C:' + s]}){hold(s, 'L3')}; "
                   f"size band coverage {_f(S[s]['size']['committee']['band80_coverage'])}" for s in SYMS)
    n3 = sum(V[f"L3C:{s}"] == "pass" for s in SYMS)
    v3 = "inconclusive" if n3 else "rejected"
    if read:
        ok = [s for s in SYMS if V[f"L3C:{s}"] == "pass" and (hm["specialists"][s]["committee"].get("hit_rate") or 0) >= 0.53
              and (hm["specialists"][s]["committee"].get("p") or 1) <= 0.05]
        v3 = "inconclusive" if ok else "rejected"
    out.append(base("mb1-l3-committee", "MCX brain L3: calibrated committee that abstains (direction at p >= 0.55 / <= 0.45; size band)",
                    "ml", "When the calibrated committee acts, it is right >= 53% of the time on >= 300 acted rows (BH q <= 0.05).",
                    ["L1-B and L1-C probabilities", "L2 state"], "logistic stack + isotonic on the inner 30% of each training fold",
                    6, f"Design: {n3} of 6 pass. {l3}.", v3,
                    "A 53% hit rate does not cover MCX costs on a one-hour hold (L4 B1 lost on every symbol).", read))
    G, VV = l4["gates"], l4["variants"]
    for rule, title, hyp in (
            ("B1", "committee direction, 60-minute hold, stop 1x predicted range", "Trading the committee's direction pays after MCX costs."),
            ("B2", "big-size follow: predicted range top third/fifth and a 30-minute move >= 0.33x it", "When the brain expects a big hour, following the last 30 minutes pays."),
            ("B3", "quiet fade: predicted range bottom third and price >= 1-1.5x it from the session mean", "When the brain expects a quiet hour, fading stretched prices to the session mean pays."),
            ("B4", "size-edge straddle: predicted range / ATM-IV implied range top decile/fifth, buy the ATM straddle", "When the brain expects more movement than the options price, buying the straddle pays.")):
        parts = []
        for s in config.TRADE:
            g = G[f"{rule}:{s}"]
            r = VV[g["frozen"]]
            parts.append(f"{s} {r['trades']} trades net Rs {r['net']:,.0f} (gross {r.get('gross', 0):,.0f}), twin p {_f(r['twin_p'])} "
                         f"q {_f(g['q'])}, wf Rs {VV[rule + ':' + s + ':wf']['wf_net']:,.0f}, top-5 removed Rs {r.get('top5_removed', 0):,.0f}: {g['verdict']}")
        out.append(dict(
            id=f"mb1-l4-{rule.lower()}", title=f"MCX brain L4 {rule}: {title}", family="mcx-crypto", date="2026-10-20",
            doc=DOC, hypothesis=hyp, data_version=DATA, period=PERIOD, instruments=list(config.TRADE),
            features=["brain forecasts (L1-L3, walk-forward test folds)", "MCX future minute closes", "ATM IV (B4)"],
            model_or_rule=f"{rule}: {title}. Signal on the MCX future; traded as one mini-future lot (B1-B3) or the ATM straddle (B4)",
            settings="research/brain/mcx/l4.py, PREREG section 7; results/l4_design.json", prereg=PREREG, variants_tried=8,
            results=dict(summary="Design (forecasts from walk-forward test folds, ~150 sessions): " + "; ".join(parts) + ".",
                         costs="Zerodha MCX futures/options charges (R53), R53 half-spreads, 0.02% stop slippage (R83)"),
            holdout_read=False, verdict="rejected",
            notes="No rule passed the design gate, so the holdout was not read for rules (PREREG section 7). Stays a note."))
    return out


def main():
    dm = json.loads((RES / "design_metrics.json").read_text())
    l4 = json.loads((RES / "l4_design.json").read_text())
    hp = RES / "holdout_metrics.json"
    hm = json.loads(hp.read_text()) if hp.exists() else None
    for e in entries(dm, l4, hm):
        (OUT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
        print("wrote", e["id"], e["verdict"])


if __name__ == "__main__":
    main()
