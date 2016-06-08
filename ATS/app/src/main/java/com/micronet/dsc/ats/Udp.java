/////////////////////////////////////////////////////////////
// Udp:
//  Sending and Receiving UDP Messages
/////////////////////////////////////////////////////////////


package com.micronet.dsc.ats;


import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;


import 	java.net.SocketTimeoutException;

public class Udp {


    public static final String TAG = "ATS-Udp";

    List<Codec.IncomingMessage> incomingList = Collections.synchronizedList(new ArrayList<Codec.IncomingMessage>());
    List<Codec.OutgoingMessage> outgoingList = Collections.synchronizedList(new ArrayList<Codec.OutgoingMessage>());


    boolean isUnitTesting = false;
    String socketName = "";

    public Udp(boolean isUnitTesting, String socketName) {
        this.isUnitTesting =isUnitTesting;
        this.socketName = socketName;
    }


    ///////////////////////////////////////////////////////////////////
    // receiveMessage() : safe to call from a different Thread than the UDP threads
    // returns null if there are no messages to receive
    public Codec.IncomingMessage receiveMessage() {
        synchronized(incomingList ) {
            if (incomingList.size() == 0) return null; // nothing in the list

            Codec.IncomingMessage message = incomingList.get(0);
            incomingList.remove(0);
            return message;
        }

    }

    ///////////////////////////////////////////////////////////////////
    // sendMessage() : safe to callfrom a different Thread than the UDP threads
    public void sendMessage(Codec.OutgoingMessage message) {

        Log.vv(TAG, "SendMessage()");
        synchronized(outgoingList ) {
            outgoingList.add(message);
        }
        Log.vv(TAG, "SendMessage() END");
    }



    UdpRunnable udpRunnable = null;

    ///////////////////////////////////////////////////////
    // start() : starts the threads to listen and send UDP messages
    ///////////////////////////////////////////////////////
    public boolean start(int localPort, String remoteAddress, int remotePort) {
        // close any prior socket that still exists

        Log.v(TAG, "start() " + socketName);

        if (udpRunnable != null) {
            udpRunnable.cancelThread = true;
            if (!udpRunnable.isClosed) {
                Log.v(TAG, "Runnable not closed, returning for now");
                return false;
            }
        }


        udpRunnable = new UdpRunnable(localPort, remoteAddress, remotePort);

        if (!isUnitTesting) {
			// Don't start other threads if we are unit testing
            Thread clientThread = new Thread(udpRunnable);
            clientThread.start();
        }

        return true;
    } // startUDP()

    ///////////////////////////////////////////////////////
    // stop()
    //  called on shutdown
    ///////////////////////////////////////////////////////
    public void stop() {
        if (udpRunnable == null) return;
        udpRunnable.cancelThread = true;
    }


    // hasStopped(), return true if the thread was running and stopped or never ran.
    public boolean hasStopped() {
        if (udpRunnable == null) return true;
        if (udpRunnable.isClosed) return true;
        return false;
    }

    // hasOutgoingMessage(), return true if there are outgoing messages that will sent very soon (next 50 ms or so)
    public boolean hasOutgoingMessage() {
        synchronized (outgoingList) {
            if (outgoingList.size() == 0) return false;
        }
        return true;
    }


    ////////////////////////////////////////////////////////
    // UdpRunnable : this is the code that runs on another thread and
    //  handles UDP sending and receiving
    ////////////////////////////////////////////////////////
    public class UdpRunnable implements Runnable {

        int localPort;
        String remoteAddress;
        int remotePort;
        volatile boolean cancelThread = false;
        volatile boolean isClosed = false;

        public UdpRunnable(int localPort, String remoteAddress, int remotePort) {

            Log.i(TAG, "UDP Socket " + socketName + "= 0.0.0.0:" + localPort + " -> " + remoteAddress + ":" + remotePort );
            this.localPort = localPort;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            cancelThread = false;
        }

        public void run() {

            DatagramSocket socket;

            // open a new socket.
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setSoTimeout(50); // don't block more than 50 ms
                InetSocketAddress localAddr = new InetSocketAddress("0.0.0.0", localPort);
                socket.bind(localAddr);
            } catch (Exception e) {
                Log.e(TAG, "Exception on creating DatagramSocket on " + localPort + ": " + e.getMessage());
                return;
            }

            Log.d(TAG, "UDP " + socketName + " thread started. listening on " + localPort);

            while (!cancelThread) {
                // try and receive a packet
                boolean isReceived = false;
                Codec.IncomingMessage inMessage = new Codec.IncomingMessage();
                DatagramPacket inPacket = new DatagramPacket(inMessage.data, Codec.MAX_INCOMING_MESSAGE_LENGTH);
                try {
                    socket.receive(inPacket);
                    inMessage.length = inPacket.getLength();
                    isReceived = true;
                } catch (SocketTimeoutException e) {
                    // This is OK and expected, don't do anything
                } catch (Exception e) {
                    Log.e(TAG, "Exception on socket while reading. Canceling Thread: " + e.getMessage());
                    cancelThread = true;
                }


                if (isReceived) {
                    synchronized (incomingList) {
                        Log.d(TAG, "rcv (" + socketName + ") " + inMessage.length + " bytes");
                        Log.i(TAG, "packet (" + socketName + ") <-- " + Log.bytesToHex(inMessage.data, inMessage.length));
                        incomingList.add(inMessage);
                    } // sync
                }

                // try and send a packet
                if (!cancelThread) {
                    synchronized (outgoingList) {
                        while (outgoingList.size() > 0) {
                            // send something now, if we can.
                            Codec.OutgoingMessage outMessage = outgoingList.get(0);

                            try {
                                InetSocketAddress destAddress = new InetSocketAddress(remoteAddress, remotePort);

                                Log.d(TAG, "send (" + socketName + ") " + outMessage.length + " bytes: " + remoteAddress + ":" + remotePort);
                                Log.i(TAG, "packet (" + socketName + ") --> " + Log.bytesToHex(outMessage.data, outMessage.length));
                                DatagramPacket outPacket = new DatagramPacket(outMessage.data, outMessage.length, destAddress);
                                socket.send(outPacket);
                            } catch (Exception e) {
                                Log.e(TAG, "Unable to send datagram. Canceling Thread");
                                cancelThread = true;
                            }

                            outgoingList.remove(0);
                        }
                    } // sync
                }

            } // thread not canceled
            // close the socket
            try {
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "Exception closing socket (" + socketName + ")");
            }

            Log.v(TAG, "UDP Thread ("+ socketName + ") terminated");

            isClosed = true;

        } // run
    } // UdpServer




} // Udp
