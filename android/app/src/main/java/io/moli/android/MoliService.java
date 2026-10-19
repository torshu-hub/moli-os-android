package io.moli.android;

import android.app.*;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The service, not the WebView, owns the native server's lifetime. */
public final class MoliService extends Service {
    static final String STOP = "io.moli.android.STOP";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LocalBinder binder = new LocalBinder();
    private volatile String origin;
    private volatile String token;
    private volatile boolean starting;
    private volatile boolean failed;
    private volatile boolean stopping;

    final class LocalBinder extends Binder {
        MoliService service() { return MoliService.this; }
    }
    String origin() { return origin; }
    String token() { return token; }
    boolean failed() { return failed; }
    boolean stopped() { return stopping && origin == null && !starting; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("moli", getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopEngine();
            return START_NOT_STICKY;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MoliService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "moli")
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.service_running))
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build();
        startForeground(1, notification);
        if (!starting && origin == null && !stopping) {
            starting = true;
            worker.execute(() -> {
                try {
                    JSONObject keys = ProtectedKey.open(this);
                    String session = ProtectedKey.randomHex();
                    int port = NativeServer.start(new File(getFilesDir(), "moli").getAbsolutePath(),
                            keys.getString("master"), session, keys.getString("pin"));
                    token = session;
                    origin = "http://127.0.0.1:" + port;
                } catch (Exception | LinkageError failure) {
                    // Never include key material or native panic text in logs/UI.
                    failed = true;
                    new Handler(Looper.getMainLooper()).post(() -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
                } finally { starting = false; }
            });
        }
        return START_NOT_STICKY;
    }
    private void stopEngine() {
        if (stopping) return;
        stopping = true;
        worker.execute(() -> {
            try { NativeServer.stop(); }
            catch (Exception | LinkageError failure) { failed = true; }
            finally {
                origin = null; token = null;
                new Handler(Looper.getMainLooper()).post(() -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
            }
        });
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        if (!stopping) worker.execute(() -> {
            try { NativeServer.stop(); }
            catch (Exception | LinkageError failure) { failed = true; }
        });
        worker.shutdown();
        super.onDestroy();
    }
}
