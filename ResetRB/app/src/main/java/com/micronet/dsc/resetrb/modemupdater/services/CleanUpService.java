package com.micronet.dsc.resetrb.modemupdater.services;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DEVICE_CLEANED_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.MODEM_UPDATED_AND_CLEANED_KEY;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.SHARED_PREF_FILE_KEY;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.UPDATE_SUCCESSFUL_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.runShellCommand;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

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
                    // Force stop communitake, clear communitake, and uninstall updater.
                    // Not sure if we need to force stop communitake or not.
                    runShellCommand(new String[]{"am", "force-stop", "com.communitake.mdc.micronet"});
                    runShellCommand(new String[]{"pm", "clear", "com.communitake.mdc.micronet"});
                    runShellCommand(new String[]{"pm", "uninstall", "com.micronet.a317modemupdater"});

                    // Remove pincode.txt
                    File pincodeFile = new File("data/internal_Storage/Gsd/pincode.txt");
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

                    // Update shared preferences
                    SharedPreferences sharedPref = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
                    sharedPref.edit().putBoolean(MODEM_UPDATED_AND_CLEANED_KEY, true).apply();

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
