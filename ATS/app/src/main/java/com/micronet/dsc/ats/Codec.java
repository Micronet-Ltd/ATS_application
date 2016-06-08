/////////////////////////////////////////////////////////////
// Codec:
//  Encoding and Decoding of Messages
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import android.content.Context;
import java.util.Arrays;

public class Codec {

    public static final String TAG = "ATS-Codec"; // TAG used for logging

    public static final int SEQUENCE_ID_RECEIVE_MASK = 0xFFFF; // these are the bits that we are capable of receiving for sequence ID
                                                               // Used during message ACKs to determine if there is a sequence ID match

	// Incoming/OutgoingMessage: contains the raw data for 
	//	mobile-terminated (incoming) and mobile-originated (outgoing) messages
    public static final int MAX_INCOMING_MESSAGE_LENGTH = 100;
    public static final int MAX_OUTGOING_MESSAGE_LENGTH = 512; // this allows like 412+ extra data bytes

    public static class IncomingMessage {
        int length = 0;
        byte[] data = new byte[MAX_INCOMING_MESSAGE_LENGTH];
    }

    public static class OutgoingMessage {
        int length = 0;
        byte[] data = new byte[MAX_OUTGOING_MESSAGE_LENGTH];
    }


	// Define various NAKs that can be sent by this application to the server
    public static final int NAK_UNKNOWN_COMMAND = 1;
    public static final int NAK_MISSING_REQUIRED_DATA= 2;
    public static final int NAK_BAD_VALUE_ENCODING = 3;
    public static final int NAK_BAD_SETTING_ID= 4;
    public static final int NAK_ERROR_IN_SAVING= 5;


	// Define assorted constants that are used in the constuction
	final static int THIS_APPLICATION_SOURCE_ID = 0; // ID of this application/source
    final static int SERVICE_TYPE_ACK_REQUIRED_BITVALUE = 0x80;

	// Define lengths of various fields in the data packet, used to construct+deconstruct the data packets
    final static int FIELD_LENGTH_SERIAL_LENGTH = 1;
    final static int FIELD_LENGTH_SERVICE_TYPE = 1;
    final static int FIELD_LENGTH_SEQUENCE_NUM= 2;
    final static int FIELD_LENGTH_EVENT_CODE= 1;
    final static int FIELD_LENGTH_CARRIER_ID= 3;
    final static int FIELD_LENGTH_RSSI= 1;
    final static int FIELD_LENGTH_NETWORK_TYPE= 1;
    final static int FIELD_LENGTH_DATETIME= 4;
    final static int FIELD_LENGTH_BATTERY_VOLTAGE= 1;
    final static int FIELD_LENGTH_INPUT_STATES= 1;

    final static int FIELD_LENGTH_LATITUDE= 4;
    final static int FIELD_LENGTH_LONGITUDE= 4;
    final static int FIELD_LENGTH_SPEED= 2;
    final static int FIELD_LENGTH_HEADING= 2;
    final static int FIELD_LENGTH_FIX_TYPE= 1;
    final static int FIELD_LENGTH_HDOP= 2;
    final static int FIELD_LENGTH_SAT_COUNT= 1;
    final static int FIELD_LENGTH_ODOMETER= 4;
    final static int FIELD_LENGTH_IDLE= 2;

    final static int FIELD_LENGTH_DATA_LENGTH = 2; // length of the  "additional data length" field
    


	// Define some safeties to limit the length of variable fields in case something goes awry
    final static int MAX_SERIAL_LENGTH = 20;
	final static int MAX_SIZE_ADDITIONAL_DATA = 250; 



	///////////////////////////////////////////////////
	// Class constructor and variables to access other info..

    MainService service; // just a reference to the service context

    public Codec(MainService service) {
        this.service = service;
    }


    ////////////////////////////////////////////////////
    // These functions are just used for compiler testing
    ///////////////////////////////////////////////////

    public static class ByteContainer {
        byte thebyte;
    }

    public boolean testWeirdInternal(ByteContainer bc) {

        byte mybyte = bc.thebyte;

        int ss;
        ss = mybyte ;
        byte sb;
        sb = mybyte ;

        //if (mybyte < 0) {
            ss |= 0xFFFFFF00;
        //}

        boolean retval = false;
        if (sb == ss) retval= true;
        return retval;

    }


    public void testWeird(ByteContainer unused) {
        ByteContainer bc = new ByteContainer();
        bc.thebyte = -93;
        testWeirdInternal(unused);

    }


    ///////////////////////////////////////////////////
    ///////////////////////////////////////////////////
    // Encode and Decode functions
    ///////////////////////////////////////////////////


