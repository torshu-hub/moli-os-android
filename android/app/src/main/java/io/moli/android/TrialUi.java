package io.moli.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;

/** Small platform-only layout vocabulary for the preview's single-task screens. */
final class TrialUi {
    static final int INK=Color.rgb(28,47,55), TEAL=Color.rgb(17,100,105);
    static int dp(Activity a,int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    static LinearLayout page(Activity a){
        a.getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        a.getWindow().setStatusBarColor(Color.rgb(240,245,244));a.getWindow().setNavigationBarColor(Color.rgb(240,245,244));
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(240,245,244));
        FrameLayout frame=new FrameLayout(a);scroll.addView(frame);
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);int p=dp(a,24);body.setPadding(p,p,p,p);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.min(a.getResources().getDisplayMetrics().widthPixels,dp(a,840)),-2);lp.gravity=android.view.Gravity.TOP|android.view.Gravity.CENTER_HORIZONTAL;
        frame.addView(body,lp);a.setContentView(scroll);return body;
    }
    static TextView text(Activity a,LinearLayout parent,int key){return text(a,parent,a.getString(key),16);}
    static TextView text(Activity a,LinearLayout parent,String value,int size){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(INK);t.setPadding(0,dp(a,6),0,dp(a,10));parent.addView(t);return t;}
    static TextView title(Activity a,LinearLayout parent,int key){TextView t=text(a,parent,a.getString(key),28);t.setTypeface(null,Typeface.BOLD);return t;}
    static LinearLayout card(Activity a,LinearLayout parent){LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);int p=dp(a,18);box.setPadding(p,p,p,p);GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(a,18));box.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(a,8),0,dp(a,10));parent.addView(box,lp);return box;}
    static Button button(Activity a,LinearLayout parent,int key,boolean primary,Runnable action){Button b=new Button(a);b.setText(key);b.setAllCaps(false);b.setTextSize(17);b.setMinHeight(dp(a,54));GradientDrawable bg=new GradientDrawable();bg.setColor(primary?TEAL:Color.rgb(225,237,236));bg.setCornerRadius(dp(a,12));b.setBackground(bg);b.setTextColor(primary?Color.WHITE:INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(a,6),0,dp(a,8));parent.addView(b,lp);b.setOnClickListener(v->action.run());return b;}
    private TrialUi(){}
}
