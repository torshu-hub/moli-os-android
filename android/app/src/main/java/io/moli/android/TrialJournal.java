package io.moli.android;

import org.json.*;

/** Only fixed codes and numbers are recordable; never arbitrary strings or payloads. */
final class TrialJournal {
    static final int MAX_EVENTS = 256;
    enum Event { SESSION_START, SESSION_END, PROFILE_REVIEWED, PROFILE_APPROVED, PROFILE_REJECTED,
        SELECTION_CHANGED, ENGINE_STARTED, ENGINE_STOPPED, ENGINE_FAILED, ENGINE_READ_OK, ENGINE_READ_FAILED,
        SYNTHETIC_RECEIVED, DEMO_RECEIVED, WEATHER_RECEIVED, PROBE_START, PROBE_OK, PROBE_FAILED,
        PROBE_LIMITED, HEALTH_OK, HEALTH_FAILED, CHECK_GAP }
    boolean active;
    int dropped;
    final JSONArray events = new JSONArray();
    static TrialJournal parse(String text) throws JSONException {
        TrialJournal j = new TrialJournal(); JSONObject root = new JSONObject(text);
        j.active = root.optBoolean("active"); j.dropped = Math.max(0,root.optInt("dropped"));
        JSONArray entries = root.optJSONArray("events");
        if (entries != null) for (int i=Math.max(0,entries.length()-MAX_EVENTS);i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i); if (e==null) continue;
            try { j.add(e.optLong("time"),Event.valueOf(e.optString("event")),e.optLong("a"),e.optLong("b")); }
            catch (IllegalArgumentException ignored) { /* Unrecognized text is never exported. */ }
        }
        return j;
    }
    void add(long now, Event event, long a, long b) throws JSONException {
        if(event==Event.SYNTHETIC_RECEIVED || event==Event.DEMO_RECEIVED || event==Event.WEATHER_RECEIVED) {
            for(int i=events.length()-1;i>=0;i--) { JSONObject previous=events.getJSONObject(i);
                if(event.name().equals(previous.getString("event"))) { if(previous.getLong("a")==a && previous.getLong("b")==b) return; break; }
            }
        }
        if (events.length()>=MAX_EVENTS) { events.remove(0); dropped++; }
        events.put(new JSONObject().put("time",now).put("event",event.name()).put("a",a).put("b",b));
    }
    String json() throws JSONException { return new JSONObject().put("active",active).put("dropped",dropped).put("events",events).toString(); }
    String text() throws JSONException {
        StringBuilder s=new StringBuilder("Moli OS trial journal\nactive=").append(active).append(" dropped=").append(dropped).append('\n');
        s.append("Fields: epoch_ms event a b; ENGINE_READ_OK: a=read_count b=weather_receipt_ms; *_RECEIVED: a=1 fresh, a=0 not fresh; CHECK_GAP: a=milliseconds.\n");
        for(int i=0;i<events.length();i++) { JSONObject e=events.getJSONObject(i);
            s.append(e.getLong("time")).append(' ').append(e.getString("event")).append(' ')
                .append(e.getLong("a")).append(' ').append(e.getLong("b")).append('\n'); }
        return s.toString();
    }
}
