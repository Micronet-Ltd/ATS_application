package com.micronet.dsc.resetrb.modemupdater.services;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_APP_NAME;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_BACKOFF_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.MODEM_APP_NAME;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.MODEM_UPDATED_AND_CLEANED_KEY;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.SHARED_PREF_FILE_KEY;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.isAppInstalled;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.isAppRunning;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.runShellCommand;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.sleep;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Backoff service that ensures that CommuniTake is running and that it has successfully
 * downloaded the required modem updater application. Used to combat the issue where
 * CommuniTake doesn't download required apps properly.
 */
public class CommunitakeBackoffService extends IntentService {

    private static final String TAG = "ResetRB-BackoffService";

    public CommunitakeBackoffService() {
        super("CommunitakeBackoffService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(COMM_BACKOFF_ACTION)){
                for(int i = 0; i < 10; i++){
                    // Try to sleep for 10 minutes and then if Updater isn't installed and device
                    // hasn't already been cleaned up, then force stop and start modem updater.
                    sleep(600000);

                    boolean cleaned = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).getBoolean(MODEM_UPDATED_AND_CLEANED_KEY, false);
                    boolean commRunning = isAppRunning(this, COMM_APP_NAME);
                    boolean modemInstalled = isAppInstalled(this, MODEM_APP_NAME);

                    // If we have cleaned or the updater is already installed then discontinue this loop.
                    if (cleaned || modemInstalled) {
                        break;
                    } else {
                        try {
                            if(commRunning) {
                                // Force stop Communitake and run it again
                                runShellCommand(new String[]{"am", "force-stop", "com.communitake.mdc.micronet"});

                                sleep(5000);

                                // Then launch Communitake
                                Intent launchIntent = this.getPackageManager().getLaunchIntentForPackage(COMM_APP_NAME);
                                if (launchIntent != null) {
                                    launchIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    this.startActivity(launchIntent);
                                    Log.i(TAG, "Sent intent to start Communitake");
                                    // Start Communitake again
                                    this.startActivity(launchIntent);
                                }
                            }
                        }catch (Exception e){
                            Log.e(TAG, e.toString());
                        }
                    }
                }
            }
        }
    }
}
