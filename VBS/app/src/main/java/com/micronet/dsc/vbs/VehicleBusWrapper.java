/////////////////////////////////////////////////////////////
// VehicleBusCommWrapper:
//  provides a class that can be extended by J1708 and CAN classes
//  allows setup of the singleton interfaces and sockets needed for access to hardware by CAN and J1708
/////////////////////////////////////////////////////////////

package com.micronet.dsc.vbs;

import android.os.Handler;
import android.os.Looper;

import com.micronet.canbus.CanbusFrameType;
import com.micronet.canbus.CanbusHardwareFilter;
import com.micronet.canbus.CanbusInterface;
import com.micronet.canbus.CanbusSocket;

import java.util.ArrayList;


/**
 * Created by dschmidt on 2/18/16.
 */
public class VehicleBusWrapper {
    public static final String TAG = "ATS-VBS-Wrap";


    static boolean isUnitTesting = true;


    // Singleton methods
    private static VehicleBusWrapper instance = null;
    private VehicleBusWrapper() {}
    public static VehicleBusWrapper getInstance() {
        if(instance == null) {
            instance = new VehicleBusWrapper();
        }
        return instance;
    }


    Handler callbackHandler = new Handler(Looper.getMainLooper());
    ArrayList<String> instanceNames = new ArrayList<String>();
    ArrayList<Runnable> callbackArrayReady = new ArrayList<Runnable>();
    ArrayList<Runnable> callbackArrayTerminated = new ArrayList<Runnable>();

    BusSetupRunnable busSetupRunnable = new BusSetupRunnable();



    CanbusInterface createInterface(boolean listen_only, int bitrate, CanbusHardwareFilter[] hardwareFilters) {

        CanbusInterface canInterface = null;

        try {
            canInterface = new CanbusInterface();
        } catch (Exception e) {
            Log.e(TAG, "Unable to create new CanbusInterface() " + e.toString());
            return null;
        }


        try {
            canInterface.setBitrate(bitrate);
        } catch (Exception e) {
            Log.e(TAG, "Unable to set bitrate for CanbusInterface() " + e.toString());
            return null;
        }

        try {
            canInterface.create();
        } catch (Exception e) {
            Log.e(TAG, "Unable to call create() for CanbusInterface() " + e.toString());
            return null;
        }


        Log.d(TAG, "Interface created @ " + bitrate + "kb " + (listen_only ? "READ-ONLY" : "READ-WRITE"));

        if (hardwareFilters != null) {
            try {
                canInterface.setFilters(hardwareFilters);
            } catch (Exception e) {
                Log.e(TAG, "Unable to set filters for CanbusInterface() " + e.toString());
                removeInterface(canInterface);
                return null;
            }
            String filter_str = "";
            for (CanbusHardwareFilter filter : hardwareFilters) {
                int[] ids = filter.getIds();
                filter_str += " (";
                for (int id : ids) {
                    filter_str += "x" + String.format("%X", id) + " ";
                }
                filter_str += "M:x" + String.format("%X", filter.getMask()) + ")";
            }

            Log.d(TAG, "Filters = " + filter_str);
        }

        return canInterface;
    } // createInterface()


    void removeInterface(CanbusInterface canInterface) {
        try {
            canInterface.remove();
        } catch (Exception e) {
            Log.e(TAG, "Unable to remove CanbusInterface() " + e.toString());
        }
    } // removeInterface()



    CanbusSocket createSocket(CanbusInterface canInterface) {

        CanbusSocket socket = null;

        // open a new socket.
        try {
            socket = canInterface.createSocket();
            if (socket == null) {
                Log.e(TAG, "Socket not created .. returned NULL");
                return null;
            }
            // set socket options here
        } catch (Exception e) {
            Log.e(TAG, "Exception creating Socket: "  + e.toString(), e);
            return null;
        }
        return socket;
    } // createSocket()


    boolean openSocket(CanbusSocket socket) {
        try {
            socket.open();
        } catch (Exception e) {
            Log.e(TAG, "Exception opening Socket: " +  e.toString(), e);
            return false;
        }
        return true;
    } // openSocket


    void closeSocket(CanbusSocket socket) {
        // close the socket
        try {
            if (socket != null)
                socket.close();
            socket = null;
        } catch (Exception e) {
            Log.e(TAG, "Exception closeSocket()" + e.toString(), e);
        }
    } // closeSocket();



