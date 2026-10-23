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
    private TextView status,dashboardStatus;
    private Button startButton,stopButton,restartButton;
    private long loadedLease=-1,authLease=-1,authRequest;
    private boolean pageFailed;
    private long retryAfter;
    private android.view.View home;
    private LinearLayout dashboard;
    private boolean viewing;
    private ReportExport report;
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
            long[] health=EngineHealth.snapshot();
            if(state==EngineHost.State.RUNNING)status.setText(health[0]==1 ? getString(R.string.engine_live,health[1]/1000) :
                    getString(health[5]==2 ? R.string.engine_observed_failure : health[5]==3 ? R.string.engine_not_observed : R.string.engine_no_sample));
            startButton.setEnabled(state==EngineHost.State.STOPPED);
            stopButton.setEnabled(state==EngineHost.State.RUNNING || state==EngineHost.State.STARTING);
            restartButton.setEnabled(state==EngineHost.State.RUNNING || state==EngineHost.State.ERROR);
            if (state != EngineHost.State.RUNNING) {
                if (loaded != null) {
                    invalidateDashboard();
                }
                web.setVisibility(View.GONE); status.setVisibility(View.VISIBLE);
                status.setText(state == EngineHost.State.STARTING ? R.string.starting : state == EngineHost.State.STOPPING ? R.string.stopping :
                        state == EngineHost.State.ERROR ? (EngineHost.profileFailure ? R.string.integration_changed : R.string.failed) : state == EngineHost.State.MAINTENANCE ? R.string.backup_working : R.string.stopped);
                if (state == EngineHost.State.STOPPED || state == EngineHost.State.ERROR) {
                    if (bound) { unbindService(connection); bound = false; } server = null;
                }
            }
            long generation=EngineHost.currentLease();
            if (server != null && state==EngineHost.State.RUNNING && server.origin()!=null &&
                    (loadedLease!=generation || !server.origin().equals(loaded) || pageFailed) &&
                    (!pageFailed || SystemClock.elapsedRealtime()>=retryAfter) && authLease!=generation) {
                authenticateDashboard(generation,server.origin(),server.token());
            }
            dashboardStatus.setText(pageFailed?getString(R.string.engine_display_retry):status.getText());dashboardStatus.setVisibility(state==EngineHost.State.RUNNING && !pageFailed?View.GONE:View.VISIBLE);
            handler.postDelayed(this, 500);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { server = ((MoliService.LocalBinder) binder).service(); }
        @Override public void onServiceDisconnected(ComponentName name) {
            server = null; invalidateDashboard();
            status.setText(R.string.stopped); status.setVisibility(View.VISIBLE); web.setVisibility(View.GONE);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); TrialLog.attach(this);
        TrialLog.begin(this);
        LinearLayout root = TrialUi.page(this);
        LinearLayout controls=TrialUi.card(this,root);
        status=TrialUi.text(this,controls,R.string.stopped);
        LinearLayout buttons=new LinearLayout(this);controls.addView(buttons);
        startButton=TrialUi.button(this,buttons,R.string.start,true,this::prepare);
        stopButton=TrialUi.button(this,buttons,R.string.stop,false,()->sendCommand(MoliService.STOP));
        restartButton=TrialUi.button(this,buttons,R.string.engine_restart,false,()->sendCommand(MoliService.RESTART));
        for(Button button:new Button[]{startButton,stopButton,restartButton}) {
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(TrialUi.dp(this,2),0,TrialUi.dp(this,2),0);button.setLayoutParams(lp);
        }
        TrialUi.button(this,controls,R.string.engine_reconnect,false,()->{invalidateDashboard();EngineHealth.check();});
        home=new LinearLayout(this);((LinearLayout)home).setOrientation(LinearLayout.VERTICAL);root.addView(home);
        LinearLayout front=(LinearLayout)home;
        TrialUi.title(this,front,R.string.preview_home_title);
        TrialUi.text(this,front,R.string.preview_home_help);
        LinearLayout actions=TrialUi.card(this,front);
        TrialUi.button(this,actions,R.string.preview_search,true,()->{if(ensureKeys())startActivity(new Intent(this,SearchHomeActivity.class));});
        TrialUi.button(this,actions,R.string.preview_devices,false,this::showDevices);
        report=new ReportExport(this);
        TrialUi.button(this,actions,R.string.report_export,false,report::choose);
        TrialUi.text(this,front,R.string.preview_report_auto);
        TrialUi.button(this,front,R.string.preview_settings,false,()->startActivity(new Intent(this,PreviewSettingsActivity.class)));
        dashboard=new LinearLayout(this);dashboard.setOrientation(LinearLayout.VERTICAL);root.addView(dashboard);dashboard.setVisibility(View.GONE);
        TrialUi.button(this,dashboard,R.string.preview_home_title,false,this::showHome);
        dashboardStatus=TrialUi.text(this,dashboard,R.string.starting);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error) {
                if(request.isForMainFrame()) dashboardFailed();
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response) {
                if(request.isForMainFrame()) dashboardFailed();
            }
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
        dashboard.addView(web,new LinearLayout.LayoutParams(-1,Math.max(TrialUi.dp(this,480),getResources().getDisplayMetrics().heightPixels-TrialUi.dp(this,160))));
        if (Build.VERSION.SDK_INT >= 33) unregisterBack = ModernBack.register(this);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        if(saved==null && getIntent().getBooleanExtra("devices",false))showDevices();
        if(saved!=null && saved.getBoolean("viewing")){viewing=true;home.setVisibility(View.GONE);dashboard.setVisibility(View.VISIBLE);}
        handler.post(refresh);
        if (EngineHost.state() == EngineHost.State.RUNNING || EngineHost.state() == EngineHost.State.STARTING ||
                (getSharedPreferences("host", MODE_PRIVATE).getBoolean("wanted", true) && ProtectedKey.exists(this))) startServer();
    }
    private void prepare() {
        if (EngineHost.state() != EngineHost.State.STOPPED) return;
        if (ProtectedKey.exists(this)) { startServer(); return; }
        if(ensureKeys())startServer();
    }
    private boolean ensureKeys(){
        if(ProtectedKey.exists(this))return true;
        try{ProtectedKey.createPreview(this);return true;}catch(Exception failure){status.setText(R.string.key_unavailable);return false;}
    }
    void showDevices(){
        if(!ensureKeys())return;
        viewing=true;home.setVisibility(View.GONE);dashboard.setVisibility(View.VISIBLE);web.setVisibility(View.VISIBLE);
        if(EngineHost.state()==EngineHost.State.STOPPED)prepare();
        else if(loaded!=null)web.loadUrl(loaded+"/#/");
    }
    void showHome(){viewing=false;dashboard.setVisibility(View.GONE);home.setVisibility(View.VISIBLE);web.setVisibility(View.GONE);resetExitBack();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("viewing",viewing);}
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);report.result(request,result,intent);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(intent.getBooleanExtra("devices",false))showDevices();}
    private void startServer() {
        getSharedPreferences("host", MODE_PRIVATE).edit().putBoolean("wanted", true).apply();
        status.setText(R.string.starting); status.setVisibility(View.VISIBLE);
        Intent intent = new Intent(this, MoliService.class);
        startForegroundService(intent);
        if(!bound)bound = bindService(intent, connection, BIND_AUTO_CREATE);
    }
    private void sendCommand(String command) {
        if(EngineHost.state()==EngineHost.State.MAINTENANCE)return;
        invalidateDashboard();
        startService(new Intent(this,MoliService.class).setAction(command));
    }
    private void dashboardFailed() {
        pageFailed=true;retryAfter=SystemClock.elapsedRealtime()+5000;
        dashboardStatus.setText(R.string.engine_display_retry);dashboardStatus.setVisibility(View.VISIBLE);
    }
    private void invalidateDashboard() {
        ++authRequest;authLease=-1;loadedLease=-1;loaded=null;pageFailed=false;
        if(web!=null){web.stopLoading();web.loadUrl("about:blank");}
    }
    private void authenticateDashboard(long generation,String origin,String token) {
        authLease=generation;long request=++authRequest;
        CookieManager.getInstance().setCookie(origin,"moli_android="+token+"; Path=/; HttpOnly; SameSite=Strict",accepted->{
            if(isDestroyed() || request!=authRequest)return;
            authLease=-1;
            if(EngineHost.state()!=EngineHost.State.RUNNING || generation!=EngineHost.currentLease() || !origin.equals(EngineHost.origin()))return;
            if(!Boolean.TRUE.equals(accepted)){dashboardFailed();return;}
            loaded=origin;loadedLease=generation;pageFailed=false;
            web.loadUrl(origin+"/#/");web.setVisibility(viewing?View.VISIBLE:View.GONE);
        });
    }
    // Return to the dashboard root, rather than traversing WebView history (which
    // also contains loading/stopped pages). Closing the UI never stops the service.
    private void dashboardBack() {
        String origin = loaded;
        String url = viewing?web.getUrl():null;
        if (origin != null && url != null && url.startsWith(origin + "/")) {
            Uri page = Uri.parse(url);
            String fragment = page.getFragment();
            if (!"/".equals(page.getPath()) || (fragment != null && !fragment.isEmpty() && !"/".equals(fragment))) {
                resetExitBack();
                web.loadUrl(origin + "/#/");
                return;
            }
        }
        if(viewing){showHome();return;}
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
        super.onStart(); web.onResume(); invalidateDashboard(); EngineHealth.check(); handler.removeCallbacks(refresh); handler.post(refresh);
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
