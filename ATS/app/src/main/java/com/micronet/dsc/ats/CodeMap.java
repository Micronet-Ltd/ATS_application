////////////////////////////////////////////////////////////////////
// CodeMap
//  Re-Maps event codes from internal to external
//  contains two files that store the mo and mt mappings, respectively.
////////////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import android.content.Context;
import android.content.SharedPreferences;

public class CodeMap {

    private static final String TAG = "ATS-Codemapper";

    private static final String MT_FILENAME = "mteventcodes";
    private static final String MO_FILENAME = "moeventcodes";

    // Paths of the files (used for copying from the alternate to the real location)
    private static final String FILENAME_STANDARD_PATH = "/data/data/" + BuildConfig.APPLICATION_ID + "/shared_prefs";
    private static final String FILENAME_ALTERNATE_PATH = "/internal_Storage/ATS/";



    Context context;
    SharedPreferences mtSharedPref;
    SharedPreferences moSharedPref;

    public CodeMap(Context context) {
        this.context = context;

    }


    /////////////////////////////////////////////////////////////////
    //  open():
    //      opens the config file
    //  Returns: 0 if the standard location was opened
    //           |1 if the alternate location was opened for MO file
    //           |2 if the alternate location was opened for MT file
    /////////////////////////////////////////////////////////////////
    public int open() {

        boolean mo_copied = Config.copyFile(FILENAME_ALTERNATE_PATH, FILENAME_STANDARD_PATH, MO_FILENAME + ".xml");
        boolean mt_copied = Config.copyFile(FILENAME_ALTERNATE_PATH, FILENAME_STANDARD_PATH, MT_FILENAME + ".xml");

        mtSharedPref = context.getSharedPreferences(
                MT_FILENAME, Context.MODE_PRIVATE);
        moSharedPref = context.getSharedPreferences(
                MO_FILENAME, Context.MODE_PRIVATE);

        return (mo_copied  ? 1 : 0) + (mt_copied ? 2 : 0);

    } // open()


    ///////////////////////////////////////////////////
    // clearAll()
    //  deletes ALL mappings and restores factory default
    ///////////////////////////////////////////////////
    public void clearAll() {
        SharedPreferences.Editor moEditor = moSharedPref.edit();
        moEditor.clear().commit();

        SharedPreferences.Editor mtEditor = moSharedPref.edit();
        mtEditor.clear().commit();

    }


    ///////////////////////////////////////////////////
    // mapMtEventCode()
    //  returns an internal event code from the UDP event code
    ///////////////////////////////////////////////////
    public int mapMtEventCode(int external_event_code) {
        int internal = mtSharedPref.getInt(Integer.toString(external_event_code), 0);

        if (internal == 0) return external_event_code; // no map entry
        return internal;
    }


    ///////////////////////////////////////////////////
    // mapMoEventCode()
    //  returns a UDP event code from the internal event code
    ///////////////////////////////////////////////////
    public int mapMoEventCode(int internal_event_code) {
        int external =  moSharedPref.getInt(Integer.toString(internal_event_code), 0);

        if (external == 0) return internal_event_code; // no map entry
        return external;
    }


    ///////////////////////////////////////////////////
    // writeMoEventCode()
    //   writes to the file to store the event code
    //  returns : true if it was written, false if it was not
    ///////////////////////////////////////////////////
    public boolean writeMoEventCode(final int internal_event_code, final int external_event_code) {

        SharedPreferences.Editor editor = moSharedPref.edit();

        editor.putInt(Integer.toString(internal_event_code), external_event_code);
        editor.commit();

        return true; // OK
    }


    ///////////////////////////////////////////////////
    // writeMtEventCode()
    //   writes to the file to store the event code
    //  returns : true if it was written, false if it was not
    ///////////////////////////////////////////////////
    public boolean writeMtEventCode(final int external_event_code , final int internal_event_code) {

        SharedPreferences.Editor editor = mtSharedPref.edit();

        editor.putInt(Integer.toString(external_event_code), internal_event_code);
        editor.commit();

        return true; // OK
    }

    ///////////////////////////////////////////////////
    // writeFakeMoEventCode()
    //   writes a fake ID to the file in order to make sure the file exists
    //      this is needed for GSD updates ?
    ///////////////////////////////////////////////////
    public boolean writeFakeMoEventCode() {

        int internal_event_code = 0;
        int external_event_code = 0;
        SharedPreferences.Editor editor = moSharedPref.edit();

        editor.putInt(Integer.toString(internal_event_code), external_event_code);
        editor.commit();

        return true; // OK
    }


    ///////////////////////////////////////////////////
    // writeFakeMtEventCode()
    //   writes a fake ID to the file in order to make sure the file exists
    //      this is needed for GSD updates ?
    ///////////////////////////////////////////////////
    public boolean writeFakeMtEventCode() {

        int internal_event_code = 0;
        int external_event_code = 0;

        SharedPreferences.Editor editor = mtSharedPref.edit();

        editor.putInt(Integer.toString(external_event_code), internal_event_code);
        editor.commit();

        return true; // OK
    }


} // class CodeMap
