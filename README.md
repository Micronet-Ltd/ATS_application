# ATS Service

This repository contains 4 projects:

1 ATS -- Android Telematics Service 

2 VBS -- Vehicle Bus Service (talks to the CAN and J1708 buses and forwards this info to ATS)

3 ResetRB -- Resets the Redbend Client Data (accepts a command to reset from ATS and broadcasts the ACK)

4 testapps -- apps used for testing devices/bugs or functionality


These projects require the correct system signing keys. Rename the a300keys.properties.sample file in the project root directory to a300keys.properties and edit so it contains the correct key information.

Documentation can be found [here](https://micronet1023744.sharepoint.com/RD/Forms/AllItems.aspx?viewpath=%2FRD%2FForms%2FAllItems%2Easpx&id=%2FRD%2FATS).