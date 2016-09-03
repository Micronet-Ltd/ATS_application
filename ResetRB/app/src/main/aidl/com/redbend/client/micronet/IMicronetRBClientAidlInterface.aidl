// IMicronetRBClientAidlInterface.aidl
package com.redbend.client.micronet;

// Declare any non-default types here with import statements

interface IMicronetRBClientAidlInterface {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
    //void basicTypes(int anInt, long aLong, boolean aBoolean, float aFloat,
      //      double aDouble, String aString);


    int getNoInstallLock(String lock_name, int expires_seconds); // request a specific lock for a certain time period

    int releaseNoInstallLock(String lock_name); // releases a specific lock


}
