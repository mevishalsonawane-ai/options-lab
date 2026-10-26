package com.irathinker.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the alert chain and the watchdog after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Alerts.scheduleNext(context);
            WatchService.start(context);
        }
    }
}
