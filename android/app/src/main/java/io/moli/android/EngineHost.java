package io.moli.android;

import android.content.Context;
import org.json.JSONObject;
import java.util.concurrent.*;

/** One process-wide queue serializes JNI lifetimes and all data maintenance. */
final class EngineHost {
    enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR, MAINTENANCE }
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static volatile State state = State.STOPPED;
    private static volatile String origin, token;
    private static long lease;
    private static SyntheticEndpoint synthetic;
    static volatile boolean profileFailure;
    private EngineHost() {}
    static State state() { return state; }
    static String origin() { return origin; }
    static String token() { return token; }
    static synchronized long currentLease() { return lease; }
    static synchronized long start(Context context) {
        if (state == State.RUNNING || state == State.STARTING) return lease;
        if (state != State.STOPPED) return -1;
        long current = ++lease;
        state = State.STARTING; profileFailure = false;
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                JSONObject keys = ProtectedKey.open(app);
                String session = ProtectedKey.randomHex();
                synthetic = ProfileStore.prepare(app);
                int port = NativeServer.start(ProtectedKey.dataDirectory(app, keys).getAbsolutePath(),
                        keys.getString("master"), session, keys.getString("pin"));
                synchronized (EngineHost.class) {
                    token = session; origin = "http://127.0.0.1:" + port;
                    if (state == State.STARTING) {
                        state = State.RUNNING;
                        android.content.SharedPreferences history = app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE);
                        history.edit().putLong("starts", history.getLong("starts", 0) + 1).apply();
                    }
                }
            } catch (Exception | LinkageError failure) {
                if (synthetic != null) { synthetic.close(); synthetic = null; }
                profileFailure = failure instanceof ProfileStore.Rejected;
                synchronized (EngineHost.class) { state = State.ERROR; }
            }
        });
        return current;
    }
    static synchronized void stop(long owner) {
        if (owner != lease || state == State.STOPPED || state == State.STOPPING || state == State.MAINTENANCE) return;
        state = State.STOPPING;
        WORK.execute(() -> {
            boolean success = false;
            try { NativeServer.stop(); success = true; }
            catch (Exception | LinkageError failure) { /* No secret/native error logging. */ }
            finally {
                synchronized (EngineHost.class) {
                    if (synthetic != null) { synthetic.close(); synthetic = null; }
                    origin = null; token = null; state = success ? State.STOPPED : State.ERROR;
                }
            }
        });
    }
    static synchronized <T> Future<T> maintain(Callable<T> task) {
        if (state != State.STOPPED) throw new IllegalStateException("Stop the engine first");
        state = State.MAINTENANCE;
        return WORK.submit(() -> {
            try { return task.call(); }
            finally { synchronized (EngineHost.class) { state = State.STOPPED; } }
        });
    }
}
