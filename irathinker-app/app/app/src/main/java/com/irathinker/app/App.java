package com.irathinker.app;

import android.app.Application;

/** App entry: arm the fallback job chain and start the watchdog
 *  (the watchdog starts only inside its 08:30-24:00 window). */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashGuard.install(this);
        try {
            Alerts.scheduleNext(this);
            WatchService.start(this);
        } catch (Throwable ignored) {
            // scheduling must never take the whole process down at start
        }
    }
}
