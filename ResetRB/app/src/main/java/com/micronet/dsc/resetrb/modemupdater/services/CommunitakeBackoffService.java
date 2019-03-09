package com.micronet.dsc.resetrb.modemupdater.services;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_APP_NAME;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_BACKOFF;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.MODEM_APP_NAME;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.SHARED_PREF_FILE_KEY;

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

public class CommunitakeBackoffService extends IntentService {

    private static final String TAG = "ResetRB-BackoffService";

    public CommunitakeBackoffService() {
        super("CommunitakeBackoffService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(COMM_BACKOFF)){
                // If it isn't within a certain amount of time then try force stopping and starting
                // Communitake again. Should use a relatively low amount of data for check ins.
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

                    try {
                        if(commRunning) {
                            // Force stop Communitake and run it again
                            runShellCommand(new String[]{"am", "force-stop", "com.communitake.mdc.micronet"});

                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }

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
}