	///////////////////////////////////////////////////
	// encodeMessage()
    // 		takes information about an item in the queue and connection status and 
    //		encodes it according to the Micronet protocol for sending to servers
    // Parameters:
    //		queueItem: contains the status + stored data from the time of event trigger
    //		connectInfo: contains the current status of the cellular connection
    // Returns:
    //		the raw data to send (in the form of an OutgoingMessage class)
    ///////////////////////////////////////////////////
    public OutgoingMessage encodeMessage(QueueItem queueItem, Ota.ConnectInfo connectInfo) {

        Log.vv(TAG, "encodeMessage()");

        OutgoingMessage message = new OutgoingMessage();
        message.length = 0;


        String device_id = service.io.getDeviceId();

        int index;
        index = 0;

        // Serial Number Length
        int serial_length = device_id.length();
        if (serial_length > MAX_SERIAL_LENGTH) serial_length = MAX_SERIAL_LENGTH; // safety only encode first X characters


        message.data[index] = (byte) serial_length ;
        index += FIELD_LENGTH_SERIAL_LENGTH;


        // Serial Number (variable length field)
        int i;
        for (i=0 ; i< serial_length; i++) {
            message.data[index+i] = (byte) device_id.codePointAt(i);
        }
        index += i;

        // service type: contains the source app (= 0 for ATS), and the ack-required bit

        message.data[index] = (byte) (THIS_APPLICATION_SOURCE_ID | SERVICE_TYPE_ACK_REQUIRED_BITVALUE);
        if ((queueItem.event_type_id == EventType.EVENT_TYPE_NAK) ||
             (queueItem.event_type_id == EventType.EVENT_TYPE_ACK))
            message.data[index] = THIS_APPLICATION_SOURCE_ID ; // no ack required on an ack or nak
        index += FIELD_LENGTH_SERVICE_TYPE;

        int sequence_index = queueItem.sequence_id;
        message.data[index] = (byte) (sequence_index & 0xFF);
        message.data[index+1] = (byte) ((sequence_index >> 8) & 0xFF);
        index += FIELD_LENGTH_SEQUENCE_NUM;


        message.data[index] = (byte) service.codemap.mapMoEventCode((byte) queueItem.event_type_id);

        index += FIELD_LENGTH_EVENT_CODE;


        // Cellular Network Information
        if (service.SHOULD_SEND_CURRENT_CELL) {
            // Send current cellular information

            int carrier_id = 0;
            try {
                carrier_id = Integer.parseInt(connectInfo.networkOperator);
            } catch (Exception e) {
                Log.v(TAG, "networkOperator " + connectInfo.networkOperator + " is not a number");
            }
            message.data[index] = (byte) (carrier_id & 0xFF);
            message.data[index + 1] = (byte) ((carrier_id >> 8) & 0xFF);
            message.data[index + 2] = (byte) ((carrier_id >> 16) & 0xFF);
            index += FIELD_LENGTH_CARRIER_ID;


            message.data[index] = (byte) Math.abs(connectInfo.signalStrength);
            if (connectInfo.isRoaming)
                message.data[index] |= 0x80;
            index += FIELD_LENGTH_RSSI;

            message.data[index] = (byte) connectInfo.networkType;
            index += FIELD_LENGTH_NETWORK_TYPE;

        } else {
            // Send stored cellular information from queue
            message.data[index] = (byte) (queueItem.carrier_id & 0xFF);
            message.data[index + 1] = (byte) ((queueItem.carrier_id >> 8) & 0xFF);
            message.data[index + 2] = (byte) ((queueItem.carrier_id >> 16) & 0xFF);
            index += FIELD_LENGTH_CARRIER_ID;

            // DS: There is some unexplained weirdness here where converting from negative byte to an int
            //  fails to set the bits on the high three bytes of the int, causing it to be treated as a positive number
            // To compensate, we have to set the high three bytes manually.

            //message.data[index] = (byte) Math.abs(queueItem.signal_strength);
            int ss = queueItem.signal_strength;
            ss |= 0xFFFFFF00;
            message.data[index] = (byte) Math.abs(ss);

            // DS: END


            if (queueItem.is_roaming)
                message.data[index] |= 0x80;
            index += FIELD_LENGTH_RSSI;

            message.data[index] = (byte) queueItem.network_type;
            index += FIELD_LENGTH_NETWORK_TYPE;
        }

        long trigger_dt = queueItem.trigger_dt;
        message.data[index] = (byte) (trigger_dt & 0xFF);
        message.data[index+1] = (byte) ((trigger_dt >> 8) & 0xFF);
        message.data[index+2] = (byte) ((trigger_dt >> 16) & 0xFF);
        message.data[index+3] = (byte) ((trigger_dt >> 24) & 0xFF);
        index += FIELD_LENGTH_DATETIME;

        if (queueItem.battery_voltage > 255)
            message.data[index] = (byte) 0xFF;
        else
            message.data[index] = (byte) queueItem.battery_voltage;

        index += FIELD_LENGTH_BATTERY_VOLTAGE;

        message.data[index] = (byte) queueItem.input_bitfield;
        index += FIELD_LENGTH_INPUT_STATES;


        // Latitude and Longitude are 4 byte twos complements in 10^-7 units

        double d;
        int l;
        d = queueItem.latitude / .0000001;
        l = (int) d;
        message.data[index] = (byte) (l & 0xFF);
        message.data[index+1] = (byte) ((l >> 8) & 0xFF);
        message.data[index+2] = (byte) ((l >> 16) & 0xFF);
        message.data[index+3] = (byte) ((l >> 24) & 0xFF);
        index += FIELD_LENGTH_LATITUDE;

        d = queueItem.longitude / .0000001;
        l = (int) d;
        message.data[index] = (byte) (l & 0xFF);
        message.data[index+1] = (byte) ((l >> 8) & 0xFF);
        message.data[index+2] = (byte) ((l >> 16) & 0xFF);
        message.data[index+3] = (byte) ((l >> 24) & 0xFF);
        index += FIELD_LENGTH_LONGITUDE;


        message.data[index] = (byte) (queueItem.speed & 0xFF);
        message.data[index+1] = (byte) ((queueItem.speed >> 8) & 0xFF);
        index += FIELD_LENGTH_SPEED;

        message.data[index] = (byte) (queueItem.heading & 0xFF);
        message.data[index+1] = (byte) ((queueItem.heading >> 8) & 0xFF);
        index += FIELD_LENGTH_HEADING;

        message.data[index] = (byte) (queueItem.fix_type & 0xFF);
        if (queueItem.is_fix_historic) // set the historic bit in the fix type field for location
            message.data[index] |= 64;
        else
            message.data[index] &= ~64;

        index += FIELD_LENGTH_FIX_TYPE;


        // convert accuracy in meters to HDOP w/ 10m base precision and 0.1 units
        if (queueItem.fix_accuracy < 10)
            l =0;
        else
            l = queueItem.fix_accuracy - 10;
        if (l > 65535)
            l = 65535; // max value

        message.data[index] = (byte) (l & 0xFF);
        message.data[index+1] = (byte) ((l >>8) & 0xFF);
        index += FIELD_LENGTH_HDOP;

        message.data[index] = (byte) (queueItem.sat_count & 0xFF);
        index += FIELD_LENGTH_SAT_COUNT;


        message.data[index] = (byte) (queueItem.odometer & 0xFF);
        message.data[index+1] = (byte) ((queueItem.odometer >> 8) & 0xFF);
        message.data[index+2] = (byte) ((queueItem.odometer >> 16) & 0xFF);
        message.data[index+3] = (byte) ((queueItem.odometer >> 24) & 0xFF);
        index += FIELD_LENGTH_ODOMETER;


        message.data[index] = (byte) (queueItem.continuous_idle & 0xFF);
        message.data[index+1] = (byte) ((queueItem.continuous_idle >> 8) & 0xFF);
        index += FIELD_LENGTH_IDLE;


        if (queueItem.additional_data_bytes != null) {

            int data_length = queueItem.additional_data_bytes.length;
            message.data[index] = (byte) (data_length & 0xFF);
            message.data[index+1] = (byte) ((data_length >>8) & 0xFF);
            index += FIELD_LENGTH_DATA_LENGTH;

            for (i = 0 ; i < data_length; i++) {
                if (index+i < MAX_OUTGOING_MESSAGE_LENGTH) // safety
                    message.data[index+i] = queueItem.additional_data_bytes[i];
            }

            index += data_length;

            if (index >= MAX_OUTGOING_MESSAGE_LENGTH) // safety
                index = MAX_OUTGOING_MESSAGE_LENGTH-1;

        } else {

            // TODO: THIS IS DEPRECATED way of storing the queue item .. remove this in a few versions
            //  (after there are no more items in existing queues that require it)
            if ((queueItem.event_type_id == EventType.EVENT_TYPE_REBOOT) ||
                    (queueItem.event_type_id == EventType.EVENT_TYPE_ERROR)) {
                // add one more byte (either the wakeup reason or the error reason) to the message
                int data_length = 1; // one more byte of data
                message.data[index] = (byte) (data_length & 0xFF);
                message.data[index+1] = (byte) ((data_length >>8) & 0xFF);
                index += FIELD_LENGTH_DATA_LENGTH;

                message.data[index] = (byte) (queueItem.extra & 0xFF);
                index += data_length;

            } // EVENT_TYPE_REBOOT


        } // additional data was null
        

        message.length = index;

        return message;
    } // encodeMessage()



