/////////////////////////////////////////////////////////////
// Config:
//  contains saved configuration parameters
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;


import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;


public class Config {

    private static final String TAG = "ATS-Config";

    private static final String FILENAMEKEY = "configuration";

    // Paths of the files (used for copying from the alternate to the real location)
    private static final String FILENAME_ALTERNATE_PATH = "/internal_Storage/ATS";
    private static final String FILENAME_STANDARD_PATH = "/data/data/" + BuildConfig.APPLICATION_ID + "/shared_prefs";

    // Units are seconds or meters unless otherwise indicated

    public static final int SETTING_SERVER_ADDRESS = 1; // Destination Server IP  | Server Port
        public static final int PARAMETER_SERVER_ADDRESS_IP = 0;
        public static final int PARAMETER_SERVER_ADDRESS_PORT = 1;
    public static final int SETTING_LOCAL_PORT = 2; // Port
        public static final int PARAMETER_LOCAL_ADDRESS_PORT = 0;
    public static final int SETTING_BACKOFF_RETRIES = 3; // Array of min times (seconds) between consecutive send attempts
    public static final int SETTING_HEARTBEAT = 4; // When to wake up for heartbeat
        public static final int PARAMETER_HEARTBEAT_TRIGGER_TOD = 0; // time-of-day to trigger
        public static final int PARAMETER_HEARTBEAT_KEEPAWAKE = 1; // how long to keep awake
    public static final int SETTING_PING = 5; // pings while ignition on
        public static final int PARAMETER_PING_SECONDS_MOVING = 0;
        public static final int PARAMETER_PING_METERS_MOVING = 1;
        public static final int PARAMETER_PING_DEGREES_MOVING = 2;
        public static final int PARAMETER_PING_SECONDS_NOTMOVING = 3;
    public static final int SETTING_SCHEDULED_WAKEUP = 6; // When to wake up for heartbeat
        public static final int PARAMETER_SCHEDULED_WAKEUP_TRIGGER_TOD = 0; // time-of-day to trigger
        public static final int PARAMETER_SCHEDULED_WAKEUP_KEEPAWAKE = 1; // how long to keep awake
    public static final int SETTING_ENGINE_STATUS = 7;
        public static final int PARAMETER_ENGINE_STATUS_VOLTAGE = 0;
        public static final int PARAMETER_ENGINE_STATUS_MESSAGES = 1;
    public static final int SETTING_LOW_BATTERY_STATUS = 8;
        public static final int PARAMETER_LOW_BATTERY_STATUS_VOLTAGE = 0;
        public static final int PARAMETER_LOW_BATTERY_STATUS_SECONDS_LOWER = 1;
        public static final int PARAMETER_LOW_BATTERY_STATUS_SECONDS_HIGHER = 2;
        public static final int PARAMETER_LOW_BATTERY_STATUS_MESSAGES = 3;
    public static final int SETTING_BAD_ALTERNATOR_STATUS = 9;
        public static final int PARAMETER_BAD_ALTERNATOR_STATUS_VOLTAGE = 0;
        public static final int PARAMETER_BAD_ALTERNATOR_STATUS_SECONDS_LOWER = 1;
        public static final int PARAMETER_BAD_ALTERNATOR_STATUS_SECONDS_HIGHER = 2;
        public static final int PARAMETER_BAD_ALTERNATOR_STATUS_MESSAGES = 3;
    public static final int SETTING_INPUT_IGNITION = 10;
        public static final int PARAMETER_INPUT_IGNITION_SECONDS_WAKE = 0;
        public static final int PARAMETER_INPUT_IGNITION_MESSAGES = 1;
    public static final int SETTING_INPUT_GP1 = 11;
    public static final int SETTING_INPUT_GP2 = 12;
    public static final int SETTING_INPUT_GP3 = 13;
    public static final int SETTING_INPUT_GP4 = 14;
    public static final int SETTING_INPUT_GP5 = 15;
    public static final int SETTING_INPUT_GP6 = 24;
        public static final int PARAMETER_INPUT_GP_BIAS = 0;
        public static final int PARAMETER_INPUT_GP_TENTHS_DEBOUNCE_TOACTIVE = 1;
        public static final int PARAMETER_INPUT_GP_TENTHS_RESET_AFTER_OFF = 2;
        public static final int PARAMETER_INPUT_GP_SECONDS_WAKE = 3;
        public static final int PARAMETER_INPUT_GP_MESSAGES = 4;
        public static final int PARAMETER_INPUT_GP_TENTHS_DEBOUNCE_TOINACTIVE = 5;
    public static final int SETTING_MOVING_THRESHOLD = 17;
        public static final int PARAMETER_MOVING_THRESHOLD_CMS = 0; // speed cm / s
    public static final int SETTING_IDLING = 18;
        public static final int PARAMETER_IDLING_SECONDS = 0;
    public static final int SETTING_SPEEDING = 19;
        public static final int PARAMETER_SPEEDING_CMS = 0; // speed cm / s
        public static final int PARAMETER_SPEEDING_SECONDS = 1; // speed cm / s
    public static final int SETTING_ACCELERATING = 20;
        public static final int PARAMETER_ACCELERATING_CMS2 = 0; // accel: cm / s^2
        public static final int PARAMETER_ACCELERATING_TENTHS = 1; // tenths of seconds
    public static final int SETTING_BRAKING = 21;
        public static final int PARAMETER_BRAKING_CMS2 = 0; // accel: cm / s^2
        public static final int PARAMETER_BRAKING_TENTHS = 1; // tenths of seconds
    public static final int SETTING_CORNERING = 22;
        public static final int PARAMETER_CORNERING_CMS2 = 0; // accel: cm / s^2
        public static final int PARAMETER_CORNERING_TENTHS = 1; // tenths of seconds
    public static final int SETTING_COMMUNICATION = 23;
        public static final int PARAMETER_COMMUNICATION_NONCELLULAR_OK = 0; // boolean, set if wifi should be used
    // 24 is Input GP6, see above
    public static final int SETTING_COMWATCHDOG = 25;
        public static final int PARAMETER_COMWATCHDOG_TIME1 = 0; // seconds
        public static final int PARAMETER_COMWATCHDOG_TIME2 = 1; // seconds
        public static final int PARAMETER_COMWATCHDOG_TIME3 = 2; // seconds


