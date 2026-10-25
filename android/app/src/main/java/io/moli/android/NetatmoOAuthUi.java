package io.moli.android;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Pure presentation policy, JVM-testable without browser/account/Android keys.
 * The native Rust boundary owns all ephemeral proofs and token persistence. */
final class NetatmoOAuthUi {
    interface Backend { JSONObject call(JSONObject input) throws Exception; }
    private static final Set<String> FIELDS=Set.of("setup_required","state","scope","credential_present","instances","selected","authorization_url","remote_revocation_required","client_id","redirect_uri","experimental","homes","selected_home","read_verified");
    private static final Set<String> STATES=Set.of("idle","pending","saving","stored","denied","expired","error");
    boolean setupRequired=true, credentials, revokeRequired, experimental, verified, enabled;
    String state="idle", selected="", clientId="", redirect="";
    List<String> instances=List.of();
    List<String> homeIds=List.of(), homeNames=List.of();
    String selectedHome="";
    static final String PERSONAL_REDIRECT="http://localhost/moli-netatmo";

    void apply(JSONObject result) throws Exception {
        if(result.length()>FIELDS.size())throw new IllegalArgumentException("oauth_input");
        for(Iterator<String> keys=result.keys();keys.hasNext();)if(!FIELDS.contains(keys.next()))throw new IllegalArgumentException("oauth_input");
        String next=result.getString("state");
        if(!STATES.contains(next)||!"read_thermostat".equals(result.getString("scope")))throw new IllegalArgumentException("oauth_input");
        JSONArray rows=result.getJSONArray("instances");if(rows.length()<1||rows.length()>64)throw new IllegalArgumentException("oauth_input");
        List<String> ids=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            String id=rows.getJSONObject(i).getString("id");
            if(id.isEmpty()||id.length()>128||id.indexOf(':')>=0||id.indexOf('/')>=0||id.indexOf('\\')>=0||id.chars().anyMatch(Character::isISOControl)||ids.contains(id))throw new IllegalArgumentException("oauth_input");ids.add(id);
        }
        String chosen=result.getString("selected");if(!ids.contains(chosen))throw new IllegalArgumentException("oauth_input");
        boolean selectedEnabled=false;
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(chosen))selectedEnabled=rows.getJSONObject(i).optBoolean("enabled");
        JSONArray homes=result.optJSONArray("homes");List<String> homeIdsNext=new ArrayList<>(),homeNamesNext=new ArrayList<>();
        if(homes!=null){if(homes.length()>32)throw new IllegalArgumentException("oauth_input");for(int i=0;i<homes.length();i++){
            JSONObject home=homes.getJSONObject(i);String id=home.getString("id"),name=home.getString("name");
            if(!id.matches("[A-Za-z0-9_-]{1,128}")||homeIdsNext.contains(id)||name.length()>256||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("oauth_input");
            homeIdsNext.add(id);homeNamesNext.add(name+" — "+id);
        }}
        boolean setup=result.getBoolean("setup_required");
        String app=result.optString("client_id"),callback=result.optString("redirect_uri");
        if(!setup&&(!app.matches("[A-Za-z0-9_-]{1,128}")||!validRedirect(callback)))throw new IllegalArgumentException("oauth_setup");
        setupRequired=setup;state=next;selected=chosen;instances=List.copyOf(ids);
        credentials=result.getBoolean("credential_present");revokeRequired=result.optBoolean("remote_revocation_required");
        clientId=app;redirect=callback;
        homeIds=List.copyOf(homeIdsNext);homeNames=List.copyOf(homeNamesNext);selectedHome=result.isNull("selected_home")?"":result.optString("selected_home");
        experimental=result.optBoolean("experimental");verified=result.optBoolean("read_verified");enabled=selectedEnabled;
    }
    JSONObject request(String action,boolean consent) throws Exception {
        if(!Set.of("status","begin","check","cancel","disconnect","provision","complete","homes","prepare_home").contains(action))throw new IllegalArgumentException("oauth_input");
        if(Set.of("begin","disconnect","provision","prepare_home").contains(action)&&!consent)throw new IllegalArgumentException("oauth_consent");
        if(action.equals("begin")&&setupRequired)throw new IllegalArgumentException("oauth_setup");
        JSONObject request=new JSONObject().put("action",action);
        if(!selected.isEmpty())request.put("instance",selected);
        if(consent)request.put("consent",true);return request;
    }
    void select(String id){if(!instances.contains(id)||state.equals("pending")||state.equals("saving"))throw new IllegalArgumentException("oauth_state");selected=id;}
    String begin(Backend backend,boolean consent) throws Exception {
        JSONObject response=backend.call(request("begin",consent));apply(response);
        String url=response.getString("authorization_url");
        if(!validAuthorization(url,clientId,redirect))throw new IllegalArgumentException("oauth_state");
        return url;
    }
    void refresh(Backend backend) throws Exception {
        apply(backend.call(request("status",false)));
        if(state.equals("pending")||state.equals("saving"))apply(backend.call(request("check",false)));
    }
    boolean interrupted(boolean awaiting){return awaiting&&state.equals("idle");}
    static boolean validRedirect(String value) {
        if(PERSONAL_REDIRECT.equals(value))return true;
        try {URI uri=new URI(value);return value.length()<=1024&&"https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getRawUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getRawQuery()==null&&uri.getFragment()==null&&!uri.getPath().isEmpty();}
        catch(Exception invalid){return false;}
    }
    static boolean validAuthorization(String value,String app,String redirect) {
        try {
            if(value.length()>4096||!validRedirect(redirect))return false;
            URI uri=new URI(value);
            if(!"https".equals(uri.getScheme())||!"api.netatmo.com".equals(uri.getHost())||uri.getRawUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||!"/oauth2/authorize".equals(uri.getRawPath())||uri.getFragment()!=null)return false;
            Map<String,String> query=new HashMap<>();
            for(String field:uri.getRawQuery().split("&")){
                String[] pair=field.split("=",2);if(pair.length!=2)return false;
                String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8.name());
                if(query.put(key,URLDecoder.decode(pair[1],StandardCharsets.UTF_8.name()))!=null)return false;
            }
            return query.keySet().equals(Set.of("response_type","client_id","redirect_uri","scope","state"))&&"code".equals(query.get("response_type"))&&app.equals(query.get("client_id"))&&redirect.equals(query.get("redirect_uri"))&&"read_thermostat".equals(query.get("scope"))&&query.get("state").matches("[a-f0-9]{64}");
        }catch(Exception invalid){return false;}
    }
    @Override public String toString(){return "NetatmoOAuthUi("+state+")";}
}
