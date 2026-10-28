package io.moli.android;

import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Invented credentials and loopback HTTP only, never a device or provider. */
public final class DeviceLinkConnectionTest {
    private static final String TOKEN="a".repeat(64),HUMAN="b".repeat(64);
    @Test public void nativeHumanSessionStaysPrivateForRuleActivation()throws Exception {
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(5000);ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> responder=worker.submit(()->{
                String[] paths={"GET "+DeviceLinkConnection.PATH,"POST /api/session","PUT "+DeviceLinkConnection.PATH,"DELETE /api/session"};
                try{for(int i=0;i<paths.length;i++)try(Socket socket=server.accept()){
                    socket.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    assertEquals(paths[i]+" HTTP/1.1",reader.readLine());int length=0;String cookie="",line;
                    while(!(line=reader.readLine()).isEmpty()){String lower=line.toLowerCase(java.util.Locale.ROOT);if(lower.startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());if(lower.startsWith("cookie:"))cookie=line.substring(line.indexOf(':')+1).trim();}
                    assertEquals("moli_android="+TOKEN+((i==2||i==3)?"; moli_session="+HUMAN:""),cookie);
                    char[] chars=new char[length];int read=0;while(read<length){int n=reader.read(chars,read,length-read);assertTrue(n>0);read+=n;}
                    String body=new String(chars);if(i==1)assertEquals("654321",new JSONObject(body).getString("pin"));
                    if(i==2){JSONObject input=new JSONObject(body);assertEquals(true,input.getBoolean("enabled"));assertFalse(input.has("point"));assertFalse(input.has("confirmed"));}
                    String set=i==1?"Set-Cookie: moli_session="+HUMAN+"; HttpOnly; SameSite=Strict; Path=/\r\n":"";
                    byte[] payload="{}".getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n"+set+"Content-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(payload);
                }}catch(Exception failure){throw new RuntimeException(failure);}
            });
            try{DeviceLinkConnection client=new DeviceLinkConnection("http://127.0.0.1:"+server.getLocalPort(),TOKEN);client.read();client.login("654321");client.save(new JSONObject().put("source","synthetic:thermostat").put("target","synthetic:outlet").put("enabled",true));client.logout();responder.get(8,TimeUnit.SECONDS);}finally{worker.shutdownNow();}
        }
    }
    @Test public void rejectsExternalOrMalformedOriginsWithoutNetwork()throws Exception {
        for(String origin:new String[]{"https://127.0.0.1:1234","http://example.com:1234","http://127.0.0.1:1234/path","http://127.0.0.1:1234?query"}){
            try{new DeviceLinkConnection(origin,TOKEN);fail(origin);}catch(IOException expected){}
        }
    }
}
