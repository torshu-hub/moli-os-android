package io.moli.android;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
/** Single entry for voluntary foreground discovery; no automatic network action on navigation. */
public final class SearchHomeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TrialLog.begin(this);
        LinearLayout body=TrialUi.page(this);TrialUi.title(this,body,R.string.preview_search);
        LinearLayout card=TrialUi.card(this,body);TrialUi.text(this,card,R.string.auto_meross_help);
        TrialUi.button(this,card,R.string.auto_meross_title,true,()->startActivity(new Intent(this,MerossAccountActivity.class)));
        LinearLayout other=TrialUi.card(this,body);TrialUi.text(this,other,R.string.preview_other_help);
        TrialUi.button(this,other,R.string.preview_other,false,()->startActivity(new Intent(this,LocalNetworkActivity.class)));
        TrialUi.button(this,body,R.string.back,false,this::finish);
    }
}
