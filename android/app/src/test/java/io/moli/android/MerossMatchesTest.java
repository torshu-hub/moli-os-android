package io.moli.android;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class MerossMatchesTest {
    @Test public void aggregatesAllInventoryAndRefusesAmbiguousAddresses()throws Exception{
        MerossMatches m=new MerossMatches();JSONArray rows=new JSONArray();
        for(int i=1;i<=4;i++)rows.put(new JSONObject().put("uuid",String.format("%032x",i)).put("host","192.168.1."+(20+i)).put("result","matched"));
        for(int i=0;i<9;i++)rows.put(new JSONObject().put("host","192.168.1."+(50+i)).put("result","unavailable"));
        m.add(rows);assertEquals(4,m.found());String id=String.format("%032x",2);assertEquals("192.168.1.22",m.unique(id));
        m.add(new JSONArray().put(new JSONObject().put("uuid",id).put("host","192.168.1.99").put("result","matched")));
        assertTrue(m.ambiguous(id));assertNull(m.unique(id));assertEquals(3,m.found());
        assertEquals(14,m.results.length());
    }
    @Test public void additionUsesOnlyTheUniquelyIdentifiedAccountPlugs()throws Exception {
        MerossMatches m=new MerossMatches();JSONArray inventory=new JSONArray(),rows=new JSONArray();
        for(int n=1;n<=4;n++)inventory.put(new JSONObject().put("uuid",String.format("%032x",n)).put("name","Plug "+n));
        for(int n=1;n<=3;n++)rows.put(new JSONObject().put("uuid",String.format("%032x",n)).put("host","192.168.1."+(20+n)).put("result","matched"));
        m.add(rows);JSONArray plan=m.plan(inventory);assertEquals(3,plan.length());assertEquals("Plug 2",plan.getJSONObject(1).getString("name"));
        assertEquals("192.168.1.22",plan.getJSONObject(1).getString("host"));
        m.add(new JSONArray().put(new JSONObject().put("uuid",String.format("%032x",2)).put("host","192.168.1.99").put("result","matched")));
        assertEquals(2,m.plan(inventory).length());
    }
    @Test public void absentAndForeignIdentitiesNeverAcquireAnAddress()throws Exception{
        MerossMatches m=new MerossMatches();
        m.add(new JSONArray().put(new JSONObject().put("uuid",String.format("%032x",1)).put("host","192.168.1.21").put("result","matched"))
            .put(new JSONObject().put("host","192.168.1.99").put("result","different")));
        assertNull(m.unique(String.format("%032x",2)));assertEquals(1,m.found());
    }
}
