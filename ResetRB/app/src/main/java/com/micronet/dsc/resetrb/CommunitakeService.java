package com.micronet.dsc.resetrb;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class CommunitakeService extends IntentService {

    private static final String TAG = "ResetRB-CommService";
    private static final String APP_NAME = "com.communitake.mdc.micronet";
    private static final String PINCODE = "6136910101";

    public CommunitakeService() {
        super("CommunitakeService");
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

            // If Communitake isn't running
            if(!isAppRunning(context)){
                Log.i(TAG, "Communitake isn't running.");

                try {
                    // If pincode isn't in place, then put it in place
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
                    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(APP_NAME);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        Log.i(TAG, "Started Communitake");
                    }
                } catch (IOException e) {
                    Log.e(TAG, e.toString());
                }
            }else{
                Log.d(TAG, "Communitake is already running.");
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
}
