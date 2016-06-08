package com.micronet.dsc.ats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by dschmidt on 2/22/16.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    static public final String TAG = "ATS-WDS-Rcvr";

    // OnReceive()   : Can receive alarms for the watchdog service telling it to run
    @Override
    public void onReceive(Context context, Intent intent) {


        if (intent.getAction().equals(WatchdogService.WATCHDOG_ALARM_NAME)) {

            Log.d(TAG, "Restart Watchdog Alarm has triggered");
            int pid = intent.getIntExtra(WatchdogService.WATCHDOG_EXTRA_PROCESSID, 0);
            String ba = intent.getStringExtra(WatchdogService.WATCHDOG_EXTRA_BROADCASTACTION);

            if (pid == 0) {
                Log.e(TAG, "Alarm Unable to restart watchdog service (missing processId & broadcastAction)");
            } else {

                Log.v(TAG, "Re-starting Watchdog Service");

                Intent serviceIntent = new Intent(context, WatchdogService.class);
                serviceIntent.setAction(WatchdogService.WATCHDOG_SERVICE_ACTION_START);
                serviceIntent.putExtra(WatchdogService.WATCHDOG_EXTRA_BROADCASTACTION, ba); // broadcastAction to listen for
                serviceIntent.putExtra(WatchdogService.WATCHDOG_EXTRA_PROCESSID, pid);  // PID to kill when the watchdog triggers
                context.startService(serviceIntent);

            }
        }
    }


} // class
