/////////////////////////////////////////////////////////////
// LocalMessage:
//  This class is responsible for communicating with other applications on the same device
//  along with the ExternalReceiver Module, which receives broadcast
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;


import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;

public class LocalMessage {

    public static final String TAG = "ATS-LocalM";

    // Periodic messages are sent regularly and contain current state information
    public static final String BROADCAST_MESSAGE_PERIODIC_DEVICE = "com.micronet.dsc.ats.device.status";
    public static final String BROADCAST_MESSAGE_PERIODIC_VEHICLE = "com.micronet.dsc.ats.vehicle.status";

    // Change messages are sent irregularly when there is a change to status
    public static final String BROADCAST_MESSAGE_CHANGE_DEVICE = "com.micronet.dsc.ats.device.event";
    public static final String BROADCAST_MESSAGE_CHANGE_VEHICLE = "com.micronet.dsc.ats.vehicle.event";


    // Raw Bus messages are sent when raw bus data matches the filters
    public static final String BROADCAST_MESSAGE_RAWBUS = "com.micronet.dsc.ats.vehicle.raw";


    public static final int PERIODIC_TIMER_MS = 1000; // broadcast once per second


    MainService service;
    Handler mainHandler = null;

    LocalMessage(MainService s) {
        service = s;
    }


    public void start() {
        // Register a check timer
        Log.v(TAG, "start");


        mainHandler  = new Handler();
        mainHandler.postDelayed(periodicTask, PERIODIC_TIMER_MS);

    }

    public void stop() {
        Log.v(TAG, "stop");

        mainHandler.removeCallbacks(periodicTask);

    }


    public void sendRawBusMessage(int bus_type, int message_type, long elapsedRealtime, int source, byte[] data) {

        Log.v(TAG, "Sending Raw Bus Message");

        // send a local message:
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_MESSAGE_RAWBUS);
        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot -- time the data is valid
        ibroadcast.putExtra("busType", bus_type);
        ibroadcast.putExtra("messageType", message_type);
        ibroadcast.putExtra("sourceAddress", source);
        ibroadcast.putExtra("rawData", data);

