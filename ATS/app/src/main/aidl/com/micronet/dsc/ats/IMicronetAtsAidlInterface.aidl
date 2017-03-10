package com.micronet.dsc.ats;

interface IMicronetAtsAidlInterface {

    // Getting ATS Version and Restarting ATS service

    int getVersionInt(); // gets the version of ATS as an integer
    String getVersionString(); // gets the version of ATS as a string
    int restartService(); // restarts the ATS Service (to make sure all config changes take effect)


    // Configuration Settings

    boolean configIsSettingSupported(in int setting_id); // checks if this setting ID is supported
    int configClearSetting(in int setting_id); // clear a single setting back to the default values
    int configWriteSetting(in int setting_id, in String value); // write new values for the setting
    String configReadSetting(in int setting_id);                // read the current values for the setting


    // EventCode Remapping

    int configClearAllMOMap(); // remove ALL MO Map entries (Clear the ENTIRE MO MAP to defaults)
    int configWriteMOMapping(in int internal_eventcode, in int external_eventcode); // write a single map entry
    int configReadMOMapping(in int internal_eventcode); // read a current map entry

    int configClearAllMTMap(); // remove ALL MT Map entries (Clear the ENTIRE MT MAP to defaults)
    int configWriteMTMapping(in int external_eventcode, in int internal_eventcode); // write a single map entry
    int configReadMTMapping(in int external_eventcode); // read a current map entry

}

