package io.moli.android;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NetatmoOAuthUiTest {
    @Test public void personalRedirectAndPrivateHomesAreStrictlyBoundWithoutExposingCredentials()throws Exception {
        assertTrue(NetatmoOAuthUi.validRedirect(NetatmoOAuthUi.PERSONAL_REDIRECT));
        assertFalse(NetatmoOAuthUi.validRedirect("http://localhost.evil/moli-netatmo"));
        NetatmoOAuthUi model=new NetatmoOAuthUi();
        JSONObject state=snapshot("stored",true,false).put("experimental",true).put("redirect_uri",NetatmoOAuthUi.PERSONAL_REDIRECT)
            .put("read_verified",true).put("selected_home","home-test").put("homes",new JSONArray().put(new JSONObject().put("id","home-test").put("name","Synthetic house")));
        model.apply(state);assertTrue(model.experimental);assertTrue(model.verified);assertEquals("home-test",model.homeIds.get(0));
        assertThrows(IllegalArgumentException.class,()->model.request("prepare_home",false));
        assertThrows(IllegalArgumentException.class,()->model.request("provision",false));
        assertEquals("provision",model.request("provision",true).getString("action"));
        assertFalse(model.toString().contains("Synthetic house"));
        state.getJSONArray("homes").put(new JSONObject().put("id","home-test").put("name","Duplicate"));
        assertThrows(IllegalArgumentException.class,()->model.apply(state));
    }
    private static final String REDIRECT="https://oauth.example.com/netatmo/return";
    private static String url() throws Exception {return "https://api.netatmo.com/oauth2/authorize?response_type=code&client_id=test-client&redirect_uri="+URLEncoder.encode(REDIRECT,StandardCharsets.UTF_8.name())+"&scope=read_thermostat&state="+"a".repeat(64);}
    private static JSONObject snapshot(String state,boolean credentials,boolean setup)throws Exception {
        JSONObject out=new JSONObject().put("state",state).put("credential_present",credentials).put("setup_required",setup).put("scope","read_thermostat").put("selected","energy-test").put("instances",new JSONArray().put(new JSONObject().put("id","energy-test").put("enabled",false).put("configured",true)));
        if(!setup)out.put("client_id","test-client").put("redirect_uri",REDIRECT);return out;
    }
    @Test public void noConsentOrServiceNeverCallsBackend() throws Exception {
        NetatmoOAuthUi model=new NetatmoOAuthUi();AtomicInteger calls=new AtomicInteger();
        NetatmoOAuthUi.Backend backend=r->{calls.incrementAndGet();return snapshot("pending",false,true);};
        model.apply(snapshot("idle",false,true));
        assertThrows(IllegalArgumentException.class,()->model.begin(backend,true));
        model.apply(snapshot("idle",false,false));
        assertThrows(IllegalArgumentException.class,()->model.begin(backend,false));
        assertEquals(0,calls.get());
    }
    @Test public void approvedFlowUsesExactOfficialReadUrlAndReturnsWithoutADeepLink() throws Exception {
        NetatmoOAuthUi model=new NetatmoOAuthUi();model.apply(snapshot("idle",false,false));
        String actual=model.begin(input->{assertEquals("begin",input.getString("action"));assertTrue(input.getBoolean("consent"));return snapshot("pending",false,false).put("authorization_url",url());},true);
        assertEquals(url(),actual);
        model.refresh(input->snapshot(input.getString("action").equals("status")?"pending":"stored",true,false));
        assertEquals("stored",model.state);assertTrue(model.credentials);assertFalse(model.toString().contains("state="));
    }
    @Test public void forgedRedirectHostScopeOrDuplicateParameterIsRejected() throws Exception {
        assertTrue(NetatmoOAuthUi.validAuthorization(url(),"test-client",REDIRECT));
        for(String value:new String[]{url().replace("api.netatmo.com","api.netatmo.com.evil"),url().replace("read_thermostat","write_thermostat"),url()+"&scope=read_thermostat",url()+"&access_token=synthetic",url()+"#fragment",url().replace("test-client","other-client")})assertFalse(NetatmoOAuthUi.validAuthorization(value,"test-client",REDIRECT));
        assertFalse(NetatmoOAuthUi.validAuthorization(url(),"test-client","https://other.example.com/return"));
    }
    @Test public void denialAndExpiryAreStatusesAndDoNotOverwritePreviousCredentials() throws Exception {
        NetatmoOAuthUi model=new NetatmoOAuthUi();model.apply(snapshot("pending",true,false));
        model.refresh(r->snapshot(r.getString("action").equals("status")?"pending":"denied",true,false));
        assertEquals("denied",model.state);assertTrue(model.credentials);
        model.apply(snapshot("expired",true,false));assertEquals("expired",model.state);assertTrue(model.credentials);
    }
    @Test public void reopeningDoesNotRestoreAnyNonceUrlOrTokensFromUiState() throws Exception {
        NetatmoOAuthUi reopened=new NetatmoOAuthUi();
        reopened.refresh(r->snapshot("idle",true,true));
        assertEquals("idle",reopened.state);assertTrue(reopened.credentials);assertTrue(reopened.setupRequired);assertTrue(reopened.interrupted(true));
        assertFalse(reopened.request("status",false).has("authorization_url"));
        assertFalse(reopened.toString().contains("test-client"));
    }
    @Test public void secretBearingRepliesAndUnexpectedStatesAreNeverAcceptedForDisplay() throws Exception {
        NetatmoOAuthUi model=new NetatmoOAuthUi();
        for(String key:new String[]{"access_token","refresh_token","client_secret","code","master"}) {
            JSONObject unsafe=snapshot("idle",false,false).put(key,"synthetic-private");
            assertThrows(IllegalArgumentException.class,()->model.apply(unsafe));
        }
        assertThrows(IllegalArgumentException.class,()->model.apply(snapshot("unknown",false,false)));
        assertFalse(model.toString().contains("synthetic-private"));
    }
    @Test public void disconnectReportsManualRemoteRevocationAndKeepsInstanceIdentity() throws Exception {
        NetatmoOAuthUi model=new NetatmoOAuthUi();model.apply(snapshot("stored",true,false));
        assertThrows(IllegalArgumentException.class,()->model.request("disconnect",false));
        model.apply(snapshot("idle",false,false).put("remote_revocation_required",true));
        assertTrue(model.revokeRequired);assertEquals("energy-test",model.selected);assertFalse(model.credentials);
    }
}
