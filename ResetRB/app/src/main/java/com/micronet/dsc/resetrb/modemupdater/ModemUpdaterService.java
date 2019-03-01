package com.micronet.dsc.resetrb.modemupdater;

import static com.micronet.dsc.resetrb.modemupdater.Rild.startRild;
import static com.micronet.dsc.resetrb.modemupdater.Rild.stopRild;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class ModemUpdaterService extends IntentService {

    private static final String TAG = "ResetRB-UpdaterService";
    private static final String MODEM_APP_NAME = "com.micronet.a317modemupdater";
    private static final String COMM_APP_NAME = "com.communitake.mdc.micronet";
    private static final String UPDATE_SUCCESSFUL = "com.micronet.dsc.resetrb.modemupdater.UPDATE_SUCCESSFUL";
    static final String SHARED_PREF_FILE_KEY = "ModemUpdaterService";

    // Use specific pincode to put it into a certain group on communitake.
    private static final String PINCODE = "3983605404";

    public ModemUpdaterService() {
        super("ModemUpdaterService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(intent != null && intent.getAction() != null){
            // Check modem firmware version to see if an update is needed.
            // Try to get good result up to 6 times (AKA no errors when checking)

            // Check shared preferences
            int result = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).getInt("ModemFirmwareUpdateNeeded", -1);
            if(result == -1){
                for(int i = 0; i < 6; i++){
                    result = isModemFirmwareUpdateNeededThroughPort();

                    if(result != -1){
                        break;
                    }
                }
            }

            Log.i(TAG, "Intent action is " + intent.getAction());
            // If an update is needed then handle broadcast and start needed apps
            if(result == 1){
                if(intent.getAction().equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)){
                    // Boot Completed Intent
                    // Start Communitake and Modem Updater
                    Log.i(TAG, "Trying to start Communitake Service");
                    startCommunitake();

                    Log.i(TAG, "Trying to start LTE Modem Updater");
                    startModemUpdater();
                }else if(intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_REPLACED)){
                    if(intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.dsc.resetrb")){
                        // New version of ResetRB installed
                        Log.i(TAG, "Trying to start Communitake Service");
                        startCommunitake();
                    }
                }else if(intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_ADDED)) {
                    if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.a317modemupdater")) {
                        // LTE Modem Updater just installed
                        Log.i(TAG, "Trying to start LTE Modem Updater");
                        startModemUpdater();
                    }
                }
            }else if (result == -1){
                // TODO: Better handling of error checking the modem version. Need to think of ideas to handle this.
                Log.e(TAG, "Error checking modem firmware version.");
            }else{
                // If LTE Modem Updater is installed then start the modem updater
                if(isAppInstalled(this, MODEM_APP_NAME)){
                    // It's likely the logs haven't been updated so start modem updater
                    Log.i(TAG, "No modem firmware update needed but LTE Modem Updater is installed. Starting Modem Updater to upload logs.");
                    startModemUpdater();
                }else{
                    Log.i(TAG, "Modem firmware already updated.");
                    // TODO: Remove this line below. Only adding for testing purposes.
                    startCommunitake();
                }
            }
        }
    }

    private void startModemUpdater(){
        // Check and see if LTE Modem Updater is installed
        if(isAppInstalled(this, MODEM_APP_NAME)){
            // If LTE Modem Updater isn't running
            if(!isAppRunning(this, MODEM_APP_NAME)){
                // Launch LTE Modem Updater
                Intent launchIntent = this.getPackageManager().getLaunchIntentForPackage(MODEM_APP_NAME);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    this.startActivity(launchIntent);
                    Log.i(TAG, "Started LTE Modem Updater");
                }
            }else{
                Log.d(TAG, "LTE Modem Updater is already running.");
            }
        }else{
            Log.d(TAG, "LTE Modem Updater isn't installed.");
        }
    }

    private void startCommunitake(){
        // Sleep for initial 15 seconds
        try {
            Thread.sleep(15000);
        } catch (InterruptedException e) {
            Log.e(TAG, e.toString());
        }

        // TODO: Is it bad to start Communitake if it is already running?
        try {
            // If pincode isn't in place, then put it in place
            // TODO: Is it likely that a pincode will already exist?
            File pincodeFile = new File("data/internal_Storage/Gsd/pincode.txt");
            if(!pincodeFile.exists()){
                FileWriter fileWriter = new FileWriter(pincodeFile);
                fileWriter.write(PINCODE);
                fileWriter.flush();
                fileWriter.close();
                Log.i(TAG, "Wrote communitake pincode to file.");
            }else{
                Log.i(TAG, "Pincode already exists.");
            }

            // Then launch Communitake
            Intent launchIntent = this.getPackageManager().getLaunchIntentForPackage(COMM_APP_NAME);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(launchIntent);
                Log.i(TAG, "Sent intent to start Communitake");

                // Update shared preferences
                SharedPreferences sharedPref = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
                sharedPref.edit().putBoolean("UpdateProcessStarted", true).apply();

                // TODO: Make sure that LTE Modem Updater is actually downloaded.
                // If it isn't within a certain amount of time then try force stopping and starting
                // Communitake again. Should use a relatively low amount of data for checkins.
                // We are doing this to address the issue where Communitake fails to download even
                // though it has good signal and a data connection. Not sure if we should make this
                // separate service or not.

                for(int i = 0; i < 10; i++){
                    // Try to sleep for 10 minutes and then if Updater isn't installed and device
                    // hasn't already been cleaned up, then force stop and start modem updater.
                    try {
                        Thread.sleep(600000);
                    } catch (InterruptedException e) {
                        Log.e(TAG, e.toString());
                    }

                    boolean cleaned = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE)
                            .getBoolean("ModemUpdatedAndDeviceCleaned", false);
                    boolean commRunning = isAppRunning(this, COMM_APP_NAME);
                    boolean modemInstalled = isAppInstalled(this, MODEM_APP_NAME);

                    // If we have cleaned or the updater is already installed then discontinue this loop.
                    if (cleaned || modemInstalled) {
                        break;
                    }

                    if(commRunning) {
                        // Force stop Communitake and run it again
                        runShellCommand(new String[]{"am", "force-stop", "com.communitake.mdc.micronet"});

                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        // Start Communitake again
                        this.startActivity(launchIntent);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString());
        }
    }

    private static boolean isAppRunning(final Context context, final String appName) {
        final ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if(activityManager != null){
            final List<RunningAppProcessInfo> procInfos = activityManager.getRunningAppProcesses();
            if (procInfos != null)
            {
                for (final ActivityManager.RunningAppProcessInfo processInfo : procInfos) {
                    if (processInfo.processName.equals(appName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isAppInstalled(Context context, String appName) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(appName, 0);
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }

    // Return -1 on error, return 0 on no update needed, and return 1 on updated needed.
    private int isModemFirmwareUpdateNeededThroughSettings() {
        // Get the modem baseband version from settings
        String radioVersion = Build.getRadioVersion();

        if (radioVersion != null) {
            Log.i(TAG, "Modem firmware version: " + radioVersion);

            // Populate versions that need updates
            // Radio version does not contain the extended version number
            ArrayList<String> versionsThatNeedUpdate = new ArrayList<>();
            versionsThatNeedUpdate.add("20.00.034");
            versionsThatNeedUpdate.add("20.00.522");

            if (versionsThatNeedUpdate.contains(radioVersion)) {
                // Update needed
                return 1;
            } else {
                // Update not needed
                return 0;
            }
        } else {
            // Return error
            return -1;
        }
    }

    // Return -1 on error, return 0 on no update needed, and return 1 on updated needed.
    private int isModemFirmwareUpdateNeededThroughPort(){
        // TODO: Issues with this function when it fails. Stops/starts rild too much. Should limit it.
        // Try to stop rild to communicate with the modem
        if (!stopRild()) {
            Log.e(TAG, "Error killing rild. Could not properly update modem firmware.");
            startRild();
            return -1;
        }

        // Try to set up the port to communicate with the modem
        Port port = new Port("/dev/ttyACM0");
        if (!port.setupPort()) {
            port.closePort();
            startRild();
            return -1;
        }

        if (port.testConnection()) {
            // Check if this modem firmware version needs to be updated.
            String modemType = port.getModemType();
            String modemFirmwareVersion = port.getModemVersion();

            Log.i(TAG, "Modem type: " + modemType + ", Modem firmware version: " + modemFirmwareVersion);

            // Populate versions that need updates
            ArrayList<String> versionsThatNeedUpdate = new ArrayList<>();
            versionsThatNeedUpdate.add("20.00.034.4");
            versionsThatNeedUpdate.add("20.00.034.6");
            versionsThatNeedUpdate.add("20.00.034.10");
            versionsThatNeedUpdate.add("20.00.522.4");
            versionsThatNeedUpdate.add("20.00.522.7");

            if(versionsThatNeedUpdate.contains(modemFirmwareVersion)){
                // Return update needed
                port.closePort();
                startRild();

                // Update shared preferences
                SharedPreferences sharedPref = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
                sharedPref.edit().putInt("ModemFirmwareUpdateNeeded", 1).apply();

                return 1;
            }else{
                // Return no update needed
                port.closePort();
                startRild();

                // Update shared preferences
                SharedPreferences sharedPref = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
                sharedPref.edit().putInt("ModemFirmwareUpdateNeeded", 0).apply();

                return 0;
            }
        } else {
            // Return error
            port.closePort();
            startRild();

            // Update shared preferences
            SharedPreferences sharedPref = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
            sharedPref.edit().putInt("ModemFirmwareUpdateNeeded", -1).apply();

            return -1;
        }
    }

    private static void runShellCommand(String[] commands) throws IOException {
        StringBuilder sb = new StringBuilder();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec(commands).getInputStream()));
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            sb.append(line);
        }

        bufferedReader.close();

        Log.i(TAG, "Clean up output: " + sb.toString());
    }
}
