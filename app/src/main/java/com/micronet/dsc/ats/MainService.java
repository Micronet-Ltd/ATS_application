package com.micronet.dsc.ats;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
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
    // Global Configuration constants used during release an debugging.
    //

    // Enable/Disable Features that were added or removed after the project started
    //  and would contradict the original contract requirements
    //  set this to true to produce a contract-compliant build, false for normal builds.
    public static final boolean ENFORCE_STRICT_CONTRACT_CONFORMANCE = false;


    // Features that were requested during development and later withdrawn:
    //  Generally, these should always be false unless they are requested again:
    public static final boolean SHOULD_WRITE_FAKE_CONFIGURATION_XML = false; // Write fake entry #0 to configuration files to make sure they exist
    public static final boolean SHOULD_SELF_DESTROY_ON_SHUTDOWN = false; // the service should try to kill itself when OS is shutting down, before the OS does it.

    // Features that are likely to be removed later
    // Generally, these should always be true
    public static final boolean SHOULD_KEEP_SCREEN_ON = true; // always keep the screen on while running

    // Features that were previously disabled and now enabled again
    // Generally, these should always be true
    public static final boolean SHOULD_BROADCAST_REDBEND_ON_HEARTBEAT = true; // send broadcast to redbend on heartbeat

    /////////////////////////////////////
    // Begin actual class

    public static final String TAG = "ATS-Service";

    public static final int ONGOING_NOTIFICATION_ID = 12345; // unique ID for the foreground notification

    //  Generally these should be false except when building a release that needs to conform to the contract.
    public static final boolean SHOULD_RESET_SEQ_ON_WAKE = ENFORCE_STRICT_CONTRACT_CONFORMANCE; // true = reset the sequence ID in UDP protocol on wakeup, otherwise just keep incrementing
    public static final boolean SHOULD_SEND_CURRENT_CELL = ENFORCE_STRICT_CONTRACT_CONFORMANCE; // true = send current cell information in udp protocol, otherwise send stored info

    Context context;
    Config config;
    CodeMap codemap;
    State state;

    Queue queue;
    Io io;
    Power power;
    Ota ota;
    Position position;

    boolean isAlreadyRunning = false;

    long lastRunningElapsedTime; // the last time we knew for sure we were running
    long createdElapsedTime; // the time this service ran OnCreate()

    ScheduledThreadPoolExecutor exec = null;

    // Constructor for real-life
    public MainService() {
    }

    // Constructor for test cases, pass a fake context and initializes all objects without waiting for OnCreate()
    public MainService(Context context) {
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

        // These require access to the config and state variables
        queue = new Queue(this);
        queue.open();
        power = new Power(this);

        // These require access to the config, state, and queue variables:
        ota = new Ota(this);

        // These require access to the config, state, queue, and other variables:
        io = new Io(this);
        position = new Position(this);


        if ((config_files_used > 0) || (codemap_files_used > 0)) {
            // we loaded an alternate configuration file, add a message to the queue
            // Extra data:
            //      |1 = configuration.xml replaced
            //      |2 = moeventcodes.xml replaced
            //      |4 = mteventcodes.xml replaced
            addEventWithExtra(QueueItem.EVENT_TYPE_CONFIGURATION_REPLACED,
                    (config_files_used & 1) + ((codemap_files_used & 3) << 1)
            );

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

            addEvent(QueueItem.EVENT_TYPE_HEARTBEAT);

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
        }
        // Clear any crash data that we stored previously REGARDLESS IF IT is restorable
        crash.clearAll();

        // start things which have timers, receivers, etc, and only get started once..
        power.start();
        io.start();
        ota.start();
        position.init(); // Don't "start" the position, just get the last known location

        createdElapsedTime = SystemClock.elapsedRealtime();


    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The service is starting, due to a call to startService()

        Log.v(TAG, "OnStartCommand() " + (intent == null ? "" : intent.toString()));


        boolean skipSetup = false; // default is to reset alarms, foregroundness, etc..

        // we will send a message when we are booted up, and a resume message if we are not already running


        if (intent != null) {
            final int heartbeat_id = intent.getIntExtra(Power.ALARM_HEARTBEAT_NAME, 0);
            final int scheduled_id = intent.getIntExtra(Power.ALARM_SCHEDULED_NAME, 0);
            final int boot_id = intent.getIntExtra(Power.BOOT_REQUEST_NAME, 0);
            final int shutdown_id = intent.getIntExtra(Power.SHUTDOWN_REQUEST_NAME, 0);
            final int restart_id = intent.getIntExtra(Power.ALARM_RESTART_NAME, 0);
            final int external_id = intent.getIntExtra(ExternalReceiver.EXTERNAL_BROADCAST_PING, 0);

            if (heartbeat_id == 1) {
                // this was started by a heartbeat alarm, we want to acquire a lock and trigger an event
                Log.i(TAG, " (Started From Heartbeat Alarm)");

                if (power.isCurrentAlarmInvalid(Power.ALARM_HEARTBEAT_NAME)) {
                    Log.i(TAG, "  Ignoring Heartbeat (scheduled with invalid time and time is now valid)");
                } else {

                    int keepawake_sec = config.readParameterInt(Config.SETTING_HEARTBEAT, Config.PARAMETER_HEARTBEAT_KEEPAWAKE);
                    power.setHeartbeatWakeLock(keepawake_sec);

                    addEvent(QueueItem.EVENT_TYPE_HEARTBEAT);

                    power.openFOTAUpdateWindow(keepawake_sec);
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
                isAlreadyRunning = true; // trick below into thinking we are already running
                clearEventSequenceIdIfNeeded();


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
                    io_boot_state = -1; // record this event anywaymark this as an error, unable to determine state
                addEventWithExtra(QueueItem.EVENT_TYPE_REBOOT, io_boot_state); // system booted

                // check to see if this could have been caused by a heartbeat
                // if it could have been caused by heartbeat, then send the heartbeat alarm.
                checkRecentAlarms(); // we need to check for recent alarms if we just booted

            } else if (shutdown_id == 1) {
                Log.i(TAG, " (Started From System Shutdown)");
                skipSetup = true; // we are trying to shut down, so we don't really need to setup.

                //power.killService(); // can;t kill the process or the intent will just be redelivered

                // We need to start the shutdown timer, during which period we ignore erratic certain signals from Inputs
                io.startShutdownWindow();

                if ((SHOULD_SELF_DESTROY_ON_SHUTDOWN) ||   // we want to destroy the service when we get this
                    (!isAlreadyRunning)) {   // we weren't already running, just destroy this service and ignore
                    // Try to shut down this service
                    stopSelf(); // stop the service (this will call shutdown when OnDestroy is called)
                    return START_NOT_STICKY;
                }

            } else if (restart_id == 1) {
                Log.i(TAG, " (Started From Process Killer)");
            } else if (external_id == 1) {
                // This can happen frequently, so don't spend time reseting alarms or foreground, etc unless needed
                skipSetup = true;
                if (!isAlreadyRunning) { // don't clutter logs up
                    Log.v(TAG, " (Started From Externa)");
                }
            } else {
                Log.i(TAG, " (Started From Other/Unknown Intent)");
                // If the service can be started by another app (such as kiosk) or user on boot,
                // then we need to check for recent alarms, because we may have booted which then caused another app to start us.

                // Since we are always registering to start on boot, we don't need to do this
                // checkRecentAlarms(); // we need to check for recent alarms

            }


        }  // intent was not null


        if (!isAlreadyRunning) {
            // first time starting after loading
            clearEventSequenceIdIfNeeded();
            addEvent(QueueItem.EVENT_TYPE_RESTART); // service restarted
            isAlreadyRunning = true; // remember for next time we are already running
            skipSetup = false; // if we weren't running we def need to do all setup
        }


        if (lastRunningElapsedTime == 0) { // initial condition after creation
            // and start a timer to make sure we haven't fallen asleep
            lastRunningElapsedTime = SystemClock.elapsedRealtime(); // we know we were running at this time
            exec = new ScheduledThreadPoolExecutor(1);
            exec.scheduleWithFixedDelay(new MonitorTask(), 500, 500, TimeUnit.MILLISECONDS); // every 1/2 second, check we are still awake
        }


        lastRunningElapsedTime = SystemClock.elapsedRealtime(); // we know we were running at this time


        // Always makes sure all alarm is set appropriately, unless this is just a ping and we were already running
        if (!skipSetup) {
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

        power.stop(); // tears down FOTA windows and locks
        io.stop();  // tears down I/O polling
        ota.stop(); // tears down UDP
        queue.close(); // closes DB

        if (save_crash_data) {
            Log.i(TAG, "Saving crash data");
            Crash crash = new Crash(context);
            crash.edit();
            power.saveCrashData(crash);
            io.saveCrashData(crash);
            position.saveCrashData(crash);
            crash.commit();
        }

        // start removing wake-locks, etc.
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
    // addEvent() : Add an event to the end of the queue
    //      creates the item from the given event_type_id,
    //  sets the trigger time and other data to now
    ////////////////////////////////////
    public void addEventWithExtra(int event_type_id, int extra) {

        try {
            int i;
            for (i = 0; i < QueueItem.DISABLED_EVENTS.length; i++) {
                if (QueueItem.DISABLED_EVENTS[i] == event_type_id) {
                    // This event is disabled and should be silently ignored
                    return;
                }
            }


            // create an object to hold this event item
            QueueItem item = new QueueItem();
            item.event_type_id = event_type_id;
            item.extra = extra;

            // get the sequence ID to use.
            int seq = state.readState(State.COUNTER_MESSAGE_SEQUENCE_INDEX);
            item.sequence_id = seq;
            state.writeState(State.COUNTER_MESSAGE_SEQUENCE_INDEX, seq + 1);

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
            Log.e(TAG, "Exception: addEventWithExtra() " + e.toString(), e);
        }

        //return item.getId();
    } // addEventWithExtra()


    public void addEvent(int event_type_id) {
        addEventWithExtra(event_type_id, 0);
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


        int runCounter = 0;
        boolean sent_iothread_jam_message = false;
        final int MASK_RUNCOUNTER_8_SECONDS = 0xF; // If the runCounter and this is 0, then 8 seconds have elapsed


        @Override
        public void run() {

            try {
                Log.vv(TAG, "MonitorTask()");

                // check to see if any of our tasks have not been running like they are supposed to
                runCounter++;

                if ((runCounter & MASK_RUNCOUNTER_8_SECONDS) == 0) { // 8 seconds have elapsed

                    if (io.isWatchdogError()) {
                        // Problem, we have not reset the watchdog recently!!!!
                        Log.e(TAG, "Watchdog Error. IO thread has Jammed.");
                        if (!sent_iothread_jam_message) {
                            // we haven't yet sent a message to the server saying that we jammed
                            addEventWithExtra(QueueItem.EVENT_TYPE_ERROR, QueueItem.ERROR_IO_THREAD_JAMMED);
                            sent_iothread_jam_message = true;
                        }
                        // Take corrective action
                        power.restartService();

                    }

                }


                // checks to see if we have skipped time (meaning we were sleeping and now awake)

                long thisElapsedTime = SystemClock.elapsedRealtime();


                if (thisElapsedTime - lastRunningElapsedTime > 2000) {
                    // more than 2 seconds has elapsed, maybe we were sleeping ?

                    Log.w(TAG, "Just woke up! Last Sleep Monitor Check was " + (thisElapsedTime - lastRunningElapsedTime / 1000) + " s ago");
                    clearEventSequenceIdIfNeeded();
                    addEvent(QueueItem.EVENT_TYPE_WAKEUP);

                }

                lastRunningElapsedTime = thisElapsedTime;

            } catch (Exception e) {
                Log.e(TAG + ".MonitorTask", "Exception during MonitorTask:run() " + e.toString(), e);
            }
        }
    }

} // Service
