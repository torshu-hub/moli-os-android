package io.moli.android;

/** Persists attempts as well as successes: at most one/minute and ten/hour. */
final class WeatherBudget {
    long last, window; int attempts;
    WeatherBudget(long last,long window,int attempts) { this.last=last; this.window=window; this.attempts=attempts; }
    boolean reserve(long now) {
        if (last>0 && (now<last || now-last<60000)) return false;
        if (window==0 || now-window>=3600000) { window=now; attempts=0; }
        if (now<window || attempts>=10) return false;
        last=now; attempts++; return true;
    }
}
