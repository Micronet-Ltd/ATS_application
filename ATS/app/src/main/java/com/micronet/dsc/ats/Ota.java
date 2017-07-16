/////////////////////////////////////////////////////////////
// Ota:
//  handles when to send messages over the air, including:
//      . back-off timers
//      . network state and signal monitors
// Checks the network status, status of queue, and blackouts to determine when to attempt a message
/////////////////////////////////////////////////////////////


package com.micronet.dsc.ats;


import android.content.BroadcastReceiver;
import android.content.Context;

import android.telephony.TelephonyManager;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;

//import java.util.concurrent.ScheduledThreadPoolExecutor;
//import java.util.concurrent.TimeUnit;

import android.os.Handler;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.content.Intent;
import android.content.IntentFilter;


import java.util.Arrays;

public class Ota {

    public static final String TAG = "ATS-Ota";

    public static final int CHECK_TIMER_MS_NORMAL = 500; // every 500 ms do a check for messages?
    public static final int CHECK_TIMER_MS_JUSTSENT = 200; // check again in 200 ms after sending something


    public static final int WATCHDOG_AIRPLANE_TOGGLE_ON_MS = 4000; // keep airplane mode on for 4000 ms when toggling it.
    public static final int WATCHDOG_MOBILEDATA_TOGGLE_ON_MS = 4000; // keep mobile data off for 4000 ms when toggling it.

    /*
    public static final int[] BACKOFF_DELAYS_SECONDS = {30, // 30 seconds
                                                    60, // 1 min
                                                    120, // 2 min
                                                    240, // 4 min
                                                    480, // 8 min
                                                    960, // 16 min
                                                    1920,   // 32 min
                                                    3840,    // 1 hr 4 min
                                                    7680,    // 2 hr 8 min
                                                    15360    // 4 hr 16 min
    };
    */

    MainService service; // contains the context for this service
    Handler mainHandler = null;
    SignalStrengthChangeListener mySignalStrengthListener;


    int watchdogStage = 0; // what stage the com watchdog is at.
    int airplaneToggleStage = 0; // what stage the airplane toggle portion of com watchdog is at (turn on or turn off)
    int mobiledataToggleStage = 0; // what stage the mobile data toggle portion of com watchdog is at (turn on or turn off)

    // This app can communicate with multiple "Servers"

    static final int NUM_SUPPORTED_SERVERS = 2; // Note, DO NOT CHANGE: this is also dependent on the value in Queue class

    class Server {
        boolean isEnabled = false; // has this server been enabled
        String name = ""; // a name for this server to use in logging
        Udp udp = null; // the UDP datagram socket
        Backoff backoff = null; // the back-off timers

        int localPort;
        String remoteAddress;
        int remotePort;
        String[] delays_array;
    }

    Server[] servers = new Server[NUM_SUPPORTED_SERVERS];



    public Ota(MainService service) {
        this.service = service;


        for (int i =0 ; i < NUM_SUPPORTED_SERVERS; i++) {
            servers[i] = new Server();
            servers[i].name = new String("s" + (i+1));
        }

    } //

