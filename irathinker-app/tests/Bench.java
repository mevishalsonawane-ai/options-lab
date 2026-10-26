package com.irathinker.app;

/** Throwaway JVM benchmark for the v48 speed pass. Not shipped. */
public class Bench {
    public static void main(String[] args) throws Exception {
        String dir = args[0];
        YahooClient.Chart rel5 = TestMain.loadCsv(dir + "/reliance_5m.csv");
        YahooClient.Chart rel1 = TestMain.loadCsv(dir + "/reliance_1d.csv");
        Universe.Sym rel = null;
        for (Universe.Sym u : Universe.all()) if ("RELIANCE.NS".equals(u.yahoo)) rel = u;
        System.out.println("bars: " + rel5.ts.length);

        long t0 = System.nanoTime();
        Analysis.Scan s = Analysis.scan(rel, rel5, rel1);
        long t1 = System.nanoTime();
        System.out.printf("scan (60d chart): %.1f ms, plan=%s%n", (t1 - t0) / 1e6, s.hasPlan());

        t0 = System.nanoTime();
        Predict.Forecast f = Predict.forecast(rel5);
        t1 = System.nanoTime();
        System.out.printf("Predict.forecast: %.1f ms (live=%d)%n", (t1 - t0) / 1e6, f.combosLive);
        System.out.println("FC dir=" + java.util.Arrays.toString(f.dir)
                + " cons=" + java.util.Arrays.toString(f.consensus)
                + " exp=" + java.util.Arrays.toString(f.expPts)
                + " holdout=" + f.holdoutAcc + "/" + f.holdoutN
                + " fam60=" + java.util.Arrays.toString(f.famDir60));

        t0 = System.nanoTime();
        Predict.forecast(rel5);
        t1 = System.nanoTime();
        System.out.printf("Predict.forecast again: %.1f ms%n", (t1 - t0) / 1e6);

        if (s.hasPlan()) {
            t0 = System.nanoTime();
            Analysis.Odds o = Analysis.timeMachine(rel5, s, 3000);
            t1 = System.nanoTime();
            System.out.printf("timeMachine(3000): %.1f ms (analogs=%d, simOk=%s)%n",
                    (t1 - t0) / 1e6, o.analogFires, o.simOk);
            System.out.printf("ODDS tf=%.4f sf=%.4f nn=%.4f aT=%d aS=%d aN=%d simT=%.4f simS=%.4f simN=%.4f eff=%.3f raw=%d%n",
                    o.targetFirst, o.stopFirst, o.neither, o.analogTarget, o.analogStop, o.analogNeither,
                    o.simTarget, o.simStop, o.simNeither, o.simEffN, o.simRaw);
            System.out.printf("SCAN entry=%.2f stop=%.2f target=%.2f score-lite=%d paBody=%.4f thrust=%.4f run=%.4f%n",
                    s.entry, s.stop, s.target, Analysis.liteScore(s), s.paBody, s.paThrust, s.runMove);
        }

        t0 = System.nanoTime();
        java.util.List<Analysis.TestedSignal> h = Analysis.signalHistory(rel5, rel);
        t1 = System.nanoTime();
        System.out.printf("signalHistory 1 symbol: %.1f ms (rows=%d) -> x55 symbols = %.1f s%n",
                (t1 - t0) / 1e6, h.size(), (t1 - t0) / 1e9 * 55);

        t0 = System.nanoTime();
        java.util.List<Integer> idx = YahooClient.indicesOfDay(rel5,
                java.time.LocalDate.of(2026, 10, 22));
        t1 = System.nanoTime();
        System.out.printf("indicesOfDay once: %.2f ms (n=%d)%n", (t1 - t0) / 1e6, idx.size());
    }
}
