/////////////////////////////////////////////////////////////
// QueueItem: 
//	This is a single event (item) in the Event Queue
//  It is used as the basis for encoding an outgoing raw data message (done in Codec)
//	It is also used as the result for a decoded incoming messages from server (also done in Codec)
/////////////////////////////////////////////////////////////


package com.micronet.dsc.ats;

import 	java.lang.System;

public class QueueItem {



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

    public byte[] additional_data_bytes = null;




	////////////////////////////////////////////
	// Constructor + getter and setters for ID field
    QueueItem() {
        trigger_dt = getSystemDT(); // sent to trigger time to now by default
    }

    ///////////////////////////////////////////////////
    //  ID is a unique ID for the item, different from the server_id or sequence_id
    ///////////////////////////////////////////////////
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
