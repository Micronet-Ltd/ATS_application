/////////////////////////////////////////////////////////////
// ExternalReceiver:
//  Receives messages from external (outside this package) applications (not just system alarms)
/////////////////////////////////////////////////////////////
package com.micronet.dsc.ats;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;




//This is registered by setting the alarm in Power class
public class ExternalReceiver extends WakefulBroadcastReceiver {
    // Here is where we are receiving our alarm.
    //  Information about this should also be put in the manifest.

    // Note if we were powered down then we don't get this, we will get a boot notice instead (see BootReceiver)

    public static final String TAG = "ATS-ExternalReceiver";

    public static final String EXTERNAL_BROADCAST_PING = "com.micronet.dsc.ats.ping";
    public static final String EXTERNAL_BROADCAST_PING_REPLY = "com.micronet.dsc.ats.pingreply";
    @Override
    public void onReceive(Context context, Intent intent)
    {

        //Log.d(TAG, "Heartbeat Alarm rcv " + intent.getAction());

        if (intent.getAction().equals(EXTERNAL_BROADCAST_PING)) {

            Log.d(TAG, "External ping received");


            // reply that we got this command
            Intent ibroadcast = new Intent();
            ibroadcast.setAction(EXTERNAL_BROADCAST_PING_REPLY);
            context.sendBroadcast(ibroadcast);

            // Send intent to service (so service is started if needed)
            Intent iservice = new Intent(context, MainService.class);
            iservice.putExtra(EXTERNAL_BROADCAST_PING, 1);
            startWakefulService(context, iservice); // wakeful to make sure we don't power down before
        }


    } // OnReceive()


} // class

