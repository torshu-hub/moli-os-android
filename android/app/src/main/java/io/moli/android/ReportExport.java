package io.moli.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.text.SimpleDateFormat;
import java.util.*;

/** Voluntary SAF export of the fixed-schema redacted journal; never a backup or an upload. */
final class ReportExport {
    static final int REQUEST=73;
    private final Activity activity;
    ReportExport(Activity a){activity=a;}
    static String fileName(long now,String version){
        String clean=version.replaceFirst("-.*$","");
        if(!clean.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))clean="unknown";
        return "Rapport-essai-Moli-OS_"+new SimpleDateFormat("yyyy-MM-dd_HH-mm",Locale.ROOT).format(new Date(now))+"_v"+clean+".txt";
    }
    void choose(){
        TrialLog.begin(activity);
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,fileName(System.currentTimeMillis(),BuildConfig.VERSION_NAME));
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));
        try{activity.startActivityForResult(intent,REQUEST);}catch(ActivityNotFoundException ignored){showFailure();}
    }
    void result(int request,int result,Intent data){
        if(request!=REQUEST || result!=Activity.RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        new Thread(()->{
            boolean ok=false;String name=activity.getString(R.string.report_fallback_name);
            try{
                if(!"content".equals(uri.getScheme()))throw new java.io.IOException();
                try(java.io.OutputStream out=activity.getContentResolver().openOutputStream(uri,"wt")){
                    if(out==null)throw new java.io.IOException();out.write(TrialLog.export(activity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                try(android.database.Cursor c=activity.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
                    if(c!=null && c.moveToFirst()){String n=c.getString(0);if(n!=null && n.length()<256)name=n;}
                }
                ok=true;
            }catch(Exception ignored){}
            boolean success=ok;String display=name;
            activity.runOnUiThread(()->{if(activity.isDestroyed())return;if(!success){showFailure();return;}
                new AlertDialog.Builder(activity).setTitle(R.string.report_saved).setMessage(activity.getString(R.string.report_saved_help,display))
                    .setPositiveButton(android.R.string.ok,null).setNeutralButton(R.string.report_share,(d,w)->{
                        Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri)
                            ;
                        send.setClipData(ClipData.newUri(activity.getContentResolver(),display,uri));send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try{activity.startActivity(Intent.createChooser(send,activity.getString(R.string.report_share)));}catch(ActivityNotFoundException ignored){showFailure();}
                    }).show();
            });
        },"moli-report-export").start();
    }
    private void showFailure(){new AlertDialog.Builder(activity).setMessage(R.string.trial_export_failed).setPositiveButton(android.R.string.ok,null).show();}
}
