package com.micronet.dsc.ats;

import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;


import java.util.Arrays;


/**
 * Created by dschmidt on 12/7/15.
 */
public class J1939Test extends AndroidTestCase {

    private MainService service;
    private J1939 j1939;
    TestCommon test;

    public void setUp() {
        RenamingDelegatingContext context
                = new RenamingDelegatingContext(getContext(), "test_");

        Config config = new Config(context);
        State state = new State(context);

        // clear config and state info to the default before init'ing IO
        config.open();
        config.clearAll();
        state.clearAll();

        service = new MainService(context);

        j1939 = new J1939(service.engine, true, 123456);

        service.queue.clearAll();
        service.clearEventSequenceIdNow();

        test = new TestCommon(service.queue);


    } // setup


    public void tearDown() throws Exception {


        super.tearDown();
    }

    /////////////////////////////////////////////////////////
    // isInCanQueue()
    //  Returns true if the given frame is in the can queue
    /////////////////////////////////////////////////////////
    public boolean isInCanQueue(int frameId, byte[] data) {

        if (j1939.outgoingList.isEmpty()) return false;
        J1939.CanFrame f;
        for (int i= 0 ; i < j1939.outgoingList.size(); i++) {
            f = j1939.outgoingList.get(i);
            if ( ((f.id & 0xFFFFFF) == (frameId & 0xFFFFFF)) &&
                    (Arrays.equals(f.data, data))
                    )
                return true; // it's here!
        }
        return false;

    } // isInCanQueue()


    public void test_packet2frame() {

        J1939.CanPacket packet;
        J1939.CanFrame frame;

        packet = new J1939.CanPacket();

        final byte[] STATIC_DATA = new byte[]{01, 02, 80, 81};

        packet.protocol_format = 0xAA;
        packet.destination_address = 0xBB;
        packet.priority = 5;
        packet.source_address = 0xF0;
        packet.data = STATIC_DATA;

        frame = j1939.packet2frame(packet, 4);

        // frame ID should be equal to :
        // Pri(3) Reserved (1) DP (1) PF (8), PS (8), SA (8)

        // 10100 10101010 10111011 11110000 = 0x14AABBF0
        //assertEquals(frame.getType(), CanbusFrameType.EXTENDED);
        assertEquals(Log.bytesToHex(frame.data, frame.data.length), Log.bytesToHex(STATIC_DATA, STATIC_DATA.length));
        assertEquals(frame.id, 0x14AABBF0);

    } // test_packet2frame


    public void test_frame2packet() {

        J1939.CanPacket packet;
        J1939.CanFrame frame;

        final byte[] STATIC_DATA = new byte[]{80, 01, 42, 94};
        frame = new J1939.CanFrame();
        frame.id = 0x14AABBF0;
        frame.data = STATIC_DATA;

        packet = j1939.frame2packet(frame);

        j1939.packet2frame(packet, 8);

        // frame ID should be equal to :
        // Pri(3) Reserved (1) DP (1) PF (8), PS (8), SA (8)

        // 10100 10101010 10111011 11110000 = 0x14AABBF0
        assertEquals(packet.priority, 5);
        assertEquals(packet.protocol_format, 0xAA);
        assertEquals(packet.destination_address, 0xBB);
        assertEquals(packet.source_address, 0xF0);
        assertEquals(packet.getPGN(), 0xAA00); // lower byte is 0 if high byte < E0
        assertEquals(Log.bytesToHex(packet.data, packet.data.length), Log.bytesToHex(STATIC_DATA, STATIC_DATA.length));


        frame = new J1939.CanFrame(0x14F2BBF0, STATIC_DATA);

        packet = j1939.frame2packet(frame);
        assertEquals(packet.getPGN(), 0xF2BB); // high byte > E0

    } // test_frame2packet


    public void test_receivePacketPGNs() {

        J1939.CanPacket packet = new J1939.CanPacket();

        j1939.myAddress = 0xBB; // just some random address

        // PGN_GEAR
        packet.setPGN(0xF005);
        packet.source_address = 0xF0;
        packet.data = new byte[] {0x7D ,0 ,0, 0x7D, 0, 0, 0, 0}; // <- 4th byte is current gear, 7D is neutral

        assertEquals(1, j1939.receivePacket(packet)); // 1 means parsed as PGN
        assertFalse(j1939.engine.status.flagReverseGear);
    }

