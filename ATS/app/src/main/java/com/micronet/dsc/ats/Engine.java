    /////////////////////////////////////////////////////////////
// Engine:
//  Handles all engine diagnostics and communications with vehicle buses
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
import java.util.Iterator;
import java.util.List;

public class Engine {

    private static final String TAG = "ATS-Engine"; // for logging
    MainService service; // just a reference to the service context
    Handler mainHandler = null;

    boolean isStarted;
    boolean isEnabled; // this can be disabled in config
    String last_device_serial;

    J1939 j1939; // a SAE J1939 bus connection
    J1587 j1587; // a SAE J1587 bus connection


    public static final int BUS_TYPE_NONE = 0; // the default
    public static final int BUS_TYPE_J1939_250K = 1;
    public static final int BUS_TYPE_J1939_500K = 2;

    public static final int BUS_TYPE_J1939 = BUS_TYPE_J1939_250K; // for generic J1939, use the same ID as 250
    public static final int BUS_TYPE_J1587 = 4;

    // make sure there is a name for each bus
    public static final String[] BUS_NAMES = new String[] {
            "NONE",
            "J1939-250",
            "J1939-500",
            "N/A",
            "J1587"
    };
    public String getBusName(int bus_type) {
        if (bus_type >= BUS_NAMES.length) return "UNK";
        return BUS_NAMES[bus_type];
    }


    public static final int POLL_TIME_MS = 1000; // poll about once per second (not exact)



    String vin = new String("");
    int bus_type_vin = BUS_TYPE_NONE;


    class Status {
        // Bit fields that contain the status of various vehicle buses
        int buses_detected = 0; // what buses have we detected this power-cycle ?
        int buses_communicating = 0; // what buses are we currently communicating on ?

        boolean flagParkingBrake;
        boolean flagReverseGear;
        long odometer_m;
        long fuel_mL;
        long fuel_mperL;
    };

    Status status = new Status();


    int bus_type_parking_brake = BUS_TYPE_NONE; // which bus did this come from ?
    int bus_type_reverse_gear = BUS_TYPE_NONE; // which bus did this come from ?
    int bus_type_odometer_m = BUS_TYPE_NONE ; // which bus did this come from ?
    int bus_type_fuel_mL = BUS_TYPE_NONE ; // which bus did this come from ?
    int bus_type_fuel_mperL = BUS_TYPE_NONE ; // which bus did this come from ?


    public int ENGINE_DTC_REMOVAL_COUNT = 3; // how many times must a code not appear on bus before it is considered gone
                                             // this is needed to hedge against us losing communication
    public static class EngineDtc {
        long dtc_value; // The value that is sent to the server, meaning can depend on bus type and vehicle
        int bus_type = BUS_TYPE_NONE;
        int removal_count = 0; // if a code does not appear on the bus, this count is incremented until it is removed

        // For conversion between the structure and an array of bytes
        //  each item is 5 bytes (one byte for the bus and then the 4 byte DTC)
        static int DTC_ARRAY_ENTRY_SIZE = 5;
        static void convertToBytes(EngineDtc dtc, byte[] output, int start_index) {
            output[0 + start_index] = (byte) dtc.bus_type;
            output[1 + start_index] = (byte) (dtc.dtc_value & 0xFF);
            output[2 + start_index] = (byte) ((dtc.dtc_value >> 8) & 0xFF);
            output[3 + start_index] = (byte) ((dtc.dtc_value >> 16) & 0xFF);
            output[4 + start_index] = (byte) ((dtc.dtc_value >> 24) & 0xFF);

        } // convertToBytes

        static EngineDtc convertFromBytes(byte[] input, int start_index) {
            EngineDtc dtc = new EngineDtc();
            dtc.bus_type = (input[start_index] & 0xFF);
            dtc.dtc_value = (input[start_index + 4] & 0xFF);
            dtc.dtc_value <<= 8;
            dtc.dtc_value |= (input[start_index+3] & 0xFF);
            dtc.dtc_value <<= 8;
            dtc.dtc_value |= (input[start_index+2] & 0xFF);
            dtc.dtc_value <<= 8;
            dtc.dtc_value |= (input[start_index+1] & 0xFF);
            return dtc;
        } // convertFromBytes

    } // EngineDtc

    List<EngineDtc> current_dtcs = (List<EngineDtc>) new ArrayList<EngineDtc>();



    boolean warmStart = false;      // assume a cold start unless told otherwise
    int j1587_vinAttemptsRemaining= 0; // number of times we should attempt to get a VIN if we haven't gotten one yet
    int j1939_vinAttemptsRemaining= 0; // number of times we should attempt to get a VIN if we haven't gotten one yet

    public static final int NUM_COLD_START_VIN_ATTEMPTS = 3; // # of times to attempt after each cold start

    public Engine(MainService service) {
        this.service = service;

        status.flagReverseGear = service.state.readStateBool(State.FLAG_REVERSE_GEAR_STATUS);
        status.flagParkingBrake = service.state.readStateBool(State.FLAG_PARKING_BRAKE_STATUS);

        status.odometer_m = service.state.readStateLong(State.ACTUAL_ODOMETER);
        status.fuel_mL = service.state.readStateLong(State.FUEL_CONSUMPTION);
        status.fuel_mperL = service.state.readStateLong(State.FUEL_ECONOMY);


        vin = service.state.readStateString(State.STRING_VIN);


        warmStart = service.state.readStateBool(State.ENGINE_WARM_START);

        // Load the current DTCs
        current_dtcs.clear();

        byte[] dtc_array;
        dtc_array = service.state.readStateArray(State.ARRAY_FAULT_CODES);
        // dtc array is 5 bytes for each dtc: 1 byte bus followed by 4 byte dtc_value

        if (dtc_array != null) {
            int num_dtcs = dtc_array.length / EngineDtc.DTC_ARRAY_ENTRY_SIZE;
            EngineDtc dtc;
            for (int i = 0; i < num_dtcs; i++) {
                dtc = EngineDtc.convertFromBytes(dtc_array, i * EngineDtc.DTC_ARRAY_ENTRY_SIZE);
                //Log.d(TAG, "retrieved from state: DTC " + getBusName(dtc.bus_type) + " " + String.format("%08X", dtc.dtc_value));
                current_dtcs.add(dtc);
            }
        }

        // set initial values for isEnabled, which determines whether local broadcasts get sent for vehicle status
        boolean j1939_enabled = getConfigJ1939Enabled();
        boolean j1587_enabled = getConfigJ1587Enabled();
        if ((j1939_enabled) || (j1587_enabled)) {
            isEnabled = true;
        } else {
            isEnabled = false;
        }




    } // Engine()


