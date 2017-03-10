/////////////////////////////////////////////////////////////
// Position:
//  Handles GPS locations and acceleration, braking, cornering, distances, etc..
/////////////////////////////////////////////////////////////
package com.micronet.dsc.ats;


import android.content.Context;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Position {

    public static final String TAG = "ATS-Position";

    public static final int FIX_TYPE_FLAG_2D = 128; // set the bit with this value in fix type if it is only a 2D fix
    public static final int FIX_TYPE_FLAG_LOC_GNSS = 32; // set this to say that the position came from GNSS
    public static final int FIX_TYPE_FLAG_TIME_EARTH = 1; // set this to say that the time came from an "Earth based means" (NTP, NTZZ, manual)

    public static final int FIX_HISTORIC_TIME_MS = 10000; // if fix is older than 10 seconds, consider it historic
    public static final int FIX_DIFFERENTIATOR_MAX_TIME_MS = 2000; // if fix older than 2 seconds, don't differentiate
    public static final int FIX_INTEGRATOR_MAX_TIME_MS = 10000; // if fix older than 10 seconds, don't integrate


    public boolean flagMoving = false;
    public long virtual_odometer_m;
    public boolean flagIdling;
    public int continuous_idling_s;


    boolean flagSpeeding, flagAccelerating, flagBraking, flagCornering;
    int debounceIdling, debounceSpeeding, debounceAccelerating, debounceBraking, debounceCornering; // counter while in debounce-period

    // pingAccumulator : remember how long it has been since last ping
    public static class PingAccumulator {
        int seconds_moving = 0; // number of seconds while not moving
        int seconds_not_moving = 0; // number of seconds while moving
        int meters = 0; // number of meters (obviously while moving)
        int absolute_bearing = 0; // what our bearing was at the last ping.
        boolean is_bearing_valid = false; // set if this is a valid bearing
        boolean was_moving = false; // was it moving at the time of the last ping ?
        boolean is_moving_valid = false; // is the moving status valid ?

        public void clear() {
            seconds_moving = 0;
            seconds_not_moving = 0;
            meters = 0;
            absolute_bearing = 0;
            is_bearing_valid = false;
            was_moving = false;
            is_moving_valid = false;
        }

        public void setLastBearing(int new_bearing) {
            absolute_bearing = new_bearing;
            is_bearing_valid = true;
        }

        public void setLastMoving(boolean new_moving) {
            was_moving = new_moving;
            is_moving_valid = true;
        }

    } // PingAccumulator

    PingAccumulator pingAccumulator = new PingAccumulator();


    public class SavedAcceleration {
        double forward_acceleration_cms2;
        double orthogonal_acceleration_cms2;
    }



    public class SavedLocation {

        long time_elapsed; // this is the monotonic clock, for determining aging
        long time_location; // this is from location.getTime, for determining the derivatives
        double latitude;
        double longitude;
        int speed_cms;
        short bearing;
        int accuracy_m;
        short sat_count; // number of satellites in seen
        int fix_type;



        // remember(): store everything we need for later
        public void remember(Location location) {

            if (location == null) return; // ignore any null information

            if (time_location == 0) {
                // we have never remembered anything, check to see if we should adjust the system time
                service.power.checkAdjustTime(location.getTime());

            }

            time_elapsed = SystemClock.elapsedRealtime();
            time_location = location.getTime();
            latitude = location.getLatitude();
            longitude = location.getLongitude();
            speed_cms = (int) (location.getSpeed() * 100.0);
            bearing = (short) location.getBearing();
            accuracy_m = (int) location.getAccuracy();

            fix_type &= FIX_TYPE_FLAG_2D; // clear everything except 2D/3D flag
            fix_type |= FIX_TYPE_FLAG_LOC_GNSS; // this is the only thing we are using right now
        }

        public void remember(int num_satellites, int num_satellitesInFix ) {
            sat_count = (short) num_satellites;
            if (num_satellitesInFix >= 4)
                fix_type &= ~FIX_TYPE_FLAG_2D; // clear the 2D flag
                else
                fix_type |= FIX_TYPE_FLAG_2D; // set the 2D flag
        }

        // isValid: is this a valid Location (or never locked, etc.) ?
        public boolean isValid() {
            if (time_location == 0) {
                return false;
            }
            return true;
        }

    } // SavedLocation

    SavedLocation savedLocation = new SavedLocation();
    SavedAcceleration savedAcceleration = new SavedAcceleration();


    private final GpsStatus.Listener gpsStatusListener = new GpsStatus.Listener() {
        @Override
        public void onGpsStatusChanged(int event) {

            try {
                Log.vv(TAG, "onGpsStatusChanged()");

                LocationManager locationManager = (LocationManager) service.context.getSystemService(Context.LOCATION_SERVICE);

                int satellites = 0;
                int satellitesInFix = 0;

                int timetofix = locationManager.getGpsStatus(null).getTimeToFirstFix();
                for (GpsSatellite sat : locationManager.getGpsStatus(null).getSatellites()) {
                    if (sat.usedInFix()) {
                        satellitesInFix++;
                    }
                    satellites++;
                }
                Log.d(TAG, "Satellites: ttff = " + String.valueOf(timetofix) + "; " + satellites + " sats total; " + satellitesInFix + " sats used");

                savedLocation.remember(satellites, satellitesInFix);
            } catch (Exception e) {
                Log.e(TAG, "OnGpsStatusChanged exception " + e.toString(), e);
            }
        }
    };

    private final LocationListener locationListener = new LocationListener() {


        //////////////////////////////////////////////////////////////////
        // onLocationChanged()
        //  this function receives the location updates from the Android OS
        //////////////////////////////////////////////////////////////////
        @Override
        public void onLocationChanged(Location location) {
            // Called when a new location is found by the network location provider.
            try {

                Log.vv(TAG, "onLocationChanged()");

                // if we don't have a speed or direction, this should be ignored as suspect.
                if (!location.hasSpeed() || !location.hasBearing()) {
                    Log.d(TAG, "onLocationChanged: Ignoring for lack of Speed or Bearing");
                    return;
                }


                // time should be elapsed Realtime, but this is only avialable after API 17
                // long diffTime = location.getElapsedRealtimeNanos() - lastElapsedRealtimeNanos;
                // Instead, use getTime()
                long diffTime_ms; // milliseconds
                int newSpeed_cms; // centimeters per second
                diffTime_ms = location.getTime() - savedLocation.time_location;
                newSpeed_cms = (int) (location.getSpeed() * 100.0); // convert speed to cm/s


                // Since this might be UTC time, it could go backwards, or it could hop forward
                //   we will just ignore readings if this happens (since it will be rare)
                //   also, this ignores the very first reading since time difference will be huge.
                if ((diffTime_ms < 0) || //
                        (diffTime_ms > 1500)) { //  or it could hop forward
                    Log.w(TAG, "onLocationChanged: Ignoring derivatives b/c time deltas out-of-range: " + diffTime_ms + "ms");
                    diffTime_ms = 0;
                }

                // if something is unreasonable, we should ignore everything about it
                if (newSpeed_cms > 6000) {
                    Log.w(TAG, "onLocationChanged: Ignoring reading b/c new speed is too high: " + newSpeed_cms + "cm/s");
                    return;
                }

                int diffSpeed_cms;
                int avgSpeed_cms;
                short diffBearing; // degrees
                diffSpeed_cms = newSpeed_cms - savedLocation.speed_cms;
                avgSpeed_cms = (newSpeed_cms + savedLocation.speed_cms) / 2;
                diffBearing = (short) Math.abs(location.getBearing() - savedLocation.bearing);
                if (diffBearing > 180) // can't have a difference greater than 180 degrees
                    diffBearing = (short) (360 - diffBearing);

                // Save all the data for comparison with next reason, or in case we send a message.
                savedLocation.remember(location);

                if (diffTime_ms > 0) {

                    // normalize inline and orthogonal accelerations

                    savedAcceleration.forward_acceleration_cms2 = diffSpeed_cms * 1000.0 / diffTime_ms; // converts from ms to seconds
                    savedAcceleration.orthogonal_acceleration_cms2 = Math.abs(avgSpeed_cms * Math.sin(diffBearing * Math.PI / 180.0)) * 1000.0 / diffTime_ms;  // converts from ms to seconds
                }

                Log.d(TAG, "Location: (@" + diffTime_ms + "ms) [" +
                            savedAcceleration.forward_acceleration_cms2 + ", " +
                            savedAcceleration.orthogonal_acceleration_cms2 + "] cm/s^2, " +
                            location.getSpeed() + "m/s , " + location.getBearing() + "deg , +/-" + location.getAccuracy() + "m , " + location.getLatitude() + "," + location.getLongitude());

                // set or clear the moving flag
                setMovingFlag(newSpeed_cms);


            } catch (Exception e) {
                Log.e(TAG, "OnLocationChanged exception " + e.toString(), e);
            }
        } // OnLocationChanged

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.v(TAG, "onStatusChanged()");
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "onProviderEnabled()");
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.d(TAG, "onProviderDisabled()");
        }

    }; // LocationListener

    MainService service;

    // Constructor
    public Position(MainService service) {
        this.service = service;

        // Intiialize non-zero values:

        // load our virtual odometer
        virtual_odometer_m = service.state.readStateLong(State.VIRTUAL_ODOMETER); // returns the value in meters
    }



    public void populateQueueItem(QueueItem item) {
        try {
            item.latitude = savedLocation.latitude;
            item.longitude = savedLocation.longitude;
            item.heading = savedLocation.bearing;
            item.fix_accuracy = savedLocation.accuracy_m;
            item.speed = savedLocation.speed_cms;
            item.fix_type = savedLocation.fix_type; // the time we are remembering came from the system

            if (Power.isTimeValid(item.trigger_dt * 1000L))
                item.fix_type |= Position.FIX_TYPE_FLAG_TIME_EARTH;

            item.sat_count = savedLocation.sat_count;

            item.is_fix_historic = Position.isFixHistoric(savedLocation);
            item.odometer = virtual_odometer_m;
            item.continuous_idle = continuous_idling_s;

        } catch (Exception e) {
            Log.e(TAG, "Exception populateQueueItem() " + e.toString(), e);
        }
    }



    class Status {
        long virtual_odometer_m;
        long continuous_idling_s;

        boolean flagIdling, flagSpeeding, flagAccelerating, flagBraking, flagCornering;
    }

    //////////////////////////////////////////////////////////////////
    // getStatus()
    //  return status information for use in Local Broadcasts, etc.
    //////////////////////////////////////////////////////////////////
    public Status getStatus() {
        Status status = new Status();
        status.virtual_odometer_m = virtual_odometer_m;
        status.continuous_idling_s = continuous_idling_s;
        status.flagIdling = flagIdling;
        status.flagSpeeding= flagSpeeding;
        status.flagAccelerating= flagAccelerating;
        status.flagBraking= flagBraking;
        status.flagCornering= flagCornering;
        return status;
    }


    //////////////////////////////////////////////////////////////////
    // setIfFixHistoric()
    //  sets the historic bit in the fix type if it is needed
    //////////////////////////////////////////////////////////////////

    public static boolean isFixHistoric(SavedLocation savedFix) {

        if (SystemClock.elapsedRealtime() > savedFix.time_elapsed + FIX_HISTORIC_TIME_MS)
            return true;
        else
            return false;

    }


    ScheduledThreadPoolExecutor exec;

    //////////////////////////////////////////////////////////////////
    // init()
    //  call this to initialize the last known location, etc. (called when app starts)
    //////////////////////////////////////////////////////////////////
    public void init() {
        Log.v(TAG, "init()");

        // just ask for the last known location

        LocationManager locationManager = (LocationManager) service.context.getSystemService(Context.LOCATION_SERVICE);
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        if (location == null)
            Log.d(TAG, "Last known location is null");
        else
            Log.d(TAG, "Last known location: " + location.getLatitude() + ", " + location.getLongitude() + " @ " + location.getTime());


        // We can't really tell for sure when this reading was taken until API version 17
        //  since location.getElapsedRealtimeNanos() is not available until then
        //  so, for now, we should assume this was taken a long time ago
        savedLocation.remember(location);
        if (savedLocation.isValid())
            savedLocation.time_elapsed = -10000; // a number guaranteed to be a while ago.

    } // init()



    //////////////////////////////////////////////////////////////////
    // start()
    //  starts listening for location updates (e.g. call this if ignition turns on)
    //////////////////////////////////////////////////////////////////
    public void start() {


        Log.v(TAG, "start()");

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable(){
            @Override public void run() {

                Log.v(TAG, "Setting up Location Listener");

                LocationManager locationManager = (LocationManager) service.context.getSystemService(Context.LOCATION_SERVICE);


                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250,0, locationListener); // every 250 ms
                locationManager.addGpsStatusListener(gpsStatusListener);


                exec = new ScheduledThreadPoolExecutor(1);
                exec.scheduleAtFixedRate(new IntegratorTask(), 1000, 1000,  TimeUnit.MILLISECONDS); // 1 second
                exec.scheduleAtFixedRate(new DifferentiatorTask(), 100, 100,  TimeUnit.MILLISECONDS); // 1/10th second

                }
            }
        ); // post


    } // start()

    //////////////////////////////////////////////////////////////////
    // stop();
    //  stops listening for location updates (e.g. call this if ignition turns off)
    //////////////////////////////////////////////////////////////////
    public void stop() {

        Log.v(TAG, "stop()");

        if (exec != null)
            exec.shutdown();

        // save the latest virtual odometer
        service.state.writeStateLong(State.VIRTUAL_ODOMETER, virtual_odometer_m);

        LocationManager locationManager = (LocationManager) service.context.getSystemService(Context.LOCATION_SERVICE);
        locationManager.removeUpdates(locationListener);
        locationManager.removeGpsStatusListener(gpsStatusListener);
    }



    ////////////////////////////////////////////////////////////////////
    // saveCrashData()
    //      save data in preparation for an imminent crash+recovery
    ////////////////////////////////////////////////////////////////////
    public void saveCrashData(Crash crash) {

        crash.writeStateBool(Crash.FLAG_IDLE_STATUS, flagIdling);
        crash.writeStateBool(Crash.FLAG_SPEEDING_STATUS, flagSpeeding);
        crash.writeStateBool(Crash.FLAG_ACCELERATING_STATUS, flagAccelerating);
        crash.writeStateBool(Crash.FLAG_BRAKING_STATUS, flagBraking);
        crash.writeStateBool(Crash.FLAG_CORNERING_STATUS, flagCornering);

        crash.writeStateInt(Crash.CONTINUOUS_IDLING_SECONDS, continuous_idling_s);

        crash.writeStateInt(Crash.DEBOUNCE_IDLING, debounceIdling);
        crash.writeStateInt(Crash.DEBOUNCE_SPEEDING, debounceSpeeding);
        crash.writeStateInt(Crash.DEBOUNCE_ACCELERATING, debounceAccelerating);
        crash.writeStateInt(Crash.DEBOUNCE_BRAKING, debounceBraking);
        crash.writeStateInt(Crash.DEBOUNCE_CORNERING, debounceCornering);

        int[] ping_array = new int[7];
        ping_array[0] = pingAccumulator.seconds_moving;
        ping_array[1] = pingAccumulator.seconds_not_moving;
        ping_array[2] = pingAccumulator.meters;
        ping_array[3] = pingAccumulator.absolute_bearing;
        ping_array[4] =(pingAccumulator.is_bearing_valid ? 1: 0);
        ping_array[5] =(pingAccumulator.was_moving ? 1: 0);
        ping_array[6] =(pingAccumulator.is_moving_valid ? 1: 0);
        crash.writeStateArrayInt(Crash.LAST_PING_DATA_ARRAY, ping_array);

    } // saveCrashData()


    ////////////////////////////////////////////////////////////////////
    // restoreCrashData()
    //      restore data from a recent crash
    ////////////////////////////////////////////////////////////////////
    public void restoreCrashData(Crash crash) {

        flagIdling = crash.readStateBool(Crash.FLAG_IDLE_STATUS);
        flagSpeeding = crash.readStateBool(Crash.FLAG_SPEEDING_STATUS);
        flagAccelerating= crash.readStateBool(Crash.FLAG_ACCELERATING_STATUS);
        flagBraking= crash.readStateBool(Crash.FLAG_BRAKING_STATUS);
        flagCornering = crash.readStateBool(Crash.FLAG_CORNERING_STATUS);

        continuous_idling_s = crash.readStateInt(Crash.CONTINUOUS_IDLING_SECONDS);

        debounceIdling = crash.readStateInt(Crash.DEBOUNCE_IDLING);
        debounceSpeeding = crash.readStateInt(Crash.DEBOUNCE_SPEEDING);
        debounceAccelerating = crash.readStateInt(Crash.DEBOUNCE_ACCELERATING);
        debounceBraking = crash.readStateInt(Crash.DEBOUNCE_BRAKING);
        debounceCornering = crash.readStateInt(Crash.DEBOUNCE_CORNERING);

        String[] ping_array;

        ping_array = crash.readStateArray(Crash.LAST_PING_DATA_ARRAY);

        if (ping_array.length >= 5) {
            pingAccumulator.seconds_moving = Integer.parseInt(ping_array[0]);
            pingAccumulator.seconds_not_moving = Integer.parseInt(ping_array[1]);
            pingAccumulator.meters = Integer.parseInt(ping_array[2]);
            pingAccumulator.absolute_bearing = Integer.parseInt(ping_array[3]);
            pingAccumulator.is_bearing_valid = (Integer.parseInt(ping_array[4]) == 1 ? true : false);
            pingAccumulator.was_moving = false;
            pingAccumulator.is_moving_valid = false;
            if (ping_array.length >= 7) {
                pingAccumulator.was_moving = (Integer.parseInt(ping_array[5]) == 1 ? true : false);
                pingAccumulator.is_moving_valid = (Integer.parseInt(ping_array[6]) == 1 ? true : false);
            }

        }

        // pingAccumulator.seconds_moving

    } // restoreCrashData()



    /////////////////////////////////////////////////
    // startTrip()
    //   indicates a new trip is starting (e.g. ignition turned on). (accumulated ping should clear)
    /////////////////////////////////////////////////
    public void startTrip() {
        pingAccumulator.clear();
    }


    public void clearOdometer() {
        virtual_odometer_m = 0;
        service.state.writeStateLong(State.VIRTUAL_ODOMETER, virtual_odometer_m);
    }


    boolean setMovingFlag(int speed_cms) {

        int threshold_cms = service.config.readParameterInt(Config.SETTING_MOVING_THRESHOLD, Config.PARAMETER_MOVING_THRESHOLD_CMS);

        if (speed_cms > threshold_cms) {
            flagMoving = true;
        } else {
            flagMoving = false;
        }

        return flagMoving;
    } // set Moving Flag

    ////////////////////////////////////////////////
    // checkSpeeding: called every second.
    ////////////////////////////////////////////////
    boolean checkSpeeding(double speed_cms) {

        // For speeding, there is a high-state, a low-state, and an in-between-state
        // to be speeding you must be higher than the high threshold for debounce time
        // to be not speeding you must be lower than the low threshold for debounce time

        int high_threshold = service.config.readParameterInt(Config.SETTING_SPEEDING, Config.PARAMETER_SPEEDING_CMS);
        int low_threshold = high_threshold;
        if (low_threshold > 200) low_threshold-=200; else low_threshold = 0;

        int time_secs = service.config.readParameterInt(Config.SETTING_SPEEDING, Config.PARAMETER_SPEEDING_SECONDS);


        if (((speed_cms > high_threshold) && (!flagSpeeding)) ||
            ((speed_cms < low_threshold) && (flagSpeeding))
            ) { // we are in the wrong state and should be debouncing if not already
            if (debounceSpeeding == 0) debounceSpeeding = time_secs;
        }
        else { // we are not in a wrong state, so we should not be debouncing
            debounceSpeeding = 0;
        }

        if (debounceSpeeding > 0) { // we are debouncing
            debounceSpeeding--;
            if (debounceSpeeding == 0) {  // done debouncing
                flagSpeeding = !flagSpeeding;
                if (flagSpeeding) {
                    Log.i(TAG, "Speeding On");
                    service.addEvent(EventType.EVENT_TYPE_SPEEDING);
                } else {
                    Log.i(TAG, "Speeding Off");
                }
            }
        }
        return flagSpeeding;
    } // checkSpeeding()


    ////////////////////////////////////////////////
    // stopIdling()
    //  force a stop to idling (e.g. ignition turns off)
    ////////////////////////////////////////////////
    public void stopIdling() {

        checkIdling(true); // just pretend we started moving
    }

    ////////////////////////////////////////////////
    // checkIdling: called every second
    ////////////////////////////////////////////////
    boolean checkIdling(boolean is_moving) {

        // To be idling, engine status must be on and we must be not moving
        // i.e. if engine status is off OF we are moving then we are not idling

        if ((is_moving) || // we are moving OR
           (!service.io.status.flagEngineStatus)) // engine is not on
        {
            // we are therefore no longer idling
            debounceIdling = 0;
            if (flagIdling) { // we were idling
                Log.i(TAG, "Idling Off");
                flagIdling = false;
                service.addEvent(EventType.EVENT_TYPE_IDLING_OFF);
            }
        } else { // not moving and engine status is on
            debounceIdling++;
            if (!flagIdling) { // we are not idling, check if we should be
                int threshold_seconds = service.config.readParameterInt(Config.SETTING_IDLING, Config.PARAMETER_IDLING_SECONDS);
                if ((threshold_seconds > 0) &&
                   (debounceIdling >= threshold_seconds)) { // past threshold
                    Log.i(TAG, "Idling On");
                    flagIdling = true; // we should be idling.
                    continuous_idling_s = debounceIdling; // so it is set to correct value before triggering event
                    service.addEvent(EventType.EVENT_TYPE_IDLING_ON);
                }
            }
            if (flagIdling) // if we are idling, make sure the variable always reflects the correct, full time
                continuous_idling_s = debounceIdling;
        }

        return flagIdling;
    } // checkIdling()


    boolean checkAccelerating(double forward_accel_cms2) {

        int threshold_cms2 = service.config.readParameterInt(Config.SETTING_ACCELERATING, Config.PARAMETER_ACCELERATING_CMS2);
        int time_tenthsecs = service.config.readParameterInt(Config.SETTING_ACCELERATING, Config.PARAMETER_ACCELERATING_TENTHS);

        if (((forward_accel_cms2 > threshold_cms2) && (!flagAccelerating)) ||
            ((forward_accel_cms2 < threshold_cms2) && (flagAccelerating))
            ) { // in a wrong state, we should be debouncing
            if (debounceAccelerating == 0) debounceAccelerating = time_tenthsecs;
        } else { // not in a wrong state, should not be debouncing
            debounceAccelerating = 0;
        }

        if (debounceAccelerating > 0) { // we are debouncing
            debounceAccelerating--;
            if (debounceAccelerating == 0) { // done debouncing
                flagAccelerating = !flagAccelerating;
                if (flagAccelerating) {
                    Log.i(TAG, "Rapid Accelerating On");
                    service.addEvent(EventType.EVENT_TYPE_ACCELERATING);
                } else {
                    Log.i(TAG, "Rapid Accelerating Off");
                }
            }
        }
        return flagAccelerating;
    } // checkAccelerating()

    boolean checkBraking(double forward_accel_cms2) {
        int threshold_cms2 = service.config.readParameterInt(Config.SETTING_BRAKING, Config.PARAMETER_BRAKING_CMS2 );
        int time_tenthsecs = service.config.readParameterInt(Config.SETTING_BRAKING, Config.PARAMETER_BRAKING_TENTHS);

        threshold_cms2 *= -1;   // braking is always a negative acceleration

        if ( ((forward_accel_cms2 < threshold_cms2) && (!flagBraking)) ||
             ((forward_accel_cms2  > threshold_cms2) && (flagBraking))
            ) { // in a wrong state, we should be debouncing
            if (debounceBraking == 0) debounceBraking = time_tenthsecs;
        } else { // not in wrong state, should not be debouncing
            debounceBraking = 0;
        }

        if (debounceBraking > 0) { // debounce
            debounceBraking--;
            if (debounceBraking == 0) {
                flagBraking = !flagBraking;
                if (flagBraking) {
                    Log.i(TAG, "Hard Braking On");
                    service.addEvent(EventType.EVENT_TYPE_BRAKING);
                } else {
                    Log.i(TAG, "Hard Braking Off");
                }
            }
        }

        return flagBraking;
    } // checkBraking()

    boolean checkCornering(double orthogonal_accel_cms2) {
        int threshold_cms2 = service.config.readParameterInt(Config.SETTING_CORNERING, Config.PARAMETER_CORNERING_CMS2);
        int time_tenthsecs = service.config.readParameterInt(Config.SETTING_CORNERING, Config.PARAMETER_CORNERING_TENTHS);


        if (((orthogonal_accel_cms2 > threshold_cms2) && (!flagCornering)) ||
            ((orthogonal_accel_cms2  < threshold_cms2) && (flagCornering))
           ) { // in wrong state, should be debouncing
            if (debounceCornering  == 0) debounceCornering = time_tenthsecs;
        } else { // not in wrong state, should not be debouncing
            debounceCornering = 0;
        }


        if (debounceCornering > 0) { // we are debouncing
            debounceCornering--;
            if (debounceCornering == 0) { // done debouncing
                flagCornering = !flagCornering;
                if (flagCornering) {
                    Log.i(TAG, "Harsh Cornering On");
                    service.addEvent(EventType.EVENT_TYPE_CORNERING);
                } else {
                    Log.i(TAG, "Harsh Cornering Off");
                }
            }
        }

        return flagCornering;
    } // checkCornering()


    ////////////////////////////////////////////////
    // checkPing:
    //   is it time to send a ping ?
    ////////////////////////////////////////////////
    boolean checkPing(PingAccumulator pingAccumulator, Integer newBearing, Boolean newMoving) {

        int threshold_secs_moving = service.config.readParameterInt(Config.SETTING_PING, Config.PARAMETER_PING_SECONDS_MOVING);
        int threshold_meters_moving = service.config.readParameterInt(Config.SETTING_PING, Config.PARAMETER_PING_METERS_MOVING);
        int threshold_delta_heading_moving = service.config.readParameterInt(Config.SETTING_PING, Config.PARAMETER_PING_DEGREES_MOVING);
        int threshold_secs_not_moving = service.config.readParameterInt(Config.SETTING_PING, Config.PARAMETER_PING_SECONDS_NOTMOVING);

        boolean should_trigger;
        should_trigger = false;

        if (threshold_secs_moving > 0)
            if (pingAccumulator.seconds_moving >= threshold_secs_moving) {
                Log.i(TAG, "Ping Triggered (seconds moving = " + pingAccumulator.seconds_moving + ")");
                should_trigger = true;
            }
        if (threshold_secs_not_moving > 0)
            if (pingAccumulator.seconds_not_moving >= threshold_secs_not_moving) {
                Log.i(TAG, "Ping Triggered (seconds not moving = " + pingAccumulator.seconds_not_moving + ")");
                should_trigger = true;
            }


        if (threshold_meters_moving > 0)
            if (pingAccumulator.meters >= threshold_meters_moving) {
                Log.i(TAG, "Ping Triggered (meters moving = " + pingAccumulator.meters + ")");
                should_trigger = true;
            }

        if ((threshold_delta_heading_moving > 0) &&
                (pingAccumulator.is_bearing_valid) && // valid old bearing
                (newBearing != null)) // valid new bearing
        {
            int diff_bearing = Math.abs(pingAccumulator.absolute_bearing - newBearing.intValue());
            // a bearing change can't ever be more than 180 degrees:
            if (diff_bearing > 180) diff_bearing = 360 - diff_bearing;

            if (diff_bearing >= threshold_delta_heading_moving) {
                Log.i(TAG, "Ping Triggered (delta bearing = " + diff_bearing + ")");
                should_trigger = true;
            }
        }

        if ((pingAccumulator.is_moving_valid) &&
            (newMoving != null)) {
            if ((pingAccumulator.was_moving) && // we were moving
                (!newMoving.booleanValue())) { // we are not now moving
                Log.i(TAG, "Ping Triggered (moving status change, moving now = " + newMoving.booleanValue() + ")");
                should_trigger = true;
            }
            else
            if  ((!pingAccumulator.was_moving) && // we were not moving
                (newMoving.booleanValue())) { // we are now moving
                // just make sure we remember we were moving
                pingAccumulator.setLastMoving(newMoving.booleanValue());
            }
        }

        if ((!pingAccumulator.is_bearing_valid) &&
            (newBearing != null)) {
            pingAccumulator.setLastBearing(newBearing.intValue());
        }

        if ((!pingAccumulator.is_moving_valid) &&
                (newMoving != null)) {
            pingAccumulator.setLastMoving(newMoving.booleanValue());
        }



        if (should_trigger) {
            service.addEvent(EventType.EVENT_TYPE_PING);
            // clear the seconds and meters ping accumulators and set the bearing to the current value
            pingAccumulator.clear();
            if (newBearing != null) {
                pingAccumulator.setLastBearing(newBearing.intValue());
            }
            if (newMoving != null) {
                pingAccumulator.setLastMoving(newMoving.booleanValue());
            }

            return true;
        }
        return false;
    } // checkPing()


    long lastIntegratorRunTime, lastDifferentiatorRunTime;

    /////////////////////////////////////////////////////
    // IntegratorTask()
    //  Run every 1 second.
    //  This integrates the speed so long as the speed is not older than MAX_TIME ( = 10 seconds)
    //  It also handles checks for Speeding, Idling, and Pinging since the 1s interval is convenient
    /////////////////////////////////////////////////////
    class IntegratorTask implements Runnable {

        @Override
        public void run() {

            try {
                Log.vv(TAG, "integratorTask()");

                long now = SystemClock.elapsedRealtime();
                if (savedLocation.time_elapsed + FIX_INTEGRATOR_MAX_TIME_MS > now) {

                    // only update the odometer if we are not degradating
                    if (flagMoving) {
                        int rounded_distance_m = (savedLocation.speed_cms + 50) / 100; // ( * 1 second)
                        virtual_odometer_m += rounded_distance_m;
                        pingAccumulator.meters += rounded_distance_m;
                    }


                    checkIdling(flagMoving);

                    checkSpeeding(savedLocation.speed_cms);
                } else {
                    // This can happen if say, ignition is on but engine status is not
                    lastIntegratorRunTime += 1;
                    Log.vv(TAG, "integratation skipped b/c Time Gap Large = " +
                            (savedLocation.time_elapsed ==0 ?
                                    " (No Prior)" :
                                    "" + (now - savedLocation.time_elapsed) + "ms"));
                }

                if (flagMoving) {
                    pingAccumulator.seconds_moving += 1;
                } else {
                    pingAccumulator.seconds_not_moving += 1;
                }

                checkPing(pingAccumulator,
                        (((savedLocation != null) && (flagMoving)) ?
                        new Integer(savedLocation.bearing) :
                        null),
                        ((savedLocation != null) ? flagMoving : null)
                    );

            } catch (Exception e) {
                Log.e(TAG, "integratorTask Exception " + e.toString(), e);
            }
        } // run()
    } // class: IntegratorTask

    /////////////////////////////////////////////////////
    // DifferentiatorTask()
    //  Run every 1/10th second.
    //  It takes the differentiated speed and checks for acceleration conditions so long
    //      as the differentiated speed is not older than MAX_TIME (= 2 seconds)
    //  Note that the acceleration data is likely at a slower frequency (4Hz?)
    //     than the configuration setting resolutions (10Hz).
    /////////////////////////////////////////////////////
    class DifferentiatorTask implements Runnable {

        @Override
        public void run() {
            try {
                Log.vv(TAG, "differentiatorTask()");

                long now = SystemClock.elapsedRealtime();
                if (savedLocation.time_elapsed + FIX_DIFFERENTIATOR_MAX_TIME_MS > now) {

                    // now check for movement conditions:

                    // 1) too much positive acceleration
                    // 2) too much negative acceleration
                    // 3) too much orthogonal acceleration
                    checkAccelerating(savedAcceleration.forward_acceleration_cms2);
                    checkBraking(savedAcceleration.forward_acceleration_cms2);
                    checkCornering(savedAcceleration.orthogonal_acceleration_cms2);
                }
                else {
                    Log.vv(TAG, "differentiation skipped b/c Time Gap Large = " +
                            (savedLocation.time_elapsed ==0 ?
                                    " (No Prior)" :
                                    "" + (now - savedLocation.time_elapsed) + "ms"));

                    lastDifferentiatorRunTime += 1;
                }

            } catch (Exception e) {
                Log.e(TAG, "differentiatorTask Exception " + e.toString(), e);
            }
        } // run()
    } // class: DifferentiatorTask


} // class
