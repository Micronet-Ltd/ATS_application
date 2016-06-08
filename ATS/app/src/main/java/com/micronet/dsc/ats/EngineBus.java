/////////////////////////////////////////////////////////////
// EngineBus:
//  Contains generic functions that all engine buses can implement
//      This is meant to be extended by a particular engine bus (eg J1939, J1587, etc)
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;


import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

public class EngineBus {



    // These variables should all be set by the sub-class
    public String TAG = "ATS-EngineBus";;
    public int DTC_COLLECTION_TIME_MS = 20000;
    Engine engine;
    Handler mainHandler = null;
    int myBusType = Engine.BUS_TYPE_NONE;





    // Bus Status

    static final int BUS_STATUS_IDLE = 0;               // OFF
    static final int BUS_STATUS_DISCOVER = 1;        // Looking for the presence of a bus
    static final int BUS_STATUS_CLAIMING_ADDRESS = 2;   // Claiming an Address on the bus
    static final int BUS_STATUS_UP = 3;                 // Communicating normally
    static final int BUS_STATUS_UP_NOCOMM = 4;          // we think we are up, but we have no rx communication in last second or so

    static final int BUS_STATUS_FAILED = 10;            // we weren't able to get an address, or something else


    int bus_status = BUS_STATUS_IDLE;
    boolean hasRecentRx = false; // has this bus communicated in last time period (1 second or so)

    static final int CHECK_BUS_ACTIVE_WAIT_MS = 1000; // check every second that we have received something on the bus



    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // Bus Activity Status
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////


    ///////////////////////////////////////////////////////////////////
    // isCommunicating()
    //  is this bus "on" and communicating ?
    ///////////////////////////////////////////////////////////////////
    public boolean isCommunicating() {

        if (bus_status == BUS_STATUS_UP) return true;

        return false;

    } // isCommunicating()


    ///////////////////////////////////////////////////////////////////
    // setBusStatus()
    //  set a status for the bus and report to engine if the bus is "communicating"
    ///////////////////////////////////////////////////////////////////
    void setBusStatus(int new_status) {
        bus_status = new_status;

        // Log.v(TAG, "setting bus status " + myBusType + " to " + bus_status);
        if (myBusType != Engine.BUS_TYPE_NONE) {
            if (bus_status == BUS_STATUS_UP)
                engine.setBusCommunicating(myBusType);
            else
                engine.clearBusCommunicating(myBusType);
        }

    } // setBusStatus

    ///////////////////////////////////////////////////////
    // startCheckingActivity()
    //  called to start checking for the absence of detectable activity on the bus
    ///////////////////////////////////////////////////////
    void startCheckingActivity() {
        if (mainHandler != null) {
            mainHandler.postDelayed(checkBusActiveTask, CHECK_BUS_ACTIVE_WAIT_MS);
        }
    }

    void stopCheckingActivity() {

        if (mainHandler != null) {
            mainHandler.removeCallbacks(checkBusActiveTask);
        }
    }

