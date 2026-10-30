package io.moli.android;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.*;

/** Native APK space selector. Never replaces or paints the official dashboard. */
final class ApkHome {
    private static int dp(Activity a,int n) {return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private static TextView text(Activity a,LinearLayout parent,int label,int size,boolean bold) {
        TextView view=new TextView(a);view.setText(label);view.setTextSize(size);
        view.setTextColor(a.getColor(R.color.management_ink));if(bold)view.setTypeface(null,Typeface.BOLD);
        view.setPadding(0,dp(a,8),0,dp(a,8));parent.addView(view);return view;
    }
    static LinearLayout create(Activity a,Runnable moli,Runnable management) {
        LinearLayout root=new LinearLayout(a);root.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout content=new LinearLayout(a);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(a,20),dp(a,24),dp(a,20),dp(a,24));scroll.addView(content);
        text(a,content,R.string.apk_home,30,true).setAccessibilityHeading(true);
        text(a,content,R.string.app_name,16,false).setTextColor(a.getColor(R.color.management_muted));
        boolean landscape=a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout cards=new LinearLayout(a);cards.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);content.addView(cards);
        card(a,cards,landscape,R.string.moli_os,R.string.official_dashboard,R.drawable.ic_management_moli,R.id.open_moli,moli);
        card(a,cards,landscape,R.string.management,R.string.android_tools,R.drawable.ic_management_integrations,R.id.open_management,management);
        return root;
    }
    private static void card(Activity a,LinearLayout parent,boolean horizontal,int label,int description,int icon,int id,Runnable action) {
        LinearLayout card=new LinearLayout(a);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(a,24),dp(a,24),dp(a,24),dp(a,24));
        GradientDrawable shape=new GradientDrawable();shape.setColor(a.getColor(R.color.management_card));shape.setCornerRadius(dp(a,18));shape.setStroke(dp(a,1),a.getColor(R.color.management_border));card.setBackground(shape);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(horizontal ? 0 : -1,-2,horizontal ? 1 : 0);layout.setMargins(dp(a,6),dp(a,24),dp(a,6),0);parent.addView(card,layout);
        ImageView image=new ImageView(a);image.setImageResource(icon);image.setColorFilter(a.getColor(R.color.management_blue));image.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);card.addView(image,new LinearLayout.LayoutParams(dp(a,36),dp(a,36)));
        text(a,card,label,22,true);text(a,card,description,16,false).setTextColor(a.getColor(R.color.management_muted));
        Button open=new Button(a);open.setId(id);open.setText(label);open.setAllCaps(false);open.setMinHeight(dp(a,52));open.setGravity(Gravity.CENTER);
        open.setBackgroundResource(R.drawable.management_button_primary);open.setTextColor(a.getColorStateList(R.color.management_primary_text));
        LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(-1,-2);button.topMargin=dp(a,20);card.addView(open,button);open.setOnClickListener(v->action.run());
    }
    private ApkHome() {}
}