    //////////////////////////////////////////////////////////////////
    // isSupported()
    //  does the hardware support J1708 ?
    //////////////////////////////////////////////////////////////////
    public static boolean isJ1708Supported() {

        Log.v(TAG, "Testing isJ1708Supported?");
        if (!isUnitTesting) {
            CanbusInterface canInterface = new CanbusInterface();
            return canInterface.isJ1708Supported();
        } else {
            return true;
        }

    } // isJ1708Supported?



    void callbackNowReady() {
        if (callbackHandler != null) {
            for (Runnable r : callbackArrayReady) {
                callbackHandler.post(r);
            }
        }
    } // callbackNowReady()

    void callbackNowTerminated() {
        if (callbackHandler != null) {
            for (Runnable r : callbackArrayTerminated) {
                callbackHandler.post(r);
            }
        }
    } // callbackNowTerminated()


    public boolean setCharacteristics(boolean listen_only, int bitrate, CanbusHardwareFilter[] hwFilters) {

        /*
        if (isUnitTesting) {
            // since we are unit testing and not on realy device, even creating the CanbusInterface will fail fatally,
            //  so we need to skip this in testing
            Log.e(TAG, "UnitTesting is on. Will not setup bus.");
            return false;
        }
*/
/*
        if (busSetupRunnable.isSetup()) {
            Log.e(TAG, "Bus already running. Stop bus first.");
            return false;
        }


        // if you allow resetting of characteristics, you will have to tear-down any existing bus
        if (busSetupRunnable.isSetup())
            busSetupRunnable.teardown();
*/

        // busSetupRunnable = new BusSetupRunnable();


        // will take effect on the next setup() call
        busSetupRunnable.setCharacteristics(listen_only, bitrate, hwFilters);

        return true;
    } // setCharacteristics()



    public boolean start(String name, Runnable readyCallback, Runnable terminatedCallback) {


        if (isUnitTesting) {
            // since we are unit testing and not on realy device, even creating the CanbusInterface will fail fatally,
            //  so we need to skip this in testing
            Log.e(TAG, "UnitTesting is on. Will not start bus.");
            return false;
        }

        // If we are already setup, then call ready right away
        if (busSetupRunnable == null) {
            Log.e(TAG, "busSetupRunnable is null!! Cannot start.");
            return false;
        }

        if (!instanceNames.isEmpty()) {
            if (instanceNames.contains(name)) {
                //Log.d(TAG, "" + name + " previously started. Start Ignored -- must stop first.");
                return false;
            }
        }


        Log.d(TAG, "Starting for " + name);
        // If we are ready, then just call back, otherwise start the thread.

        instanceNames.add(name);
        if (readyCallback != null) callbackArrayReady.add(readyCallback);
        if (terminatedCallback != null) callbackArrayTerminated.add(terminatedCallback);



        if (busSetupRunnable.isSetup()) {
            // If we are adding J1708 to CAN, we can re-use
            if (name.equals("J1708")) {
                // call this right away and return
                callbackHandler.post(readyCallback);
                return true;
            }
            // IF we are adding CAN to J1708, we must shutdown and restart
            busSetupRunnable.teardown();

        }

        //String names = "";
        //for (String iname : instanceNames) {
        //    names = names + iname + " ";
        //}
        //Log.v(TAG, "Names open = " + names);

        // since we haven't already, we should set-up now
        busSetupRunnable.setup();


        return true;
    } // start()


    public void stop(String name, Runnable readyCallback, Runnable terminatedCallback) {


        if (isUnitTesting) {
            // since we are unit testing and not on realy device, even creating the CanbusInterface will fail fatally,
            //  so we need to skip this in testing
            Log.e(TAG, "UnitTesting is on. Will not stop bus.");
            return;
        }


        if (!instanceNames.contains(name)) {
            //Log.d(TAG, "" + name + " never started. Stop ignored -- must start first");
            return;
        }

        Log.d(TAG, "Stopping for " + name);

        // remove from callback list
        instanceNames.remove(name);
        if (readyCallback != null) callbackArrayReady.remove(readyCallback);
        if (terminatedCallback != null) callbackArrayTerminated.remove(terminatedCallback);


        // we MUST teardown, even if we are not the last bus, because that is the only
        //  way we can get any waiting socket reads to error and complete.


        //if (instanceNames.isEmpty()) {
            // no reason to keep this open

        if (busSetupRunnable != null)
            busSetupRunnable.teardown();

        //}

        // If we still have buses remaining, we must re-setup and callback readys
        if (!instanceNames.isEmpty()) {
            Log.d(TAG, " Restarting for other buses");
            if (busSetupRunnable != null) {
                busSetupRunnable.setup(); // this will also call callback array
            }

        }

    } // stop()


