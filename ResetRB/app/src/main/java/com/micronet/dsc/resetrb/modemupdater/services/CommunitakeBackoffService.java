package com.micronet.dsc.resetrb.modemupdater.services;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_APP_NAME;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_BACKOFF_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.MODEM_APP_NAME;
import static com.micronet.dsc.resetrb.modemupdater.Utils.MODEM_UPDATED_AND_CLEANED_KEY;
import static com.micronet.dsc.resetrb.modemupdater.Utils.getBoolean;
import static com.micronet.dsc.resetrb.modemupdater.Utils.isAppInstalled;
import static com.micronet.dsc.resetrb.modemupdater.Utils.isAppRunning;
import static com.micronet.dsc.resetrb.modemupdater.Utils.runShellCommand;
import static com.micronet.dsc.resetrb.modemupdater.Utils.sleep;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

/**
 * Backoff service that ensures that CommuniTake is running and that it has successfully
 * downloaded the required modem updater application. Used to combat the issue where
 * CommuniTake doesn't download required apps properly.
 */
public class CommunitakeBackoffService extends IntentService {

    private static final String TAG = "ResetRB-BackoffService";

    private final int NUM_RETRIES = 10;
    private final int SLEEP_TIME = 600000;
    private final int WAIT_PERIOD = 5000;

    public CommunitakeBackoffService() {
        super("CommunitakeBackoffService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(COMM_BACKOFF_ACTION)){
                for(int i = 0; i < NUM_RETRIES; i++){
                    // Try to sleep for SLEEP_TIME ms and then if Updater isn't installed and device
                    // hasn't already been cleaned up, then force stop and start modem updater.
                    sleep(SLEEP_TIME);

                    boolean cleaned = getBoolean(this, MODEM_UPDATED_AND_CLEANED_KEY, false);
                    boolean commRunning = isAppRunning(this, COMM_APP_NAME);
                    boolean modemInstalled = isAppInstalled(this, MODEM_APP_NAME);

                    // If we have cleaned or the updater is already installed then discontinue this loop.
                    if (cleaned || modemInstalled) {
                        break;
                    } else {
                        try {
                            if(commRunning) {
                                // Force stop Communitake and run it again
                                runShellCommand(new String[]{"am", "force-stop", COMM_APP_NAME});

                                sleep(WAIT_PERIOD);

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
