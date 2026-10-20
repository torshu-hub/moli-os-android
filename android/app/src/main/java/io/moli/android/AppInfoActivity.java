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
    private TextView identity, background, health;
    private final android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable renderHealth = new Runnable() {
        @Override public void run() {
            long[] sample = EngineHealth.snapshot();
            health.setText(getString(R.string.engine_diagnostic,
                    getString(sample[0] == 1 ? R.string.engine_confirmed : R.string.engine_unconfirmed),
                    sample[1] < 0 ? getString(R.string.engine_never) : Long.toString(sample[1] / 1000),
                    sample[2], sample[3], sample[4] / 1000,
                    getSharedPreferences("diagnostics", MODE_PRIVATE).getLong("starts", 0)));
            refresh.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        identity = new TextView(this); identity.setTextIsSelectable(true); content.addView(identity);
        TextView instructions = new TextView(this); instructions.setText(R.string.update_help); content.addView(instructions);
        background = new TextView(this); content.addView(background);
        health = new TextView(this); health.setTextIsSelectable(true); content.addView(health);
        Button check = new Button(this); check.setText(R.string.engine_check);
        check.setOnClickListener(v -> EngineHealth.check()); content.addView(check);
        TextView caveat = new TextView(this); caveat.setText(R.string.engine_help); content.addView(caveat);
        Button settings = new Button(this); settings.setText(R.string.app_settings);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))); content.addView(settings);
        Button back = new Button(this); back.setText(R.string.back); back.setOnClickListener(v -> finish()); content.addView(back);
        setContentView(scroll);
    }

    @Override public void onPause() { refresh.removeCallbacks(renderHealth); super.onPause(); }

    @Override public void onResume() {
        super.onResume();
        refresh.removeCallbacks(renderHealth); refresh.post(renderHealth);
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