    public static final int NUM_SETTINGS = 25;

    public static final String[] SETTING_DEFAULTS = {
            "", // Reserved
            "10.0.2.2|9999", //"10.0.2.2|9999"// Destination Server
            "9999", // Local Port
            "10|10|15|15|20|20|60", // BackOff Retries
            "1|1800", // Heartbeat (1 second after UTC,  seconds awake)
            "30|50|90|300", // Ping: seconds moving, meters moving, degrees moving, seconds not moving
            "0|1800", // Schedule Wakeup (Off,  seconds awake)
            "132|1", // Engine Status: 1/10 volts, messages
            "105|300|300|1", // Low Battery: 1/10 volts, seconds below, seconds above, messages
            "132|300|300|1", // Bad Alternator: 1/10 volts, seconds below, seconds above, messages
            "1800|3", // Ignition Line: seconds awake, messages
            "1|20|40|1800|1|0", // Input 1: bias, 1/10s debounce-on, 1/10s delay, 1/10s keep-alive, bf messages, 1/10s debounce-off (0 = same as on)
            "1|20|40|1800|1|0", // Input 2: bias, 1/10s debounce-on, 1/10s delay, 1/10s keep-alive, bf messages, 1/10s debounce-off (0 = same as on)
            "1|20|40|1800|1|0", // Input 3: bias, 1/10s debounce-on, 1/10s delay, 1/10s keep-alive, bf messages, 1/10s debounce-off (0 = same as on)
            "1|20|40|1800|1|0", // Input 4: bias, 1/10s debounce-on, 1/10s delay, 1/10s keep-alive, bf messages, 1/10s debounce-off (0 = same as on)
            "1|20|40|1800|1|0", // Input 5: bias, 1/10s debounce-on, 1/10s delay, 1/10s keep-alive, bf messages, 1/10s debounce-off (0 = same as on)
            "", // Old Input6 -- not used
            "130", // Moving Threshold: cm/s
            "300", // Idling: seconds
            "3000|10", // Speeding: cm/s , seconds
            "250|15", // Acceleration: cm/s^2, 1/10 seconds
            "300|15", // Braking: cm/s^2, 1/10 seconds
            "200|20", // Cornering: cm/s^2, 1/10 seconds
            "0", // Do not send packets if cellular not active
            "1|20|40|0|0|0", // Input 6: bias, 1/10s debounce-on, 1/10s delay, 1/10s keep-alive, bf messages, 1/10s debounce-off (0 = same as on)
            "900|120|120"
    };


    public static int[] DISABLED_SETTINGS = {
/*            SETTING_SCHEDULED_WAKEUP,
            SETTING_LOW_BATTERY_STATUS,
            SETTING_BAD_ALTERNATOR_STATUS,
            SETTING_INPUT_GP1,
            SETTING_INPUT_GP2,
            SETTING_INPUT_GP3,
            SETTING_INPUT_GP4,
            SETTING_INPUT_GP5,
            SETTING_INPUT_GP6,
            SETTING_ACCELERATING,
            SETTING_BRAKING,
            SETTING_CORNERING
            SETTING_IDLING,
            SETTING_SPEEDING,
*/
    };

