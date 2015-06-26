/////////////////////////////////////////////////////////////
// ShutdownReceiver:
//  Receives the Shutdown Message from the System
/////////////////////////////////////////////////////////////
package com.micronet.dsc.ats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;


// This is registered in the Manifest
public class ShutdownReceiver extends BroadcastReceiver {

    // Here is where we are receiving our boot message.
    //  Information about this should also be put in the manifest.

    public static final String TAG = "ATS-ShutdownReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
		
		// We can try to start our service when OS notifies us it is shutting down
		// 	we would do this so that our service can kill itself before the OS does
		// This was added to test a system freezing problem, and should be throughouly tested before ever being used
		
		if (MainService.SHOULD_SELF_DESTROY_ON_SHUTDOWN) {

			Log.d(TAG, "System Shutdown Notification -- Taking action");

			// Send intent to service (to shut itself down)
			Intent i = new Intent(context, MainService.class);
			i.putExtra(Power.SHUTDOWN_REQUEST_NAME, 1);
			context.startService(i);
		} else {
			
			// Do not do anything with this notification
			Log.d(TAG, "System Shutdown Notification -- Ignored");
		}
    }

} // class
