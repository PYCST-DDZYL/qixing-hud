package com.qixing.hud.glasses;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts the HUD service again after a reboot. The glasses' system does not deliver BOOT_COMPLETED
 * to third-party apps, but it does keep persisted jobs across reboots, so a job that is always
 * pending brings the service back within about a minute of power-on.
 */
public class KeepAliveJob extends JobService {
    private static final int ID = 4781;
    private static final long EVERY_MS = 60_000;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            JobInfo job = new JobInfo.Builder(ID, new ComponentName(c, KeepAliveJob.class))
                    .setPersisted(true)
                    .setMinimumLatency(EVERY_MS)
                    .setOverrideDeadline(EVERY_MS * 2)
                    .build();
            js.schedule(job);
        } catch (Exception e) {
            Log.w("QixingHud", "keep-alive job: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            if (HudService.I == null) {
                Log.i("QixingHud", "keep-alive job starts the HUD service");
                startForegroundService(new Intent(this, HudService.class));
            }
        } catch (Exception e) {
            Log.w("QixingHud", "keep-alive start: " + e);
        }
        jobFinished(params, false);
        schedule(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
