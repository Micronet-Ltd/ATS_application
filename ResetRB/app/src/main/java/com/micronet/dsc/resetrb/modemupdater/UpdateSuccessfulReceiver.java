package com.micronet.dsc.resetrb.modemupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class UpdateSuccessfulReceiver extends BroadcastReceiver {
    private static final String TAG = "ResetRB-FirmwareUpdate";
    private static final String UPDATE_SUCCESSFUL = "com.micronet.dsc.resetrb.modemupdater.UPDATE_SUCCESSFUL";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Broadcast received in ResetRB Modem Updater Successful receiver. Action: " + intent.getAction());

        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(UPDATE_SUCCESSFUL)){
                // Start clean up service
                startModemUpdaterCleanUpService(context, intent);
            }
        }
    }

    private void startModemUpdaterCleanUpService(Context context, Intent intent){
        Intent modemCleanUpService = new Intent(context, ModemUpdaterCleanUpService.class);
        modemCleanUpService.setAction(intent.getAction());
        context.startService(modemCleanUpService);
        Log.i(TAG, "Started Modem Updater Clean Up Service.");
    }
}
