package com.micronet.dsc.resetrb.modemupdater.receivers;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DBG;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.SHARED_PREF_FILE_KEY;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService;

/**
 * If the device's modem has already been updated and cleaned then nothing will happen.
 *
 * On boot completed, on com.micronet.dsc.resetrb package replaced, or on com.micronet.a317modemupdater added
 * this receiver will start the modem updater service.
 */
public class ModemUpdaterReceiver extends BroadcastReceiver {

    private static final String TAG = "ResetRB-UpdaterReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Check shared preferences
        SharedPreferences sharedPref = context.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
        boolean updatedAndCleaned = sharedPref.getBoolean("ModemUpdatedAndDeviceCleaned", false);
        boolean errorMaxChecksReached = sharedPref.getBoolean("ErrorCouldNotCheckModemMax", false);

        if (updatedAndCleaned) {
            if (DBG) Log.i(TAG, "Modem firmware version already updated and files cleaned.");
        } else {
            if (!errorMaxChecksReached) {
                if (intent.getAction() != null) {
                    if (intent.getAction().equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)) {
                        if (DBG) Log.i(TAG, "Broadcast received in ResetRB Modem Updater receiver. Action: " + intent.getAction());
                        // Boot Completed Intent
                        // Start Communitake and Modem Updater
                        startModemUpdaterService(context, intent);
                    } else if (intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_REPLACED)) {
                        if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.dsc.resetrb")) {
                            if (DBG) Log.i(TAG, "Broadcast received in ResetRB Modem Updater receiver. Action: " + intent.getAction());
                            // New version of ResetRB installed
                            startModemUpdaterService(context, intent);
                        }
                    } else if (intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_ADDED)) {
                        if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.a317modemupdater")) {
                            if (DBG) Log.i(TAG, "Broadcast received in ResetRB Modem Updater receiver. Action: " + intent.getAction());
                            // LTE Modem Updater just installed
                            startModemUpdaterService(context, intent);
                        }
                    }
                }
            } else {
                if (DBG) Log.e(TAG, "Error checking modem firmware version. Max checks reached.");
            }
        }
    }

    private void startModemUpdaterService(Context context, Intent intent) {
        Intent modemUpdaterService = new Intent(context, ModemUpdaterService.class);
        modemUpdaterService.setAction(intent.getAction());
        modemUpdaterService.setData(intent.getData());
        context.startService(modemUpdaterService);
        if (DBG) Log.i(TAG, "Started Modem Updater Service.");
    }
}
