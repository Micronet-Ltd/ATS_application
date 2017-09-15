/////////////////////////////////////////////////////////////
// RBCReceiver:
//  Receives messages from the redbend client
/////////////////////////////////////////////////////////////
package com.micronet.dsc.resetrb;;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;


//This is registered by setting the alarm in Power class
public class RBCReceiver extends BroadcastReceiver {
    // Here is where we are receiving our alarm.
    //  Information about this should also be put in the manifest.

    // Note if we were powered down then we don't get this, we will get a boot notice instead (see BootReceiver)

    public static final String TAG = "ResetRB-RBCReceiver";


    // This is broadcast by the modified redbend client at startup
    public static final String BROADCAST_RBC_STARTUP = "com.redbend.client.micronet.STARTING";
    public static final String EXTRA_STRING_VERSION = "VERSION";


    // For shared preferences

    public final String MY_PREFS_NAME = "rbc"; // name of the shared preferences


    @Override
    public void onReceive(Context context, Intent intent)
    {

        //Log.d(TAG, "Heartbeat Alarm rcv " + intent.getAction());

        String action = intent.getAction();
        if (action.equals(BROADCAST_RBC_STARTUP)) {

            String version = intent.getStringExtra(EXTRA_STRING_VERSION);

            Log.d(TAG, "Received " + action + " from RBC with version = " + version);

            // We want to check the version, and if it is different, then we want to reset the client files so they can be regenerated

            if (checkVersionChanged(context, version)) {
                RBCClearer.clearRedbendFiles();
                rememberVersion(context, version);
            }

            // but if the tree.xml is ever zero bytes long, clear it out.
            if (RBCClearer.isTreeXmlZeroBytes()) {
                RBCClearer.clearRedbendFiles();
            }

        }

        else
        {
            Log.d(TAG, "Unknown action: " + action);
        }


    } // OnReceive()



    ////////////////////////////////////////////////////////////////////
    // checkVersionChanged()
    // Is the new client version different from what we have remembered?
    // Is that difference significant (not just the portion after the "-MN")?
    ////////////////////////////////////////////////////////////////////
    boolean checkVersionChanged(Context context, String new_version) {

        SharedPreferences prefs = context.getSharedPreferences(MY_PREFS_NAME, context.MODE_PRIVATE);

        String old_version;

        old_version = prefs.getString("version", "");

        // only compare portion before the -MN. e.g. "10.2.1.7-MN-2" and "10.2.1.7-MN-3" will be considered identical

        String[] old_splits = old_version.split("-MN");
        String[] new_splits = new_version.split("-MN");

        Log.d(TAG, "Comparing Base Redbend Client versions: " + old_splits[0] + " to " + new_splits[0]);

        if (old_splits[0].equals(new_splits[0])) return false;

        Log.d(TAG, "Base Redbend Client version has changed. Old version was = " + old_version);

        return true;

    } // checkVersionChanged()


    void rememberVersion(Context context, String new_version) {

        SharedPreferences.Editor editor = context.getSharedPreferences(MY_PREFS_NAME, context.MODE_PRIVATE).edit();

        editor.putString("version", new_version);
        editor.apply();
    } // rememberVersion




} // class

