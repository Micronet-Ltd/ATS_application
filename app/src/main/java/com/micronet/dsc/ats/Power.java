/////////////////////////////////////////////////////////////
// Power:
//  handles both Power and Time functions
//      setting of the alarms that start the application (heartbeat, scheduled wakeup)
//      wakeup and shutdown management
//      restarting the app/service
/////////////////////////////////////////////////////////////


package com.micronet.dsc.ats;


import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
//import android.provider.Settings;
//import android.support.v4.content.WakefulBroadcastReceiver;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Power {


    public static final String TAG = "ATS-Power";

    public static final int ALARM_HEARTBEAT_REQUEST_CODE = 123456789; // unique private ID for the Heartbeat Alarm
    public static final int ALARM_SCHEDULED_WAKEUP_REQUEST_CODE = 123456788; // unique private ID for the Scheduled Wakeup Alarm
    public static final int ALARM_RESTART_REQUEST_CODE = 123456787; // unique private ID for the Scheduled Wakeup Alarm

    public static final String ALARM_HEARTBEAT_NAME = "com.micronet.dsc.ats.alarm";
    public static final String ALARM_SCHEDULED_NAME = "com.micronet.dsc.ats.scheduledwakeup";

    public static final String ALARM_RESTART_NAME = "com.micronet.dsc.ats.restartservice";
    public static final String BOOT_REQUEST_NAME = "com.micronet.dsc.ats.boot";
    public static final String SHUTDOWN_REQUEST_NAME = "com.micronet.dsc.ats.shutdown";


    private static final String WAKELOCK_HEARTBEAT_NAME = "ATS_HEARTBEAT";
    private static final String WAKELOCK_SCHEDULED_NAME = "ATS_SCHEDULEDWAKEUP";

    MainService service; // contains the context


    boolean heartbeatAlarmTimeWasInvalid; // remember that the last set alarm time is invalid
    boolean scheduledAlarmTimeWasInvalid; // remember that the last set alarm time is invalid

    // keep track of what wake locks we requested so we can manually control power-down time
    class HeldWakeLock {
        String wakeLockName;
        long untilElapsedTimeSec;  // if zero, then forever, otherwise hold this lock until the given system elapsed time
    }

    ArrayList<HeldWakeLock> heldWakeLocks = new ArrayList<HeldWakeLock>();


    PowerManager.WakeLock heartbeatWakeLock; // wakelock related to the Heartbeat.
                                             // (wakelocks related to I/O are in the Io class file)

    PowerManager.WakeLock scheduledWakeLock; // wakelock related to the Scheduled Wakeup
    // (wakelocks related to I/O are in the Io class file)


    PowerManager.WakeLock screenlock = null; // this is a lock to hold the screen on

    boolean powerdownWasSent = false; // this will be set to true once we send a power-down request so only one will be sent

    int initialStartUpTicks = 0; // number of seconds after starting during which Power Downs cannot be issued.

    public Power(MainService service) {
        this.service = service;
    }

    ScheduledThreadPoolExecutor exec;

    ////////////////////////////////////////////////////////////////////
    // start()
    //      Starts all monitoring (time changes, etc.), called when app is starting
    ////////////////////////////////////////////////////////////////////
    public void start() {

        if (service.SHOULD_KEEP_SCREEN_ON)
            acquireScreenLock();   // prevent the screen from sleeping

        registerTimeChanges(); // register to receive time-change notifications


        // get the number of seconds during which we should not power-down after startup
        initialStartUpTicks = service.config.readParameterInt(Config.SETTING_POWER, Config.PARAMETER_INITIAL_KEEPAWAKE);
        exec = new ScheduledThreadPoolExecutor(1);
        // Do not change this once second interval because it is used to determine initialStartupTicks
        exec.scheduleWithFixedDelay(new PowerDownTask(), 1000, 1000, TimeUnit.MILLISECONDS); // every 1 second, check if we should power down


        // Register a Listener to catch redbend updates
        IntentFilter filter = new IntentFilter();
        filter.addAction("SwmClient.NEW_UPDATE_AVAILABLE");
        filter.addAction("SwmClient.DMA UPDATE_FINISHED");
        filter.addAction("SwmClient.UPDATE_FINISHED_FAILED");

        mainHandler  = new Handler(); // initialize
        service.context.registerReceiver(redbendReceiver, filter);


    } // start()

    ////////////////////////////////////////////////////////////////////
    // stop()
    //      Stops all monitoring (time changes, etc.), called when app is ending
    ////////////////////////////////////////////////////////////////////
    public void stop() {
        service.context.unregisterReceiver(redbendReceiver);

        closeFOTAUpdateWindow(); // if it was opened

        if (exec != null)
            exec.shutdown();

        unregisterTimeChanges();
    }


    ////////////////////////////////////////////////////////////////////
    // destroy()
    //      Destroys wakelocks (this is done after recording crash data as a last step
    ////////////////////////////////////////////////////////////////////
    public void destroy() {
        // release all wakelocks

        if (service.SHOULD_KEEP_SCREEN_ON) {
            // if we turned on the screen, then we want to turn it off
            releaseScreenLock();
        }

        if (heartbeatWakeLock != null)
            heartbeatWakeLock = cancelWakeLock(WAKELOCK_HEARTBEAT_NAME, heartbeatWakeLock);
        if (scheduledWakeLock != null)
            scheduledWakeLock = cancelWakeLock(WAKELOCK_SCHEDULED_NAME, scheduledWakeLock);

    } // destroy()

        ////////////////////////////////////////////////////////////////////
    // saveCrashData()
    //      save data in preparation for an imminent crash+recovery
    ////////////////////////////////////////////////////////////////////
    public void saveCrashData(Crash crash) {

        long l;
        l = getWakeLockUntilElapsedTime(WAKELOCK_HEARTBEAT_NAME);
        if (l >=0)
            crash.writeStateLong(Crash.WAKELOCK_ELAPSED_HEARTBEAT, l);

        l = getWakeLockUntilElapsedTime(WAKELOCK_SCHEDULED_NAME);
        if (l >=0)
            crash.writeStateLong(Crash.WAKELOCK_ELAPSED_SCHEDULED_WAKEUP, l);

    } // saveCrashData()


    ////////////////////////////////////////////////////////////////////
    // restoreCrashData()
    //      restore data from a recent crash
    ////////////////////////////////////////////////////////////////////
    public void restoreCrashData(Crash crash) {
        // restore settings from just before a crash
        long l;
        int confirm;
        l = crash.readStateLong(Crash.WAKELOCK_ELAPSED_HEARTBEAT);
        confirm = service.config.readParameterInt(Config.SETTING_HEARTBEAT, Config.PARAMETER_HEARTBEAT_KEEPAWAKE);
        long now = SystemClock.elapsedRealtime() / 1000;
        if ((l > 0) && (l > now ) && (l < now + confirm))
            setHeartbeatWakeLock((int) (l - now));
        l = crash.readStateLong(Crash.WAKELOCK_ELAPSED_SCHEDULED_WAKEUP);
        confirm = service.config.readParameterInt(Config.SETTING_SCHEDULED_WAKEUP, Config.PARAMETER_SCHEDULED_WAKEUP_KEEPAWAKE);
        if ((l > 0) && (l > now ) && (l < now + confirm))
            setScheduledWakeLock((int) (l - now));
    } // restoreCrashData()

    ////////////////////////////////////////////////////////////////
    // getNextAlarmTime()
    //  figure out when the next alarm should be set for
    //  Returns: 0 if we do not expect an alarm
    //      otherwise, the expected unix time of the next alarm.
    ////////////////////////////////////////////////////////////////
    public long getNextAlarmTime(String alarmName) {
        Log.v(TAG, "Determining Next Alarm Time for " + alarmName);

        int alarm_tod = 0;

        if (alarmName.equals(ALARM_HEARTBEAT_NAME)) {
            alarm_tod = service.config.readParameterInt(Config.SETTING_HEARTBEAT, Config.PARAMETER_HEARTBEAT_TRIGGER_TOD);
        } else if (alarmName.equals(ALARM_SCHEDULED_NAME)) {
            alarm_tod = service.config.readParameterInt(Config.SETTING_SCHEDULED_WAKEUP, Config.PARAMETER_SCHEDULED_WAKEUP_TRIGGER_TOD);
        } else {
            Log.e(TAG, "Unknown alarm type");
        }



        if (alarm_tod == 0) return 0 ; // No Value = No alarm required
        if (alarm_tod >= 86400) return 0; // not a valid value in the day


        long current_time = System.currentTimeMillis() / 1000; // convert to seconds

        boolean alarmTimeWasInvalid;
        if (!isTimeValid(current_time * 1000L)) {
            Log.v(TAG, " Remembering current alarm time was Invalid");
            alarmTimeWasInvalid = true;
        } else {
            alarmTimeWasInvalid = false;
        }

        if (alarmName.equals(ALARM_HEARTBEAT_NAME)) {
            heartbeatAlarmTimeWasInvalid = alarmTimeWasInvalid;
        } else if (alarmName.equals(ALARM_SCHEDULED_NAME)) {
            scheduledAlarmTimeWasInvalid = alarmTimeWasInvalid;
        } else {
            Log.e(TAG, "Unknown alarm type");
        }


        long current_day = current_time / 86400;


        long next_alarm_time = (current_day * 86400) + alarm_tod; // in seconds


        if (next_alarm_time <= current_time) // obviously can't allow a time in the past or equal to right now
            next_alarm_time += 86400;

        //consider if we need to adjust the configuration setting for daylight saving
        TimeZone tz = TimeZone.getDefault();
        int raw_offset = tz.getRawOffset() / 1000; // the standard offset for this timezone in seconds
        int wdst_offset = tz.getOffset(next_alarm_time*1000) / 1000; // in seconds


        // if we move forward an hour for dst (eg wdst = -7, raw = -8), then we should trigger an hour earlier (= -1)
        int dst_adjustment = raw_offset - wdst_offset;

        if (dst_adjustment != 0) {
            Log.v(TAG, " Alarm DST Adjustment = " + dst_adjustment + ", TZ offset = " + raw_offset + ", current offset = " + wdst_offset);
        } else {
            Log.v(TAG, " Alarm No DST Adjustment needed, TZ offset = " + raw_offset +
                            " dst " + tz.useDaylightTime() + " " + tz.inDaylightTime(new Date(next_alarm_time*1000))
            );
        }


        next_alarm_time += dst_adjustment;
        // check if this is still in the future
        if (next_alarm_time <= current_time) {  // uh-oh, the dst adjustment has put us in the past or right now
            next_alarm_time -= dst_adjustment; // undo our adjustment
            next_alarm_time += 86400; // go forward another day
            wdst_offset = tz.getOffset(next_alarm_time*1000) / 1000 ; // figure out the offset of that new day in seconds
            dst_adjustment = raw_offset - wdst_offset; // and the adjustment we'll need
            Log.v(TAG, " New Alarm DST Adjustment = " + dst_adjustment + ", TZ offset = " + raw_offset + ", current offset = " + wdst_offset);
            next_alarm_time += dst_adjustment;  // and make that adjustment
        }

        Log.v(TAG, " Now = " + current_time +  " s, Next Alarm = " + next_alarm_time + " s");

        return next_alarm_time;
    } // getNextAlarmTime()


    ////////////////////////////////////////////////////////////////
    // wasCurrentAlarmRecent()
    //      If we think the last heartbeat was scheduled in last two minutes, then it was recent
    //      (This is called during boot to see if the re-boot may have been caused by a heartbeat.
    //       since we won't ever receive an alarm broadcast if we were shutdown, we only receive the boot)
    //  Returns: false if it wasn't recent,
    //           true if it was recent (and therefore this boot may have been caused by alarm)
    ////////////////////////////////////////////////////////////////
    public boolean wasCurrentAlarmRecent(String alarmName) {

        long next_alarm_time_s = 0;

        if (alarmName.equals(ALARM_HEARTBEAT_NAME)) {
            next_alarm_time_s = service.state.readStateLong(State.NEXT_HEARTBEAT_TIME_S);
        } else if (alarmName.equals(ALARM_SCHEDULED_NAME)) {
            next_alarm_time_s = service.state.readStateLong(State.NEXT_SCHEDULEDWAKEUP_TIME_S);
        } else {
            Log.e(TAG, "Unknown alarm type");
        }


        if (next_alarm_time_s ==0) return false; // could not have been caused by alarm b/c it was off

        long current_time_s = System.currentTimeMillis() / 1000; // convert to seconds

        if (next_alarm_time_s > current_time_s) return false; // could not have been triggered by alarm b/c it was in future.

        long delta_time_s = (current_time_s - next_alarm_time_s );

        // if the alarm would have triggered in last 2 minutes, then it is recent.
        if (delta_time_s < 120) return true;

        return false;
    } // wasCurrentAlarmRecent()


    ////////////////////////////////////////////////////////////////
    // isCurrentAlarmInvalid()
    //      If we think the last heartbeat was scheduled when we didn't know the correct time, then it is "Invalid"
    //      This is used to prevent automatically triggering a heartbeat when we learn the correct time.
    //  Returns: false if it wasn't invalid,
    //           true if it was invalid (and therefore we need to check the time when Alarm is triggered)
    ////////////////////////////////////////////////////////////////
    public boolean isCurrentAlarmInvalid(String alarmName) {
        boolean alarmTimeWasInvalid = false;

        if (alarmName.equals(ALARM_HEARTBEAT_NAME)) {
            alarmTimeWasInvalid = heartbeatAlarmTimeWasInvalid;
        } else if (alarmName.equals(ALARM_SCHEDULED_NAME)) {
            alarmTimeWasInvalid = scheduledAlarmTimeWasInvalid;
        } else {
            Log.e(TAG, "Unknown alarm type");
        }


        if (!alarmTimeWasInvalid) return false; // no chance of being invalid

        if (isTimeValid(0)) return true; // alarm was set when time was invalid, but now time is valid .. this makes the alarm itself invalid

        return false; // both then and now were both invalid .. that is ok, trigger the alarm
    } // isCurrentAlarmInvalid()


    /////////////////////////////////////////////////////////////////
    // clearNextAlarm()
    //      cancels the alarm for the given type
    /////////////////////////////////////////////////////////////////
    void clearNextAlarm(String alarmName) {

        Log.v(TAG, "clearNextAlarm() " + alarmName);

        int alarm_request_code, alarm_state_id ;
        if (alarmName.equals(ALARM_HEARTBEAT_NAME)) {
            alarm_request_code = ALARM_HEARTBEAT_REQUEST_CODE;
            alarm_state_id = State.NEXT_HEARTBEAT_TIME_S;
        } else if (alarmName.equals(ALARM_SCHEDULED_NAME)) {
            alarm_request_code = ALARM_SCHEDULED_WAKEUP_REQUEST_CODE;
            alarm_state_id = State.NEXT_SCHEDULEDWAKEUP_TIME_S;
        } else {
            Log.e(TAG, "Unknown alarm type " + alarmName);
            return;
        }

        Log.i(TAG, " Next Alarm " + alarmName + " Cleared");

                AlarmManager alarmservice = (AlarmManager) service.context.getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)


        Intent i = new Intent(service.context, AlarmReceiver.class); // it goes to this guy
        i.setAction(alarmName);

        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(service.context, alarm_request_code , i, PendingIntent.FLAG_CANCEL_CURRENT);

        alarmservice.cancel(pi);

        service.state.writeStateLong(alarm_state_id, 0);
    }

    /////////////////////////////////////////////////////////////////
    // setNextAlarm()
    //      sets the alarm for the given type
    /////////////////////////////////////////////////////////////////
    public void setNextAlarm(String alarmName) {

        Log.v(TAG, "setNextAlarm() " + alarmName);

        int alarm_request_code;
        int alarm_state_id;
        if (alarmName.equals(ALARM_HEARTBEAT_NAME)) {
            alarm_request_code = ALARM_HEARTBEAT_REQUEST_CODE;
            alarm_state_id = State.NEXT_HEARTBEAT_TIME_S;
        } else if (alarmName.equals(ALARM_SCHEDULED_NAME)) {
            alarm_request_code = ALARM_SCHEDULED_WAKEUP_REQUEST_CODE;
            alarm_state_id = State.NEXT_SCHEDULEDWAKEUP_TIME_S;
        } else {
            Log.e(TAG, "Unknown alarm type");
            return;
        }



        long current_time_s = System.currentTimeMillis() / 1000; // convert to seconds
        long next_alarm_time_s= getNextAlarmTime(alarmName);

        if (next_alarm_time_s == 0) {
            // Do not set an Alarm
            Log.v(TAG, " Alarm is Configured Off.");
            clearNextAlarm(alarmName);
            return;
        }

        // Log the time in a local format for each
        int adjhb_tod = (int) (next_alarm_time_s % 86400);
        int adjhb_tod_hour = adjhb_tod / 3600;
        int adjhb_tod_minute = (adjhb_tod % 3600) / 60;
        int adjhb_tod_second = (adjhb_tod % 60);


        int delta_time = (int) (next_alarm_time_s - current_time_s);
        int delta_hour =  (delta_time / 3600);
        int delta_minute =  ((delta_time) % 3600) / 60;
        int delta_second = (delta_time % 60);


        Log.i(TAG, " Next Alarm " + alarmName + " @ " + adjhb_tod_hour + ":" + adjhb_tod_minute + ":" + adjhb_tod_second + " UTC, " +
                "(" + delta_hour +" hrs " + delta_minute + " mins " + delta_second + " secs from now)");


        ////////////////////////////////////////////////////////
        // tell android about this alarm

        AlarmManager alarmservice = (AlarmManager) service.context.getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)


        Intent i = new Intent(service.context, AlarmReceiver.class); // it goes to this guy
        i.setAction(alarmName);

        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(service.context, alarm_request_code, i, PendingIntent.FLAG_CANCEL_CURRENT);

        //alarmservice.set(AlarmManager.RTC_WAKEUP, next_heartbeat_time*1000, pi); // time is is ms
        alarmservice.set(micronet.hardware.MicronetHardware.RTC_POWER_UP, next_alarm_time_s*1000, pi); // time is is ms

        // remember this so we can check at boot to see if boot was possible caused by heartbeat.
        service.state.writeStateLong(alarm_state_id, next_alarm_time_s);

    } // setNextAlarm()


    ////////////////////////////////////////////////////////////////////
    // setHeartbeatWakeLock()
    //      sets the wake lock specific to the Alarm/heartbeat after heartbeat occurs
    ////////////////////////////////////////////////////////////////////
    public void setHeartbeatWakeLock(int keepawake_sec) {
        heartbeatWakeLock = changeWakeLock(WAKELOCK_HEARTBEAT_NAME, heartbeatWakeLock, keepawake_sec);
    }


    ////////////////////////////////////////////////////////////////////
    // setScheduledWakeLock()
    //  sets the wake lock specific to the Alarm/scheduled-wakeup after wakeup occurs
    ////////////////////////////////////////////////////////////////////
    public void setScheduledWakeLock(int keepawake_sec) {
        scheduledWakeLock = changeWakeLock(WAKELOCK_SCHEDULED_NAME, scheduledWakeLock, keepawake_sec);
    }

    ////////////////////////////////////////////////////////////////////
    // cancelWakeLock()
    //  cancels a previously acquired wakelock
    ////////////////////////////////////////////////////////////////////
    public synchronized PowerManager.WakeLock cancelWakeLock(String name, PowerManager.WakeLock wl) {


        Log.v(TAG, "Cancelling " + name + " Wake Lock");

        int i;
        int found = -1;
        for (i =0; i < heldWakeLocks.size(); i++) {
            if (heldWakeLocks.get(i).wakeLockName.equals(name)) {
                Log.v(TAG, " !HELD " + heldWakeLocks.get(i).wakeLockName + " = " + heldWakeLocks.get(i).untilElapsedTimeSec);
                found = i;
            } else {
                Log.v(TAG, " HELD " + heldWakeLocks.get(i).wakeLockName + " = " + heldWakeLocks.get(i).untilElapsedTimeSec);
            }
        }


        if (found >= 0) {
            // found our guy, remove it now
            heldWakeLocks.remove(found);
        }

        if (wl == null) {
            Log.d(TAG, "Error Cancelling " + name + " Wake Lock (is null)");
            return null;
        }

        if (wl.isHeld() == false) {
            Log.d(TAG, "Error Cancelling " + name + " Wake Lock (is not held)");
            return null;
        }

        wl.release();
        return null;
    } // cancelWakeLock()

    /////////////////////////////////////////////////////////////////
    // changeWakeLock
    //  changes the timing on a previously acquired Wakelock
    // Parameters:
    //  num_seconds: if 0 then acquire for infinite time (until released)
    /////////////////////////////////////////////////////////////////
    public synchronized PowerManager.WakeLock changeWakeLock(String name, PowerManager.WakeLock oldwl, int num_seconds) {

        PowerManager pm = (PowerManager) service.context.getSystemService(Context.POWER_SERVICE);

        if (num_seconds == 0) {
            Log.d(TAG, "Change " + name + " Wake Lock to infinite seconds");
        } else {
            Log.d(TAG, "Change " + name + " Wake Lock to " + num_seconds + " seconds");
        }

        // first acquire a new wake lock, before releasing the old one
        // this way we don't accidentally go to sleep before we acquire the new one.
        PowerManager.WakeLock newwl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name);
        newwl.setReferenceCounted(false); // any release kills all acquires

        if (num_seconds == 0)
            newwl.acquire();
        else
            newwl.acquire(num_seconds * 1000); // convert to ms

        // now we can get rid of the old one, if it was passed
        if (oldwl != null) {
            if (oldwl.isHeld()) // needed to prevent errors in framework
                oldwl.release();
        }


        long untilElapsedTimeSec = num_seconds;
        if (untilElapsedTimeSec != 0) {
            untilElapsedTimeSec += (SystemClock.elapsedRealtime() / 1000); // from ms to sec
        }


        int i;
        for (i =0; i < heldWakeLocks.size(); i++) {
            if (heldWakeLocks.get(i).wakeLockName.equals(name)) break;
        }
        if (i < heldWakeLocks.size()) {
            // found our guy, just update the time
            heldWakeLocks.get(i).untilElapsedTimeSec = untilElapsedTimeSec ;
        } else {
            // create a new one
            HeldWakeLock heldWakeLock = new HeldWakeLock();
            heldWakeLock.wakeLockName = name;
            heldWakeLock.untilElapsedTimeSec = untilElapsedTimeSec ;
            heldWakeLocks.add(heldWakeLock);
        }

        for (i =0; i < heldWakeLocks.size(); i++) {
            Log.v(TAG, " HELD " + heldWakeLocks.get(i).wakeLockName + " = " + heldWakeLocks.get(i).untilElapsedTimeSec);
        }

