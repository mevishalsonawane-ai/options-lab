package com.irathinker.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/** On-device settings. Everything lives only on the user's phone -
 *  including the Dhan credentials reserved for the Dhan phase. */
public final class Prefs {
    private static final String FILE = "irathinker";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!sp.contains("ntfyTopic")) {
            // a personal, hard-to-guess default topic (ntfy topics are public)
            sp.edit().putString("ntfyTopic", genTopic()).apply();
        }
    }

    private static String genTopic() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder("irathinker-");
        SecureRandom r = new SecureRandom();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    public String ntfyTopic() { return sp.getString("ntfyTopic", ""); }
    public void setNtfyTopic(String t) { sp.edit().putString("ntfyTopic", t.trim()).apply(); }

    // ---- private alert channel (v41) ----
    // The user's stored topic is a public, guessable name. A second,
    // generated topic is his private channel: until he confirms he
    // subscribed to it (privateLive), posts go to BOTH topics so no
    // alert can be lost in the move; afterwards only the private one.
    public String privateTopic() {
        String t = sp.getString("ntfyPrivateTopic", "");
        if (t.isEmpty()) {
            t = genTopic();
            sp.edit().putString("ntfyPrivateTopic", t).apply();
        }
        return t;
    }
    public boolean privateLive() { return sp.getBoolean("ntfyPrivateLive", false); }
    public void setPrivateLive(boolean b) { sp.edit().putBoolean("ntfyPrivateLive", b).apply(); }
    public java.util.List<String> activeTopics() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (privateLive()) {
            out.add(privateTopic());
        } else {
            String pub = ntfyTopic();
            if (!pub.isEmpty()) out.add(pub);
            String priv = privateTopic();
            if (!priv.isEmpty() && !priv.equals(pub)) out.add(priv);
        }
        return out;
    }
    // ---- v58: manual-share topics (user clarification 2026-10-25) ----
    // A share the user sends himself from a Detail screen goes to
    // ALL his human topics - the stored topic AND the private one -
    // always. The privateLive narrowing in activeTopics() stands
    // for AUTO alerts (the privacy rule for unattended posts); it
    // never narrows a manual send the user triggers himself. (The
    // machine feed topic is the share's third destination, via the
    // v56 feed leg in DetailActivity.)
    public java.util.List<String> manualShareTopics() {
        return manualShareTopics(ntfyTopic(), privateTopic());
    }

    /** The deduped union of the stored and private topics:
     *  empties and nulls skipped, stored first. Pure and flag-free
     *  on purpose - the manual list is the same whether privateLive
     *  is set or not, so the harness can pin it directly. */
    public static java.util.List<String> manualShareTopics(String stored, String priv) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (stored != null && !stored.isEmpty()) out.add(stored);
        if (priv != null && !priv.isEmpty() && !out.contains(priv)) out.add(priv);
        return out;
    }
    public boolean moveNoticeSent() { return sp.getBoolean("ntfyMoveNotice", false); }
    public void markMoveNotice() { sp.edit().putBoolean("ntfyMoveNotice", true).apply(); }
    public boolean flushSent(String dateKey) { return dateKey.equals(sp.getString("flushDate", "")); }
    public void markFlush(String dateKey) { sp.edit().putString("flushDate", dateKey).apply(); }

    public double capital() { return sp.getFloat("capital", 100000f); }
    public void setCapital(double v) { sp.edit().putFloat("capital", (float) v).apply(); }

    public double maxLossPct() { return sp.getFloat("maxLossPct", 1.0f); }
    public void setMaxLossPct(double v) { sp.edit().putFloat("maxLossPct", (float) v).apply(); }

    public boolean alertsOn() { return sp.getBoolean("alertsOn", true); }
    public void setAlertsOn(boolean b) { sp.edit().putBoolean("alertsOn", b).apply(); }

    // ---- v53: BANKNIFTY mom60 strategy alerts ----
    // On by default - the user asked for these alerts explicitly.
    // The master alerts switch still gates the whole engine pass.
    public boolean mom60On() { return sp.getBoolean("mom60On", true); }
    public void setMom60On(boolean b) { sp.edit().putBoolean("mom60On", b).apply(); }

    // ---- v54: BANKNIFTY strangle paper-track alerts ----
    // On by default - the user asked for the paper track explicitly.
    // One entry per week, deduped on the Monday date; this mark is
    // the second guard beside the paper log's own record.
    public boolean strangleOn() { return sp.getBoolean("strangleOn", true); }
    public void setStrangleOn(boolean b) { sp.edit().putBoolean("strangleOn", b).apply(); }
    public String strangleWeek() { return sp.getString("strangleWeek", ""); }
    public void markStrangleWeek(String mondayIso) { sp.edit().putString("strangleWeek", mondayIso).apply(); }

    // ---- v55: FINNIFTY + Sensex mom60 paper-track alerts ----
    // Each on by default - the user asked for both explicitly -
    // and independent of the BANKNIFTY switch and of each other:
    // turning one index off never silences another's watcher, and
    // resolution of an open position continues regardless (the
    // engines resolve with their switch off, as mom60 always has).
    public boolean mom60FinOn() { return sp.getBoolean("mom60FinOn", true); }
    public void setMom60FinOn(boolean b) { sp.edit().putBoolean("mom60FinOn", b).apply(); }
    public boolean mom60SensexOn() { return sp.getBoolean("mom60SensexOn", true); }
    public void setMom60SensexOn(boolean b) { sp.edit().putBoolean("mom60SensexOn", b).apply(); }

    // Dhan phase (stored now, wired when we move from Yahoo to Dhan)
    public String dhanClientId() { return sp.getString("dhanClientId", ""); }
    public void setDhanClientId(String v) { sp.edit().putString("dhanClientId", v.trim()).apply(); }
    public String dhanToken() { return sp.getString("dhanToken", ""); }
    public void setDhanToken(String v) { sp.edit().putString("dhanToken", v.trim()).apply(); }

    // ---- alert de-duplication state ----
    public boolean bellSent(String dateKey) { return dateKey.equals(sp.getString("bellDate", "")); }
    public void markBell(String dateKey) { sp.edit().putString("bellDate", dateKey).apply(); }
    public boolean squareOffSent(String dateKey) { return dateKey.equals(sp.getString("sqoffDate", "")); }
    public void markSquareOff(String dateKey) { sp.edit().putString("sqoffDate", dateKey).apply(); }

    public boolean setupFired(String key) {
        return sp.getStringSet("firedSetups", new HashSet<>()).contains(key);
    }

    /** Today's fired-alert keys (v49: the ledger joins on these to
     *  mark which recorded plans were actually sent as alerts). */
    public Set<String> firedKeys() {
        return new HashSet<>(sp.getStringSet("firedSetups", new HashSet<>()));
    }

    /** How many buy alerts have fired on a given date (yyyy-MM-dd). */
    public int firedCount(String dateKey) {
        int n = 0;
        for (String k : sp.getStringSet("firedSetups", new HashSet<>())) {
            if (k.endsWith(dateKey)) n++;
        }
        return n;
    }
    public void markSetupFired(String key) {
        Set<String> s = new HashSet<>(sp.getStringSet("firedSetups", new HashSet<>()));
        s.add(key);
        // keep only today's keys to stop unbounded growth
        String today = key.substring(key.lastIndexOf('|') + 1);
        s.removeIf(k -> !k.endsWith(today));
        sp.edit().putStringSet("firedSetups", s).apply();
    }

    // ---- v6: intelligence settings ----
    /** Minimum IraScore for a buy alert to fire. 0 = alert on everything. */
    public int minAlertScore() { return sp.getInt("minAlertScore", 0); }
    public void setMinAlertScore(int v) { sp.edit().putInt("minAlertScore", v).apply(); }

    public boolean watchdogOn() { return sp.getBoolean("watchdogOn", true); }
    public void setWatchdogOn(boolean b) { sp.edit().putBoolean("watchdogOn", b).apply(); }

    public String briefDate() { return sp.getString("briefDate", ""); }
    public void setBriefDate(String d) { sp.edit().putString("briefDate", d).apply(); }
    public long lastPassAt() { return sp.getLong("lastPassAt", 0); }
    public void setLastPassAt(long millis) { sp.edit().putLong("lastPassAt", millis).apply(); }

    public boolean eodSent(String dateKey) { return dateKey.equals(sp.getString("eodDate", "")); }
    public void markEod(String dateKey) { sp.edit().putString("eodDate", dateKey).apply(); }

    // ---- v6: fired-signal outcome tracking ----
    // open signal entry: yahoo|setup|date|breakTs|stop|target
    // (v50 ORB alerts append a 7th field: |target3, the 3R runner)
    public Set<String> openSignals() {
        return new HashSet<>(sp.getStringSet("openSignals", new HashSet<>()));
    }
    public void addOpenSignal(String entry) {
        Set<String> s = openSignals();
        s.add(entry);
        sp.edit().putStringSet("openSignals", s).apply();
    }
    public void removeOpenSignal(String entry) {
        Set<String> s = openSignals();
        s.remove(entry);
        sp.edit().putStringSet("openSignals", s).apply();
    }

    /** Record a resolved outcome: 'T' target, 'S' stop, 'N' neither (closed flat). */
    public void recordOutcome(String key, char outcome) {
        Set<String> s = new HashSet<>(sp.getStringSet("sigOutcomes", new HashSet<>()));
        s.add(key + "=" + outcome);
        // prune entries older than ~40 days (dates are ISO, compare lexicographically)
        java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(40);
        s.removeIf(e -> {
            int i = e.lastIndexOf('|');
            if (i < 0 || i + 11 > e.length()) return false;
            return e.substring(i + 1, i + 11).compareTo(cutoff.toString()) < 0;
        });
        sp.edit().putStringSet("sigOutcomes", s).apply();
    }
    public boolean outcomeRecorded(String key) {
        for (String e : sp.getStringSet("sigOutcomes", new HashSet<>())) {
            if (e.startsWith(key + "=")) return true;
        }
        return false;
    }
    /** {targets, stops, neither} across the stored window. */
    public int[] outcomeCounts() {
        int[] c = new int[3];
        for (String e : sp.getStringSet("sigOutcomes", new HashSet<>())) {
            if (e.endsWith("=T")) c[0]++;
            else if (e.endsWith("=S")) c[1]++;
            else if (e.endsWith("=N")) c[2]++;
        }
        return c;
    }

    // ---- v50: the second exit's record (3R runner) ----
    // Kept in its own store so the 2R record above - the scoreboard
    // the EOD report and journal have always used - keeps its exact
    // meaning while the two exits are judged head-to-head live.
    /** Record a resolved 3R outcome: 'T' target, 'S' stop, 'N' neither. */
    public void recordOutcome3(String key, char outcome) {
        Set<String> s = new HashSet<>(sp.getStringSet("sigOutcomes3", new HashSet<>()));
        s.add(key + "=" + outcome);
        // same ~40-day prune as the 2R record
        java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(40);
        s.removeIf(e -> {
            int i = e.lastIndexOf('|');
            if (i < 0 || i + 11 > e.length()) return false;
            return e.substring(i + 1, i + 11).compareTo(cutoff.toString()) < 0;
        });
        sp.edit().putStringSet("sigOutcomes3", s).apply();
    }
    public boolean outcome3Recorded(String key) {
        for (String e : sp.getStringSet("sigOutcomes3", new HashSet<>())) {
            if (e.startsWith(key + "=")) return true;
        }
        return false;
    }
    /** {targets, stops, neither} among one day's ORB alerts, 2R record. */
    public int[] outcomeCountsOn(String dateKey) { return countsOn("sigOutcomes", dateKey); }
    /** {targets, stops, neither} among one day's ORB alerts, 3R record. */
    public int[] outcome3CountsOn(String dateKey) { return countsOn("sigOutcomes3", dateKey); }
    private int[] countsOn(String store, String dateKey) {
        int[] c = new int[3];
        for (String e : sp.getStringSet(store, new HashSet<>())) {
            int eq = e.lastIndexOf('=');
            if (eq < 0) continue;
            String[] parts = e.substring(0, eq).split("\\|"); // yahoo|setup|date
            if (parts.length < 3 || !parts[2].equals(dateKey)) continue;
            if (!parts[1].startsWith("ORB")) continue; // the dual record is ORB-only; fades exit at 1R
            char oc = e.charAt(eq + 1);
            if (oc == 'T') c[0]++; else if (oc == 'S') c[1]++; else if (oc == 'N') c[2]++;
        }
        return c;
    }

    // ---- v51: System Feed (machine-readable mirror of the alerts) ----
    // A separate ntfy topic the user's order system watches. IraThinker
    // stays the support system: it publishes the alerts there as raw
    // JSON; the order application consumes them. Since v57 the mirror
    // covers every alert family (the user's all-topics rule), with the
    // paper strategies tagged paper:true.
    public String feedTopic() { return sp.getString("feedTopic", ""); }
    public void setFeedTopic(String t) { sp.edit().putString("feedTopic", t.trim()).apply(); }
    public String feedServer() { return sp.getString("feedServer", "ntfy.sh"); }
    public void setFeedServer(String s) { sp.edit().putString("feedServer", s.trim()).apply(); }
    public boolean feedOn() { return sp.getBoolean("feedOn", false); }
    public void setFeedOn(boolean b) { sp.edit().putBoolean("feedOn", b).apply(); }
    /** Last System Feed send attempt (millis) and whether it landed -
     *  shown on the Settings status line, test sends included. */
    public long feedLastAt() { return sp.getLong("feedLastAt", 0); }
    public boolean feedLastOk() { return sp.getBoolean("feedLastOk", false); }
    public void setFeedStatus(long at, boolean ok) {
        sp.edit().putLong("feedLastAt", at).putBoolean("feedLastOk", ok).apply();
    }

    // ---- v59: per-channel post stamps (the Settings feed-health card) ----
    // Every shared send path records, per channel, when it last
    // ATTEMPTED a post, when it last SUCCEEDED, and whether the last
    // attempt landed - so the health card can say honestly what each
    // channel last did instead of guessing. The feed channel ALSO
    // keeps the v51 feedLastAt/feedLastOk pair above (the Settings
    // status line reads it); postFeedNow stamps both.
    public static final String CH_STORED = "hs";   // human posts, stored topic
    public static final String CH_PRIVATE = "hp";  // human posts, private topic
    public static final String CH_FEED = "fd";     // machine feed topic

    public void stampChannel(String ch, boolean ok) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = sp.edit()
                .putLong("chanAtt_" + ch, now)
                .putBoolean("chanOk_" + ch, ok);
        if (ok) e.putLong("chanSuc_" + ch, now);
        e.apply();
    }
    /** Last post attempt on a channel (millis), 0 = never this install. */
    public long chanAttemptAt(String ch) { return sp.getLong("chanAtt_" + ch, 0); }
    /** Last SUCCESSFUL post on a channel (millis), 0 = none yet. */
    public long chanSuccessAt(String ch) { return sp.getLong("chanSuc_" + ch, 0); }
    /** Whether the channel's last attempt landed. */
    public boolean chanLastOk(String ch) { return sp.getBoolean("chanOk_" + ch, false); }

    // ---- v8: watchlist + one-shot price alerts ----
    public boolean isWatched(String yahoo) {
        return sp.getStringSet("watchlist", new HashSet<>()).contains(yahoo);
    }
    public boolean toggleWatch(String yahoo) {
        Set<String> s = new HashSet<>(sp.getStringSet("watchlist", new HashSet<>()));
        boolean now;
        if (s.contains(yahoo)) { s.remove(yahoo); now = false; } else { s.add(yahoo); now = true; }
        sp.edit().putStringSet("watchlist", s).apply();
        return now;
    }
    public Set<String> priceAlerts() {
        return new HashSet<>(sp.getStringSet("priceAlerts", new HashSet<>()));
    }
    /** entry format: yahoo|ABOVE|price or yahoo|BELOW|price */
    public void addPriceAlert(String yahoo, boolean above, double price) {
        addPriceAlert(yahoo, above, price, false);
    }
    /** v59: a recurring price alert carries a 4th field "R" and
     *  re-arms after firing instead of removing itself. Entries
     *  written before v59 have no 4th field and stay one-shot -
     *  the format is an extension, never a rewrite. */
    public void addPriceAlert(String yahoo, boolean above, double price, boolean recurring) {
        Set<String> s = priceAlerts();
        s.add(yahoo + "|" + (above ? "ABOVE" : "BELOW") + "|" + price + (recurring ? "|R" : ""));
        sp.edit().putStringSet("priceAlerts", s).apply();
    }
    /** Whether a stored price-alert entry is a recurring one (v59).
     *  Pure and static so the harness can pin the format directly. */
    public static boolean priceAlertRecurring(String entry) {
        if (entry == null) return false;
        String[] p = entry.split("\\|");
        return p.length >= 4 && "R".equals(p[3]);
    }
    public void removePriceAlert(String entry) {
        Set<String> s = priceAlerts();
        s.remove(entry);
        sp.edit().putStringSet("priceAlerts", s).apply();
    }
}