    ////////////////////////////////////////////////////////////////////
    //  setWarmStart()
    //  set what will happen the next time start() is called
    //      cold:   will attempt to get the VIN, discover a J1939 bus, etc..
    //      warm:   will use the current VIN, current J1939 bus, and just attempt an address claim, etc..
    ////////////////////////////////////////////////////////////////////
    public void setWarmStart(boolean newWarmStart) {
        Log.v(TAG, "Warm starting set to " + newWarmStart);
        warmStart = newWarmStart;

        // remember we are cold starting
        service.state.writeState(State.ENGINE_WARM_START, (warmStart ? 1 : 0));

    } // setWarmStart()


    boolean getConfigJ1939Enabled() {
        String j1939_config = service.config.readParameterString(Config.SETTING_VEHICLECOMMUNICATION, Config.PARAMETER_VEHICLECOMMUNICATION_J1939_SPEED_KBS );
        boolean j1939_enabled = false;
        if (!j1939_config.toUpperCase().equals("OFF")) {
            j1939_enabled = true;
        }
        return j1939_enabled;
    }

    boolean getConfigJ1587Enabled() {
        String j1587_config = service.config.readParameterString(Config.SETTING_VEHICLECOMMUNICATION, Config.PARAMETER_VEHICLECOMMUNICATION_J1708_ENABLED);
        boolean j1587_enabled = false;
        if (!j1587_config.toUpperCase().equals("OFF")) {
            j1587_enabled = true;
        }
        return j1587_enabled;
    }


    ////////////////////////////////////////////////////////////////////
    // start()
    //      "Start" the Engine monitoring, called when app is started
    // Parameters:
    //  serial: a device serial number to incorporate into any node names (like J1939)
    //  coldStart: a cold start is the first start() since the device was powered (it may have changed vehicles in interim)
    ////////////////////////////////////////////////////////////////////
    public void start(String device_serial) {


        boolean j1939_enabled = getConfigJ1939Enabled();
        boolean j1587_enabled = getConfigJ1587Enabled();



        if ((!j1939_enabled) && (!j1587_enabled)) {
            Log.v(TAG, "All buses disabled in config");
            isEnabled = false;
            return;
        }

        isEnabled = true;


        Log.v(TAG, "start(" + device_serial + ") " + (warmStart ? "(warm)" :  "(cold)") + " for" +
                        (j1939_enabled ? " J1939": "") + (j1587_enabled ? " J1587": "")
        );

        last_device_serial = device_serial; // used if this needs to be restarted

        mainHandler  = new Handler(Looper.getMainLooper());

        if (warmStart) {
            j1939_vinAttemptsRemaining = 0; // do not attempt to re-get the VIN
            j1587_vinAttemptsRemaining = 0; // do not attempt to re-get the VIN
        } else {
            // we should try and get the VIN at the first opportunity
            j1939_vinAttemptsRemaining = NUM_COLD_START_VIN_ATTEMPTS;
            j1587_vinAttemptsRemaining = NUM_COLD_START_VIN_ATTEMPTS;
            bus_type_vin = BUS_TYPE_NONE; // assume we have not gotten a vin off the bus yet
        }

        // we need to get a unique integer for this device to use
        int device_serial_int = 0;
        try {
            device_serial_int = Integer.parseInt(device_serial);
        } catch (Exception e) {
            Log.e(TAG, "Unable to convert Device Serial # '" + device_serial + "' to a unique integer");
        }

        // try to start a J1939 bus


        // load into memory any requests in configuration to forward raw bus data for a specific message.
        loadRawForwardRequests();


        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(VehicleBusConstants.BROADCAST_STATUS);
            service.context.registerReceiver(busStatusReceiver, intentFilter, VehicleBusConstants.PERMISSION_VBS_TXRX, null);
        } catch (Exception e) {
            Log.e(TAG, "Could not register busStatus receiver");
        }



        if (j1939_enabled) {
            j1939 = new J1939(this, warmStart, device_serial_int);
            j1939.setAdditionalPGNs(getRawForwardPGNs());
            j1939.start();
        }

        // j1587 should be started AFTER j1939 and stopped BEFORE

        // if (J1587.isSupported()) {
        //    Log.i(TAG, "Starting J1587 bus");

        if (j1587_enabled) {
            j1587 = new J1587(this, warmStart);
            j1587.start();
        }


        initBusService(); // prepare bus service for starting (for the monitor)

