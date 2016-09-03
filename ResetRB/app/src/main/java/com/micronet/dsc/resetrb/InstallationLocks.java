/////////////////////////////////////////////////////////////
// InstallationLocks
//  This class is used to determine whether or not installation should be locked
/////////////////////////////////////////////////////////////

package com.micronet.dsc.resetrb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.concurrent.locks.Lock;

/**
 * Created by dschmidt on 8/22/16.
 */
public class InstallationLocks {

    public static final String TAG = "ResetRB-InstallLocks";


    // Default values to use if no value is known
    public static final int DEFAULT_UNLOCK_START_TIMEOFDAY_SECONDS = 0; // 0 minutes after midnight
    public static final int DEFAULT_UNLOCK_END_TIMEOFDAY_SECONDS = 5*60*60; // 5 AM


    // Android Alarm that will trigger when we need to set locks
    public static final String RELOCK_ALARM_NAME = "com.micronet.dsc.resetRB.alarm.relockInstall"; // Name of the alarm to register with Android
    public static final int RELOCK_ALARM_ID = 23458972;   // random number that will be the ID of the Lock Alarm

    // Acutal name of lock that we will hold with the redbend client
    public static final String INSTALL_LOCK_NAME = "resetRBLock"; // Name of the Lock to hold with the Redbend Client



    // Shared Prefs
    public static final String MY_PREFS_NAME = "lockConfiguration";

    static final String PREFS_UNLOCK_START = "unlockStart_TodSecondsLocal";
    static final String PREFS_UNLOCK_END = "unlockEnd_TodSecondsLocal";

    static class LockConfiguration {

        int unlock_start_tod_s_local = DEFAULT_UNLOCK_START_TIMEOFDAY_SECONDS;
        int unlock_end_tod_s_local = DEFAULT_UNLOCK_END_TIMEOFDAY_SECONDS;
    }



    //////////////////////////////////////////////////////////////////
    // getLockConfiguration()
    //      retrieve the parameters that determine the installation locking scheme
    //////////////////////////////////////////////////////////////////
    static LockConfiguration getLockConfiguration(Context context) {

        SharedPreferences prefs = context.getSharedPreferences(MY_PREFS_NAME, context.MODE_PRIVATE);

        LockConfiguration lc = new LockConfiguration();


        lc.unlock_start_tod_s_local = prefs.getInt(PREFS_UNLOCK_START, DEFAULT_UNLOCK_START_TIMEOFDAY_SECONDS);
        lc.unlock_end_tod_s_local = prefs.getInt(PREFS_UNLOCK_END, DEFAULT_UNLOCK_END_TIMEOFDAY_SECONDS);


        if (!prefs.contains(PREFS_UNLOCK_START)) { // File does not exist (or at least does not contain this parameter)
            saveLockConfiguration(context, lc);
        }

        return lc;

    } // getLockConfiguration()


    //////////////////////////////////////////////////////////////////
    // saveLockConfiguration()
    //      save the parameters that determine installation locking scheme
    //////////////////////////////////////////////////////////////////
    static void saveLockConfiguration(Context context, LockConfiguration lockConfiguration) {

        Log.d(TAG, "Writing lock preferences to shared pref file: " + MY_PREFS_NAME);

        SharedPreferences.Editor editor = context.getSharedPreferences(MY_PREFS_NAME, context.MODE_PRIVATE).edit();

        editor.putInt(PREFS_UNLOCK_START, lockConfiguration.unlock_start_tod_s_local);
        editor.putInt(PREFS_UNLOCK_END,  lockConfiguration.unlock_end_tod_s_local);

        editor.apply();
    } // rememberVersion



    //////////////////////////////////////////////////////////////////
    // setUnlockTimePeriod()
    //     sets a start and end time for a daily installation unlock period
    // Returns:
    //      0 for success, otherwise an error
    //////////////////////////////////////////////////////////////////
    static int setUnlockTimePeriod(Context context, int start_tod_s_local, int end_tod_s_local) {


        if ((start_tod_s_local < 0) || (start_tod_s_local >= 86400)) {
            Log.e(TAG, "setUnlockTimePeriod() Invalid start time requested: " + start_tod_s_local);
            return -1;
        }

        if ((end_tod_s_local < 0) || (end_tod_s_local >= 86400)) {
            Log.e(TAG, "setUnlockTimePeriod() Invalid end time requested: " + end_tod_s_local);
            return -1;
        }

        // Otherwise set the new time

        Log.d(TAG, "Setting Installation Unlock period from TOD local " + start_tod_s_local + " to " + end_tod_s_local + " s");

        LockConfiguration lc;

        lc = getLockConfiguration(context); // get existing settings

        // change the time period settings
        lc.unlock_start_tod_s_local = start_tod_s_local;
        lc.unlock_end_tod_s_local = end_tod_s_local;

        // and save them
        saveLockConfiguration(context, lc);

        // Lastly, we may need to re-set our locks
        adjustLock(context);

        return 0;
    }


