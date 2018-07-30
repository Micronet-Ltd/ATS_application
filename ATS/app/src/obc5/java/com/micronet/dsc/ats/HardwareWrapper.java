/////////////////////////////////////////////////////////////
// IoServiceHardwareWrapper:
//  Handles communications with hardware regarding I/O and Device Info.
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.SystemClock;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import micronet.hardware.MicronetHardware;

public class HardwareWrapper extends IoServiceHardwareWrapper {

    static public final String TAG = "ATS-IOS-Wrap-OBC5";

    //static public final int HW_RTC_POWER_UP = AlarmManager.RTC_WAKEUP; // micronet.hardware.MicronetHardware.RTC_POWER_UP;
    //static public final String HW_SHUTDOWN_DEVICE = null; // micronet.hardware.MicronetHardware.SHUTDOWN_DEVICE;


    static int IO_SCHEME_DEFAULT = 0 ; // No HW schemes implemented



    static void shutdownDevice(PowerManager powerManager) {
        // we can't really power down the device

        // powerManager.reboot(null);

        String command;
        command = "setprop sys.powerctl shutdown";

        int exitCode = -1; // error

        try {
            exitCode = Runtime.getRuntime().exec(new String[] { "sh", "-c", command } ).waitFor();
        } catch (Exception e) {
            Log.d(TAG, "Exception exec: " + command + ": " + e.getMessage());
        }

        Log.d(TAG, command + " returned " + exitCode);

        // exitCode should be 0 if it completed successfully
        //return exitCode;
        return;
    }

    static int restartRILDriver() {
        // Not done in OBC5
        Log.i(TAG, "*** Restart RIL Driver Request Ignored  (OBC HW does not control RIL Driver)");
        return 0;
    }

    static void wakeupDevice(AlarmManager alarmManager, long triggerAtMillis, PendingIntent operation) {
        // we can't do a real wakeup here.
        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
    }

