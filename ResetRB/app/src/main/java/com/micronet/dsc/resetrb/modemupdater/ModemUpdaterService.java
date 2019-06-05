package com.micronet.dsc.resetrb.modemupdater;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.util.Log;

import com.micronet.dsc.resetrb.modemupdater.services.CommunitakeBackoffService;
import com.micronet.dsc.resetrb.modemupdater.services.DropboxUploadService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that checks modem firmware version and starts update process if needed.
 */
public class ModemUpdaterService extends IntentService {

    private static final String TAG = "ResetRB-UpdaterService";
    public static final boolean DBG = true;

    // App names
    public static final String MODEM_APP_NAME = "com.micronet.a317modemupdater";
    public static final String COMM_APP_NAME = "com.communitake.mdc.micronet";

    // Actions
    public static final String UPDATE_SUCCESSFUL_ACTION = "com.micronet.dsc.resetrb.modemupdater.UPDATE_SUCCESSFUL_ACTION";
    public static final String COMM_BACKOFF_ACTION = "com.micronet.dsc.resetrb.modemupdater.COMM_BACKOFF_ACTION";
    public static final String COMM_STARTED_ACTION = "com.micronet.dsc.resetrb.modemupdater.COMM_STARTED_ACTION";
    public static final String DEVICE_CLEANED_ACTION = "com.micronet.dsc.resetrb.modemupdater.DEVICE_CLEANED_ACTION";
    public static final String ERROR_CHECKING_VERSION_ACTION = "com.micronet.dsc.resetrb.modemupdater.ERROR_CHECKING_VERSION";

    // Keys
    public static final String SHARED_PREF_FILE_KEY = "ModemUpdaterService";
    public static final String MODEM_UPDATE_NEEDED_KEY = "ModemFirmwareUpdateNeeded";
    public static final String MODEM_UPDATE_PROCESS_STARTED_KEY = "UpdateProcessStarted";
    public static final String MODEM_UPDATED_AND_CLEANED_KEY = "ModemUpdatedAndDeviceCleaned";

    // TODO Possibly make pincode configurable?
    private static final String PINCODE = "3983605404";

    public ModemUpdaterService() {
        super("ModemUpdaterService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            // Sleep 30 seconds
            sleep(30000);

            // Check if update is needed
            int result = getInt(MODEM_UPDATE_NEEDED_KEY, -1);
            if (result == -1) {
                result = isModemFirmwareUpdateNeededThroughSettings();
            }

            switch (result) {
                case 1: // If an update is needed then handle broadcast and start needed apps
                    if (intent.getAction()
                            .equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)) { // Boot Completed Intent, start Communitake and Modem Updater
                        if (DBG) Log.i(TAG, "Trying to start Communitake Service");
                        startCommunitake();

                        if (DBG) Log.i(TAG, "Trying to start LTE Modem Updater");
                        startModemUpdater();
                    } else if (intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_REPLACED)) { // New version of ResetRB installed
                        if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.dsc.resetrb")) {
                            if (DBG) Log.i(TAG, "Trying to start Communitake Service");
                            startCommunitake();
                        }
                    } else if (intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_ADDED)) { // LTE Modem Updater just installed
                        if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase("package:com.micronet.a317modemupdater")) {
                            if (DBG) Log.i(TAG, "Trying to start LTE Modem Updater");
                            startModemUpdater();
                        }
                    }
                    break;
                case -1:
                    // TODO Want to try to upload just once if there is an error checking the modem firmware version?
                    if (DBG) Log.e(TAG, "Error checking modem firmware version.");

