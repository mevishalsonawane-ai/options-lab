package com.irathinker.app;

import java.time.LocalDate;

public class TicketCheck {
    static Analysis.Scan scan(String name, String yahoo, double entry, double stop, double target) {
        Analysis.Scan sc = new Analysis.Scan();
        sc.sym = new Universe.Sym(name, yahoo, "");
        sc.setup = Analysis.Setup.ORB_DOWN;
        sc.entry = entry; sc.stop = stop; sc.target = target;
        return sc;
    }

    public static void main(String[] a) {
        LocalDate today = LocalDate.of(2026, 10, 23);
        System.out.println("DRREDDY" + OptionsMath.ticketText(scan("Dr Reddy's", "DRREDDY.NS", 1229.10, 1243.95, 1214.25), 22, today));
        System.out.println("BAJAJ" + OptionsMath.ticketText(scan("Bajaj Auto", "BAJAJ-AUTO.NS", 10062, 10134, 9990), 36, today));
        System.out.println("LT" + OptionsMath.ticketText(scan("Larsen & Toubro", "LT.NS", 3631.70, 3651.95, 3611.45), 21, today));
    }
}
