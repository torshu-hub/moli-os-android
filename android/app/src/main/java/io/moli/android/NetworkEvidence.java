package io.moli.android;

import java.net.*;
import java.util.*;

/** Reused code39 facts/scope rules, grouped by IP. Nothing here is persisted. */
final class NetworkEvidence {
    enum Kind { IPP, HTTP, CAST, UPNP, IGD, TCP, PRESENCE }
    enum Proof { DESCRIPTION, ANNOUNCEMENT, OPEN_PORT, ECHO, PTR }
    static final class Entry {
        final String ip,name,path,location;
        String manufacturer="",model="",identity="",deviceType="",dnsName="";
        final int port;
        final Kind kind;
        final Proof proof;
        Entry(String ip,int port,String name,Kind kind,Proof proof,String path,String location) {
            if(!privateIp(ipv4(ip))||port<0||port>65535||(port==0&&(kind!=Kind.PRESENCE||(proof!=Proof.ECHO&&proof!=Proof.PTR))))throw new IllegalArgumentException("endpoint");
            this.ip=ip;this.port=port;this.name=safeName(name);this.kind=kind;this.proof=proof;
            this.path=path==null?"":path;this.location=location==null?"":location;
        }
        String id(){return port+":"+kind+":"+proof;}
        String description(){return String.join(" · ",java.util.stream.Stream.of(manufacturer,model).filter(v->!v.isEmpty()).toArray(String[]::new));}
    }
    static final class Device {
        final String ip;
        final List<Entry> facts;
        Device(String ip,Collection<Entry> facts){this.ip=ip;this.facts=new ArrayList<>(facts);}
        String name(){
            return facts.stream().filter(e->!e.name.isEmpty()).min(Comparator.comparingInt(e->e.proof.ordinal())).map(e->e.name).orElse("");
        }
    }
    static String safeName(String value) {
        if(value==null||value.length()>160||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))return "";
        return value.trim();
    }
    static Entry mdns(String ip,int port,String name,String type,byte[] rp) {
        if(!privateIp(ipv4(ip))||port<1||port>65535||type==null)return null;
        String normalized=type.endsWith(".")?type.substring(0,type.length()-1):type;
        Kind kind=normalized.equals("_ipp._tcp")?Kind.IPP:normalized.equals("_http._tcp")?Kind.HTTP:normalized.equals("_googlecast._tcp")?Kind.CAST:null;
        if(kind==null && java.util.Set.of("_ipps._tcp","_airplay._tcp","_raop._tcp","_androidtvremote2._tcp").contains(normalized))kind=Kind.PRESENCE;
        if(kind==null)return null;
        return new Entry(ip,port,name,kind,Proof.ANNOUNCEMENT,"","");
    }
    /** Same-device literal IPv4 only: no DNS, credentials, queries or redirects. */
    static Entry ssdp(String source,String packet) {
        if(!privateIp(ipv4(source))||packet==null||packet.length()>4096)return null;
        String[] lines=packet.split("\r?\n");
        if(lines.length==0||!lines[0].equals("HTTP/1.1 200 OK"))return null;
        String location=null;
        for(String line:lines){
            int colon=line.indexOf(':');
            if(colon>0&&line.substring(0,colon).equalsIgnoreCase("location")){
                if(location!=null)return null;location=line.substring(colon+1).trim();
            }
        }
        if(location==null||location.length()>320)return null;
        try {
            URI uri=new URI(location);int port=uri.getPort()==-1?80:uri.getPort();String path=uri.getRawPath();
            if(!"http".equals(uri.getScheme())||!source.equals(uri.getHost())||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||port<1||port>65535||!safePath(path))return null;
            return new Entry(source,port,"",Kind.UPNP,Proof.ANNOUNCEMENT,path,location);
        }catch(URISyntaxException invalid){return null;}
    }
    static boolean safePath(String path){
        if(path==null||path.length()>256||!path.matches("/[A-Za-z0-9/._-]*")||path.startsWith("//"))return false;
        for(String part:path.split("/"))if(part.equals(".")||part.equals(".."))return false;
        return true;
    }
    static long ipv4(String value) {
        if(value==null||!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return -1;
        long n=0;
        for(String part:value.split("\\.")){int b=Integer.parseInt(part);if(b>255||!part.equals(String.valueOf(b)))return -1;n=(n<<8)|b;}return n;
    }
    static String address(long value){return ((value>>>24)&255)+"."+((value>>>16)&255)+"."+((value>>>8)&255)+"."+(value&255);}
    static boolean privateIp(long ip){return ip>=0&&((ip>>>24)==10||(ip>>>20)==0xac1||(ip>>>16)==0xc0a8);}
    static final class Scope {
        final long own,mask,first,last;
        final int prefix;
        Scope(String address,int prefix){
            own=ipv4(address);if(!privateIp(own)||prefix<8||prefix>32)throw new IllegalArgumentException("scope");
            this.prefix=prefix;mask=(0xffffffffL<<(32-prefix))&0xffffffffL;
            first=(own&mask)+(prefix<=30?1:0);last=((own&mask)|(~mask&0xffffffffL))-(prefix<=30?1:0);
        }
        boolean contains(String address){long ip=ipv4(address);return privateIp(ip)&&ip>=first&&ip<=last&&ip!=own;}
        boolean probesLimited(){return prefix<24||prefix==32;}
        List<String> probeHosts(){
            if(probesLimited())return Collections.emptyList();
            List<String> hosts=new ArrayList<>();for(long ip=first;ip<=last;ip++)if(ip!=own&&privateIp(ip))hosts.add(address(ip));return hosts;
        }
    }
    static final class Results {
        private final LinkedHashMap<String,LinkedHashMap<String,Entry>> devices=new LinkedHashMap<>();
        boolean limited;
        synchronized boolean add(Entry entry){
            LinkedHashMap<String,Entry> facts=devices.get(entry.ip);
            if(facts==null){if(devices.size()>=128){limited=true;return false;}facts=new LinkedHashMap<>();devices.put(entry.ip,facts);}
            Entry old=facts.get(entry.id());
            if(old!=null&&old.name.equals(entry.name)&&old.location.equals(entry.location)&&old.description().equals(entry.description())&&old.identity.equals(entry.identity)&&old.dnsName.equals(entry.dnsName))return false;
            if(old==null&&facts.size()>=12){limited=true;return false;}
            facts.put(entry.id(),entry);return true;
        }
        synchronized List<Device> list(){
            List<Device> list=new ArrayList<>();devices.forEach((ip,facts)->list.add(new Device(ip,facts.values())));
            list.sort(Comparator.comparingLong(d->ipv4(d.ip)));return list;
        }
        synchronized int size(){return devices.size();}
        synchronized void clear(){devices.clear();limited=false;}
    }
    private NetworkEvidence(){}
}
