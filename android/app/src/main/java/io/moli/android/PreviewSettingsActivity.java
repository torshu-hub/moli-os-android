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
        TrialUi.button(this,body,R.string.home_settings,false,()->startActivity(new Intent(this,HomeSettingsActivity.class)));
        TrialUi.button(this,body,R.string.netatmo_title,false,()->startActivity(new Intent(this,NetatmoOAuthActivity.class)));
        TrialUi.button(this,body,R.string.app_info,false,()->startActivity(new Intent(this,AppInfoActivity.class)));
        LinearLayout expert=TrialUi.card(this,body);expert.setVisibility(android.view.View.GONE);
        TrialUi.text(this,expert,R.string.maintenance_expert_help);
        TrialUi.button(this,expert,R.string.equipment_manual,false,()->startActivity(new Intent(this,EquipmentSettingsActivity.class)));
        TrialUi.button(this,expert,R.string.maintenance_profiles,false,()->stopThen(()->startActivity(new Intent(this,IntegrationsActivity.class).putExtra("maintenance",true))));
        TrialUi.button(this,body,R.string.preview_advanced,false,()->expert.setVisibility(expert.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE));
        // Place the explanation and rare actions immediately after their toggle.
        body.removeView(expert);body.addView(expert);
        TrialUi.button(this,body,R.string.back,false,this::finish);
    }
    private void stopThen(Runnable next){if(EngineHost.state()==EngineHost.State.STOPPED){next.run();return;}
        new AlertDialog.Builder(this).setMessage(R.string.auto_stop_help).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.auto_stop,(d,w)->{
            getSharedPreferences("host",MODE_PRIVATE).edit().putBoolean("wanted",false).apply();startService(new Intent(this,MoliService.class).setAction(MoliService.STOP));
            Handler h=new Handler(getMainLooper());h.post(new Runnable(){int left=100;public void run(){if(isDestroyed())return;if(EngineHost.state()==EngineHost.State.STOPPED)next.run();else if(--left>0)h.postDelayed(this,200);}});
        }).show();
    }
}
