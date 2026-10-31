package io.moli.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Bounded PTR wire format. DNS names are labels, never device identities. */
final class DiscoveryDns {
    static String reverse(String ip) {
        if(!NetworkEvidence.privateIp(NetworkEvidence.ipv4(ip)))throw new IllegalArgumentException();
        String[] p=ip.split("\\.");return p[3]+"."+p[2]+"."+p[1]+"."+p[0]+".in-addr.arpa";
    }
    static byte[] query(String ip,int id) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(id>>>8);out.write(id);out.write(1);out.write(0);out.write(0);out.write(1);
        for(int i=0;i<6;i++)out.write(0);
        for(String label:reverse(ip).split("\\.")){byte[] bytes=label.getBytes(StandardCharsets.US_ASCII);out.write(bytes.length);out.write(bytes,0,bytes.length);}
        out.write(0);out.write(0);out.write(12);out.write(0);out.write(1);return out.toByteArray();
    }
    private static int u16(byte[] b,int p) {
        if(p<0||p+2>b.length)throw new IllegalArgumentException();return (b[p]&255)*256+(b[p+1]&255);
    }
    private static String name(byte[] b,int[] cursor) {
        int pos=cursor[0],next=-1;StringBuilder result=new StringBuilder();Set<Integer> visited=new HashSet<>();
        for(int n=0;n<128;n++) {
            if(pos>=b.length||!visited.add(pos))throw new IllegalArgumentException();
            int length=b[pos++]&255;
            if(length==0){cursor[0]=next<0?pos:next;return result.toString();}
            if((length&192)==192){if(pos>=b.length)throw new IllegalArgumentException();if(next<0)next=pos+1;pos=((length&63)<<8)|(b[pos]&255);continue;}
            if(length>63||pos+length>b.length||result.length()+length>253)throw new IllegalArgumentException();
            if(result.length()>0)result.append('.');
            for(int i=0;i<length;i++){int c=b[pos++]&255;if(c<33||c>126||c=='.')throw new IllegalArgumentException();result.append((char)c);}
        }
        throw new IllegalArgumentException();
    }
    static String answer(byte[] bytes,String ip,int id) {
        try {
            if(bytes.length<12||bytes.length>4096||u16(bytes,0)!=(id&65535)||(u16(bytes,2)&0xfa0f)!=0x8000||u16(bytes,4)!=1)return "";
            int[] p={12};if(!name(bytes,p).equalsIgnoreCase(reverse(ip))||u16(bytes,p[0])!=12||u16(bytes,p[0]+2)!=1)return "";p[0]+=4;
            int count=u16(bytes,6);if(count>32)return "";
            for(int i=0;i<count;i++) {
                String owner=name(bytes,p);int type=u16(bytes,p[0]),cls=u16(bytes,p[0]+2),length=u16(bytes,p[0]+8);p[0]+=10;
                int end=p[0]+length;if(end>bytes.length)return "";
                if(type==12&&cls==1&&owner.equalsIgnoreCase(reverse(ip))){int[] value={p[0]};String result=name(bytes,value);if(value[0]>end)return "";return NetworkEvidence.safeName(result);}
                p[0]=end;
            }
        }catch(IllegalArgumentException invalid){return "";}
        return "";
    }
    private DiscoveryDns(){}
}
