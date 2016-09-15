/////////////////////////////////////////////////////////////
// IoService:
//  Handles communications with hardware regarding I/O and Device Info.
//  can be started in a separate process (since interactinos with hardware API are prone to jam or crash)
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import micronet.hardware.MicronetHardware;

/**
 * Created by dschmidt on 2/25/16.
 */
public class IoService extends Service {
    static public final String TAG = "ATS-IOS";


    static public final String SERVICE_ACTION_START = "com.micronet.dsc.ats.io.start";
    static public final String SERVICE_ACTION_STOP = "com.micronet.dsc.ats.io.stop";


    static public final String BROADCAST_IO_HARDWARE_INITDATA = "com.micronet.dsc.ats.io.initData";
    static public final String BROADCAST_IO_HARDWARE_INPUTDATA = "com.micronet.dsc.ats.io.inputData";


    public static final int INPUT_POLL_PERIOD_TENTHS = 4; // poll period in tenths of a second, runs the timer that polls the hardware API

    // determine values for reading digital lows and highs on analog lines (anything in between is a float)
    private static final int ANALOG_THRESHOLD_LOW_MV = 1000; // anything below 1 volt is low
    private static final int ANALOG_THRESHOLD_HIGH_MV = 5000; // anything above 5 volt is high



    int processId = 0;

    // Possible I/O schemes. Older pcbs use a digital scheme, newer pcbs (v 4+) use an analog scheme
    public static final int IO_SCHEME_97 = 1; // all digital input, TYPE_INPUT1 is ignition
    public static final int IO_SCHEME_A = 2;  // all digital inputs, TYPE_11 (input 6) is ignition
    public static final int IO_SCHEME_6 = 3;  // 3 digital, 3 analog inputs, TYPE_8 is ignition

    // set the default value for the I/O scheme
    static int IO_SCHEME_DEFAULT = IO_SCHEME_6 ;


    private static int io_scheme = IO_SCHEME_DEFAULT; // this is the current IO scheme


