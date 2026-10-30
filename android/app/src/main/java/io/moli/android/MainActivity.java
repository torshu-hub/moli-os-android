package io.moli.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
import java.util.Collections;

/** Native space selector and an unmodified, origin-restricted official dashboard. */
public final class MainActivity extends Activity {
    WebView web;
    private LinearLayout home, dashboard;
    private TextView status;
    private long loadedLease=-1,request;
    private volatile String loaded;
    private boolean viewing, authenticating;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){
        EngineHost.State state=EngineHost.state();
        status.setText(state==EngineHost.State.RUNNING?R.string.running:state==EngineHost.State.ERROR?R.string.operation_failed:state==EngineHost.State.STOPPED?R.string.stopped:R.string.working);
        if(state!=EngineHost.State.RUNNING){if(loaded!=null){invalidate();}web.setVisibility(View.GONE);status.setVisibility(View.VISIBLE);}
        else if(viewing && loadedLease!=EngineHost.currentLease() && !authenticating)authenticate();
        handler.postDelayed(this,500);
    }};
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout root=HostUi.screen(this);
        root.setBackgroundColor(getColor(R.color.management_background));
        home=ApkHome.create(this,this::showDashboard,()->startActivity(new Intent(this,ManagementActivity.class)));
        root.addView(home,new LinearLayout.LayoutParams(-1,0,1));
        dashboard=new LinearLayout(this);dashboard.setOrientation(LinearLayout.VERTICAL);root.addView(dashboard,new LinearLayout.LayoutParams(-1,0,1));dashboard.setVisibility(View.GONE);
        Button apkHome=HostUi.button(this,dashboard,R.string.apk_home,this::showHome);
        apkHome.setId(R.id.apk_home_return);apkHome.setAllCaps(false);
        apkHome.setBackgroundResource(R.drawable.management_button_secondary);
        apkHome.setTextColor(getColorStateList(R.color.management_button_text));
        status=HostUi.text(this,dashboard,R.string.stopped);
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage message){return true;}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !allowed(r.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return allowed(r.getUrl())?null:new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())failed();}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){if(r.isForMainFrame())failed();}
        });
        dashboard.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        if(saved!=null && saved.getBoolean("viewing"))showDashboard();
    }
    private boolean allowed(Uri uri){String origin=loaded;return origin!=null && EngineHost.state()==EngineHost.State.RUNNING && origin.equals(EngineHost.origin()) && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort()==Uri.parse(origin).getPort();}
    private void failed(){runOnUiThread(()->{status.setText(R.string.display_failed);status.setVisibility(View.VISIBLE);});}
    private void invalidate(){++request;loadedLease=-1;authenticating=false;loaded=null;web.stopLoading();web.loadUrl("about:blank");}
    private void authenticate(){
        final long generation=EngineHost.currentLease(),current=++request;
        final String origin=EngineHost.origin(),token=EngineHost.token();if(origin==null||token==null)return;
        authenticating=true;
        CookieManager.getInstance().setCookie(origin,"moli_android="+token+"; Path=/; HttpOnly; SameSite=Strict",accepted->{
            if(isDestroyed()||current!=request)return;authenticating=false;
            if(EngineHost.state()!=EngineHost.State.RUNNING || generation!=EngineHost.currentLease() || !origin.equals(EngineHost.origin()))return;
            if(!Boolean.TRUE.equals(accepted)){failed();return;}
            loaded=origin;loadedLease=generation;status.setVisibility(View.GONE);web.setVisibility(View.VISIBLE);
            web.loadUrl(origin+"/?view="+generation+"_"+current+"#/");
        });
    }
    void showDashboard(){viewing=true;home.setVisibility(View.GONE);dashboard.setVisibility(View.VISIBLE);if(EngineHost.state()==EngineHost.State.STOPPED || EngineHost.state()==EngineHost.State.ERROR)HostUi.command(this,null);}
    void showHome(){viewing=false;dashboard.setVisibility(View.GONE);home.setVisibility(View.VISIBLE);}
    @Override public void onBackPressed(){if(viewing){String url=web.getUrl();if(loaded!=null&&url!=null&&url.contains("#/")&&!url.endsWith("#/"))web.loadUrl(loaded+"/#/");else showHome();}else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("viewing",viewing);}
    @Override public void onStart(){super.onStart();WeatherBinding.start(getApplicationContext(),EngineHost.currentLease());web.onResume();invalidate();handler.post(refresh);}
    @Override public void onStop(){handler.removeCallbacks(refresh);web.onPause();super.onStop();}
    @Override public void onDestroy(){handler.removeCallbacks(refresh);web.destroy();super.onDestroy();}
}
