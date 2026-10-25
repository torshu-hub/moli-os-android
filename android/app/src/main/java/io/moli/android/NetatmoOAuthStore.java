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
            try{
                JSONObject result=ProfileStore.nativeResult(request.toString(),7);
                if("prepare_home".equals(request.optString("action"))){
                    java.io.File dir=ProtectedKey.dataDirectory(context,keys);
                    synchronized(ConfigurationTransaction.class){
                        if(!ProfileStore.read(new java.io.File(dir,"moli.toml"),ConfigurationTransaction.FILE_LIMIT).equals(result.getString("expected_configuration")))throw new ProfileStore.Rejected("configuration_conflict");
                        ProfileStore.transaction(dir).commit(java.util.Map.of("moli.toml",result.getString("configuration")));
                    }
                    JSONObject status=new JSONObject().put("data",dir.getAbsolutePath()).put("master",keys.getString("master")).put("action","status").put("instance",request.getString("instance"));
                    try{return ProfileStore.nativeResult(status.toString(),7);}finally{status.remove("master");}
                }
                return result;
            }
            finally{request.remove("master");request.remove("application");request.remove("callback");keys.remove("master");}
        };
        if(!"status".equals(request.optString("action"))&&EngineHost.state()!=EngineHost.State.STOPPED)throw new ProfileStore.Rejected("running");
        return ("status".equals(request.optString("action"))?EngineHost.inspect(work):EngineHost.maintain(work)).get();
    }
    private NetatmoOAuthStore(){}
}
