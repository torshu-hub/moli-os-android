package io.moli.android;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.Assert.*;
public final class DiscoveryDnsTest {
    private byte[] response() {
        byte[] query=DiscoveryDns.query("192.168.50.21",1234);query[2]=(byte)0x81;query[3]=(byte)0x80;query[7]=1;
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(query,0,query.length);
        byte[] answer={(byte)0xc0,12,0,12,0,1,0,0,0,60,0,14,6,'t','a','b','l','e','t',5,'l','o','c','a','l',0};
        out.write(answer,0,answer.length);return out.toByteArray();
    }
    @Test public void ptrRequiresMatchingTransactionQuestionAndBoundedName() {
        byte[] bytes=response();assertEquals("tablet.local",DiscoveryDns.answer(bytes,"192.168.50.21",1234));
        assertEquals("",DiscoveryDns.answer(bytes,"192.168.50.22",1234));
        assertEquals("",DiscoveryDns.answer(bytes,"192.168.50.21",1235));
        for(int n=0;n<bytes.length;n++)assertEquals("",DiscoveryDns.answer(Arrays.copyOf(bytes,n),"192.168.50.21",1234));
        bytes[2]|=2;assertEquals("",DiscoveryDns.answer(bytes,"192.168.50.21",1234));
    }
    @Test public void compressionLoopsAndErrorsAreIgnored() {
        byte[] bytes=response();bytes[12]=(byte)0xc0;bytes[13]=12;
        assertEquals("",DiscoveryDns.answer(bytes,"192.168.50.21",1234));
        bytes=response();bytes[3]=(byte)0x83;assertEquals("",DiscoveryDns.answer(bytes,"192.168.50.21",1234));
    }
    @Test public void presenceWithoutTcpAndNamesAreMergedWithoutInventingIdentity() {
        NetworkEvidence.Results results=new NetworkEvidence.Results();
        results.add(new NetworkEvidence.Entry("192.168.50.21",0,"",NetworkEvidence.Kind.PRESENCE,NetworkEvidence.Proof.ECHO,"",""));
        assertEquals(1,results.size());assertEquals("",results.list().get(0).name());
        results.add(new NetworkEvidence.Entry("192.168.50.21",0,"tablet.local",NetworkEvidence.Kind.PRESENCE,NetworkEvidence.Proof.PTR,"",""));
        assertEquals(1,results.size());assertEquals("tablet.local",results.list().get(0).name());
        assertEquals("",results.list().get(0).facts.get(0).identity);
    }
}