        isStarted = true;
        mainHandler.postDelayed(pollTimer, POLL_TIME_MS); // once per second


    } // start();

    ////////////////////////////////////////////////////////////////////
    // stop()
    //      "Stop" the Engine monitoring, called when app is ended
    ////////////////////////////////////////////////////////////////////
    public void stop() {

        Log.v(TAG, "stop()");

        isStarted = false;

        stopFuelUpdates();

        //Log.v(TAG, "stopping poll callback");
        if (mainHandler != null) {
            mainHandler.removeCallbacks(pollTimer);
            mainHandler = null;
        }

        //Log.v(TAG, "stopping -- writestates()");
        // save the latest info for quick changing items that aren't saved on change

        service.state.writeStateLong(State.ACTUAL_ODOMETER, status.odometer_m);
        service.state.writeStateLong(State.FUEL_CONSUMPTION, status.fuel_mL);
        service.state.writeStateLong(State.FUEL_ECONOMY, status.fuel_mperL );


        // j1587 should be started AFTER j1939 and stopped BEFORE
        if (j1587 != null) {
            j1587.stop();
        }

        //Log.v(TAG, "stopping -- J1939()");
        // stop the J1939 bus
        if (j1939 != null) {
            j1939.stop();
        } else {
            //Log.e(TAG, "J1939 object was null. (Attempting stop before it started?)");
        }


        try {
            service.context.unregisterReceiver(busStatusReceiver);
        } catch(Exception e) {
            // don't do anything
        }


        status.buses_communicating = 0; // we can't be communicating with any buses if we are stopping this module

        //Log.v(TAG, "stopping -- done");

    }


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

        // Intermediate Data that needs to be stored (like Odometer?)


        service.state.writeStateLong(State.ACTUAL_ODOMETER, status.odometer_m);
        service.state.writeStateLong(State.FUEL_CONSUMPTION, status.fuel_mL);
        service.state.writeStateLong(State.FUEL_ECONOMY, status.fuel_mperL);


        // Other info that needs to be stored between crashes
        //crash.writeStateInt(Crash.ENGINE_BUS_TYPE_VIN, bus_type_vin); // remember if we got the vin from a bus


    } // saveCrashData()


    ////////////////////////////////////////////////////////////////////
    // restoreCrashData()
    //      restore data from a recent crash
    ////////////////////////////////////////////////////////////////////
    public void restoreCrashData(Crash crash) {
        // Intermediate Data that needs to be restored (like Odometer?)

        status.odometer_m = service.state.readStateLong(State.ACTUAL_ODOMETER);
        status.fuel_mL = service.state.readStateLong(State.FUEL_CONSUMPTION);
        status.fuel_mperL = service.state.readStateLong(State.FUEL_ECONOMY);

    } // restoreCrashData


    ////////////////////////////////////////////////////////////////////
    // getStatus()
    //  called everytime we need a status snapshot of relevant parameters (like for a local broadcast)
    ////////////////////////////////////////////////////////////////////
    public Status getStatus() {
        return status;
    }

    ////////////////////////////////////////////////////////////////////
    // getEnabled()
    //  called everytime we need to know if the engine module is enabled (like for a local broadcast)
    ////////////////////////////////////////////////////////////////////
    public boolean getEnabled() {
        return isEnabled;
    }



    ///////////////////////////////////////////////////////////////////
    // turnEngineOn()
    //  this is notification that engine status is on or off, used to start/stop fuel updates
    ///////////////////////////////////////////////////////////////////
    public boolean turnEngineOn(boolean on) {

        if (on)
            restartFuelUpdates();
        else
            stopFuelUpdates();

        return on;
    } // turnEngineOn()



    /////////////////////////////////////
    /////////////////////////////////////
    /////////////////////////////////////
    // Fuel Status Updates


    ///////////////////////////////////////////////////////////////////
    // restartFuelUpdates()
    //  sets the timer for fuel updates
    ///////////////////////////////////////////////////////////////////
    void restartFuelUpdates() {

        int seconds = service.config.readParameterInt(Config.SETTING_FUEL_STATUS, Config.PARAMETER_FUEL_STATUS_SECONDS);

        if (mainHandler != null)
            mainHandler.removeCallbacks(fuelUpdateTimer);

        if (seconds > 0) {
            if (mainHandler != null)
                mainHandler.postDelayed(fuelUpdateTimer, seconds * 1000);
            else {
                Log.e(TAG, "mainHandler is null. cannot (re)start fuel status update timer");
            }
        }
    } // restartFuelUpdates()


    ///////////////////////////////////////////////////////////////////
    // stopFuelUpdates()
    //  stops the timer for fuel updates
    ///////////////////////////////////////////////////////////////////
    void stopFuelUpdates() {
        if (mainHandler != null)
            mainHandler.removeCallbacks(fuelUpdateTimer);
    } // stopFuelUpdates()



    ////////////////////////////////////////////////////////////////////////////
    // fuelUpdateTimer()
    //  sends periodic fuel status messages to the server
    ////////////////////////////////////////////////////////////////////////////
    Runnable fuelUpdateTimer = new Runnable() {
        @Override
        public void run() {
            try {
                Log.v(TAG, "fuelUpdateTimer()");
                sendFuelStatusUpdate();
                restartFuelUpdates(); // restarts the timer to send next fuel update
                Log.v(TAG, "fuelUpdateTimer() END");
            } catch (Exception e) {
                Log.e(TAG + ".fuelUpdateTimer", "Exception: " + e.toString(), e);
            }
        } // run()
    }; // fuelUpdateTimer()


    void sendFuelStatusUpdate() {
        Codec codec = new Codec(service);
        service.addEventWithData(EventType.EVENT_TYPE_FUELSTATUS_PING, codec.dataForFuelStatus());
    } // sendFuelStatusUpdate()



    /////////////////////////////////////
    /////////////////////////////////////
    /////////////////////////////////////
    // Bus Detection and Priority



    ////////////////////////////////////////////////////////////////
    // hasBusPriority()
    //  we may get conflicting info from different buses, if so, then this determines who gets priority
    //  when we go to set a piece of data
    // Returns true if the "new" bus type has priority over the "old" bus type
    ////////////////////////////////////////////////////////////////
    static boolean hasBusPriority(int new_bus_type, int old_bus_type) {

        if (new_bus_type == BUS_TYPE_J1939) return true;
        if ((new_bus_type == BUS_TYPE_J1587) && (old_bus_type != BUS_TYPE_J1939))
            return true;

        return false;

    } // hasBusPriority


    /////////////////////////////////////////////////////////
    // getBusDetected()
    //  gets which buses we are talking to.
    /////////////////////////////////////////////////////////
    public byte getBusDetected() {

        return (byte) (status.buses_detected & 0xFF);
    }

    /////////////////////////////////////////////////////////
    // setBusDetected()
    //  set the given bus as detected (working)
    /////////////////////////////////////////////////////////
    public void setBusDetected(int bus_type) {

        status.buses_detected |= bus_type;
    }

    /////////////////////////////////////////////////////////
    // clearBusDetected()
    //  set the given bus as no longer detected
    /////////////////////////////////////////////////////////
    public void clearBusDetected(int bus_type) {

        status.buses_detected &= ~bus_type;
    }

    /////////////////////////////////////////////////////////
    // getBusCommunicating()
    //  gets which buses we are talking to.
    /////////////////////////////////////////////////////////
    public byte getBusCommunicating() {

        return (byte) (status.buses_communicating & 0xFF);
    }

    /////////////////////////////////////////////////////////
    // setBusCommunicating()
    //  set the given bus as communicating
    /////////////////////////////////////////////////////////
    public void setBusCommunicating(int bus_type) {

        status.buses_communicating |= bus_type;
    }

    /////////////////////////////////////////////////////////
    // clearBusCommunicating()
    //  set the given bus as no longer communicating
    /////////////////////////////////////////////////////////
    public void clearBusCommunicating(int bus_type) {

        status.buses_communicating &= ~bus_type;
    }





    /////////////////////////////////////////////////////////
    // clearAllParams()
    //  resets odometer, fuel, etc, as unreliable because a vin has changed ?
    /////////////////////////////////////////////////////////
    void clearAllParams() {

    } // clearAllParams()


    /////////////////////////////////////
    /////////////////////////////////////
    /////////////////////////////////////
    // Variable Tracking

    public boolean checkParkingBrake(int bus_type, boolean on) {

        // if the new info doesn't have priority, then ignore it
        if (!hasBusPriority(bus_type, bus_type_parking_brake)) return status.flagParkingBrake;
        bus_type_parking_brake = bus_type;

        if (on == status.flagParkingBrake) return status.flagParkingBrake;

        status.flagParkingBrake = on;


        if (status.flagParkingBrake) {
            Log.d(TAG, "Parking Brake On");
            service.addEvent(EventType.EVENT_TYPE_PARKBRAKE_ON);
            service.state.writeState(State.FLAG_PARKING_BRAKE_STATUS, 1); // remember this
        } else {
            Log.d(TAG, "Parking Brake Off");
            service.addEvent(EventType.EVENT_TYPE_PARKBRAKE_OFF);
            service.state.writeState(State.FLAG_PARKING_BRAKE_STATUS, 0); // remember this
        }

        return status.flagParkingBrake;
    } // checkParkingBrake()


    public boolean checkReverseGear(int bus_type, boolean on) {

        // if the new info doesn't have priority, then ignore it
        if (!hasBusPriority(bus_type, bus_type_reverse_gear)) return status.flagReverseGear;
        bus_type_reverse_gear = bus_type;

        if (on == status.flagReverseGear) return status.flagReverseGear;

        status.flagReverseGear = on;

        if (status.flagReverseGear) {
            Log.d(TAG, "Reverse Gear On");

            service.addEvent(EventType.EVENT_TYPE_REVERSE_ON);
            service.state.writeState(State.FLAG_REVERSE_GEAR_STATUS, 1); // remember this

        } else {
            Log.d(TAG, "Reverse Gear Off");

            service.addEvent(EventType.EVENT_TYPE_REVERSE_OFF);
            service.state.writeState(State.FLAG_REVERSE_GEAR_STATUS, 0); // remember this
        }

        return status.flagReverseGear;
    } // checkReverseGear()

    public long checkOdometer(int bus_type, long new_odometer_m) {

        if (!hasBusPriority(bus_type, bus_type_odometer_m)) return status.odometer_m;
        bus_type_odometer_m = bus_type;

        if (new_odometer_m == status.odometer_m) return status.odometer_m;

        status.odometer_m = new_odometer_m;
        Log.vv(TAG, "odometer changed to " + status.odometer_m);

        return status.odometer_m;
    } // checkOdometer()

    public long checkFuelConsumption(int bus_type, long new_fuel_mL) {

        if (!hasBusPriority(bus_type, bus_type_fuel_mL)) return status.fuel_mL;
        bus_type_fuel_mL = bus_type;

        if (new_fuel_mL == status.fuel_mL) return status.fuel_mL;

        status.fuel_mL = new_fuel_mL;
        Log.vv(TAG, "fuel consumption changed to " + status.fuel_mL + " mL");

        return status.fuel_mL;
    } // checkFuelConsumption()

    public long checkFuelEconomy(int bus_type, long new_fuel_mperL) {

        if (!hasBusPriority(bus_type, bus_type_fuel_mperL)) return status.fuel_mperL;
        bus_type_fuel_mperL = bus_type;

        if (new_fuel_mperL == status.fuel_mperL) return status.fuel_mperL;

        status.fuel_mperL = new_fuel_mperL;
        Log.vv(TAG, "fuel economy changed to " + status.fuel_mperL + " m/L");

        return status.fuel_mperL;
    } // checkFuelEconomy()



    public String checkVin(int bus_type, String newVin) {


        if (!hasBusPriority(bus_type, bus_type_vin)) return vin;
        bus_type_vin = bus_type;

        // We don't need to do this again until we cold start
        setWarmStart(true);

        if (newVin.equals(vin)) {
            Log.d(TAG, "VIN confirmed as " + vin);
            return vin;
        }

        vin = newVin;
        Log.i(TAG, "VIN has changed to " + vin);
        clearAllParams();
        service.state.writeStateString(State.STRING_VIN, vin);

        return vin;
    } // checkVin()








    /////////////////////////////////////////////////////
    // checkDtcs()
    //  Takes a new list of DTCs from a particular bus and compares for any additions or deletions against the current list.
    //  Note: The list must include all DTCs from the bus (not just from one node on the bus).
    // Returns:
    //  0xAADD where AA = num added, DD = num_deleted
    /////////////////////////////////////////////////////
    public int checkDtcs(int bus_type, long[] newDtcs) {

        // compare everything in tempDtcs to the real list of DTCs
        // indicate this came from the J1939 bus


        int num_added=0, num_deleted = 0;
        boolean found;


        // Check for deletions (items in current list that are not in new list)
        Iterator<EngineDtc> current_iter = current_dtcs.iterator();
        while (current_iter.hasNext()) {
            EngineDtc current_dtc = current_iter.next();

            if (current_dtc.bus_type == bus_type) { // only check codes that are for the same bus type
                // we will only consider removing codes that are for the same bus type
                found = false;
                for (long dtc_value : newDtcs) {
                    if (dtc_value == current_dtc.dtc_value) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    // maybe this code should be removed?
                    current_dtc.removal_count++;
                    if (current_dtc.removal_count >= ENGINE_DTC_REMOVAL_COUNT) {
                        // removal count is high enough ... remove this code
                        Log.d(TAG, "removing " + getBusName(bus_type) + " DTC " + String.format("x%08X", current_dtc.dtc_value) + " from current DTCs");



                        byte data[] = new byte[5];
                        data[0] = (byte) current_dtc.bus_type;
                        data[1] = (byte) (current_dtc.dtc_value & 0xFF);
                        data[2] = (byte) ((current_dtc.dtc_value >> 8) & 0xFF);
                        data[3] = (byte) ((current_dtc.dtc_value >> 16) & 0xFF);
                        data[4] = (byte) ((current_dtc.dtc_value >> 24) & 0xFF);

                        service.addEventWithData(EventType.EVENT_TYPE_FAULTCODE_OFF, data);


                        current_iter.remove();
                        num_deleted++;

                    }   // remove this code
                } // existing code not found in new list
            } // code is for correct bus type
        } // each current code

        // Check for additions (items in new list that are not in current list)
        int i = 0;
        for (i =0 ; i < newDtcs.length; i++) {

            EngineDtc newDtc = new EngineDtc();
            newDtc.dtc_value = newDtcs[i];
            newDtc.bus_type = bus_type;
            newDtc.removal_count = 0;

            found = false;
            for (EngineDtc currentDtc : current_dtcs) {
                if ((currentDtc.dtc_value == newDtc.dtc_value) &&
                    (currentDtc.bus_type == newDtc.bus_type)) {

                    // item exists in current list, update it accordingly
                    currentDtc.removal_count = 0; // we do not remove it
                    found = true;
                    break;
                }
            }

            if (!found) {
                // add this item to the list of DTCs!
                Log.d(TAG, "adding " + getBusName(bus_type) + " DTC " + String.format("x%08X", newDtc.dtc_value) + " to current DTCs");

                byte data[] = new byte[5];
                data[0] = (byte) newDtc.bus_type;
                data[1] = (byte) (newDtc.dtc_value & 0xFF);
                data[2] = (byte) ((newDtc.dtc_value >> 8) & 0xFF);
                data[3] = (byte) ((newDtc.dtc_value >> 16) & 0xFF);
                data[4] = (byte) ((newDtc.dtc_value >> 24) & 0xFF);

                service.addEventWithData(EventType.EVENT_TYPE_FAULTCODE_ON, data);

                current_dtcs.add(newDtc);
                num_added++;
            }
        }


        if ((num_added > 0) || (num_deleted > 0)) {
            // a change was made ,,, record the new array of dtc values & bus_types

            String hex = new String("");

            int num_dtcs = current_dtcs.size();

            byte[] dtc_array= new byte[num_dtcs * EngineDtc.DTC_ARRAY_ENTRY_SIZE];

            i = 0;
            for (EngineDtc current_dtc : current_dtcs) {
                if (i < num_dtcs) {
                    EngineDtc.convertToBytes(current_dtc, dtc_array, i*EngineDtc.DTC_ARRAY_ENTRY_SIZE);
                }
                i++;
            } // each dtc

            //Log.d(TAG, "adding State DTC " + Log.bytesToHex(dtc_array, dtc_array.length));
            service.state.writeStateArray(State.ARRAY_FAULT_CODES, dtc_array); // remember this
        }

        return ((num_added & 0xFF) << 8) | (num_deleted & 0xFF);
    } // checkDtcs()




    ////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////
    // Raw Forwarding Support
    ////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////

    // requests for forwarding (filters to match)
    class RawForwardRequest  {
        int bus_type;
        int message_type; // PGN or PID
        boolean active;
        int period_seconds;
        long last_sent_realtime_ms; // time this was last sent (so we know if it has been longer)
    } // RawForwardRequest

    RawForwardRequest[] rawForwardRequests = null;

    // results for forwarding (received data that matches)
    class RawForwardResult  {
        int bus_type;
        int message_type; // PGN or PID
        long time_ms; // realtime last time this was received/processed
        int source; // who sent this message (maybe we get the same info from multiple sources ?
        byte[] data;
    }

    ArrayList<RawForwardResult> rawForwardResults = new ArrayList<RawForwardResult>() ;




    ////////////////////////////////////////////////////////////////////
    // loadRawForwardRequests()
    //      loads the Raw Forwards requested by the user from the configuration
    ////////////////////////////////////////////////////////////////////
    void loadRawForwardRequests() {
        ArrayList<RawForwardRequest> resultList = new ArrayList<RawForwardRequest>();
        resultList.clear();

        String[] arr = service.config.readParameterArray(Config.SETTING_FORWARD_RAW_BUS);

        int i = 0;
        while ( i < arr.length) {

            //  Each Entry has 4 parameters
            //  [+0]       : Bus "J1939, j1939, J1587, j1587"
            //  [+1]       : Message ID (PGN or PID)
            //  [+2]       : "P" or "A"
            //  [+3]       : time between requests

            if (i + 2 <= arr.length) { // must be at least two bytes
                String rbus = arr[i + 0].trim().toUpperCase();

                if (!rbus.isEmpty()) {

                    RawForwardRequest rfr = new RawForwardRequest();
                    rfr.bus_type = BUS_TYPE_NONE;

                    if (rbus.equals("J1939")) {
                        rfr.bus_type = Engine.BUS_TYPE_J1939;
                    } else if (rbus.equals("J1587")) {
                        rfr.bus_type = Engine.BUS_TYPE_J1587;
                    } else {
                        Log.e(TAG, "Unrecognized Bus for Raw Forward setting: " + arr[i] + " (Entry ignored)");
                    }

                    rfr.message_type = 0;
                    try {
                        rfr.message_type = Integer.valueOf(arr[i + 1]);
                    } catch (Exception e) {
                        rfr.bus_type = BUS_TYPE_NONE;
                        Log.e(TAG, "Invalid Message for Raw Forward setting: " + arr[i + 1] + " (Message ignored)");
                    }

                    rfr.active = false;
                    if (i + 2 < arr.length)
                        if (arr[i + 2].trim().toUpperCase().startsWith("A")) {
                            rfr.active = true;
                        }

                    rfr.period_seconds = 5; // 5 seconds is default
                    if (i + 3 < arr.length) {
                        try {
                            rfr.period_seconds = Integer.valueOf(arr[i + 3]);
                        } catch (Exception e) {
                            Log.e(TAG, "Invalid Period for Raw Forward setting: " + arr[i + 3] + " (Default used = " + rfr.period_seconds + ")");
                        }
                    }

                    rfr.last_sent_realtime_ms = 0; // never sent -- long time ago.
                    resultList.add(rfr);

                    Log.v(TAG, "Loaded Raw Forward Request for bus " + rfr.bus_type + " pgn/pid " + rfr.message_type + " " + (rfr.active ? "A" : "P") + " " + rfr.period_seconds + "s");
                } // recognized the requested bus
            } // at least two bytes in entry

            i+=4;
        } // while each entry

        rawForwardRequests = new RawForwardRequest[resultList.size()];
        rawForwardRequests = resultList.toArray(rawForwardRequests);

    } // loadRawForwardRequests()

    ////////////////////////////////////////////////////////////////////
    // getRawForwardPGNs()
    //      gets the J1939 pgns out of the loaded raw forward requests
    ////////////////////////////////////////////////////////////////////
    int[] getRawForwardPGNs() {
        ArrayList<Integer> resultList = new ArrayList<Integer>();
        resultList.clear();

        for (RawForwardRequest rfr : rawForwardRequests ) {
            if (rfr.bus_type == Engine.BUS_TYPE_J1939) {
                resultList.add(new Integer(rfr.message_type));
            }
        }
        int[] resultArray = new int[resultList.size()];
        int i = 0;
        for (Integer n : resultList) {
            resultArray[i++] = n;
        }

        return resultArray;
    } // getRawForwardPGNs()

    ////////////////////////////////////////////////////////////////////
    // getRawForwardActiveRequests()
    //      returns a list of the pgns or pids that need to be actively sent over the vehicle bus
    //  Parameters:
    //      rf_bus_type : Engine.BUS_TYPE_J1939 or BUS_TYPE_J1587
    //  Returns:
    //      array of integers representing the message IDs to request
    ////////////////////////////////////////////////////////////////////
    int[] getRawForwardActiveRequests(int rf_bus_type) {
        ArrayList<Integer> resultList = new ArrayList<Integer>();
        resultList.clear();

        long now = SystemClock.elapsedRealtime();

        for (RawForwardRequest rfr : rawForwardRequests ) {
            if ((rfr.bus_type == rf_bus_type) &&
                (rfr.active) &&
                (rfr.last_sent_realtime_ms + rfr.period_seconds*1000 < now))
            {
                resultList.add(new Integer(rfr.message_type));
                rfr.last_sent_realtime_ms = now;
            }
        }
        int[] resultArray = new int[resultList.size()];
        int i = 0;
        for (Integer n : resultList) {
            resultArray[i++] = n;
        }

        return resultArray;
    } // getRawForwardActiveRequests()

    ////////////////////////////////////////////////////////////////////////////
    // addOrReplaceResult()
    //  returns: true if it was added, false if it was replaced
    ////////////////////////////////////////////////////////////////////////////
    boolean addOrReplaceResult(RawForwardResult rfresult) {

        // if found, then replace it
        for (RawForwardResult rfr : rawForwardResults) {
            if ((rfr.bus_type == rfresult.bus_type) &&
                    (rfr.message_type == rfresult.message_type) &&
                    (rfr.source == rfresult.source)) {
                Log.vv(TAG, "Matched result for bus " + rfr.bus_type + " pgn/pid " + rfr.message_type + " source " + rfr.source);
                rfr.data = rfresult.data;
                rfr.time_ms = rfresult.time_ms;
                return false;
            }
        }

        // was not found, add it
        rawForwardResults.add(rfresult);
        return true;

    } // addOrReplaceResult()


    ////////////////////////////////////////////////////////////////////////////
    // checkRawForwarding()
    //  checks received raw data from the vehicle bus to see if it needs to be forwarded to local broadcasts
    //  If so, adds it to our results list so it can be forwarded later
    ////////////////////////////////////////////////////////////////////////////
    public void checkRawForwarding(int bus_type, int message_type, int source, byte[] data) {

        if (rawForwardRequests == null) return; // nothing to raw-forward

        for (RawForwardRequest rawForwardRequest : rawForwardRequests) {

            if ((bus_type == rawForwardRequest.bus_type) &&
                    (message_type == rawForwardRequest.message_type)) {

                // Yes this should be, add or replace this result

                RawForwardResult rfresult = new RawForwardResult();

                rfresult.bus_type = rawForwardRequest.bus_type;
                rfresult.message_type = rawForwardRequest.message_type;
                rfresult.time_ms = SystemClock.elapsedRealtime();
                rfresult.source = source;
                rfresult.data = data;

                addOrReplaceResult(rfresult);
            }
        } // each thing to raw-forward

    } // checkRawForwarding()


    ////////////////////////////////////////////////////////////////////////////
    // forwardRawResults()
    //  forwards any raw results to the local broadcaster, removing them from the results list
    ////////////////////////////////////////////////////////////////////////////
    void forwardRawResults() {
        Log.vv(TAG, "Forwarding Raw Results");

        for (int i=rawForwardResults.size()-1;i>=0;i--) {
            // Do something
            RawForwardResult rfresult = rawForwardResults.get(i);
            rawForwardResults.remove(i);

            service.local.sendRawBusMessage(rfresult.bus_type, rfresult.message_type, rfresult.time_ms, rfresult.source, rfresult.data);

        }
    } // forwardRawResults()


    /////////////////////////////////////
    /////////////////////////////////////
    /////////////////////////////////////
    // Active Polling (Requests) for Vehicle Buses




    ////////////////////////////////////////////////////////////////////////////
    // pollTimer()
    //  sends periodic polls (requests) to the vehicle buses, about once per second
    ////////////////////////////////////////////////////////////////////////////
    Runnable pollTimer = new Runnable() {

        int everyother = 0;

        @Override
        public void run() {
            try {
                Log.vv(TAG, "pollTimer()");

                // verify that the service is running
                verifyBusService();

                // send anything we need to the bus
                pollBuses(everyother);

                // just toggle
                everyother ^=1; // toggle

            } catch (Exception e) {
                Log.e(TAG + ".pollTimer", "Exception: " + e.toString(), e);
            }
            try {
                if (mainHandler != null) {
                    mainHandler.postDelayed(pollTimer, POLL_TIME_MS); // once per second
                }
            } catch (Exception e) {
                Log.e(TAG + ".pollTimer", "mainHandler Exception: " + e.toString(), e);
            }
            Log.vv(TAG, "pollTimer() END");
        } // run()
    }; // pollTimer()



    ////////////////////////////////////////////////////////////////////////////
    // pollBuses()
    //  perform the various polls required to the various buses
    ////////////////////////////////////////////////////////////////////////////
    void pollBuses(int everyother) {

        if (!isEnabled) return; // we don't need to do anything if engine isn't enabled


        Log.d(TAG,
                (!isBusServiceRunning() ? "VBS=Off" :
                    ((status.buses_detected & BUS_TYPE_J1939_250K) > 0 ? " J1939-250=" + ((status.buses_communicating & BUS_TYPE_J1939_250K) > 0 ? "UP" : "--") : "")+
                    ((status.buses_detected & BUS_TYPE_J1939_500K) > 0 ? " J1939-500=" + ((status.buses_communicating & BUS_TYPE_J1939_500K) > 0 ? "UP" : "--") : "") +
                    ((status.buses_detected & BUS_TYPE_J1587) > 0 ? " J1587=" + ((status.buses_communicating & BUS_TYPE_J1587) > 0 ? "UP" : "--")  : "")
                ) +
                " : " +
                "DTCs " + current_dtcs.size() +
                " Odom " + (bus_type_odometer_m != BUS_TYPE_NONE ? status.odometer_m + "m": "?") +
                " FuelC " + (bus_type_fuel_mL != BUS_TYPE_NONE ?  status.fuel_mL + "mL": "?") +
                " FuelE " + (bus_type_fuel_mperL != BUS_TYPE_NONE ? status.fuel_mperL + "m/L": "?") +
                " Brake " + (bus_type_parking_brake != BUS_TYPE_NONE ? (status.flagParkingBrake  ? "1" : "0") : "?") +
                " Reverse " + (bus_type_reverse_gear != BUS_TYPE_NONE ? (status.flagReverseGear ? "1" : "0")  : "?")
        );



        if ((j1939 != null) && (j1939.isCommunicating())) {

            j1939.logStatus();

            if ((j1939_vinAttemptsRemaining > 0) &&   // we have attempts remaining to get the VIN
               (bus_type_vin == BUS_TYPE_NONE)) // we have not yet retrieved a VIN
            {
                if ((everyother & 1) == 0) { // only do this every 2 seconds, instead of every one
                    j1939.sendRequestVIN();
                    j1939_vinAttemptsRemaining--; // remember this attempt
                }
            } else {
                // This is our normal routine
                // if we don't have a hi-res odometer, try to get a low-res one.
                if (!j1939.isHighResOdometerPresent())
                    j1939.sendRequestOdometerLoRes();
                j1939.sendRequestTotalFuel();

                j1939.sendRequestCustom(getRawForwardActiveRequests(BUS_TYPE_J1939));

            }
        } // j1939

        if ((j1587 != null) && (j1587.isCommunicating())) {

            j1587.logStatus();

            if ((j1587_vinAttemptsRemaining > 0) &&   // we have attempts remaining to get the VIN
                    (bus_type_vin == BUS_TYPE_NONE)) // we have not yet retrieved a VIN
            {
                if ((everyother & 1) == 0) { // only do this every 2 seconds, instead of every one
                    j1587.sendRequestVIN();
                    j1587_vinAttemptsRemaining--; // remember this attempt
                }
            } else {
                // This is our normal routine
                j1587.sendRequestTotalFuel();
                j1587.sendRequestDTC();

                j1587.sendRequestCustom(getRawForwardActiveRequests(BUS_TYPE_J1587));
            }
        } // j1939


        // now forward any raw packets that we received that need to be forwarded locally.
        forwardRawResults();


    } // pollBuses




    /////////////////////////////////////
    /////////////////////////////////////
    /////////////////////////////////////
    // Monitoring of the separate Vehicle Bus Service to make sure it is installed / alive.




    // the bus status should be broadcast by VBus every second. If we go 3 seconds without it, then something is wrong.
    static final int MAX_BUSSTATUS_RECEIPT_MS = 3000; // 3 seconds


    boolean isBusServiceRunning() {
        long nowElapsedTime = SystemClock.elapsedRealtime();

        //Log.v(TAG, "times : " + nowElapsedTime  + "  " + busStatusReceiver.last_alive_ertime + "  " + (nowElapsedTime-busStatusReceiver.last_alive_ertime));

        if ((nowElapsedTime > busStatusReceiver.last_alive_ertime) &&
                (nowElapsedTime - busStatusReceiver.last_alive_ertime > MAX_BUSSTATUS_RECEIPT_MS)) {
            return false;
        }
        return true;
    }


    ////////////////////////////////////////////////////////////////////////////
    // verifyBusService()
    //  Verify that the Bus Service is running
    ////////////////////////////////////////////////////////////////////////////
    void verifyBusService() {

        if (!isBusServiceRunning()) {
            if (busStatusReceiver.isAlive) {
                Log.e(TAG, "VBS Service is not running!");
                busStatusReceiver.isAlive = false;
                service.addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_VBS_SERVICE_JAMMED);
            }
            // Try to restart it
            restartBusService();
            initBusService(); // re-start the timers so we give it time to start
        }
    } // verifyBusService()


    void initBusService() {
        // give us time to receive something from the bus service
        busStatusReceiver.last_alive_ertime = SystemClock.elapsedRealtime() + MAX_BUSSTATUS_RECEIPT_MS;
        // pretend we are alive so that we will send error message if it "dies" (or doesn't start)
        busStatusReceiver.isAlive = true;
    }

    void restartBusService() {
        Log.i(TAG, "Requesting VBS Service Restart");
        Intent serviceIntent = new Intent();
        serviceIntent.setPackage(VehicleBusConstants.PACKAGE_NAME_VBS);
        serviceIntent.setAction(VehicleBusConstants.SERVICE_ACTION_RESTART);

        service.context.startService(serviceIntent);
    }


    ////////////////////////////////////////////////////////////////////////////
    // busStatusReceiver()
    //  get the status information from the vehicle bus modules
    ////////////////////////////////////////////////////////////////////////////
    BusStatusReceiver busStatusReceiver = new BusStatusReceiver();
    class BusStatusReceiver extends BroadcastReceiver {

        long last_alive_ertime = 0;
        boolean isAlive = false;
        int vbus_processId = 0;

        @Override
        public void onReceive(Context context, Intent intent) {

            try {
                Log.vv(TAG, "Received VBS status");
/*
                String password = intent.getStringExtra("password");
                if ((password == null) || (!password.equals(VehicleBusService.BROADCAST_PASSWORD))) {
                    Log.e(TAG, "Received invalid vbus status broadcast");
                    return;
                }
*/
                isAlive = true;
                last_alive_ertime = intent.getLongExtra("elapsedRealtime", 0);
        //        int processId = intent.getIntExtra("processId", 0);
        //        if (processId != vbus_processId) {
        //            vbus_processId =processId;
        //            Log.i(TAG, "Received PID " + vbus_processId + " for VBus Service");
        //        }

                boolean cantx = intent.getBooleanExtra("cantx", false);
                if (cantx) {
                    if (j1939 != null) {
                        j1939.busReadyTxCallback();
                    }
                }


            } catch (Exception e) {
                Log.e(TAG, ".busStatusReceiver Exception : " + e.toString(), e);
            }
        }
    } // busStatusReceiver()