    public CanbusSocket getSocket() {
        if (busSetupRunnable == null) return null; // never even created
        if (!busSetupRunnable.isSetup()) return null; // no valid socket
        return busSetupRunnable.setupSocket;
    } // getSocket()



    ////////////////////////////////////////////////////////
    // BusSetupRunnable :
    // this sets up or tears down the socket + interface
    //  It is separated into own class so it can be run on its own thread for testing.
    ////////////////////////////////////////////////////////
    public class BusSetupRunnable { // implements Runnable {


        volatile boolean cancelThread = false;
        volatile boolean isClosed = false;

        volatile boolean isSocketReady = false;

        CanbusInterface setupInterface;
        CanbusSocket setupSocket;

        boolean listen_only = true; // default listen_only
        int bitrate = 250000; // default bit rate
        CanbusHardwareFilter[] hardwareFilters = null;


        BusSetupRunnable() {
            setDefaultFilters();
        }


        void setDefaultFilters() {
            // create default filters to block all CAN packets that arent all 0s
            hardwareFilters = new CanbusHardwareFilter[2];

            int[] ids = new int[1];
            ids[0] = 0;
            hardwareFilters[0] = new CanbusHardwareFilter(ids, 0x3FFFFFF, CanbusFrameType.EXTENDED);
            hardwareFilters[1] = new CanbusHardwareFilter(ids, 0x7FF, CanbusFrameType.STANDARD);
        }


        public void setCharacteristics(boolean new_listen_only, int new_bitrate, CanbusHardwareFilter[] new_hardwareFilters) {
            // these take effect at next Setup()
            listen_only = new_listen_only;
            bitrate = new_bitrate;
            hardwareFilters = new_hardwareFilters;
        }


        public boolean isSetup() {
            return isSocketReady;
        }

        // setup() : External call to setup the bus
        public void setup() {
            doInternalSetup();


            /*
            // Do the setup in a separate thread:
            Thread setupThread = new Thread(busSetupRunnable);
            setupThread.start();
            */
        }

        // teardown () : External call to teardown the bus
        public void teardown() {

            doInternalTeardown();

            // do the teardown in a separate thread:
            // cancelThread = true;
        }


        ///////////////////////////////////////////
        // doInternalSetup()
        //  does all setup steps
        //  returns true if setup was successful, otherwise false
        ///////////////////////////////////////////
        boolean doInternalSetup() {
            setupInterface = createInterface(listen_only, bitrate, hardwareFilters);
            if (setupInterface == null) return false;


            //Log.v(TAG, "creating socket");
            setupSocket = createSocket(setupInterface);
            if (setupSocket == null) {
                removeInterface(setupInterface);
                isClosed = true;
                return false;
            }

            //Log.v(TAG, "opening socket");
            if (!openSocket(setupSocket)) {
                removeInterface(setupInterface);
                isClosed = true;
                return false;
            }

            isSocketReady = true;

            // Notify the main thread that our socket is ready
            callbackNowReady();



            return true;
        } // doInternalSetup()

        /////////////////////////////////////////////
        // doInternalTeardown()
        //  does all teardown steps
        /////////////////////////////////////////////
        void doInternalTeardown() {



            if (setupSocket != null)
                closeSocket(setupSocket);
            if (setupInterface != null)
                removeInterface(setupInterface);

            isSocketReady = false;

            // Notify the main thread that are socket is terminated
            callbackNowTerminated();

        } // doInternalTeardown()

        //////////////////////////////////////////////////////
        // run()
        //      in case we want setup to occur on separate thread, we can run this
        //////////////////////////////////////////////////////
        public void run() {

            Log.v(TAG, "Setup thread starting");


            isClosed = false;
            cancelThread = false;


            // open the socket
            if (!doInternalSetup()) return;

            Log.v(TAG, "Setup thread ready");

            while (!cancelThread) {
                android.os.SystemClock.sleep(5); // we can wait 5 ms until we want to cancel this
            }

            Log.v(TAG, "Setup thread terminating");

            doInternalTeardown();

            Log.v(TAG, "Setup thread terminated");
            isClosed = true;


        } // run
    } // BusSetupRunnable

} // VehicleBusCommWrapper