	///////////////////////////////////////////////////
	// unsigned()
    // 		make sure we are treating a byte as unsigned
    //		(since java doesn't have native unsigned types)
    ///////////////////////////////////////////////////
    private static int unsigned(byte b) {
        return (b & 0xFF);
    }

    
	///////////////////////////////////////////////////
	// decodeMessage()
    // 		takes raw data coming in from server and decodes it
    //		checks that it is for this device, etc..
    // Parameters:
    //		message: the raw data from the server (in form of a IncomingMessage class)    
    // Returns:
    //		QueueItem: the decoded message (eventcode and extracted extra data)
    //		or NULL (if this data cannot be decoded into a message that is for us)
    ///////////////////////////////////////////////////    
    public QueueItem decodeMessage(IncomingMessage message) {

        Log.vv(TAG, "decodeMessage()");

        QueueItem item = new QueueItem();


        int index;

        index = 0;

        if (message.length < index + FIELD_LENGTH_SERIAL_LENGTH) return null; // this can't be a message
        int serial_length = unsigned(message.data[index]);

        index += FIELD_LENGTH_SERIAL_LENGTH;
        if (serial_length >= MAX_SERIAL_LENGTH) return null; // something wrong with this message

        if (message.length < index + serial_length) return null; // we can't have the full serial number


        // compare the received serial number to our own
        String device_id = service.io.getDeviceId();
        int i;

        if (serial_length != device_id.length()) {
            Log.w(TAG, "Received Serial Number has wrong length (expected " + device_id.length() + " was " + serial_length);
            return null;

        }

        for(i =0 ; i < serial_length && i < device_id.length(); i++) {
            if (message.data[index+i] != (byte) device_id.codePointAt(i)) {
                Log.w(TAG, "Received Serial Number does not match at pos " + i + " (expected " + device_id.codePointAt(i) + " was " + message.data[index+i]);
                return null;
            }
        }
        index += i; // serial is a variable length field

        if (message.length < index + FIELD_LENGTH_SEQUENCE_NUM) {
            Log.w(TAG, "Received message is too short to contain sequence number (only " + message.length + " bytes)");
            return null; // this can't be a message, and we can't ACK or NAK it b/c we can't determine the sequence number.
        }


        int service_type = unsigned(message.data[index]);
        index += FIELD_LENGTH_SERVICE_TYPE;

        if ((service_type & 0x7F) != THIS_APPLICATION_SOURCE_ID){
            Log.w(TAG, "Application source does not match (expected " + THIS_APPLICATION_SOURCE_ID + " was " + (service_type & 0x7F));
            return null; // we still don't have a sequence num, so cannot ACK or NAK
        }

        // ignore the ack required field (service_Type & SERVICE_TYPE_ACK_REQUIRED_BITVALUE)
        // we will ACK everything except an ACK or NAK event type.

        int sequence_id;
        sequence_id = (unsigned(message.data[index+1]) << 8) + unsigned(message.data[index]);
        item.sequence_id = sequence_id;
        index += FIELD_LENGTH_SEQUENCE_NUM;

        item.event_type_id  = service.codemap.mapMtEventCode(unsigned(message.data[index]));
        index += FIELD_LENGTH_EVENT_CODE;

        item.trigger_dt = QueueItem.getSystemDT();


        int additional_data_length;
        additional_data_length = (unsigned(message.data[index+1]) << 8) + unsigned(message.data[index]);
        index += FIELD_LENGTH_DATA_LENGTH;

        if (additional_data_length > 0) {
            if
                ((additional_data_length + index <= message.length) &&
                (additional_data_length + index <= message.data.length) &&
                (additional_data_length < 250)) { // just a safety for max size
                item.additional_data_bytes = Arrays.copyOfRange(message.data, index, index + additional_data_length);
            }
        }

        Log.i(TAG, "Decoded Message: Seq " + item.sequence_id + " , Type: " + item.event_type_id + ", x-data: " +
                (item.additional_data_bytes != null ?
                " len " + item.additional_data_bytes.length + " " + Arrays.toString(item.additional_data_bytes):
                "0")
        );

        return item;

    } // decodeMessage()


