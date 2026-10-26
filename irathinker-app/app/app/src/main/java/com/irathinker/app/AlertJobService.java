package com.irathinker.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Fallback runner for the same AlertEngine the WatchService drives.
 *  If the service is ever killed despite everything, the job chain
 *  still delivers the day's alerts. */
public class AlertJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { AlertEngine.runChecks(this); } catch (Exception ignored) { }
            Alerts.scheduleNext(this);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // retry later
    }
}