    //////////////////////////////////////////////////////////////////
    // start();
    //  start listening for cellular updates (called at app start)
    //////////////////////////////////////////////////////////////////
    public void start() {

        // Three ways that we will start our servers and try to send a message
        Log.v(TAG, "start");

        for (int i =0; i < NUM_SUPPORTED_SERVERS; i++) {
            servers[i].udp = new Udp(service.isUnitTesting, servers[i].name);
            servers[i].backoff = new Backoff(i);
            servers[i].localPort = getServerLocalPort(i);
            servers[i].remoteAddress = getServerRemoteAddress(i);
            servers[i].remotePort = getServerRemotePort(i);
            servers[i].delays_array = getServerBackoffArray(i);
        }

        // Register a Signal Strength Listener
        mySignalStrengthListener = new SignalStrengthChangeListener();
        ((TelephonyManager) service.context.getSystemService(Context.TELEPHONY_SERVICE)).
                listen(mySignalStrengthListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

        // Register a Connectivity Change Listener
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        service.context.registerReceiver(connectivityChangeReceiver, filter);

        // Register a check timer
        mainHandler  = new Handler();
        mainHandler.postDelayed(checkTask, CHECK_TIMER_MS_NORMAL);

        // make sure we are not accidentally left in airplane mode or with mobile data off
        service.power.setAirplaneMode(false);
        service.power.setMobileDataEnabled(true);
    } // start()

    //////////////////////////////////////////////////////////////////
    // stop();
    //  stops listening for cellular updates (called at app end
    //////////////////////////////////////////////////////////////////
    public void stop() {

        Log.v(TAG, "stop");

        // remove the three ways to start the listener and start the service
        service.context.unregisterReceiver(connectivityChangeReceiver);
        mainHandler.removeCallbacks(checkTask);

        for (int i = 0 ; i < NUM_SUPPORTED_SERVERS; i++) {
            servers[i].backoff.clearBackoff();
        }
        clearWatchdog();

        ((TelephonyManager) service.context.getSystemService(Context.TELEPHONY_SERVICE)).
                listen(mySignalStrengthListener, PhoneStateListener.LISTEN_NONE);

        // since w started udp from this class, we should stop it from here too.
        for (int i = 0 ; i < NUM_SUPPORTED_SERVERS; i++) {
            servers[i].isEnabled = false;
            if (servers[i].udp != null)
                servers[i].udp.stop();
        }

    } // stop()

    //////////////////////////////////////////////////////////////////
    // destroy();
    //  Called after saving crash data
    //////////////////////////////////////////////////////////////////
    public void destroy() {
    }


    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////
    //  Retrieve Server-specific Configuration Settings

    ///////////////////////////////////////////////////////////////
    //  getServerBackoffArray()
    //      gets the configured array for the specific server
    ///////////////////////////////////////////////////////////////
    String[] getServerBackoffArray(int server_num) {
        Log.vv(TAG, "getServerBackoffArray(" + servers[server_num].name + ")");
        if (server_num == 0) { // primary
            return service.config.readParameterArray(Config.SETTING_BACKOFF_RETRIES);
        } else {
            return service.config.readParameterArray(Config.SETTING_SECONDARY_BACKOFF_RETRIES);
        }
    } // getServerBackoffArray

    ///////////////////////////////////////////////////////////////
    //  getServerLocalPort()
    //      gets the configured port for the specified server
    ///////////////////////////////////////////////////////////////
    int getServerLocalPort(int server_num) {
        Log.vv(TAG, "getServerLocalPort(" + servers[server_num].name + ")");
        if (server_num == 0) { // primary
            return service.config.readParameterInt(Config.SETTING_LOCAL_PORT, Config.PARAMETER_LOCAL_ADDRESS_PORT);
        } else {
            return service.config.readParameterInt(Config.SETTING_SECONDARY_LOCAL_PORT, Config.PARAMETER_SECONDARY_LOCAL_ADDRESS_PORT);
        }
    } // getServerLocalPort

    ///////////////////////////////////////////////////////////////
    //  getServerRemotePort()
    //      gets the configured port for the specified server
    ///////////////////////////////////////////////////////////////
    int getServerRemotePort(int server_num) {
        Log.vv(TAG, "getServerRemotePort(" + servers[server_num].name + ")");
        if (server_num == 0) { // primary
            return service.config.readParameterInt(Config.SETTING_SERVER_ADDRESS, Config.PARAMETER_SERVER_ADDRESS_PORT);
        } else {
            return service.config.readParameterInt(Config.SETTING_SECONDARY_SERVER_ADDRESS, Config.PARAMETER_SECONDARY_SERVER_ADDRESS_PORT);
        }
    } // getServerRemotePort


    ///////////////////////////////////////////////////////////////
    //  getServerRemoteAddress()
    //      gets the configured port for the specified server
    ///////////////////////////////////////////////////////////////
    String getServerRemoteAddress(int server_num) {
        Log.vv(TAG, "getServerRemoteAddress(" + servers[server_num].name + ")");
        if (server_num == 0) { // primary
            return service.config.readParameterString(Config.SETTING_SERVER_ADDRESS, Config.PARAMETER_SERVER_ADDRESS_IP);
        } else {
            return service.config.readParameterString(Config.SETTING_SECONDARY_SERVER_ADDRESS, Config.PARAMETER_SECONDARY_SERVER_ADDRESS_IP);
        }
    } // getServerRemoteAddress()






    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////
    // Generic Data Connection Monitoring
    //      Are we connected to a data network that can send/receive messages?
    //      What Network, what signal strength? etc..
    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////



    static class ConnectInfo {
        ConnectInfo() {}
        String networkOperator;
        int phoneType;
        int networkType;
        int dataState;
        boolean isRoaming;
        int signalStrength;
    }

    ///////////////////////////////////////////////////
    // getConnectInfo()
    //  get information about the GSM connection
    ///////////////////////////////////////////////////
    public ConnectInfo getConnectInfo() {

        Log.vv(TAG, "getConnectInfo()");

        ConnectInfo connectInfo = new ConnectInfo();

        TelephonyManager telephonyManager = (TelephonyManager) service.context.getSystemService(Context.TELEPHONY_SERVICE);


        // Note: phoneType may return none if voice calls are not supported.
        connectInfo.phoneType = telephonyManager.getPhoneType(); //
        //  PHONE_TYPE_NONE
        //  PHONE_TYPE_GSM
        //  PHONE_TYPE_CDMA
        //  PHONE_TYPE_SIP

        connectInfo.networkOperator = telephonyManager.getNetworkOperator();


        connectInfo.networkType = telephonyManager.getNetworkType();


        connectInfo.dataState = telephonyManager.getDataState(); // cellular state only
        // DATA_DISCONNECTED
        // DATA_CONNECTING
        // DATA_CONNECTED
        // DATA_SUSPENDED


        connectInfo.signalStrength = listenerSignalStrength;


        // Try to get whether we are roaming from the active network
        final ConnectivityManager connMgr = (ConnectivityManager)
                service.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo activeNetwork =  connMgr.getActiveNetworkInfo();

        if (activeNetwork == null) {
            // No Active Network -- can;t be roaming
            connectInfo.isRoaming = false;
        } else
        if (activeNetwork.getType() != ConnectivityManager.TYPE_MOBILE) {
            // Active network is not mobile -- can't be roaming
            connectInfo.isRoaming = false;
        } else
        {
            // Active network is a mobile network .. check if we are roaming
            connectInfo.isRoaming = activeNetwork.isRoaming();
        }

        Log.d(TAG, "Connectivity: Phone: " + connectInfo.phoneType + "; " +
                   "Op: " + connectInfo.networkOperator + "; " +
                   "Name: " + telephonyManager.getNetworkOperatorName() + "; " +
                   "Net: " + connectInfo.networkType + "; " +
                   "Data: " + connectInfo.dataState + "; " +
                   "Roam: " + connectInfo.isRoaming + "; " +
                   "Rssi: " + connectInfo.signalStrength + "; "
        );
        return connectInfo;
    }



    ///////////////////////////////////////////////////////////////
    // ConnectivityChangeReceiver: receives notification when the network changes
    ///////////////////////////////////////////////////////////////
    private BroadcastReceiver connectivityChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {

            try {
                Log.v(TAG, "connectivityChangeReceiver()");
                String action = intent.getAction();
                if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                    Log.e(TAG, "ConnectivityChangeReceiver received wrong action??");
                    return;
                }

                // Figure out what the new connectivity is and send if needed
                final ConnectivityManager connMgr = (ConnectivityManager)
                        service.context.getSystemService(Context.CONNECTIVITY_SERVICE);

                final NetworkInfo active =  connMgr.getActiveNetworkInfo();

                if (active == null) {
                    Log.d(TAG, "Connectivity: NONE");
                } else {
                    Log.d(TAG, "Connectivity: connected = " + active.isConnected() + " Type " + active.getTypeName());
                }

                for (int i=0; i < NUM_SUPPORTED_SERVERS; i++) {
                    servers[i].backoff.backoffUntilConnectivityChanges = false;
                    attemptSendMessage(i);
                }

            } catch(Exception e) {
                Log.e(TAG + ".connectivityChangeReceiver", "Exception: " + e.toString(), e);
            }
        } // OnReceive()
    }; // connectivityChangeReceiver()



    // Implementation Details so that we always know the signal strength of the GSM

    int listenerSignalStrength  = 99; // >=0 (99) means unknown

    ///////////////////////////////////////////////////////////////
    // signalStrengthChangeListener: receives notification when signal strength changes
    ///////////////////////////////////////////////////////////////
    private class SignalStrengthChangeListener extends PhoneStateListener {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            super.onSignalStrengthsChanged(signalStrength);
            // Remember the GSM Signal Strength, valid values are (0-31, 99) as defined in TS 27.007 8.5

            try {
                Log.vv(TAG, "signalStrengthChangeReceiver()");
                if (signalStrength.isGsm()) {
                    listenerSignalStrength = convertGSMStrengthtoDBM(signalStrength.getGsmSignalStrength());
                    Log.d(TAG, "Signal Strength: GSM = " + listenerSignalStrength);
                } else {
                    listenerSignalStrength = signalStrength.getCdmaDbm();
                    Log.d(TAG, "Signal Strength: CDMA = " + listenerSignalStrength);
                }

                for (int i=0; i < NUM_SUPPORTED_SERVERS; i++) {
                    attemptSendMessage(i); // maybe we went over the threshold
                }
            } catch(Exception e) {
                Log.e(TAG + ".SignalStrengthChangeListener", "Exception: " + e.toString(), e);
            }
        }
    } // signal strength change listener


    // returns a dBM equivalent of the GSM signal strength
    static int convertGSMStrengthtoDBM(int gsm_strength) {
        // 0        -113 dBm or less
        // 1        -111 dBm
        // 2...30   -109... -53 dBm
        // 31        -51 dBm or greater

        // 99 not known or not detectable
        if (gsm_strength == 99) return 0;

        return -113 + (2 *gsm_strength);

    } // convertGSMStrengthtoDBM()



    ////////////////////////////////////////////////////////////////////
    // populateQueueItem()
    //  called every time we trigger an event to store the data from the class
    ////////////////////////////////////////////////////////////////////
    public void populateQueueItem(QueueItem item) {
        try {

            ConnectInfo connectInfo = getConnectInfo();

            item.is_roaming = connectInfo.isRoaming;
            item.signal_strength = (byte) connectInfo.signalStrength;
            item.network_type = (byte) connectInfo.networkType;
            item.carrier_id = 0; // in case we can't parse the network operator into a number

            try {
                item.carrier_id = Integer.parseInt(connectInfo.networkOperator);
            } catch (Exception e) {
                //Log.v(TAG, "networkOperator " + connectInfo.networkOperator + " is not a number");
            }


        } catch (Exception e) {
            Log.e(TAG, "Exception populateQueueItem() " + e.toString(), e);
        }
    }





    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////
    // Backoff Functionality
    //      Backoff ensures that we don't send messages too quickly when a server is not responding
    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////

    class Backoff { // there will be one backoff class for each server

        int backoffDelayIndex = 0;
        boolean backoffUntilTimer = false;
        boolean backoffUntilConnectivityChanges = false;

        int backoffServerNumber = 0;


        public Backoff(int server_num) {
            backoffServerNumber = server_num;
        }


        ///////////////////////////////////////////////////////////////
        // isInBackoff()
        //  Returns:
        //      True if we are in a backoff period and must wait to retry the message
        ///////////////////////////////////////////////////////////////
        private boolean isInBackoff() {
            if ((backoffUntilTimer) ||
                    (backoffUntilConnectivityChanges))
                return true;

            return false;
        } // isInBackoff()

        ///////////////////////////////////////////////////////////////
        // nextBackoff()
        //  Use this to start the next backoff period, e.g. after each send attempt of an ACKABLE message
        ///////////////////////////////////////////////////////////////
        private void nextBackoff() {

            Log.v(TAG, "nextBackoff(" + servers[backoffServerNumber].name + ")");
            //String[] delays_array = getServerBackoffArray(backoffServerNumber);
            int delay_ms = 10000; // default of 10 seconds if this parameter cant be read

            try {
                if (servers[backoffServerNumber].delays_array[backoffDelayIndex] != null)
                    delay_ms = Integer.parseInt(servers[backoffServerNumber].delays_array[backoffDelayIndex]) * 1000;
            } catch (Exception e) {
                Log.w(TAG, "Config parameter " + servers[backoffServerNumber].name + " " +
                            " Backoff delay at index " + backoffDelayIndex +
                            " is not a number (= " + servers[backoffServerNumber].delays_array[backoffDelayIndex] + ")");
            }

            if (delay_ms > 0) {
                // wait for specified time period
                mainHandler.postDelayed(backoffTask, delay_ms);
                backoffUntilTimer = true;
            } else {
                // wait until a change in cellular connection
                backoffUntilConnectivityChanges = true;
            }

            if (backoffDelayIndex < servers[backoffServerNumber].delays_array.length - 1)
                backoffDelayIndex++;
            else
                backoffDelayIndex = 0; //start over from beginning

            // record the state info in case of shutdown ??
            //  NO, allow this to reset at sleep/wakeup.

        } // nextBackoff()

        ///////////////////////////////////////////////////////////////
        // clearBackoff()
        //  Use this to stop the backoff if it is running, e.g. after an ACK is received
        ///////////////////////////////////////////////////////////////
        private void clearBackoff() {

            Log.vv(TAG, "clearBackoff(" + servers[backoffServerNumber].name + ")");

            mainHandler.removeCallbacks(backoffTask);
            backoffUntilConnectivityChanges = false;
            backoffUntilTimer = false;
            backoffDelayIndex = 0;
        }


        ///////////////////////////////////////////////////////////////
        // backoffTask()
        //  Timer that blocks the retrying of messages during the given back-off period.
        ///////////////////////////////////////////////////////////////
        private Runnable backoffTask = new Runnable() {

            @Override
            public void run() {
                try {
                    backoffUntilTimer = false;
                    Log.v(TAG, "backoffTask(" + servers[backoffServerNumber].name + "): timer expired");
                    attemptSendMessage(backoffServerNumber);
                } catch (Exception e) {
                    Log.e(TAG + ".backoffTask", "Exception: " + e.toString(), e);
                }
            }
        }; // backoffTask()
    } // class Backoff




    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////
    // Watchdog Functionality
    //      Watchdog only applies to the primary server
    //      It checks that we are receiving communications from the server and resets device if not
    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////

    public static final int WATCHDOG_STAGE_OFF = 0;
    public static final int WATCHDOG_STAGE_UDP = 1;
    public static final int WATCHDOG_STAGE_MOBILEDATA = 2;
    public static final int WATCHDOG_STAGE_AIRPLANE = 3;
    public static final int WATCHDOG_STAGE_RILDRIVER = 4;
    public static final int WATCHDOG_STAGE_POWERDOWN = 5;


    ///////////////////////////////////////////////////////////////
    // startWatchdog()
    //  Use this to start the next watchdog period,
    //      The watchdog will check for communication with the server within the designated time, or will fire an escalating action
    //      Watchdog only applies to server#1
    ///////////////////////////////////////////////////////////////
    private void startWatchdog() {


        Log.vv(TAG, "startWatchdog() ");
        if (watchdogStage != WATCHDOG_STAGE_OFF) return; // watchdog is already running

        escalateWatchdog(); // escalate watchdog to the next stage

    } // startWatchdog()



    ///////////////////////////////////////////////////////////////
    // escalateWatchdog()
    //  Use this to go to the next step in the watchdog, e.g. after a stage expires and is fired.
    ///////////////////////////////////////////////////////////////
    private void escalateWatchdog() {
        int nextseconds, nextstage;
        nextseconds = 0;

        if (watchdogStage < WATCHDOG_STAGE_UDP) {
            nextseconds = service.config.readParameterInt(Config.SETTING_COMWATCHDOG, Config.PARAMETER_COMWATCHDOG_TIME1);
            if (nextseconds != 0) watchdogStage = WATCHDOG_STAGE_UDP;
        }

        if ((nextseconds == 0) && (watchdogStage < WATCHDOG_STAGE_MOBILEDATA)) {
            nextseconds = service.config.readParameterInt(Config.SETTING_COMWATCHDOG, Config.PARAMETER_COMWATCHDOG_TIME2);
            if (nextseconds != 0) watchdogStage = WATCHDOG_STAGE_MOBILEDATA;
        }

        // Do NOT ever toggle airplane mode
        /*
        if ((nextseconds == 0) && (watchdogStage < WATCHDOG_STAGE_AIRPLANE)) {
            nextseconds = service.config.readParameterInt(Config.SETTING_COMWATCHDOG, Config.PARAMETER_COMWATCHDOG_TIME2);
            if (nextseconds != 0) watchdogStage = WATCHDOG_STAGE_AIRPLANE;
        }
        */

        if ((nextseconds == 0) && (watchdogStage < WATCHDOG_STAGE_RILDRIVER)) {
            nextseconds = service.config.readParameterInt(Config.SETTING_COMWATCHDOG, Config.PARAMETER_COMWATCHDOG_TIME3);
            if (nextseconds != 0) watchdogStage = WATCHDOG_STAGE_RILDRIVER;
        }

        if ((nextseconds == 0) && (watchdogStage < WATCHDOG_STAGE_POWERDOWN)) {
            nextseconds = service.config.readParameterInt(Config.SETTING_COMWATCHDOG, Config.PARAMETER_COMWATCHDOG_TIME_SHUTDOWN);
            if (nextseconds != 0) watchdogStage = WATCHDOG_STAGE_POWERDOWN;
        }


        if (nextseconds != 0) {
            Log.d(TAG, "Server-Comm Watchdog will escalate to stage " + (watchdogStage) + " in " +  nextseconds + " seconds");
            mainHandler.postDelayed(watchdogTask, nextseconds * 1000); // to ms from seconds
        } else {
            //Log.d(TAG, "Server Comm watchdog not set");
        }

    } // escalateWatchdog()



    ///////////////////////////////////////////////////////////////
    // clearWatchdog()
    //  Use this to stop the com watchdog if it is running, e.g. after an ACK is received
    ///////////////////////////////////////////////////////////////
    private void clearWatchdog() {

        if (watchdogStage != 0){
            Log.v(TAG, "clearWatchdog()");
        }

        stopMobileDataToggling();
        stopAirplaneToggling();
        stopRilToggling();
        mainHandler.removeCallbacks(watchdogTask);
        watchdogStage= 0;

    } // clearWatchdog()



    ///////////////////////////////////////////////////////////////
    // startAirplaneToggling()
    //  start toggling airplane mode
    ///////////////////////////////////////////////////////////////
    private void startMobileDataToggling() {
        mobiledataToggleStage = 0;
        mainHandler.postDelayed(mobileDataToggleTask, 500); // try to start toggling, use minimal initial delay (0.5 seconds)
    }

    ///////////////////////////////////////////////////////////////
    // stopAirplaneToggling()
    //  make sure we are no longer toggling airplane mode
    ///////////////////////////////////////////////////////////////
    private void stopMobileDataToggling() {
        mainHandler.removeCallbacks(mobileDataToggleTask);
        if (mobiledataToggleStage > 0) {
            // if we turned mobile data off -- turn it back on
            service.power.setMobileDataEnabled(true); //we want to be out of airplane mode
        }
    }


    ///////////////////////////////////////////////////////////////
    // mobileDataToggleTask()
    //  Timer that controls toggling on and off of airplane mode when started by the com watchdog
    ///////////////////////////////////////////////////////////////
    private Runnable mobileDataToggleTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.i(TAG, "Server-Comm Watchdog mobile data toggle step " + mobiledataToggleStage );

                int nextseconds;

                switch (mobiledataToggleStage) {

                    case 0: // Turn Mobile Data Off
                        mobiledataToggleStage |= 1;
                        service.addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_OTA_STAGE_MOBILEDATA);
                        service.power.setMobileDataEnabled(false);
                        mainHandler.postDelayed(mobileDataToggleTask, WATCHDOG_MOBILEDATA_TOGGLE_ON_MS); // come back in four seconds to turn airplane mode off
                        break;
                    case 1: // Turn Mobile Data On
                        service.power.setMobileDataEnabled(true);

                        nextseconds = service.config.readParameterInt(Config.SETTING_COMWATCHDOG, Config.PARAMETER_COMWATCHDOG_RETRY_SECONDS);

                        mobiledataToggleStage = 0; // restart
                        if (nextseconds > 0) {
                            // come back in a bit to toggle airplane mode again
                            Log.d(TAG, "Will retry mobile data toggle in " + nextseconds + "s");
                            mainHandler.postDelayed(mobileDataToggleTask, nextseconds * 1000); // convert from seconds to ms
                        }
                        break;
                }
            } catch(Exception e) {
                Log.e(TAG + ".mobileDataToggleTask", " + Exception (Stage " + mobiledataToggleStage + "): " + e.toString(), e);
                mainHandler.postDelayed(mobileDataToggleTask, 4000); // not sure what to do ... try again in 4 sec ?
                return;
            }

        }

    }; // mobileDataToggleTask()




    ///////////////////////////////////////////////////////////////
    // startAirplaneToggling()
    //  start toggling airplane mode
    ///////////////////////////////////////////////////////////////
    private void startAirplaneToggling() {
        airplaneToggleStage = 0;
        mainHandler.postDelayed(airplaneToggleTask, 500); // try to start toggling, use minimal initial delay (0.5 seconds)
    }

    ///////////////////////////////////////////////////////////////
    // stopAirplaneToggling()
    //  make sure we are no longer toggling airplane mode
    ///////////////////////////////////////////////////////////////
    private void stopAirplaneToggling() {
        mainHandler.removeCallbacks(airplaneToggleTask);
        if (airplaneToggleStage > 0) {
            //we turned airplane mode on .. turn it back off
            service.power.setAirplaneMode(false); // we want to be out of airplane mode
        }
    }


    ///////////////////////////////////////////////////////////////
    // airplaneToggleTask()
    //  Timer that controls toggling on and off of airplane mode when started by the com watchdog
    ///////////////////////////////////////////////////////////////
    private Runnable airplaneToggleTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.i(TAG, "Server-Comm Watchdog airplane mode toggle step " + airplaneToggleStage );

                int nextseconds;

                switch (airplaneToggleStage) {

                    case 0: // Turn Airplane Mode On
                        airplaneToggleStage |= 1;
                        service.addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_OTA_STAGE_AIRPLANEMODE);
                        service.power.setAirplaneMode(true);
                        mainHandler.postDelayed(airplaneToggleTask, WATCHDOG_AIRPLANE_TOGGLE_ON_MS); // come back in four seconds to turn airplane mode off
                        break;
                    case 1: // Turn Airplane Mode Off
                        service.power.setAirplaneMode(false);

                        nextseconds = service.config.readParameterInt(Config.SETTING_COMWATCHDOG, Config.PARAMETER_COMWATCHDOG_RETRY_SECONDS);

                        airplaneToggleStage = 0; // restart
                        if (nextseconds > 0) {
                            // come back in a bit to toggle airplane mode again
                            Log.d(TAG, "Will retry airplane mode toggle in " + nextseconds + "s");
                            mainHandler.postDelayed(airplaneToggleTask, nextseconds * 1000); // convert from seconds to ms
                        }
                        break;
                }
            } catch(Exception e) {
                Log.e(TAG + ".airplaneToggleTask", " + Exception (Stage " + airplaneToggleStage + "): " + e.toString(), e);
                mainHandler.postDelayed(airplaneToggleTask, 4000); // not sure what to do ... try again in 4 sec ?
                return;
            }

        }

    }; // airplaneToggleTask()



    ///////////////////////////////////////////////////////////////
    // startRilToggling()
    //  start toggling of RIL driver
    ///////////////////////////////////////////////////////////////
    private void startRilToggling() {
        mainHandler.postDelayed(rilToggleTask, 500); // try to start toggling, use minimal initial delay (0.5 seconds)
    }

    ///////////////////////////////////////////////////////////////
    // stopRilToggling()
    //  make sure we are no longer restarting the RIL driver
    ///////////////////////////////////////////////////////////////
    private void stopRilToggling() {
        mainHandler.removeCallbacks(rilToggleTask);
    }


    ///////////////////////////////////////////////////////////////
    // rilToggleTask()
    //  Timer that controls toggling of RIL Driver when started by the com watchdog
    ///////////////////////////////////////////////////////////////
    private Runnable rilToggleTask= new Runnable() {

        @Override
        public void run() {
            try {
                Log.i(TAG, "Server-Comm Watchdog ril driver restart");

                int nextseconds;



                int exitcode = service.power.restartRILDriver();

                if (exitcode == 0) {
                    // success
                    service.addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_OTA_STAGE_RILDRIVER);
                } else {
                    // failure
                    service.addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_OTA_STAGE_RILDRIVER_FAILURE);
                }

                nextseconds = service.config.readParameterInt(Config.SETTING_COMWATCHDOG, Config.PARAMETER_COMWATCHDOG_RETRY_SECONDS);

                if (nextseconds > 0) {
                    // come back in a bit to toggle airplane mode again
                    Log.d(TAG, "Will retry in " + nextseconds + "s");
                    mainHandler.postDelayed(rilToggleTask, nextseconds * 1000); // convert from seconds to ms
                }


            } catch(Exception e) {
                Log.e(TAG + ".rilToggleTask", " + Exception " + e.toString(), e);
                mainHandler.postDelayed(airplaneToggleTask, 4000); // not sure what to do ... try again in 4 sec ?
                return;
            }

        }

    }; // rilToggleTask()


    ///////////////////////////////////////////////////////////////
    // watchdogTask()
    //  Timer that checks it hasn't been too long since we last communicated with server
    ///////////////////////////////////////////////////////////////
    private Runnable watchdogTask = new Runnable() {

        @Override
        public void run() {
            try {
                //backoffUntilTimer = false;
                Log.i(TAG, "Server-Comm Watchdog triggered @ stage=" + watchdogStage);

                switch(watchdogStage) {

                    case WATCHDOG_STAGE_UDP: // first stage, attempt ____
                        // check back in xx
                        service.addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_OTA_STAGE_UDP);
                        for (int i = 0 ; i < NUM_SUPPORTED_SERVERS; i++) {
                            // try to stop all communication with servers
                            servers[i].isEnabled = false;
                            if (servers[i].udp != null) servers[i].udp.stop();
                        }
                        // the servers/sockets will be opened by checkTask if it is closed like every 500 ms.
                        escalateWatchdog(); // escalate to next stage
                        break;

                    case WATCHDOG_STAGE_MOBILEDATA: // Start airplane mode toggling
                        startMobileDataToggling();
                        escalateWatchdog(); // escalate to next stage
                        break;

                    case WATCHDOG_STAGE_AIRPLANE: // Start airplane mode toggling
                        stopMobileDataToggling();
                        startAirplaneToggling();
                        escalateWatchdog(); // escalate to next stage
                        break;

                    case WATCHDOG_STAGE_RILDRIVER: // restart the RIL driver
                        stopMobileDataToggling();
                        stopAirplaneToggling();
                        startRilToggling();
                        escalateWatchdog(); // escalate to next stage
                        break;
                    case WATCHDOG_STAGE_POWERDOWN: // shutdown the device
                        stopMobileDataToggling();
                        stopAirplaneToggling();
                        stopRilToggling();
                        service.addEventWithExtra(EventType.EVENT_TYPE_ERROR, EventType.ERROR_OTA_STAGE_REBOOT);
                        service.power.powerDown();
                        break;
                }

            } catch(Exception e) {
                Log.e(TAG + ".watchdogTask", " + Exception (Stage " + watchdogStage + "): " + e.toString(), e);
                mainHandler.postDelayed(watchdogTask, 4000); // try again in 4 sec ?
                return;
            }
        }
    }; // watchdogTask()



    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////
    // Message Handling
    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////




    /////////////////////////////////////////////////////////////
    // requiresAck()
    // returns
    //  true it the message (queued item) will require an ACK
    //  false if no ACK will be required
    /////////////////////////////////////////////////////////////
    private boolean requiresAck(QueueItem queueItem) {

        if ((queueItem.event_type_id == EventType.EVENT_TYPE_NAK) ||
            (queueItem.event_type_id == EventType.EVENT_TYPE_ACK) ||
            (queueItem.event_type_id == EventType.EVENT_TYPE_ACK_TOP)) {
            return false;
        }

        return true; // all other messages require us to send an ACK;

        /*
        Code to check from a configuration setting
        String[] messages_needing_ack = config.readParameterArray(Config.SETTING_ACK_MESSAGES);

        int event_type_id = queueItem.getEventTypeId();
        int i;
        for (i=0; i < messages_needing_ack.length; i++) {
            if (event_type_id == try { Integer.parseInt(messages_needing_ack[i])) return true; } catch() etc..
        }

        return false; // no ACK required
        */
    }


    /////////////////////////////////////////////////////////////
    // attemptListenMessage()
    //      attempts to listen for messages on all servers (e.g. start UDP server)
    /////////////////////////////////////////////////////////////
    private void attemptListenMessage() {

        //Log.v(TAG, "attemptListen()");

        for (int i=0; i < NUM_SUPPORTED_SERVERS; i++) {
            Log.vv(TAG, "attemptListen(" + i + ")");
            if ((!servers[i].isEnabled) || (servers[i].udp.hasStopped())) {
                Log.vv(TAG, "attemptListen: udp has stopped");
                // we need to start up the UDP
                //Log.d(TAG, "ret udp (" + servers[i].name + "):" + localPort + " -> " + remoteAddress + ":" + remotePort);
                if ((servers[i].localPort != 0) && (servers[i].remotePort !=0) &&
                        (servers[i].remoteAddress != null) && (!servers[i].remoteAddress.isEmpty())) {
                    Log.d(TAG, "start udp (" + servers[i].name + "):" + servers[i].localPort + " -> " + servers[i].remoteAddress + ":" + servers[i].remotePort);
                    if (!servers[i].udp.start(servers[i].localPort, servers[i].remoteAddress, servers[i].remotePort)) {
                        // Error should have already been logged
                        servers[i].isEnabled = false;
                    } else {
                        servers[i].isEnabled = true;
                    }
                } // we have the needed server parameters
            } // server is stopped
        } // each supported server

        Log.vv(TAG, "END attemptListen()");
    } // attemptListenMessage()


    ////////////////////////////////////////////////////////
    // isDataNetworkConnected()
    //  checks if we have a current connection to an acceptable data network
    //  returns either NULL or the current network connection
    ////////////////////////////////////////////////////////
    public NetworkInfo isDataNetworkConnected() {
        Log.vv(TAG, "isDataNetworkConnected()");
        final ConnectivityManager connMgr = (ConnectivityManager)
                service.context.getSystemService(Context.CONNECTIVITY_SERVICE);

        final NetworkInfo active = connMgr.getActiveNetworkInfo();

        if (active == null) return null; // no active network
        if (!active.isConnected()) return null; // network is not connected, cannot make an attempt


        int noncellularok = service.config.readParameterInt(Config.SETTING_SERVERCOMMUNICATION, Config.PARAMETER_SERVERCOMMUNICATION_NONCELLULAR_OK);
        if (noncellularok == 0) {
            // require mobile network (for security)
            if (active.getType() != ConnectivityManager.TYPE_MOBILE)
                return null; // only allow the mobile network (no wi-fi, etc)
        }
        return active;
    } // isDataNetworkConnected()



    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////
    // Per-Server Message Handling
    ///////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////





    ////////////////////////////////////////////////////////
    // attemptSendMessage()
    //  attempts to send a message
    // Returns : true if an attempt was made (this is then used to speed up checks for responses)
    //           false if no attempt was made
    ////////////////////////////////////////////////////////
    private boolean attemptSendMessage(int server_number) {
        Log.vv(TAG, "attemptSend(" + servers[server_number].name + ")");


        // Have we enabled this server .. if not, then there is no reason to try and send
        if (!servers[server_number].isEnabled) return false;

        // Is there something in the queue to send ?
        QueueItem queueItem = service.queue.getFirstItem(server_number);

        if (queueItem == null) return false; // nothing in queue to attempt

        if (server_number == 0) { // only start watchdog for primary server
            startWatchdog(); // start the watchdog timer if it is not already running
        }

        // Now that the watchdog is running, we can see if it is OK to attempt a send.
        // Watchdog will fire even if we never attempt a send in case we are stuck somewhere else

        if (servers[server_number].backoff.isInBackoff()) return false;    // in a back-off period, cannot make an attempt yet

        NetworkInfo active = isDataNetworkConnected();
        if (active == null) return false; // we have no connection to an acceptable data network

        // TODO: signal strength check ?
        //   A: don't do this for now as the platform has problems reporting the correct signal strength (Apr2014)


        // Make sure that the IO is fully initialized

        if (!service.io.isFullyInitialized()) return false; // do nothing until we have initialized IO calls


        Log.vv(TAG, "Sending over network type " + active.getTypeName());

        // OK, proceed with the attempt.

        sendMessage(server_number, queueItem);

        return true;
    } // attemptSendMessage()




    /////////////////////////////////////////////////////////////////
    // attemptReceiveMessage()
    //  checks if there are any messages to "Receive" from any endpoints and receives them
    //  currently, the only thing to receive from is the UDP servers.
    //  Parameters:
    //      server_number: which server to check (0 = primary server, 1 = secondary, etc..)
    // Returns true if a valid message was received, otherwise false
    /////////////////////////////////////////////////////////////////
    private boolean attemptReceiveMessage(int server_number) {

        if ((server_number < 0) || (server_number >= NUM_SUPPORTED_SERVERS)) return false; // safety

        Log.vv(TAG, "attemptReceive(" + servers[server_number].name + ")");

        Codec.IncomingMessage message;
        message = servers[server_number].udp.receiveMessage();
        if (message == null) return false; // no messages to receive
        return receiveMessage(server_number, message);
    } // attemptReceiveMessage();


    /////////////////////////////////////////////////////////////////
    // receiveMessage()
    //  "receives" a particular message immediately (decodes and processes it)
    //  Parameters:
    //      server_number: which server this message is from (0 = primary server)
    //  Returns True if a valid message was received, otherwise false.
    /////////////////////////////////////////////////////////////////
    private boolean receiveMessage(int server_number, Codec.IncomingMessage message) {

        if ((server_number < 0) || (server_number >= NUM_SUPPORTED_SERVERS)) return false; // safety

        Log.v(TAG, "Received message from server " +servers[server_number].name);


        Codec codec = new Codec(service);

        QueueItem item = codec.decodeMessage(message);

        if (item == null) {
            // Not a decodeable message for us, might be junk or spam, just ignore it
            // Do Not NAK (since we don't really know if this came from the server)
            return false;
        }

        if (item.event_type_id == EventType.EVENT_TYPE_ACK_TOP) {
            // process this ack
            // delete item at top of queue no matter what
            Log.v(TAG, "Processing message: ACK-TOP");

            if (item.additional_data_bytes != null) {
                // take of the top item for the designated server
                service.queue.deleteTopItem(item.additional_data_bytes[0]);
            }
            else {
                // take of the top from this server
                service.queue.deleteTopItem(server_number);
            }
        } else
        if (item.event_type_id == EventType.EVENT_TYPE_ACK) {
            // process this ack
            // delete item at top of queue if if matches this ACK
            Log.v(TAG, "Processing message: ACK");

            service.queue.deleteItemBySequenceId(server_number, item.sequence_id, Codec.SEQUENCE_ID_RECEIVE_MASK);
        } else
        //if (item.event_type_id == QueueItem.EVENT_TYPE_RESTART_IO) {
            //Log.d(TAG, "message is RESTART-IO");
            // try to send this ack first
            //service.io.restart();
            //sendAck(item);
        //} else
        if (item.event_type_id == EventType.EVENT_TYPE_RESTART) {
            Log.v(TAG, "Processing message: RESTART");
            // try to send this ack first
            sendAck(server_number, item);

            int maxAttempts = 60; // wait a maximum of 3 seconds ?
            while ((maxAttempts > 0) &&
                  (servers[server_number].udp.hasOutgoingMessage())) {
                try {
                    Thread.sleep(50);
                } catch(Exception e) {
                    Log.e(TAG, ".receiveMessage() Error in Thread.sleep(50)");
                }
                maxAttempts--;
            }
            if (maxAttempts == 0) {
                Log.e(TAG, "Unable to ACK RESTART message before timing out, restarting without ACK");
            }
            service.power.restartAtsProcess(Power.RESTART_REASON_OTA_REQUEST);
        } else
        if (item.event_type_id == EventType.EVENT_TYPE_CLEAR_QUEUE) {
            // process this ack
            // delete item at top of queue if if matches this ACK
            Log.v(TAG, "Processing message: CLEAR-QUEUE");
            service.queue.clearAll();
            sendAck(server_number, item);
        } else

        if (item.event_type_id == EventType.EVENT_TYPE_RESET_FOTA_UPDATER) {
            Log.v(TAG, "Processing message: RESET-FOTA-UPDATER");
            service.power.resetFotaUpdater();
            sendAck(server_number, item);
        } else
        if (item.event_type_id == EventType.EVENT_TYPE_CLEAR_ODOMETER) {
            Log.v(TAG, "Processing message: CLEAR-ODOMETER");
            service.position.clearOdometer();
            sendAck(server_number, item);
        } else
        if (item.event_type_id == EventType.EVENT_TYPE_MOREMAPW) {
            // process this configuration write command

            Log.v(TAG, "Processing message: MoRemapW");
            //additional data bytes contains the internal event code followed by the remapped value

            if ((item.additional_data_bytes == null) ||
                    (item.additional_data_bytes.length != 2)) {
                // send a NAK
                sendNak(server_number, Codec.NAK_MISSING_REQUIRED_DATA, item);
                return false;
            } else { // acceptable command

                int internal_event_code, external_event_code;
                internal_event_code = item.additional_data_bytes[0];
                external_event_code = item.additional_data_bytes[1];
                if (!service.codemap.writeMoEventCode(internal_event_code, external_event_code)) {
                    sendNak(server_number, Codec.NAK_ERROR_IN_SAVING, item);
                } else {
                    sendAck(server_number, item);
                }
            }
        } else
        if (item.event_type_id == EventType.EVENT_TYPE_MTREMAPW) {
            // process this configuration write command

            Log.v(TAG, "Processing message: MoRemapW");
            //additional data bytes contains the external event code followed by the remapped value

            if ((item.additional_data_bytes == null) ||
                    (item.additional_data_bytes.length != 2)) {
                // send a NAK
                sendNak(server_number, Codec.NAK_MISSING_REQUIRED_DATA, item);
                return false;
            } else { // acceptable command

                int internal_event_code, external_event_code;
                external_event_code = item.additional_data_bytes[0];
                internal_event_code = item.additional_data_bytes[1];
                if (!service.codemap.writeMtEventCode(external_event_code, internal_event_code)) {
                    sendNak(server_number, Codec.NAK_ERROR_IN_SAVING, item);
                } else {
                    sendAck(server_number, item);
                }
            }
        } else
        if (item.event_type_id == EventType.EVENT_TYPE_CONFIGW) {
            // process this configuration write command

            Log.v(TAG, "Received message: ConfigW");
            //additional data bytes contains the configuration setting index (1 byte) followed by data

            if ((item.additional_data_bytes == null) ||
                (item.additional_data_bytes.length == 0)) {
                // send a NAK
                sendNak(server_number, Codec.NAK_MISSING_REQUIRED_DATA, item);
                return false;
            }
            else { // acceptable command

                int setting_id;
                setting_id = item.additional_data_bytes[0];

                if (!service.config.settingExists(setting_id)) {
                    sendNak(server_number, Codec.NAK_BAD_SETTING_ID, item);
                } else
                if (item.additional_data_bytes.length == 1) {
                    // Delete the setting from config
                    if (!service.config.clearSetting(setting_id)) {
                        sendNak(server_number, Codec.NAK_ERROR_IN_SAVING, item);
                    } else {
                        sendAck(server_number, item);
                    }
                } else {
                    // Write the new setting value

                    byte[] setting_value_bytes = Arrays.copyOfRange(item.additional_data_bytes, 1, item.additional_data_bytes.length);
                    String setting_value;
                    try {
                        setting_value = new String(setting_value_bytes, "ISO-8859-1");
                    } catch (Exception e) {
                        sendNak(server_number, Codec.NAK_BAD_VALUE_ENCODING, item);
                        return false;
                    }

                    if (!service.config.writeSetting(setting_id, setting_value)) {
                        sendNak(server_number, Codec.NAK_ERROR_IN_SAVING, item);
                    } else {
                        sendAck(server_number, item);
                    }
                } // write new setting value
            } // acceptable command

        } else {
            // send a NAK for unknown type
            sendNak(server_number, Codec.NAK_UNKNOWN_COMMAND, item);
        }

        // clear the backoff timer (since we are talking to server)
        servers[server_number].backoff.clearBackoff();

        // clear the watchdog timer (since we are talking to server)
        if (server_number == 0) { // only clear watchdog for primary server
            clearWatchdog();
        }

        // attempt to send something from queue if we have something
        attemptSendMessage(server_number);

        return true;
    } // receive message



    /////////////////////////////////////////////////////////////////
    // sendNak()
    //  sends a NAK message immediately
    /////////////////////////////////////////////////////////////////
    private void sendNak(int server_number, int nak_reason, QueueItem receivedItem) {


        Log.d(TAG, "sendNak(" + servers[server_number].name + ") Reason=" + nak_reason);

        // get extra info to incorporate into the message
        ConnectInfo connectInfo = getConnectInfo();


        QueueItem queueItem = new QueueItem();
        queueItem.event_type_id = EventType.EVENT_TYPE_NAK;
        queueItem.sequence_id = receivedItem.sequence_id;
        queueItem.additional_data_bytes = new byte[1];
        queueItem.additional_data_bytes[0] = (byte) nak_reason;
        // add position information
        service.position.populateQueueItem(queueItem);
        // add I/O information
        service.io.populateQueueItem(queueItem);



        Codec codec = new Codec(service);
        Codec.OutgoingMessage encodedMessage = codec.encodeMessage(queueItem, connectInfo);

        // Check if we need to start the listener
        attemptListenMessage();

        // Send the Datagram
        servers[server_number].udp.sendMessage(encodedMessage);

    } // sendNak()


    /////////////////////////////////////////////////////////////////
    // sendAck()
    //  sends an ACK message immediately
    /////////////////////////////////////////////////////////////////
    private void sendAck(int server_number, QueueItem receivedItem) {


        Log.d(TAG, "sendAck(" + servers[server_number].name + ")");

        // get extra info to incorporate into the message
        ConnectInfo connectInfo = getConnectInfo();


        QueueItem queueItem = new QueueItem();
        queueItem.event_type_id = EventType.EVENT_TYPE_ACK;
        queueItem.sequence_id = receivedItem.sequence_id;
        // add position information
        service.position.populateQueueItem(queueItem);
        // add I/O information
        service.io.populateQueueItem(queueItem);


        Codec codec = new Codec(service);
        Codec.OutgoingMessage encodedMessage = codec.encodeMessage(queueItem, connectInfo);

        // Check if we need to start the listener
        attemptListenMessage();

        // Send the Datagram
        servers[server_number].udp.sendMessage(encodedMessage);

    } // sendAck()


    /////////////////////////////////////////////////////////////////
    // sendMessage()
    //  sends the given Queued message immediately and starts back-off timers if required
    /////////////////////////////////////////////////////////////////
    private void sendMessage(int server_number, QueueItem queueItem) {


        Log.v(TAG, "sendMessage(" + servers[server_number].name + ")");

        // get extra info to incorporate into the message
        ConnectInfo connectInfo = getConnectInfo();

        Codec codec = new Codec(service);
        Codec.OutgoingMessage encodedMessage = codec.encodeMessage(queueItem, connectInfo);

        // Check if we need to start the listener
        attemptListenMessage();

        // Send the Datagram

        servers[server_number].udp.sendMessage(encodedMessage);

        // If this message requires an ACK, then set the back-off timer

        if (requiresAck(queueItem)) {
            // set the backoff timer
            Log.v(TAG, servers[server_number].name + " ACK Rqd");
            servers[server_number].backoff.nextBackoff(); // start the backoff timer if required
        } else {
            // remove from queue right away
            Log.v(TAG, servers[server_number].name + " NO ACK Rqd");
            if (server_number == 0) { // only clear watchdog for primary server
                clearWatchdog(); // clear the watchdog timer (since we don't expect an ACK)
            }
            service.queue.deleteItemByID(queueItem.getId());
        }
    } // sendMessage()




    ///////////////////////////////////////////////////////////////
    // checkTask()
    //  Timer that checks 1) if we are listening
    //      2) If there are items in the queue that need to be sent
    ///////////////////////////////////////////////////////////////
    private Runnable checkTask = new Runnable() {

        @Override
        public void run() {
            try {
                Log.vv(TAG, "checkTask()");

                boolean justsent;

                attemptListenMessage();

                justsent = false;
                for (int i = 0 ; i < NUM_SUPPORTED_SERVERS; i++) {
                    justsent = attemptReceiveMessage(i) || justsent;
                    justsent = attemptSendMessage(i) || justsent;
                }

                if (justsent)
                    mainHandler.postDelayed(checkTask, CHECK_TIMER_MS_JUSTSENT);
                else
                    mainHandler.postDelayed(checkTask, CHECK_TIMER_MS_NORMAL);
                Log.vv(TAG, "checkTask() END");
            } catch(Exception e) {
                Log.e(TAG + ".checkTask", "Exception: " + e.toString(), e);
                mainHandler.postDelayed(checkTask, CHECK_TIMER_MS_NORMAL);
            }
        }
    }; // checkTask()





} // class Ota
