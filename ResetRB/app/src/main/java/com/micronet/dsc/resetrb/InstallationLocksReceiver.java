/////////////////////////////////////////////////////////////
// InstallationLocksReceiver:
//  Receives messages from system when it is time to adjust the Installation Locks
//      Boot up
//      Time or timezone was changed
//      We are a at the time of day where we need to re-acquire the Installation Lock

// Changes Required to Client:
//      Add this receiver to Manifest.xml


/////////////////////////////////////////////////////////////
package com.micronet.dsc.resetrb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


// This is registered in the Manifest
public class InstallationLocksReceiver extends BroadcastReceiver {

    // Here is where we are receiving our boot message.
    //  Information about this should also be put in the manifest.

    public static final String TAG = "ResetRB-BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {



        String versionString = "RBCC version " + BuildConfig.VERSION_NAME + ":";

        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_TIME_CHANGED)) {
            Log.d(TAG, versionString + " rcvd Time Changed Notification");
        }
        else if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
            Log.d(TAG, versionString + " rcvd Timezone Changed Notification");
        }
        else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            Log.d(TAG, versionString + " rcvd System Boot Notification");
        }
        else if (action.equals(InstallationLocks.RELOCK_ALARM_NAME)) {
                Log.d(TAG, versionString + " rcvd Lock Alarm Notification");
        } else {
            Log.d(TAG, versionString + " rcvd Unknown Notification!!!!!!!!!");
        }


        // We need to set or clear the installation locks accordingly


        InstallationLocks.adjustLock(context);


        // Clear all prior locks from previous sessions

        // start the binding service if it was not already started, and clear out locks from prior boots

        //Intent i = new Intent(context, MicronetBindingService.class);
        //i.setAction(MicronetBindingService.START_ACTION_CLEAR_INSTALL_LOCKS);
        //context.startService(i);

    }

} // class

/*
timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        */