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
    private byte[] vehicleExport;
    private static final int VEHICLE_EXPORT=2101;
    private final class VehicleExport {
        @JavascriptInterface public void privacy(boolean active){runOnUiThread(()->{if(loaded==null||!allowed(Uri.parse(loaded)))return;if(active)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);});}
        @JavascriptInterface public void save(String json){
            if(json==null||json.length()>8*1024*1024)return;
            runOnUiThread(()->{if(loaded==null||!allowed(Uri.parse(loaded))||web.getUrl()==null||!allowed(Uri.parse(web.getUrl()))||vehicleExport!=null)return;try{JSONObject v=new JSONObject(json);if(v.optInt("schema")!=1||!"Europe/Paris".equals(v.optString("timezone"))||v.optJSONArray("days")==null)return;vehicleExport=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"moli-vehicle-journal.json");startActivityForResult(intent,VEHICLE_EXPORT);}catch(Exception e){vehicleExport=null;}});
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=VEHICLE_EXPORT)return;byte[] bytes=vehicleExport;vehicleExport=null;if(resultCode!=RESULT_OK||data==null||bytes==null)return;Uri uri=data.getData();if(uri==null||!"content".equals(uri.getScheme()))return;new Thread(()->{try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)return;out.write(bytes);runOnUiThread(()->Toast.makeText(this,R.string.exported,Toast.LENGTH_SHORT).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,R.string.operation_failed,Toast.LENGTH_SHORT).show());}},"vehicle-journal-export").start();}

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
        web.addJavascriptInterface(new VehicleExport(),"MoliVehicleExport");
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage message){return true;}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !allowed(r.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return resourceAllowed(r)?null:new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())failed();}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){if(r.isForMainFrame())failed();}
            @Override public void onPageFinished(WebView v,String url){
                // A new engine lease has a new origin. Its initial page must not
                // navigate back into a closed engine or an about:blank entry.
                if(resetHistory && allowed(Uri.parse(url))){resetHistory=false;v.clearHistory();}
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        String automation=getIntent().getStringExtra("automation");
        if(automation!=null && automation.matches("[A-Za-z0-9_-]{1,128}"))restoredRoute="/automatismes/"+automation;
        if(saved!=null) restoredRoute=saved.getString("route","/");
        showDashboard();
    }
    private boolean allowed(Uri uri){String origin=loaded;return origin!=null && EngineHost.state()==EngineHost.State.RUNNING && origin.equals(EngineHost.origin()) && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort()==Uri.parse(origin).getPort();}
    boolean resourceAllowed(WebResourceRequest request) {
        if(allowed(request.getUrl()))return true;
        String origin=loaded;
        return origin!=null && allowed(Uri.parse(origin)) && osmTile(request);
    }
    static boolean osmTile(WebResourceRequest request) {
        Uri uri=request.getUrl();
        return !request.isForMainFrame() && "GET".equals(request.getMethod())
            && "https".equals(uri.getScheme()) && "tile.openstreetmap.org".equals(uri.getHost())
            && ("tile.openstreetmap.org".equals(uri.getEncodedAuthority()) || "tile.openstreetmap.org:443".equals(uri.getEncodedAuthority()))
            && uri.getEncodedQuery()==null && uri.getEncodedFragment()==null
            && uri.getEncodedPath()!=null && uri.getEncodedPath().matches("/[0-9]+/[0-9]+/[0-9]+\\.png");
    }
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
        web.evaluateJavascript("location.origin==="+JSONObject.quote(origin)+"?location.hash:null",result->{
            if(isDestroyed() || current!=request || !origin.equals(loaded) || !origin.equals(EngineHost.origin()) || EngineHost.state()!=EngineHost.State.RUNNING)return;
            try {
                Object value=new org.json.JSONTokener(result).nextValue();
                if(!(value instanceof String))return;
                String parent=parentRoute((String)value);
                if(parent==null){openManagement();return;}
                web.evaluateJavascript("if(location.origin==="+JSONObject.quote(origin)+")location.hash="+JSONObject.quote(parent),null);
            }catch(org.json.JSONException invalid){web.evaluateJavascript("if(location.origin==="+JSONObject.quote(origin)+")location.hash='/'",null);}
        });
    }
    /** Null closes Moli only at its home; unknown/invalid routes return home. */
    static String parentRoute(String hash) {
        if(hash==null)return "/";
        String route=hash.startsWith("#")?hash.substring(1):hash;
        if(route.isEmpty()||route.equals("/"))return null;
        if(route.startsWith("/"))route=route.substring(1);
        String[] parts=route.split("/",-1);
        java.util.Set<String> roots=new java.util.HashSet<>(java.util.Arrays.asList("moli","salon","pieces","plan","carte","cameras","parler","energie","impression","dehors","automatismes","systeme","infra","parc"));
        if(!roots.contains(parts[0]))return "/";
        for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals("..")||!part.matches("[A-Za-z0-9_%:.-]+"))return "/";
        return parts.length==1?"/":"/"+String.join("/",java.util.Arrays.copyOf(parts,parts.length-1));
    }

    private void openManagement(){startActivity(new Intent(this,ManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);String url=web.getUrl();
        String route=url==null ? null : Uri.parse(url).getFragment();
        if(route!=null && route.startsWith("/"))out.putString("route",route);
    }
    @Override public void onStart(){super.onStart();WeatherBinding.start(getApplicationContext(),EngineHost.currentLease());web.onResume();handler.post(refresh);}
    @Override public void onStop(){handler.removeCallbacks(refresh);web.onPause();super.onStop();}
    @Override public void onDestroy(){handler.removeCallbacks(refresh);vehicleExport=null;web.removeJavascriptInterface("MoliVehicleExport");web.destroy();super.onDestroy();}
}
