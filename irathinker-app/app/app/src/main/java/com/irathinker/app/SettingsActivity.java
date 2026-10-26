package com.irathinker.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private EditText topicEt, capitalEt, maxLossEt, dhanIdEt, dhanTokenEt, minScoreEt;
    private EditText feedTopicEt, feedServerEt;
    private CheckBox alertsCb, watchdogCb, feedCb;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.screenBg());
        Ui.applySystemBarPadding(sv);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        body.setPadding(pad, pad, pad, pad);
        sv.addView(body);
        setContentView(sv);

        body.addView(Ui.backButton(this, this::finish));
        TextView h = Ui.tv(this, "Settings", 22, Ui.TXT, true);
        h.setPadding(0, 0, 0, Ui.dp(this, 12));
        body.addView(h);

        // ---- alerts card ----
        LinearLayout alerts = Ui.card(this);
        alerts.addView(Ui.sectionTitle(this, "Alerts · ntfy"));
        alerts.addView(Ui.tv(this, "Your alert channel. Subscribe to this exact topic in the ntfy app. "
                + "Topics are public, so keep yours personal and don't share it.", 12.5f, Ui.DIM, false));
        topicEt = field(prefs.ntfyTopic(), false);
        alerts.addView(topicEt);
        alertsCb = new CheckBox(this);
        alertsCb.setText("Instant buy alerts — ntfy fires the moment a buy / buy-put plan appears "
                + "(checked every minute — every 30 seconds 09:30–11:30 — and instantly while the app is open)");
        alertsCb.setTextColor(Ui.TXT);
        alertsCb.setTextSize(13.5f);
        alertsCb.setChecked(prefs.alertsOn());
        alertsCb.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        alerts.addView(alertsCb);
        TextView test = Ui.pill(this, "SEND TEST NOTIFICATION", Ui.CARD_ALT, Ui.TXT);
        test.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        test.setOnClickListener(v -> {
            String topic = topicEt.getText().toString().trim();
            new Thread(() -> {
                boolean ok = NtfyClient.post(topic, "IraThinker test",
                        "If you can read this, your alerts are wired.", "bell");
                prefs.stampChannel(Prefs.CH_STORED, ok); // v59 feed health
                runOnUiThread(() -> Toast.makeText(this,
                        ok ? "Test sent to " + topic : "Failed — check topic and internet",
                        Toast.LENGTH_LONG).show());
            }).start();
        });
        alerts.addView(test);
        long lp = prefs.lastPassAt();
        alerts.addView(Ui.tv(this, lp <= 0
                        ? "Background checker: hasn't run yet — it ticks every minute (30s in the morning rush), 08:30–24:00, and checks gold every 5 minutes overnight."
                        : "Background checker: last ran at "
                            + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                                    .format(new java.util.Date(lp))
                            + " — it ticks every minute (30s in the morning rush), 08:30–24:00, and checks gold every 5 minutes overnight.",
                12f, Ui.DIM, false));
        body.addView(alerts);

        // ---- private channel card (v41) ----
        LinearLayout priv = Ui.card(this);
        priv.addView(Ui.sectionTitle(this, "Private alert channel"));
        TextView privStatus = Ui.tv(this, "", 12.5f, Ui.DIM, false);
        Runnable privRefresh = () -> privStatus.setText(prefs.privateLive()
                ? "Status: PRIVATE. Alerts go only to your private topic: " + prefs.privateTopic()
                : "Your topic above is a public name — anyone who guesses it can read your alerts. "
                + "A private topic is ready for you: " + prefs.privateTopic()
                + "  ·  1) Tap OPEN, subscribe to it in the ntfy app. 2) Come back and tap GO PRIVATE. "
                + "Until then, every alert is sent to BOTH topics so you miss nothing.");
        privRefresh.run();
        priv.addView(privStatus);
        TextView openP = Ui.pill(this, "OPEN PRIVATE TOPIC IN NTFY", Ui.CARD_ALT, Ui.TXT);
        openP.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        openP.setGravity(Gravity.CENTER);
        openP.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://ntfy.sh/" + prefs.privateTopic())));
            } catch (Exception e) {
                Toast.makeText(this, "Open the ntfy app and subscribe to: "
                        + prefs.privateTopic(), Toast.LENGTH_LONG).show();
            }
        });
        priv.addView(openP);
        TextView goP = Ui.pill(this, "I SUBSCRIBED — GO PRIVATE", Ui.CARD_ALT, Ui.TXT);
        goP.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        goP.setGravity(Gravity.CENTER);
        goP.setOnClickListener(v -> {
            prefs.setPrivateLive(true);
            privRefresh.run();
            new Thread(() -> {
                boolean ok = NtfyClient.post(prefs.privateTopic(), "IraThinker - private channel",
                        "This is your private alert channel. From now on, IraThinker alerts come only here.",
                        "lock");
                prefs.stampChannel(Prefs.CH_PRIVATE, ok); // v59 feed health
                runOnUiThread(() -> Toast.makeText(this,
                        ok ? "Private channel live — check ntfy for the confirmation"
                           : "Saved, but the confirmation post failed — check internet",
                        Toast.LENGTH_LONG).show());
            }).start();
        });
        priv.addView(goP);
        body.addView(priv);

        // ---- system feed card (v51) ----
        LinearLayout feed = Ui.card(this);
        feed.addView(Ui.sectionTitle(this, "System Feed · machine alerts"));
        feed.addView(Ui.tv(this, "A machine-readable copy of every alert the app sends "
                + "(ORB and gap fades, manual shares, mom60 and the strangle - the paper "
                + "strategies tagged paper:true - and your price alerts), posted as JSON "
                + "to a separate topic for your order system. Your human alerts above are "
                + "unchanged, and a feed failure never affects them.", 12.5f, Ui.DIM, false));
        feed.addView(label("Machine topic (for your order system)"));
        feedTopicEt = field(prefs.feedTopic(), false);
        feedTopicEt.setHint("e.g. a long random topic name");
        feed.addView(feedTopicEt);
        feed.addView(label("Feed server"));
        feedServerEt = field(prefs.feedServer(), false);
        feedServerEt.setHint("ntfy.sh");
        feed.addView(feedServerEt);
        feedCb = new CheckBox(this);
        feedCb.setText("System Feed on — post every alert as JSON to the machine topic");
        feedCb.setTextColor(Ui.TXT);
        feedCb.setTextSize(13.5f);
        feedCb.setChecked(prefs.feedOn());
        feedCb.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        feed.addView(feedCb);
        TextView feedStatus = Ui.tv(this, "", 12f, Ui.DIM, false);
        Runnable feedRefresh = () -> {
            long fa = prefs.feedLastAt();
            feedStatus.setText(fa <= 0
                    ? "System Feed: nothing sent yet."
                    : "System Feed: last send at "
                        + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                                .format(new java.util.Date(fa))
                        + (prefs.feedLastOk() ? " — delivered." : " — FAILED (check topic, server and internet)."));
        };
        feedRefresh.run();
        TextView feedTest = Ui.pill(this, "SEND TEST SIGNAL", Ui.CARD_ALT, Ui.TXT);
        feedTest.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        feedTest.setGravity(Gravity.CENTER);
        feedTest.setOnClickListener(v -> {
            String topic = feedTopicEt.getText().toString().trim();
            String server = SystemFeed.normalizeServer(feedServerEt.getText().toString());
            if (topic.isEmpty()) {
                Toast.makeText(this, "Enter a machine topic first", Toast.LENGTH_LONG).show();
                return;
            }
            new Thread(() -> {
                boolean ok = false;
                try {
                    ok = NtfyClient.postRaw(server, topic,
                            SystemFeed.testPayload(SystemFeed.nowIst()).toString());
                } catch (Throwable ignored) { }
                prefs.setFeedStatus(System.currentTimeMillis(), ok);
                prefs.stampChannel(Prefs.CH_FEED, ok); // v59 feed health
                final boolean sent = ok;
                runOnUiThread(() -> {
                    feedRefresh.run();
                    Toast.makeText(this, sent
                            ? "Test signal sent to " + topic
                            : "Test failed — check topic, server and internet",
                            Toast.LENGTH_LONG).show();
                });
            }).start();
        });
        feed.addView(feedTest);
        feed.addView(feedStatus);
        feed.addView(Ui.tv(this, "Pick a long, unguessable topic name — on a public ntfy server, "
                + "anyone who knows the topic name can read what is posted to it.", 11.5f, Ui.FAINT, false));
        body.addView(feed);

        // ---- feed health card (v59) ----
        // Every channel, its last known post result and time - from
        // the stamps the shared send paths record on every post
        // (human dual-send, feed send, and the test buttons). The
        // card never guesses: an unstamped channel says so.
        LinearLayout health = Ui.card(this);
        health.addView(Ui.sectionTitle(this, "Feed health · last posts"));
        health.addView(Ui.tv(this, "What each channel last did, from the posts themselves - "
                + "every alert path stamps its result here as it sends.", 12.5f, Ui.DIM, false));
        TextView healthText = Ui.mono(this, "", 12, Ui.TXT, false);
        healthText.setPadding(0, Ui.dp(this, 8), 0, 0);
        health.addView(healthText);
        Runnable healthRefresh = () -> {
            long now = System.currentTimeMillis();
            java.util.List<String> ls = FeedHealth.lines(prefs.feedOn(),
                    SystemFeed.normalizeServer(prefs.feedServer()),
                    new FeedHealth.Chan("Human · stored topic", prefs.ntfyTopic(),
                            prefs.chanAttemptAt(Prefs.CH_STORED), prefs.chanSuccessAt(Prefs.CH_STORED),
                            prefs.chanLastOk(Prefs.CH_STORED)),
                    new FeedHealth.Chan("Human · private topic", prefs.privateTopic(),
                            prefs.chanAttemptAt(Prefs.CH_PRIVATE), prefs.chanSuccessAt(Prefs.CH_PRIVATE),
                            prefs.chanLastOk(Prefs.CH_PRIVATE)),
                    new FeedHealth.Chan("Machine feed", prefs.feedTopic(),
                            prefs.chanAttemptAt(Prefs.CH_FEED), prefs.chanSuccessAt(Prefs.CH_FEED),
                            prefs.chanLastOk(Prefs.CH_FEED)),
                    now);
            healthText.setText(String.join("\n", ls));
        };
        healthRefresh.run();
        TextView healthResult = Ui.tv(this, "", 12.5f, Ui.TXT, false);
        healthResult.setPadding(0, Ui.dp(this, 8), 0, 0);
        TextView testAll = Ui.pill(this, "SEND REAL TEST TO ALL", Ui.CARD_ALT, Ui.TXT);
        testAll.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        testAll.setGravity(Gravity.CENTER);
        testAll.setOnClickListener(v -> {
            final String stored = topicEt.getText().toString().trim();
            final String privTopic = prefs.privateTopic();
            final String feedTopic = feedTopicEt.getText().toString().trim();
            final String server = SystemFeed.normalizeServer(feedServerEt.getText().toString());
            new Thread(() -> {
                String testBody = "Feed-health test from IraThinker Settings: this readable alert "
                        + "went to BOTH of your human topics, and a test JSON went to the machine "
                        + "feed topic. If a channel is missing this, its leg failed - the Settings "
                        + "feed-health card shows which.";
                boolean okStored = false, okPriv = false;
                if (!stored.isEmpty()) {
                    okStored = NtfyClient.post(stored, "IraThinker test — all channels", testBody, "bell");
                    prefs.stampChannel(Prefs.CH_STORED, okStored);
                }
                if (!privTopic.isEmpty() && !privTopic.equals(stored)) {
                    okPriv = NtfyClient.post(privTopic, "IraThinker test — all channels", testBody, "bell");
                    prefs.stampChannel(Prefs.CH_PRIVATE, okPriv);
                } else if (privTopic.equals(stored)) {
                    okPriv = okStored; // one topic wearing both hats: the single post above is both legs
                    prefs.stampChannel(Prefs.CH_PRIVATE, okPriv);
                }
                boolean okFeed = false;
                if (!feedTopic.isEmpty()) {
                    try {
                        okFeed = NtfyClient.postRaw(server, feedTopic,
                                SystemFeed.testPayload(SystemFeed.nowIst()).toString());
                    } catch (Throwable ignored) { }
                    prefs.setFeedStatus(System.currentTimeMillis(), okFeed);
                    prefs.stampChannel(Prefs.CH_FEED, okFeed);
                }
                final boolean fs = okStored, fp = okPriv, ff = okFeed;
                final String report =
                        FeedHealth.legLine("Stored topic", fs, stored.isEmpty() ? "no topic set" : stored)
                        + "\n" + FeedHealth.legLine("Private topic", fp,
                                privTopic.isEmpty() ? "no topic set" : privTopic.equals(stored) ? privTopic + " (same as stored)" : privTopic)
                        + "\n" + FeedHealth.legLine("Machine feed", ff, feedTopic.isEmpty() ? "no topic set" : feedTopic);
                runOnUiThread(() -> {
                    healthResult.setText(report);
                    healthRefresh.run();
                    Toast.makeText(this, fs && fp && ff ? "All three channels sent"
                            : "Some legs failed — see the card above", Toast.LENGTH_LONG).show();
                });
            }).start();
        });
        health.addView(testAll);
        health.addView(healthResult);
        body.addView(health);

        // ---- trading card ----
        LinearLayout trading = Ui.card(this);
        trading.addView(Ui.sectionTitle(this, "Trading"));
        trading.addView(label("Trading capital (Rs)"));
        capitalEt = field(String.valueOf((long) prefs.capital()), true);
        trading.addView(capitalEt);
        trading.addView(label("Max loss per trade (%)"));
        maxLossEt = field(String.valueOf(prefs.maxLossPct()), true);
        trading.addView(maxLossEt);
        trading.addView(label("Minimum IraScore for buy alerts (0 = alert on all)"));
        minScoreEt = field(String.valueOf(prefs.minAlertScore()), true);
        trading.addView(minScoreEt);
        trading.addView(Ui.tv(this, "IraScore grades every setup 0-100 (break strength, trend, RSI, "
                + "Time Machine odds). Set e.g. 70 and only strong setups will ping you.", 12f, Ui.DIM, false));
        body.addView(trading);

        // ---- watchdog card ----
        LinearLayout watch = Ui.card(this);
        watch.addView(Ui.sectionTitle(this, "Background watchdog · 08:30–24:00 + gold overnight"));
        watch.addView(Ui.tv(this, "Keeps IraThinker checking every minute from 08:30 to midnight - "
                + "pre-market brief, instant buy alerts, square-off reminder - even with the app closed. "
                + "Overnight (00:00–08:30) it wakes every 5 minutes for gold only: XAU/USD trades "
                + "around the clock on Binance, so your gold price alerts can fire at night too. "
                + "Android requires a small persistent notification while it runs. "
                + "For it to survive battery optimisation, allow the exemption below (one time).", 12.5f, Ui.DIM, false));
        watchdogCb = new CheckBox(this);
        watchdogCb.setText("Watchdog always on (alerts 08:30-24:00 · gold overnight)");
        watchdogCb.setTextColor(Ui.TXT);
        watchdogCb.setTextSize(13.5f);
        watchdogCb.setChecked(prefs.watchdogOn());
        watchdogCb.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        watch.addView(watchdogCb);
        TextView batt = Ui.pill(this, "ALLOW ALWAYS-ON (BATTERY EXEMPTION)", Ui.CARD_ALT, Ui.TXT);
        batt.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        batt.setGravity(Gravity.CENTER);
        batt.setOnClickListener(v -> {
            try {
                android.content.Intent bi = new android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                bi.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(bi);
            } catch (Exception e) {
                Toast.makeText(this, "Open system Settings > Apps > IraThinker > Battery > Unrestricted",
                        Toast.LENGTH_LONG).show();
            }
        });
        watch.addView(batt);
        body.addView(watch);

        // ---- dhan card ----
        LinearLayout dhan = Ui.card(this);
        dhan.addView(Ui.sectionTitle(this, "Dhan · next phase"));
        dhan.addView(Ui.tv(this, "We start on Yahoo. When we move to Dhan live data, the app will use "
                + "these — stored only on this phone. The access token is generated on web.dhan.co "
                + "and stays valid 24 hours.", 12.5f, Ui.DIM, false));
        dhanIdEt = field(prefs.dhanClientId(), true);
        dhanIdEt.setHint("Dhan client ID");
        dhan.addView(dhanIdEt);
        dhanTokenEt = field(prefs.dhanToken(), false);
        dhanTokenEt.setHint("Dhan access token");
        dhanTokenEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        dhan.addView(dhanTokenEt);
        body.addView(dhan);

        // ---- save ----
        TextView save = Ui.pill(this, "SAVE SETTINGS", Ui.BLUE, Ui.TXT);
        save.setTextSize(14);
        save.setBackground(Ui.accentBtnBg(this));
        save.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        save.setGravity(Gravity.CENTER);
        save.setOnClickListener(v -> {
            prefs.setNtfyTopic(topicEt.getText().toString());
            prefs.setFeedTopic(feedTopicEt.getText().toString());
            prefs.setFeedServer(feedServerEt.getText().toString());
            prefs.setFeedOn(feedCb.isChecked());
            try { prefs.setCapital(Double.parseDouble(capitalEt.getText().toString())); } catch (Exception ignored) { }
            try { prefs.setMaxLossPct(Double.parseDouble(maxLossEt.getText().toString())); } catch (Exception ignored) { }
            prefs.setAlertsOn(alertsCb.isChecked());
            try {
                int ms = Integer.parseInt(minScoreEt.getText().toString().trim());
                prefs.setMinAlertScore(Math.max(0, Math.min(100, ms)));
            } catch (Exception ignored) { }
            prefs.setWatchdogOn(watchdogCb.isChecked());
            if (watchdogCb.isChecked()) WatchService.start(this);
            else stopService(new android.content.Intent(this, WatchService.class));
            prefs.setDhanClientId(dhanIdEt.getText().toString());
            prefs.setDhanToken(dhanTokenEt.getText().toString());
            if (prefs.alertsOn()) Alerts.scheduleNext(this); else Alerts.cancel(this);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });
        body.addView(save);

        TextView note = Ui.tv(this, "Data note: the app runs on Yahoo Finance — free, no login, but quotes "
                + "are delayed, and commodities are the global COMEX/NYMEX benchmarks, not MCX. "
                + "The Dhan phase switches the engine to live exchange ticks.", 11.5f, Ui.FAINT, false);
        note.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(note);
    }

    private TextView label(String text) {
        TextView t = Ui.tv(this, text, 13, Ui.TXT, true);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        return t;
    }

    private EditText field(String value, boolean numeric) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(14.5f);
        e.setTextColor(Ui.TXT);
        e.setHintTextColor(Ui.FAINT);
        e.setBackground(Ui.cardBg(Ui.CARD_ALT, 10, this));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        e.setLayoutParams(lp);
        return e;
    }
}
