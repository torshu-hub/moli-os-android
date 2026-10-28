package io.moli.android;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Foreground lifetime; no driver, weather poller or scheduler lives here. */
public final class MoliService extends Service {
    static final String STOP="stop", RESTART="restart";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable monitor=new Runnable(){public void run(){
        EngineHost.State state=EngineHost.state();
        if(state==EngineHost.State.STOPPED || state==EngineHost.State.ERROR){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return;}
        handler.postDelayed(this,500);
    }};
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("moli",getString(R.string.app_name),NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"moli").setSmallIcon(android.R.drawable.ic_menu_myplaces).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.service_running)).setContentIntent(open).setOngoing(true).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        startForeground(1,notification());
        String action=intent==null?null:intent.getAction();
        if(STOP.equals(action))EngineHost.stop(this,false);
        else if(RESTART.equals(action))EngineHost.stop(this,true);
        else EngineHost.start(this);
        handler.removeCallbacks(monitor);handler.postDelayed(monitor,500);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){handler.removeCallbacks(monitor);if(EngineHost.state()!=EngineHost.State.STOPPED && EngineHost.state()!=EngineHost.State.ERROR)EngineHost.stop(this,false);super.onDestroy();}
}
