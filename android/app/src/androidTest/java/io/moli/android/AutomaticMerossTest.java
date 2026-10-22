package io.moli.android;
import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Disposable, synthetic account/discovery with real Rust fixture measurements; screenshots contain fixture data only. */
@RunWith(AndroidJUnit4.class)
public final class AutomaticMerossTest {
    private interface Inspect{boolean read(MerossAccountActivity a);}
    private static void waitUi(ActivityScenario<MerossAccountActivity> ui,Inspect check)throws Exception{
        MerossAccountTest.until(()->{AtomicReference<Boolean> ok=new AtomicReference<>(false);ui.onActivity(a->ok.set(check.read(a)));return ok.get();});
    }
    private static void capture(Context app,String name)throws Exception{
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(300);
        Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);
        File dir=new File(app.getFilesDir(),"trial-screenshots");assertTrue(dir.isDirectory()||dir.mkdir());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
    }
    private static int visibleOldControls(View v,Context a){int n=0;if(v.isShown()&&v instanceof TextView){String t=((TextView)v).getText().toString();for(int key:new int[]{R.string.home_tools,R.string.journal_title,R.string.equipment_title})if(t.equals(a.getString(key)))n++;}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)n+=visibleOldControls(((ViewGroup)v).getChildAt(i),a);return n;}
    @Test public void freshPreviewAutomaticSixteenCandidatesMultipleConsentReadAndReport()throws Exception{
        assertTrue(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();assertEquals(EngineHost.State.STOPPED,EngineHost.state());assertFalse("First class in a fresh emulator",ProtectedKey.exists(app));
        app.getSharedPreferences("host",Context.MODE_PRIVATE).edit().putBoolean("wanted",false).commit();
        try(ActivityScenario<MainActivity> home=ActivityScenario.launch(MainActivity.class)){
            home.onActivity(a->{assertFalse(ProtectedKey.exists(a));assertEquals(0,visibleOldControls(a.getWindow().getDecorView(),a));assertNotNull(MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(R.string.preview_search)));});
            capture(app,"01-accueil");
            // The first voluntary search creates a random Keystore bootstrap, without asking for a PIN.
            home.onActivity(a->MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(R.string.preview_search)).performClick());
            MerossAccountTest.until(()->ProtectedKey.exists(app));JSONObject keys=ProtectedKey.open(app);assertEquals(2,keys.getInt("format"));assertEquals(64,keys.getString("pin").length());assertNotEquals(keys.getString("master"),keys.getString("pin"));assertTrue(TrialLog.active());
        }
        EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject());return null;}).get();String own=MerossReadTest.address();
        try(MerossReadTest.Fixture one=new MerossReadTest.Fixture(1);MerossReadTest.Fixture three=new MerossReadTest.Fixture(3);MerossReadTest.Fixture four=new MerossReadTest.Fixture(4)){
            MerossAccountTest.Fixture account=new MerossAccountTest.Fixture(app);account.connected=true;int[] operations={0,0,0};
            MerossAccountStore.Backend backend=r->{switch(r.getString("action")){
                case "status":return account.status().put("inventory_wait_ms",0);
                case "inventory":operations[0]++;return account.status();
                case "identify_start":return new JSONObject().put("state","scan_ready").put("scan","synthetic-scan-lease");
                case "identify_cancel":return new JSONObject().put("state","cancelled");
                case "identify":{
                    operations[1]++;JSONArray ips=r.getJSONArray("candidates");assertTrue(ips.length()<=4);JSONArray rows=new JSONArray();
                    for(int i=0;i<ips.length();i++){String ip=ips.getString(i);int n=Integer.parseInt(ip.substring(ip.lastIndexOf('.')+1))-20;int id=switch(n){case 0->4;case 1->1;case 2,4->2;case 3->3;default->0;};
                        JSONObject row=new JSONObject().put("host",ip).put("result",id>0?"matched":switch(n%4){case 0->"different";case 1->"unavailable";case 2->"key_rejected";default->"incompatible";});if(id>0)row.put("uuid",String.format(Locale.ROOT,"%032x",id));rows.put(row);
                    }return new JSONObject().put("state","identified").put("results",rows);
                }
                case "associate":{
                    operations[2]++;assertTrue(r.getBoolean("enabled"));int id=Integer.parseInt(r.getString("uuid"),16);assertNotEquals("Ambiguous plug must never be saved",2,id);
                    int port=id==1?one.port():id==3?three.port():four.port();String uuid=r.getString("uuid");JSONObject config=EquipmentStore.read(app);JSONArray rows=config.optJSONArray("meross");if(rows==null)rows=new JSONArray();rows.put(new JSONObject().put("id",uuid).put("uuid",uuid).put("host",own).put("port",port).put("name","Synthetic plug "+id).put("enabled",true));config.put("meross",rows);
                    EngineHost.maintain(()->{EquipmentStore.save(app,config,new JSONArray().put(new JSONObject().put("id",uuid).put("key","a".repeat(32)).put("required",true)));return null;}).get();return new JSONObject().put("state","associated").put("uuid",uuid);
                }
                default:throw new AssertionError("No other cloud/command operations allowed");
            }};
            try(ActivityScenario<MerossAccountActivity> ui=ActivityScenario.launch(MerossAccountActivity.class)){
                waitUi(ui,a->a.loaded&&!a.busy);ui.onActivity(a->{a.backend=backend;a.reload();});waitUi(ui,a->a.inventory.length()==4&&!a.busy);
                ui.onActivity(a->{a.discovery=()->new DiscoverySearch(){public void start(Listener l){for(int i=20;i<36;i++)l.found(new NetworkEvidence.Entry("192.168.1."+i,80,"",NetworkEvidence.Kind.HTTP,NetworkEvidence.Proof.OPEN_PORT,"",""));l.finished(End.COMPLETE);}public void close(){}};a.search.performClick();});
                waitUi(ui,a->a.consentDialog!=null&&a.consentDialog.isShowing());ui.onActivity(a->a.consentDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                waitUi(ui,a->!a.active&&!a.busy&&a.matches.results.length()==16);assertEquals(4,operations[1]);assertEquals(0,operations[0]);
                ui.onActivity(a->{assertEquals(3,a.matches.found());assertTrue(a.matches.ambiguous(String.format(Locale.ROOT,"%032x",2)));assertEquals(3,a.choices.size());for(CheckBox c:a.choices.values()){assertFalse(c.isChecked());c.setChecked(true);}a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);});
                capture(app,"02-prises-reconnues-fixtures");
                ui.onActivity(a->a.add.performClick());waitUi(ui,a->a.addDialog!=null&&a.addDialog.isShowing());ui.onActivity(a->a.addDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());assertEquals(0,operations[2]);
                ui.onActivity(a->a.add.performClick());ui.onActivity(a->a.addDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
                waitUi(ui,a->!a.active&&!a.busy&&a.status.getText().toString().equals(a.getString(R.string.auto_saved,3)));assertEquals(3,operations[2]);
            }
            MerossAccountTest.until(()->EngineHost.state()==EngineHost.State.RUNNING);
            MerossAccountTest.until(()->{one.check();three.check();four.check();return MerossReadTest.device(1)!=null&&MerossReadTest.device(3)!=null&&MerossReadTest.device(4)!=null&&MerossReadTest.device(4).getJSONObject("state").has("power");});
            assertEquals(3,EquipmentStore.read(app).getJSONArray("meross").length());assertEquals(123.456,MerossReadTest.device(1).getJSONObject("state").getJSONObject("power").getDouble("value"),0.001);
            EquipmentMonitor.publish(app,EquipmentStore.read(app),MerossReadTest.get("/api/devices"),null);
            try(ActivityScenario<MainActivity> home=ActivityScenario.launch(MainActivity.class)){
                Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Uri output=Uri.parse("content://io.moli.synthetic.documents/automatic-trial-export");AtomicReference<String> file=new AtomicReference<>();
                Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())){assertEquals("text/plain",intent.getType());file.set(intent.getStringExtra(Intent.EXTRA_TITLE));return new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(output));}return null;}};
                instrumentation.addMonitor(monitor);
                try{home.onActivity(a->MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(R.string.report_export)).performClick());
                    MerossAccountTest.until(()->{try{return ProfileStore.bounded(app.getContentResolver().openInputStream(output),131072).contains("EQUIPMENT_READ");}catch(Exception ignored){return false;}});
                    assertNotNull(file.get());assertTrue(file.get().startsWith("Rapport-essai-Moli-OS_"));assertFalse(file.get().contains("preview"));
                    String report=ProfileStore.bounded(app.getContentResolver().openInputStream(output),131072);assertTrue(report.contains("CANDIDATE_RESULT"));assertTrue(report.contains("FRAIS"));assertTrue(report.contains("UUID_CHECK"));
                    for(String secret:new String[]{own,"192.168.","a".repeat(32),ProtectedKey.open(app).getString("master"),ProtectedKey.open(app).getString("pin"),MerossAccountTest.UUID,"Synthetic plug"})assertFalse(secret,report.contains(secret));
                }finally{instrumentation.removeMonitor(monitor);}
            }
        }finally{app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));MerossAccountTest.until(()->EngineHost.state()==EngineHost.State.STOPPED);EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject());return null;}).get();TrialLog.end(app);}
    }
}
