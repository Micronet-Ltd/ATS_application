/////////////////////////////////////////////////////////////////////////////////////
// WatchdogService
//  This runs in its own process:
//  For starting, it accepts an intent with 2 extras
//      broadcastAction (string) : creates a receiver that listens for this broadcast
//      processId (int): will trigger if we don't receive the broadcast within MAXIMUM_TIME_BETWEEN_BROADCASTS_MS
//  When triggering:
//      kills the process ID that was passed
//      schedules the an ALARM_RESTART_NAME broadcast that contains EXTRA_SOURCE_NAME .. this should re-start the service
/////////////////////////////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;


import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class WatchdogService extends Service {
    static public final String TAG = "ATS-WDS";


    public static final String WATCHDOG_ALARM_NAME = "com.micronet.dsc.ats.watchdog.alarm";
    public static final int WATCHDOG_ALARM_REQUEST_CODE = 2985320; // unique private ID for this watchdog alarm


    // For Sending to the startService()
    public static final String WATCHDOG_SERVICE_ACTION_START = "com.micronet.dsc.ats.watchdog.start";
    public static final String WATCHDOG_SERVICE_ACTION_STOP = "com.micronet.dsc.ats.watchdog.stop";


    public static final String WATCHDOG_EXTRA_BROADCASTACTION = "broadcastAction";
    public static final String WATCHDOG_EXTRA_PROCESSID = "processId";





    // For sending to the service
    public static final String SERVICE_ALARM_ACTION = "com.micronet.dsc.ats.restartservice"; // the action for the alarm
    public static final String SERVICE_EXTRA_SOURCE_NAME = "WatchdogService"; // extra which tells us it was this external process watchdog

    final int MAXIMUM_TIME_BETWEEN_BROADCASTS_MS = 5000; //if more than this time elapses, we will kill specified process and set broadcast alarm.

    ScheduledThreadPoolExecutor exec;

    long lastReceivedTime = 0;
    String monitoredBroadcastAction = "";
    int processId = 0;

    public WatchdogService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        android.util.Log.v(TAG, "Watchdog Service Created");

        lastReceivedTime = SystemClock.elapsedRealtime();

    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {



        if ((intent == null) && (processId == 0)) {
            Log.e(TAG, "Unable to start Watchdog Service with null intent");
            return START_NOT_STICKY;
        }

        if (intent != null) {
            String action = intent.getAction();
            if (action.equals(WATCHDOG_SERVICE_ACTION_STOP)) {
                android.util.Log.v(TAG, "Watchdog Service Stopping");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // This is a start action
        android.util.Log.v(TAG, "Watchdog Service Starting");

        int newProcessId = 0;

        if (intent != null) {
            newProcessId = intent.getIntExtra(WATCHDOG_EXTRA_PROCESSID, 0);
        }

        if ((newProcessId == 0) && (processId == 0)) {
            Log.e(TAG, "Unable to determine a process Id");
            return START_NOT_STICKY;
        }

        if (newProcessId != 0) {
            // if we got a new process Id and action to monitor, use that
            processId = newProcessId;
            monitoredBroadcastAction  = intent.getStringExtra(WATCHDOG_EXTRA_BROADCASTACTION);
        }


        android.util.Log.i(TAG, "Starting Watchdog for " + monitoredBroadcastAction + " pid " + processId);


        lastReceivedTime = SystemClock.elapsedRealtime();


        // register the receiver that we are monitoring

        try {
            getApplicationContext().unregisterReceiver(monitorReceiver);
        } catch (Exception e) {
            // do nothing .. this is expected
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(monitoredBroadcastAction);
        getApplicationContext().registerReceiver(monitorReceiver, intentFilter);


        // start the threads that will check we received something on the receiver

        if (exec != null) {
            exec.shutdown();
            exec = null;
        }

        exec = new ScheduledThreadPoolExecutor(1);
        exec.scheduleWithFixedDelay(new MonitorTask(), 2000, 2000, TimeUnit.MILLISECONDS); // every 2 second, check we are still awake


        // create an Alarm in case this service is killed
        setWatchdogAlarm();

        return START_REDELIVER_INTENT;
    } // OnStartCommand()

    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed

        // OnDestroy() is NOT guaranteed to be called, and won't be for instance
        //  if the app is updated
        //  if ths system needs RAM quickly
        //  if the user force-stops the application


        android.util.Log.v(TAG, "OnDestroy()");


        if (exec != null) {
            exec.shutdown();
            exec = null;
        }
        try {
            getApplicationContext().unregisterReceiver(monitorReceiver);
        } catch (Exception e) {
            // do nothing ... this can happen if it is not yet registered
        }

    } // OnDestroy()




    void setWatchdogAlarm() {
        Context context = getApplicationContext();
        AlarmManager alarmservice = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)


        Intent i = new Intent(context, WatchdogService.class); // it goes to us
        i.setAction(WATCHDOG_ALARM_NAME);
        i.putExtra(WATCHDOG_EXTRA_PROCESSID , processId); // say who generated this, so service knows
        i.putExtra(WATCHDOG_EXTRA_BROADCASTACTION, monitoredBroadcastAction); // say who generated this, so service knows

        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(context, WATCHDOG_ALARM_REQUEST_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmservice.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 5000, pi); // wait 5 seconds

    } // setWatchdogAlarm()



    void restartMonitoredService(int processId) {

        Context context = getApplicationContext();


        AlarmManager alarmservice = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)


        Intent i = new Intent(context, AlarmReceiver.class); // it goes to this guy
        i.setAction(SERVICE_ALARM_ACTION);
        i.putExtra("source", SERVICE_EXTRA_SOURCE_NAME); // say who generated this, so service knows



        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(context, WATCHDOG_ALARM_REQUEST_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmservice.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pi); // wait 1 second


        // And kill off the given process
        Log.i(TAG, "Killing process " + processId);
        android.os.Process.killProcess(processId);
        //android.os.Process.sendSignal(processId, android.os.Process.SIGNAL_QUIT);

        /*
        // maybe try and tell the service to save itself ?
        //  -- Does not work

        Log.i(TAG, "Signaling service to restart itself");
        Intent serviceIntent = new Intent(context, MainService.class);
        serviceIntent.setPackage(context.getPackageName());
        serviceIntent.setAction(MainService.SERVICE_ACTION_WD_RESTART);

        startService(serviceIntent);
        */

        //android.os.Process.killProcess();
    } // restartMonitoredService()



    class MonitorTask implements Runnable {


        @Override
        public void run() {

            try {
                android.util.Log.v(TAG, "MonitorTask()");

                setWatchdogAlarm(); // make sure that we will restart this watchdog if it dies

                long now = SystemClock.elapsedRealtime();

                long diff = now - lastReceivedTime;

                if (diff > MAXIMUM_TIME_BETWEEN_BROADCASTS_MS) {
                    android.util.Log.e(TAG, "Watchdog Error. ATS Service jammed (" + monitoredBroadcastAction  + ": " + diff + " ms). Process " + processId);
                    if (processId != 0) {
                        try {
                            lastReceivedTime = now; // so we don't just keep triggering the watchdog over and over
                            restartMonitoredService(processId);

                        } catch (Exception e) {
                            android.util.Log.e(TAG, "can't kill process " + processId);
                        }
                    } else {
                        android.util.Log.e(TAG, "Won't kill process " + processId);
                    }
                }

            } catch (Exception e) {
                android.util.Log.e(TAG + ".MonTask", "Exception during MonitorTask:run() " + e.toString(), e);
            }

        } // run
    } // class MonitorTask


    /////////////////////////////////////////////////////
    // MonitorReceiver : receives the broadcast that we are monitoring
    /////////////////////////////////////////////////////
    class MonitorReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent)
        {
            // Update the last time we got this
            Log.d(TAG, "MonitorReceiver::onReceive():");
            lastReceivedTime = SystemClock.elapsedRealtime();

        }
    }

    MonitorReceiver monitorReceiver = new MonitorReceiver();




} // class
