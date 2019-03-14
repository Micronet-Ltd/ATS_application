package com.micronet.dsc.resetrb;

import static org.junit.Assert.*;

import android.util.Log;
import java.text.SimpleDateFormat;
import org.junit.Test;

public class RBCClearerTest {

    @Test
    public void isTimeForPeriodicCleaning() {
        try {
            // Exactly 1 month difference (technically 30 days)
            long currentTime= new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse("2019/10/29 18:10:45").getTime();
            long lastCleaning = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse("2019/09/29 18:10:45").getTime();

            assertTrue(RBCClearer.isTimeForPeriodicCleaning(lastCleaning, currentTime));

            // Less than 1 month
            currentTime= new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse("2019/10/29 18:10:45").getTime();
            lastCleaning = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse("2019/10/05 18:10:45").getTime();

            assertFalse(RBCClearer.isTimeForPeriodicCleaning(lastCleaning, currentTime));

            // Greater than 1 month
            currentTime= new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse("2019/10/29 18:10:45").getTime();
            lastCleaning = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse("2019/05/05 18:10:45").getTime();

            assertTrue(RBCClearer.isTimeForPeriodicCleaning(lastCleaning, currentTime));
        } catch (Exception e) {
            Log.e("RBCClearerTest", e.toString());
            fail();
        }
    }
}