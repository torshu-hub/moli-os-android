package io.moli.android;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
/** Necessary maintenance lives here, outside the normal trial path. */
public final class PreviewSettingsActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);LinearLayout body=TrialUi.page(this);TrialUi.title(this,body,R.string.preview_settings);
        LinearLayout box=TrialUi.card(this,body);TrialUi.text(this,box,R.string.preview_transfer_help);
        TrialUi.button(this,box,R.string.backup_title,false,()->stopThen(()->startActivity(new Intent(this,BackupActivity.class))));
        TrialUi.button(this,body,R.string.preview_advanced,false,()->new AlertDialog.Builder(this).setTitle(R.string.preview_advanced)
            .setItems(new String[]{getString(R.string.home_settings),getString(R.string.integration_title),getString(R.string.app_info),getString(R.string.equipment_manual),getString(R.string.stop)},(d,w)->{
                if(w==4){startService(new Intent(this,MoliService.class).setAction(MoliService.STOP));return;}
                startActivity(new Intent(this,w==0?HomeSettingsActivity.class:w==1?IntegrationsActivity.class:w==2?AppInfoActivity.class:EquipmentSettingsActivity.class));
            }).show());
        TrialUi.button(this,body,R.string.back,false,this::finish);
    }
    private void stopThen(Runnable next){if(EngineHost.state()==EngineHost.State.STOPPED){next.run();return;}
        new AlertDialog.Builder(this).setMessage(R.string.auto_stop_help).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.auto_stop,(d,w)->{
            getSharedPreferences("host",MODE_PRIVATE).edit().putBoolean("wanted",false).apply();startService(new Intent(this,MoliService.class).setAction(MoliService.STOP));
            Handler h=new Handler(getMainLooper());h.post(new Runnable(){int left=100;public void run(){if(isDestroyed())return;if(EngineHost.state()==EngineHost.State.STOPPED)next.run();else if(--left>0)h.postDelayed(this,200);}});
        }).show();
    }
}
