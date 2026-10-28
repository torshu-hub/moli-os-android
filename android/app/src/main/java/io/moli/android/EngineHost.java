package io.moli.android;

import android.content.Context;
import java.util.concurrent.*;
import org.json.JSONObject;

/** The code39 single-queue lifecycle, without simulation or secondary configuration. */
final class EngineHost {
    enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR, MAINTENANCE }
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static volatile State state = State.STOPPED;
    private static volatile String origin, token;
    private static long lease;
    static State state() { return state; }
    static String origin() { return origin; }
    static String token() { return token; }
    static synchronized long currentLease() { return lease; }
    private static void startNow(Context app) throws Exception {
        if(!ProtectedKey.exists(app)) ProtectedKey.createPreview(app);
        JSONObject keys = ProtectedKey.open(app);
        new ConfigStore(app).transaction.recover(); // Recover before native startup.
        String session = ProtectedKey.randomHex();
        int port = NativeServer.start(ProtectedKey.dataDirectory(app,keys).getAbsolutePath(), keys.getString("master"), session, keys.getString("pin"));
        synchronized(EngineHost.class) { ++lease; token=session; origin="http://127.0.0.1:"+port; state=State.RUNNING; }
        GeneralJournal.record(app, GeneralJournal.Event.ENGINE_STARTED);
    }
    private static void stopNow(Context app) throws Exception {
        NativeServer.stop();
        synchronized(EngineHost.class) { ++lease; origin=null; token=null; }
        GeneralJournal.record(app, GeneralJournal.Event.ENGINE_STOPPED);
    }
    static synchronized void start(Context context) {
        if(state!=State.STOPPED && state!=State.ERROR) return;
        state=State.STARTING;
        Context app=context.getApplicationContext();
        WORK.execute(()->{try{startNow(app);}catch(Exception|LinkageError failure){state=State.ERROR;GeneralJournal.record(app,GeneralJournal.Event.ENGINE_FAILED);}});
    }
    static synchronized void stop(Context context, boolean restart) {
        if(state==State.MAINTENANCE || state==State.STOPPING) return;
        Context app=context.getApplicationContext(); state=State.STOPPING;
        WORK.execute(()->{try{stopNow(app);state=State.STOPPED;if(restart){state=State.STARTING;startNow(app);}}
            catch(Exception|LinkageError failure){state=State.ERROR;GeneralJournal.record(app,GeneralJournal.Event.ENGINE_FAILED);}});
    }
    static <T> Future<T> inspect(Callable<T> task) { return WORK.submit(task); }
    static synchronized <T> Future<T> maintain(Context context, Callable<T> task) {
        if(state!=State.STOPPED && state!=State.RUNNING) throw new IllegalStateException("busy");
        boolean resume=state==State.RUNNING; state=State.MAINTENANCE;
        Context app=context.getApplicationContext();
        return WORK.submit(()->{
            String previous = null;
            try { if(resume){stopNow(app);previous=new ConfigStore(app).read();} return task.call(); }
            finally {
                if(resume){
                    try{startNow(app);}
                    catch(Exception failure){
                        // A valid file can still fail to activate. Restore the prior
                        // generation before attempting to resume the previous engine.
                        try{ConfigStore store=new ConfigStore(app);String current=store.read();if(previous!=null&&!java.util.Objects.equals(previous,current))store.restore(current,previous);startNow(app);}
                        catch(Exception rollback){state=State.ERROR;}
                        GeneralJournal.record(app,GeneralJournal.Event.ENGINE_FAILED);throw failure;
                    }
                } else state=State.STOPPED;
            }
        });
    }
    private EngineHost() {}
}
