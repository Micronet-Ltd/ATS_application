package com.micronet.dsc.ats;

import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;


public class EngineTest extends AndroidTestCase {

    private MainService service;
    private Engine engine;
    TestCommon test;

    public void setUp(){
        RenamingDelegatingContext context
                = new RenamingDelegatingContext(getContext(), "test_");

        Config config = new Config(context);
        State state = new State(context);

        // clear config and state info to the default before init'ing IO
        config.open();
        config.clearAll();
        state.clearAll();

        service = new MainService(context);
        engine = service.engine;

        service.queue.clearAll();
        service.clearEventSequenceIdNow();

        test = new TestCommon(service.queue);


    } // setup


    public void tearDown() throws Exception{


        super.tearDown();
    }



    public void test_hasBusPriority() {

        // priority is simple, just J1939 bus has higher priority than J1587 has higher priority than none.

        assertTrue(engine.hasBusPriority(Engine.BUS_TYPE_J1939, Engine.BUS_TYPE_NONE));
        assertTrue(engine.hasBusPriority(Engine.BUS_TYPE_J1587, Engine.BUS_TYPE_NONE));
        assertTrue(engine.hasBusPriority(Engine.BUS_TYPE_J1939, Engine.BUS_TYPE_J1587));
        assertTrue(engine.hasBusPriority(Engine.BUS_TYPE_J1939, Engine.BUS_TYPE_J1939));
        assertTrue(engine.hasBusPriority(Engine.BUS_TYPE_J1587, Engine.BUS_TYPE_J1587));

        assertFalse(engine.hasBusPriority(Engine.BUS_TYPE_J1587, Engine.BUS_TYPE_J1939));
    } // test_hasBusPriority()


