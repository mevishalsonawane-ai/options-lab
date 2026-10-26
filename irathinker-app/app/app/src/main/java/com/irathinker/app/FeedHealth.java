package com.irathinker.app;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Feed health (v59): the Settings card that says, per channel,
 *  what last happened on it - built from the per-channel post
 *  stamps the shared send paths record in Prefs (last attempt,
 *  last success, whether the last attempt landed). Pure text
 *  building only, so the harness can pin the card's words
 *  against a fixture state and the Activity just renders them.
 *  Honest by construction: a channel with no stamp says "no
 *  posts yet this install"; a failed last attempt says FAILED
 *  and still shows the last success when there was one. */
public final class FeedHealth {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("d MMM HH:mm");

    /** One channel's stamp state, exactly as Prefs holds it. */
    public static final class Chan {
        public final String label;
        public final String topic;
        public final long attemptAt, successAt;
        public final boolean lastOk;

        public Chan(String label, String topic, long attemptAt, long successAt, boolean lastOk) {
            this.label = label;
            this.topic = topic;
            this.attemptAt = attemptAt;
            this.successAt = successAt;
            this.lastOk = lastOk;
        }
    }

    /** "HH:mm" for an instant on now's IST date, "d MMM HH:mm"
     *  for an older one - the card's clock shorthand. */
    static String when(long millis, long nowMs) {
        if (millis <= 0) return "";
        Instant t = Instant.ofEpochMilli(millis);
        java.time.LocalDate d = t.atZone(IST).toLocalDate();
        java.time.LocalDate today = Instant.ofEpochMilli(nowMs).atZone(IST).toLocalDate();
        return (d.equals(today) ? HM : DM).withZone(IST).format(t);
    }

    /** One channel's line: name + topic, then its stamp story. */
    public static String chanLine(Chan c, long nowMs) {
        String head = c.label + (c.topic == null || c.topic.isEmpty() ? " (not set)" : " · " + c.topic);
        if (c.attemptAt <= 0) return head + ": no posts yet this install";
        String s = head + ": last post " + when(c.attemptAt, nowMs)
                + (c.lastOk ? " — delivered ✓" : " — FAILED ✗");
        if (c.successAt > 0 && c.successAt != c.attemptAt) {
            s += " · last success " + when(c.successAt, nowMs);
        } else if (!c.lastOk && c.successAt <= 0) {
            s += " · no success yet";
        }
        return s;
    }

    /** The whole card's lines: the feed switch state first, then
     *  one line per channel (human stored, human private, feed). */
    public static List<String> lines(boolean feedOn, String feedServer,
                                     Chan stored, Chan priv, Chan feed, long nowMs) {
        List<String> out = new ArrayList<>();
        out.add("System Feed switch: " + (feedOn ? "ON" : "OFF")
                + " · feed server " + (feedServer == null || feedServer.isEmpty() ? "ntfy.sh" : feedServer)
                + (feedOn ? "" : " — the machine topic gets nothing while this is off (human alerts are unaffected)"));
        out.add(chanLine(stored, nowMs));
        out.add(chanLine(priv, nowMs));
        out.add(chanLine(feed, nowMs));
        return out;
    }

    /** The SEND REAL TEST TO ALL button's per-leg report line. */
    public static String legLine(String label, boolean ok, String note) {
        return label + ": " + (ok ? "✓ sent" : "✗ FAILED")
                + (note == null || note.isEmpty() ? "" : " — " + note);
    }
}
