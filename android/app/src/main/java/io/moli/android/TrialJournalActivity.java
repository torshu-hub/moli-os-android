package io.moli.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.util.concurrent.*;

/** One explicit trial session shared by every screen; export through the system document picker only. */
public final class TrialJournalActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    TextView status,message; Button begin,end;
    private final Runnable refresh=new Runnable(){public void run(){
        TrialLog.attach(TrialJournalActivity.this);boolean active=TrialLog.active();begin.setEnabled(!active);end.setEnabled(active);
        status.setText(getString(active?R.string.trial_active:R.string.trial_inactive,TrialLog.count()));handler.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,24,24,24);ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll);
        status=new TextView(this);body.addView(status);message=new TextView(this);message.setText(R.string.journal_help);body.addView(message);
        begin=button(body,R.string.trial_begin,() -> {if(TrialLog.count()==0)TrialLog.begin(this);else new AlertDialog.Builder(this).setMessage(R.string.journal_replace).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.trial_begin,(d,w)->TrialLog.begin(this)).show();});
        end=button(body,R.string.trial_end,()->TrialLog.end(this));
        button(body,R.string.trial_export,()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,ReportExport.fileName(System.currentTimeMillis(),BuildConfig.VERSION_NAME)),72));
        button(body,R.string.back,this::finish);
    }
    private Button button(LinearLayout body,int label,Runnable task){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->task.run());body.addView(b);return b;}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request!=72||result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();io.execute(()->{boolean ok=false;try{
            if(!"content".equals(uri.getScheme()))throw new java.io.IOException();
            String journal=TrialLog.export(this);try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(journal.getBytes(java.nio.charset.StandardCharsets.UTF_8));}ok=true;
        }catch(Exception ignored){}boolean saved=ok;runOnUiThread(()->{if(!isDestroyed())message.setText(saved?R.string.trial_exported:R.string.trial_export_failed);});});
    }
    @Override public void onStart(){super.onStart();handler.post(refresh);}
    @Override public void onStop(){handler.removeCallbacks(refresh);super.onStop();}
    @Override public void onDestroy(){io.shutdown();super.onDestroy();}
}