//                    // Try to report error to dropbox
//                    Intent dropboxUploadService = new Intent(this, DropboxUploadService.class);
//                    dropboxUploadService.setAction(ERROR_CHECKING_VERSION_ACTION);
//                    this.startService(dropboxUploadService);
                    break;
                default:
                    // If LTE Modem Updater is installed then start the modem updater
                    if (isAppInstalled(this, MODEM_APP_NAME)) {
                        // It's likely the logs haven't been updated so start modem updater
                        if (DBG) Log.i(TAG, "No modem firmware update needed but LTE Modem Updater is installed. Starting Modem Updater to upload logs.");
                        startModemUpdater();
                    } else {
                        if (DBG) Log.i(TAG, "Modem firmware already updated.");
                        // TODO Remove this line below. Only adding for testing purposes.
                        startCommunitake();
                    }
                    break;
            }
        }
    }

    private void startModemUpdater() {
        // Check and see if LTE Modem Updater is installed
        if (isAppInstalled(this, MODEM_APP_NAME)) {
            // If LTE Modem Updater isn't running
            if (!isAppRunning(this, MODEM_APP_NAME)) {
                // Launch LTE Modem Updater
                Intent launchIntent = this.getPackageManager().getLaunchIntentForPackage(MODEM_APP_NAME);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    this.startActivity(launchIntent);
                    if (DBG) Log.i(TAG, "Started LTE Modem Updater");
                }
            } else {
                if (DBG) Log.e(TAG, "LTE Modem Updater is already running.");
            }
        } else {
            if (DBG) Log.e(TAG, "LTE Modem Updater isn't installed.");
        }
    }

    private void startCommunitake() {
        // Sleep for initial 15 seconds
        sleep(15000);

        // TODO: Is it bad to start Communitake if it is already running?
        try {
            // If pincode isn't in place, then put it in place
            // TODO: Is it likely that a pincode will already exist?
            File pincodeFile = new File("data/internal_Storage/Gsd/pincode.txt");
            if (!pincodeFile.exists()) {
                FileWriter fileWriter = new FileWriter(pincodeFile);
                fileWriter.write(PINCODE);
                fileWriter.flush();
                fileWriter.close();
                if (DBG) Log.i(TAG, "Wrote communitake pincode to file.");
            } else {
                if (DBG) Log.i(TAG, "Pincode already exists.");
            }

            // Then launch Communitake
            Intent launchIntent = this.getPackageManager().getLaunchIntentForPackage(COMM_APP_NAME);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(launchIntent);
                if (DBG) Log.i(TAG, "Sent intent to start Communitake");

                // Update shared preferences
                putBoolean(MODEM_UPDATE_PROCESS_STARTED_KEY, true);

                // Start backoff service for Communitake to make sure that app actually downloads
                Intent communitakeBackoffService = new Intent(this, CommunitakeBackoffService.class);
                communitakeBackoffService.setAction(COMM_BACKOFF_ACTION);
                this.startService(communitakeBackoffService);
                if (DBG) Log.i(TAG, "Started Communitake Backoff Service.");

                // Start upload service to upload logs to Dropbox
                Intent dropboxUploadService = new Intent(this, DropboxUploadService.class);
                dropboxUploadService.setAction(COMM_STARTED_ACTION);
                this.startService(dropboxUploadService);
                if (DBG) Log.i(TAG, "Started Dropbox Upload Service.");
            }
        } catch (IOException e) {
            if (DBG) Log.e(TAG, e.toString());
        }
    }

    public static boolean isAppRunning(final Context context, final String appName) {
        final ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            final List<RunningAppProcessInfo> procInfos = activityManager.getRunningAppProcesses();
            if (procInfos != null) {
                for (final ActivityManager.RunningAppProcessInfo processInfo : procInfos) {
                    if (processInfo.processName.equals(appName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isAppInstalled(Context context, String appName) {
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

        if (radioVersion != null && !radioVersion.trim().isEmpty()) {
            if (DBG) Log.i(TAG, "Modem firmware version: " + radioVersion);

            // Populate versions that need updates, radio version does not contain the extended version number
            ArrayList<String> versionsThatNeedUpdate = new ArrayList<>();
            versionsThatNeedUpdate.add("20.00.034");
            versionsThatNeedUpdate.add("20.00.522");

            if (versionsThatNeedUpdate.contains(radioVersion)) {
                // Update needed, update shared preferences
                putInt(MODEM_UPDATE_NEEDED_KEY, 1);
                return 1;
            } else {
                // Update not needed, update shared preferences
                putInt(MODEM_UPDATE_NEEDED_KEY, 0);
                return 0;
            }
        } else {
            // Return error, update shared preferences
            putInt(MODEM_UPDATE_NEEDED_KEY, -1);
            return -1;
        }
    }

    public static void runShellCommand(String[] commands) throws IOException {
        StringBuilder sb = new StringBuilder();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec(commands).getInputStream()));
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            sb.append(line);
        }

        bufferedReader.close();

        if (DBG) Log.i(TAG, "Shell command output: " + sb.toString());
    }

    public static void sleep(long ms){
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Log.e(TAG, e.toString());
        }
    }

    public void putInt(String key, int value) {
        this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).edit().putInt(key, value).apply();
    }

    public int getInt(String key, int defValue) {
        return this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).getInt(key, defValue);
    }

    public void putBoolean(String key, boolean value) {
        this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    public boolean getBoolean(String key, boolean defValue) {
        return this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).getBoolean(key, defValue);
    }

//    /**
//     * Queries modem firmware version and returns whether an update is needed.
//     * @return -1 on error, return 0 on no update needed, and return 1 on updated needed.
//     */
//    private int isModemFirmwareUpdateNeededThroughPort() {
//        // Issues with this function when it fails. Stops/starts rild too much. Should limit it.
//        // Try to stop rild to communicate with the modem
//        if (!stopRild()) {
//            if (DBG) Log.e(TAG, "Error killing rild. Could not properly update modem firmware.");
//            startRild();
//            return -1;
//        }
//
//        // Try to set up the port to communicate with the modem
//        Port port = new Port("/dev/ttyACM0");
//        if (!port.setupPort()) {
//            port.closePort();
//            startRild();
//            return -1;
//        }
//
//        if (port.testConnection()) {
//            // Check if this modem firmware version needs to be updated.
//            String modemType = port.getModemType();
//            String modemFirmwareVersion = port.getModemVersion();
//
//            if (DBG) Log.i(TAG, "Modem type: " + modemType + ", Modem firmware version: " + modemFirmwareVersion);
//
//            // Populate versions that need updates
//            ArrayList<String> versionsThatNeedUpdate = new ArrayList<>();
//            versionsThatNeedUpdate.add("20.00.034.4");
//            versionsThatNeedUpdate.add("20.00.034.6");
//            versionsThatNeedUpdate.add("20.00.034.10");
//            versionsThatNeedUpdate.add("20.00.522.4");
//            versionsThatNeedUpdate.add("20.00.522.7");
//
//            if (versionsThatNeedUpdate.contains(modemFirmwareVersion)) {
//                // Return update needed
//                port.closePort();
//                startRild();
//
//                // Update shared preferences
//                SharedPreferences sharedPref = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
//                sharedPref.edit().putInt(MODEM_UPDATE_NEEDED_KEY, 1).apply();
//
//                return 1;
//            } else {
//                // Return no update needed
//                port.closePort();
//                startRild();
//
//                // Update shared preferences
//                SharedPreferences sharedPref = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
//                sharedPref.edit().putInt(MODEM_UPDATE_NEEDED_KEY, 0).apply();
//
//                return 0;
//            }
//        } else {
//            // Return error
//            port.closePort();
//            startRild();
//
//            // Update shared preferences
//            SharedPreferences sharedPref = this.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE);
//            sharedPref.edit().putInt(MODEM_UPDATE_NEEDED_KEY, -1).apply();
//
//            return -1;
//        }
//    }
}
