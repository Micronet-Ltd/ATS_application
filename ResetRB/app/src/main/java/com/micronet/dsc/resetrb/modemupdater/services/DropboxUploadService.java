package com.micronet.dsc.resetrb.modemupdater.services;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.COMM_STARTED_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DBG;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DEVICE_CLEANED_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.ERROR_CHECKING_VERSION_ACTION;
import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.sleep;

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
            if(intent.getAction().equalsIgnoreCase(COMM_STARTED_ACTION) || intent.getAction().equalsIgnoreCase(DEVICE_CLEANED_ACTION) ||
                    intent.getAction().equals(ERROR_CHECKING_VERSION_ACTION)){
                // Get the current dt to upload with log
                String dt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Calendar.getInstance().getTime());
                DropBox dropBox = new DropBox(this);
                for(int i = 0; i < 10; i++) {
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
                    sleep(120000);
                }
            }
        }
    }

    private boolean hasInternetConnection() {
        ConnectivityManager connectivityManager = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivityManager != null){
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            if (networkInfo != null) { // connected to the internet
                return networkInfo.getType() == ConnectivityManager.TYPE_WIFI || networkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
            }
        }
        return false;
    }
}