    static micronet.hardware.Info getInfoInstance() {
        micronet.hardware.Info info = null;

        beginCall();
        try {
            info = new micronet.hardware.Info();
        } catch (Exception e) {
            Log.e(TAG, "Exception when trying to get Info Instance of Micronet Hardware API");
            info = null;
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
        return IO_SCHEME_DEFAULT;
    } // getHardwareIoScheme()


    public static int remapBootInputMask(int boot_input_mask) {

        // Original bitmap:
            // b0: ignition
            // b1: wiggle
            // b2: arm lockup
            // b3: watchdog

        // Final bitmap:
            // b0: ignition
            // b1: input 1
            // b2: input 2
            // b3: input 3
            // b4: wiggle
            // b5: arm lockup
            // b6: watchdog

        int ignition_bit = boot_input_mask & 1;
        boot_input_mask &= 0xFE;

        boot_input_mask <<= 3;
        boot_input_mask |= ignition_bit;

        return boot_input_mask;
    }

    public static void setUntrustworthyShutdown(int io_scheme, HardwareInputResults hardwareInputResults) {

        // Inputs  are untrustworthy if they are received from analog and they report a ground during the shutdown window

        if (hardwareInputResults.input1 == 0)
            hardwareInputResults.input1 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
        if (hardwareInputResults.input2 == 0)
            hardwareInputResults.input2 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
        if (hardwareInputResults.input3 == 0)
            hardwareInputResults.input3 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
        if (hardwareInputResults.input4 == 0)
            hardwareInputResults.input4 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
        if (hardwareInputResults.input5 == 0)
            hardwareInputResults.input5 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
        if (hardwareInputResults.input6 == 0)
            hardwareInputResults.input6 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
        if (hardwareInputResults.input7 == 0)
            hardwareInputResults.input7 = IoServiceHardwareWrapper.HW_INPUT_UNKNOWN;
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


        if (getIoInstance() == null) {
            Log.e(TAG, "NULL result when trying to getInstance() of Micronet Hardware API");
            return null;
        } else {

            try {

                int inputVal;

                /////////////////////////////
                // Handle Analog inputs


                int[] allanalogs = null;
                Log.vv(TAG, "getAllAnalogInput()");
                allanalogs = HardwareWrapper.getAllAnalogInput();

                if (allanalogs == null) {
                    Log.e(TAG, "Could not read analog inputs; getAllAnalogInput() returns null");
                } else if (allanalogs.length < 1) { // should be at least 1 entries
                    Log.e(TAG, "Could not read analog inputs; getAllAnalogInput() returns < 1 entry = " + Arrays.toString(allanalogs));
                } else {
                    Log.v(TAG, " Analog results " + Arrays.toString(allanalogs));

                    inputVal = allanalogs[MicronetHardware.kADC_POWER_IN]; // This is the main analog voltage
                    if (inputVal == -1) {
                        Log.w(TAG, "reading kADC_POWER_IN returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_POWER_IN);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading kADC_POWER_IN returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) { // valid Input Value
                        hardwareInputResults.voltage = inputVal / 1000.0; // convert to volts from mvolts
                    }



                    // input1
                    inputVal = allanalogs[MicronetHardware.kADC_GPIO_IN1];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input1 (kADC_GPIO_IN1) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_GPIO_IN1);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input1 (kADC_GPIO_IN1) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) {
                        // we got a value
                        if (inputVal < ANALOG_THRESHOLD_LOW_MV) hardwareInputResults.input1 = 0;
                        else if (inputVal > IoServiceHardwareWrapper.ANALOG_THRESHOLD_HIGH_MV)
                            hardwareInputResults.input1 = 1;
                        else {
                            hardwareInputResults.input1 = HW_INPUT_FLOAT;
                        }
                    }


                    // input2
                    inputVal = allanalogs[MicronetHardware.kADC_GPIO_IN2];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input2 (kADC_GPIO_IN2) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_GPIO_IN2);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input2 (kADC_GPIO_IN2) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) {
                        // we got a value
                        if (inputVal < ANALOG_THRESHOLD_LOW_MV) hardwareInputResults.input2 = 0;
                        else if (inputVal > IoServiceHardwareWrapper.ANALOG_THRESHOLD_HIGH_MV)
                            hardwareInputResults.input2 = 1;
                        else {
                            hardwareInputResults.input2 = HW_INPUT_FLOAT;
                        }
                    }


                    // kADC_GPIO_IN3 cannot distinguish between ground and high, just float and not-float

                    // input3
                    inputVal = allanalogs[MicronetHardware.kADC_GPIO_IN3];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input3 (kADC_GPIO_IN3) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_GPIO_IN3);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input3 (kADC_GPIO_IN3) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) {
                        // we got a value
                        if (inputVal < ANALOG_THRESHOLD_LOW_MV) hardwareInputResults.input3 = 0;
                        else if (inputVal > IoServiceHardwareWrapper.ANALOG_THRESHOLD_HIGH_MV)
                            hardwareInputResults.input3 = 1;
                        else {
                            hardwareInputResults.input3 = HW_INPUT_FLOAT;
                        }
                    }


