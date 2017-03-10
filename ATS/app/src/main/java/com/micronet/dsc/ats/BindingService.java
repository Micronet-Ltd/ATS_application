package com.micronet.dsc.ats;

/**
 * Created by dschmidt on 1/25/17.
 */


import android.app.Service;
import android.content.Context;
import android.content.Intent;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;


import com.micronet.dsc.ats.IMicronetAtsAidlInterface;




public class BindingService extends Service {

    public static final String TAG = "ATS-BindingService";


    static final int ATS_ACTION_SUCCESS = 0;
    static final int ATS_ACTION_ERROR = -1; // Unknown error
    static final int ATS_ACTION_ERROR_PERMISSION = -10; // Your app does not have permission
    static final int ATS_ACTION_ERROR_SETTING = -11; // Setting does not exist

    Context context;
    Config config;
    CodeMap codemap;

    public BindingService() {

    }

    @Override
    public void onCreate() {

        // do some setup
        context = getApplicationContext();
        config = new Config(context);
        codemap = new CodeMap(context);

        // open access to the configuration files, since we will probably be writing values to these
        // Note: we would have previously copied the alternate configuration files (if they exist) when the application started
        //      so we could be making changes to those values now.

        config.open();
        codemap.open();

    }


    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        return mBinder;

        //throw new UnsupportedOperationException("Not yet implemented");
    }


    //////////////////////////////
    // hasPermission()
    //  returns true if we have permission to access the binding, otherwise false
    //      For now, this always returns true. Even if security were enforced here,
    //      there are unprotected ways around it such as a complete file overwrite or a UDP command.
    boolean hasPermission() {

        int uid = Binder.getCallingUid();
        String callingApp = context.getPackageManager().getNameForUid(uid);

        // TODO: Check the certficate/signature of the calling app against a list of trusted signatures.
        // Log.d(TAG, "Checking permission for " + callingApp + " (UID " + uid + ")");

        return true; // allow access

    }

    //////////////////////////////////////////
    // generateEvent()
    //  generates an event that will be sent to the server indicating something has been changed.
    //  eventcode: the event to generate
    //  extra: either a setting ID, an eventcode map ID, or a config file ID; meaning is based on the eventcode parameter
    boolean generateEvent(int eventcode, int extra) {


        // Is the main service running? If so, then we can add this event to its queue

        boolean record_files_changed = false;

        try {
            MainService service = MainService.getInitializedService();
            if (service != null) {
                // try to add specific message to the queue
                service.addEventWithExtra(eventcode, extra);
            } else {
                record_files_changed = true;
            }
        } catch (Exception e) {
            record_files_changed = true;
        }


        if (record_files_changed) {
            // we weren't able to record a specific event (service might not be running), so just make sure that
            // the next time the service starts, it realizes that a configuration file has changed

            int files_changed = 0;

            State state = new State(getApplicationContext());

            // figure out which file has changed from the eventcode
            switch(eventcode) {
                case EventType.EVENT_TYPE_CONFIGW:
                    files_changed = EventType.CONFIG_FILE_SETTINGS;
                    break;
                case EventType.EVENT_TYPE_MOREMAPW:
                    files_changed = EventType.CONFIG_FILE_MOMAP;
                    break;
                case EventType.EVENT_TYPE_MTREMAPW:
                    files_changed = EventType.CONFIG_FILE_MTMAP;
                    break;
                case EventType.EVENT_TYPE_CONFIGURATION_REPLACED:
                    files_changed = extra; // the parameter we passed to this function is already a bitfield of files
                    break;
            }

            // add these changed files to what -- if anything -- we have previously recorded
            state.setFlags(State.PRECHANGED_CONFIG_FILES_BF, files_changed);
        }

        return true;
    }


    /**
     * IAdd definition is below
     */
    private final IMicronetAtsAidlInterface.Stub mBinder = new IMicronetAtsAidlInterface.Stub() {


        @Override
        public boolean configIsSettingSupported(int setting_id) {

            // No permission check needed
            //if (setting_id <= 0) return false;
            return config.settingExists(setting_id);
        }

        ////////////////////////////////////////////////
        //  configClearSetting()
        //  clears a config setting
        ////////////////////////////////////////////////
        @Override
        public int configClearSetting(int setting_id) {

            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;

            boolean result = config.clearSetting(setting_id);

            if (!result) return ATS_ACTION_ERROR_SETTING; // setting does not exist

            // Generate an event to inform servers that a config setting has changed
            generateEvent(EventType.EVENT_TYPE_CONFIGW, setting_id);


            return ATS_ACTION_SUCCESS; // success
        }

        ////////////////////////////////////////////////
        //  configWriteSetting()
        //  writes a config setting
        ////////////////////////////////////////////////
        @Override
        public int configWriteSetting(int setting_id, String value) throws RemoteException {


            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;


            boolean result = config.writeSetting(setting_id, value);

            if (!result) return ATS_ACTION_ERROR_SETTING; // setting does not exist


            // Generate an event to inform servers that a config setting has changed
            generateEvent(EventType.EVENT_TYPE_CONFIGW, setting_id);


            return ATS_ACTION_SUCCESS; // success

        }


        ////////////////////////////////////////////////
        //  configReadSetting()
        //  reads a config setting
        ////////////////////////////////////////////////
        @Override
        public String configReadSetting(int setting_id) throws RemoteException {

            if (!hasPermission()) return null;

            // Perform the Action

            String setting = config.readSetting(setting_id);

            return setting;
        }

/*
        ////////////////////////////////////////////////
        @Override
        public boolean configIsEventCodeSupported(int internal_eventcode) { // check if this internal eventcode is supported

            // No permission check needed

            codemap.

        }
*/
        ////////////////////////////////////////////////
        @Override
        public int configClearAllMOMap() {

            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;

            codemap.clearAllMO();


            generateEvent(EventType.EVENT_TYPE_CONFIGURATION_REPLACED, EventType.CONFIG_FILE_MOMAP);

            return ATS_ACTION_SUCCESS;

        } // remove all MO Map entries

        ////////////////////////////////////////////////
        @Override
        public int configWriteMOMapping(int internal_eventcode, int external_eventcode) {
            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;

            boolean result = codemap.writeMoEventCode(internal_eventcode, external_eventcode);

            if (!result) return ATS_ACTION_ERROR;


            generateEvent(EventType.EVENT_TYPE_MOREMAPW, internal_eventcode);

            return ATS_ACTION_SUCCESS;
        } // write a single map entry

        ////////////////////////////////////////////////
        @Override
        public int configReadMOMapping(int internal_eventcode) {

            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;


            return codemap.mapMoEventCode(internal_eventcode);
        } // read a current map entry

        ////////////////////////////////////////////////
        @Override
        public int configClearAllMTMap() {
            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;

            codemap.clearAllMT();

            generateEvent(EventType.EVENT_TYPE_CONFIGURATION_REPLACED, EventType.CONFIG_FILE_MTMAP);

            return ATS_ACTION_SUCCESS;

        } // remove all MT Map entries

        ////////////////////////////////////////////////
        @Override
        public int configWriteMTMapping(int external_eventcode, int internal_eventcode) {
            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;

            boolean result = codemap.writeMtEventCode(external_eventcode, internal_eventcode);

            if (!result) return ATS_ACTION_ERROR;

            generateEvent(EventType.EVENT_TYPE_MTREMAPW, external_eventcode);

            return ATS_ACTION_SUCCESS;

        }

        ////////////////////////////////////////////////
        @Override
        public int configReadMTMapping(int external_eventcode) {
            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;

            return codemap.mapMtEventCode(external_eventcode);
        }










        ////////////////////////////////////////////////
        // getVersionString()
        //  gets the ATS version as a String
        ////////////////////////////////////////////////
        @Override
        public String getVersionString() throws RemoteException {

            // No permission check needed

            return BuildConfig.VERSION_NAME;
        }

        ////////////////////////////////////////////////
        // getVersionInt()
        //  gets the ATS version as an integer
        ////////////////////////////////////////////////
        @Override
        public int getVersionInt() throws RemoteException {

            // No permission check needed

            return BuildConfig.VERSION_CODE;
        }



        public int restartService() {

            if (!hasPermission()) return ATS_ACTION_ERROR_PERMISSION;

            // check if we are running and call restart
            // otherwise start it fresh.

            MainService service = MainService.getInitializedService();
            if (service != null) {
                // service is running, we need to restart it (kill the old one first)
                service.power.restartAtsProcess(Power.RESTART_REASON_LOCAL_REQUEST);
            } else {
                // Service is not running, we can just start it
                Power.startService(context, Power.RESTART_REASON_LOCAL_REQUEST);
            }

            return ATS_ACTION_SUCCESS;
        }


    };


}