//        setPowerDownDelay();

        return newwl;
    } // changeWakeLock()



    /////////////////////////////////////////////////////////////
    // getWakeLockUntilElapsedTime()
    //  gets the end of the wake-lock in system elapsed time for given lock name
    //      0 means an infinite lock
    //      -1 means no lock found
    /////////////////////////////////////////////////////////////
    long getWakeLockUntilElapsedTime(String name) {
        int i;
        HeldWakeLock held;
        long now = SystemClock.elapsedRealtime() / 1000; // in seconds

        for (i =0; i < heldWakeLocks.size(); i++) {
            held = heldWakeLocks.get(i);
            if ((held.untilElapsedTimeSec == 0) ||
                (held.untilElapsedTimeSec > now)) {
                if (held.wakeLockName.equals(name)) {
                    return held.untilElapsedTimeSec;
                }
            }
        }

        return -1;
    } // getWakeLockUntilElapsedTime()



        /////////////////////////////////////////////////////////////
    // isWakeLockHeld()
    //  checks the array list of held wake locks to determine if we can power down
    /////////////////////////////////////////////////////////////
    boolean isWakeLockHeld() {
        final int POWERDOWN_DELAY_TIMESECONDS = 5; // seconds to wait after last lock has expired

        long now = SystemClock.elapsedRealtime() / 1000; // in seconds

        int i;
        for (i =0; i < heldWakeLocks.size(); i++) {
            if (heldWakeLocks.get(i).untilElapsedTimeSec == 0) return true;
            if (now < (heldWakeLocks.get(i).untilElapsedTimeSec + POWERDOWN_DELAY_TIMESECONDS)) return true;
        }
        return false; // no wake locks held
    } // isWakeLockHeld()


