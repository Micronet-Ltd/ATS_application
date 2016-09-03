/////////////////////////////////////////////////////////////
// ResetRBService:
//  Handles requests to this service from other applications like ATS
//  supports a PING, or a request to clear the redbend files
//
//  This is organized as a service, although it could have been organized as broadcast receivers
/////////////////////////////////////////////////////////////

package com.micronet.dsc.resetrb;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;


public class ResetRBService extends Service {

    public static final String TAG = "ResetRB-Service";


    public static final String PACKAGE_NAME_ATS = "com.micronet.dsc.ats";
    public static final String START_SERVICE_ACTION_PING = "com.micronet.dsc.resetRB.ping";
    public static final String START_SERVICE_ACTION_RESET = "com.micronet.dsc.resetRB.reset";
    public static final String START_SERVICE_ACTION_SETPERIOD = "com.micronet.dsc.resetRB.setUnlockPeriod";

    public static final String BROADCAST_RESPONSE_RESET = "com.micronet.dsc.resetRB.replyReset";
    public static final String BROADCAST_RESPONSE_PING = "com.micronet.dsc.resetRB.replyPing";


    public static final String ACTION_EXTRA_START_TOD_S = "startSecondsAfterLocalMidnight"; // number of seconds after local time's midnight to start allowing Installation
    public static final String ACTION_EXTRA_END_TOD_S = "endSecondsAfterLocalMidnight"; // number of seconds after local time's midnight to stop allowing Installation

    public ResetRBService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }


    @Override
    public void onCreate() {
        android.util.Log.i(TAG, "Service Created: RRS version " + BuildConfig.VERSION_NAME);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action;

        Log.d(TAG, "onStartCommand()");

        if (intent == null) {
            // load up our saved settings from file and use those
            Log.d(TAG, "Intent was null. Aborting.");
            stopSelf();
            return START_NOT_STICKY;
        } // intent was null .. load from file


        action = intent.getAction();

        if (action.equals(ResetRBService.START_SERVICE_ACTION_RESET)) {
            android.util.Log.i(TAG, "Reseting Redbend Client ");

            RBCClearer.clearRedbendFiles();
            broadcastResetReply();

        }
        else
        if (action.equals(ResetRBService.START_SERVICE_ACTION_PING)) {
            android.util.Log.i(TAG, "Received Ping ");
            broadcastPingReply();
        }
        else
        if (action.equals(ResetRBService.START_SERVICE_ACTION_SETPERIOD)) {
            android.util.Log.i(TAG, "Received Set Installation Unlock Period ");


            int start_tod_s_local = intent.getIntExtra(ACTION_EXTRA_START_TOD_S, -1);
            int end_tod_s_local = intent.getIntExtra(ACTION_EXTRA_END_TOD_S, -1);


            Context context = getApplicationContext();
            if (0 == InstallationLocks.setUnlockTimePeriod(context, start_tod_s_local, end_tod_s_local)) {
                broadcastPingReply();
            }

        }



        stopSelf();
        return START_NOT_STICKY;

    } // OnStartCommand()

    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed

        // OnDestroy() is NOT guaranteed to be called, and won't be for instance
        //  if the app is updated
        //  if ths system needs RAM quickly
        //  if the user force-stops the application

        android.util.Log.v(TAG, "Destroying Service");

    } // OnDestroy()


    void broadcastResetReply() {

        Context context = getApplicationContext();

        Intent ibroadcast = new Intent();
        //ibroadcast.setPackage(PACKAGE_NAME_ATS);
        ibroadcast.setAction(BROADCAST_RESPONSE_RESET);


        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot
        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot
        //ibroadcast.putExtra("password", VehicleBusService.BROADCAST_PASSWORD);

        //ibroadcast.putExtra("processId", processId); // so this can be killed?


        context.sendBroadcast(ibroadcast);

    } // broadcastReply()


    void broadcastPingReply() {

        Context context = getApplicationContext();

        Intent ibroadcast = new Intent();
        //ibroadcast.setPackage(PACKAGE_NAME_ATS);
        ibroadcast.setAction(BROADCAST_RESPONSE_PING);

        ibroadcast.putExtra("version", BuildConfig.VERSION_NAME);

        context.sendBroadcast(ibroadcast);

    } // broadcastReply()



} // class VehicleBusService
