package com.micronet.dsc.resetrb.modemupdater;

import static com.micronet.dsc.resetrb.modemupdater.Utils.COMMUNITAKE_PACKAGE;
import static com.micronet.dsc.resetrb.modemupdater.Utils.ERROR_CHECKING_MODEM_KEY;
import static com.micronet.dsc.resetrb.modemupdater.Utils.ERROR_COULD_NOT_CHECK_MODEM_KEY;
import static com.micronet.dsc.resetrb.modemupdater.Utils.MODEM_UPDATED_AND_CLEANED_KEY;
import static com.micronet.dsc.resetrb.modemupdater.Utils.MODEM_UPDATER_STARTS;
import static com.micronet.dsc.resetrb.modemupdater.Utils.MODEM_UPDATE_NEEDED_KEY;
import static com.micronet.dsc.resetrb.modemupdater.Utils.MODEM_UPDATE_PROCESS_STARTED_KEY;
import static com.micronet.dsc.resetrb.modemupdater.Utils.RESETRB_PACKAGE;
import static com.micronet.dsc.resetrb.modemupdater.Utils.UPDATER_PACKAGE;
import static com.micronet.dsc.resetrb.modemupdater.Utils.getBoolean;
import static com.micronet.dsc.resetrb.modemupdater.Utils.getInt;
import static com.micronet.dsc.resetrb.modemupdater.Utils.isAppInstalled;
import static com.micronet.dsc.resetrb.modemupdater.Utils.isAppRunning;
import static com.micronet.dsc.resetrb.modemupdater.Utils.putBoolean;
import static com.micronet.dsc.resetrb.modemupdater.Utils.putInt;
import static com.micronet.dsc.resetrb.modemupdater.Utils.sleep;

import android.app.IntentService;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.micronet.dsc.resetrb.modemupdater.services.CleanUpService;
import com.micronet.dsc.resetrb.modemupdater.services.CommunitakeBackoffService;
import com.micronet.dsc.resetrb.modemupdater.services.DropboxUploadService;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Service that checks modem firmware version and starts update process if needed.
 */
public class ModemUpdaterService extends IntentService {

    private static final String TAG = "ResetRB-UpdaterService";
    public static final boolean DBG = true;

    // Pin code
    private static final String PIN_CODE = "3983605404";

    // Sleep times
    private static final int INITIAL_WAIT = 60000;
    private static final int PIN_CODE_CREATION_WAIT = 1000;
    private static final int COMM_INITIAL_WAIT = 15000;
    private static final int PIN_CODE_POST_WAIT = 5000;

    // Update states
    private static final int UPDATE_NEEDED_UNKNOWN = -1;
    private static final int UPDATE_NEEDED_NO = 0;
    private static final int UPDATE_NEEDED_YES = 1;

    // Attempts
    private static final int MAX_UPDATER_STARTS = 15;
    private static final int NUM_PIN_CODE_WRITE_ATTEMPTS = 5;

    // App names
    public static final String MODEM_APP_NAME = "com.micronet.a317modemupdater";
    public static final String COMM_APP_NAME = "com.communitake.mdc.micronet";

    // Versions
    public static final String ATT_FIRMWARE = "20.00.522";
    public static final String VERIZON_FIRMWARE = "20.00.034";
    public static final String UPDATED_MANAGE_VERSION = "9.3.18";

    // Actions
    public static final String UPDATE_SUCCESSFUL_ACTION = "com.micronet.dsc.resetrb.modemupdater.UPDATE_SUCCESSFUL_ACTION";
    public static final String COMM_BACKOFF_ACTION = "com.micronet.dsc.resetrb.modemupdater.COMM_BACKOFF_ACTION";
    public static final String COMM_STARTED_ACTION = "com.micronet.dsc.resetrb.modemupdater.COMM_STARTED_ACTION";
    public static final String DEVICE_CLEANED_ACTION = "com.micronet.dsc.resetrb.modemupdater.DEVICE_CLEANED_ACTION";
    public static final String ERROR_CHECKING_VERSION_ACTION = "com.micronet.dsc.resetrb.modemupdater.ERROR_CHECKING_VERSION";

