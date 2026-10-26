package com.irathinker.app;

import java.util.ArrayList;
import java.util.List;

/** The IraThinker universe: every NIFTY 50 and BANKNIFTY constituent,
 *  the headline indices, and the commodity benchmarks.
 *  Symbols are Yahoo Finance tickers (the app's no-login data engine).
 *  A symbol that fails to fetch is skipped gracefully, so index
 *  reconstitutions only ever cost one row until this list is edited. */
public final class Universe {

    public static final class Sym {
        public final String name;
        public final String yahoo;
        public final String group;

        Sym(String name, String yahoo, String group) {
            this.name = name;
            this.yahoo = yahoo;
            this.group = group;
        }
    }

    public static final String G_INDEX = "Indices";
    public static final String G_NIFTY = "NIFTY 50 companies";
    public static final String G_BANK = "BANKNIFTY companies";
    public static final String G_COMM = "Commodities (global benchmark)";

    private static final List<Sym> ALL = new ArrayList<>();

    static {
        // ---- Indices ----
        idx("NIFTY 50", "^NSEI");
        idx("BANKNIFTY", "^NSEBANK");
        idx("FINNIFTY", "^CNXFIN");
        idx("MIDCAP 50", "^NSEMDCP50");
        idx("SENSEX", "^BSESN");
        idx("INDIA VIX", "^INDIAVIX");
        idx("USD / INR", "USDINR=X");

        // ---- NIFTY 50 constituents ----
        String[][] nifty = {
            {"Adani Enterprises", "ADANIENT"}, {"Adani Ports", "ADANIPORTS"},
            {"Apollo Hospitals", "APOLLOHOSP"}, {"Asian Paints", "ASIANPAINT"},
            {"Axis Bank", "AXISBANK"}, {"Bajaj Auto", "BAJAJ-AUTO"},
            {"Bajaj Finance", "BAJFINANCE"}, {"Bajaj Finserv", "BAJAJFINSV"},
            {"BEL", "BEL"}, {"Bharti Airtel", "BHARTIARTL"},
            {"Cipla", "CIPLA"}, {"Coal India", "COALINDIA"},
            {"Dr Reddy's", "DRREDDY"}, {"Eicher Motors", "EICHERMOT"},
            {"Grasim", "GRASIM"}, {"HCL Tech", "HCLTECH"},
            {"HDFC Bank", "HDFCBANK"}, {"HDFC Life", "HDFCLIFE"},
            {"Hero MotoCorp", "HEROMOTOCO"}, {"Hindalco", "HINDALCO"},
            {"Hindustan Unilever", "HINDUNILVR"}, {"ICICI Bank", "ICICIBANK"},
            {"IndusInd Bank", "INDUSINDBK"}, {"Infosys", "INFY"},
            {"ITC", "ITC"}, {"Jio Financial", "JIOFIN"},
            {"JSW Steel", "JSWSTEEL"}, {"Kotak Bank", "KOTAKBANK"},
            {"Larsen & Toubro", "LT"}, {"Mahindra & Mahindra", "M&M"},
            {"Maruti Suzuki", "MARUTI"}, {"Nestle India", "NESTLEIND"},
            {"NTPC", "NTPC"}, {"ONGC", "ONGC"},
            {"Power Grid", "POWERGRID"}, {"Reliance Industries", "RELIANCE"},
            {"SBI Life", "SBILIFE"}, {"Shriram Finance", "SHRIRAMFIN"},
            {"State Bank of India", "SBIN"}, {"Sun Pharma", "SUNPHARMA"},
            {"TCS", "TCS"}, {"Tata Consumer", "TATACONSUM"},
            {"Tata Motors PV", "TMPV"}, {"Tata Steel", "TATASTEEL"},
            {"Tech Mahindra", "TECHM"}, {"Titan", "TITAN"},
            {"Trent", "TRENT"}, {"UltraTech Cement", "ULTRACEMCO"},
            {"Wipro", "WIPRO"},
        };
        for (String[] s : nifty) ALL.add(new Sym(s[0], s[1] + ".NS", G_NIFTY));

        // ---- BANKNIFTY constituents (the banks not already above still get
        // their own group rows so the group view is complete) ----
        String[][] bank = {
            {"AU Small Finance Bank", "AUBANK"}, {"Bank of Baroda", "BANKBARODA"},
            {"Canara Bank", "CANBK"}, {"Federal Bank", "FEDERALBNK"},
            {"IDFC First Bank", "IDFCFIRSTB"}, {"Punjab National Bank", "PNB"},
        };
        for (String[] s : bank) ALL.add(new Sym(s[0], s[1] + ".NS", G_BANK));
        // banks shared with NIFTY 50 are tagged into the bank group in the UI
        // via BANK_MEMBERS below.

        // ---- Commodities: Yahoo carries the global benchmarks (COMEX /
        // NYMEX). True MCX live ticks need the Dhan feed (Settings). ----
        ALL.add(new Sym("Gold (COMEX)", "GC=F", G_COMM));
        // v52: XAU/USD spot gold, fed by Binance (Yahoo delisted spot
        // metals) - the id below is the Binance pair, routed by
        // YahooClient.fetch to BinanceClient. A full pipeline symbol:
        // board, Detail, plans, Prediction, Lab and price alerts.
        // NOT a strategy-alert symbol: BuyAlerts keeps it out of the
        // equity ORB gate and the System Feed, and it has no options
        // ticket (gold does not trade on NSE).
        ALL.add(new Sym("GOLD (XAU/USD)", BinanceClient.PERP, G_COMM));
        ALL.add(new Sym("Silver (COMEX)", "SI=F", G_COMM));
        ALL.add(new Sym("Crude Oil (NYMEX)", "CL=F", G_COMM));
        ALL.add(new Sym("Natural Gas (NYMEX)", "NG=F", G_COMM));
        ALL.add(new Sym("Copper (COMEX)", "HG=F", G_COMM));
    }