                    // input4
                    inputVal = allanalogs[MicronetHardware.kADC_GPIO_IN4];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input4 (kADC_GPIO_IN4) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_GPIO_IN4);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input4 (kADC_GPIO_IN4) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) {
                        // we got a value
                        if (inputVal < ANALOG_THRESHOLD_LOW_MV) hardwareInputResults.input4 = 0;
                        else if (inputVal > IoServiceHardwareWrapper.ANALOG_THRESHOLD_HIGH_MV)
                            hardwareInputResults.input4 = 1;
                        else {
                            hardwareInputResults.input4 = HW_INPUT_FLOAT;
                        }
                    }

                    // input 5
                    inputVal = allanalogs[MicronetHardware.kADC_GPIO_IN5];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input5 (kADC_GPIO_IN5) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_GPIO_IN5);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input5 (kADC_GPIO_IN5) returned error (-1) on retry, aborting read");
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

                    // input 6
                    inputVal = allanalogs[MicronetHardware.kADC_GPIO_IN6];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input6 (kADC_GPIO_IN6) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_GPIO_IN6);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input6 (kADC_GPIO_IN6) returned error (-1) on retry, aborting read");
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

                    // input 7
                    inputVal = allanalogs[MicronetHardware.kADC_GPIO_IN7];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input7 (kADC_GPIO_IN7) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_GPIO_IN7);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input7 (kADC_GPIO_IN7) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) {
                        // we got a value
                        if (inputVal < ANALOG_THRESHOLD_LOW_MV) hardwareInputResults.input7 = 0;
                        else if (inputVal > ANALOG_THRESHOLD_HIGH_MV)
                            hardwareInputResults.input7 = 1;
                        else {
                            hardwareInputResults.input7 = HW_INPUT_FLOAT;
                        }
                    }


                } // analog inputs returned something



                /////////////////////////////
                // Handle Digital inputs

                Log.vv(TAG, "getAllPinState()");
                // Read All Inputs at once
                int[] alldigitals = null;
                alldigitals = HardwareWrapper.getAllPinInState();

                if (alldigitals == null) {
                    Log.e(TAG, "Could not read digital inputs; getAllPinInState() returns null");
                }
                else if (alldigitals.length < 8) { // should be at least 11 entries
                    Log.e(TAG, "Could not read digital inputs;getAllPinInState() returns < 8 entries = " + Arrays.toString(alldigitals));
                } else {
                    Log.v(TAG, " Digital results " + Arrays.toString(alldigitals));
/*
                    // All boards have at least three digital inputs

                    // Read Types 2 (input1), 3 (input 3), and 4 (input 3)

                    inputVal = alldigitals[MicronetHardware.kADC_GPIO_IN2]; // TYPE 2 is the 2nd item in array
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input1 (kADC_GPIO_IN2) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getInputState(MicronetHardware.kADC_GPIO_IN2);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input1 (kADC_GPIO_IN22) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) { // valid Input Value
                        hardwareInputResults.input1 = (inputVal == 0 ? 0 : 1);
                    } // valid Input Value

                    inputVal = alldigitals[MicronetHardware.kADC_GPIO_IN3];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input2 (kADC_GPIO_IN3) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getInputState(MicronetHardware.kADC_GPIO_IN3);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input2 (kADC_GPIO_IN3) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) { // valid Input Value
                        hardwareInputResults.input2 = (inputVal == 0 ? 0 : 1);
                    } // valid Input Value

                    inputVal = alldigitals[MicronetHardware.kADC_GPIO_IN4];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading Input3 (kADC_GPIO_IN4) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getInputState(MicronetHardware.kADC_GPIO_IN4);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading Input3 (kADC_GPIO_IN4) returned error (-1) on retry, aborting read");
                        }
                    }
                    if (inputVal != -1) { // valid Input Value
                        hardwareInputResults.input3 = (inputVal == 0 ? 0 : 1);
                    } // valid Input Value

*/


                    ///////////////////////////////
                    // Ignition detection


                    inputVal = alldigitals[MicronetHardware.TYPE_IGNITION];
                    if (inputVal == -1) {
                        Log.w(TAG, "reading IGN (TYPE_IGNITION) returned error (-1), trying again");
                        inputVal = HardwareWrapper.getInputState(MicronetHardware.TYPE_IGNITION);
                        if (inputVal == -1) {
                            Log.e(TAG, "reading IGN (TYPE_IGNITION) returned error (-1) on retry, aborting read");
                        }

                    }
                    if (inputVal != -1) { // valid Input Value
                        hardwareInputResults.ignition_valid = true;
                        hardwareInputResults.ignition_input = (inputVal != 0);
                    } // valid Input Value
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

                inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_POWER_IN);
                if (inputVal == -1) {
                    Log.w(TAG, "reading kADC_POWER_IN returned error (-1), trying again");
                    inputVal = HardwareWrapper.getAnalogInput(MicronetHardware.kADC_POWER_IN);
                    if (inputVal == -1) {
                        Log.e(TAG, "reading kADC_POWER_IN returned error (-1) on retry, aborting read");
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

