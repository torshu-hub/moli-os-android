package io.moli.android;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.security.MessageDigest;
import java.util.Locale;

/** Public installation metadata only; never reads the Moli key or signing private key. */
public final class AppInfoActivity extends Activity {
    private TextView identity, background;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        identity = new TextView(this); identity.setTextIsSelectable(true); content.addView(identity);
        TextView instructions = new TextView(this); instructions.setText(R.string.update_help); content.addView(instructions);
        background = new TextView(this); content.addView(background);
        Button settings = new Button(this); settings.setText(R.string.app_settings);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))); content.addView(settings);
        Button back = new Button(this); back.setText(R.string.back); back.setOnClickListener(v -> finish()); content.addView(back);
        setContentView(scroll);
    }

    @Override public void onResume() {
        super.onResume();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
            StringBuilder fingerprint = new StringBuilder();
            for (byte b : digest) fingerprint.append(String.format(Locale.ROOT, "%02X", b & 255));
            identity.setText(getString(R.string.app_identity, info.versionName, info.getLongVersionCode(), getPackageName(), fingerprint.toString()));
        } catch (Exception failure) { identity.setText(R.string.app_identity_unavailable); }
        boolean notifications = getSystemService(NotificationManager.class).areNotificationsEnabled();
        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        background.setText(getString(R.string.background_status,
                getString(notifications ? R.string.notifications_allowed : R.string.notifications_blocked),
                getString(unrestricted ? R.string.battery_exempt : R.string.battery_optimized)));
    }
}
