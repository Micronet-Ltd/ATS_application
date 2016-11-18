/////////////////////////////////////////////////////////////
// RBCClearer:
//  Clears out the data from the redbend client, allowing it to be regenerated
/////////////////////////////////////////////////////////////


package com.micronet.dsc.resetrb;

import android.util.Log;

/**
 * Created by dschmidt on 8/26/16.
 */
public class RBCClearer {

    public static final String TAG = "ResetRB-RBCClearer";

    //////////////////////////////////////////////////////////////////
    // clearRedbendFiles()
    //  clears out the redbend client files required to reset the client
    //  /data/misc/rb/* and /data/data/com.redbend.client
    //////////////////////////////////////////////////////////////////
    static void clearRedbendFiles() {

        Log.d(TAG, "Clearing redbend client files");
        // since this has a wildcard it must be run in a shell

        String command = "";

        command = "rm -r /data/misc/rb/*";
        Log.d(TAG, "Running " + command);

        try {
            Runtime.getRuntime().exec(new String[] { "sh", "-c", command } ).waitFor();
        } catch (Exception e) {
            Log.d(TAG, "Exception exec: " + command + ": " + e.getMessage());
        }

        // Remove result file from prior UA operations
        command = "rm /data/redbend/result";
        Log.d(TAG, "Running " + command);

        try {
            Runtime.getRuntime().exec(new String[] { "sh", "-c", command } ).waitFor();
        } catch (Exception e) {
            Log.d(TAG, "Exception exec: " + command + ": " + e.getMessage());
        }


        command = "pm clear com.redbend.client";
        Log.d(TAG, "Running " + command);

        try {
            Runtime.getRuntime().exec(command).waitFor();
        } catch (Exception e) {
            Log.d(TAG, "Exception exec " + command + ": " +  e.getMessage());
        }

    } // clearRedbendFiles


}
