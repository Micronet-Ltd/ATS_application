package com.micronet.dsc.ats;


import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

public class PositionTest extends AndroidTestCase {
    private MainService service;
    private Position position;
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
        position = service.position;

        service.queue.clearAll();
        service.clearEventSequenceId();

        test = new TestCommon(service.queue);
    }


    public void tearDown() throws Exception{

        super.tearDown();
    }

    public void test_setMovingFlag() {
        service.config.writeSetting(Config.SETTING_MOVING_THRESHOLD, "130");


        // when less than we are not moving
        assertFalse(position.setMovingFlag(129));

        // when more than we are moving
        assertTrue(position.setMovingFlag(131));

        // when equal then we are not moving
        assertFalse(position.setMovingFlag(130));

    } // test_setMovingFlag


    public void test_checkIdling() {

        service.config.writeSetting(Config.SETTING_IDLING, "10");

        assertFalse(service.position.flagIdling);
        service.queue.clearAll();

        position.continuous_idling_s = 167; // random previous value, to test it is unaffected

        // we must be both stationary and engine status on to be idling

        int i;

        // we cannot be idling if engine status is off, even if stationary
        service.io.flagEngineStatus = false;
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkIdling(false));
        }

        // we cannot be idling if moving, even if engine status is on
        service.io.flagEngineStatus = true;
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkIdling(true));
        }

        // we cannot be idling if stationary for less than the time
        for (i= 1; i < 10; i++) {
            assertFalse(position.checkIdling(false));
        }

        assertEquals(167, position.continuous_idling_s); // should still be the same (last time idling)


        // but one more and we are there
        assertTrue(position.checkIdling(false));
        assertEquals(10, position.continuous_idling_s);
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_IDLING_OFF)); // no messages yet

        // tick a few more off, see that idling seconds is changing
        assertTrue(position.checkIdling(false));
        assertEquals(11, position.continuous_idling_s);

        assertTrue(position.checkIdling(false));
        assertEquals(12, position.continuous_idling_s);


        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_IDLING_OFF)); // no messages yet
        // as soon as we are moving again, then idling is off
        assertFalse(position.checkIdling(true));
        assertEquals(12, position.continuous_idling_s); // remains the same
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_IDLING_OFF)); // now we got our off message
        service.queue.clearAll();

        // we cannot be idling if stationary for less than the time
        for (i= 1; i < 10; i++) {
            assertFalse(position.checkIdling(false));
        }

        assertEquals(12, position.continuous_idling_s); // still same for last time

        // but one more and we are idling again
        assertTrue(position.checkIdling(false));
        assertEquals(10, position.continuous_idling_s);

        assertTrue(position.checkIdling(false));
        assertEquals(11, position.continuous_idling_s);

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_IDLING_OFF)); // now we got our off message

        // turn off directly
        position.stopIdling();
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_IDLING_OFF)); // now we got our off message

    } // test_checkIdling()


    public void test_checkIdling_Special0() {
        // check the special configuration value of 0 which means "no idling"
        service.config.writeSetting(Config.SETTING_IDLING, "0");

        assertFalse(service.position.flagIdling);
        service.queue.clearAll();

        position.continuous_idling_s = 167; // random previous value, to test it is unaffected

        int i;

        // even while engine is on and stationary, we can never be idling
        service.io.flagEngineStatus = true;
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkIdling(false));
        }

    } // test_checkIdling_Special0()

    public void test_checkSpeeding() {

        service.config.writeSetting(Config.SETTING_SPEEDING, "3000|10");
        assertFalse(service.position.flagSpeeding);
        service.queue.clearAll();

        int i;

        // we cannot be speeding if we are under speed high threshold (= configuration setting)
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkSpeeding(2999));
        }

        // we cannot be speeding if too short of a time
        for (i= 1; i < 10; i++) {
            assertFalse(position.checkSpeeding(3001));
        }

        assertFalse(position.checkSpeeding(2999));  // back under

        // we cannot be speeding if at speed
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkSpeeding(3000));
        }

        // we cannot be speeding if too short of a time
        for (i= 1; i < 10; i++) {
            assertFalse(position.checkSpeeding(3001));
        }

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_SPEEDING));

        // and over.
        assertTrue(position.checkSpeeding(3001));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_SPEEDING));
        service.queue.clearAll();


        // still over
        assertTrue(position.checkSpeeding(3001));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_SPEEDING));


        // under, but under less than 200 cm/s has no effect
        for (i= 1; i < 20; i++) {
            assertTrue(position.checkSpeeding(2900));
        }

        // under the lower limit but for too short a time
        for (i= 1; i < 10; i++) {
            assertTrue(position.checkSpeeding(2000));
        }

        // back over, no matter how long has no effect
        for (i= 1; i < 20; i++) {
            assertTrue(position.checkSpeeding(3001));
        }

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_SPEEDING));

        // and under for long enough

        for (i= 1; i < 10; i++) {
            assertTrue(position.checkSpeeding(2799));
        }

        assertFalse(position.checkSpeeding(2799));

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_SPEEDING));

        // now we can trigger again though

        for (i= 1; i < 10; i++) {
            assertFalse(position.checkSpeeding(3001));
        }

        assertTrue(position.checkSpeeding(3001));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_SPEEDING));

    } // test_checkSpeeding()





    public void test_checkAccelerating() {
        service.config.writeSetting(Config.SETTING_ACCELERATING, "250|15");
        assertFalse(service.position.flagAccelerating);
        service.queue.clearAll();

        int i;

        // we cannot be accelerating if we are under threshold (= configuration setting)
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkAccelerating(249));
        }

        // we cannot be accelerating  if at threshold
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkAccelerating(250));
        }

        // we cannot be accelerating if at 0
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkAccelerating(0));
        }

        // we cannot be accelerating if a little bit negative
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkAccelerating(-1));
        }

        // we cannot be accelerating if really negative
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkAccelerating(-301));
        }

        // we cannot be accelerating  if too short of a time
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkAccelerating(251));
        }

        assertFalse(position.checkAccelerating(249));  // back under


        // we cannot be accelerating if too short of a time
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkAccelerating(251));
        }

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_ACCELERATING));


        // and over
        assertTrue(position.checkAccelerating(251));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_ACCELERATING));
        service.queue.clearAll();


        // nothing changes no matter how long
        for (i= 1; i < 20; i++) {
            assertTrue(position.checkAccelerating(251));
        }

        // even if we dip below briefly
        for (i= 1; i < 15; i++) {
            assertTrue(position.checkAccelerating(249));
        }

        // and back up
        for (i= 1; i < 20; i++) {
            assertTrue(position.checkAccelerating(251));
        }

        // and down
        for (i= 1; i < 15; i++) {
            assertTrue(position.checkAccelerating(249));
        }

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_ACCELERATING));


        // and enough: -> Accelerating off
        assertFalse(position.checkAccelerating(249));

        // now able to retrigger
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkAccelerating(251));
        }
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_ACCELERATING));

        // and over
        assertTrue(position.checkAccelerating(251));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_ACCELERATING));

    } // test_checkAccelerating()


    public void test_checkBraking() {
        service.config.writeSetting(Config.SETTING_BRAKING, "300|15");
        assertFalse(service.position.flagBraking);
        service.queue.clearAll();

        int i;

        // we cannot be braking if we are under threshold (= configuration setting)
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkBraking(-299));
        }


        // we cannot be braking if we are accelerating
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkBraking(1));
        }

        // we cannot be braking if we are accelerating large number
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkBraking(301));
        }

        // we cannot be braking  if at threshold
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkBraking(-300));
        }

        // we cannot be braking if too short of a time
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkBraking(-301));
        }

        assertFalse(position.checkBraking(-299));  // back under


        // we cannot be braking if too short of a time
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkBraking(-301));
        }

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_BRAKING));


        // and over
        assertTrue(position.checkBraking(-301));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_BRAKING));
        service.queue.clearAll();


        // nothing changes no matter how long
        for (i= 1; i < 20; i++) {
            assertTrue(position.checkBraking(-301));
        }

        // even if we dip below briefly
        for (i= 1; i < 15; i++) {
            assertTrue(position.checkBraking(-299));
        }

        // and back up
        for (i= 1; i < 20; i++) {
            assertTrue(position.checkBraking(-301));
        }

        // and down
        for (i= 1; i < 15; i++) {
            assertTrue(position.checkBraking(-299));
        }

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_BRAKING));


        // and enough: -> Braking off
        assertFalse(position.checkBraking(-299));

        // now able to retrigger
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkBraking(-301));
        }
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_BRAKING));

        // and over
        assertTrue(position.checkBraking(-301));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_BRAKING));

    } // test_checkBraking()




    public void test_checkCornering() {
        service.config.writeSetting(Config.SETTING_CORNERING, "250|15");
        assertFalse(service.position.flagCornering);
        service.queue.clearAll();

        int i;

        // we cannot be Cornering if we are under threshold (= configuration setting)
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkCornering(249));
        }

        // we cannot be Cornering if at threshold
        for (i= 0; i < 20; i++) {
            assertFalse(position.checkCornering(250));
        }

        // we cannot be Cornering if too short of a time
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkCornering(251));
        }

        assertFalse(position.checkCornering(249));  // back under


        // we cannot be accelerating if too short of a time
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkCornering(251));
        }

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_CORNERING));


        // and over
        assertTrue(position.checkCornering(251));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_CORNERING));
        service.queue.clearAll();


        // nothing changes no matter how long
        for (i= 1; i < 20; i++) {
            assertTrue(position.checkCornering(251));
        }

        // even if we dip below briefly
        for (i= 1; i < 15; i++) {
            assertTrue(position.checkCornering(249));
        }

        // and back up
        for (i= 1; i < 20; i++) {
            assertTrue(position.checkCornering(251));
        }

        // and down
        for (i= 1; i < 15; i++) {
            assertTrue(position.checkCornering(249));
        }

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_CORNERING));


        // and enough: -> Accelerating off
        assertFalse(position.checkCornering(249));

        // now able to retrigger
        for (i= 1; i < 15; i++) {
            assertFalse(position.checkCornering(251));
        }
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_CORNERING));

        // and over
        assertTrue(position.checkCornering(251));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_CORNERING));

    } // test_checkCornering()



    private boolean arePingAccumulatorsCleared(Position.PingAccumulator pingAcc) {

        if ((pingAcc.meters == 0) &&
            (pingAcc.seconds_moving == 0) &&
            (pingAcc.seconds_not_moving == 0)) return true;

        return false;
    } // arePingAccumulatorsCleared()


    public void test_checkPing_Moving() {

        // Check that a transition from moving to not-moving triggers a ping

        service.config.writeSetting(Config.SETTING_PING, "30|50|90|130"); // every 90 degrees
        assertFalse(service.position.flagCornering);
        service.queue.clearAll();

        int i;

        Position.PingAccumulator pingAccumulator = new Position.PingAccumulator();

        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertFalse(pingAccumulator.is_bearing_valid);
        pingAccumulator.seconds_moving = 1;
        pingAccumulator.seconds_not_moving = 1;
        pingAccumulator.meters = 1;
        pingAccumulator.absolute_bearing = 1;



        Integer newBearing = new Integer(pingAccumulator.absolute_bearing );
        Boolean newMoving = new Boolean(false);

        assertFalse(position.checkPing(pingAccumulator, newBearing, newMoving));
        assertEquals(false, pingAccumulator.was_moving);
        assertEquals(true, pingAccumulator.is_moving_valid);

        // now transition to moving

        assertFalse(position.checkPing(pingAccumulator, newBearing, true));
        assertEquals(true, pingAccumulator.was_moving);
        assertEquals(true, pingAccumulator.is_moving_valid);

        // and transition to not moving

        assertTrue(position.checkPing(pingAccumulator, newBearing, false));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_PING));
        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertEquals(1, pingAccumulator.absolute_bearing);
        assertEquals(true, pingAccumulator.is_bearing_valid);
        assertEquals(false, pingAccumulator.was_moving);
        assertEquals(true, pingAccumulator.is_moving_valid);
        service.queue.clearAll();
    }


    public void test_checkPing_Bearing() {
        service.config.writeSetting(Config.SETTING_PING, "30|50|90|130"); // every 90 degrees
        assertFalse(service.position.flagCornering);
        service.queue.clearAll();

        int i;

        Position.PingAccumulator pingAccumulator = new Position.PingAccumulator();

        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertFalse(pingAccumulator.is_bearing_valid);
        pingAccumulator.seconds_moving = 1;
        pingAccumulator.seconds_not_moving = 1;
        pingAccumulator.meters = 1;
        pingAccumulator.absolute_bearing = 1;

        // give a big bearing change when it is not valid

        Integer newBearing = new Integer(100);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));

        // our bearing should now be valid
        assertTrue(pingAccumulator.is_bearing_valid);
        assertEquals(100, pingAccumulator.absolute_bearing);

        // give a small change in bearing
        newBearing = new Integer(189);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        newBearing = new Integer(11);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));

        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));

        // large enough change in bearing should trigger
        newBearing = new Integer(9);
        assertTrue(position.checkPing(pingAccumulator, newBearing, null));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_PING));
        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertTrue(pingAccumulator.is_bearing_valid);
        assertEquals(9, pingAccumulator.absolute_bearing);
        service.queue.clearAll();

        // wrap around, but not large enough to trigger
        newBearing = new Integer(350);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));

        // wrap around, large enough to trigger
        newBearing = new Integer(278);
        assertTrue(position.checkPing(pingAccumulator, newBearing, null));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_PING));
        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertTrue(pingAccumulator.is_bearing_valid);
        assertEquals(278, pingAccumulator.absolute_bearing);
        service.queue.clearAll();


        // wrap around (clockwise), but not large enough to trigger
        newBearing = new Integer(7);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));


        // wrap around (clockwise), large enough to trigger
        newBearing = new Integer(11);
        assertTrue(position.checkPing(pingAccumulator, newBearing, null));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_PING));
        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertTrue(pingAccumulator.is_bearing_valid);
        assertEquals(11, pingAccumulator.absolute_bearing);
        service.queue.clearAll();



    } // test_checkPing_Bearing()



    public void test_checkPing_Bearing_Special0() {
        // check for the special 0 value for Configuration Parameter
        service.config.writeSetting(Config.SETTING_PING, "30|50|0|130");
        assertFalse(service.position.flagCornering);
        service.queue.clearAll();

        int i;


        Position.PingAccumulator pingAccumulator = new Position.PingAccumulator();

                // give a big bearing change when it is not valid

        Integer newBearing = new Integer(100);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));

        assertTrue(pingAccumulator.is_bearing_valid);
        assertEquals(100, pingAccumulator.absolute_bearing);

        // No matter how much the bearing moves, we never trigger
        newBearing = new Integer(200);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));

        newBearing = new Integer(300);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));

    }



    public void test_checkPing_NonBearing() {

        //checks the non-bearing components of the ping (distance, time moving, time not moving)

        service.config.writeSetting(Config.SETTING_PING, "7|50|90|13");
        assertFalse(service.position.flagCornering);
        service.queue.clearAll();

        int i;

        Position.PingAccumulator pingAccumulator = new Position.PingAccumulator();

        assertTrue(arePingAccumulatorsCleared(pingAccumulator));

        pingAccumulator.is_bearing_valid = true;
        pingAccumulator.absolute_bearing = 91;

        // make everybody not quite enough
        pingAccumulator.seconds_moving = 6;
        pingAccumulator.seconds_not_moving = 12;
        pingAccumulator.meters = 49;

        Integer newBearing = new Integer(180);
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));


        // Test meters
        pingAccumulator.meters = 50;
        assertTrue(position.checkPing(pingAccumulator, newBearing, null));
        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_PING));
        assertTrue(pingAccumulator.is_bearing_valid);
        service.queue.clearAll();


        // Test Moving Time
        pingAccumulator.seconds_moving = 6;
        pingAccumulator.seconds_not_moving = 12;
        pingAccumulator.meters = 49;
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));

        pingAccumulator.seconds_moving = 7;
        assertTrue(position.checkPing(pingAccumulator, newBearing, null));
        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_PING));
        assertTrue(pingAccumulator.is_bearing_valid);
        service.queue.clearAll();


        // Test Not-Moving Time
        pingAccumulator.seconds_moving = 6;
        pingAccumulator.seconds_not_moving = 12;
        pingAccumulator.meters = 49;
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));

        pingAccumulator.seconds_not_moving = 13;
        assertTrue(position.checkPing(pingAccumulator, newBearing, null));
        assertTrue(arePingAccumulatorsCleared(pingAccumulator));
        assertTrue(test.isInQueue(QueueItem.EVENT_TYPE_PING));
        assertTrue(pingAccumulator.is_bearing_valid);
        service.queue.clearAll();


    } // test_checkPing_NonBearing()


    public void test_checkPing_NonBearing_Special0() {

        //checks the non-bearing components of the ping (distance, time moving, time not moving)
        // for the special values of 0 (ignore)
        service.config.writeSetting(Config.SETTING_PING, "0|50|90|13");
        assertFalse(service.position.flagCornering);
        service.queue.clearAll();

        int i;

        Position.PingAccumulator pingAccumulator = new Position.PingAccumulator();

        assertTrue(arePingAccumulatorsCleared(pingAccumulator));

        pingAccumulator.is_bearing_valid = true;
        pingAccumulator.absolute_bearing = 91;
        Integer newBearing = new Integer(180);

        // test seconds moving
        pingAccumulator.seconds_moving = 999;
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));


        // test seconds not moving
        service.config.writeSetting(Config.SETTING_PING, "7|50|90|0");
        pingAccumulator.seconds_moving = 0;
        pingAccumulator.seconds_not_moving = 999;
        pingAccumulator.meters = 0;
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));

        // test meters
        service.config.writeSetting(Config.SETTING_PING, "7|0|90|13");
        pingAccumulator.seconds_moving = 0;
        pingAccumulator.seconds_not_moving = 0;
        pingAccumulator.meters = 999;
        assertFalse(position.checkPing(pingAccumulator, newBearing, null));
        assertFalse(test.isInQueue(QueueItem.EVENT_TYPE_PING));

    } // test_checkPing_NonBearing_Special0

} // PositionTest