    public static int MESSAGES_BF_ON = 1;  // if the first bit is set in messages parameter, this means send the on message
    public static int MESSAGES_BF_OFF = 2; // if the second bit is set in messages parameter, this means send the off message

    SharedPreferences sharedPref;

    Context context;
    public Config(Context c) {
        context = c;

    }


    /////////////////////////////////////////////////////////////////
    //  open():
    //      opens the config file
    //  Returns: 0 if the standard location was opened
    //           |1 if the alternate location was opened,
    /////////////////////////////////////////////////////////////////
    public int open() {
        Log.d(TAG, "Opening shared prefs " + FILENAMEKEY);
        try {

            boolean file_copied = copyFile(FILENAME_ALTERNATE_PATH, FILENAME_STANDARD_PATH, FILENAMEKEY + ".xml");

            sharedPref = context.getSharedPreferences(
                    FILENAMEKEY, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);

            return (file_copied ? 1 : 0);
        } catch (Exception e) {
            Log.e(TAG, "Error opening prefs " + e.toString(), e);
            throw e;
        }
    }


    ///////////////////////////////////////////////////
    // clearAll()
    //  deletes ALL configuration settings and restores factory default
    ///////////////////////////////////////////////////
    public void clearAll() {
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.clear().commit();
    }


    public boolean clearSetting(int setting_id) {
        if (!settingExists(setting_id)) return false;

        SharedPreferences.Editor editor = sharedPref.edit();
        editor.remove(Integer.toString(setting_id));
        editor.commit();

        return true; // OK
    } // clearSetting()


    ///////////////////////////////////////////////////
    // writeFakeSetting()
    //   writes a fake setting to make sure the file is created
    ///////////////////////////////////////////////////
    public boolean writeFakeSetting() {

        int setting_id = 0;
        String new_value = "";
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(Integer.toString(setting_id), new_value);
        editor.commit();

        return true; // OK
    }


    ///////////////////////////////////////////////////
    // writeSetting()
    //   writes all parameters for a config setting
    //  returns : true if it was written, false if it was not
    ///////////////////////////////////////////////////
    public boolean writeSetting(int setting_id, String new_value) {

        if (!settingExists(setting_id)) return false;

        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(Integer.toString(setting_id), new_value);
        editor.commit();

        return true; // OK
    }


    ///////////////////////////////////////////////////
    // readSetting()
    //  returns all parameters for a config setting, or the default if none exists
    ///////////////////////////////////////////////////
    public String readSetting(int setting_id) {

        if (!settingExists(setting_id)) return null;

        String defaultValue = getDefaultValue(setting_id);
        return sharedPref.getString(Integer.toString(setting_id), defaultValue);
    }


    ///////////////////////////////////////////////////
    // readParameter()
    //  returns just one parameter of a config feature
    ///////////////////////////////////////////////////
    public String readParameter(int setting_id, int param_number) {
        try {

            String featureval = readSetting(setting_id);

            if (featureval == null) return null; // No feature means there can be no parameter
            if (param_number < 0) return null; // impossible parameter number

            String[] parameters = featureval.split("\\|");

            if (parameters.length > param_number)
                return parameters[param_number];

            // Hmm. this parameter does not exist, look for the parameter in the defaults

            String defaultval = getDefaultValue(setting_id);
            parameters = defaultval.split("\\|");

            if (parameters.length > param_number)
                return parameters[param_number];

            // parameter not found in saved value or default value
        } catch(Exception e) {
            Log.e(TAG, "Exception: readParameter(): " + e.toString(), e);
        }
        return null; // this is an invalid parameter for this feature
    } // readParameter

    public String readParameterString(int setting_id) {
        return readParameter(setting_id, 0);
    }

    public String readParameterString(int setting_id, int param_number) {
        return readParameter(setting_id, param_number);
    }

    ///////////////////////////////////////////////////
    // readParameterInt()
    //  returns just one parameter of a config feature, returns it as an integer
    ///////////////////////////////////////////////////
    public int readParameterInt(int setting_id, int param_number) {

        String strval = readParameter(setting_id, param_number);

        if (strval == null) return 0;

        int retval = 0;
        try {
            retval = Integer.parseInt(strval);
        } catch (Exception e) {
            // hmm, parameter exists but is not a number, just return 0.
            Log.w(TAG, "Config Parameter " + setting_id + "-" + param_number + " is not a number (=" + strval + ")");
        }

        return retval;
    } // readParameterInt


