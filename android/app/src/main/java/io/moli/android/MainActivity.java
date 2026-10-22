package io.moli.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.webkit.*;
import android.widget.*;

public final class MainActivity extends Activity {
    WebView web; // Package-visible to the isolated instrumentation smoke test.
    private TextView status;
    private Button start, stop, backup;
    private MoliService server;
    private boolean bound;
    private volatile String loaded;
    private long exitBackAt = -1;
    private Toast exitHint;
    private Runnable unregisterBack;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            EngineHost.State state = EngineHost.state();
            // A settings screen may have started the service while this screen
            // was stopped and unbound. Bind it without issuing another start.
            if (!bound && (state == EngineHost.State.RUNNING || state == EngineHost.State.STARTING))
                bound = bindService(new Intent(MainActivity.this, MoliService.class), connection, 0);
            // Binding can complete after the first UI refresh. Do not unbind a start
            // request before Android has delivered its command to the service.
            if (state == EngineHost.State.STOPPED && bound && (server == null || !server.hasCommand()))
                state = EngineHost.State.STARTING;
            start.setEnabled(state == EngineHost.State.STOPPED);
            stop.setEnabled(state == EngineHost.State.RUNNING || state == EngineHost.State.STARTING || state == EngineHost.State.ERROR);
            backup.setEnabled(state == EngineHost.State.STOPPED);
            if (state != EngineHost.State.RUNNING) {
                if (loaded != null) {
                    loaded = null; web.stopLoading(); web.loadUrl("about:blank");
                }
                web.setVisibility(View.GONE); status.setVisibility(View.VISIBLE);
                status.setText(state == EngineHost.State.STARTING ? R.string.starting : state == EngineHost.State.STOPPING ? R.string.stopping :
                        state == EngineHost.State.ERROR ? (EngineHost.profileFailure ? R.string.integration_changed : R.string.failed) : state == EngineHost.State.MAINTENANCE ? R.string.backup_working : R.string.stopped);
                if (state == EngineHost.State.STOPPED || state == EngineHost.State.ERROR) {
                    if (bound) { unbindService(connection); bound = false; } server = null;
                }
            }
            if (server != null && server.origin() != null && !server.origin().equals(loaded)) {
                String origin = server.origin();
                String token = server.token();
                CookieManager.getInstance().setCookie(origin,
                        "moli_android=" + token + "; Path=/; HttpOnly; SameSite=Strict", accepted -> {
                    if (!isDestroyed() && EngineHost.state() == EngineHost.State.RUNNING && origin.equals(EngineHost.origin()) && Boolean.TRUE.equals(accepted)) {
                        loaded = origin;
                        web.loadUrl(origin + "/");
                        web.setVisibility(View.VISIBLE);
                        status.setVisibility(View.GONE);
                    }
                });
            }
            handler.postDelayed(this, 500);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { server = ((MoliService.LocalBinder) binder).service(); }
        @Override public void onServiceDisconnected(ComponentName name) {
            server = null; loaded = null;
            status.setText(R.string.stopped); status.setVisibility(View.VISIBLE); web.setVisibility(View.GONE);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout controls = new LinearLayout(this);
        start = new Button(this); start.setText(R.string.start); start.setOnClickListener(v -> prepare());
        stop = new Button(this); stop.setText(R.string.stop); stop.setOnClickListener(v -> stopServer());
        controls.addView(start); controls.addView(stop); root.addView(controls);
        Button settings = new Button(this); settings.setText(R.string.home_settings);
        settings.setOnClickListener(v -> startActivity(new Intent(this,HomeSettingsActivity.class))); controls.addView(settings);
        backup = new Button(this); backup.setText(R.string.backup_title);
        backup.setOnClickListener(v -> startActivity(new Intent(this, BackupActivity.class)));
        LinearLayout tools = new LinearLayout(this); tools.addView(backup);
        Button diagnostics = new Button(this); diagnostics.setText(R.string.home_tools);
        diagnostics.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this,diagnostics);
            menu.getMenu().add(0,1,0,R.string.app_info); menu.getMenu().add(0,2,1,R.string.integration_title);
            menu.setOnMenuItemClickListener(item -> { startActivity(new Intent(this,item.getItemId()==1?AppInfoActivity.class:IntegrationsActivity.class)); return true; }); menu.show();
        }); tools.addView(diagnostics);
        Button equipment = new Button(this); equipment.setText(R.string.equipment_title);
        equipment.setOnClickListener(v -> startActivity(new Intent(this,EquipmentSettingsActivity.class)));
        tools.addView(equipment); root.addView(tools);
        status = new TextView(this); status.setText(R.string.stopped); root.addView(status);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                resetExitBack();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String origin = loaded;
                if (request.isForMainFrame() && origin != null && EngineHost.state() == EngineHost.State.RUNNING && origin.equals(EngineHost.origin()) && (origin+"/android/equipment").equals(uri.toString())) {
                    startActivity(new Intent(MainActivity.this,EquipmentSettingsActivity.class)); return true;
                }
                if (request.isForMainFrame() && origin != null && EngineHost.state() == EngineHost.State.RUNNING && origin.equals(EngineHost.origin()) && (origin+"/android/settings").equals(uri.toString())) {
                    startActivity(new Intent(MainActivity.this,HomeSettingsActivity.class)); return true;
                }
                return origin == null || EngineHost.state() != EngineHost.State.RUNNING || !origin.equals(EngineHost.origin()) || !"http".equals(uri.getScheme())
                        || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() != Uri.parse(origin).getPort();
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String origin = loaded;
                if (origin != null && EngineHost.state() == EngineHost.State.RUNNING && origin.equals(EngineHost.origin()) && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                        && uri.getPort() == Uri.parse(origin).getPort()) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) unregisterBack = ModernBack.register(this);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        handler.post(refresh);
        if (EngineHost.state() == EngineHost.State.RUNNING || EngineHost.state() == EngineHost.State.STARTING ||
                (getSharedPreferences("host", MODE_PRIVATE).getBoolean("wanted", true) && ProtectedKey.exists(this))) startServer();
    }
    private void prepare() {
        if (EngineHost.state() != EngineHost.State.STOPPED) return;
        if (ProtectedKey.exists(this)) { startServer(); return; }
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint(R.string.pin_hint);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.pin_title).setMessage(R.string.preview_warning).setView(pin)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.start, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = pin.getText().toString();
            if (!code.matches("[0-9]{4,32}")) { pin.setError(getString(R.string.pin_invalid)); return; }
            if (EngineHost.state() != EngineHost.State.STOPPED) { dialog.dismiss(); return; }
            try { ProtectedKey.create(this, code); dialog.dismiss(); startServer(); }
            catch (Exception failure) { status.setText(R.string.key_unavailable); dialog.dismiss(); }
        }));
        dialog.show();
    }
    private void startServer() {
        if (bound) return;
        getSharedPreferences("host", MODE_PRIVATE).edit().putBoolean("wanted", true).apply();
        status.setText(R.string.starting); status.setVisibility(View.VISIBLE);
        Intent intent = new Intent(this, MoliService.class);
        startForegroundService(intent);
        bound = bindService(intent, connection, BIND_AUTO_CREATE);
    }
    private void stopServer() {
        getSharedPreferences("host", MODE_PRIVATE).edit().putBoolean("wanted", false).apply();
        // Keep the binding until the worker confirms that persistence/shutdown finished.
        startService(new Intent(this, MoliService.class).setAction(MoliService.STOP));
        web.loadUrl("about:blank"); web.setVisibility(View.GONE);
        status.setText(R.string.stopping); status.setVisibility(View.VISIBLE);
    }
    // Return to the dashboard root, rather than traversing WebView history (which
    // also contains loading/stopped pages). Closing the UI never stops the service.
    private void dashboardBack() {
        String origin = loaded;
        String url = web.getUrl();
        if (origin != null && url != null && url.startsWith(origin + "/")) {
            Uri page = Uri.parse(url);
            String fragment = page.getFragment();
            if (!"/".equals(page.getPath()) || (fragment != null && !fragment.isEmpty() && !"/".equals(fragment))) {
                resetExitBack();
                web.loadUrl(origin + "/#/");
                return;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (exitBackAt >= 0 && now - exitBackAt <= 2000) {
            resetExitBack();
            finish();
        } else {
            exitBackAt = now;
            if (exitHint != null) exitHint.cancel();
            exitHint = Toast.makeText(this, EngineHost.state() == EngineHost.State.RUNNING
                    ? R.string.back_exit_running : R.string.back_exit, Toast.LENGTH_SHORT);
            exitHint.show();
        }
    }
    private void resetExitBack() {
        exitBackAt = -1;
        if (exitHint != null) { exitHint.cancel(); exitHint = null; }
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { dashboardBack(); }

    @android.annotation.TargetApi(33)
    private static final class ModernBack {
        static Runnable register(MainActivity activity) {
            android.window.OnBackInvokedCallback callback = activity::dashboardBack;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return () -> activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
    }
    @Override public void onStart() {
        super.onStart(); web.onResume(); handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override public void onStop() {
        resetExitBack(); handler.removeCallbacks(refresh); web.onPause(); super.onStop();
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(refresh);
        if (unregisterBack != null) unregisterBack.run();
        if (bound) unbindService(connection);
        web.destroy();
        super.onDestroy();
    }
}
