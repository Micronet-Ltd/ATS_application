package com.micronet.dsc.ats;

/**
 * Created by dschmidt on 2/16/16.
 */
public class EventType {

    // Define the various event codes

    // special events for the benefit of the server

    public static int EVENT_TYPE_ACK = 1;    // Ack the designated message matching sequence ID if it is top of queue
    public static int EVENT_TYPE_NAK = 2;	 // There is something wrong with the command from the server
    public static int EVENT_TYPE_ACK_TOP = 3; // Ack the message at top of queue no matter what

    public static int EVENT_TYPE_REBOOT = 5; // System has booted and started the service
    public static int EVENT_TYPE_RESTART = 6; // Service was started and was not already running
    public static int EVENT_TYPE_WAKEUP = 7; // System was sleeping or execution was paused.
    public static int EVENT_TYPE_SHUTDOWN = 8; // System is going into shutdown

    public static int EVENT_TYPE_HEARTBEAT = 10;
    public static int EVENT_TYPE_PING = 11;
    public static int EVENT_TYPE_ERROR = 12; // some error has occurred in the App
    public static int EVENT_TYPE_CHANGE_SYSTEMTIME = 13; // this app has changed the system time
    public static int EVENT_TYPE_CONFIGURATION_REPLACED = 14; // a configuration file has been replaced/overwritten


    // Events triggered by the device . .these will be sent by local broadcast
    public static int EVENT_TYPES_DEVICE_START = 20;
    public static int EVENT_TYPES_DEVICE_END = 69;

    public static int EVENT_TYPE_IGNITION_KEY_ON = 20;
    public static int EVENT_TYPE_INPUT1_ON = 21;
    public static int EVENT_TYPE_INPUT2_ON = 22;
    public static int EVENT_TYPE_INPUT3_ON = 23;
    public static int EVENT_TYPE_INPUT4_ON = 24;
    public static int EVENT_TYPE_INPUT5_ON = 25;
    public static int EVENT_TYPE_INPUT6_ON = 26;

    public static int EVENT_TYPE_IGNITION_KEY_OFF = 30;
    public static int EVENT_TYPE_INPUT1_OFF = 31;
    public static int EVENT_TYPE_INPUT2_OFF = 32;
    public static int EVENT_TYPE_INPUT3_OFF = 33;
    public static int EVENT_TYPE_INPUT4_OFF = 34;
    public static int EVENT_TYPE_INPUT5_OFF = 35;
    public static int EVENT_TYPE_INPUT6_OFF = 36;

    public static int EVENT_TYPE_ENGINE_STATUS_ON = 40;
    public static int EVENT_TYPE_LOW_BATTERY_ON = 41;
    public static int EVENT_TYPE_BAD_ALTERNATOR_ON = 42;
    public static int EVENT_TYPE_IDLING_ON = 43;

    public static int EVENT_TYPE_ENGINE_STATUS_OFF = 50;
    public static int EVENT_TYPE_LOW_BATTERY_OFF = 51;
    public static int EVENT_TYPE_BAD_ALTERNATOR_OFF = 52;
    public static int EVENT_TYPE_IDLING_OFF = 53;

    public static int EVENT_TYPE_SPEEDING = 60;
    public static int EVENT_TYPE_ACCELERATING = 61;
    public static int EVENT_TYPE_BRAKING = 62;
    public static int EVENT_TYPE_CORNERING = 63;


    // Events that are triggered by the vehicle .. these will be sent by local broadcast
    public static int EVENT_TYPES_VEHICLE_START = 70;
    public static int EVENT_TYPES_VEHICLE_END = 89;

    public static int EVENT_TYPE_REVERSE_ON = 70;
    public static int EVENT_TYPE_PARKBRAKE_ON = 71;
    public static int EVENT_TYPE_FAULTCODE_ON = 72;
    public static int EVENT_TYPE_FUELSTATUS_PING = 73;
    public static int EVENT_TYPE_REVERSE_OFF = 80;
    public static int EVENT_TYPE_PARKBRAKE_OFF = 81;
    public static int EVENT_TYPE_FAULTCODE_OFF = 82;

    // Events triggered by other apps
    public static int EVENT_TYPE_CUSTOM_PAYLOAD = 90;

    // MT events
    public static int EVENT_TYPE_CONFIGW = 100; // configuration write
    public static int EVENT_TYPE_MOREMAPW = 101; // remap mo write
    public static int EVENT_TYPE_MTREMAPW = 102; // remap mt write
    public static int EVENT_TYPE_CLEAR_QUEUE = 110; // configuration write
    public static int EVENT_TYPE_CLEAR_ODOMETER = 120; // configuration write

    public static int EVENT_TYPE_RESET_FOTA_UPDATER = 125; // clear the FW update files for redbend client (requires resetRB apk)


    public static int EVENT_TYPE_TEST = 200; // just a blank / test message


    // Define the various errors that can be used in the error eventcode message

    public static int ERROR_IO_THREAD_JAMMED = 1; // Internal Watchdog
    public static int ERROR_VBS_SERVICE_JAMMED = 2; // // VBS should be running, but it isn't ?
    public static int ERROR_EXTERNAL_WATCHDOG = 9; // came from a different process .. may lead to loss of information
    public static int ERROR_OTA_STAGE_UDP = 11;
    public static int ERROR_OTA_STAGE_AIRPLANEMODE = 15;
    public static int ERROR_OTA_STAGE_REBOOT = 19;
    public static int ERROR_J1939_NOADDRESSAVAILABLE = 21;


    // Define any event codes that should be ignored
    public static int[] DISABLED_EVENTS = {
            // There are no disabled events
    }; // DISABLED EVENTS

    // Define any event codes that should never be sent over-the-air
    public static int[] NONOTA_EVENTS = {
            EVENT_TYPE_IDLING_ON
    };


} // class Event
