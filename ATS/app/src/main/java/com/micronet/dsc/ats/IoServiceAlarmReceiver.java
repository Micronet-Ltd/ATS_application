package com.micronet.dsc.ats;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;


/**
 * Created by dschmidt on 3/2/16.
 */
public class IoServiceAlarmReceiver extends BroadcastReceiver {

    public static final String TAG = "ATS-IOS-AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent.getAction().equals(IoService.SERVICE_ACTION_START)) {

            Log.d(TAG, "Restart Service Alarm");

            //String source = intent.getStringExtra("source");

            // Send intent to service (and start if needed)
            Intent i = new Intent(context, IoService.class);
            i.setAction(IoService.SERVICE_ACTION_START);

            context.startService(i);
            //startWakefulService(context, i); // wakeful to make sure we don't power down before
        }
    } // OnREceiver()

}
