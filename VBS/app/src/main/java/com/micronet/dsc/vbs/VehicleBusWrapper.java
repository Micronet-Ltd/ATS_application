/////////////////////////////////////////////////////////////
// VehicleBusCommWrapper:
//  provides a class that can be extended by J1708 and CAN classes
//  allows setup of the singleton interfaces and sockets needed for access to canlibrary by CAN and J1708
/////////////////////////////////////////////////////////////

package com.micronet.dsc.vbs;

import android.os.Handler;
import android.os.Looper;

import com.micronet.canbus.CanbusFrameType;
import com.micronet.canbus.CanbusHardwareFilter;
import com.micronet.canbus.CanbusInterface;
import com.micronet.canbus.CanbusSocket;

import java.util.ArrayList;
import java.util.Iterator;


/**
 * Created by dschmidt on 2/18/16.
 */
public class VehicleBusWrapper {
    public static final String TAG = "ATS-VBS-Wrap";


    static boolean isUnitTesting = true; // we don't actually open sockets when unit testing


    // Singleton methods: makes this class a singleton
    private static VehicleBusWrapper instance = null;
    private VehicleBusWrapper() {}
    public static VehicleBusWrapper getInstance() {
        if(instance == null) {
            instance = new VehicleBusWrapper();
        }
        return instance;
    }

    // basic handler for posting
    Handler callbackHandler = new Handler(Looper.getMainLooper());


    // We need a list of which bus types are currently actively used.
    //  We'll shut down the socket when nobody needs it.

    ArrayList<String> instanceNames = new ArrayList<String>();


    // A class to hold callbacks so we can let others know when their requested socket is ready or when it has gone away
    private class callbackStruct {
        String busName;
        Runnable callback;

        public callbackStruct(String name, Runnable cb) {
            busName = name;
            callback = cb;
        }
    }

    ArrayList<callbackStruct> callbackArrayReady = new ArrayList<callbackStruct>();
    ArrayList<callbackStruct> callbackArrayTerminated = new ArrayList<callbackStruct>();


    // Create a new class for thread where startup/shutdown work will be performed
    BusSetupRunnable busSetupRunnable = new BusSetupRunnable();



    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    //
    // Wrappers for the Canlib function calls
    //
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////


