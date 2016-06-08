package com.micronet.dsc.ats;

import android.app.Application;
import android.test.ApplicationTestCase;

import java.util.Arrays;


/**
 * <a href="http://d.android.com/tools/testing/testing_android.html">Testing Fundamentals</a>
 */
public class ApplicationTest extends ApplicationTestCase<Application> {
    public ApplicationTest() {
        super(Application.class);
    }


/*
    public void test_bytesToHex() {

        byte[] testBytes = new byte[] {0x00, (byte) 0xA5, 0x0F, 0x31, 0x7A, (byte) 0xFF, (byte) 0xC9, (byte) 0xE2} ;
        String testHex = new String("00A50F317AFFC9E2");


        byte[] resultBytes;
        String resultHex;



        resultHex = Log.bytesToHex(testBytes, 8);
        assertEquals(resultHex, testHex);



        resultBytes = Log.hexToBytes(testHex);
        assertEquals(Arrays.toString(resultBytes), Arrays.toString(testBytes) );


    } // test_bytesToHex()
*/



}