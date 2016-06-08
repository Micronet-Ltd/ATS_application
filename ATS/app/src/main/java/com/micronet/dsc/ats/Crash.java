/////////////////////////////////////////////////////////////
// Crash:
//  contains saved state-information flags for just before a crash/restart
//      e.g. flags that have short reset periods and are not normally written unless we know a restart is imminent
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;


public class Crash {

    private static final String TAG = "ATS-Crash";

    private static final String FILENAMEKEY = "crash";

    static final long MAX_ELAPSED_RESTORE_TIME_MS = 10000; // if data is older than 10 seconds, don't restore it



    public static final int CONTINUOUS_IDLING_SECONDS = 1000;
    public static final int LAST_PING_DATA_ARRAY = 1001;

    public static final int FLAG_IDLE_STATUS = 2000;
    public static final int FLAG_SPEEDING_STATUS = 2001;
    public static final int FLAG_ACCELERATING_STATUS = 2002;
    public static final int FLAG_BRAKING_STATUS = 2003;
    public static final int FLAG_CORNERING_STATUS = 2004;


    public static final int DEBOUNCE_BAD_ALTERNATOR = 3000;
    public static final int DEBOUNCE_BAD_LOW_BATTERY = 3001;
    public static final int DEBOUNCE_GP_INPUTS_ARRAY = 3002;
    public static final int RESET_GP_INPUTS_ARRAY = 3003;

    public static final int DEBOUNCE_IDLING = 3010;
    public static final int DEBOUNCE_SPEEDING = 3011;
    public static final int DEBOUNCE_ACCELERATING = 3012;
    public static final int DEBOUNCE_BRAKING = 3013;
    public static final int DEBOUNCE_CORNERING = 3014;


    public static final int WAKELOCK_ELAPSED_IGNITION = 4000;
    public static final int WAKELOCK_ELAPSED_GP_INPUTS_ARRAY = 4001;
    public static final int WAKELOCK_ELAPSED_HEARTBEAT = 4002;
    public static final int WAKELOCK_ELAPSED_SCHEDULED_WAKEUP = 4003;


    Context context;
    SharedPreferences sharedPref;
    SharedPreferences.Editor editor;

    public Crash(Context context) {
        this.context = context;
        sharedPref = context.getSharedPreferences(
                FILENAMEKEY, Context.MODE_PRIVATE);

    }


    ///////////////////////////////////////////////////
    // isRestoreable()
    //  just check if the crash data exists and can be used for a restoration
    //  the data must have been saved within the restore time frame in order to "exist"
    ///////////////////////////////////////////////////
    public boolean isRestoreable() {


        String version = sharedPref.getString("Version", "");
        long saved = sharedPref.getLong("SaveTime", 0);
        long now = SystemClock.elapsedRealtime();

        if (saved == 0) {
            Log.v(TAG, "No saved crash data found");
        } else {
            Log.v(TAG, "Saved data is for " + version + " @ " + saved + " ms; (now=" + now + " ms)");
        }

        // if data is not for our version, it is not restoreable
        if (!version.equals(BuildConfig.VERSION_NAME)) return false;

        // if data was saved too long ago in the past, it is not restoreable

        if ((saved > 0) &&
            (saved <= now) &&
            (saved + MAX_ELAPSED_RESTORE_TIME_MS >  SystemClock.elapsedRealtime())) {
            return true;
        }

        return false;
    } // isRestoreable()


    ///////////////////////////////////////////////////
    // commit()
    //  commits the file and sets the save time
    ///////////////////////////////////////////////////
    public boolean commit() {
        try {
            editor.putString("Version", BuildConfig.VERSION_NAME);
            editor.putLong("SaveTime", SystemClock.elapsedRealtime());
            editor.commit();
        } catch (Exception e) {
            Log.e(TAG, "Exception: commit() " + e.toString(), e );
        }
        return true;
    }

    // edit: you must call edit before calling any write functions
    public boolean edit() {
        try {
            editor = sharedPref.edit();
        } catch (Exception e) {
            Log.e(TAG, "Exception: edit() " + e.toString(), e );
        }
        return true;
    }



    ///////////////////////////////////////////////////
    // clearAll()
    //  deletes ALL crash values
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
    public boolean writeStateInt(final int state_id, final int new_value) {

        try {
            editor.putInt(Integer.toString(state_id), new_value);
        } catch (Exception e) {
            Log.e(TAG, "Exception: writeState() " + e.toString(), e );
        }
        return true; // OK
    }

    public boolean writeStateBool(final int state_id, final boolean new_value) {
        return writeStateInt(state_id, (new_value ? 1 : 0));
    }

    public boolean writeStateLong(final int state_id, final long new_value) {

        try {

            editor.putLong(Integer.toString(state_id), new_value);

        } catch (Exception e) {
            Log.e(TAG, "Exception: writeStateLong() " + e.toString(), e );
        }

        return true; // OK
    }

    ///////////////////////////////////////////////////
    // writeStateArrayLong()
    //  returns a setting like an array of parameters
    ///////////////////////////////////////////////////
    public boolean writeStateArrayLong(final int state_id, final long[] new_value) {
        try {
            int i;
            String new_value_str = "";
            for (i=0; i < new_value.length; i++) {
                if (i !=0) new_value_str += '|';
                new_value_str += Long.toString(new_value[i]);
            }

            editor.putString(Integer.toString(state_id), new_value_str);

        } catch (Exception e) {
            Log.e(TAG, "Exception: writeStateArrayLong() " + e.toString(), e );
        }

        return true; // OK

    } // writeStateArrayLong

    ///////////////////////////////////////////////////
    // writeStateArrayInt()
    //  returns a setting like an array of parameters
    ///////////////////////////////////////////////////
    public boolean writeStateArrayInt(final int state_id, final int[] new_value) {
        try {
            int i;
            String new_value_str = "";
            for (i=0; i < new_value.length; i++) {
                if (i !=0) new_value_str += '|';
                new_value_str += Integer.toString(new_value[i]);
            }

            editor.putString(Integer.toString(state_id), new_value_str);

        } catch (Exception e) {
            Log.e(TAG, "Exception: writeStateArrayInt() " + e.toString(), e );
        }

        return true; // OK

    } // writeStateArrayInt

    ///////////////////////////////////////////////////
    // readStateInt()
    //  returns a value for a particular state
    ///////////////////////////////////////////////////
    public int readStateInt(int state_id) {
        return sharedPref.getInt(Integer.toString(state_id), 0);
    }

    // readStateLong(): get the state info but return a long instead of int
    public long readStateLong(int state_id) {
        return sharedPref.getLong(Integer.toString(state_id), 0);
    }

    // readStateBool(): get the state info but return a bool instead of int
    public boolean readStateBool(int state_id) {
        int value = sharedPref.getInt(Integer.toString(state_id), 0);
        if (value ==0) return false;
        return true;
    }

    ///////////////////////////////////////////////////
    // readStateArray()
    //  returns a setting like an array of parameters
    ///////////////////////////////////////////////////
    public String[] readStateArray(int state_id) {
        String valstring = sharedPref.getString(Integer.toString(state_id), null);

        if (valstring == null) return null;

        String[] val_array = valstring.split("\\|");

        return val_array;

    } // readParameterArray




} // Class Crash