    public static final int MAX_NUMBER_OF_CHECKS = 5;
    public static final int NUMBER_STARTS_BEFORE_COMM_START = 2;


    public ModemUpdaterService() {
        super("ModemUpdaterService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            // Sleep 60 seconds so everything starts up and is running
            sleep(INITIAL_WAIT);

            // Check to make sure not already updated and cleaned
            boolean updatedAndCleaned = getBoolean(this, MODEM_UPDATED_AND_CLEANED_KEY, false);
            if (updatedAndCleaned) {
                Log.d(TAG, "Already updated and cleaned. Returning from Modem Updater Service.");
                return;
            }

            // Check if update is needed
            int result = getInt(this, MODEM_UPDATE_NEEDED_KEY, UPDATE_NEEDED_UNKNOWN);
            if (result == UPDATE_NEEDED_UNKNOWN) {
                result = isModemFirmwareUpdateNeededThroughSettings();
            }

            switch (result) {
                case UPDATE_NEEDED_YES: // If an update is needed then handle broadcast and start needed apps
                    if (intent.getAction()
                            .equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)) { // Boot Completed Intent, start Communitake and Modem Updater
                        if (DBG) Log.i(TAG, "Trying to start Communitake Service if updated");
                        startCommunitake();

                        if (DBG) Log.i(TAG, "Trying to start LTE Modem Updater");
                        startModemUpdater();
                    } else if (intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_REPLACED)) { // New version of ResetRB installed
                        if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase(RESETRB_PACKAGE)) {
                            if (DBG) Log.i(TAG, "Trying to start Communitake Service if updated");
                            startCommunitake();
                            startModemUpdater();
                        } else if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase(COMMUNITAKE_PACKAGE)) {
                            if (DBG) Log.i(TAG, "Trying to start Communitake Service if updated");
                            startCommunitake();
                        }
                    } else if (intent.getAction().equalsIgnoreCase(Intent.ACTION_PACKAGE_ADDED)) { // LTE Modem Updater just installed
                        if (intent.getDataString() != null && intent.getDataString().equalsIgnoreCase(UPDATER_PACKAGE)) {
                            if (DBG) Log.i(TAG, "Trying to start LTE Modem Updater");
                            startModemUpdater();
                        }
                    }
                    break;
                case UPDATE_NEEDED_UNKNOWN:
                    if (DBG) Log.e(TAG, "Error checking modem firmware version.");

                    int numberOfTimesChecked = getInt(this, ERROR_CHECKING_MODEM_KEY, 0);
                    if(numberOfTimesChecked < MAX_NUMBER_OF_CHECKS){
                        // Try to report error to dropbox
                        Intent dropboxUploadService = new Intent(this, DropboxUploadService.class);
                        dropboxUploadService.setAction(ERROR_CHECKING_VERSION_ACTION);
                        this.startService(dropboxUploadService);

                        // Increment check counter
                        putInt(this, ERROR_CHECKING_MODEM_KEY, numberOfTimesChecked+1);
                    } else {
                        // Stop checking because this isn't working
                        putBoolean(this, ERROR_COULD_NOT_CHECK_MODEM_KEY, true);
                    }
                    break;
                case UPDATE_NEEDED_NO:
                    // If LTE Modem Updater is installed then start the modem updater
                    if (isAppInstalled(this, MODEM_APP_NAME)) {
                        // It's likely the logs haven't been updated so start modem updater
                        if (DBG) Log.i(TAG, "No modem firmware update needed but LTE Modem Updater is installed. Starting Modem Updater to upload logs.");
                        startModemUpdater();
                    } else {
                        if (DBG) Log.i(TAG, "Modem firmware already updated.");
                        Intent modemCleanUpService = new Intent(this, CleanUpService.class);
                        modemCleanUpService.setAction(UPDATE_SUCCESSFUL_ACTION);
                        this.startService(modemCleanUpService);
                        if (DBG) Log.i(TAG, "Started Modem Updater Clean Up Service.");
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
                // Do this to make sure that the device doesn't end up in a continuous reboot loop if the modem can't be updated
                int numberOfTimesStarted = getInt(this, MODEM_UPDATER_STARTS, 0);
                if (numberOfTimesStarted > MAX_UPDATER_STARTS){
                    if (DBG) Log.e(TAG, "Already passed max amount of Modem Updater starts.");
                    return;
                }
                putInt(this, MODEM_UPDATER_STARTS, numberOfTimesStarted + 1);

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
        // If modem updater has been started multiple times then that means it erred out updating
        int numberOfModemUpdaterStarts = getInt(this, MODEM_UPDATER_STARTS, 0);
        if (DBG) Log.d(TAG, "Number of LTE Modem Updater starts: " + numberOfModemUpdaterStarts);
        if (numberOfModemUpdaterStarts <= NUMBER_STARTS_BEFORE_COMM_START) {
            return;
        }

        // Check if Manage v9.3.18 is installed. If it isn't then don't start CommuniTake
        if(!isUpdatedManageInstalled()){
            Log.d(TAG, "Updated manage is not installed. Not starting CommuniTake");
            return;
        }

        sleep(COMM_INITIAL_WAIT);

        try {
            writePincode();

            // Then launch Communitake
            Intent launchIntent = this.getPackageManager().getLaunchIntentForPackage(COMM_APP_NAME);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(launchIntent);
                if (DBG) Log.i(TAG, "Sent intent to start Communitake");

                // Update shared preferences
                putBoolean(this, MODEM_UPDATE_PROCESS_STARTED_KEY, true);

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

    ////////////////////
    // Helper methods
    ////////////////////

    private void writePincode() throws IOException {
        File pincodeFile = new File("data/internal_Storage/Gsd/pincode.txt");

        // Delete pincode if it already exists
        if(pincodeFile.exists()) {
            boolean deleted = pincodeFile.delete();
            if (deleted) {
                if (DBG) Log.d(TAG, "Deleted old pincode");
            } else {
                if (DBG) Log.e(TAG, "Error deleting old pincode");
            }
            sleep(PIN_CODE_CREATION_WAIT);
        }

        // Write pincode out
        FileWriter fileWriter = new FileWriter(pincodeFile);
        fileWriter.write(PIN_CODE);
        fileWriter.flush();
        fileWriter.close();
        if (DBG) Log.i(TAG, "Wrote communitake pincode to file.");
        sleep(PIN_CODE_CREATION_WAIT);
    }

    private boolean isUpdatedManageInstalled() {
        try {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(COMM_APP_NAME, 0);
            String version = pInfo.versionName;
            Log.d(TAG, "Manage version is: " + version);
            return version.equalsIgnoreCase(UPDATED_MANAGE_VERSION);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, e.toString());
        }

        return false;
    }

    // Return -1 on error, return 0 on no update needed, and return 1 on updated needed.
    private int isModemFirmwareUpdateNeededThroughSettings() {
        // Get the modem baseband version from settings
        String radioVersion = Build.getRadioVersion();

        if (radioVersion != null && !radioVersion.trim().isEmpty()) {
            if (DBG) Log.i(TAG, "Modem firmware version: " + radioVersion);

            // Populate versions that need updates, radio version does not contain the extended version number
            ArrayList<String> versionsThatNeedUpdate = new ArrayList<>();
            versionsThatNeedUpdate.add(ATT_FIRMWARE);
            versionsThatNeedUpdate.add(VERIZON_FIRMWARE);

            if (versionsThatNeedUpdate.contains(radioVersion)) {
                // Update needed, update shared preferences
                putInt(this, MODEM_UPDATE_NEEDED_KEY, UPDATE_NEEDED_YES);
                return UPDATE_NEEDED_YES;
            } else {
                // Update not needed, update shared preferences
                putInt(this, MODEM_UPDATE_NEEDED_KEY, UPDATE_NEEDED_NO);
                return UPDATE_NEEDED_NO;
            }
        } else {
            // Return error, update shared preferences
            putInt(this, MODEM_UPDATE_NEEDED_KEY, UPDATE_NEEDED_UNKNOWN);
            return UPDATE_NEEDED_UNKNOWN;
        }
    }
}
