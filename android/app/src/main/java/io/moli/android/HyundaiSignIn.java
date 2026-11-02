package io.moli.android;

import android.app.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.*;
import android.view.*;
import android.webkit.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Issuer pages only: credentials/terms/verification remain inside Hyundai, no JS bridge. */
final class HyundaiSignIn {
    interface Completion {void finish(String callback,String failure);}
    static boolean allowed(Uri u){return u!=null&&"https".equalsIgnoreCase(u.getScheme())&&(u.getPort()==-1||u.getPort()==443)&&u.getUserInfo()==null&&u.getFragment()==null&&u.toString().length()<=8192&&Arrays.asList("idpconnect-eu.hyundai.com","oneapp.hyundai.com").contains(u.getHost());}
    static boolean callback(Uri u){return allowed(u)&&"oneapp.hyundai.com".equals(u.getHost())&&"/redirect".equals(u.getPath());}
    static void show(Activity activity,String url,Completion completion){
        Uri initial=Uri.parse(url);
        if(!allowed(initial)||!"idpconnect-eu.hyundai.com".equals(initial.getHost())){completion.finish(null,"redirect_not_allowed");return;}
        WebView web=new WebView(activity);web.setSaveEnabled(false);web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setSafeBrowsingEnabled(true);settings.setUserAgentString(settings.getUserAgentString()+"_CCS_APP_AOS");
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        AtomicBoolean finished=new AtomicBoolean();Handler main=new Handler(Looper.getMainLooper());
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.hyundai_browser_title).setView(web).setNegativeButton(android.R.string.cancel,null).create();
        final java.util.function.BiConsumer<String,String> end=(result,failure)->{
            if(!finished.compareAndSet(false,true))return;
            main.post(()->{dialog.dismiss();completion.finish(result,failure);});
        };
        web.setWebViewClient(new WebViewClient(){
            boolean navigation(Uri u){if(!allowed(u)){end.accept(null,"redirect_not_allowed");return true;}if(callback(u)){end.accept(u.toString(),null);return true;}return false;}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return r.isForMainFrame()&&navigation(r.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(r.isForMainFrame()&&navigation(r.getUrl()))return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(new byte[0]));return null;
            }
            @Override public void onPageStarted(WebView v,String address,Bitmap icon){if(navigation(Uri.parse(address)))v.stopLoading();}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,SslError error){handler.cancel();end.accept(null,"tls_failure");}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())end.accept(null,e.getErrorCode()==ERROR_TIMEOUT?"network_timeout":"network");}
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){if(r.isForMainFrame())end.accept(null,"provider_page_failed");}
        });
        web.setDownloadListener((a,b,c,d,e)->end.accept(null,"redirect_not_allowed"));
        dialog.setOnShowListener(d->{dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);web.loadUrl(url);});
        dialog.setOnDismissListener(d->{
            web.stopLoading();web.loadUrl("about:blank");web.clearHistory();web.clearCache(true);web.clearFormData();web.destroy();clearIssuerCookies();
            if(finished.compareAndSet(false,true))completion.finish(null,null);
        });dialog.show();
    }
    private static void clearIssuerCookies(){
        CookieManager cookies=CookieManager.getInstance();
        for(String host:Arrays.asList("idpconnect-eu.hyundai.com","oneapp.hyundai.com"))for(String path:Arrays.asList("/","/auth","/auth/account","/auth/api","/web","/web/v1/user/authorization")){
            String existing=cookies.getCookie("https://"+host+path);if(existing==null)continue;
            for(String cookie:existing.split(";")){String name=cookie.trim().split("=",2)[0];if(!name.matches("[A-Za-z0-9_.-]{1,80}"))continue;
                String expiry=name+"=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; Path="+path;
                cookies.setCookie("https://"+host+path,expiry);cookies.setCookie("https://"+host+path,expiry+"; Domain="+host);cookies.setCookie("https://"+host+path,expiry+"; Domain=.hyundai.com");
            }
        }
    }
}
