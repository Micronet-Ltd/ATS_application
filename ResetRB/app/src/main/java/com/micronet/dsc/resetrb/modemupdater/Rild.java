package com.micronet.dsc.resetrb.modemupdater;

import static com.micronet.dsc.resetrb.modemupdater.ModemUpdaterService.DBG;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Contains static methods for starting and stopping rild
 */
class Rild {

    private final static String TAG = "ResetRB-Rild";

    /**
     * Tries to start rild, retries up to 10 times.
     *
     * @return Whether or not rild was started.
     */
    static boolean startRild() {
        for (int i = 0; i < 10; i++) {
            try {
                // Start rild
                runShellCommand(new String[]{"/system/bin/setprop", "ctl.start", "ril-daemon"});

                // Check to make sure that rild is running
                String output = runShellCommand(new String[]{"/system/bin/getprop"});

                if (output.toLowerCase().contains("[init.svc.ril-daemon]: [running]")) {
                    if (DBG) Log.i(TAG, "Rild started");
                    return true;
                } else {
                    if (DBG) Log.d(TAG, "Rild not started correctly, trying again.");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        if (DBG) Log.e(TAG, e.toString());
                    }
                }
            } catch (IOException e) {
                if (DBG) Log.e(TAG, "Error rild not started correctly" + e.toString());
                return false;
            }
        }

        if (DBG) Log.e(TAG, "Error rild not started correctly.");
        return false;
    }

    /**
     * Tries to kill rild, retries up to 10 times.
     *
     * @return Whether or not rild was killed.
     */
    static boolean stopRild() {
        for (int i = 0; i < 10; i++) {
            try {
                // Start rild
                runShellCommand(new String[]{"/system/bin/setprop", "ctl.stop", "ril-daemon"});

                // Check to make sure that rild is running
                String output = runShellCommand(new String[]{"/system/bin/getprop"});

                if (output.toLowerCase().contains("[init.svc.ril-daemon]: [stopped]")) {
                    if (DBG) Log.i(TAG, "Rild stopped");
                    return true;
                } else {
                    if (DBG) Log.d(TAG, "Rild not stopped correctly, trying again.");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        if (DBG) Log.e(TAG, e.toString());
                    }
                }
            } catch (IOException e) {
                if (DBG) Log.e(TAG, "Error rild not stopped correctly" + e.toString());
                return false;
            }
        }

        if (DBG) Log.e(TAG, "Error rild not stopped correctly");
        return false;
    }

    private static String runShellCommand(String[] commands) throws IOException {
        StringBuilder sb = new StringBuilder();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec(commands).getInputStream()));
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            sb.append(line);
        }

        bufferedReader.close();

        return sb.toString();
    }
}
