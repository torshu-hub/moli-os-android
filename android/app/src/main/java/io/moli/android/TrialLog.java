package io.moli.android;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;

/** Explicit trial sessions only. Latest bounded journal, private file, voluntary export. */
final class TrialLog {
    private static Context app;
    private static TrialJournal journal;
    static synchronized void attach(Context context) {
        app=context.getApplicationContext();
        if(journal!=null) return;
        try { File f=file(); journal=f.exists()?TrialJournal.parse(ProfileStore.bounded(new AtomicFile(f).openRead(),65536)):new TrialJournal(); }
        catch(Exception ignored) { journal=new TrialJournal(); }
    }
    private static File file() { return new File(app.getFilesDir(),"trial-session.json"); }
    private static void save() { try { ProfileStore.atomic(file(),journal.json()); } catch(Exception ignored) { /* No payload/error text in logs. */ } }
    static synchronized void begin(Context c) {
        attach(c); if(journal.active) return;
        journal=new TrialJournal(); journal.active=true; record(TrialJournal.Event.SESSION_START,0,0);
    }
    static synchronized void end(Context c) { attach(c); if(!journal.active) return; record(TrialJournal.Event.SESSION_END,0,0); journal.active=false; save(); }
    static synchronized boolean active() { return journal!=null && journal.active; }
    static synchronized int count() { return journal==null?0:journal.events.length(); }
    static synchronized void record(TrialJournal.Event event,long a,long b) {
        if(app==null || journal==null || !journal.active) return;
        try { journal.add(System.currentTimeMillis(),event,a,b); save(); } catch(Exception ignored) { /* Fixed schema only. */ }
    }
    static String export(Context c) throws Exception {
        String text;
        synchronized(TrialLog.class) { attach(c); text=journal.text(); }
        android.content.pm.PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        long[] health=EngineHealth.snapshot();
        return "version="+info.versionName+" package="+c.getPackageName()+"\n"
                +"engine_starts="+c.getSharedPreferences("diagnostics",Context.MODE_PRIVATE).getLong("starts",0)+"\n"
                +"health_success="+health[2]+" health_failure="+health[3]+" max_check_gap_ms="+health[4]+"\n"
                +"A gap is no measurement, not a proven engine failure. Engine reads are not provider refreshes.\n"
                +"No PIN, credential, token, key, filename, profile content, URL, coordinates or arbitrary error text.\n"
                +text;
    }
}
