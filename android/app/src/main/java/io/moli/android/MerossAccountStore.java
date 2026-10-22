package io.moli.android;

import android.content.Context;
import android.net.*;
import org.json.*;

/** All cloud/device keys remain in Rust and the existing encrypted vault. */
final class MerossAccountStore {
    interface Backend { JSONObject call(JSONObject request) throws Exception; }
    static JSONObject request(Context context,JSONObject request) throws Exception {
        if(!ProtectedKey.exists(context)) throw new ProfileStore.Rejected("account_initialize");
        if("associate".equals(request.optString("action"))) {
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class); Network network=cm.getActiveNetwork();
            NetworkCapabilities cap=cm.getNetworkCapabilities(network);
            if(cap==null || !cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !LocalDiscovery.scope(cm.getLinkProperties(network)).contains(request.optString("host"))) throw new ProfileStore.Rejected("account_wifi");
        }
        return EngineHost.maintain(() -> {
            JSONObject keys=ProtectedKey.open(context);
            request.put("data",ProtectedKey.dataDirectory(context,keys).getAbsolutePath()).put("master",keys.getString("master"));
            try { return ProfileStore.nativeResult(request.toString(),6); }
            finally { for(String field:new String[]{"master","password","otp","email"}) request.remove(field); }
        }).get();
    }
    private MerossAccountStore() {}
}
