package com.dbuezas.t9launcher;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.ResultReceiver;

/**
 * Rescans the app list in its own short-lived process (":scan"). Reading every app's label
 * maps their APKs and the Settings app into memory (70-130 MB for a moment); here that never
 * touches the panel's process. The result goes to apps.tsv; the caller gets a ResultReceiver call.
 */
public final class ScanService extends Service {
    static final String EXTRA_DONE = "done";
    private Handler worker;

    @Override
    public void onCreate() {
        super.onCreate();
        HandlerThread t = new HandlerThread("scan");
        t.start();
        worker = new Handler(t.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver done = intent == null ? null : intent.getParcelableExtra(EXTRA_DONE);
        worker.post(() -> {
            boolean complete = Store.scanInThisProcess(this);
            if (done != null) done.send(complete ? 1 : 0, null);
            stopSelf(startId); // stops only if no newer request is queued
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Give the memory back at once instead of waiting for the system to reclaim a cached process.
        Process.killProcess(Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
