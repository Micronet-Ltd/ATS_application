/////////////////////////////////////////////////////////////
// IoServiceHardwareWrapper:
//  Handles communications with hardware regarding I/O and Device Info.
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.SystemClock;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;

import micronet.hardware.MicronetHardware;

/**
 * Created by dschmidt on 2/25/16.
 */
public class HardwareWrapper extends IoServiceHardwareWrapper {

    static public final String TAG = "ATS-IOS-Wrap-A317";

    static public final int HW_RTC_POWER_UP = micronet.hardware.MicronetHardware.RTC_POWER_UP;
    static public final String HW_SHUTDOWN_DEVICE = micronet.hardware.MicronetHardware.SHUTDOWN_DEVICE;

    // Possible I/O schemes. Older pcbs use a digital scheme, newer pcbs (v 4+) use an analog scheme
    public static final int IO_SCHEME_97 = 1; // all digital input, TYPE_INPUT1 is ignition
    public static final int IO_SCHEME_A = 2;  // all digital inputs, TYPE_11 (input 6) is ignition
    public static final int IO_SCHEME_6 = 3;  // 3 digital, 3 analog inputs, TYPE_8 is ignition

    // set the default value for the I/O scheme
    static int IO_SCHEME_DEFAULT = IO_SCHEME_6 ;




    static void shutdownDevice(PowerManager powerManager) {
        powerManager.reboot(HW_SHUTDOWN_DEVICE);
    }

    static void wakeupDevice(AlarmManager alarmManager, long triggerAtMillis, PendingIntent operation) {
        alarmManager.set(HW_RTC_POWER_UP, triggerAtMillis, operation);
    }

    static int restartRILDriver() {
        Log.i(TAG, "Restarting RIL Driver ");

        String command;
        command = "su -c 'setprop ctl.restart ril-daemon'";

        int exitCode = -1; // error

        try {
            exitCode = Runtime.getRuntime().exec(new String[] { "sh", "-c", command } ).waitFor();
        } catch (Exception e) {
            Log.d(TAG, "Exception exec: " + command + ": " + e.getMessage());
        }

        Log.d(TAG, command + " returned " + exitCode);

        // exitCode should be 0 if it completed successfully
        return exitCode;
    }


