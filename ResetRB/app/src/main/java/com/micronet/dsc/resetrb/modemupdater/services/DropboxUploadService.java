package com.micronet.dsc.resetrb.modemupdater.services;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_STARTED_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DBG;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DEVICE_CLEANED_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.ERROR_CHECKING_VERSION_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.Utils.sleep;

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

    private static final int MAX_NUM_UPLOAD_TRIES = 10;
    private static final int WAIT_BETWEEN_UPLOAD_RETRIES = 120000;

    public DropboxUploadService() {
        super("DropboxUploadService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(intent.getAction() != null) {
            if(intent.getAction().equalsIgnoreCase(COMM_STARTED_ACTION) || intent.getAction().equalsIgnoreCase(DEVICE_CLEANED_ACTION) ||
                    intent.getAction().equals(ERROR_CHECKING_VERSION_ACTION)){
                // Get the current dt to upload with log
                String dt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Calendar.getInstance().getTime());
                DropBox dropBox = new DropBox();
                for(int i = 0; i < MAX_NUM_UPLOAD_TRIES; i++) {
                    if(hasInternetConnection()){
                        boolean result = false;
                        switch(intent.getAction()) {
                            case COMM_STARTED_ACTION:
                                result = dropBox.uploadStartedCommunitake(dt);
                                break;
                            case DEVICE_CLEANED_ACTION:
                                result = dropBox.uploadCleanedUpDevice(dt);
                                break;
                            case ERROR_CHECKING_VERSION_ACTION:
                                result = dropBox.uploadErrorCheckingModemVersion(dt);
                                break;
                        }

                        if(result) {
                            if (DBG) Log.i(TAG, "Uploaded logs for action: " + intent.getAction());
                            break;
                        }
                    }

                    // Sleep two minutes before trying again
                    if (DBG) Log.v(TAG, "Couldn't upload logs at this time for action: " + intent.getAction());
                    sleep(WAIT_BETWEEN_UPLOAD_RETRIES);
                }
            }
        }
    }

    private boolean hasInternetConnection() {
        return true;

//        ConnectivityManager connectivityManager = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
//        if(connectivityManager != null){
//            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
//            if (networkInfo != null) {
//                // Make sure you also have a data connection to the internet.
//                if (!networkInfo.isConnected()) {
//                    return false;
//                }
//
//                return networkInfo.getType() == ConnectivityManager.TYPE_WIFI || networkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
//            }
//        }
//        return false;
    }
}
