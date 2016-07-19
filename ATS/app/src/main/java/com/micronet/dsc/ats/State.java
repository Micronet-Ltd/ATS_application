/////////////////////////////////////////////////////////////
// State:
//  contains saved state-information flags
//      e.g. flags that have long reset periods or cannot just be figured out again soon after reboot.
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;


public class State {

    private static final String TAG = "ATS-State";

    private static final String FILENAMEKEY = "state";

    // I/O

    public static final int FLAG_ENGINE_STATUS = 1;
    public static final int FLAG_LOWBATTERY_STATUS = 2;
    public static final int FLAG_BADALTERNATOR_STATUS = 3;


    public static final int FLAG_USING_INPUT6_AS_IGNITION = 4; // set if we are using input6 as the ignition line

    public static final int FLAG_IGNITIONKEY_INPUT = 10;
    public static final int FLAG_GENERAL_INPUT1 = 11;
    public static final int FLAG_GENERAL_INPUT2 = 12;
    public static final int FLAG_GENERAL_INPUT3 = 13;
    public static final int FLAG_GENERAL_INPUT4 = 14;
    public static final int FLAG_GENERAL_INPUT5 = 15;
    public static final int FLAG_GENERAL_INPUT6 = 16;


    // GPS

    public static final int VIRTUAL_ODOMETER = 20;


    // Engine diagnostics

    public static final int STRING_VIN = 30;   // from engine diagnostics
    public static final int ACTUAL_ODOMETER = 31;   // from engine diagnostics
    public static final int FUEL_CONSUMPTION = 32;   // from engine diagnostics
    public static final int FUEL_ECONOMY = 33;   // from engine diagnostics
    public static final int FLAG_REVERSE_GEAR_STATUS = 34;
    public static final int FLAG_PARKING_BRAKE_STATUS = 35;
    public static final int ARRAY_FAULT_CODES = 36;


    // Internal variables

    public static final int COUNTER_MESSAGE_SEQUENCE_INDEX = 100;
    public static final int NEXT_HEARTBEAT_TIME_S = 101;  // for checking at boot if it could have been caused by heartbeat
                                                          // also used to place data in shutdown message
    public static final int NEXT_SCHEDULEDWAKEUP_TIME_S = 102;  // for checking at boot if it could have been caused by heartbeat
    public static final int LAST_BOOT_TIME = 103; // for saving the time of last boot to determine if we rebooted
    public static final int ENGINE_WARM_START = 104; // for saving whether we've already gotten engine info since last reboot


    // regarding which engine bus and addressing info
    public static final int J1939_BUS_TYPE = 110;
    public static final int J1939_BUS_ADDRESS = 111;




    public static final int VERSION_SPECIFIC_CODE = 200; // can be used to see if we executed code needed to run once for this version

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
