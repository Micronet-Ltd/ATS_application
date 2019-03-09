package com.micronet.dsc.resetrb.modemupdater.receivers;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.UPDATE_SUCCESSFUL;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.micronet.dsc.resetrb.modemupdater.services.CleanUpService;

public class UpdateSuccessfulReceiver extends BroadcastReceiver {
    private static final String TAG = "ResetRB-FirmwareUpdate";

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
        Intent modemCleanUpService = new Intent(context, CleanUpService.class);
        modemCleanUpService.setAction(intent.getAction());
        context.startService(modemCleanUpService);
        Log.i(TAG, "Started Modem Updater Clean Up Service.");
    }
}
