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
    @Test public void reportNameIsRecognizableDatedAndHasNoInternalPreviewLabel(){
        String n=ReportExport.fileName(1792700000000L,"0.13.0-preview");
        assertTrue(n.matches("Rapport-essai-Moli-OS_[0-9-]+_[0-9-]+_v0\\.13\\.0\\.txt"));assertFalse(n.contains("preview"));
    }
}
