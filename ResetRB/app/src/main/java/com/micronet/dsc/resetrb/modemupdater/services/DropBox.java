package com.micronet.dsc.resetrb.modemupdater.services;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.NetworkIOException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.WriteMode;
import com.micronet.dsc.resetrb.BuildConfig;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Used to connect to dropbox and upload logs when:
 * - ResetRB has started Communitake
 * - ResetRB has cleaned up device after broadcast from LTE Modem Updater
 *
 * This way we know when Communitake has been started and when/if the device has been cleaned up.
 */
class DropBox {

    private final String TAG = "ResetRB-DropBox";
    private final String ACCESS_TOKEN = "LPPT11VZzEAAAAAAAAAA4ynGYT6dCM7XhuMS0YJcgt4fkehBOmlVAJTb8jhRPj3w";
    private final String COMM_STARTED_FILENAME = "CommunitakeStarted";
    private final String COMM_STARTED_DATA = "Started Communitake for first time.";
    private final String DEVICE_CLEANED_FILENAME = "DeviceCleaned";
    private final String DEVICE_CLEANED_DATA = "Device successfully cleaned.";
    private final String ERROR_CHECKING_FILENAME = "ErrorCheckingModemVersion";
    private final String ERROR_CHECKING_DATA = "Error checking modem version is ResetRB.";

    private DbxClientV2 client;
    private String id;

    DropBox() {
        // Create Dropbox client
        DbxRequestConfig config = DbxRequestConfig.newBuilder("LTEModemUpdater/" + BuildConfig.VERSION_NAME).build();
        client = new DbxClientV2(config, ACCESS_TOKEN);

        id = Build.SERIAL;
    }

    // Upload when the ModemUpdaterService has started Communitake for the first time
    boolean uploadStartedCommunitake(String dt) {
        return upload(dt, COMM_STARTED_DATA, COMM_STARTED_FILENAME);
    }

    // Upload after device has been cleaned in the CleanUpService
    boolean uploadCleanedUpDevice(String dt) {
        return upload(dt, DEVICE_CLEANED_DATA, DEVICE_CLEANED_FILENAME);
    }

    // Upload after device has been cleaned in the CleanUpService
    boolean uploadErrorCheckingModemVersion(String dt) {
        return upload(dt, ERROR_CHECKING_DATA, ERROR_CHECKING_FILENAME);
    }

    boolean upload(String dt, String data, String filename) {
        try {
            InputStream in = new ByteArrayInputStream(data.getBytes(Charset.forName("UTF-8")));

            client.files().uploadBuilder("/" + id + "/" + filename + " " + dt + ".txt")
                    .withMode(WriteMode.ADD)
                    .withAutorename(true).uploadAndFinish(in);
        } catch (NetworkIOException e) {
            Log.d(TAG, "Error: no network connection - " + e.toString());
            return false;
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            return false;
        }

        return true;
    }
}
