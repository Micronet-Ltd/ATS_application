/////////////////////////////////////////////////////////////
// J1939:
//  Handles the SAE J1939 Heavy Vehicle Bus protocol that runs on CAN
/////////////////////////////////////////////////////////////


package com.micronet.dsc.ats;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;


import java.util.ArrayList;
import java.util.Arrays;

import java.util.Collections;
import java.util.List;
import 	java.util.Random;
import java.util.concurrent.ScheduledThreadPoolExecutor;


public class J1939 extends EngineBus {

    //static final int DEBUG_FORCE_LAST_ADDRESS = 0x80; // always use 0x80 for the preferred address
    static final int DEBUG_FORCE_LAST_ADDRESS = 0 ; // do not force any particular last address



    private static final String TAG = "ATS-J1939"; // for logging
    public static final int DTC_COLLECTION_TIME_MS = 2000; // wait this long to collect all reported DTCs (DTCs are sent at 1Hz, this must be longer)




    ScheduledThreadPoolExecutor exec;

//    CAN can;
//    CanbusHardwareFilter[] canHardwareFilters;
    int[] canFilterIds;     // Frame Ids that we are interested in
    int[] canFilterMasks;   // Corresponding Masks for those ids
    boolean busTxIsReady; // this will be set to true once we are told by service that the bus Tx is ready


    // These are really only used for logging
    String vin = "";
    boolean flagParkingBrake = false;
    boolean flagReverseGear = false;
    long odometer_m = -1;
    long fuel_mL = -1;
    long fuel_mperL = -1;




    int last_known_bus_type = Engine.BUS_TYPE_NONE;
    int last_known_bus_address = J1939_ADDRESS_NULL;

    // Discovery

    public static final int DISCOVER_BUS_WAIT_MS = 2000; // wait 2 seconds on each bus

    public static final int DISCOVERY_STAGE_OFF = 0;
    public static final int DISCOVERY_STAGE_250 = 1;
    public static final int DISCOVERY_STAGE_500 = 2;

    int discoveryStage = DISCOVERY_STAGE_OFF;


    boolean discoveryRequired = false; // this is set to true if the bus needs to be discovered before using it,


    // Address Claiming

    //public static final long J1939_NAME = 0x8000000000000000L;

    // A J1939 Name is a 64 bit Id composed of 10 fields of (x) bits = Value
    // Abitrary Addressing? (1) = 1
    // Industry Group (3)
    // System Instance (4)
    // System (7)
    // Reserved (1) = 0
    // Function (8)
    // Function Instance (5) = 0 (first Function)
    // ECU Instance (3) = 0 (first ECU)
    // Manufacturer (11) assigned by committee
    // Identity (21) = 0 (assigned by us)

    // The name is sent over CAN little-endian byte 1 first, byte 8 last
    // Byte 7:  bit7 : Arbitrary Address Capable
    //          bit6,5,4 : Industry Group
    //          bit3-0 : Vehicle system instance
    // Byte 6:  bits 7-1: Vehicle System
    //          bit 0: reserved
    // Byte 5:  Function
    // Byte 4:  bits 7-3: Function instance
    //          bits 2,1,0: ECU instance
    // Byte 3:     Manufacturer Code (MS 8 bits)
    // Byte 2:  bits 7,6,5: Manufacturer Code (LS 3 bits)
    //          bits 4-0: Identity Number (MS 5 bits)
    // Byte 1:  Identity Number
    // Byte 0:  Identity Number (LSB)


    public static final long J1939_NAME_FUNCTION = 20; // trip recorder
    public static final long J1939_NAME_MANUFACTURER_CODE = 718; // the assigned manu id

    public static final long J1939_BASE_NAME = 0x8000000000000000L | // arbitrary capable
            ((J1939_NAME_FUNCTION & 0xFF) << 40) |
            ((J1939_NAME_MANUFACTURER_CODE & 0x7FF) << 21)
            // Identify number gets added dynamically on class creation
            ;


    public long J1939_NAME_IDENTITY_NUMBER = 0;
    public long J1939_NAME = J1939_BASE_NAME;

    public static final int J1939_ADDRESS_RANGE_LOW = 128; // lowest address we can claim for arbitrary address claiming
    public static final int J1939_ADDRESS_RANGE_HIGH = 247; // highest address we can claim for arbitrary address claiming
    public static final int J1939_ADDRESS_NULL = 254; // the null address if we don't have one yet
    public static final int J1939_ADDRESS_GLOBAL = 255; // the broadcast address


    public static final int ADDRESS_COLLECT_WINDOW_MS = 1250; // 1.25s time to wait after requesting address(es)/Name(s) from other CAs
    public static final int ADDRESS_CLAIMWAIT_WINDOW_MS = 250; // 250ms time to wait after broadcasting a claimed address before continuing
    public static final int ADDRESS_MAX_CANNOTCLAIM_DELAY_MS = 153; // 153ms max time to wait before sending a cannot claim message
                                                               // Note: this may need to be adjusted due to delays in API propagationg

    int myAddress = J1939_ADDRESS_NULL; // the address I have claimed on the bus
    int attemptingAddress = J1939_ADDRESS_NULL; // an address I am attempting to claim

    boolean addressClaimAttempted = false; // have we attempted an address claim yet ?

    boolean[] available_addresses = new boolean[J1939_ADDRESS_RANGE_HIGH+1]; // what addresses are available on the bus


    // TP / Connections

    public static final int CONNECTION_TO_CHECK_MS = 500; // check every 500 ms for connections that have timed-out



    // Packet Parsing

    public static final int PARSED_PACKET_TYPE_NONE = 0; // unable to process this packet
    public static final int PARSED_PACKET_TYPE_PGN = 1; // recognized as a pgn we know about
    public static final int PARSED_PACKET_TYPE_CONTROL = 2; // recognized as a control packet of some kind (request, TP packet, etc.)



    public static class CanFilters {

        int[] ids;
        int[] masks;

    }


    public static class CanFrame {

        int id;
        byte[] data;

        CanFrame() {
        }

        CanFrame(int in_id, byte[] in_data) {
            id = in_id;
            data = in_data;
        }

    } // CanFrame

    public static class CanPacket {

        // The 29 bit ID forms these values
        int priority = 6;
        int source_address;
        int protocol_format; // protocol_format and destination address (16 bits total) form the PGN
        int destination_address; // or protocol_specific

        // The data
        byte[] data = new byte[] {-1, -1, -1, -1, -1, -1, -1, -1}; // there are up to 8 bytes in a packet (set to 0xFF by default)


        void setPGN(int pgn) {
            protocol_format = ((pgn >>> 8) & 0xFF);
            destination_address = (pgn & 0xFF);
        }

        int getPGN() {
            if (protocol_format < 240) // lower byte is always 0 if protocol_format < 240
                return (protocol_format << 8);
            else
                return (protocol_format << 8) + destination_address;
        }


    } // CanPacket






    // PFs have a destination address


    public static final int PF_REQUEST = 0xEA;              // make a request of another node
    public static final int PF_CLAIMED_ADDRESS = 0xEE;      // claim an address

    public static final int PF_CONNECTION_MANAGE = 0xEC;      // manage a connection
    public static final int PF_CONNECTION_DATA = 0xEB;      // transfer data in a connection


    //public static final int PGN_CLAIMED_ADDRESS = 0x00EEFF; // these are always going to global (FF)

    public static final int PGN_VIN = 0x00FEEC; //
    public static final int PGN_FAULT_DM1 = 0x00FECA; //
    public static final int PGN_ODOMETER_HIRES = 0x00FEC1; //
    public static final int PGN_ODOMETER_LORES =  0x00FEE0; //
    public static final int PGN_FUEL_CONSUMPTION = 0x00FEE9; //
    public static final int PGN_FUEL_ECONOMY = 0x00FEF2; //

    public static final int PGN_PARKING = 0x00FEF1; // cruise control&vehicle speed (reports parking brake)
    public static final int PGN_GEAR = 0x00F005; // ECM2 (reports reverse gear)


    // These are Full PGN that we want to receive (not global PDFs)
    public static final int HW_RECEIVE_PGN_MASK = 0x3FFFF00; // use the Reserved bit, DataPage and PF and GN as the hardware mask
    public static final int[] HW_RECEIVE_PGNS = new int[] {
            PGN_VIN << 8,
            PGN_FAULT_DM1 << 8,
            PGN_ODOMETER_HIRES << 8,
            PGN_ODOMETER_LORES << 8,
            PGN_FUEL_CONSUMPTION << 8,
            PGN_FUEL_ECONOMY << 8,
            PGN_PARKING << 8,
            PGN_GEAR << 8
    };

    // These are PFs that we want to receive (all possible addresses)
    public static final int HW_RECEIVE_PF_MASK = 0x3FF0000; // use the Resrved bit, DataPage and PF as the hardware mask
    public static final int[] HW_RECEIVE_PFS = new int[]{
            PF_CLAIMED_ADDRESS << 16,
            PF_CONNECTION_MANAGE << 16,
            PF_CONNECTION_DATA << 16
    };



