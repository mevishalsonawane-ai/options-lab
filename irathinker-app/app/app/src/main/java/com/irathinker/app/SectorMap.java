package com.irathinker.app;

import java.util.HashMap;
import java.util.Map;

/** Sector for every equity in the universe (for Flow's sector heat).
 *  Keyed by Yahoo symbol; indices/commodities return null. */
public final class SectorMap {

    private static final Map<String, String> MAP = new HashMap<>();
    static {
        String[][] rows = {
            {"Financials", "AXISBANK.NS,BAJFINANCE.NS,BAJAJFINSV.NS,HDFCBANK.NS,HDFCLIFE.NS,ICICIBANK.NS,INDUSINDBK.NS,JIOFIN.NS,KOTAKBANK.NS,SBILIFE.NS,SHRIRAMFIN.NS,SBIN.NS,AUBANK.NS,BANKBARODA.NS,CANBK.NS,FEDERALBNK.NS,IDFCFIRSTB.NS,PNB.NS"},
            {"IT", "HCLTECH.NS,INFY.NS,TCS.NS,TECHM.NS,WIPRO.NS"},
            {"Auto", "BAJAJ-AUTO.NS,EICHERMOT.NS,HEROMOTOCO.NS,M&M.NS,MARUTI.NS,TMPV.NS"},
            {"Pharma & Health", "APOLLOHOSP.NS,CIPLA.NS,DRREDDY.NS,SUNPHARMA.NS"},
            {"Energy & Power", "COALINDIA.NS,NTPC.NS,ONGC.NS,POWERGRID.NS,RELIANCE.NS"},
            {"FMCG & Consumer", "HINDUNILVR.NS,ITC.NS,NESTLEIND.NS,TATACONSUM.NS,TITAN.NS,TRENT.NS,ASIANPAINT.NS"},
            {"Metals & Materials", "GRASIM.NS,HINDALCO.NS,JSWSTEEL.NS,TATASTEEL.NS,ULTRACEMCO.NS"},
            {"Infra & Defence", "ADANIENT.NS,ADANIPORTS.NS,BEL.NS,LT.NS"},
            {"Telecom", "BHARTIARTL.NS"},
        };
        for (String[] r : rows) {
            for (String y : r[1].split(",")) MAP.put(y, r[0]);
        }
    }

    public static String sectorOf(String yahoo) {
        return MAP.get(yahoo);
    }
}
