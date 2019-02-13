package com.micronet.dsc.resetrb.modemupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ModemUpdaterReceiver extends BroadcastReceiver {
    private static final String TAG = "ResetRB-UpdaterReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Broadcast received in ResetRB Modem Updater receiver. Action: " + intent.getAction());

        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)){
                // Boot Completed Intent
                // Start Communitake and Modem Updater
                startModemUpdaterService(context, intent);
            }else if(intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_REPLACED)){
                if(intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.dsc.resetrb")){
                    // New version of ResetRB installed
                    startModemUpdaterService(context, intent);
                }
            }else if(intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_ADDED)) {
                if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.a317modemupdater")) {
                    // LTE Modem Updater just installed
                    startModemUpdaterService(context, intent);
                }
            }
        }
    }

    private void startModemUpdaterService(Context context, Intent intent){
        Intent modemUpdaterService = new Intent(context, ModemUpdaterService.class);
        modemUpdaterService.setAction(intent.getAction());
        modemUpdaterService.setData(intent.getData());
        context.startService(modemUpdaterService);
        Log.i(TAG, "Started Modem Updater Service.");
    }
}
