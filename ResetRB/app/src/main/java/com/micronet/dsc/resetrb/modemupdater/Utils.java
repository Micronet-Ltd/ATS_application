package com.micronet.dsc.resetrb.modemupdater;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DBG;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public class Utils {

    private static final String TAG = "ResetRB-Utils";

    // Package strings
    public static final String COMMUNITAKE_PACKAGE = "package:com.communitake.mdc.micronet";
    public static final String RESETRB_PACKAGE = "package:com.micronet.dsc.resetrb";
    public static final String UPDATER_PACKAGE = "package:com.micronet.a317modemupdater";

    // Keys
    public static final String SHARED_PREF_FILE_KEY = "ModemUpdaterService";
    public static final String MODEM_UPDATE_NEEDED_KEY = "ModemFirmwareUpdateNeeded";
    public static final String MODEM_UPDATE_PROCESS_STARTED_KEY = "UpdateProcessStarted";
    public static final String MODEM_UPDATED_AND_CLEANED_KEY = "ModemUpdatedAndDeviceCleaned";
    public static final String MODEM_UPDATER_STARTS = "LteModemUpdaterStarts";
    public static final String ERROR_CHECKING_MODEM_KEY = "ErrorCheckingModem";
    public static final String ERROR_COULD_NOT_CHECK_MODEM_KEY = "ErrorCouldNotCheckModemMax";
    public static final String PREVIOUS_PINCODE = "PreviousPinCode";
    public static final String FIRST_PINCODE_CHECK = "FirstPinCodeCheck";
    public static final String FIRST_COMM_CHECK = "FirstCommCheck";
    public static final String UPDATED_COMM_ALREADY_INSTALLED = "UpdatedCommAlreadyInstalled";

    public static final String PIN_CODE_PATH = "data/internal_Storage/Gsd/pincode.txt";

    // Private constructor
    private Utils(){}

    public synchronized static boolean getBoolean(Context context, String key, boolean defaultVal) {
        if (context != null && key != null) {
            return context.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).getBoolean(key, defaultVal);
        } else {
            return defaultVal;
        }
    }

    public synchronized static void putBoolean(Context context, String key, boolean val) {
        if (context != null && key != null) {
            context.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).edit().putBoolean(key, val).apply();
        }
    }

    public synchronized static int getInt(Context context, String key, int defaultVal) {
        if (context != null && key != null) {
            return context.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).getInt(key, defaultVal);
        } else {
            return defaultVal;
        }
    }

    public synchronized static void putInt(Context context, String key, int val) {
        if (context != null && key != null) {
            context.getSharedPreferences(SHARED_PREF_FILE_KEY, Context.MODE_PRIVATE).edit().putInt(key, val).apply();
        }
    }

    public static void sleep(long ms){
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Log.e(TAG, e.toString());
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
}