    public void test_receiveAddressRequest() {



        J1939.CanFrame frame;
        byte[] expected_data = new byte[8]; // expected response of the address claim packet
        j1939.long2LittleEndian(j1939.J1939_NAME, expected_data, 0, 8);

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address

        // priority reserved and dp = 10100 = 14
        // protocol format = EA (request)
        // destination address = 255 global
        // source address = n254 null

        // if destination is my address then we respond
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());
        frame = new J1939.CanFrame(0x14EABBFE, new byte[] {0 ,(byte) 0xEE ,0, 0, 0, 0, 0, 0});
        assertEquals(2, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(isInCanQueue(0xEEFFBB, expected_data));

        // If destination is global, then we respond after a delay (delay is skipped during testing)
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());
        frame = new J1939.CanFrame(0x14EAFFFE, new byte[] {0 ,(byte) 0xEE ,0, 0, 0, 0, 0, 0});
        assertEquals(2, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        //assertTrue(j1939.mainHandler.hasMessages(1));
        assertTrue(isInCanQueue(0xEEFFBB, expected_data));


        // if destination is not my address, then we dont respond
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());
        frame = new J1939.CanFrame(0x14EAC0FE, new byte[] {0 ,(byte) 0xEE ,0, 0, 0, 0, 0, 0});
        assertEquals(0, j1939.receiveCANFrame(frame)); // 0 means rejected
        assertTrue(j1939.outgoingList.isEmpty());

        // if we haven't attempted to claim and address than we don't respond
        j1939.outgoingList.clear();
        j1939.myAddress = J1939.J1939_ADDRESS_NULL; // just some random address
        j1939.addressClaimAttempted = false;

        frame = new J1939.CanFrame(0x14EAFFFE, new byte[] {0 ,(byte) 0xEE ,0, 0, 0, 0, 0, 0});
        assertEquals(2, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty());

        // however, if we have attempted to claim an address and failed, then we should respond
        j1939.outgoingList.clear();
        j1939.addressClaimAttempted = true;
        j1939.myAddress = J1939.J1939_ADDRESS_NULL; // just some random address

        frame = new J1939.CanFrame(0x14EAFFFE, new byte[] {0 ,(byte) 0xEE ,0, 0, 0, 0, 0, 0});
        assertEquals(2, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(isInCanQueue(0xEEFFFE, expected_data));


    } // test_receiveAddressRequest()

    public void test_parseTroubleCode() {

        byte[] input = new byte[] { 0x20, 0x21, 0x22, 0x23, (byte) 0x90, (byte) 0x91, (byte) 0x92, (byte) 0x93};
        long res;


        // For J1939 DTCs:
        //  occurence_count is 7 LSbits of final (4th) byte

        J1939.Dtc dtc;
        dtc = j1939.parseTroubleCode(input, 0);
        assertEquals(dtc.dtc_value, 0x00222120L);
        assertEquals(dtc.occurence_count, 0x23);

        dtc = j1939.parseTroubleCode(input, 4);
        assertEquals(dtc.dtc_value, 0x80929190L);
        assertEquals(dtc.occurence_count, 0x13);
    } // test_parseTroubleCode()


    public void test_receiveDM1() {
        // test the ability of the code to collect DM1 broadcasts into DTCs
        // the DM1 frames could be received by both BAM and by normal frame (PGN)

        J1939.CanFrame frame;

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address
        byte[] expected_response;


        /////
        // First send the TP Connect Request
        j1939.outgoingList.clear();
        j1939.clearCollectedDtcs();



        assertTrue(j1939.outgoingList.isEmpty());

        // DTC PGN  = 0x00FECA
        // TP.CM_BAM is ID 32
        // message is 10 bytes which takes 2 packets
        // FF = reserved


        frame = new J1939.CanFrame(0x14ECFFCC, new byte[] {32 , 10 ,0, 2, (byte) 0xFF, (byte) 0xCA, (byte) 0xFE, 0});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        // Since this is broadcast, there should be no response from us
        assertTrue(j1939.outgoingList.isEmpty());

        // First Data packet
        // first byte is MILs, second byte is reserved, then 4 byte DTCs
        frame = new J1939.CanFrame(0x14EBFFCC, new byte[] {1 , 0 , 0, 0x61, 0x62, 0x63, 0x64, (byte) 0x85});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty());


