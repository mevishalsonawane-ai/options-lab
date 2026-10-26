package com.irathinker.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Throwaway live runner: feeds Yahoo JSON (downloaded by Python, since
 *  this sandbox's JVM cannot tunnel HTTPS) through the app's real
 *  Analysis.scan / timeMachine / scoring code. Modes:
 *    symbols           -> idx|yahoo per line
 *    scan <dir>        -> one line per symbol with its scan + lite score
 *    full <dir> <idxs> -> for the given indexes, Time Machine odds + full score */
public class LiveTop {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static String f(double v) { return Double.isNaN(v) ? "-" : String.format("%.2f", v); }

    public static void main(String[] args) throws Exception {
        List<Universe.Sym> all = Universe.all();
        if (args[0].equals("symbols")) {
            for (int i = 0; i < all.size(); i++) System.out.println(i + "|" + all.get(i).yahoo);
            return;
        }
        File dir = new File(args[1]);
        if (args[0].equals("scan")) {
            for (int i = 0; i < all.size(); i++) {
                Universe.Sym s = all.get(i);
                File f5 = new File(dir, i + "_5d.json"), f1 = new File(dir, i + "_1d.json");
                if (!f5.exists()) { System.out.println(i + "|" + s.yahoo + "|" + s.name + "|" + s.group + "|NOFETCH"); continue; }
                try {
                    YahooClient.Chart intra = YahooClient.parse(read(f5));
                    YahooClient.Chart daily = f1.exists() ? YahooClient.parse(read(f1)) : null;
                    Analysis.Scan sc = Analysis.scan(s, intra, daily);
                    int lite = Analysis.liteScore(sc);
                    System.out.println(i + "|" + s.yahoo + "|" + s.name + "|" + s.group + "|" + sc.setup
                            + "|" + sc.hasPlan() + "|" + lite + "|" + f(sc.ltp) + "|" + f(sc.dayPct)
                            + "|" + f(sc.entry) + "|" + f(sc.stop) + "|" + f(sc.target)
                            + "|" + (sc.breakTs > 0 ? HM.format(Instant.ofEpochSecond(sc.breakTs).atZone(IST)) : "-")
                            + "|" + (sc.lastTs > 0 ? HM.format(Instant.ofEpochSecond(sc.lastTs).atZone(IST)) : "-")
                            + "|" + f(sc.orHigh) + "|" + f(sc.orLow) + "|" + (sc.error == null ? "" : sc.error));
                } catch (Exception e) {
                    System.out.println(i + "|" + s.yahoo + "|" + s.name + "|" + s.group + "|ERROR|" + e.getMessage());
                }
            }
            return;
        }
        if (args[0].equals("dump")) {
            YahooClient.Chart vixD = YahooClient.parse(read(new File(dir, "5_60d.json")));
            for (int i = 0; i < all.size(); i++) {
                Universe.Sym s = all.get(i);
                File f60 = new File(dir, i + "_60d.json");
                if (!f60.exists()) continue;
                YahooClient.Chart intra = YahooClient.parse(read(f60));
                for (Analysis.TestedSignal t : Analysis.signalHistory(intra, s, vixD)) {
                    Analysis.Scan sc = t.scan;
                    System.out.println(s.yahoo + "|" + s.name + "|" + s.group + "|" + t.day + "|" + sc.setup
                            + "|" + t.outcome + "|" + t.score + "|" + f(t.movePts)
                            + "|" + f(sc.entry) + "|" + f(sc.stop) + "|" + f(sc.target)
                            + "|" + f(sc.orHigh) + "|" + f(sc.orLow)
                            + "|" + (sc.breakTs > 0 ? HM.format(Instant.ofEpochSecond(sc.breakTs).atZone(IST)) : "-")
                            + "|" + f(sc.prevClose) + "|" + t.alertGrade);
                }
            }
            return;
        }
        if (args[0].equals("gatecheck")) {
            YahooClient.Chart vixG = YahooClient.parse(read(new File(dir, "5_60d.json")));
            // buckets: [0]=agrees DOWN with consensus>=80, [1]=agrees DOWN <80, [2]=disagrees/abstains
            long[][] b12 = new long[3][3];
            long[][] b6 = new long[3][3];
            for (int i = 0; i < all.size(); i++) {
                Universe.Sym s = all.get(i);
                if (!Universe.G_NIFTY.equals(s.group) && !Universe.G_BANK.equals(s.group)) continue;
                File f60 = new File(dir, i + "_60d.json");
                if (!f60.exists()) continue;
                YahooClient.Chart intra = YahooClient.parse(read(f60));
                java.util.List<Analysis.TestedSignal> hist = Analysis.signalHistory(intra, s, vixG);
                java.util.TreeSet<java.time.LocalDate> daySet = new java.util.TreeSet<>();
                for (Analysis.TestedSignal t : hist) daySet.add(t.day);
                java.util.List<java.time.LocalDate> dayList = new java.util.ArrayList<>(daySet);
                if (dayList.size() < 10) continue;
                java.time.LocalDate cutoff = dayList.get((int) (dayList.size() * 0.6));
                Predict.Model model = null;
                for (Analysis.TestedSignal t : hist) {
                    if (!t.alertGrade || t.day.compareTo(cutoff) <= 0) continue;
                    if (model == null) model = Predict.train(intra);
                    double[] a12 = model.at(t.scan.breakTs, 12);
                    double[] a6 = model.at(t.scan.breakTs, 6);
                    int bi12 = a12[0] < 0 ? (a12[1] >= 80 ? 0 : 1) : 2;
                    int bi6 = a6[0] < 0 ? (a6[1] >= 80 ? 0 : 1) : 2;
                    b12[bi12][0]++; if (t.outcome > 0) b12[bi12][1]++; else if (t.outcome < 0) b12[bi12][2]++;
                    b6[bi6][0]++; if (t.outcome > 0) b6[bi6][1]++; else if (t.outcome < 0) b6[bi6][2]++;
                }
            }
            for (String[] cfg : new String[][]{{"60-min horizon", "x"}, {"30-min horizon", "y"}}) {
                long[][] b = cfg[0].startsWith("60") ? b12 : b6;
                System.out.println(cfg[0] + ":");
                String[] names = {"agrees DOWN, consensus >= 80", "agrees DOWN, consensus < 80", "disagrees/abstains"};
                for (int k = 0; k < 3; k++) {
                    long n = b[k][0], w = b[k][1], l = b[k][2];
                    System.out.println("  " + names[k]
                            + ": n=" + n + " target=" + w + " stop=" + l
                            + " win=" + (w + l > 0 ? String.format("%.1f%%", w * 100.0 / (w + l)) : "-"));
                }
            }
            return;
        }
        if (args[0].equals("predict1")) {
            for (int a = 2; a < args.length; a++) {
                int i = Integer.parseInt(args[a]);
                Universe.Sym s = all.get(i);
                Predict.Forecast fc = Predict.forecast(YahooClient.parse(read(new File(dir, i + "_60d.json"))));
                StringBuilder sb = new StringBuilder(s.name + ":");
                for (int hi = 0; hi < 4; hi++) {
                    sb.append(" ").append(Predict.HORIZON_LABEL[hi]).append("=")
                            .append(fc.dir[hi] > 0 ? "UP" : fc.dir[hi] < 0 ? "DOWN" : "-")
                            .append(String.format("(%.0f%%,%+.1f)", fc.consensus[hi], fc.expPts[hi]));
                }
                sb.append(" holdout=").append(String.format("%.1f%%", fc.holdoutAcc));
                System.out.println(sb);
            }
            return;
        }
        if (args[0].equals("predict")) {
            long totN = 0; double totHits = 0; int symsUsed = 0;
            for (int i = 0; i < all.size(); i++) {
                Universe.Sym s = all.get(i);
                if (!Universe.G_NIFTY.equals(s.group) && !Universe.G_BANK.equals(s.group)) continue;
                File f60 = new File(dir, i + "_60d.json");
                if (!f60.exists()) continue;
                long t0 = System.currentTimeMillis();
                Predict.Forecast fc = Predict.forecast(YahooClient.parse(read(f60)));
                long ms = System.currentTimeMillis() - t0;
                symsUsed++;
                totN += fc.holdoutN; totHits += fc.holdoutAcc * fc.holdoutN / 100.0;
                System.out.println(s.name + " combos=" + fc.combosTotal + " live=" + fc.combosLive
                        + " holdout=" + String.format("%.1f%%", fc.holdoutAcc) + " n=" + fc.holdoutN
                        + " dir30=" + fc.dir[1] + " cons30=" + String.format("%.0f", fc.consensus[1])
                        + " exp30=" + String.format("%+.1f", fc.expPts[1]) + " (" + ms + "ms)");
            }
            System.out.println("ENSEMBLE holdout across " + symsUsed + " stocks: "
                    + String.format("%.1f%%", totHits / Math.max(totN, 1) * 100) + " on n=" + totN);
            return;
        }
        if (args[0].equals("sigtest")) {
            YahooClient.Chart vixS = YahooClient.parse(read(new File(dir, "5_60d.json")));
            long[] tot = new long[4];   // n, target, stop, neither
            long[] ag = new long[4];    // alert-grade subset
            long[] agUp = new long[4], agDn = new long[4];
            int syms = 0;
            for (int i = 0; i < all.size(); i++) {
                Universe.Sym s = all.get(i);
                if (!Universe.G_NIFTY.equals(s.group) && !Universe.G_BANK.equals(s.group)) continue;
                File f60 = new File(dir, i + "_60d.json");
                if (!f60.exists()) continue;
                syms++;
                YahooClient.Chart intra = YahooClient.parse(read(f60));
                for (Analysis.TestedSignal t : Analysis.signalHistory(intra, s, vixS)) {
                    tot[0]++; if (t.outcome > 0) tot[1]++; else if (t.outcome < 0) tot[2]++; else tot[3]++;
                    if (t.alertGrade) {
                        long[] b = t.scan.setup == Analysis.Setup.ORB_UP ? agUp : agDn;
                        ag[0]++; b[0]++;
                        if (t.outcome > 0) { ag[1]++; b[1]++; } else if (t.outcome < 0) { ag[2]++; b[2]++; } else { ag[3]++; b[3]++; }
                    }
                }
            }
            System.out.println("symbols tested: " + syms);
            System.out.println("ALL signals:      n=" + tot[0] + " target=" + tot[1] + " stop=" + tot[2] + " neither=" + tot[3]);
            System.out.println("ALERT-GRADE:      n=" + ag[0] + " target=" + ag[1] + " stop=" + ag[2] + " neither=" + ag[3]);
            System.out.println("  alert BUY:      n=" + agUp[0] + " target=" + agUp[1] + " stop=" + agUp[2] + " neither=" + agUp[3]);
            System.out.println("  alert BUY PUT:  n=" + agDn[0] + " target=" + agDn[1] + " stop=" + agDn[2] + " neither=" + agDn[3]);
            return;
        }
        if (args[0].equals("today")) {
            YahooClient.Chart vixC = YahooClient.parse(read(new File(dir, "5_5d.json")));
            Analysis.Scan vix = Analysis.scan(all.get(5), vixC, null);
            System.out.println("VIX setup=" + vix.setup + " break=" + (vix.breakTs > 0 ? HM.format(Instant.ofEpochSecond(vix.breakTs).atZone(IST)) : "-"));
            java.util.List<String> rows = new java.util.ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                Universe.Sym s = all.get(i);
                if (!Universe.G_NIFTY.equals(s.group) && !Universe.G_BANK.equals(s.group)) continue;
                File f5 = new File(dir, i + "_5d.json");
                if (!f5.exists()) continue;
                YahooClient.Chart intra = YahooClient.parse(read(f5));
                File f1 = new File(dir, i + "_1d.json");
                YahooClient.Chart daily = f1.exists() ? YahooClient.parse(read(f1)) : null;
                Analysis.Scan sc = Analysis.scan(s, intra, daily);
                if (sc == null || !sc.hasPlan()) continue;
                int lite = Analysis.liteScore(sc);
                boolean worthy = Analysis.alertWorthy(sc, lite, 65);
                boolean vixOk = Analysis.vixConfirms(vix.setup, vix.breakTs, sc);
                int oc = Analysis.outcomeOf(intra, sc.breakTs, sc.stop, sc.target, sc.setup == Analysis.Setup.ORB_UP);
                String hm = sc.breakTs > 0 ? HM.format(Instant.ofEpochSecond(sc.breakTs).atZone(IST)) : "-";
                rows.add(String.format("%s|%-22s|%-8s|score=%3d|pts=%5.1f|gate=%b|vixOk=%b|%s",
                        hm, s.name, sc.setup, lite, Math.abs(sc.target - sc.entry), worthy, vixOk,
                        oc > 0 ? "TARGET" : oc < 0 ? "STOP" : "OPEN"));
            }
            rows.sort(null);
            for (String r : rows) System.out.println(r);
            return;
        }
        if (args[0].equals("full")) {
            for (String p : args[2].split(",")) {
                int i = Integer.parseInt(p.trim());
                Universe.Sym s = all.get(i);
                YahooClient.Chart intra = YahooClient.parse(read(new File(dir, i + "_5d.json")));
                File f1 = new File(dir, i + "_1d.json");
                YahooClient.Chart daily = f1.exists() ? YahooClient.parse(read(f1)) : null;
                Analysis.Scan sc = Analysis.scan(s, intra, daily);
                YahooClient.Chart intra60 = YahooClient.parse(read(new File(dir, i + "_60d.json")));
                Analysis.Odds o = Analysis.timeMachine(intra60, sc, 3000);
                int full = Analysis.fullScore(sc, o);
                int lite = Analysis.liteScore(sc);
                System.out.println(i + "|" + s.yahoo + "|" + s.name + "|" + sc.setup + "|" + full + "|" + lite
                        + "|" + f(o.targetFirst) + "|" + f(o.stopFirst) + "|" + f(o.neither)
                        + "|" + o.analogFires + "|" + o.analogTarget + "|" + o.analogStop
                        + "|" + f(sc.entry) + "|" + f(sc.stop) + "|" + f(sc.target)
                        + "|" + f(sc.ltp) + "|" + f(sc.dayPct)
                        + "|" + (sc.breakTs > 0 ? HM.format(Instant.ofEpochSecond(sc.breakTs).atZone(IST)) : "-"));
            }
            return;
        }
    }
}
