package com.optionslab.ira

/**
 * The trade manager's bundled defaults: research round 12's settings block (research/HUNT_R12_TRADE_MANAGER.md, Part D),
 * read through [ManagerConfig.parse] like any research block, so a later round replaces [JSON] and nothing else. Per strategy:
 * its default mode on paper and live (only where Boss has stored nothing: [TradeManager.Policy] keeps his choices), its
 * committee (the research's exit and lock rules as specialist parameters, [R12Rules]) and its verdict in one plain sentence.
 *
 * Changes from the research's block, on Boss's brief (10 Oct, round 2), each said in the block's own "note":
 *  - mcx-eve, mcx-morning and silver-night are SHADOW, not OFF: R12 said OFF only because MCX minutes start on 5 Aug 2025 (too
 *    little data to tune anything), and recording in SHADOW is how that data gets built. Nothing acts there.
 *  - solo and pine keep the app's existing defaults (ACT on paper, SHADOW on live): R12 did not test them. Their record line
 *    carries the caveat ([TradeManager.SOLO_PINE_NOTE]).
 *  - "mode": "ACT" means ACT on PAPER only; LIVE stays SHADOW (LIVE to ACT always takes the PIN, [ManagerConfig.bounded]).
 *  - each strategy has a "verdict" for the record and Jarvis.
 * The live-only specialists (order flow, delta divergence, absorption, traps, news, consensus, data) and the original
 * extension stay SHADOW wherever a research committee runs: only its members decide ([ManagerConfig.members]). Solo and
 * Pine keep their whole committee, as before.
 */
object R12Defaults {
    const val VERSION = "R12-2026-10-10"

