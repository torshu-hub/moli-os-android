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
    @Test public void linksOnlyUnambiguousAirWithReceivedMeasurementsAndKeepsWeather()throws Exception {
        JSONObject air=form("air").put("manifest",new JSONObject().put("integration",new JSONObject().put("id","open-meteo-air")));
        JSONObject weather=form("weather").put("manifest",new JSONObject().put("integration",new JSONObject().put("id","open-meteo")));
        JSONObject catalogue=new JSONObject().put("packages",new JSONArray().put(weather).put(air));
        JSONObject device=new JSONObject().put("id","air:runtime-id").put("instance","air").put("manufacturer","Open-Meteo").put("online",true).put("state",new JSONObject());
        JSONObject snapshot=new JSONObject().put("devices",new JSONArray().put(device));
        String before="{\"outdoor\":{\"weather\":\"owner-weather\"},\"preserve\":[1,true]}";
        assertNull(WeatherBinding.linkedCatalogue(before,catalogue,snapshot));
        device.getJSONObject("state").put("aqi",new JSONObject().put("value",18));
        JSONObject next=new JSONObject(WeatherBinding.linkedCatalogue(before,catalogue,snapshot));
        assertEquals("owner-weather",next.getJSONObject("outdoor").getString("weather"));
        assertEquals("air:runtime-id",next.getJSONObject("outdoor").getString("air"));
        assertEquals("[1,true]",next.getJSONArray("preserve").toString());
        assertNull(WeatherBinding.linkedCatalogue(next.toString(),catalogue,snapshot));
        air.getJSONArray("instances").put(new JSONObject().put("id","second-air"));
        assertNull(WeatherBinding.linkedCatalogue(before,catalogue,snapshot));
    }
}