    public void test_checkParkingBrake() {

        // Set the config for parking brake

        service.config.writeSetting(Config.SETTING_PARKING_BRAKE, "3|1"); // all messages, conflict-default is on


        // because input poll period is slower (500ms), really this takes wither 5 or 6 polls, not 30 or 40

        //assertFalse((engine.savedIo.input_bitfield & Io.INPUT_BITVALUE_IGNITION) != 0);
        service.queue.clearAll();

        int i;

        // no matter how long, if ignition is off then we are off
        for (i= 1 ; i < 10; i++) {
            assertFalse(engine.checkParkingBrake(Engine.BUS_TYPE_J1939, false));
        }

        // it must be high for longer than the debounce time to be considered on
        //  debounce time is always two polls

        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));
        //assertFalse(engine.checkParkingBrake(true));
        assertTrue(engine.checkParkingBrake(Engine.BUS_TYPE_J1939, true));

        // conflicting info from another bus does nothing

        assertTrue(engine.checkParkingBrake(Engine.BUS_TYPE_J1587, false));



        // other things that should have happened when ignition turned on:
        assertTrue(service.state.readStateBool(State.FLAG_PARKING_BRAKE_STATUS));
        assertEquals(EventType.EVENT_TYPE_PARKBRAKE_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);


        // and ignition turns off just as easily (two poll periods)
        service.queue.clearAll();
        //assertTrue(engine.checkParkingBrake(false));
        assertFalse(engine.checkParkingBrake(Engine.BUS_TYPE_J1939, false));

        // other things that should have happened when ignition turned off:
        assertFalse(service.state.readStateBool(State.FLAG_PARKING_BRAKE_STATUS));
        assertEquals(EventType.EVENT_TYPE_PARKBRAKE_OFF, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);

    } // test_checkParkingBrake()


    void test_checkDTCs() {

        // there should be no outstanding DTCs to start

        assertEquals(0, engine.current_dtcs.size());

        service.queue.clearAll();
        service.config.writeSetting(Config.SETTING_FAULT_CODES, "3"); // messages



        long[] dtclist2 = new long[] { 0x12345678, 0xABCDEF01};
        long[] dtclist1 = new long[] { 0x12345678};
        long[] dtclist0 = new long[] {};


        byte[] state_code_array; // expected state info
        byte[] message_code_array; // expected for messages
        byte[] message_code_array1; // expected for messages
        byte[] message_code_array2; // expected for messages


        // checkDTCs responds with 0xAADD (AA = num added, DD = num deleted)

        // add a DTCs
        assertEquals(0x0100, engine.checkDtcs(Engine.BUS_TYPE_J1587, dtclist1));

        // check for messages and state changes
        state_code_array = new byte[] { Engine.BUS_TYPE_J1587, 0x78, 0x56, 0x34, 0x12};
        message_code_array = state_code_array;

        assertEquals(state_code_array , service.state.readStateArray(State.ARRAY_FAULT_CODES));
        assertEquals(EventType.EVENT_TYPE_FAULTCODE_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        assertEquals(message_code_array, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).additional_data_bytes);


        // add another DTC
        service.queue.clearAll();
        assertEquals(0x0100, engine.checkDtcs(Engine.BUS_TYPE_J1587, dtclist2));


        // check for messages and state changes
        state_code_array = new byte[] { Engine.BUS_TYPE_J1587, 0x78, 0x56, 0x34, 0x12,
                Engine.BUS_TYPE_J1587, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB};
        message_code_array = new byte[] {Engine.BUS_TYPE_J1587, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB};


        assertEquals(state_code_array , service.state.readStateArray(State.ARRAY_FAULT_CODES));
        assertEquals(EventType.EVENT_TYPE_FAULTCODE_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        assertEquals(message_code_array, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).additional_data_bytes);



        // add two DTCs from a different bus (these should be kept separate from the other bus DTCs)
        service.queue.clearAll();
        assertEquals(0x0200, engine.checkDtcs(Engine.BUS_TYPE_J1939, dtclist2));

        // check for messages and state changes
        state_code_array = new byte[] { Engine.BUS_TYPE_J1587, 0x78, 0x56, 0x34, 0x12,
                Engine.BUS_TYPE_J1587, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB,
                Engine.BUS_TYPE_J1939, 0x78, 0x56, 0x34, 0x12,
                Engine.BUS_TYPE_J1939, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB
        };
        message_code_array1 = new byte[] {
                Engine.BUS_TYPE_J1939, 0x78, 0x56, 0x34, 0x12 };
        message_code_array2 = new byte[] {
                Engine.BUS_TYPE_J1939, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB
        };

        assertEquals(state_code_array , service.state.readStateArray(State.ARRAY_FAULT_CODES));
        assertEquals(EventType.EVENT_TYPE_FAULTCODE_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        assertEquals(message_code_array1, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).additional_data_bytes);

        assertEquals(EventType.EVENT_TYPE_FAULTCODE_ON, service.queue.getAllItems().get(1).event_type_id);
        assertEquals(message_code_array2, service.queue.getAllItems().get(1).additional_data_bytes);





        // now remove one dtc

        service.queue.clearAll();
        assertEquals(0x0200, engine.checkDtcs(Engine.BUS_TYPE_J1939, dtclist1));

        // check for messages and state changes
        state_code_array = new byte[] { Engine.BUS_TYPE_J1587, 0x78, 0x56, 0x34, 0x12,
                Engine.BUS_TYPE_J1587, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB,
                Engine.BUS_TYPE_J1939, 0x78, 0x56, 0x34, 0x12,

        };
        message_code_array1 = new byte[]{
                Engine.BUS_TYPE_J1939, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB
        };

        assertEquals(state_code_array , service.state.readStateArray(State.ARRAY_FAULT_CODES));
        assertEquals(EventType.EVENT_TYPE_FAULTCODE_OFF, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        assertEquals(message_code_array1, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).additional_data_bytes);


        // now remove two dtcs


        service.queue.clearAll();
        assertEquals(0x0200, engine.checkDtcs(Engine.BUS_TYPE_J1587, dtclist0));

        // check for messages and state changes
        state_code_array = new byte[] {
                Engine.BUS_TYPE_J1939, 0x78, 0x56, 0x34, 0x12,
                Engine.BUS_TYPE_J1939, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB
        };
        message_code_array1 = new byte[]{
                Engine.BUS_TYPE_J1587, 0x78, 0x56, 0x34, 0x12
        };
        message_code_array2 = new byte[] {
                Engine.BUS_TYPE_J1587, 0x01, (byte) 0xEF, (byte) 0xCD, (byte) 0xAB
        };

        assertEquals(state_code_array , service.state.readStateArray(State.ARRAY_FAULT_CODES));
        assertEquals(EventType.EVENT_TYPE_FAULTCODE_OFF, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        assertEquals(message_code_array1, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).additional_data_bytes);

        assertEquals(EventType.EVENT_TYPE_FAULTCODE_OFF, service.queue.getAllItems().get(1).event_type_id);
        assertEquals(message_code_array2, service.queue.getAllItems().get(1).additional_data_bytes);


    } // check DTCs



} // class EngineTest
