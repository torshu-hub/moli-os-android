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
public final class Code15DeliveryTest {
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
        ins.waitForIdleSync();Thread.sleep(300);
        File dir=new File(app.getFilesDir(),"code15-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){
            assertTrue(ins.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out));
        }
    }
    void savedAccount()throws Exception {
        assertEquals(Integer.parseInt(InstrumentationRegistry.getArguments().getString("expectedVersion","15")),app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        Code12UpgradeSeedTest.sameData(app);
        JSONObject state=EngineHost.inspect(()->new ConfigStore(app).netatmo(new JSONObject().put("operation","status"))).get(15,TimeUnit.SECONDS);
        assertTrue(state.getBoolean("credential_present"));assertTrue(state.getBoolean("write_allowed"));
        assertFalse(state.getBoolean("setup_required"));assertEquals("home-test",state.getString("selected_home"));
        assertFalse(state.toString().contains("synthetic-refresh"));Code12UpgradeSeedTest.sameData(app);
    }
    @Test public void upgradeAndEditOfficialClimateCard()throws Exception {
        savedAccount();
        // Management hosts the isolated WebView without MainActivity's intended
        // automatic engine start, which would legitimately append to SQLite WAL.
        activity=ins.startActivitySync(new Intent(app,ManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String index=asset("code15-web/index.html");
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedIndex"),Beta2JourneyTest.digest(index));
        String html=index.replace("<head>","<head><script>"+asset("code15-bootstrap.js")+"</script>");
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
        waitJs("document.querySelector('.temporary input')?.value === '20.5'");
        js("window.field=document.querySelector('.temporary input');field.scrollIntoView({block:'center'});field.focus();true");
        // The focus handler selects the existing value: a normal delete clears it.
        ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);
        waitJs("field.value === '' && document.querySelector('.temporary button').disabled");
        js("code15Report(18);true");Thread.sleep(200);
        assertEquals("true",js("field.value === '' && code15Commands.length === 0"));capture("climate-empty");
        ins.sendStringSync("21.5");
        waitJs("field.value === '21.5'");js("code15Report(19);true");Thread.sleep(200);
        assertEquals("\"21.5\"",js("field.value"));
        js("field.select();true");ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);ins.sendStringSync("31");
        waitJs("document.querySelector('.temporary button').disabled && code15Commands.length === 0");
        js("field.select();true");ins.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);ins.sendStringSync("21.5");
        js("const duration=document.querySelectorAll('.temporary input')[1];duration.value='90';duration.dispatchEvent(new Event('input',{bubbles:true}));true");
        waitJs("!document.querySelector('.temporary button').disabled");
        js("document.querySelector('.temporary button').click();true");
        waitJs("code15Commands.length === 1");
        assertEquals("true",js("code15Commands[0].point === 'climate-test:room/setpoint' && JSON.parse(code15Commands[0].value).temperature === 21.5 && JSON.parse(code15Commands[0].value).duration_minutes === 90"));
        js("code15Report(7);field.blur();true");Thread.sleep(200);
        assertEquals("\"21.5\"",js("field.value"));capture("climate-replaced");
        savedAccount();
        File dir=new File(app.getFilesDir(),"code15-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,"update-proof.txt"))){out.write(("update; synthetic OAuth pair/application/home, files/config/master and four disabled Meross instances identical; production dashboard bytes in Android WebView; focus/delete, empty draft surviving a state event, keyboard replacement, bounds, explicit duration/payload, one captured offline command; no provider traffic or physical commands.\n").getBytes(StandardCharsets.UTF_8));}
        main(()->{web.destroy();activity.finish();});
    }
    @Test public void verifySavedAccountAfterRestart()throws Exception {savedAccount();}
}
