package io.moli.android;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.json.*;

/** Existing code19 REST transport, without an Activity or rule state. */
final class AutomationRest {
    private final String origin,cookies;
    AutomationRest(String origin,String token,String savedCookies) {
        this.origin=origin;
        String value="moli_android="+token;
        if(savedCookies!=null)for(String cookie:savedCookies.split(";"))if(cookie.trim().startsWith("moli_session="))value+="; "+cookie.trim();
        this.cookies=value;
    }
    static class RequestFailure extends IOException {
        final int status;
        RequestFailure(int status){this.status=status;}
    }
    private static final class Conflict extends RequestFailure {Conflict(){super(409);}}
    JSONObject request(String method,String path,JSONObject body,String fingerprint)throws Exception {
        if(!Objects.equals(origin,EngineHost.origin()))throw new IOException();
        HttpURLConnection c=(HttpURLConnection)new URL(origin+path).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestMethod(method);c.setRequestProperty("Cookie",cookies);
        if(fingerprint!=null)c.setRequestProperty("If-Match",fingerprint);
        try{
            if(body!=null){c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();if(status==409)throw new Conflict();if(status!=200)throw new RequestFailure(status);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>2_097_152)throw new IOException();out.write(buffer,0,n);}return new JSONObject(out.toString("UTF-8"));
            }
        }finally{c.disconnect();}
    }
}
