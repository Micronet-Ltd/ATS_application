# ATS Service

This repository contains 4 projects:

1 ResetRB -- Resets the Redbend Client Data (accepts a command to reset from ATS and broadcasts the ACK)

2 ATS (Outdated) -- Android Telematics Service -- Refer to https://github.com/Micronet-Ltd/ATS for ATS on SmartHub/SmarTab and to https://github.com/Micronet-Ltd/ATS_legacy for ATS on A317

3 VBS (Outdated) -- Vehicle Bus Service -- Refer to https://github.com/Micronet-Ltd/VBS for VBS on all types of devices

4 Test Apps -- apps used for testing devices/bugs or functionality

These projects require the correct system signing keys. Different hardware platforms have different keys. Sample files are included for you to copy and edit so they point to the correct keys.

Copy and edit these files in project root directory:

	"a300keys.properties.sample" to "a300keys.properties"

	"obc5keys.properties.sample" to "obc5keys.properties"

The configuration.xml file goes to `/internal_Storage/ATS` for the A-317 and `/storage/sdcard0/ATS` for the OBC5. 

Documentation can be found [here](https://micronet1023744.sharepoint.com/RD/Forms/AllItems.aspx?viewpath=%2FRD%2FForms%2FAllItems%2Easpx&id=%2FRD%2FATS).