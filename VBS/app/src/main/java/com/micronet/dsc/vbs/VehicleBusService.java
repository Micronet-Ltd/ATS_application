/////////////////////////////////////////////////////////////
// VBusService:
//  Handles communications with hardware regarding CAN and J1708
//  can be started in a separate process (since interactions with the API are prone to jam or crash -- and take down the whole process when they do)
/////////////////////////////////////////////////////////////

package com.micronet.dsc.vbs;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;



import com.micronet.canbus.CanbusFrameType;
import com.micronet.canbus.CanbusHardwareFilter;

import java.util.Arrays;

public class VehicleBusService extends Service {

    public static final String TAG = "ATS-VBS";

    public static final int BROADCAST_STATUS_DELAY_MS = 1000; // every 1 s



    // These are the possible buses that are monitored here
    static final int VBUS_CAN = 1;
    static final int VBUS_J1708 = 2;






    int processId = 0;
    boolean hasStartedCAN = false;
    boolean hasStartedJ1708 = false;


    Handler mainHandler = null;

    VehicleBusJ1708 my_j1708;
    VehicleBusCAN my_can;


    boolean isUnitTesting = false;

    public VehicleBusService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }


    @Override
    public void onCreate() {
        android.util.Log.i(TAG, "Service Created: VBS version " + BuildConfig.VERSION_NAME);
        processId = android.os.Process.myPid();
        mainHandler  = new Handler();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String bus;
        String action;




        if (intent == null) {
            // load up our saved settings from file and use those
            if (!startFromFile()) return START_NOT_STICKY;
            return START_NOT_STICKY;
        } // intent was null .. load from file


        action = intent.getAction();

        if (action.equals(VehicleBusConstants.SERVICE_ACTION_RESTART)) {
            android.util.Log.i(TAG, "Vehicle Bus Service Restarting");
            if (!startFromFile()) return START_NOT_STICKY;
            return START_NOT_STICKY;

        }


        bus = intent.getStringExtra("bus");

        if (bus == null) {
            Log.e(TAG, "cannot start/stop service. must designate bus");
            return START_NOT_STICKY;
        }



        if (action.equals(VehicleBusConstants.SERVICE_ACTION_START)) {
            android.util.Log.i(TAG, "Vehicle Bus Service Starting: " + bus);


            // if bus is J1708, then filter out all CAN messages so we don't get any before starting.

            if (bus.equals("J1708")) {
                stopJ1708(false);
                startJ1708();
            }
            else
            if (bus.equals("CAN")) {
                int bitrate = intent.getIntExtra("bitrate", 250000);
                boolean listen_only = intent.getBooleanExtra("listenOnly", false);

                int[] ids = intent.getIntArrayExtra("hardwareFilterIds");
                int[] masks = intent.getIntArrayExtra("hardwareFilterMasks");


                // and do it
                stopCAN(false);
                startCAN(bitrate, listen_only, ids, masks);
            }


        } else
        if (action.equals(VehicleBusConstants.SERVICE_ACTION_STOP)) {
            android.util.Log.i(TAG, "Vehicle Bus Service Stopped: " + bus);

            // ignore J1708 requests for now, J1708 is stopped same time as CAN
            if (bus.equals("CAN")) {

                if (!isAnythingElseOn(VBUS_CAN)) {
                    stopSelf(); // nothing on, stop everything and exit
                } else {
                    stopCAN(true); // just stop the CAN
                }
            }
            else
            if (bus.equals("J1708")) {
                if (!isAnythingElseOn(VBUS_J1708)) {
                    stopSelf(); // nothing on, stop everything and exit
                } else {
                    stopJ1708(true); // just stop the J1708
                }
            }
        }

        return START_NOT_STICKY; // since this is being monitored elsewhere and since we can't start_sticky (null intent)
                                // rely on something else to restart us when down.
    } // OnStartCommand()

    @Override
    public void onDestroy() {
        // The service is no longer used and is being destroyed

        // OnDestroy() is NOT guaranteed to be called, and won't be for instance
        //  if the app is updated
        //  if ths system needs RAM quickly
        //  if the user force-stops the application

        android.util.Log.v(TAG, "Destroying Service");
        stopJ1708(false);
        stopCAN(false);

    } // OnDestroy()




    ////////////////////////////////////////////////////////////////
    // startFromFile()
    //  start up the buses based on saved state information
    //  return true if this was successful, false if there was a problem
    ////////////////////////////////////////////////////////////////
    boolean startFromFile() {
        android.util.Log.i(TAG, "Vehicle Bus Service Starting: (From Saved File)" );

        State state = new State(getApplicationContext());

        boolean enCan = state.readStateBool(State.FLAG_CAN_ON) ;
        boolean enJ1708 = state.readStateBool(State.FLAG_J1708_ON);

        if ((!enCan) && (!enJ1708)) {
            android.util.Log.i(TAG, "All buses are set to off. stopping service");
            stopSelf(); // nothing on, stop everything and exit
        }

        if (enCan) { // enable CAN bus
            int bitrate = state.readState(State.CAN_BITRATE);
            if (bitrate == 0) bitrate = 250000;
            boolean listen_only = state.readStateBool(State.FLAG_CAN_LISTENONLY);

            String idstring = state.readStateString(State.CAN_FILTER_IDS);
            String maskstring = state.readStateString(State.CAN_FILTER_MASKS);

            String[] idsplits = idstring.split(",");
            String[] masksplits = maskstring.split(",");

            int ids[] = new int[idsplits.length];
            int masks[] = new int[masksplits.length];

            for (int i=0; i < idsplits.length && i < masksplits.length; i++) {
                try {
                    ids[i] = Integer.parseInt(idsplits[i]);
                    masks[i] = Integer.parseInt(masksplits[i]);
                } catch (Exception e) {
                    Log.e(TAG, "CAN Masks or IDs are not a number!. Aborting start.");
                    return false; // we can't start, not sure what to do, we don't want to start without any filters
                }
            }

            stopCAN(false);
            startCAN(bitrate, listen_only, ids, masks);
        }

        if (enJ1708) { // enable J1708 bus
            stopJ1708(false);
            startJ1708();
        }

        return true;
    } // startFromFile()




    ////////////////////////////////////////////////////////////////
    // isAnythingElseOn()
    //  returns true if any other bus is on, other than the one specified
    ////////////////////////////////////////////////////////////////
    boolean isAnythingElseOn(int bus) {
        switch (bus) {
            case VBUS_CAN:
                return hasStartedJ1708;
            case VBUS_J1708:
                return hasStartedCAN;
            default:
                return hasStartedCAN || hasStartedJ1708;
        }
    } // isAnythingElseOn()

    ////////////////////////////////////////////////////////////////
    // startCAN()
    //  Start up the CAN bus with the given parameters
    ////////////////////////////////////////////////////////////////
    void startCAN(int bitrate, boolean listen_only, int[] ids, int masks[]) {


        if (hasStartedCAN) {
            Log.w(TAG, "CAN already started. Ignoring Start.");
        }

        hasStartedCAN = true; // don't start again
        Context context = getApplicationContext();

        // save this information so we can load it up on restart.
        State state;
        state = new State(context);

        state.writeState(State.FLAG_CAN_ON, 1);
        state.writeState(State.CAN_BITRATE, bitrate);
        state.writeState(State.FLAG_CAN_LISTENONLY, (listen_only ? 1 : 0));

        String idstring = "";
        String maskstring = "";
        if ((ids != null) && (masks != null)) {
            for (int id : ids) {
                if (!idstring.isEmpty()) {
                    idstring += ',';
                }
                idstring += id;
            }

            for (int mask : masks) {
                if (!maskstring.isEmpty()) {
                    maskstring += ',';
                }
                maskstring += mask;
            }
        }
        state.writeStateString(State.CAN_FILTER_IDS, idstring);
        state.writeStateString(State.CAN_FILTER_MASKS, maskstring);

/*
        // We CANNOT Start CAN after J1708. in this case must stop and restart J1708
        //  That way we re-create the interface and socket to use correct filters, bitrate, etc.
        boolean stoppedJ1708 = false;
        if (hasStartedJ1708) {
            stoppedJ1708 = true;
            stopJ1708(false);
        }
*/
        // create the combined filters
        CanbusHardwareFilter[] canHardwareFilters = createCombinedFilters(ids, masks);


        my_can = new VehicleBusCAN(context, isUnitTesting);
        my_can.start(bitrate, listen_only, canHardwareFilters);

/*
        // now we need to restart J1708 again if we previously stopped it
        if (stoppedJ1708) {
            startJ1708();
        }
*/

        if (!isAnythingElseOn(VBUS_CAN)) {
            // if we haven't started J1708, we need to start status broadcasts
            if (mainHandler != null)
                mainHandler.postDelayed(statusTask, BROADCAST_STATUS_DELAY_MS); // broadcast a status every 1s
        }
    } // startCAN()

    ////////////////////////////////////////////////////////////////
    // stopCAN()
    //  stop the CAN bus
    ////////////////////////////////////////////////////////////////
    void stopCAN(boolean show_error) {

        if (!hasStartedCAN) {
            if (show_error) {
                Log.w(TAG, "CAN not started. Ignoring Stop.");
            }
            return;
        }

        // remember this to file
        State state = new State(getApplicationContext());
        state.writeState(State.FLAG_CAN_ON, 0);


        // remove callbacks first in case the stop() jams
        if (!isAnythingElseOn(VBUS_CAN)) {
            mainHandler.removeCallbacks(statusTask);
        }

        if (my_can != null) {
            my_can.stop();
        }

        hasStartedCAN = false;

    } // stopCAN()



    ////////////////////////////////////////////////////////////////
    // startJ1708()
    //  start the J1708 bus
    ////////////////////////////////////////////////////////////////
    void startJ1708() {


        if (hasStartedJ1708) {
            Log.w(TAG, "J1708 already started. Ignoring Start.");
        }

        if (!VehicleBusJ1708.isSupported()) {
            Log.e(TAG, "J1708 not supported");
            return;
        }

        hasStartedJ1708 = true; // don't start again
        Context context = getApplicationContext();

        // remember this to file
        State state = new State(context);
        state.writeState(State.FLAG_J1708_ON, 1);

        my_j1708 = new VehicleBusJ1708(context, isUnitTesting);
        my_j1708.start();

        if (!isAnythingElseOn(VBUS_J1708)) {
            // if we haven't already started CAN, we need to start status broadcasts
            if (mainHandler != null) {
                mainHandler.postDelayed(statusTask, BROADCAST_STATUS_DELAY_MS); // broadcast a status every 1s
            }
        }
    } // startJ1708()

    ////////////////////////////////////////////////////////////////
    // stopJ1708()
    //  stop the J1708 bus
    ////////////////////////////////////////////////////////////////
    void stopJ1708(boolean show_error) {

        if (!hasStartedJ1708) {
            if (show_error) {
                Log.w(TAG, "J1708 not started. Ignoring Stop.");
            }
            return;
        }

        // remember this to file
        State state = new State(getApplicationContext());
        state.writeState(State.FLAG_J1708_ON, 0);


        // remove callbacks first in case stop() jams
        if (!isAnythingElseOn(VBUS_J1708)) {
            mainHandler.removeCallbacks(statusTask);
        }

        if (my_j1708 != null) {
            my_j1708.stop();
        }

        hasStartedJ1708 = false;

    } // stopJ1708()









    ///////////////////////////////////////////////////////////////
    // createCombinedFilters()
    //  take the ids and masks passed to this and combine into CanBusHardwareFilter
    ///////////////////////////////////////////////////////////////
    CanbusHardwareFilter[] createCombinedFilters(int[] ids, int[] masks) {


        if ((ids == null) || (masks == null) ||
                (ids.length == 0) || (masks.length == 0)) {
            Log.e(TAG, "Error -- no can filters specified");
            return null;
        }

        int count = ids.length;
        CanbusHardwareFilter[] canHardwareFilters = new CanbusHardwareFilter[count];

        for (int i = 0; i < masks.length && i < ids.length; i++) {
            canHardwareFilters[i] =
                 new CanbusHardwareFilter(new int[] {ids[i]},masks[i], CanbusFrameType.EXTENDED);
        }

        return canHardwareFilters;

        /*

        // count the number we need
        int count = 0;
        int lastmask = -1;

        for (int mask : masks) {
            if (mask != lastmask) count++;
        }


        CanbusHardwareFilter[] canHardwareFilters = new CanbusHardwareFilter[count];

        int filter_i = 0;
        int start_mask_i = 0;
        CanbusHardwareFilter chf;
        int i;

        for (i = 0; i < masks.length && i < ids.length; i++) {

            if ((masks[i] != lastmask) && (i > 0)) {
                // new mask
                chf = new CanbusHardwareFilter(Arrays.copyOfRange(ids, start_mask_i, i-1),lastmask, CanbusFrameType.EXTENDED);
                start_mask_i = i; // next filter will start here.

                if (filter_i < count) { // safety
                    canHardwareFilters[filter_i] = chf;
                    filter_i++;
                }
            }

        } // for each one


        if (i > 0) { // last one
            chf = new CanbusHardwareFilter(Arrays.copyOfRange(ids, start_mask_i, i - 1), lastmask, CanbusFrameType.EXTENDED);
            if (filter_i < count) { // safety
                canHardwareFilters[filter_i] = chf;
            }
        }

        return canHardwareFilters;
        */
    } // createCombinedFilters()



    void broadcastStatus() {

        Context context = getApplicationContext();

        Intent ibroadcast = new Intent();
        ibroadcast.setPackage(VehicleBusConstants.PACKAGE_NAME_ATS);
        ibroadcast.setAction(VehicleBusConstants.BROADCAST_STATUS);


        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot
        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot
        //ibroadcast.putExtra("password", VehicleBusService.BROADCAST_PASSWORD);

        //ibroadcast.putExtra("processId", processId); // so this can be killed?

        if (my_can != null) { // safety
            ibroadcast.putExtra("canrx", my_can.isReadReady());
            ibroadcast.putExtra("cantx", my_can.isWriteReady());
        }

        if (my_j1708 != null) { // safety
            ibroadcast.putExtra("j1708rx", my_j1708.isReadReady());
            ibroadcast.putExtra("j1708tx", my_j1708.isWriteReady());
        }

        context.sendBroadcast(ibroadcast);

    } // broadcastStatus()

    ///////////////////////////////////////////////////////////////
    // statusTask()
    //  Timer to broadcast that we are still alive
    ///////////////////////////////////////////////////////////////
    private Runnable statusTask = new Runnable() {

        int count = 0;
        @Override
        public void run() {
            try {
/*
                count++;
                if (count > 20) {
                    Log.d(TAG, "FAKE JAM VBUS");
                    Thread.sleep(20000);
                }
*/

                broadcastStatus();
                if (mainHandler != null)
                    mainHandler.postDelayed(statusTask, BROADCAST_STATUS_DELAY_MS); // broadcast a status every 1s
            } catch (Exception e) {
                Log.e(TAG + ".statusTask", "Exception: " + e.toString(), e);
            }
        }
    }; // statusTask()


} // class VehicleBusService
