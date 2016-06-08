/////////////////////////////////////////////////////////////
// J1587:
//  Handles the SAE J1587 Heavy Vehicle Bus protocol that runs on J1708
/////////////////////////////////////////////////////////////


package com.micronet.dsc.ats;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class J1587 extends EngineBus {


    private static final String TAG = "ATS-J1587"; // for logging
    public static final int DTC_COLLECTION_TIME_MS = 20000; // wait this long to collect all reported DTCs (DTCs are sent every 15s, this must be longer)


    private static final int ADDRESS_MID = 180 ; // this is the MID (Address) that we will use for requests
                                                 // 180 = "Off-board diagnostics #2"



    //ScheduledThreadPoolExecutor exec;

//    J1708 j1708;


    // These are really only used for logging
    String vin = "";
    long odometer_m = -1;
    long fuel_mL = -1;




    public static class Packet {
        int messageId;

        int priority = 6;
        byte[] data = new byte[] {-1, -1, -1, -1, -1, -1, -1, -1}; // there are up to 8 bytes in a packet (set to 0xFF by default)

    } // Packet


    public static class J1708Frame {
        int priority;
        int id;
        byte[] data;
    }


    //////////////////////////////////////////////////
    // PIDs
    //////////////////////////////////////////////////

    static final int MID_J1587_MINIMUM = 128; //MIDs below this number are not part of J1587 and must be ignored


    static final int PID_REQUEST = 0;   // PID used when making a request for info
    static final int PID_VIN = 237;
    static final int PID_ODOMETER = 245;
    static final int PID_FUEL_CONSUMPTION = 250;
    static final int PID_DIAGNOSTICS = 194;

    static final int PID_CONNECTION_MANAGEMENT = 197; // for transport protocol
    static final int PID_CONNECTION_DATA = 198;

    static final int PID_ESCAPE = 254; // a PID with first byte of this value means add 256 to the next byte to get the PID value
    static final int PID_EXTENSION = 255; // a PID with first byte of this value means add 256 to the next byte to get the PID value

    static final int PID_MAX_ONE_BYTE = 127; // PIDs less than this are 1 byte long
    static final int PID_MAX_TWO_BYTE = 191; // PIDs less than this are 2 bytes long
    static final int PID_MAX_VAR_BYTE = 253; // PIDs less than this are variable length


    public void logStatus() {
        Log.d(TAG,  engine.getBusName(myBusType) + "= " + (isCommunicating() ? "UP" : "--") + " Addr " + ADDRESS_MID + " : " +
            " DTCs " + numCollectedDtcs +
            " Odom " +  (odometer_m  == -1 ? '?' : odometer_m + "m") +
            " FuelC " + (fuel_mL == -1 ? '?' : fuel_mL + "mL") +
            " VIN " + vin
        );
    } // logStatus



    public J1587(Engine engine, boolean warmStart) {

        super.TAG = TAG;
        super.DTC_COLLECTION_TIME_MS = DTC_COLLECTION_TIME_MS;
        myBusType = Engine.BUS_TYPE_J1587; // bus type is constant and does not change for this class
        this.engine = engine;


        // and we need to create the connection objects in the array
        //for (int i=0; i< MAX_TP_CONNECTIONS; i++ )
        //    connections[i] = new TpConnection();



        // if this is the first start since power-up. we do a cold-start
        if (!warmStart) {
            // do something ?
        }
    }




    ////////////////////////////////////////////////////////////////////
    // start()
    //      "Start" the J1587 monitoring, called when app is started, ignition turned on, etc..
    ////////////////////////////////////////////////////////////////////
    public void start() {
        Log.v(TAG, "start()");



        setBusStatus(BUS_STATUS_IDLE);

        mainHandler  = new Handler(Looper.getMainLooper());

        if (!setupJ1708bus()) return;


        hasRecentRx = false; // no recent RX has been received from the bus

        startCheckingActivity();

        startJ1708bus();



    } // start



    ////////////////////////////////////////////////////////////////////
    // stop()
    //      "Stop" the J1587 monitoring, called when app is ended, ignition is turned off, etc..
    ////////////////////////////////////////////////////////////////////
    public void stop() {

        Log.v(TAG, "stop()");



        stopCollectingDtcs();
        stopCheckingActivity();


        // Stop the underlying J1708 layer
        stopJ1708bus();

        setBusStatus(BUS_STATUS_IDLE);

        mainHandler  = null;

    } // stop()


    ////////////////////////////////////////////////////////////////////
    // destroy()
    //      Destroys wakelocks (this is done after recording crash data as a last step
    ////////////////////////////////////////////////////////////////////
    public void destroy() {
    }

    ////////////////////////////////////////////////////////////////////
    // saveCrashData()
    //      save data in preparation for an imminent crash+recovery
    ////////////////////////////////////////////////////////////////////
    public void saveCrashData(Crash crash) {

        // Intermediate Data that needs to be stored

    } // saveCrashData()


    ////////////////////////////////////////////////////////////////////
    // restoreCrashData()
    //      restore data from a recent crash
    ////////////////////////////////////////////////////////////////////
    public void restoreCrashData(Crash crash) {
        // Intermediate Data that needs to be restored
    } // restoreCrashData()

    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    //  Functions that will be called publicly
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////


    ////////////////////////////////////////////////////////////////
    // sendRequestVIN()
    //  sends a request to respond with the vehicle vin
    ////////////////////////////////////////////////////////////////
    public void sendRequestVIN() {

        requestPid(PID_VIN);

    } // sendRequestVIN()


    ////////////////////////////////////////////////////////////////
    // sendRequestDTC()
    //  sends a request to respond with the vehicle dtc
    ////////////////////////////////////////////////////////////////
    public void sendRequestDTC() {

        requestPid(PID_DIAGNOSTICS);

    } // sendRequestVIN()


    ////////////////////////////////////////////////////////////////
    // sendRequestTotalFuel()
    //  sends a request to respond with the vehicle vin
    ////////////////////////////////////////////////////////////////
    public void sendRequestTotalFuel() {

        requestPid(PID_FUEL_CONSUMPTION);

    } // sendRequestTotalFuel()


    ////////////////////////////////////////////////////////////////
    // sendRequestCustom()
    //  sends a request for the pids in the list
    ////////////////////////////////////////////////////////////////
    public void sendRequestCustom(int[] pids_array) {

        if (pids_array == null) return;

        for (int pid : pids_array) {
            requestPid(pid);
        }

    } // sendRequestCustom()


    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    //  Checking if monitored variables have changed
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////



    String checkVin(String newVin) {

        vin = newVin;
        return engine.checkVin(Engine.BUS_TYPE_J1587, newVin);
    }


    long checkOdometer(long new_odometer_m) {

        odometer_m = new_odometer_m;
        return engine.checkOdometer(Engine.BUS_TYPE_J1587, new_odometer_m);

    } // checkOdometer()

    long checkFuelConsumption(long new_fuel_mL) {

        fuel_mL = new_fuel_mL;
        return engine.checkFuelConsumption(Engine.BUS_TYPE_J1587, new_fuel_mL);

    } // checkFuelConsumption()


    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // Transport Protocol & Connection Management
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////



    // Max J1708 frame size is 21 bytes, hence the need for Transport protocol

    // However, in the field, all the messages should fit in this frame.
    // If in future we request or process larger data sizes (e.g. text messages), then
    // this will need to be implemented


    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // Basic PID Codec
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////





    void requestPid(int pid) {

        Packet packet = new Packet();

        packet.messageId = ADDRESS_MID;
        packet.priority = 8;
        packet.data = new byte[] {PID_REQUEST, (byte) pid};

        sendPacket(packet);
    } // requestPid


    void parsePid(int mid, int pid, byte[] data, int start_index, int length) {

        // all integers are little endian, all strings are big endian

        Log.vv(TAG, "Parsing MID " + mid + " PID " + pid);

        // check if we need to forward "raw" data to somewhere else
        engine.checkRawForwarding(Engine.BUS_TYPE_J1587, pid, mid, data);

        long lval;
        switch(pid) {

            case PID_VIN:
                Log.v(TAG, "Parsing VIN From " + mid + " PID " + pid);
                String newVin = new String(data, start_index, length);
                checkVin(newVin);
                break;
            case PID_ODOMETER:
                Log.v(TAG, "Parsing Odometer From " + mid + " PID " + pid);
                // should be 4 bytes
                if (length != 4) {
                    Log.e(TAG, "Expected Odometer value to be 4 bytes long");
                    return;
                }
                // Bit Resolution: 0.161 km (0.1 mi)
                // so each unit is 160.9 = 161 meters
                lval = littleEndian2Long(data, start_index, length);
                checkOdometer(lval * 161);

                break;
            case PID_FUEL_CONSUMPTION:
                Log.v(TAG, "Parsing Total Fuel From " + mid + " PID " + pid);
                // should be 4 bytes
                if (length != 4) {
                    Log.e(TAG, "Expected Total Fuel value to be 4 bytes long");
                    return;
                }

                // Bit Resolution: 0.473 L (0.125 gal
                // 0.47317647 L = 473 mL
                lval = littleEndian2Long(data, start_index, length);
                checkFuelConsumption(lval * 473);

                break;
            case PID_DIAGNOSTICS:

                // n a b c a b c a b c a b c a b c a b c
                // n = PID data length .. already decoded since this is variable length PID
                //  a = PID/SID
                //  b = bitfield and FMI
                //      bit 8 = occurrence count is present
                //      bit 7 = fault is inactive
                //      bit 6 = standard diagnostic code (0 = extended)
                //      bit 5 = SID ( 0 = PID)
                //      bites 4-1 = Failure Mode Indicator
                //  c = optional occurrence count

                // reported DTC is C MID PID B

                int i = start_index;

                int num_processed_bytes = 0; // number of bytes of dtcs processed from thus packet

                Log.v(TAG, "Parsing DTCs From " + mid + " PID " + pid + " (" + length + " bytes) = x" +
                    Log.bytesToHex(data, start_index, length)

                );


                startCollectingDtcs(); // if we are not yet collecting, this starts the timer

                Dtc dtc;
                while(num_processed_bytes < length) {

                    // Parse out each DTC from the array of bytes


                    dtc = parseTroubleCode(mid, data, i);

                    if (dtc ==null) {
                        Log.v(TAG, "DTC: Null value ??");
                        break; // if something is wrong we must stop parsing since we don't know how many bytes to use
                    }

                    addDtc(dtc);

                    i += dtc.bytes_parsed;
                    num_processed_bytes += dtc.bytes_parsed;
                }

                break;

        }

    } // parsePid()


    ////////////////////////////////////////////////////////////////
    // sendRequestPid()
    //  sends a request for a PID
    ////////////////////////////////////////////////////////////////
    private void sendRequestPid(int pid) {

        Packet packet = new Packet();

        packet.messageId = ADDRESS_MID;
        packet.priority = 8; // requests are always lowest priority
        packet.data = new byte[] {0 , (byte) pid };

        sendPacket(packet);

    } // sendRequestPid()


    ////////////////////////////////////////////////////////////////
    //  parseTroubleCode
    //      parses out a trouble code
    //  returns:
    //      0 if the DTC is invalid (not a DTC)
    //      otherwise, returns the 4 byte DTC
    ////////////////////////////////////////////////////////////////
    Dtc parseTroubleCode(int mid, byte[] data, int startpos) {

        // J1587 Trouble codes are 2-3 bytes long with the third byte (occurrence count) is optional
        //  They are also dependent on mid

        // byte 1 PID
        // byte 2 Code Character bitfield (bit 7 is current status, bit 8 is occurrence_count_included
        // byte 3 (optional) occurrence count

        if (data.length < startpos + 2) return null; // safety: invalid (not enough data)

        if ((data[0+startpos] == 0) || (data[0+startpos] == 0xFF)) {
            // invalid code PID/SID 0 or FF
            return null;
        }

        // start parsing

        Dtc dtc = new Dtc();

        dtc.bytes_parsed = 2; // default is 2 bytes

        dtc.occurence_count = 0;
        if ((data[startpos+1] & 0x80) > 0) {
            // occurrence count is present
            // this means the dtc is three bytes here
            dtc.occurence_count = data[startpos+2];
            dtc.bytes_parsed = 3;
        }



        // resulting value (big endian)
        //  DTC Value Result is 0x00 MID PID/SID DCC (Diagnostic code except bit8&7 includes fmi)

        long res;

        res = (data[1+startpos] & 0x3F); // DCC: ignore bits for occurrence count included and current status
        res <<=8;
        res |= mid;  // MID
        res <<=8;
        res |= (data[startpos] & 0xFF); // PID

        dtc.dtc_value = res;


        return dtc;

    } // parseTroubleCode()



    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // J1708 Bus control
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////



    ///////////////////////////////////////////////////////////////////
    // ceasePacketsInProgress()
    //  stop attempting to send any packets in progress (maybe our address changed)
    ///////////////////////////////////////////////////////////////////
    private void ceasePacketsInProgress() {


        // kill any transmits in the CAN queue (must happen within 50 ms)
        //j1708.abortTransmits();

        // kill any connections opened by the transport protocol (do not send abort .. let these timeout)
        //removeAllConnections();

    } // ceasePacketsInProgress()



    //////////////////////////////////////////////////////////////////
    // packet2frame()
    //  convert from J1587 packet to a J1708 frame
    //////////////////////////////////////////////////////////////////
    J1708Frame packet2frame(Packet packet) {

        J1708Frame frame = new J1708Frame();
        frame.priority = packet.priority;
        frame.id = packet.messageId;
        frame.data = packet.data;

        return frame;
    } // packet2frame()


    //////////////////////////////////////////////////////////////////
    // frame2packet()
    //  convert from J1708 frame to a J1587 packet
    //////////////////////////////////////////////////////////////////
    Packet frame2packet(J1708Frame frame) {
        Packet packet = new Packet();

        packet.messageId = frame.id;
        packet.priority = frame.priority;
        packet.data = frame.data;

        return packet;
    } //frame2packet()




    /////////////////////////////////////////////////////////
    // sendPacket()
    //  sends a packet onto the CAN bus
    /////////////////////////////////////////////////////////
    private void sendPacket(Packet packet) {

        Log.vv(TAG, "packet --> P" + packet.priority + " " + packet.messageId + " : " + Log.bytesToHex(packet.data, packet.data.length));

        //Log.vv(TAG, "SendPacket()");

        J1708Frame frame = packet2frame(packet);
        broadcastTx(frame);
        Log.vv(TAG, "SendPacket() END");

    } // sendPacket()


    /////////////////////////////////////////////////////////
    // receivePacket()
    //  receives a packet from the CAN bus
    //  Returns:
    //      null : did not parse any PIDs
    //      array of ints : up to first three PIDs that were parsed (up to max amount of 10
    /////////////////////////////////////////////////////////
    int[] receivePacket(Packet packet) {


        if (packet.messageId < MID_J1587_MINIMUM) {
            // not a J1587 message ID, there can be other protocols running on J1708
            return null;
        }

        engine.setBusDetected(Engine.BUS_TYPE_J1587); // let the engine know we have detected data on this bus;
        setRecentRxReceived();
        setBusStatus(BUS_STATUS_UP); // guarantee that bus status is now up, even if it was something other than IDLE


        // OK, This is a valid J1587 packet

        Log.vv(TAG, "packet <-- P" + packet.priority + " " + packet.messageId + " : " + Log.bytesToHex(packet.data, packet.data.length));


        if (packet.data.length == 0) {
            Log.e(TAG, "Empty message has no PIDs (message length = 0)");
            return null;
        }


        // variables for returning what we did
        final int MAX_RETURN_PIDS = 3;
        int[] parsedPIDs = new int[] {0,0,0}; // this should be length of MAX_RETURN PIDS
        int num_parsed_pids = 0;

        // We need to parse out PIDs from the data
        int i =0;
        int length;
        int pid;
        int mid = packet.messageId;

        boolean is_second_page_pid = false; // the pids in this message are first page

        // The first byte may be PID_EXTENSION, which means all PIDs are second page
        if (packet.data[0] == PID_EXTENSION) {
            is_second_page_pid = true;
            i = 1; // start at the next byte (index 1)
        }


        while (i < packet.data.length) {
            pid = ((int) packet.data[i] & 0xFF);

            if (is_second_page_pid) pid += 256; // this is a second page PID

            i++;
            length = 0;

            if ((pid % 256) == PID_ESCAPE) {
                Log.e(TAG, "Packet contains escape character at [" + i + "]. Processing aborted.");
                return null;
            }
            else
            if ((pid % 256) <= PID_MAX_ONE_BYTE) {
                // pid is only one byte long
                length = 1;
            } else
            if ((pid % 256) <= PID_MAX_TWO_BYTE) {
                // pid is two bytes long
                length = 2;
            } else
            if ((pid % 256) <= PID_MAX_VAR_BYTE) {
                // Next byte determines the length
                if (i >= packet.data.length) {
                    Log.e(TAG, "Packet too small. Expected variable length at [" + i +"] for PID=" + pid);
                    return null;
                }

                length = ((int) packet.data[i] & 0xFF);
                i++;
            }

            // It is possible to receive a PID with length 0 (e.g. Diagnostic PID with no DTCs reporting)
            //if (length ==0) {
            //    Log.e(TAG, "Received PID=" + pid + " with unknown length. Processing aborted.");
            //    return null;
            //}
            if (i+length > packet.data.length) { // this is >, not >=, because we are already pointing at byte after the PID
                Log.e(TAG, "Packet too small. Expected " + length +" bytes data at [" + i + "] for PID=" + pid );
                return null;
            }
            parsePid(mid, pid, packet.data, i, length);
            i+=length;

            // and remember what we did so we can return for unit testing
            if (num_parsed_pids < MAX_RETURN_PIDS) {
                parsedPIDs[num_parsed_pids] = pid;
                num_parsed_pids++;
            }

        } // while more to parse

        if (num_parsed_pids == 0) {
            Log.e(TAG, "Could not find any PIDs in packet");
            return null;
        }

        return Arrays.copyOf(parsedPIDs, num_parsed_pids);

    } // receivePacket()


    // return null if nothing was parsed or an array of ints representing the PIDs parsed
    int[] receiveJ1708Frame(J1708Frame frame) {

        if (frame != null) {
            Packet packet = frame2packet(frame);
            return receivePacket(packet);
        }
        return null; // not parsed
    } // receiveJ1708Frame()


    /*

    //////////////////////////////////////////////////////////////////
    // isSupported()
    //  does the hardware support J1587 ?
    //////////////////////////////////////////////////////////////////
    public static boolean isSupported() {
        return J1708.isSupported();
    } // isSupported()



    boolean setupJ1708bus() {
        if (!isSupported()) return false; // we don't support J1587

        j1708 = new J1708(engine.service.isUnitTesting);
        j1708.setReceiveCallbacks(mainHandler, frameAvailableCallback, busReadyRxCallback, busReadyTxCallback);

        return true;
    }

    void startJ1708bus() {
        j1708.start();
    }

    void stopJ1708bus() {
        if (j1708 != null) {
            j1708.stop();
        }
    }



    ///////////////////////////////////////////////////////////////
    // receiveAllFrames()
    //  takes available frames from the CAN class one by one and processes them
    ///////////////////////////////////////////////////////////////
    void receiveAllFrames() {
        // is there something in the queue to be received?
        J1708Frame frame;
        do {
            frame = j1708.receiveFrame();
            receiveJ1708Frame(frame);
        } while (frame != null);
    } // receiveAllFrames()


    ///////////////////////////////////////////////////////////////
    // frameAvailableCallback()
    //  This runnable will be posted by the CAN class whenever there is something in the receive buffer
    ///////////////////////////////////////////////////////////////
    private Runnable frameAvailableCallback = new Runnable() {
        @Override
        public void run() {
            try {
                Log.vv(TAG, "frameAvailableCallback()");
                // process any frames that are ready
                receiveAllFrames();
                Log.vv(TAG, "frameAvailableCallback() END");
            } catch (Exception e) {
                Log.e(TAG + ".frameAvailableCallback", "Exception: " + e.toString(), e);
            }
        } // run()
    }; // frameAvailableCallback()


    ///////////////////////////////////////////////////////////////
    // busReadyCallback()
    //  This runnable will be posted by the CAN class whenever we are ready to write on the bus
    ///////////////////////////////////////////////////////////////
    private Runnable busReadyTxCallback = new Runnable() {
        @Override
        public void run() {
            try {
                Log.vv(TAG, "busReadyTxCallback()");

            } catch (Exception e) {
                Log.e(TAG + ".busReadyTxCallback", "Exception: " + e.toString(), e);
            }
        } // run()
    }; // busReadyTxCallback()

    ///////////////////////////////////////////////////////////////
    // busReadyCallback()
    //  This runnable will be posted by the CAN class whenever we are ready to read on the bus
    ///////////////////////////////////////////////////////////////
    private Runnable busReadyRxCallback = new Runnable() {
        @Override
        public void run() {
            try {
                Log.vv(TAG, "busReadyRxCallback()");

            } catch (Exception e) {
                Log.e(TAG + ".busReadyRxCallback", "Exception: " + e.toString(), e);
            }
        } // run()
    }; // busReadyRxCallback()

*/


    boolean setupJ1708bus() {
        return true; // setup successful
    }

    void startJ1708bus() {
        Log.v(TAG, "Starting Vbus/J1708 Service");

        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(VehicleBusConstants.BROADCAST_J1708_RX);
            engine.service.context.registerReceiver(rxReceiver, intentFilter, VehicleBusConstants.PERMISSION_VBS_TXRX, null);
        } catch (Exception e) {
            Log.e(TAG, "Could not register CAN Rx receiver");
        }

        //ComponentName component = ComponentName(VehicleBusConstants.PACKAGE_NAME, VehicleBusConstants.SERVICE_CLASS_NAME);

        Intent serviceIntent = new Intent();
        serviceIntent.setPackage(VehicleBusConstants.PACKAGE_NAME_VBS);
        serviceIntent.setAction(VehicleBusConstants.SERVICE_ACTION_START);

        serviceIntent.putExtra("bus", "J1708");
        engine.service.context.startService(serviceIntent);

    }

    void stopJ1708bus() {

        Log.v(TAG, "Stopping Vbus/J1708 Service");

        try {
            engine.service.context.unregisterReceiver(rxReceiver);
        } catch(Exception e) {
            // don't do anything
        }

        Intent serviceIntent = new Intent();
        serviceIntent.setPackage(VehicleBusConstants.PACKAGE_NAME_VBS);
        serviceIntent.setAction(VehicleBusConstants.SERVICE_ACTION_STOP);
        serviceIntent.putExtra("bus", "J1708");

        engine.service.context.startService(serviceIntent);

    }


    List<J1708Frame> outgoingList = Collections.synchronizedList(new ArrayList<J1708Frame>());

    void broadcastTx(J1708Frame frame) {

        if (engine.service.isUnitTesting) {
            // just place this in a list so we can test against it
            outgoingList.add(frame);
            return;
        }

        Intent ibroadcast = new Intent();
        ibroadcast.setPackage(VehicleBusConstants.PACKAGE_NAME_VBS);
        ibroadcast.setAction(VehicleBusConstants.BROADCAST_J1708_TX);


        //ibroadcast.putExtra("password", VehicleBusService.BROADCAST_PASSWORD);
        ibroadcast.putExtra("id", frame.id);
        ibroadcast.putExtra("priority", frame.priority);
        ibroadcast.putExtra("data", frame.data);

        engine.service.context.sendBroadcast(ibroadcast);
    } // broadcastTx()


    RxReceiver rxReceiver = new RxReceiver();
    class RxReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            try {
                /*
                String password = intent.getStringExtra("password");
                if ((password == null) || (!password.equals(VehicleBusService.BROADCAST_PASSWORD))) {
                    Log.e(TAG, "Received invalid J1708 RX broadcast");
                    return;
                }
                */

                J1708Frame frame = new J1708Frame();

                frame.priority = intent.getIntExtra("priority", -1);
                frame.id = intent.getIntExtra("id", -1);
                frame.data = intent.getByteArrayExtra("data");

                receiveJ1708Frame(frame);
            } catch (Exception e) {
                Log.e(TAG, ".RxReceiver Exception : " + e.toString(), e);
            }
        }
    } // RxReceiver


} // class J1587
