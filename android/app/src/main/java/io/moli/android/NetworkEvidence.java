package io.moli.android;

import java.net.*;
import java.util.*;

/** Bounded, ephemeral discovery facts; a port is never proof of a device model. */
final class NetworkEvidence {
    enum Kind { IPP, HTTP, CAST, UPNP, IGD }
    enum Proof { DESCRIPTION, ANNOUNCEMENT, OPEN_PORT }
    static final class Entry {
        final String ip, name, path, location;
        final int port;
        final Kind kind;
        final Proof proof;
        Entry(String ip,int port,String name,Kind kind,Proof proof,String path,String location) {
            this.ip=ip; this.port=port; this.name=safeName(name); this.kind=kind; this.proof=proof;
            this.path=ippPath(path); this.location=location==null?"":location;
        }
        String id() { return ip+":"+port+":"+(kind==Kind.IGD?Kind.UPNP:kind); }
    }
    static String safeName(String value) {
        if(value==null || value.length()>160 || value.codePoints().anyMatch(Character::isISOControl)) return "";
        return value;
    }
    static Entry mdns(String ip,int port,String name,String type,byte[] rp) {
        if(!privateIp(ipv4(ip)) || port<1 || port>65535 || type==null) return null;
        String normalized=type.endsWith(".")?type.substring(0,type.length()-1):type;
        Kind kind=normalized.equals("_ipp._tcp")?Kind.IPP:normalized.equals("_http._tcp")?Kind.HTTP:normalized.equals("_googlecast._tcp")?Kind.CAST:null;
        if(kind==null) return null;
        String path=rp==null || rp.length>256?"":new String(rp,java.nio.charset.StandardCharsets.UTF_8);
        return new Entry(ip,port,name,kind,Proof.ANNOUNCEMENT,path,"");
    }
    static String ippPath(String value) {
        if(value==null || value.length()>256) return "";
        String p=value.startsWith("/")?value:"/"+value;
        if(!p.matches("/[A-Za-z0-9/._-]+") || p.contains("//")) return "";
        for(String part:p.split("/")) if(part.equals(".")||part.equals("..")) return "";
        if(p.equals("/ipp") || p.equals("/ipp/print") || p.equals("/ipp/printer") || p.matches("/printers/[A-Za-z0-9._-]+")) return p;
        return "";
    }
    static long ipv4(String value) {
        if(value==null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return -1;
        long n=0;
        for(String part:value.split("\\.")) {
            int b=Integer.parseInt(part); if(b>255 || !part.equals(String.valueOf(b))) return -1;
            n=(n<<8)|b;
        }
        return n;
    }
    static String address(long value) {
        return ((value>>>24)&255)+"."+((value>>>16)&255)+"."+((value>>>8)&255)+"."+(value&255);
    }
    static boolean privateIp(long ip) {
        return ip>=0 && ((ip>>>24)==10 || (ip>>>20)==0xac1 || (ip>>>16)==0xc0a8);
    }
    static final class Scope {
        final long own, mask, first, last;
        final int prefix;
        Scope(String address,int prefix) {
            own=ipv4(address);
            if(!privateIp(own) || prefix<8 || prefix>30) throw new IllegalArgumentException("scope");
            this.prefix=prefix; mask=(0xffffffffL << (32-prefix))&0xffffffffL;
            first=(own&mask)+1; last=((own&mask)|(~mask&0xffffffffL))-1;
        }
        boolean contains(String address) {
            long ip=ipv4(address);
            return privateIp(ip) && ip>=first && ip<=last && ip!=own;
        }
        List<String> probeHosts() {
            // Never truncate a large subnet silently or assume the Wi-Fi is /24.
            if(prefix<24) return Collections.emptyList();
            ArrayList<String> result=new ArrayList<>();
            for(long ip=first;ip<=last;ip++) if(ip!=own && privateIp(ip)) result.add(address(ip));
            return result;
        }
    }
    static final class Results {
        private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
        synchronized boolean add(Entry entry) {
            Entry before=entries.get(entry.id());
            if(before==null && entries.size()>=128) return false;
            if(before!=null && before.proof.ordinal()<entry.proof.ordinal()) return false;
            if(before!=null && before.proof==entry.proof && before.name.equals(entry.name) && before.path.equals(entry.path) && before.location.equals(entry.location)) return false;
            entries.put(entry.id(),entry); return true;
        }
        synchronized List<Entry> list() { return new ArrayList<>(entries.values()); }
        synchronized void clear() { entries.clear(); }
    }
    private NetworkEvidence() {}
}