    static micronet.hardware.Info getInfoInstance() {
        micronet.hardware.Info info = null;
        beginCall();
        try {
            info = micronet.hardware.Info.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "Exception when trying to get Info Instance of Micronet Hardware API");
            info= null;
        }
        endCall("Info()");
        return info;

    } // getInfoInstance()




    //////////////////////////////////////////////////////////
    // getHardwareIoScheme()
    //  decides which I/O scheme to use for reading inputs.
    //  earlier pcbs used a digital scheme, later pcbs use an analog scheme to allow float-defaults
    //////////////////////////////////////////////////////////
    public static int getHardwareIoScheme() {

        // manu parameters are in cat sys/module/device/parameters/mnfrparams

        // Two possible results, contains:
        //  <M317-A019-001> (old digital scheme)
        //  <M317-A033-001> (new analog scheme)

        String mnfrparams = "";



        try {

            File file = new File("/sys/module/device/parameters/mnfrparams");
            InputStream in = new FileInputStream(file);
            byte[] re = new byte[1000];
            int read = 0;
            while ( (read = in.read(re, 0, 1000)) != -1) {
                String string = new String(re, 0, read);
                mnfrparams += string;
            }
            in.close();
        } catch (Exception e) {
            Log.e(TAG, "Exception reading mnfrparams file to set IO scheme. Using default ");
            return IO_SCHEME_DEFAULT;
        }
        Log.d(TAG, "Manufacturer parameters = " + mnfrparams);

        // How to determine IO scheme
        //  1) find the parameter p04_27 in the string
        //  2) get the value of the 19th character
        //  3) Expect an "9", "7", "6", or "A" and assign to appropriate scheme

        // Examples: mnfrparams string contains this parameter value (amongst others):
        //  pcb v4: "p04_27<71AB06ZW4WD1CT200-66A730947>" : the 19th character is a 6
        //  pcb v3: "p04_27<71AA06BW4WD1CQ200-A6A730947>" : the 19th character is an A

        int parameter_start = mnfrparams.indexOf("p04_27<");
        final int SCHEME_CHARACTER_OFFSET = 25;

        if ((parameter_start < 0) || // -1 means our parameter was not found.
                (parameter_start + SCHEME_CHARACTER_OFFSET >= mnfrparams.length())) {
            Log.e(TAG, "Could not extract I/O scheme from manufacturer parameters, using default");
            return IO_SCHEME_DEFAULT;
        }

        parameter_start += SCHEME_CHARACTER_OFFSET;

        String scheme_character = mnfrparams.substring(parameter_start, parameter_start + 1);

        if ((scheme_character.equals("9")) || (scheme_character.equals("7"))) {
            Log.i(TAG, "Recognized IO Scheme 97");
            return IO_SCHEME_97;
        } else if (scheme_character.equals("A")) {
            Log.i(TAG, "Recognized IO Scheme A");
            return IO_SCHEME_A;
        } else if (scheme_character.equals("6")) {
            Log.i(TAG, "Recognized IO Scheme 6");
            return IO_SCHEME_6;
        } else {
            Log.i(TAG, "Unrecognized IO Scheme: using default");
            return IO_SCHEME_DEFAULT;
        }

    } // getHardwareIoScheme()


    public static int remapBootInputMask(int boot_input_mask) {

        // Original bitmap:
        // b0: ignition
        // b1: input 1
        // b2: input 2
        // b3: input 3

        // Final bitmap:
        // b0: ignition
        // b1: input 1
        // b2: input 2
        // b3: input 3
        // b4: wiggle
        // b5: arm lockup
        // b6: watchdog

        return boot_input_mask & 0x0F;
    }


    public static void setUntrustworthyShutdown(int io_scheme, HardwareInputResults hardwareInputResults) {

        // In Scheme 6, Inputs 4, 5 and 6 (the tri-state inputs) are untrustworthy if they report a ground during the shutdown window

        if (io_scheme == HardwareWrapper.IO_SCHEME_6) {
            if (hardwareInputResults.input4 == 0)
                hardwareInputResults.input4 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
            if (hardwareInputResults.input5 == 0)
                hardwareInputResults.input5 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
            if (hardwareInputResults.input6 == 0)
                hardwareInputResults.input6 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
        }

    }

    //////////////////////////////////////////////////////////
    // getAllHardwareInputs()
    //  Calls the hardware API
    //  This is called from timer to get the state of the hardware inputs
    // Returns HardwareInputResults (or null if not found)
    //////////////////////////////////////////////////////////
    public static HardwareInputResults getAllHardwareInputs(int io_scheme) {


        long start_now = SystemClock.elapsedRealtime();

        Log.v(TAG, "getAllHardwareInputs()");

        HardwareInputResults hardwareInputResults = new HardwareInputResults();

        hardwareInputResults.savedTime = start_now;

        //micronet.hardware.MicronetHardware hardware;


        //hardware = HardwareWrapper.getInstance();



        if (getIoInstance() == null) {
            Log.e(TAG, "NULL result when trying to getInstance() of Micronet Hardware API");
            return null;
        } else {

            try {

                int inputVal;

                /////////////////////////////
                // Handle Analog inputs

                if ((io_scheme == IO_SCHEME_A) || (io_scheme == IO_SCHEME_97)) {

                    // These schemes have one analog value for the main voltage
                    // cannot use getAllAnalogInput() -- it always returns null
                    Log.v(TAG, "getAnalogInput()");
                    inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT1);

                    //Log.v(TAG, " Analog result [" + inputVal + "]");

                    if (inputVal == -1) {
                        Log.w(TAG, "reading Analog1 returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT1);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Analog1 returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) {
                        hardwareInputResults.voltage = inputVal / 1000.0; // convert to volts from mvolts
                    }


                } else if (io_scheme == IO_SCHEME_6) {
                    // scheme 6 has three additional analog inputs in addition to the main voltage

                    // Read Analog Type 1 (voltage), 5 (input 4), 6 (input 5), 7 (input 6)

                    int[] allanalogs = null;
                    Log.vv(TAG, "getAllAnalogInput()");
                    allanalogs = HardwareWrapper.getAllAnalogInput();

                    if (allanalogs == null) {
                        Log.e(TAG, "Could not read analog inputs; getAllAnalogInput() returns null");
                    } else if (allanalogs.length < 1) { // should be at least 1 entries
                        Log.e(TAG, "Could not read analog inputs; getAllAnalogInput() returns < 1 entry = " + Arrays.toString(allanalogs));
                    } else {
                        Log.v(TAG, " Analog results " + Arrays.toString(allanalogs));

                        inputVal = allanalogs[0]; // This is the main analog voltage
                        if (inputVal == -1) {
                            Log.w(TAG, "reading Analog1 returned error (-1), trying again");
                            inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT1);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading Analog1 returned error (-1) on retry, aborting read");
                            }
                        }
                        if (inputVal != -1) { // valid Input Value
                            hardwareInputResults.voltage = inputVal / 1000.0; // convert to volts from mvolts
                        }



                        //
                        //
                        // TEMP: Disable Analog reading for Inputs 4, 5, 6 since it doesn't return correct value when screen is off
                        //
                        //



                        // in the analog scheme, TYPE9, TYPE10, and TYPE11 are always read as analog and they can float
                        inputVal = allanalogs[4];
                        if (inputVal == -1) {
                            Log.w(TAG, "reading Input4 (TYPE_ANALOG5) returned error (-1), trying again");
                            inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT5);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading Input4 (TYPE_ANALOG5) returned error (-1) on retry, aborting read");
                            }
                        }
                        if (inputVal != -1) {
                            // we got a value
                            if (inputVal < ANALOG_THRESHOLD_LOW_MV) hardwareInputResults.input4 = 0;
                            else if (inputVal > ANALOG_THRESHOLD_HIGH_MV)
                                hardwareInputResults.input4 = 1;
                            else {
                                hardwareInputResults.input4 = HW_INPUT_FLOAT;
                            }
                        }

                        inputVal = allanalogs[5];
                        if (inputVal == -1) {
                            Log.w(TAG, "reading Input5 (TYPE_ANALOG6) returned error (-1), trying again");
                            inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT6);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading Input5 (TYPE_ANALOG6) returned error (-1) on retry, aborting read");
                            }
                        }
                        if (inputVal != -1) {
                            // we got a value
                            if (inputVal < ANALOG_THRESHOLD_LOW_MV) hardwareInputResults.input5 = 0;
                            else if (inputVal > ANALOG_THRESHOLD_HIGH_MV)
                                hardwareInputResults.input5 = 1;
                            else {
                                hardwareInputResults.input5 = HW_INPUT_FLOAT;
                            }
                        }

                        inputVal = allanalogs[6];
                        if (inputVal == -1) {
                            Log.w(TAG, "reading Input6 (TYPE_ANALOG7) returned error (-1), trying again");
                            inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT7);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading Input6 (TYPE_ANALOG7) returned error (-1) on retry, aborting read");
                            }
                        }
                        if (inputVal != -1) {
                            // we got a value
                            if (inputVal < ANALOG_THRESHOLD_LOW_MV) hardwareInputResults.input6 = 0;
                            else if (inputVal > ANALOG_THRESHOLD_HIGH_MV)
                                hardwareInputResults.input6 = 1;
                            else {
                                hardwareInputResults.input6 = HW_INPUT_FLOAT;
                            }
                        }

                        //
                        //
                        // END TEMP: Disable Analog Reading for Inputs 4,5,6
                        //
                        //

                    } // analog inputs returned something
                } // IO_SCHEME_6


                /////////////////////////////
                // Handle Digital inputs

                Log.vv(TAG, "getAllPinState()");
                // Read All Inputs at once
                int[] alldigitals = null;
                alldigitals = HardwareWrapper.getAllPinInState();

                if (alldigitals == null) {
                    Log.e(TAG, "Could not read digital inputs; getAllPinInState() returns null");
                }
                else if (alldigitals.length < 11) { // should be at least 11 entries
                    Log.e(TAG, "Could not read digital inputs;getAllPinInState() returns < 11 entries = " + Arrays.toString(alldigitals));
                } else {
                    Log.v(TAG, " Digital results " + Arrays.toString(alldigitals));

                    // All boards have at least three digital inputs

                    // Read Types 2 (input1), 3 (input 3), and 4 (input 3)

                    inputVal = alldigitals[1]; // TYPE 2 is the 2nd item in array
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input1 (TYPE 2) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT2);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input1 (TYPE 2) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) { // valid Input Value
                        hardwareInputResults.input1 = (inputVal == 0 ? 0 : 1);
                    } // valid Input Value

                    inputVal = alldigitals[2];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input2 (TYPE 3) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT3);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input2 (TYPE 3) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) { // valid Input Value
                        hardwareInputResults.input2 = (inputVal == 0 ? 0 : 1);
                    } // valid Input Value

                    inputVal = alldigitals[3];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input3 (TYPE 4) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT4);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input3 (TYPE 4) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) { // valid Input Value
                        hardwareInputResults.input3 = (inputVal == 0 ? 0 : 1);
                    } // valid Input Value


                    // Does the scheme include additional digital inputs ?

                    if (
                        // TEMP: Disable Analog reading for Inputs 4, 5, 6 since it doesn't return correct value when screen is off
                        //(io_scheme == IO_SCHEME_6) || // read digital inputs for scheme 6.
                        // END TEMP: Disable Analog reading for Inputs 4, 5, 6 since it doesn't return correct value when screen is off
                            (io_scheme == IO_SCHEME_97) || (io_scheme==IO_SCHEME_A)) { //scheme A or scheme 97
                        // type  9 and  10 are read as digital inputs
                        inputVal = alldigitals[8];
                        if (inputVal == -1) {
                            Log.w(TAG, "reading Input4 (TYPE 9) returned error (-1), trying again");
                            inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT9);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading Input4 (TYPE 9) returned error (-1) on retry, aborting read");
                            }
                        }
                        if (inputVal != -1) { // valid Input Value
                            hardwareInputResults.input4 = (inputVal == 0 ? 0 : 1);
                        } // valid Input Value


                        inputVal = alldigitals[9];
                        if (inputVal == -1) {
                            Log.w(TAG, "reading Input5 (TYPE 10) returned error (-1), trying again");
                            inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT10);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading Input5 (TYPE 10) returned error (-1) on retry, aborting read");
                            }
                        }
                        if (inputVal != -1) { // valid Input Value
                            hardwareInputResults.input5 = (inputVal == 0 ? 0 : 1);
                        } // valid Input Value

                        if (io_scheme != IO_SCHEME_A) { // scheme A does not have an Input 6
                            // scheme 97 does have input 6
                            inputVal = alldigitals[10];
                            if (inputVal == -1) {
                                Log.w(TAG, "reading Input6 (TYPE 11) returned error (-1), trying again");
                                inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT11);
                                if (inputVal == -1) {
                                    Log.e(TAG, "reading Input6 (TYPE 11) returned error (-1) on retry, aborting read");
                                }
                            }
                            if (inputVal != -1) { // valid Input Value
                                hardwareInputResults.input6 = (inputVal == 0 ? 0 : 1);
                            } // valid Input Value
                        } // scheme 97
                    } // A or 97 (additional digital inputs)


                    ///////////////////////////////
                    // Ignition detection


                    if (io_scheme == IO_SCHEME_6) {
                        // In scheme 6 , type 8 is a digital input and is the ignition line
                        inputVal = alldigitals[7];
                        if (inputVal == -1) {
                            Log.w(TAG, "reading IGN (TYPE 8) returned error (-1), trying again");
                            inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT8);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading IGN (TYPE 8) returned error (-1) on retry, aborting read");
                            }

                        }
                        if (inputVal != -1) { // valid Input Value
                            hardwareInputResults.ignition_valid = true;
                            hardwareInputResults.ignition_input = (inputVal != 0);
                        } // valid Input Value
                    } else if (io_scheme == IO_SCHEME_A) {
                        // In Scheme A, type 11 is a digital input and is the ignition line
                        inputVal = alldigitals[10];
                        if (inputVal == -1) {
                            Log.w(TAG, "reading IGN (TYPE 11) returned error (-1), trying again");
                            inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT11);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading IGN (TYPE 11) returned error (-1) on retry, aborting read");
                            }
                        }
                        if (inputVal != -1) { // valid Input Value
                            //hardwareInputResults.input6 = (inputVal != 0);

                            hardwareInputResults.ignition_valid = true;
                            hardwareInputResults.ignition_input = (inputVal != 0);
                        } // valid Input Value

                    } else if (io_scheme == IO_SCHEME_97) {
                        // in scheme 97, type 1 is a digital input and is the ignition line
                        inputVal = alldigitals[0];
                        if (inputVal == -1) {
                            Log.w(TAG, "reading IGN (TYPE 1) returned error (-1), trying again");
                            inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_INPUT1);
                            if (inputVal == -1) {
                                Log.e(TAG, "reading IGN (TYPE 1) returned error (-1) on retry, aborting read");
                            }
                        }
                        if (inputVal != -1) { // valid Input Value
                            //hardwareInputResults.input6 = (inputVal != 0);

                            hardwareInputResults.ignition_valid = true;
                            hardwareInputResults.ignition_input = (inputVal != 0);
                        } // valid Input Value

                    } // schemes

                } // getAllPinState() returned SOMETHING


            } catch (Exception e) {
                Log.e(TAG, "Exception when trying to get Inputs from Micronet Hardware API ");
                Log.e(TAG, "Exception = " + e.toString(), e);
                return null;
            }

        } // no null

        long end_now = SystemClock.elapsedRealtime();
        //Log.v(TAG, "getHardwareInputs() END: " + (end_now - start_now) + " ms");

        return hardwareInputResults;

    } // getAllHardwareInputs()

    //////////////////////////////////////////////////////////
    // getHardwareVoltageOnly()
    //  Calls the hardware API
    //  This is called from timer to get the state of the voltage
    // Returns the voltage of the analog input, used during init
    //////////////////////////////////////////////////////////
    public static HardwareInputResults getHardwareVoltageOnly() {


        long start_now = SystemClock.elapsedRealtime();

        Log.vv(TAG, "getHardwareVoltage()");

        HardwareInputResults hardwareVoltageResults = new HardwareInputResults();

        hardwareVoltageResults.savedTime = start_now;

        if (HardwareWrapper.getIoInstance() == null) {
            Log.e(TAG, "NULL result when trying to getInstance() of Micronet Hardware API");
            return null;
        } else {

            try {

                int inputVal;

                inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT1);
                if (inputVal == -1) {
                    Log.w(TAG, "reading Analog1 returned error (-1), trying again");
                    inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT1);
                    if (inputVal == -1) {
                        Log.e(TAG, "reading Analog1 returned error (-1) on retry, aborting read");
                    }
                }
                if (inputVal != -1) {
                    hardwareVoltageResults.voltage = inputVal / 1000.0; // convert to volts from mvolts
                }


            } catch (Exception e) {
                Log.e(TAG, "Exception when trying getAnalogInput() from Micronet Hardware API ");
                Log.e(TAG, "Exception = " + e.toString(), e);
                return null;
            }
        } // no null

        long end_now = SystemClock.elapsedRealtime();
        //Log.v(TAG, "getHardwareVoltage() END: " + (end_now - start_now) + " ms");

        return hardwareVoltageResults;

    } // getHardwareVoltage()



} // HardwareWrapper

