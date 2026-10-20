package io.moli.android;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;
import java.net.*;

/** User-controlled read-only onboarding. The document is staged, never executed from its URI. */
public final class IntegrationsActivity extends Activity {
    static final int PICK = 71;
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    Button choose, approve;
    AlertDialog confirmationDialog;
    TextView review, values, message, refreshStatus;
    int successfulReads;
    private ScrollView scroll;
    private JSONObject pending;
    private String source = "";
    private final Handler timer = new Handler(Looper.getMainLooper());
    private boolean reading;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { loadValues(); timer.postDelayed(this, 5000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); body.setPadding(pad,pad,pad,pad); scroll.addView(body);
        TextView heading = new TextView(this); heading.setText(R.string.integration_data_heading); heading.setTextSize(22); body.addView(heading);
        refreshStatus = new TextView(this); refreshStatus.setText(R.string.integration_no_read); body.addView(refreshStatus);
        button(body,R.string.integration_refresh, this::loadValues);
        values = new TextView(this); values.setTextIsSelectable(true); body.addView(values);
        message = new TextView(this); message.setText(R.string.integration_help); body.addView(message);
        button(body, R.string.stop, () -> startService(new Intent(this,MoliService.class).setAction(MoliService.STOP)));
        button(body,R.string.start, () -> {
            if (!ProtectedKey.exists(this)) { message.setText(R.string.integration_initialize); return; }
            getSharedPreferences("host",MODE_PRIVATE).edit().putBoolean("wanted",true).apply();
            startForegroundService(new Intent(this,MoliService.class)); loadValues();
        });
        choose = button(body,R.string.integration_import, () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, PICK);
        });
        button(body,R.string.integration_example, () -> stage(null));
        review = new TextView(this); review.setTextIsSelectable(true); body.addView(review);
        approve = button(body,R.string.integration_approve, () -> {
            if (pending == null) return;
            final String hash = pending.optString("sha256");
            change(() -> { ProfileStore.approve(this, hash); return null; });
        }); approve.setEnabled(false);
        button(body,R.string.integration_demo, () -> confirm(R.string.integration_demo_permission, () -> { ProfileStore.demo(this); return null; }));
        button(body,R.string.integration_weather, () -> confirm(R.string.integration_weather_permission, () -> { ProfileStore.weather(this); return null; }));
        button(body,R.string.integration_remove_import, () -> change(() -> { ProfileStore.remove(this,"import"); return null; }));
        button(body,R.string.integration_remove_demo, () -> change(() -> { ProfileStore.remove(this,"demo"); return null; }));
        button(body,R.string.integration_remove_weather, () -> change(() -> { ProfileStore.remove(this,"weather"); return null; }));
        button(body,R.string.integration_rollback, () -> change(() -> { ProfileStore.rollback(this); return null; }));
        button(body,R.string.back, this::finish); setContentView(scroll);
    }
    private Button button(LinearLayout parent, int label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); parent.addView(b); return b;
    }
    private void confirm(int explanation, Callable<Void> action) {
        if (EngineHost.state() != EngineHost.State.STOPPED) {
            showStopRequired(); return;
        }
        confirmationDialog = new AlertDialog.Builder(this).setMessage(explanation).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.integration_approve,(d,w) -> change(action)).show();
    }
    private void change(Callable<Void> action) {
        try {
            Future<Void> work = EngineHost.maintain(action);
            approve.setEnabled(false);
            IO.execute(() -> {
                try { work.get(); runOnUiThread(() -> { if (!isDestroyed()) { pending = null; review.setText(""); message.setText(R.string.integration_saved); scroll.smoothScrollTo(0,0); loadValues(); } }); }
                catch (Exception failure) { error(failure.getCause() == null ? failure : failure.getCause()); }
            });
        } catch (IllegalStateException busy) { showStopRequired(); }
    }
    private void showStopRequired() {
        message.setText(R.string.integration_stop_first);
        new AlertDialog.Builder(this).setMessage(R.string.integration_stop_first)
                .setPositiveButton(android.R.string.ok,null).show();
        scroll.smoothScrollTo(0,0);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request == PICK && result == RESULT_OK && data != null && data.getData() != null) stage(data.getData());
    }
    void stage(Uri uri) {
        pending = null; approve.setEnabled(false); review.setText("");
        IO.execute(() -> {
            try {
                String name = getString(R.string.integration_example);
                if (uri != null) {
                    if (!"content".equals(uri.getScheme())) throw new ProfileStore.Rejected("input");
                    name = getString(R.string.integration_document);
                    try (Cursor c = getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                        if (c != null && c.moveToFirst()) name = c.getString(0);
                    }
                }
                final String display = name == null ? "TOML" : name.substring(0,Math.min(name.length(),120));
                JSONObject info = ProfileStore.stage(this,uri == null ? getAssets().open("synthetic-temperature.toml") : getContentResolver().openInputStream(uri));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    pending = info; source = display;
                    review.setText(getString(R.string.integration_review,source,info.optString("name"),info.optString("sha256"),
                            getString("weather".equals(info.optString("mode")) ? R.string.integration_weather_permission : R.string.integration_synthetic_permission),
                            info.optInt("points"), info.optJSONArray("reads").toString()));
                    approve.setEnabled(true);
                });
            } catch (Exception | LinkageError failure) { error(failure); }
        });
    }
    private void error(Throwable failure) {
        runOnUiThread(() -> { if (!isDestroyed()) {
            int id = failure instanceof ProfileStore.Rejected && "changed".equals(failure.getMessage()) ? R.string.integration_changed : R.string.integration_rejected;
            message.setText(id); pending = null; approve.setEnabled(false);
        }});
    }
    void loadValues() {
        if (reading || values == null) return;
        reading = true;
        IO.execute(() -> {
            StringBuilder text = new StringBuilder();
            long queriedAt = 0;
            try {
                JSONObject installed = ProfileStore.state(this);
                if (installed.has("weather") || (installed.optJSONObject("import") != null
                        && "weather".equals(installed.getJSONObject("import").optString("mode"))))
                    text.append(getString(R.string.integration_weather_cadence)).append('\n');
                text.append(getString(R.string.integration_selected,
                        installed.optBoolean("demo") ? "Demo" : "—",
                        installed.has("weather") ? "Open-Meteo" : "—",
                        installed.has("import") ? installed.getJSONObject("import").optString("name") : "—")).append('\n');
                if (EngineHost.state() != EngineHost.State.RUNNING) {
                    text.append(getString(EngineHost.profileFailure ? R.string.integration_changed : R.string.integration_not_running));
                } else {
                    String origin = EngineHost.origin(), token = EngineHost.token(); long lease = EngineHost.currentLease();
                    HttpURLConnection c = (HttpURLConnection)new URL(origin + "/api/devices").openConnection();
                    c.setConnectTimeout(2000); c.setReadTimeout(2000); c.setUseCaches(false); c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("Cookie","moli_android=" + token);
                    JSONObject snapshot;
                    try { if (c.getResponseCode()!=200) throw new java.io.IOException(); snapshot = new JSONObject(ProfileStore.bounded(c.getInputStream(),262144)); }
                    finally { c.disconnect(); }
                    if (EngineHost.state()!=EngineHost.State.RUNNING || lease!=EngineHost.currentLease()) throw new java.io.IOException();
                    text.append(getString(R.string.integration_engine_running)).append('\n');
                    text.append(renderValues(installed,snapshot));
                    queriedAt = System.currentTimeMillis();
                }
            } catch (Exception failure) { text.append(getString(R.string.integration_unavailable)); }
            String result = text.toString(); final long observedAt = queriedAt;
            runOnUiThread(() -> {
                reading = false;
                if (!isDestroyed()) {
                    values.setText(result);
                    if (observedAt > 0) {
                        successfulReads++;
                        refreshStatus.setText(getString(R.string.integration_read_status,
                                java.text.DateFormat.getTimeInstance().format(new java.util.Date(observedAt)), successfulReads));
                    } else refreshStatus.setText(R.string.integration_no_read);
                }
            });
        });
    }
    private String renderValues(JSONObject installed, JSONObject snapshot) throws Exception {
        StringBuilder out = new StringBuilder(); JSONArray devices = snapshot.getJSONArray("devices"), drivers = snapshot.getJSONArray("drivers");
        for (int i=0;i<devices.length();i++) {
            JSONObject device = devices.getJSONObject(i); String instance = device.optString("instance");
            String key = instance.equals("android-import") ? "import" : instance.equals("android-weather") ? "weather" : instance.equals("android-demo") ? "demo" : "";
            if (key.isEmpty() || !installed.has(key)) continue;
            boolean running = false;
            for (int d=0;d<drivers.length();d++) if (instance.equals(drivers.getJSONObject(d).optString("instance")))
                running = "running".equals(drivers.getJSONObject(d).getJSONObject("status").optString("state"));
            JSONObject entry = installed.optJSONObject(key);
            boolean simulated = key.equals("demo") || (entry != null && "synthetic".equals(entry.optString("mode")));
            out.append('\n').append(device.optString("native_name")).append(" — ").append(getString(simulated ? R.string.integration_simulated : R.string.integration_internet)).append('\n');
            JSONObject state = device.getJSONObject("state"); JSONArray points = device.getJSONArray("points");
            long threshold = Math.max(30,(entry == null ? 5 : entry.optLong("every",900))+30)*1000;
            android.net.ConnectivityManager connectivity = getSystemService(android.net.ConnectivityManager.class);
            boolean transportAvailable = simulated || connectivity.getActiveNetwork() != null;
            for (int j=0;j<points.length();j++) {
                JSONObject point = points.getJSONObject(j), sample = state.optJSONObject(point.getString("key"));
                if (sample == null) continue;
                long stamp = sample.optLong("ts");
                boolean fresh = DataFreshness.recent(running && transportAvailable, device.optBoolean("online"), !sample.isNull("value"), stamp, System.currentTimeMillis(), threshold);
                out.append(point.optString("label")).append(": ").append(sample.opt("value")).append(' ').append(point.optString("unit"))
                        .append(" — ").append(getString(fresh ? R.string.integration_fresh : R.string.integration_stale))
                        .append(" — ").append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(stamp))).append('\n');
            }
        }
        return out.length()==0 ? getString(R.string.integration_waiting) : out.toString();
    }
    @Override public void onResume() { super.onResume(); timer.removeCallbacks(refresh); timer.post(refresh); }
    @Override public void onPause() { timer.removeCallbacks(refresh); super.onPause(); }
}
