package io.moli.android;
import org.json.*;
import java.util.*;
/** Inventory-wide identity aggregation. A duplicate UUID is never silently assigned an address. */
final class MerossMatches {
    final Map<String,LinkedHashSet<String>> hosts=new LinkedHashMap<>();
    final JSONArray results=new JSONArray();
    void add(JSONArray rows)throws JSONException{for(int i=0;i<rows.length();i++){
        JSONObject row=rows.getJSONObject(i);results.put(row);
        if("matched".equals(row.optString("result")))hosts.computeIfAbsent(row.getString("uuid"),k->new LinkedHashSet<>()).add(row.getString("host"));
    }}
    String unique(String uuid){Set<String> addresses=hosts.get(uuid);return addresses!=null&&addresses.size()==1?addresses.iterator().next():null;}
    boolean ambiguous(String uuid){Set<String> addresses=hosts.get(uuid);return addresses!=null&&addresses.size()>1;}
    JSONArray plan(JSONArray inventory)throws JSONException {
        JSONArray plan=new JSONArray();
        for(int i=0;i<inventory.length();i++) {
            JSONObject item=inventory.getJSONObject(i);String uuid=item.getString("uuid"),host=unique(uuid);
            if(host!=null)plan.put(new JSONObject().put("uuid",uuid).put("host",host).put("name",item.getString("name")));
        }
        return plan;
    }
    int found(){return (int)hosts.values().stream().filter(s->s.size()==1).count();}
}
