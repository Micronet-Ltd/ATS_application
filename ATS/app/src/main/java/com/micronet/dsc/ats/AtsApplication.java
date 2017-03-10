package com.micronet.dsc.ats;

import android.app.Application;

import java.util.Arrays;

/**
 * Created by dschmidt on 1/26/17.
 */

public class AtsApplication extends Application {

    public static final String TAG = "ATS";

    public AtsApplication() {
        // this method fires only once per application start.
        // getApplicationContext returns null here
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // this method fires once as well as constructor
        // but also application has context here



        Log.i(TAG, "Created: ATS version " + BuildConfig.VERSION_NAME);

        // if they exist we need to move the alternate configuration files to the primary location here
        //  before anything else in the application tries to access them

        copyAlternateConfigFiles();

    }


    ///////////////////////////////////////////////////////////////////
    // copyAlternateConfigFiles()
    //      copy the config files from the alternate location to the primary, and remember if we did this
    public void copyAlternateConfigFiles() {

        int config_files_updated = Config.init();
        int eventcode_files_updated = CodeMap.init();

        // now, if we did something, we should remember this in the state file so that we can
        //  generate a message the next time the ATS service starts (which could be right away if that is how we got here)

        if ((config_files_updated | eventcode_files_updated) != 0) {
            State state = new State(getApplicationContext());
            state.setFlags(State.PRECHANGED_CONFIG_FILES_BF, config_files_updated | eventcode_files_updated);
        }
    }


} // AtsApplication class
