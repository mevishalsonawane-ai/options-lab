package com.irathinker.app;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Posts alerts to the user's ntfy topic. ntfy.sh needs no account:
 *  whoever knows the topic can read it, so the app generates a personal
 *  topic name by default. */
public final class NtfyClient {

    /** Post to every topic in the list (the private-channel move
     *  dual-sends during the transition). True when at least one
     *  post landed. */
    public static boolean postToAll(java.util.List<String> topics, String title, String body, String tags) {
        boolean any = false;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String t : topics) {
            if (t == null || !seen.add(t.trim())) continue;
            if (post(t, title, body, tags)) any = true;
        }
        return any;
    }

    /** The same dual-send, stamping each human channel's outcome
     *  into Prefs (v59 feed health): a post to the stored topic
     *  stamps CH_STORED, to the private topic CH_PRIVATE - matched
     *  by topic string against the prefs' own values, so a channel
     *  is credited only for a post actually attempted to it. An
     *  unrelated topic in the list posts unstamped. Send behaviour
     *  (dedupe, any-landed result) is identical to the plain
     *  overload above. */
    public static boolean postToAll(Prefs prefs, java.util.List<String> topics,
                                    String title, String body, String tags) {
        boolean any = false;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        String stored = prefs.ntfyTopic(), priv = prefs.privateTopic();
        for (String t : topics) {
            if (t == null || !seen.add(t.trim())) continue;
            boolean ok = post(t, title, body, tags);
            if (ok) any = true;
            try {
                String tt = t.trim();
                if (tt.equals(priv)) prefs.stampChannel(Prefs.CH_PRIVATE, ok);
                if (tt.equals(stored)) prefs.stampChannel(Prefs.CH_STORED, ok);
            } catch (Throwable ignored) { }
        }
        return any;
    }

    public static boolean post(String topic, String title, String body, String tags) {
        if (topic == null || topic.trim().isEmpty()) return false;
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://ntfy.sh/" + topic.trim()).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Title", title);
            c.setRequestProperty("Tags", tags);
            c.setRequestProperty("Priority", "high");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Raw-body publish for the System Feed (v51): the body goes out
     *  byte-for-byte - it IS the message (a JSON signal for the user's
     *  order system to parse), so no human title/tags/priority
     *  dressing, only a minimal "signal" title header. Server is a
     *  bare host (SystemFeed.normalizeServer output); the scheme is
     *  always https, as with the human alerts. */
    public static boolean postRaw(String server, String topic, String body) {
        if (topic == null || topic.trim().isEmpty() || body == null) return false;
        String host = server == null ? "" : server.trim()
                .replaceFirst("(?i)^https?://", "").replaceAll("/+$", "");
        if (host.isEmpty()) host = "ntfy.sh";
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://" + host + "/" + topic.trim()).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Title", "signal");
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
