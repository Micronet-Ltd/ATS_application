package com.micronet.dsc.resetrb.modemupdater.services;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_STARTED;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DEVICE_CLEANED;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class DropboxUploadService extends IntentService {

    private static final String TAG = "ResetRB-DropboxService";

    public DropboxUploadService() {
        super("DropboxUploadService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(COMM_STARTED) || intent.getAction().equalsIgnoreCase(DEVICE_CLEANED)){

                // Get the current dt to upload with log
                String dt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Calendar.getInstance().getTime());

                // Try to upload to state to Dropbox
                if(intent.getAction().equalsIgnoreCase(COMM_STARTED)){
                    DropBox dropBox = new DropBox(this);

                    for(int i = 0; i < 10; i++){
                        if(hasInternetConnection(this)){
                            boolean result = dropBox.uploadStartedCommunitake(dt);

                            if(result) {
                                Log.i(TAG, "Uploaded Communitake started log.");
                                break;
                            }
                        }

                        Log.v(TAG, "Couldn't upload Communitake started log at this time.");

                        // Sleep two minutes before trying again
                        try {
                            Thread.sleep(120000);
                        } catch (InterruptedException e) {
                            Log.e(TAG, e.toString());
                        }
                    }
                }else{
                    DropBox dropBox = new DropBox(this);

                    for(int i = 0; i < 10; i++){
                        if(hasInternetConnection(this)){
                            boolean result = dropBox.uploadCleanedUpDevice(dt);

                            if(result) {
                                Log.i(TAG, "Uploaded device cleaned log.");
                                break;
                            }
                        }

                        Log.v(TAG, "Couldn't upload device cleaned at this time.");

                        // Sleep two minutes before trying again
                        try {
                            Thread.sleep(120000);
                        } catch (InterruptedException e) {
                            Log.e(TAG, e.toString());
                        }
                    }
                }
            }
        }
    }

    private boolean hasInternetConnection(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivityManager != null){
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

            if (networkInfo != null) { // connected to the internet
                if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                    // connected to wifi
                    return true;
                } else if (networkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                    // connected to the mobile provider's data plan
                    return true;
                }
            }
        }
        return false;
    }
}
