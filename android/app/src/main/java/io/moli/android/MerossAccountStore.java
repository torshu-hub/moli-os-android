package io.moli.android;

import android.content.Context;
import android.net.*;
import org.json.*;

/** All cloud/device keys remain in Rust and the existing encrypted vault. */
final class MerossAccountStore {
    interface Backend { JSONObject call(JSONObject request) throws Exception; }
    static JSONObject request(Context context,JSONObject request) throws Exception {
        if(!ProtectedKey.exists(context)) throw new ProfileStore.Rejected("account_initialize");
        if("associate".equals(request.optString("action")) || "identify".equals(request.optString("action")) || "identify_start".equals(request.optString("action"))) {
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class); Network network=cm.getActiveNetwork();
            NetworkCapabilities cap=cm.getNetworkCapabilities(network);
            if(cap==null || !cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) throw new ProfileStore.Rejected("account_wifi");
            NetworkEvidence.Scope scope=LocalDiscovery.scope(cm.getLinkProperties(network));
            if("identify".equals(request.optString("action"))) {
                JSONArray candidates=request.optJSONArray("candidates");
                if(candidates==null || candidates.length()<1 || candidates.length()>8) throw new ProfileStore.Rejected("account_input");
                for(int i=0;i<candidates.length();i++) if(!scope.contains(candidates.optString(i))) throw new ProfileStore.Rejected("account_wifi");
            } else if("associate".equals(request.optString("action")) && !scope.contains(request.optString("host"))) throw new ProfileStore.Rejected("account_wifi");
        }
        java.util.concurrent.Callable<JSONObject> work=() -> {
            JSONObject keys=ProtectedKey.open(context);
            request.put("data",ProtectedKey.dataDirectory(context,keys).getAbsolutePath()).put("master",keys.getString("master"));
            try { return ProfileStore.nativeResult(request.toString(),6); }
            finally { for(String field:new String[]{"master","password","otp","email"}) request.remove(field); }
        };
        return ("status".equals(request.optString("action"))?EngineHost.inspect(work):EngineHost.maintain(work)).get();
    }
    private MerossAccountStore() {}
}
