package io.moli.android;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Android foreground lifetime around a process-wide, serialized native host. */
public final class MoliService extends Service {
    static final String STOP = "io.moli.android.STOP";
    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lease = -1;
    private EngineHost.State notified;
    final class LocalBinder extends Binder { MoliService service() { return MoliService.this; } }
    String origin() { return EngineHost.state() == EngineHost.State.RUNNING ? EngineHost.origin() : null; }
    String token() { return EngineHost.token(); }
    boolean failed() { return EngineHost.state() == EngineHost.State.ERROR; }
    boolean stopped() { return EngineHost.state() == EngineHost.State.STOPPED; }
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            EngineHost.State state = EngineHost.state();
            if (state == EngineHost.State.STOPPED || state == EngineHost.State.ERROR || state == EngineHost.State.MAINTENANCE) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return;
            }
            if (state != notified) { startForeground(1, notification(state)); notified = state; }
            if (state != EngineHost.State.RUNNING) handler.postDelayed(this, 250);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                "moli", getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(EngineHost.State state) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MoliService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        int text = state == EngineHost.State.STARTING ? R.string.starting :
                state == EngineHost.State.STOPPING ? R.string.stopping : R.string.service_running;
        return new Notification.Builder(this, "moli").setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle(getString(R.string.app_name)).setContentText(getString(text))
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            getSharedPreferences("host", MODE_PRIVATE).edit().putBoolean("wanted", false).apply();
            lease = EngineHost.currentLease();
            EngineHost.stop(lease);
        } else {
            // Required immediately even when a duplicate start is being rejected.
            startForeground(1, notification(EngineHost.State.STARTING));
            lease = EngineHost.start(this);
            if (lease < 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        }
        notified = null; handler.removeCallbacks(monitor); handler.post(monitor);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        handler.removeCallbacks(monitor);
        if (EngineHost.state() != EngineHost.State.ERROR) EngineHost.stop(lease);
        super.onDestroy();
    }
}
