package io.moli.android;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class NetworkEvidenceTest {
    @Test public void wifiScopeNeverBecomesAnInternetOrLargeSubnetSweep() {
        NetworkEvidence.Scope wifi=new NetworkEvidence.Scope("192.168.1.40",24);
        assertEquals(253,wifi.probeHosts().size());
        assertTrue(wifi.contains("192.168.1.2"));
        for(String excluded:new String[]{"192.168.1.40","192.168.1.0","192.168.1.255","192.168.2.20","8.8.8.8","127.0.0.1","192.168.001.2","10.0.0.1"}) assertFalse(excluded,wifi.contains(excluded));
        NetworkEvidence.Scope large=new NetworkEvidence.Scope("172.16.2.40",16);
        assertTrue(large.contains("172.16.9.20")); assertTrue(large.probeHosts().isEmpty());
        assertTrue(new NetworkEvidence.Scope("10.0.0.1",30).contains("10.0.0.2"));
        for(String bad:new String[]{"172.32.0.1","8.8.8.8","127.0.0.1","192.168.1.999","192.168.01.2","example.org"}) {
            try { new NetworkEvidence.Scope(bad,24); fail(bad); } catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void mdnsUsesOnlyAnnouncedServicesAndSafeQueuePaths() {
        NetworkEvidence.Entry ipp=NetworkEvidence.mdns("192.168.1.20",631,"Invented printer","_ipp._tcp.","ipp/print".getBytes(StandardCharsets.UTF_8));
        assertEquals(NetworkEvidence.Kind.IPP,ipp.kind); assertEquals("/ipp/print",ipp.path);
        assertEquals(NetworkEvidence.Proof.ANNOUNCEMENT,ipp.proof);
        NetworkEvidence.Entry http=NetworkEvidence.mdns("192.168.1.21",80,"Invented appliance","_http._tcp",null);
        assertEquals(NetworkEvidence.Kind.HTTP,http.kind); assertTrue(http.path.isEmpty());
        assertNull(NetworkEvidence.mdns("8.8.8.8",631,"x","_ipp._tcp.",null));
        assertNull(NetworkEvidence.mdns("192.168.1.20",631,"x","_ipps._tcp.",null));
        assertNull(NetworkEvidence.mdns("192.168.1.20",0,"x","_ipp._tcp.",null));
        for(String bad:new String[]{"/admin","/ipp/../admin","//server/ipp","/printers/a?token=private","/printers/a/b","/ipp\n/print"}) assertEquals("",NetworkEvidence.ippPath(bad));
        assertEquals("",NetworkEvidence.safeName("Fake\nname"));
    }
    @Test public void duplicateProbesCannotOverwriteStrongerEvidenceOrGrowWithoutBound() {
        NetworkEvidence.Results results=new NetworkEvidence.Results();
        NetworkEvidence.Entry probe=new NetworkEvidence.Entry("192.168.1.20",631,"",NetworkEvidence.Kind.IPP,NetworkEvidence.Proof.OPEN_PORT,"","");
        NetworkEvidence.Entry mdns=new NetworkEvidence.Entry("192.168.1.20",631,"Announced printer",NetworkEvidence.Kind.IPP,NetworkEvidence.Proof.ANNOUNCEMENT,"/ipp/print","");
        assertTrue(results.add(probe)); assertTrue(results.add(mdns)); assertFalse(results.add(probe)); assertFalse(results.add(mdns));
        assertEquals(1,results.list().size()); assertEquals("Announced printer",results.list().get(0).name);
        results.add(new NetworkEvidence.Entry("192.168.1.1",80,"",NetworkEvidence.Kind.UPNP,NetworkEvidence.Proof.ANNOUNCEMENT,"","http://192.168.1.1/root.xml"));
        results.add(new NetworkEvidence.Entry("192.168.1.1",80,"Invented gateway",NetworkEvidence.Kind.IGD,NetworkEvidence.Proof.DESCRIPTION,"","http://192.168.1.1/root.xml"));
        assertEquals(2,results.list().size()); assertEquals(NetworkEvidence.Kind.IGD,results.list().get(1).kind);
        for(int i=0;i<200;i++) results.add(new NetworkEvidence.Entry("192.168.2."+i,80,"",NetworkEvidence.Kind.HTTP,NetworkEvidence.Proof.OPEN_PORT,"",""));
        assertEquals(128,results.list().size()); results.clear(); assertTrue(results.list().isEmpty());
    }
}
