package com.micronet.dsc.resetrb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ModemUpdaterReceiver extends BroadcastReceiver {
    private static final String TAG = "ResetRB-BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Broadcast received in ResetRB Modem Updater receiver.");

        if(intent != null && intent.getAction() != null){
            if(intent.getAction().equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)){
                // Boot Completed Intent
                // Start Communitake and Modem Updater Services
                Intent communitakeService = new Intent(context, CommunitakeService.class);
                context.startService(communitakeService);

                Intent modemUpdaterService = new Intent(context, ModemUpdaterService.class);
                context.startService(modemUpdaterService);

                Log.i(TAG, "Started both ResetRB Modem Updater and Communitake Service");
            }else if(intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_REPLACED)){
                if(intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.dsc.resetrb")){
                    // New version of ResetRB installed
                    Intent communitakeService = new Intent(context, CommunitakeService.class);
                    context.startService(communitakeService);
                    Log.i(TAG, "Started ResetRB Communitake Service");
                }
            }else if(intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_ADDED)){
                if(intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.a317modemupdater")){
                    // LTE Modem Updater just installed
                    Intent modemUpdaterService = new Intent(context, ModemUpdaterService.class);
                    context.startService(modemUpdaterService);
                    Log.i(TAG, "Started ResetRB Modem Updater Service");
                }
            }
        }
    }
}
