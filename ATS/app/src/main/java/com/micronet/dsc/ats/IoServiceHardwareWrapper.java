package com.micronet.dsc.ats;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicLong;

import micronet.hardware.MicronetHardware;

public class IoServiceHardwareWrapper {

    static final String TAG = "ATS-IOS-Wrap";

    static final int HW_INPUT_LOW = 0; //
    static final int HW_INPUT_HIGH = 1; //
    static final int HW_INPUT_FLOAT = -1; // for ints: 0 will me low, 1 will me high and this will mean float
    static final int HW_INPUT_UNKNOWN = -2; // could not determine a value for the input

    // determine values for reading digital lows and highs on analog lines (anything in between is a float)
    static int ANALOG_THRESHOLD_LOW_MV; // anything below 6 volt is low
    static int ANALOG_THRESHOLD_HIGH_MV; // anything above 7 volt is high


    static {
        if(BuildConfig.FLAVOR_DEVICE.equals(MainService.BUILD_FLAVOR_OBC5)) {
            ANALOG_THRESHOLD_LOW_MV = 6000;
            ANALOG_THRESHOLD_HIGH_MV = 7000;
        }
        else { // A317
            ANALOG_THRESHOLD_LOW_MV = 1000;
            ANALOG_THRESHOLD_HIGH_MV = 5000;
        }

    }

    //public static class HardwareVoltageResults {

    //}

    public static class HardwareInputResults {
        boolean ignition_input, ignition_valid; // sometimes the ignition reading is unknown or invalid.
        int input1 = HW_INPUT_UNKNOWN;
        int input2 = HW_INPUT_UNKNOWN;
        int input3 = HW_INPUT_UNKNOWN;
        int input4 = HW_INPUT_UNKNOWN;
        int input5 = HW_INPUT_UNKNOWN;
        int input6 = HW_INPUT_UNKNOWN;
        int input7 = HW_INPUT_UNKNOWN;
        double voltage;
        long savedTime; // if this is 0, then they are invalid
    }




    static volatile int inHardwareCall;  // ints are always atmoic
    static volatile AtomicLong hardwareCallElapsedStart = new AtomicLong();

    static micronet.hardware.MicronetHardware hardware = null;




    static boolean isInCall() {
        if (inHardwareCall == 1) return true;
        return false;
    }

    static long getElapsedStart() {
        return hardwareCallElapsedStart.get();
    }

    static void beginCall() {
        hardwareCallElapsedStart.set(SystemClock.elapsedRealtime());
        inHardwareCall = 1;
    }

    static void endCall(String name) {
        inHardwareCall = 0;
        Log.vv(TAG, "io_call: " + (SystemClock.elapsedRealtime() - getElapsedStart()) + " " + name);
    }

    static micronet.hardware.MicronetHardware getIoInstance() {

        hardware = null;
        beginCall();
        try {
            hardware = micronet.hardware.MicronetHardware.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "Exception when trying to getInstance() of Micronet Hardware API");
            hardware = null;
        }
        endCall("getInstance()");
        return hardware;
    }

    static int getAnalogInput(int analog_type) {
        beginCall();
        int result = hardware.getAnalogInput(analog_type);
        endCall("getAnalogInput()");
        return result;
    }

    static int[] getAllAnalogInput() {
        beginCall();
        int[] result = hardware.getAllAnalogInput();
        endCall("getAllAnalogInput()");
        return result;
    }

    static int[] getAllPinInState() {
        beginCall();
        int[] result = hardware.getAllPinInState();
        endCall("getAllPinInState()");
        return result;
    }

    static int getInputState(int digital_type) {
        beginCall();
        int result = hardware.getInputState(digital_type);
        endCall("getInputState()");
        return result;
    }

    static int getPowerUpIgnitionState() {
        beginCall();
        int result = hardware.getPowerUpIgnitionState();
        endCall("getPowerUpIgnitionState()");
        return result;
    }

    static String GetSerialNumber() {
        beginCall();

        micronet.hardware.Info info = HardwareWrapper.getInfoInstance();
        if (info == null) return "";
        String result = info.GetSerialNumber();

        //sleep(5000);

        endCall("getPowerUpIgnitionState()");
        return result;
    }




    //////////////////////////////////////////////////////////
    // getHardwareDeviceId()
    //  Calls the hardware API to request the serial number of the device and
    //      remembers the result in memory so we don't need to call it again
    //  (Done this way because calling the API doesn't always work
    //////////////////////////////////////////////////////////
    public static String getHardwareDeviceId() {

        Log.vv(TAG, "getHardwareDeviceId()");


        String serial = GetSerialNumber();
        if ((serial == null) || (serial.isEmpty())) {
            serial = "";
            Log.e(TAG, "getHardwareDeviceId(): serial number not found");
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

        Log.vv(TAG, "getHardwareBootState()");


        if (HardwareWrapper.getIoInstance() == null) {
            Log.e(TAG, "NULL result when trying to get instance of Hardware API ");
            return 0;
        }

        int boot_input_mask = HardwareWrapper.getPowerUpIgnitionState();

        if (boot_input_mask == -1) { // error
            Log.e(TAG, "Unable to get PowerUpIgnitionState");
        } else {
            // DEAL with IGNITION CONCURRENCY PROBLEM:
            if ((boot_input_mask & 0x0E) != 0) { // INPUT1,2, or 3 was the cause
                boot_input_mask &= 0x0E; // We cannot say for sure that Ignition was also a cause
            } // IGNITION


            // Normalize the boot input mask
            boot_input_mask = HardwareWrapper.remapBootInputMask(boot_input_mask);

            return boot_input_mask;
        }

        boot_input_mask = 0;  // we cannot determine that any of the inputs caused a wakeup
        return boot_input_mask;
    } // getHardwareBootState()



}