        service.context.sendBroadcast(ibroadcast);

    } // sendRawBusMessage


    public void sendChangeMessage(int event_type_id, byte[] data) {
        // send a Local Message that some value has changed

        // Check if this is a "device" event or a "vehicle" event

        if ((event_type_id >= EventType.EVENT_TYPES_DEVICE_START) &&
        (event_type_id <= EventType.EVENT_TYPES_DEVICE_END)) {
            sendChangeDeviceMessage( event_type_id, data);
        } else
        if ((event_type_id >= EventType.EVENT_TYPES_VEHICLE_START) &&
        (event_type_id <= EventType.EVENT_TYPES_VEHICLE_END)) {
            sendChangeVehicleMessage( event_type_id, data);
        }

        // otherwise do nothing, as this is not a local broadcast event

    } // sendChangeMessage()

    public void sendChangeDeviceMessage(int event_type_id, byte[] data) {
        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot

        Log.v(TAG, "Sending Change Message (Device)");

        // send a local message:
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_MESSAGE_CHANGE_DEVICE);
        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot
        ibroadcast.putExtra("eventCode", event_type_id);
        if (data != null) {
            ibroadcast.putExtra("eventData", data);
        }

        service.context.sendBroadcast(ibroadcast);

    } // sendChangeDeviceMessage()

    public void sendChangeVehicleMessage(int event_type_id, byte[] data) {
        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot

        Log.v(TAG, "Sending Change Message (Vehicle)");

        // send a local message:
        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_MESSAGE_CHANGE_VEHICLE);
        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot
        ibroadcast.putExtra("eventType", event_type_id);
        if (data != null) {
            ibroadcast.putExtra("data", data);
        }

        service.context.sendBroadcast(ibroadcast);

    } // sendChangeVehicleMessage()


    /////////////////////////////////////////////////////////////
    // sendPeriodicDeviceMessage()
    //  information that comes from the device's own sensors
    /////////////////////////////////////////////////////////////
    public void sendPeriodicDeviceMessage() {

        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_MESSAGE_PERIODIC_DEVICE);
        // Take snapshot of everything
        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot
        Io.Status ioStatus = service.io.getStatus();
        Position.Status posStatus = service.position.getStatus();

        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime);

        // IO
        ibroadcast.putExtra("batteryVoltage", (float) ioStatus.battery_voltage / 10);
        ibroadcast.putExtra("ignitionState", ((ioStatus.input_bitfield & 1) > 0 ? 1 : 0));
        ibroadcast.putExtra("input1State", ((ioStatus.input_bitfield & 2) > 0 ? 1 : 0));
        ibroadcast.putExtra("input2State", ((ioStatus.input_bitfield & 4) > 0 ? 1 : 0));
        ibroadcast.putExtra("input3State", ((ioStatus.input_bitfield & 8) > 0 ? 1 : 0));
        ibroadcast.putExtra("input4State", ((ioStatus.input_bitfield & 16) > 0 ? 1 : 0));
        ibroadcast.putExtra("input5State", ((ioStatus.input_bitfield & 32) > 0 ? 1 : 0));
        ibroadcast.putExtra("input6State", ((ioStatus.input_bitfield & 64) > 0 ? 1 : 0));

        // GPS & IO
        ibroadcast.putExtra("badAlternatorState", (ioStatus.flagBadAlternator ? 1 : 0));
        ibroadcast.putExtra("lowBatteryState", (ioStatus.flagLowBattery ? 1 : 0));
        ibroadcast.putExtra("engineState", (ioStatus.flagEngineStatus ? 1 : 0));

        ibroadcast.putExtra("idlingState", (posStatus.flagIdling ? 1 : 0));
        ibroadcast.putExtra("speedingState", (posStatus.flagSpeeding ? 1 : 0));
        ibroadcast.putExtra("acceleratingState", (posStatus.flagAccelerating ? 1 : 0));
        ibroadcast.putExtra("brakingState", (posStatus.flagBraking ? 1 : 0));
        ibroadcast.putExtra("corneringState", (posStatus.flagCornering ? 1 : 0));
        ibroadcast.putExtra("virtualOdometerMeters", posStatus.virtual_odometer_m);
        ibroadcast.putExtra("continuousIdlingSeconds", posStatus.continuous_idling_s);

        service.context.sendBroadcast(ibroadcast);
    } // sendPeriodicDeviceMessage()


    /////////////////////////////////////////////////////////////
    // sendPeriodicVehicleMessage()
    //  information that comes from the vehicle and is reported over a communication bus
    /////////////////////////////////////////////////////////////
    public void sendPeriodicVehicleMessage() {

        Intent ibroadcast = new Intent();
        ibroadcast.setAction(BROADCAST_MESSAGE_PERIODIC_VEHICLE);
        // Take snapshot of everything
        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot
        Engine.Status engineStatus = service.engine.getStatus();

        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime);


        // Engine
        ibroadcast.putExtra("parkingBrakeState", (engineStatus.flagParkingBrake ? 1 : 0));
        ibroadcast.putExtra("reverseGearState", (engineStatus.flagReverseGear ? 1 : 0));
        ibroadcast.putExtra("actualOdometerMeters", engineStatus.odometer_m);
        ibroadcast.putExtra("fuelConsumptionMilliliters", engineStatus.fuel_mL);
        ibroadcast.putExtra("fuelEconomyMetersPerLiter", engineStatus.fuel_mperL);

        // Add DTCs

        ibroadcast.putExtra("numDtcs", service.engine.current_dtcs.size());

        int dtc_index = 0;
        for (Engine.EngineDtc dtc : service.engine.current_dtcs) {
            long[] dtc_array = new long[] {0,0};
            dtc_array[0] = dtc.bus_type;
            dtc_array[1] = dtc.dtc_value;
            ibroadcast.putExtra("dtc" + dtc_index , dtc_array);
            dtc_index++;
        } // for each DTC

        service.context.sendBroadcast(ibroadcast);

    } // sendPeriodicVehicleMessage()

    ///////////////////////////////////////////////////////////////
    // sendPeriodicMessages()
    //  This is called from the periodic task and used to send regular status messages to other apps
    ///////////////////////////////////////////////////////////////
    public void sendPeriodicMessages() {
        // send a Local Message with all tracked values

        Log.v(TAG, "Sending Periodic Messages");


        sendPeriodicDeviceMessage();


        if (service.engine.getEnabled()) {
            // only send the broadcast with vehicle received parameters if the engine module is enabled
            sendPeriodicVehicleMessage();
        }


    } // sendPeriodicMessages()


    ///////////////////////////////////////////////////////////////
    // periodicTask()
    //  Timer to send the periodic broadcast
    ///////////////////////////////////////////////////////////////
    private Runnable periodicTask= new Runnable() {

        @Override
        public void run() {
            try {
                sendPeriodicMessages();
            } catch (Exception e) {
                Log.e(TAG + ".periodicTask", "Exception: " + e.toString(), e);
            }
            mainHandler.postDelayed(periodicTask, PERIODIC_TIMER_MS);
        }
    }; // periodicTask()



} // class
