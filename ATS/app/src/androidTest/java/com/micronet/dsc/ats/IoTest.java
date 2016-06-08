package com.micronet.dsc.ats;

import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;


public class IoTest  extends AndroidTestCase {

    private MainService service;
    private Io io;
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
        io = service.io;

        service.queue.clearAll();
        service.clearEventSequenceId();

        test = new TestCommon(service.queue);


    }


    public void tearDown() throws Exception{


        super.tearDown();
    }





    public void test_checkIgnition() {

        // Get the voltage threshold for the config setting, make sure it is as we expect

        service.config.writeSetting(Config.SETTING_INPUT_IGNITION, "18|3"); // seconds awake, messages


        // because input poll period is slower (500ms), really this takes wither 5 or 6 polls, not 30 or 40

        assertFalse((io.status.input_bitfield & Io.INPUT_BITVALUE_IGNITION) != 0);
        service.queue.clearAll();

        int i;

        // no matter how long, if ignition is off then we are off
        for (i= 1 ; i < 10; i++) {
            assertFalse(io.checkIgnitionInput(false));
        }

        // it must be high for longer than the debounce time to be considered on
        //  debounce time is always two polls

        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));
        assertFalse(io.checkIgnitionInput(true));
        assertTrue(io.checkIgnitionInput(true));





        // other things that should have happened when ignition turned on:
        assertTrue(service.state.readStateBool(State.FLAG_IGNITIONKEY_INPUT));
        assertEquals(EventType.EVENT_TYPE_IGNITION_KEY_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);


        // and ignition turns off just as easily (two poll periods)
        service.queue.clearAll();
        assertTrue(io.checkIgnitionInput(false));
        assertFalse(io.checkIgnitionInput(false));

        // other things that should have happened when ignition turned off:
        assertFalse(service.state.readStateBool(State.FLAG_IGNITIONKEY_INPUT));
        assertEquals(EventType.EVENT_TYPE_IGNITION_KEY_OFF, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);

    } // test_checkIgnition()



    private int ticksPerTenth(int tenth_secs) {
        return tenth_secs/ IoService.INPUT_POLL_PERIOD_TENTHS;
    }

    public void test_checkBadAlternator() {

        // Get the voltage threshold for the config setting, make sure it is as we expect


        // You should pick values here which are integrals of io.INPUT_POLL_PERIOD_TENTHS

        final int TSHORT = 2 * 10 * IoService.INPUT_POLL_PERIOD_TENTHS; // shorter than trigger
        final int TTRIGGER = 6 * 10 * IoService.INPUT_POLL_PERIOD_TENTHS;
        final int TRESET = 8 * 10 * IoService.INPUT_POLL_PERIOD_TENTHS;
        final int TLONG = 10 * 10 *  IoService.INPUT_POLL_PERIOD_TENTHS; // longer than reset




        service.config.writeSetting(Config.SETTING_BAD_ALTERNATOR_STATUS, "132|" + (TTRIGGER/10)+ "|" + (TRESET/10)+ "|3");

        // because input poll period is slower (500ms), really this takes wither 5 or 6 polls, not 30 or 40


        assertFalse(io.status.flagBadAlternator);
        service.queue.clearAll();
        int i;

        // no matter how long, if voltage is higher then it doesn't trigger




        for (i= 1 ; i < ticksPerTenth(TLONG); i++) {
            assertFalse(io.checkBadAlternator((short) 133));
        }
        // voltage goes low for too short a period --> doesn't trigger
        for (i= 1 ; i < ticksPerTenth(TTRIGGER); i++) {
            assertFalse(io.checkBadAlternator((short) 131));
        }
        // no matter how long, if voltage is higher then it doesn't trigger
        for (i= 1 ; i < ticksPerTenth(TLONG); i++) {
            assertFalse(io.checkBadAlternator((short) 133));
        }
        // voltage goes low for too short a period --> doesn't trigger
        for (i= 1 ; i < ticksPerTenth(TTRIGGER); i++) {
            assertFalse(io.checkBadAlternator((short) 131));
        }
        // low for a long enough period -> trigger
        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));
        assertTrue(io.checkBadAlternator((short) 131));
        assertEquals(EventType.EVENT_TYPE_BAD_ALTERNATOR_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        service.queue.clearAll();

        // low for long time ->  still triggered
        for (i= 1 ; i < ticksPerTenth(TLONG); i++) {
            assertTrue(io.checkBadAlternator((short) 131));
        }
        // high for a short time --> still triggered
        for (i= 1 ; i < ticksPerTenth(TRESET); i++) {
            assertTrue(io.checkBadAlternator((short) 133));
        }
        // low again for short time --> still triggered
        for (i= 1 ; i < ticksPerTenth(TSHORT); i++) {
            assertTrue(io.checkBadAlternator((short) 131));
        }
        // high for a short time --> still triggered
        for (i= 1 ; i < ticksPerTenth(TRESET); i++) {
            assertTrue(io.checkBadAlternator((short) 133));
        }
        // high for long enough, no longer triggered
        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));
        assertFalse(io.checkBadAlternator((short) 133));
        assertEquals(EventType.EVENT_TYPE_BAD_ALTERNATOR_OFF, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);

    } // test_checkBadAlternator()


    public void test_checkLowBattery() {

        // You should pick values here which are integrals of io.INPUT_POLL_PERIOD_TENTHS

        final int TSHORT = 2 * 10 * IoService.INPUT_POLL_PERIOD_TENTHS; // shorter than trigger
        final int TTRIGGER = 6 * 10 * IoService.INPUT_POLL_PERIOD_TENTHS;
        final int TRESET = 8 * 10 * IoService.INPUT_POLL_PERIOD_TENTHS;
        final int TLONG = 10 * 10 *  IoService.INPUT_POLL_PERIOD_TENTHS; // longer than reset



        // Get the voltage threshold for the config setting, make sure it is as we expect

        service.config.writeSetting(Config.SETTING_LOW_BATTERY_STATUS, "105|" + (TTRIGGER/10) + "|" + (TRESET/10) + "|3");

        // because input poll period is slower (500ms), really this takes wither 5 or 6 polls, not 30 or 40


        assertFalse(io.status.flagLowBattery);
        service.queue.clearAll();
        int i;

        // no matter how long, if voltage is higher then it doesn't trigger
        for (i= 1 ; i < ticksPerTenth(TSHORT); i++) {
            assertFalse(io.checkLowBattery((short) 106));
        }
        // voltage goes low for too short a period --> doesn't trigger
        for (i= 1 ; i < ticksPerTenth(TTRIGGER); i++) {
            assertFalse(io.checkLowBattery((short) 104));
        }
        // no matter how long, if voltage is higher then it doesn't trigger
        for (i= 1 ; i < ticksPerTenth(TLONG); i++) {
            assertFalse(io.checkLowBattery((short) 106));
        }
        // voltage goes low for too short a period --> doesn't trigger
        for (i= 1 ; i < ticksPerTenth(TTRIGGER); i++) {
            assertFalse(io.checkLowBattery((short) 104));
        }
        // low for a long enough period -> trigger
        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));
        assertTrue(io.checkLowBattery((short) 104));
        assertEquals(EventType.EVENT_TYPE_LOW_BATTERY_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        service.queue.clearAll();

        // low for long time ->  still triggered
        for (i= 1 ; i < ticksPerTenth(TLONG); i++) {
            assertTrue(io.checkLowBattery((short) 104));
        }
        // on mark for long period has no effect
        for (i= 1 ; i < ticksPerTenth(TLONG); i++) {
            assertTrue(io.checkLowBattery((short) 105));
        }
        // high for a short time --> still triggered
        for (i= 1 ; i < ticksPerTenth(TRESET); i++) {
            assertTrue(io.checkLowBattery((short) 106));
        }
        // low again for short time --> still triggered
        for (i= 1 ; i < ticksPerTenth(TSHORT); i++) {
            assertTrue(io.checkLowBattery((short) 104));
        }
        // high for a short time --> still triggered
        for (i= 1 ; i < ticksPerTenth(TRESET); i++) {
            assertTrue(io.checkLowBattery((short) 106));
        }
        // high for long enough, no longer triggered
        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));
        assertFalse(io.checkLowBattery((short) 106));
        assertEquals(EventType.EVENT_TYPE_LOW_BATTERY_OFF, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);

        // on mark for long period has not effect
        for (i= 1 ; i < ticksPerTenth(TLONG); i++) {
            assertFalse(io.checkLowBattery((short) 105));
        }

    } // test_checkLowBattery()



    public void test_checkDigitalInput_Input1() {


        // You should pick values here which are integrals of io.INPUT_POLL_PERIOD_TENTHS

        final int T10 = 2 * IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T20 = 4 *  IoService.INPUT_POLL_PERIOD_TENTHS; // longer than reset
        final int T50 = 10 * IoService.INPUT_POLL_PERIOD_TENTHS;



        // Get the voltage threshold for the config setting, make sure it is as we expect


        //Config Parameters: bias, 1/10 seconds on, 1/10 seconds reset delay, seconds awake, messages
        service.config.writeSetting(Config.SETTING_INPUT_GP1, "1|" + T10 + "|" + T50 + "|10|3");

        // remember input poll period is slower (500ms) than the configuration units (100ms),
        //  this causes different number of polls


        assertFalse((io.status.input_bitfield & ~Io.INPUT_BITVALUE_IGNITION) != 0);
        service.queue.clearAll();

        int i;

        // no matter how long we are physically inactive, we are never logically active
        for (i= 1; i < ticksPerTenth(T50); i++) {
            assertFalse(io.checkDigitalInput(1, 0));
        }

        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));


        // OK, lets turn Input 1 physically active
        assertFalse(io.checkDigitalInput(1, 1));
        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));

        // and again -> In1 On
        assertTrue(io.checkDigitalInput(1, 1));
        assertEquals(EventType.EVENT_TYPE_INPUT1_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        service.queue.clearAll();

        // continued physical on has no effect
        for (i= 1; i < ticksPerTenth(T20); i++) {
            assertTrue(io.checkDigitalInput(1, 1));
        }
        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));

        // And turn Input 1 physical inactive
        assertTrue(io.checkDigitalInput(1, 0));
        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));

        // And again -- > In1 Off
        assertFalse(io.checkDigitalInput(1, 0));
        assertEquals(EventType.EVENT_TYPE_INPUT1_OFF, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);
        service.queue.clearAll();

        // now check for just shy of reset period
        for (i= 1; i < ticksPerTenth(T50); i++) {
            assertFalse(io.checkDigitalInput(1, 0));
        }

        // followed by lots of time on
        for (i= 1; i < ticksPerTenth(T20); i++) {
            assertFalse(io.checkDigitalInput(1, 1));
        }

        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));

        // now the reset period off
        // now check for full reset period
        for (i= 1; i <= ticksPerTenth(T50); i++) {
            assertFalse(io.checkDigitalInput(1, 0));
        }

        // followed by trigger time on
        assertFalse(io.checkDigitalInput(1, 1));
        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));
        assertTrue(io.checkDigitalInput(1, 1));
        assertEquals(EventType.EVENT_TYPE_INPUT1_ON, service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY).event_type_id);


    } // test_checkDigitalInput_Input1()




    int F=2; // should have turned off
    int N=3; // should have turned on



    private boolean verifyPulseResult(int expectedResult, boolean actualResult,
                                      int event_type_on, int event_type_off,
                                      boolean sendingOn, boolean sendingOff) {

        if (expectedResult == F) {
            if (actualResult) return false;
            if (sendingOff) {
                if (!test.isInQueue(event_type_off)) return false;
            } else {
                if (test.isInQueue(event_type_off)) return false;
            }
            if (test.isInQueue(event_type_on)) return false;
        } else if (expectedResult == N) {
            if (!actualResult) return false;
            if (sendingOn) {
                if (!test.isInQueue(event_type_on)) return false;
            } else {
                if (test.isInQueue(event_type_on)) return false;
            }
            if (test.isInQueue(event_type_off)) return false;
        } else {
            if (expectedResult == 0) if (actualResult) return false;
            if (expectedResult == 1) if (!actualResult) return false;
            if (test.isInQueue(event_type_on)) return false;
            if (test.isInQueue(event_type_off)) return false;
        }

        return true;
    }


    public void test_checkDigitalInput_Multiple() {

        // check multiple inputs at the same time, each with different settings

        final int T0 = 0 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T10 = 2 * IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T15 = 3 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T20 = 4 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T25 = 5 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T30 = 6 * IoService.INPUT_POLL_PERIOD_TENTHS;



        //Config Parameters: bias, 1/10 seconds on, 1/10 seconds reset delay, seconds awake, messages,default debounce off
        service.config.writeSetting(Config.SETTING_INPUT_GP1, "1|" + T10 + "|" + T30 + "|10|3");
        service.config.writeSetting(Config.SETTING_INPUT_GP3, "0|" + T15 + "|" + T25 + "|0|2");
        service.config.writeSetting(Config.SETTING_INPUT_GP5, "1|" + T20 + "|" + T0 + "|10|1");


        assertFalse((io.status.input_bitfield & ~Io.INPUT_BITVALUE_IGNITION) != 0);
        service.queue.clearAll();

        int i;

        // no matter how long we are physically inactive, we are never logically active
        for (i = 1; i < T30; i++) {
            assertFalse(io.checkDigitalInput(1, 0));
            assertFalse(io.checkDigitalInput(3, 1)); // this one is set to be active-low;
            assertFalse(io.checkDigitalInput(5, 0));
            assertFalse(io.checkDigitalInput(6, 0));
        }

        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));

        // OK, we are going to start changing physical input levels and check the expected logical results
        //
        // use these pulse diagrams (500ms per tick): 0 = physical low, 1 =  physical high, -1 is float
        //  N = Logically On, F = Logically Off, R = Input is Reset
        // 0 = is off, 1 = is on

        int[] physicalInput1 = { 0,0,1,1,1,1,1,0,0,0,1,1,1,0,0,0,0,0,0,0,1,1 };
        // Logical State:      F       N         F                   R     N
        int[] resultInput1=    { 0,0,0,N,1,1,1,1,F,0,0,0,0,0,0,0,0,0,0,0,0,N };

        int[] physicalInput3 = { 0,0,0,1,1,1,1,1,1,1,0,0,0,1,1,1,1,1,0,0,0,0 }; // remember this is set as active-low
        // Logical State:      F     N     F                       R     N
        int[] resultInput3=    { 0,0,N,1,1,F,0,0,0,0,0,0,0,0,0,0,0,0,0,0,N,1 };

        int[] physicalInput5 = { 0,0,1,1,1,1,0,0,0,0,1,1,1,1,1,1,1,1,1,1,1,1 };
        // Logical State:      F           N       FR      N
        int[] resultInput5=    { 0,0,0,0,0,N,1,1,1,F,0,0,0,N,1,1,1,1,1,1,1,1 };

        boolean input1R, input3R, input5R;
        for (i=0 ; i < physicalInput1.length; i++) {

            service.queue.clearAll();

            input1R = io.checkDigitalInput(1, (physicalInput1[i]));
            input3R = io.checkDigitalInput(3, (physicalInput3[i]));
            input5R = io.checkDigitalInput(5, (physicalInput5[i]));

            assertTrue("iter=" + i, verifyPulseResult(resultInput1[i], input1R, EventType.EVENT_TYPE_INPUT1_ON, EventType.EVENT_TYPE_INPUT1_OFF, true, true));
            assertTrue("iter=" + i, verifyPulseResult(resultInput3[i], input3R, EventType.EVENT_TYPE_INPUT3_ON, EventType.EVENT_TYPE_INPUT3_OFF, false, true));
            assertTrue("iter=" + i, verifyPulseResult(resultInput5[i], input5R, EventType.EVENT_TYPE_INPUT5_ON, EventType.EVENT_TYPE_INPUT5_OFF, true, false));


        } // each pulse


    } // test_checkDigitalInput_Multiple()


    public void test_checkDigitalInput_PulsedInput() {

        // test with a pulsing input where the debounce-on is less than the debounce-off

        final int T0 = 0 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T10 = 2 * IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T15 = 3 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T20 = 4 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T25 = 5 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T30 = 6 * IoService.INPUT_POLL_PERIOD_TENTHS;



        //Config Parameters: bias, 1/10 seconds on, 1/10 seconds reset delay, seconds awake, messages
        service.config.writeSetting(Config.SETTING_INPUT_GP1, "1|" + T10 + "|" + T30 + "|10|3|" + T20);
        service.config.writeSetting(Config.SETTING_INPUT_GP6, "1|" + T10 + "|" + T30 + "|10|3|" + T20);



        assertFalse((io.status.input_bitfield & ~Io.INPUT_BITVALUE_IGNITION) != 0);
        service.queue.clearAll();

        int i;

        // no matter how long we are physically inactive, we are never logically active
        for (i = 1; i < T30; i++) {
            assertFalse(io.checkDigitalInput(1, 0));
        }

        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));

        // OK, we are going to start changing physical input levels and check the expected logical results
        //
        // use these pulse diagrams (500ms per tick): 0 = physical low, 1 =  physical high, -1 is float
        //  N = Logically On , F = Logically Off, R = Input is Reset
        // 0 = is off, 1 = is on

        int[] physicalInput1 = { 0,0,1,0,1,1,0,0,1,1,0,0,1,1,0,0,0,0,0,1,1,0 };
        // Logical State:      F       N                           FR
        int[] resultInput1=    { 0,0,0,0,0,N,1,1,1,1,1,1,1,1,1,1,1,F,0,0,0,0 };

        int[] physicalInput6 = { 0,0,1,0,1,1,0,0,1,1,0,0,1,1,0,0,0,0,0,1,1,0 };
        // Logical State:      F       N                           FR
        int[] resultInput6=    { 0,0,0,0,0,N,1,1,1,1,1,1,1,1,1,1,1,F,0,0,0,0 };


        boolean input1R, input6R;
        for (i=0 ; i < physicalInput1.length; i++) {

            service.queue.clearAll();
            input1R = io.checkDigitalInput(1, (physicalInput1[i]));
            input6R = io.checkDigitalInput(6, (physicalInput6[i]));

            assertTrue("iter=" + i, verifyPulseResult(resultInput1[i], input1R, EventType.EVENT_TYPE_INPUT1_ON, EventType.EVENT_TYPE_INPUT1_OFF, true, true));
            assertTrue("iter=" + i, verifyPulseResult(resultInput6[i], input6R, EventType.EVENT_TYPE_INPUT6_ON, EventType.EVENT_TYPE_INPUT6_OFF, true, true));



        } // each pulse


    } // test_checkDigitalInput_PulsedInput()



    public void test_checkDigitalInput_Bias() {

        // test the bias (float-detection) of inputs

        final int T0 = 0 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T10 = 2 * IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T15 = 3 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T20 = 4 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T25 = 5 *  IoService.INPUT_POLL_PERIOD_TENTHS;
        final int T30 = 6 * IoService.INPUT_POLL_PERIOD_TENTHS;


        //Config Parameters: bias, 1/10 seconds on, 1/10 seconds reset delay, seconds awake, messages
        service.config.writeSetting(Config.SETTING_INPUT_GP4, "1|" + T10 + "|" + T30 + "|10|3"); // Float is same as ground
        service.config.writeSetting(Config.SETTING_INPUT_GP5, "0|" + T10 + "|" + T30 + "|10|3"); // Float is same as high
        service.config.writeSetting(Config.SETTING_INPUT_GP6, "0|" + T10 + "|" + T30 + "|10|3"); // Float is same as high

        assertFalse((io.status.input_bitfield & ~Io.INPUT_BITVALUE_IGNITION) != 0);
        service.queue.clearAll();

        int i;

        // no matter how long we are physically inactive, we are never logically active
        for (i = 1; i < T30; i++) {
            assertFalse(io.checkDigitalInput(4, 0));
            assertFalse(io.checkDigitalInput(5, 1));
            assertFalse(io.checkDigitalInput(6, 1));
        }

        assertNull(service.queue.getFirstItem(Queue.SERVER_ID_PRIMARY));

        // OK, we are going to start changing physical input levels and check the expected logical results
        //
        // use these pulse diagrams (500ms per tick): 0 = physical low, 1 =  physical high, 2 is float
        //  N = Logically On, F = Logically Off, R = Input is Reset
        // 0 = is off, 1 = is on

        int[] physicalInput4 = { 0,0,1,1,1,1,1,2,2,0,1,1,1,0,0,2,0,0,2,2,1,1 };
        // Logical State:      F       N         F                   R     N
        int[] resultInput4=    { 0,0,0,N,1,1,1,1,F,0,0,0,0,0,0,0,0,0,0,0,0,N };

        int[] physicalInput5 = { 1,0,0,1,2,1,1,2,1,2,2,0,0,0,2,2,2,1,0,0,2,2 }; // remember this is set as active-low
        // Logical State:      F     N   F             R N                R
        int[] resultInput5=    { 0,0,N,1,F,0,0,0,0,0,0,0,N,1,1,F,0,0,0,0,0,0 };

        int[] physicalInput6 = { 1,0,0,1,2,1,1,2,1,2,2,0,0,0,2,2,2,1,0,0,2,2 }; // remember this is set as active-low
        // Logical State:      F     N   F             R N                R
        int[] resultInput6=    { 0,0,N,1,F,0,0,0,0,0,0,0,N,1,1,F,0,0,0,0,0,0 };

        boolean input4R, input5R, input6R;
        for (i=0 ; i < physicalInput4.length; i++) {

            service.queue.clearAll();

            input4R = io.checkDigitalInput(4, (physicalInput4[i] == 2 ? IoService.HW_INPUT_FLOAT : physicalInput4[i]));
            input5R = io.checkDigitalInput(5, (physicalInput5[i] == 2 ? IoService.HW_INPUT_FLOAT : physicalInput5[i]));
            input6R = io.checkDigitalInput(6, (physicalInput6[i] == 2 ? IoService.HW_INPUT_FLOAT : physicalInput6[i]));


            assertTrue("iter=" + i, verifyPulseResult(resultInput4[i], input4R, EventType.EVENT_TYPE_INPUT4_ON, EventType.EVENT_TYPE_INPUT4_OFF, true, true));
            assertTrue("iter=" + i, verifyPulseResult(resultInput5[i], input5R, EventType.EVENT_TYPE_INPUT5_ON, EventType.EVENT_TYPE_INPUT5_OFF, true, true));
            assertTrue("iter=" + i, verifyPulseResult(resultInput6[i], input6R, EventType.EVENT_TYPE_INPUT6_ON, EventType.EVENT_TYPE_INPUT6_OFF, true, true));


        } // each pulse


    } // test_checkDigitalInput_Bias()



    public void test_setEngineStatus() {
        // Configuration Engine Status: 1/10 volts, messages
        service.config.writeSetting(Config.SETTING_ENGINE_STATUS, "132|1");

        assertFalse((io.status.input_bitfield & ~Io.INPUT_BITVALUE_IGNITION) != 0);
        service.queue.clearAll();

        // cannot be on if ignition is off, even if voltage is high
        io.status.battery_voltage = 150;

        int i;

        for (i = 0; i < 20; i++) {
            assertFalse(io.checkEngineStatus());
        }

        // cannot be on if ignition is on if voltage is low
        io.status.battery_voltage = 130;
        io.status.input_bitfield |= Io.INPUT_BITVALUE_IGNITION;

        for (i = 0; i < 20; i++) {
            assertFalse(io.checkEngineStatus());
        }

        assertFalse(test.isInQueue(EventType.EVENT_TYPE_ENGINE_STATUS_ON));
        assertFalse(test.isInQueue(EventType.EVENT_TYPE_ENGINE_STATUS_OFF));

        // but as soon as we have both ignition and voltage, then engine status is on
        io.status.battery_voltage = 140;

        assertTrue(io.checkEngineStatus());
        assertTrue(test.isInQueue(EventType.EVENT_TYPE_ENGINE_STATUS_ON));
        assertFalse(test.isInQueue(EventType.EVENT_TYPE_ENGINE_STATUS_OFF));

        // and it won't turn off again even if voltage falls
        service.queue.clearAll();
        io.status.battery_voltage = 130;
        for (i = 0; i < 20; i++) {
            assertTrue(io.checkEngineStatus());
        }

    }  // test_setEngineStatus()



} // class IoTest
