package com.irathinker.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

public class TicketSweep {
    static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        List<Universe.Sym> all = Universe.all();
        LocalDate today = LocalDate.of(2026, 10, 23);
        int thrown = 0;
        for (int i = 0; i < all.size(); i++) {
            Universe.Sym s = all.get(i);
            File f5 = new File(dir, i + "_5d.json"), f1 = new File(dir, i + "_1d.json");
            if (!f5.exists()) continue;
            try {
                YahooClient.Chart intra = YahooClient.parse(read(f5));
                YahooClient.Chart daily = f1.exists() ? YahooClient.parse(read(f1)) : null;
                Analysis.Scan sc = Analysis.scan(s, intra, daily);
                // full score path (Time Machine board / Detail header)
                if (sc != null && sc.hasPlan()) {
                    try {
                        Analysis.Odds o = Analysis.timeMachine(intra, sc, 500);
                        Analysis.fullScore(sc, o);
                    } catch (Throwable t) {
                        thrown++;
                        System.out.println(i + " " + s.yahoo + " FULLSCORE THREW: " + t);
                    }
                }
                if (sc != null && sc.hasPlan() && daily != null) {
                    double iv = OptionsMath.realizedVol(daily);
                    if (!Double.isNaN(iv)) {
                        iv = Math.max(10, Math.min(iv, 90));
                        OptionsMath.ticketText(sc, iv, today);
                        OptionsMath.Ticket tk = OptionsMath.ticket(sc, iv, today);
                        // ITM alternative exactly as Detail computes it
                        int step = OptionsMath.strikeStepFor(s.yahoo, sc.entry);
                        double itmStrike = sc.setup == Analysis.Setup.ORB_UP ? tk.strike - step : tk.strike + step;
                        OptionsMath.bsPrice(sc.entry, itmStrike, tk.dte, iv, sc.setup == Analysis.Setup.ORB_UP);
                        OptionsMath.bsDelta(sc.entry, itmStrike, tk.dte, iv, sc.setup == Analysis.Setup.ORB_UP);
                    }
                }
            } catch (Throwable t) {
                thrown++;
                System.out.println(i + " " + s.yahoo + " THREW: " + t);
            }
        }
        System.out.println("sweep done, thrown=" + thrown);
    }
}
