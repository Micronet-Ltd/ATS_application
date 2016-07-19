package com.micronet.dsc.ats;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.content.Context;

// For notification and Foreground processing

import android.app.Service;
import 	android.app.Notification;
import 	android.app.Notification.Builder;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.os.StrictMode;
import android.os.SystemClock;


public class MainService extends Service {

    /////////////////////////////////////
    // Global Configuration constants used during release and debugging.
    //

    // Enable/Disable Features that were added or removed after the project started
    //  and would contradict the original contract requirements
    //  set this to true to produce a contract-compliant build, false for normal builds.
    public static final boolean ENFORCE_STRICT_CONTRACT_CONFORMANCE = false;


    // Features that were requested during development and later withdrawn:
    //  Generally, these should always be false unless they are requested again:
    public static final boolean SHOULD_WRITE_FAKE_CONFIGURATION_XML = false; // Write fake entry #0 to configuration files to make sure they exist
    public static final boolean SHOULD_SELF_DESTROY_ON_SHUTDOWN = false; // the service should try to kill itself when OS is shutting down, before the OS does it.
    public static final boolean SHOULD_BROADCAST_REDBEND_ON_HEARTBEAT = false; // send broadcast to redbend on heartbeat

    // Features that are likely to be removed later
    // Generally, these should always be true
    public static final boolean SHOULD_KEEP_SCREEN_ON = true; // always keep the screen on while running
    public static final boolean SHOULD_AUTO_RESET_FOTA_AFTER_5_MINUTES = false; // send request to reset FOTA files after 5 minutes


    // Features that were previously disabled and now enabled again
    // Generally, these should always be true




    ///////////////////////////////////////////
    // Debug Options -- these should all be false before releasing
    public static final boolean DEBUG_IO_IGNORE_VOLTAGE_FOR_ENGINE_STATUS = false;





    /////////////////////////////////////
    // Begin actual class

    public static final String TAG = "ATS-Service";

    public static final String SERVICE_ACTION_WD_RESTART = "wdrestart";

    public static final int ONGOING_NOTIFICATION_ID = 12345; // unique ID for the foreground notification

    //  Generally these should be false except when building a release that needs to conform to the contract.
    public static final boolean SHOULD_RESET_SEQ_ON_WAKE = ENFORCE_STRICT_CONTRACT_CONFORMANCE; // true = reset the sequence ID in UDP protocol on wakeup, otherwise just keep incrementing
    public static final boolean SHOULD_SEND_CURRENT_CELL = ENFORCE_STRICT_CONTRACT_CONFORMANCE; // true = send current cell information in udp protocol, otherwise send stored info



    static int processId = 0;

    Context context;
    Config config;
    CodeMap codemap;
    State state;

    Queue queue;
    Io io;
    Power power;
    Ota ota;
    Position position;
    Engine engine;
    LocalMessage local;

    static boolean isUnitTesting = false; // this is set when we unit test to deal with threading, etc..
    boolean isAlreadyRunning = false;

    long lastRunningElapsedTime; // the last time we knew for sure we were running
    long createdElapsedTime; // the time this service ran OnCreate()

    ScheduledThreadPoolExecutor exec = null;

    // Constructor for real-life
    public MainService() {
    }

    // Constructor for test cases, pass a fake context and initializes all objects without waiting for OnCreate()
    public MainService(Context context) {
        isUnitTesting = true; // this is used by other modules to know not to start threads, etc..
        initializeObjects(context);
    }