        // Next (last Data packet)
        frame = new J1939.CanFrame(0x14EBFFCC, new byte[] {2 , (byte) 0x86, (byte) 0x87, (byte) 0x88,  (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty());


        // Verify that the DTCs were received correctly
        assertEquals(j1939.collectedDtcs.size(), 2);


        // J1939 DTCs
        //  occurence_count is 7 LSbits of final (4th) byte

        // DTC #1 was sent as 0x61, 0x62, 0x63, 0x64
        assertEquals(j1939.collectedDtcs.get(0).dtc_value, (long) 0x00636261L);
        assertEquals(j1939.collectedDtcs.get(0).occurence_count, (0x64 & 0x7F));

        // DTC #1 was sent as 0x85, 0x87, 0x88, 0x88
        assertEquals(j1939.collectedDtcs.get(1).dtc_value, (long) 0x80878685L);
        assertEquals(j1939.collectedDtcs.get(1).occurence_count, (0x88 & 0x7F));


        /////////////
        // Give a duplicate (different occurrence count) and make sure that it is not added as new item.

        // priority reserved and dp = 10100 = 14
        // DTC PGN  = 0x00FECA
        // source address = CD (random, but differnet than above)
        // Data:    First two byutes are lamp status
        //          followed by 4 byte DTC

        frame = new J1939.CanFrame(0x14FECACD, new byte[] {0 , 0 , 0x61, 0x62,0x63,0x65, (byte) 0xFF, (byte) 0xFF});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        // Since this is broadcast, there should be no response from us
        assertTrue(j1939.outgoingList.isEmpty());

        // check that nothing was added
        assertEquals(j1939.collectedDtcs.size(), 2);
        // but occurrence count has changed on first dtc
        assertEquals(j1939.collectedDtcs.get(0).dtc_value, (long) 0x00636261L);
        assertEquals(j1939.collectedDtcs.get(0).occurence_count, (0x65 & 0x7F));


        /////////////
        //  Now give a new DTC and see that it is added

        frame = new J1939.CanFrame(0x14FECACD, new byte[] {0 , 0 , 0x41, 0x42,0x43, (byte) 0x81, (byte) 0xFF, (byte) 0xFF});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        // Since this is broadcast, there should be no response from us
        assertTrue(j1939.outgoingList.isEmpty());


        // check that it was added, but others remain unchanged
        assertEquals(j1939.collectedDtcs.size(), 3);
        assertEquals(j1939.collectedDtcs.get(0).dtc_value, (long) 0x00636261L);
        assertEquals(j1939.collectedDtcs.get(0).occurence_count, (0x65 & 0x7F));
        assertEquals(j1939.collectedDtcs.get(1).dtc_value, (long) 0x80878685L);
        assertEquals(j1939.collectedDtcs.get(1).occurence_count, (0x88 & 0x7F));
        assertEquals(j1939.collectedDtcs.get(2).dtc_value, (long) 0x80434241L);
        assertEquals(j1939.collectedDtcs.get(2).occurence_count, 1);


    } // test_receiveDM1()


    public void test_processCollectedDTCs() {
        // Tests ability to process the collected DTCs

        // setup

        j1939.myBusType = Engine.BUS_TYPE_J1939_250K;
        j1939.engine.current_dtcs.clear();
        j1939.collectedDtcs.clear();

        J1939.Dtc dtc1 = new J1939.Dtc();
        J1939.Dtc dtc2 = new J1939.Dtc();
        J1939.Dtc dtc3 = new J1939.Dtc();

        dtc1.occurence_count = 3;
        dtc2.occurence_count = 5;
        dtc3.occurence_count = 0x74;
        dtc1.dtc_value = 0x80121314L;
        dtc2.dtc_value = 0x00272829L;
        dtc3.dtc_value = 0x803A3B3CL;

        j1939.addDtc(dtc1);
        j1939.addDtc(dtc2);
        j1939.addDtc(dtc3);

        assertEquals(j1939.engine.current_dtcs.size(), 0);
        assertEquals(j1939.collectedDtcs.size(), 3);

        // process these (move them to the engine DTC collection)
        j1939.processCollectedDTCs();

        assertEquals(j1939.collectedDtcs.size(), 0);
        assertEquals(j1939.engine.current_dtcs.size(), 3);
        assertEquals(Engine.BUS_TYPE_J1939_250K, j1939.engine.current_dtcs.get(0).bus_type);
        assertEquals(0x80121314L, j1939.engine.current_dtcs.get(0).dtc_value);
        assertEquals(Engine.BUS_TYPE_J1939_250K, j1939.engine.current_dtcs.get(1).bus_type);
        assertEquals(0x00272829L, j1939.engine.current_dtcs.get(1).dtc_value);
        assertEquals(Engine.BUS_TYPE_J1939_250K, j1939.engine.current_dtcs.get(2).bus_type);
        assertEquals(0x803A3B3CL, j1939.engine.current_dtcs.get(2).dtc_value);


    } // test_processCollectedDTCs()

    public void test_receiveVIN() {
        // test the ability of the code to receive a VIN over the transport protocol

        J1939.CanFrame frame;

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address
        byte[] expected_response;


        /////
        // First send the TP Connect Request
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());

        // VIN is PGN 0x00FEEC
        // TP.CM_RTS is ID 16
        // response is 17 bytes, which takes 3 packets
        // accepting 5 max packets per burst

        frame = new J1939.CanFrame(0x14ECBB00, new byte[] {16 ,17 ,0, 3, 5, (byte) 0xEC, (byte) 0xFE, 0});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        // we should have sent a CTS
        // TP_CTS = 17
        // 1 packet per burst
        //  Next packet #1
        // FF FF reserved
        // PGN
        expected_response = new byte[] {17, 1, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xEC, (byte) 0xFE, 0} ; // expected response of the address claim packet
        assertTrue(isInCanQueue(0xEC00BB, expected_response));


        /////
        // Now Send First Data Packet
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());

