package io.moli.android;

import org.json.*;

/** Evidence comes from fresh authenticated engine samples, never profile display names. */
final class TrialEvidence {
    boolean synthetic, demo, weather;
    long weatherReceipt;
    static TrialEvidence observe(JSONObject selected,JSONObject snapshot,long now,boolean internet) throws JSONException {
        TrialEvidence result=new TrialEvidence(); JSONArray drivers=snapshot.getJSONArray("drivers"), devices=snapshot.getJSONArray("devices");
        for(int i=0;i<devices.length();i++) {
            JSONObject d=devices.getJSONObject(i); String instance=d.optString("instance");
            String key=instance.equals("android-demo")?"demo":instance.equals("android-import")?"import":instance.equals("android-weather")?"weather":"";
            if(key.isEmpty() || !selected.has(key)) continue;
            boolean running=false;
            for(int j=0;j<drivers.length();j++) { JSONObject driver=drivers.getJSONObject(j);
                if(instance.equals(driver.optString("instance"))) running="running".equals(driver.getJSONObject("status").optString("state")); }
            JSONObject entry=selected.optJSONObject(key), state=d.getJSONObject("state");
            boolean simulated=key.equals("demo") || (entry!=null && "synthetic".equals(entry.optString("mode")));
            long age=Math.max(30,(entry==null?5:entry.optLong("every",900))+30)*1000;
            JSONObject t=state.optJSONObject("temperature"),h=state.optJSONObject("humidity");
            boolean fresh=valid(t,running && (simulated || internet),d.optBoolean("online"),now,age)
                    && valid(h,running && (simulated || internet),d.optBoolean("online"),now,age);
            if(key.equals("demo")) result.demo=fresh;
            else if(simulated) result.synthetic=fresh && Math.abs(t.optDouble("value")-42.5)<0.000001;
            else { result.weather|=fresh; if(fresh) result.weatherReceipt=Math.max(result.weatherReceipt,Math.min(t.optLong("ts"),h.optLong("ts"))); }
        }
        return result;
    }
    private static boolean valid(JSONObject s,boolean running,boolean online,long now,long age) {
        return s!=null && Double.isFinite(s.optDouble("value")) && DataFreshness.recent(running,online,!s.isNull("value"),s.optLong("ts"),now,age);
    }
}
