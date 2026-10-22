package io.moli.android;
/** Attach the bounded journal before any page or service, including process recreation. */
public final class MoliApplication extends android.app.Application {
    @Override public void onCreate(){super.onCreate();TrialLog.attach(this);}
}
