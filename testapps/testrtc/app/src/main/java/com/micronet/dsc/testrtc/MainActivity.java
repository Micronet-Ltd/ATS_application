package com.micronet.dsc.testrtc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import micronet.hardware.*;

public class MainActivity extends ActionBarActivity {

    private final static String TAG = "TestRTC-Activity";


    public static final int SECONDS_UNTIL_SHUTDOWN = 60;
    public static final int SECONDS_UNTIL_WAKEUP = 120;

    //public static final int SECONDS_UNTIL_SHUTDOWN = 450;
    //public static final int SECONDS_UNTIL_WAKEUP = 900;


    public static final String ALARM_WAKEUP_NAME = "com.micronet.dsc.testrtc.wakeup";
    public static final int ALARM_WAKEUP_REQUEST_CODE = 19742; // some random number


    public static final String PREF_COUNT_FILENAME = "counts";

    ScheduledThreadPoolExecutor exec = null;

    Button resetButton, shutdownButton;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Log.v(TAG, "onCreate()");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        shutdownButton = (Button) findViewById(R.id.shutdownButton);
        shutdownButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "Shutdown button pressed");
                cancelPowerDown();
                cancelWakeup();

                finish();
            }
        });

        resetButton = (Button) findViewById(R.id.resetButton);
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "Reset button pressed");
                clearAllCounters();
            }
        });

        exec = new ScheduledThreadPoolExecutor(1);

        // start shutdown timer

        schedulePowerDown(SECONDS_UNTIL_SHUTDOWN);

        // and remember to wake us back up.
        scheduleWakeup(SECONDS_UNTIL_WAKEUP);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public void onStart() {
        super.onStart();
        Log.v(TAG, "onStart()");

        displayBootCounter();
    }


    @Override
    public void onStop() {
        super.onStop();
        Log.v(TAG, "onStop()");


    }


    @Override
    public void onResume() {
        super.onResume();
        Log.v(TAG, "onResume()");

    } // onResume()


    @Override
    public void onPause() {
        super.onPause();
        Log.v(TAG, "onPause()");

    } // onPause()

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "onDestroy()");


        //Log.d("Stopping Test.");

        // stop sensor events
