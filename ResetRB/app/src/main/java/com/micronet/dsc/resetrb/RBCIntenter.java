/////////////////////////////////////////////////////////////
// RBCIntenter:
// sends intents to the Redbend Client app, allows:
//      acquiring and releasing of installation locks
//      pinging the client to determine the version of the client
//      requesting that the client immediately contact the server to check for updates

/////////////////////////////////////////////////////////////

package com.micronet.dsc.resetrb;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.redbend.client.micronet.IMicronetRBClientAidlInterface;

/**
 * Created by dschmidt on 8/19/16.
 */
public class RBCIntenter {

    public static final String LOG_TAG = "ResetRB-RBCIntenter";


    // Start Actions:
    public static final String START_ACTION_CLEAR_INSTALL_LOCKS = "com.redbend.client.micronet.CLEAR_INSTALL_LOCKS";
    public static final String START_ACTION_ACQUIRE_INSTALL_LOCK = "com.redbend.client.micronet.ACQUIRE_INSTALL_LOCK";
    public static final String START_ACTION_RELEASE_INSTALL_LOCK = "com.redbend.client.micronet.RELEASE_INSTLAL_LOCK";
    public static final String START_ACTION_PING_CLIENT = "com.redbend.client.micronet.PING";


    public static final String BROADCAST_REDBEND_CHECKSERVER = "SwmClient.CHECK_FOR_UPDATES_NOW";

    // Extras for Actions
    public static final String START_EXTRA_LOCK_NAME = "LOCK_NAME";
    public static final String START_EXTRA_SECONDS = "SECONDS";



    static void acquireInstallLock(Context context, String lock_name, int expires_seconds) {

        Intent intent = new Intent(START_ACTION_ACQUIRE_INSTALL_LOCK);
        intent.putExtra(START_EXTRA_LOCK_NAME, lock_name);
        intent.putExtra(START_EXTRA_SECONDS, expires_seconds);

        context.startService(intent);
    }

    static void releaseInstallLock(Context context, String lock_name) {

        Intent intent = new Intent(START_ACTION_RELEASE_INSTALL_LOCK);
        intent.putExtra(START_EXTRA_LOCK_NAME, lock_name);

        context.startService(intent);

    }

    static void checkServerNow(Context context) {
        Intent broadcastIntent = new Intent();
        //serviceIntent.setPackage(PACKAGE_NAME_RESETRB);
        broadcastIntent.setAction(BROADCAST_REDBEND_CHECKSERVER);

        context.sendBroadcast(broadcastIntent);
    }

    static void pingClient(Context context) {
        Intent intent = new Intent(START_ACTION_PING_CLIENT);
        context.startService(intent);

    }



}