    val JSON: String = """
{
 "version": "R12-2026-10-10",
 "costs": "app fills + app charges + h24 half-spread; decisions on the minute close, filled next open; locks rest on the low",
 "strategies": {
  "orb_fresh":  {"mode": "ACT", "quorum": 1,
                 "exit": [{"spec": "PA", "family": "price action / MFE", "T_min": 20, "m": 0.03, "weight": 1},
                          {"spec": "PB", "family": "price action / MAE", "a": 0.12, "on": "minute close", "weight": 1}],
                 "lock": [{"spec": "VC", "family": "volatility trail", "k_atr": 3.0, "arm_atr": 1.0, "atr": "option 1-min range, mean of previous 10"}],
                 "extend": null,
                 "evidence": "design WF +9,169 (3/3 folds), holdout +14,365, twin p 0.001, BH q 0.008; the arm still loses",
                 "verdict": "ORB Fresh: research-proven to cut losses (+₹14,365 on 474 holdout trades); the arm still loses overall."},
  "orb":        {"mode": "SHADOW", "quorum": 1,
                 "exit": [{"spec": "PA", "T_min": 20, "m": 0.03, "weight": 1}, {"spec": "PB", "a": 0.12, "weight": 1}],
                 "lock": [{"spec": "VC", "k_atr": 6.0, "arm_atr": 1.0}], "extend": null,
                 "evidence": "holdout +12,736 but twin p 0.15: no better than random cutting",
                 "verdict": "ORB: research says its rules cut losses no better than random early exits; recording only."},
  "orb_sweep":  {"mode": "SHADOW", "quorum": 1,
                 "exit": [{"spec": "OI", "z0": 1.5, "window_min": 15, "strikes": "ATM+-2", "weight": 1},
                          {"spec": "PA", "T_min": 45, "m": 0.03, "weight": 1}],
                 "lock": [], "extend": {"votes": ["VWAP>=0.5SD", "flow>=+0.5SD"], "quorum": 2},
                 "evidence": "holdout -1,936, twin p 0.98",
                 "verdict": "ORB Sweep: research says its rules lost money in the holdout (−₹1,936); recording only."},
  "range_fade": {"mode": "SHADOW", "quorum": 1,
                 "exit": [{"spec": "PA", "T_min": 10, "m": 0.10, "weight": 1},
                          {"spec": "VW", "z_sd": 0.5, "persist_min": 5, "weight": 1}],
                 "lock": [], "extend": null,
                 "evidence": "holdout +28,054 but twin p 0.49: random exits save as much",
                 "verdict": "Range Fade: research says random early exits saved as much as its rules; recording only."},
  "liquidity":  {"mode": "SHADOW", "quorum": 1,
                 "exit": [{"spec": "OI", "z0": 1.0, "weight": 1}, {"spec": "PA", "T_min": 45, "m": 0.10, "weight": 1}],
                 "lock": [], "extend": null,
                 "evidence": "design WF +797 (1/3 folds), White RC 0.87; keep the original exits",
                 "verdict": "Liquidity: research says keep the original exits; recording only."},
  "vixdiv":     {"mode": "SHADOW",
                 "NIFTY": {"quorum": 1, "exit": [{"spec": "MP", "value_area": "prior day", "persist_min": 5}, {"spec": "PB", "a": 0.12}], "extend": null},
                 "BANKNIFTY": {"quorum": 1, "exit": [{"spec": "DP", "z0": 2.0}, {"spec": "MP", "value_area": "prior day", "persist_min": 5}], "extend": null},
                 "evidence": "design WF -7,419 / -21,286; keep the original exits",
                 "verdict": "VIX divergence: research says keep the original exits; recording only."},
  "hero":       {"mode": "SHADOW", "quorum": 1,
                 "exit": [{"spec": "MP", "value_area": "prior day", "persist_min": 1}, {"spec": "PA", "T_min": 10, "m": 0.10}],
                 "lock": [{"spec": "BE", "b": 0.15}], "extend": null,
                 "evidence": "design WF -7,620, 4 holdout trades; a lottery ticket - never cut it on evidence this thin",
                 "verdict": "Hero: research says keep the original exits (4 holdout trades, too few to judge); recording only."},
  "night":        {"mode": "OFF", "why": "15:20 -> next 09:16; 10 in-session minutes, nothing to manage",
                   "verdict": "Night: nothing to manage (10 minutes in session); off."},
  "mcx-eve":      {"mode": "SHADOW", "why": "under 2 months of design data (MCX minutes start 5 Aug 2025)",
                   "note": "R12 said OFF for lack of data only; SHADOW records live trades to build that data",
                   "verdict": "MCX evening: not tested (too little MCX data); recording only, to build that data."},
  "mcx-morning":  {"mode": "SHADOW", "why": "not rebuilt; same data limit",
                   "note": "R12 said OFF for lack of data only; SHADOW records live trades to build that data",
                   "verdict": "MCX morning: not tested (too little MCX data); recording only, to build that data."},
  "mcx-trend":    {"mode": "OFF", "why": "multi-month futures holds; a minute option manager does not apply",
                   "verdict": "MCX trend: multi-month futures holds, a minute manager does not apply; off."},
  "silver-night": {"mode": "SHADOW", "why": "futures; under 2 months of design data",
                   "note": "R12 said OFF for lack of data only; SHADOW records live trades to build that data",
                   "verdict": "US-night silver: not tested (too little MCX data); recording only, to build that data."},
  "solo":         {"mode": "ACT", "why": "not tested in R12; the R12 evidence says trails and early exits hurt profitable arms",
                   "note": "the app's existing default kept: ACT on paper, SHADOW on live",
                   "verdict": "Solo: R12: not tested; on history, early exits and trails hurt profitable strategies. Paper only until the record proves it."},
  "pine":         {"mode": "ACT", "why": "not tested in R12; same",
                   "note": "the app's existing default kept: ACT on paper, SHADOW on live",
                   "verdict": "Pine: R12: not tested; on history, early exits and trails hurt profitable strategies. Paper only until the record proves it."}
 },
 "current_manager_rules": {
  "EXTEND (flow>=65 & VWAP>=0.5SD & outside value & OI & VIX calm & 90% to target)": "SHADOW - without flow it fired 0-4 times a year, effect about Rs 0",
  "lock ratchet on extension + 50% trail between extensions": "keep as coded (risk-reducing), SHADOW with EXTEND",
  "OPPOSITE_FLOW": "SHADOW (live-only)",
  "DIVERGENCE (delta)": "SHADOW (live-only)",
  "ABSORPTION": "SHADOW (live-only)",
  "TRAP": "SHADOW (live-only)",
  "NEWS": "SHADOW (live-only)",
  "CONSENSUS (3 findings against)": "SHADOW (live-only)",
  "DATA near stop": "SHADOW (live-only; it is a safety rule, can be ACT by Boss's choice)"
 }
}
"""

    /** Every strategy's (and "vixdiv/nifty"'s, "vixdiv/banknifty"'s) committee, mode and verdict, parsed once. */
    val configs: Map<String, ManagerConfig> by lazy { ManagerConfig.parse(JSON) }

    /** The committee a strategy's trade on [underlying] starts from: the per-index one first ("vixdiv/nifty"), else the family's. */
    fun config(family: String, underlying: String? = null): ManagerConfig? =
        underlying?.let { configs["$family/${it.lowercase(java.util.Locale.ENGLISH)}"] } ?: configs[family]

    /** The research's verdict for [family] in one sentence (null: none). */
    fun verdict(family: String): String? = configs[family]?.verdict
}
