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


    public static final int VIRTUAL_ODOMETER = 20;


    public static final int COUNTER_MESSAGE_SEQUENCE_INDEX = 100;
    public static final int NEXT_HEARTBEAT_TIME_S = 101;  // for checking at boot if it could have been caused by heartbeat
    public static final int NEXT_SCHEDULEDWAKEUP_TIME_S = 102;  // for checking at boot if it could have been caused by heartbeat

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

//        Handler mainHandler = new Handler(Looper.getMainLooper());
  //      mainHandler.post(new Runnable(){
    //        @Override public void run() {
        try {
            SharedPreferences.Editor editor = sharedPref.edit();

            editor.putInt(Integer.toString(state_id), new_value);
            editor.commit();

//                }
            //           }
            //    );
        } catch (Exception e) {
            Log.e(TAG, "Exception: writeState() " + e.toString(), e );
        }
        return true; // OK
    }

    public boolean writeStateLong(final int state_id, final long new_value) {

//        Handler mainHandler = new Handler(Looper.getMainLooper());
  //      mainHandler.post(new Runnable(){
    //        @Override public void run() {
        try {
                SharedPreferences.Editor editor = sharedPref.edit();

                editor.putLong(Integer.toString(state_id), new_value);
                editor.commit();


//                }
  //           }
    //    );
        } catch (Exception e) {
            Log.e(TAG, "Exception: writeStateLong() " + e.toString(), e );
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

    // readStateBool(): get the state info but return a bool instead of int
    public boolean readStateBool(int state_id) {
        int value = sharedPref.getInt(Integer.toString(state_id), 0);
        if (value ==0) return false;
        return true;
    }


} // Class State
