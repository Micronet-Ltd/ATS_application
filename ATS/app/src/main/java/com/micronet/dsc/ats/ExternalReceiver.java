/////////////////////////////////////////////////////////////
// ExternalReceiver:
//  Receives messages from external (outside this package) applications (not just system alarms)
/////////////////////////////////////////////////////////////
package com.micronet.dsc.ats;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;




//This is registered by setting the alarm in Power class
public class ExternalReceiver extends WakefulBroadcastReceiver {
    // Here is where we are receiving our alarm.
    //  Information about this should also be put in the manifest.

    // Note if we were powered down then we don't get this, we will get a boot notice instead (see BootReceiver)

    public static final String TAG = "ATS-ExternalReceiver";

    public static final String EXTERNAL_BROADCAST_PING = "com.micronet.dsc.ats.ping";
    public static final String EXTERNAL_BROADCAST_PING_REPLY = "com.micronet.dsc.ats.replyPing";

    public static final String EXTERNAL_SEND_PAYLOAD = "com.micronet.dsc.ats.server.send";
    public static final String EXTERNAL_SEND_PAYLOAD_REPLY = "com.micronet.dsc.ats.server.replySend";

    public static final String EXTERNAL_SEND_PAYLOAD_DATA_PAYLOAD = "dataPayload";
    public static final String EXTERNAL_SEND_PAYLOAD_DATA_REQUEST_ID = "dataRequestId";
    public static final String EXTERNAL_SEND_PAYLOAD_DATA_SOURCE_ID = "dataSourceId";
    public static final String EXTERNAL_SEND_PAYLOAD_DATA_RESULT = "dataResult";


    // since we want to limit a total UDP payload to 512 bytes, limit this sub-payload to 400 bytes for now
    // we will still add other things to the payload like GPS data, event type, ios etc..
    public static final int MAX_PAYLOAD_DATA_SIZE = 400;


    @Override
    public void onReceive(Context context, Intent intent)
    {

        //Log.d(TAG, "Heartbeat Alarm rcv " + intent.getAction());

        if (intent.getAction().equals(EXTERNAL_BROADCAST_PING)) {

            Log.d(TAG, "External ping received");

            // reply that we got this command
            Intent ibroadcast = new Intent();
            ibroadcast.setAction(EXTERNAL_BROADCAST_PING_REPLY);
            context.sendBroadcast(ibroadcast);

            // Send intent to service (so service is started if needed)
            Intent iservice = new Intent(context, MainService.class);
            iservice.putExtra(EXTERNAL_BROADCAST_PING, 1);
            startWakefulService(context, iservice); // wakeful to make sure we don't power down before
        }


        if (intent.getAction().equals(EXTERNAL_SEND_PAYLOAD)) {
            // This is a request to send a custom payload message to the server

            byte[] payload = null;
            String source, in_source;
            String request_id, in_request_id;
            try {
                payload = intent.getByteArrayExtra(EXTERNAL_SEND_PAYLOAD_DATA_PAYLOAD);
                in_request_id = intent.getStringExtra(EXTERNAL_SEND_PAYLOAD_DATA_REQUEST_ID);
                in_source = intent.getStringExtra(EXTERNAL_SEND_PAYLOAD_DATA_SOURCE_ID);
            } catch (Exception e) {
                Log.e(TAG, "Error in Payload Forwarding Request (request ignored): " + e.getMessage());
                return;
            }

            request_id = "";
            source = "";
            if (in_request_id != null) request_id = in_request_id;
            if (in_source != null) source = in_source;

            Log.d(TAG, "Payload forwarding requested (ReqId = " + request_id + " from " + source + ")");
            if (payload != null) {
                Log.d(TAG, "   payload = " + Log.bytesToHex(payload, payload.length));
            }


            if ((payload != null) && (payload.length > 0) &&
                    (payload.length <= MAX_PAYLOAD_DATA_SIZE) // maximum payload size
                ) {
                // reply that we got this command and will process it
                Intent ibroadcast = new Intent();
                ibroadcast.setAction(EXTERNAL_SEND_PAYLOAD_REPLY);
                ibroadcast.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_PAYLOAD, payload);
                ibroadcast.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_REQUEST_ID, request_id);
                ibroadcast.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_SOURCE_ID, source);
                ibroadcast.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_RESULT, 0); // success
                context.sendBroadcast(ibroadcast);

                // Send intent to service (so service is started if needed)
                Intent iservice = new Intent(context, MainService.class);
                iservice.putExtra(EXTERNAL_SEND_PAYLOAD, 1);
                iservice.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_PAYLOAD, payload);
                startWakefulService(context, iservice); // wakeful to make sure we don't power down before

            } else { // Error in format
                Log.e(TAG, "Payload request rejected: Invalid Payload" );
                if (!request_id.isEmpty()) { // send a response since we have an ID
                    Intent ibroadcast = new Intent();
                    ibroadcast.setAction(EXTERNAL_SEND_PAYLOAD_REPLY);
                    ibroadcast.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_PAYLOAD, payload);
                    ibroadcast.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_REQUEST_ID, request_id);
                    ibroadcast.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_SOURCE_ID, source);
                    ibroadcast.putExtra(EXTERNAL_SEND_PAYLOAD_DATA_RESULT, -1); // Error of some kind
                    context.sendBroadcast(ibroadcast);
                }
            }
        } // EXTERNAL_SEND_PAYLOAD


    } // OnReceive()


} // class

