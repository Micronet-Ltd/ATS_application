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
    private static final int DATABASE_VERSION = 10;


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
        Log.w(TAG,
                "Upgrading database from version " + oldVersion + " to "
                        + newVersion + ", which will destroy all old data");
        db.execSQL("DROP TABLE IF EXISTS " + Queue.TABLE_NAME);

        onCreate(db);
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
