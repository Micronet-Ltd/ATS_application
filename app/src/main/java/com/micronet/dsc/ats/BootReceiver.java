/////////////////////////////////////////////////////////////
// BootReceiver:
//  Receives the Boot Message from the System
/////////////////////////////////////////////////////////////
package com.micronet.dsc.ats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;


// This is registered in the Manifest
public class BootReceiver extends BroadcastReceiver {

    // Here is where we are receiving our boot message.
    //  Information about this should also be put in the manifest.

    public static final String TAG = "ATS-BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        Log.d(TAG, "System Boot Notification");

        // Send intent to service (and start if needed)
        Intent i = new Intent(context, MainService.class);
        i.putExtra(Power.BOOT_REQUEST_NAME, 1);
        context.startService(i);

    }

} // class
