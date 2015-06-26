/////////////////////////////////////////////////////////////
// QueueItem: 
//	This is a single event (item) in the Event Queue
//  It is used as the basis for encoding an outgoing raw data message (done in Codec)
//	It is also used as the result for a decoded incoming messages from server (also done in Codec)
/////////////////////////////////////////////////////////////


package com.micronet.dsc.ats;

import 	java.lang.System;

public class QueueItem {


	// Define the various event codes

    public static int EVENT_TYPE_ACK = 1;    // Ack the designated message matching sequence ID if it is top of queue
    public static int EVENT_TYPE_NAK = 2;	 // There is something wrong with the command from the server
    public static int EVENT_TYPE_ACK_TOP = 3; // Ack the message at top of queue no matter what

    public static int EVENT_TYPE_REBOOT = 5; // System has booted and started the service
    public static int EVENT_TYPE_RESTART = 6; // Service was started and was not already running
    public static int EVENT_TYPE_WAKEUP = 7; // System was sleeping or execution was paused.    

    public static int EVENT_TYPE_HEARTBEAT = 10;
    public static int EVENT_TYPE_PING = 11;
    public static int EVENT_TYPE_ERROR = 12; // some error has occurred in the App
    public static int EVENT_TYPE_CHANGE_SYSTEMTIME = 13; // this app has changed the system time
    public static int EVENT_TYPE_CONFIGURATION_REPLACED = 14; // a configuration file has been replaced/overwritten

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

    public static int EVENT_TYPE_ENGINE_STATUS_OFF = 50;
    public static int EVENT_TYPE_LOW_BATTERY_OFF = 51;
    public static int EVENT_TYPE_BAD_ALTERNATOR_OFF = 52;
    public static int EVENT_TYPE_IDLING_OFF = 53;

    public static int EVENT_TYPE_SPEEDING = 60;
    public static int EVENT_TYPE_ACCELERATING = 61;
    public static int EVENT_TYPE_BRAKING = 62;
    public static int EVENT_TYPE_CORNERING = 63;

    public static int EVENT_TYPE_CONFIGW = 100; // configuration write
    public static int EVENT_TYPE_MOREMAPW = 101; // remap mo write
    public static int EVENT_TYPE_MTREMAPW = 102; // remap mt write
    public static int EVENT_TYPE_CLEAR_QUEUE = 110; // configuration write
    public static int EVENT_TYPE_CLEAR_ODOMETER = 120; // configuration write



    public static int EVENT_TYPE_TEST = 200; // just a blank / test message

    
    // Define the various errors that can be used in the error eventcode message

    public static int ERROR_IO_THREAD_JAMMED = 1;


    // Define any event codes that should be ignored
    
    public static int[] DISABLED_EVENTS = {
		// There are no disabled events        
    }; // DISABLED EVENTS



	// Define variables that make up this class

    private long id;            // Real identifier for the Event, guaranteed to be unique
    public int sequence_id;     // A resettable ID that is placed in the UDP message to identify this event
    public int event_type_id; 	// Type of Event (event code)
    public long trigger_dt;     // DateTime that the event was triggered
    public short battery_voltage;	// voltage in 1/10th volts
    public short input_bitfield;	// describe logical input states
    public long odometer;			// virtual odometer in 
    public int continuous_idle;		// seconds that we have been continuously idle

    public double latitude;
    public double longitude;
    public int speed;				// in cm/s
    public int heading;				// in degrees (0-360)
    public int fix_type;			// bitfield where the time and location came from.
    public boolean is_fix_historic;	
    public int fix_accuracy;		// in meters
    public int sat_count;			//	satelite count
    public int extra;				//	extra byte that has event-code specific meaning


    public int carrier_id;
    public byte signal_strength;
    public byte network_type;
    public boolean is_roaming;

    public byte[] additional_data_bytes; // Note: This does not get saved to Queue Database





	////////////////////////////////////////////
	// Constructor + getter and setters for ID field
    QueueItem() {
        trigger_dt = getSystemDT(); // sent to trigger time to now by default
    }
	
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }



	////////////////////////////////////////////////////
	// clone()
	//	creates a copy of this QueueItem
	//	(this is only used in Unit Tests)
	////////////////////////////////////////////////////
    public QueueItem clone() { 
        QueueItem n = new QueueItem();
        n.id = this.id;
        n.sequence_id = this.sequence_id;
        n.event_type_id = this.event_type_id;
        n.trigger_dt = this.trigger_dt;
        n.battery_voltage = this.battery_voltage;
        n.input_bitfield = this.input_bitfield;
        n.odometer = this.odometer;
        n.continuous_idle = this.continuous_idle;
        n.latitude = this.latitude;
        n.longitude = this.longitude;
        n.speed = this.speed;
        n.heading = this.heading;
        n.fix_type = this.fix_type;
        n.fix_accuracy = this.fix_accuracy;
        n.is_fix_historic = this.is_fix_historic;
        n.sat_count = this.sat_count;
        n.extra = this.extra;

        n.carrier_id = this.carrier_id;
        n.network_type = this.network_type;
        n.signal_strength = this.signal_strength;
        n.is_roaming = this.is_roaming;

        return n;
    }


	/////////////////////////////////////////////////
	// getSystemDT()
	//  Returns: the system time in seconds since epoch
	/////////////////////////////////////////////////
    public static long getSystemDT() { 

        return System.currentTimeMillis() / 1000L;
    }

} // class QueueItem
