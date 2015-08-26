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

    public static final int CHECK_TIMER_MS_NORMAL = 500; // every 500 ms do a check ?
    public static final int CHECK_TIMER_MS_JUSTSENT = 200; // check again in 200 ms after sending something
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

    Handler mainHandler = null;
    SignalStrengthChangeListener mySignalStrengthListener;


    Udp myUdp = null;

    MainService service; // contains the context

    public Ota(MainService service) {
        this.service = service;

    } //

    //////////////////////////////////////////////////////////////////
    // start();
    //  start listening for cellular updates (called at app start)
    //////////////////////////////////////////////////////////////////
    public void start() {

        // Three ways that we will start our servers and try to send a message
        Log.v(TAG, "start");

        myUdp = new Udp();

        // Register a Signal Strength Listener
        mySignalStrengthListener = new SignalStrengthChangeListener();
        ((TelephonyManager) service.context.getSystemService(Context.TELEPHONY_SERVICE)).
                listen(mySignalStrengthListener,PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);

        // Register a Connectivity Change Listener
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        service.context.registerReceiver(connectivityChangeReceiver, filter);

        // Register a check timer
        mainHandler  = new Handler();
        mainHandler.postDelayed(checkTask, CHECK_TIMER_MS_NORMAL);

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
        clearBackoff();
        ((TelephonyManager) service.context.getSystemService(Context.TELEPHONY_SERVICE)).
                listen(mySignalStrengthListener,PhoneStateListener.LISTEN_NONE);

        // since w started udp from this class, we should stop it from here too.
        if (myUdp != null)
            myUdp.stop();

    }

    //////////////////////////////////////////////////////////////////
    // destroy();
    //  Called after saving crash data
    //////////////////////////////////////////////////////////////////
    public void destroy() {
    }



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

        Log.v(TAG, "getConnectInfo()");

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






    int backoffDelayIndex = 0;
    boolean backoffUntilTimer = false;
    boolean backoffUntilConnectivityChanges = false;


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

        Log.v(TAG, "nextBackoff()");

        String[] delays_array = service.config.readParameterArray(Config.SETTING_BACKOFF_RETRIES);

        int delay_ms = 10000; // default of 10 seconds if this parameter cant be read

        try {
            if (delays_array[backoffDelayIndex] != null)
                delay_ms = Integer.parseInt(delays_array[backoffDelayIndex]) * 1000;
        } catch (Exception e) {
            Log.w(TAG, "Config Parameter Backoff delay at index " + backoffDelayIndex + " is not a number (= " + delays_array[backoffDelayIndex] + ")");
        }

        if (delay_ms > 0) {
            // wait for specified time period
            mainHandler.postDelayed(backoffTask, delay_ms);
            backoffUntilTimer = true;
        } else {
            // wait until a change in cellular connection
            backoffUntilConnectivityChanges = true;
        }

        if (backoffDelayIndex < delays_array.length - 1)
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

        Log.v(TAG, "clearBackoff()");

            mainHandler.removeCallbacks(backoffTask);
            backoffUntilConnectivityChanges = false;
            backoffUntilTimer = false;
            backoffDelayIndex= 0;
    }


    /////////////////////////////////////////////////////////////
    // requiresAck()
    // returns
    //  true it the message (queued item) will require an ACK
    //  false if no ACK will be required
    /////////////////////////////////////////////////////////////
    private boolean requiresAck(QueueItem queueItem) {

        if ((queueItem.event_type_id == QueueItem.EVENT_TYPE_NAK) ||
            (queueItem.event_type_id == QueueItem.EVENT_TYPE_ACK) ||
            (queueItem.event_type_id == QueueItem.EVENT_TYPE_ACK_TOP)) {
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
    //      attempts to listen for messages (e.g. start UDP server)
    /////////////////////////////////////////////////////////////
    private void attemptListenMessage() {

        Log.vv(TAG, "attemptListen()");

        if (myUdp.hasStopped()) {
            // we need to start up the UDP

            int localPort = service.config.readParameterInt(Config.SETTING_LOCAL_PORT, Config.PARAMETER_LOCAL_ADDRESS_PORT);
            String remoteAddress = service.config.readParameterString(Config.SETTING_SERVER_ADDRESS, Config.PARAMETER_SERVER_ADDRESS_IP);
            int remotePort = service.config.readParameterInt(Config.SETTING_SERVER_ADDRESS, Config.PARAMETER_SERVER_ADDRESS_PORT);

            Log.d(TAG, "start udp :" + localPort + " -> " + remoteAddress + ":" + remotePort);
            if (!myUdp.start(localPort, remoteAddress, remotePort)) {
                return;
            }
        }

    }


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


        int noncellularok = service.config.readParameterInt(Config.SETTING_COMMUNICATION, Config.PARAMETER_COMMUNICATION_NONCELLULAR_OK);
        if (noncellularok == 0) {
            // require mobile network (for security)
            if (active.getType() != ConnectivityManager.TYPE_MOBILE)
                return null; // only allow the mobile network (no wi-fi, etc)
        }
        return active;
    } // isDataNetworkConnected()



    ////////////////////////////////////////////////////////
    // attemptSendMessage()
    //  attempts to send a message
    // Returns : true if an attempt was made (this is then used to speed up checks for responses)
    //           false if no attempt was made
    ////////////////////////////////////////////////////////
    private boolean attemptSendMessage() {
        Log.vv(TAG, "attemptSend()");

        if (isInBackoff()) return false;    // in a back-off period, cannot make an attempt yet

        NetworkInfo active = isDataNetworkConnected();
        if (active == null) return false; // we have no connection to an acceptable data network

        // TODO: signal strength check ?
        //   A: don't do this for now as the platform has problems reporting the correct signal strength (Apr2014)

        QueueItem queueItem = service.queue.getFirstItem();

        if (queueItem == null) return false; // nothing in queue to attempt


        // Make sure that the IO is fully initialized

        if (!service.io.isFullyInitialized()) return false; // do nothing until we have initialized IO calls


        Log.v(TAG, "Sending over network type " + active.getTypeName());

        // OK, proceed with the attempt.

        sendMessage(queueItem);

        return true;
    } // attemptSendMessage()




    /////////////////////////////////////////////////////////////////
    // attemptReceiveMessage()
    //  checks if there are any messages to "Receive" from any endpoints and receives them
    //  currently, the only thing to receive from is the UDP server.
    // Returns true if a valid message was received, otherwise false
    /////////////////////////////////////////////////////////////////
    private boolean attemptReceiveMessage() {

        Log.vv(TAG, "attemptReceive()");

        Codec.IncomingMessage message = myUdp.receiveMessage();
        if (message == null) return false; // no messages to receive
        return receiveMessage(message);
    } // attemptReceiveMessage();


    /////////////////////////////////////////////////////////////////
    // receiveMessage()
    //  "receives" a particular message immediately (decodes and processes it)
    //  Returns True if a valid message was received, otherwise false.
    /////////////////////////////////////////////////////////////////
    private boolean receiveMessage(Codec.IncomingMessage message) {
        Log.v(TAG, "receiveMessage()");


        Codec codec = new Codec(service);

        QueueItem item = codec.decodeMessage(message);

        if (item == null) {
            // Not a decodeable message for us, might be junk or spam, just ignore it
            // Do Not NAK (since we don't really know if this came from the server)
            return false;
        }

        if (item.event_type_id == QueueItem.EVENT_TYPE_ACK_TOP) {
            // process this ack
            // delete item at top of queue no matter what
            Log.v(TAG, "Processing message: ACK-TOP");
            service.queue.deleteTopItem();
        } else
        if (item.event_type_id == QueueItem.EVENT_TYPE_ACK) {
            // process this ack
            // delete item at top of queue if if matches this ACK
            Log.v(TAG, "Processing message: ACK");
            service.queue.deleteItemBySequenceId(item.sequence_id);
        } else
        //if (item.event_type_id == QueueItem.EVENT_TYPE_RESTART_IO) {
            //Log.d(TAG, "message is RESTART-IO");
            // try to send this ack first
            //service.io.restart();
            //sendAck(item);
        //} else
        if (item.event_type_id == QueueItem.EVENT_TYPE_RESTART) {
            Log.v(TAG, "Processing message: RESTART");
            // try to send this ack first
            sendAck(item);

            int maxAttempts = 60; // wait a maximum of 3 seconds ?
            while ((maxAttempts > 0) &&
                  (myUdp.hasOutgoingMessage())) {
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
            service.power.restartService();
        } else
        if (item.event_type_id == QueueItem.EVENT_TYPE_CLEAR_QUEUE) {
            // process this ack
            // delete item at top of queue if if matches this ACK
            Log.v(TAG, "Processing message: CLEAR-QUEUE");
            service.queue.clearAll();
            sendAck(item);
        } else
        if (item.event_type_id == QueueItem.EVENT_TYPE_CLEAR_ODOMETER) {
            Log.v(TAG, "Processing message: CLEAR-ODOMETER");
            service.position.clearOdometer();
            sendAck(item);
        } else
        if (item.event_type_id == QueueItem.EVENT_TYPE_MOREMAPW) {
            // process this configuration write command

            Log.v(TAG, "Processing message: MoRemapW");
            //additional data bytes contains the internal event code followed by the remapped value

            if ((item.additional_data_bytes == null) ||
                    (item.additional_data_bytes.length != 2)) {
                // send a NAK
                sendNak(Codec.NAK_MISSING_REQUIRED_DATA, item);
                return false;
            } else { // acceptable command

                int internal_event_code, external_event_code;
                internal_event_code = item.additional_data_bytes[0];
                external_event_code = item.additional_data_bytes[1];
                if (!service.codemap.writeMoEventCode(internal_event_code, external_event_code)) {
                    sendNak(Codec.NAK_ERROR_IN_SAVING, item);
                } else {
                    sendAck(item);
                }
            }
        } else
        if (item.event_type_id == QueueItem.EVENT_TYPE_MTREMAPW) {
            // process this configuration write command

            Log.v(TAG, "Processing message: MoRemapW");
            //additional data bytes contains the external event code followed by the remapped value

            if ((item.additional_data_bytes == null) ||
                    (item.additional_data_bytes.length != 2)) {
                // send a NAK
                sendNak(Codec.NAK_MISSING_REQUIRED_DATA, item);
                return false;
            } else { // acceptable command

                int internal_event_code, external_event_code;
                external_event_code = item.additional_data_bytes[0];
                internal_event_code = item.additional_data_bytes[1];
                if (!service.codemap.writeMtEventCode(external_event_code, internal_event_code)) {
                    sendNak(Codec.NAK_ERROR_IN_SAVING, item);
                } else {
                    sendAck(item);
                }
            }
        } else
        if (item.event_type_id == QueueItem.EVENT_TYPE_CONFIGW) {
            // process this configuration write command

            Log.v(TAG, "Received message: ConfigW");
            //additional data bytes contains the configuration setting index (1 byte) followed by data

            if ((item.additional_data_bytes == null) ||
                (item.additional_data_bytes.length == 0)) {
                // send a NAK
                sendNak(Codec.NAK_MISSING_REQUIRED_DATA, item);
                return false;
            }
            else { // acceptable command

                int setting_id;
                setting_id = item.additional_data_bytes[0];

                if (!service.config.settingExists(setting_id)) {
                    sendNak(Codec.NAK_BAD_SETTING_ID, item);
                } else
                if (item.additional_data_bytes.length == 1) {
                    // Delete the setting from config
                    if (!service.config.clearSetting(setting_id)) {
                        sendNak(Codec.NAK_ERROR_IN_SAVING, item);
                    } else {
                        sendAck(item);
                    }
                } else {
                    // Write the new setting value

                    byte[] setting_value_bytes = Arrays.copyOfRange(item.additional_data_bytes, 1, item.additional_data_bytes.length);
                    String setting_value;
                    try {
                        setting_value = new String(setting_value_bytes, "ISO-8859-1");
                    } catch (Exception e) {
                        sendNak(Codec.NAK_BAD_VALUE_ENCODING, item);
                        return false;
                    }

                    if (!service.config.writeSetting(setting_id, setting_value)) {
                        sendNak(Codec.NAK_ERROR_IN_SAVING, item);
                    } else {
                        sendAck(item);
                    }
                } // write new setting value
            } // acceptable command

        } else {
            // send a NAK for unknown type
            sendNak(Codec.NAK_UNKNOWN_COMMAND, item);
        }

        // clear the backoff timer (since we are talking to server)
        clearBackoff();
        // attempt to send something from queue if we have something
        attemptSendMessage();

        return true;
    } // receive message



    /////////////////////////////////////////////////////////////////
    // sendNak()
    //  sends a NAK message immediately
    /////////////////////////////////////////////////////////////////
    private void sendNak(int nak_reason, QueueItem receivedItem) {


        Log.d(TAG, "sendNak() Reason=" + nak_reason);

        // get extra info to incorporate into the message
        ConnectInfo connectInfo = getConnectInfo();


        QueueItem queueItem = new QueueItem();
        queueItem.event_type_id = QueueItem.EVENT_TYPE_NAK;
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
        myUdp.sendMessage(encodedMessage);

    } // sendNak()


    /////////////////////////////////////////////////////////////////
    // sendAck()
    //  sends an ACK message immediately
    /////////////////////////////////////////////////////////////////
    private void sendAck(QueueItem receivedItem) {


        Log.d(TAG, "sendAck()");

        // get extra info to incorporate into the message
        ConnectInfo connectInfo = getConnectInfo();


        QueueItem queueItem = new QueueItem();
        queueItem.event_type_id = QueueItem.EVENT_TYPE_ACK;
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
        myUdp.sendMessage(encodedMessage);

    } // sendAck()


    /////////////////////////////////////////////////////////////////
    // sendMessage()
    //  sends the given Queued message immediately and starts back-off timers if required
    /////////////////////////////////////////////////////////////////
    private void sendMessage(QueueItem queueItem) {


        Log.v(TAG, "sendMessage()");

        // get extra info to incorporate into the message
        ConnectInfo connectInfo = getConnectInfo();

        Codec codec = new Codec(service);
        Codec.OutgoingMessage encodedMessage = codec.encodeMessage(queueItem, connectInfo);

        // Check if we need to start the listener
        attemptListenMessage();

        // Send the Datagram

        myUdp.sendMessage(encodedMessage);

        // If this message requires an ACK, then set the back-off timer

        if (requiresAck(queueItem)) {
            // set the backoff timer
            Log.v(TAG, "(ACK Rqd)");
            nextBackoff();
        } else {
            // remove from queue right away
            Log.v(TAG, "(NO ACK Rqd)");
            service.queue.deleteItemByID(queueItem.getId());
        }
    } // sendMessage




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
                justsent = attemptReceiveMessage();
                justsent = (attemptSendMessage() || justsent);
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
    };



    ///////////////////////////////////////////////////////////////
    // backoffTask()
    //  Timer that blocks the retrying of messages during the given back-off period.
    ///////////////////////////////////////////////////////////////
    private Runnable backoffTask = new Runnable() {

        @Override
        public void run() {
            try {
                backoffUntilTimer = false;
                Log.v(TAG, "Receiver: backoff timer expired");
                attemptSendMessage();
            } catch(Exception e) {
                Log.e(TAG + ".backoffTask", "Exception: " + e.toString(), e);
            }
        }
    };


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


                backoffUntilConnectivityChanges = false;
                attemptSendMessage();
            } catch(Exception e) {
                Log.e(TAG + ".connectivityChangeReceiver", "Exception: " + e.toString(), e);
            }
        } // OnReceive()
    }; // connectivityChangeReceiver



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
                Log.v(TAG, "signalStrengthChangeReceiver()");
                if (signalStrength.isGsm()) {
                    listenerSignalStrength = convertGSMStrengthtoDBM(signalStrength.getGsmSignalStrength());
                    Log.d(TAG, "Signal Strength: GSM = " + listenerSignalStrength);
                } else {
                    listenerSignalStrength = signalStrength.getCdmaDbm();
                    Log.d(TAG, "Signal Strength: CDMA = " + listenerSignalStrength);
                }


                attemptSendMessage(); // maybe we went over the threshold
            } catch(Exception e) {
                Log.e(TAG + ".SignalStrengthChangeListener", "Exception: " + e.toString(), e);
            }
        }
    }


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



} // class Ota
