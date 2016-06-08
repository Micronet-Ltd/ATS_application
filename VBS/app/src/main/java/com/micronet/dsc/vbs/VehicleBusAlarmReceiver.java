package com.micronet.dsc.vbs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by dschmidt on 3/2/16.
 */
public class VehicleBusAlarmReceiver extends BroadcastReceiver {
    public static final String TAG = "ATS-VBS-AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent.getAction().equals(VehicleBusConstants.SERVICE_ACTION_START)) {

            Log.d(TAG, "Restart Service Alarm");

            //String source = intent.getStringExtra("source");

            // Send intent to service (and start if needed)
            Intent i = new Intent(context, VehicleBusService.class);
            i.setAction(VehicleBusConstants.SERVICE_ACTION_START);

            context.startService(i);
            //startWakefulService(context, i); // wakeful to make sure we don't power down before
        }
    } // OnREceiver()


}