    ///////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////
    // Encoding of individual Additional Data Variables



    ///////////////////////////////////////////////////
    // encodeDataAllFuel()
    //  returns a byte array that contains fuel data (both total and average)
    ///////////////////////////////////////////////////
    byte[] encodeDataAllFuel() {
        byte[] fuelData= new byte[6];
        // Total Fuel (4 bytes) liters
        // Average Fuel (2 bytes) meters per liter

        long f = service.engine.status.fuel_mL / 1000;

        if (f >= 4294967296L) f = 4294967295L; // max value

        fuelData[0] = (byte) (f & 0xFF);
        f >>= 8;
        fuelData[1] = (byte) (f & 0xFF);
        f >>= 8;
        fuelData[2] = (byte) (f & 0xFF);
        f >>= 8;
        fuelData[3] = (byte) (f & 0xFF);
        f >>= 8;

        f = service.engine.status.fuel_mperL;

        if (f >= 65536) f = 65535; // max value

        fuelData[4] = (byte) (f & 0xFF);
        f >>= 8;
        fuelData[5] = (byte) (f & 0xFF);
        f >>= 8;


        return fuelData;
    } // encodeDataFuel()


    ///////////////////////////////////////////////////
    // encodeDataOdometer()
    //  returns a byte array that contains actual odometer
    ///////////////////////////////////////////////////
    byte[] encodeDataOdometer() {
        byte[] odomData= new byte[4];
        long f = service.engine.status.odometer_m;

        if (f >= 4294967296L) f = 4294967295L; // max value

        odomData[0] = (byte) (f & 0xFF);
        f >>= 8;
        odomData[1] = (byte) (f & 0xFF);
        f >>= 8;
        odomData[2] = (byte) (f & 0xFF);
        f >>= 8;
        odomData[3] = (byte) (f & 0xFF);
        f >>= 8;

        return odomData;
    } // encodeDataOdometer()