    ///////////////////////////////////////////////////
    // readParameterArray()
    //  returns a setting like an array of parameters
    ///////////////////////////////////////////////////
    public String[] readParameterArray(int setting_id) {
        String featureval = readSetting(setting_id);

        if (featureval == null) return null; // No feature means there can be no parameter

        String[] parameters = featureval.split("\\|");

        return parameters;

    } // readParameterArray


    ///////////////////////////////////////////////////
    // getDefaultValue()
    //  just takes a setting ID and returns the default value for that feature
    ///////////////////////////////////////////////////
    private String getDefaultValue(int setting_id) {

        // if this ever goes public, be sure to add the check that feature exists

        String val = SETTING_DEFAULTS[setting_id];
        return val;
    }


    ///////////////////////////////////////////////////
    // settingExists()
    //      returns true if the setting exists (it is a valid setting for this version)
    ///////////////////////////////////////////////////
    public boolean settingExists(int setting_id) {

        if ((setting_id < 0) || (setting_id >= SETTING_DEFAULTS.length))
            return false; // this is an invalid feature



        int i;
        for (i=0 ; i < DISABLED_SETTINGS.length; i++) {
            if (DISABLED_SETTINGS[i] == setting_id) {
                // this is a disabled setting, silently ignore it
                return false;
            }
        }

        return true;
    } // featureExists()


    private static String getFilePath(Context context) {
        File f = context.getDatabasePath(FILENAMEKEY + ".xml");
        if (f != null)
            return f.getAbsolutePath();

            //Log.i("TAG", f.getAbsolutePath())
        return null;
    }


    ///////////////////////////////////////////////////
    // copyFile()
    //  copies the config file from the alternate location (if exists) to the
    //      real location where it can be read into memory
    //  then renames to .bak, deleting any existing file with that name
    //  Returns true if a file was copied, false if it was not
    ///////////////////////////////////////////////////
    public static boolean copyFile(String source_path, String destination_path, String filename) {
        FileInputStream inStream = null;
        FileOutputStream outStream = null;
        File src = null;
        File dst = null;

        boolean success = false;


        try {
            src = new File(source_path, filename);
            if (!src.exists()) return false; // the source file doesn't exist so we have nothing to do
        } catch (Exception e) {
            Log.e(TAG, "Unable to create an object to reference the source File; " + e.toString());
            return false;
        }

        // OK, attempt the file copy

        Log.d(TAG, "Attempting copy " + filename + " from " + source_path + " to " + destination_path);

        try {
            // try to create directories if they don't already exist .. needed after uninstall
            File dstpath = new File(destination_path);
            dstpath.mkdirs();
            if (!dstpath.isDirectory()) {
                Log.e(TAG, "Unable to create destination directory " + destination_path);
                return false;
            }
        } catch(Exception e) {
            Log.e(TAG, "Unable to create directory " + destination_path + ";" + e.toString());
            return false;
        }

        try {


            dst = new File(destination_path, filename);
            inStream = new FileInputStream(src);
            outStream = new FileOutputStream(dst);
            FileChannel inChannel = inStream.getChannel();
            FileChannel outChannel = outStream.getChannel();
            inChannel.transferTo(0, inChannel.size(), outChannel);
            inStream.close();
            outStream.close();
            success = true;
            Log.i(TAG, FILENAMEKEY + " file has been overwritten with the alternate");
        } catch (Exception e) {
            Log.w(TAG, "File copy not completed; " + e.toString());
        } finally {
            try {
                if (inStream != null)
                    inStream.close();
                if (outStream != null)
                    outStream.close();
            } catch (Exception e) {
                // Do Nothing
            }
        }


        // try and rename and delete the file

        if ((success) && (src != null)) {
            try {
                File old_src = new File(source_path, filename + ".bak");
                old_src.delete();
                Log.i(TAG, "Old archived file (" + filename + ".bak) deleted");
            } catch (Exception e) {
                //Log.w(TAG, "Unable to delete alternate copy of file" + e.toString());
            }

            try {
                src = new File(source_path, filename);
                success = src.renameTo(new File(source_path, filename + ".bak"));

                if (success)
                    Log.i(TAG, "New file archived to " + source_path + "/" + filename + ".bak");
                else
                    Log.e(TAG, "Unable to rename alternate copy of file to .bak; Check permissions.");
            } catch (Exception e) {
                Log.e(TAG, "Unable to rename alternate copy of file to .bak; Check permissions;" + e.toString());
            }

        }
        return success;
    } // copyFile

} // Class Config
