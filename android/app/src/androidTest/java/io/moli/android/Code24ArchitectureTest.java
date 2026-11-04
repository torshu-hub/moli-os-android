package io.moli.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Existing options survive collapsed groups; dispatcher is synthetic, with no provider I/O. */
@RunWith(AndroidJUnit4.class)
public final class Code24ArchitectureTest {
    @Test public void collapsed_options_and_missing_composition_sources_are_preserved() throws Exception {
        android.app.Instrumentation ins=InstrumentationRegistry.getInstrumentation();
        Context app=ins.getTargetContext();
        Activity activity=ins.startActivitySync(new Intent(app,ManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject saved=new JSONObject().put("vehicles",new JSONArray().put("v_synthetic")).put("poll_seconds",1800).put("max_age_seconds",7200).put("commands_enabled",false).put("location_enabled",false).put("weather_point","weather:synthetic/temperature").put("dedicated_ev_meter","meter:synthetic/energy");
        AtomicReference<JSONObject> submitted=new AtomicReference<>();
        AtomicReference<AlertDialog> dialog=new AtomicReference<>();
        AtomicReference<HyundaiPanel> panel=new AtomicReference<>();
        HyundaiPanel.State state=new HyundaiPanel.State();
        state.snapshot=new JSONObject().put("options",saved).put("vehicles",new JSONArray().put(new JSONObject().put("key","v_synthetic").put("name","Synthetic vehicle")));
        ins.runOnMainSync(()->{
            panel.set(new HyundaiPanel(activity,state,()->{},()->{},(request,mutate)->{assertTrue(mutate);submitted.set(new JSONObject(request.toString()));return CompletableFuture.completedFuture(state.snapshot);}));
            dialog.set(panel.get().options());
        });
        ins.waitForIdleSync();
        ins.runOnMainSync(()->{
            View root=dialog.get().getWindow().getDecorView();
            assertEquals(View.GONE,root.findViewWithTag("hyundai:advanced-content").getVisibility());
            assertEquals(View.GONE,root.findViewWithTag("hyundai:journal-content").getVisibility());
            dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        JSONObject options=submitted.get().getJSONObject("options");
        for(String key:new String[]{"vehicles","poll_seconds","max_age_seconds","commands_enabled","location_enabled","weather_point","dedicated_ev_meter"})assertEquals(key,saved.get(key).toString(),options.get(key).toString());
        assertEquals("configure",submitted.get().getString("operation"));assertTrue(submitted.get().getBoolean("consent"));
        ins.runOnMainSync(()->{
            panel.get().complete();dialog.set(panel.get().options());
            View root=dialog.get().getWindow().getDecorView();
            root.findViewWithTag("hyundai:advanced").performClick();root.findViewWithTag("hyundai:journal-settings").performClick();
            assertEquals(View.VISIBLE,root.findViewWithTag("hyundai:advanced-content").getVisibility());
            assertEquals(View.VISIBLE,root.findViewWithTag("hyundai:journal-content").getVisibility());
            assertEquals("1800",((EditText)root.findViewWithTag("hyundai:poll")).getText().toString());
            assertTrue(((Spinner)root.findViewWithTag("hyundai:weather")).getSelectedItem().toString().contains("weather:synthetic/temperature"));
            assertTrue(((Spinner)root.findViewWithTag("hyundai:meter")).getSelectedItem().toString().contains("meter:synthetic/energy"));
        });
        ins.waitForIdleSync();Thread.sleep(500);
        File directory=new File(app.getFilesDir(),"code24-captures");directory.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(directory,"options-landscape.png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out));}
        ins.runOnMainSync(()->{dialog.get().dismiss();activity.finish();});
    }
}
