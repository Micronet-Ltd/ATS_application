/////////////////////////////////////////////////////////////
// CAN:
//  Handles the setup/teardown of threads the control the CAN bus, and communications to/from
/////////////////////////////////////////////////////////////

// API TODO:
// Listen-only mode (no acking)
// cancel write (do I have to close socket from another thread?)
// cancel read (like when shutting down the socket)


package com.micronet.dsc.vbs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;

import com.micronet.canbus.CanbusFrame;
import com.micronet.canbus.CanbusFrameType;
import com.micronet.canbus.CanbusHardwareFilter;
import com.micronet.canbus.CanbusSocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class VehicleBusCAN {

    private static final String TAG = "ATS-VBS-CAN"; // for logging


    static final int SAFETY_MAX_OUTGOING_QUEUE_SIZE = 10; // just make sure this queue doesn't ever keep growing forever

    CANWriteRunnable canWriteRunnable = null; // thread for writing
    CANReadRunnable canReadRunnable = null; // thread for reading

//    CanbusInterface canInterface = null;
//    CanbusSocket canSocket = null;

    Handler callbackHandler = null; // the handler that the runnable will be posted to
    Runnable receiveRunnable = null; // runnable to be posted to handler when a frame is received
    Runnable readyRxRunnable = null; // runnable to be posted to handler when the bus is ready for transmit/receive
    Runnable readyTxRunnable = null; // runnable to be posted to handler when the bus is ready for transmit/receive


    List<CanbusFrame> incomingList = Collections.synchronizedList(new ArrayList<CanbusFrame>());
    List<CanbusFrame> outgoingList = Collections.synchronizedList(new ArrayList<CanbusFrame>());


    VehicleBusWrapper busWrapper;

    Context context;

    public VehicleBusCAN(Context context) {
        busWrapper = VehicleBusWrapper.getInstance();
        busWrapper.isUnitTesting = false;
        this.context = context;
    }

    public VehicleBusCAN(Context context, boolean isUnitTesting) {
        busWrapper = VehicleBusWrapper.getInstance();
        busWrapper.isUnitTesting = isUnitTesting;
        this.context = context;
    }

    /*
    ///////////////////////////////////////////////////////////////////
    // getSocket()
    //  this socket may be needed for other functionality like accessing the J1708 bus
    ///////////////////////////////////////////////////////////////////
    public CanbusSocket getSocket() {
        return busWrapper.getSocket();
    } // getSocket()
*/

    ///////////////////////////////////////////////////////////////////
    // receiveFrame() : safe to call from a different Thread than the CAN threads
    // returns null if there are no frames to receive
    ///////////////////////////////////////////////////////////////////
    public CanbusFrame receiveFrame() {

        synchronized (incomingList) {
            if (incomingList.size() == 0) return null; // nothing in the list

            CanbusFrame frame = incomingList.get(0);
            incomingList.remove(0);
            return frame;
        }
    } // receiveFrame()


    ///////////////////////////////////////////////////////////////////
    // sendFrame() : safe to callfrom a different Thread than the CAN threads
    ///////////////////////////////////////////////////////////////////
    public void sendFrame(CanbusFrame frame) {

        Log.vv(TAG, "SendFrame()");
        synchronized (outgoingList) {
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

        synchronized (outgoingList) {
            outgoingList.clear();
        }
    } //clearQueues()


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


    public static String BUS_NAME = "CAN";

    //////////////////////////////////////////////////////
    // start() : starts the threads to listen and send CAN frames
    //  this can be called multiple times to re-initialize the CAN connection
    ///////////////////////////////////////////////////////
    public boolean start(int bitrate, boolean listen_only, CanbusHardwareFilter[] hardwareFilters) {
        // close any prior socket that still exists

        Log.v(TAG, "start()");


        stop(); // stop any threads already running


        if (busWrapper.isUnitTesting) {
            // since we are unit testing and not on realy device, even creating the CanbusInterface will fail fatally,
            //  so we need to skip this in testing
            Log.e(TAG, "UnitTesting is on. Will not create CAN Interface");
            return false;
        }


        /*
        try {
            if (canInterface != null) {
                canInterface.remove(); // make sure the interface is removed before setting bitrate
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception removing CAN interface "  + e.toString(), e);
            // DO nothing
        }
*/
        busWrapper.setCharacteristics(listen_only, bitrate, hardwareFilters);
        busWrapper.start(BUS_NAME, busReadyCallback, null);

        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(VehicleBusConstants.BROADCAST_CAN_TX);
            context.registerReceiver(txReceiver, intentFilter);
            Log.v(TAG, "TX Receiver Registered");
        } catch (Exception e) {
            Log.e(TAG, "Could not register CAN Tx receiver");
        }


        return true;
    } // start()









    private Runnable busReadyCallback = new Runnable() {
        @Override
        public void run() {
            try {
//                Log.v(TAG, "busReadyCallback()");
                // process any frames that are ready
                startReading();
                startWriting();
//                Log.v(TAG, "busReadyCallback() END");
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
        if (canReadRunnable != null)
            canReadRunnable.cancelThread = true;

        canReadRunnable = new CANReadRunnable(busWrapper.getSocket());

        // If we aren't unit testing, then start the thread
        if (!busWrapper.isUnitTesting) {
            Thread clientThread = new Thread(canReadRunnable);
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
        if (canWriteRunnable != null)
            canWriteRunnable.cancelThread = true;

        canWriteRunnable = new CANWriteRunnable(busWrapper.getSocket());

        // If we aren't unit testing, then start the thread
        if (!busWrapper.isUnitTesting) {
            Thread clientThread = new Thread(canWriteRunnable);
            clientThread.start();
        }

        return true;
    } // startWriting()

    public boolean isWriteReady() {
        try {
            if ((canWriteRunnable != null) &&
                (canWriteRunnable.isReady)) return true;
        } catch (Exception e) {
            // DO nothing
        }

        return false;
    }

    public boolean isReadReady() {
        try {
            if ((canReadRunnable != null) &&
                    (canReadRunnable.isReady)) return true;
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


        if (canReadRunnable != null)
            canReadRunnable.cancelThread = true;
        if (canWriteRunnable != null)
            canWriteRunnable.cancelThread = true;


        busWrapper.stop(BUS_NAME, busReadyCallback, null);



        /*
        if (canSocket!= null) {
            try {
                closeSocket(canSocket);
            } catch (Exception e) {
                Log.e(TAG, "Exception closing CAN socket"  + e.toString(), e);
            }
        }

        if (canInterface != null) {
            try {
                canInterface.remove();
            }  catch (Exception e) {
                Log.e(TAG, "Exception closing CAN interface "  + e.toString(), e);
            }
        }
        */
    } // stop()







    ///////////////////////////////////////////////////////////////////
    // abortTransmits()
    //  stop attempting to send any Tx packets in progress (maybe our address was changed, etc..)
    ///////////////////////////////////////////////////////////////////
    public void abortTransmits() {

        // TODO: kill any frames in the CAN queue (must happen within 50 ms)
        // Is this implemented in CAN API yet?

        // kill any frames in our queue
        synchronized (outgoingList) {
            outgoingList.clear();
        }
    } // abortTransmits



    // We need separate threads for sending and receiving data since both are blocking operations


    ////////////////////////////////////////////////////////
    // CANWriteRunnable : this is the code that runs on another thread and
    //  handles CAN writing to bus
    ////////////////////////////////////////////////////////
    public class CANWriteRunnable implements Runnable {


        volatile boolean cancelThread = false;
        volatile boolean isClosed = false;
        volatile boolean isReady = false;
        //CanbusInterface canInterface;
        CanbusSocket canWriteSocket;

        CANWriteRunnable(CanbusSocket socket) {
//                CanbusInterface new_canInterface) {
            canWriteSocket = socket;
        }

        public void run() {

            CanbusFrame outFrame = null;

            while (!cancelThread) {

                // remove anything in our outgoing queues and connections
                abortTransmits();

                if (!cancelThread) {
                    // Notify the main thread that we are ready for write
                    if ((callbackHandler != null) && (readyTxRunnable != null)) {
                        callbackHandler.post(readyTxRunnable);
                    }
                    Log.v(TAG, "CAN-Write thread ready");
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
                            canWriteSocket.write(outFrame);
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
            Log.v(TAG, "CAN Write Thread terminated");
            isClosed = true;

        } // run
    } // CAN Write communications (runnable)





    ////////////////////////////////////////////////////////
    // CANRunnable : this is the code that runs on another thread and
    //  handles CAN sending and receiving
    ////////////////////////////////////////////////////////
    public class CANReadRunnable implements Runnable {


        volatile boolean cancelThread = false;
        volatile boolean isClosed = false;
        volatile boolean isReady = false;

        //CanbusInterface canInterface;
        CanbusSocket canReadSocket;

        CANReadRunnable(CanbusSocket new_canSocket) {
//            CanbusInterface new_canInterface) {
            //canInterface = new_canInterface;
            canReadSocket = new_canSocket;
        }

        public void run() {


            while (!cancelThread) {

                // also remove anything that was incoming on last bus (so we know what bus it arrived on)
                synchronized (incomingList) {
                    incomingList.clear();
                }


                CanbusFrame inFrame = null;


                if (!cancelThread) {
                    // Notify the main thread that we are ready for read
                    if ((callbackHandler != null) && (readyRxRunnable != null)) {
                        callbackHandler.post(readyRxRunnable);
                    }
                    Log.v(TAG, "CAN-Read thread ready");
                    isReady = true;
                }

                while (!cancelThread)  {
                    // try and receive a packet
                    inFrame = null;
                    try {

                        //Log.v(TAG, "Reading... ");
                        inFrame = canReadSocket.read();
                        //Log.v(TAG, "Done Reading... ");

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
            Log.v(TAG, "CAN Read Thread terminated");
            isClosed = true;

        } // run
    } // CAN Read communications (runnable)



    void broadcastRx(CanbusFrame frame) {



        //synchronized (incomingList) {
        //  incomingList.add(inFrame);
        //} // sync

        // Notify the main thread that something is available in the incomingList
        //if ((callbackHandler != null) && (receiveRunnable != null)) {
        //    callbackHandler.post(receiveRunnable);
        //}


        long elapsedRealtime = SystemClock.elapsedRealtime(); // ms since boot

        Intent ibroadcast = new Intent();
        ibroadcast.setPackage(VehicleBusConstants.PACKAGE_NAME_ATS);
        ibroadcast.setAction(VehicleBusConstants.BROADCAST_CAN_RX);


        ibroadcast.putExtra("elapsedRealtime", elapsedRealtime); // ms since boot
        //ibroadcast.putExtra("password", VehicleBusService.BROADCAST_PASSWORD);
        ibroadcast.putExtra("id", frame.getId());
        ibroadcast.putExtra("data", frame.getData());

        context.sendBroadcast(ibroadcast);
    } // broadcastRx


    TxReceiver txReceiver = new TxReceiver();
    class TxReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            try {

                Log.v(TAG, "Received TX");
/*
                String password = intent.getStringExtra("password");
                if ((password == null) || (!password.equals(VehicleBusService.BROADCAST_PASSWORD))) {
                    Log.e(TAG, "Received invalid CAN TX request");
                    return;
                }
*/
                int id = intent.getIntExtra("id", -1);
                byte[] data = intent.getByteArrayExtra("data");

                if ((id != -1) && (data != null) && (data.length > 0)) {
                    CanbusFrame frame = new CanbusFrame(id, data, CanbusFrameType.EXTENDED);
                    sendFrame(frame);
                }
            } catch (Exception e) {
                Log.e(TAG, ".txReceiver Exception : " + e.toString(), e);
            }

        }
    } // TxReceiver
} // CAN class
