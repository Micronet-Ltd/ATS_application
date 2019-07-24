package com.micronet.dsc.resetrb.modemupdater.services;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_APP_NAME;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DEVICE_CLEANED_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.MODEM_APP_NAME;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.UPDATE_SUCCESSFUL_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.Utils.MODEM_UPDATED_AND_CLEANED_KEY;
import static com.micronet.dsc.resetrb.modemupdater.Utils.PIN_CODE_PATH;
import static com.micronet.dsc.resetrb.modemupdater.Utils.PREVIOUS_PINCODE;
import static com.micronet.dsc.resetrb.modemupdater.Utils.UPDATED_COMM_ALREADY_INSTALLED;
import static com.micronet.dsc.resetrb.modemupdater.Utils.getBoolean;
import static com.micronet.dsc.resetrb.modemupdater.Utils.putBoolean;
import static com.micronet.dsc.resetrb.modemupdater.Utils.runShellCommand;
import static com.micronet.dsc.resetrb.modemupdater.Utils.sleep;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;
import java.io.File;
import java.io.IOException;

public class CleanUpService extends IntentService {

    private static final String TAG = "ResetRB-CleanUpService";

    public CleanUpService() {
        super("CleanUpService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(UPDATE_SUCCESSFUL_ACTION)){
                // Modem Firmware Update Successful, clean up communitake and LTE Modem Updater
                try {
                    // Remove pincode.txt
                    if (!getBoolean(this, PREVIOUS_PINCODE, false)) {
                        File pincodeFile = new File(PIN_CODE_PATH);
                        if(pincodeFile.exists()){
                            boolean deleted = pincodeFile.delete();

                            if(deleted){
                                Log.i(TAG, "Deleted communitake pincode.");
                            }else{
                                Log.e(TAG, "Error deleting pincode.txt");
                            }
                        }else{
                            Log.i(TAG, "Pincode doesn't exist.");
                        }
                    }

                    // Force stop communitake, clear communitake, and uninstall updater.
                    // Not sure if we need to force stop communitake or not.
                    if (!getBoolean(this, UPDATED_COMM_ALREADY_INSTALLED, false)) {
                        runShellCommand(new String[]{"am", "force-stop", COMM_APP_NAME});
                        sleep(500);
                        runShellCommand(new String[]{"pm", "clear", COMM_APP_NAME});
                        sleep(500);
                        runShellCommand(new String[]{"pm", "uninstall", COMM_APP_NAME});
                        sleep(500);
                    }

                    runShellCommand(new String[]{"pm", "uninstall", MODEM_APP_NAME});

                    // Update shared preferences
                    putBoolean(this, MODEM_UPDATED_AND_CLEANED_KEY, true);

                    Log.i(TAG, "Cleaned up device after successful modem firmware update.");

                    // Start upload service to upload logs to Dropbox
                    Intent dropboxUploadService = new Intent(this, DropboxUploadService.class);
                    dropboxUploadService.setAction(DEVICE_CLEANED_ACTION);
                    this.startService(dropboxUploadService);
                    Log.i(TAG, "Started Dropbox Upload Service.");
                } catch (IOException e) {
                    Log.e(TAG, e.toString());
                }
            }
        }
    }
}
