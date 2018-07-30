/////////////////////////////////////////////////////////////
// IoService:
//  Handles communications with hardware regarding I/O and Device Info.
//  can be started in a separate process (since interactinos with hardware API are prone to jam or crash)
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import micronet.hardware.MicronetHardware;

/**
 * Created by dschmidt on 2/25/16.
 */
public class IoService extends Service {
    static public final String TAG = "ATS-IOS";


    static public final String SERVICE_ACTION_START = "com.micronet.dsc.ats.io.start";
    static public final String SERVICE_ACTION_STOP = "com.micronet.dsc.ats.io.stop";


    static public final String BROADCAST_IO_HARDWARE_INITDATA = "com.micronet.dsc.ats.io.initData";
    static public final String BROADCAST_IO_HARDWARE_INPUTDATA = "com.micronet.dsc.ats.io.inputData";


    public static final int INPUT_POLL_PERIOD_TENTHS = 4; // poll period in tenths of a second, runs the timer that polls the hardware API




    int processId = 0;



    private static int io_scheme = HardwareWrapper.IO_SCHEME_DEFAULT; // this is the current IO scheme





    ScheduledThreadPoolExecutor exec;









    public IoService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        android.util.Log.d(TAG, "IoService Created");
        processId = android.os.Process.myPid();

        exec = new ScheduledThreadPoolExecutor(1);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {



        String action = intent.getAction();

        if ((action != null) && (action.equals(SERVICE_ACTION_STOP))) {
            android.util.Log.d(TAG, "IoService Stopped");
            stopSelf(); // stop the service (this will call OnDestroy is called)

        } else {
            android.util.Log.d(TAG, "IoService Started");
            broadcastProcessId();
            start();
        }
        return START_NOT_STICKY; // no reason to re-start it as it is monitored elsewhere.

    } // OnStartCommand()

    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed

        // OnDestroy() is NOT guaranteed to be called, and won't be for instance
        //  if the app is updated
        //  if ths system needs RAM quickly
        //  if the user force-stops the application


        android.util.Log.v(TAG, "OnDestroy()");

