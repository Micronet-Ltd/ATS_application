/////////////////////////////////////////////////////////////
// AlarmReceiver:
//  Receives the Alarm from the System
/////////////////////////////////////////////////////////////
package com.micronet.dsc.testrtc;

import android.content.BroadcastReceiver;
import android.util.Log;
import android.content.Context;
import android.content.Intent;


// This is registered when the wakeup is set
public class AlarmReceiver extends BroadcastReceiver {
    // Here is where we are receiving our alarm.
    //  Information about this should also be put in the manifest.

    // Note if we were powered down then we don't get this, we will get a boot notice instead (see BootReceiver)

    public static final String TAG = "TestRTC-AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

            Log.d(TAG, "Alarm Received (wakeup), starting activity");

            // Send intent to the activity
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);


    } // OnReceive()


} // class

