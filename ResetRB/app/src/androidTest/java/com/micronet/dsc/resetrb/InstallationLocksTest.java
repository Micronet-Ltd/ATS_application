package com.micronet.dsc.resetrb;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

import android.util.Log;
import java.util.Calendar;
import java.util.TimeZone;

public class InstallationLocksTest extends AndroidTestCase {

    //State st;

    public void setUp(){

//        RenamingDelegatingContext context
  //              = new RenamingDelegatingContext(getContext(), "test_");
  //      st = new State(context);
  //      st.clearAll();

    }

    public void tearDown() throws Exception{

        super.tearDown();
    }


    public void testGetSecondsUntilStartPeriod() {


        // setup

        Calendar cal  = Calendar.getInstance();

        cal.set(2016, 8, 30, 3, 30, 0); // 2016-08-30 03:30AM

        long res;


        // TESTS:

        // midnight until 5 am == We are inside the period
        res = InstallationLocks.getSecondsUntilStartPeriod(cal, 0, 5*60*60);
        assertEquals(0, res);


        // 5 am until 9 am == 90 minutes until period
        res = InstallationLocks.getSecondsUntilStartPeriod(cal, 5*60*60, 9*60*60);
        assertEquals(90*60, res);


        // 1 am until 2 am == 21 hours 30 minute until period
        res = InstallationLocks.getSecondsUntilStartPeriod(cal, 1*60*60, 2*60*60);
        assertEquals(21*60*60 + 30*60, res);


        // 5 am until 2 am == 90 minutes until period
        res = InstallationLocks.getSecondsUntilStartPeriod(cal, 5*60*60, 2*60*60);
        assertEquals(90*60, res);


        // 5 am until 4 am == We are inside the period
        res = InstallationLocks.getSecondsUntilStartPeriod(cal, 5*60*60, 4*60*60);
        assertEquals(0, res);

    } // testGetSecondsUntilStartPeriod()

    public void testDetermineNextDateTimeMillis() {


        // setup

        Calendar cal  = Calendar.getInstance();

        cal.set(2016, 7, 30, 3, 30, 0); // 2016-08-30 03:30AM  (MONTH is ZERO based in Calendar)
        cal.set(Calendar.MILLISECOND, 0);

        int gmt_offset_ms = cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET);

        assertEquals(1472527800000L - gmt_offset_ms, cal.getTimeInMillis());

        Calendar expected_value;


        long res;

        //TESTS:


        // when is the next 4 am ? Answer = Today

        Calendar now;
        now = (Calendar) cal.clone();

        res = InstallationLocks.determineNextDateTimeMillis(now, 4*60*60);

        expected_value = (Calendar) cal.clone();


        expected_value.set(Calendar.HOUR, 4);
        expected_value.set(Calendar.MINUTE, 0);
        expected_value.set(Calendar.SECOND, 0);

        assertEquals(expected_value.getTimeInMillis(), res);


        // when is the next 11:30 pm ? Answer = Today

        res = InstallationLocks.determineNextDateTimeMillis(now, 23*60*60+30*60);

        expected_value = (Calendar) cal.clone();
        expected_value.set(Calendar.HOUR, 23);
        expected_value.set(Calendar.MINUTE, 30);
        expected_value.set(Calendar.SECOND, 0);

        assertEquals(expected_value.getTimeInMillis(), res);


        // when is the next 1:30 am ? Answer = Tomorrow

        res = InstallationLocks.determineNextDateTimeMillis(now, 1*60*60+30*60);

        expected_value = (Calendar) cal.clone();
        expected_value.set(Calendar.DAY_OF_MONTH, 31);
        expected_value.set(Calendar.HOUR, 1);
        expected_value.set(Calendar.MINUTE, 30);
        expected_value.set(Calendar.SECOND, 0);

        assertEquals(expected_value.getTimeInMillis(), res);


        // when is the next 3:15 am ? Answer = Tomorrow

        res = InstallationLocks.determineNextDateTimeMillis(now, 3*60*60+15*60);

        expected_value = (Calendar) cal.clone();
        expected_value.set(Calendar.DAY_OF_MONTH, 31);
        expected_value.set(Calendar.HOUR, 3);
        expected_value.set(Calendar.MINUTE, 15);
        expected_value.set(Calendar.SECOND, 0);

        assertEquals(expected_value.getTimeInMillis(), res);




        // if it is exactly the same time, then we should return tomorrow's time
        // when is next 3:30 am ? Answer  = Tomorrow
        res = InstallationLocks.determineNextDateTimeMillis(now, 3*60*60+30*60);

        expected_value = (Calendar) cal.clone();
        expected_value.set(Calendar.DAY_OF_MONTH, 31);
        expected_value.set(Calendar.HOUR, 3);
        expected_value.set(Calendar.MINUTE, 30);
        expected_value.set(Calendar.SECOND, 0);

        assertEquals(expected_value.getTimeInMillis(), res);



    } // testReadDefault()

    public void testBootCompletedReceived(){
        try {
            Runtime.getRuntime().exec("am broadcast -a android.intent.action.BOOT_COMPLETED -n com.micronet.dsc.resetrb/.InstallationLocksReceiver").waitFor();
        } catch (Exception e) {
            fail("Error sending broadcast: " + e.toString());
        }
    }

} // class StateTest