    // There are two different odometer types we can use, lo-res and hi-res.
    // If hi-res is present, then we want that to take priority, otherwise we use lo-res

    static final int ODOMETER_TYPE_UNKNOWN = 0;
    static final int ODOMETER_TYPE_LORES = 1;
    static final int ODOMETER_TYPE_HIRES = 2;
    int odometer_type = ODOMETER_TYPE_UNKNOWN;



    public void logStatus() {
        Log.d(TAG,  engine.getBusName(myBusType) + "= " + (isCommunicating() ? "UP" : "--") + " Addr " + myAddress + " : " +
                " DTCs " + numCollectedDtcs +
                " Odom " + (odometer_m == -1 ? "?" : (odometer_type  == ODOMETER_TYPE_LORES ? "(L) " : (odometer_type == ODOMETER_TYPE_HIRES ? "(H) " : "(?)")) + odometer_m + "m") +
                " FuelC " + (fuel_mL == -1 ? "?" : fuel_mL + "mL") +
                " FuelE " + (fuel_mperL == -1 ? "?" : fuel_mperL + "m/L") +
                " Brake " + (flagParkingBrake  ? "1" : "0") +
                " Reverse " + (flagReverseGear ? "1" : "0") +
                " VIN " + vin
        );

    } // logStatus

    ////////////////////////////////////////////////////////////////////
    // J1939() class
    //  warmStart: if true, then we don't need to verify what vehicle we are connected to
    // Parameters:
    //  device_id : The J1939 Name that is broadcast by this node will incorporate the device_id for uniqueness
    ////////////////////////////////////////////////////////////////////
    public J1939(Engine engine, boolean warmStart, int device_id) {
        super.TAG = TAG;
        super.DTC_COLLECTION_TIME_MS = DTC_COLLECTION_TIME_MS;

        this.engine = engine;


        // and we need to create the connection objects in the array
        for (int i=0; i< MAX_TP_CONNECTIONS; i++ )
            connections[i] = new TpConnection();

        // serial numbers are currently 6 digits
        // there are 21 bits available in J1939 protocol for device serial number
        // we'll use up to 20 bits to guarantee high bit is always 0 and reserved for future meanings.
        J1939_NAME_IDENTITY_NUMBER = device_id & 0xFFFFF; // 20 bits
        J1939_NAME &= 0xFFFFFFFFFFE00000L; // 21 bits
        J1939_NAME |= J1939_NAME_IDENTITY_NUMBER;

        // if this is the first start since power-up. we do a cold-start
        if (!warmStart) {
            // DS 2016-01: note bus discovery is temporarily overrriden later so that it never actually occurs
            discoveryRequired = true; // we must discover the bus, if any, we are connected to
        }
    }

/*
    ///////////////////////////////////////////////////////////////////
    // getSocket()
    //  this socket may be needed for other functionality like accessing the J1708 bus
    ///////////////////////////////////////////////////////////////////
    public CanbusSocket getSocket() {
        if (can != null)
            return can.canSocket;
        return null;
    } // getSocket()
*/
    int[] additionalPGNs = null;

    public void setAdditionalPGNs(int[] requested_pgns) {
        additionalPGNs = requested_pgns;
    }

    ////////////////////////////////////////////////////////////////////
    // start()
    //      "Start" the J1939 monitoring, called when app is started
    // Parameters:
    //  requested_pgns : make sure that we also are capable of receiving this pgns.
    ////////////////////////////////////////////////////////////////////
    public void start() {
        Log.v(TAG, "start()");

        myBusType = Engine.BUS_TYPE_NONE;

        setBusStatus(BUS_STATUS_IDLE);


        addressClaimAttempted = false; // pretend we have never attempted a claim
        myAddress = J1939_ADDRESS_NULL; // to start, we don't have an address
        attemptingAddress = J1939_ADDRESS_NULL; // we have not attempted an address

        mainHandler  = new Handler(Looper.getMainLooper());


        setupCanBus(additionalPGNs);

        // start a timer to check bus activity
        hasRecentRx = false; // no recent RX has been received from the bus
        startCheckingActivity();

        // start a timer to time-out connections
        mainHandler.postDelayed(checkConnectionTOTask, CONNECTION_TO_CHECK_MS);


/*        exec = new ScheduledThreadPoolExecutor(1);
        exec.scheduleWithFixedDelay(new ConnectionTOTask(), 500, 500, TimeUnit.MILLISECONDS); // check every 500 ms
*/

        // read information about the last known J1939 connection
        last_known_bus_type = engine.service.state.readState(State.J1939_BUS_TYPE);
        last_known_bus_address = engine.service.state.readState(State.J1939_BUS_ADDRESS);
        if (last_known_bus_address == 0) // not a valid address for us
            last_known_bus_address = J1939_ADDRESS_NULL;

        if (DEBUG_FORCE_LAST_ADDRESS != 0)
            last_known_bus_address = DEBUG_FORCE_LAST_ADDRESS;


        // Start: API does not support auto-detect (discovery)
        // The API does not support listen-only mode, and so we must know the baud rate of the bus before hand
        //  so we cannot discover the baud rate
        discoveryRequired = false;
        int bus_speed = engine.service.config.readParameterInt(Config.SETTING_VEHICLECOMMUNICATION, Config.PARAMETER_VEHICLECOMMUNICATION_J1939_SPEED_KBS);

        if (bus_speed == 500) {
            last_known_bus_type = Engine.BUS_TYPE_J1939_500K;
        } else {
            last_known_bus_type = Engine.BUS_TYPE_J1939_250K;
        }

        // End: API does not support auto-detect


        if (discoveryRequired) {
            // we need to re-discover the bus
            discoverBus(last_known_bus_type);
            // waits for other messages to be detected on the bus
        } else {
            // we already know which bus we are on
            selectBus(last_known_bus_type);
            // wait for the bus to come online and then we can start address claiming
        }

    } // start();

    ////////////////////////////////////////////////////////////////////
    // stop()
    //      "Stop" the J1939 monitoring, called when app is ended
    ////////////////////////////////////////////////////////////////////
    public void stop() {

        Log.v(TAG, "stop()");


        // cancel any timers that may be started
        stopDiscovery(); // Bus discovery tasks
        stopClaimingAddress(); // addressing tasks

        stopCollectingDtcs();
        stopCheckingActivity();

        // remove connection managmeent and tasks
        removeAllConnections(); // kill any TP connections
        if (mainHandler != null) {
            mainHandler.removeCallbacks(checkConnectionTOTask);
        }

        //Log.v(TAG, "stopping -- can");
        // Stop the underlying CAN layer
        stopCanBus();

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
        // Any intermediate Data that needs to be restored
    } // restoreCrashData()





    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    //  Functions that will be called publicly
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////













    ///////////////////////////////////////////////////////////////////
    // isHighResOdometerPresent()
    //  returns if we are receiving high-res odometer information or not
    ///////////////////////////////////////////////////////////////////
    public boolean isHighResOdometerPresent() {
        if (odometer_type == ODOMETER_TYPE_HIRES)
            return true;
        else
            return false;
    } // isHighResOdometerPresent()



    ////////////////////////////////////////////////////////////////
    // sendRequestVIN()
    //  sends a request to respond with the vehicle vin
    ////////////////////////////////////////////////////////////////
    public void sendRequestVIN() {

        sendRequestPGN(PGN_VIN);

    } // sendRequestVIN()


    ////////////////////////////////////////////////////////////////
    // sendRequestOdometerLoRes()
    //  sends a request to respond with the vehicle vin
    ////////////////////////////////////////////////////////////////
    public void sendRequestOdometerLoRes() {

        sendRequestPGN(PGN_ODOMETER_LORES);

    } // sendRequestOdometerLoRes()


    ////////////////////////////////////////////////////////////////
    // sendRequestTotalFuel()
    //  sends a request to respond with the vehicle vin
    ////////////////////////////////////////////////////////////////
    public void sendRequestTotalFuel() {

        sendRequestPGN(PGN_FUEL_CONSUMPTION);

    } // sendRequestTotalFuel()