        if (exec != null) {
            exec.shutdown();
        }

    } // OnDestroy()


    void start() {
        // start polling if we aren't already
        if (exec != null) {
            exec.execute(ioInitTask);

            int pollperiodms = INPUT_POLL_PERIOD_TENTHS * 100;
            exec.scheduleAtFixedRate(ioInputTask, pollperiodms, pollperiodms, TimeUnit.MILLISECONDS); // check levels
        }

    } // start()

    void stop() {

    } // stop()





    static IoServiceHardwareWrapper.HardwareInputResults getFakeHardwareInputResults() {
        IoServiceHardwareWrapper.HardwareInputResults hardwareInputResults = new IoServiceHardwareWrapper.HardwareInputResults();
        hardwareInputResults.savedTime = SystemClock.elapsedRealtime();
        hardwareInputResults.voltage = 12.0;
        hardwareInputResults.ignition_valid = true;
        hardwareInputResults.ignition_input = true;
        return hardwareInputResults;
    } //getFakeHardwareInputResults()















    //////////////////////////////////////////////////////////
    // IoInitTask()
    //   the recurring timer call that processes the I/O
    //////////////////////////////////////////////////////////
    IoInitTask ioInitTask = new IoInitTask();
    class IoInitTask implements Runnable {

        boolean inProgress = false;
        boolean hasCompleted = false;

        String deviceId;
        int deviceBootCapturedInputs;

        @Override
        public void run() {
            try {

                Log.vv(TAG, "IoInitTask()");
                if (inProgress) {
                    Log.e(TAG, "IoInitTask() already in progress. Aborting.");
                    return;
                }

                inProgress = true;
                IoServiceHardwareWrapper.HardwareInputResults hardwareInputResults;

                if (MainService.DEBUG_MICRONET_HARDWARE_LIBRARY_MISSING) {
                    Log.i(TAG, "*** Using Fake deviceID/scheme/voltages (MainService.DEBUG_MICRONET_HARDWARE_LIBRARY_MISSING flag is set)");
                    hardwareInputResults = getFakeHardwareInputResults();
                    deviceId = "123456789";
                    deviceBootCapturedInputs = 0;
                } else {
                    if (!hasCompleted) {
                        // if we haven't previously completed

                        io_scheme = HardwareWrapper.getHardwareIoScheme();

                        deviceId = HardwareWrapper.getHardwareDeviceId();
                        deviceBootCapturedInputs = HardwareWrapper.getHardwareBootState();
                    }
                    hardwareInputResults = HardwareWrapper.getHardwareVoltageOnly();
                }

                // Now broadcast this info
                broadcastInit(io_scheme, deviceId, deviceBootCapturedInputs, hardwareInputResults);

                Log.vv(TAG, "IoInitTask() END");
                inProgress = false;
                hasCompleted = true;
            } catch (Exception e) {
                Log.e(TAG, "IoInitTask Exception " + e.toString(), e);
            }

        } // run()

    }


    // We need to make sure that sombody knows our process Id so they can kill us even if Init fails
    // broadcastProcessId()
    public void broadcastProcessId() {
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_IO_HARDWARE_INITDATA);

        Context context = getApplicationContext();

        ibroadcast.setPackage(context.getPackageName());
        ibroadcast.putExtra("processId", processId);
        context.sendBroadcast(ibroadcast);
    }

    public void broadcastInit(int io_scheme,
        String deviceId,
        int deviceBootCapturedInputs,
        IoServiceHardwareWrapper.HardwareInputResults hir) {


        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot

        // send a local message:
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_IO_HARDWARE_INITDATA);

        Context context = getApplicationContext();

        ibroadcast.setPackage(context.getPackageName());

        ibroadcast.putExtra("processId", processId);
        ibroadcast.putExtra("containsInit", true); // this also will contain init info
        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot

        ibroadcast.putExtra("ioScheme", io_scheme);
        ibroadcast.putExtra("deviceId", deviceId);
        ibroadcast.putExtra("deviceBootCapturedInputs", deviceBootCapturedInputs);
        ibroadcast.putExtra("voltage", hir.voltage);
        ibroadcast.putExtra("savedTime", hir.savedTime); // ms since boot


        context.sendBroadcast(ibroadcast);
    } // broadcastInit()




















    //////////////////////////////////////////////////////////
    // IoInputTask()
    //   the recurring timer call that processes the I/O
    //////////////////////////////////////////////////////////
    IoInputTask ioInputTask = new IoInputTask();
    class IoInputTask implements Runnable {

        int count = 0;
        @Override
        public void run() {
            try {

                Log.vv(TAG, "IoInputTask()");

                if (!ioInitTask.hasCompleted) {
                    // we've never completed an init, dont try to do anything else
                    Log.e(TAG, "Init never completed");
                    return;
                }
                IoServiceHardwareWrapper.HardwareInputResults hardwareInputResults;
                if (MainService.DEBUG_MICRONET_HARDWARE_LIBRARY_MISSING) {
                    Log.i(TAG, "*** Using Fake I/O (MainService.DEBUG_MICRONET_HARDWARE_LIBRARY_MISSING flag is set =" + MainService.DEBUG_MICRONET_HARDWARE_LIBRARY_MISSING + ")");
                    hardwareInputResults = getFakeHardwareInputResults();
                } else {
                    hardwareInputResults = HardwareWrapper.getAllHardwareInputs(io_scheme);
                }

                broadcastInputs(hardwareInputResults);

            } catch (Exception e) {
                Log.e(TAG, "IoInputTask Exception " + e.toString(), e);
            }

        } // run()

    } // IoInputTask()




    public void broadcastInputs(IoServiceHardwareWrapper.HardwareInputResults hir) {

        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot

        // send a local message:
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_IO_HARDWARE_INPUTDATA);

        Context context = getApplicationContext();

        ibroadcast.setPackage(context.getPackageName());

        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot
        ibroadcast.putExtra("ignition_input", hir.ignition_input);
        ibroadcast.putExtra("ignition_valid", hir.ignition_valid);
        ibroadcast.putExtra("input1", hir.input1);
        ibroadcast.putExtra("input2", hir.input2);
        ibroadcast.putExtra("input3", hir.input3);
        ibroadcast.putExtra("input4", hir.input4);
        ibroadcast.putExtra("input5", hir.input5);
        ibroadcast.putExtra("input6", hir.input6);
        ibroadcast.putExtra("input7", hir.input7);
        ibroadcast.putExtra("voltage", hir.voltage);
        ibroadcast.putExtra("savedTime", hir.savedTime);


        context.sendBroadcast(ibroadcast);


    } // broadcastInputs()

/*
    public boolean isIoTaskRunning() {
        return HardwareWrapper.isInCall();
    }

    public long getLastIoTaskAttemptTime() {
        return HardwareWrapper.getElapsedStart();
    }
*/


} // class IoService