    private static void idx(String name, String yahoo) {
        ALL.add(new Sym(name, yahoo, G_INDEX));
    }

    /** BANKNIFTY's 12 members by Yahoo symbol (some live in the NIFTY list). */
    public static final String[] BANK_MEMBERS = {
        "HDFCBANK.NS", "ICICIBANK.NS", "SBIN.NS", "KOTAKBANK.NS", "AXISBANK.NS",
        "INDUSINDBK.NS", "BANKBARODA.NS", "PNB.NS", "IDFCFIRSTB.NS",
        "FEDERALBNK.NS", "AUBANK.NS", "CANBK.NS"
    };

    public static List<Sym> all() {
        return ALL;
    }

    /** Review 26 Oct 2026: the symbols a strategy alert (ORB / gap
     *  fade) may fire on - NSE F&O stocks (the NIFTY 50 and bank
     *  lists) and the three NSE indices whose options the ticket
     *  prices (NIFTY weekly, BANKNIFTY / FINNIFTY monthly). INDIA
     *  VIX, USD/INR, MIDCAP 50, SENSEX (BSE expiry rules, no index
     *  strike table here) and the global commodity benchmarks have
     *  no NSE option the ticket can name, so they never alert (they
     *  stay on the board, and price alerts on them still work). */
    public static boolean alertable(Sym s) {
        if (s == null || s.yahoo == null) return false;
        if (G_NIFTY.equals(s.group) || G_BANK.equals(s.group)) return s.yahoo.endsWith(".NS");
        return "^NSEI".equals(s.yahoo) || "^NSEBANK".equals(s.yahoo) || "^CNXFIN".equals(s.yahoo);
    }

    public static boolean isCommodity(Sym s) {
        return s != null && G_COMM.equals(s.group);
    }

    /** The Binance-fed XAU/USD gold symbol (v52). */
    public static Sym gold() {
        for (Sym s : ALL) if (BinanceClient.PERP.equals(s.yahoo)) return s;
        return null;
    }

    public static boolean isGold(Sym s) {
        return s != null && BinanceClient.PERP.equals(s.yahoo);
    }

    public static boolean isGoldYahoo(String yahoo) {
        return BinanceClient.PERP.equals(yahoo);
    }

    public static boolean isBankMember(String yahoo) {
        for (String b : BANK_MEMBERS) if (b.equals(yahoo)) return true;
        return false;
    }
}