    //////////////////////////////////////////////////////////////////
    // getSecondsUntilUnlockPeriod()
    //  now : the current datetime in the Calendar
    //  start_tod_s : the starting time-of-day for the period in seconds after midnight
    //  start_tod_s : the ending time-of-day for the period in seconds after midnight
    // Returns:
    //  0 = we are within the unlock period
    //  > 0 : we have this many seconds until the period begins
    //////////////////////////////////////////////////////////////////
    static int getSecondsUntilStartPeriod(Calendar now, int start_tod_s_local, int end_tod_s_local) {



        Calendar cal  = now;
        int cal_hour = cal.get(Calendar.HOUR_OF_DAY);
        int cal_minute = cal.get(Calendar.MINUTE);
        int cal_second = cal.get(Calendar.SECOND);

        int now_tod_seconds = (cal_hour * 60*60) + (cal_minute*60) + cal_second;

        Log.d(TAG, "Now is " + (cal_hour) + ":" + cal_minute + ":" + cal_second + " = TOD local " + now_tod_seconds  + " s");

        if (end_tod_s_local > start_tod_s_local) {
            // Normal case (start of period is earlier in the day than the end of period)

            if ((now_tod_seconds >= start_tod_s_local) &&
                    (now_tod_seconds < end_tod_s_local)) {
                // We are inside the period.
                return 0;
            }

        } else {
            //Backward case (start of period is later in the day than start of period , e.g. 10 pm to 2 am)

            if ((now_tod_seconds >= start_tod_s_local) ||
                    (now_tod_seconds < end_tod_s_local)) {
                // We are inside the period.
                return 0;
            }

        }


        // We are outside the period, determine how much time there is until the start of the period

        if (start_tod_s_local > now_tod_seconds) {
            // we have not reached the start time yet
        } else {
            start_tod_s_local += 24*60*60; // make start time tomorrow
        }

        return start_tod_s_local -  now_tod_seconds; // how much seconds until the start time

    } // getSecondsUntilStartPeriod()

    /////////////////////////////////////////////////
    // determineNextDateTimeMillis()
    //  determines the next date time that the given local time of day occurs
    // Parameters:
    //  now : the current datetime in the Calendar
    //  tod_seconds_local: The time-of day desired (in seconds after midnight)
    // Returns:
    //  The time in Millis past epoch in UTC
    /////////////////////////////////////////////////
    static long determineNextDateTimeMillis(Calendar now, int tod_seconds_local) {


        Calendar now_cal  = now;
        Calendar next_cal  = (Calendar) now.clone();

        int cal_seconds = tod_seconds_local % 60;
        int cal_minutes = (tod_seconds_local / 60) % 60;
        int cal_hours = (tod_seconds_local / 3600);


        next_cal.set(Calendar.HOUR_OF_DAY, cal_hours);
        next_cal.set(Calendar.MINUTE, cal_minutes);
        next_cal.set(Calendar.SECOND, cal_seconds);


        if (next_cal.getTimeInMillis() <= now_cal.getTimeInMillis()) {
            // If it we are exactly the same time then return tomorrow's time.
            //  This happens frequently since we will set the locks based on an alarm at the end time
            next_cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        return next_cal.getTimeInMillis();


    } // determineNextDateTime()




    //////////////////////////////////////////////////////////////
    // setReLockAlarm()
    //  sets the alarm to remember to re-lock the installations
    // unlock_end_tod_s_local : the time-od-day seconds when unlocking ends (when locking starts)
    //////////////////////////////////////////////////////////////

    static void setReLockAlarm(Context context, int unlock_end_tod_s_local) {

        AlarmManager alarmservice = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)

        Intent i = new Intent(context, InstallationLocksReceiver.class); // it goes to this guy
        i.setAction(RELOCK_ALARM_NAME);


        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(context, RELOCK_ALARM_ID, i, PendingIntent.FLAG_CANCEL_CURRENT);



        // determine the next wakeup time
        long next_alarm_time_s = determineNextDateTimeMillis(Calendar.getInstance(), unlock_end_tod_s_local) / 1000L;

        long now_s = System.currentTimeMillis() / 1000L;


        long friendly_diff = next_alarm_time_s - now_s;

        int friendly_hours = (int) friendly_diff / 3600;
        friendly_diff -= friendly_hours*3600;
        int friendly_minutes = (int) friendly_diff / 60;
        friendly_diff -= friendly_minutes*60;
        int friendly_seconds = (int) friendly_diff;


        Log.d(TAG, "Setting Alarm to re-lock at " + next_alarm_time_s + " (" + friendly_hours + " h " + friendly_minutes + " m " + friendly_seconds + " s from now)");

        //alarmservice.set(AlarmManager.RTC_WAKEUP, next_heartbeat_time*1000, pi); // time is is ms
        if (Build.VERSION.SDK_INT >=Build.VERSION_CODES.KITKAT)
            alarmservice.setExact(AlarmManager.RTC, next_alarm_time_s * 1000, pi); // time is is ms
        else{
            alarmservice.set(AlarmManager.RTC, next_alarm_time_s * 1000, pi); // time is is ms
        }


    }


    ///////////////////////////////////////////////////////////////////
    // adjustLock()
    //  This is called:
    //      at boot
    //      when the time or timezone changes
    //      when we need to re-acquire a lock (each time the unlock period ends)
    ///////////////////////////////////////////////////////////////////
    public static void adjustLock(Context context) {


        LockConfiguration lockConfig = getLockConfiguration(context);


        Log.d(TAG, "Installation period is TOD local " + lockConfig.unlock_start_tod_s_local + " to " + lockConfig.unlock_end_tod_s_local + " s");

        int seconds_until_period = getSecondsUntilStartPeriod(Calendar.getInstance(), lockConfig.unlock_start_tod_s_local, lockConfig.unlock_end_tod_s_local);

        if (seconds_until_period == 0) {

            // We are inside the unlock period
            Log.d(TAG, "Inside Installation Period .. Releasing the Installation Lock");

            RBCIntenter.releaseInstallLock(context, INSTALL_LOCK_NAME);

        } else {

            // we are outside the unlock period .. acquire a lock until the period begins
            Log.d(TAG, "Outside Installation Period for " + seconds_until_period + " more seconds .. Acquiring the Installation Lock");

            RBCIntenter.acquireInstallLock(context, INSTALL_LOCK_NAME, seconds_until_period);

        }

        // Always set an alarm to re-lock installations again when the next unlock period ends.
        setReLockAlarm(context, lockConfig.unlock_end_tod_s_local);

    }



} // class