    static final int HW_INPUT_LOW = 0; //
    static final int HW_INPUT_HIGH = 1; //
    static final int HW_INPUT_FLOAT = -1; // for ints: 0 will me low, 1 will me high and this will mean float
    static final int HW_INPUT_UNKNOWN = -2; // could not determine a value for the input



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
        double voltage;
        long savedTime; // if this is 0, then they are invalid
    }



    ScheduledThreadPoolExecutor exec;









    public IoService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        android.util.Log.d(TAG, "IoService Created");
        processId = android.os.Process.myPid();

        exec = new ScheduledThreadPoolExecutor(1);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {



        String action = intent.getAction();

        if ((action != null) && (action.equals(SERVICE_ACTION_STOP))) {
            android.util.Log.d(TAG, "IoService Stopped");
            stopSelf(); // stop the service (this will call OnDestroy is called)

        } else {
            android.util.Log.d(TAG, "IoService Started");
            broadcastProcessId();
            start();
        }
        return START_NOT_STICKY; // no reason to re-start it as it is monitored elsewhere.

    } // OnStartCommand()

    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed

        // OnDestroy() is NOT guaranteed to be called, and won't be for instance
        //  if the app is updated
        //  if ths system needs RAM quickly
        //  if the user force-stops the application


        android.util.Log.v(TAG, "OnDestroy()");

        if (exec != null) {
            exec.shutdown();
        }

    } // OnDestroy()


    void start() {
        // start polling if we aren't already
        if (exec != null) {
            exec.execute(ioInitTask);

            int pollperiodms = INPUT_POLL_PERIOD_TENTHS * 100;
            exec.scheduleAtFixedRate(ioInputTask, pollperiodms, pollperiodms, TimeUnit.MILLISECONDS); // check levels
        }

    } // start()

    void stop() {

    } // stop()


    static class HardwareWrapper {

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



        static micronet.hardware.MicronetHardware getInstance() {

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
            micronet.hardware.Info info = micronet.hardware.Info.getInstance();
            String result = info.GetSerialNumber();

            //sleep(5000);

            endCall("getPowerUpIgnitionState()");
            return result;
        }

    } // HardwareWrapper

    //////////////////////////////////////////////////////////
    // getAllHardwareInputs()
    //  Calls the hardware API
    //  This is called from timer to get the state of the hardware inputs
    // Returns HardwareInputResults (or null if not found)
    //////////////////////////////////////////////////////////
    public static HardwareInputResults getAllHardwareInputs() {


        long start_now = SystemClock.elapsedRealtime();

        Log.v(TAG, "getAllHardwareInputs()");

        HardwareInputResults hardwareInputResults = new HardwareInputResults();

        hardwareInputResults.savedTime = start_now;

        //micronet.hardware.MicronetHardware hardware;


        //hardware = HardwareWrapper.getInstance();



        if (HardwareWrapper.getInstance() == null) {
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
                            // scheme 97 and Scheme 6 does have input 6
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

    //////////////////////////////////////////////////////////
    // getHardwareDeviceId()
    //  Calls the hardware API to request the serial number of the device and
    //      remembers the result in memory so we don't need to call it again
    //  (Done this way because calling the API doesn't always work
    //////////////////////////////////////////////////////////
    public static String getHardwareDeviceId() {

        Log.vv(TAG, "getHardwareDeviceId()");

        // micronet.hardware.Info info = micronet.hardware.Info.getInstance();

        String serial = HardwareWrapper.GetSerialNumber();
        if ((serial == null) || (serial.isEmpty())) {
            serial = "";
            Log.e(TAG, "getHardwareDeviceId(): serial number not found");
            return serial;
        }

        Log.i(TAG, "Retrieved serial number " + serial);

        /*
        // set whether we are dealing with earlier I/O (digital) or later I/O (all analog) versions
        if ((serial.length() == 6) && (serial.compareTo("709900") <= 0))
            setIoScheme(IO_SCHEME_DIGITAL);
        else
            setIoScheme(IO_SCHEME_ANALOG);
        */

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

        //micronet.hardware.MicronetHardware hardware = micronet.hardware.MicronetHardware.getInstance();

        if (HardwareWrapper.getInstance() == null) {
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


            return boot_input_mask;
        }

        boot_input_mask = 0;  // we cannot determine that any of the inputs caused a wakeup
        return boot_input_mask;
    } // getHardwareBootState()


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

        /*
        micronet.hardware.MicronetHardware hardware;

        hardware = null;
        try {
            hardware = micronet.hardware.MicronetHardware.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "Exception when trying to getInstance() of Micronet Hardware API");
            return null;
        }
*/
        if (HardwareWrapper.getInstance() == null) {
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


















    //////////////////////////////////////////////////////////
    // IoInitTask()
    //   the recurring timer call that processes the I/O
    //////////////////////////////////////////////////////////
    IoInitTask ioInitTask = new IoInitTask();
    class IoInitTask implements Runnable {

        boolean inProgress = false;
        boolean hasCompleted = false;

        String deviceId;
        int deviceBootCapturedInputs;

        @Override
        public void run() {
            try {

                Log.vv(TAG, "IoInitTask()");
                if (inProgress) {
                    Log.e(TAG, "IoInitTask() already in progress. Aborting.");
                    return;
                }



                inProgress = true;
                if (!hasCompleted) {
                    // if we haven't previously completed

                    io_scheme = getHardwareIoScheme();

                    deviceId = getHardwareDeviceId();
                    deviceBootCapturedInputs = getHardwareBootState();
                }

                HardwareInputResults hardwareInputResults = getHardwareVoltageOnly();

                broadcastInit(io_scheme, deviceId, deviceBootCapturedInputs, hardwareInputResults);

                Log.vv(TAG, "IoInitTask() END");
                inProgress = false;
                hasCompleted = true;
            } catch (Exception e) {
                Log.e(TAG, "IoInitTask Exception " + e.toString(), e);
            }

        } // run()

    }


    // We need to make sure that sombody knows our process Id so they can kill us even if Init fails
    // broadcastProcessId()
    public void broadcastProcessId() {
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_IO_HARDWARE_INITDATA);

        Context context = getApplicationContext();

        ibroadcast.setPackage(context.getPackageName());
        ibroadcast.putExtra("processId", processId);
        context.sendBroadcast(ibroadcast);
    }

    public void broadcastInit(int io_scheme, String deviceId, int deviceBootCapturedInputs, HardwareInputResults hir) {


        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot

        // send a local message:
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_IO_HARDWARE_INITDATA);

        Context context = getApplicationContext();

        ibroadcast.setPackage(context.getPackageName());

        ibroadcast.putExtra("processId", processId);
        ibroadcast.putExtra("containsInit", true); // this also will contain init info
        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot

        ibroadcast.putExtra("ioScheme", io_scheme);
        ibroadcast.putExtra("deviceId", deviceId);
        ibroadcast.putExtra("deviceBootCapturedInputs", deviceBootCapturedInputs);
        ibroadcast.putExtra("voltage", hir.voltage);
        ibroadcast.putExtra("savedTime", hir.savedTime); // ms since boot


        context.sendBroadcast(ibroadcast);
    } // broadcastInit()




















    //////////////////////////////////////////////////////////
    // IoInputTask()
    //   the recurring timer call that processes the I/O
    //////////////////////////////////////////////////////////
    IoInputTask ioInputTask = new IoInputTask();
    class IoInputTask implements Runnable {

        int count = 0;
        @Override
        public void run() {
            try {

                Log.vv(TAG, "IoInputTask()");

                if (!ioInitTask.hasCompleted) {
                    // we've never completed an init, dont try to do anything else
                    Log.e(TAG, "Init never completed");
                    return;
                }

                HardwareInputResults hardwareInputResults = getAllHardwareInputs();

                broadcastInputs(hardwareInputResults);

            } catch (Exception e) {
                Log.e(TAG, "IoInputTask Exception " + e.toString(), e);
            }

        } // run()

    } // IoInputTask()




    public void broadcastInputs(HardwareInputResults hir) {

        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot

        // send a local message:
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_IO_HARDWARE_INPUTDATA);

        Context context = getApplicationContext();

        ibroadcast.setPackage(context.getPackageName());

        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot
        ibroadcast.putExtra("ignition_input", hir.ignition_input);
        ibroadcast.putExtra("ignition_valid", hir.ignition_valid);
        ibroadcast.putExtra("input1", hir.input1);
        ibroadcast.putExtra("input2", hir.input2);
        ibroadcast.putExtra("input3", hir.input3);
        ibroadcast.putExtra("input4", hir.input4);
        ibroadcast.putExtra("input5", hir.input5);
        ibroadcast.putExtra("input6", hir.input6);
        ibroadcast.putExtra("voltage", hir.voltage);
        ibroadcast.putExtra("savedTime", hir.savedTime);


        context.sendBroadcast(ibroadcast);


    } // broadcastInputs()

/*
    public boolean isIoTaskRunning() {
        return HardwareWrapper.isInCall();
    }

    public long getLastIoTaskAttemptTime() {
        return HardwareWrapper.getElapsedStart();
    }
*/


} // class IoService
