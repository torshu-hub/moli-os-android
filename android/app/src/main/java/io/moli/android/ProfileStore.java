package io.moli.android;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;

/** Exact-byte approval, immutable profile copies and atomic selection. Engine must be stopped for changes. */
final class ProfileStore {
    static final int LIMIT = 65536;
    static final class Rejected extends Exception { Rejected(String code) { super(code); } }
    static File data(Context c) throws Exception { return ProtectedKey.dataDirectory(c, ProtectedKey.open(c)); }
    static String bounded(InputStream stream, int limit) throws Exception {
        if (stream == null) throw new Rejected("input");
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new Rejected("size"); out.write(b, 0, n); }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
        }
    }
    static String read(File f, int limit) throws Exception { return bounded(new FileInputStream(f), limit); }
    static void atomic(File f, String text) throws Exception {
        AtomicFile target = new AtomicFile(f); FileOutputStream out = target.startWrite();
        try { out.write(text.getBytes(StandardCharsets.UTF_8)); target.finishWrite(out); }
        catch (Exception failure) { target.failWrite(out); throw failure; }
    }
    static JSONObject nativeResult(String text, int operation) throws Exception {
        JSONObject result = new JSONObject(NativeServer.profile(text, operation));
        if (result.has("error")) throw new Rejected(result.getString("error"));
        return result;
    }
    static File stage(Context c) { return new File(c.getCacheDir(), "profile-staged.toml"); }
    static JSONObject stage(Context c, InputStream in) throws Exception {
        String text = bounded(in, LIMIT); JSONObject result = nativeResult(text, 0);
        atomic(stage(c), text); TrialLog.record(TrialJournal.Event.PROFILE_REVIEWED,0,0); return result;
    }
    static JSONObject state(Context c) throws Exception {
        File f = new File(data(c), "android-integrations.json");
        return f.exists() ? new JSONObject(bounded(new AtomicFile(f).openRead(), LIMIT)) : new JSONObject();
    }
    private static void save(Context c, JSONObject next) throws Exception {
        File dir = data(c);
        atomic(new File(dir, "android-integrations.previous.json"), state(c).toString());
        atomic(new File(dir, "android-integrations.json"), next.toString());
        TrialLog.record(TrialJournal.Event.SELECTION_CHANGED,next.optBoolean("demo")?1:0,next.has("weather")?1:0);
    }
    static void approve(Context c, String expected) throws Exception {
        String text = read(stage(c), LIMIT); JSONObject info = nativeResult(text, 0);
        if (!info.getString("sha256").equals(expected)) throw new Rejected("changed");
        installFile(c, text, info);
        JSONObject next = state(c); next.put("import", info); save(c, next); TrialLog.record(TrialJournal.Event.PROFILE_APPROVED,0,0);
    }
    private static void installFile(Context c, String text, JSONObject info) throws Exception {
        File dir = new File(data(c), "android-profiles");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("storage");
        File target = new File(dir, info.getString("sha256") + ".toml");
        if (target.exists()) { if (!read(target, LIMIT).equals(text)) throw new Rejected("changed"); }
        else {
            String[] entries = dir.list(); if (entries == null || entries.length >= 16) throw new Rejected("quota");
            atomic(target, text);
        }
        File trust = new File(data(c), "trusted-profiles.toml");
        String old = trust.exists() ? read(trust, 262144) : "";
        String hash = info.getString("sha256");
        if (!old.contains("sha256 = \"" + hash + "\"")) {
            // Paths are app-owned; TOML literal strings avoid escape interpretation.
            atomic(trust, old + "\n[[trusted]]\npath = '" + target.getAbsolutePath() + "'\nsha256 = \"" + hash + "\"\n");
        }
    }
    static void demo(Context c) throws Exception {
        JSONObject next = state(c); next.put("demo", true); save(c, next);
    }
    static void weather(Context c) throws Exception {
        String text = bounded(c.getAssets().open("open-meteo.toml"), LIMIT);
        JSONObject info = nativeResult(text, 0); installFile(c, text, info);
        JSONObject next = state(c), previous = next.optJSONObject("weather");
        if (previous != null && previous.has("location")) info.put("location", previous.getJSONObject("location"));
        next.put("weather", info); save(c, next);
    }
    static void configureWeather(Context c, String label, double latitude, double longitude) throws Exception {
        if (label.trim().isEmpty() || label.length() > 80 || !Double.isFinite(latitude) || Math.abs(latitude) > 90 || !Double.isFinite(longitude) || Math.abs(longitude) > 180) throw new Rejected("location");
        String text = bounded(c.getAssets().open("open-meteo.toml"), LIMIT);
        JSONObject info = nativeResult(text, 0); installFile(c, text, info);
        info.put("location", new JSONObject().put("label", label.trim()).put("latitude", latitude).put("longitude", longitude));
        JSONObject next = state(c); next.put("weather", info); save(c, next);
    }
    static JSONObject home(Context c) throws Exception {
        File f = new File(data(c), "home.json");
        return f.exists() ? new JSONObject(read(f, 262144)) : new JSONObject();
    }
    static void rooms(Context c, String names) throws Exception {
        org.json.JSONArray rooms = new org.json.JSONArray(); java.util.Set<String> used = new java.util.HashSet<>();
        for (String line : names.split("\\n")) {
            String name = line.trim(); if (name.isEmpty() || !used.add(name)) continue;
            if (name.length() > 60 || rooms.length() >= 32) throw new Rejected("rooms");
            JSONObject existing = null;
            org.json.JSONArray old = home(c).optJSONArray("rooms");
            if (old != null) for (int i=0;i<old.length();i++) if (name.equals(old.getJSONObject(i).optString("name"))) existing = old.getJSONObject(i);
            rooms.put(existing == null ? new JSONObject().put("name",name) : existing);
        }
        JSONObject h = home(c); h.put("rooms",rooms); atomic(new File(data(c),"home.json"),h.toString());
    }
    private static void prepareHome(Context c, JSONObject selected) throws Exception {
        JSONObject h = home(c), android = h.optJSONObject("android");
        if (android == null) android = new JSONObject();
        android.put("tablet", "android-tablet:tablet");
        JSONObject weather = selected.optJSONObject("weather");
        android.put("weather", weather == null ? JSONObject.NULL : "android-weather:api.open-meteo.com:443");
        android.put("location", weather == null ? "" : weather.optJSONObject("location") == null ? "Paris (démo)" : weather.getJSONObject("location").getString("label"));
        h.put("android",android);
        JSONObject out = h.optJSONObject("outdoor"); if (out == null) out = new JSONObject();
        if (weather != null) out.put("weather",android.getString("weather"));
        else if ("android-weather:api.open-meteo.com:443".equals(out.optString("weather"))) out.remove("weather");
        h.put("outdoor",out); atomic(new File(data(c),"home.json"),h.toString());
    }
    static void remove(Context c, String item) throws Exception {
        if (!item.equals("demo") && !item.equals("weather") && !item.equals("import")) throw new Rejected("input");
        JSONObject next = state(c); next.remove(item); save(c, next);
    }
    static void rollback(Context c) throws Exception {
        File previous = new File(data(c), "android-integrations.previous.json");
        JSONObject next = new JSONObject(read(previous, LIMIT));
        // Revalidate all selected bytes before publishing the previous selection.
        for (String item : new String[]{"import", "weather"}) if (next.has(item)) checked(c, next.getJSONObject(item));
        save(c, next);
    }
    private static JSONObject checked(Context c, JSONObject entry) throws Exception {
        String hash = entry.getString("sha256");
        if (!hash.matches("[a-f0-9]{64}")) throw new Rejected("changed");
        String text = read(new File(data(c), "android-profiles/" + hash + ".toml"), LIMIT);
        JSONObject info = nativeResult(text, 0);
        if (!hash.equals(info.getString("sha256"))) throw new Rejected("changed");
        JSONObject result = new JSONObject().put("text", text).put("sha256", hash);
        if (entry.has("location")) result.put("location", entry.getJSONObject("location"));
        return result;
    }
    static JSONObject weatherProbe(Context c) throws Exception {
        JSONObject selected=state(c), entry=selected.optJSONObject("weather");
        if(entry==null) entry=selected.optJSONObject("import");
        if(entry==null || !"weather".equals(entry.optString("mode"))) throw new Rejected("approval");
        JSONObject input=checked(c,entry); input.put("data",data(c).getAbsolutePath());
        android.content.SharedPreferences p=c.getSharedPreferences("weather-budget",Context.MODE_PRIVATE);
        WeatherBudget budget=new WeatherBudget(p.getLong("last",0),p.getLong("window",0),p.getInt("attempts",0));
        if(!budget.reserve(System.currentTimeMillis())) { TrialLog.record(TrialJournal.Event.PROBE_LIMITED,0,0); throw new Rejected("rate"); }
        if(!p.edit().putLong("last",budget.last).putLong("window",budget.window).putInt("attempts",budget.attempts).commit()) throw new Rejected("storage");
        TrialLog.record(TrialJournal.Event.PROBE_START,0,0);
        try {
            JSONObject result=nativeResult(input.toString(),2);
            TrialLog.record(TrialJournal.Event.PROBE_OK,result.getLong("received_ms"),0);
            return result;
        } catch(Exception failure) { TrialLog.record(TrialJournal.Event.PROBE_FAILED,0,0); throw failure; }
    }
    static SyntheticEndpoint prepare(Context c) throws Exception {
        File dir = data(c);
        JSONObject selected = state(c), spec = new JSONObject();
        spec.put("data", dir.getAbsolutePath()).put("demo", selected.optBoolean("demo"));
        for (String item : new String[]{"import", "weather"}) if (selected.has(item)) {
            JSONObject checked = checked(c, selected.getJSONObject(item)); spec.put(item, checked);
        }
        String tabletText = bounded(c.getAssets().open("tablet.toml"), LIMIT);
        JSONObject tablet = nativeResult(tabletText, 0); installFile(c, tabletText, tablet);
        spec.put("tablet", new JSONObject().put("text", tabletText).put("sha256", tablet.getString("sha256")));
        SyntheticEndpoint endpoint = new SyntheticEndpoint(c.getApplicationContext());
        try {
            spec.put("port", endpoint.port());
            File config = new File(dir, "moli.toml");
            spec.put("config", config.exists() ? read(config, 262144) : "");
            if (selected.optBoolean("demo")) atomic(new File(dir, "android-demo.json"), bounded(c.getAssets().open("android-demo.json"), LIMIT));
            String updated = nativeResult(spec.toString(), 1).getString("config");
            atomic(config, updated);
            prepareHome(c, selected);
            return endpoint;
        } catch (Exception failure) { if (endpoint != null) endpoint.close(); throw failure; }
    }
}
