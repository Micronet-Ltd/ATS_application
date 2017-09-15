/////////////////////////////////////////////////////////////
// RBCClearer:
//  Clears out the data from the redbend client, allowing it to be regenerated
/////////////////////////////////////////////////////////////


package com.micronet.dsc.resetrb;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Created by dschmidt on 8/26/16.
 */
public class RBCClearer {

    public static final String TAG = "ResetRB-RBCClearer";


    static final String TREE_XML_FILE_PATH = "/data/data/com.redbend.client/files/tree.xml";

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




    /**
     * Does /data/data/com.redbend.client/files/tree.xml exist with a zero byte length?
     *      (this is a known problem condition)
     * @return
     */
    static boolean isTreeXmlZeroBytes() {

        // This is done via ls command (and not java) because we need to su first

        Log.d(TAG, "Checking " + TREE_XML_FILE_PATH + " for a zero-byte length");

        String command;
        command = "su -c 'ls -s " + TREE_XML_FILE_PATH + "'";
        // returns <size_in_blocks><space><file_name>


        String result = null;
        int exitCode = -1;

        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "sh", "-c", command } );

            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));

            result = in.readLine();

            exitCode = p.waitFor();

            in.close();
        } catch (Exception e) {
            Log.e(TAG, "Exception running command: " + command + " : " + e.getMessage());
        }

        if (exitCode != 0) {
            Log.w(TAG, TREE_XML_FILE_PATH + " does not exist");
            return false; // it is not existing with zero bytes because it does not yet exist.
        }

        if ((result != null) && (!result.isEmpty())) {

            String[] splits = result.split(" ", 2);
            int filesize = -1; // non-zero value
            try {
                filesize = Integer.parseInt(splits[0]);
            } catch (Exception e) {
                Log.e(TAG, "command " + command + " returns '" + result + "' which is not parseable as integer");
                return false; //assume we are not zero-byte length
            }

            if (filesize == 0) {
                Log.w(TAG, TREE_XML_FILE_PATH + " has zero bytes length!");
                return true;
            }
        }

        Log.d(TAG, TREE_XML_FILE_PATH + " has non-zero length.");
        return false; // does not have a zero length
    } // isTreeXmlZeroBytes()

} // class
