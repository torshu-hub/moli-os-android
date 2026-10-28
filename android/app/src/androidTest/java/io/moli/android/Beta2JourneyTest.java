package io.moli.android;

import android.app.*;
import android.content.*;
import android.webkit.WebView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class Beta2JourneyTest {
    @Test public void nativeLifecycleConfigurationAuthAndOfficialDashboard() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context app=instrumentation.getTargetContext();
        assertTrue(app.getPackageName().equals("io.moli.android.app.beta2.test"));
        if(!ProtectedKey.exists(app))ProtectedKey.create(app,"482913"); // synthetic test identity only
        app.startForegroundService(new Intent(app,MoliService.class));
        waitState(EngineHost.State.RUNNING);
        String[] forbidden={"","moli_android=wrong"};for(String cookie:forbidden)assertEquals(401,get("/api/health",cookie).code);
        assertEquals(200,get("/api/health",cookie()).code);
        String origin=EngineHost.origin();
        String old=EngineHost.inspect(()->new ConfigStore(app).read()).get(10,TimeUnit.SECONDS);
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);
            store.save(old,"weather-fixture",new JSONObject().put("lat","0").put("lon","0"));return null;
        }).get(30,TimeUnit.SECONDS);
        waitState(EngineHost.State.RUNNING);
        String configured=EngineHost.inspect(()->new ConfigStore(app).read()).get(10,TimeUnit.SECONDS);
        assertTrue(configured.contains("open-meteo"));assertNotEquals(origin,EngineHost.origin());
        try{
            EngineHost.maintain(app,()->{new ConfigStore(app).save(configured,"bad:id",new JSONObject().put("lat","0").put("lon","0"));return null;}).get(30,TimeUnit.SECONDS);fail("invalid native driver id accepted");
        }catch(ExecutionException expected){}
        waitState(EngineHost.State.RUNNING);
        assertEquals(configured,EngineHost.inspect(()->new ConfigStore(app).read()).get());
        try{
            EngineHost.maintain(app,()->{new ConfigStore(app).save(old,"weather-fixture",new JSONObject().put("lat","1").put("lon","1"));return null;}).get(30,TimeUnit.SECONDS);fail("stale form accepted");
        }catch(ExecutionException expected){}
        assertEquals(configured,EngineHost.inspect(()->new ConfigStore(app).read()).get());
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(activity::showDashboard);
        java.util.concurrent.ConcurrentLinkedQueue<String> errors=new java.util.concurrent.ConcurrentLinkedQueue<>();
        instrumentation.runOnMainSync(()->activity.web.setWebChromeClient(new android.webkit.WebChromeClient(){
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message){if(message.messageLevel()==android.webkit.ConsoleMessage.MessageLevel.ERROR && errors.size()<8)errors.add(message.message().substring(0,Math.min(300,message.message().length())));return true;}
        }));
        long deadline=System.currentTimeMillis()+30000;boolean displayed=false;
        while(System.currentTimeMillis()<deadline){
            CountDownLatch latch=new CountDownLatch(1);String[] value={null};
            instrumentation.runOnMainSync(()->activity.web.evaluateJavascript("JSON.stringify({nav:!!document.querySelector('.maison nav.rail'),text:document.body.innerText.length})",v->{value[0]=v;latch.countDown();}));
            if(latch.await(3,TimeUnit.SECONDS)&&value[0]!=null&&!value[0].equals("null")){
                JSONObject dom=new JSONObject(new JSONTokener(value[0]).nextValue().toString());
                if(dom.optBoolean("nav") && dom.optInt("text")>20){displayed=true;break;}
            }
            Thread.sleep(250);
        }
        assertTrue("Official Svelte dashboard did not render: "+errors,displayed);
        CountDownLatch visual=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->activity.web.postVisualStateCallback(1,new WebView.VisualStateCallback(){@Override public void onComplete(long request){visual.countDown();}}));
        assertTrue("WebView did not commit its visual state",visual.await(5,TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();Thread.sleep(500);
        String[] geometry={""};CountDownLatch details=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->activity.web.evaluateJavascript("JSON.stringify({viewport:[innerWidth,innerHeight],visibility:document.visibilityState,body:document.body.innerText.slice(0,200),maison:document.querySelector('.maison')?.getBoundingClientRect().toJSON(),bg:getComputedStyle(document.querySelector('.maison')).backgroundColor})",value->{geometry[0]=value;details.countDown();}));
        assertTrue(details.await(3,TimeUnit.SECONDS));
        android.graphics.Rect rectangle=new android.graphics.Rect();String[] nativeView={""};
        instrumentation.runOnMainSync(()->{activity.web.getGlobalVisibleRect(rectangle);nativeView[0]="width="+activity.web.getWidth()+" height="+activity.web.getHeight()+" shown="+activity.web.isShown()+" alpha="+activity.web.getAlpha()+" hardware="+activity.web.isHardwareAccelerated()+" rect="+rectangle;});
        android.graphics.Bitmap screenshot=instrumentation.getUiAutomation().takeScreenshot();
        File captures=new File(app.getFilesDir(),"screenshots");captures.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(captures,"official-dashboard.png"))){assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
        int painted=0;
        for(int y=Math.max(0,rectangle.top+2);y<Math.min(screenshot.getHeight(),rectangle.bottom-2);y+=4)
            for(int x=Math.max(0,rectangle.left+2);x<Math.min(screenshot.getWidth(),rectangle.right-2);x+=4){int color=screenshot.getPixel(x,y);if(android.graphics.Color.red(color)<200||android.graphics.Color.green(color)<200||android.graphics.Color.blue(color)<200)painted++;}
        String diagnostic=nativeView[0]+" painted="+painted+"\n"+geometry[0]+"\n"+errors;
        try(FileOutputStream out=new FileOutputStream(new File(captures,"render.txt"))){out.write(diagnostic.getBytes(StandardCharsets.UTF_8));}
        assertTrue("Official WebView is not visibly painted: "+diagnostic,rectangle.width()>100 && rectangle.height()>100 && painted>20);
        instrumentation.runOnMainSync(activity::finish);
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));waitState(EngineHost.State.STOPPED);
        app.startForegroundService(new Intent(app,MoliService.class));waitState(EngineHost.State.RUNNING);
        assertEquals(configured,EngineHost.inspect(()->new ConfigStore(app).read()).get());
        String report=GeneralJournal.export(app);assertTrue(report.contains("ENGINE_STARTED"));assertTrue(report.contains("CONFIGURATION")||report.contains("ENGINE_STOPPED"));assertFalse(report.contains("482913"));
        assertFalse(new File(app.getFilesDir(),"moli/android-integrations.json").exists());
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));waitState(EngineHost.State.STOPPED);
        app.getSharedPreferences("beta2-test-proof",Context.MODE_PRIVATE).edit()
            .putString("config",digest(configured)).putString("key",digest(ProtectedKey.open(app).getString("master"))).commit();
    }
    @Test public void updateKeepsTheNativeConfigurationAndWrappedKey()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(2,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        assertTrue(ProtectedKey.exists(app));
        android.content.SharedPreferences proof=app.getSharedPreferences("beta2-test-proof",Context.MODE_PRIVATE);
        assertEquals(proof.getString("key","missing"),digest(ProtectedKey.open(app).getString("master")));
        assertEquals(proof.getString("config","missing"),digest(new ConfigStore(app).read()));
        app.startForegroundService(new Intent(app,MoliService.class));waitState(EngineHost.State.RUNNING);
        assertEquals(proof.getString("config","missing"),digest(EngineHost.inspect(()->new ConfigStore(app).read()).get()));
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));waitState(EngineHost.State.STOPPED);
    }
    static String digest(String value)throws Exception {
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static String cookie(){return "moli_android="+EngineHost.token();}
    static final class Reply{int code;String text;Reply(int c,String t){code=c;text=t;}}
    static Reply get(String path,String cookie)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(EngineHost.origin()+path).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(3000);if(!cookie.isEmpty())connection.setRequestProperty("Cookie",cookie);
        try{int code=connection.getResponseCode();InputStream in=code<400?connection.getInputStream():connection.getErrorStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(in!=null){try(in){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);}}return new Reply(code,bytes.toString("UTF-8"));}finally{connection.disconnect();}
    }
    static void waitState(EngineHost.State state)throws Exception{long end=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<end){if(EngineHost.state()==state)return;if(EngineHost.state()==EngineHost.State.ERROR)fail("Native engine error");Thread.sleep(100);}fail("State timeout: "+state);}
}