/*
    /////////////////////////////////////////////////////////////
    // clearPowerDownDelay()
    //  removes any power down delay (sets it to a really long time)
    /////////////////////////////////////////////////////////////
    void clearPowerDownDelay() {
        //micronet.hardware.MicronetHardware hardware = micronet.hardware.MicronetHardware.getInstance();
        //hardware.SetDelayedPowerDownTime(Integer.MAX_VALUE);
        // Log.d(TAG, " setPowerDownDelay to = " + maxDelay + " seconds");

        Settings.System.putInt(service.context.getContentResolver(), micronet.hardware.MicronetHardware.IGNITION_OFF_POWER_DOWN_TIMEOUT, Integer.MAX_VALUE);

        Log.d(TAG, " setPowerDownTO to MAX = " + Integer.MAX_VALUE + " ms");
    }


    /////////////////////////////////////////////////////////////
    // setPowerDownDelay()
    //  checks the array list of held wake locks to determine the next power down
    //      sets the device to power down at the appropriate time
    //      if no wake locks are held, then it just powers down in minimum time (7 seconds)
    /////////////////////////////////////////////////////////////
    void setPowerDownDelay() {

        final int MIN_POWERDOWN_TIMESECONDS = 7; // minimum allowed by API

        int maxDelay;
        int maxDelayms;

        maxDelay = -1;
        maxDelayms = -1;
        int i;

        long now = SystemClock.elapsedRealtime() / 1000;

        for (i =0; i < heldWakeLocks.size(); i++) {
            if (heldWakeLocks.get(i).untilElapsedTimeSec == 0) {
                maxDelay = Integer.MAX_VALUE; // hold forever
                maxDelayms = Integer.MAX_VALUE; // hold forever
                break; // we know this is the maximum
            } else {
                if (heldWakeLocks.get(i).untilElapsedTimeSec > now) {
                    int delta = (int) (heldWakeLocks.get(i).untilElapsedTimeSec - now);

                    Log.d(TAG, " LOCK " + heldWakeLocks.get(i).wakeLockName + " delta = " + delta);

                    if (delta > maxDelay) maxDelay = delta;
                    if ((delta * 1000) > maxDelayms) maxDelayms = delta * 1000;
                }
            }
        }

        if (maxDelayms < MIN_POWERDOWN_TIMESECONDS*1000)
            maxDelayms = MIN_POWERDOWN_TIMESECONDS*1000;

        Log.d(TAG, " setPowerDownTO to = " + maxDelayms + " ms");

        Settings.System.putInt(service.context.getContentResolver(), micronet.hardware.MicronetHardware.IGNITION_OFF_POWER_DOWN_TIMEOUT, maxDelayms);
*/
/*
        // there is a minimum power down time that we can set.
        if (maxDelay < MIN_POWERDOWN_TIMESECONDS)
            maxDelay = MIN_POWERDOWN_TIMESECONDS;

        micronet.hardware.MicronetHardware hardware = micronet.hardware.MicronetHardware.getInstance();

        if (hardware.GetDelayedPowerDownTime() != maxDelay) {
            Log.d(TAG, " setPowerDownDelay to = " + maxDelay + " seconds");
            hardware.SetDelayedPowerDownTime(MIN_POWERDOWN_TIMESECONDS);
        } else {
            Log.d(TAG, " PowerDownDelay already = " + maxDelay + " seconds");
        }

        //PowerManager pm=(PowerManager) service.context.getSystemService(Context.POWER_SERVICE);
        //pm.reboot(micronet.hardware.MicronetHardware.SHUTDOWN_DEVICE);

        */
