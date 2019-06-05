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
 *
 * Backoff service should be handled in this class.
 */
class DropBox {

    private final String TAG = "ResetRB-DropBox";
    private final String ACCESS_TOKEN = "LPPT11VZzEAAAAAAAAAAU47-w7F3dzDyGLmL0IagOX5HsECjVqkVRUa6Rum2vGam";
    private DbxClientV2 client;

    DropBox(Context context) {
        // Create Dropbox client
        DbxRequestConfig config = DbxRequestConfig.newBuilder("A317ModemUpdater/" + BuildConfig.VERSION_NAME).build();
        client = new DbxClientV2(config, ACCESS_TOKEN);
    }

    // Upload when the ModemUpdaterService has started Communitake for the first time
    boolean uploadStartedCommunitake(String dt) {
        try {
            String id = Build.SERIAL;
            InputStream in = new ByteArrayInputStream(("Started Communitake for first time.")
                    .getBytes(Charset.forName("UTF-8")));

            FileMetadata metadata = client.files().uploadBuilder("/a317ModemUpdater/" + id + "/CommunitakeStarted " + dt + ".txt")
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

    // Upload after device has been cleaned in the CleanUpService
    boolean uploadCleanedUpDevice(String dt) {
        try {
            String id = Build.SERIAL;
            InputStream in = new ByteArrayInputStream(("Device successfully cleaned.")
                    .getBytes(Charset.forName("UTF-8")));

            FileMetadata metadata = client.files().uploadBuilder("/a317ModemUpdater/" + id + "/DeviceCleaned " + dt + ".txt")
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

    // Upload after device has been cleaned in the CleanUpService
    boolean uploadErrorCheckingModemVersion(String dt) {
        try {
            String id = Build.SERIAL;
            InputStream in = new ByteArrayInputStream(("Error checking modem version is ResetRB.")
                    .getBytes(Charset.forName("UTF-8")));

            FileMetadata metadata = client.files().uploadBuilder("/a317ModemUpdater/" + id + "/ErrorCheckingModemVersion " + dt + ".txt")
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
