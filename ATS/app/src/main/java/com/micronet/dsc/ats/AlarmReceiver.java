/////////////////////////////////////////////////////////////
// AlarmReceiver:
//  Receives the Alarm from the System
/////////////////////////////////////////////////////////////
package com.micronet.dsc.ats;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;


//This is registered by setting the alarm in Power class
public class AlarmReceiver extends WakefulBroadcastReceiver {
    // Here is where we are receiving our alarm.
    //  Information about this should also be put in the manifest.

    // Note if we were powered down then we don't get this, we will get a boot notice instead (see BootReceiver)

    public static final String TAG = "ATS-AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent)
    {

        //Log.d(TAG, "Heartbeat Alarm rcv " + intent.getAction());

        if (intent.getAction().equals(Power.ALARM_HEARTBEAT_NAME)) {

            Log.d(TAG, "Heartbeat Alarm");

            // Send intent to service (and start if needed)
            Intent i = new Intent(context, MainService.class);
            i.putExtra(Power.ALARM_HEARTBEAT_NAME, 1);
            startWakefulService(context, i); // wakeful to make sure we don't power down before
        }
        if (intent.getAction().equals(Power.ALARM_SCHEDULED_NAME)) {

            Log.d(TAG, "Scheduled Wakeup Alarm");

            // Send intent to service (and start if needed)
            Intent i = new Intent(context, MainService.class);
            i.putExtra(Power.ALARM_SCHEDULED_NAME, 1);
            startWakefulService(context, i); // wakeful to make sure we don't power down before
        }
        if (intent.getAction().equals(Power.ALARM_RESTART_NAME)) {

            Log.d(TAG, "Restart Service Alarm");

            String reason = intent.getStringExtra("reason");

            // Send intent to service (and start if needed)
            Intent i = new Intent(context, MainService.class);
            i.putExtra(Power.ALARM_RESTART_NAME, 1);
            i.putExtra("reason", reason);
            startWakefulService(context, i); // wakeful to make sure we don't power down before
        }


    } // OnReceive()


} // class

