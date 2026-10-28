package io.moli.android;

import android.content.*;
import android.os.BatteryManager;
import android.net.*;
import org.json.JSONObject;

/** Coarse OS observations only: no GPS, SSID, IP, hardware identifier or credentials. */
final class TabletReading {
    private TabletReading() {}
    static JSONObject json(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("id", "tablet");
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) out.put("battery", Math.min(100, 100.0 * level / scale));
                out.put("charging", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            }
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities cap = cm.getNetworkCapabilities(cm.getActiveNetwork());
            String network = cap == null ? "offline" : cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet" : "other";
            out.put("network", network);
        } catch (Exception ignored) { /* Missing observations stay absent, never fake success. */ }
        return out;
    }
}
