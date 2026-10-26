package com.irathinker.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Records the last uncaught crash (thread + stack trace) to prefs
 *  before the process dies, so the next launch can show exactly what
 *  failed. The previous system handler still runs afterwards, so
 *  Android's own crash behaviour is unchanged. */
public final class CrashGuard {

    private static final String FILE = "irathinker";
    public static final String KEY_TRACE = "lastCrash";
    public static final String KEY_AT = "lastCrashAt";

    public static void install(Context ctx) {
        final SharedPreferences sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String trace = "Thread: " + t.getName() + "\n" + sw.toString();
                if (trace.length() > 3500) trace = trace.substring(0, 3500);
                sp.edit().putString(KEY_TRACE, trace)
                        .putLong(KEY_AT, System.currentTimeMillis())
                        .commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    public static String lastCrash(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TRACE, "");
    }

    public static long lastCrashAt(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_AT, 0);
    }

    public static void clear(Context ctx) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .remove(KEY_TRACE).remove(KEY_AT).apply();
    }
}
