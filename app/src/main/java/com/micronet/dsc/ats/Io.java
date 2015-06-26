/////////////////////////////////////////////////////////////
// Io:
//  Handles Ignition Line Inputs, Digital Inputs, Analog Inputs, etc.
/////////////////////////////////////////////////////////////
package com.micronet.dsc.ats;


//import android.content.Context;
//import android.hardware.Sensor;
//import android.hardware.SensorManager;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

//import micronet.hardware.Info;
import micronet.hardware.*;

public class Io {

    private static final String TAG = "ATS-Io";

    private static final String WAKELOCK_INPUT_NAME = "ATS_INPUT";
    private static final String WAKELOCK_IGNITION_NAME = "ATS_IGNITION";

    // This class currently supports:
    //  1 Ignition Key Input
    //  1 Analog Voltage Input
    //  6 General Purpose Digital Inputs

    public static final int MAX_GP_INPUTS_SUPPORTED = 6; // used in arrays
    public static final int INPUT_BITVALUE_IGNITION = 1; // the bit value in the inputs_bitfield of the ignition bit
    // gp Inputs are hardcoded to = 1 << input number (e.g. Input#2 = 1 << 2 = value of 4)
    public static final int INPUT_POLL_PERIOD_TENTHS = 8; // poll period in tenths of a second, runs the timer that polls the hardware API

    public static final String DEFAULT_SERIAL_NUMBER = "00000000"; // used if we can't determine the serial number of device


    public static boolean DEFAULT_ALWAYS_OVERRIDE = false; // always use the defaults and ignore the actual hardware states
    public static boolean DEFAULT_IGNITION_STATE = false; // default state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT1_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT2_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT3_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT4_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT5_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT6_STATE = false; // default physical state if hardware API not connected (can be changed from activity)

    public static double DEFAULT_BATTERY_VOLTS = 13.4; // default state if hardware API not connected (can be changed from activity)


    private static boolean USE_INPUT6_AS_IGNITION = false; // are we currently using input6 as the ignition line?

    MainService service; // just a reference to the service context

    volatile boolean watchdog_IoPollTask = false; // this gets set by the poll task when it completes.

    //////////////////////////////////////////
    // Variables to store info from the Hardware API so we can segregate all calling into monitored threads

    public class DeviceConstants {
        String deviceId = DEFAULT_SERIAL_NUMBER; // stores the serial-number (ID) of the device that will be used in OTA.
        int deviceBootCapturedInputs = 0;       // stores the boot-captures flags of the inputs during boot
        boolean isValid = false; // this gets set to true once we have valid info from the Hardware API
    }
    DeviceConstants deviceConstants = new DeviceConstants(); // just to make passing these variables around easier


    public class SavedIo {
        short battery_voltage; // our current voltage
        short input_bitfield; // see above, bit 0 is the ignition key, bit 1 = input 1, etc..
    }
    SavedIo savedIo = new SavedIo(); // just to make passing these variables around easier
    long savedPhysicalElapsedTime = 0;

    //////////////////////////////////////////
    // Flags and debounce counters for tracking Input states

    public boolean flagEngineStatus; // engine on or off .. used in idling, etc..
    boolean flagBadAlternator, flagLowBattery;
    private int debounceIgnition, debounceBadAlternator, debounceLowBattery; //counters for when debouncing

    // general purpose inputs, one variable for each input
    int[] gpInputResets = new int[MAX_GP_INPUTS_SUPPORTED];
    int[] gpInputDebounces = new int[MAX_GP_INPUTS_SUPPORTED];




    //////////////////////////////////////////
    //  Wake Locks to make sure the device doesn't sleep

    PowerManager.WakeLock ignitionWakeLock; // so we can hold a wake lock during and after ignition key input
    PowerManager.WakeLock[] gpInputWakelocks = new PowerManager.WakeLock[MAX_GP_INPUTS_SUPPORTED];


    //////////////////////////////////////////
    // Items to manage sensor events (currently disabled)
    volatile static HardwareInputResults sensorInputResults = new HardwareInputResults();
    SensorTask sensorTask;

    //////////////////////////////////////////
    // Construct Io()

