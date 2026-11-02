package io.moli.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.webkit.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual production dashboard in Android WebView; all transport stays offline. */
@RunWith(AndroidJUnit4.class)
public final class Code21HyundaiTest {
    final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    final Context app=ins.getTargetContext();
    WebView web;Activity activity;
    @org.junit.Rule public final org.junit.rules.TestWatcher failureCapture=new org.junit.rules.TestWatcher(){
        @Override protected void failed(Throwable error,org.junit.runner.Description description){try{capture("failure");}catch(Exception ignored){}}
    };
    void main(Runnable action){ins.runOnMainSync(action);}
    String asset(String name)throws Exception {
        try(InputStream input=ins.getContext().getAssets().open(name)){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
    String js(String expression)throws Exception {
        AtomicReference<String> value=new AtomicReference<>();CountDownLatch ready=new CountDownLatch(1);
        main(()->web.evaluateJavascript(expression,v->{value.set(v);ready.countDown();}));
        assertTrue("WebView callback",ready.await(5,TimeUnit.SECONDS));return value.get();
    }
    void waitJs(String expression)throws Exception {
        long end=SystemClock.uptimeMillis()+20000;
        while(SystemClock.uptimeMillis()<end){if("true".equals(js(expression)))return;Thread.sleep(100);}
        fail("WebView condition: "+expression+"; page: "+js("document.body.innerText.slice(0,600)"));
    }
    void capture(String name)throws Exception {
        if(activity==null)return;
        if(web!=null){
            CountDownLatch drawn=new CountDownLatch(1);
            main(()->web.postVisualStateCallback(SystemClock.uptimeMillis(),new WebView.VisualStateCallback(){
                @Override public void onComplete(long requestId){web.invalidate();drawn.countDown();}
            }));
            assertTrue("WebView rendered the current DOM before screenshot",drawn.await(10,TimeUnit.SECONDS));
        }
        ins.waitForIdleSync();Thread.sleep(500);
        File dir=new File(app.getFilesDir(),"code21-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){
            assertTrue(ins.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out));
        }
    }
    @Test public void nativeCatalogueAndProductionVehicleCard()throws Exception {
        JSONObject catalogue=EngineHost.inspect(()->{ConfigStore store=new ConfigStore(app);return ConfigStore.nativeCall(new JSONObject().put("action","catalogue").put("text",store.read()));}).get(15,TimeUnit.SECONDS);
        JSONObject hyundai=null;org.json.JSONArray packages=catalogue.getJSONArray("packages");for(int i=0;i<packages.length();i++){JSONObject p=packages.getJSONObject(i);if("hyundai".equals(p.getJSONObject("manifest").getJSONObject("integration").getString("id")))hyundai=p;}
        assertNotNull(hyundai);assertTrue(hyundai.getBoolean("configurable"));assertEquals(0,hyundai.getJSONArray("instances").length());
        JSONObject status=EngineHost.inspect(()->new ConfigStore(app).hyundai(new JSONObject().put("operation","status"))).get(15,TimeUnit.SECONDS);
        assertFalse(status.getBoolean("connected"));assertFalse(status.getJSONObject("options").getBoolean("commands_enabled"));assertFalse(status.getJSONObject("options").getBoolean("location_enabled"));
        activity=ins.startActivitySync(new Intent(app,ManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(()->activity.findViewById(R.id.navigation_integrations).performClick());
        long until=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<until){final AtomicReference<android.view.View> row=new AtomicReference<>();main(()->row.set(activity.findViewById(android.R.id.content).findViewWithTag("hyundai")));if(row.get()!=null){main(()->row.get().performClick());break;}Thread.sleep(100);}
        until=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<until){final AtomicReference<android.view.View> login=new AtomicReference<>();main(()->login.set(activity.findViewById(android.R.id.content).findViewWithTag("hyundai:login")));if(login.get()!=null)break;Thread.sleep(100);}
        main(()->assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("hyundai:login")));capture("hyundai-native-landscape");
        String index=asset("code15-web/index.html");
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedIndex"),Beta2JourneyTest.digest(index));
        String html=index.replace("<head>","<head><script>"+asset("code21-bootstrap.js")+"</script>");
        main(()->{
            web=new WebView(activity);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
            web.setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    String path=request.getUrl().getPath();
                    try{
                        if(!"moli.test".equals(request.getUrl().getHost())||path==null||path.contains(".."))throw new IOException();
                        if(path.equals("/"))return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                        if(!path.startsWith("/assets/"))throw new IOException();
                        String mime=path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".woff2")?"font/woff2":"application/octet-stream";
                        return new WebResourceResponse(mime,"UTF-8",ins.getContext().getAssets().open("code15-web"+path));
                    }catch(IOException refused){return new WebResourceResponse("text/plain","UTF-8",404,"Offline",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
                }
            });
            activity.setContentView(web);web.requestFocus();web.loadUrl("https://moli.test/");
        });

        waitJs("document.querySelector('[data-testid=vehicle-card]') != null");
        assertEquals("true",js("code21Commands.length===0 && code21Reads.every(p=>!p.includes('/private/'))"));
        js("document.querySelector('.vehicle > button').click();true");waitJs("document.querySelector('.vehicle nav') != null");js("document.querySelector('.vehicle nav').scrollIntoView({block:'start'});true");capture("vehicle-readings-landscape");
        js("document.querySelectorAll('.vehicle nav button')[1].click();true");waitJs("document.querySelector('.vehicle .controls button') != null");
        assertEquals("true",js("code21Commands.length===0"));
        js("document.querySelector('.vehicle .controls button').click();true");waitJs("document.querySelector('.vehicle form') != null");
        assertEquals("true",js("document.querySelector('.vehicle form button[type=submit]').disabled && code21Commands.length===0"));
        js("const c=document.querySelector('.vehicle .confirmation input');c.click();true");waitJs("!document.querySelector('.vehicle form button[type=submit]').disabled");
        js("document.querySelector('.vehicle form button[type=submit]').click();true");waitJs("code21Commands.length===1");
        assertEquals("true",js("code21Commands[0].point==='hyundai-test:v_synthetic/lock' && code21Commands[0].value==='{}'"));js("document.querySelector('.vehicle nav').scrollIntoView({block:'start'});true");Thread.sleep(3500);capture("vehicle-commands-landscape");
        js("document.querySelectorAll('.vehicle nav button')[2].click();true");waitJs("document.querySelector('.vehicle .dates button') != null");js("document.querySelector('.vehicle .dates button').click();true");waitJs("document.querySelectorAll('.vehicle tbody tr').length===2");
        assertEquals("true",js("document.querySelector('.vehicle tbody').innerText.includes('16') && document.querySelector('.vehicle tbody').innerText.includes('Trou de collecte')"));js("document.querySelector('.vehicle .dates').scrollIntoView({block:'start'});true");capture("vehicle-journal-landscape");
        assertEquals("true",js("code21Commands.length===1 && code21Reads.every(p=>!p.includes('/private/'))"));
        main(()->{web.destroy();activity.finish();});
    }
}