    ///////////////////////////////////////////////////////////////
    // checkBusActiveTask()
    //  periodically checks that we have received something on the bus and sets status if nothing received
    ///////////////////////////////////////////////////////////////
    private Runnable checkBusActiveTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.vv(TAG, "checkBusActiveTask()");
                if (!hasRecentRx) {
                    // we have not received any rx .. mark the bus as not active
                    if (bus_status == BUS_STATUS_UP) {
                        setBusStatus(BUS_STATUS_UP_NOCOMM);
                    }
                }
                hasRecentRx = false;

            } catch(Exception e) {
                Log.e(TAG + ".checkBusActiveTask", "Exception: " + e.toString(), e);
            }

            mainHandler.postDelayed(checkBusActiveTask, CHECK_BUS_ACTIVE_WAIT_MS); // check again in a second or so
            Log.vv(TAG, "checkBusActiveTask() END");
        }
    }; // checkBusActiveTask()


    ///////////////////////////////////////////////////////////////
    // setRecentRxReceived()
    //  remembers that we received something on the bus
    ///////////////////////////////////////////////////////////////
    void setRecentRxReceived() {

        hasRecentRx = true; // we've received some kind of activity on this bus
        if (bus_status == BUS_STATUS_UP_NOCOMM)
            setBusStatus(BUS_STATUS_UP);
    }



    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // DTC collection
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////

    public static class Dtc {
        long dtc_value;
        int occurence_count;
        int bytes_parsed; // the length of the DTC in the message (may be either 2 or 3 bytes)
    } // Dtc


    List<Dtc> collectedDtcs = new ArrayList<Dtc>();
    boolean dtcIsCollecting = false;
    int numCollectedDtcs = 0;

    ////////////////////////////////////////////////////////////////
    // startCollectingDtcs()
    //      start the timer that will process all the DTCs received when the collection window expires
    ////////////////////////////////////////////////////////////////
    void startCollectingDtcs() {

        if (dtcIsCollecting) return; // we've already started collecting the DTCs

        clearCollectedDtcs();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(collectDTCsTask);
            mainHandler.postDelayed(collectDTCsTask, DTC_COLLECTION_TIME_MS);
            dtcIsCollecting = true;
        } else {
            Log.e(TAG, "MainHandler Null!? Cannot collect DTCs");
        }

    } // startCollectingDtcs()

    ////////////////////////////////////////////////////////////////
    // stopCollectingDtcs()
    ////////////////////////////////////////////////////////////////
    void stopCollectingDtcs() {

        if (mainHandler != null) {
            mainHandler.removeCallbacks(collectDTCsTask);
        }
        dtcIsCollecting = false;
    } // stopCollectingDtcs()

    ////////////////////////////////////////////////////////////////
    //  clearCollectedDtcs
    //      prepares a temporary queue to hold the DTCs we receive from various nodes
    ////////////////////////////////////////////////////////////////
    void clearCollectedDtcs() {
        // empty our list so we can start adding what is reported
        collectedDtcs.clear();

        // as a safety, we should track that we've received at least one DTC message.
        // we should receive a DTC message even if there are no Current DTCs
        dtcIsCollecting = false;
    }


    ////////////////////////////////////////////////////////////////
    // addDTC()
    //  adds a DTC to the list of ones we have collected recently, or updates it
    ////////////////////////////////////////////////////////////////
    void addDtc(Dtc dtc) {
        if (dtc == null) return;

        // check that we have not already been collected. if we have, then just update
        boolean found = false;
        for (Dtc cdtc : collectedDtcs) {
            if (cdtc.dtc_value == dtc.dtc_value) {
                found = true;
                cdtc.occurence_count = dtc.occurence_count;
                break;
            }
        }
        // if we haven't found our DTC in the list, then add it now.
        if (!found) collectedDtcs.add(dtc);

    } // addDtc






    /////////////////////////////////////////////////////
    // checkDtcs()
    //  Inform the engine module that the DTC list is ready for processing and checking for changes
    // Returns:
    //  0xAADD where AA = num added, DD = num_deleted
    /////////////////////////////////////////////////////
    int checkDtcs(List<Dtc> newDtcs) {

        long[] longDtcs = new long[newDtcs.size()];

        int i = 0;
        for (Dtc dtc : newDtcs) {
            longDtcs[i] = dtc.dtc_value;
            i++;
        }

        // we should use our actual bus-type (not the generic "J1939" here, because the Engine module
        //  keeps track of each DTC along with a bus and this is also sent to the server.
        // Even though it doesn't matter to server
        //  whether this comes from 250kb or 500kb bus, we want to keep the constants values consistent.
        numCollectedDtcs = newDtcs.size();
        return engine.checkDtcs(myBusType, longDtcs);

    } // checkDtcs()




    ////////////////////////////////////////////////////////////////
    // processCollectedDTCs()
    //  sends our complete DTC list to the Engine module, and then clears our list to start collecting again
    //  make sure enough time has passed between calls to be sure we captured all of the DTCs that are reporting on the bus
    ////////////////////////////////////////////////////////////////
    void processCollectedDTCs() {


        // we received at least one DTC message
        Log.v(TAG, "" + collectedDtcs.size() + " active DTCs");

        checkDtcs(collectedDtcs);

        clearCollectedDtcs();
    }



    ///////////////////////////////////////////////////////////////
    // collectDTCsTask()
    //  executes periodically to process any collected DTCs
    ///////////////////////////////////////////////////////////////
    Runnable collectDTCsTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.vv(TAG, "collectDTCsTask()");
                processCollectedDTCs();
                Log.vv(TAG, "collectDTCsTask() END");
            } catch(Exception e) {
                Log.e(TAG + ".collectDTCsTask", "Exception: " + e.toString(), e);
            }
        }
    }; // collectDTCsTask()



    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // Utility functions
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////



    ////////////////////////////////////////////////////////
    // littleEndian2Long()
    //  takes a little endian byte array (up to 8 bytes) and returns a long value
    //  Parameters:
    //   byte_array: the array that we are using
    //   index: the starting index in the array (e.g. 0 to start at beginning of array)
    //   number_of_bytes: how many bytes to use (e.g. 4 for a 4 byte value, 8 for the maximum long value)
    ////////////////////////////////////////////////////////
    public static long littleEndian2Long(byte[] bytearray, int start_index, int number_of_bytes) {
        long l;
        int i;

        l = 0;
        for (i = start_index+number_of_bytes-1; i >= start_index; i--) {
            l <<= 8;
            l |= (bytearray[i] & 0xFF);
        }

        return l;
    } // littleEndian2Long



    ////////////////////////////////////////////////////////
    // long2LittleEndian()
    //  places the value into the array little-endian style
    //  Parameters:
    //   value: the value to place into the array
    //   byte_array: the array that we are using, where we will place the value
    //   index: the starting index in the array (e.g. 0 to start at beginning of array)
    //   number_of_bytes: how many bytes to use (e.g. 4 for a 4 byte value, 8 for the maximum long value)
    ////////////////////////////////////////////////////////
    public static void long2LittleEndian(long value, byte[] bytearray, int start_index, int number_of_bytes) {

        int i;

        for (i = start_index; i < start_index + number_of_bytes; i++) {
            bytearray[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }

    } // long2LittleEndian






} // class
