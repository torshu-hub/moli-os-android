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
import org.json.JSONObject;

/** Origin-restricted official dashboard; Android management owns the launcher. */
public final class MainActivity extends Activity {
    WebView web;
    private TextView status;
    private long loadedLease=-1,request;
    private volatile String loaded;
    private boolean authenticating, resetHistory;
    private String restoredRoute="/";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){
        EngineHost.State state=EngineHost.state();
        status.setText(state==EngineHost.State.RUNNING?R.string.running:state==EngineHost.State.ERROR?R.string.operation_failed:state==EngineHost.State.STOPPED?R.string.stopped:R.string.working);
        if(state!=EngineHost.State.RUNNING){if(loaded!=null){invalidate();}web.setVisibility(View.GONE);status.setVisibility(View.VISIBLE);}
        else if(loadedLease!=EngineHost.currentLease() && !authenticating)authenticate();
        handler.postDelayed(this,500);
    }};
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout root=HostUi.screen(this);
        root.setPadding(0,0,0,0);
        if(Build.VERSION.SDK_INT>=30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime=insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;
            });
        }
        root.setBackgroundColor(getColor(R.color.management_background));
        status=HostUi.text(this,root,R.string.stopped);
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage message){return true;}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !allowed(r.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return allowed(r.getUrl())?null:new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())failed();}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){if(r.isForMainFrame())failed();}
            @Override public void onPageFinished(WebView v,String url){
                // A new engine lease has a new origin. Its initial page must not
                // navigate back into a closed engine or an about:blank entry.
                if(resetHistory && allowed(Uri.parse(url))){resetHistory=false;v.clearHistory();}
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        if(saved!=null) restoredRoute=saved.getString("route","/");
        showDashboard();
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
            resetHistory=true;
            web.loadUrl(origin+"/?view="+generation+"_"+current+"#"+restoredRoute);
            restoredRoute="/";
        });
    }
    void showDashboard(){if(EngineHost.state()==EngineHost.State.STOPPED || EngineHost.state()==EngineHost.State.ERROR)HostUi.command(this,null);}
    @Override public void onBackPressed(){
        final String origin=loaded;final long current=request;
        if(origin==null || EngineHost.state()!=EngineHost.State.RUNNING){openManagement();return;}
        // Hash routes and replaceState belong to the document's session history.
        // Read and traverse that history together rather than a lagging native list.
        web.evaluateJavascript("(()=>{if(location.origin!=="+JSONObject.quote(origin)+")return false;const route=location.hash.replace(/^#\\/?/,'');if(!route)return false;if(history.length>1)history.back();else location.hash='/';return true})()",handled->{
            if(isDestroyed() || current!=request || !origin.equals(loaded))return;
            if("false".equals(handled))openManagement();
        });
    }
    private void openManagement(){startActivity(new Intent(this,ManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);String url=web.getUrl();
        String route=url==null ? null : Uri.parse(url).getFragment();
        if(route!=null && route.startsWith("/"))out.putString("route",route);
    }
    @Override public void onStart(){super.onStart();WeatherBinding.start(getApplicationContext(),EngineHost.currentLease());web.onResume();handler.post(refresh);}
    @Override public void onStop(){handler.removeCallbacks(refresh);web.onPause();super.onStop();}
    @Override public void onDestroy(){handler.removeCallbacks(refresh);web.destroy();super.onDestroy();}
}
