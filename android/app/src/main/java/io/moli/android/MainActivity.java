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
    private MoliService server;
    private boolean bound;
    private String loaded;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (server != null && (server.failed() || server.stopped())) {
                boolean failed = server.failed();
                if (bound) { unbindService(connection); bound = false; }
                server = null; loaded = null;
                web.loadUrl("about:blank"); web.setVisibility(View.GONE);
                status.setText(failed ? R.string.failed : R.string.stopped);
                status.setVisibility(View.VISIBLE);
                return;
            }
            if (server != null && server.origin() != null && !server.origin().equals(loaded)) {
                String origin = server.origin();
                String token = server.token();
                CookieManager.getInstance().setCookie(origin,
                        "moli_android=" + token + "; Path=/; HttpOnly; SameSite=Strict", accepted -> {
                    if (!isDestroyed() && Boolean.TRUE.equals(accepted)) {
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
        Button start = new Button(this); start.setText(R.string.start); start.setOnClickListener(v -> prepare());
        Button stop = new Button(this); stop.setText(R.string.stop); stop.setOnClickListener(v -> stopServer());
        controls.addView(start); controls.addView(stop); root.addView(controls);
        status = new TextView(this); status.setText(R.string.stopped); root.addView(status);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String origin = loaded;
                return origin == null || !"http".equals(uri.getScheme())
                        || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() != Uri.parse(origin).getPort();
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String origin = loaded;
                if (origin != null && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                        && uri.getPort() == Uri.parse(origin).getPort()) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        prepare();
    }
    private void prepare() {
        if (ProtectedKey.exists(this)) { startServer(); return; }
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint(R.string.pin_hint);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.pin_title).setMessage(R.string.preview_warning).setView(pin)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.start, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = pin.getText().toString();
            if (!code.matches("[0-9]{4,32}")) { pin.setError(getString(R.string.pin_invalid)); return; }
            try { ProtectedKey.create(this, code); dialog.dismiss(); startServer(); }
            catch (Exception failure) { status.setText(R.string.key_unavailable); dialog.dismiss(); }
        }));
        dialog.show();
    }
    private void startServer() {
        if (bound) return;
        status.setText(R.string.starting); status.setVisibility(View.VISIBLE);
        Intent intent = new Intent(this, MoliService.class);
        startForegroundService(intent);
        bound = bindService(intent, connection, BIND_AUTO_CREATE);
        handler.post(refresh);
    }
    private void stopServer() {
        handler.removeCallbacks(refresh);
        if (bound) { unbindService(connection); bound = false; }
        server = null; loaded = null;
        startService(new Intent(this, MoliService.class).setAction(MoliService.STOP));
        web.loadUrl("about:blank"); web.setVisibility(View.GONE);
        status.setText(R.string.stopped); status.setVisibility(View.VISIBLE);
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(refresh);
        if (bound) unbindService(connection);
        web.destroy();
        super.onDestroy();
    }
}
