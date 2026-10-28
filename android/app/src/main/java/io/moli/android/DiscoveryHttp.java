package io.moli.android;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Narrow HTTP GET over an already Wi-Fi-bound socket; no global cleartext/WebView policy change. */
final class DiscoveryHttp {
    static final int LIMIT=32768;
    static String description(Socket socket,String host,int port,String path) throws IOException {
        if(!NetworkEvidence.privateIp(NetworkEvidence.ipv4(host)) || port<1 || port>65535 || path.length()>256 || !path.matches("/[A-Za-z0-9/._-]+") || !path.toLowerCase(Locale.ROOT).endsWith(".xml")) throw new IOException("endpoint");
        for(String part:path.split("/")) if(part.equals(".") || part.equals("..")) throw new IOException("endpoint");
        OutputStream out=socket.getOutputStream();
        out.write(("GET "+path+" HTTP/1.1\r\nHost: "+host+":"+port+"\r\nAccept: text/xml, application/xml\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
        InputStream in=new BufferedInputStream(socket.getInputStream());
        String status=line(in,512); if(!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException("status");
        int headerBytes=status.length(),length=-1; boolean chunked=false;
        while(true) {
            String h=line(in,8192); headerBytes+=h.length()+2; if(headerBytes>8192) throw new IOException("headers");
            if(h.isEmpty()) break;
            int colon=h.indexOf(':'); if(colon<1) throw new IOException("headers");
            String name=h.substring(0,colon).toLowerCase(Locale.ROOT),value=h.substring(colon+1).trim();
            if(name.equals("content-length")) {
                if(length!=-1 || !value.matches("[0-9]{1,5}")) throw new IOException("length");
                length=Integer.parseInt(value); if(length>LIMIT) throw new IOException("length");
            } else if(name.equals("transfer-encoding")) {
                if(chunked || !value.equalsIgnoreCase("chunked")) throw new IOException("encoding"); chunked=true;
            } else if(name.equals("content-encoding") && !value.equalsIgnoreCase("identity")) throw new IOException("encoding");
        }
        if(chunked && length!=-1) throw new IOException("framing");
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        if(chunked) {
            while(true) {
                String size=line(in,64).split(";",2)[0];
                if(!size.matches("[0-9a-fA-F]{1,5}")) throw new IOException("chunk");
                int n=Integer.parseInt(size,16); if(n==0) break;
                if(n>LIMIT-body.size()) throw new IOException("length");
                exact(in,body,n); if(!line(in,2).isEmpty()) throw new IOException("chunk");
            }
        } else if(length>=0) exact(in,body,length);
        else {
            byte[] bytes=new byte[2048]; int n;
            while((n=in.read(bytes))!=-1) { if(n>LIMIT-body.size()) throw new IOException("length"); body.write(bytes,0,n); }
        }
        return body.toString(StandardCharsets.UTF_8.name());
    }
    private static void exact(InputStream in,ByteArrayOutputStream body,int length) throws IOException {
        byte[] bytes=new byte[Math.min(2048,Math.max(1,length))];
        while(length>0) { int n=in.read(bytes,0,Math.min(bytes.length,length)); if(n<0) throw new EOFException(); body.write(bytes,0,n); length-=n; }
    }
    private static String line(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        while(bytes.size()<=limit) {
            int c=in.read(); if(c<0) throw new EOFException();
            if(c=='\r') { if(in.read()!='\n') throw new IOException("line"); return bytes.toString(StandardCharsets.US_ASCII.name()); }
            if(c=='\n') throw new IOException("line"); bytes.write(c);
        }
        throw new IOException("line");
    }
    private DiscoveryHttp() {}
}
