/////////////////////////////////////////////////////////////
// BootReceiver:
//  Receives the Boot Message from the System
/////////////////////////////////////////////////////////////
package com.micronet.dsc.testrtc;

import android.util.Log;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;


// This is registered in the Manifest
public class BootReceiver extends BroadcastReceiver {

    // Here is where we are receiving our boot message.
    //  Information about this should also be put in the manifest.

    public static final String TAG = "IoJam-BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        Log.d(TAG, "System Boot Notification");

        MainActivity.incrementBootCounter(context);

        // Send intent to the activity
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

} // class