    public Io(MainService service) {
        this.service = service;


        // Initialize non-zero values:

        // load our Ignition State, Input States, Battery, and Alternator States
        //  (since these could take some time to determine and/or we don't want to resend messages)
        flagBadAlternator = service.state.readStateBool(State.FLAG_BADALTERNATOR_STATUS);
        flagLowBattery = service.state.readStateBool(State.FLAG_LOWBATTERY_STATUS);


        USE_INPUT6_AS_IGNITION = service.state.readStateBool(State.FLAG_USING_INPUT6_AS_IGNITION);

        savedIo.input_bitfield = 0;
        boolean flag;
        flag = service.state.readStateBool(State.FLAG_IGNITIONKEY_INPUT);
        if (flag) savedIo.input_bitfield |= INPUT_BITVALUE_IGNITION;

        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT1);
        if (flag) savedIo.input_bitfield |= (1 << 1);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT2);
        if (flag) savedIo.input_bitfield |= (1 << 2);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT3);
        if (flag) savedIo.input_bitfield |= (1 << 3);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT4);
        if (flag) savedIo.input_bitfield |= (1 << 4);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT5);
        if (flag) savedIo.input_bitfield |= (1 << 5);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT6);
        if (flag) savedIo.input_bitfield |= (1 << 6);



    } // Io()


    ScheduledThreadPoolExecutor exec;
    ////////////////////////////////////////////////////////////////////
    // start()
    //      "Start" the I/O monitoring, called when app is started
    ////////////////////////////////////////////////////////////////////
    public void start() {

        // schedule the IO checks with a fixed delay instead of fixed rate
        // so that we don't execute 100,000+ times after waking up from a sleep.

        Log.v(TAG, "start()");


        // start up a different thread to get and store needed init info from Hardware API
        // this must be in a different thread in case it jams, we can at least detect it.

        InitTask initTask = new InitTask();
        Thread initThread = new Thread(initTask);
        initThread.start();

    } // start()

    ////////////////////////////////////////////////////////////////////
    // stop()
    //      "Stop" the I/O monitoring, called when app is ended
    ////////////////////////////////////////////////////////////////////
    public void stop() {

        Log.v(TAG, "stop()");

        if (exec != null) {
            exec.shutdown();
        }

        //stopSensor();

        // since we start position from this class, we should stop it from here too.
        service.position.stop();

    } // stop()


    ////////////////////////////////////////////////////////////////////
    // destroy()
    //      Destroys wakelocks (this is done after recording crash data as a last step
    ////////////////////////////////////////////////////////////////////
    public void destroy() {
        // make sure we release any wake locks
        if (ignitionWakeLock != null) {
            ignitionWakeLock = service.power.cancelWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock);
        }

        int i;
        for (i= 0; i < MAX_GP_INPUTS_SUPPORTED; i++) {
            if (gpInputWakelocks[i] != null)
                gpInputWakelocks[i] = service.power.cancelWakeLock(WAKELOCK_INPUT_NAME + (i+1), gpInputWakelocks[i]);
        }

    } // destroy()


        ////////////////////////////////////////////////////////////////////
    // saveCrashData()
    //      save data in preparation for an imminent crash+recovery
    ////////////////////////////////////////////////////////////////////
    public void saveCrashData(Crash crash) {
        crash.writeStateInt(Crash.DEBOUNCE_BAD_ALTERNATOR, debounceBadAlternator);
        crash.writeStateInt(Crash.DEBOUNCE_BAD_LOW_BATTERY, debounceLowBattery);

        crash.writeStateArrayInt(Crash.DEBOUNCE_GP_INPUTS_ARRAY, gpInputDebounces);
        crash.writeStateArrayInt(Crash.RESET_GP_INPUTS_ARRAY, gpInputResets);

        long l;
        l = service.power.getWakeLockUntilElapsedTime(WAKELOCK_IGNITION_NAME);
        crash.writeStateLong(Crash.WAKELOCK_ELAPSED_IGNITION, l);

        int i;

        long[] gpwakes = new long[MAX_GP_INPUTS_SUPPORTED];

        for (i = 0 ; i < MAX_GP_INPUTS_SUPPORTED; i++) {
            l = service.power.getWakeLockUntilElapsedTime(WAKELOCK_INPUT_NAME + (i+1));
            gpwakes[i] = l;
        }
        crash.writeStateArrayLong(Crash.WAKELOCK_ELAPSED_GP_INPUTS_ARRAY, gpwakes);
    } // saveCrashData()


    ////////////////////////////////////////////////////////////////////
    // restoreCrashData()
    //      restore data from a recent crash
    ////////////////////////////////////////////////////////////////////
    public void restoreCrashData(Crash crash) {
        debounceBadAlternator= crash.readStateInt(Crash.DEBOUNCE_BAD_ALTERNATOR);
        debounceLowBattery= crash.readStateInt(Crash.DEBOUNCE_BAD_LOW_BATTERY);
        String[] debounces, resets;
        debounces = crash.readStateArray(Crash.DEBOUNCE_GP_INPUTS_ARRAY);
        resets = crash.readStateArray(Crash.RESET_GP_INPUTS_ARRAY);

        int i;
        for (i = 0 ; i < MAX_GP_INPUTS_SUPPORTED && i < debounces.length; i++) {
            gpInputDebounces[i] = Integer.parseInt(debounces[i]);
        }
        for (i = 0 ; i < MAX_GP_INPUTS_SUPPORTED && i < resets.length; i++) {
            gpInputResets[i] = Integer.parseInt(resets[i]);
        }

        long now = SystemClock.elapsedRealtime() / 1000;

        long l;
        int confirm;
        l = crash.readStateLong(Crash.WAKELOCK_ELAPSED_IGNITION);
        confirm = service.config.readParameterInt(Config.SETTING_INPUT_IGNITION, Config.PARAMETER_INPUT_IGNITION_SECONDS_WAKE);
        if (l ==0) {
            ignitionWakeLock = service.power.changeWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock, 0);
        } else if ((l > 0) && (l > now ) && (l < now + confirm)) {
            ignitionWakeLock = service.power.changeWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock, (int) (l - now));
        }

        String[] gpwakes;
        gpwakes = crash.readStateArray(Crash.WAKELOCK_ELAPSED_GP_INPUTS_ARRAY);
        for (i = 0 ; i < MAX_GP_INPUTS_SUPPORTED && i < gpwakes.length; i++) {
            l = Long.parseLong(gpwakes[i]);
            confirm = service.config.readParameterInt(Config.SETTING_INPUT_GP1 + i, Config.PARAMETER_INPUT_GP_SECONDS_WAKE);
            if (l == 0) {
                gpInputWakelocks[i] = service.power.changeWakeLock(WAKELOCK_INPUT_NAME + (i+1), gpInputWakelocks[i], 0);
            } else if ((l > 0) && (l > now ) && (l < now + confirm)) {
                gpInputWakelocks[i] = service.power.changeWakeLock(WAKELOCK_INPUT_NAME + (i+1), gpInputWakelocks[i], (int) (l - now));
            }
        }

    } // restoreCrashData


    ////////////////////////////////////////////////////////////////////
    // isWatchdogError()
    //  called to check if a thread jammed
    ////////////////////////////////////////////////////////////////////
    public boolean isWatchdogError() {

        // if we are "started" and we haven't heard from the poll task in a while then there is an error.

        if (!watchdog_IoPollTask) {
            return true; // there's a problem
        }

        watchdog_IoPollTask = false; // reset so that we are ready to check again.
        return false; // no error
    } // isWatchdogError




    ////////////////////////////////////////////////////////////////////
    // populateQueueItem()
    //  called every time we trigger an event to store the data from the class
    ////////////////////////////////////////////////////////////////////
    public void populateQueueItem(QueueItem item) {
        try {
            item.battery_voltage = savedIo.battery_voltage;
            item.input_bitfield = savedIo.input_bitfield;
        } catch (Exception e) {
            Log.e(TAG, "Exception populateQueueItem() " + e.toString(), e);
        }
    }


    //  *************************************************
    //  Methods to manage to physical state of Inputs and Hardware API:
    //  *************************************************


    //////////////////////////////////////////////////////////
    // isFullyInitialized()
    //  Returns if this I/O class is initialized (meaning we can send messages)
    //  Since initialization is done asynchronously, this tells us it is done
    //////////////////////////////////////////////////////////
    public boolean isFullyInitialized() {
        return deviceConstants.isValid;
    }

    //////////////////////////////////////////////////////////
    // getDeviceId()
    //  Returns the device ID that is stored in memory
    //  Called from encoding/decoding procedures
    //////////////////////////////////////////////////////////
    public String getDeviceId() {
        return deviceConstants.deviceId;
    }

    //////////////////////////////////////////////////////////
    // getBootState()
    //  Returns the device ID that is stored in memory
    //  Called from encoding/decoding procedures
    //////////////////////////////////////////////////////////
    public int getBootState() {
        return deviceConstants.deviceBootCapturedInputs;
    }


    //////////////////////////////////////////////////////////
    // getHardwareDeviceId()
    //  Calls the hardware API to request the serial number of the device and
    //      remembers the result in memory so we don't need to call it again
    //  (Done this way because calling the API doesn't always work
    //////////////////////////////////////////////////////////
    public static String getHardwareDeviceId() {

        Log.v(TAG, "getHardwareDeviceId()");

        micronet.hardware.Info info = micronet.hardware.Info.getInstance();

        String serial = info.GetSerialNumber();
        if ((serial == null) || (serial.isEmpty())) {
            serial = DEFAULT_SERIAL_NUMBER;
            Log.e(TAG, "getHardwareDeviceId(): serial number not found, using " + serial);
            return serial;
        }

        Log.i(TAG, "Retrieved serial number " + serial);
        return serial;

    } // getHardwareDeviceId()


    ////////////////////////////////////////////////////////////////////
    // getHardwareBootState()
    //  Calls the hardware API
    //  gets the status of the wakeup I/O at the instant A-317 was booted.
    ////////////////////////////////////////////////////////////////////
    public static int getHardwareBootState() {

        // We can sort of check Ignition, and Input 1,2,3 for their condition at boot time
        // Note we can only check physical state, not logical state, and we can't distinguish ignition
        //  if another input is physically high.

        Log.v(TAG, "getHardwareBootState()");

        micronet.hardware.MicronetHardware hardware = micronet.hardware.MicronetHardware.getInstance();

        int boot_input_mask = hardware.getPowerUpIgnitionState();

        if (boot_input_mask != -1) { // not an error
            // DEAL with IGNITION CONCURRENCY PROBLEM:
            if ((boot_input_mask & 0x0E) != 0) { // INPUT1,2, or 3 was the cause
                boot_input_mask &= 0x0E; // We cannot say for sure that Ignition was also a cause
            } // IGNITION


            return boot_input_mask;
        }

        boot_input_mask = 0;  // we cannot determine that any of the inputs caused a wakeup
        return boot_input_mask;
    } // getHardwareBootState()



    public static class HardwareVoltageResults {
        double voltage;
        long savedTime;
    }

    public static class HardwareInputResults {
        boolean ignition_input, ignition_valid; // sometimes the ignition reading is unknown or invalid.
        boolean input1, input2, input3, input4, input5, input6;
        long savedTime;
    }


    //////////////////////////////////////////////////////////
    // getHardwareInputs()
    //  Calls the hardware API
    //  This is called from timer to get the state of the hardware inputs
    // Returns HardwareInputResults (or null if not found)
    //////////////////////////////////////////////////////////
    public static HardwareInputResults getHardwareInputs() {


        long start_now = SystemClock.elapsedRealtime();

        Log.v(TAG, "getHardwareInputs()");

        HardwareInputResults hardwareInputResults = new HardwareInputResults();

        hardwareInputResults.savedTime = start_now;

        micronet.hardware.MicronetHardware hardware;

        hardware = null;
        try {
            hardware = micronet.hardware.MicronetHardware.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "Exception when trying to getInstance() of Micronet Hardware API");
            return null;
        }

        if (hardware == null) {
            Log.e(TAG, "NULL result when trying to getInstance() of Micronet Hardware API");
            return null;
        } else {

            try {

                // TYPE_INPUT2-4 in Hardware API is really INPUT1-3 on the cable, because in Hardware API
                //  TYPE_INPUT1 is used for the ignition line.
                // TYPE_INPUT9-11 in Hardware API is really INPUT4-6 on the cable, because of wakeup requirements

                // Note: If INPUT1, INPUT2, or INPUT3 is high, then IGNITION will also be read as high
                //      regardless of its actual value.
                //       INPUT4,5, and 6 are not affected by this.

                /*
                // Alternatively, this data could be gotten with sensor manager, but then there's no hint if something were to go wrong
                //

                SensorManager sensorManager = (SensorManager) hwcontext.getSystemService(hwcontext.SENSOR_SERVICE);
                Sensor sensor;
                sensor = sensorManager.getDefaultSensor(MicronetHardware.AUTOMOTIVE_IN_SENSOR_ID);
                */

                int inputVal;
                // read input 1
                inputVal= hardware.getInputState(MicronetHardware.TYPE_INPUT2);
                if (inputVal == -1) {
                    Log.w(TAG, "reading Input1 (TYPE 2) returned error (-1), trying again");
                    inputVal = hardware.getInputState(MicronetHardware.TYPE_INPUT2);
                    if (inputVal == -1) {
                        Log.e(TAG, "reading Input1 (TYPE 2) returned error (-1) on retry, aborting read");
                    }
                }
                if (inputVal != -1) { // valid Input Value
                    hardwareInputResults.input1 = (inputVal != 0);
                } // valid Input Value

                // read input 2
                inputVal= hardware.getInputState(MicronetHardware.TYPE_INPUT3);
                if (inputVal == -1) {
                    Log.w(TAG, "reading Input2 (TYPE 3) returned error (-1), trying again");
                    inputVal = hardware.getInputState(MicronetHardware.TYPE_INPUT3);
                    if (inputVal == -1) {
                        Log.e(TAG, "reading Input2 (TYPE 3) returned error (-1) on retry, aborting read");
                    }
                }
                if (inputVal != -1) { // valid Input Value
                    hardwareInputResults.input2 = (inputVal != 0);
                } // valid Input Value


                // read input 3
                inputVal= hardware.getInputState(MicronetHardware.TYPE_INPUT4);
                if (inputVal == -1) {
                    Log.w(TAG, "reading Input3 (TYPE 4) returned error (-1), trying again");
                    inputVal = hardware.getInputState(MicronetHardware.TYPE_INPUT4);
                    if (inputVal == -1) {
                        Log.e(TAG, "reading Input3 (TYPE 4) returned error (-1) on retry, aborting read");
                    }
                }
                if (inputVal != -1) { // valid Input Value
                    hardwareInputResults.input3 = (inputVal != 0);
                } // valid Input Value


                // There is currently an ignition concurrency problem where the ignition line input
                //  is undefined when input1,2, or 3 is physically high.

                // IF NO IGNITION CONCURRENCY PROBLEM, then just use this code to get the remaining inputs:
                /*
                hardwareResults.ignition_input = (hardware.getInputState(MicronetHardware.TYPE_INPUT1) != 0);
                hardwareResults.input4 = (hardware.getInputState(MicronetHardware.TYPE_INPUT9) != 0);
                hardwareResults.input5 = (hardware.getInputState(MicronetHardware.TYPE_INPUT10) != 0);
                hardwareResults.input6 = (hardware.getInputState(MicronetHardware.TYPE_INPUT11) != 0);
                */

                // Otherwise, use this code to minimize false readings to an acceptable range:

                hardwareInputResults.ignition_valid = false;
                if (!USE_INPUT6_AS_IGNITION) {
                    // we're not using input 6, which means we have to use the normal ignition sensing and deal with co-mingling

                    if ((!hardwareInputResults.input1) &&
                        (!hardwareInputResults.input2) &&
                        (!hardwareInputResults.input3)) {
                        // it may be possible to check the ignition line ?
                        boolean possible_ignition = (hardware.getInputState(MicronetHardware.TYPE_INPUT1) != 0);

                        hardwareInputResults.input1 = (hardware.getInputState(MicronetHardware.TYPE_INPUT2) != 0);
                        hardwareInputResults.input2 = (hardware.getInputState(MicronetHardware.TYPE_INPUT3) != 0);
                        hardwareInputResults.input3 = (hardware.getInputState(MicronetHardware.TYPE_INPUT4) != 0);
                        if ((!hardwareInputResults.input1) &&
                                (!hardwareInputResults.input2) &&
                                (!hardwareInputResults.input3)) {
                            // OK, all three inputs were off before, and off after, so we'll assume they were off when we read ignition
                            // if not, then this should get filtered out by the debounce anyway ?
                            hardwareInputResults.ignition_input = possible_ignition;
                            hardwareInputResults.ignition_valid = true;
                        }
                    }
                } // not using input6 as the ignition line



                // Read Input 4
                inputVal= hardware.getInputState(MicronetHardware.TYPE_INPUT9);
                if (inputVal == -1) {
                    Log.w(TAG, "reading Input4 (TYPE 9) returned error (-1), trying again");
                    inputVal = hardware.getInputState(MicronetHardware.TYPE_INPUT9);
                    if (inputVal == -1) {
                        Log.e(TAG, "reading Input4 (TYPE 9) returned error (-1) on retry, aborting read");
                    }
                }
                if (inputVal != -1) { // valid Input Value
                    hardwareInputResults.input4 = (inputVal != 0);
                } // valid Input Value

                // Read Input 5
                inputVal= hardware.getInputState(MicronetHardware.TYPE_INPUT10);
                if (inputVal == -1) {
                    Log.w(TAG, "reading Input5 (TYPE 10) returned error (-1), trying again");
                    inputVal = hardware.getInputState(MicronetHardware.TYPE_INPUT10);
                    if (inputVal == -1) {
                        Log.e(TAG, "reading Input5 (TYPE 10) returned error (-1) on retry, aborting read");
                    }
                }

                if (inputVal != -1) { // valid Input Value
                    hardwareInputResults.input5 = (inputVal != 0);
                } // valid Input Value


                // Read Input 5
                inputVal= hardware.getInputState(MicronetHardware.TYPE_INPUT11);
                if (inputVal == -1) {
                    Log.w(TAG, "reading Input6 (TYPE 11) returned error (-1), trying again");
                    inputVal = hardware.getInputState(MicronetHardware.TYPE_INPUT11);
                    if (inputVal == -1) {
                        Log.e(TAG, "reading Input6 (TYPE 11) returned error (-1) on retry, aborting read");
                    }
                }

                if (inputVal != -1) { // valid Input Value

                    // set the ignition line to always use the input 6 ??
                    if (USE_INPUT6_AS_IGNITION) {
                        hardwareInputResults.ignition_input = (inputVal != 0);
                        hardwareInputResults.ignition_valid = true;
                    } else {
                        hardwareInputResults.input6 = (inputVal != 0);
                    }
                } // valid Input Value


/*
                // Log Physical
                Log.d(TAG, "Inputs (Phy): " +
                                " , IGN:" + (hardwareInputResults.ignition_valid ? (hardwareInputResults.ignition_input ?  "1" : "0") : "?") +
                                " , IN1:" + (hardwareInputResults.input1 ? "1" : "0") +
                                " , IN2:" + (hardwareInputResults.input2 ? "1" : "0") +
                                " , IN3:" + (hardwareInputResults.input3 ? "1" : "0") +
                                " , IN4:" + (hardwareInputResults.input4 ? "1" : "0") +
                                " , IN5:" + (hardwareInputResults.input5 ? "1" : "0") +
                                " , IN6:" + (hardwareInputResults.input6 ? "1" : "0")
                );
*/

            } catch (Exception e) {
                Log.e(TAG, "Exception when trying to get Inputs from Micronet Hardware API ");
                Log.e(TAG, "Exception = " + e.toString(), e);
                return null;
            }
        } // no null

        long end_now = SystemClock.elapsedRealtime();
        //Log.v(TAG, "getHardwareInputs() END: " + (end_now - start_now) + " ms");

        return hardwareInputResults;

    } // getHardwareInputs()



    //////////////////////////////////////////////////////////
    // getHardwareVoltage()
    //  Calls the hardware API
    //  This is called from timer to get the state of the hardware analog inputs
    // Returns the voltage of the analog input
    //////////////////////////////////////////////////////////
    public static HardwareVoltageResults getHardwareVoltage() {


        long start_now = SystemClock.elapsedRealtime();

        Log.v(TAG, "getHardwareVoltage()");

        HardwareVoltageResults hardwareVoltageResults = new HardwareVoltageResults();


        hardwareVoltageResults.savedTime = start_now;

        micronet.hardware.MicronetHardware hardware;

        hardware = null;
        try {
            hardware = micronet.hardware.MicronetHardware.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "Exception when trying to getInstance() of Micronet Hardware API");
            return null;
        }

        if (hardware == null) {
            Log.e(TAG, "NULL result when trying to getInstance() of Micronet Hardware API");
            return null;
        } else {

            try {

                // First, get the voltage
                hardwareVoltageResults.voltage = hardware.getAnalogInput(MicronetHardware.TYPE_ANALOG_INPUT1) / 1000.0;

                // Log Physical
//                Log.d(TAG, "Analog (Phy): " +
  //                              (hardwareVoltageResults.voltage) + "V"
//
  //              );


            } catch (Exception e) {
                Log.e(TAG, "Exception when trying to get Voltage from Micronet Hardware API ");
                Log.e(TAG, "Exception = " + e.toString(), e);
                return null;
            }
        } // no null

        long end_now = SystemClock.elapsedRealtime();
        //Log.v(TAG, "getHardwareVoltage() END: " + (end_now - start_now) + " ms");

        return hardwareVoltageResults;

    } // getHardwareVoltage()



    //  *************************************************
    //  Methods to manage to logical state of Inputs:
    //  *************************************************



    //////////////////////////////////////////////////////////
    // checkIgnitionInput()
    //  checks the physical state of the input and determines the logical state
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkIgnitionInput(boolean on) {

        boolean flagIgnition = (savedIo.input_bitfield & INPUT_BITVALUE_IGNITION) > 0 ? true : false;

        if (((on) && (!flagIgnition)) ||
                ((!on) && (flagIgnition))
                ) { // in the wrong state, should be debouncing
            if (debounceIgnition == 0) {
                if (flagIgnition)
                    debounceIgnition = 2; // debounce time for turning off (two poll periods)
                else
                    debounceIgnition = 2; // debounce time for turning on (two poll periods)

                // convert from tenths of a second (configuration) to our set poll period, rounding up
                //debounceIgnition = (debounceIgnition + INPUT_POLL_PERIOD_TENTHS-1) / INPUT_POLL_PERIOD_TENTHS;
            }
        } else { // not in a wrong state, should not be debouncing
            debounceIgnition = 0;
        }

        if (debounceIgnition > 0) { // we are debouncing
            debounceIgnition--;
            if (debounceIgnition == 0) { // done debouncing
                flagIgnition = !flagIgnition;

                if (!flagIgnition) { // turning ignition off

                    Log.i(TAG, "Ignition Off");

                    int seconds_wake = service.config.readParameterInt(Config.SETTING_INPUT_IGNITION, Config.PARAMETER_INPUT_IGNITION_SECONDS_WAKE);
                    int messages_bf = service.config.readParameterInt(Config.SETTING_INPUT_IGNITION, Config.PARAMETER_INPUT_IGNITION_MESSAGES);

                    // set the fact that ignition is off

                    savedIo.input_bitfield &= ~INPUT_BITVALUE_IGNITION;

                    if ((messages_bf & Config.MESSAGES_BF_OFF) != 0)
                        service.addEvent(QueueItem.EVENT_TYPE_IGNITION_KEY_OFF);

                    // Save that we think ignition is off
                    service.state.writeState(State.FLAG_IGNITIONKEY_INPUT, 0);

                    // Stop the location information
                    service.position.stop();

                    // Turn Engine Status Off ?
                    if (flagEngineStatus) {
                        Log.i(TAG, "Engine Status Off");
                        flagEngineStatus = false;
                        service.state.writeState(State.FLAG_ENGINE_STATUS, 0); // remember this
                        messages_bf = service.config.readParameterInt(Config.SETTING_ENGINE_STATUS, Config.PARAMETER_ENGINE_STATUS_MESSAGES);
                        if ((messages_bf & Config.MESSAGES_BF_OFF) != 0)
                            service.addEvent(QueueItem.EVENT_TYPE_ENGINE_STATUS_OFF);
                    }

                    // stop Idling (if needed) since engine status is now off
                    service.position.stopIdling();

                    // set or release our ignition off wake lock
                    if (seconds_wake > 0) {
                        ignitionWakeLock = service.power.changeWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock, seconds_wake);
                    } else if (ignitionWakeLock != null) {
                        ignitionWakeLock = service.power.cancelWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock);
                    }

                } else { // ignition was off

                    Log.i(TAG, "Ignition On");
                    int messages_bf = service.config.readParameterInt(Config.SETTING_INPUT_IGNITION, Config.PARAMETER_INPUT_IGNITION_MESSAGES);

                    // set a wake lock b/c we need to make sure that we keep awake until we have time to execute the ignition-off code

                    ignitionWakeLock = service.power.changeWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock, 0);

                    // reset the ping counters, etc..
                    service.position.startTrip();

                    // set the fact that ignition is on
                    savedIo.input_bitfield |= INPUT_BITVALUE_IGNITION;

                    if ((messages_bf & Config.MESSAGES_BF_ON) != 0)
                        service.addEvent(QueueItem.EVENT_TYPE_IGNITION_KEY_ON);

                    // Save that we think ignition is on
                    service.state.writeState(State.FLAG_IGNITIONKEY_INPUT, 1);

                    // Start the location information
                    service.position.start();

                } // ignition was off

            } // done debouncing
        } // we were debouncing
        return flagIgnition;
    } // checkIgnitionInput

    void setVoltageInput(double voltage) {

        savedIo.battery_voltage = (short) (voltage * 10.0);
    }

    //////////////////////////////////////////////////////////
    // checkEngineStatus()
    //  checks whether engines status should be on or off
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkEngineStatus() {

        // check if we need to turn engine status on
        if ((!flagEngineStatus) &&
           ((savedIo.input_bitfield & INPUT_BITVALUE_IGNITION) != 0)) { // ignition key is on

            int status_volt_threshold_tenths = service.config.readParameterInt(Config.SETTING_ENGINE_STATUS, Config.PARAMETER_ENGINE_STATUS_VOLTAGE);

            if (savedIo.battery_voltage > status_volt_threshold_tenths) {
                Log.i(TAG, "Engine Status On");
                flagEngineStatus = true;
                service.state.writeState(State.FLAG_ENGINE_STATUS, 1); // remember this
                int messages_bf = service.config.readParameterInt(Config.SETTING_ENGINE_STATUS, Config.PARAMETER_ENGINE_STATUS_MESSAGES);

                if ((messages_bf & Config.MESSAGES_BF_ON) != 0) {
                    service.addEvent(QueueItem.EVENT_TYPE_ENGINE_STATUS_ON);
                }
            }
        }

        // now we either need to check for a low battery or bad alternator based on the engine status state
        if (flagEngineStatus ) { // engine status is on
            checkBadAlternator(savedIo.battery_voltage);
            debounceLowBattery = 0;
        } else { // engine status is off
            checkLowBattery(savedIo.battery_voltage);
            debounceBadAlternator = 0;
        }

        return flagEngineStatus;
    } // checkEngineStatus()


    //////////////////////////////////////////////////////////
    // checkBadAlternator()
    //  checks the physical state of the voltage and determines the logical state of the Alternator
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkBadAlternator(short voltage) {

        int voltage_threshold = service.config.readParameterInt(Config.SETTING_BAD_ALTERNATOR_STATUS, Config.PARAMETER_BAD_ALTERNATOR_STATUS_VOLTAGE);

        if (((voltage < voltage_threshold) && (!flagBadAlternator)) ||
            ((voltage > voltage_threshold) && (flagBadAlternator))
            ) { // in the wrong state, should be debouncing
            if (debounceBadAlternator == 0) {
                if (flagBadAlternator)
                    debounceBadAlternator = service.config.readParameterInt(
                        Config.SETTING_BAD_ALTERNATOR_STATUS,
                        Config.PARAMETER_BAD_ALTERNATOR_STATUS_SECONDS_HIGHER);
                else
                    debounceBadAlternator = service.config.readParameterInt(
                        Config.SETTING_BAD_ALTERNATOR_STATUS,
                        Config.PARAMETER_BAD_ALTERNATOR_STATUS_SECONDS_LOWER);

                // convert from seconds (configuration) to our set poll period, rounding up
                debounceBadAlternator = (debounceBadAlternator *10 + INPUT_POLL_PERIOD_TENTHS-1) / INPUT_POLL_PERIOD_TENTHS;
            }
        } else { // not in a wrong state, should not be debouncing
            debounceBadAlternator = 0;
        }

        if (debounceBadAlternator > 0) { // we are debouncing
            debounceBadAlternator--;
            if (debounceBadAlternator == 0) { // done debouncing
                flagBadAlternator = !flagBadAlternator;
                int messages_bf = service.config.readParameterInt(Config.SETTING_BAD_ALTERNATOR_STATUS, Config.PARAMETER_BAD_ALTERNATOR_STATUS_MESSAGES);

                if (flagBadAlternator) {
                    Log.i(TAG, "Bad Alternator On (" + (voltage / 10.0) + "V)");
                    if ((messages_bf & Config.MESSAGES_BF_ON) != 0)
                        service.addEvent(QueueItem.EVENT_TYPE_BAD_ALTERNATOR_ON);
                    service.state.writeState(State.FLAG_BADALTERNATOR_STATUS, 1); // remember this
                } else {
                    Log.i(TAG, "Bad Alternator Off (" + (voltage / 10.0) + "V)");
                    if ((messages_bf & Config.MESSAGES_BF_OFF) != 0)
                        service.addEvent(QueueItem.EVENT_TYPE_BAD_ALTERNATOR_OFF);
                    service.state.writeState(State.FLAG_BADALTERNATOR_STATUS, 0); // remember this
                }
            }
        }

        return flagBadAlternator;
    } // checkBadAlternator()

    //////////////////////////////////////////////////////////
    // checkLowBattery()
    //  checks the physical state of the voltage and determines the logical state of the Alternator
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkLowBattery(short voltage) {
        int voltage_threshold = service.config.readParameterInt(Config.SETTING_LOW_BATTERY_STATUS, Config.PARAMETER_LOW_BATTERY_STATUS_VOLTAGE);

        if (((voltage < voltage_threshold) && (!flagLowBattery)) ||
                ((voltage > voltage_threshold) && (flagLowBattery))
                ) { // in the wrong state, should be debouncing
            if (debounceLowBattery == 0) {
                if (flagLowBattery)
                    debounceLowBattery = service.config.readParameterInt(
                            Config.SETTING_LOW_BATTERY_STATUS,
                            Config.PARAMETER_LOW_BATTERY_STATUS_SECONDS_HIGHER);
                else
                    debounceLowBattery = service.config.readParameterInt(
                            Config.SETTING_LOW_BATTERY_STATUS,
                            Config.PARAMETER_LOW_BATTERY_STATUS_SECONDS_LOWER);

                // convert from seconds (configuration) to our set poll period, rounding up
                debounceLowBattery = (debounceLowBattery*10 + INPUT_POLL_PERIOD_TENTHS-1) / INPUT_POLL_PERIOD_TENTHS;

            }
        } else { // not in a wrong state, should not be debouncing
            debounceLowBattery = 0;
        }

        if (debounceLowBattery > 0) { // we are debouncing
            debounceLowBattery--;
            if (debounceLowBattery == 0) { // done debouncing
                flagLowBattery = !flagLowBattery;
                int messages_bf = service.config.readParameterInt(Config.SETTING_LOW_BATTERY_STATUS, Config.PARAMETER_LOW_BATTERY_STATUS_MESSAGES);
                if (flagLowBattery) {
                    Log.i(TAG, "Low Battery On (" + (voltage / 10.0) + "V)");
                    if ((messages_bf & Config.MESSAGES_BF_ON) != 0)
                        service.addEvent(QueueItem.EVENT_TYPE_LOW_BATTERY_ON);
                    service.state.writeState(State.FLAG_LOWBATTERY_STATUS, 1); // remember this
                } else {
                    Log.i(TAG, "Low Battery Off ("  + (voltage / 10.0) + "V)");
                    if ((messages_bf & Config.MESSAGES_BF_OFF) != 0)
                        service.addEvent(QueueItem.EVENT_TYPE_LOW_BATTERY_OFF);
                    service.state.writeState(State.FLAG_LOWBATTERY_STATUS, 0); // remember this
                }
            }
        }

        return flagLowBattery;
    } // checkLowBattery()



    //////////////////////////////////////////////////////////
    // checkDigitalInput()
    //  checks the physical state of the input and determines the logical state
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkDigitalInput(int input_num, boolean physical_on) {
        int setting_id;
        int event_on_id, event_off_id;
        int state_id;

        if ((input_num < 1) || (input_num > MAX_GP_INPUTS_SUPPORTED)) {
            //safety : out of range of the array
            Log.w(TAG, "setDigitalInput() Invalid Input #" + input_num);
            return false;
        }

        switch (input_num) {
            case 1: setting_id = Config.SETTING_INPUT_GP1;
                    event_on_id = QueueItem.EVENT_TYPE_INPUT1_ON;
                    event_off_id = QueueItem.EVENT_TYPE_INPUT1_OFF;
                    state_id = State.FLAG_GENERAL_INPUT1;
                break;
            case 2: setting_id = Config.SETTING_INPUT_GP2;
                    event_on_id = QueueItem.EVENT_TYPE_INPUT2_ON;
                    event_off_id = QueueItem.EVENT_TYPE_INPUT2_OFF;
                    state_id = State.FLAG_GENERAL_INPUT3;
                break;
            case 3: setting_id = Config.SETTING_INPUT_GP3;
                    event_on_id = QueueItem.EVENT_TYPE_INPUT3_ON;
                    event_off_id = QueueItem.EVENT_TYPE_INPUT3_OFF;
                    state_id = State.FLAG_GENERAL_INPUT3;
                break;
            case 4: setting_id = Config.SETTING_INPUT_GP4;
                    event_on_id = QueueItem.EVENT_TYPE_INPUT4_ON;
                    event_off_id = QueueItem.EVENT_TYPE_INPUT4_OFF;
                    state_id = State.FLAG_GENERAL_INPUT4;
                break;
            case 5: setting_id = Config.SETTING_INPUT_GP5;
                    event_on_id = QueueItem.EVENT_TYPE_INPUT5_ON;
                    event_off_id = QueueItem.EVENT_TYPE_INPUT5_OFF;
                    state_id = State.FLAG_GENERAL_INPUT5;
                break;
            case 6: setting_id = Config.SETTING_INPUT_GP6;
                    event_on_id = QueueItem.EVENT_TYPE_INPUT6_ON;
                    event_off_id = QueueItem.EVENT_TYPE_INPUT6_OFF;
                    state_id = State.FLAG_GENERAL_INPUT6;
                break;
            default:
                Log.w(TAG, "setDigitalInput() Unimplemented Input #" + input_num);
                return false;
        }
        boolean flagInput = ((savedIo.input_bitfield & (1 << input_num)) != 0);

        // get the active level for this input as a boolean and determine the instantaneous logical state
        int active_level_i = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_BIAS);
        boolean logical_on; // the instantaneous logical state
        if (active_level_i == 0) // this input is active-low, convert
            logical_on = !physical_on;
        else
            logical_on = physical_on;


        // are we in reset?
        if (gpInputResets[input_num-1] > 0) {
            // yep, don't do anything except return until time period expires
            if (!logical_on) { // we are inactive, just wait for reset period to expire
                gpInputResets[input_num - 1]--;
            } else { // we are logically active again (too soon), restart the reset period
                // get the reset period before we can turn back logically on
                gpInputResets[input_num-1] = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_TENTHS_RESET_AFTER_OFF);
                // convert from tenths of a second (configuration) to our set poll period, rounding up
                gpInputResets[input_num - 1] = (gpInputResets[input_num - 1] + INPUT_POLL_PERIOD_TENTHS - 1) / INPUT_POLL_PERIOD_TENTHS;
            }
            return ((savedIo.input_bitfield & (1 << input_num)) != 0);
        }


        // not in reset ... start checking this input as normal
        if (logical_on != flagInput) { // in the wrong state, should be debouncing
            if (gpInputDebounces[input_num-1] == 0) {
                gpInputDebounces[input_num - 1] = service.config.readParameterInt(
                        setting_id,
                        Config.PARAMETER_INPUT_GP_TENTHS_DEBOUNCE);

                // convert from tenths of a second (configuration) to our set poll period, rounding up
                gpInputDebounces[input_num - 1] = (gpInputDebounces[input_num - 1] + INPUT_POLL_PERIOD_TENTHS - 1) / INPUT_POLL_PERIOD_TENTHS;
            }

        } else { // not in a wrong state, should not be debouncing
            gpInputDebounces[input_num-1] = 0;
        }

        if (gpInputDebounces[input_num-1] > 0) { // we are debouncing
            gpInputDebounces[input_num-1]--;
            if (gpInputDebounces[input_num-1] == 0) { // done debouncing
                flagInput = !flagInput;
                savedIo.input_bitfield ^= (1 << input_num);
                int messages_bf = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_MESSAGES);

                if (flagInput) {
                    Log.i(TAG, "Input " + input_num + " On");
                    if ((messages_bf & Config.MESSAGES_BF_ON) != 0) {
                        service.addEvent(event_on_id);
                    }
                    service.state.writeState(state_id, 1); // remember this


                    // set our wake lock to stop sleeping if this is set
                    int seconds_wake = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_SECONDS_WAKE);
                    if (seconds_wake > 0)
                        gpInputWakelocks[input_num-1] = service.power.changeWakeLock(WAKELOCK_INPUT_NAME + input_num, gpInputWakelocks[input_num-1], 0); // forever
                    else if (gpInputWakelocks[input_num-1] != null) {
                        gpInputWakelocks[input_num-1] = service.power.cancelWakeLock(WAKELOCK_INPUT_NAME + input_num, gpInputWakelocks[input_num - 1]);
                    }

                } else {
                    Log.i(TAG, "Input " + input_num + " Off");
                    if ((messages_bf & Config.MESSAGES_BF_OFF) != 0)
                        service.addEvent(event_off_id);

                    service.state.writeState(state_id, 0); // remember this

                    // start the reset period before we can turn back logically on
                    gpInputResets[input_num-1] = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_TENTHS_RESET_AFTER_OFF);
                    // convert from tenths of a second (configuration) to our set poll period, rounding up
                    gpInputResets[input_num - 1] = (gpInputResets[input_num - 1] + INPUT_POLL_PERIOD_TENTHS - 1) / INPUT_POLL_PERIOD_TENTHS;

                    // set our wake lock for certain amount of time afterward
                    int seconds_wake = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_SECONDS_WAKE);
                    if (seconds_wake > 0)
                        gpInputWakelocks[input_num-1] = service.power.changeWakeLock(WAKELOCK_INPUT_NAME + input_num, gpInputWakelocks[input_num-1], seconds_wake);
                    else if (gpInputWakelocks[input_num-1] != null)  {
                        gpInputWakelocks[input_num-1] = service.power.cancelWakeLock(WAKELOCK_INPUT_NAME + input_num, gpInputWakelocks[input_num - 1]);
                    }

                }
            }
        } // we were debouncing

        return ((savedIo.input_bitfield & (1 << input_num)) != 0);
    } // checkDigitalInput()


    //  *************************************************
    //  Methods for async tasks (Init and polling)
    //  *************************************************


    //////////////////////////////////////////////////////////
    // InitTask()
    //   this task must complete before I/O is "ready" to be used
    //   gets vital things like the Serial Number of the device
    //      and battery voltage to include in messages
    //////////////////////////////////////////////////////////
    class InitTask implements Runnable {

        @Override
        public void run() {
            try {

                Log.v(TAG, "initTask()");

                deviceConstants.deviceId = getHardwareDeviceId();
                deviceConstants.deviceBootCapturedInputs = getHardwareBootState();
                // set the initial physical voltage value .. this is needed to be put into messages
                HardwareVoltageResults hardwareVoltageResults = getHardwareVoltage();
                if (hardwareVoltageResults  != null)
                    setVoltageInput(hardwareVoltageResults.voltage);


                // We don't need to set other input states, as these are logical (and came from the state xml file).
                // In fact, we should NOT set them, otherwise any transitions from file will not be detected

                // mark this init as completed (it's ok the send messages now)
                deviceConstants.isValid = true;

                // if ignition is on, then we need to start other stuff too
                if ((savedIo.input_bitfield & INPUT_BITVALUE_IGNITION) != 0) {
                    Log.i(TAG, "Ignition is already On, starting GPS...");
                    ignitionWakeLock = service.power.changeWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock, 0);
                    service.position.start();
                }


                // start the sensor before reading initial values so we are notified of changes.
                //startSensor();

                // set the initial physical input values
                //sensorInputResults = getHardwareInputs();

                // schedule the I/O poller
                exec = new ScheduledThreadPoolExecutor(1);
                int pollperiodms = INPUT_POLL_PERIOD_TENTHS * 100;
                exec.scheduleAtFixedRate(new IoPollTask(), pollperiodms, pollperiodms, TimeUnit.MILLISECONDS); // check levels


            } catch (Exception e) {
                Log.e(TAG, "initTask Exception " + e.toString(), e);

            }

        } // run()
    } // InitTask()





    //////////////////////////////////////////////////////////
    // IoPollTask()
    //   the recurring timer call that processes the I/O
    //////////////////////////////////////////////////////////
    class IoPollTask implements Runnable {

        int counter = 0;

        @Override
        public void run() {
            try {
                Log.vv(TAG, "ioPollTask()");

                double voltage_input;
                boolean ignition_input, ignition_valid;
                boolean input1, input2, input3, input4, input5, input6;

                voltage_input = DEFAULT_BATTERY_VOLTS;
                ignition_input = DEFAULT_IGNITION_STATE;
                ignition_valid = true;
                input1 = DEFAULT_INPUT1_STATE;
                input2 = DEFAULT_INPUT2_STATE;
                input3 = DEFAULT_INPUT3_STATE;
                input4 = DEFAULT_INPUT4_STATE;
                input5 = DEFAULT_INPUT5_STATE;
                input6 = DEFAULT_INPUT6_STATE;

                HardwareVoltageResults hardwareVoltageResults = getHardwareVoltage();
                HardwareInputResults hardwareInputResults = getHardwareInputs();

                if (!DEFAULT_ALWAYS_OVERRIDE) {
                    if (hardwareVoltageResults != null) {
                        // always use the results of actual hardware, if they are present
                        voltage_input = hardwareVoltageResults.voltage;
                    }
                    if (hardwareInputResults != null) {
                        ignition_input = hardwareInputResults.ignition_input;
                        ignition_valid = hardwareInputResults.ignition_valid;
                        input1 = hardwareInputResults.input1;
                        input2 = hardwareInputResults.input2;
                        input3 = hardwareInputResults.input3;
                        input4 = hardwareInputResults.input4;
                        input5 = hardwareInputResults.input5;
                        input6 = hardwareInputResults.input6;
                    }
                }


                // should we allow the use of input 6 as ignition line ?
                // if voltage is detected on this line, then switch over
                if (service.SHOULD_ALLOW_INPUT6_AS_IGNITION) {
                    if ((input6) &&
                            (!USE_INPUT6_AS_IGNITION)) {
                        // once we detect a high level on input 6, then from this point forward use input 6 as ignition.
                        Log.i(TAG, "Voltage sensed on IN6, IN6 will now be used as ignition line");
                        USE_INPUT6_AS_IGNITION = true;
                        input6 = false;
                        service.state.writeState(State.FLAG_USING_INPUT6_AS_IGNITION, 1); // remember this
                    }
                }


                // log the current state values for physical input once every 10 seconds
                //counter++;
                //if ((counter % 10) == 0) {
                if ((hardwareVoltageResults != null) &&
                    (hardwareInputResults != null )) {
                    Log.d(TAG, "Inputs (Phy): " +
                        (voltage_input) + "V" +
                        " , IGN:" + (ignition_valid ? (ignition_input ? "1" : "0") : "?") +
                        " , IN1:" + (input1 ? "1" : "0") +
                        " , IN2:" + (input2 ? "1" : "0") +
                        " , IN3:" + (input3 ? "1" : "0") +
                        " , IN4:" + (input4 ? "1" : "0") +
                        " , IN5:" + (input5 ? "1" : "0") +
                        " , IN6:" + (input6 ? "1" : "0")
                    );
                }
                //}


                if (ignition_valid)
                    checkIgnitionInput(ignition_input );
                else
                    debounceIgnition = 0; // freeze the state we are in (needed because of ignition-comingling bug)
                setVoltageInput(voltage_input);
                checkEngineStatus();
                checkDigitalInput(1, input1);
                checkDigitalInput(2, input2);
                checkDigitalInput(3, input3);
                checkDigitalInput(4, input4);
                checkDigitalInput(5, input5);
                checkDigitalInput(6, input6);


                watchdog_IoPollTask = true; // successfully completed
            } catch (Exception e) {
                Log.e(TAG, "ioPollTask Exception " + e.toString(), e);
            }

        } // run()
    } // IoPollTask()



    public void startSensor() {

        SensorManager sensorManager = (SensorManager) service.context.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor;
        sensor= sensorManager.getDefaultSensor(MicronetHardware.AUTOMOTIVE_IN_SENSOR_ID);

        sensorTask = new SensorTask();
        sensorManager.registerListener(sensorTask, sensor, SensorManager.SENSOR_DELAY_FASTEST);
    }


    public void stopSensor() {

        SensorManager sensorManager = (SensorManager) service.context.getSystemService(Context.SENSOR_SERVICE);
        sensorManager.unregisterListener(sensorTask);
    }

    public static class SensorTask implements SensorEventListener {


        public void onAccuracyChanged(Sensor thissensor, int accuracy) {
        }

        public void onSensorChanged(SensorEvent event) {

            try {

                Log.v(TAG, "SensorChanged: " + event.timestamp + " : " + event.values[0]);

                int bitfield = (int) event.values[0];

                sensorInputResults.input1 = ((bitfield & 2) > 0 ? true : false);
                sensorInputResults.input2 = ((bitfield & 4) > 0 ? true : false);
                sensorInputResults.input3 = ((bitfield & 8) > 0 ? true : false);
                sensorInputResults.input4 = ((bitfield & 256) > 0 ? true : false);
                sensorInputResults.input5 = ((bitfield & 512) > 0 ? true : false);
                sensorInputResults.input6 = ((bitfield & 1024) > 0 ? true : false);

                // make sure we set ignition_valid and ignition_input in the correct order
                if (sensorInputResults.input1 || sensorInputResults.input2 || sensorInputResults.input3) {
                    sensorInputResults.ignition_valid = false;
                    sensorInputResults.ignition_input = ((bitfield & 1) > 0 ? true : false);
                } else {
                    sensorInputResults.ignition_input = ((bitfield & 1) > 0 ? true : false);
                    sensorInputResults.ignition_valid = true;
                }


                // Log Physical
                Log.d(TAG, "Inputs (Sens): " +
//                                (hwResults.voltage) + "V" +
                                " , IGN:" + (sensorInputResults.ignition_valid ? (sensorInputResults.ignition_input ?  "1" : "0") : "?") +
                                " , IN1:" + (sensorInputResults.input1 ? "1" : "0") +
                                " , IN2:" + (sensorInputResults.input2 ? "1" : "0") +
                                " , IN3:" + (sensorInputResults.input3 ? "1" : "0") +
                                " , IN4:" + (sensorInputResults.input4 ? "1" : "0") +
                                " , IN5:" + (sensorInputResults.input5 ? "1" : "0") +
                                " , IN6:" + (sensorInputResults.input6 ? "1" : "0")
                );
            } catch (Exception e) {
                Log.e(TAG, "sensorTask.onSensorChanged() Exception " + e.toString(), e);
            }

        } // onSensorChanged()

    } // SensorTask()


} // class
