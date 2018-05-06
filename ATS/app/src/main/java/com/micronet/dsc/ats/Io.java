/////////////////////////////////////////////////////////////
// Io:
//  Handles Ignition Line Inputs, Digital Inputs, Analog Inputs, etc.
/////////////////////////////////////////////////////////////
package com.micronet.dsc.ats;


import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;


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

    public static final String DEFAULT_SERIAL_NUMBER = "00000000"; // used if we can't determine the serial number of device



    //private static boolean USE_INPUT6_AS_IGNITION = false; // are we currently using input6 as the ignition line?

/*
    static final int HW_INPUT_LOW = 0; //
    static final int HW_INPUT_HIGH = 1; //
    static final int HW_INPUT_FLOAT = -1; // for ints: 0 will me low, 1 will me high and this will mean float
    static final int HW_INPUT_UNKNOWN = -2; // could not determine a value for the input

*/

    public static boolean DEFAULT_ALWAYS_OVERRIDE = false; // always use the defaults and ignore the actual hardware states
    public static boolean DEFAULT_IGNITION_STATE = false; // default state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT1_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT2_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT3_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT4_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT5_STATE = false; // default physical state if hardware API not connected (can be changed from activity)
    public static boolean DEFAULT_INPUT6_STATE = false; // default physical state if hardware API not connected (can be changed from activity)

    public static double DEFAULT_BATTERY_VOLTS = 13.4; // default state if hardware API not connected (can be changed from activity)




    long lastIoReceived = 0; // keep track of when we received the last Io data (realtime ms)




    MainService service; // just a reference to the service context

    //volatile boolean watchdog_IoPollTask = false; // this gets set by the poll task when it completes.
    //volatile boolean ioTaskRunning = false;
    //volatile long lastIoTaskAttemptTime = 0;


    //////////////////////////////////////////
    // Variables for the safety shutdown window during which we don't fully trust the input values

    private static int SHUTDOWN_WINDOW_MS = 30000; // keep shutdown window open for 30 seconds max.
    boolean inShutdownWindow = false; // are we in a special shutdown window where we don't trust the inputs ?
    Handler mainHandler = null;

    //////////////////////////////////////////
    // Variables to store info from the Hardware API so we can segregate all calling into monitored threads

    int ioServiceProcessId = 0; // process of the hardware service (non-zero means it is valid)
    public class DeviceConstants {
        String deviceId = DEFAULT_SERIAL_NUMBER; // stores the serial-number (ID) of the device that will be used in OTA.
        int deviceBootCapturedInputs = 0;       // stores the boot-captures flags of the inputs during boot
        int io_scheme = HardwareWrapper.IO_SCHEME_DEFAULT;
        boolean isValid = false; // this gets set to true once we have valid info from the Hardware API
    }
    DeviceConstants deviceConstants = new DeviceConstants(); // just to make passing these variables around easier


    public class Status {
        short battery_voltage; // our current voltage
        short input_bitfield; // logical input states .. see above, bit 0 is the ignition key, bit 1 = input 1, etc..

        public boolean flagEngineStatus; // engine on or off .. used in idling, etc..
        boolean flagBadAlternator, flagLowBattery;

        boolean flagWaitEngineOnMessage; // are we waiting to send an engine on message ?
    }
    Status status = new Status(); // just to make passing these variables around easier
    //long savedPhysicalElapsedTime = 0;


    private int debounceIgnition, debounceBadAlternator, debounceLowBattery; //counters for when debouncing

    // general purpose inputs, one variable for each input
    int[] gpInputResets = new int[MAX_GP_INPUTS_SUPPORTED];
    int[] gpInputDebounces = new int[MAX_GP_INPUTS_SUPPORTED];




    //////////////////////////////////////////
    //  Wake Locks to make sure the device doesn't sleep

    PowerManager.WakeLock ignitionWakeLock; // so we can hold a wake lock during and after ignition key input
    PowerManager.WakeLock[] gpInputWakelocks = new PowerManager.WakeLock[MAX_GP_INPUTS_SUPPORTED];


    //////////////////////////////////////////
    // Other
    int ENGINE_ON_TRIGGER_DELAY_MS = 15000; // 15 second delay to make sure enough time to communicate with all buses
    /*
    //////////////////////////////////////////
    // Items to manage sensor events (currently disabled)
    volatile static HardwareInputResults sensorInputResults = new HardwareInputResults();
    SensorTask sensorTask;
    */

    //////////////////////////////////////////
    // Construct Io()

    public Io(MainService service) {
        this.service = service;


        // Initialize non-zero values:

        // load our Ignition State, Input States, Battery, and Alternator States
        //  (since these could take some time to determine and/or we don't want to resend messages)
        status.flagBadAlternator = service.state.readStateBool(State.FLAG_BADALTERNATOR_STATUS);
        status.flagLowBattery = service.state.readStateBool(State.FLAG_LOWBATTERY_STATUS);


        // We don't read the Engine Status or the Wait Engine On Message because we want to send these again
        //  in case we gained or lost communication with the engine ??


        //USE_INPUT6_AS_IGNITION = service.state.readStateBool(State.FLAG_USING_INPUT6_AS_IGNITION);

        status.input_bitfield = 0;
        boolean flag;
        flag = service.state.readStateBool(State.FLAG_IGNITIONKEY_INPUT);
        if (flag) status.input_bitfield |= INPUT_BITVALUE_IGNITION;

        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT1);
        if (flag) status.input_bitfield |= (1 << 1);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT2);
        if (flag) status.input_bitfield |= (1 << 2);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT3);
        if (flag) status.input_bitfield |= (1 << 3);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT4);
        if (flag) status.input_bitfield |= (1 << 4);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT5);
        if (flag) status.input_bitfield |= (1 << 5);
        flag = service.state.readStateBool(State.FLAG_GENERAL_INPUT6);
        if (flag) status.input_bitfield |= (1 << 6);


        //Log.d(TAG, "Read Input States: " + savedIo.input_bitfield);

    } // Io()


    ////////////////////////////////////////////////////////////////////
    // start()
    //      "Start" the I/O monitoring, called when app is started
    ////////////////////////////////////////////////////////////////////
    public void start() {

        // schedule the IO checks with a fixed delay instead of fixed rate
        // so that we don't execute 100,000+ times after waking up from a sleep.

        Log.v(TAG, "start()");


        mainHandler  = new Handler();

        // register the Init receiver
        IntentFilter intentFilterInit = new IntentFilter();
        intentFilterInit.addAction(IoService.BROADCAST_IO_HARDWARE_INITDATA);
        service.context.registerReceiver(ioInitReceiver, intentFilterInit);

        // register the IO receiver
        IntentFilter intentFilterIo = new IntentFilter();
        intentFilterIo.addAction(IoService.BROADCAST_IO_HARDWARE_INPUTDATA);
        service.context.registerReceiver(ioPollReceiver, intentFilterIo);


        // start the IO Service
        Intent serviceIntent = new Intent(service.context, IoService.class);
        serviceIntent.setPackage(service.context.getPackageName());
        serviceIntent.setAction(IoService.SERVICE_ACTION_START);

        ioInitReceiver.finish = true; // finish Starting when we get the init message from service.
        service.context.startService(serviceIntent);

/*
        // start up a different thread to get and store needed init info from Hardware API
        // this must be in a different thread in case it jams, we can at least detect it.

        InitTask initTask = new InitTask();
        Thread initThread = new Thread(initTask);
        initThread.start();

*/
    } // start()

    //////////////////////////////////////////////////////////
    // finishStart()
    //  this is called after receiving the init callback from IoService while starting
    //////////////////////////////////////////////////////////
    public void finishStart() {

        Log.v(TAG, "Finishing Io Startup");
        ioInitReceiver.finish = false; // we've called it

        // if ignition is on, then we need to start other stuff too
        if ((status.input_bitfield & INPUT_BITVALUE_IGNITION) != 0) {
            Log.i(TAG, "Ignition is already On, starting GPS&Engine...");
            ignitionWakeLock = service.power.changeWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock, 0);
            service.position.start();
            service.engine.start(deviceConstants.deviceId);

            // check if we are waiting to send the engine on message
            if ((status.flagEngineStatus) && (status.flagWaitEngineOnMessage)) {
                // we are, start the timer, etc..
                startWaitEngineOnMessage();
            }

        }
    } // finishStart()
    ////////////////////////////////////////////////////////////////////
    // stop()
    //      "Stop" the I/O monitoring, called when app is ended
    ////////////////////////////////////////////////////////////////////
    public void stop() {

        Log.v(TAG, "stop()");

        ioInitReceiver.finish = false; // we're not starting


        // remove the delayed engine-on message task if it was waiting
        if (mainHandler != null) {
            mainHandler.removeCallbacks(delayedEngineOnTask);
        }


        // stop the IO Service
        Intent serviceIntent = new Intent(service.context, IoService.class);
        serviceIntent.setPackage(service.context.getPackageName());
        serviceIntent.setAction(IoService.SERVICE_ACTION_STOP);

        service.context.startService(serviceIntent);

        // unregister receivers
        try {
            service.context.unregisterReceiver(ioPollReceiver);
            service.context.unregisterReceiver(ioInitReceiver);
        } catch (Exception e) {
            // not an issue, this can happen if they weren't registered
        }

        //stopSensor();

        // since we start position and engine from this class, we should stop it from here too.
        service.position.stop();
        service.engine.stop();

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






    //////////////////////////////////////////////////////////
    // startShutdownWindow()
    //  This is used b/c Analog Inputs report that they are being grounded for several seconds during shutdown
    //  This also happens whenever the screen is off, which is why we try to keep the screen on.

    //  starts a window during which we expect the device to shutdown.
    //  during this portions of this period, we can't trust the results that we get from the Tri-state Inputs
    //  since they seem to return as ground even when they are not grounded for brief periods (seconds).
    //////////////////////////////////////////////////////////
    public void startShutdownWindow() {


        Log.v(TAG, "startShutdownWindow()");
        if (mainHandler != null) { // we're not trying to call this before we called start on the I/O
            inShutdownWindow = true;
            mainHandler.removeCallbacks(shutdownWindowTask);
            mainHandler.postDelayed(shutdownWindowTask, SHUTDOWN_WINDOW_MS);
        }

    } // startShutdownWindow()

    ///////////////////////////////////////////////////////////
    // isInShutdown()
    //  This is used to prevent watchdogs from restarting services when we are trying to shutdown
    ///////////////////////////////////////////////////////////
    public boolean isInShutdown() {
        return inShutdownWindow;
    }


    ///////////////////////////////////////////
    ///////////////////////////////////////////
    // Watchdog related items
/*
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

*/



    ////////////////////////////////////////////////////////////////////
    // populateQueueItem()
    //  called every time we trigger an event to store the data from the class
    ////////////////////////////////////////////////////////////////////
    public void populateQueueItem(QueueItem item) {
        try {
            item.battery_voltage = status.battery_voltage;
            item.input_bitfield = status.input_bitfield;
        } catch (Exception e) {
            Log.e(TAG, "Exception populateQueueItem() " + e.toString(), e);
        }
    }
    ////////////////////////////////////////////////////////////////////
    // getStatus()
    //  called everytime we need a status snapshot of relevant parameters (like for a local broadcast)
    ////////////////////////////////////////////////////////////////////
    public Status getStatus() {
        return status;
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










    /////////////////////////////////////////////////////////////
    // correctHardwareInputs()
    //  Sometimes the values we get back from the hardware API are untrustworthy (like in shutdown)
    //      this corrects those values
    /////////////////////////////////////////////////////////////
    private void correctHardwareInputs(int io_scheme,
       IoServiceHardwareWrapper.HardwareInputResults hardwareInputResults) {

        // certain conditions can cause the I/O values that we previously read to be untrustworthy in shutdown.
        if (inShutdownWindow) {
            // set the untrustworthy values to an unknown
            HardwareWrapper.setUntrustworthyShutdown(io_scheme, hardwareInputResults);
        }

    } // correctHardwareInputs()


    //  *************************************************
    //  Methods to manage to logical state of Inputs:
    //  *************************************************



    //////////////////////////////////////////////////////////
    // checkIgnitionInput()
    //  checks the physical state of the input and determines the logical state
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkIgnitionInput(boolean on) {

        boolean flagIgnition = (status.input_bitfield & INPUT_BITVALUE_IGNITION) > 0 ? true : false;

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

                    // set the fact that ignition is off

                    status.input_bitfield &= ~INPUT_BITVALUE_IGNITION;
                    Codec codec = new Codec(service);
                    service.addEventWithData(EventType.EVENT_TYPE_IGNITION_KEY_OFF, codec.dataForIgnitionOff());


                    // Save that we think ignition is off
                    service.state.writeState(State.FLAG_IGNITIONKEY_INPUT, 0);

                    // Stop the location information
                    service.position.stop();
                    service.engine.stop(); // turn off engine module too

                    // Turn Engine Status Off ?
                    if (status.flagEngineStatus) {
                        Log.i(TAG, "Engine Status Off");
                        status.flagEngineStatus = false;
                        service.state.writeState(State.FLAG_ENGINE_STATUS, 0); // remember this
                        // if we sent the ON, then send the off
                        if (!status.flagWaitEngineOnMessage) // if we aren't waiting to send the engine-on message, send the off
                            service.addEvent(EventType.EVENT_TYPE_ENGINE_STATUS_OFF);

                        service.engine.turnEngineOn(false); // used to start fuel updates, etc..
                    }

                    // stop waiting to send an engine on message, if we were waiting
                    stopWaitEngineOnMessage();

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

                    // set a wake lock b/c we need to make sure that we keep awake until we have time to execute the ignition-off code

                    ignitionWakeLock = service.power.changeWakeLock(WAKELOCK_IGNITION_NAME, ignitionWakeLock, 0);

                    // reset the ping counters, etc..
                    service.position.startTrip();

                    // set the fact that ignition is on
                    status.input_bitfield |= INPUT_BITVALUE_IGNITION;

                    service.addEvent(EventType.EVENT_TYPE_IGNITION_KEY_ON);

                    // Save that we think ignition is on
                    service.state.writeState(State.FLAG_IGNITIONKEY_INPUT, 1);

                    // Start the location information
                    service.position.start();
                    service.engine.start(deviceConstants.deviceId); // start engine monitoring

                } // ignition was off

            } // done debouncing
        } // we were debouncing
        return flagIgnition;
    } // checkIgnitionInput

    void setVoltageInput(double voltage) {

        status.battery_voltage = (short) (voltage * 10.0);
    }


    //////////////////////////////////////////////////////////
    // startWaitEngineOnMessage()
    //  start waiting to send an engine on message, will be sent after a delay (if still needed)
    //////////////////////////////////////////////////////////
    void startWaitEngineOnMessage() {
        status.flagWaitEngineOnMessage= true; // we have not sent the on message yet
        service.state.writeState(State.FLAG_WAIT_ENGINE_ON_MESSAGE, 1); // remember this
        mainHandler.postDelayed(delayedEngineOnTask, ENGINE_ON_TRIGGER_DELAY_MS);
    }

    //////////////////////////////////////////////////////////
    // stopWaitEngineOnMessage()
    //  stop waiting to send an engine on message (it was sent or is no longer needed)
    //////////////////////////////////////////////////////////
    void stopWaitEngineOnMessage() {
        status.flagWaitEngineOnMessage = false; // we have not sent the on message yet
        service.state.writeState(State.FLAG_WAIT_ENGINE_ON_MESSAGE, 0); // remember this

        if (mainHandler != null) {
            mainHandler.removeCallbacks(delayedEngineOnTask);
        }

    }


    //////////////////////////////////////////////////////////
    // checkEngineStatus()
    //  checks whether engines status should be on or off
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkEngineStatus() {

        // check if we need to turn engine status on
        if ((!status.flagEngineStatus) &&
           ((status.input_bitfield & INPUT_BITVALUE_IGNITION) != 0)) { // ignition key is on

            int status_volt_threshold_tenths = service.config.readParameterInt(Config.SETTING_ENGINE_STATUS, Config.PARAMETER_ENGINE_STATUS_VOLTAGE);

            if ((status.battery_voltage > status_volt_threshold_tenths) ||
                (service.DEBUG_IO_IGNORE_VOLTAGE_FOR_ENGINE_STATUS)) {
                Log.i(TAG, "Engine Status On");
                status.flagEngineStatus = true;
                service.state.writeState(State.FLAG_ENGINE_STATUS, 1); // remember this


                int messages_bf = service.config.readParameterInt(Config.SETTING_ENGINE_STATUS, Config.PARAMETER_ENGINE_STATUS_MESSAGES);

                if ((messages_bf & Config.MESSAGES_BF_ON) != 0) {
                    // Add a delay so that we have time to retrieve the VIN, available buses, etc from the vehicle if engine just turned on
                    if (mainHandler == null) {
                        Log.e(TAG, "mainHandler is null. Unable to delay Engine On message");
                        // We shouldn't get here, but as a safety just add this event without delay ??
                        Codec codec = new Codec(service);
                        service.addEventWithData(EventType.EVENT_TYPE_ENGINE_STATUS_ON, codec.dataForEngineOn());
                        stopWaitEngineOnMessage();
                    } else {
                        startWaitEngineOnMessage();
                }
                }

                service.engine.turnEngineOn(true); // turn off fuel updates, etc..
            }
        }

        // now we either need to check for a low battery or bad alternator based on the engine status state
        if (status.flagEngineStatus ) { // engine status is on
            checkBadAlternator(status.battery_voltage);
            debounceLowBattery = 0;
        } else { // engine status is off
            checkLowBattery(status.battery_voltage);
            debounceBadAlternator = 0;
        }

        return status.flagEngineStatus;
    } // checkEngineStatus()


    //////////////////////////////////////////////////////////
    // checkBadAlternator()
    //  checks the physical state of the voltage and determines the logical state of the Alternator
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkBadAlternator(short voltage) {

        int voltage_threshold = service.config.readParameterInt(Config.SETTING_BAD_ALTERNATOR_STATUS, Config.PARAMETER_BAD_ALTERNATOR_STATUS_VOLTAGE);

        if (((voltage < voltage_threshold) && (!status.flagBadAlternator)) ||
            ((voltage > voltage_threshold) && (status.flagBadAlternator))
            ) { // in the wrong state, should be debouncing
            if (debounceBadAlternator == 0) {
                if (status.flagBadAlternator)
                    debounceBadAlternator = service.config.readParameterInt(
                        Config.SETTING_BAD_ALTERNATOR_STATUS,
                        Config.PARAMETER_BAD_ALTERNATOR_STATUS_SECONDS_HIGHER);
                else
                    debounceBadAlternator = service.config.readParameterInt(
                        Config.SETTING_BAD_ALTERNATOR_STATUS,
                        Config.PARAMETER_BAD_ALTERNATOR_STATUS_SECONDS_LOWER);

                // convert from seconds (configuration) to our set poll period, rounding up
                debounceBadAlternator = (debounceBadAlternator *10 + IoService.INPUT_POLL_PERIOD_TENTHS-1) / IoService.INPUT_POLL_PERIOD_TENTHS;
            }
        } else { // not in a wrong state, should not be debouncing
            debounceBadAlternator = 0;
        }

        if (debounceBadAlternator > 0) { // we are debouncing
            debounceBadAlternator--;
            if (debounceBadAlternator == 0) { // done debouncing
                status.flagBadAlternator = !status.flagBadAlternator;

                if (status.flagBadAlternator) {
                    Log.i(TAG, "Bad Alternator On (" + (voltage / 10.0) + "V)");
                    service.addEvent(EventType.EVENT_TYPE_BAD_ALTERNATOR_ON);
                    service.state.writeState(State.FLAG_BADALTERNATOR_STATUS, 1); // remember this
                } else {
                    Log.i(TAG, "Bad Alternator Off (" + (voltage / 10.0) + "V)");
                    service.addEvent(EventType.EVENT_TYPE_BAD_ALTERNATOR_OFF);
                    service.state.writeState(State.FLAG_BADALTERNATOR_STATUS, 0); // remember this
                }
            }
        }

        return status.flagBadAlternator;
    } // checkBadAlternator()

    //////////////////////////////////////////////////////////
    // checkLowBattery()
    //  checks the physical state of the voltage and determines the logical state of the Alternator
    //  called every 1/10th second
    //////////////////////////////////////////////////////////
    boolean checkLowBattery(short voltage) {
        int voltage_threshold = service.config.readParameterInt(Config.SETTING_LOW_BATTERY_STATUS, Config.PARAMETER_LOW_BATTERY_STATUS_VOLTAGE);

        if (((voltage < voltage_threshold) && (!status.flagLowBattery)) ||
                ((voltage > voltage_threshold) && (status.flagLowBattery))
                ) { // in the wrong state, should be debouncing
            if (debounceLowBattery == 0) {
                if (status.flagLowBattery)
                    debounceLowBattery = service.config.readParameterInt(
                            Config.SETTING_LOW_BATTERY_STATUS,
                            Config.PARAMETER_LOW_BATTERY_STATUS_SECONDS_HIGHER);
                else
                    debounceLowBattery = service.config.readParameterInt(
                            Config.SETTING_LOW_BATTERY_STATUS,
                            Config.PARAMETER_LOW_BATTERY_STATUS_SECONDS_LOWER);

                // convert from seconds (configuration) to our set poll period, rounding up
                debounceLowBattery = (debounceLowBattery*10 + IoService.INPUT_POLL_PERIOD_TENTHS-1) / IoService.INPUT_POLL_PERIOD_TENTHS;

            }
        } else { // not in a wrong state, should not be debouncing
            debounceLowBattery = 0;
        }

        if (debounceLowBattery > 0) { // we are debouncing
            debounceLowBattery--;
            if (debounceLowBattery == 0) { // done debouncing
                status.flagLowBattery = !status.flagLowBattery;

                if (status.flagLowBattery) {
                    Log.i(TAG, "Low Battery On (" + (voltage / 10.0) + "V)");
                    service.addEvent(EventType.EVENT_TYPE_LOW_BATTERY_ON);
                    service.state.writeState(State.FLAG_LOWBATTERY_STATUS, 1); // remember this
                } else {
                    Log.i(TAG, "Low Battery Off ("  + (voltage / 10.0) + "V)");
                    service.addEvent(EventType.EVENT_TYPE_LOW_BATTERY_OFF);
                    service.state.writeState(State.FLAG_LOWBATTERY_STATUS, 0); // remember this
                }
            }
        }

        return status.flagLowBattery;
    } // checkLowBattery()



    //////////////////////////////////////////////////////////
    // checkDigitalInput()
    //  checks the physical state of the input and determines the logical state
    //  called every 1/10th second
    // Parameters:
    //  physical_on : 0 for low, 1 for high, -1 for float
    //////////////////////////////////////////////////////////
    boolean checkDigitalInput(int input_num, int physical_on) {
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
                    event_on_id = EventType.EVENT_TYPE_INPUT1_ON;
                    event_off_id = EventType.EVENT_TYPE_INPUT1_OFF;
                    state_id = State.FLAG_GENERAL_INPUT1;
                break;
            case 2: setting_id = Config.SETTING_INPUT_GP2;
                    event_on_id = EventType.EVENT_TYPE_INPUT2_ON;
                    event_off_id = EventType.EVENT_TYPE_INPUT2_OFF;
                    state_id = State.FLAG_GENERAL_INPUT2;
                break;
            case 3: setting_id = Config.SETTING_INPUT_GP3;
                    event_on_id = EventType.EVENT_TYPE_INPUT3_ON;
                    event_off_id = EventType.EVENT_TYPE_INPUT3_OFF;
                    state_id = State.FLAG_GENERAL_INPUT3;
                break;
            case 4: setting_id = Config.SETTING_INPUT_GP4;
                    event_on_id = EventType.EVENT_TYPE_INPUT4_ON;
                    event_off_id = EventType.EVENT_TYPE_INPUT4_OFF;
                    state_id = State.FLAG_GENERAL_INPUT4;
                break;
            case 5: setting_id = Config.SETTING_INPUT_GP5;
                    event_on_id = EventType.EVENT_TYPE_INPUT5_ON;
                    event_off_id = EventType.EVENT_TYPE_INPUT5_OFF;
                    state_id = State.FLAG_GENERAL_INPUT5;
                break;
            case 6: setting_id = Config.SETTING_INPUT_GP6;
                    event_on_id = EventType.EVENT_TYPE_INPUT6_ON;
                    event_off_id = EventType.EVENT_TYPE_INPUT6_OFF;
                    state_id = State.FLAG_GENERAL_INPUT6;
                break;
            default:
                Log.w(TAG, "setDigitalInput() Unimplemented Input #" + input_num);
                return false;
        }
        boolean flagInput = ((status.input_bitfield & (1 << input_num)) != 0);

        // get the active level for this input as a boolean and determine the instantaneous logical state
        int active_level_i = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_BIAS);
        boolean logical_on; // the instantaneous logical state

        if (physical_on == IoServiceHardwareWrapper.HW_INPUT_FLOAT)  // physically floating, this means we are logically inactive
            logical_on = false;
        else if (active_level_i == 0) // this input is active-low, convert
            logical_on = (physical_on == 1 ? false : true);
        else
            logical_on = (physical_on == 1 ? true : false);


        // are we in reset?
        if (gpInputResets[input_num-1] > 0) {
            // yep, don't do anything except return until time period expires
            if (!logical_on) { // we are inactive, just wait for reset period to expire
                gpInputResets[input_num - 1]--;
            } else { // we are logically active again (too soon), restart the reset period
                // get the reset period before we can turn back logically on
                gpInputResets[input_num-1] = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_TENTHS_RESET_AFTER_OFF);
                // convert from tenths of a second (configuration) to our set poll period, rounding up
                gpInputResets[input_num - 1] = (gpInputResets[input_num - 1] + IoService.INPUT_POLL_PERIOD_TENTHS - 1) / IoService.INPUT_POLL_PERIOD_TENTHS;
            }
            return ((status.input_bitfield & (1 << input_num)) != 0);
        }


        // not in reset ... start checking this input as normal
        if (logical_on != flagInput) { // in the wrong state, should be debouncing
            if (gpInputDebounces[input_num-1] == 0) {

                if (logical_on)
                    gpInputDebounces[input_num - 1] = service.config.readParameterInt(
                        setting_id,
                        Config.PARAMETER_INPUT_GP_TENTHS_DEBOUNCE_TOACTIVE);
                else {
                    gpInputDebounces[input_num - 1] = service.config.readParameterInt(
                            setting_id,
                            Config.PARAMETER_INPUT_GP_TENTHS_DEBOUNCE_TOINACTIVE);
                    if (gpInputDebounces[input_num - 1] == 0) {
                        gpInputDebounces[input_num - 1] = service.config.readParameterInt(
                                setting_id,
                                Config.PARAMETER_INPUT_GP_TENTHS_DEBOUNCE_TOACTIVE);
                    }
                }
                // convert from tenths of a second (configuration) to our set poll period, rounding up
                gpInputDebounces[input_num - 1] = (gpInputDebounces[input_num - 1] + IoService.INPUT_POLL_PERIOD_TENTHS - 1) / IoService.INPUT_POLL_PERIOD_TENTHS;
            }

        } else { // not in a wrong state, should not be debouncing
            gpInputDebounces[input_num-1] = 0;
        }

        if (gpInputDebounces[input_num-1] > 0) { // we are debouncing
            gpInputDebounces[input_num-1]--;
            if (gpInputDebounces[input_num-1] == 0) { // done debouncing
                flagInput = !flagInput;
                status.input_bitfield ^= (1 << input_num);

                if (flagInput) {
                    Log.i(TAG, "Input " + input_num + " On");
                        service.addEvent(event_on_id);
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
                        service.addEvent(event_off_id);

                    service.state.writeState(state_id, 0); // remember this

                    // start the reset period before we can turn back logically on
                    gpInputResets[input_num-1] = service.config.readParameterInt(setting_id, Config.PARAMETER_INPUT_GP_TENTHS_RESET_AFTER_OFF);
                    // convert from tenths of a second (configuration) to our set poll period, rounding up
                    gpInputResets[input_num - 1] = (gpInputResets[input_num - 1] + IoService.INPUT_POLL_PERIOD_TENTHS - 1) / IoService.INPUT_POLL_PERIOD_TENTHS;

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

        return ((status.input_bitfield & (1 << input_num)) != 0);
    } // checkDigitalInput()


    //  *************************************************
    //  Methods for async tasks (Init and polling)
    //  *************************************************

    void parseReceivedHardwareInit(Intent intent) {

        deviceConstants.deviceId = intent.getStringExtra("deviceId");

        if ((deviceConstants.deviceId == null) || (deviceConstants.deviceId.isEmpty())) {
            deviceConstants.deviceId = DEFAULT_SERIAL_NUMBER;
            Log.i(TAG, "Using Default Serial Number " + deviceConstants.deviceId);
        }

        deviceConstants.deviceBootCapturedInputs = intent.getIntExtra("deviceBootCapturedInputs", 0);
        deviceConstants.io_scheme = intent.getIntExtra("ioScheme", 0);

    } // parseReceivedHardwareInit

    //////////////////////////////////////////////////////////
    // InitReceiver()
    //   this task must complete before I/O is "ready" to be used
    //   gets vital things like the Serial Number of the device
    //      and battery voltage to include in messages
    //////////////////////////////////////////////////////////
    IoInitReceiver ioInitReceiver = new IoInitReceiver();
    class IoInitReceiver extends BroadcastReceiver {


        boolean finish = false; // we don't to not finish an io.start() until we get init data for the service
                                     // this way our events have correct voltage

        @Override
        public void onReceive(Context context, Intent intent)
        {
            try {

                Log.vv(TAG, "initTask()");

                // This may just be a process Id
                int processId = intent.getIntExtra("processId", 0);
                if (processId != ioServiceProcessId) {
                    ioServiceProcessId = processId;
                    Log.i(TAG, "Received PID " + ioServiceProcessId + " for IO service");
                }

                if (intent.getBooleanExtra("containsInit", false)) { // does this contain Init info ?


                    Log.d(TAG, "Received Init Info from IO service");

                    parseReceivedHardwareInit(intent);

                    double voltage = intent.getDoubleExtra("voltage", 0);
                    setVoltageInput(voltage);

/*
                // determine if we should be using an analog or digital scheme
                io_scheme = getHardwareIoScheme();

                deviceConstants.deviceId = getHardwareDeviceId();
                deviceConstants.deviceBootCapturedInputs = getHardwareBootState();
                // set the initial physical voltage value .. this is needed to be put into messages
                HardwareVoltageResults hardwareVoltageResults = getHardwareVoltageOnly();
                if (hardwareVoltageResults  != null)
                    setVoltageInput(hardwareVoltageResults.voltage);

*/

                // We don't need to set other input states, as these are logical (and came from the state xml file).
                // In fact, we should NOT set them, otherwise any transitions from file will not be detected

                // mark this init as completed (it's ok the send messages now)
                deviceConstants.isValid = true;

                    if (finish) { // finish starting this io
                        finishStart();
                    } // finishStart
                } // containsInit


                // start the sensor before reading initial values so we are notified of changes.
                //startSensor();

                // set the initial physical input values
                //sensorInputResults = getHardwareInputs();

                /*
                // schedule the I/O poller
                if (!service.isUnitTesting) {
					// We dont want to start separate threads when we are unit testing
                exec = new ScheduledThreadPoolExecutor(1);
                int pollperiodms = INPUT_POLL_PERIOD_TENTHS * 100;
                exec.scheduleAtFixedRate(new IoPollTask(), pollperiodms, pollperiodms, TimeUnit.MILLISECONDS); // check levels

                }
                */

            } catch (Exception e) {
                Log.e(TAG, "IoInitReceiver Exception " + e.toString(), e);

            }

        } // onReceive()
    } // IoInitReceiver()



    IoServiceHardwareWrapper.HardwareInputResults parseReceivedHardwareInputs(Intent intent) {
        IoServiceHardwareWrapper.HardwareInputResults hir = new IoServiceHardwareWrapper.HardwareInputResults();

        hir.ignition_input = intent.getBooleanExtra("ignition_input", false);
        hir.ignition_valid = intent.getBooleanExtra("ignition_valid", false);
        hir.input1 = intent.getIntExtra("input1", 0);
        hir.input2 = intent.getIntExtra("input2", 0);
        hir.input3 = intent.getIntExtra("input3", 0);
        hir.input4 = intent.getIntExtra("input4", 0);
        hir.input5 = intent.getIntExtra("input5", 0);
        hir.input6 = intent.getIntExtra("input6", 0);
        hir.voltage = intent.getDoubleExtra("voltage", 0);
        hir.savedTime = intent.getLongExtra("savedTime", 0);

        return hir;

    } // parseReceivedHardwareInputs()


    //////////////////////////////////////////////////////////
    // IoPollReceiver()
    //   the receiver that processes the I/O
    //////////////////////////////////////////////////////////
    IoPollReceiver ioPollReceiver = new IoPollReceiver();
    class IoPollReceiver extends BroadcastReceiver {

        //int counter = 0;

        @Override
        public void onReceive(Context context, Intent intent)
        {
            try {
                Log.vv(TAG, "ioInputReceiver()");

                // we should ignore all results until we got the valid init

                if (!deviceConstants.isValid) {
                    Log.i(TAG, "Received IO input data, but still waiting for init Data");
                    return;
                }


                lastIoReceived = SystemClock.elapsedRealtime();


                IoServiceHardwareWrapper.HardwareInputResults hardwareInputResults;

                hardwareInputResults= parseReceivedHardwareInputs(intent);

                double voltage_input;
                boolean ignition_input, ignition_valid;
                int input1, input2, input3, input4, input5, input6;

                voltage_input = DEFAULT_BATTERY_VOLTS;
                ignition_input = DEFAULT_IGNITION_STATE;
                ignition_valid = true;
                input1 = (DEFAULT_INPUT1_STATE ? 1 : 0);
                input2 = (DEFAULT_INPUT2_STATE ? 1 : 0);
                input3 = (DEFAULT_INPUT3_STATE ? 1 : 0);
                input4 = (DEFAULT_INPUT4_STATE ? 1 : 0);
                input5 = (DEFAULT_INPUT5_STATE ? 1 : 0);
                input6 = (DEFAULT_INPUT6_STATE ? 1 : 0);
                //lastIoTaskAttemptTime = SystemClock.elapsedRealtime();
                //ioTaskRunning = true;

                //HardwareVoltageResults hardwareVoltageResults = getHardwareVoltage();
                //ioTaskRunning = false;

                // take into account the state of the application to correct any values we don't trust
                correctHardwareInputs(deviceConstants.io_scheme, hardwareInputResults);

                if (!DEFAULT_ALWAYS_OVERRIDE) {
                    if (hardwareInputResults != null) {
                        voltage_input = hardwareInputResults.voltage;
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


                // log the current state values for physical input once every 10 seconds
                //counter++;
                //if ((counter % 10) == 0) {
                if ((hardwareInputResults != null )) {
                    Log.d(TAG, "Inputs (Phy): " +
                                    (voltage_input) + "V" +
                                    " , IGN:" + (ignition_valid ? (ignition_input ? "1" : "0") : "?") +
                                    " , IN1:" + (input1 == IoServiceHardwareWrapper.HW_INPUT_UNKNOWN ? "?" : (input1 == IoServiceHardwareWrapper.HW_INPUT_FLOAT ? "F" : input1)) +
                                    " , IN2:" + (input2 == IoServiceHardwareWrapper.HW_INPUT_UNKNOWN ? "?" : (input2 == IoServiceHardwareWrapper.HW_INPUT_FLOAT ? "F" : input2)) +
                                    " , IN3:" + (input3 == IoServiceHardwareWrapper.HW_INPUT_UNKNOWN ? "?" : (input3 == IoServiceHardwareWrapper.HW_INPUT_FLOAT ? "F" : input3)) +
                                    " , IN4:" + (input4 == IoServiceHardwareWrapper.HW_INPUT_UNKNOWN ? "?" : (input4 == IoServiceHardwareWrapper.HW_INPUT_FLOAT ? "F" : input4)) +
                                    " , IN5:" + (input5 == IoServiceHardwareWrapper.HW_INPUT_UNKNOWN ? "?" : (input5 == IoServiceHardwareWrapper.HW_INPUT_FLOAT ? "F" : input5)) +
                                    " , IN6:" + (input6 == IoServiceHardwareWrapper.HW_INPUT_UNKNOWN ? "?" : (input6 == IoServiceHardwareWrapper.HW_INPUT_FLOAT ? "F" : input6))
                    );
                }

                    //}


                if (ignition_valid)
                    checkIgnitionInput(ignition_input);
                else
                    debounceIgnition = 0; // freeze the state we are in (needed because of ignition-comingling bug)
                setVoltageInput(voltage_input);
                checkEngineStatus();
                if (input1 != IoServiceHardwareWrapper.HW_INPUT_UNKNOWN)
                    checkDigitalInput(1, input1);
                if (input2 != IoServiceHardwareWrapper.HW_INPUT_UNKNOWN)
                    checkDigitalInput(2, input2);
                if (input3 != IoServiceHardwareWrapper.HW_INPUT_UNKNOWN)
                    checkDigitalInput(3, input3);
                if (input4 != IoServiceHardwareWrapper.HW_INPUT_UNKNOWN)
                    checkDigitalInput(4, input4);
                if (input5 != IoServiceHardwareWrapper.HW_INPUT_UNKNOWN)
                    checkDigitalInput(5, input5);
                if (input6 != IoServiceHardwareWrapper.HW_INPUT_UNKNOWN)
                    checkDigitalInput(6, input6);


                //watchdog_IoPollTask = true; // successfully completed
            } catch (Exception e) {
                Log.e(TAG, "IoPollReceiver Exception " + e.toString(), e);
            }
            //ioTaskRunning = false;

        } // onReceive()
    } // IoPollReceiver()


    private Runnable delayedEngineOnTask  = new Runnable() {
        @Override
        public void run() {
            try {
                // now that the delay has expired we can add the event, hopefully there has been enough time
                // to try and talk to all the buses and get VIN, etc..
                Codec codec = new Codec(service);
                service.addEventWithData(EventType.EVENT_TYPE_ENGINE_STATUS_ON, codec.dataForEngineOn());
                stopWaitEngineOnMessage(); // since we sent this, we no longer need to wait to send it
            } catch(Exception e) {
                Log.e(TAG + ".delayedEngineOnTask", "Exception: " + e.toString(), e);
            }
        }

    }; // delayedEngineOnTask()

    ///////////////////////////////////////////////////////////////
    // shutdownWindowTask()
    //  Safety timer that ends the shutdown window after shutdown is requested
    ///////////////////////////////////////////////////////////////
    private Runnable shutdownWindowTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.e(TAG, "shutdown window expired w/o shutdown!!!");
                inShutdownWindow = false;
            } catch(Exception e) {
                Log.e(TAG + ".shutdownWindowTask", "Exception: " + e.toString(), e);
            }
        }
    }; // shutdownWindowTask()
    long getLastIoReceiptTime() {
        return lastIoReceived;
    }


    void killIoService() {
        if (ioServiceProcessId  != 0) { // do we have a valid process Id?
            Log.d(TAG, "Killing IO Service PID " + ioServiceProcessId );
            android.os.Process.killProcess(ioServiceProcessId );
            ioServiceProcessId  = 0; // no longer valid
        }
    }


    ///////////////////////////////////////////////////////////
    // restart(): Restart the service
    //  restart can be useful to clear any problems in the app during troubleshooting
    ///////////////////////////////////////////////////////////
    public void restartIosProcess() {

        Log.i(TAG, "Restarting IO Service (expect 2s delay)");

        lastIoReceived= SystemClock.elapsedRealtime();

        AlarmManager alarmservice = (AlarmManager) service.context.getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)


        Intent i = new Intent(service.context, IoServiceAlarmReceiver.class); // it goes to this guy
        i.setPackage(service.context.getPackageName());
        i.setAction(IoService.SERVICE_ACTION_START);

        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(service.context, Power.ALARM_IOS_RESTART_REQUEST_CODE, i, 0);
        alarmservice.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi); // wait 1 second
        //service.stopSelf();

        killIoService();


    } // restartIosProcess



/*
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

*/
} // class
