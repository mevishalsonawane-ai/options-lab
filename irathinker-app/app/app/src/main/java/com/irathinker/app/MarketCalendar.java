package com.irathinker.app;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** NSE trading holidays, equity segment, 2026 - from the exchange's
 *  holiday circular as republished by NDTV Profit / Paytm / brokers,
 *  cross-checked Oct 23, 2026. Weekends are the callers' own rule;
 *  this list is the weekday closures only. (The Sun Nov 8, 2026
 *  Muhurat session is a weekend day, so the engine stays quiet then -
 *  acceptable: it is a one-hour ceremonial session, not a normal day.) */
public final class MarketCalendar {

    private static final Set<LocalDate> HOLIDAYS_2026 = new HashSet<>();

    static {
        int[][] md = {
                {1, 15},   // Municipal Corporation elections, Maharashtra
                {1, 26},   // Republic Day
                {3, 3},    // Holi
                {3, 26},   // Shri Ram Navami
                {3, 31},   // Shri Mahavir Jayanti
                {4, 3},    // Good Friday
                {4, 14},   // Dr. Baba Saheb Ambedkar Jayanti
                {5, 1},    // Maharashtra Day
                {5, 28},   // Bakri Eid
                {6, 26},   // Muharram
                {9, 14},   // Ganesh Chaturthi
                {10, 2},   // Mahatma Gandhi Jayanti
                {10, 20},  // Dussehra
                {11, 10},  // Diwali - Balipratipada
                {11, 24},  // Guru Nanak Jayanti
                {12, 25},  // Christmas
        };
        for (int[] d : md) HOLIDAYS_2026.add(LocalDate.of(2026, d[0], d[1]));
        // Review 26 Oct 2026: NSE's 2027 list is not out yet. Republic
        // Day is a fixed national holiday, and Tue 26 Jan 2027 is
        // January's last Tuesday, so the monthly expiry (shown from
        // 30 Dec) must already move to Mon 25 Jan. Replace with the
        // full 2027 circular when NSE publishes it.
        HOLIDAYS_2026.add(LocalDate.of(2027, 1, 26));
    }

    public static boolean isHoliday(LocalDate date) {
        return date != null && HOLIDAYS_2026.contains(date);
    }
}