    ////////////////////////////////////////////////////////////////
    // sendRequestCustom()
    //  sends a request for the PGNs in the list
    ////////////////////////////////////////////////////////////////
    public void sendRequestCustom(int[] pgns_array) {

        if (pgns_array == null) return;

        for (int pgn : pgns_array) {
            sendRequestPGN(pgn);
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
        return engine.checkVin(Engine.BUS_TYPE_J1939,  newVin);
    }

    boolean checkParkingBrake(boolean on) {
        flagParkingBrake = on;
        return engine.checkParkingBrake(Engine.BUS_TYPE_J1939, on);
    } // checkParkingBrake()


    boolean checkReverseGear(boolean on) {
        flagReverseGear = on;
        return engine.checkReverseGear(Engine.BUS_TYPE_J1939, on);

    } // checkReverseGear()

    /////////////////////////////////////////////////////
    // checkOdometer()
    //  do not overwrite a hi-res value with a low-res value, only use low-res if hi-res not available
    /////////////////////////////////////////////////////
    long checkOdometer(int new_odometer_type, long new_odometer_m) {

        // if we ever get a hi-res value, then we remember this and don't use the low-res again
        if (new_odometer_type == ODOMETER_TYPE_HIRES) {

            // always accept and remember that we are using hi-res
            odometer_type = ODOMETER_TYPE_HIRES;
        }
        else if (new_odometer_type == ODOMETER_TYPE_LORES) {
            // only accept if we are not previously using hi-res
            if (odometer_type == ODOMETER_TYPE_HIRES) return 0;
            odometer_type = ODOMETER_TYPE_LORES;
        }

        odometer_m = new_odometer_m;
        return engine.checkOdometer(Engine.BUS_TYPE_J1939, new_odometer_m);

    } // checkOdometer()

    long checkFuelConsumption(long new_fuel_mL) {

        fuel_mL = new_fuel_mL;
        return engine.checkFuelConsumption(Engine.BUS_TYPE_J1939, new_fuel_mL);

    } // checkFuelConsumption()

    long checkFuelEconomy(long new_fuel_mperL) {
        fuel_mperL = new_fuel_mperL;
        return engine.checkFuelEconomy(Engine.BUS_TYPE_J1939, new_fuel_mperL);

    } // checkFuelEconomy()




























    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // Bus Discovery functions
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////




    ////////////////////////////////////////////////////////
    // discoverBus()
    //  attempt to discover the presence of a J1939 bus
    // Parameters
    //  preferred_bus_type : if given, start with this bus (remembered from last time)
    ////////////////////////////////////////////////////////
    boolean discoverBus(int preferred_bus_type) {

        Log.v(TAG, "discoverBus()");

        setBusStatus(BUS_STATUS_DISCOVER);

        // we are not communicating with any bus since we are in "discovery"
        engine.clearBusDetected(Engine.BUS_TYPE_J1939_500K);
        engine.clearBusDetected(Engine.BUS_TYPE_J1939_250K);

        if (preferred_bus_type == Engine.BUS_TYPE_J1939_500K) {
            discoveryStage = DISCOVERY_STAGE_500;
            startCanBus(500000, true, canFilterIds, canFilterMasks);
        } else {
            discoveryStage = DISCOVERY_STAGE_250;
            startCanBus(250000, true, canFilterIds, canFilterMasks);
        }

        // Now we need to wait a certain amount of time to see if we detect any activity on this bus
        // If no activity is detected, then we will try another bus

        mainHandler.postDelayed(discoverBusTask, DISCOVER_BUS_WAIT_MS); // try two seconds

        return true;
    } // discoverBus()


    ///////////////////////////////////////////////////////////////
    // selectBus()
    //  forces the bus to be a specific type (also means we are no longer in discovery)
    ///////////////////////////////////////////////////////////////
    void selectBus(int bus_type) {

        Log.v(TAG, "selectBus(" + bus_type + ")");


        discoveryStage = DISCOVERY_STAGE_OFF; // turn off discovery if it was on

        // clear the J1939 bus types in the engine communication bitfield, we will mark the correct one below
        engine.clearBusDetected(Engine.BUS_TYPE_J1939_500K);
        engine.clearBusDetected(Engine.BUS_TYPE_J1939_250K);

        switch (bus_type) {
            case Engine.BUS_TYPE_J1939_250K:
                engine.setBusDetected(Engine.BUS_TYPE_J1939_250K);
                startCanBus(250000, false, canFilterIds, canFilterMasks); // permanently select this bus (not read-only)
                break;
            case Engine.BUS_TYPE_J1939_500K:
                engine.setBusDetected(Engine.BUS_TYPE_J1939_500K);
                startCanBus(500000, false, canFilterIds, canFilterMasks);
                break;
        }

        myBusType = bus_type;

    } // selectBus()


    ///////////////////////////////////////////////////////////////
    // markDiscovered()
    //  mark the current bus as discovered
    ///////////////////////////////////////////////////////////////
    public void markDiscovered() {

        if (mainHandler != null)
            mainHandler.removeCallbacks(discoverBusTask); // remove any pending timers

        if (discoveryStage == DISCOVERY_STAGE_OFF) return; // we were not in process of discovering

        switch (discoveryStage){
            case DISCOVERY_STAGE_250:
                myBusType = Engine.BUS_TYPE_J1939_250K;
                break;
            case DISCOVERY_STAGE_500:
                myBusType = Engine.BUS_TYPE_J1939_500K;
                break;
        }

        engine.setBusDetected(myBusType); // let the engine know we have detected a bus;

        startCanWriting();

        // remember this we discovered this bus, this will last until the next power-up
        engine.service.state.writeState(engine.service.state.J1939_BUS_TYPE, myBusType);

    } // markDiscovered()


    ///////////////////////////////////////////////////////////////
    // stopDiscovery()
    //  aborts all discovery
    ///////////////////////////////////////////////////////////////
    public void stopDiscovery() {

        if (mainHandler != null) {
            mainHandler.removeCallbacks(discoverBusTask); // remove any pending timers
        }
        discoveryStage = DISCOVERY_STAGE_OFF; // turn off discovery

    } // stopDiscovery

    ///////////////////////////////////////////////////////////////
    // discoverBusTask()
    //  task that executes after listening on a bus for a given amount of time to listen on next bus
    ///////////////////////////////////////////////////////////////
    private Runnable discoverBusTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.d(TAG, "discover window expired");

                switch (discoveryStage) {
                    case DISCOVERY_STAGE_250:
                        startCanBus(500000, true, canFilterIds, canFilterMasks);
                        discoveryStage = DISCOVERY_STAGE_500;
                        break;
                    case DISCOVERY_STAGE_500:
                        startCanBus(250000, true, canFilterIds, canFilterMasks);
                        discoveryStage = DISCOVERY_STAGE_250;
                        break;
                }

            } catch(Exception e) {
                Log.e(TAG + ".discoverBusTask", "Exception: " + e.toString(), e);
            }
            if (discoveryStage != DISCOVERY_STAGE_OFF)
                mainHandler.postDelayed(discoverBusTask, DISCOVER_BUS_WAIT_MS); // try again for two seconds
        }
    }; // discoverBusTask()








    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    //  Address Claiming
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////


    // Address re-intialization should occur for disturbances longer than 1 second
    // When receiving an Address Request, only send the Cannot Claim response if address claim was previously attempted
    // We should attempt to re-use the last address we claimed if there is one.

    // We must wait 250 ms after sending a claim address before we can send anything else to allow others to respond.


    ///////////////////////////////////////////////////////////////////
    // startClaimingAddress()
    //  start the process of claiming a new address
    // Parameters:
    //  preferred_address: try to get this address first if available (pass J1939_ADDRESS_NULL for no preference)
    ///////////////////////////////////////////////////////////////////
    private void startClaimingAddress(int preferred_address) {

        // OK, I guess I don't have an address yet
        myAddress = J1939_ADDRESS_NULL;
        attemptingAddress = J1939_ADDRESS_NULL;

        setBusStatus(BUS_STATUS_CLAIMING_ADDRESS);

        if (preferred_address != J1939_ADDRESS_NULL) {
            Log.v(TAG, "Attempting to claim preferred J1939 address " + preferred_address);
            // we will attempt to claim this preferred address

            sendClaimedAddress(preferred_address); // let everyone know it's ours
            return;
        }

        //
        //  We need to figure out which addresses are available
        Log.v(TAG, "Attempting to claim an arbitrary J1939 address");
        // send the request for everyone to respond with their address
        requestAllAddresses();


    } // startClaimingAddress()


    private void stopClaimingAddress() {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(addressCollectWindowTask);
            mainHandler.removeCallbacks(addressClaimAttemptSuccessTask);
            mainHandler.removeCallbacks(addressNullDelayTask);
        }
    } // stopClaimingAddress()



    private void markAddressInUse(int address) {
        if (address <= J1939_ADDRESS_RANGE_HIGH) {
            available_addresses[address] = false;
        }
    }

    ////////////////////////////////////////////////////////////////
    // requestAllAddresses()
    //  sends a request for all devices to respond with their address
    ////////////////////////////////////////////////////////////////
    private void requestAllAddresses() {


        // mark all possible addresses as unused

        for (int i=J1939_ADDRESS_RANGE_LOW; i <= J1939_ADDRESS_RANGE_HIGH; i++ ) {
            available_addresses[i] = true;
        }

        // send from J1939_ADDRESS_NULL
        // send to J1939_ADDRESS_GLOBAL


        CanPacket packet = new CanPacket();
        packet.protocol_format = PF_REQUEST;
        packet.destination_address = J1939_ADDRESS_GLOBAL;
        packet.source_address = J1939_ADDRESS_NULL;


        // Data is PGN 60928 (x00EE00)
        packet.data[0] = 0;
        packet.data[1] = (byte) PF_CLAIMED_ADDRESS;
        packet.data[2] = 0;


        sendPacket(packet);

        // start a timer to wait to collect all responses.
        if (mainHandler != null) { // safety
            mainHandler.removeCallbacks(addressCollectWindowTask);
            mainHandler.postDelayed(addressCollectWindowTask, ADDRESS_COLLECT_WINDOW_MS);
        }

    } // requestAllAddresses()



    ///////////////////////////////////////////////////////////////////
    // selectAvailableAddress()
    //  Find an address that is still available on the bus
    ///////////////////////////////////////////////////////////////////
    private int selectAvailableAddress() {

        int i = J1939_ADDRESS_RANGE_LOW;

        while (i <= J1939_ADDRESS_RANGE_HIGH) {
            if (available_addresses[i]) break;
            i++;
        }

        if (i <= J1939_ADDRESS_RANGE_HIGH) return i;

        Log.e(TAG, "Unable to find an available address");


        return J1939_ADDRESS_NULL;

    } // selectAvailableAddress()


    ///////////////////////////////////////////////////////////////
    // addressCollectWindowTask()
    //  Timer that waits for all address responses to be collected during address claiming
    ///////////////////////////////////////////////////////////////
    private Runnable addressCollectWindowTask = new Runnable() {

        @Override
        public void run() {
            try {
                int newaddress = selectAvailableAddress();

                if (newaddress != J1939_ADDRESS_NULL) { // this is a valid address
                    Log.i(TAG, "Attempting to claim address " + newaddress);
                    sendClaimedAddress(newaddress); // let everyone know it's ours
                } else {
                    myAddress = newaddress;
                    addressClaimAttempted = true; // remember we tried to do a claim
                    sendClaimedAddress(newaddress); // let everyone know we couldn't claim
                    // Nothing we can do since there were no addresses available
                    // Trigger a watchdog message here?
                    engine.service.addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_J1939_NOADDRESSAVAILABLE);
                    // remember that we failed.
                    setBusStatus(BUS_STATUS_FAILED);
                }

            } catch(Exception e) {
                Log.e(TAG + ".addressCollectWindowTask", "Exception: " + e.toString(), e);
            }
        }
    }; // addressCollectWindowTask()



    ////////////////////////////////////////////////////////////////
    // sendClaimedAddress()
    //  tell the network that we are claiming a specific address
    //  we send this if we receive a lower-priority claim on our address, or when we first claim it
    // Claiming the NULL address means "Cannot claim address"
    ////////////////////////////////////////////////////////////////
    private void sendClaimedAddress(int address) {

        // always send to J1939_ADDRESS_GLOBAL ,so everyone can update their tables
        // we can use the NULL address, but make sure it is vetted first (appropriate delay was added, etc..)

        if (mainHandler != null) { // we're not trying to call this before we called start on the I/O
            mainHandler.removeCallbacks(addressClaimAttemptSuccessTask);
            mainHandler.removeCallbacks(addressNullDelayTask);
        }


        CanPacket packet = new CanPacket();
        packet.protocol_format = PF_CLAIMED_ADDRESS;
        packet.destination_address = J1939_ADDRESS_GLOBAL;
        packet.source_address = address;

        // convert name from a long to a little endian byte array as called for in J1939 data
        long2LittleEndian(J1939_NAME, packet.data, 0, 8);

        sendPacket(packet);

        // we must wait 250ms before continuing to give time for others to object
        if (address != J1939_ADDRESS_NULL) {
            attemptingAddress = address;
            if (mainHandler != null) { // we're not trying to call this before we called start on the I/O
                mainHandler.postDelayed(addressClaimAttemptSuccessTask, ADDRESS_CLAIMWAIT_WINDOW_MS);
            }
        }


    } // sendClaimedAddress()


    ///////////////////////////////////////////////////////////////
    // abortClaimAttempt()
    //  if we have a claim that we are attempting it is no longer valid
    ///////////////////////////////////////////////////////////////
    private void abortClaimAttempt() {

        if (attemptingAddress == J1939_ADDRESS_NULL) return; // no attempt in progress

        Log.d(TAG, "Aborting outstanding attempt on address " + attemptingAddress);

        attemptingAddress = J1939_ADDRESS_NULL;

        if (mainHandler != null) { // we're not trying to call this before we called start on the I/O
            mainHandler.removeCallbacks(addressClaimAttemptSuccessTask);
        }

        addressClaimAttempted = true; // remember we attempted a claim

    } // abortClaimAttempt()



    ///////////////////////////////////////////////////////////////
    // addressClaimAttemptSuccessTask()
    //  Timer that waits after we broadcast our claimed address to allow others to object
    ///////////////////////////////////////////////////////////////
    private Runnable addressClaimAttemptSuccessTask = new Runnable() {

        @Override
        public void run() {
            try {
                // nobody has objected, so it's ours!!!

                addressClaimAttempted = true; // remember we attempted a claim
                myAddress = attemptingAddress;
                attemptingAddress = J1939_ADDRESS_NULL;

                // remember our address for next time
                engine.service.state.writeState(engine.service.state.J1939_BUS_ADDRESS, myAddress);


                // TODO: Start sending and receiving data as needed.
                setBusStatus(BUS_STATUS_UP);
                Log.i(TAG, "Claimed Address " + myAddress);
            } catch(Exception e) {
                Log.e(TAG + ".addressClaimAttemptSuccessTask", "Exception: " + e.toString(), e);
            }
        }
    }; // addressClaimAttemptSuccessTask()


    ///////////////////////////////////////////////////////////////
    // addressNullDelayTask()
    //  Timer expires after psuedo-random time and sends the cannot claim (null) address packet
    ///////////////////////////////////////////////////////////////
    private Runnable addressNullDelayTask = new Runnable() {

        @Override
        public void run() {
            try {
                // OK, time has expired, we can send the null response now
                sendClaimedAddress(myAddress); // we know this will be the NULL address unless something has recently changed

            } catch(Exception e) {
                Log.e(TAG + ".addressNullDelayTask", "Exception: " + e.toString(), e);
            }
        }
    }; // addressNullDelayTask()

    /////////////////////////////////////////////////////////////////
    // compareNamePriority()
    //  compares two names. The name with the lower value has the higher priorty
    //  returns:
    //      1 if name1 has less priority than name2
    //      0 if name1 = name 2
    //      -1 if name2 has more priority than name1
    /////////////////////////////////////////////////////////////////
    private int compareNamePriority(long name1, long name2) {
        if (name1 == name2) return 0;

        long n1shift, n2shift;

        // compare as an unsigned number
        // 0 bits have higher prioirity than one buts

        n1shift = name1 >>> 1;
        n2shift = name2 >>> 1;

        if (n1shift < n2shift) return 1;
        if (n1shift > n2shift) return -1;


        // they are equal, compare lowest bit
        n1shift = name1 & 1;
        n2shift = name2 & 1;

        if (n1shift < n2shift) return 1;
        if (n1shift > n2shift) return -1;

        return 0; // they are equal (shouldn't ever get here)
    } // compareNamePriority()




    ////////////////////////////////////////////////////////////////
    // receiveRequestAddress()
    //  we received a request for our name/address, if we have an address, then send it
    //
    ////////////////////////////////////////////////////////////////
    private void receiveRequestAddress() {

        // even if we have no address (myAddress is J1939_ADDRESS_NULL), we should respond with our address
        // however, in that case we must add a psuedo-random delay

        // if we are sending to the null address, then we must add a pseudo-random delay
        if (myAddress == J1939_ADDRESS_NULL) {

            if (!addressClaimAttempted) return; // we haven't attempted to claim, so we should not send anything.

            // we previously attempted to claim and failed

            Random r = new Random();
            int delay_ms = r.nextInt(ADDRESS_MAX_CANNOTCLAIM_DELAY_MS);

            if (mainHandler != null) { // we're not trying to call this before we called start on the I/O
                mainHandler.removeCallbacks(addressNullDelayTask);
                mainHandler.postDelayed(addressNullDelayTask, delay_ms);
                if (engine.service.isUnitTesting) {
                    // don't wait for the time-out, just perform it now
                    addressNullDelayTask.run();
                }

            }
            return;

        }

        // otherwise we can send this right away

        sendClaimedAddress(myAddress);

    } // receiveRequestAddress()


    ////////////////////////////////////////////////////////////////
    // receiveClaimedAddress()
    //  remember who is at this address, and make sure nobody is claiming our address
    ////////////////////////////////////////////////////////////////
    private void receiveClaimedAddress(int address, long name) {


        // we only care if this is using our address or an address we are attempting to claim
        if ((address != myAddress) &&
            (address != attemptingAddress))
            return;

        if (address == J1939_ADDRESS_NULL) return; // this was sent from the null address, ignore

        // oops somebody is claiming our address!!
        if (compareNamePriority(J1939_NAME, name) == 1) {
            // We have priority -- take it back now
            Log.d(TAG, "J1939 address battle won (" + String.format("x%X", J1939_NAME) + " vs " + String.format("x%X", name) +") -- re-asserting dominance.");
            sendClaimedAddress(myAddress);
        } else {
            // somebody took our address! we need to claim a new one now
            Log.i(TAG, "J1939 address battle lost (" + String.format("x%X", J1939_NAME) + " vs " + String.format("x%X", name) + ") -- we surrender.");
                    ceasePacketsInProgress(); // stop any transmissions we can
            abortClaimAttempt(); // abort any outstanding attempts
            startClaimingAddress(J1939_ADDRESS_NULL); // we have no preferred address
        }
    } //receiveClaimedAddress()



    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // Transport Protocol & Connection Management
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////


    // Max CAN frame size is 8 bytes, hence the need for Transport protocol

    public static final int TP_CM_RTS = 16; // Request
    public static final int TP_CM_CTS = 17; // Clear
    public static final int TP_CM_EOM = 19; // end of message ACK
    public static final int TP_CM_ABORT = 255;
    public static final int TP_CM_BAM = 32; // broadcast announce


    // TP Connection abort reasons per J1939
    public static final int TP_ABORT_MAXCONNECTIONS =1;
    public static final int TP_ABORT_NOSYSTEMRESOURCES =2;
    public static final int TP_ABORT_TIMEOUT =3;

    class TpConnection {
        int pgn;
        int expected_bytes;
        int expected_packets; //if this is set to 0 then the connection is "closed"
        int max_packets_per_burst;
        int source_address; // the remote address that sourced the connection
        int destination_address; // us (or the global address)
        long timeout_elapsedms; // time that this connection will expire in elapsed ms
        byte[] data;
    } // class

    public static final int MAX_TP_CONNECTIONS = 5; // maximum # of simultaneous connections
            // note: we may have a couple nodes sending multiple DTCs, and a couple nodes sending VIN responses at the same time
    public static final int MAX_TP_FRAMES_PER_BURST = 1; // maximum number of frames we will accept in each burst


    public static final int TP_TIMEOUT_MS = 1250; // 1250 ms is the maximum timout (T2 and T3)

    // this is the list of pgns that we will accept TP connections for:
    static final int TP_PGNS[] = new int[] {PGN_VIN, PGN_FAULT_DM1};

    TpConnection connections[] = new TpConnection[MAX_TP_CONNECTIONS]; // list of open connections



    ///////////////////////////////////////////////////////////////////
    // receiveConnectionManage()
    //  get a connection management message .. these can come in response to VIN requests, etc.
    ///////////////////////////////////////////////////////////////////
    public void receiveConnectionManage(int source_address, int destination_address, byte[] data) {

        // really, we are only accepting the RTS and BAM since we aren't sending any large data over the bus


        if ((data[0] != (byte) TP_CM_RTS) &&
            (data[0] != (byte) TP_CM_BAM)) {
        //    Log.e(TAG, "Rejected first byte " + String.format("%X", data[0]) + " not appropriate");
            return; // ignore the message
        }

        // [1 ..2] = number of bytes
        // [3] = Total packets
        // [4] = Max packets per CTS , FF = no limit
        // [5] = PGN (Little Endian)

        boolean send_response = false;

        // send a response if this is an RTS directed to our address

        if ((destination_address == myAddress) &&
            (data[0] == (byte) TP_CM_RTS))
             send_response= true;



        // Determine if we want to accept this connection.


        // We're only accepting certain PGN
        int pgn = (int) littleEndian2Long(data, 5, 3);

        int i;
        for (i =0 ; i < TP_PGNS.length; i++) {
            if (TP_PGNS[i] == pgn) break;
        }

        if (i == TP_PGNS.length) {
            // refuse this connection
            // this may have been sent to global, in which case we do not respond
        //    Log.e(TAG, "Unlisted connection PGN x" + String.format("%X", pgn) + ", connection discarded");

            if (send_response) {
                sendConnectAbort(source_address, pgn, (byte) TP_ABORT_NOSYSTEMRESOURCES); // "No system resources"
            }
            return;
        }



        // Do we have an existing connection with this source?
        int connection_i;

        connection_i = findOpenConnection(source_address, destination_address);
        if (connection_i < 0) {
            // No open connections, find an available ID for the new connection
            for (connection_i=0 ; connection_i< MAX_TP_CONNECTIONS; connection_i++) {
                if (isConnectionIdAvailable(i)) break;
            }
        }


        if (connection_i == MAX_TP_CONNECTIONS) {
            // We have reached our maximum number of simultaneous connections
            Log.e(TAG, "Max open connections " + MAX_TP_CONNECTIONS + " reached, connection discarded");
            if (send_response) { // only send if this wasn't a broadcast
                sendConnectAbort(source_address, pgn, (byte) TP_ABORT_MAXCONNECTIONS); // "max connections reached"
            }
            return;
        }



        // OK, accept the connection
        TpConnection tp = connections[connection_i];
        tp.pgn = pgn;
        tp.expected_bytes = ((((int) data[2]) & 0xFF) << 8) | (data[1] & 0xFF);
        tp.expected_packets = ((int) data[3]) & 0xFF;
        tp.max_packets_per_burst = ((((int) data[4]) & 0xFF) > MAX_TP_FRAMES_PER_BURST ? MAX_TP_FRAMES_PER_BURST : ((int) data[4]) & 0xFF);
        tp.source_address = source_address;
        tp.destination_address = destination_address;
        tp.timeout_elapsedms = SystemClock.elapsedRealtime() + TP_TIMEOUT_MS;

        tp.data = new byte[tp.expected_bytes]; // create the array
        java.util.Arrays.fill(tp.data, (byte) 0xFF);

        Log.v(TAG, "accept <-- " +
                String.format("%02X to %02X (%04X)",
                        tp.source_address,
                        tp.destination_address,
                        tp.pgn) +
                " expect " + tp.expected_packets + " packets " + tp.expected_bytes + " bytes"

        );

        // remember this connection
        if (send_response) {
            sendConnectCTS(source_address, pgn, tp.max_packets_per_burst, 1);
        }

    } // receiveConnectionManage()

    ///////////////////////////////////////////////////////////////////
    // receiveConnectionData()
    //  receives the data through a connection
    ///////////////////////////////////////////////////////////////////
    public void receiveConnectionData(int source_address, int destination_address, byte[] data) {

        // Data:
        //  [0] = sequence number
        //  [1..7] = the actual data

        int connectionID = findOpenConnection(source_address, destination_address);

        if (connectionID < 0) {
            // no open connection found -- perhaps something went wrong here
            return; // just ignore this and let the other side time-out
        }

        // open connection found, update the time-out
        connections[connectionID].timeout_elapsedms = SystemClock.elapsedRealtime() + TP_TIMEOUT_MS;


        boolean send_response = false; // this may be a broadcast, in which case we shouldn't respond
        if (connections[connectionID].destination_address == myAddress)
            send_response = true; // this was sent to us, we should respond

        if (data[0] == 0) {
            // Problem. bad Sequence number
            Log.e(TAG, "Bad Sequence number on TP DATA packet = " + ((int) data[0]));
            return;
        }

        // copy the data from this frame to the data array
        int starting_byte = (((int)data[0]-1) & 0xFF) * 7;
        for (int i=0; i < 7; i++) { // there are always 7 bytes in the frame
            if ((starting_byte+i) < connections[connectionID].expected_bytes)
                connections[connectionID].data[starting_byte+i] = data[1+i];
        }

        // Have we received all the data ? If so then send EOM, otherwise send CTS
        if ( (((int) data[0]) & 0xFF) >= connections[connectionID].expected_packets) {
            // last packet

            // Do whatever we want to do with the data
            processConnectionData(connectionID);

            // Tell the remote to close connection. (Do this after processing so it doesn't open a new one and overwrite)

            int pgn = connections[connectionID].pgn;
            int expected_bytes = connections[connectionID].expected_bytes;
            int expected_packets = connections[connectionID].expected_packets;

            removeConnection(connectionID);

            if (send_response) {
                sendConnectEOM(source_address, pgn, expected_bytes, expected_packets);
            }


        } else {
            // more packets to come
            if (send_response) {
                sendConnectCTS(source_address,
                        connections[connectionID].pgn,
                        connections[connectionID].max_packets_per_burst,
                        ((((int) data[0]) & 0xFF) + 1) // current packet number
                );
            }
        }

    } // receiveConnectionData()


    ///////////////////////////////////////////////////////////////////
    // findOpenConnection()
    //  returns the index of an open connection if one exists.
    //  returns -1 if no open connection exists
    ///////////////////////////////////////////////////////////////////
    int findOpenConnection(int source_address, int destination_address) {
        for (int i=0 ; i< MAX_TP_CONNECTIONS; i++) {
            if ((connections[i].source_address == source_address) &&
                    (connections[i].destination_address == destination_address) &&
                    (connections[i].expected_packets != 0))  {
                return i; // index of the open connection
            }
        }

        return -1; // no open connection ID
    } // findOpenConnection()



    boolean isConnectionIdAvailable(int connectionID) {
        if (connections[connectionID].expected_packets == 0) return true;
        return false;
    } // isConnectionIdAvailable()


    ///////////////////////////////////////////////////////////////////
    // removeConnection()
    //  the connection is no longer open, remove it from our list.
    ///////////////////////////////////////////////////////////////////
    void removeConnection(int connectionID) {
        connections[connectionID].expected_packets = 0;
    } // removeConnection()


    void removeAllConnections() {
        for (int connectionID = 0; connectionID < MAX_TP_CONNECTIONS; connectionID++) {
            removeConnection(connectionID);
        }
    } // removeAllConnections


    public void sendConnectAbort(int to_address, int pgn, byte reason) {

        CanPacket packet = new CanPacket();
        packet.protocol_format = PF_CONNECTION_MANAGE;
        packet.destination_address = to_address;
        packet.source_address = myAddress;

        packet.data[0] = (byte) TP_CM_ABORT;
        packet.data[1] = reason; // Error reason , use generic "no system resources"

        // [5] .. 7] is the pgn
        long2LittleEndian(pgn, packet.data, 5, 3);

        sendPacket(packet);

    } // sendConnectAbort()

    public void sendConnectCTS(int to_address, int pgn, int max_packets, int first_packet) {

        CanPacket packet = new CanPacket();
        packet.protocol_format = PF_CONNECTION_MANAGE;
        packet.destination_address = to_address;
        packet.source_address = myAddress;

        packet.data[0] = TP_CM_CTS;
        packet.data[1] = (byte) max_packets;
        packet.data[2] = (byte) first_packet;

        // [5] .. 7] is the pgn
        long2LittleEndian(pgn, packet.data, 5, 3);

        sendPacket(packet);

    } // sendConnectCTS()


    public void sendConnectEOM(int to_address, int pgn, int total_bytes, int total_packets) {

        CanPacket packet = new CanPacket();
        packet.protocol_format = PF_CONNECTION_MANAGE;
        packet.destination_address = to_address;
        packet.source_address = myAddress;

        packet.data[0] = TP_CM_EOM;
        packet.data[1] = (byte) (total_bytes & 0xFF);
        packet.data[2] = (byte) ((total_bytes >> 8) & 0xFF);
        packet.data[3] = (byte) total_packets;

        // [5] .. 7] is the pgn
        long2LittleEndian(pgn, packet.data, 5, 3);

        sendPacket(packet);

    } // sendConnectEOM()


    ///////////////////////////////////////////////////////////////
    // purgeOldConnections()
    //  remove any connections that have timedout -- call this periodically
    ///////////////////////////////////////////////////////////////
    void purgeOldConnections() {
        long now = SystemClock.elapsedRealtime();

        // check if any of our connections need to be timed-out
        for (int connectionID = 0 ;connectionID < MAX_TP_CONNECTIONS; connectionID++) {
            if (!isConnectionIdAvailable(connectionID)) {
                // if it is not available, that means it is in use
                if (connections[connectionID].timeout_elapsedms < now) {
                    // we should time this bad boy out

                    sendConnectAbort(connections[connectionID].source_address, connections[connectionID].pgn, (byte) TP_ABORT_TIMEOUT);
                }
            }
        }
    } // purgeOldConnections()




    ///////////////////////////////////////////////////////////////
    // checkConnectionTOTask()
    //  executes periodically to see if any open connections have timed out and purges them
    ///////////////////////////////////////////////////////////////
    Runnable checkConnectionTOTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.vv(TAG, "connectionTOTask()");
                purgeOldConnections();
            } catch(Exception e) {
                Log.e(TAG + ".connectionTOTask", "Exception: " + e.toString(), e);
            }
            if (mainHandler != null)
                mainHandler.postDelayed(checkConnectionTOTask, CONNECTION_TO_CHECK_MS);
            Log.vv(TAG, "connectionTOTask() END");
        }
    }; // checkConnectionTOTask()


    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // Basic PGN codec
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////




    ////////////////////////////////////////////////////////////////
    // sendRequestPGN()
    //  sends a request for a PGN to global address
    ////////////////////////////////////////////////////////////////
    private void sendRequestPGN(int pgn) {


        if (myAddress == J1939_ADDRESS_NULL) return; // we can only send a message if we have an address

        CanPacket packet = new CanPacket();
        packet.protocol_format = PF_REQUEST;
        packet.destination_address =  J1939_ADDRESS_GLOBAL;
        packet.source_address = myAddress;

        //pgn = PGN_VIN;

        long2LittleEndian(pgn, packet.data, 0, 3);

        //packet.data = new byte[] {(byte) 0xBE, (byte) 0xFE, 0};


        sendPacketWithLength(packet, 3);

    } // sendRequestPGN



    ////////////////////////////////////////////////////////////////
    // parsePGN()
    //  parses a received message for the PGN
    ////////////////////////////////////////////////////////////////
    void parsePGN(int pgn, byte[] data, int data_length) {


        int i;
        long l;

        switch (pgn) {
            case PGN_VIN:
                String s = new String(data, 0 , data_length); // byte_array, offset, length

                // TODO: This might have an asterisk at the end of it that needs to be removed ??

                checkVin(s);
                break;
            case PGN_FAULT_DM1:

                // bytes 1 and 2 are lamp status
                // bytes 3+ are for DTCs (4 bytes each)
                //  DTCs may be all 0s or all 1s to indicate a NULL value

                startCollectingDtcs(); // if we are not yet collecting, this starts the timer

                Dtc dtc;

                for (i = 2; i < data_length; i +=4) {

                    if (i+4 > data_length) {
                        //Log.e(TAG, "DM1 message length is wrong! (" + data_length + " bytes)");
                        // This can just mean we reached the end of the message
                        break; // ignore last bytes
                    }

                    dtc = parseTroubleCode(data, i);
                    if (dtc != null) {
                        addDtc(dtc); // add the DTC to a temp list
                    } else {
                        // not a valid code, this indicates the end of the list.
                        break;
                    }
                }

                break;
            case PGN_ODOMETER_HIRES:
                //  Bytes 1- 4 : vehicle distance
                //  Bytes 5- 8 : trip distance
                // 5 meters per bit

                l = littleEndian2Long(data, 0, 4);
                if (l == 0xFFFFFFFFL) break; // "Unknown"

                checkOdometer(ODOMETER_TYPE_HIRES, l * 5);

                break;
            case PGN_ODOMETER_LORES:
                //  Note byte arrangement is opposite that of hi-res
                // Bytes 1- 4: trip distance
                // Bytes 5 -8: vehicle distance
                // 0.125 km per bit
                l = littleEndian2Long(data, 4, 4);
                if (l == 0xFFFFFFFFL) break; // "Unknown"
                checkOdometer(ODOMETER_TYPE_LORES, l * 125);
                break;
            case PGN_FUEL_CONSUMPTION:
                // Bytes 1-4 = Trip fuel
                // Bytes 5-8 = Total fuel
                //  0.5 L per bit (this is liquid fuel, nat gas is different)
                l = littleEndian2Long(data, 4, 4);
                if (l == 0xFFFFFFFFL) break; // "Unknown"
                checkFuelConsumption(l * 500);

                break;
            case PGN_FUEL_ECONOMY:
                // Bytes 1-2 = Fuel Rate
                //  bytes 3-4 = Instant Fuel Economy
                // bytes 5-6 = Average Fuel Economy
                // Bytes 7 = Throttle posisiotn

                // 1/512 km/L per bit
                // put this in meters
                l = littleEndian2Long(data, 4, 2);
                if (l == 0xFFFF) break; // "Unknown"
                checkFuelEconomy(l * 1000 / 512);
                break;
            case PGN_PARKING: //Cruise Control + Vehicle Speed
                // Byte 1 : Measure_SW1 bits 2,1 : two speed axle switch
                //                      bits 4,3 : parking brake switch  00 = off 01 = on
                //                      bites 8-5: not defined
                // Bytes 2-8 : various cruise control, idling, etc..

                // This reports the value of the parking brake switch, not the actuator

                //if ((data[0] & 0x0C) > 0) {
                if ((data[0] & 0x0C) == 0x04) {
                    checkParkingBrake(true);
                } else if  ((data[0] & 0x0C) == 0) {
                    checkParkingBrake(false);
                } else {
                    // Don't do anything.
                    // the data is not valid or is being reported as "unknown" 0x0C like from a module that doesn't know this info
                }

                break;
            case PGN_GEAR: // ETC2 message
                // Byte 1 : selected gear
                // Byte 2-3: Actual gear ratio
                // byte 4: current gear
                // byte 5-6 transmission requested range
                // byte 7-8 transmission actual range


                // Gear:
                //      251 = Park
                //      125 = Neutral
                //      > 125 is forward gears
                //      < 125 is reverse gears


                if (data[3] == -1) break; // "Unknown"

                if ((data[3] < 125) && (data[3] > 0)) {
                    checkReverseGear(true);
                } else {
                    // not a reverse gear
                    checkReverseGear(false);
                }

                break;
        } // switch


    } // parsePGN()


    ///////////////////////////////////////////////////////////////////
    // processConnectionData()
    //  the data in the connection is fully received, go ahead and process it
    // Parameters
    //  connectionID: ID of the connection that is containing data
    ///////////////////////////////////////////////////////////////////
    void processConnectionData(int connectionID) {

        Log.v(TAG,
                "data <-- " +
                        String.format("%02X to %02X (%04X)",
                                connections[connectionID].source_address,
                                connections[connectionID].destination_address,
                                connections[connectionID].pgn) +
                        " : " +
                        Log.bytesToHex(connections[connectionID].data, connections[connectionID].expected_bytes)

        );

        // check if we need to forward "raw" data to somewhere else
        //engine.checkRawForwarding(Engine.BUS_TYPE_J1939, connections[connectionID].pgn, connections[connectionID].source_address, connections[connectionID].data);
        parsePGN(connections[connectionID].pgn, connections[connectionID].data, connections[connectionID].expected_bytes);

    } // processConnectionData



    ////////////////////////////////////////////////////////////////
    //  parseTroubleCode
    //      parses out a trouble code
    //  returns:
    //      0 if the DTC is invalid (not a DTC)
    //      otherwise, returns the 4 byte DTC
    //      DTC value result is (MSB to LSB ): CM FMI/SPN SPN SPN
    ////////////////////////////////////////////////////////////////
    Dtc parseTroubleCode(byte[] data, int startpos) {

        // J1939 Trouble codes are 4 bytes long, and are parsed differently depending on their format specifier
        // Some format specifiers require more info about make+model of vehicle for complete parsing.


        if (data.length < startpos + 4) return null; // safety: invalid (not enough data)

        // If the bytes are all x00 or all xFF, then this is not a valid code.

        if ((data[0+startpos] == 0) &&
            (data[1+startpos] == 0) &&
            (data[2+startpos] == 0) &&
            (data[3+startpos] == 0)) {
            // Invalid Code
            return null;
        }

        if ((data[0+startpos] == (byte) 0xFF) &&
                (data[1+startpos] ==  (byte) 0xFF) &&
                (data[2+startpos] == (byte) 0xFF) &&
                (data[3+startpos] == (byte) 0xFF)) {
            // Invalid Code
            return null;
        }



        int spn, fmi, oc, cm; // suspect parameter num, failure mode indicator, occurrence count, conversion mode
        cm = (((data[3+startpos] & 0x80) > 0) ? 1 : 0);

        // cm is needed because in 1996, this protocol did not adequately specify how to decode the spn, and
        //  thus different manufacturers implemented different methods. cm bit was reserved at the time and = 1

        fmi = (data[2+startpos] & 0x1F);
        oc = (data[3+startpos] & 0x7F);

        if (cm == 0) {
            // Standard conversion format (version 4)
            spn = (data[2+startpos] & 0xE0) << 16;
            spn |= (data[1+startpos] << 8);
            spn |= (data[0+startpos]);

        } else {
            // Conversion format version 1, 2, or 3
            // SPN can't be determined without further information
        }


        Dtc dtc = new Dtc();


        // Result is MSB to LSB : CM FMI/SPN SPN SPN

        long res;

        res = 0;
        res |= (data[3+startpos] & 0x80); // high bit only give the conversion method
        res <<= 8;
        res |= (data[2+startpos] & 0xFF);
        res <<= 8;
        res |= (data[1+startpos] & 0xFF);
        res <<= 8;
        res |= (data[0+startpos] & 0xFF);

        dtc.dtc_value = res;
        dtc.occurence_count = oc;

        return dtc;

    } // parseTroubleCode




    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // CAN bus control
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////





    ///////////////////////////////////////////////////////////////////
    // ceasePacketsInProgress()
    //  stop attempting to send any packets in progress (maybe our address changed)
    ///////////////////////////////////////////////////////////////////
    private void ceasePacketsInProgress() {


        // kill any transmits in the CAN queue (must happen within 50 ms)
        abortCanTransmits();

        // kill any connections opened by the transport protocol (do not send abort .. let these timeout)
        removeAllConnections();

    } // ceasePacketsInProgress()



    //////////////////////////////////////////////////////////////////
    // packet2frame()
    //  convert from J1939 packet to a CAN frame
    //////////////////////////////////////////////////////////////////
    CanFrame  packet2frame(CanPacket packet, int length) {


        // A J1939 CAN 29b frame ID looks like:
        // PRI(3) RSRV(1) DP(1) PF(8) PS(8) SA(8)

        int frameId = packet.priority;
        frameId <<= 1;
        frameId |= 0; // reserved
        frameId <<= 1;
        frameId |= 0; // Data Page
        frameId <<= 8;
        frameId |= packet.protocol_format;
        frameId <<= 8;
        frameId |= packet.destination_address; // or protocol-specific for some formats
        frameId <<= 8;
        frameId |= packet.source_address;

        CanFrame frame = new CanFrame();
        frame.id = frameId;
        frame.data = Arrays.copyOf(packet.data, length);

        return frame;
    } // packet2frame()


    //////////////////////////////////////////////////////////////////
    // frame2packet()
    //  convert from CAN frame to a J1939 packet
    //////////////////////////////////////////////////////////////////
    CanPacket frame2packet(CanFrame frame) {
        CanPacket packet = new CanPacket();


        int frameId = frame.id;

        // A J1939 CAN 29b frame ID looks like:
        // PRI(3) RSRV(1) DP(1) PF(8) PS(8) SA(8)

        packet.source_address = (frameId & 0xFF);
        frameId >>>= 8;
        packet.destination_address = (frameId & 0xFF);
        frameId >>>= 8;
        packet.protocol_format = (frameId & 0xFF);
        frameId >>>= 8;
        // Data Page
        frameId >>>= 1;
        // Reserved
        frameId >>>= 1;
        packet.priority = (frameId & 0x7);


        packet.data = frame.data;

        return packet;
    } //frame2packet()




    /////////////////////////////////////////////////////////
    // sendPacket()
    //  sends a packet onto the CAN bus
    /////////////////////////////////////////////////////////

    private void sendPacket(CanPacket packet) {
        sendPacketWithLength(packet, 8);
    }

    private void sendPacketWithLength(CanPacket packet, int dlc_length) {

        Log.vv(TAG, "packet --> " + String.format("%02x %02x %02x", packet.protocol_format, packet.destination_address, packet.source_address) +
                " : " +
                Log.bytesToHex(packet.data, dlc_length));

        //Log.vv(TAG, "SendPacket()");

        CanFrame frame = packet2frame(packet, dlc_length);
        broadcastTx(frame);
        //can.sendFrame(frame);
        Log.vv(TAG, "SendPacket() END");

    } // sendPacket()


    /////////////////////////////////////////////////////////
    // receivePacket()
    //  receives a packet from the CAN bus
    //  Returns:
    //      0 : did not recognize packet
    //      1 : Parsed a PGN
    //      2 : Parsed a control packet
    /////////////////////////////////////////////////////////
    int receivePacket(CanPacket packet) {



        markDiscovered(); // no matter what we are getting valid packets on this bus.

        setRecentRxReceived();

        // was this sent by somebody claiming to be me ?

        if ((myAddress != J1939_ADDRESS_NULL) &&
            (packet.source_address == myAddress)) {

            // was this me ? if not then this might be an imposter!!!!
            // we will say we have this address and if somebody objects they can respond accordingly.
            // Is ECHO On?

            // It's ok to get address claims from somebody else claiming this address


            //Log.w(TAG, "J1939 Address imposter detected");

            Log.v(TAG, "packet <-- " + String.format("%02x %02x %02x", packet.protocol_format, packet.destination_address, packet.source_address) + " : " +
                    Log.bytesToHex(packet.data, 8) +
                    " Address Imposter Detected!");

            if (packet.protocol_format != PF_CLAIMED_ADDRESS) {
                // this is a normal packet, not somebody telling us who we are
                sendClaimedAddress(myAddress);
                return PARSED_PACKET_TYPE_NONE;
            }
            // if this is somebody else telling us who they are, then we fall through and process the claimed address packet
            //  to determine who wins the battle

        }


        markAddressInUse(packet.source_address); // remember that somebody on network thinks they have this address

        // If this wasn't sent to anybody, it can be ignored
        // Well, destinatino address could be group extension which could be 0
        //if (packet.destination_address == J1939_ADDRESS_NULL) return;

        // was this sent to either me or global?

        if ((packet.protocol_format < 240) && // protocol formats less than 240 have a destination address (otherwise it is part of PGN)
            (packet.destination_address != myAddress) &&
           (packet.destination_address != J1939_ADDRESS_GLOBAL)) {

            return PARSED_PACKET_TYPE_NONE; // we can ignore it
        }

        // it was sent to me or sent globally, let's process it

        Log.vv(TAG, "packet <-- " + String.format("%02x %02x %02x", packet.protocol_format, packet.destination_address, packet.source_address) + " : " +
                Log.bytesToHex(packet.data, 8));


        int pgn = packet.getPGN();

        engine.checkRawForwarding(Engine.BUS_TYPE_J1939, pgn, packet.source_address, packet.data);


        // is this a request for our address
        switch (packet.protocol_format) {
            case PF_REQUEST:
                // this is a request for data
                if ((packet.data[0] == 0) &&
                    (packet.data[1] == (byte) PF_CLAIMED_ADDRESS) &&
                    (packet.data[2] == (byte) 0)) {
                    // this is a request for our address+name
                    receiveRequestAddress();
                    return PARSED_PACKET_TYPE_CONTROL; // parsed a control packet
                }
                break;
            case PF_CLAIMED_ADDRESS:
                // this is a claim to an address
                long name = littleEndian2Long(packet.data, 0, 8);
                receiveClaimedAddress(packet.source_address, name);
                return PARSED_PACKET_TYPE_CONTROL; // parsed a control packet
            case PF_CONNECTION_MANAGE:
                // this is a request for a connection
                receiveConnectionManage(packet.source_address, packet.destination_address, packet.data);
                return PARSED_PACKET_TYPE_CONTROL; // parsed a control packet
            case PF_CONNECTION_DATA:
                // this is data for a connection
                receiveConnectionData(packet.source_address, packet.destination_address, packet.data);
                return PARSED_PACKET_TYPE_CONTROL; // parsed a control packet

        } // switch PF


        switch (pgn) {
            // place specific PGNs here that are not multipacket PGNs and which should be parsed
            case PGN_FAULT_DM1:
            case PGN_ODOMETER_HIRES:
            case PGN_ODOMETER_LORES:
            case PGN_FUEL_CONSUMPTION:
            case PGN_FUEL_ECONOMY:
            case PGN_PARKING: //Cruise Control + Vehicle Speed
            case PGN_GEAR: // ETC2 message

                parsePGN(pgn, packet.data, 8);
                return PARSED_PACKET_TYPE_PGN; // parsed a PGN packet
        }


        return PARSED_PACKET_TYPE_NONE; // unrecognized packet

    } // receivePacket()



    int receiveCANFrame(CanFrame frame) {

        CanPacket packet = frame2packet(frame);
        return receivePacket(packet);

    } // receiveCANFrame()



    /*

    void startCanWriting() {
        can.startWriting()
    }


    void abortCanTransmits() {
        can.abortTransmits();
    }

    void startCanBus(int bitrate, boolean listenOnly, int[] ids, int[] masks) {
            can.start(bitrate, listenOnly,//     .....   canHardwareFilters);
    }

    void stopCanBus() {
        if (can != null) {
            can.stop();
        }
    }

    void setupCanBus(int[] additional_pgns) {
        can = new CAN(engine.service.isUnitTesting);

        can.setReceiveCallbacks(mainHandler, frameAvailableCallback, busReadyRxCallback, busReadyTxCallback);

        // Create the hardware filters we will put on this interface
        if ((additionalPGNs != null) && (additionalPGNs.length > 0)) {
            canHardwareFilters = new CanbusHardwareFilter[3];

            // add additional filters for user-requested PGNs
            int[] adjusted_hw_pgns = new int[additionalPGNs.length];

            for (int i = 0; i < adjusted_hw_pgns.length; i++) {
                adjusted_hw_pgns[0] = additionalPGNs[0] << 8; // convert from PGN to HW Frame format
            }
            canHardwareFilters[2]= new CanbusHardwareFilter(adjusted_hw_pgns, HW_RECEIVE_PGN_MASK, CanbusFrameType.EXTENDED);
        } else {
            canHardwareFilters = new CanbusHardwareFilter[2]; // only two filters
        }

        // These filters remain the same no matter what:
        canHardwareFilters[0] = new CanbusHardwareFilter(HW_RECEIVE_PFS, HW_RECEIVE_PF_MASK, CanbusFrameType.EXTENDED);
        canHardwareFilters[1] = new CanbusHardwareFilter(HW_RECEIVE_PGNS, HW_RECEIVE_PGN_MASK, CanbusFrameType.EXTENDED);

    }



    ///////////////////////////////////////////////////////////////
    // receiveAllFrames()
    //  takes available frames from the CAN class one by one and processes them
    ///////////////////////////////////////////////////////////////
    void receiveAllFrames() {
        // is there something in the queue to be received?
        CanbusFrame frame;
        do {
            frame = can.receiveFrame();
            receiveCANFrame(frame.getId(), frame.getData());
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
                Log.v(TAG, "busReadyTxCallback()");

                // Note this can be called many times during discovery when the bus is changed.

                if (discoveryStage == DISCOVERY_STAGE_OFF) {
                    // We are done with discovery, try to claim an address
                    startClaimingAddress(last_known_bus_address);
                }
                Log.v(TAG, "busReadyTxCallback() END");
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

    ///////////////////////////////////////////////////////////////
    // busReadyTxCallback()
    //  This runnable will be posted by the CAN class whenever we are ready to write on the bus
    ///////////////////////////////////////////////////////////////
    void busReadyTxCallback() {

        if (!busTxIsReady) {
            Log.v(TAG, "received TX Ready signal");
            busTxIsReady = true;

            if (discoveryStage == DISCOVERY_STAGE_OFF) {
                // We are done with discovery, try to claim an address
                startClaimingAddress(last_known_bus_address);
            }
        }

    } // busReadyTxCallback()


    void abortCanTransmits() {
        // Intentionally Blank For Now
    }

    void startCanWriting() {
        // Writing is always enabled on bus for now
    }


    void startCanBus(int bitrate, boolean listenOnly, int[] ids, int[] masks) {
        Log.v(TAG, "Starting Vbus/CAN Service");
        //Log.v(TAG, "Filter[0] " + String.format("%X", ids[0]) + " " + String.format("%X", masks[0]));


        busTxIsReady = false;

        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(VehicleBusConstants.BROADCAST_CAN_RX);
            engine.service.context.registerReceiver(rxReceiver, intentFilter, VehicleBusConstants.PERMISSION_VBS_TXRX, null);
        } catch (Exception e) {
            Log.e(TAG, "Could not register CAN Rx receiver");
        }

        Intent serviceIntent = new Intent();
        serviceIntent.setPackage(VehicleBusConstants.PACKAGE_NAME_VBS);
        serviceIntent.setAction(VehicleBusConstants.SERVICE_ACTION_START);

        serviceIntent.putExtra("bus", "CAN");
        serviceIntent.putExtra("bitrate", bitrate);
        serviceIntent.putExtra("listenOnly", listenOnly);
        serviceIntent.putExtra("hardwareFilterIds", ids);
        serviceIntent.putExtra("hardwareFilterMasks", masks);

        engine.service.context.startService(serviceIntent);

    } // startCANBus()

    void stopCanBus() {
        Log.v(TAG, "Stopping Vbus/CAN Service");



        try {
            engine.service.context.unregisterReceiver(rxReceiver);
        } catch(Exception e) {
            // don't do anything
        }


        Intent serviceIntent = new Intent();
        serviceIntent.setPackage(VehicleBusConstants.PACKAGE_NAME_VBS);
        serviceIntent.setAction(VehicleBusConstants.SERVICE_ACTION_STOP);
        serviceIntent.putExtra("bus", "CAN");

        engine.service.context.startService(serviceIntent);

        busTxIsReady = false;
    } // stopCANBus()

    void setupCanBus(int[] additionalPGNs) {


        // find total number of filters we need

        int total_count = HW_RECEIVE_PFS.length + HW_RECEIVE_PGNS.length;

        if ((additionalPGNs != null) && (additionalPGNs.length > 0)) {
            total_count += additionalPGNs.length; // we have user-defined ones too
        }

        canFilterIds = new int[total_count];
        canFilterMasks = new int[total_count];

        int count_i = 0;
        // first add system defined ones
        for (int pf : HW_RECEIVE_PFS) {
            if (count_i < total_count) { // safety
                canFilterIds[count_i] = pf;
                canFilterMasks[count_i] = HW_RECEIVE_PF_MASK;
                count_i++;
            }
        }

        for (int pgn : HW_RECEIVE_PGNS) {
            if (count_i < total_count) { // safety
                canFilterIds[count_i] = pgn;
                canFilterMasks[count_i] = HW_RECEIVE_PGN_MASK;
                count_i++;
            }
        }

        // add user defined ones
        if ((additionalPGNs !=null) && (additionalPGNs.length > 0)) {
            for (int pgn : additionalPGNs) {
                if (count_i < total_count) { // safety
                    canFilterIds[count_i] = pgn << 8; // converts from pgn to HW Frame ID format
                    canFilterMasks[count_i] = HW_RECEIVE_PGN_MASK;
                    count_i++;
                }
            }
        }

    } // setupCanBus()

    List<CanFrame> outgoingList = Collections.synchronizedList(new ArrayList<CanFrame>());

    void broadcastTx(CanFrame frame) {

        if (engine.service.isUnitTesting) {
            // just place this in a list so we can test against it
            outgoingList.add(frame);
            return;
        }


        Intent ibroadcast = new Intent();
        ibroadcast.setPackage(VehicleBusConstants.PACKAGE_NAME_VBS);
        ibroadcast.setAction(VehicleBusConstants.BROADCAST_CAN_TX);

        //ibroadcast.putExtra("password", VehicleBusConstants.BROADCAST_PASSWORD);
        ibroadcast.putExtra("id", frame.id);
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
                    Log.e(TAG, "Received invalid CAN RX broadcast");
                    return;
                }
                */

                CanFrame frame = new CanFrame();

                frame.id = intent.getIntExtra("id", -1);
                frame.data = intent.getByteArrayExtra("data");

                receiveCANFrame(frame);
            } catch (Exception e) {
                Log.e(TAG, ".RxReceiver Exception : " + e.toString(), e);
            }
        }
    } // RxReceiver

} // class J1939