    ///////////////////////////////////////////////////
    // encodeDataVin()
    //  returns a byte array that contains the VIN, preceded by byte count
    ///////////////////////////////////////////////////
    byte[] encodeDataVin() {

        int vinlen = service.engine.vin.length();
        if (vinlen > 255) vinlen = 255; // we will return a max length of 255.

        byte[] vinData = new byte[vinlen+1];

        vinData[0] = (byte) (vinlen & 0xFF);


        for (int i=0; i < vinlen; i++) {
            vinData[i+1] = (byte) service.engine.vin.charAt(i);
        }

        return vinData;
    } // encodeDataVin()




    ///////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////
    // Encoding of data variable combinations for specific message



    ///////////////////////////////////////////////////
    // dataForEngineOn()
    //  returns a byte array that contains data for Engine Status On
    ///////////////////////////////////////////////////
    byte[] dataForEngineOn() {

        byte[] vin = encodeDataVin();
        byte[] odom = encodeDataOdometer();
        byte[] fuel = encodeDataAllFuel();

        byte[] data = new byte[1 + vin.length + odom.length + fuel.length];
        int data_pos = 0;
        System.arraycopy(new byte[] {service.engine.getBusCommunicating()}, 0, data, data_pos, 1);
        data_pos += 1;
        System.arraycopy(vin, 0, data, data_pos, vin.length);
        data_pos += vin.length;

        System.arraycopy(odom, 0, data, data_pos, odom.length);
        data_pos += odom.length;

        System.arraycopy(fuel, 0, data, data_pos, fuel.length);
        data_pos += fuel.length;

        return data;

    } // dataForEngineOn()


    ///////////////////////////////////////////////////
    // dataForIgnitionOff()
    //  returns a byte array that contains data for Ignition Off message
    ///////////////////////////////////////////////////
    byte[] dataForIgnitionOff() {

        byte[] odom = encodeDataOdometer();
        byte[] fuel = encodeDataAllFuel();

        byte[] data = new byte[odom.length + fuel.length];
        int data_pos = 0;
        System.arraycopy(odom, 0, data, data_pos, odom.length);
        data_pos += odom.length;

        System.arraycopy(fuel, 0, data, data_pos, fuel.length);
        data_pos += fuel.length;

        return data;

    } // dataForIgnitionOff()


    ///////////////////////////////////////////////////
    // dataForFuelStatus()
    //  returns a byte array that contains data for Ignition Off message
    ///////////////////////////////////////////////////
    byte[] dataForFuelStatus() {
        return encodeDataAllFuel();
    } // dataForFuelStatus()


    } // Codec class