    ///////////////////////////////////////////
    // initializeObjects() : init the objects that are needed for both testing and real-life
    //  in test-environment, this is called directly. In real-life it's called from OnCreate()
    ///////////////////////////////////////////
    public void initializeObjects(Context context) {

        isAlreadyRunning = false;

        this.context = context;

        config = new Config(context);
        int config_files_used = config.open();

        codemap = new CodeMap(context);
        int codemap_files_used = codemap.open();
        state = new State(context);

        local = new LocalMessage(this);

        // These require access to the config and state variables
        queue = new Queue(this);
        queue.open();
        power = new Power(this);

        // These require access to the config, state, and queue variables:
        ota = new Ota(this);

        // These require access to the config, state, queue, and other variables:
        io = new Io(this);
        position = new Position(this);

        engine = new Engine(this);

        if ((config_files_used > 0) || (codemap_files_used > 0)) {
            // we loaded an alternate configuration file, add a message to the queue
            // Extra data:
            //      |1 = configuration.xml replaced
            //      |2 = moeventcodes.xml replaced
            //      |4 = mteventcodes.xml replaced
            addEventWithExtra(EventType.EVENT_TYPE_CONFIGURATION_REPLACED,
                    (config_files_used & 1) + ((codemap_files_used & 3) << 1)
            );

        } else {
            Log.i(TAG, "No new configuration files found");
        }


    } // initializeObjects()


    ///////////////////////////////////////////////////////////////////
    // checkRecentAlarms()
    //  checks whether any recent heartbeats or scheduled wake-ups occurred
    //  this needs to be done after boot, since we may not receive the alarm notification
    ///////////////////////////////////////////////////////////////////
    public void checkRecentAlarms() {
        // check to see if this could have been caused by a heartbeat
        // if it could have been caused by heartbeat, then send the heartbeat alarm.

        Log.v(TAG, "checkRecentAlarms()");

        if (power.wasCurrentAlarmRecent(Power.ALARM_HEARTBEAT_NAME)) {

            Log.i(TAG, "  Last scheduled heartbeat was recent, assuming this caused boot.");
            // this was started by an alarm, we want to acquire a lock and trigger an event

            int keepawake_sec = config.readParameterInt(Config.SETTING_HEARTBEAT, Config.PARAMETER_HEARTBEAT_KEEPAWAKE);
            power.setHeartbeatWakeLock(keepawake_sec);

            addEvent(EventType.EVENT_TYPE_HEARTBEAT);

            power.openFOTAUpdateWindow(keepawake_sec);
        }
        if (power.wasCurrentAlarmRecent(Power.ALARM_SCHEDULED_NAME)) {

            Log.i(TAG, "  Last scheduled wakeup was recent, assuming this caused boot.");
            // this was started by an alarm, we want to acquire a lock and trigger an event


            int keepawake_sec = config.readParameterInt(Config.SETTING_SCHEDULED_WAKEUP, Config.PARAMETER_SCHEDULED_WAKEUP_KEEPAWAKE);
            power.setScheduledWakeLock(keepawake_sec);

        }


    } // checkRecentAlarms()

    @Override
    public void onCreate() {
        // The service is being created

        processId = android.os.Process.myPid();
        Log.i(TAG, "Service Created: ATS version " + BuildConfig.VERSION_NAME);


        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyDeath()
                .build());


        // Initialize all objects with this context
        initializeObjects(this);


        // Create shard_pref files if they dont exist
        //  b/c GSD updater cannot create files on the device if they don't already exist ????
        if (SHOULD_WRITE_FAKE_CONFIGURATION_XML){
            config.writeFakeSetting();
            codemap.writeFakeMoEventCode();
            codemap.writeFakeMtEventCode();
        }

        // Check for saved crash data to restore
        Crash crash = new Crash(context);
        if (crash.isRestoreable()) {
            Log.i(TAG, "Restoring saved crash data");
            power.restoreCrashData(crash);
            io.restoreCrashData(crash);
            position.restoreCrashData(crash);
            engine.restoreCrashData(crash);
        }
        // Clear any crash data that we stored previously REGARDLESS IF IT is restorable
        crash.clearAll();


        // Before starting power or io, we should check if we potentially rebooted since last ran
        if (power.hasRebooted()) {
            engine.setWarmStart(false); // we can't do a warm start if there is a chance we have rebooted
        }

        // start things which have timers, receivers, etc, and only get started once..
        local.start();  // local broadcasts
        power.start();
        io.start();
        ota.start();
        position.init(); // Don't "start" the position, just get the last known location

