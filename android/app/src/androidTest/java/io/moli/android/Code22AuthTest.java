package io.moli.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real account UI with a synthetic dispatcher: absolutely no identity/provider traffic. */
@RunWith(AndroidJUnit4.class)
public final class Code22AuthTest {
    final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    @Test public void explicit_login_mode_no_readonly_checkbox_and_safe_phase_diagnostic()throws Exception {
        Context app=ins.getTargetContext();ManagementActivity activity=(ManagementActivity)ins.startActivitySync(new Intent(app,ManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ins.runOnMainSync(()->activity.findViewById(R.id.navigation_integrations).performClick());
        long end=android.os.SystemClock.uptimeMillis()+20000;
        while(android.os.SystemClock.uptimeMillis()<end){AtomicReference<View> item=new AtomicReference<>();ins.runOnMainSync(()->item.set(activity.findViewById(android.R.id.content).findViewWithTag("hyundai")));if(item.get()!=null){ins.runOnMainSync(()->item.get().performClick());break;}Thread.sleep(100);}
        end=android.os.SystemClock.uptimeMillis()+20000;while(android.os.SystemClock.uptimeMillis()<end){if(activity.hyundaiPanel.state.snapshot!=null&&activity.hyundaiPanel.state.work==null)break;Thread.sleep(100);}
        List<JSONObject> submitted=Collections.synchronizedList(new ArrayList<>());
        AtomicReference<AlertDialog> dialog=new AtomicReference<>();
        ins.runOnMainSync(()->{
            HyundaiPanel original=activity.hyundaiPanel;
            activity.hyundaiPanel=new HyundaiPanel(activity,original.state,original.render,original.reload,(request,mutate)->{
                JSONObject copy=new JSONObject(request.toString());if(!"status".equals(copy.optString("operation")))submitted.add(copy);
                return CompletableFuture.completedFuture(new JSONObject().put("connected",false).put("vehicles",new JSONArray()).put("options",new JSONObject()).put("diagnostic",new JSONObject().put("phase","signin_redirect").put("cause","provider_terms_required").put("http",302)));
            });
            activity.hyundaiPanel.state.snapshot=new JSONObject();activity.hyundaiPanel.render.run();dialog.set(activity.hyundaiPanel.login());
        });
        ins.waitForIdleSync();
        ins.runOnMainSync(()->{
            View root=dialog.get().getWindow().getDecorView();Spinner modes=root.findViewWithTag("hyundai:mode");assertEquals(2,modes.getCount());assertEquals(0,modes.getSelectedItemPosition());
            assertTrue((dialog.get().getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            assertEquals(0,checkboxes(root));modes.setSelection(1);((EditText)root.findViewWithTag("hyundai:pin")).setText("0000");
            dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        assertEquals("begin_login",submitted.get(0).getString("operation"));assertEquals("gspa",submitted.get(0).getString("api_mode"));assertTrue(submitted.get(0).getBoolean("consent"));assertEquals("0000",submitted.get(0).getString("pin"));
        ins.runOnMainSync(()->{activity.hyundaiPanel.complete();dialog.set(activity.hyundaiPanel.passwordLogin("europe","0000")); });
        ins.waitForIdleSync();
        ins.runOnMainSync(()->{View root=dialog.get().getWindow().getDecorView();assertEquals(0,checkboxes(root));((EditText)root.findViewWithTag("hyundai:username")).setText("user@example.com");((EditText)root.findViewWithTag("hyundai:password")).setText("synthetic-password");dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals("",((EditText)root.findViewWithTag("hyundai:password")).getText().toString());activity.hyundaiPanel.complete();});
        JSONObject request=submitted.stream().filter(r->"login".equals(r.optString("operation"))).findFirst().get();assertEquals("europe",request.getString("api_mode"));assertTrue(request.getBoolean("consent"));
        assertTrue(activity.hyundaiPanel.state.error.contains("signin_redirect / provider_terms_required"));assertFalse(activity.hyundaiPanel.state.error.contains("synthetic-password"));
        assertTrue(HyundaiSignIn.allowed(Uri.parse("https://oneapp.hyundai.com/redirect")));assertFalse(HyundaiSignIn.allowed(Uri.parse("http://oneapp.hyundai.com/redirect")));assertFalse(HyundaiSignIn.allowed(Uri.parse("https://oneapp.hyundai.com.other.example.org/redirect")));assertFalse(HyundaiSignIn.allowed(Uri.parse("https://oneapp.hyundai.com/redirect").buildUpon().encodedAuthority("synthetic-user"+"@"+"oneapp.hyundai.com").build()));assertFalse(HyundaiSignIn.callback(Uri.parse("https://oneapp.hyundai.com/other")));
        ins.waitForIdleSync();Thread.sleep(500);File directory=new File(app.getFilesDir(),"code22-captures");directory.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(directory,"auth-diagnostic-landscape.png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out));}
        ins.runOnMainSync(activity::finish);
    }
    @Test public void legacy_authorization_page_has_an_exact_navigation_allowlist() {
        String page="https://prd.eu-ccapi.hyundai.com:8080/web/v1/user/authorize";
        assertTrue(HyundaiSignIn.allowed(Uri.parse(page+"?next_uri=synthetic")));
        assertFalse(HyundaiSignIn.callback(Uri.parse(page+"?code=synthetic")));
        for(String bad:Arrays.asList(page.replace("https:","http:"),page.replace(":8080", ""),page.replace(":8080", ":443"),page+"/extra",page+"#synthetic",page.replace("/authorize","/%61uthorize"),page.replace("/web/v1/user/authorize","/api/v1/spa/vehicles"),page.replace("prd.eu-ccapi.hyundai.com","prd.eu-ccapi.hyundai.com.other.example.org")))assertFalse(bad,HyundaiSignIn.allowed(Uri.parse(bad)));
        assertFalse(HyundaiSignIn.allowed(Uri.parse(page).buildUpon().encodedAuthority("synthetic"+"@"+"prd.eu-ccapi.hyundai.com:8080").build()));
    }
    static int checkboxes(View view){int count=view instanceof CheckBox?1:0;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=checkboxes(group.getChildAt(i));}return count;}
}