/*
    } // setPowerDownDelay()




    //////////////////////////////////////////////////////////////////
    // screenReceiver
    //      a broadcast receiver to receive screen state changes (screen-on vs off)
    //      used in power management (we won't power down the device when screen is on)
    //////////////////////////////////////////////////////////////////
    BroadcastReceiver screenReceiver = new BroadcastReceiver() {

        static final String rTAG = "Power:screenReceiver";
        //When Event is published, onReceive method is called
        @Override
        public void onReceive(Context context, Intent intent) {
            if(intent.getAction().equals(Intent.ACTION_SCREEN_ON)){
                Log.d(rTAG, "Screen ON");
      //          clearPowerDownDelay();
            }
            else if(intent.getAction().equals(Intent.ACTION_SCREEN_OFF)){
                Log.d(rTAG, "Screen OFF");
                setPowerDownDelay();
            }
        }
    };



    // register to receive screen state changes
    void registerScreenChanges() {
        IntentFilter screenStateFilter = new IntentFilter();
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        service.context.registerReceiver(screenReceiver, screenStateFilter);
    }

    // unregister to stop receiving screen state changes
    void unregisterScreenChanges() {
        service.context.unregisterReceiver(screenReceiver);
    }

*/



    /////////////////////////////////////////////////////////////////////////////////
    // setAirplaneMode()
    //  turns airplane mode off or on
    /////////////////////////////////////////////////////////////////////////////////
    void setAirplaneMode(boolean on) {

        // Note, this code will not work with or above android version 4.2

		Log.i(TAG, "Setting Airplane Mode = " + on);

        // Toggle airplane mode.
        Settings.System.putInt(
                service.context.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, on ? 1 : 0);

        // Post an intent to reload.
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", on);
        service.context.sendBroadcast(intent);

    } // setAirplaneMode






    /////////////////////////////////////////////////////////////////////////////////
    // isScreenOn()
    //  checks whether screen is on. 
	//	This is Deprecated: We used to only power-down when screen is off
    /////////////////////////////////////////////////////////////////////////////////
    boolean isScreenOn() {
        PowerManager pm = (PowerManager) service.context.getSystemService(Context.POWER_SERVICE);
        return pm.isScreenOn();
        // For API above 20:
        //return pm.isInteractive();
    }



    /////////////////////////////////////////////////////////////////////////////////
    // acquireScreenLock()
    //  prevents the screen from turning off
    /////////////////////////////////////////////////////////////////////////////////
    void acquireScreenLock() {

        PowerManager pm = (PowerManager) service.context.getSystemService(Context.POWER_SERVICE);

        // SCREEN locks are deprecated after version 17
        // cause the screen to turn on if it is not already on when we acquire the lock
        screenlock = pm.newWakeLock((PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.SCREEN_DIM_WAKE_LOCK), "ATS-ScreenLock");
        screenlock.acquire();

    } // acquireScreenLock()

    /////////////////////////////////////////////////////////////////////////////////
    // releaseScreenLock()
    //  allows the screen to turn off again
    /////////////////////////////////////////////////////////////////////////////////
    void releaseScreenLock() {
        if (screenlock != null) {
            screenlock.release();
        }
        screenlock = null;

    } // releaseScreenLock()



    /////////////////////////////////////////////////////////////////////////////////
    // powerDown()
    //  requests power down of the device
    /////////////////////////////////////////////////////////////////////////////////
    void powerDown() {

        if (powerdownWasSent) {
            // Do not ever re-send a power down request more than once
            Log.i(TAG, "Skipping Power Down Request (already sent)");
        } else {
            Log.i(TAG, "Sending Power Down Request (expect 7s delay)");

            // Let the IO module know we are requesting shut down so it can look for untrustworthy input values
            service.io.startShutdownWindow();

            // Send the power down request
            powerdownWasSent = true;
            PowerManager pm = (PowerManager) service.context.getSystemService(Context.POWER_SERVICE);
            pm.reboot(micronet.hardware.MicronetHardware.SHUTDOWN_DEVICE);
        }

    } // powerDown()

    /////////////////////////////////////////////////////////////////////////////////
    // reboot()
    //  requests a reboot of the device
    /////////////////////////////////////////////////////////////////////////////////
    void reboot() {
        if (powerdownWasSent) {
            // Do not ever re-send a power down request more than once
            Log.i(TAG, "Skipping Reboot Request (already sent)");
        } else {
            Log.i(TAG, "Sending Reboot Request (expect 7s delay)");

            // Let the IO module know we are requesting shut down so it can look for untrustworthy input values
            service.io.startShutdownWindow();

            // Send the reboot request
            powerdownWasSent = true;
            PowerManager pm = (PowerManager) service.context.getSystemService(Context.POWER_SERVICE);
            pm.reboot(null);
        }
    } // reboot()





    class PowerDownTask implements Runnable {

        @Override
        public void run() {

            try {
                // checks to see if we should power down
//                Log.v(TAG, "PowerDownTask()");

				// Do not power down during the initial service start-up window.
                if (initialStartUpTicks > 0) {
                    initialStartUpTicks--;
                    if (initialStartUpTicks == 0) {
                        Log.v(TAG, "Start-up Window has expired.");
                    }

                }

                if (initialStartUpTicks == 0) { // we are not in the startup window
                    if ((service.SHOULD_KEEP_SCREEN_ON) || // if we are always keeping screen on
                            (!isScreenOn())) { // or if screen is off
                        // then we can power down when there are no wakelocks
                        if (!isWakeLockHeld()) {
                            powerDown();
                        }
                    } // screen is appropriate
                } // not in startup window

            } catch (Exception e) {
                Log.e(TAG + ".PowerDownTask()", "Exception: " + e.toString(), e);
            }
        }
    }








    //////////////////////////////////////////////////////////////////
    // timeReceiver
    //      a broadcast receiver to changes to the time (user changed system time)
    //      this resets the heartbeat alarm so it won't trigger just because time was changed
    //      unfortunately the alarm triggers before this receiver
    //////////////////////////////////////////////////////////////////
    BroadcastReceiver timeReceiver = new BroadcastReceiver() {

        //When Event is published, onReceive method is called
        @Override
        public void onReceive(Context context, Intent intent) {

            try {
                final String action = intent.getAction();
                if (action.equals(Intent.ACTION_TIME_CHANGED) ||
                        action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {

                    // time has changed
                    Log.i(TAG, "System Time has changed, resetting all alarms");
                    setNextAlarm(ALARM_HEARTBEAT_NAME);
                    setNextAlarm(ALARM_SCHEDULED_NAME);

                }
            } catch (Exception e) {
                Log.e(TAG + ".timeReceiver()", "Exception: " + e.toString(), e);
            }
        }
    };


    //////////////////////////////////////////////////////////
    // registerTimeChanges()
    //  register to receive time changes (user changed the systme time)
    //////////////////////////////////////////////////////////
    void registerTimeChanges() {
        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        service.context.registerReceiver(timeReceiver, timeFilter);
    }

    //////////////////////////////////////////////////////////
    // unregisterTimeChanges()
    //  unregister to stop receiving time changes (user changed the systme time)
    //////////////////////////////////////////////////////////
    void unregisterTimeChanges() {
        service.context.unregisterReceiver(timeReceiver);
    }


    //////////////////////////////////////////////////////////
    // isTimeValid()
    //  returns true if this is valid time ( >= 2015) , otherwise false
    //      used for deciding if we should allow a triggered alarm and also for setting the time-fix type in protocol
    // Parameters
    //  time_ms: pass 0 to use the current system time
    //////////////////////////////////////////////////////////
    public static boolean isTimeValid(long time_ms) {

        // consider if the time is before 2015 then it is not valid

        if (time_ms == 0) // use the current time
             time_ms = System.currentTimeMillis();

        GregorianCalendar c = new GregorianCalendar();
        c.setTimeInMillis(time_ms);

        int year = c.get(Calendar.YEAR);

        if (year < 2015) return false;
        return true;

    } // isTimeValid()


    ///////////////////////////////////////////////////////////
    // kill(): Kill the service
    //  called during shutdown
    ///////////////////////////////////////////////////////////
    public void killService() {

        Log.i(TAG, "Killing Service");

        service.shutdownService(false); // do not save crash data

        Log.v(TAG, "Killing this Process");
        android.os.Process.killProcess(android.os.Process.myPid());

    } // killService




    ///////////////////////////////////////////////////////////
    // restart(): Restart the service
    //  restart can be useful to clear any problems in the app during troubleshooting
    ///////////////////////////////////////////////////////////
    public void restartService() {

        Log.i(TAG, "Restarting Service (expect 2s delay)");

        AlarmManager alarmservice = (AlarmManager) service.context.getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)


        Intent i = new Intent(service.context, AlarmReceiver.class); // it goes to this guy
        i.setAction(ALARM_RESTART_NAME);


        service.shutdownService(true); // save crash data too

        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(service.context, ALARM_RESTART_REQUEST_CODE, i, 0);
        alarmservice.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi); // wait 1 second
        //service.stopSelf();
        Log.v(TAG, "Killing this Process");
        android.os.Process.killProcess(android.os.Process.myPid());
    } // restartService


    ////////////////////////////////////////////////////////////////
    // checkAdjustTime: checks if we should set and adjust the wall clock
    //      will adjust if the old dates is obviously wrong and look like they have never been set
    ////////////////////////////////////////////////////////////////
    public void checkAdjustTime(long newTime_ms) {

        Calendar c_new = Calendar.getInstance();
        c_new.setTimeInMillis(newTime_ms);

        Calendar c_old = Calendar.getInstance();

        if ((c_new.get(Calendar.YEAR) >= 2015) &&
            (c_old.get(Calendar.YEAR) < 2015)
               ) {
            Log.i(TAG, "Setting the System Time from " + c_old.toString() + " to " + c_new.toString());

            AlarmManager am = (AlarmManager) service.context.getSystemService(Context.ALARM_SERVICE);
            am.setTime(newTime_ms);

            service.addEvent(QueueItem.EVENT_TYPE_CHANGE_SYSTEMTIME);
        }

    } // setWallClock()



    ///////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////
    // Stuff related to requesting FOTA updates
    ///////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////

    static final int FOTA_UPDATE_CHECK_MS = 5000; // check every 5 seconds during window
    Handler mainHandler = null;

    ////////////////////////////////////////////////////////////////
    // openFOTAUpdateWindow()
    //  opens the window during which we will check for FOTA updates if we have data coverage
    ////////////////////////////////////////////////////////////////
    public void openFOTAUpdateWindow(int seconds) {

        // We should wait up to 30 minutes for cell coverage
        // Send a redbend update request if coverage is attained in that time-period

        if (service.SHOULD_BROADCAST_REDBEND_ON_HEARTBEAT) {
            Log.i(TAG, "FOTA update window opened");

            if (mainHandler != null) {
                // Set the timeout for how long we are allowed to check for updates
                mainHandler.removeCallbacks(expireFOTAWindowTask);
                mainHandler.postDelayed(expireFOTAWindowTask, seconds * 1000); // convert to ms

                // start checking for updates
                mainHandler.removeCallbacks(requestFOTATask);
                mainHandler.postDelayed(requestFOTATask, FOTA_UPDATE_CHECK_MS);
            } // main Handler exists;
        }

    } // openFOTAUpdateWindow()


    ///////////////////////////////////////////////////////////////
    // closeFOTAUpdateWindow()
    //  closes the window
    ///////////////////////////////////////////////////////////////
    public void closeFOTAUpdateWindow() {
        if (service.SHOULD_BROADCAST_REDBEND_ON_HEARTBEAT) {
            if (mainHandler != null) {
                mainHandler.removeCallbacks(expireFOTAWindowTask);
                mainHandler.removeCallbacks(requestFOTATask);
            }
            Log.i(TAG, "FOTA update window closed");
        }
    } // closeFOTAUpdateWindow()

    ///////////////////////////////////////////////////////////////
    // requestFOTAUpdateNow()
    //  Sends the actual request for FOTA to redbend
    ///////////////////////////////////////////////////////////////
    private void requestFOTAUpdateNow() {
        Log.i(TAG, "Broadcasting SwmClient.CHECK_FOR_UPDATES_NOW for FOTA updates");

        Intent intent = new Intent();
        intent.setAction("SwmClient.CHECK_FOR_UPDATES_NOW");
        service.context.sendBroadcast(intent);
    } // requestFOTAUpdateNow()


    ///////////////////////////////////////////////////////////////
    // requestFOTATask()
    //  Timer that is active during the time period we are allowed to wait for coverage to request a FOTA
    //      once this expires, then we no longer check for FOTAs until next heartbeat
    ///////////////////////////////////////////////////////////////
    private Runnable requestFOTATask = new Runnable() {

        @Override
        public void run() {
            try {
                //Log.vv(TAG, "requestFOTATask()");

                NetworkInfo network = service.ota.isDataNetworkConnected();
                if (network == null) {
                    // no acceptable data network, just check again later
                    mainHandler.postDelayed(requestFOTATask, FOTA_UPDATE_CHECK_MS);
                } else {
                    requestFOTAUpdateNow();
                    closeFOTAUpdateWindow(); // we've served our purpose, close the window now.
                }
            } catch(Exception e) {
                Log.e(TAG + ".requestFOTATask", "Exception: " + e.toString(), e);

            }
        }
    };


    ///////////////////////////////////////////////////////////////
    // expireFOTAWindowTask()
    //  Timer that is active during the time period we are allowed to wait for coverage to request a FOTA
    //      once this expires, then we no longer check for FOTAs until next heartbeat
    ///////////////////////////////////////////////////////////////
    private Runnable expireFOTAWindowTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.d(TAG, "expireFOTAWindowTask()");

                // Stop checking for Fotas
                closeFOTAUpdateWindow(); // we've served our purpose, close the window now.

                //Log.vv(TAG, "expireFOTAWindowTask() END");
            } catch(Exception e) {
                Log.e(TAG + ".expireFOTAWindowTask", "Exception: " + e.toString(), e);

            }
        }
    };




    ///////////////////////////////////////////////////////////////
    // redbendReceiver: receives notifications from the redbend FOTA engine
    ///////////////////////////////////////////////////////////////
    private BroadcastReceiver redbendReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {

            try {
                // Log.v(TAG, "redbendReceiver()");
                String action = intent.getAction();


                // "SwmClient.NEW_UPDATE_AVAILABLE"
                // "SwmClient.DMA UPDATE_FINISHED""
                // "SwmClient.UPDATE_FINISHED_FAILED"

                // Just log that the broadcast was received

                Log.d(TAG, "Received Redbend Broadcast " + action);

                // Do Nothing

            } catch(Exception e) {
                Log.e(TAG + ".redbendReceiver", "Exception: " + e.toString(), e);
            }
        } // OnReceive()
    }; // redbendReceiver


} // class