        createdElapsedTime = SystemClock.elapsedRealtime();

    } // onCreate()

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The service is starting, due to a call to startService()

        Log.v(TAG, "OnStartCommand() " + (intent == null ? "" : intent.toString()));
        processId = android.os.Process.myPid(); // just update (I don't think it can change, but doesn't hurt)

        //boolean skipSetup = false; // default is to reset alarms, foregroundness, etc..

        boolean doRegularSetup = true;
        boolean doTriggerRestartEvent = true;


        // we will send a message when we are booted up, and a resume message if we are not already running


        if (intent != null) {

            final String action = intent.getAction();

            if (action != null) {
                if (action.equals(SERVICE_ACTION_WD_RESTART)) {
                    if (isAlreadyRunning) {
                        Log.i(TAG, " (Started From Watchdog Error)");
                        power.restartAtsProcess();
                        return START_NOT_STICKY;
                    } else {
                        Log.i(TAG, " (Started From Watchdog Error -- Not previously running)");
                        // just keep going and start
                    }
                }
            }



            final int heartbeat_id = intent.getIntExtra(Power.ALARM_HEARTBEAT_NAME, 0);
            final int scheduled_id = intent.getIntExtra(Power.ALARM_SCHEDULED_NAME, 0);
            final int boot_id = intent.getIntExtra(Power.BOOT_REQUEST_NAME, 0);
            final int shutdown_id = intent.getIntExtra(Power.SHUTDOWN_REQUEST_NAME, 0);
            final int restart_id = intent.getIntExtra(Power.ALARM_RESTART_NAME, 0);
            final int ping_id = intent.getIntExtra(ExternalReceiver.EXTERNAL_BROADCAST_PING, 0);
            final int payload_id = intent.getIntExtra(ExternalReceiver.EXTERNAL_SEND_PAYLOAD, 0);
            final int resetrb_id = intent.getIntExtra(ExternalReceiver.EXTERNAL_BROADCAST_RESETRB_REPLY, 0);

            if (heartbeat_id == 1) {
                // this was started by a heartbeat alarm, we want to acquire a lock and trigger an event
                Log.i(TAG, " (Started From Heartbeat Alarm)");

                if (power.isCurrentAlarmInvalid(Power.ALARM_HEARTBEAT_NAME)) {
                    Log.i(TAG, "  Ignoring Heartbeat (scheduled with invalid time and time is now valid)");
                } else {

                    int keepawake_sec = config.readParameterInt(Config.SETTING_HEARTBEAT, Config.PARAMETER_HEARTBEAT_KEEPAWAKE);
                    power.setHeartbeatWakeLock(keepawake_sec);

                    addEvent(EventType.EVENT_TYPE_HEARTBEAT);

                    power.openFOTAUpdateWindow(keepawake_sec); // will open window if build option is set
                }
            } else if (scheduled_id == 1) {
                // this was started by a scheduled wakeup alarm, we want to acquire a lock
                Log.i(TAG, " (Started From Scheduled Wakeup)");

                if (power.isCurrentAlarmInvalid(Power.ALARM_SCHEDULED_NAME)) {
                    Log.i(TAG, "  Ignoring Scheduled Wakeup (scheduled with invalid time and time is now valid)");
                } else {

                    int keepawake_sec = config.readParameterInt(Config.SETTING_SCHEDULED_WAKEUP, Config.PARAMETER_SCHEDULED_WAKEUP_KEEPAWAKE);
                    power.setScheduledWakeLock(keepawake_sec);

                }


            } else if (boot_id == 1) {
                Log.i(TAG, " (Started From System Boot)");
                //isAlreadyRunning = true; // trick below into thinking we are already running so we don't send a restarted message

                doTriggerRestartEvent = false;

                clearEventSequenceIdIfNeeded();

                engine.setWarmStart(false); // don't do a warm-start (check for bus presence, read VIN again, etc..)

                // We need to trigger a BOOT EVENT, which requires us to know the boot-capture input states
                //  since these are included in the event.
                // The boot-capture input states are determined asynchronously in case they jam
                //  so we must wait a reasonable amount of time.
                // After the time-out, we should record the event anyway but mark the boot state as indeterminate

                while ((!io.isFullyInitialized()) &&
                        (SystemClock.elapsedRealtime() - createdElapsedTime < 2500)) {
                    try {
                        Thread.sleep(50);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in Thread.sleep(50)");
                    }
                }

                int io_boot_state;

                if (io.isFullyInitialized())
                    io_boot_state = io.getBootState();
                else
                    io_boot_state = -1; // record this event anyway, mark this as an error, unable to determine state
                
                int data = Codec.dataForSystemBoot(io_boot_state);
                addEventWithExtra(QueueItem.EVENT_TYPE_REBOOT, data); // system booted

                // check to see if this could have been caused by a heartbeat
                // if it could have been caused by heartbeat, then send the heartbeat alarm.
                checkRecentAlarms(); // we need to check for recent alarms if we just booted

            } else if (shutdown_id == 1) {
                Log.i(TAG, " (Started From System Shutdown)");
                //skipSetup = true;

                // we are trying to shut down, so we don't really need to do setup stuff.
                doRegularSetup = false;
                doTriggerRestartEvent = false; // never send a restart, even if we weren't running

                //power.killService(); // can;t kill the process or the intent will just be redelivered

                // We need to start the shutdown timer, during which period we ignore erratic certain signals from Inputs
                io.startShutdownWindow();
                stopWatchdogService(); // stop any watchdogs we have running on this service

                // We should trigger a message unless we already know we are shutting down
                if (!power.hasSentShutdown()) {
                    Codec codec = new Codec(this);
                    int data = codec.dataForSystemShutdown(Codec.SHUTDOWN_REASON_SYS_SHUTDOWN);
                    addEventWithExtra(QueueItem.EVENT_TYPE_SHUTDOWN, data);
                }


                if ((SHOULD_SELF_DESTROY_ON_SHUTDOWN) ||   // we want to destroy the service when we get this
                    (!isAlreadyRunning)) {   // we weren't already running, just destroy this service and ignore
                    // Try to shut down this service
                    stopSelf(); // stop the service (this will call shutdown when OnDestroy is called)
                    return START_NOT_STICKY;
                }

            } else if (restart_id == 1) {
                String source = intent.getStringExtra("source");
                Log.i(TAG, " (Started From Process Killer: source = " + source);

                // We may need to add a message to queue if this was started from an external process killer

                if ((source != null) && (!source.isEmpty())) {
                    if (source.equals(WatchdogService.SERVICE_EXTRA_SOURCE_NAME)) {
                        //Log.i(TAG, " (killed by external Watchdog Service -- some data might be lost)");
                        addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_EXTERNAL_WATCHDOG);
                    }
                }
            } else if (resetrb_id == 1) {
                // This can happen frequently, so don't spend time reseting alarms or foreground, etc unless needed
                skipSetup = true;

                Log.v(TAG, " (Started From ResetRB FOTA Updater ACK)");
                addEvent(QueueItem.EVENT_TYPE_RESET_FOTA_UPDATER);
            
            } else if (ping_id == 1) {

                //skipSetup = true;
                if (!isAlreadyRunning) { // don't clutter logs up
                    Log.v(TAG, " (Started From Externa)");
                } else {
                    // This can happen frequently, so don't spend time reseting alarms or foreground, etc unless needed
                    doRegularSetup = false;
                }
            } else if (payload_id == 1) {

                //skipSetup = true;
                if (!isAlreadyRunning) { // don't clutter logs up
                    Log.v(TAG, " (Started From Payload Forwarder)");

                } else {
                    // This can happen frequently, so don't spend time reseting alarms or foreground, etc unless needed
                    doRegularSetup = false;
                }

            byte[] payload = intent.getByteArrayExtra(ExternalReceiver.EXTERNAL_SEND_PAYLOAD_DATA_PAYLOAD);

                if ((payload.length > 0) && (payload.length <= ExternalReceiver.MAX_PAYLOAD_DATA_SIZE)) { // safety
                    addEventWithData(EventType.EVENT_TYPE_CUSTOM_PAYLOAD, payload);
                }
            } else {
                Log.i(TAG, " (Started From Other/Unknown Intent)");
                // If the service can be started by another app (such as kiosk) or user on boot,
                // then we need to check for recent alarms, because we may have booted which then caused another app to start us.

                // Since we are always registering to start on boot, we don't need to do this
                // checkRecentAlarms(); // we need to check for recent alarms

            }


        }  else { // intent was not null
            Log.i(TAG, " (Started From NULL Intent)");
        }


        if (!isAlreadyRunning) {
            // first time starting after loading
            isAlreadyRunning = true; // remember for next time we are already running
        } else { // we were already running

            //skipSetup = false; // if we weren't running we def need to do all setup
            doTriggerRestartEvent = false;
        }

        if (doTriggerRestartEvent) {
            clearEventSequenceIdIfNeeded();
            addEventWithExtra(QueueItem.EVENT_TYPE_RESTART, BuildConfig.VERSION_CODE); // service restarted
        }



        if (lastRunningElapsedTime == 0) { // initial condition after creation
            // and start a timer to make sure we haven't fallen asleep
            lastRunningElapsedTime = SystemClock.elapsedRealtime(); // we know we were running at this time
            if (!isUnitTesting) {
				// Don't start other threads when unit testing

                startWatchdogThread();

                setAutoFotaResetIfNeeded();
            }
        }


        lastRunningElapsedTime = SystemClock.elapsedRealtime(); // we know we were running at this time


        // Always makes sure all alarm is set appropriately, unless this is just a ping and we were already running
        if (doRegularSetup) {

            startWatchdogService(processId, LocalMessage.BROADCAST_MESSAGE_PERIODIC_DEVICE); // start up a separate watchdog service if not already running

            power.setNextAlarm(Power.ALARM_HEARTBEAT_NAME);
            power.setNextAlarm(Power.ALARM_SCHEDULED_NAME);

            setForeground();
        }


        try {
            AlarmReceiver.completeWakefulIntent(intent);
        } catch (Exception e) {
            Log.e(TAG, "Exception during completeWakefulIntent: " + e.toString());
        }
        return START_STICKY;
    }


    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        MainService getService() {
            // Return this instance of LocalService so clients can call public methods in the service
            return MainService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "OnBind()");
        return mBinder;
    }
    /*
    @Override
    public boolean onUnbind(Intent intent) {
        // All clients have unbound with unbindService()
        return mAllowRebind;
    }
    @Override
    public void onRebind(Intent intent) {
        // A client is binding to the service with bindService(),
        // after onUnbind() has already been called
    }
    */
    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed

        // OnDestroy() is NOT guaranteed to be called, and won't be for instance
        //  if the app is updated
        //  if ths system needs RAM quickly
        //  if the user force-stops the application


        Log.v(TAG, "OnDestroy()");
        shutdownService(false); // normal shutdown, no need to save crash data
    }


    ////////////////////////////////////////////////////
    // shutdownService()
    //  call this to perform an orderly shutdown of all components
    //  note that this does not, by itself, kill the process or perform garbage collection.
    // Parameters
    //  save_crash_data : set this to true to save variable data to disk that will be used for init on next start of service
    ////////////////////////////////////////////////////
    public void shutdownService(boolean save_crash_data) {
        Log.i(TAG, "Shutting Down Service");

        if (exec != null) {
            exec.shutdown();
            exec = null;
        }

        local.stop(); // tear down local broadcasts
        power.stop(); // tears down FOTA windows and locks
        io.stop();  // tears down I/O polling
        ota.stop(); // tears down UDP
        queue.close(); // closes DB

        if (save_crash_data) {
            Log.i(TAG, "Saving crash data");
            Crash crash = new Crash(context);
            crash.edit();
            engine.saveCrashData(crash);
            power.saveCrashData(crash);
            io.saveCrashData(crash);
            position.saveCrashData(crash);
            crash.commit();
        }

        // start removing wake-locks, etc.
        engine.destroy();
        power.destroy();
        io.destroy();
        ota.destroy();

        // move us to the background
        setBackground();
    } // shutdownService()


    public void setForeground() {

        Intent myIntent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                myIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification noti = new Notification.Builder(context)
                .setContentTitle("A317 Telematics Service")
                .setContentText("is running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                        //.setLargeIcon(aBitmap)
                // Android 4.1 or newer: use build()
                //      .build();
                // Android 4.0 or older: use getNotification()
                .getNotification();

        startForeground(ONGOING_NOTIFICATION_ID, noti);
    }


    private void setBackground() {
        stopForeground(true); // true = remove notification
    }



    ////////////////////////////////////
    // queueEventWithData() : Add an event to the end of the queue
    //      creates the item from the given event_type_id,
    //  sets the trigger time and other data to now
    ////////////////////////////////////
    public void queueEventWithData(int event_type_id, byte[] data) {

        try {
            int i;
            for (i = 0; i < EventType.DISABLED_EVENTS.length; i++) {
                if (EventType.DISABLED_EVENTS[i] == event_type_id) {
                    // This event is disabled and should be silently ignored
                    return;
                }
            }


            // create an object to hold this event item
            QueueItem item = new QueueItem();
            item.event_type_id = event_type_id;
            item.additional_data_bytes = data;

            // get the sequence ID to use.


            synchronized (this) {
                int seq = state.readState(State.COUNTER_MESSAGE_SEQUENCE_INDEX);
                item.sequence_id = seq;

                // Determine the next sequence ID
                int new_sequence_id = seq + 1;
                if ((new_sequence_id & Codec.SEQUENCE_ID_RECEIVE_MASK) == 0) {
                    // don't allow sequence IDs where the mask bits are all 0.
                    new_sequence_id++;
                }
                state.writeState(State.COUNTER_MESSAGE_SEQUENCE_INDEX, new_sequence_id);
            }


            // save needed Information from time-of trigger

            // add position information
            position.populateQueueItem(item);
            // add I/O information
            io.populateQueueItem(item);
            // add OTA information
            ota.populateQueueItem(item);

            // Finally, Add this item to the queue
            queue.addItem(item);

        } catch (Exception e) {
            Log.e(TAG, "Exception: queueEventWithData() " + e.toString(), e);
        }

        //return item.getId();
    } // queueEventWithData()


    // other aliases
    public void addEventWithData(int event_type_id, byte[] data) {
        Log.d(TAG, "Triggered Event " + event_type_id);
        local.sendChangeMessage(event_type_id, data);

        if (config.shouldSendOTA(event_type_id)) { // send this message to the servers ?
            queueEventWithData(event_type_id, data);
        }

    }
    public void addEventWithExtra(int event_type_id, int extra) {

        Log.d(TAG, "Triggered Event " + event_type_id);

        byte[] data = new byte[1];
        data[0] = (byte) (extra & 0xFF);
        local.sendChangeMessage(event_type_id, data);

        if (config.shouldSendOTA(event_type_id)) { // send this message to the servers ?
            queueEventWithData(event_type_id, data);
        }

    }
    public void addEvent(int event_type_id) {

        Log.d(TAG, "Triggered Event " + event_type_id);

        local.sendChangeMessage(event_type_id, null);

        if (config.shouldSendOTA(event_type_id)) { // send this message to the servers ?
            queueEventWithData(event_type_id, null);
        }

    } // addEvent


    ////////////////////////////////////
    // clearEventSequenceId() :
    //  Resets the Message Sequence ID to 0
    ////////////////////////////////////
    public void clearEventSequenceId() {
        state.writeState(State.COUNTER_MESSAGE_SEQUENCE_INDEX, 0);
    } // clearEventSequenceId()


    ////////////////////////////////////
    // clearEventSequenceIdIfNeeded() :
    //  Reset the Message Sequence ID to 0, but only if the build constant is set
    //  this gets called at every wake-up
    ////////////////////////////////////
    public void clearEventSequenceIdIfNeeded() {

        // if we have our build flag to reset the sequence ID when waking, then do that now
        if (SHOULD_RESET_SEQ_ON_WAKE)
            clearEventSequenceId();

    } // clearSequenceIdIfNeeded()

    //////////////////////////////////////////////////////////////
    // setAutoFotaResetIfNeeded()
    //  This will try to auto reset the redbend FOTA by clearing out data files
    //  It does this immediately if it is more than 5 minutes after boot, or sets a timer to do this at 5 minutes after boot.
    //////////////////////////////////////////////////////////////
    public void setAutoFotaResetIfNeeded() {
        if (SHOULD_AUTO_RESET_FOTA_AFTER_5_MINUTES) {

            int version = state.readState(State.VERSION_SPECIFIC_CODE);

            if (version == BuildConfig.VERSION_CODE) {
                Log.d(TAG, "No Auto FOTA Reset needed (already ran after install)");
                return; // already executed code for this version
            }

            // If we are more than 5 minutes past boot, execute our version specific code, otherwise set the time to execute

            long elapsed_ms = SystemClock.elapsedRealtime(); // time since boot

            long schedule_in_ms = 300000 - elapsed_ms; // how long until 300 seconds after boot ?

            if (schedule_in_ms <= 500) schedule_in_ms = 500; // 500 ms from now minimum (if we are close to our past 300 seconds from boot)

            Log.d(TAG, "Scheduling Auto FOTA Reset for " + schedule_in_ms + " ms from now");
            exec.schedule(new VersionSpecificTask(), schedule_in_ms, TimeUnit.MILLISECONDS);


        }
    } // setAutoFotaResetIfNeeded()


    class VersionSpecificTask implements Runnable {
        @Override
        public void run() {
            power.resetFotaUpdater();

            // mark this as done so we don't do it again.
            state.writeState(State.VERSION_SPECIFIC_CODE, BuildConfig.VERSION_CODE);
        } // run
    }





    class AddTestPingTask implements Runnable {

        @Override
        public void run() {

            try {
                // add a Test Ping
            /*
            Log.d(TAG, "Test Ping Triggered");
            queue.addEvent(QueueItem.EVENT_TYPE_TEST);
            */
            } catch (Exception e) {}
        }
    }


    class MonitorTask implements Runnable {

        boolean sent_iothread_jam_message = false;

        final static int THREAD_WATCHDOG_MAX_IO_RECEIPT_MS = 2500; //2.5 seconds // IoService.INPUT_POLL_PERIOD_TENTHS*100*2; // allow 2x maximum time between IO receipts
        final static int THREAD_WATCHDOG_MAX_ENGINE_RECEIPT_MS = 2500; //2.5 seconds // VehicleBusService.BROADCAST_STATUS_DELAY_MS * 2;

        @Override
        public void run() {

            try {

                if (io.isInShutdown()) return; // don't monitor/restart anything while trying to shutdown

                Log.vv(TAG, "MonitorTask()");

                // check to see if any of our tasks have not been running like they are supposed to
                // hardware polling happens every 4 tenths of a second
/*
                runCounter++;

                // First, if it is running, make sure it completes. This is needed because sometimes the task
                //  scheduler gets frozen and this task will not be run again.
                if (io.isIoTaskRunning()) {

                    loop_cnt = 0;
                    while (loop_cnt < 40) { // 40 * 50ms = 2 seconds max wait

                        loop_cnt++;
                        android.os.SystemClock.sleep(50); // we can wait 50 ms
                        if (!io.isIoTaskRunning()) break; // we're OK now
                    }
                    if (loop_cnt == 40) {
                        // Ooops, this task has not stopped running
                        Log.e(TAG, "Watchdog Error. IO task running too long.");
                        if (!sent_iothread_jam_message) {
                            // we haven't yet sent a message to the server saying that we jammed
                            addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_IO_THREAD_JAMMED);
                            sent_iothread_jam_message = true;
                        }
                        // Take corrective action
                        power.restartService();
                    }

                } else // task scheduler is not currently running, just check that it has run in last 4 seconds
                if ((runCounter & MASK_RUNCOUNTER_4_SECONDS) == 0) { // 8 seconds have elapsed

                    if (io.isWatchdogError()) {
                        // Problem, we have not reset the watchdog recently!!!!

                    }
                }
*/

                long nowElapsedTime = SystemClock.elapsedRealtime();

                // Check that it hasn't been that long since attempting IO poll

                long lastIoPollTime = io.getLastIoReceiptTime();

                if ((nowElapsedTime > lastIoPollTime) &&
                        (nowElapsedTime - lastIoPollTime > THREAD_WATCHDOG_MAX_IO_RECEIPT_MS)) {
                    Log.e(TAG, "Thread Watchdog Error. IO service jammed. Last received " + (nowElapsedTime - lastIoPollTime) + " ms ago");
                    if (!sent_iothread_jam_message) {
                        // we haven't yet sent a message to the server saying that we jammed
                        addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_IO_THREAD_JAMMED);
                        sent_iothread_jam_message = true;
                    }

                    // restart the io service process
                    io.restartIosProcess();


                    //io.killIoService();

                    // Take corrective action
                    //power.restartService();
                }
/*
                long lastEnginePollTime = engine.getLastStatusReceiptTime();
                if ((nowElapsedTime > lastEnginePollTime) &&
                        (nowElapsedTime - lastEnginePollTime > THREAD_WATCHDOG_MAX_ENGINE_RECEIPT_MS)) {
                    Log.e(TAG, "Thread Watchdog Error. Vbus service jammed. Last received " + (nowElapsedTime - lastEnginePollTime) + " ms ago");
                    if (!sent_iothread_jam_message) {
                        // we haven't yet sent a message to the server saying that we jammed
                        addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_VBUS_THREAD_JAMMED);
                        sent_iothread_jam_message = true;
                    }

                    // kill vbus service
                    engine.restartVBusService();

                    // Take corrective action
                    //power.restartService();
                }
*/



                // checks to see if we have skipped time (meaning we were sleeping and now awake)
                if ((nowElapsedTime > lastRunningElapsedTime) &&
                        (nowElapsedTime - lastRunningElapsedTime > 5000)) { // remember there is supposed to be 3 s delay at start
                    // more than 5 seconds has elapsed, maybe we were sleeping ?

                    Log.w(TAG, "Just woke up! Last Sleep Monitor Check was " + (nowElapsedTime - lastRunningElapsedTime) + " ms ago");
                    clearEventSequenceIdIfNeeded();
                    addEvent(EventType.EVENT_TYPE_WAKEUP);

                }

                lastRunningElapsedTime = nowElapsedTime;

                    Log.vv(TAG, "MonitorTask() END");
            } catch (Exception e) {
                Log.e(TAG + ".MonitorTask", "Exception during MonitorTask:run() " + e.toString(), e);
            }

        }
    }


    void startWatchdogService(int newProcessId, String newBroadcastAction) {

        Log.v(TAG, "Starting Watchdog Service");

        Intent serviceIntent = new Intent(this, WatchdogService.class);
        serviceIntent.setAction(WatchdogService.WATCHDOG_SERVICE_ACTION_START);
        serviceIntent.putExtra(WatchdogService.WATCHDOG_EXTRA_BROADCASTACTION, newBroadcastAction); // action when the watchdog triggers
        serviceIntent.putExtra(WatchdogService.WATCHDOG_EXTRA_PROCESSID, newProcessId);  // PID to killw hen the watchdog triggers
        startService(serviceIntent);

    } // startWatchdogService()

    void stopWatchdogService() {

        Log.v(TAG, "Stopping Watchdog Service");

        Intent serviceIntent = new Intent(this, WatchdogService.class);
        serviceIntent.setAction(WatchdogService.WATCHDOG_SERVICE_ACTION_STOP);
        startService(serviceIntent);

    } // stopWatchdogService()

    void startWatchdogThread() {

        Log.v(TAG, "Starting Watchdog Thread");
        exec = new ScheduledThreadPoolExecutor(1);
        exec.scheduleWithFixedDelay(new MonitorTask(), 5000, 500, TimeUnit.MILLISECONDS); // after 3 second, check every 1/10 second that we are still awake
    } // startWatchdogThread()

} // Service
