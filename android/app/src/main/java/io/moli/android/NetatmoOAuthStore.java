package io.moli.android;

import android.content.Context;
import org.json.JSONObject;

/** Reuses the active data generation/Keystore; never initializes or exports keys. */
final class NetatmoOAuthStore {
    static JSONObject request(Context context,JSONObject request) throws Exception {
        if(!ProtectedKey.exists(context))throw new ProfileStore.Rejected("account_initialize");
        java.util.concurrent.Callable<JSONObject> work=()->{
            JSONObject keys=ProtectedKey.open(context);
            request.put("data",ProtectedKey.dataDirectory(context,keys).getAbsolutePath()).put("master",keys.getString("master"));
            try{return ProfileStore.nativeResult(request.toString(),7);}
            finally{request.remove("master");keys.remove("master");}
        };
        if(!"status".equals(request.optString("action"))&&EngineHost.state()!=EngineHost.State.STOPPED)throw new ProfileStore.Rejected("running");
        return ("status".equals(request.optString("action"))?EngineHost.inspect(work):EngineHost.maintain(work)).get();
    }
    private NetatmoOAuthStore(){}
}
