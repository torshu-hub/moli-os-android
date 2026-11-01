package io.moli.android;

import android.app.Instrumentation;
import android.content.*;
import android.widget.*;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Disposable upgrade fixture only; all configured integrations remain disabled. */
@RunWith(AndroidJUnit4.class)
public final class Code19AutomationTest {
    @Test public void nativeClientCreatesOneDraftInOfficialApiWithoutActivation()throws Exception {
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context app=ins.getTargetContext();
        assertEquals("io.moli.android.app.beta2.test",app.getPackageName());
        JSONObject catalogue=ConfigStore.nativeCall(new JSONObject().put("action","catalogue").put("text",new ConfigStore(app).read()));
        JSONArray packages=catalogue.getJSONArray("packages");
        for(int i=0;i<packages.length();i++){JSONArray instances=packages.getJSONObject(i).getJSONArray("instances");for(int j=0;j<instances.length();j++)assertFalse(instances.getJSONObject(j).optBoolean("enabled",true));}
        app.startForegroundService(new Intent(app,MoliService.class));Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        AutomationActivity activity=(AutomationActivity)ins.startActivitySync(new Intent(app,AutomationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String id=null;
        try{
            Thread.sleep(1500);
            JSONObject draft=new JSONObject().put("name","Synthetic demand link").put("mode","queued").put("enabled",false).put("graph",DemandAutomation.graph("synthetic-energy:room/declared_demand","synthetic-plug:outlet/on"));
            JSONObject result=activity.request("POST","/api/automations",draft,null);JSONObject a=result.getJSONObject("automation");id=a.getString("id");
            assertFalse(result.getBoolean("live"));assertFalse(a.getBoolean("enabled"));
            JSONObject read=activity.request("GET","/api/automations/"+id,null,null);assertEquals(result.getString("fingerprint"),read.getString("fingerprint"));assertNotNull(DemandAutomation.wiring(read.getJSONObject("automation").getJSONObject("graph")));
            assertTrue(read.getJSONArray("runs").length()==0);
            a.put("name","Synthetic renamed link");
            JSONObject updated=activity.request("PUT","/api/automations/"+id,a,result.getString("fingerprint"));assertFalse(updated.getBoolean("live"));
            try{activity.request("PUT","/api/automations/"+id,a,result.getString("fingerprint"));fail("stale edit accepted");}catch(java.io.IOException expected){}
            try{activity.request("POST","/api/automations/"+id+"/approve",new JSONObject().put("fingerprint",updated.getString("fingerprint")),null);fail("approval without human session");}catch(java.io.IOException expected){}
            assertFalse(activity.request("GET","/api/automations/"+id,null,null).getBoolean("live"));
        }finally{
            if(id!=null)activity.request("DELETE","/api/automations/"+id,null,null);
            ins.runOnMainSync(activity::finish);
            app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
        }
    }
}
