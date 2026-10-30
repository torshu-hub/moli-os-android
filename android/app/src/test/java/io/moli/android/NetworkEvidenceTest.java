package io.moli.android;

import org.junit.Test;
import static org.junit.Assert.*;

/** Code39 scope/evidence cases adapted to a single IP row and no integration binding. */
public final class NetworkEvidenceTest {
    @Test public void realPrefixControlsBoundsWithoutAnInternetOrLargeSubnetSweep(){
        NetworkEvidence.Scope lan=new NetworkEvidence.Scope("192.168.50.40",24);
        assertEquals(253,lan.probeHosts().size());assertTrue(lan.contains("192.168.50.2"));
        for(String excluded:new String[]{"192.168.50.40","192.168.50.0","192.168.50.255","192.168.51.20","8.8.8.8","127.0.0.1","192.168.050.2","10.0.0.1"})assertFalse(excluded,lan.contains(excluded));
        NetworkEvidence.Scope small=new NetworkEvidence.Scope("192.168.50.40",26);
        assertEquals(61,small.probeHosts().size());assertFalse(small.contains("192.168.50.64"));
        NetworkEvidence.Scope large=new NetworkEvidence.Scope("172.16.2.40",16);
        assertTrue(large.contains("172.16.9.20"));assertTrue(large.probeHosts().isEmpty());assertTrue(large.probesLimited());
        assertEquals(1,new NetworkEvidence.Scope("10.0.0.1",30).probeHosts().size());
        assertEquals(1,new NetworkEvidence.Scope("10.0.0.0",31).probeHosts().size());
        assertTrue(new NetworkEvidence.Scope("10.0.0.1",32).probeHosts().isEmpty());
        for(String bad:new String[]{"172.32.0.1","8.8.8.8","127.0.0.1","192.168.50.999","192.168.050.2","example.org"}){
            try{new NetworkEvidence.Scope(bad,24);fail(bad);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void mdnsNamesAndServiceTypesAreAnnouncementsNotModels(){
        NetworkEvidence.Entry ipp=NetworkEvidence.mdns("192.168.50.20",631,"Fixture printer","_ipp._tcp.",null);
        assertEquals(NetworkEvidence.Kind.IPP,ipp.kind);assertEquals(NetworkEvidence.Proof.ANNOUNCEMENT,ipp.proof);
        assertNull(NetworkEvidence.mdns("8.8.8.8",631,"x","_ipp._tcp.",null));
        assertNull(NetworkEvidence.mdns("192.168.50.20",0,"x","_ipp._tcp.",null));
        assertNull(NetworkEvidence.mdns("192.168.50.20",80,"x","_unknown._tcp.",null));
        assertEquals("",NetworkEvidence.safeName("bad\nname"));assertEquals("",NetworkEvidence.safeName("bad\u202Ename"));assertEquals("",NetworkEvidence.safeName("x".repeat(161)));
    }
    @Test public void repeatedProtocolsMergeByIpAndPreserveSeparateProofs(){
        NetworkEvidence.Results r=new NetworkEvidence.Results();
        NetworkEvidence.Entry port=new NetworkEvidence.Entry("192.168.50.20",631,"",NetworkEvidence.Kind.TCP,NetworkEvidence.Proof.OPEN_PORT,"","");
        NetworkEvidence.Entry mdns=NetworkEvidence.mdns("192.168.50.20",631,"Fixture printer","_ipp._tcp.",null);
        assertTrue(r.add(port));assertTrue(r.add(mdns));assertFalse(r.add(port));assertFalse(r.add(mdns));
        assertEquals(1,r.size());assertEquals(2,r.list().get(0).facts.size());assertEquals("Fixture printer",r.list().get(0).name());
        r.add(new NetworkEvidence.Entry("192.168.50.20",80,"Fixture description",NetworkEvidence.Kind.UPNP,NetworkEvidence.Proof.DESCRIPTION,"/root.xml","http://192.168.50.20/root.xml"));
        assertEquals("Fixture description",r.list().get(0).name());
        r.add(new NetworkEvidence.Entry("192.168.50.7",443,"",NetworkEvidence.Kind.TCP,NetworkEvidence.Proof.OPEN_PORT,"",""));
        assertEquals("192.168.50.7",r.list().get(0).ip);assertEquals("",r.list().get(0).name());
    }
    @Test public void resultAndPerDeviceEvidenceHaveFiniteCaps(){
        NetworkEvidence.Results r=new NetworkEvidence.Results();
        for(int i=1;i<=200;i++)r.add(new NetworkEvidence.Entry("192.168.51."+i,80,"",NetworkEvidence.Kind.TCP,NetworkEvidence.Proof.OPEN_PORT,"",""));
        assertEquals(128,r.size());assertTrue(r.limited);r.clear();
        for(int i=1;i<=20;i++)r.add(new NetworkEvidence.Entry("192.168.50.20",i,"",NetworkEvidence.Kind.TCP,NetworkEvidence.Proof.OPEN_PORT,"",""));
        assertEquals(12,r.list().get(0).facts.size());assertTrue(r.limited);r.clear();assertFalse(r.limited);assertEquals(0,r.size());
    }
    @Test public void ssdpOnlyAllowsOneSafeLiteralSameDeviceDescription(){
        String source="192.168.50.20";
        assertNotNull(NetworkEvidence.ssdp(source,"HTTP/1.1 200 OK\r\nLOCATION: http://192.168.50.20:8080/root.xml\r\n\r\n"));
        for(String url:new String[]{"http://8.8.8.8/root.xml","http://192.168.50.21/root.xml","http://user@192.168.50.20/root.xml","http://example.org/root.xml","https://192.168.50.20/root.xml","http://192.168.50.20/root.xml?secret=x","http://192.168.50.20/../root.xml","http://192.168.50.20/%2e%2e/root.xml","http://192.168.50.20//root.xml","http://192.168.50.20:0/root.xml","http://192.168.50.20/root.xml#fragment"})assertNull(url,NetworkEvidence.ssdp(source,"HTTP/1.1 200 OK\r\nLOCATION: "+url+"\r\n"));
        assertNull(NetworkEvidence.ssdp(source,"HTTP/1.1 200 OK\r\nLOCATION: http://192.168.50.20/root.xml\r\nLOCATION: http://192.168.50.20/other.xml\r\n"));
        assertNull(NetworkEvidence.ssdp(source,"x".repeat(4097)));
    }
}
