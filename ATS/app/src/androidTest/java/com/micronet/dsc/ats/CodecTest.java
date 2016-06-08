package com.micronet.dsc.ats;

import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

import java.util.Arrays;


public class CodecTest extends AndroidTestCase {
    private MainService service;
    private Codec codec;


    public void setUp(){
        RenamingDelegatingContext context
                = new RenamingDelegatingContext(getContext(), "test_");

        CodeMap codeMap = new CodeMap(context);
        codeMap.open();
        codeMap.clearAll();

        service = new MainService(context);

        codec = new Codec(service);



    }

    public void tearDown() throws Exception{

        super.tearDown();
    }





    public void test_aaa() {
        Codec.ByteContainer bc = new Codec.ByteContainer();
        bc.thebyte = -93;
        codec.testWeird(bc);
    }


    public void test_encode() {




        QueueItem queueItem = new QueueItem();
        Ota.ConnectInfo connectInfo = new Ota.ConnectInfo();

        Codec.OutgoingMessage message;


        connectInfo.isRoaming = false;
        connectInfo.networkOperator = "246824";
        connectInfo.networkType = TelephonyManager.NETWORK_TYPE_LTE;
        connectInfo.dataState = TelephonyManager.DATA_CONNECTED;
        connectInfo.phoneType = TelephonyManager.PHONE_TYPE_GSM;
        connectInfo.signalStrength = -93; // Ota.convertGSMStrengthtoDBM(10);

        // For saved carrier info
        queueItem.signal_strength = (byte) connectInfo.signalStrength;
        queueItem.network_type = (byte) connectInfo.networkType;
        queueItem.is_roaming = connectInfo.isRoaming;
        queueItem.carrier_id = Integer.parseInt(connectInfo.networkOperator);


        queueItem.trigger_dt = 1425063620L;

        queueItem.sequence_id = 377;
        queueItem.event_type_id = EventType.EVENT_TYPE_HEARTBEAT;
        queueItem.input_bitfield = 0x43; // ignition on, some inputs on
        queueItem.battery_voltage = 136;
        queueItem.is_fix_historic = true;
        queueItem.fix_accuracy = 5; // really good accuracy (5m)
        queueItem.fix_type = Position.FIX_TYPE_FLAG_LOC_GNSS | Position.FIX_TYPE_FLAG_TIME_EARTH;
        queueItem.sat_count = 5;
        queueItem.continuous_idle = 451;
        queueItem.latitude = 36.12345;
        queueItem.longitude = -117.56789;
        queueItem.odometer = 539131044L; // 539,131,044 meters = 335000.5 miles
        queueItem.speed = 3576; // 3,576 cm/s = 80 mph
        queueItem.heading = 313;

        message = codec.encodeMessage(queueItem, connectInfo);

        // Here are the expected results:

        byte[] expectedMessage = {
            8,                // length of device ID
            48, 48, 48, 48, 48, 48, 48, 48,  // device ID
            (byte) 0x80,                // requires an ACK, application source ID = 0
            0x79, 0x01,       // sequence number 377
            10,               // Heartbeat
            0x28, (byte) 0xC4, 0x3, // carrier ID "246824"
            93,              // signal strength
            13,               // LTE
            (byte) 0xC4, (byte) 0xBE, (byte) 0xF0, 0x54,   // time
            (byte) 136,            // Battery voltage
            0x43,              // Input bitfield
            0x44,0, (byte) 0x88, 0x15, //lat
            0x4C, (byte) 0x90,(byte) 0xEC, (byte) 0xB9, //lon
            (byte) 0xF8, 0x0D,       // speed
            0x39, 1,        // heading
            0x61,             // historic GPS, earth time
            0,0,                // HDOP
            5,                // satelite count
            (byte) 0xA4, 0x7C, 0x22, 0x20, // odom
            (byte) 0xC3, 0x01 // idle
        };

        assertEquals(expectedMessage.length, message.length);
        assertEquals(Arrays.toString(expectedMessage), Arrays.toString(Arrays.copyOf(message.data, message.length)));

        ////////////////////////////////////
        // now add  re-mapping and re-encode
        service.codemap.writeMoEventCode(10, 0x99); // make all 10s appear as 99s.

        message = codec.encodeMessage(queueItem, connectInfo);

        expectedMessage[12] = (byte) 0x99;

        assertEquals(expectedMessage.length, message.length);
        assertEquals(Arrays.toString(expectedMessage), Arrays.toString(Arrays.copyOf(message.data, message.length)));


    } // test_encode()


    public void test_decode() {

        byte[] newMessageData = {
                8,                // length of device ID
                48, 48, 48, 48, 48, 48, 48, 48,  // device ID
                (byte) 0x80,                // requires an ACK
                0x79, 0x01,       // sequence number 377
                100,                 // Config Write
                4, 0,                // data length
                18,               // Idling
                0x33, 0x32, 0x31 // new value: 321
        };

        Codec.IncomingMessage incomingMessage = new Codec.IncomingMessage();


        incomingMessage.data = Arrays.copyOf(newMessageData, newMessageData.length);
        incomingMessage.length = newMessageData.length;

        QueueItem queueItem;
        queueItem = codec.decodeMessage(incomingMessage);


        assertEquals(EventType.EVENT_TYPE_CONFIGW, queueItem.event_type_id);
        assertEquals(377, queueItem.sequence_id);

        byte[] additionalData = {18, 0x33, 0x32, 0x31};
        assertNotNull(queueItem.additional_data_bytes);
        assertEquals(additionalData.length, queueItem.additional_data_bytes.length);
        assertEquals(Arrays.toString(additionalData), Arrays.toString(queueItem.additional_data_bytes));


        ////////////////////////////////////
        // now add  re-mapping and re-decode
        service.codemap.writeMtEventCode(0x98, 100); // make all 0x98s appear as type 100s

        newMessageData[12] = (byte) 0x98;
        incomingMessage = new Codec.IncomingMessage();


        incomingMessage.data = Arrays.copyOf(newMessageData, newMessageData.length);
        incomingMessage.length = newMessageData.length;

        queueItem = codec.decodeMessage(incomingMessage);

        assertEquals(EventType.EVENT_TYPE_CONFIGW, queueItem.event_type_id);
        assertEquals(377, queueItem.sequence_id);

    } // test_decode()


} // CodecTest