//        stopSensor();

        // stop monitoring and polling


    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }






    public void clearAllCounters() {

        Log.d(TAG, "Clearing all Counters");

        SharedPreferences sharedPref;
        sharedPref = getSharedPreferences(PREF_COUNT_FILENAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.clear().commit();


        displayBootCounter();

    }


    public void displayBootCounter() {

        SharedPreferences sharedPref;
        sharedPref = getSharedPreferences(PREF_COUNT_FILENAME, Context.MODE_PRIVATE);
        final int boot_counter_value = sharedPref.getInt("num_boots", 0);

        Log.d(TAG, "Read Boot Counter = " + boot_counter_value);

        MainActivity.this.runOnUiThread(new Runnable() {
            public void run() {
                TextView textNumBoots = (TextView) findViewById(R.id.textNumBoots);
                textNumBoots.setText(Integer.toString(boot_counter_value));
            }
        });
    }

    public static void incrementBootCounter(Context context) {

        SharedPreferences sharedPref;

        sharedPref = context.getSharedPreferences(PREF_COUNT_FILENAME, Context.MODE_PRIVATE);
        int boot_counter_value = sharedPref.getInt("num_boots", 0);

        SharedPreferences.Editor editor = sharedPref.edit();
        boot_counter_value++;
        editor.putInt("num_boots", boot_counter_value);
        editor.commit();
    }


    public String displayTime(final int whichViewId, long time_s) {

        // Log the time in a local format for each
        int adjhb_tod = (int) (time_s % 86400);
        int adjhb_tod_hour = adjhb_tod / 3600;
        int adjhb_tod_minute = (adjhb_tod % 3600) / 60;
        int adjhb_tod_second = (adjhb_tod % 60);

        final String displayStr = String.format("%02d", adjhb_tod_hour) + ":" + String.format("%02d", adjhb_tod_minute) + ":" + String.format("%02d", adjhb_tod_second) + " UTC";

        MainActivity.this.runOnUiThread(new Runnable() {
            public void run() {
                TextView text = (TextView) findViewById(whichViewId);
                text.setText(displayStr);
            }
        });

        return displayStr;
    }


    ////////////////////////////////////////////////
    // ShutdownTask()
    //      shuts down the device after a certain time period
    ////////////////////////////////////////////////
    class ShutdownTask implements Runnable {



        private final static String TAG = "TestRTC-ShutdownTask";

        @Override
        public void run() {

            try {
                Log.v(TAG, "ShutdownTask()");

                powerDown();


            } catch (Exception e) {
                Log.e(TAG, "Exception during ShutdownTask:run() " + e.toString(), e);
            }
        }
    } // ShutdownTask



    /////////////////////////////////////////////////////////////////
    // cancelWakeup()
    //      cancels the alarm for the given type
    /////////////////////////////////////////////////////////////////
    void cancelWakeup() {

        Log.v(TAG, "cancelWakeup() ");

        Context context = getApplicationContext();


        String alarmName = ALARM_WAKEUP_NAME;
        int alarm_request_code = ALARM_WAKEUP_REQUEST_CODE;



        Log.i(TAG, " Next Wakeup Cancelled");

        AlarmManager alarmservice = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)


        Intent i = new Intent(context, AlarmReceiver.class); // it goes to this guy
        i.setAction(alarmName);

        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(context, alarm_request_code, i, PendingIntent.FLAG_CANCEL_CURRENT);

        alarmservice.cancel(pi);

    }

    /////////////////////////////////////////////////////////////////
    // scheduleWakeup()
    //      sets the wakeup alarm for a given number of seconds in the future
    /////////////////////////////////////////////////////////////////
    public void scheduleWakeup(int time_from_now_s) {

        Log.v(TAG, "scheduleWakeup() ");


        Context context = getApplicationContext();

        int alarm_request_code = ALARM_WAKEUP_REQUEST_CODE;
        String alarmName = ALARM_WAKEUP_NAME;


        long current_time_s = System.currentTimeMillis() / 1000; // convert to seconds
        long next_alarm_time_s= current_time_s + time_from_now_s;


        // Log the time in a local format for each
        int adjhb_tod = (int) (next_alarm_time_s % 86400);
        int adjhb_tod_hour = adjhb_tod / 3600;
        int adjhb_tod_minute = (adjhb_tod % 3600) / 60;
        int adjhb_tod_second = (adjhb_tod % 60);


        int delta_time = (int) (next_alarm_time_s - current_time_s);
        int delta_hour =  (delta_time / 3600);
        int delta_minute =  ((delta_time) % 3600) / 60;
        int delta_second = (delta_time % 60);


        Log.i(TAG, " Next Wakeup " + " @ " + adjhb_tod_hour + ":" + adjhb_tod_minute + ":" + adjhb_tod_second + " UTC, " +
                "(" + delta_hour +" hrs " + delta_minute + " mins " + delta_second + " secs from now)");


        displayTime(R.id.textPowerUp, adjhb_tod);

        ////////////////////////////////////////////////////////
        // tell android about this alarm

        AlarmManager alarmservice = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        // since target is 4.0.3, all alarms are exact
        // for kit-kat or higher, if exact timing is needed
        // alarmservice.setExact ( RTC_WAKEUP, long triggerAtMillis, PendingIntent operation)


        Intent i = new Intent(context, AlarmReceiver.class); // it goes to this guy
        i.setAction(alarmName);

        // Our alarm intent MUST be a broadcast, if we just start a service, then wake lock may not be
        //  acquired in time to actually start running code in the service.
        PendingIntent pi = PendingIntent.getBroadcast(context, alarm_request_code, i, PendingIntent.FLAG_CANCEL_CURRENT);

        alarmservice.set(micronet.hardware.MicronetHardware.RTC_POWER_UP, next_alarm_time_s * 1000, pi); // time is is ms

    } // setNextAlarm()


    /////////////////////////////////////////////////////
    // schedulePowerDown()
    //  schedule the next scheduled power down
    /////////////////////////////////////////////////////
    void schedulePowerDown(int time_from_now_s) {
        long current_time_s = System.currentTimeMillis() / 1000; // convert to seconds
        long next_shutdown_time_s = current_time_s + time_from_now_s; // 450 seconds (7.5 minutes) from now
        exec.schedule(new ShutdownTask(), (time_from_now_s * 1000), TimeUnit.MILLISECONDS); // in ms
        String displayStr = displayTime(R.id.textPowerDown, next_shutdown_time_s);

        Log.i(TAG, "Next PowerDown @ " + displayStr);

    }

    /////////////////////////////////////////////////////
    // cancelPowerDown()
    //  cancel the next scheduled power down
    /////////////////////////////////////////////////////
    void cancelPowerDown() {
        Log.d(TAG, "Cancelling Next PowerDown");
        exec.shutdownNow(); // cancel all waiting threads too
        exec = null;
    }

    /////////////////////////////////////////////////////
    // powerDown()
    //  power down the device now
    /////////////////////////////////////////////////////
    void powerDown() {
        Log.i(TAG, "Powering Down (expect 7s delay)");
        PowerManager pm=(PowerManager) getSystemService(Context.POWER_SERVICE);
        pm.reboot(micronet.hardware.MicronetHardware.SHUTDOWN_DEVICE);

    } // powerDown()

} // class