        frame = new J1939.CanFrame(0x14EBBB00, new byte[] {1 , 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        // we should have send the ACK
        expected_response = new byte[] {17, 1, 2, (byte) 0xFF, (byte) 0xFF, (byte) 0xEC, (byte) 0xFE, 0} ; // expected response of the address claim packet
        assertTrue(isInCanQueue(0xEC00BB, expected_response));


        /////
        // Now Send Next Data Packet
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());

        frame = new J1939.CanFrame(0x14EBBB00, new byte[] {2 , 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        // we should have sent the ACK
        expected_response = new byte[] {17, 1, 3, (byte) 0xFF, (byte) 0xFF, (byte) 0xEC, (byte) 0xFE, 0} ; // expected response
        assertTrue(isInCanQueue(0xEC00BB, expected_response));


        /////
        // Now Send Last Data Packet
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());

        frame = new J1939.CanFrame(0x14EBBB00, new byte[] {3 , 0x4F, 0x50, 0x51, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        // we should have sent the EOM
        expected_response = new byte[] {19, 17, 0, 3, (byte) 0xFF, (byte) 0xEC, (byte) 0xFE, 0} ; // expected response
        assertTrue(isInCanQueue(0xEC00BB, expected_response));


        // we should also have our new vin
        assertEquals(j1939.engine.vin, "ABCDEFGHIJKLMNOPQ");

    } // test_receiveVIN


    public void test_global_receiveVIN() {
        // test the ability of the code to receive a VIN over the transport protocol to a glboal address
        // follow this with something else on the global address to make sure it doesnt interfere

        J1939.CanFrame frame;

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address

        ///////////////////////////////////////////////
        ///////////////////////////////////////////////
        // Send VIN to global


        /////
        // First send the TP Connect Request
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());

        // VIN is PGN 0x00FEEC
        // TP.CM_RTS is ID 16
        // response is 17 bytes, which takes 3 packets
        // accepting 5 max packets per burst

        frame = new J1939.CanFrame(0x14ECFF00, new byte[] {16 ,17 ,0, 3, 5, (byte) 0xEC, (byte) 0xFE, 0});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed


        frame = new J1939.CanFrame(0x14EBFF00, new byte[] {1 , 0x51, 0x50, 0x4F, 0x4E, 0x4D, 0x4C, 0x4B});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed


        frame = new J1939.CanFrame(0x14EBFF00, new byte[] {2 , 0x4A, 0x49, 0x48, 0x47, 0x46, 0x45, 0x44});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed

        frame = new J1939.CanFrame(0x14EBFF00, new byte[] {3 , 0x43, 0x42, 0x41, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed


        // we should also have our new vin
        assertEquals(j1939.engine.vin, "QPONMLKJIHGFEDCBA");







        //////////////////////////////////////////////////
        //////////////////////////////////////////////////
        // GIVE some OTHER transported PGN after the VIN
        //      make sure this doesn't take over the prior connection and overwrite the values
        //      this happened once in the field.

        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());

        J1939.CanFrame frame1 = new J1939.CanFrame(0x18ecff00, new byte[] { 0x20, 0x22, 0x00, 0x05, (byte) 0xFF, (byte) 0xE3, (byte) 0xFE, 0x00});
        J1939.CanFrame frame2 = new J1939.CanFrame(0x18ebff00, new byte[] { 0x01, 0x00, 0x19, (byte) 0xAE, 0x40, 0x51, (byte) 0xC5, 0x08});
        J1939.CanFrame frame3 = new J1939.CanFrame(0x18ebff00, new byte[] { 0x02, 0x20, (byte) 0xBD, (byte) 0xC0, 0x2B, (byte) 0xCF, (byte) 0xC0, 0x44});
        J1939.CanFrame frame4 = new J1939.CanFrame(0x18ebff00, new byte[] { 0x03, (byte) 0xD0, 0x40, 0x51, (byte) 0xFF, (byte) 0xFF, (byte) 0xB9, 0x04});
        J1939.CanFrame frame5 = new J1939.CanFrame(0x18ebff00, new byte[] { 0x04, 0x38, 0x5E, 0x3C, 0x46, (byte) 0xFA, 0x7D, (byte) 0xCF});
        J1939.CanFrame frame6 = new J1939.CanFrame(0x18ebff00, new byte[] { 0x05, 0x40, 0x51, (byte) 0x86, 0x00, 0x2A, 0x03, (byte) 0xFF});



        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame1));
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame2));
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame3));
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame4));
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame5));
        assertEquals(J1939.PARSED_PACKET_TYPE_CONTROL, j1939.receiveCANFrame(frame6));

        // nothing in queue
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed

        // Vin has not changed
        assertEquals(j1939.engine.vin, "QPONMLKJIHGFEDCBA");


    } // test_global_receiveVIN()



    public void test_receiveFuelConsumption() {
        // test the ability of the code to receive a Fuel Consumption value

        // Setup
        J1939.CanFrame frame;

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());


        // Testing

        // Fuel Consumption is PGN 0x00FEE9
        // Data:
        //      Bytes 1- 4 : Trip Fuel
        //      Bytes 5-8 : Total Fuel 0.5 L/bit gain, 0 L offset


        frame = new J1939.CanFrame(0x14FEE9CC, new byte[] {0 ,0 ,0, 0, (byte) 0xFF, 0x30, 0x40, (byte) 0x80});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed

        assertEquals(0x804030FFL * 500, j1939.engine.status.fuel_mL); // 500 is conversion to mL


        // Now test unknown value of all FF

        frame = new J1939.CanFrame(0x14FEE9CC, new byte[] {0 ,0 ,0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet


        // result should be unchanged
        assertEquals(0x804030FFL * 500, j1939.engine.status.fuel_mL); // 500 is conversion to mL

    } // test_receiveFuelConsumption()


    public void test_receiveFuelEconomy() {
        // test the ability of the code to receive a Fuel Economy value

        // Setup
        J1939.CanFrame frame;

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());


        // Testing

        // Fuel Economy is PGN 0x00FEF2
        // Data:
        //      Bytes 5-6 : Average Fuel Econ 1/512 km/L per bit gain, 0 km/L offset



        frame = new J1939.CanFrame(0x14FEF2CC, new byte[] {0 ,0 ,0, 0, (byte) 0x80, (byte) 0xF0, 0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed


        assertEquals(0xF080 * 1000 / 512, j1939.engine.status.fuel_mperL); // 1000 / 512 is conversion to mperL


        // Now test unknown value of all FF

        frame = new J1939.CanFrame(0x14FEF2CC, new byte[] {0 ,0 ,0, 0, (byte) 0xFF, (byte) 0xFF, 0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet

        // unchanged
        assertEquals(0xF080 * 1000 / 512, j1939.engine.status.fuel_mperL); // 1000 / 512 is conversion to mperL
    } // test_receiveFuelEconomy()



    public void test_receiveParkingBrake() {
        // test the ability of the code to receive the parking brake on/off

        // Setup
        J1939.CanFrame frame;

        // set the default if we have mixed (conflicting) reports on parking brake status
        service.config.writeSetting(Config.SETTING_PARKING_BRAKE, "3|1");

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());

        // Park Brake is PGN 0x00FEF1
        // Data:
        //      Byte 1 , bits 4-3 = parking brake switch
        //                      00 = not set, 01 = set

        // Whatever we receive goes into a 5-deep history buffer. Current status is based on whole history.

        // Place four entries in history, until we receive the first five entries status does not change
        for (int i = 0; i < 4; i++) {
            frame = new J1939.CanFrame(0x14FEF117, new byte[]{0b00000100, 0, 0, 0, 0, 0, 0, 0});
            assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
            assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
            assertFalse(j1939.engine.status.flagParkingBrake); // unchanged
        }



        // Turn it on -- 5th consecutive entry of same value
        frame = new J1939.CanFrame(0x14FEF1CC, new byte[] {0b00000100 ,0 ,0, 0, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertTrue(j1939.engine.status.flagParkingBrake);


        // Testing -- And receive some offs so that we are now conflicting
        for (int i = 0; i < 4; i++) {
            frame = new J1939.CanFrame(0x14FEF1CC, new byte[]{0b00000000, 0, 0, 0, 0, 0, 0, 0});
            assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
            assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
            assertTrue(j1939.engine.status.flagParkingBrake); // still on --- default in case of conflict
        }

        // Last one means all in buffer are off
        frame = new J1939.CanFrame(0x14FEF1CC, new byte[]{0b00000000, 0, 0, 0, 0, 0, 0, 0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertFalse(j1939.engine.status.flagParkingBrake); // still on --- default in case of conflict



        // Turn On: (Actual data from vehicle)
        for (int i = 0; i < 6; i++) {
            frame = new J1939.CanFrame(0x14FEF1CC, new byte[]{(byte) 0xF7, (byte) 0xFF, (byte) 0xFF, (byte) 0xC3, 0x00, (byte) 0xFF, (byte) 0xFF, 0x3F});
            assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
            assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
            assertTrue(j1939.engine.status.flagParkingBrake); // default is on, so after first one there is a conflict and we are on
        }



        // Unchanged (actual data from vehicle)
        for (int i = 0; i < 6; i++) {
            frame = new J1939.CanFrame(0x14FEF1CC, new byte[]{(byte) 0xFF, 0x00, 0x00, (byte) 0xFC, 0x33, 0x58, 0x00, (byte) 0xCF});
            assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
            assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
            assertTrue(j1939.engine.status.flagParkingBrake);
        }


        // And Turn Off (actual data from vehicle)
        //F3FF FF D300FFFF3F^
        for (int i = 0; i < 6; i++) {
            frame = new J1939.CanFrame(0x14FEF1CC, new byte[]{(byte) 0xF3, (byte) 0xFF, (byte) 0xFF, (byte) 0xD3, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0x3F});
            assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
            assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        }
        assertFalse(j1939.engine.status.flagParkingBrake); // we will only turn off after we clear through history


        // Unchanged
        for (int i = 0; i < 6; i++) {
            frame = new J1939.CanFrame(0x14FEF1CC, new byte[]{(byte) 0xFF, 0x00, 0x00, (byte) 0xFC, 0x33, 0x58, 0x00, (byte) 0xCF});
            assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
            assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
            assertFalse(j1939.engine.status.flagParkingBrake);
        }



    } // test_receiveParkingBrake()

    public void test_receiveReverseGear() {
        // test the ability of the code to receive whether we are in reverse or not

        // Setup
        J1939.CanFrame frame;

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());


        // Testing -- Turn it on

        // Gear is PGN 0x00F005
        // Data:
        //      Byte 1 selected gear
        //      Byte 4 current gear
        //              125 (x7D) is neutral
        //              < 125 is reverse
        //              > 125 is forward
        //              251 = Park

        //Neutral
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0,0x7D, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertFalse(j1939.engine.status.flagReverseGear);


        // Reverse 1
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0,0x7C, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertTrue(j1939.engine.status.flagReverseGear);


        // Now test unchanged
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0,(byte) 0xFF, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertTrue(j1939.engine.status.flagReverseGear);


        // Forward 1
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0,0x7E, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertFalse(j1939.engine.status.flagReverseGear);


        // Now test unchanged
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0,(byte) 0xFF, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertFalse(j1939.engine.status.flagReverseGear);


        // Reverse 2
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0,0x7B, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertTrue(j1939.engine.status.flagReverseGear);

        // Park
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0, (byte) 0xFB, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertFalse(j1939.engine.status.flagReverseGear);

        // Reverse 3
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0,0x7A, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertTrue(j1939.engine.status.flagReverseGear);

        // Forward 4
        frame = new J1939.CanFrame(0x14F005CC, new byte[] {0,0,0,(byte) 0x81, 0,0,0,0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertFalse(j1939.engine.status.flagReverseGear);


    } // test_receiveReverseGear()



    public void test_receiveOdometer() {
        // test the ability of the code to receive an Odometer value over the transport protocol
        // Check both Low Res and Hi-Res Odometers (Hi-Res will take precendence)

        // Setup
        J1939.CanFrame frame;

        j1939.start();
        j1939.myAddress = 0xBB; // just some random address
        j1939.outgoingList.clear();
        assertTrue(j1939.outgoingList.isEmpty());


        // Testing

        // Send low-res distance

        // Lo-Res Vehicle Disance is PGN FEE0
        // Data:
        //      Bytes 1- 4 : Trip Distance
        //      Bytes 5-8 : Total Distance 0.125 km/bit gain, 0 km offset


        frame = new J1939.CanFrame(0x14FEE0CC, new byte[] {0 ,0 ,0, 0, (byte) 0xAC, 0x50, 0x60, (byte) 0x82});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed

        assertEquals(0x826050ACL * 125, j1939.engine.status.odometer_m); // 125 is conversion to meters



        // Send hi-res distance

        // Hi-Res Vehicle Distance is PGN FEC1
        // Data:
        //      Bytes 1- 4: Total Distance 5 m/bit gain, 0 m offset
        //      Bytes 5- 8 : Trip Distance


        frame = new J1939.CanFrame(0x14FEC1CC, new byte[] {(byte) 0x40, (byte) 0x91, 0x70, (byte) 0xC2, 0, 0, 0, 0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed

        assertEquals(0xC2709140L * 5, j1939.engine.status.odometer_m); // 5 is conversion to meters



        // Send another low-res distance -- should be ignored since we have a high-res

        // Lo-Res Vehicle Disance is PGN FEE0
        // Data:
        //      Bytes 1- 4 : Trip Distance
        //      Bytes 5-8 : Total Distance 0.125 km/bit gain, 0 km offset


        frame = new J1939.CanFrame(0x14FEE0CC, new byte[] {0 ,0 ,0, 0, (byte) 0xAC, 0x50, 0x60, (byte) 0x82});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed

        // odometer should be the same as before (the high-res value
        assertEquals(0xC2709140L * 5, j1939.engine.status.odometer_m); // 5 is conversion to meters


        // Send another hi-res distance -- should be accepted

        // Hi-Res Vehicle Distance is PGN FEC1
        // Data:
        //      Bytes 1- 4 : Total Distance 5 m/bit gain, 0 m offset
        //      Bytes 5- 8 : Trip Distance



        frame = new J1939.CanFrame(0x14FEC1CC, new byte[] {(byte) 0x70, (byte) 0x91, 0x70, (byte) 0xC2, 0, 0, 0, 0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertEquals(0xC2709170L * 5, j1939.engine.status.odometer_m); // 5 is conversion to meters


        // Test Unchanged value hi-res

        frame = new J1939.CanFrame(0x14FEC1CC, new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0});
        assertEquals(J1939.PARSED_PACKET_TYPE_PGN, j1939.receiveCANFrame(frame)); // 2 means parsed as control packet
        assertTrue(j1939.outgoingList.isEmpty()); // No Response needed
        assertEquals(0xC2709170L * 5, j1939.engine.status.odometer_m); // 5 is conversion to meters




    } // test_receiveOdometer()




} // class J1939Test
