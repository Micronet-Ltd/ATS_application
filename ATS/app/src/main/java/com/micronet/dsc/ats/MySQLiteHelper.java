/////////////////////////////////////////////////////////////
// MySQLiteHelper
//  Helper class that is used to open the correct Database and create/upgrade tables if needed
/////////////////////////////////////////////////////////////


package com.micronet.dsc.ats;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;


public class MySQLiteHelper extends SQLiteOpenHelper {

    private static final String TAG = "ATS-SQLiteHelper";

    private static final String DATABASE_NAME = "buffer.db";
    private static final int DATABASE_VERSION = 12;


    public MySQLiteHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        Log.w(TAG,
                "Creating database at version " + DATABASE_VERSION);

        database.execSQL(Queue.SQL_CREATE );

    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        if (oldVersion < 10) {

            Log.w(TAG,
                    "Upgrading database from version " + oldVersion + " to "
                            + newVersion + ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS " + Queue.TABLE_NAME);

            onCreate(db);

        } else { // oldversion >= 10, we can upgrade

            if ((newVersion >= 11) && (oldVersion < 11)) {
                Log.w(TAG,
                        "Upgrading database from version " + oldVersion + " to "
                                + newVersion + ", data will be saved");
                db.execSQL(Queue.SQL_UPDATE_V11);
            }

            if ((newVersion >= 12) && (oldVersion < 12)) {
                Log.w(TAG,
                        "Upgrading database from version " + oldVersion + " to "
                                + newVersion + ", data will be saved");
                db.execSQL(Queue.SQL_UPDATE_V12);
            }

        } // oldVersion is upgradable
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG,
                "Downgrading database from version " + oldVersion + " to "
                        + newVersion + ", which will destroy all old data");
        db.execSQL("DROP TABLE IF EXISTS " + Queue.TABLE_NAME);

        onCreate(db);
    }

}
