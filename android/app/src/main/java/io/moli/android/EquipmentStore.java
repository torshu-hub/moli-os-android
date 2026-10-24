package io.moli.android;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import org.json.JSONObject;

/** User-approved local reads, included in the existing encrypted engine backup. */
final class EquipmentStore {
    private EquipmentStore() {}
    static synchronized JSONObject raw(Context context) throws Exception {
        File file = new File(ProfileStore.data(context), "android-equipment.json");
        return file.exists() ? new JSONObject(ProfileStore.bounded(new AtomicFile(file).openRead(),16384)) : new JSONObject();
    }
    static synchronized JSONObject read(Context context) throws Exception { return ProfileStore.nativeResult(raw(context).toString(),3); }
    static JSONObject preserve(JSONObject old, JSONObject known) throws Exception {
        JSONObject result=new JSONObject(old.toString());
        for(String key:new String[]{"printer","gateway"}) if(known.has(key)) {
            JSONObject item=old.optJSONObject(key)==null?new JSONObject():new JSONObject(old.getJSONObject(key).toString());
            JSONObject update=known.getJSONObject(key);
            for(java.util.Iterator<String> it=update.keys();it.hasNext();) {String k=it.next();item.put(k,update.get(k));}
            result.put(key,item);
        }
        org.json.JSONArray rows=known.optJSONArray("meross"), before=old.optJSONArray("meross"), merged=new org.json.JSONArray();
        if(rows!=null)for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i), item=new JSONObject();
            if(before!=null)for(int j=0;j<before.length();j++)if(row.getString("id").equals(before.getJSONObject(j).optString("id")))item=new JSONObject(before.getJSONObject(j).toString());
            for(java.util.Iterator<String> it=row.keys();it.hasNext();) {String k=it.next();item.put(k,row.get(k));}
            merged.put(item);
        }
        if(rows!=null)result.put("meross",merged);
        return result;
    }
    static synchronized void save(Context context, JSONObject input, org.json.JSONArray updates) throws Exception {
        JSONObject checked = ProfileStore.nativeResult(input.toString(), 3);
        JSONObject keys = ProtectedKey.open(context);
        ProfileStore.nativeResult(new JSONObject().put("data",ProtectedKey.dataDirectory(context,keys).getAbsolutePath())
                .put("master",keys.getString("master")).put("keys",updates).toString(),4);
        ProfileStore.saveEquipment(context, checked);
    }
    static synchronized void save(Context context, JSONObject input) throws Exception {
        JSONObject checked = ProfileStore.nativeResult(input.toString(), 3);
        ProfileStore.saveEquipment(context, checked);
    }
}