    CanbusInterface createInterface(boolean listen_only, int bitrate, CanbusHardwareFilter[] hardwareFilters) {

        CanbusInterface canInterface = null;

        try {
            canInterface = new CanbusInterface();
        } catch (Exception e) {
            Log.e(TAG, "Unable to create new CanbusInterface() " + e.toString());
            return null;
        }

	// We must set bitrate and listening mode both before and after creating the interface.
	// We would prefer to always set before, but that doesn't always work

        try {
            canInterface.setBitrate(bitrate);
        } catch (Exception e) {
            Log.e(TAG, "Unable to set bitrate for CanbusInterface() " + e.toString());
            return null;
        }



        // we must first set listening only mode before creating it as listen-only
        try {
            canInterface.setListeningMode(listen_only);
        } catch (Exception e) {
            Log.e(TAG, "Unable to set mode for CanbusInterface() " + e.toString());
            return null;
        }

        try {
            canInterface.create(listen_only);
        } catch (Exception e) {
            Log.e(TAG, "Unable to call create(" + listen_only + ") for CanbusInterface() " + e.toString());
            return null;
        }


        try {
            canInterface.setListeningMode(listen_only);
        } catch (Exception e) {
            Log.e(TAG, "Unable to set mode for CanbusInterface() " + e.toString());
            return null;
        }


        // Set the bitrate again since it doesn't work to set this before creating interface first time after power-up
        // We are in listen mode, so it shouldn't be a problem to open at wrong bitrate
        try {
            canInterface.setBitrate(bitrate);
        } catch (Exception e) {
            Log.e(TAG, "Unable to set bitrate for CanbusInterface() " + e.toString());
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


    boolean openSocket(CanbusSocket socket, boolean discardBuffer) {
        try {
            socket.open();
        } catch (Exception e) {
            Log.e(TAG, "Exception opening Socket: " +  e.toString(), e);
            return false;
        }

        // we have to discard when opening a socket at a new bitrate, but this causes a 3 second gap in frame reception

        if (discardBuffer) {
            try {
                socket.discardInBuffer();
            } catch (Exception e) {
                Log.e(TAG, "Exception discarding Socket buffer: " + e.toString(), e);
                return false;
            }
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





    ///////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////
    //
    // Functions to be called from outside this class
    //
    ///////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////



    //////////////////////////////////////////////////
    // setCharacteristics()
    //  set details for the CAN, call this before starting a CAN bus
    //////////////////////////////////////////////////
    public boolean setCharacteristics(boolean listen_only, int bitrate, CanbusHardwareFilter[] hwFilters) {

        // will take effect on the next bus stop/start cycle
        busSetupRunnable.setCharacteristics(listen_only, bitrate, hwFilters);
        return true;
    } // setCharacteristics()


    //////////////////////////////////////////////////
    // setCharacteristics()
    //  set details for the CAN, call this before starting a CAN bus
    //////////////////////////////////////////////////
    public boolean setNormalMode() {

        // will take effect on the next bus stop/start cycle
        busSetupRunnable.setNormalMode();
        return true;
    } // setCharacteristics()


    //////////////////////////////////////////////////
    // start()
    //   startup a bus
    //   name: either "J1708" or "CAN"
    //////////////////////////////////////////////////
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


        // add this bus to the list of running instances and add any callbacks
        instanceNames.add(name);
        addInstanceCallbacks(name, readyCallback, terminatedCallback);




        if (busSetupRunnable.isSetup()) {
            // If we are adding J1708 to CAN, we can re-use the existing socket
            if (name.equals("J1708")) {
                // call this right away and return
                Log.v(TAG, "piggybacking J1708 on existing CAN socket");
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


        // if we are starting J1708 and we haven't started CAN, we need to set CAN to listen-only

        if (name.equals("J1708")) { // we are starting J1708
            if (!instanceNames.contains("CAN")) { // CAN was not started
                busSetupRunnable.setDefaultCharacteristics(); // this puts us in listen mode and also filters out all rx CAN packets
            }
        }

        // since we haven't already, we should set-up now
        busSetupRunnable.setup();


        return true;
    } // start()


    //////////////////////////////////////////////////
    // stop()
    //  stop a bus
    //   name: either "J1708" or "CAN"
    //////////////////////////////////////////////////
    public void stop(String name) {


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

        // remove from list of active buses and remove all callbacks for the bus
        instanceNames.remove(name);
        removeInstanceCallbacks(name);


        // we MUST teardown, even if we are not the last bus, because that is the only
        //  way we can get any waiting socket reads to error and complete.

        if (busSetupRunnable != null)
            busSetupRunnable.teardown();

        // If we still have buses remaining, we must re-setup and call the ready callbacks again

        if (!instanceNames.isEmpty()) {
            Log.d(TAG, " Restarting for other buses");
            if (busSetupRunnable != null) {
                busSetupRunnable.setup(); // this will also call callback array
            }

        }

    } // stop()


    //////////////////////////////////////////////////
    // stopAll()
    //  stops ALL buses .. this should be used instead of stop() if we know that we will be stopping all buses
    //      b/c this will prevent re-formation of any buses that you are not explicitly stopping in the regular stop() call
    //////////////////////////////////////////////////
    public void stopAll() {
        Log.d(TAG, "Stopping All buses");


        // remove from list of active buses and remove all callbacks
        instanceNames.clear();
        clearInstanceCallbacks();

        // teardown the socket & interface
        if (busSetupRunnable != null)
            busSetupRunnable.teardown();
    }

    //////////////////////////////////////////////////
    // restart()
    //  restarts the buses
    //  used for changing the speed or mode of CAN without having to start/stop J1708 twice (once to remove CAN and once to re-add CAN)
    //      using this call, J1708 is only restarted once when CAN is changed.
    //   name: "CAN"
    //////////////////////////////////////////////////
    public boolean restart(String replaceCallbacksName,
                           Runnable newReadyCallback,
                           Runnable newTerminatedCallback) {

        if (isUnitTesting) {
            // since we are unit testing and not on real device, even creating the CanbusInterface will fail fatally,
            //  so we need to skip this in testing
            Log.e(TAG, "UnitTesting is on. Will not restart bus.");
            return false;
        }

        if (busSetupRunnable == null) {
            Log.e(TAG, "busSetupRunnable is null!! Cannot restart.");
            return false;
        }

        Log.d(TAG, "Restarting buses");

        // If we are ready, then just call back, otherwise start the thread.

        if (replaceCallbacksName != null) {
            removeInstanceCallbacks(replaceCallbacksName);
            addInstanceCallbacks(replaceCallbacksName, newReadyCallback, newTerminatedCallback);
        }


        // we must teardown and restart the interface

        if (busSetupRunnable != null)
            busSetupRunnable.teardown();

        if (busSetupRunnable != null) {
            busSetupRunnable.setup(); // this will also call callback array
        }


        return true;

    } // restart()



    ///////////////////////////////////////////////////
    // getSocket()
    //  return the socket that this wrapper created
    ///////////////////////////////////////////////////
    public CanbusSocket getSocket() {
        if (busSetupRunnable == null) return null; // never even created
        if (!busSetupRunnable.isSetup()) return null; // no valid socket
        return busSetupRunnable.setupSocket;
    } // getSocket()



    ///////////////////////////////////////////////////
    // getCANBitrate()
    //  return the bitrate for can that is being used (0 if no bitrate in use)
    ///////////////////////////////////////////////////
    public int getCANBitrate() {
        if (busSetupRunnable == null) return 0; // no bitrate -- class doesnt even exit
        if (!busSetupRunnable.isSetup()) return 0; // no bitrate -- socket wasn't even created yet

        return busSetupRunnable.bitrate;
    }




    ///////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////
    //
    // Actual Background work of setting up or tearing down a bus
    //      These are private: Do not call these from outside this class
    //
    ///////////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////



    //////////////////////////////////////////////////
    // addInstanceCallbacks()
    //      adds callbacks for a particular bus name (like when shutting down that bus)
    //////////////////////////////////////////////////
    void addInstanceCallbacks(String name, Runnable readyCB, Runnable terminatedCB) {

        removeInstanceCallbacks(name); // we need to remove the old ones for that bus, before adding the new ones

        if (readyCB != null) {
            callbackStruct readyStruct = new callbackStruct(name, readyCB);
            callbackArrayReady.add(readyStruct);
        }

        if (terminatedCB != null) {
            callbackStruct terminatedStruct = new callbackStruct(name, terminatedCB);
            callbackArrayTerminated.add(terminatedStruct);
        }
    }



    //////////////////////////////////////////////////
    // removeInstanceCallbacks()
    //      removes the callbacks for a particular bus name (like when shutting down that bus)
    //////////////////////////////////////////////////
    void removeInstanceCallbacks(String name) {
        // remove callbacks for this bus
        Iterator<callbackStruct> it = callbackArrayReady.iterator();
        while (it.hasNext()) {
            if (it.next().busName.equals(name)) {
                it.remove();
                // If you know it's unique, you could `break;` here
            }
        }

        it = callbackArrayTerminated.iterator();
        while (it.hasNext()) {
            if (it.next().busName.equals(name)) {
                it.remove();
                // If you know it's unique, you could `break;` here
            }
        }

    }


    //////////////////////////////////////////////////
    // clearInstanceCallbacks()
    //  removes ALL callbacks for ALL buses (like when shutting down ALL buses)
    //////////////////////////////////////////////////
    void clearInstanceCallbacks() {
        callbackArrayReady.clear();
        callbackArrayTerminated.clear();
    }

    //////////////////////////////////////////////////
    // callbackNowReady()
    //  calls the ready callbacks to let others know their socket is ready
    //////////////////////////////////////////////////
    void callbackNowReady() {
        if (callbackHandler != null) {
            for (callbackStruct cs : callbackArrayReady) {
                // make sure there is only one of these calls in the post queue at any given time
                callbackHandler.removeCallbacks(cs.callback);
                callbackHandler.post(cs.callback);
            }
        }
    } // callbackNowReady()

    //////////////////////////////////////////////////
    // callbackNowTerminated()
    //  calls the terminated callbacks to let others know their socket has gone away
    //////////////////////////////////////////////////
    void callbackNowTerminated() {
        if (callbackHandler != null) {
            for (callbackStruct cs : callbackArrayTerminated) {
                // make sure there is only one of these calls in the post queue at any given time
                callbackHandler.removeCallbacks(cs.callback);
                callbackHandler.post(cs.callback);
            }
        }
    } // callbackNowTerminated()




    ////////////////////////////////////////////////////////
    // BusSetupRunnable :
    // this sets up or tears down the socket + interface
    //  It is separated into own class so it can be run on its own thread for testing.
    ////////////////////////////////////////////////////////
    class BusSetupRunnable { // implements Runnable {


        volatile boolean cancelThread = false;
        volatile boolean isClosed = false;

        volatile boolean isSocketReady = false;

        CanbusInterface setupInterface;
        CanbusSocket setupSocket;

        boolean listen_only = true; // default listen_only
        int bitrate = 250000; // default bit rate
        CanbusHardwareFilter[] hardwareFilters = null;


        BusSetupRunnable() {
            setDefaultCharacteristics();
        }


        public void setNormalMode() {
            listen_only = false;
        }



        public void setCharacteristics(boolean new_listen_only, int new_bitrate, CanbusHardwareFilter[] new_hardwareFilters) {
            // these take effect at next Setup()
            listen_only = new_listen_only;
            bitrate = new_bitrate;
            hardwareFilters = new_hardwareFilters;
        }


        void setDefaultFilters() {
            // create default filters to block all CAN packets that arent all 0s
            hardwareFilters = new CanbusHardwareFilter[2];

            int[] ids = new int[1];
            ids[0] = 0;
            hardwareFilters[0] = new CanbusHardwareFilter(ids, 0x3FFFFFF, CanbusFrameType.EXTENDED);
            hardwareFilters[1] = new CanbusHardwareFilter(ids, 0x7FF, CanbusFrameType.STANDARD);
        }


        public void setDefaultCharacteristics() {
            // these take effect at next Setup()
            listen_only = true;
            bitrate = 250000;
            setDefaultFilters(); // block everything
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

	    // we want to discard buffer when opening listen-only sockets because this means we
        //      may be switching bitrates (unless we are only starting J1708, in which case only downside
        //      is it takes 3 seconds longer than it otherwise would to start getting packets).

            if (!openSocket(setupSocket, listen_only)) { 
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

            setupSocket = null;

            if (setupInterface != null)
                removeInterface(setupInterface);

            setupInterface = null;

            isSocketReady = false;

            // Notify the main threads that our socket is terminated
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
