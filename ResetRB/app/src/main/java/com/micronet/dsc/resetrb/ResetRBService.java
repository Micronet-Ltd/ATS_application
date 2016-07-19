/////////////////////////////////////////////////////////////
// VBusService:
//  Handles communications with hardware regarding CAN and J1708
//  can be started in a separate process (since interactions with the API are prone to jam or crash -- and take down the whole process when they do)
/////////////////////////////////////////////////////////////

package com.micronet.dsc.resetrb;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;


public class ResetRBService extends Service {

    public static final String TAG = "ATS-RRS";


    public static final String PACKAGE_NAME_ATS = "com.micronet.dsc.ats";
    public static final String SERVICE_ACTION_RESET = "com.micronet.dsc.resetRB.reset";

    public static final String BROADCAST_RESPONSE_RESET = "com.micronet.dsc.resetRB.replyReset";


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

        if (action.equals(ResetRBService.SERVICE_ACTION_RESET)) {
            android.util.Log.i(TAG, "Reseting Redbend Client ");

            clearRedbendFiles();
            broadcastReply();

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




    //////////////////////////////////////////////////////////////////
    // clearRedbendFiles()
    //  clears out the rebend client files required to reset the client
    //  /data/misc/rb/* and /data/data/com.redbend.client
    //////////////////////////////////////////////////////////////////
    void clearRedbendFiles() {

        Log.d(TAG, "Clearing redbend client files");
        // since this has a wildcard it must be run in a shell

        String command = "";

        command = "rm -r /data/misc/rb/*";
        Log.d(TAG, "Running " + command);

        try {
            Runtime.getRuntime().exec(new String[] { "sh", "-c", command } ).waitFor();
        } catch (Exception e) {
            Log.d(TAG, "Exception exec: " + command + ": " + e.getMessage());
        }

        command = "pm clear com.redbend.client";
        Log.d(TAG, "Running " + command);

        try {
            Runtime.getRuntime().exec(command).waitFor();
        } catch (Exception e) {
            Log.d(TAG, "Exception exec " + command + ": " +  e.getMessage());
        }

    } // clearRedbendFiles


    void broadcastReply() {

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



} // class VehicleBusService
