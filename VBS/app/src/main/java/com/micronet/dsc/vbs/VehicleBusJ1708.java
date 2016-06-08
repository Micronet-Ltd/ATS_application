/////////////////////////////////////////////////////////////
// J1708:
//  Handles the setup/teardown of threads the control the J1708 bus, and communications to/from
/////////////////////////////////////////////////////////////


package com.micronet.dsc.vbs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;

import com.micronet.canbus.CanbusFrame;
import com.micronet.canbus.CanbusFrameType;
import com.micronet.canbus.J1708Frame;
import com.micronet.canbus.CanbusHardwareFilter;
import com.micronet.canbus.CanbusInterface;
import com.micronet.canbus.CanbusSocket;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class VehicleBusJ1708 {

    private static final String TAG = "ATS-VBS-J1708"; // for logging


    static final int SAFETY_MAX_OUTGOING_QUEUE_SIZE = 10; // just make sure this queue doesn't ever keep growing forever

    J1708WriteRunnable j1708WriteRunnable = null; // thread for writing
    J1708ReadRunnable j1708ReadRunnable = null; // thread for reading

//    CanbusInterface j1708Interface = null;
//    CanbusSocket j1708Socket = null;

    Handler callbackHandler = null; // the handler that the runnable will be posted to
    Runnable receiveRunnable = null; // runnable to be posted to handler when a frame is received
    Runnable readyRxRunnable = null; // runnable to be posted to handler when the bus is ready for receive
    Runnable readyTxRunnable = null; // runnable to be posted to handler when the bus is ready for transmit


    List<J1708Frame> incomingList = Collections.synchronizedList(new ArrayList<J1708Frame>());
    List<J1708Frame> outgoingList = Collections.synchronizedList(new ArrayList<J1708Frame>());

    VehicleBusWrapper busWrapper;

    Context context;

    public VehicleBusJ1708(Context context) {
        busWrapper = VehicleBusWrapper.getInstance();
        busWrapper.isUnitTesting = false;
        this.context = context;
    }

    public VehicleBusJ1708(Context context, boolean isUnitTesting) {
        busWrapper = VehicleBusWrapper.getInstance();
        busWrapper.isUnitTesting = isUnitTesting;
        this.context = context;
    }

    public static boolean isSupported() {
        return VehicleBusWrapper.isJ1708Supported();
    }

    ///////////////////////////////////////////////////////////////////
    // receiveFrame() : safe to call from a different Thread than the CAN threads
    // returns null if there are no frames to receive
    ///////////////////////////////////////////////////////////////////
    public J1708Frame receiveFrame() {

        synchronized (incomingList) {
            if (incomingList.size() == 0) return null; // nothing in the list

            J1708Frame frame = incomingList.get(0);
            incomingList.remove(0);
            return frame ;
        }
    } // receiveFrame()


    ///////////////////////////////////////////////////////////////////
    // sendFrame() : safe to callfrom a different Thread than the CAN threads
    ///////////////////////////////////////////////////////////////////
    public void sendFrame(J1708Frame frame) {

        Log.vv(TAG, "SendFrame()");
        synchronized (outgoingList ) {
            if (outgoingList.size() < SAFETY_MAX_OUTGOING_QUEUE_SIZE) {
                outgoingList.add(frame);
            }
        }
        Log.vv(TAG, "SendFrame() END");
    }


    ///////////////////////////////////////////////////////////////////
    // clearQueues()
    //  This is used only in testing to clear the incoming and outgoing frames when starting a test
    ///////////////////////////////////////////////////////////////////
    public void clearQueues() {
        synchronized (incomingList) {
            incomingList.clear();
        }

        synchronized(outgoingList ) {
            outgoingList.clear();
        }
    } //clearQueues()




    ///////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////
    // setReceiveCallbacks
    //  sets a callback that will be posted whenever a frame is received and read for processing
    //  call this before start()
    ///////////////////////////////////////////////////////////////////
    public void setReceiveCallbacks(Handler handler, Runnable receiveCallback, Runnable readyRxCallback, Runnable readyTxCallback) {
        this.callbackHandler = handler;
        this.receiveRunnable = receiveCallback;
        this.readyRxRunnable = readyRxCallback;
        this.readyTxRunnable = readyTxCallback;
    } // setReceiveCallback




    public static String BUS_NAME = "J1708";

    //////////////////////////////////////////////////////
    // start() : starts the threads to listen and send CAN frames
    //  this can be called multiple times to re-initialize the CAN connection
    // Parameters
    //  new_socket: You must pass a valid socket to use while starting.
    //          this socket can be retrieved from the CAN interface
    ///////////////////////////////////////////////////////
    public boolean start() {
        // close any prior socket that still exists

        Log.v(TAG, "start()");


        stop(); // stop any threads already running


        if (busWrapper.isUnitTesting) {
            // since we are unit testing and not on realy device, even creating the CanbusInterface will fail fatally,
            //  so we need to skip this in testing
            Log.e(TAG, "UnitTesting is on. Will not create J1708 Interface");
            return false;
        }
/*
        if (j1708Socket == null) {
            Log.e(TAG, "J1708 start() called with a null socket!");
            return false;
        }
*/

        // bus characteristics were already set when we started J1939
        busWrapper.start(BUS_NAME, busReadyCallback, null);

        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(VehicleBusConstants.BROADCAST_J1708_TX);
            context.registerReceiver(txReceiver, intentFilter);
        } catch (Exception e) {
            Log.e(TAG, "Could not register J1708 Tx receiver");
        }



        return true;
    } // start()




    private Runnable busReadyCallback = new Runnable() {
        @Override
        public void run() {
            try {
                Log.vv(TAG, "busReadyCallback()");
                // process any frames that are ready
                startReading();
                startWriting();
                Log.vv(TAG, "busReadyCallback() END");
            } catch (Exception e) {
                Log.e(TAG + ".busReadyCallback", "Exception: " + e.toString(), e);
            }
        } // run()
    }; // busReadyCallback()



    ///////////////////////////////////////////////////////////
    // startReading()
    //  starts a new read thread after a read thread is previously opened
    ///////////////////////////////////////////////////////////
    public boolean startReading() {

        if (busWrapper.getSocket() == null) return false;

        // Safety: make sure we cancel any previous thread if we are starting a new one
        if (j1708ReadRunnable != null)
            j1708ReadRunnable.cancelThread = true;

        j1708ReadRunnable = new J1708ReadRunnable(busWrapper.getSocket());

        // If we aren't unit testing, then start the thread
        if (!busWrapper.isUnitTesting) {
            Thread clientThread = new Thread(j1708ReadRunnable);
            clientThread.start();
        }

        return true;
    } // startReading()



    ///////////////////////////////////////////////////////////
    // startWriting()
    //  starts a new write thread after a read thread is previously opened
    ///////////////////////////////////////////////////////////
    public boolean startWriting() {

        if (busWrapper.getSocket() == null) return false;

        // Safety: make sure we cancel any previous thread if we are starting a new one
        if (j1708WriteRunnable != null)
            j1708WriteRunnable.cancelThread = true;

        j1708WriteRunnable = new J1708WriteRunnable(busWrapper.getSocket());

        // If we aren't unit testing, then start the thread
        if (!busWrapper.isUnitTesting) {
            Thread clientThread = new Thread(j1708WriteRunnable);
            clientThread.start();
        }

        return true;
    } // startWriting()


    public boolean isWriteReady() {
        try {
            if ((j1708WriteRunnable != null) &&
                    (j1708WriteRunnable.isReady)) return true;
        } catch (Exception e) {
            // DO nothing
        }

        return false;
    }

    public boolean isReadReady() {
        try {
            if ((j1708ReadRunnable != null) &&
                    (j1708ReadRunnable.isReady)) return true;
        } catch (Exception e) {
            // DO nothing
        }

        return false;
    }


    ///////////////////////////////////////////////////////
    // stop()
    //  called on shutdown
    ///////////////////////////////////////////////////////
    public void stop() {

        try {
            context.unregisterReceiver(txReceiver);
        } catch(Exception e) {
            // don't do anything
        }


        if (j1708ReadRunnable != null)
            j1708ReadRunnable.cancelThread = true;
        if (j1708WriteRunnable != null)
            j1708WriteRunnable.cancelThread = true;

        busWrapper.stop(BUS_NAME, busReadyCallback, null);
    }


    ///////////////////////////////////////////////////////////////////
    // abortTransmits()
    //  stop attempting to send any Tx packets in progress (maybe our address was changed, etc..)
    ///////////////////////////////////////////////////////////////////
    public void abortTransmits() {

        // kill any frames in our queue
        synchronized (outgoingList) {
            outgoingList.clear();
        }
    } // abortTransmits



    // We need separate threads for sending and receiving data since both are blocking operations






    ////////////////////////////////////////////////////////
    // J1708WriteRunnable : this is the code that runs on another thread and
    //  handles J1708 writing to bus
    ////////////////////////////////////////////////////////
    public class J1708WriteRunnable implements Runnable {


        volatile boolean cancelThread = false;
        volatile boolean isClosed = false;
        volatile boolean isReady = false;

        CanbusSocket j1708WriteSocket;

        J1708WriteRunnable(CanbusSocket socket) {

            j1708WriteSocket = socket;
        }

        public void run() {

            J1708Frame outFrame = null;

            while (!cancelThread) {

                // remove anything in our outgoing queues and connections
                abortTransmits();

                if (!cancelThread) {
                    // Notify the main thread that we are ready for write
                    if ((callbackHandler != null) && (readyTxRunnable != null)) {
                        callbackHandler.post(readyTxRunnable);
                    }
                    Log.v(TAG, "J1708-Write thread ready");
                    isReady = true;
                }


                while (!cancelThread) {

                    // try and send a packet
                    outFrame = null;
                    // get what we need to send
                    synchronized (outgoingList) {
                        if (outgoingList.size() > 0) {
                            outFrame = outgoingList.get(0);
                            outgoingList.remove(0);
                        }
                    }
                    if (outFrame == null) {
                        android.os.SystemClock.sleep(5); // we can wait 5 ms if nothing to send.
                    } else {
                        Log.v(TAG, "frame --> " + String.format("%02x", outFrame.getId()) + " : " + Log.bytesToHex(outFrame.getData(), outFrame.getData().length));
                        try {
                            j1708WriteSocket.writeJ1708(outFrame);
                            //Log.d(TAG, "Write Returns");
                        } catch (Exception e) {
                            // exceptions are expected if the interface is closed
                            Log.v(TAG, "Exception on write socket. Canceling Thread");
                            cancelThread = true;
                        }
                    }
                } // thread not canceled

            } // thread not cancelled

            isReady = false;
            Log.v(TAG, "J1708 Write Thread terminated");
            isClosed = true;

        } // run
    } // CAN Write communications (runnable)





    ////////////////////////////////////////////////////////
    // J1708Runnable : this is the code that runs on another thread and
    //  handles CAN sending and receiving
    ////////////////////////////////////////////////////////
    public class J1708ReadRunnable implements Runnable {


        volatile boolean cancelThread = false;
        volatile boolean isClosed = false;
        volatile boolean isReady = false;

        CanbusSocket j1708ReadSocket;

        J1708ReadRunnable(CanbusSocket socket) {

            j1708ReadSocket = socket;
        }

        public void run() {


            while (!cancelThread) {

                // also remove anything that was incoming on last bus (so we know what bus it arrived on)
                synchronized (incomingList) {
                    incomingList.clear();
                }


                J1708Frame inFrame = null;


                if (!cancelThread) {
                    // Notify the main thread that we are ready for read
                    if ((callbackHandler != null) && (readyRxRunnable != null)) {
                        callbackHandler.post(readyRxRunnable);
                    }
                    Log.v(TAG, "J1708-Read thread ready");
                    isReady = true;
                }

                while (!cancelThread)  {
                    // try and receive a packet
                    inFrame = null;
                    try {

                        Log.vv(TAG, "Reading... ");
                        inFrame = j1708ReadSocket.readJ1708();
                        Log.vv(TAG, "Done Reading... ");

                    } catch (Exception e) {
                        // exceptions are expected if the interface is closed
                        Log.v(TAG, "Exception on read socket. Canceling Thread: " + e.getMessage());
                        cancelThread = true;
                    }


                    if (inFrame != null) {

                        Log.v(TAG, "frame  <-- " + String.format("%02x", inFrame.getId()) +
                                " : " +
                                Log.bytesToHex(inFrame.getData(), inFrame.getData().length));

                        broadcastRx(inFrame);

                    }

                } // thread not canceled


            } // thread not cancelled

            isReady = false;
            Log.v(TAG, "J1708 Read Thread terminated");
            isClosed = true;

        } // run
    } // CAN Read communications (runnable)




    void broadcastRx(J1708Frame frame) {


        //synchronized (incomingList) {
        //    incomingList.add(frame);
        //} // sync

        // Notify the main thread that something is available in the incomingList
        //if ((callbackHandler != null) && (receiveRunnable != null)) {
        //    callbackHandler.post(receiveRunnable);
        //}


        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot

        Intent ibroadcast = new Intent();
        ibroadcast.setPackage(VehicleBusConstants.PACKAGE_NAME_ATS);
        ibroadcast.setAction(VehicleBusConstants.BROADCAST_J1708_RX);

        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot
        //ibroadcast.putExtra("password", VehicleBusService.BROADCAST_PASSWORD);
        ibroadcast.putExtra("priority", frame.getPriority());
        ibroadcast.putExtra("id", frame.getId());
        ibroadcast.putExtra("data", frame.getData());

        context.sendBroadcast(ibroadcast);
    } // broadcastRx


    TxReceiver txReceiver = new TxReceiver();
    class TxReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            try {
                /*
                String password = intent.getStringExtra("password");
                if ((password == null) || (!password.equals(VehicleBusService.BROADCAST_PASSWORD))) {
                    Log.e(TAG, "Received invalid J1708 TX request");
                    return;
                }
                */

                int priority = intent.getIntExtra("priority", -1);
                int id = intent.getIntExtra("id", -1);
                byte[] data = intent.getByteArrayExtra("data");

                if ((priority != -1) && (id != -1) && (data != null) && (data.length > 0)) {
                    J1708Frame frame = new J1708Frame(priority, id, data);
                    sendFrame(frame);
                }
            } catch (Exception e) {
                Log.e(TAG, ".txReceiver Exception : " + e.toString(), e);
            }

        }
    } // TxReceiver
} // J1708 class
