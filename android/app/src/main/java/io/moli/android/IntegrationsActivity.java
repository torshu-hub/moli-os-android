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
    static final int PICK = 71, EXPORT_TRIAL = 72;
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    Button choose, approve;
    AlertDialog confirmationDialog;
    TextView review, values, message, refreshStatus;
    int successfulReads;
    TextView guide, probeResult, trialStatus;
    Button probe;
    JSONObject probeObservation;
    String probeOutcome = "none";
    private ScrollView scroll;
    private JSONObject pending;
    private String source = "";
    private final Handler timer = new Handler(Looper.getMainLooper());
    private boolean reading;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { loadValues(); timer.postDelayed(this, 5000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); TrialLog.attach(this);
        scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); body.setPadding(pad,pad,pad,pad); scroll.addView(body);
        TextView heading = new TextView(this); heading.setText(R.string.integration_data_heading); heading.setTextSize(22); body.addView(heading);
        button(body,R.string.trial_view_values, () -> scroll.smoothScrollTo(0,values.getTop()));
        guide = new TextView(this); body.addView(guide);
        trialStatus = new TextView(this); body.addView(trialStatus); updateTrialStatus();
        button(body,R.string.trial_begin, () -> IO.execute(() -> { TrialLog.begin(this); runOnUiThread(this::updateTrialStatus); loadValuesOnUi(); }));
        button(body,R.string.trial_end, () -> IO.execute(() -> { TrialLog.end(this); runOnUiThread(this::updateTrialStatus); }));
        button(body,R.string.trial_export, () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"moli-os-essai-0.6.0.txt"),EXPORT_TRIAL));
        probe = button(body,R.string.trial_probe, this::weatherProbe);
        probeResult = new TextView(this); probeResult.setText(R.string.trial_probe_none); body.addView(probeResult);
        refreshStatus = new TextView(this); refreshStatus.setText(R.string.integration_no_read); body.addView(refreshStatus);
        button(body,R.string.integration_refresh, this::loadValues);
        values = new TextView(this); values.setTextIsSelectable(true); body.addView(values);
        button(body,R.string.trial_view_guide, () -> scroll.smoothScrollTo(0,0));
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
        renderGuide(new JSONObject(),new TrialEvidence());
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
        if (request == EXPORT_TRIAL && result == RESULT_OK && data != null && data.getData() != null) {
            Uri destination=data.getData();
            IO.execute(() -> {
                try {
                    if(!"content".equals(destination.getScheme())) throw new java.io.IOException();
                    String journal=TrialLog.export(this);
                    try(java.io.OutputStream out=getContentResolver().openOutputStream(destination,"wt")) {
                        if(out==null) throw new java.io.IOException(); out.write(journal.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    runOnUiThread(() -> { if(!isDestroyed()) message.setText(R.string.trial_exported); });
                } catch(Exception ignored) { runOnUiThread(() -> { if(!isDestroyed()) message.setText(R.string.trial_export_failed); }); }
            });
        }
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
        TrialLog.record(TrialJournal.Event.PROFILE_REJECTED,0,0);
        runOnUiThread(() -> { if (!isDestroyed()) {
            int id = failure instanceof ProfileStore.Rejected && "changed".equals(failure.getMessage()) ? R.string.integration_changed : R.string.integration_rejected;
            message.setText(id); pending = null; approve.setEnabled(false);
        }});
    }
    private void loadValuesOnUi() { runOnUiThread(this::loadValues); }
    private void updateTrialStatus() {
        trialStatus.setText(getString(TrialLog.active()?R.string.trial_active:R.string.trial_inactive,TrialLog.count()));
    }
    private void weatherProbe() {
        probe.setEnabled(false); probeObservation=null; probeOutcome="pending"; probeResult.setText(R.string.trial_probe_pending);
        IO.execute(() -> {
            try {
                JSONObject result=ProfileStore.weatherProbe(this);
                runOnUiThread(() -> { if(!isDestroyed()) { probeObservation=result; probeOutcome="ok"; probeResult.setText(getString(R.string.trial_probe_ok,
                        result.optDouble("temperature"),result.optDouble("humidity"),
                        java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(result.optLong("received_ms"))))); } });
            } catch(Exception failure) {
                int code=failure instanceof ProfileStore.Rejected && "rate".equals(failure.getMessage())?R.string.trial_probe_limited:
                        failure instanceof ProfileStore.Rejected && "approval".equals(failure.getMessage())?R.string.trial_probe_approval:R.string.trial_probe_failed;
                runOnUiThread(() -> { if(!isDestroyed()) { probeOutcome=code==R.string.trial_probe_limited?"limited":"unavailable"; probeResult.setText(code); } });
            } finally { runOnUiThread(() -> { if(!isDestroyed()) { probe.setEnabled(true); updateTrialStatus(); } }); }
        });
    }
    private String yes(boolean value) { return getString(value?R.string.trial_seen:R.string.trial_not_seen); }
    private void renderGuide(JSONObject selected,TrialEvidence evidence) {
        guide.setText(getString(R.string.trial_guide,
                getString(EngineHost.state()==EngineHost.State.STOPPED?R.string.stopped:EngineHost.state()==EngineHost.State.RUNNING?R.string.trial_running:R.string.trial_transition),
                yes(selected.has("import")),yes(evidence.synthetic),yes(evidence.demo),yes(selected.has("weather") || (selected.optJSONObject("import")!=null && "weather".equals(selected.optJSONObject("import").optString("mode")))),yes(evidence.weather)));
    }
    void loadValues() {
        if (reading || values == null) return;
        reading = true;
        IO.execute(() -> {
            StringBuilder text = new StringBuilder();
            long queriedAt = 0; boolean attemptedRead = false;
            JSONObject installed = new JSONObject(); TrialEvidence evidence = new TrialEvidence();
            try {
                installed = ProfileStore.state(this);
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
                    try { attemptedRead = true; if (c.getResponseCode()!=200) throw new java.io.IOException(); snapshot = new JSONObject(ProfileStore.bounded(c.getInputStream(),262144)); }
                    finally { c.disconnect(); }
                    if (EngineHost.state()!=EngineHost.State.RUNNING || lease!=EngineHost.currentLease()) throw new java.io.IOException();
                    text.append(getString(R.string.integration_engine_running)).append('\n');
                    text.append(renderValues(installed,snapshot));
                    queriedAt = System.currentTimeMillis();
                    android.net.ConnectivityManager net=getSystemService(android.net.ConnectivityManager.class);
                    evidence=TrialEvidence.observe(installed,snapshot,queriedAt,net.getActiveNetwork()!=null);
                    TrialLog.record(TrialJournal.Event.ENGINE_READ_OK,successfulReads+1,evidence.weatherReceipt);
                    TrialLog.record(TrialJournal.Event.SYNTHETIC_RECEIVED,evidence.synthetic?1:0,0);
                    TrialLog.record(TrialJournal.Event.DEMO_RECEIVED,evidence.demo?1:0,0);
                    TrialLog.record(TrialJournal.Event.WEATHER_RECEIVED,evidence.weather?1:0,0);
                }
            } catch (Exception failure) {
                text.append(getString(R.string.integration_unavailable));
                if(attemptedRead) TrialLog.record(TrialJournal.Event.ENGINE_READ_FAILED,0,0);
            }
            String result = text.toString(); final long observedAt = queriedAt;
            final JSONObject selected=installed; final TrialEvidence observed=evidence;
            runOnUiThread(() -> {
                reading = false;
                if (!isDestroyed()) {
                    values.setText(result); renderGuide(selected,observed); updateTrialStatus();
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
