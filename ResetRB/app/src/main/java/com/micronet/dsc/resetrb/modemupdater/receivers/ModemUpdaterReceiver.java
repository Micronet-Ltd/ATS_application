package com.micronet.dsc.resetrb.modemupdater.receivers;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.SHARED_PREF_FILE_KEY;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService;

public class ModemUpdaterReceiver extends BroadcastReceiver {
    private static final String TAG = "ResetRB-UpdaterReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Check shared preferences
        SharedPreferences sharedPref = context.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
        boolean updatedAndCleaned = sharedPref.getBoolean("ModemUpdatedAndDeviceCleaned", false);

        if(!updatedAndCleaned){
            if(intent.getAction() != null) {
                if(intent.getAction().equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)){
                    Log.i(TAG, "Broadcast received in ResetRB Modem Updater receiver. Action: " + intent.getAction());
                    // Boot Completed Intent
                    // Start Communitake and Modem Updater
                    startModemUpdaterService(context, intent);
                }else if(intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_REPLACED)){
                    if(intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.dsc.resetrb")){
                        Log.i(TAG, "Broadcast received in ResetRB Modem Updater receiver. Action: " + intent.getAction());
                        // New version of ResetRB installed
                        startModemUpdaterService(context, intent);
                    }
                }else if(intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_ADDED)) {
                    if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.a317modemupdater")) {
                        Log.i(TAG, "Broadcast received in ResetRB Modem Updater receiver. Action: " + intent.getAction());
                        // LTE Modem Updater just installed
                        startModemUpdaterService(context, intent);
                    }
                }
            }
        }else{
            Log.i(TAG, "Modem firmware version already updated and files cleaned.");
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
