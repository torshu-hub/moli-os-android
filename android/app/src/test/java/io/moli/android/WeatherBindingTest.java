package io.moli.android;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class WeatherBindingTest {
    private JSONObject form(String... ids)throws Exception {
        JSONArray instances=new JSONArray();for(String id:ids)instances.put(new JSONObject().put("id",id));
        return new JSONObject().put("instances",instances);
    }
    private JSONObject devices(Object temperature)throws Exception {
        JSONObject sample=new JSONObject().put("value",temperature);
        return new JSONObject().put("devices",new JSONArray().put(new JSONObject()
            .put("id","weather:opaque-runtime-identity").put("instance","weather").put("manufacturer","Open-Meteo")
            .put("online",true).put("state",new JSONObject().put("temperature",sample))));
    }
    @Test public void usesPublishedIdentityAndPreservesTheWholeExistingLayout()throws Exception {
        String before="{\"title\":\"Fixture\",\"outdoor\":{\"air\":\"air-id\",\"garden_lights\":[{\"id\":\"lamp-id\"}]},\"unknown\":{\"nested\":[1,true]}}";
        JSONObject next=new JSONObject(WeatherBinding.linkedHome(before,form("weather"),devices(12.5)));
        assertEquals("weather:opaque-runtime-identity",next.getJSONObject("outdoor").remove("weather"));
        JSONObject original=new JSONObject(before);
        assertEquals(original.length(),next.length());
        assertEquals(original.getString("title"),next.getString("title"));
        assertEquals(original.getJSONObject("unknown").toString(),next.getJSONObject("unknown").toString());
        assertEquals(original.getJSONObject("outdoor").toString(),next.getJSONObject("outdoor").toString());
    }
    @Test public void neverOverridesAChoiceOrGuessesWithoutRealData()throws Exception {
        assertNull(WeatherBinding.linkedHome("{\"outdoor\":{\"weather\":\"chosen-by-owner\"}}",form("weather"),devices(12.5)));
        assertNull(WeatherBinding.linkedHome(null,form("weather","other"),devices(12.5)));
        assertNull(WeatherBinding.linkedHome(null,form("weather"),devices(JSONObject.NULL)));
        assertNull(WeatherBinding.linkedHome(null,form("different"),devices(12.5)));
        try {WeatherBinding.linkedHome("{broken",form("weather"),devices(12.5));fail();}catch(JSONException expected){}
    }
}
