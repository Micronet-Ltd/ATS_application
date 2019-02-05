package com.micronet.dsc.resetrb;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;
import java.util.List;

public class ModemUpdaterService extends IntentService {

    private static final String TAG = "ResetRB-UpdaterService";
    private static final String APP_NAME = "com.micronet.a317modemupdater";

    public ModemUpdaterService() {
        super("ModemUpdaterService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(intent != null){
            // Sleep for initial 10 seconds
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Log.e(TAG, e.toString());
            }

            final Context context = getApplicationContext();

            // Check and see if LTE Modem Updater is installed
            if(isAppInstalled(context)){
                // If LTE Modem Updater isn't running
                if(!isAppRunning(context)){
                    // Launch LTE Modem Updater
                    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(APP_NAME);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        Log.i(TAG, "Started LTE Modem Updater");
                    }
                }else{
                    Log.d(TAG, "LTE Modem Updater is already running.");
                }
            }else{
                Log.d(TAG, "LTE Modem Updater isn't installed.");
            }
        }
    }

    private static boolean isAppRunning(final Context context) {
        final ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if(activityManager != null){
            final List<RunningAppProcessInfo> procInfos = activityManager.getRunningAppProcesses();
            if (procInfos != null)
            {
                for (final ActivityManager.RunningAppProcessInfo processInfo : procInfos) {
                    if (processInfo.processName.equals(APP_NAME)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isAppInstalled(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(APP_NAME, 0);
        } catch (NameNotFoundException e) {
            return false;
        }
        return true;
    }
}
