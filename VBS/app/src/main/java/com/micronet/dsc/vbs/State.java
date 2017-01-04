/////////////////////////////////////////////////////////////
// State:
//  contains saved state-information flags
//      e.g. flags that have long reset periods or cannot just be figured out again soon after reboot.
/////////////////////////////////////////////////////////////

package com.micronet.dsc.vbs;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;


public class State {

    private static final String TAG = "ATS-VBS-State";

    private static final String FILENAMEKEY = "state";

    // I/O

    public static final int FLAG_CAN_ON = 201;
    public static final int FLAG_CAN_LISTENONLY = 202; // deprecated in favor of AUTODETECT
    public static final int CAN_BITRATE = 203;
    public static final int CAN_FILTER_IDS = 204;
    public static final int CAN_FILTER_MASKS = 205;

    public static final int FLAG_CAN_AUTODETECT = 206;

    public static final int CAN_CONFIRMED_BITRATE = 207;    // prior bitrate that was confirmed (so we don't need listen only)

    public static final int FLAG_J1708_ON = 210;


    Context context;
    SharedPreferences sharedPref;

    public State(Context context) {
        this.context = context;
        sharedPref = context.getSharedPreferences(
                FILENAMEKEY, Context.MODE_PRIVATE);

    }



    ///////////////////////////////////////////////////
    // clearAll()
    //  deletes ALL state settings and restores factory default
    ///////////////////////////////////////////////////
    public void clearAll() {
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.clear().commit();
    }


    ///////////////////////////////////////////////////
    // writeState()
    //   writes a value for the state setting
    //  returns : true if it was written, false if it was not
    ///////////////////////////////////////////////////
    public boolean writeState(final int state_id, final int new_value) {

        try {
            SharedPreferences.Editor editor = sharedPref.edit();

            editor.putInt(Integer.toString(state_id), new_value);
            editor.commit();

        } catch (Exception e) {
            Log.e(TAG, "Exception: writeState() " + e.toString(), e );
        }
        return true; // OK
    }


    public boolean writeStateArray(final int state_id, final byte[] new_value) {

        try {
            SharedPreferences.Editor editor = sharedPref.edit();

            String newString;
            newString = Log.bytesToHex(new_value, new_value.length);
            editor.putString(Integer.toString(state_id), newString);

            editor.commit();

        } catch (Exception e) {
            Log.e(TAG, "Exception: writeStateArray() " + e.toString(), e );
        }
        return true; // OK
    }


    public boolean writeStateLong(final int state_id, final long new_value) {

        try {
                SharedPreferences.Editor editor = sharedPref.edit();

                editor.putLong(Integer.toString(state_id), new_value);
                editor.commit();

        } catch (Exception e) {
            Log.e(TAG, "Exception: writeStateLong() " + e.toString(), e );
        }

        return true; // OK
    }

    public boolean writeStateString(final int state_id, final String new_value) {

        try {
            SharedPreferences.Editor editor = sharedPref.edit();

            editor.putString(Integer.toString(state_id), new_value);
            editor.commit();

        } catch (Exception e) {
            Log.e(TAG, "Exception: writeStateString() " + e.toString(), e );
        }

        return true; // OK
    }


    ///////////////////////////////////////////////////
    // readState()
    //  returns a value for a particular state
    ///////////////////////////////////////////////////
    public int readState(int state_id) {
        return sharedPref.getInt(Integer.toString(state_id), 0);
    }

    // readStateLong(): get the state info but return a long instead of int
    public long readStateLong(int state_id) {
        return sharedPref.getLong(Integer.toString(state_id), 0);
    }

    // readStateString(): get the state info but return a long instead of int
    public String readStateString(int state_id) {
        return sharedPref.getString(Integer.toString(state_id), "");
    }


    // readStateBool(): get the state info but return a bool instead of int
    public boolean readStateBool(int state_id) {
        int value = sharedPref.getInt(Integer.toString(state_id), 0);
        if (value ==0) return false;
        return true;
    }

    // readStateArray(): get the state info but return a byte[] instead of int
    public byte[] readStateArray(int state_id) {
        String value = sharedPref.getString(Integer.toString(state_id), "");
        if (value.isEmpty()) return null;

        byte[] array = Log.hexToBytes(value);

        return array;
    }

} // Class State