/*
    long getLastStatusReceiptTime() {

        if (!isStarted) {
            // return now, so that it looks like everything is OK
            return SystemClock.elapsedRealtime();
        }

        return busStatusReceiver.last_alive_ertime;
    }

    void killVBusService() {

        if (busStatusReceiver.vbus_processId != 0) {
            Log.d(TAG, "Killing VBS PID " + busStatusReceiver.vbus_processId);
            android.os.Process.killProcess(busStatusReceiver.vbus_processId);
            busStatusReceiver.vbus_processId = 0;
        }

    }

    ////////////////////////////////////////////////////////////////////////////
    // restartTask()
    //
    ////////////////////////////////////////////////////////////////////////////
    Runnable restartTask = new Runnable() {

        int everyother = 0;

        @Override
        public void run() {
            try {
                start(last_device_serial);
            } catch (Exception e) {
                Log.e(TAG + ".restartTask", "Exception: " + e.toString(), e);
            }

        } // run()
    }; // restartTask()


    ///////////////////////////////////////////////////////////
    // restart(): Restart the service
    //  restart can be useful to clear any problems in the app during troubleshooting
    ///////////////////////////////////////////////////////////
    public void restartVBusService() {

        Log.i(TAG, "Restarting Vbus Service (expect 2s delay)");
        busStatusReceiver.last_alive = SystemClock.elapsedRealtime() + 3000; // givei it a little more time to recover

        stop();
        killVBusService();

        mainHandler  = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(restartTask, 1000); // once per second


    }
*/
} // Engine Class
