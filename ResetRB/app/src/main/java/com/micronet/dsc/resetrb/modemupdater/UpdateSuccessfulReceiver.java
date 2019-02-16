package com.micronet.dsc.resetrb.modemupdater;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.SHARED_PREF_FILE_KEY;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

public class UpdateSuccessfulReceiver extends BroadcastReceiver {
    private static final String TAG = "ResetRB-FirmwareUpdate";
    private static final String UPDATE_SUCCESSFUL = "com.micronet.dsc.resetrb.modemupdater.UPDATE_SUCCESSFUL";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Broadcast received in ResetRB Modem Updater Successful receiver. Action: " + intent.getAction());
        goAsync();

        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(UPDATE_SUCCESSFUL)){
                // Modem Firmware Update Successful
                // Clean up communitake and LTE Modem Updater
                try {
                    // Force stop communitake, clear communitake, and uninstall updater.
                    // Not sure if we need to force stop communitake or not.
                    //runShellCommand(new String[]{"am", "force-stop", "com.communitake.mdc.micronet"});
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
                    SharedPreferences sharedPref = context.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
                    sharedPref.edit().putBoolean("ModemUpdatedAndDeviceCleaned", true).apply();

                    Log.i(TAG, "Clean up device after successful modem firmware update.");
                } catch (IOException e) {
                    Log.e(TAG, e.toString());
                }
            }
        }
    }

    private static String runShellCommand(String[] commands) throws IOException {
        StringBuilder sb = new StringBuilder();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec(commands).getInputStream()));
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            sb.append(line);
        }

        bufferedReader.close();

        Log.i(TAG, "Clean up output: " + sb.toString());
        return sb.toString();
    }
}
