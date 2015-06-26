package com.micronet.dsc.ats;


import java.util.Arrays;

public class Log {


    // set which type of entries you want to record to the log

    public static boolean LOGLEVEL_VERBOSE_VERBOSE = false;
    public static boolean LOGLEVEL_VERBOSE = true;
    public static boolean LOGLEVEL_DEBUG = true;

    // Info, Warnings, and Errors are always recorded.



    public interface LogCallbackInterface {
        public void show(String tag, String text);
    }


    public static LogCallbackInterface callbackInterface;
    public static String callbackTags[] = {"*"};
    public static String callbackLevels[] = {"i", "w", "e"};


    public static void vv(final String TAG, final String TEXT) { // Extra verbose

        if (LOGLEVEL_VERBOSE_VERBOSE) {
            android.util.Log.v(TAG, TEXT);

            if ((callbackInterface != null) &&
                    (Arrays.asList(callbackLevels).contains("vv"))) {
                callbackInterface.show(TAG, TEXT);
            }
        }
    }


    public static void v(final String TAG, final String TEXT) {
        if (LOGLEVEL_VERBOSE) {

            android.util.Log.v(TAG, TEXT);

            if ((callbackInterface != null) &&
                    (Arrays.asList(callbackLevels).contains("v"))) {
                callbackInterface.show(TAG, TEXT);
            }
        }
    }


    public static void d(final String TAG, final String TEXT) {
        if (LOGLEVEL_DEBUG) {
            android.util.Log.d(TAG, TEXT);
            if ((callbackInterface != null) &&
                    (Arrays.asList(callbackLevels).contains("d"))) {
                callbackInterface.show(TAG, TEXT);
            }
        }
    }


    public static void i(final String TAG, final String TEXT) {
        android.util.Log.i(TAG, TEXT);
        if ((callbackInterface != null) &&
                (Arrays.asList(callbackLevels).contains("i"))) {
            callbackInterface.show(TAG, TEXT);
        }
    }

    public static void w(final String TAG, final String TEXT) {
        android.util.Log.w(TAG, TEXT);
        if ((callbackInterface != null) &&
                (Arrays.asList(callbackLevels).contains("w"))) {
            callbackInterface.show(TAG, TEXT);
        }
    }


    public static void e(final String TAG, final String TEXT) {
        android.util.Log.e(TAG, TEXT);
        if ((callbackInterface != null) &&
                (Arrays.asList(callbackLevels).contains("e"))) {
            callbackInterface.show(TAG, TEXT);
        }
    }

    public static void e(final String TAG, final String TEXT, final Exception e) {
        android.util.Log.e(TAG, TEXT, e);
        if ((callbackInterface != null) &&
                (Arrays.asList(callbackLevels).contains("e"))) {
            callbackInterface.show(TAG, TEXT);
        }
    }


} // class
