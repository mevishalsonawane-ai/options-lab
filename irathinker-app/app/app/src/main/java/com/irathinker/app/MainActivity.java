package com.irathinker.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final int BG = Ui.BG_BOT;
    static final int PANEL = Ui.CARD;
    static final int TXT = Ui.TXT;
    static final int DIM = Ui.DIM;
    static final int GREEN = Ui.GREEN;
    static final int RED = Ui.RED;
    static final int ORANGE = Ui.ORANGE;
    static final int BLUE = Ui.BLUE;

    private static final int MODE_TM = 0, MODE_SCAN = 1, MODE_FLOW = 2;
    private int mode = MODE_TM;

    // Written by loader pool threads, iterated on the main thread:
    // concurrent maps, whose iterators never throw
    // ConcurrentModificationException (the v28-recorded startup crash).
    private final Map<String, Analysis.Scan> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Analysis.Odds> tmOdds = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.List<Ledger.Rec> ledgerSnap;
    private volatile Ledger.Grade liveGrade;
    private final Set<String> tmLoading = new HashSet<>();
    private final List<Analysis.Scan> visible = new ArrayList<>();
    private final List<Analysis.Scan> tmVisible = new ArrayList<>();
    private ScanAdapter scanAdapter;
    private TmAdapter tmAdapter;
    private ListView list;
    private LinearLayout groupRow;
    private ScrollView flowScroll;
    private LinearLayout flowBody;
    private FlowView flowView;
    private final List<TextView> flowArrows = new ArrayList<>();
    private List<NewsClient.Item> flowNews = null;
    private boolean flowNewsLoading = false;

    private TextView statusTv, marketPill;
    private ProgressBar spinner;
    private LinearLayout chipsRow;
    private final Map<String, TextView> modePills = new HashMap<>();
    private final Map<String, TextView> groupPills = new HashMap<>();
    private final Map<String, TextView> sortPills = new HashMap<>();
    private String scanFilter = "Scanner";
    private String scanSort = "setups";
    private String scanQuery = "";
    private android.widget.EditText searchEt;
    private LinearLayout sortRow;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private int loadBatch = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean resumed = false;
    private int flowPulseIdx = 0;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateMarketLine();
            boolean commOnly = commOnlyNow();
            if (marketOpenNow() || commOnly) {
                statusTv.setText(commOnly ? "Auto-refreshing commodities..." : "Auto-refreshing...");
                for (Universe.Sym s : wanted()) {
                    if (commOnly && !Universe.isCommodity(s)) continue;
                    cache.remove(s.yahoo);
                }
                loadMissing();
            }
            ui.postDelayed(this, 60000);
        }
    };

    private final Runnable flowPulse = new Runnable() {
        @Override public void run() {
            if (!resumed || mode != MODE_FLOW) return;
            for (int i = 0; i < flowArrows.size(); i++) {
                flowArrows.get(i).setTextColor(i == flowPulseIdx % Math.max(1, flowArrows.size())
                        ? Ui.BLUE : Ui.FAINT);
            }
            flowPulseIdx++;
            ui.postDelayed(this, 550);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.screenBg());
        int pad = Ui.dp(this, 14);
        root.setPadding(pad, pad, pad, pad);
        Ui.applySystemBarPadding(root);

        // header
        LinearLayout header = Ui.card(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        try {
            Drawable icon = getPackageManager().getApplicationIcon(getPackageName());
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
            ilp.setMargins(0, 0, Ui.dp(this, 12), 0);
            header.addView(iv, ilp);
        } catch (Exception ignored) { }
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.tv(this, "IraThinker", 21, Ui.TXT, true));
        titles.addView(Ui.tv(this, "India day-trading copilot", 12, Ui.DIM, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = Ui.pill(this, "SETTINGS", Ui.CARD_ALT, Ui.TXT);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings);
        LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) header.getLayoutParams();
        hlp.setMargins(0, 0, 0, Ui.dp(this, 10));
        header.setLayoutParams(hlp);
        root.addView(header);

        // market row
        LinearLayout mrow = new LinearLayout(this);
        mrow.setOrientation(LinearLayout.HORIZONTAL);
        mrow.setGravity(Gravity.CENTER_VERTICAL);
        marketPill = Ui.pill(this, "...", Ui.CARD_ALT, Ui.DIM);
        mrow.addView(marketPill);
        mrow.addView(Ui.tv(this, "  Yahoo data (delayed) · Dhan phase next", 11.5f, Ui.FAINT, false));
        root.addView(mrow);

        // last-crash card (CrashGuard): if the previous run died, show the
        // recorded failure here so it can be screenshotted and fixed.
        try {
            String crash = CrashGuard.lastCrash(this);
            if (!crash.isEmpty()) {
                LinearLayout cc = Ui.card(this);
                cc.addView(Ui.tv(this, "⚠ IraThinker crashed last time — screenshot this card and send it to Muse, then dismiss.",
                        13, Ui.RED, true));
                String shown = crash.length() > 700 ? crash.substring(0, 700) : crash;
                cc.addView(Ui.mono(this, shown, 10.5f, Ui.DIM, false));
                TextView dis = Ui.pill(this, "DISMISS", Ui.CARD_ALT, Ui.TXT);
                dis.setOnClickListener(v -> { CrashGuard.clear(this); cc.setVisibility(View.GONE); });
                cc.addView(dis);
                root.addView(cc);
            }
        } catch (Exception ignored) { }

        // index chips
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(chipsRow);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-1, -2);
        chlp.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        root.addView(hsv, chlp);

        // mode segmented control
        root.addView(segmented(new String[]{"Time Machine", "Scanner", "Flow"},
                new String[]{"tm", "scan", "flow"}, modePills,
                key -> setMode(key.equals("tm") ? MODE_TM : key.equals("scan") ? MODE_SCAN : MODE_FLOW)));

        // group filters (scanner mode only)
        groupRow = segmented(new String[]{"All", "Indices", "NIFTY", "BANK", "Commod", "★ Watch"},
                new String[]{"Scanner", "Indices", "NIFTY 50", "BANKNIFTY", "Commodities", "Watchlist"}, groupPills,
                key -> { scanFilter = key; stylePills(groupPills, scanFilter); refreshScanList(); loadMissing(); });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.setMargins(0, 0, 0, Ui.dp(this, 4));
        root.addView(groupRow, glp);

        // scanner search + sort (scanner mode only)
        searchEt = new android.widget.EditText(this);
        searchEt.setHint("Search stocks…");
        searchEt.setHintTextColor(Ui.FAINT);
        searchEt.setTextColor(Ui.TXT);
        searchEt.setTextSize(13.5f);
        searchEt.setSingleLine(true);
        searchEt.setBackground(Ui.cardBg(Ui.CARD_ALT, 10, this));
        searchEt.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams selp = new LinearLayout.LayoutParams(-1, -2);
        selp.setMargins(0, 0, 0, Ui.dp(this, 4));
        searchEt.setLayoutParams(selp);
        searchEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                scanQuery = s.toString().trim().toLowerCase();
                if (mode == MODE_SCAN) refreshScanList();
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        root.addView(searchEt);
        sortRow = segmented(new String[]{"Setups", "% Move", "Score", "A–Z"},
                new String[]{"setups", "move", "score", "az"}, sortPills,
                key -> { scanSort = key; stylePills(sortPills, scanSort); refreshScanList(); });
        root.addView(sortRow);
        stylePills(sortPills, scanSort);

        // status
        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setVisibility(View.GONE);
        srow.addView(spinner);
        statusTv = Ui.tv(this, "Loading...", 12.5f, Ui.DIM, false);
        statusTv.setPadding(Ui.dp(this, 6), 0, 0, 0);
        srow.addView(statusTv);
        root.addView(srow);

        // content frame: list OR flow
        FrameLayout frame = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        scanAdapter = new ScanAdapter();
        tmAdapter = new TmAdapter();
        list.setAdapter(tmAdapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Analysis.Scan s = (mode == MODE_TM) ? tmVisible.get(pos) : visible.get(pos);
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra("name", s.sym.name);
            i.putExtra("yahoo", s.sym.yahoo);
            startActivity(i);
        });
        frame.addView(list);
        flowScroll = new ScrollView(this);
        flowBody = new LinearLayout(this);
        flowBody.setOrientation(LinearLayout.VERTICAL);
        flowScroll.addView(flowBody);
        flowScroll.setVisibility(View.GONE);
        frame.addView(flowScroll);
        LinearLayout.LayoutParams flp2 = new LinearLayout.LayoutParams(-1, 0, 1);
        flp2.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        root.addView(frame, flp2);

        // refresh
        TextView refresh = Ui.pill(this, "REFRESH  ANALYSIS", Ui.BLUE, Ui.TXT);
        refresh.setTextSize(14);
        refresh.setBackground(Ui.accentBtnBg(this));
        refresh.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        refresh.setOnClickListener(v -> {
            if (commOnlyNow()) {
                // after the equity close, a refresh re-works only the
                // commodities - the day's equity record stays frozen
                for (Universe.Sym s : Universe.all()) {
                    if (Universe.isCommodity(s)) { cache.remove(s.yahoo); tmOdds.remove(s.yahoo); }
                }
            } else {
                cache.clear(); tmOdds.clear();
            }
            loadMissing();
        });
        root.addView(refresh);
        LinearLayout toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(-1, -2);
        trlp.setMargins(0, Ui.dp(this, 8), 0, 0);
        toolRow.setLayoutParams(trlp);
        TextView labBtn = Ui.pill(this, "STRATEGY LAB · TEST BUY/SELL", Ui.CARD_ALT, Ui.ORANGE);
        labBtn.setTextSize(12.5f);
        labBtn.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        labBtn.setGravity(Gravity.CENTER);
        labBtn.setOnClickListener(v -> startActivity(new Intent(this, LabActivity.class)));
        toolRow.addView(labBtn, new LinearLayout.LayoutParams(0, -2, 1));
        TextView journalBtn = Ui.pill(this, "PAPER JOURNAL", Ui.CARD_ALT, Ui.DIM);
        journalBtn.setTextSize(12.5f);
        journalBtn.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        journalBtn.setGravity(Gravity.CENTER);
        journalBtn.setOnClickListener(v -> startActivity(new Intent(this, JournalActivity.class)));
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(0, -2, 1);
        jlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        toolRow.addView(journalBtn, jlp);
        root.addView(toolRow);
        LinearLayout toolRow2 = new LinearLayout(this);
        toolRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tr2lp = new LinearLayout.LayoutParams(-1, -2);
        tr2lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        toolRow2.setLayoutParams(tr2lp);
        TextView testBtn = Ui.pill(this, "SIGNAL TEST · EVERY SIGNAL: TARGET OR STOP?", Ui.CARD_ALT, Ui.ORANGE);
        testBtn.setTextSize(12.5f);
        testBtn.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        testBtn.setGravity(Gravity.CENTER);
        testBtn.setOnClickListener(v -> startActivity(new Intent(this, SignalTestActivity.class)));
        toolRow2.addView(testBtn, new LinearLayout.LayoutParams(0, -2, 1));
        TextView meterBtn = Ui.pill(this, "MARKET METER · BULL/BEAR GAUGE", Ui.CARD_ALT, Ui.ORANGE);
        meterBtn.setTextSize(12.5f);
        meterBtn.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        meterBtn.setGravity(Gravity.CENTER);
        meterBtn.setOnClickListener(v -> startActivity(new Intent(this, MeterActivity.class)));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1);
        mlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        toolRow2.addView(meterBtn, mlp);
        root.addView(toolRow2);
        LinearLayout toolRow3 = new LinearLayout(this);
        toolRow3.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tr3lp = new LinearLayout.LayoutParams(-1, -2);
        tr3lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        toolRow3.setLayoutParams(tr3lp);
        TextView stratBtn = Ui.pill(this, "STRATEGIES · MOM60", Ui.CARD_ALT, Ui.ORANGE);
        stratBtn.setTextSize(12.5f);
        stratBtn.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        stratBtn.setGravity(Gravity.CENTER);
        stratBtn.setOnClickListener(v -> startActivity(new Intent(this, StrategiesActivity.class)));
        toolRow3.addView(stratBtn, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolRow3);
        TextView note = Ui.tv(this, "Home is the Time Machine board — every live setup with its honest odds. "
                + "Flow shows how a signal travels from data to your phone.", 11, Ui.FAINT, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(note);

        setContentView(root);
        Alerts.scheduleNext(this);
        // notifications permission (watchdog's persistent notification, API 33+)
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        WatchService.start(this);
        updateMarketLine();
        setMode(MODE_TM);
        loadMissing();
    }

    private interface Pick { void onPick(String key); }

    private LinearLayout segmented(String[] labels, String[] keys, Map<String, TextView> store, Pick pick) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.cardBg(Ui.CARD, 40, this));
        int fp = Ui.dp(this, 4);
        bar.setPadding(fp, fp, fp, fp);
        for (int i = 0; i < labels.length; i++) {
            final String key = keys[i];
            TextView p = Ui.pill(this, labels[i], Ui.CARD, Ui.DIM);
            p.setTextSize(12.5f);
            p.setOnClickListener(v -> pick.onPick(key));
            bar.addView(p, new LinearLayout.LayoutParams(0, -2, 1));
            store.put(key, p);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        bar.setLayoutParams(lp);
        return bar;
    }

    private void stylePills(Map<String, TextView> pills, String activeKey) {
        for (Map.Entry<String, TextView> e : pills.entrySet()) {
            boolean on = e.getKey().equals(activeKey);
            e.getValue().setBackground(Ui.pillBg(on ? Ui.BLUE : Ui.CARD, this));
            e.getValue().setTextColor(on ? Ui.TXT : Ui.DIM);
        }
    }

    private void setMode(int m) {
        mode = m;
        stylePills(modePills, m == MODE_TM ? "tm" : m == MODE_SCAN ? "scan" : "flow");
        groupRow.setVisibility(m == MODE_SCAN ? View.VISIBLE : View.GONE);
        if (searchEt != null) searchEt.setVisibility(m == MODE_SCAN ? View.VISIBLE : View.GONE);
        if (sortRow != null) sortRow.setVisibility(m == MODE_SCAN ? View.VISIBLE : View.GONE);
        list.setVisibility(m == MODE_FLOW ? View.GONE : View.VISIBLE);
        flowScroll.setVisibility(m == MODE_FLOW ? View.VISIBLE : View.GONE);
        if (m == MODE_TM) { list.setAdapter(tmAdapter); refreshTmList(); }
        if (m == MODE_SCAN) { list.setAdapter(scanAdapter); refreshScanList(); }
        if (m == MODE_FLOW) { buildFlow(); ui.removeCallbacks(flowPulse); ui.post(flowPulse); }
        // the Flow globe runs continuously while the app is open (user
        // request, v40): tab switches no longer pause its animation loop
        loadMissing();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        updateMarketLine();
        ui.removeCallbacks(autoRefresh);
        ui.postDelayed(autoRefresh, 60000);
        if (mode == MODE_FLOW) {
            ui.removeCallbacks(flowPulse); ui.post(flowPulse);
        }
        if (flowView != null) flowView.setLive(true); // continuous while the app is open (v40)
        maybePostMoveNotice();
    }

    /** One-time nudge (v41): tell the user, on his current public
     *  topic, that a private alert channel is ready and how to move.
     *  Retries on later resumes until a post actually lands. */
    private void maybePostMoveNotice() {
        Prefs p = new Prefs(this);
        if (p.privateLive() || p.moveNoticeSent()) return;
        String oldTopic = p.ntfyTopic();
        new Thread(() -> {
            boolean ok = NtfyClient.post(oldTopic, "IraThinker - action needed: private alerts",
                    "Your alert topic '" + oldTopic + "' is a public name anyone can guess. "
                    + "IraThinker has a private channel ready for you. Open the app -> Settings -> "
                    + "Private alert channel -> OPEN PRIVATE TOPIC IN NTFY and subscribe, then tap GO PRIVATE. "
                    + "Until you do, alerts keep coming here as well.", "lock");
            if (ok) new Prefs(this).markMoveNotice();
        }).start();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(autoRefresh);
        ui.removeCallbacks(flowPulse);
        if (flowView != null) flowView.setLive(false); // stop the 40ms canvas loop off-screen
    }

    static boolean marketOpenNow() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDateTime now = LocalDateTime.now(ist);
        if (now.getDayOfWeek().getValue() > 5) return false;
        if (MarketCalendar.isHoliday(now.toLocalDate())) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    private void updateMarketLine() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDateTime now = LocalDateTime.now(ist);
        boolean open = marketOpenNow();
        String txt = (open ? "● MARKET OPEN  " : "● MARKET CLOSED  ")
                + now.format(DateTimeFormatter.ofPattern("hh:mm a"));
        int color = open ? Ui.GREEN : Ui.DIM;
        if (open && cache != null && !cache.isEmpty()) {
            long newest = 0;
            for (Analysis.Scan sc : cache.values()) if (sc.lastTs > newest) newest = sc.lastTs;
            String fs = Analysis.feedState(newest, System.currentTimeMillis() / 1000, true);
            if (!fs.isEmpty()) {
                txt += " · YAHOO " + fs.substring(5);
                if (fs.equals("FEED STALE")) color = Ui.RED;
                else if (fs.equals("FEED DELAYED")) color = Ui.ORANGE;
            }
        }
        marketPill.setText(txt);
        marketPill.setTextColor(color);
    }

    /** Symbols the current view needs. TM and Flow always need the whole universe. */
    private List<Universe.Sym> wanted() {
        List<Universe.Sym> out = new ArrayList<>();
        if (mode != MODE_SCAN) return Universe.all();
        Prefs wp = new Prefs(this);
        for (Universe.Sym s : Universe.all()) {
            switch (scanFilter) {
                case "Indices": if (s.group.equals(Universe.G_INDEX)) out.add(s); break;
                case "NIFTY 50": if (s.group.equals(Universe.G_NIFTY)) out.add(s); break;
                case "BANKNIFTY": if (Universe.isBankMember(s.yahoo)) out.add(s); break;
                case "Watchlist": if (wp.isWatched(s.yahoo)) out.add(s); break;
                case "Commodities": if (s.group.equals(Universe.G_COMM)) out.add(s); break;
                default: out.add(s);
            }
        }
        return out;
    }

    // ---------------- scanner list ----------------

    private void refreshScanList() {
        visible.clear();
        for (Universe.Sym s : wanted()) {
            if (mode != MODE_SCAN) break;
            if (!scanQuery.isEmpty() && !s.name.toLowerCase().contains(scanQuery)) continue;
            Analysis.Scan sc = cache.get(s.yahoo);
            if (sc != null) visible.add(sc);
        }
        switch (scanSort) {
            case "move":
                visible.sort((a, b) -> Double.compare(
                        Double.isNaN(b.dayPct) ? 0 : Math.abs(b.dayPct),
                        Double.isNaN(a.dayPct) ? 0 : Math.abs(a.dayPct)));
                break;
            case "score": {
                Map<String, Integer> sm = new HashMap<>();
                for (Analysis.Scan sc : visible) sm.put(sc.sym.yahoo, Analysis.liteScore(sc));
                visible.sort((a, b) -> Integer.compare(
                        sm.getOrDefault(b.sym.yahoo, 0), sm.getOrDefault(a.sym.yahoo, 0)));
                break;
            }
            case "az":
                visible.sort((a, b) -> a.sym.name.compareToIgnoreCase(b.sym.name));
                break;
            default:
                visible.sort((a, b) -> {
                    int ra = rank(a), rb = rank(b);
                    if (ra != rb) return Integer.compare(ra, rb);
                    double pa = Double.isNaN(a.dayPct) ? 0 : Math.abs(a.dayPct);
                    double pb = Double.isNaN(b.dayPct) ? 0 : Math.abs(b.dayPct);
                    return Double.compare(pb, pa);
                });
        }
        scanAdapter.notifyDataSetChanged();
        renderChips();
    }

    private static int rank(Analysis.Scan s) {
        switch (s.setup) {
            case ORB_UP: case ORB_DOWN: return 0;
            case OVERSOLD_BOUNCE: return 1;
            case ABOVE_20DMA: case BELOW_20DMA: return 2;
            default: return 3;
        }
    }

    private void renderChips() {
        chipsRow.removeAllViews();
        for (Universe.Sym s : Universe.all()) {
            if (!s.group.equals(Universe.G_INDEX)) continue;
            Analysis.Scan sc = cache.get(s.yahoo);
            if (sc == null || Double.isNaN(sc.ltp)) continue;
            String txt = String.format("%s  %s  %s%.2f%%", s.name, fmt(sc.ltp),
                    Double.isNaN(sc.dayPct) ? "" : (sc.dayPct >= 0 ? "▲+" : "▼"), sc.dayPct);
            TextView chip = Ui.pill(this, txt, Ui.CARD_ALT,
                    Double.isNaN(sc.dayPct) ? Ui.DIM : (sc.dayPct >= 0 ? Ui.GREEN : Ui.RED));
            chip.setTypeface(android.graphics.Typeface.MONOSPACE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(this, 6), 0);
            chipsRow.addView(chip, lp);
        }
    }

    // ---------------- time machine board ----------------

    private void refreshTmList() {
        tmVisible.clear();
        for (Analysis.Scan sc : cache.values()) if (sc.hasPlan()) tmVisible.add(sc);
        tmVisible.sort((a, b) -> {
            Integer sa = BuyAlerts.cachedScore(a.sym.yahoo), sb = BuyAlerts.cachedScore(b.sym.yahoo);
            int ca = sa == null ? -1 : sa, cb = sb == null ? -1 : sb;
            if (ca != cb) return Integer.compare(cb, ca);
            Analysis.Odds oa = tmOdds.get(a.sym.yahoo), ob = tmOdds.get(b.sym.yahoo);
            double ta = oa == null ? -1 : oa.targetFirst, tb = ob == null ? -1 : ob.targetFirst;
            return Double.compare(tb, ta);
        });
        tmAdapter.notifyDataSetChanged();
        renderChips();
        for (Analysis.Scan sc : tmVisible) ensureOdds(sc);
    }

    private void ensureOdds(Analysis.Scan sc) {
        final String key = sc.sym.yahoo;
        synchronized (tmOdds) {
            if (tmOdds.containsKey(key) || tmLoading.contains(key)) return;
            tmLoading.add(key);
        }
        pool.execute(() -> {
            try {
                YahooClient.Chart intra = YahooClient.intraday(key);
                java.util.List<Ledger.Rec> snap = ledgerSnap;
                if (snap == null) {
                    try { snap = Ledger.load(this); ledgerSnap = snap; } catch (Throwable ignored) { }
                }
                Analysis.Odds o = Analysis.timeMachine(intra, sc, 3000, snap);
                BuyAlerts.noteScore(key, Analysis.fullScore(sc, o));
                synchronized (tmOdds) { tmOdds.put(key, o); tmLoading.remove(key); }
                runOnUiThread(() -> { if (mode == MODE_TM) refreshTmList(); if (mode == MODE_FLOW) buildFlow(); });
            } catch (Exception e) {
                synchronized (tmOdds) { tmLoading.remove(key); }
            }
        });
    }

    // ---------------- flow view ----------------

    private void buildFlow() {
        flowBody.removeAllViews();
        flowArrows.clear();
        Prefs prefs = new Prefs(this);
        int total = Universe.all().size();
        int loaded = cache.size();
        int orReady = 0, up = 0, down = 0, bounce = 0, adv = 0, dec = 0;
        List<Analysis.Scan> plans = new ArrayList<>();
        for (Analysis.Scan sc : cache.values()) {
            if (sc.orReady) orReady++;
            if (sc.hasPlan()) { plans.add(sc); if (sc.setup == Analysis.Setup.ORB_UP) up++; else down++; }
            if (sc.setup == Analysis.Setup.OVERSOLD_BOUNCE) bounce++;
            if (!Double.isNaN(sc.dayPct)) { if (sc.dayPct >= 0) adv++; else dec++; }
        }
        int riskPass = 0;
        for (Analysis.Scan sc : plans) {
            if (Analysis.budget(sc, prefs.capital(), prefs.maxLossPct()).pass) riskPass++;
        }
        String bestLine = "No odds computed yet — open the Time Machine tab.";
        double best = -1;
        for (Map.Entry<String, Analysis.Odds> e : tmOdds.entrySet()) {
            if (e.getValue().targetFirst > best) {
                best = e.getValue().targetFirst;
                String nm = e.getKey();
                for (Analysis.Scan sc : plans) if (sc.sym.yahoo.equals(e.getKey())) nm = sc.sym.name;
                bestLine = String.format("Best odds: %s — target first %.0f%% (%d past firings)",
                        nm, best, e.getValue().analogFires);
            }
        }
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ISO_LOCAL_DATE);
        int fired = prefs.firedCount(today);

        if (flowView == null) flowView = new FlowView(this);
        Integer bestScore = null;
        for (Analysis.Scan sc : plans) {
            Integer fs = BuyAlerts.cachedScore(sc.sym.yahoo);
            if (fs != null && (bestScore == null || fs > bestScore)) bestScore = fs;
        }
        double bpNow = (adv + dec) == 0 ? 50 : adv * 100.0 / (adv + dec);
        String[] rgNow = Analysis.regime(cache.get("^NSEI"), cache.get("^INDIAVIX"), bpNow);
        flowView.setStats(loaded, total, plans.size(), fired, bestScore, "Regime: " + rgNow[0]);
        flowView.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 520)));
        flowBody.addView(flowView);
        flowView.setLive(true);
        stage(1, "DATA IN", loaded + " / " + total + " symbols",
                "Yahoo Finance — delayed quotes, 5-min bars + daily history.",
                "NIFTY 50 · BANKNIFTY · indices · commodities",
                "Dhan live tick feed arrives in the next phase.");
        double breadthPct = (adv + dec) == 0 ? 50 : adv * 100.0 / (adv + dec);
        String[] regime = Analysis.regime(cache.get("^NSEI"), cache.get("^INDIAVIX"), breadthPct);
        java.util.Map<String, double[]> secAgg = new java.util.HashMap<>();
        for (Analysis.Scan sc : cache.values()) {
            String sec = SectorMap.sectorOf(sc.sym.yahoo);
            if (sec == null || Double.isNaN(sc.dayPct)) continue;
            double[] a = secAgg.computeIfAbsent(sec, kk -> new double[2]);
            a[0] += sc.dayPct; a[1] += 1;
        }
        String secLine = "Sector heat builds as stocks load.";
        String hotName = null, coldName = null;
        double hotAvg = -1e9, coldAvg = 1e9;
        int hotN = 0, coldN = 0;
        for (java.util.Map.Entry<String, double[]> e : secAgg.entrySet()) {
            if (e.getValue()[1] < 3) continue;
            double avg = e.getValue()[0] / e.getValue()[1];
            if (avg > hotAvg) { hotAvg = avg; hotName = e.getKey(); hotN = (int) e.getValue()[1]; }
            if (avg < coldAvg) { coldAvg = avg; coldName = e.getKey(); coldN = (int) e.getValue()[1]; }
        }
        if (hotName != null) {
            secLine = String.format("Sector heat: %s %+.2f%% (%d stocks) leads · %s %+.2f%% (%d) lags.",
                    hotName, hotAvg, hotN, coldName, coldAvg, coldN);
        }
        stage(2, "MARKET STUDY", "regime: " + regime[0],
                indexLine(),
                "Breadth " + adv + "▲ / " + dec + "▼ (" + String.format("%.0f%%", breadthPct) + " advancing).",
                secLine,
                "Regime: " + regime[0] + " — " + regime[1] + ".");
        if (flowNews == null && !flowNewsLoading) {
            flowNewsLoading = true;
            new Thread(() -> {
                List<NewsClient.Item> items = NewsClient.fetch("NIFTY SENSEX BANKNIFTY stock market", 3);
                runOnUiThread(() -> { flowNews = items; flowNewsLoading = false; if (mode == MODE_FLOW) buildFlow(); });
            }).start();
        }
        String[] newsLines;
        if (flowNews == null) newsLines = new String[]{"Fetching headlines..."};
        else if (flowNews.isEmpty()) newsLines = new String[]{"Headlines unavailable right now."};
        else {
            newsLines = new String[flowNews.size()];
            for (int i = 0; i < flowNews.size(); i++) {
                NewsClient.Item n = flowNews.get(i);
                int tone = NewsClient.toneOf(n.title);
                newsLines[i] = "• " + n.title + (n.source.isEmpty() ? "" : "  (" + n.source + ")")
                        + (tone > 0 ? "  [bullish tone]" : tone < 0 ? "  [bearish tone]" : "");
            }
        }
        stage(3, "NEWS · CATALYST CHECK", flowNews == null ? "..." : flowNews.size() + " headlines",
                newsLines);
        stage(4, "SCANNER", plans.size() + " plans",
                "Opening range (9:15–9:30) ready on " + orReady + " symbols.",
                "Breakouts up " + up + " · Breakdowns " + down + " · Oversold bounces " + bounce + ".",
                "Everything else is still inside its range — no signal, no noise.");
        stage(5, "TIME MACHINE", tmOdds.size() + " evaluated",
                "Each plan is replayed 3,000 times on the stock's own bars + its real past firings.",
                bestLine);
        stage(6, "PREDICTION ENGINE", String.format("%,d combos / stock", Predict.comboCount()),
                "Momentum, mean-reversion, trend, RSI, channel, pattern-analog and opening-range rules "
                        + "crossed with lookbacks, thresholds and horizons.",
                "Each combination is graded on the stock's older candles; only the ones that beat a coin "
                        + "flip vote on the current candles.",
                "Consensus direction + expected points are in each stock's Detail, with the ensemble's "
                        + "own holdout hit-rate printed beside them.");
        stage(7, "BUDGET & RISK GATE", riskPass + " / " + plans.size() + " pass",
                "Capital " + DetailActivity.fmtRs(prefs.capital()) + " · max loss " + prefs.maxLossPct() + "% per trade.",
                "Position sized from the stop. Anything failing the gate is blocked, not sized up.");
        List<String> sigList = new ArrayList<>();
        if (plans.isEmpty()) sigList.add("No live signals right now.");
        for (Analysis.Scan sc : plans) {
            Integer fs = BuyAlerts.cachedScore(sc.sym.yahoo);
            sigList.add((sc.setup == Analysis.Setup.ORB_UP ? "BUY " : "BUY PUT ") + sc.sym.name
                    + " · E " + fmt(sc.entry) + " · SL " + fmt(sc.stop) + " · T " + fmt(sc.target)
                    + (fs != null ? " · Score " + fs : ""));
        }
        int[] oc = prefs.outcomeCounts();
        int open = prefs.openSignals().size();
        sigList.add(String.format("Live track record: %d targets · %d stops · %d flat · %d still open",
                oc[0], oc[1], oc[2], open));
        if (prefs.minAlertScore() > 0) {
            sigList.add("Alert gate: only IraScore " + prefs.minAlertScore() + "+ setups ping you.");
        }
        stage(8, "SIGNAL → NTFY", fired + " fired today",
                sigList.toArray(new String[0]));
        TextView end = Ui.tv(this, prefs.alertsOn()
                ? "Alerts are ON — refresh every minute; only breakdowns India VIX has already confirmed, with IraScore above 65 and 10+ points to the target, notify once the break bar closes: top 3 a day, same entry/target never twice."
                : "Alerts are OFF — turn them on in Settings.", 12, prefs.alertsOn() ? Ui.GREEN : Ui.RED, true);
        end.setGravity(Gravity.CENTER);
        end.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        flowBody.addView(end);
    }

    private String indexLine() {
        StringBuilder sb = new StringBuilder();
        for (String y : new String[]{"^NSEI", "^NSEBANK", "^INDIAVIX"}) {
            Analysis.Scan sc = cache.get(y);
            if (sc != null && !Double.isNaN(sc.ltp)) {
                if (sb.length() > 0) sb.append("   ·   ");
                sb.append(sc.sym.name).append(' ').append(fmt(sc.ltp));
                if (!Double.isNaN(sc.dayPct)) sb.append(String.format(" (%+.2f%%)", sc.dayPct));
            }
        }
        return sb.length() == 0 ? "Index data loading..." : sb.toString();
    }

    private void stage(int num, String title, String status, String... lines) {
        if (num > 1) {
            TextView arrow = Ui.tv(this, "▼", 15, Ui.FAINT, true);
            arrow.setGravity(Gravity.CENTER);
            arrow.setPadding(0, Ui.dp(this, 1), 0, Ui.dp(this, 1));
            flowBody.addView(arrow);
            flowArrows.add(arrow);
        }
        LinearLayout card = Ui.card(this);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = Ui.pill(this, String.valueOf(num), Ui.BLUE, Ui.TXT);
        head.addView(badge);
        TextView t = Ui.tv(this, "  " + title, 15, Ui.TXT, true);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Ui.pill(this, status, Ui.CARD_ALT, Ui.ORANGE));
        card.addView(head);
        for (String line : lines) {
            TextView v = Ui.tv(this, line, 12.5f, Ui.DIM, false);
            v.setPadding(0, Ui.dp(this, 5), 0, 0);
            card.addView(v);
        }
        flowBody.addView(card);
    }

    // ---------------- loading ----------------

    /** After 15:30 only commodities keep working (user rule): stock
     *  and index feeds are frozen at the close, the commodity session
     *  runs into the night. */
    private boolean commOnlyNow() {
        return Analysis.commodityOnlyHours(LocalTime.now(ZoneId.of("Asia/Kolkata")));
    }

    private void loadMissing() {
        List<Universe.Sym> need = new ArrayList<>();
        boolean commOnly = commOnlyNow();
        for (Universe.Sym s : wanted()) {
            if (commOnly && !Universe.isCommodity(s)) continue; // stocks stop at 15:30
            if (!cache.containsKey(s.yahoo)) need.add(s);
        }
        if (need.isEmpty()) {
            spinner.setVisibility(View.GONE);
            statusReady();
            refreshCurrent();
            return;
        }
        // A fresh load supersedes any still-running one: the old
        // batch's completions must not touch the UI (their counter
        // ran past the total - "132/67" - and their refresh raced
        // this batch's writes).
        final int myBatch = ++loadBatch;
        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger();
        spinner.setVisibility(View.VISIBLE);
        statusTv.setText("Scanning " + need.size() + " symbols...");
        for (Universe.Sym s : need) {
            pool.execute(() -> {
                Analysis.Scan sc;
                try {
                    YahooClient.Chart intra = YahooClient.intradayShort(s.yahoo);
                    YahooClient.Chart daily = null;
                    try { daily = YahooClient.daily(s.yahoo); } catch (Exception ignored) { }
                    sc = Analysis.scan(s, intra, daily);
                } catch (Exception e) {
                    sc = new Analysis.Scan();
                    sc.sym = s;
                    sc.error = e.getMessage();
                }
                cache.put(s.yahoo, sc);
                int d = done.incrementAndGet();
                runOnUiThread(() -> {
                    if (myBatch != loadBatch) return; // superseded batch
                    statusTv.setText("Scanning... " + d + "/" + need.size());
                    refreshCurrent();
                    if (d == need.size()) {
                        spinner.setVisibility(View.GONE);
                        statusReady();
                        List<Analysis.Scan> all = new ArrayList<>();
                        for (Analysis.Scan x : cache.values()) {
                            // after 15:30, alert checks run on the
                            // commodities only - never on frozen stocks
                            if (commOnly && (x.sym == null || !Universe.isCommodity(x.sym))) continue;
                            all.add(x);
                        }
                        new Thread(() -> {
                            BuyAlerts.fireNewPlans(this, all);
                            BuyAlerts.recordLedger(this, all);
                            try { ledgerSnap = Ledger.load(this); liveGrade = null; } catch (Throwable ignored) { }
                            Prefs p2 = new Prefs(this);
                            PriceAlerts.check(this, p2, all);
                            BuyAlerts.resolveOutcomes(this, p2);
                            Ledger.resolveAll(this);
                        }).start();
                        refreshCurrent();
                    }
                });
            });
        }
    }

    private void statusReady() {
        updateMarketLine(); // freshness label must reflect the data that just landed
        int plans = 0;
        for (Analysis.Scan sc : cache.values()) if (sc.hasPlan()) plans++;
        statusTv.setText(marketOpenNow()
                ? "Live session · " + plans + " setups on the board."
                : "Market closed · showing the latest session's " + plans + " setups + odds.");
    }

    private void refreshCurrent() {
        if (mode == MODE_TM) refreshTmList();
        else if (mode == MODE_SCAN) refreshScanList();
        else buildFlow();
    }

    // ---------------- shared formatting ----------------

    static String badge(Analysis.Scan s) {
        switch (s.setup) {
            case ORB_UP: return "ORB BREAKOUT · BUY";
            case ORB_DOWN: return "ORB BREAKDOWN · BUY PUT";
            case OVERSOLD_BOUNCE: return "OVERSOLD BOUNCE · RSI " + fmt(s.rsi);
            case ABOVE_20DMA: return "ABOVE 20DMA";
            case BELOW_20DMA: return "BELOW 20DMA";
            default: return s.orReady ? "INSIDE OPENING RANGE" : "";
        }
    }

    static int badgeColor(Analysis.Scan s) {
        switch (s.setup) {
            case ORB_UP: return Ui.GREEN;
            case ORB_DOWN: return Ui.RED;
            case OVERSOLD_BOUNCE: return Ui.ORANGE;
            default: return Ui.DIM;
        }
    }

    static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%,.2f", v);
    }

    // ---------------- adapters ----------------

    private LinearLayout rowShell() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Ui.cardBg(Ui.CARD, 14, this));
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2);
        outer.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        row.setLayoutParams(outer);
        return row;
    }

    private LinearLayout nameRow(Analysis.Scan s) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        boolean watched = new Prefs(this).isWatched(s.sym.yahoo);
        // v52: gold's name carries its live feed tag - "· Binance" for
        // the XAUUSDT perpetual, "· PAXG ref" when the fallback token
        // feed is the one answering. Never an unlabelled gold price.
        String nm0 = (watched ? "★ " : "") + s.sym.name
                + (Universe.isGold(s.sym) ? BinanceClient.feedTag() : "");
        TextView nm = Ui.tv(this, nm0, 15.5f,
                watched ? Ui.ORANGE : Ui.TXT, true);
        top.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
        String pctTxt = Double.isNaN(s.dayPct) ? "" :
                String.format("%s%.2f%%", s.dayPct >= 0 ? "▲ +" : "▼ ", s.dayPct);
        top.addView(Ui.mono(this, fmt(s.ltp) + "  " + pctTxt, 14.5f,
                Double.isNaN(s.dayPct) ? Ui.TXT : (s.dayPct >= 0 ? Ui.GREEN : Ui.RED), true));
        return top;
    }

    private class ScanAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int i) { return visible.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Analysis.Scan s = visible.get(pos);
            LinearLayout row = rowShell();
            row.addView(nameRow(s));
            String b = s.error != null ? "FETCH FAILED" : badge(s);
            if (!b.isEmpty()) {
                LinearLayout brow = new LinearLayout(MainActivity.this);
                brow.setOrientation(LinearLayout.HORIZONTAL);
                brow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                blp.setMargins(0, Ui.dp(MainActivity.this, 7), 0, 0);
                brow.setLayoutParams(blp);
                brow.addView(Ui.pill(MainActivity.this, b, Ui.CARD_ALT, badgeColor(s)));
                if (s.hasPlan()) {
                    int ls = Analysis.liteScore(s);
                    TextView sp = Ui.pill(MainActivity.this, "SCORE " + ls, Ui.CARD_ALT,
                            ls >= 70 ? Ui.GREEN : ls >= 50 ? Ui.ORANGE : Ui.DIM);
                    LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-2, -2);
                    splp.setMargins(Ui.dp(MainActivity.this, 6), 0, 0, 0);
                    brow.addView(sp, splp);
                    brow.addView(Ui.mono(MainActivity.this, String.format(
                            "  E %s · SL %s · T %s", fmt(s.entry), fmt(s.stop), fmt(s.target)),
                            12, Ui.DIM, false));
                }
                row.addView(brow);
            }
            return row;
        }
    }

    private class TmAdapter extends BaseAdapter {
        @Override public int getCount() { return tmVisible.size(); }
        @Override public Object getItem(int i) { return tmVisible.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Analysis.Scan s = tmVisible.get(pos);
            LinearLayout row = rowShell();
            row.addView(nameRow(s));
            LinearLayout brow = new LinearLayout(MainActivity.this);
            brow.setOrientation(LinearLayout.HORIZONTAL);
            brow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.setMargins(0, Ui.dp(MainActivity.this, 7), 0, Ui.dp(MainActivity.this, 4));
            brow.setLayoutParams(blp);
            brow.addView(Ui.pill(MainActivity.this, badge(s), Ui.CARD_ALT, badgeColor(s)));
            brow.addView(Ui.mono(MainActivity.this, String.format("  E %s · SL %s · T %s",
                    fmt(s.entry), fmt(s.stop), fmt(s.target)), 12, Ui.DIM, false));
            row.addView(brow);

            Analysis.Odds o = tmOdds.get(s.sym.yahoo);
            if (o == null) {
                row.addView(Ui.tv(MainActivity.this, "Computing Time Machine odds...", 12.5f, Ui.FAINT, false));
            } else {
                Integer fs = BuyAlerts.cachedScore(s.sym.yahoo);
                if (fs != null) {
                    String adjTxt = "";
                    try {
                        Ledger.Grade g = liveGrade;
                        if (g == null && ledgerSnap != null) { g = Ledger.gradeOf(ledgerSnap); liveGrade = g; }
                        if (g != null) {
                            Ledger.Adj adj = Ledger.adjustFor(g, s, fs);
                            if (adj.points != 0) adjTxt = String.format(" · live %s%d", adj.points > 0 ? "+" : "−", Math.abs(adj.points));
                        }
                    } catch (Throwable ignored) { }
                    TextView scoreLine = Ui.tv(MainActivity.this,
                            "IraScore " + fs + "/100" + adjTxt + (s.why.isEmpty() ? "" : " — " + String.join(" · ", s.why)),
                            12, fs >= 70 ? Ui.GREEN : fs >= 50 ? Ui.ORANGE : Ui.DIM, true);
                    scoreLine.setPadding(0, 0, 0, Ui.dp(MainActivity.this, 4));
                    row.addView(scoreLine);
                }
                row.addView(Ui.bar(MainActivity.this, "Target first", o.targetFirst, Ui.GREEN));
                row.addView(Ui.bar(MainActivity.this, "Stop first", o.stopFirst, Ui.RED));
                row.addView(Ui.bar(MainActivity.this, "Neither", o.neither, Ui.DIM));
                String rec = o.analogFires > 0
                        ? String.format("Real record: %d past firings — target %d · stop %d · neither %d",
                                o.analogFires, o.analogTarget, o.analogStop, o.analogNeither)
                        : "Real record: never fired before in the feed window";
                TextView r = Ui.tv(MainActivity.this, rec, 12, Ui.DIM, false);
                r.setPadding(0, Ui.dp(MainActivity.this, 4), 0, 0);
                row.addView(r);
                if (o.simOk) {
                    TextView sim = Ui.tv(MainActivity.this, String.format(
                            "Closest matches (~%.0f similar setups): target %.0f%% · stop %.0f%% · neither %.0f%%",
                            o.simEffN, o.simTarget, o.simStop, o.simNeither), 12, Ui.TXT, false);
                    sim.setPadding(0, Ui.dp(MainActivity.this, 2), 0, 0);
                    row.addView(sim);
                }
            }
            return row;
        }
    }
}
