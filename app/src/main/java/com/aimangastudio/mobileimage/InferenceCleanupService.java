package com.aimangastudio.mobileimage;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Keeps no model itself; it only gives Android a reliable task-removal hook. */
public final class InferenceCleanupService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        NativeGenerator.cancel();
        new Thread(() -> {
            NativeGenerator.release();
            stopSelf();
        }, "release-local-model").start();
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
