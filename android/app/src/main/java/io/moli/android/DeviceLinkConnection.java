package io.moli.android;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Authenticated loopback only. Human cookie never enters the WebView or logs. */
final class DeviceLinkConnection {
    static final String PATH="/api/device-link";
    private final String origin,token;
    private String human="";
    DeviceLinkConnection(String origin,String token)throws Exception {
        URL url=new URL(origin);
        if(!origin.equals("http://127.0.0.1:"+url.getPort())||!"http".equals(url.getProtocol())||!"127.0.0.1".equals(url.getHost())||url.getPort()<1||!url.getPath().isEmpty()||token==null||!token.matches("[a-f0-9]{64}"))throw new IOException("local_connection");
        this.origin=origin;this.token=token;
    }
    static final class Refused extends IOException { Refused(String reason){super(reason);} }
    JSONObject read()throws Exception{return request("GET",PATH,null);}
    JSONObject save(JSONObject input)throws Exception{return request("PUT",PATH,input);}
    JSONObject refresh(JSONObject input)throws Exception{return request("POST",PATH+"/refresh",input);}
    void login(String pin)throws Exception {
        // Only following an explicit configuration/activation gesture.
        request("POST","/api/session",new JSONObject().put("pin",pin));
        if(human.isEmpty())throw new IOException("human_session");
    }
    void logout(){try{if(!human.isEmpty())request("DELETE","/api/session",null);}catch(Exception ignored){}finally{human="";}}
    private JSONObject request(String method,String path,JSONObject input)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(origin+path).openConnection();
        c.setConnectTimeout(2000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestMethod(method);
        c.setRequestProperty("Cookie","moli_android="+token+(human.isEmpty()?"":"; "+human));
        c.setRequestProperty("X-Moli-Origin","ui");c.setRequestProperty("Cache-Control","no-store");
        try {
            if(input!=null){byte[] bytes=input.toString().getBytes(StandardCharsets.UTF_8);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            if(c.getResponseCode()!=200){
                String reason="transport";
                try(InputStream in=c.getErrorStream()){if(in!=null)reason=new JSONObject(ProfileStore.bounded(in,4096)).optString("reason","transport");}catch(Exception ignored){}
                if(path.equals("/api/session"))reason="human_session";
                throw new Refused(reason);
            }
            if(path.equals("/api/session")&&method.equals("POST")){
                String set=c.getHeaderField("Set-Cookie");
                if(set!=null&&set.matches("moli_session=[a-f0-9]{64};.*"))human=set.substring(0,set.indexOf(';'));
            }
            try(InputStream in=c.getInputStream()){return new JSONObject(ProfileStore.bounded(in,65536));}
        } finally {c.disconnect();}
    }
}
