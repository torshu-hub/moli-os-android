package io.moli.android;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Android foreground lifetime around a process-wide, serialized native host. */
public final class MoliService extends Service {
    static final String RESTART = "io.moli.android.RESTART";
    private boolean restarting;
    static final String STOP = "io.moli.android.STOP";
    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lease = -1;
    private boolean commandReceived;
    boolean hasCommand() { return commandReceived; }
    private EngineHost.State notified;
    private boolean notifiedHealthy;
    final class LocalBinder extends Binder { MoliService service() { return MoliService.this; } }
    String origin() { return EngineHost.state() == EngineHost.State.RUNNING ? EngineHost.origin() : null; }
    String token() { return EngineHost.token(); }
    boolean failed() { return EngineHost.state() == EngineHost.State.ERROR; }
    boolean stopped() { return EngineHost.state() == EngineHost.State.STOPPED; }
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            EngineHost.State state = EngineHost.state();
            if(state==EngineHost.State.STOPPED && restarting) {
                restarting=false;lease=EngineHost.start(MoliService.this);state=EngineHost.state();
            }
            if (state == EngineHost.State.STOPPED || state == EngineHost.State.ERROR || state == EngineHost.State.MAINTENANCE) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return;
            }
            boolean healthy = EngineHealth.snapshot()[0] == 1;
            if (state != notified || healthy != notifiedHealthy) {
                startForeground(1, notification(state)); notified = state; notifiedHealthy = healthy;
            }
            if (state == EngineHost.State.RUNNING) EngineHealth.check();
            if(TrialLog.active())EquipmentMonitor.poll(MoliService.this,null,false);
            handler.postDelayed(this, state == EngineHost.State.RUNNING ? EngineHealth.INTERVAL : 250);
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
                state == EngineHost.State.STOPPING ? R.string.stopping : EngineHealth.snapshot()[0] == 1 ? R.string.engine_confirmed : R.string.engine_unconfirmed;
        return new Notification.Builder(this, "moli").setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle(getString(R.string.app_name)).setContentText(getString(text))
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        commandReceived = true;
        if (intent != null && STOP.equals(intent.getAction())) {
            restarting=false;
            getSharedPreferences("host", MODE_PRIVATE).edit().putBoolean("wanted", false).apply();
            lease = EngineHost.currentLease();
            EngineHost.stop(lease);
        } else if(intent!=null && RESTART.equals(intent.getAction())) {
            if(EngineHost.state()==EngineHost.State.MAINTENANCE)return START_NOT_STICKY;
            getSharedPreferences("host",MODE_PRIVATE).edit().putBoolean("wanted",true).apply();
            startForeground(1,notification(EngineHost.State.STOPPING));
            restarting=true;lease=EngineHost.currentLease();EngineHost.stop(lease);
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
